package com.qihancloud.frontnavigation.chassis;

import android.util.Log;

import com.qihancloud.frontnavigation.nav.NavGateway;
import com.qihancloud.frontnavigation.nav.MsgText;

/**
 * 基于地图导航 SDK 的底盘实现。
 *
 * <p>导航 SDK 1.5 只有“导航到某点”这一类接口，没有速度环。为了接 ROS2 的
 * {@code /cmd_vel}，这里用 <b>虚拟目标点</b> 近似速度控制：把速度积分成前方
 * 0.15~0.5m 的临时目标点，按固定周期重新下发。这是保底手段，优点是只用官方 API、
 * 不依赖 MCU 私有指令；缺点是响应有延迟（SDK 内部有规划周期），所以限速要保守。
 *
 * <p>真机上更优的方案是 {@code WheelMotionManager.doNoAngleMotion(...)} 直接下发
 * 前进/转向指令，但该 bean 的构造签名在不同 dsdk 版本间不一致（本仓库留存的是
 * {@code dsdk-Normal-release.aar}，反编译看到的构造签名为 (BIIIB)V），因此没有
 * 硬编码绑定，保留为后续真机校验项。
 */
public class NavChassisAdapter implements ChassisAdapter {

    private static final String TAG = "NavChassis";
    private static final long JOG_INTERVAL_MS = 450;

    private final NavGateway nav;
    private long lastJogAt;
    private boolean jogging;

    public NavChassisAdapter(NavGateway nav) {
        this.nav = nav;
    }

    @Override
    public String name() {
        return "Sanbot-NavSDK";
    }

    @Override
    public boolean ready() {
        return nav != null && nav.isReady();
    }

    @Override
    public void goTo(double x, double y, double radians, final Callback callback) {
        if (nav == null) {
            if (callback != null) {
                callback.onResult(false, "导航服务未就绪");
            }
            return;
        }
        nav.navigateTo(x, y, radians, new NavGateway.ResultCallback() {
            @Override
            public void onResult(int code, String message) {
                if (callback != null) {
                    callback.onResult(code == com.sanbot.map.Msg.Result.RESULT_OK, message);
                }
            }
        });
    }

    @Override
    public void cancel() {
        if (nav != null) {
            nav.cancelNavigation(null);
        }
        jogging = false;
    }

    @Override
    public void stop() {
        cancel();
    }

    @Override
    public boolean supportsVelocity() {
        return false;
    }

    @Override
    public void drive(double linear, double angular) {
        if (nav == null || !nav.isReady()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastJogAt < JOG_INTERVAL_MS) {
            return;
        }
        lastJogAt = now;

        double[] p = nav.pose();
        double x = p[0];
        double y = p[1];
        double th = p[2];

        if (Math.abs(linear) < 0.02 && Math.abs(angular) < 0.02) {
            if (jogging) {
                nav.cancelNavigation(null);
                jogging = false;
            }
            return;
        }

        if (Math.abs(linear) < 0.05) {
            // 纯原地转向：目标点不变，只改朝向
            double target = normalize(th + clamp(angular, -0.6, 0.6));
            Log.d(TAG, "原地转向 -> " + Math.toDegrees(target));
            nav.navigateTo(x, y, target, null);
        } else {
            double dist = clamp(Math.abs(linear) * 0.9, 0.15, 0.5);
            double heading = th + clamp(angular * 0.35, -0.5, 0.5);
            double tx = x + Math.cos(heading) * dist * Math.signum(linear);
            double ty = y + Math.sin(heading) * dist * Math.signum(linear);
            Log.d(TAG, "虚拟目标点 -> (" + tx + "," + ty + ")");
            nav.navigateTo(tx, ty, heading, null);
        }
        jogging = true;
    }

    @Override
    public double[] pose() {
        return nav == null ? new double[]{0, 0, 0} : nav.pose();
    }

    @Override
    public String describe() {
        if (nav == null || !nav.isReady()) {
            return "底盘:未连接";
        }
        return "底盘:" + name() + " " + MsgText.navi(nav.naviState());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double normalize(double rad) {
        while (rad > Math.PI) {
            rad -= 2 * Math.PI;
        }
        while (rad < -Math.PI) {
            rad += 2 * Math.PI;
        }
        return rad;
    }
}
