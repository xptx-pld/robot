package com.qihancloud.frontnavigation.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 运营后台（Node.js 零依赖服务）的瘦客户端。
 *
 * <p>只做两件事：把导航任务上报上去（数据看板要用），以及可选的读取点位清单。
 * 网络异常一律吞掉并降级为本地模式——后台挂了不能影响机器人带路。
 */
public class BackendClient {

    private static final String TAG = "BackendClient";
    private static final String PREF = "backend";
    private static final String KEY_URL = "base_url";

    private final String baseUrl;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile long lastOkAt;

    public BackendClient(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        baseUrl = sp.getString(KEY_URL, "http://192.168.1.10:8080");
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean online() {
        return System.currentTimeMillis() - lastOkAt < 30000;
    }

    /** 上报一次导航任务（异步，失败静默）。 */
    public void postTask(final String target, final String source) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("target", target);
                    body.put("source", source);
                    body.put("ts", System.currentTimeMillis());
                    post("/api/tasks", body.toString());
                } catch (Throwable e) {
                    Log.d(TAG, "上报任务失败: " + e.getMessage());
                }
            }
        });
    }

    private void post(String path, String json) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                lastOkAt = System.currentTimeMillis();
            }
            Log.d(TAG, "POST " + path + " -> " + code);
        } catch (Throwable e) {
            Log.d(TAG, "POST " + path + " 失败: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public String get(String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            lastOkAt = System.currentTimeMillis();
            return sb.toString();
        } catch (Throwable e) {
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
