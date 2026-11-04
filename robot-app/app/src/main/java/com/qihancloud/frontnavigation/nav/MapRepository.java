package com.qihancloud.frontnavigation.nav;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import com.qihancloud.frontnavigation.App;
import com.sanbot.map.Map;
import com.sanbot.map.MapClient;
import com.sanbot.map.MapHelper;
import com.sanbot.map.PositionTag;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 地图与点位仓库。
 *
 * <p>地图来自机器人自带的建图服务（{@code com.qihancloud.qh_ros_service}，运行在 x86 板上的
 * SLAM 进程），通过 {@link MapClient#getMap()} 取到 pgm 像素、宽高、分辨率和人工标注的
 * {@link PositionTag}。SDK 里 {@code invertMap} 是原地修改，重复调用会把地图翻乱，
 * 所以这里只在 {@code mapChanged()} 或首次加载时转换一次并缓存 Bitmap。
 */
public class MapRepository {

    private static final String TAG = "MapRepository";

    private final List<PositionTag> tags = new ArrayList<>();
    private Bitmap bitmap;
    private int width;
    private int height;
    private double resolution;
    private String mapName;
    private boolean loaded;

    public synchronized boolean reload() {
        try {
            Map map = MapClient.getMap();
            if (map == null) {
                clear();
                return false;
            }
            byte[] data = map.getData();
            if (data == null || map.getWidth() <= 0 || map.getHeight() <= 0) {
                clear();
                return false;
            }
            width = map.getWidth();
            height = map.getHeight();
            resolution = map.getResolution();
            mapName = map.getName();

            // 1) 数据转换（native 优先，缺失时回退纯 Java）
            if (App.hasNativeMapHelper()) {
                MapHelper.invertMap(data, width, height);
            } else {
                MapPixelConverter.invert(data, width, height);
            }

            // 2) 填充 RGB_565 位图，颜色掩码取自官方文档示例
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            if (App.hasNativeMapHelper()) {
                MapHelper.setPixels(bmp, data, width, height,
                        0b1111111111111111, 0b0000000000000000,
                        0b1100111001011001, 0b0000000000000000);
            } else {
                MapPixelConverter.fillPixels(bmp, data, width, height,
                        0b1111111111111111, 0b0000000000000000,
                        0b1100111001011001, 0b0000000000000000);
            }

            List<PositionTag> list = map.getTags();
            tags.clear();
            if (list != null) {
                tags.addAll(list);
            }
            bitmap = bmp;
            loaded = true;
            Log.i(TAG, "地图加载完成 name=" + mapName + " " + width + "x" + height
                    + " res=" + resolution + " tags=" + tags.size());
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "加载地图失败", e);
            clear();
            return false;
        }
    }

    private void clear() {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        bitmap = null;
        tags.clear();
        loaded = false;
    }

    public synchronized boolean isLoaded() {
        return loaded && bitmap != null;
    }

    public synchronized List<PositionTag> tags() {
        return new ArrayList<>(tags);
    }

    public synchronized PositionTag findById(int id) {
        for (PositionTag t : tags) {
            if (t.getId() == id) {
                return t;
            }
        }
        return null;
    }

    public synchronized PositionTag findByName(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim();
        for (PositionTag t : tags) {
            if (key.equals(t.getName())) {
                return t;
            }
        }
        for (PositionTag t : tags) {
            if (t.getName() != null && t.getName().contains(key)) {
                return t;
            }
        }
        return null;
    }

    /** 距离最近的点位（用于判断“到达哪个点”，阈值沿用 demo 的 10 像素）。 */
    public synchronized PositionTag nearest(double x, double y, double maxDistance) {
        PositionTag best = null;
        double min = Double.MAX_VALUE;
        for (PositionTag t : tags) {
            double d = Math.hypot(x - t.getX(), y - t.getY());
            if (d < min) {
                min = d;
                best = t;
            }
        }
        return min <= maxDistance ? best : null;
    }

    public synchronized int width() {
        return width;
    }

    public synchronized int height() {
        return height;
    }

    public synchronized double resolution() {
        return resolution;
    }

    public synchronized String mapName() {
        return mapName;
    }

    /**
     * 把地图压缩成 base64 PNG 给 WebView 显示。
     *
     * @return 长度 2 的数组：[0]=dataUrl，[1]=对原始像素的缩放比例
     */
    public synchronized String[] mapDataUrl(float maxSide) {
        if (bitmap == null || bitmap.isRecycled()) {
            return new String[]{"", "1"};
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float scale = Math.min(1f, maxSide / (float) Math.max(w, h));
        Bitmap out = bitmap;
        if (scale < 1f) {
            out = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.PNG, 100, bos);
        if (out != bitmap) {
            out.recycle();
        }
        String url = "data:image/png;base64,"
                + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        return new String[]{url, String.valueOf(scale)};
    }
}
