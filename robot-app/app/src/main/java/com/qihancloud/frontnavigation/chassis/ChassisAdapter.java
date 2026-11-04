package com.qihancloud.frontnavigation.chassis;

/**
 * 底盘抽象。
 *
 * <p>PPT 里提到的 {@code ChassisAdapter} 就是这一层：上层（H5 / ROS2 桥接 / 语音指令）
 * 只依赖这个接口，真机走 {@link NavChassisAdapter}，无机器人时走
 * {@link SimChassisAdapter}，换底盘不用改业务代码。
 */
public interface ChassisAdapter {

    interface Callback {
        void onResult(boolean ok, String message);
    }

    String name();

    boolean ready();

    /** 导航到地图坐标系下的目标点（米 / 弧度通过 SDK 的像素坐标系传入，见 MapRepository）。 */
    void goTo(double x, double y, double radians, Callback callback);

    void cancel();

    void stop();

    /** 是否支持真正的速度环（ROS2 /cmd_vel）。 */
    boolean supportsVelocity();

    /** 速度指令，linear: m/s，angular: rad/s（左正）。 */
    void drive(double linear, double angular);

    /** 当前位姿 {x, y, radians}。 */
    double[] pose();

    String describe();
}
