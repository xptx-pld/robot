"""激光安全闸。

位置：nav2 控制器 -> /cmd_vel -> 本节点 -> /cmd_vel_raw -> chassis_bridge。

为什么不让 nav2 自己做：nav2 的 costmap 依赖地图与定位，一旦定位漂了或者
地图上没有行人，局部避障就会“看不到”。这层用原始 /scan 直接判前后左右，
属于最后一公里兜底，逻辑简单、可解释，出问题一眼能看出来。
"""

import math

import rclpy
from geometry_msgs.msg import Twist
from rclpy.node import Node
from sensor_msgs.msg import LaserScan
from std_msgs.msg import String

CLEAR = 0
SLOW = 1
STOP = 2


class ScanSafety(Node):

    def __init__(self):
        super().__init__('scan_safety')

        self.declare_parameter('stop_distance', 0.45)
        self.declare_parameter('slow_distance', 0.90)
        self.declare_parameter('front_half_deg', 30.0)
        self.declare_parameter('release_ratio', 1.35)
        self.declare_parameter('max_linear', 0.35)
        self.declare_parameter('slow_scale', 0.45)
        self.declare_parameter('speak', True)

        self.stop_distance = float(self.get_parameter('stop_distance').value)
        self.slow_distance = float(self.get_parameter('slow_distance').value)
        self.front_half = float(self.get_parameter('front_half_deg').value)
        self.release_ratio = float(self.get_parameter('release_ratio').value)
        self.max_linear = float(self.get_parameter('max_linear').value)
        self.slow_scale = float(self.get_parameter('slow_scale').value)
        self.speak_enabled = bool(self.get_parameter('speak').value)

        self.state = CLEAR
        self.front = float('inf')
        self.last_cmd = Twist()

        self.create_subscription(LaserScan, 'scan', self.on_scan, 10)
        self.create_subscription(Twist, 'cmd_vel', self.on_cmd, 10)
        self.pub_vel = self.create_publisher(Twist, 'cmd_vel_raw', 10)
        self.pub_state = self.create_publisher(String, 'sanbot/safety', 10)
        self.pub_tts = self.create_publisher(String, 'sanbot/tts', 10)

        self.create_timer(0.05, self.on_timer)
        self.get_logger().info(
            f'激光安全闸启动：停车 {self.stop_distance}m / 减速 {self.slow_distance}m')

    # ------------------------------------------------------------ 输入

    def on_scan(self, msg: LaserScan):
        half = math.radians(self.front_half)
        best = float('inf')
        angle = msg.angle_min
        for r in msg.ranges:
            if msg.range_min <= r <= msg.range_max:
                if -half <= angle <= half:
                    best = min(best, r)
            angle += msg.angle_increment
        self.front = best

        previous = self.state
        if best <= self.stop_distance:
            self.state = STOP
        elif best <= self.slow_distance:
            self.state = SLOW
        else:
            self.state = CLEAR
        # 退出停车状态要更宽松，避免在阈值附近抖动
        if previous == STOP and best < self.stop_distance * self.release_ratio:
            self.state = STOP
        if previous == SLOW and self.state == CLEAR and best < self.slow_distance * 1.05:
            self.state = SLOW

        if self.state != previous:
            self.notify(previous)

    def on_cmd(self, msg: Twist):
        self.last_cmd = msg

    def notify(self, previous):
        text = {
            CLEAR: '障碍已清除，继续通行',
            SLOW: '前方有人，减速通行',
            STOP: '前方有障碍，我先停一下',
        }[self.state]
        self.get_logger().info(
            f'安全状态 {previous} -> {self.state}（前方 {self.front:.2f}m）：{text}')
        state_msg = String()
        state_msg.data = f'{self.state}:{self.front:.3f}'
        self.pub_state.publish(state_msg)
        if self.speak_enabled and self.state == STOP:
            tts = String()
            tts.data = text
            self.pub_tts.publish(tts)

    # ------------------------------------------------------------ 输出

    def on_timer(self):
        out = Twist()
        if self.state == STOP:
            # 连续下发 0，让底盘彻底停住
            self.pub_vel.publish(out)
            return
        linear = self.last_cmd.linear.x
        if self.state == SLOW:
            linear *= self.slow_scale
        linear = max(-self.max_linear, min(self.max_linear, linear))
        out.linear.x = linear
        out.angular.z = self.last_cmd.angular.z
        self.pub_vel.publish(out)


def main(args=None):
    rclpy.init(args=args)
    node = ScanSafety()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == '__main__':
    main()
