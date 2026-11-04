"""导航模式启动（定位 + 全局规划 + 局部避障 + 激光安全闸）。

节点关系：
    nav2 controller_server --/cmd_vel--> scan_safety --/cmd_vel_raw--> chassis_bridge
    chassis_bridge --/odom + TF--> slam_toolbox(localization) --/map--> nav2 global_costmap

用法：
    ros2 launch sanbot_nav navigation.launch.py \
        robot_host:=192.168.1.20 \
        map:=/home/robot/maps/hotel_map.yaml
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
    nav2_share = get_package_share_directory('nav2_bringup')

    slam_params = os.path.join(nav_share, 'config', 'slam_toolbox_localization.yaml')
    nav2_params = os.path.join(nav_share, 'config', 'nav2_params.yaml')
    waypoints_file = os.path.join(nav_share, 'config', 'waypoints.yaml')

    robot_host = LaunchConfiguration('robot_host')
    map_yaml = LaunchConfiguration('map')
    use_rviz = LaunchConfiguration('use_rviz')

    lidar = IncludeLaunchDescription(
        PythonLaunchDescriptionSource(os.path.join(lidar_share, 'launch', 'rplidar_a1.launch.py')),
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
            'max_linear': 0.35,
        }],
    )

    slam = Node(
        package='slam_toolbox',
        executable='async_slam_toolbox_node',
        name='slam_toolbox',
        output='screen',
        parameters=[slam_params, {'use_sim_time': False}],
    )

    safety = Node(
        package='sanbot_nav',
        executable='scan_safety',
        name='scan_safety',
        output='screen',
        parameters=[{
            'stop_distance': 0.45,
            'slow_distance': 0.90,
            'front_half_deg': 30.0,
            'max_linear': 0.35,
        }],
    )

    waypoints = Node(
        package='sanbot_nav',
        executable='waypoint_manager',
        name='waypoint_manager',
        output='screen',
        parameters=[{'waypoints_file': waypoints_file, 'speak': True}],
    )

    nav2 = IncludeLaunchDescription(
        PythonLaunchDescriptionSource(os.path.join(nav2_share, 'launch', 'bringup_launch.py')),
        launch_arguments={
            'slam': 'False',
            'map': map_yaml,
            'params_file': nav2_params,
            'use_sim_time': 'false',
            'autostart': 'True',
            'use_composition': 'False',
        }.items(),
    )

    rviz = Node(
        package='rviz2',
        executable='rviz2',
        name='rviz2',
        condition=IfCondition(use_rviz),
    )

    return LaunchDescription([
        DeclareLaunchArgument('robot_host', default_value='192.168.1.20'),
        DeclareLaunchArgument('map', default_value='/home/robot/maps/hotel_map.yaml',
                              description='建图产出的 yaml（map_server 使用）'),
        DeclareLaunchArgument('use_rviz', default_value='true'),
        lidar,
        bridge,
        TimerAction(period=3.0, actions=[slam]),
        TimerAction(period=4.0, actions=[safety]),
        # nav2 依赖 TF 与 map，晚一点起
        TimerAction(period=6.0, actions=[nav2]),
        TimerAction(period=8.0, actions=[waypoints]),
        rviz,
    ])
