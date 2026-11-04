package com.qihancloud.frontnavigation.lidar;

/**
 * 激光避障判定（滞后比较，避免在阈值附近抖动）。
 *
 * <p>原方案的避障分两层：底盘自带的红外/超声波（近距离，负责最后一道刹车）和
 * 雷达（远距离，负责提前减速/绕行）。这里只做雷达这一层，输出三档：
 * <ul>
 *   <li>CLEAR：正常通行</li>
 *   <li>SLOW：前方 0.6~1.2m 有障碍，降速</li>
 *   <li>STOP：前方 0.6m 内被占据，立刻停车并取消当前导航</li>
 * </ul>
 * 机器人本体 0° 为车头正前方，扇区取 ±FRONT_HALF_DEG。
 */
public class ObstacleGuard {

    public static final int CLEAR = 0;
    public static final int SLOW = 1;
    public static final int STOP = 2;

    private static final float FRONT_HALF_DEG = 30f;
    private static final float STOP_MM = 600f;
    private static final float SLOW_MM = 1200f;
    private static final float RELEASE_RATIO = 1.35f;

    private volatile int state = CLEAR;
    private volatile float frontMm = Float.MAX_VALUE;
    private volatile float leftMm = Float.MAX_VALUE;
    private volatile float rightMm = Float.MAX_VALUE;

    public int state() {
        return state;
    }

    public float frontMm() {
        return frontMm;
    }

    public float leftMm() {
        return leftMm;
    }

    public float rightMm() {
        return rightMm;
    }

    public boolean isBlocked() {
        return state == STOP;
    }

    public static String name(int s) {
        switch (s) {
            case STOP:
                return "停车";
            case SLOW:
                return "减速";
            default:
                return "通畅";
        }
    }

    /** 每帧调用一次，返回新的安全状态。 */
    public int update(LaserScan scan) {
        if (scan == null) {
            return state;
        }
        frontMm = scan.minInSector(0, FRONT_HALF_DEG);
        leftMm = scan.minInSector(90, FRONT_HALF_DEG);
        rightMm = scan.minInSector(270, FRONT_HALF_DEG);

        int next;
        if (frontMm <= STOP_MM) {
            next = STOP;
        } else if (frontMm <= SLOW_MM) {
            next = SLOW;
        } else {
            next = CLEAR;
        }

        // 退出条件放大 35%，避免 600mm 附近反复停/走
        if (state == STOP && frontMm < STOP_MM * RELEASE_RATIO) {
            next = STOP;
        }
        if (state == SLOW && frontMm < SLOW_MM * 1.05f && next == CLEAR) {
            next = SLOW;
        }
        state = next;
        return state;
    }

    public void reset() {
        state = CLEAR;
        frontMm = Float.MAX_VALUE;
        leftMm = Float.MAX_VALUE;
        rightMm = Float.MAX_VALUE;
    }

    /**
     * 走不动时的转向建议：-1 左转、1 右转、0 原地等。
     * 比较左右两侧空旷程度，选更空的一侧（对应报告里“前方遇障则向更空旷一侧转向”）。
     */
    public int turnPreference() {
        if (leftMm == Float.MAX_VALUE && rightMm == Float.MAX_VALUE) {
            return 0;
        }
        if (leftMm > rightMm * 1.2f) {
            return -1;
        }
        if (rightMm > leftMm * 1.2f) {
            return 1;
        }
        return 0;
    }
}
