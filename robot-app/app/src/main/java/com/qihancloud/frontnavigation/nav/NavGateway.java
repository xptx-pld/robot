package com.qihancloud.frontnavigation.nav;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.qihancloud.frontnavigation.App;
import com.sanbot.map.MapClient;
import com.sanbot.map.Msg;
import com.sanbot.map.PositionTag;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link MapClient} 的唯一持有者。
 *
 * <p>还原要点（来自官方文档 1.5 与 demo 源码）：
 * <ul>
 *   <li>{@code MapClient.Connect(id, listener)} 是静态方法，回调里才拿到实例；</li>
 *   <li>{@code openNavigation / closeNavigation / navigationTo / cancelNavigation / adjustPosition}
 *       全部是 <b>阻塞</b> 调用，必须放到工作线程，否则会卡住 UI（这也是当年
 *       “点一下没反应/ANR” 的典型来源）；</li>
 *   <li>{@code getPose / getCtrlState / getNaviState / getMoveError} 非阻塞，可直接读。</li>
 * </ul>
 */
public class NavGateway implements MapClient.Listener, MapClient.PathListener {

    private static final String TAG = "NavGateway";

    /** 导航回调，全部在 SDK 的线程上触发，调用方需自行切线程。 */
    public interface Listener {
        void onNavConnected();

        void onNavState(int ctrl, int navi, int oldNavi);

        void onPose(double x, double y, double radians);

        void onMapChanged();

        void onMoveError(int error, int sub);

        void onPlanPath(List<PositionTag> path);
    }

    private MapClient client;
    private Listener listener;
    private Handler worker;
    private HandlerThread workerThread;
    private final String logTag = "com.qihancloud.frontnavigation";

    private volatile int ctrlState = -1;
    private volatile int naviState = -1;
    private volatile double[] pose = new double[]{0, 0, 0};

    public synchronized void connect(Listener listener) {
        this.listener = listener;
        if (workerThread == null) {
            workerThread = new HandlerThread("nav-sdk");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());
        }
        // 文档：debug(boolean) 必须在 Connect 之前调用才生效
        MapClient.debug(true);
        MapClient.Connect(logTag, this);
        Log.i(TAG, "MapClient.Connect() 发起");
    }

    public synchronized void close() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Throwable e) {
            Log.w(TAG, "close 失败", e);
        }
        client = null;
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            worker = null;
        }
    }

    public boolean isReady() {
        return client != null;
    }

    public int ctrlState() {
        return ctrlState;
    }

    public int naviState() {
        return naviState;
    }

    public double[] pose() {
        return new double[]{pose[0], pose[1], pose[2]};
    }

    public String stateText() {
        return MsgText.ctrl(ctrlState) + " / " + MsgText.navi(naviState);
    }

    // ---------------------------------------------------------------- 阻塞接口
    // 以下全部投递到工作线程，结果通过 Callback 回主线程（由上层处理）

    public interface ResultCallback {
        void onResult(int code, String message);
    }

    private void post(final String name, final ResultCallback cb, final Op op) {
        Handler h = worker;
        if (h == null || client == null) {
            if (cb != null) {
                cb.onResult(Msg.Result.NO_CONNECTED, "导航服务未连接");
            }
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                int code;
                try {
                    code = op.run(client);
                } catch (Throwable e) {
                    Log.e(TAG, name + " 异常", e);
                    code = Msg.Result.RESULT_RES_TIMEOUT;
                }
                Log.i(TAG, name + " -> " + code + " (" + MsgText.result(code) + ")");
                if (cb != null) {
                    cb.onResult(code, MsgText.result(code));
                }
            }
        });
    }

    private interface Op {
        int run(MapClient c);
    }

    public void openNavigation(ResultCallback cb) {
        post("openNavigation", cb, new Op() {
            @Override
            public int run(MapClient c) {
                return c.openNavigation();
            }
        });
    }

    public void closeNavigation(ResultCallback cb) {
        post("closeNavigation", cb, new Op() {
            @Override
            public int run(MapClient c) {
                return c.closeNavigation();
            }
        });
    }

    /** 导航到坐标；导航 SDK 的角度单位是弧度。 */
    public void navigateTo(final double x, final double y, final double radians, ResultCallback cb) {
        post("navigationTo(" + x + "," + y + ")", cb, new Op() {
            @Override
            public int run(MapClient c) {
                return c.navigationTo(x, y, radians);
            }
        });
    }

    public void cancelNavigation(ResultCallback cb) {
        post("cancelNavigation", cb, new Op() {
            @Override
            public int run(MapClient c) {
                return c.cancelNavigation();
            }
        });
    }

    /** 校验/重定位到某个已知点位，必须在 NaviState.NO_INIT 状态下调用才有效。 */
    public void adjustPosition(final PositionTag tag, ResultCallback cb) {
        post("adjustPosition", cb, new Op() {
            @Override
            public int run(MapClient c) {
                return c.adjustPosition(tag.getX(), tag.getY(), tag.getRadians());
            }
        });
    }

    /** 切换使用中的地图（长耗时，最终结果由 setApplyMapListener 回调）。 */
    public void applyMap(final String mapName, ResultCallback cb) {
        post("applyMap", cb, new Op() {
            @Override
            public int run(MapClient c) {
                return c.applyMap(mapName);
            }
        });
    }

    // ------------------------------------------------------------------ 回调

    @Override
    public void initialize(MapClient manager) {
        this.client = manager;
        try {
            manager.setPathListener(this);
        } catch (Throwable e) {
            Log.w(TAG, "setPathListener 失败", e);
        }
        refreshFromClient();
        Log.i(TAG, "SDK 初始化完成 " + stateText());
        if (listener != null) {
            listener.onNavConnected();
        }
    }

    private void refreshFromClient() {
        MapClient c = client;
        if (c == null) {
            return;
        }
        try {
            ctrlState = c.getCtrlState();
            naviState = c.getNaviState();
            double[] p = c.getPose();
            if (p != null && p.length == 3) {
                pose = p;
            }
        } catch (Throwable e) {
            Log.w(TAG, "读取状态失败", e);
        }
    }

    @Override
    public void connState(int state, Object data) {
        Log.i(TAG, "connState=" + state + " data=" + data);
    }

    @Override
    public void mapState(int ctrl, int oldCtrl, int navi, int oldNavi) {
        ctrlState = ctrl;
        naviState = navi;
        if (listener != null) {
            listener.onNavState(ctrl, navi, oldNavi);
        }
    }

    @Override
    public void position(double x, double y, double radians) {
        pose = new double[]{x, y, radians};
        if (listener != null) {
            listener.onPose(x, y, radians);
        }
    }

    @Override
    public void mapChanged() {
        if (listener != null) {
            listener.onMapChanged();
        }
    }

    @Override
    public void moveError(int error, int errorSub) {
        if (listener != null) {
            listener.onMoveError(error, errorSub);
        }
    }

    @Override
    public void planPath(List<PositionTag> tags) {
        if (listener != null) {
            listener.onPlanPath(tags == null ? new ArrayList<PositionTag>() : tags);
        }
    }

    /** 供上层做耗时统计 / 手动触发刷新。 */
    public void refreshState() {
        refreshFromClient();
    }

    /** 只为了在没连上时也能给出可读提示。 */
    public static String notConnectedText() {
        return MsgText.result(Msg.Result.NO_CONNECTED);
    }
}
