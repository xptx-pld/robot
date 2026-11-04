package com.qihancloud.frontnavigation.web;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.qihancloud.frontnavigation.core.BotController;

/**
 * H5 与原生能力的唯一出口（PPT 里的 {@code RobotJsBridge}）。
 *
 * <p>约束：{@code @JavascriptInterface} 方法都在 WebView 的 JavaBridge 线程上执行，
 * 不能直接碰 UI；这里所有方法要么是纯读状态，要么转交给 {@link BotController}
 * （其内部对阻塞的导航调用已做了工作线程投递）。
 */
public class JsBridge {

    private static final String TAG = "JsBridge";

    private final Activity activity;
    private final WebView webView;

    public JsBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    // ------------------------------------------------------------------ 只读

    @JavascriptInterface
    public String state() {
        return BotController.get().stateJson();
    }

    @JavascriptInterface
    public String map() {
        return BotController.get().mapJson();
    }

    @JavascriptInterface
    public String tags() {
        return BotController.get().tagsJson();
    }

    @JavascriptInterface
    public String scan() {
        return BotController.get().scanJson();
    }

    // ------------------------------------------------------------------ 动作

    @JavascriptInterface
    public String navigateTo(int tagId) {
        boolean ok = BotController.get().navigateTo(tagId);
        return ack(ok, ok ? "已下发导航" : "导航下发失败");
    }

    @JavascriptInterface
    public String navigateByName(String name) {
        boolean ok = BotController.get().navigateToName(name);
        return ack(ok, ok ? "已下发导航" : "没有该点位");
    }

    @JavascriptInterface
    public String cancel() {
        BotController.get().cancelNavigation();
        return ack(true, "已取消");
    }

    @JavascriptInterface
    public String emergencyStop() {
        BotController.get().emergencyStop();
        return ack(true, "已急停");
    }

    @JavascriptInterface
    public String relocalize(int tagId) {
        BotController.get().relocalize(tagId);
        return ack(true, "已请求重定位");
    }

    @JavascriptInterface
    public void speak(String text) {
        BotController.get().speak(text);
    }

    /** 打开机器人自带的地图/建图 App（SLAM 在 x86 板，本应用不负责建图算法）。 */
    @JavascriptInterface
    public String openMapApp() {
        boolean ok = BotController.get().openRobotMapApp();
        return ack(ok, ok ? "正在打开地图 App" : "地图 App 不存在");
    }

    @JavascriptInterface
    public String useSimulator() {
        BotController.get().useSimulator();
        return ack(true, "已切换仿真底盘");
    }

    /** 供 H5 拉起机器人原生语音识别设置页（可选能力）。 */
    @JavascriptInterface
    public String openSpeechSettings() {
        try {
            Intent i = new Intent("com.qihancloud.frontsettings.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return ack(true, "正在打开设置");
        } catch (Throwable e) {
            Log.d(TAG, "打开设置失败", e);
            return ack(false, "设置页不可用");
        }
    }

    private static String ack(boolean ok, String message) {
        return "{\"ok\":" + ok + ",\"message\":\"" + message.replace("\"", "'") + "\"}";
    }
}
