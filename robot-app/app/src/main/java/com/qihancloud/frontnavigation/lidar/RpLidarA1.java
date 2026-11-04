package com.qihancloud.frontnavigation.lidar;

import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;

/**
 * RPLIDAR A1M8 串口驱动（纯协议实现，不依赖 sdk 包）。
 *
 * <p>协议要点（SLAMTEC 官方协议 / A1M8 手册）：
 * <pre>
 * 请求：  0xA5 + CMD            CMD_SCAN=0x20, CMD_STOP=0x25, CMD_RESET=0x40, CMD_GET_INFO=0x50
 * 描述符：0xA5 0x5A dataType sc0 sc1 sc2 checksum   （checksum = 前 6 字节异或）
 * 测距节点（SCAN 模式，5 字节）：
 *   b0: bit0=起始标志S, bit1=!S, bit2..7=信号质量
 *   b1,b2: 角度 * 64（小端）
 *   b3,b4: 距离 * 4（b4 低 2 位为校验位：bit0=check, bit1=!check）
 * </pre>
 *
 * 波特率 115200，实测扫描频率约 5.5~8.7 Hz（PPT 记录的是 8.7 Hz），这里按帧测量不做假设。
 */
public class RpLidarA1 {

    private static final String TAG = "RpLidarA1";

    public static final int CMD_SCAN = 0x20;
    public static final int CMD_STOP = 0x25;
    public static final int CMD_RESET = 0x40;
    public static final int CMD_GET_INFO = 0x50;
    public static final int CMD_GET_HEALTH = 0x52;

    public interface FrameListener {
        void onScan(LaserScan scan);

        void onError(String message);
    }

    private final UsbSerialPort port;
    private volatile boolean running;
    private Thread reader;
    private FrameListener listener;

    public RpLidarA1(UsbSerialPort port) {
        this.port = port;
    }

    public void start(FrameListener listener) throws IOException {
        this.listener = listener;
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        port.setDTR(true);
        port.setRTS(true);
        try {
            port.write(new byte[]{(byte) 0xA5, (byte) CMD_RESET}, 500);
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        port.write(new byte[]{(byte) 0xA5, (byte) CMD_SCAN}, 500);
        readDescriptor();

        running = true;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "rplidar-reader");
        reader.setDaemon(true);
        reader.start();
        Log.i(TAG, "扫描已启动");
    }

    public void stop() {
        running = false;
        Thread t = reader;
        reader = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (port.isOpen()) {
                port.write(new byte[]{(byte) 0xA5, (byte) CMD_STOP}, 300);
            }
        } catch (Throwable e) {
            Log.w(TAG, "stop 指令发送失败", e);
        }
        try {
            port.close();
        } catch (Throwable e) {
            Log.w(TAG, "关闭串口失败", e);
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ 内部实现

    private void readDescriptor() throws IOException {
        byte[] d = new byte[7];
        readFully(d, 0, 7, 2000);
        if ((d[0] & 0xFF) != 0xA5 || (d[1] & 0xFF) != 0x5A) {
            throw new IOException("响应描述符非法: " + Arrays.toString(d));
        }
        int checksum = 0;
        for (int i = 0; i < 6; i++) {
            checksum ^= (d[i] & 0xFF);
        }
        if ((checksum & 0xFF) != (d[6] & 0xFF)) {
            throw new IOException("描述符校验失败");
        }
        Log.i(TAG, "响应描述符 dataType=0x" + Integer.toHexString(d[2] & 0xFF));
    }

    private void readLoop() {
        byte[] node = new byte[5];
        LaserScan scan = new LaserScan();
        long lastFrameAt = 0;
        int prevAngle = -1;

        try {
            while (running) {
                readFully(node, 0, 5, 1000);
                int b0 = node[0] & 0xFF;
                boolean start = (b0 & 0x01) != 0;
                boolean startInv = (b0 & 0x02) != 0;
                int quality = (b0 >> 2) & 0x3F;

                int angleQ6 = (node[1] & 0xFF) | ((node[2] & 0xFF) << 8);
                int angleDeg = (int) (angleQ6 / 64f);

                int b4 = node[4] & 0xFF;
                boolean checkOk = (b4 & 0x01) != 0 && (b4 & 0x02) == 0;
                int distQ2 = (node[3] & 0xFF) | ((b4 & 0xFC) << 6);
                float distMm = distQ2 / 4f;

                if (start && !startInv && prevAngle >= 0 && scan.validPoints() > 40) {
                    // 一整圈结束：收尾、计时、上报
                    long now = System.currentTimeMillis();
                    scan.stampMs = now;
                    if (lastFrameAt > 0) {
                        float dt = (now - lastFrameAt) / 1000f;
                        if (dt > 0.01f) {
                            scan.hz = 1f / dt;
                        }
                    }
                    lastFrameAt = now;
                    if (listener != null) {
                        listener.onScan(scan);
                    }
                    scan = new LaserScan();
                }

                if (checkOk && angleDeg >= 0 && angleDeg < LaserScan.BINS) {
                    scan.quality[angleDeg] = quality;
                    // 距离 0 或超出量程(>12m) 视为无回波
                    scan.distMm[angleDeg] = distMm > 50f ? distMm : 0f;
                    scan.points++;
                }
                prevAngle = angleDeg;
            }
        } catch (Throwable e) {
            if (running) {
                Log.e(TAG, "读取线程异常", e);
                if (listener != null) {
                    listener.onError("雷达读取中断: " + e.getMessage());
                }
            }
        }
    }

    private void readFully(byte[] buf, int off, int len, int timeoutMs) throws IOException {
        int got = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (got < len) {
            if (!running && got == 0) {
                throw new IOException("已停止");
            }
            int n = port.read(buf, off + got, len - got, 200);
            if (n > 0) {
                got += n;
            } else if (System.currentTimeMillis() > deadline) {
                throw new IOException("串口读超时");
            }
        }
    }
}
