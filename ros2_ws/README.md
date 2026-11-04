# ROS2 侧（Jazzy，运行在 VMware Ubuntu 24.04）

对应 PPT 的「感知 → 建图 → 导航 → 执行」：

```
RPLIDAR A1M8 ──USB直通──> rplidar_node ──/scan──> slam_toolbox ──/map──> nav2
                                                        │                  │
                                                   /odom + TF          /cmd_vel
                                                        │                  │
                                              chassis_bridge <── /cmd_vel_raw ── scan_safety
                                                        │
                                              TCP 9001 JSON（WiFi）
                                                        │
                                            机器人 Android（Map_1.5.2 SDK）
```

## 目录

| 包 | 作用 |
|---|---|
| `rplidar_bringup` | 雷达节点参数、启动、udev 规则（固定 `/dev/rplidar`） |
| `sanbot_nav/chassis_bridge` | ROS2 ↔ 机器人 TCP 桥：`/cmd_vel_raw` → 底盘，位姿 → `/odom` + TF |
| `sanbot_nav/scan_safety` | 激光安全闸：`/cmd_vel` → `/cmd_vel_raw`，急停/限速 |
| `sanbot_nav/waypoint_manager` | 命名点位导航（前台/会议室/充电点…），到点语音播报 |

## 编译

```bash
cd ~/ros2_ws
sudo apt install ros-$ROS_DISTRO-slam-toolbox ros-$ROS_DISTRO-navigation2 \
                 ros-$ROS_DISTRO-nav2-bringup ros-$ROS_DISTRO-rplidar-ros
rosdep install --from-paths src -i -y
colcon build --symlink-install
source install/setup.bash
```

## 建图

```bash
ros2 launch sanbot_nav mapping.launch.py robot_host:=<机器人IP>
# 推着/遥控机器人走一圈，rviz 里看 /map 逐渐铺满
ros2 service call /slam_toolbox/save_map slam_toolbox/srv/SaveMap \
  "{name: {data: /home/robot/maps/hotel_map}}"
```

保存后会得到 `hotel_map.posegraph` 与 `hotel_map.data`。
如果要给 nav2 的 `map_server` 用，还需要 `.pgm` + `.yaml`：

```bash
ros2 run nav2_map_server map_saver_cli -f /home/robot/maps/hotel_map
```

## 导航

```bash
ros2 launch sanbot_nav navigation.launch.py \
  robot_host:=<机器人IP> map:=/home/robot/maps/hotel_map.yaml

# 语音/触屏之外，命令行下发点位
ros2 topic pub --once /sanbot/goal std_msgs/String "{data: 会议室}"
ros2 topic echo /sanbot/waypoint_state
```

## 三个容易踩的坑（当年就在这里卡了 60% 的时间）

1. **单位**：`MapClient.getPose()` 返回像素，不是米。`map_resolution`（默认 0.05 m/px）
   必须和机器人地图 `map.getResolution()` 一致，否则目标点差 20 倍。
2. **y 轴**：图像坐标 y 向下，ROS 右手系 y 向左，`invert_y:=true` 负责取负并反转角。
3. **看门狗**：SDK 只有 1Hz 状态推送，上位机一断，机器人会保持最后一条速度指令
   继续走。`chassis_bridge` 的 `cmd_timeout`（默认 0.5s）会主动发 stop。

## 与 Android 端的一致性

- `config/waypoints.yaml` 里的名字必须与机器人地图 App 里标注的点位名字一致，
  否则语音说“去会议室”时，ROS2 侧和 Android 侧会指向两个不同位置。
- TCP 端口固定 9001（Android 侧 `BridgeServer` 若被占用会自动 +1，日志里能看到实际端口）。
