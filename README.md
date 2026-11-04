# 基于激光雷达的自主导航迎宾机器人（源码还原）

Sanbot S1 迎宾服务机器人二次开发项目：**激光 SLAM 建图 · 自主行走导航 · 动态避障 · 语音交互 · 运营后台**。

原始源码随开发机损坏丢失。本仓库依据留存的实物材料重建：

- 《作品方案报告》《项目介绍 PPT》（功能与架构描述）
- `20191118/navigationSDK`（旗瀚 Sanbot 地图导航 SDK 1.5.2 + 开放 SDK + demo 源码 + 协议文档）
- `雷达SDK`（SLAMTEC RPLIDAR A1M8 数据手册与使用手册）

重建依据、证据分级、已确认与未确认项见 **[docs/还原说明.md](docs/还原说明.md)**。

---

## 系统结构

```
┌──────────────────── Android（Sanbot S1 本体）────────────────────┐
│  H5 控制台（WebView）  ←→  JsBridge                              │
│  BotController                                                   │
│    ├─ NavGateway ── Map_1.5.2.jar ── 系统地图/导航服务（建图、规划）│
│    ├─ MapRepository（pgm 地图 + 点位渲染，JNI/纯 Java 双实现）      │
│    ├─ RpLidarA1 + ObstacleGuard（USB 雷达、激光安全闸）            │
│    ├─ ChassisAdapter（真机 / 仿真）                               │
│    ├─ SpeechHelper（TTS 播报）                                    │
│    └─ BridgeServer :9001（行 JSON，给 ROS2 用）                   │
└───────────────────────────┬──────────────────────────────────────┘
                            │ WiFi
┌───────────────────────────┴──── ROS2 Jazzy（Ubuntu 24.04 / VM）──┐
│  chassis_bridge   /cmd_vel_raw ↔ 底盘；位姿 → /odom + TF          │
│  scan_safety      /scan 兜底急停与限速                            │
│  slam_toolbox     mapping / localization                          │
│  nav2             NavFn 全局 + DWB 局部                           │
│  waypoint_manager 命名点位 → NavigateToPose，到点播报              │
└──────────────────────────────────────────────────────────────────┘

┌──────────────── 运营后台（Node.js，零第三方依赖）───────────────┐
│  点位 / 导航任务 / 访客台账 / 知识库问答 / 数据看板 / xlsx 导出     │
└──────────────────────────────────────────────────────────────────┘
```

## 目录

| 路径 | 说明 |
|---|---|
| `robot-app/` | 机器人端 Android 工程（Java + WebView H5），详见 [robot-app/README.md](robot-app/README.md) |
| `ros2_ws/` | ROS2 包：雷达 bringup、底盘桥接、激光安全闸、点位导航、slam/nav2 配置 |
| `backend/` | Node.js 零依赖后台（REST + 手写 xlsx），含冒烟测试 |
| `src/robot_recon/` | 第一阶段仅凭交互元数据做的 Python 控制骨架（历史产物，见 [docs/robot-recon-skeleton.md](docs/robot-recon-skeleton.md)） |
| `docs/` | 还原说明、架构、协议、待确认问题 |

## 快速开始

### 后台（可直接跑，已验证）

```bash
cd backend
node server.js            # http://localhost:8080
node test/smoke.js        # 13 项冒烟断言
```

### 机器人端

```bash
cd robot-app
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

依赖库位于 `robot-app/app/libs/`（导航 SDK 1.5.2、开放 SDK aar、`libMapHelper.so`）。
无机器人时可在 H5 界面点「仿真模式」验证交互与状态机。

### ROS2 侧

```bash
cd ros2_ws
colcon build --symlink-install && source install/setup.bash
ros2 launch sanbot_nav mapping.launch.py robot_host:=<机器人IP>
ros2 launch sanbot_nav navigation.launch.py robot_host:=<机器人IP> map:=<地图.yaml>
```

细节见 [ros2_ws/README.md](ros2_ws/README.md)。

## 验证状态

| 部分 | 状态 |
|---|---|
| 后台 REST + xlsx | ✅ 已跑通（`node test/smoke.js` 全绿） |
| ROS2 节点 / YAML | ✅ 语法与配置解析通过（未在真 ROS2 环境运行） |
| Android 资源 / H5 | ✅ XML 解析、JS 语法检查通过 |
| Android Java 编译 | ⚠️ 未验证（需 Android SDK + JDK 8） |
| 真机 / 雷达 / 导航 | ⚠️ 未验证（无硬件；雷达 USB 供电待补齐） |

## 安全提示

上车前先验证「停得住」：H5 急停、雷达遮挡前方 45cm、拔掉上位机网线（看门狗 0.5s 刹车），
三条全过再调路径。默认参数偏向保守（线速度 ≤0.35 m/s、停车距离 0.45 m）。

## 许可

代码以 Apache-2.0 发布。`robot-app/app/libs/` 下的 Sanbot SDK 与 RPLIDAR 相关资料
版权归各自厂商所有，仅用于本项目的兼容性构建，不随本许可证授权。
