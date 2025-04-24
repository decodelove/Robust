package com.meituan.sample;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.meituan.robust.PatchExecutor;

/**
 * @author gaor
 * @date 2025/4/23 18:45
 * @description application
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        Context context = this.getApplicationContext();
        if (context != null) {
            Log.i("App", "Application context is not null");
            // Initialize Robust or any other libraries here
            // For example: Robust.getInstance().init(context);
            PatchManipulateImp patchManipulate = new PatchManipulateImp();
            RobustCallBackSample robustCallBack = new RobustCallBackSample();
            new PatchExecutor(getApplicationContext(), patchManipulate, robustCallBack).start();
        }
    }
}
