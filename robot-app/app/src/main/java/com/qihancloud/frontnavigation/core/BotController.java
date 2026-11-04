package com.qihancloud.frontnavigation.core;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.qihancloud.frontnavigation.chassis.ChassisAdapter;
import com.qihancloud.frontnavigation.chassis.NavChassisAdapter;
import com.qihancloud.frontnavigation.chassis.SimChassisAdapter;
import com.qihancloud.frontnavigation.lidar.LaserScan;
import com.qihancloud.frontnavigation.lidar.LidarSession;
import com.qihancloud.frontnavigation.lidar.ObstacleGuard;
import com.qihancloud.frontnavigation.nav.MapRepository;
import com.qihancloud.frontnavigation.nav.MsgText;
import com.qihancloud.frontnavigation.nav.NavGateway;
import com.qihancloud.frontnavigation.net.BridgeServer;
import com.qihancloud.frontnavigation.net.BackendClient;
import com.qihancloud.frontnavigation.speech.SpeechHelper;
import com.sanbot.map.Msg;
import com.sanbot.map.PositionTag;
import com.sanbot.opensdk.function.unit.SpeechManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 业务编排中心。
 *
 * <p>把四个来源捏在一起：
 * <ol>
 *   <li>导航 SDK（{@link NavGateway}）：地图、点位、执行业务层导航；</li>
 *   <li>RPLIDAR（{@link LidarSession}）：远距离感知与安全兜底；</li>
 *   <li>H5 界面（{@link com.qihancloud.frontnavigation.web.JsBridge}）：本地触屏操作；</li>
 *   <li>ROS2 桥接（{@link BridgeServer}）：上位机 vm 中 slam_toolbox / nav2 的位置输出。</li>
 * </ol>
 *
 * 这里也是当年“控制不了机器人”问题的收敛点：所有对底盘的调用都要经过
 * {@link #navigateTo} → 安全门（{@link ObstacleGuard}）→ {@link ChassisAdapter}，
 * 任何绕过安全门的调用都会在日志里体现为 {@code E/…绕过安全门}。
 */
public class BotController implements NavGateway.Listener, LidarSession.Callback {

    private static final String TAG = "BotController";
    private static final BotController INSTANCE = new BotController();

    public interface EventSink {
        void onEvent(String json);
    }

    private final NavGateway nav = new NavGateway();
    private final MapRepository maps = new MapRepository();
    private final ObstacleGuard guard = new ObstacleGuard();
    private final SpeechHelper speech = new SpeechHelper();

    private Context context;
    private LidarSession lidar;
    private ChassisAdapter chassis;
    private BridgeServer bridge;
    private BackendClient backend;
    private EventSink sink;
    private ScheduledExecutorService ticker;

    private volatile LaserScan lastScan;
    private volatile PositionTag pendingTarget;
    private volatile boolean pausedByLidar;
    private volatile long clearSince;
    private volatile String lastMessage = "";
    private volatile int battery = -1;

    private BotController() {
    }

    public static BotController get() {
        return INSTANCE;
    }

    public void bindSink(EventSink sink) {
        this.sink = sink;
    }

    public synchronized void start(Context ctx, SpeechManager speechManager) {
        if (ticker != null) {
            // 权限回调/Activity 重建会重复调用，这里只刷新地图，不重复起服务
            maps.reload();
            return;
        }
        this.context = ctx.getApplicationContext();
        speech.attach(speechManager);

        chassis = new NavChassisAdapter(nav);
        lidar = new LidarSession(this);
        backend = new BackendClient(this.context);
        bridge = new BridgeServer(new BridgeServer.Handler() {
            @Override
            public String onCommand(JSONObject json) {
                return handleBridgeCommand(json);
            }

            @Override
            public String statusJson() {
                return stateJson();
            }
        });
        bridge.start(9001);

        nav.connect(this);
        lidar.start(this.context);

        if (ticker != null) {
            ticker.shutdownNow();
        }
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 500, 200, TimeUnit.MILLISECONDS);
        Log.i(TAG, "BotController 已启动");
    }

    public synchronized void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
        if (lidar != null) {
            lidar.stop();
        }
        if (bridge != null) {
            bridge.stop();
        }
        nav.close();
        speech.stop();
        Log.i(TAG, "BotController 已停止");
    }

    private void tick() {
        try {
            if (lidar != null) {
                lidar.retryIfNeeded();
            }
            if (chassis instanceof SimChassisAdapter) {
                ((SimChassisAdapter) chassis).update();
            }
            // 雷达恢复通畅后，自动续跑被打断的导航
            if (pausedByLidar && !guard.isBlocked()) {
                if (clearSince == 0) {
                    clearSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - clearSince > 2000) {
                    resumeAfterLidar();
                }
            } else {
                clearSince = 0;
            }
            battery = readBattery();
        } catch (Throwable e) {
            Log.w(TAG, "tick 异常", e);
        }
    }

    // ------------------------------------------------------------- 导航回调

    @Override
    public void onNavConnected() {
        maps.reload();
        emit("navi", "导航服务已连接");
        // 与 demo 一致：未开启导航时自动开启
        if (nav.ctrlState() != Msg.CtrlState.NAVIGATION && nav.ctrlState() != Msg.CtrlState.BUILD) {
            nav.openNavigation(new NavGateway.ResultCallback() {
                @Override
                public void onResult(int code, String message) {
                    emit("navi", "开启导航：" + message);
                }
            });
        }
    }

    @Override
    public void onNavState(int ctrl, int navi, int oldNavi) {
        String text = MsgText.navi(navi);
        if (navi == Msg.NaviState.ARRIVE) {
            PositionTag tag = pendingTarget;
            if (tag == null) {
                tag = maps.nearest(nav.pose()[0], nav.pose()[1], 10);
            }
            speech.speak(tag == null ? "已到达目标点" : "已到达" + tag.getName());
            emit("arrived", tag == null ? "" : tag.getName());
            pendingTarget = null;
            pausedByLidar = false;
        } else if (navi >= Msg.NaviState.ERROR) {
            speech.speak(text);
            emit("error", text);
            long delay = MsgText.cancelDelayMs(navi);
            if (delay > 0) {
                emit("warn", "导航异常，将自动取消：" + text);
            }
        } else if (navi == Msg.NaviState.CANCEL) {
            emit("navi", "导航已取消");
        }
        Log.i(TAG, "状态变化 ctrl=" + ctrl + " navi=" + navi + " old=" + oldNavi + " (" + text + ")");
    }

    @Override
    public void onPose(double x, double y, double radians) {
        // 高频回调，不广播，由 stateJson 轮询读取
    }

    @Override
    public void onMapChanged() {
        maps.reload();
        emit("map", "地图已更新");
    }

    @Override
    public void onMoveError(int error, int sub) {
        String text = MsgText.moveError(error);
        if (error >= Msg.MoveError.MOTOR_ID_ERROR) {
            emit("error", "运动异常：" + text);
            speech.speak(text);
        }
    }

    @Override
    public void onPlanPath(List<PositionTag> path) {
        emit("path", "规划路径点数 " + (path == null ? 0 : path.size()));
    }

    // ------------------------------------------------------------- 雷达回调

    @Override
    public void onScan(LaserScan scan) {
        lastScan = scan;
        int state = guard.update(scan);
        if (state == ObstacleGuard.STOP && !pausedByLidar && isNavigating()) {
            pausedByLidar = true;
            clearSince = 0;
            speech.speak("前方有障碍，我先停一下");
            emit("safety", "雷达急停：前方 " + Math.round(guard.frontMm()) + "mm");
            nav.cancelNavigation(null);
        } else if (state == ObstacleGuard.SLOW && isNavigating()) {
            emit("safety", "前方有人，减速通行");
        }
    }

    @Override
    public void onStatus(String message) {
        Log.i(TAG, message);
        emit("status", message);
    }

    @Override
    public void onError(String message) {
        Log.w(TAG, message);
        emit("error", message);
    }

    // ------------------------------------------------------------- 命令入口

    public boolean navigateTo(PositionTag tag) {
        if (tag == null) {
            return false;
        }
        if (guard.isBlocked()) {
            emit("error", "雷达检测到障碍，暂不下发导航：" + Math.round(guard.frontMm()) + "mm");
            return false;
        }
        pendingTarget = tag;
        pausedByLidar = false;
        speech.speak("请跟我来，我将带你去" + tag.getName());
        emit("navi", "导航至 " + tag.getName());
        backend.postTask(tag.getName(), "voice");
        chassis.goTo(tag.getX(), tag.getY(), tag.getRadians(), new ChassisAdapter.Callback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (!ok) {
                    emit("error", "导航下发失败：" + message);
                }
            }
        });
        return true;
    }

    public boolean navigateTo(int tagId) {
        PositionTag tag = maps.findById(tagId);
        if (tag == null) {
            emit("error", "找不到点位 id=" + tagId);
            return false;
        }
        return navigateTo(tag);
    }

    public boolean navigateToName(String name) {
        PositionTag tag = maps.findByName(name);
        if (tag == null) {
            emit("error", "没有叫“" + name + "”的点位");
            return false;
        }
        return navigateTo(tag);
    }

    public void cancelNavigation() {
        pendingTarget = null;
        pausedByLidar = false;
        chassis.cancel();
        speech.speak("已取消导航");
        emit("navi", "已取消导航");
    }

    public void emergencyStop() {
        pendingTarget = null;
        pausedByLidar = false;
        chassis.stop();
        speech.speak("已紧急停止");
        emit("safety", "紧急停止");
    }

    public void relocalize(int tagId) {
        PositionTag tag = maps.findById(tagId);
        if (tag == null) {
            emit("error", "重定位失败：无此点位");
            return;
        }
        nav.adjustPosition(tag, new NavGateway.ResultCallback() {
            @Override
            public void onResult(int code, String message) {
                emit("navi", "重定位：" + message);
            }
        });
    }

    private void resumeAfterLidar() {
        PositionTag tag = pendingTarget;
        pausedByLidar = false;
        clearSince = 0;
        if (tag == null) {
            return;
        }
        emit("navi", "障碍已清除，继续前往 " + tag.getName());
        speech.speak("继续带你去" + tag.getName());
        chassis.goTo(tag.getX(), tag.getY(), tag.getRadians(), null);
    }

    private String handleBridgeCommand(JSONObject json) {
        String cmd = json.optString("cmd");
        try {
            if ("vel".equals(cmd)) {
                double linear = json.optDouble("linear", 0);
                double angular = json.optDouble("angular", 0);
                if (guard.isBlocked() && linear > 0) {
                    chassis.stop();
                    return "{\"ok\":false,\"reason\":\"lidar_blocked\"}";
                }
                chassis.drive(linear, angular);
                return "{\"ok\":true}";
            }
            if ("stop".equals(cmd)) {
                chassis.stop();
                return "{\"ok\":true}";
            }
            if ("goto".equals(cmd)) {
                pendingTarget = null;
                pausedByLidar = false;
                chassis.goTo(json.optDouble("x"), json.optDouble("y"), json.optDouble("rad"), null);
                return "{\"ok\":true}";
            }
            if ("goto_name".equals(cmd)) {
                boolean ok = navigateToName(json.optString("name"));
                return "{\"ok\":" + ok + "}";
            }
            if ("cancel".equals(cmd)) {
                cancelNavigation();
                return "{\"ok\":true}";
            }
            if ("speak".equals(cmd)) {
                speak(json.optString("text"));
                return "{\"ok\":true}";
            }
            if ("status".equals(cmd)) {
                return stateJson();
            }
            return "{\"ok\":false,\"reason\":\"unknown_cmd\"}";
        } catch (Throwable e) {
            Log.w(TAG, "桥接命令失败", e);
            return "{\"ok\":false,\"reason\":\"" + e.getMessage() + "\"}";
        }
    }

    // ------------------------------------------------------------- 状态输出

    public boolean isNavigating() {
        return nav.naviState() == Msg.NaviState.NAVI || nav.naviState() == Msg.NaviState.PLANING;
    }

    public String stateJson() {
        JSONObject o = new JSONObject();
        try {
            double[] p = nav.pose();
            JSONObject navObj = new JSONObject();
            navObj.put("ready", nav.isReady());
            navObj.put("ctrl", nav.ctrlState());
            navObj.put("ctrlText", MsgText.ctrl(nav.ctrlState()));
            navObj.put("navi", nav.naviState());
            navObj.put("naviText", MsgText.navi(nav.naviState()));
            navObj.put("navigating", isNavigating());
            o.put("nav", navObj);
            o.put("pose", new JSONArray().put(p[0]).put(p[1]).put(p[2]));
            o.put("poseDeg", Math.toDegrees(p[2]));

            JSONObject mapObj = new JSONObject();
            mapObj.put("loaded", maps.isLoaded());
            mapObj.put("name", maps.mapName() == null ? "" : maps.mapName());
            mapObj.put("w", maps.width());
            mapObj.put("h", maps.height());
            o.put("map", mapObj);

            JSONObject lidarObj = new JSONObject();
            LaserScan scan = lastScan;
            lidarObj.put("running", lidar != null && lidar.isRunning());
            lidarObj.put("hz", scan == null ? 0 : Math.round(scan.hz * 10) / 10.0);
            lidarObj.put("points", scan == null ? 0 : scan.validPoints());
            lidarObj.put("front", guard.frontMm() == Float.MAX_VALUE ? -1 : Math.round(guard.frontMm()));
            lidarObj.put("left", guard.leftMm() == Float.MAX_VALUE ? -1 : Math.round(guard.leftMm()));
            lidarObj.put("right", guard.rightMm() == Float.MAX_VALUE ? -1 : Math.round(guard.rightMm()));
            lidarObj.put("safety", guard.state());
            lidarObj.put("safetyText", ObstacleGuard.name(guard.state()));
            o.put("lidar", lidarObj);

            o.put("chassis", chassis == null ? "" : chassis.describe());
            o.put("speech", speech.available());
            o.put("battery", battery);
            o.put("bridgePort", bridge == null ? 0 : bridge.port());
            o.put("bridgeClients", bridge == null ? 0 : bridge.clientCount());
            o.put("backend", backend == null ? "" : backend.baseUrl());
            o.put("pending", pendingTarget == null ? "" : pendingTarget.getName());
            o.put("message", lastMessage);
            o.put("tags", maps.tags().size());
        } catch (Throwable e) {
            Log.e(TAG, "构造状态失败", e);
        }
        return o.toString();
    }

    public String tagsJson() {
        JSONArray arr = new JSONArray();
        List<PositionTag> list = maps.tags();
        for (PositionTag t : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", t.getId());
                o.put("name", t.getName());
                o.put("x", t.getX());
                o.put("y", t.getY());
                o.put("rad", t.getRadians());
            } catch (Throwable ignored) {
            }
            arr.put(o);
        }
        return arr.toString();
    }

    public String mapJson() {
        JSONObject o = new JSONObject();
        try {
            String[] data = maps.mapDataUrl(720);
            o.put("loaded", maps.isLoaded());
            o.put("name", maps.mapName() == null ? "" : maps.mapName());
            o.put("w", maps.width());
            o.put("h", maps.height());
            o.put("scale", Double.parseDouble(data[1]));
            o.put("png", data[0]);
            o.put("tags", new JSONArray(tagsJson()));
        } catch (Throwable e) {
            Log.e(TAG, "构造地图失败", e);
        }
        return o.toString();
    }

    public String scanJson() {
        LaserScan s = lastScan;
        return s == null ? "{}" : s.toJson();
    }

    public void speak(String text) {
        speech.speak(text);
    }

    /** 打开机器人自带的地图/建图 App（SLAM 跑在 x86 板，不在本应用内）。 */
    public boolean openRobotMapApp() {
        try {
            Intent i = new Intent().setClassName("com.qihancloud.qh_ros_service",
                    "com.qihancloud.qh_ros_service.Activity.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return true;
        } catch (Throwable e) {
            emit("error", "未安装机器人地图 App: " + e.getMessage());
            return false;
        }
    }

    private int readBattery() {
        try {
            Intent sticky = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky == null) {
                return -1;
            }
            int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return scale > 0 ? Math.round(level * 100f / scale) : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    private void emit(String type, String text) {
        lastMessage = text;
        EventSink s = sink;
        if (s == null) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("text", text);
            o.put("ts", System.currentTimeMillis());
            s.onEvent(o.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 仅用于 H5 演示：切到仿真底盘（无机器人时验证界面与流程）。 */
    public void useSimulator() {
        if (chassis instanceof SimChassisAdapter) {
            return;
        }
        double[] p = nav.pose();
        chassis = new SimChassisAdapter(p[0], p[1], p[2]);
        emit("status", "已切换仿真底盘");
    }

    public Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }
}
