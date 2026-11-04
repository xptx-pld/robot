package com.qihancloud.frontnavigation.chassis;

/**
 * 无机器人时的仿真底盘：差速轮运动学积分，供 H5 界面联调 / 演示。
 *
 * <p>参数取自 Sanbot S1 量级（轮距 0.56m、最大线速度 0.4m/s），只用于演示，
 * 不要拿它当真机标定值。这也是原始项目缺失的部分——当年没有仿真器，
 * 每次改动都必须推着真机试，所以“控制不了机器人”那 60% 的时间很难收敛。
 */
public class SimChassisAdapter implements ChassisAdapter {

    private static final double MAX_LINEAR = 0.45;

    private double x;
    private double y;
    private double th;
    private double targetX;
    private double targetY;
    private double targetTh;
    private volatile boolean goToActive;
    private long lastTick;

    public SimChassisAdapter(double startX, double startY, double startTh) {
        this.x = startX;
        this.y = startY;
        this.th = startTh;
        this.targetX = startX;
        this.targetY = startY;
        this.targetTh = startTh;
        this.lastTick = System.currentTimeMillis();
    }

    @Override
    public String name() {
        return "Sim";
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public void goTo(double tx, double ty, double trad, Callback callback) {
        targetX = tx;
        targetY = ty;
        targetTh = trad;
        goToActive = true;
        if (callback != null) {
            callback.onResult(true, "仿真导航已开始");
        }
    }

    @Override
    public void cancel() {
        goToActive = false;
    }

    @Override
    public void stop() {
        goToActive = false;
    }

    @Override
    public boolean supportsVelocity() {
        return true;
    }

    @Override
    public void drive(double linear, double angular) {
        double dt = tick();
        double v = Math.max(-MAX_LINEAR, Math.min(MAX_LINEAR, linear));
        x += v * Math.cos(th) * dt;
        y += v * Math.sin(th) * dt;
        th += angular * dt;
        goToActive = false;
    }

    /** 由上层定时调用（10Hz）推进仿真。 */
    public void update() {
        double dt = tick();
        if (!goToActive) {
            return;
        }
        double dx = targetX - x;
        double dy = targetY - y;
        double dist = Math.hypot(dx, dy);
        if (dist < 0.05) {
            double dth = normalize(targetTh - th);
            if (Math.abs(dth) < 0.05) {
                goToActive = false;
                return;
            }
            th += Math.signum(dth) * Math.min(Math.abs(dth), 1.2 * dt);
            return;
        }
        double heading = Math.atan2(dy, dx);
        double dth = normalize(heading - th);
        th += Math.signum(dth) * Math.min(Math.abs(dth), 1.5 * dt);
        double step = Math.min(dist, MAX_LINEAR * 0.8 * dt);
        x += Math.cos(th) * step;
        y += Math.sin(th) * step;
    }

    @Override
    public double[] pose() {
        return new double[]{x, y, th};
    }

    @Override
    public String describe() {
        return "底盘:仿真";
    }

    private double tick() {
        long now = System.currentTimeMillis();
        double dt = Math.min(0.5, (now - lastTick) / 1000.0);
        lastTick = now;
        return dt;
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
