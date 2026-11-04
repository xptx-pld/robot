"""命名点位导航。

对应 PPT 的「在地图上标记前台 / 办公室 / 会议室 / 卫生间 / 充电点，
导航目标一键下发，名称精确匹配」。

用法：
    ros2 topic pub --once /sanbot/goal std_msgs/String "{data: 会议室}"

点位的坐标是 **map 坐标系下的米**，和 Android 端 SDK 的像素坐标不是一回事；
像素换算只在 chassis_bridge 里做。
"""

import json
import math
import os

import rclpy
import yaml
from geometry_msgs.msg import PoseStamped
from nav2_msgs.action import NavigateToPose
from rclpy.action import ActionClient
from rclpy.node import Node
from std_msgs.msg import String


class WaypointManager(Node):

    def __init__(self):
        super().__init__('waypoint_manager')

        self.declare_parameter('waypoints_file', '')
        self.declare_parameter('frame_id', 'map')
        self.declare_parameter('action_name', 'navigate_to_pose')
        self.declare_parameter('speak', True)

        self.frame_id = self.get_parameter('frame_id').value
        self.speak_enabled = bool(self.get_parameter('speak').value)
        self.waypoints = self._load_waypoints(self.get_parameter('waypoints_file').value)

        self.client = ActionClient(self, NavigateToPose,
                                   self.get_parameter('action_name').value)
        self.pub_state = self.create_publisher(String, 'sanbot/waypoint_state', 10)
        self.pub_tts = self.create_publisher(String, 'sanbot/tts', 10)
        self.create_subscription(String, 'sanbot/goal', self.on_goal, 10)

        self.current_task = None
        self.get_logger().info(f'已加载 {len(self.waypoints)} 个点位: '
                               f'{", ".join(self.waypoints.keys())}')

    # ------------------------------------------------------------ 点位表

    def _load_waypoints(self, path):
        result = {}
        if not path or not os.path.exists(path):
            self.get_logger().warning(f'点位文件不存在: {path}')
            return result
        with open(path, 'r', encoding='utf-8') as handle:
            data = yaml.safe_load(handle) or {}
        for item in data.get('waypoints', []):
            name = str(item.get('name', '')).strip()
            if not name:
                continue
            result[name] = (float(item['x']), float(item['y']),
                            float(item.get('yaw', 0.0)))
        return result

    def lookup(self, name):
        if name in self.waypoints:
            return name, self.waypoints[name]
        # 名称精确匹配优先，其次做包含匹配（口头指令常带“带我去会议室”）
        for key in self.waypoints:
            if name and (key in name or name in key):
                return key, self.waypoints[key]
        return None, None

    # ------------------------------------------------------------ 目标

    def on_goal(self, msg: String):
        requested = msg.data.strip()
        if not requested:
            return
        if requested in ('取消', 'cancel', '停止'):
            self.cancel()
            return
        name, pose = self.lookup(requested)
        if name is None:
            self.get_logger().warning(f'没有点位: {requested}')
            self.publish_state('unknown', requested, -1)
            self.speak(f'没有找到{requested}')
            return
        if not self.client.wait_for_server(timeout_sec=3.0):
            self.get_logger().error('navigate_to_pose 动作服务器不可用')
            self.speak('导航服务还没准备好')
            return

        goal = NavigateToPose.Goal()
        goal.pose = self.to_pose(pose)
        self.current_task = name
        self.publish_state('sending', name, 0)
        self.get_logger().info(f'前往 {name} ({pose[0]:.2f}, {pose[1]:.2f})')
        self.speak(f'请跟我来，我将带你去{name}')
        send_future = self.client.send_goal_async(goal, feedback_callback=self.on_feedback)
        send_future.add_done_callback(lambda future: self.on_goal_response(future, name))

    def to_pose(self, pose):
        x, y, yaw = pose
        msg = PoseStamped()
        msg.header.frame_id = self.frame_id
        msg.header.stamp = self.get_clock().now().to_msg()
        msg.pose.position.x = x
        msg.pose.position.y = y
        msg.pose.orientation.z = math.sin(yaw / 2.0)
        msg.pose.orientation.w = math.cos(yaw / 2.0)
        return msg

    def on_goal_response(self, future, name):
        handle = future.result()
        if not handle or not handle.accepted:
            self.get_logger().warning(f'{name} 目标被拒绝')
            self.publish_state('rejected', name, -1)
            self.speak('导航目标被拒绝，请检查点位是否在障碍物里')
            return
        self.publish_state('running', name, 0)
        handle.get_result_async().add_done_callback(
            lambda result: self.on_result(result, name))

    def on_feedback(self, feedback_msg):
        fb = feedback_msg.feedback
        dist = getattr(fb, 'distance_remaining', -1.0)
        self.publish_state('running', self.current_task or '', dist)

    def on_result(self, future, name):
        status = future.result().status
        # action_msgs/GoalStatus: 4=SUCCEEDED 5=CANCELED 6=ABORTED
        if status == 4:
            self.get_logger().info(f'已到达 {name}')
            self.publish_state('arrived', name, 0.0)
            self.speak(f'已到达{name}')
        elif status == 5:
            self.publish_state('canceled', name, 0.0)
        else:
            self.get_logger().warning(f'{name} 导航失败，status={status}')
            self.publish_state('failed', name, -1.0)
            self.speak('没能到达目的地，请让一让再试一次')
        self.current_task = None

    def cancel(self):
        self.get_logger().info('取消当前导航')
        self.client.cancel_all_goals_async()
        self.publish_state('canceled', self.current_task or '', 0.0)
        self.current_task = None

    # ------------------------------------------------------------ 辅助

    def publish_state(self, state, name, distance):
        msg = String()
        msg.data = json.dumps({'state': state, 'name': name,
                               'distance': distance}, ensure_ascii=False)
        self.pub_state.publish(msg)

    def speak(self, text):
        if not self.speak_enabled:
            return
        msg = String()
        msg.data = text
        self.pub_tts.publish(msg)


def main(args=None):
    rclpy.init(args=args)
    node = WaypointManager()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == '__main__':
    main()
