"""建图模式启动。

链路：RPLIDAR A1M8 -> /scan -> slam_toolbox(mapping) -> /map
      底盘位姿 -> chassis_bridge -> /odom + TF(odom->base_link)

用法：
    ros2 launch sanbot_nav mapping.launch.py robot_host:=192.168.1.20
    # 保存地图（slam_toolbox 自带服务）
    ros2 service call /slam_toolbox/save_map slam_toolbox/srv/SaveMap "{name: {data: hotel_map}}"
"""

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, IncludeLaunchDescription, TimerAction
from launch.conditions import IfCondition
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node


def generate_launch_description():
    nav_share = get_package_share_directory('sanbot_nav')
    lidar_share = get_package_share_directory('rplidar_bringup')

    slam_params = os.path.join(nav_share, 'config', 'slam_toolbox_mapping.yaml')
    waypoints_file = os.path.join(nav_share, 'config', 'waypoints.yaml')

    robot_host = LaunchConfiguration('robot_host')
    use_rviz = LaunchConfiguration('use_rviz')

    lidar = IncludeLaunchDescription(
        PythonLaunchDescriptionSource(os.path.join(lidar_share, 'launch', 'rplidar_a1.launch.py')),
    )

    slam = Node(
        package='slam_toolbox',
        executable='async_slam_toolbox_node',
        name='slam_toolbox',
        output='screen',
        parameters=[slam_params, {'use_sim_time': False}],
    )

    bridge = Node(
        package='sanbot_nav',
        executable='chassis_bridge',
        name='chassis_bridge',
        output='screen',
        parameters=[{
            'robot_host': robot_host,
            'robot_port': 9001,
            'map_resolution': 0.05,
            'invert_y': True,
        }],
    )

    waypoints = Node(
        package='sanbot_nav',
        executable='waypoint_manager',
        name='waypoint_manager',
        output='screen',
        parameters=[{'waypoints_file': waypoints_file, 'speak': True}],
    )

    # 首次使用在 rviz2 里手动 Add -> Map(/map) 与 LaserScan(/scan)，保存为默认配置即可
    rviz = Node(
        package='rviz2',
        executable='rviz2',
        name='rviz2',
        condition=IfCondition(use_rviz),
    )

    return LaunchDescription([
        DeclareLaunchArgument('robot_host', default_value='192.168.1.20',
                              description='机器人（Android 端）的 IP'),
        DeclareLaunchArgument('use_rviz', default_value='true'),
        lidar,
        bridge,
        # 等 TF 与 /scan 稳定后再起 SLAM
        TimerAction(period=3.0, actions=[slam]),
        TimerAction(period=4.0, actions=[waypoints]),
        rviz,
    ])
