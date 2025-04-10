package robust.gradle.plugin.asm;

import com.meituan.robust.ChangeQuickRedirect;
import com.meituan.robust.Constants;

import org.gradle.api.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipOutputStream;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.bytecode.AccessFlag;
import robust.gradle.plugin.InsertcodeStrategy;


/**
 * Created by zhangmeng on 2017/5/10.
 * <p>
 * insert code using asm
 */

public class AsmInsertImpl extends InsertcodeStrategy {

    static Logger logger;
    // 添加这两个常量来替代 AsmUtils 中的常量
    private static final String CLASS_INITIALIZER = "<clinit>";
    private static final String CONSTRUCTOR = "<init>";


    public AsmInsertImpl(List<String> hotfixPackageList, List<String> hotfixMethodList, List<String> exceptPackageList, List<String> exceptMethodList, boolean isHotfixMethodLevel, boolean isExceptMethodLevel, boolean isForceInsertLambda) {
        super(hotfixPackageList, hotfixMethodList, exceptPackageList, exceptMethodList, isHotfixMethodLevel, isExceptMethodLevel, isForceInsertLambda);
    }

    @Override
    protected void insertCode(List<CtClass> box, File jarFile) throws IOException, CannotCompileException {
        ZipOutputStream outStream = new JarOutputStream(new FileOutputStream(jarFile));
        //get every class in the box ,ready to insert code
        for (CtClass ctClass : box) {
            //change modifier to public ,so all the class in the apk will be public ,you will be able to access it in the patch
            // 将类修改为 public，确保补丁可以访问
            ctClass.setModifiers(AccessFlag.setPublic(ctClass.getModifiers()));
            if (isNeedInsertClass(ctClass.getName()) && !(ctClass.isInterface() || ctClass.getDeclaredMethods().length < 1)) {
                //only insert code into specific classes
                String className = ctClass.getName().replaceAll("\\.", "/");
                // 对需要插桩的类进行字节码转换
                byte[] transformCode = transformCode(ctClass.toBytecode(), className);
                zipFile(transformCode, outStream, ctClass.getName().replaceAll("\\.", "/") + ".class");
            } else {
                // 不需要插桩的类直接写入
                zipFile(ctClass.toBytecode(), outStream, ctClass.getName().replaceAll("\\.", "/") + ".class");
            }
            ctClass.defrost();
        }
        outStream.close();
    }

    private class InsertMethodBodyAdapter extends ClassVisitor implements Opcodes {

        public InsertMethodBodyAdapter() {
            super(Opcodes.ASM5);
        }

        ClassWriter classWriter;
        private String className;
        //this maybe change in the future
        private Map<String, Boolean> methodInstructionTypeMap;

        public InsertMethodBodyAdapter(ClassWriter cw, String className, Map<String, Boolean> methodInstructionTypeMap) {
            super(Opcodes.ASM5, cw);
            this.classWriter = cw;
            this.className = className;
            this.methodInstructionTypeMap = methodInstructionTypeMap;
            //insert the field
            // 添加 ChangeQuickRedirect 静态字段
            classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, Constants.INSERT_FIELD_NAME, Type.getDescriptor(ChangeQuickRedirect.class), null, null);
        }


        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            // 将 protected 方法改为 public
            if (isProtect(access)) {
                access = setPublic(access);
            }
            MethodVisitor mv = super.visitMethod(access, name,
                    desc, signature, exceptions);
    
            // 判断是否需要插桩
            if (!isQualifiedMethod(access, name, desc, methodInstructionTypeMap)) {
                return mv;
            }
    
            // 记录方法信息并生成唯一ID
            StringBuilder parameters = new StringBuilder();
            Type[] types = Type.getArgumentTypes(desc);
            for (Type type : types) {
                parameters.append(type.getClassName()).append(",");
            }
            //remove the last ","
            if (parameters.length() > 0 && parameters.charAt(parameters.length() - 1) == ',') {
                parameters.deleteCharAt(parameters.length() - 1);
            }
            
            String methodSignature = className.replace('/', '.') + "." + name + "(" + parameters.toString() + ")";
            System.out.println("insert code into " + methodSignature);
            
            // 使用修改后的MD5生成方法
            String methodId = getMD5Hex(methodSignature);
            System.out.println("methodId:" + methodId + " (MD5: " + methodId + ")");
            
            //record method number
            methodMap.put(methodSignature, methodId);
            return new MethodBodyInsertor(mv, className, desc, isStatic(access), methodId, name, access);
        }

        private boolean isProtect(int access) {
            return (access & Opcodes.ACC_PROTECTED) != 0;
        }

        private int setPublic(int access) {
            return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        }

        private boolean isQualifiedMethod(int access, String name, String desc, Map<String, Boolean> c) {
            //类初始化函数和构造函数过滤
            if (CLASS_INITIALIZER.equals(name) || CONSTRUCTOR.equals(name)) {
                return false;
            }
            //@warn 这部分代码请重点review一下，判断条件写错会要命
            //这部分代码请重点review一下，判断条件写错会要命
            // synthetic 方法暂时不aop 比如AsyncTask 会生成一些同名 synthetic方法,对synthetic 以及private的方法也插入的代码，主要是针对lambda表达式
            if (!isForceInsertLambda && ((access & Opcodes.ACC_SYNTHETIC) != 0) && ((access & Opcodes.ACC_PRIVATE) == 0)) {
                return false;
            }
            if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                return false;
            }
            if ((access & Opcodes.ACC_NATIVE) != 0) {
                return false;
            }
            if ((access & Opcodes.ACC_INTERFACE) != 0) {
                return false;
            }

            if ((access & Opcodes.ACC_DEPRECATED) != 0) {
                return false;
            }

            //方法过滤
            if (isExceptMethodLevel && exceptMethodList != null) {
                for (String item : exceptMethodList) {
                    if (name.matches(item)) {
                        return false;
                    }
                }
            }

            if (isHotfixMethodLevel && hotfixMethodList != null) {
                for (String item : hotfixMethodList) {
                    if (name.matches(item)) {
                        return true;
                    }
                }
            }

            boolean isMethodInvoke = methodInstructionTypeMap.getOrDefault(name + desc, false);
            //遍历指令类型，
            if (!isMethodInvoke) {
                return false;
            }

            return !isHotfixMethodLevel;

        }

        class MethodBodyInsertor extends GeneratorAdapter implements Opcodes {
            private String className;
            private Type[] argsType;
            private Type returnType;
            List<Type> paramsTypeClass = new ArrayList();
            boolean isStatic;
            //目前methodid是int类型的，未来可能会修改为String类型的，这边进行了一次强转
            String methodId;

            public MethodBodyInsertor(MethodVisitor mv, String className, String desc, boolean isStatic, String methodId, String name, int access) {
                super(Opcodes.ASM5, mv, access, name, desc);
                this.className = className;
                this.returnType = Type.getReturnType(desc);
                Type[] argsType = Type.getArgumentTypes(desc);
                for (Type type : argsType) {
                    paramsTypeClass.add(type);
                }
                this.isStatic = isStatic;
                this.methodId = methodId;
            }


            @Override
            public void visitCode() {
                //insert code here
                RobustAsmUtils.createInsertCode(this, className, paramsTypeClass, returnType, isStatic, methodId);
            }

        }

        private boolean isStatic(int access) {
            return (access & Opcodes.ACC_STATIC) != 0;
        }

    }

    public byte[] transformCode(byte[] b1, String className) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassReader cr = new ClassReader(b1);
        ClassNode classNode = new ClassNode();

        // 创建方法指令类型映射
        Map<String, Boolean> methodInstructionTypeMap = new HashMap<>();
        cr.accept(classNode, 0);

        final List<MethodNode> methods = classNode.methods;
        // 遍历方法,检查是否包含方法调用指令
        for (MethodNode m : methods) {
            InsnList inList = m.instructions;
            boolean isMethodInvoke = false;
            for (int i = 0; i < inList.size(); i++) {
                if (inList.get(i).getType() == AbstractInsnNode.METHOD_INSN) {
                    isMethodInvoke = true;
                }
            }
            methodInstructionTypeMap.put(m.name + m.desc, isMethodInvoke);
        }
        // 使用 InsertMethodBodyAdapter 进行代码插入
        InsertMethodBodyAdapter insertMethodBodyAdapter = new InsertMethodBodyAdapter(cw, className, methodInstructionTypeMap);
        cr.accept(insertMethodBodyAdapter, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * 使用MD5生成方法ID
     *
     * @param methodSignature 方法签名
     * @return 基于MD5的方法ID（取MD5的前8位转为int）
     */
    private String getMD5Hex(String methodSignature) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(methodSignature.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            return "MD5计算失败";
        }
    }


}

