package com.qihancloud.frontnavigation.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 兼容 2019 版 demo 的广播入口：{@code com.sanbot.action.navigation} + navi_id。
 * 机器人的外部应用（迎宾/导览）通过它让导航 App 出来带路。
 */
public class NavReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (!"com.sanbot.action.navigation".equals(intent.getAction())) {
            return;
        }
        int id = intent.getIntExtra("navi_id", 0);
        if (id == 0) {
            return;
        }
        Intent launch = new Intent(context, MainActivity.class);
        launch.putExtra("navi_id", id);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }
}
