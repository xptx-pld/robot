package com.qihancloud.frontnavigation;

import android.app.Application;
import android.util.Log;

/**
 * 进程入口。
 *
 * 2019 版 demo 在静态块里 {@code System.loadLibrary("MapHelper")}，一旦 .so 缺失
 * （例如在 x86 模拟器或 64 位设备上运行）整个进程会直接崩溃，且异常发生在
 * Application 的类初始化阶段，极难定位。还原版改为容错加载并把结果暴露成常量，
 * 地图渲染层会据此决定走 JNI 还是纯 Java 实现。
 */
public class App extends Application {

    private static final String TAG = "App";
    private static Boolean nativeMapHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        nativeMapHelper = tryLoadMapHelper();
        Log.i(TAG, "libMapHelper loaded = " + nativeMapHelper);
    }

    private static synchronized boolean tryLoadMapHelper() {
        try {
            System.loadLibrary("MapHelper");
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "libMapHelper 不可用，回退到纯 Java 像素转换", e);
            return false;
        }
    }

    /** MapHelper.so 是否可用（32 位 ARM 真机为 true）。 */
    public static boolean hasNativeMapHelper() {
        if (nativeMapHelper == null) {
            nativeMapHelper = tryLoadMapHelper();
        }
        return nativeMapHelper;
    }
}
