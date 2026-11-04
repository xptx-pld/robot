"""RPLIDAR A1M8 启动文件。

对应 PPT「激光雷达驱动 + /scan 数据输出（8.7Hz）」这一环。
USB 直通到 VMware 后设备名通常是 /dev/ttyUSB0，这里默认用 udev 别名 /dev/rplidar，
避免每次插拔后设备号变化导致启动失败（见 udev/99-rplidar.rules）。
"""

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, LogInfo
from launch.conditions import IfCondition
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node


def generate_launch_description():
    pkg_share = get_package_share_directory('rplidar_bringup')
    default_params = os.path.join(pkg_share, 'config', 'rplidar_a1.yaml')

    params_file = LaunchConfiguration('params_file')
    serial_port = LaunchConfiguration('serial_port')
    start_static_tf = LaunchConfiguration('start_static_tf')

    rplidar_node = Node(
        package='rplidar_ros',
        executable='rplidar_node',
        name='rplidar_node',
        output='screen',
        parameters=[
            params_file,
            {'serial_port': serial_port},
        ],
        remappings=[('scan', 'scan')],
    )

    # 雷达装在机器人胸口中心，相对 base_link 的静态外参（真机需按实测调整）
    static_tf = Node(
        package='tf2_ros',
        executable='static_transform_publisher',
        name='laser_tf',
        arguments=['0.0', '0.0', '0.55', '0.0', '0.0', '0.0', 'base_link', 'laser'],
        condition=IfCondition(start_static_tf),
    )

    return LaunchDescription([
        DeclareLaunchArgument('params_file', default_value=default_params,
                              description='雷达参数文件'),
        DeclareLaunchArgument('serial_port', default_value='/dev/rplidar',
                              description='雷达串口设备（USB 直通后通常是 /dev/ttyUSB0）'),
        DeclareLaunchArgument('start_static_tf', default_value='true',
                              description='是否发布 base_link -> laser 静态变换'),
        LogInfo(msg='启动 RPLIDAR A1M8，波特率 115200'),
        rplidar_node,
        static_tf,
    ])
