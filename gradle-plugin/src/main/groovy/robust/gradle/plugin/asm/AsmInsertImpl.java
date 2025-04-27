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
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipOutputStream;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.MethodInfo;
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

    // 统计信息相关字段
    private int totalClassCount = 0;                // 总类数量
    private int instrumentedClassCount = 0;         // 插桩类数量
    private int totalMethodCount = 0;               // 总方法数量
    private int instrumentedMethodCount = 0;        // 插桩方法数量
    private long originalSize = 0;                  // 原始包体大小
    private long instrumentedSize = 0;              // 插桩后包体大小
    private long methodSizeIncrease = 0;           // 插桩方法累计增加的大小
    private Map<String, String> classAnalysisMap = new HashMap<>();  // 类分析结果
    private Map<String, Integer> packageMethodCountMap = new HashMap<>(); // 包级别方法统计
    private Map<String, Integer> classMethodCountMap = new HashMap<>();  // 每个类的插桩方法数量统计


    public AsmInsertImpl(List<String> hotfixPackageList, List<String> hotfixMethodList, List<String> exceptPackageList, List<String> exceptMethodList, boolean isHotfixMethodLevel, boolean isExceptMethodLevel, boolean isForceInsertLambda) {
        super(hotfixPackageList, hotfixMethodList, exceptPackageList, exceptMethodList, isHotfixMethodLevel, isExceptMethodLevel, isForceInsertLambda);
    }

    @Override
    protected void insertCode(List<CtClass> box, File jarFile) throws IOException, CannotCompileException {
        // 记录原始文件大小
        originalSize = jarFile.length();
        totalClassCount = box.size();

        ZipOutputStream outStream = new JarOutputStream(new FileOutputStream(jarFile));
        //get every class in the box ,ready to insert code
        for (CtClass ctClass : box) {
            //change modifier to public ,so all the class in the apk will be public ,you will be able to access it in the patch
            // 将类修改为 public，确保补丁可以访问
            ctClass.setModifiers(AccessFlag.setPublic(ctClass.getModifiers()));
            String className = ctClass.getName();
            int methodCount = ctClass.getDeclaredMethods().length;
            totalMethodCount += methodCount;

            // 统计包级别的方法数
            String packageName = getPackageName(className);
            packageMethodCountMap.put(packageName, packageMethodCountMap.getOrDefault(packageName, 0) + methodCount);

            // 判断当前类是否需要插桩isNeedInsertClass(ctClass.getName()),另外接口不需要插桩以及没有方法的类也不需要插桩
            if (isNeedInsertClass(className) && !(ctClass.isInterface() || methodCount < 1)) {
                //only insert code into specific classes
                String formattedClassName = className.replaceAll("\\.", "/");
                // 记录插桩前的字节码大小
                byte[] originalCode = ctClass.toBytecode();
                int originalClassSize = originalCode.length;

                // 对需要插桩的类进行字节码转换
                byte[] transformCode = transformCode(originalCode, formattedClassName);
                int transformedClassSize = transformCode.length;

                // 累计插桩方法增加的大小
                int classSizeIncrease = transformedClassSize - originalClassSize;
                methodSizeIncrease += classSizeIncrease;

                // 记录插桩原因和大小变化
                String reason = analyzeInstrumentationReason(ctClass);
                // 初始化该类的插桩方法计数 - 使用与更新时相同的类名格式
                // 不需要在这里初始化计数，因为在visitMethod方法中会更新计数
                classAnalysisMap.put(className, reason + " (大小变化: " + classSizeIncrease + " 字节)");

                zipFile(transformCode, outStream, className.replaceAll("\\.", "/") + ".class");
                instrumentedClassCount++;
            } else {
                // 不需要插桩的类直接写入
                zipFile(ctClass.toBytecode(), outStream, className.replaceAll("\\.", "/") + ".class");

                // 记录未插桩原因
                String reason = analyzeNonInstrumentationReason(ctClass);
                classAnalysisMap.put(className, reason);
            }
            ctClass.defrost();
        }
        outStream.close();

        // 记录插桩后文件大小
        instrumentedSize = jarFile.length();
        instrumentedMethodCount = methodMap.size();

        // 生成统计报告
        generateReport(jarFile.getParentFile());
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
            
            // 更新该类的插桩方法计数
            // 将斜杠格式的类名转换为点格式，与初始化时保持一致
            String classNameDot = className.replace('/', '.');
            classMethodCountMap.put(classNameDot, classMethodCountMap.getOrDefault(classNameDot, 0) + 1);
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

    /**
     * 获取类的包名
     *
     * @param className 完整类名
     * @return 包名
     */
    private String getPackageName(String className) {
        int lastDotIndex = className.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return className.substring(0, lastDotIndex);
        }
        return "";
    }

    /**
     * 分析类需要插桩的原因
     *
     * @param ctClass 类对象
     * @return 插桩原因描述
     */
    private String analyzeInstrumentationReason(CtClass ctClass) {
        try {
            String className = ctClass.getName();
            // 检查是否在热修复包列表中
            for (String packageName : hotfixPackageList) {
                if (className.startsWith(packageName)) {
                    return "类在热修复包列表中: " + packageName;
                }
            }

            if (isHotfixMethodLevel && hotfixMethodList != null && !hotfixMethodList.isEmpty()) {
                for (String methodPattern : hotfixMethodList) {
                    for (CtMethod method : ctClass.getDeclaredMethods()) {
                        MethodInfo methodInfo = method.getMethodInfo();
                        if (methodInfo.getName().matches(methodPattern)) {
                            return "类包含需要热修复的方法: " + methodPattern;
                        }
                    }
                }
            }

            return "类符合默认插桩条件";
        } catch (Exception e) {
            return "分析插桩原因时出错: " + e.getMessage();
        }
    }

    /**
     * 分析类不需要插桩的原因
     *
     * @param ctClass 类对象
     * @return 不插桩原因描述
     */
    private String analyzeNonInstrumentationReason(CtClass ctClass) {
        try {
            String className = ctClass.getName();
            
            // 检查是否是R文件（Android资源文件）
            if (className.matches(".*\\.R(\\$[a-z]+)?$")) {
                return "类是R文件，不需要插桩";
            }

            // 检查是否在排除包列表中
            for (String packageName : exceptPackageList) {
                if (className.startsWith(packageName)) {
                    return "类在排除包列表中: " + packageName;
                }
            }

            // 检查是否是接口
            if (ctClass.isInterface()) {
                return "类是接口，不需要插桩";
            }

            // 检查是否没有方法
            if (ctClass.getDeclaredMethods().length < 1) {
                return "类没有声明方法，不需要插桩";
            }

            // 检查是否不在热修复包列表中
            boolean inHotfixPackage = false;
            for (String packageName : hotfixPackageList) {
                if (className.startsWith(packageName)) {
                    inHotfixPackage = true;
                    break;
                }
            }

            if (!inHotfixPackage) {
                return "类不在热修复包列表中";
            }

            return "类不符合插桩条件";
        } catch (Exception e) {
            return "分析非插桩原因时出错: " + e.getMessage();
        }
    }

    /**
     * 格式化字节大小为可读形式
     * 
     * @param bytes 字节数
     * @return 格式化后的字符串
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " 字节";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        }
    }

    /**
     * 生成插桩统计报告
     *
     * @param outputDir 输出目录
     */
    private void generateReport(File outputDir) {
        try {
            // 查找app/robust目录作为报告输出位置
            File appRobustDir = new File(outputDir.getAbsolutePath().replaceAll("\\/gradle-plugin.*", "/app/robust"));
            if (!appRobustDir.exists()) {
                appRobustDir.mkdirs();
            }
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String timestamp = sdf.format(new Date());

            // 使用累计的方法大小增加值
            long sizeIncrease = methodSizeIncrease;
            
            // 统计手动排除的类数量和R文件数量
            int manuallyExcludedClassCount = 0;
            int rFileCount = 0;
            Map<String, Integer> excludedPackageStats = new HashMap<>(); // 记录每个排除包中排除的类数量
            
            for (Map.Entry<String, String> entry : classAnalysisMap.entrySet()) {
                String reason = entry.getValue();
                if (reason.contains("类是R文件")) {
                    rFileCount++;
                } else if (reason.contains("类在排除包列表中")) {
                    manuallyExcludedClassCount++;
                    
                    // 提取排除的包名
                    int startIndex = reason.indexOf(": ") + 2;
                    String excludedPackage = reason.substring(startIndex).split(" ")[0];
                    excludedPackageStats.put(excludedPackage, excludedPackageStats.getOrDefault(excludedPackage, 0) + 1);
                }
            }
            
            // 统计在热修复范围内的类数量
            int inHotfixRangeClassCount = 0;
            for (String className : classAnalysisMap.keySet()) {
                boolean inRange = false;
                for (String packageName : hotfixPackageList) {
                    if (className.startsWith(packageName)) {
                        inRange = true;
                        break;
                    }
                }
                if (inRange) {
                    inHotfixRangeClassCount++;
                }
            }

            StringBuilder report = new StringBuilder();
            report.append("# Robust 插桩统计报告\n\n");
            report.append("生成时间: ").append(timestamp).append("\n\n");

            // 基本统计信息
            report.append("## 基本统计\n\n");
            report.append("- 热修复范围内类数量: ").append(inHotfixRangeClassCount).append("\n");
            report.append("- 手动排除类数量: ").append(manuallyExcludedClassCount).append("\n");
            report.append("- R文件数量: ").append(rFileCount).append("\n");
            report.append("- 插桩类数量: ").append(instrumentedClassCount).append("\n");
            report.append("- 插桩方法数量: ").append(instrumentedMethodCount).append("\n");
            report.append("- 插桩增加大小: ").append(formatFileSize(sizeIncrease)).append(" (仅统计插桩方法累计增加的大小)\n");
            report.append("- 插桩后包体大小: ").append(formatFileSize(instrumentedSize)).append("\n");
            report.append("\n");
            
            // 排除包统计
            if (!excludedPackageStats.isEmpty()) {
                report.append("## 排除包统计\n\n");
                for (Map.Entry<String, Integer> entry : excludedPackageStats.entrySet()) {
                    report.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" 类\n");
                }
                report.append("\n");
            }

            // 包级别统计 - 只显示热修复范围内的包
            report.append("## 包级别方法统计（热修复范围内）\n\n");
            for (Map.Entry<String, Integer> entry : packageMethodCountMap.entrySet()) {
                boolean inHotfixRange = false;
                for (String packageName : hotfixPackageList) {
                    if (entry.getKey().startsWith(packageName)) {
                        inHotfixRange = true;
                        break;
                    }
                }
                
                if (inHotfixRange) {
                    report.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" 方法\n");
                }
            }
            report.append("\n");

            // 类分析结果 - 只显示热修复范围内的类，且不包含R文件
            report.append("## 类分析结果（热修复范围内）\n\n");
            for (Map.Entry<String, String> entry : classAnalysisMap.entrySet()) {
                String className = entry.getKey();
                String analysisResult = entry.getValue();
                
                // 跳过R文件
                if (analysisResult.contains("类是R文件")) {
                    continue;
                }
                
                boolean inHotfixRange = false;
                for (String packageName : hotfixPackageList) {
                    if (className.startsWith(packageName)) {
                        inHotfixRange = true;
                        break;
                    }
                }
                
                if (inHotfixRange) {
                    // 获取该类的插桩方法数量
                    int methodCount = classMethodCountMap.getOrDefault(className, 0);
                    report.append("- ").append(className).append(": ").append(analysisResult);
                    // 只有当类被插桩时才显示插桩方法数量
                    if (methodCount > 0) {
                        report.append(" [插桩方法数: ").append(methodCount).append("]");
                    }
                    report.append("\n");
                }
            }

            // 写入文件
            File reportFile = new File(appRobustDir, "robust_instrumentation_report.md");
            try (FileWriter writer = new FileWriter(reportFile)) {
                writer.write(report.toString());
            }

            System.out.println("插桩统计报告已生成: " + reportFile.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("生成插桩报告失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

}

