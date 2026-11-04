package com.qihancloud.frontnavigation.lidar;

/**
 * 一帧 360° 激光扫描，按 1° 分箱保存（RPLIDAR A1 的角分辨率就是 1°）。
 *
 * <p>分发到 UI/ROS 桥接时用 {@link #toJson()} 压缩输出，避免每秒几万字节的浮点文本
 * 把 WebView / TCP 打满。
 */
public class LaserScan {

    public static final int BINS = 360;

    public final float[] distMm = new float[BINS];
    public final int[] quality = new int[BINS];
    public long stampMs;
    public int points;
    public float hz;

    public LaserScan() {
        for (int i = 0; i < BINS; i++) {
            distMm[i] = 0f;
        }
    }

    /**
     * 取以 centerDeg 为中心、半宽 halfWidthDeg 的扇区最小距离（mm）。
     *
     * @return 无有效点时返回 {@link Float#MAX_VALUE}
     */
    public float minInSector(float centerDeg, float halfWidthDeg) {
        float min = Float.MAX_VALUE;
        int half = Math.round(halfWidthDeg);
        int c = Math.round(centerDeg);
        for (int d = -half; d <= half; d++) {
            int idx = ((c + d) % BINS + BINS) % BINS;
            float v = distMm[idx];
            if (v > 0 && v < min) {
                min = v;
            }
        }
        return min;
    }

    /** 有效点数量（用于判断雷达是否真的在出数据）。 */
    public int validPoints() {
        int n = 0;
        for (int i = 0; i < BINS; i++) {
            if (distMm[i] > 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 紧凑 JSON：{"hz":8.7,"n":312,"ts":...,"r":[cm...]}
     * 元素单位厘米，-1 表示该角度无回波。
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(1200);
        sb.append("{\"hz\":").append(Math.round(hz * 10) / 10.0)
                .append(",\"n\":").append(validPoints())
                .append(",\"ts\":").append(stampMs)
                .append(",\"r\":[");
        for (int i = 0; i < BINS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            float mm = distMm[i];
            sb.append(mm > 0 ? Math.round(mm / 10f) : -1);
        }
        sb.append("]}");
        return sb.toString();
    }
}
