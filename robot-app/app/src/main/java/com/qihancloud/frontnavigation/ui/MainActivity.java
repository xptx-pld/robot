package com.qihancloud.frontnavigation.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.qihancloud.frontnavigation.R;
import com.qihancloud.frontnavigation.core.BotController;
import com.qihancloud.frontnavigation.web.JsBridge;
import com.sanbot.opensdk.base.TopBaseActivity;
import com.sanbot.opensdk.beans.FuncConstant;
import com.sanbot.opensdk.function.unit.SpeechManager;

/**
 * 机器人端主界面：WebView(H5) + 原生能力桥接。
 *
 * <p>保留 2019 版 demo 的生命周期约定：{@code register(...)} 与
 * {@code getUnitManager(...)} 必须在 {@code super.onCreate()} 之前调用，
 * 否则 {@code onMainServiceConnected} 回调拿不到 SpeechManager。
 */
public class MainActivity extends TopBaseActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERMISSION = 1001;

    private WebView webView;
    private JsBridge jsBridge;

    @Override
    protected void onMainServiceConnected() {
        Log.i(TAG, "机器人主服务已连接");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        register(MainActivity.class);
        SpeechManager speechManager = (SpeechManager) getUnitManager(FuncConstant.SPEECH_MANAGER);
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setBodyView(R.layout.activity_main);

        webView = (WebView) findViewById(R.id.web_view);
        setupWebView();

        jsBridge = new JsBridge(this, webView);
        webView.addJavascriptInterface(jsBridge, "NativeBridge");

        BotController.get().bindSink(new BotController.EventSink() {
            @Override
            public void onEvent(final String json) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript("window.Bot&&window.Bot.onEvent(" + json + ")", null);
                    }
                });
            }
        });
        BotController.get().start(this, speechManager);

        webView.loadUrl("file:///android_asset/www/index.html");
        handleIntent(getIntent());
        requestRuntimePermissions();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                View loading = findViewById(R.id.loading);
                if (loading != null) {
                    loading.setVisibility(View.GONE);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.d(TAG, "[H5] " + cm.message());
            }
        });
        webView.setBackgroundColor(0xFF0B1013);
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "存储权限已授予" : "没有存储权限，地图不可用", Toast.LENGTH_LONG).show();
            if (granted) {
                BotController.get().start(this, null);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** 兼容肩膀按键/外部应用：intent 里带 navi_id 直接带路。 */
    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("navi_id")) {
            return;
        }
        int id = intent.getIntExtra("navi_id", -1);
        if (id != -1) {
            Log.i(TAG, "收到外部导航请求 navi_id=" + id);
            BotController.get().navigateTo(id);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("NativeBridge");
            webView.destroy();
            webView = null;
        }
        BotController.get().stop();
        super.onDestroy();
    }
}
