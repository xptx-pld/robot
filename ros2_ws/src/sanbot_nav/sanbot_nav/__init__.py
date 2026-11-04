"""Sanbot S1 导航相关节点。

模块划分（对应 PPT 的「感知 → 建图 → 导航 → 执行」闭环）：

- chassis_bridge：ROS2 与机器人 Android 端 TCP 桥（9001）之间的翻译层，
  负责 /cmd_vel -> 底盘、底盘位姿 -> /odom + TF。
- scan_safety：激光安全闸，位于 nav2 控制器与底盘之间，做急停/限速。
- waypoint_manager：命名点位导航（前台/会议室/充电点…），到点播报。
"""
