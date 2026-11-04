package com.qihancloud.frontnavigation.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 机器人端 TCP 桥接服务：行分隔 JSON，默认端口 9001。
 *
 * <p>存在的理由：建图（slam_toolbox）和全局规划（nav2）跑在 VMware 里的 ROS2，
 * 而底盘只能通过机器人 Android 上的地图导航 SDK 控制。两者之间需要一个窄口。
 * 当年“控制不了机器人”就卡在这条链路上——没有这个桥，ROS2 侧无论算得多准，
 * 都下不到轮子上。
 *
 * <p>协议（每行一个 JSON）：
 * <pre>
 * 上位机 -> 机器人： {"cmd":"vel","linear":0.2,"angular":0.0}
 *                    {"cmd":"goto","x":1.2,"y":3.4,"rad":0.0}
 *                    {"cmd":"goto_name","name":"会议室"}
 *                    {"cmd":"cancel"} / {"cmd":"stop"} / {"cmd":"status"}
 * 机器人 -> 上位机： {"ok":true}           命令应答
 *                    {...stateJson...}     1Hz 状态推送
 * </pre>
 */
public class BridgeServer {

    private static final String TAG = "BridgeServer";

    public interface Handler {
        String onCommand(JSONObject json);

        String statusJson();
    }

    private final Handler handler;
    private final List<Socket> clients = Collections.synchronizedList(new ArrayList<Socket>());
    private ServerSocket serverSocket;
    private volatile boolean running;
    private int port;

    public BridgeServer(Handler handler) {
        this.handler = handler;
    }

    public void start(final int preferredPort) {
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(preferredPort);
            }
        }, "bridge-accept").start();
    }

    private void acceptLoop(int preferredPort) {
        int candidate = preferredPort;
        while (running && candidate < preferredPort + 10) {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(candidate));
                port = candidate;
                Log.i(TAG, "桥接服务监听 " + candidate);
                break;
            } catch (IOException e) {
                Log.w(TAG, "端口 " + candidate + " 不可用，尝试下一个");
                candidate++;
            }
        }
        if (serverSocket == null) {
            Log.e(TAG, "桥接服务启动失败");
            return;
        }
        startStatusBroadcast();
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                clients.add(socket);
                Log.i(TAG, "上位机接入 " + socket.getRemoteSocketAddress());
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        serve(socket);
                    }
                }, "bridge-client").start();
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept 异常", e);
                }
            }
        }
    }

    private void serve(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            OutputStream out = socket.getOutputStream();
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.trim().length() == 0) {
                    continue;
                }
                String reply;
                try {
                    reply = handler.onCommand(new JSONObject(line));
                } catch (Throwable e) {
                    reply = "{\"ok\":false,\"reason\":\"bad_json\"}";
                }
                out.write((reply + "\n").getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable e) {
            Log.w(TAG, "客户端连接结束: " + e.getMessage());
        } finally {
            clients.remove(socket);
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void startStatusBroadcast() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (clients.isEmpty()) {
                        continue;
                    }
                    byte[] payload = (handler.statusJson() + "\n").getBytes("UTF-8");
                    synchronized (clients) {
                        for (Socket s : new ArrayList<>(clients)) {
                            try {
                                OutputStream os = s.getOutputStream();
                                os.write(payload);
                                os.flush();
                            } catch (Throwable e) {
                                clients.remove(s);
                            }
                        }
                    }
                }
            }
        }, "bridge-status").start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Throwable ignored) {
        }
        synchronized (clients) {
            for (Socket s : new ArrayList<>(clients)) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            clients.clear();
        }
    }

    public int port() {
        return port;
    }

    public int clientCount() {
        return clients.size();
    }
}
