"""ROS2 与机器人 Android 端 TCP 桥之间的翻译节点。

这是整条链路最关键、也是最容易踩坑的一环（原项目 60% 的调试时间就耗在这里）：

1. 单位不是米。地图导航 SDK 的 ``getPose()`` 返回的是**地图像素坐标**，
   真实长度 = 像素 × ``map.getResolution()``（典型 0.05 m/px）。直接当米用，
   机器人会“走到目标点附近但永远差 20 倍”，表现为导航瞬间结束或乱窜。
2. 图像坐标 y 轴向下。ROS 为右手系（y 向左），因此需要 ``invert_y`` 取负，
   角度同时取反。
3. SDK 没有真正的中断式回调，状态只有 1Hz 推送，所以 /cmd_vel 要做
   看门狗：500ms 没收到新指令就主动下发 stop，避免“上位机崩了机器人还在走”。

协议见 robot-app 的 ``BridgeServer.java``。
"""

import json
import math
import socket
import threading

import rclpy
from geometry_msgs.msg import PoseStamped, TransformStamped, Twist
from nav_msgs.msg import Odometry
from rclpy.node import Node
from std_msgs.msg import String
from tf2_ros import TransformBroadcaster


def yaw_to_quaternion(yaw):
    return math.sin(yaw / 2.0), math.cos(yaw / 2.0)


class ChassisBridge(Node):

    def __init__(self):
        super().__init__('chassis_bridge')

        self.declare_parameter('robot_host', '192.168.1.20')
        self.declare_parameter('robot_port', 9001)
        self.declare_parameter('map_resolution', 0.05)
        self.declare_parameter('invert_y', True)
        self.declare_parameter('max_linear', 0.35)
        self.declare_parameter('max_angular', 0.9)
        self.declare_parameter('cmd_timeout', 0.5)
        self.declare_parameter('publish_tf', True)
        self.declare_parameter('frame_odom', 'odom')
        self.declare_parameter('frame_base', 'base_link')

        self.host = self.get_parameter('robot_host').value
        self.port = int(self.get_parameter('robot_port').value)
        self.resolution = float(self.get_parameter('map_resolution').value)
        self.invert_y = bool(self.get_parameter('invert_y').value)
        self.max_linear = float(self.get_parameter('max_linear').value)
        self.max_angular = float(self.get_parameter('max_angular').value)
        self.cmd_timeout = float(self.get_parameter('cmd_timeout').value)
        self.publish_tf = bool(self.get_parameter('publish_tf').value)
        self.frame_odom = self.get_parameter('frame_odom').value
        self.frame_base = self.get_parameter('frame_base').value

        self._sock = None
        self._sock_lock = threading.Lock()
        self._connected = False
        self._last_status = {}
        self._last_cmd_at = 0.0
        self._last_sent_vel = (0.0, 0.0)
        self._running = True

        self.create_subscription(Twist, 'cmd_vel_raw', self.on_vel, 10)
        self.create_subscription(PoseStamped, 'sanbot/goto_pose', self.on_goto_pose, 10)
        self.create_subscription(String, 'sanbot/tts', self.on_tts, 10)

        self.pub_odom = self.create_publisher(Odometry, 'odom', 10)
        self.pub_status = self.create_publisher(String, 'sanbot/status', 10)
        self.tf_broadcaster = TransformBroadcaster(self)

        self.create_timer(0.2, self.on_timer)
        self.create_timer(1.0, self.publish_status)

        threading.Thread(target=self._connect_loop, daemon=True).start()
        self.get_logger().info(
            f'底盘桥接启动 -> {self.host}:{self.port}，'
            f'分辨率 {self.resolution} m/px，invert_y={self.invert_y}')

    # ------------------------------------------------------------ TCP 连接

    def _connect_loop(self):
        while self._running and rclpy.ok():
            if self._sock is None:
                try:
                    sock = socket.create_connection((self.host, self.port), timeout=3.0)
                    sock.settimeout(None)
                    with self._sock_lock:
                        self._sock = sock
                    self._connected = True
                    self.get_logger().info('已连接机器人桥接服务')
                    threading.Thread(target=self._reader_loop, args=(sock,), daemon=True).start()
                except OSError as exc:
                    self.get_logger().warning(f'连接机器人失败: {exc}', throttle_duration_sec=10.0)
            threading.Event().wait(2.0)

    def _reader_loop(self, sock):
        buffer = b''
        try:
            while self._running:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                buffer += chunk
                while b'\n' in buffer:
                    line, buffer = buffer.split(b'\n', 1)
                    self._handle_status_line(line.decode('utf-8', 'ignore'))
        except OSError:
            pass
        finally:
            self._connected = False
            with self._sock_lock:
                self._sock = None
            self.get_logger().warning('与机器人的连接断开，2 秒后重连')

    def _handle_status_line(self, line):
        line = line.strip()
        if not line.startswith('{'):
            return
        try:
            data = json.loads(line)
        except ValueError:
            return
        if 'pose' not in data:
            return
        self._last_status = data
        self._publish_odom(data)

    def _send(self, payload):
        with self._sock_lock:
            sock = self._sock
        if sock is None:
            return False
        try:
            sock.sendall((json.dumps(payload, ensure_ascii=False) + '\n').encode('utf-8'))
            return True
        except OSError:
            self._connected = False
            return False

    # ------------------------------------------------------------ 位姿发布

    def _publish_odom(self, data):
        pose = data.get('pose') or [0.0, 0.0, 0.0]
        res = self.resolution
        x = float(pose[0]) * res
        y_raw = float(pose[1]) * res
        y = -y_raw if self.invert_y else y_raw
        yaw_sdk = float(pose[2])
        yaw = -yaw_sdk if self.invert_y else yaw_sdk

        stamp = self.get_clock().now().to_msg()

        odom = Odometry()
        odom.header.stamp = stamp
        odom.header.frame_id = self.frame_odom
        odom.child_frame_id = self.frame_base
        odom.pose.pose.position.x = x
        odom.pose.pose.position.y = y
        qz, qw = yaw_to_quaternion(yaw)
        odom.pose.pose.orientation.z = qz
        odom.pose.pose.orientation.w = qw
        # 借用 SDK 定位精度不高的先验：给一个中等量级的协方差
        odom.pose.covariance[0] = 0.05
        odom.pose.covariance[7] = 0.05
        odom.pose.covariance[35] = 0.02
        nav = data.get('nav') or {}
        speed = nav.get('speed', 0.0) if isinstance(nav, dict) else 0.0
        odom.twist.twist.linear.x = float(speed)
        self.pub_odom.publish(odom)

        if self.publish_tf:
            tf = TransformStamped()
            tf.header.stamp = stamp
            tf.header.frame_id = self.frame_odom
            tf.child_frame_id = self.frame_base
            tf.transform.translation.x = x
            tf.transform.translation.y = y
            tf.transform.rotation.z = qz
            tf.transform.rotation.w = qw
            self.tf_broadcaster.sendTransform(tf)

    def publish_status(self):
        msg = String()
        msg.data = json.dumps({
            'connected': self._connected,
            'pose_px': (self._last_status.get('pose') or [0, 0, 0]),
            'nav': self._last_status.get('nav'),
            'lidar': self._last_status.get('lidar'),
        }, ensure_ascii=False)
        self.pub_status.publish(msg)

    # ------------------------------------------------------------ 指令

    def on_vel(self, msg: Twist):
        self._last_cmd_at = self.get_clock().now().nanoseconds / 1e9
        linear = max(-self.max_linear, min(self.max_linear, msg.linear.x))
        angular = max(-self.max_angular, min(self.max_angular, msg.angular.z))
        if not self._connected:
            return
        if abs(linear) < 1e-3 and abs(angular) < 1e-3:
            if self._last_sent_vel != (0.0, 0.0):
                self._send({'cmd': 'stop'})
                self._last_sent_vel = (0.0, 0.0)
            return
        self._send({'cmd': 'vel', 'linear': linear, 'angular': angular})
        self._last_sent_vel = (linear, angular)

    def on_goto_pose(self, msg: PoseStamped):
        """米制目标点 -> SDK 像素目标点。"""
        res = self.resolution
        px = msg.pose.position.x / res
        py_raw = msg.pose.position.y / res
        py = -py_raw if self.invert_y else py_raw
        q = msg.pose.orientation
        yaw = 2.0 * math.atan2(q.z, q.w)
        yaw_sdk = -yaw if self.invert_y else yaw
        self._send({'cmd': 'goto', 'x': px, 'y': py, 'rad': yaw_sdk})

    def on_tts(self, msg: String):
        if msg.data:
            self._send({'cmd': 'speak', 'text': msg.data})

    def on_timer(self):
        now = self.get_clock().now().nanoseconds / 1e9
        if self._last_cmd_at > 0 and now - self._last_cmd_at > self.cmd_timeout:
            if self._last_sent_vel != (0.0, 0.0):
                self.get_logger().warning('cmd_vel 超时，主动刹车')
                self._send({'cmd': 'stop'})
                self._last_sent_vel = (0.0, 0.0)

    def destroy_node(self):
        self._running = False
        with self._sock_lock:
            if self._sock is not None:
                try:
                    self._sock.close()
                except OSError:
                    pass
                self._sock = None
        super().destroy_node()


def main(args=None):
    rclpy.init(args=args)
    node = ChassisBridge()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == '__main__':
    main()
