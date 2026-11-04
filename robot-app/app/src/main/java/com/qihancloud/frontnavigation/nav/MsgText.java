package com.qihancloud.frontnavigation.nav;

import com.sanbot.map.Msg;

/**
 * SDK 状态码到中文的映射。
 *
 * <p>数值与文案全部来自官方 SDK 文档 1.5 与 2019 版 demo 的 {@code ExplainCode.java}，
 * 还原时改成静态表，避免在 UI 层重复 switch。
 */
public final class MsgText {

    private MsgText() {
    }

    public static String ctrl(int ctrl) {
        switch (ctrl) {
            case Msg.CtrlState.FREE:
                return "空闲";
            case Msg.CtrlState.BUILD:
                return "建图中";
            case Msg.CtrlState.NAVIGATION:
                return "导航已开启";
            case Msg.CtrlState.WORK_3D:
                return "3D 作业中";
            default:
                return "未知状态(" + ctrl + ")";
        }
    }

    public static String navi(int navi) {
        switch (navi) {
            case Msg.NaviState.NO_NAVI:
                return "非导航状态";
            case Msg.NaviState.NO_DEFINE:
                return "未定义状态";
            case Msg.NaviState.NO_INIT:
                return "未指定初始位姿";
            case Msg.NaviState.INIT:
                return "已给定初始位姿";
            case Msg.NaviState.NAVI:
                return "导航中";
            case Msg.NaviState.ARRIVE:
                return "到达目标点";
            case Msg.NaviState.CANCEL:
                return "已取消导航";
            case Msg.NaviState.ERROR:
                return "导航程序异常，请重启机器人";
            case Msg.NaviState.TOO_CLOSE:
                return "目标点距障碍物太近";
            case Msg.NaviState.GLOBAL_PLAN_FAIL:
                return "全局规划失败";
            case Msg.NaviState.PLAN_IN_OBSTACLE:
                return "目标点在障碍物中";
            case Msg.NaviState.PLAN_ALL_BLOCK:
                return "目标点无法到达";
            case Msg.NaviState.PLAN_BLOCK_BY_PEOPLE:
                return "轨迹上有人阻挡，请让一让";
            case Msg.NaviState.ROBOT_BLOCK_BY_PEOPLE:
                return "机器人被围住";
            case Msg.NaviState.PAUSE_BY_BUTTON:
                return "导航暂停，请按绿色按键继续";
            case Msg.NaviState.NO_MAP:
                return "导航模块异常（没有地图数据）";
            case Msg.NaviState.FAIL_3D:
                return "导航模块异常（3D 模块启动失败）";
            case Msg.NaviState.WITHOUT_DATA_DRIVE:
                return "导航模块异常（底盘驱动无数据）";
            case Msg.NaviState.WITHOUT_DATA_DRIVE_3D:
                return "导航模块异常（底盘驱动与 3D 无数据）";
            case Msg.NaviState.WITHOUT_DATA_DRIVE_MAP:
                return "导航模块异常（底盘驱动与地图无数据）";
            case Msg.NaviState.WITHOUT_DATA_3D_DRIVE_MAP:
                return "导航模块异常（底盘、3D、地图均无数据）";
            case Msg.NaviState.INFRARED_OBSTACLE:
                return "底部红外避障";
            case Msg.NaviState.POSITIONING_ERROR:
                return "定位异常，需要重启";
            case Msg.NaviState.PLANING:
                return "重新规划路线中";
            case Msg.NaviState.PLAN_TIMEOUT_OBSTACLES:
                return "目标点在障碍物中，无法到达";
            case Msg.NaviState.PLAN_TIMEOUT_SELF_IN_OBSTACLES:
                return "当前定位在障碍物中";
            case Msg.NaviState.PLAN_TIMEOUT_NO_PATH:
                return "没有可以到达目标点的路线";
            case Msg.NaviState.PLAN_ERROR_SELF_IN_OBSTACLES:
                return "定位异常，在障碍物中";
            default:
                return "状态码 0x" + Integer.toHexString(navi);
        }
    }

    /** 出错后建议延时取消导航的时间（ms），0 表示无需自动取消。 */
    public static long cancelDelayMs(int navi) {
        switch (navi) {
            case Msg.NaviState.ERROR:
            case Msg.NaviState.GLOBAL_PLAN_FAIL:
            case Msg.NaviState.PLAN_ALL_BLOCK:
            case Msg.NaviState.NO_MAP:
            case Msg.NaviState.FAIL_3D:
            case Msg.NaviState.WITHOUT_DATA_DRIVE:
            case Msg.NaviState.WITHOUT_DATA_DRIVE_3D:
            case Msg.NaviState.WITHOUT_DATA_DRIVE_MAP:
            case Msg.NaviState.WITHOUT_DATA_3D_DRIVE_MAP:
            case Msg.NaviState.PLAN_TIMEOUT_OBSTACLES:
            case Msg.NaviState.PLAN_TIMEOUT_SELF_IN_OBSTACLES:
            case Msg.NaviState.PLAN_TIMEOUT_NO_PATH:
            case Msg.NaviState.TOO_CLOSE:
                return 10000L;
            case Msg.NaviState.PLAN_IN_OBSTACLE:
                return 30000L;
            default:
                return 0L;
        }
    }

    public static String moveError(int code) {
        switch (code) {
            case Msg.MoveError.MOTOR_COMMUNICATION_TIMEOUT:
                return "电机通信超时";
            case Msg.MoveError.OBSTACLE_BACK:
                return "检测到障碍物";
            case Msg.MoveError.ARM_STATE_ERROR:
            case Msg.MoveError.ARM_CONTROL_TIMEOUT:
                return "手状态错误（可在设置中关闭行走手同步）";
            case Msg.MoveError.NO_MAGNETIC1:
                return "磁道轨模式未检测磁条";
            case Msg.MoveError.RELIEVE_BRAKE_ERROR:
                return "刹车解除异常";
            case Msg.MoveError.TOUCH_STOP_BACK:
                return "触摸停止";
            case Msg.MoveError.MOTOR_PROTECT_BACK:
                return "电机保护状态，检查轮子温度";
            case Msg.MoveError.CHARGING:
            case Msg.MoveError.CHARGE_BACK:
                return "充电中，无法导航";
            default:
                return "运动错误:" + code;
        }
    }

    public static String result(int code) {
        if (code == Msg.Result.RESULT_OK) {
            return "成功";
        }
        switch (code) {
            case Msg.Result.NO_CONNECTED:
                return "没有连上导航服务";
            case Msg.Result.NO_NAVIGATION:
                return "未打开导航";
            case Msg.Result.NO_PREPARED:
                return "未准备好（正在开启导航）";
            case Msg.Result.NO_CHECK_POSITION:
                return "没有校准初始位置";
            case Msg.Result.X86_NOCONNECTED:
                return "X86 未连接";
            case Msg.Result.IN_VERIFY_MAP:
                return "正在校验地图";
            case Msg.Result.IN_BUILD_MAP:
                return "正在建图";
            case Msg.Result.IN_TRANS_MAP:
                return "正在下发地图给 X86";
            case Msg.Result.RESULT_RES_TIMEOUT:
                return "命令响应超时";
            default:
                return "错误码 0x" + Integer.toHexString(code);
        }
    }
}
