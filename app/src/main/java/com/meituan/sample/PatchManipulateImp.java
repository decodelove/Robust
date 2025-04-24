package com.meituan.sample;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.meituan.robust.Patch;
import com.meituan.robust.PatchManipulate;
import com.meituan.robust.RobustApkHashUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by mivanzhang on 17/2/27.
 * <p>
 * We recommend you rewrite your own PatchManipulate class ,adding your special patch Strategy，in the demo we just load the patch directly
 *
 * <br>
 * Pay attention to the difference of patch's LocalPath and patch's TempPath
 *
 * <br>
 * We recommend LocalPath store the origin patch.jar which may be encrypted,while TempPath is the true runnable jar
 * <br>
 * <br>
 * 我们推荐继承PatchManipulate实现你们App独特的A补丁加载策略，其中setLocalPath设置补丁的原始路径，这个路径存储的补丁是加密过的，setTempPath存储解密之后的补丁，是可以执行的jar文件
 * <br>
 * setTempPath设置的补丁加载完毕即刻删除，如果不需要加密和解密补丁，两者没有啥区别
 */

public class PatchManipulateImp extends PatchManipulate {
    private String TAG = "robust";

    /***
     * connect to the network ,get the latest patches
     * l联网获取最新的补丁
     * @param context
     *
     * @return
     */
    @Override
    protected List<Patch> fetchPatchList(Context context) {
        /*//将app自己的robustApkHash上报给服务端，服务端根据robustApkHash来区分每一次apk build来给app下发补丁
        //apkhash is the unique identifier for  apk,so you cannnot patch wrong apk.
        String robustApkHash = RobustApkHashUtils.readRobustApkHash(context);
        Log.w("robust","robustApkHash :" + robustApkHash);
        //connect to network to get patch list on servers
        //在这里去联网获取补丁列表
        Patch patch = new Patch();
        patch.setName("123");
        //we recommend LocalPath store the origin patch.jar which may be encrypted,while TempPath is the true runnable jar
        //LocalPath是存储原始的补丁文件，这个文件应该是加密过的，TempPath是加密之后的，TempPath下的补丁加载完毕就删除，保证安全性
        //这里面需要设置一些补丁的信息，主要是联网的获取的补丁信息。重要的如MD5，进行原始补丁文件的简单校验，以及补丁存储的位置，这边推荐把补丁的储存位置放置到应用的私有目录下，保证安全性
        String resPath = context.getExternalFilesDir("").getAbsolutePath();
        String patchFilePath = resPath+ File.separator+"robust"+File.separator + "patch";
        Log.w("robust","patchFilePath :" + patchFilePath);
        patch.setLocalPath(patchFilePath);

        //setPatchesInfoImplClassFullName 设置项各个App可以独立定制，需要确保的是setPatchesInfoImplClassFullName设置的包名是和xml配置项patchPackname保持一致，而且类名必须是：PatchesInfoImpl
        //请注意这里的设置
        patch.setPatchesInfoImplClassFullName("com.meituan.robust.patch.PatchesInfoImpl");
        List  patches = new ArrayList<Patch>();
        patches.add(patch);
        return patches;*/
        Log.i(TAG, "fetchPatchList start");
        //将app自己的robustApkHash上报给服务端，服务端根据robustApkHash来区分每一次apk build来给app下发补丁
        //apkhash is the unique identifier for  apk,so you cannnot patch wrong apk.
        String robustApkHash = RobustApkHashUtils.readRobustApkHash(context);
        Log.i(TAG, "robustApkHash :" + robustApkHash);
        //connect to network to get patch list on servers

        String resPath = context.getExternalFilesDir("").getAbsolutePath();
        String patchFilePath = resPath + File.separator + "robust";
        Log.i(TAG, "patchFilePath :" + patchFilePath);

        File patchFileDir = new File(patchFilePath);
        if (!patchFileDir.exists() || !patchFileDir.isDirectory()) {
            patchFileDir.mkdirs();
        }

        //模拟遍历当前应用的私有目录下是否有补丁文件
        File[] patchFiles = patchFileDir.listFiles();
        if (patchFiles == null || patchFiles.length == 0) {
            Log.i(TAG, "patchFilePath not exist :" + patchFilePath);
            return null;
        }
        List patches = new ArrayList<Patch>();
        for (File file : patchFiles) {
            String patchFileAbsolutePath = file.getAbsolutePath();
            Log.i(TAG,"patchFilePath file :" + patchFileAbsolutePath);

            String patchName = file.getName();
            if (patchName.endsWith(".jar")) {
                File patchFile = new File(patchFileAbsolutePath);
                if (Build.VERSION.SDK_INT >= 34) {
                    patchFile.setReadOnly();
                }
                String fileMD5 = getFileMD5(patchFile);
                // 修复这里，不要修改原始的patchFilePath变量，而是创建一个新的变量
                String currentPatchPath = patchFile.getParent()+ File.separator + patchName.replace(".jar", "");
                //在这里去联网获取补丁列表
                Patch patch = new Patch();
                patch.setName(fileMD5);
                //we recommend LocalPath store the origin patch.jar which may be encrypted,while TempPath is the true runnable jar
                //LocalPath是存储原始的补丁文件，这个文件应该是加密过的，TempPath是加密之后的，TempPath下的补丁加载完毕就删除，保证安全性
                //这里面需要设置一些补丁的信息，主要是联网的获取的补丁信息。重要的如MD5，进行原始补丁文件的简单校验，以及补丁存储的位置，这边推荐把补丁的储存位置放置到应用的私有目录下，保证安全性
                Log.i(TAG, "设置当前的patchPath :" + currentPatchPath);
                patch.setLocalPath(currentPatchPath);
                //setPatchesInfoImplClassFullName 设置项各个App可以独立定制，需要确保的是setPatchesInfoImplClassFullName设置的包名是和xml配置项patchPackname保持一致，而且类名必须是：PatchesInfoImpl
                //请注意这里的设置
                patch.setPatchesInfoImplClassFullName("com.meituan.robust.patch.PatchesInfoImpl");
                patches.add(patch);
            }
        }
        return patches;
    }

    /**
     * @param context
     * @param patch
     * @return you can verify your patches here
     */
    @Override
    protected boolean verifyPatch(Context context, Patch patch) {
        //do your verification, put the real patch to patch
//        String resPath = context.getExternalFilesDir("").getAbsolutePath();
//        String patchFilePath = resPath + File.separator + "robust" + File.separator + "patch";
//        Log.i(TAG, "patchFilePath :" + patchFilePath);
        //放到app的私有目录
//        String tempPath = context.getCacheDir() + File.separator + "robust" + File.separator + "patch";
//        Log.i(TAG, "tempPath :" + tempPath);
        patch.setTempPath(patch.getLocalPath());
        //in the sample we just copy the file
        /*try {
            copy(patch.getLocalPath(), patch.getTempPath());
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("copy source patch to local patch error, no patch execute in path " + patch.getTempPath());
        }*/

        return true;
    }

    public void copy(String srcPath, String dstPath) throws IOException {
        File src = new File(srcPath);
        if (!src.exists()) {
            throw new RuntimeException("source patch does not exist ");
        }
        File dst = new File(dstPath);
        if (!dst.getParentFile().exists()) {
            dst.getParentFile().mkdirs();
        }
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * @param patch
     * @return you may download your patches here, you can check whether patch is in the phone
     */
    @Override
    protected boolean ensurePatchExist(Patch patch) {
        return true;
    }

    private String getFileMD5(File file) {
        String md5 = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            fis.close();
            byte[] md5Bytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : md5Bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0'); // 确保每个字节以两位十六进制表示
                hexString.append(hex);
            }
            md5 = hexString.toString();
            return md5; // 返回 MD5 字符串
        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
            return md5;
        }
    }
}
