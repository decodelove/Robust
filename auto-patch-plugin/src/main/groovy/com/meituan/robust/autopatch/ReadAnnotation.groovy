package com.meituan.robust.autopatch

import com.meituan.robust.Constants
import com.meituan.robust.patch.annotaion.Add
import com.meituan.robust.patch.annotaion.Modify
import com.meituan.robust.utils.JavaUtils
import javassist.CannotCompileException
import javassist.CtClass
import javassist.CtMethod
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import org.codehaus.groovy.GroovyException
import org.gradle.api.logging.Logger
import robust.gradle.plugin.AutoPatchTransform

class ReadAnnotation {
    static Logger logger

    public static void readAnnotation(List<CtClass> box, Logger log) {
        logger = log;
        Set patchMethodSignureSet = new HashSet<String>();
        
        // 确保 box 不为空
        if (box == null || box.isEmpty()) {
            logger.error("No classes to scan for annotations")
            return
        }
        
        // 初始化注解类
        synchronized (AutoPatchTransform.class) {
            try {
                if (Constants.ModifyAnnotationClass == null) {
                    logger.info("Initializing ModifyAnnotationClass from ${Constants.MODIFY_ANNOTATION}")
                    Constants.ModifyAnnotationClass = box.get(0).getClassPool().get(Constants.MODIFY_ANNOTATION).toClass();
                    logger.info("ModifyAnnotationClass initialized: ${Constants.ModifyAnnotationClass}")
                }
                if (Constants.AddAnnotationClass == null) {
                    logger.info("Initializing AddAnnotationClass from ${Constants.ADD_ANNOTATION}")
                    Constants.AddAnnotationClass = box.get(0).getClassPool().get(Constants.ADD_ANNOTATION).toClass();
                    logger.info("AddAnnotationClass initialized: ${Constants.AddAnnotationClass}")
                }
            } catch (Exception e) {
                logger.error("Failed to initialize annotation classes: ${e.message}")
                e.printStackTrace()
            }
        }
        for (ctclass in box) {
            try {
                logger.info("start read annotation for class " + ctclass.name)
                boolean isNewlyAddClass = scanClassForAddClassAnnotation(ctclass);
                logger.info("scanClassForAddClassAnnotation：" + isNewlyAddClass)
                //newly add class donnot need scann for modify
                if (!isNewlyAddClass) {
                    def modifyMethod = scanClassForModifyMethod(ctclass)
                    logger.info("scanClassForModifyMethod：" + modifyMethod +" modifyMethod.size()" + modifyMethod.size())
                    if (modifyMethod.size() > 0) {
                        patchMethodSignureSet.addAll(modifyMethod);
                    }
                    //先注掉这个，因为这里会报错
                    scanClassForAddMethodAnnotation(ctclass);
                }
            } catch (NullPointerException e) {
                logger.warn("something wrong when readAnnotation, " + e.getMessage() + " cannot find class name " + ctclass.name)
                e.printStackTrace();
            } catch (RuntimeException e) {
                logger.warn("something wrong when readAnnotation, " + e.getMessage() + " cannot find class name " + ctclass.name)
                e.printStackTrace();
            }
        }

        /*// 使用并行流处理，提高扫描效率
        box.parallelStream().forEach { ctclass ->
            try {
                boolean isNewlyAddClass = scanClassForAddClassAnnotation(ctclass);
                if (!isNewlyAddClass) {
                    // 添加同步机制，确保线程安全
                    synchronized (patchMethodSignureSet) {
                        patchMethodSignureSet.addAll(scanClassForModifyMethod(ctclass));
                    }
                    scanClassForAddMethodAnnotation(ctclass);
                }
            } catch (NullPointerException e) {
                logger.warn("something wrong when readAnnotation, " + e.getMessage() + " cannot find class name " + ctclass.name)
                e.printStackTrace();
            } catch (RuntimeException e) {
                logger.warn("something wrong when readAnnotation, " + e.getMessage() + " cannot find class name " + ctclass.name)
                e.printStackTrace();
            }
        }*/

        logger.quiet "new add methods  list is $Config.newlyAddedMethodSet.toList() "

        logger.quiet "new add classes list is  $Config.newlyAddedClassNameList.size() "

        patchMethodSignureSet.iterator().each {
            logger.quiet "patchMethodSignatureSet patch method signature is $it"
        }
        //logger.quiet " patchMethodSignatureSet is printed below $patchMethodSignureSet.toString()"
        Config.patchMethodSignatureSet.addAll(patchMethodSignureSet);
    }
    public static boolean scanClassForAddClassAnnotation(CtClass ctclass) {

        Add addClassAnootation = ctclass.getAnnotation(Constants.AddAnnotationClass) as Add;
        if (addClassAnootation != null && !Config.newlyAddedClassNameList.contains(ctclass.name)) {
            Config.newlyAddedClassNameList.add(ctclass.name);
            return true;
        }

        return false;
    }

    public static void scanClassForAddMethodAnnotation(CtClass ctclass) {
        logger.info("scanClassForAddMethodAnnotation " + ctclass.name);
        ctclass.defrost();
        ctclass.declaredMethods.each { method ->
            def getAnnotation = method.getAnnotation(Constants.AddAnnotationClass)
            if (null != getAnnotation) {
                Config.newlyAddedMethodSet.add(method.longName)
            }
        }
    }

    public static Set scanClassForModifyMethod(CtClass ctclass) {
        Set patchMethodSignureSet = new HashSet<String>();
        boolean isAllMethodsPatch = true;
        
        // 确保类已解冻，可以修改
        ctclass.defrost();
        
        // 添加日志，查看类是否有方法
        logger.info("Scanning class ${ctclass.name} with ${ctclass.declaredMethods.size()} methods")
        
        // 修改注解扫描方式，使用更可靠的方法
        ctclass.declaredMethods.each { method ->
            try {
                // 直接获取注解对象
                Object annotation = method.getAnnotation(Constants.ModifyAnnotationClass)
                if (annotation != null) {
                    logger.info("Found @Modify annotation on method: ${method.longName}")
                    isAllMethodsPatch = false;
                    addPatchMethodAndModifiedClass(patchMethodSignureSet, method);
                }
            } catch (Exception e) {
                logger.warn("Error checking annotation for method ${method.longName}: ${e.message}")
            }
        }
        
        //do with lamda expression
        ctclass.declaredMethods.findAll {
            return Config.methodMap.get(it.longName) != null;
        }.each { method ->
            method.instrument(new ExprEditor() {
                @Override
                public void edit(MethodCall m) throws CannotCompileException {
                    try {
                        if (Constants.LAMBDA_MODIFY.equals(m.method.declaringClass.name)) {
                            isAllMethodsPatch = false;
                            addPatchMethodAndModifiedClass(patchMethodSignureSet, method);
                        }
                    } catch (javassist.NotFoundException e) {
                        e.printStackTrace()
                        logger.warn("  cannot find class  " + method.longName + " line number " + m.lineNumber + " this class may never used ,please remove this class");
                    }
                }
            });
        }
        
        // 检查类级别的注解
        try {
            Modify classModifyAnootation = ctclass.getAnnotation(Constants.ModifyAnnotationClass) as Modify;
            if (classModifyAnootation != null) {
                logger.info("Found @Modify annotation on class: ${ctclass.name}")
                if (isAllMethodsPatch) {
                    if (classModifyAnootation.value().length() < 1) {
                        ctclass.declaredMethods.findAll {
                            return Config.methodMap.get(it.longName) != null;
                        }.each { method ->
                            addPatchMethodAndModifiedClass(patchMethodSignureSet, method);
                        }
                    } else {
                        ctclass.getClassPool().get(classModifyAnootation.value()).declaredMethods.findAll {
                            return Config.methodMap.get(it.longName) != null;
                        }.each { method ->
                            addPatchMethodAndModifiedClass(patchMethodSignureSet, method);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error checking class annotation for ${ctclass.name}: ${e.message}")
        }
        
        // 输出扫描结果
        logger.info("Found ${patchMethodSignureSet.size()} methods to patch in class ${ctclass.name}")
        
        return patchMethodSignureSet;
    }

    public static Set addPatchMethodAndModifiedClass(Set patchMethodSignureSet, CtMethod method) {
        if (Config.methodMap.get(method.longName) == null) {
            println("addPatchMethodAndModifiedClass pint methodmap ");
            JavaUtils.printMap(Config.methodMap);
            throw new GroovyException("patch method " + method.longName + " haven't insert code by Robust.Cannot patch this method, method.signature  " + method.signature + "  ");
        }
        Modify methodModifyAnootation = method.getAnnotation(Constants.ModifyAnnotationClass) as Modify;
        Modify classModifyAnootation = method.declaringClass.getAnnotation(Constants.ModifyAnnotationClass) as Modify;
        if ((methodModifyAnootation == null || methodModifyAnootation.value().length() < 1)) {
            //no annotation value
            patchMethodSignureSet.add(method.longName);
            if (!Config.modifiedClassNameList.contains(method.declaringClass.name))
                Config.modifiedClassNameList.add(method.declaringClass.name);
        } else {
            //use value in annotation
            patchMethodSignureSet.add(methodModifyAnootation.value());
        }
        if (classModifyAnootation == null || classModifyAnootation.value().length() < 1) {
            if (!Config.modifiedClassNameList.contains(method.declaringClass.name)) {
                Config.modifiedClassNameList.add(method.declaringClass.name);
            }
        } else {
            if (!Config.modifiedClassNameList.contains(classModifyAnootation.value())) {
                Config.modifiedClassNameList.add(classModifyAnootation.value());
            }
        }
        return patchMethodSignureSet;
    }
}
