# （历史）robot-recon：仅凭交互元数据做的 Python 重建骨架

> 本文原为仓库根 `README.md`。它描述的是**第一阶段**的重建产物：
> 当时只有 322 轮交互的元数据，没有硬件型号与协议，因此做的是一个
> 「安全优先、可仿真运行」的差速轮控制骨架（`src/robot_recon/`），
> 协议部分是明确标注的占位实现。
>
> 后来拿到了方案报告、项目介绍 PPT、Sanbot 导航 SDK 与 RPLIDAR SDK，
> 重建据此改为按实物材料进行，见根目录 `README.md` 与 `docs/还原说明.md`。
> 当时的调查材料（`recovery/`）是服务器侧取证记录，不属于源码，未上传本仓库。

---

一个**安全优先、可仿真运行**的机器人控制重建骨架。

> 原始源码没有被任何服务器日志保留下来。本项目不是原项目的逐行复原，而是根据
> 架构线索完成的**架构级等价重建**。硬件型号、通信协议和业务动作仍属于
> 待确认项（G1–G6），见 [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md)。

## 当前可交付

- 分层架构：业务/控制层与设备层通过稳定接口解耦。
- 差速轮运动学：线速度/角速度与左右轮速度互换、航迹积分。
- 安全监督：速度限幅、加速度限幅、指令超时停车、急停锁存。
- 闭环航点控制：P 控制、到点容差、超时保护。
- 确定性仿真器：无需机器人即可执行完整任务并复现测试。
- 串口设备适配器：带长度与 CRC 校验的帧、可替换 codec。
- CLI：自检、仿真任务、串口只读检查。
- 单元/集成测试：无第三方运行时依赖。

## 快速开始

要求 Python 3.11+。

```bash
python -m pip install -e .
python -m pytest
python -m robot_recon selftest
python -m robot_recon simulate --scenario square --duration 30
```

Windows PowerShell 中也可直接使用：

```powershell
$env:PYTHONPATH = "src"
python -m robot_recon simulate --scenario square
```

预期结果：仿真机器人依次抵达正方形四个航点并自动停车。

## 目录

```text
src/robot_recon/
  domain.py              # 值对象、限制与遥测模型
  config.py              # TOML 配置
  kinematics.py          # 差速轮运动学
  control/
    supervisor.py        # 安全监督与指令看门狗
    path.py              # 航点闭环控制
    runner.py            # 可测试的任务循环
  hal/
    base.py              # 设备接口协议
    simulator.py         # 仿真设备
    framing.py           # 帧与 CRC
    protocol.py          # 可替换的串口 codec
    serial_device.py     # pyserial 设备适配器
  cli.py                 # 命令行入口
config/robot.example.toml
docs/
  ARCHITECTURE.md
  OPEN_QUESTIONS.md
  PROTOCOL.md
tests/
```

## 仿真

```bash
python -m robot_recon simulate --scenario straight --duration 10
python -m robot_recon simulate --scenario square --duration 30
python -m robot_recon simulate --scenario turn --duration 10
```

仿真器是确定性的，不使用系统墙钟推进物理状态；同一输入会得到同一结果。

## 串口模式（重要安全说明）

默认串口命令只连接、读取遥测并发送停车指令，不驱动电机：

```bash
python -m pip install -e ".[serial]"
python -m robot_recon serial --port COM3 --baudrate 115200 --duration 3
```

只有明确传入 `--allow-motion` 才会发送运动指令：

```bash
python -m robot_recon serial --port COM3 --allow-motion --linear 0.05 --duration 2
```

`config/robot.example.toml` 中的协议实现是**待真机验证的占位协议**，不是从原始项目中
恢复出来的事实。接入真机前必须完成 [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md)
中的 G1–G6，并按 [`docs/PROTOCOL.md`](../PROTOCOL.md) 用真实硬件协议替换 codec。
首次测试应架空车轮、限制速度、准备物理急停。

## 安全边界

本项目在拿到真机前可以验证控制逻辑，但不能证明以下事项：

- 电气电平、串口接线和供电安全；
- 真实舵机/电机驱动器协议；
- 控制周期与时序是否满足硬件要求；
- 真实传感器方向、轮径、轮距与符号约定；
- 机械限位、碰撞风险和急停链路。

因此默认不自动运动，急停必须由上层显式复位，不能靠“重新发速度”解除。
