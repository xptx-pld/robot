# 机器人端（Sanbot S1 / 旗瀚平台，Android）

用官方 **地图导航 SDK 1.5.2**（`Map_1.5.2.jar`）+ **开放 SDK**（`dsdk-Normal-release.aar`）
重建的迎宾机器人导航 App：WebView(H5) 界面 + 原生能力桥接 + RPLIDAR A1M8 避障 +
ROS2 桥接服务。

## 目录

```
app/src/main/java/com/qihancloud/frontnavigation/
├── App.java                    容错加载 libMapHelper.so（缺失时回退纯 Java）
├── core/BotController.java     业务编排：导航 / 雷达 / 安全门 / 语音 / 桥接 / 后台
├── nav/NavGateway.java         MapClient 唯一持有者（阻塞接口投递到工作线程）
├── nav/MapRepository.java      地图+点位读取、Bitmap 渲染（native 优先）
├── nav/MapPixelConverter.java  MapHelper 的纯 Java 等价实现
├── nav/MsgText.java            状态码 -> 中文（源自 SDK 文档 1.5）
├── lidar/RpLidarA1.java        RPLIDAR A1M8 协议驱动（0xA5 0x20 扫描帧解析）
├── lidar/LidarSession.java     USB 权限 / 打开串口 / 断线重连
├── lidar/ObstacleGuard.java    前后扇区判定 + 迟滞，输出 通畅/减速/停车
├── chassis/ChassisAdapter.java 底盘抽象（真机 / 仿真）
├── chassis/NavChassisAdapter.java  用导航 SDK 实现，速度用虚拟目标点近似
├── chassis/SimChassisAdapter.java  无机器人时的差速轮仿真
├── net/BridgeServer.java       TCP 9001，行 JSON，与 ROS2 通信
├── net/BackendClient.java      运营后台瘦客户端（失败静默降级）
├── speech/SpeechHelper.java    SpeechManager 播报（节流+去重）
├── ui/MainActivity.java        TopBaseActivity + WebView
├── ui/NavReceiver.java         com.sanbot.action.navigation 广播入口
└── web/JsBridge.java           H5 <-> 原生唯一出口
```

依赖库已就位：`app/libs/{Map_1.5.2.jar, dsdk-Normal-release.aar, armeabi*/libMapHelper.so}`
（从 `20191118/navigationSDK/navigation/libs` 复制）。

## 编译

需要 Android SDK 27 + JDK 8 + Gradle。用 Android Studio 直接打开 `robot-app/`，
或命令行：

```bash
cd robot-app
# 本机若无 gradle wrapper，可用 Android Studio 自带的 gradle，或先 gradle wrapper
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。安装前需与机器人系统签名一致，
真机上一般用 `adb install -r` 即可（Sanbot 系统允许调试安装）。

## 运行

1. 装上后启动「激光导航」。若提示没有地图，点 H5 上的「建图 App」跳到系统自带的
   建图界面（`com.qihancloud.qh_ros_service`）先建图、标点位。
2. 地图与点位来自系统服务，本 App 只读（`MapClient.getMap()`），不负责 SLAM 算法。
3. 雷达插上 USB 后 H5 右上角会显示 `雷达：x.x Hz`；若显示 `--`，看 `adb logcat -s LidarSession`。
4. 语音说「带我去会议室」，或点 H5 左侧点位列表。

## 与 ROS2 的对接

`BridgeServer` 默认监听 9001（被占用会自动 +1，日志里有实际端口）：

```bash
# 上位机测试连通
printf '{"cmd":"status"}\n' | nc <机器人IP> 9001
```

协议与速率限制见 `ros2_ws/README.md` 与 `docs/还原说明.md`。

## 已知边界

- **建图不在本 App 内**：报告里 SLAM 是 ROS2 + slam_toolbox 跑的，机器人本体只消费
  x86 板产出的地图。想全流程自研，需要把 SLAM 挪到 Android 或另做起图服务。
- **速度环是近似的**：导航 SDK 没有 m/s 接口，`drive()` 用虚拟目标点近似；
  真机上若需要更硬的响应，应改接 `WheelMotionManager.doNoAngleMotion(...)`
  （构造签名需按实际 dsdk 版本核对，见 `NavChassisAdapter` 注释）。
- `libMapHelper.so` 只有 32 位 ARM；在 64 位设备/模拟器上会自动走纯 Java 实现，
  颜色映射以 SDK 文档 1.5 为准，未与 native 版本逐像素比对。
