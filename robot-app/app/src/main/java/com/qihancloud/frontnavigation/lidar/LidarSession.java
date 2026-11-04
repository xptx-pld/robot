package com.qihancloud.frontnavigation.lidar;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.List;

/**
 * RPLIDAR 的 USB 接入会话：找设备 -> 申请权限 -> 打开串口 -> 交给 {@link RpLidarA1} 读数据。
 *
 * <p>PPT 里“雷达 USB 供电待补齐”说的就是这一步：机器人主板只有一个 USB Host 口，
 * 雷达是 5V/约 500mA，热插拔时容易电压不足导致设备枚举不到。所以这里失败会
 * 每 3s 重试一次，并把失败原因回传给 UI，而不是静默退出。
 */
public class LidarSession {

    private static final String TAG = "LidarSession";
    private static final String ACTION_USB_PERMISSION = "com.qihancloud.frontnavigation.USB_PERMISSION";

    public interface Callback {
        void onScan(LaserScan scan);

        void onStatus(String message);

        void onError(String message);
    }

    private final Callback callback;
    private Context context;
    private RpLidarA1 lidar;
    private BroadcastReceiver receiver;
    private volatile boolean running;
    private volatile boolean waitingPermission;
    private long lastOpenAttempt;

    public LidarSession(Callback callback) {
        this.callback = callback;
    }

    public synchronized void start(Context ctx) {
        this.context = ctx.getApplicationContext();
        running = true;
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            callback.onError("系统没有 USB Host 支持");
            return;
        }
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            callback.onError("未检测到 RPLIDAR（USB 串口设备未枚举），请检查供电与接线");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            requestPermission(usbManager, device);
            return;
        }
        openAndStart(usbManager, driver);
    }

    private void requestPermission(UsbManager usbManager, UsbDevice device) {
        if (waitingPermission) {
            return;
        }
        waitingPermission = true;
        callback.onStatus("等待 USB 权限：" + device.getDeviceName());
        registerReceiver();
        PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), 0);
        usbManager.requestPermission(device, pi);
    }

    private void registerReceiver() {
        if (receiver != null) {
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    return;
                }
                waitingPermission = false;
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && running) {
                    start(context);
                } else {
                    callback.onError("USB 权限被拒绝，雷达不可用");
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_USB_PERMISSION));
    }

    private void openAndStart(final UsbManager usbManager, final UsbSerialDriver driver) {
        lastOpenAttempt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
                    if (connection == null) {
                        callback.onError("打开 USB 设备失败（可能被其他应用占用）");
                        return;
                    }
                    UsbSerialPort port = driver.getPorts().get(0);
                    port.open(connection);
                    RpLidarA1 dev = new RpLidarA1(port);
                    dev.start(new RpLidarA1.FrameListener() {
                        @Override
                        public void onScan(LaserScan scan) {
                            callback.onScan(scan);
                        }

                        @Override
                        public void onError(String message) {
                            callback.onError(message);
                        }
                    });
                    lidar = dev;
                    callback.onStatus("雷达已启动，115200 bps");
                } catch (Throwable e) {
                    Log.e(TAG, "启动雷达失败", e);
                    callback.onError("启动雷达失败: " + e.getMessage());
                }
            }
        }, "lidar-open").start();
    }

    public synchronized void stop() {
        running = false;
        waitingPermission = false;
        RpLidarA1 dev = lidar;
        lidar = null;
        if (dev != null) {
            dev.stop();
        }
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiver = null;
        }
    }

    public boolean isRunning() {
        return lidar != null && lidar.isRunning();
    }

    /** 断线后由上层定时调用做自动重连。 */
    public void retryIfNeeded() {
        if (!running || lidar != null) {
            return;
        }
        if (System.currentTimeMillis() - lastOpenAttempt < 3000) {
            return;
        }
        start(context);
    }
}
