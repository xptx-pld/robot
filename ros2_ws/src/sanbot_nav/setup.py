import os
from glob import glob

from setuptools import setup

package_name = 'sanbot_nav'

setup(
    name=package_name,
    version='1.0.0',
    packages=[package_name],
    data_files=[
        ('share/ament_index/resource_index/packages', ['resource/' + package_name]),
        (os.path.join('share', package_name), ['package.xml']),
        (os.path.join('share', package_name, 'launch'), glob('launch/*.launch.py')),
        (os.path.join('share', package_name, 'config'), glob('config/*.yaml')),
    ],
    install_requires=['setuptools'],
    zip_safe=True,
    maintainer='restored',
    maintainer_email='dev@example.com',
    description='Sanbot S1 底盘桥接 / 激光安全闸 / 点位导航',
    license='Apache-2.0',
    entry_points={
        'console_scripts': [
            'chassis_bridge = sanbot_nav.chassis_bridge:main',
            'scan_safety = sanbot_nav.scan_safety:main',
            'waypoint_manager = sanbot_nav.waypoint_manager:main',
        ],
    },
)
