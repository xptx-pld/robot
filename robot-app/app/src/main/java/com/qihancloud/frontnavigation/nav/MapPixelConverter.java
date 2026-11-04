package com.qihancloud.frontnavigation.nav;

import android.graphics.Bitmap;

/**
 * 纯 Java 版 {@code com.sanbot.map.MapHelper}。
 *
 * <p>官方 SDK 的 MapHelper 是 native 实现，只提供 armeabi / armeabi-v7a 两个 ABI 的
 * {@code libMapHelper.so}；在 64 位设备或模拟器上必然 {@code UnsatisfiedLinkError}。
 * 协议文档《地图导航新协议sdk文档(1.5)》对这两个方法的语义有明确说明，这里按文档等价实现：
 *
 * <pre>
 * invertMap 转换后：
 *   0        -> 可行走区域
 *   99 / 100 -> 障碍物
 *   其他      -> 未知区域
 *
 * setPixels(bitmap, map, width, height, walk, obstacle, unknown, def)
 *   颜色值必须是 RGB_565（16 位），Bitmap 类型必须是 RGB_565
 * </pre>
 *
 * 真机上仍优先使用 native 版本（见 {@link MapRepository}），本类只是兜底。
 */
public final class MapPixelConverter {

    public static final int WALKABLE = 0;
    public static final int OBSTACLE = 100;
    public static final int UNKNOWN = 205;

    private MapPixelConverter() {
    }

    /** 对应 MapHelper.invertMap：把 ROS 风格 pgm（0 占据/205 未知/254 空闲）转成 SDK 约定值。 */
    public static void invert(byte[] map, int width, int height) {
        if (map == null) {
            return;
        }
        int count = Math.min(map.length, width * height);
        for (int i = 0; i < count; i++) {
            int v = map[i] & 0xFF;
            if (v <= 60) {
                map[i] = (byte) OBSTACLE;
            } else if (v >= 200) {
                map[i] = (byte) WALKABLE;
            } else {
                map[i] = (byte) UNKNOWN;
            }
        }
    }

    /** 对应 MapHelper.setPixels：按区域类型填充 RGB_565 颜色值。 */
    public static void fillPixels(Bitmap bitmap, byte[] map, int width, int height,
                                  int walk, int obstacle, int unknown, int def) {
        if (bitmap == null || map == null) {
            return;
        }
        int[] pixels = new int[width * height];
        int count = Math.min(map.length, pixels.length);
        for (int i = 0; i < count; i++) {
            int v = map[i] & 0xFF;
            int color565;
            if (v == WALKABLE) {
                color565 = walk;
            } else if (v == 99 || v == OBSTACLE) {
                color565 = obstacle;
            } else if (v == UNKNOWN) {
                color565 = unknown;
            } else {
                color565 = def;
            }
            pixels[i] = rgb565ToArgb(color565);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    public static int rgb565ToArgb(int c) {
        int r = (c >> 11) & 0x1F;
        int g = (c >> 5) & 0x3F;
        int b = c & 0x1F;
        r = (r << 3) | (r >> 2);
        g = (g << 2) | (g >> 4);
        b = (b << 3) | (b >> 2);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
