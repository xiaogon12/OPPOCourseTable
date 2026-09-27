/*
 * 课程表 · OPPO Watch X2
 * Copyright (c) 2026 xiaogon12
 * https://github.com/xiaogon12/OPPOCourseTable
 *
 * 许可：CC BY-NC-SA 4.0（署名—非商业性使用—相同方式共享）
 *   · 可以免费用、随意改、原样或改版再发布
 *   · 不可以商用、盈利，不可以移除本署名后重新发布
 *   · 改版发布必须沿用同一许可
 * 完整条款见仓库根目录 LICENSE。
 */
package com.liyan.coursetable;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 从手机接收课表（经典蓝牙 RFCOMM 服务端）。
 *
 * 为什么手表当服务端、手机当客户端：
 * OPPO 官方文档（11282）明确写了 **手表不支持应用在后台运行**，
 * 所以手表不可能常驻监听。只能反过来 —— 用户在手表上主动打开「从手机接收」，
 * 那时才起一个前台短时监听，等手机连过来把 JSON 推下来。
 *
 * 为什么用蓝牙而不是网络：
 * 手机和手表本来就是经典蓝牙已配对状态（手表以 HFP 客户端角色连着手机），
 * 不用重新配对、不用 Wi-Fi、不用热点、不用 IP、不用配对码。
 * 而且绕开了实测不通的蓝牙网络代理（com.heytap.wearable.bluetooth.net.proxy）。
 *
 * 传输帧格式（两端共用）：
 *   [4 字节大端长度][UTF-8 负载]     手机 -> 手表
 *   [4 字节大端长度][UTF-8 响应]     手表 -> 手机
 */
public final class BtReceive {

    /** 与手机端 BtSync.SERVICE_UUID **必须完全一致** */
    private static final UUID SYNC_UUID =
            UUID.fromString("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e");

    private static final String TAG = "CourseTable";

    private static final String SERVICE_NAME = "CourseTableSync";

    private static final int MAX_FRAME = 4 << 20;
    /** 总等待时长：够用户放下手表去点手机 */
    private static final long TIMEOUT_MS = 90_000L;
    /** 单次 accept 的等待片长，用来周期性检查是否被取消 */
    private static final int ACCEPT_SLICE_MS = 2000;

    /** 把负载写进 Store（在主线程调用）。返回课程数，抛异常表示失败。 */
    public interface Applier {
        int apply(String json) throws Exception;
    }

    public interface Callback {
        void onDone(int courses, long bytes);

        void onError(String message);
    }

    private static BtReceive current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Applier applier;
    private final Callback callback;

    private BluetoothServerSocket server;
    private Thread worker;
    private volatile boolean stopped;
    private volatile String applyError;

    private BtReceive(Applier applier, Callback callback) {
        this.applier = applier;
        this.callback = callback;
    }

    // ---------------------------------------------------------- 对外

    /** 开始等待。返回 null 表示已开始，否则是失败原因。 */
    public static synchronized String start(Applier applier, Callback callback) {
        stopCurrent();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return "这台设备没有蓝牙";
        }
        if (!adapter.isEnabled()) {
            return "蓝牙没打开";
        }
        // 有配对设备时顺手取消发现流程，避免占着蓝牙通道
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (Exception ignored) {
        }

        final BtReceive instance = new BtReceive(applier, callback);
        try {
            // 注意方法名：服务端是 listenUsingRfcomm**WithServiceRecord**(名字, UUID)
            instance.server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SYNC_UUID);
        } catch (Exception secureFailed) {
            // 个别机型的 RFCOMM 安全监听建不起来，退回非安全监听。
            // 手机端第 2 条连接策略正好是非安全连接，两边配成一对才有意义。
            // 两台设备本来就已配对，链路自身是加密的，这里并没有牺牲安全性。
            try {
                instance.server =
                        adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SYNC_UUID);
                Log.w(TAG, "安全监听失败，已退回非安全监听");
            } catch (Exception e) {
                return "打不开蓝牙监听：" + brief(secureFailed);
            }
        }

        Log.i(TAG, "蓝牙监听已启动 " + SERVICE_NAME + " " + SYNC_UUID);
        current = instance;
        instance.worker = new Thread(new Runnable() {
            @Override
            public void run() {
                instance.loop();
            }
        }, "bt-receive");
        instance.worker.setDaemon(true);
        instance.worker.start();
        return null;
    }

    public static synchronized void stopCurrent() {
        BtReceive r = current;
        current = null;
        if (r != null) {
            r.stop();
        }
    }

    public static synchronized boolean isRunning() {
        return current != null;
    }

    /**
     * 已配对的设备数量。
     *
     * 用来在开始等待之前先做个体检：一台都没配对，后面必然白等 90 秒，
     * 不如直接告诉用户“先去系统设置里配对”。
     *
     * @return &gt;= 0 是数量；-1 表示读不到（没权限 / 蓝牙没开）
     */
    public static int bondedCount() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return -1;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            return bonded == null ? -1 : bonded.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private void stop() {
        stopped = true;
        BluetoothServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------- 接收循环

    private void loop() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!stopped && System.currentTimeMillis() < deadline) {
            BluetoothSocket socket;
            try {
                BluetoothServerSocket s = server;
                if (s == null) {
                    break;
                }
                socket = s.accept(ACCEPT_SLICE_MS);
            } catch (Exception e) {
                // accept 超时是正常的，返回去继续等；被取消则退出
                continue;
            }
            if (socket == null) {
                continue;
            }
            Log.i(TAG, "手机已连上：" + socket.getRemoteDevice().getAddress());
            session(socket);
            return;   // 一次会话结束就收工，想再来一次用户会重新点
        }
        if (!stopped) {
            Log.w(TAG, "等待手机推送超时");
            post(new Runnable() {
                @Override
                public void run() {
                    clearIfCurrent();
                    callback.onError("等待超时，手机没有连过来。点「重试」再来一次。");
                }
            });
        }
    }

    private void session(BluetoothSocket socket) {
        int courses = -1;
        long bytes = 0;
        String error = null;

        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int len = in.readInt();
            if (len <= 0 || len > MAX_FRAME) {
                throw new IOException("负载长度异常 " + len);
            }
            byte[] buf = new byte[len];
            in.readFully(buf);
            bytes = len;
            courses = applyOnMain(new String(buf, "UTF-8"));
            if (courses < 0) {
                error = applyError == null ? "导入失败" : applyError;
            }
            Log.i(TAG, "收到负载 " + bytes + " 字节 -> " + courses + " 门课 error=" + error);
        } catch (Exception e) {
            error = brief(e);
        }

        // 先把结果回给手机，再刷新界面 —— 手机那边能立刻知道成没成
        try {
            String resp = error == null
                    ? "{\"ok\":true,\"courses\":" + courses + "}"
                    : "{\"ok\":false,\"error\":\"" + escape(error) + "\"}";
            byte[] rb = resp.getBytes("UTF-8");
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(rb.length);
            out.write(rb);
            out.flush();
        } catch (Exception ignored) {
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }

        final int c = courses;
        final long b = bytes;
        final String err = error;
        stopped = true;
        post(new Runnable() {
            @Override
            public void run() {
                clearIfCurrent();
                if (err == null) {
                    callback.onDone(c, b);
                } else {
                    callback.onError(err);
                }
            }
        });
    }

    /** 在主线程执行导入，等它结束再返回（手机那边在等回包）。 */
    private int applyOnMain(final String json) {
        final int[] result = {-1};
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = applier.apply(json);
                } catch (Throwable t) {
                    applyError = brief(t);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                applyError = "导入超时";
            }
        } catch (InterruptedException e) {
            applyError = "被中断";
        }
        return result[0];
    }

    // ---------------------------------------------------------- 杂项

    private void post(Runnable r) {
        main.post(r);
    }

    private void clearIfCurrent() {
        synchronized (BtReceive.class) {
            if (current == BtReceive.this) {
                current = null;
            }
        }
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
