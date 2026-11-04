package com.qihancloud.frontnavigation.speech;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.sanbot.opensdk.function.unit.SpeechManager;

/**
 * 语音播报封装。
 *
 * <p>导航状态回调会密集触发，直接调用 {@code startSpeak} 会出现“抢话/复读”。
 * 这里做了 1.2s 节流 + 同文案去重，并统一在主线程投放，避免 SDK 内部状态竞争。
 */
public class SpeechHelper {

    private static final String TAG = "SpeechHelper";
    private static final long MIN_INTERVAL_MS = 1200;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile SpeechManager manager;
    private long lastAt;
    private String lastText = "";

    public void attach(SpeechManager manager) {
        if (manager == null && this.manager != null) {
            return;
        }
        this.manager = manager;
        Log.i(TAG, "SpeechManager " + (manager == null ? "不可用" : "已就绪"));
    }

    public boolean available() {
        return manager != null;
    }

    public void speak(final String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        final SpeechManager m = manager;
        if (m == null) {
            Log.d(TAG, "[静音] " + text);
            return;
        }
        long now = System.currentTimeMillis();
        if (text.equals(lastText) && now - lastAt < 4000) {
            return;
        }
        if (now - lastAt < MIN_INTERVAL_MS) {
            return;
        }
        lastAt = now;
        lastText = text;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    m.startSpeak(text);
                } catch (Throwable e) {
                    Log.w(TAG, "播报失败", e);
                }
            }
        });
    }

    public void stop() {
        final SpeechManager m = manager;
        if (m == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    m.stopSpeak();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
