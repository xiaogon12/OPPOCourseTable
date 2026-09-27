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
package com.liyan.coursetable.phone.sync

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * 经典蓝牙（RFCOMM）直连推送。
 *
 * 为什么走这条路而不是网络：
 *  - 手机和手表本来就是**经典蓝牙已配对**状态（手表以 HFP 客户端角色连着手机），
 *    所以不需要重新配对、不需要 Wi-Fi、不需要热点、不需要 IP、不需要配对码；
 *  - 不依赖 `com.heytap.wearable.bluetooth.net.proxy` 那条实测不通的蓝牙网络代理。
 *
 * 角色分配：**手机是客户端，手表是服务端**。
 * 因为手表不支持后台运行（OPPO 官方 11282），只能是「手表先在界面上点开接收、等着」，
 * 然后手机主动连过去推数据。
 *
 * 传输帧格式（两端共用）：
 *   [4 字节大端长度][UTF-8 负载]     手机 -> 手表
 *   [4 字节大端长度][UTF-8 响应]     手表 -> 手机
 */
object BtSync {

    /** RFCOMM 服务 UUID，手机端和手表端**必须完全一致**。 */
    val SERVICE_UUID: UUID = UUID.fromString("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e")

    const val SERVICE_NAME = "CourseTableSync"

    private const val MAX_FRAME = 4 shl 20

    data class BondedDevice(
        val name: String,
        val address: String,
        /** 名字/设备类型像手表（优先排在前面并预选） */
        val looksLikeWatch: Boolean,
        /** 音频设备（耳机 / 音箱）。列出来但明确标出来，避免被当成手表选走 */
        val isAudio: Boolean = false,
    )

    class PushResult(
        val ok: Boolean,
        val message: String,
        val sentBytes: Int = 0,
        val receviedCourses: Int = -1,
    )

    fun adapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    /**
     * 需要**运行时申请**的蓝牙权限，空列表表示齐了。
     *
     * 这一点曾经是整个功能最大的坑：Android 12（API 31）起 `BLUETOOTH_CONNECT`
     * 是运行时权限，只在清单里声明不够 —— 没申请时 `getBondedDevices()`
     * 直接抛 `SecurityException`，界面上表现成「读不到任何已配对设备」，
     * 看起来像蓝牙坏了，其实是权限没要。
     */
    fun missingPermissions(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(Manifest.permission.BLUETOOTH_CONNECT).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** @return null 表示可以开始推送，否则是原因 */
    fun readyProblem(context: Context): String? {
        val adapter = adapter() ?: return "这台设备没有蓝牙"
        if (!adapter.isEnabled) return "手机蓝牙没打开"
        if (missingPermissions(context).isNotEmpty()) {
            return "还没给「课程表」蓝牙权限，所以读不到已配对设备"
        }
        return null
    }

    // ---------------------------------------------------------------- 设备识别

    /** 蓝牙设备类的主类型位（`bluetoothClass.deviceClass` 的高 5 位） */
    private const val MAJOR_AUDIO_VIDEO = 0x0400
    private const val MAJOR_WEARABLE = 0x0700

    private fun majorOf(deviceClass: Int): Int = deviceClass and 0x1F00

    /** 设备的蓝牙 Class，拿不到就 -1 */
    private fun deviceClassOf(device: BluetoothDevice): Int = try {
        device.bluetoothClass?.deviceClass ?: -1
    } catch (_: SecurityException) {
        -1
    }

    /**
     * 判断是不是手表。
     *
     * 只按名字匹配不够：`OPPO Enco Free4`（耳机）名字里也带 "OPPO"，
     * 早期用 `contains("oppo")` 会把它也标成手表，于是「只有一台像手表就自动选中」
     * 的逻辑直接失效，目标设备一直是空的。这里改成：
     *   1. 蓝牙 Class 明确标了「可穿戴」→ 是手表
     *   2. 蓝牙 Class 明确标了「音频设备」→ 一定不是手表（一次性排除所有耳机音箱）
     *   3. 再看名字关键词（不再单独认 "oppo"）
     */
    fun looksLikeWatch(name: String, deviceClass: Int = -1): Boolean {
        when (majorOf(deviceClass)) {
            MAJOR_WEARABLE -> return true
            MAJOR_AUDIO_VIDEO -> return false
        }
        val n = name.lowercase()
        return n.contains("watch") || n.contains("oww") || n.contains("手表") ||
            n.contains("wear os") || n.contains("wearable") || n.contains("wrist")
    }

    /**
     * 已配对设备列表。手表排最前，音频设备沉到最后，
     * 并带上 `looksLikeWatch` / `isAudio` 两个标记给界面用。
     */
    fun bondedDevices(context: Context): List<BondedDevice> {
        val adapter = adapter() ?: return emptyList()
        val out = mutableListOf<BondedDevice>()
        try {
            for (device in adapter.bondedDevices.orEmpty()) {
                val name = try {
                    device.name ?: device.address
                } catch (_: SecurityException) {
                    device.address
                }
                val cls = deviceClassOf(device)
                out += BondedDevice(
                    name = name,
                    address = device.address,
                    looksLikeWatch = looksLikeWatch(name, cls),
                    isAudio = majorOf(cls) == MAJOR_AUDIO_VIDEO,
                )
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return out.sortedWith(
            compareBy(
                { if (it.looksLikeWatch) 0 else if (it.isAudio) 2 else 1 },
                { it.name },
            ),
        )
    }

    /** 阻塞式推送，**必须在后台线程调用**。 */
    fun push(address: String, payload: String): PushResult {
        val adapter = adapter() ?: return PushResult(false, "这台设备没有蓝牙")
        if (!adapter.isEnabled) return PushResult(false, "手机蓝牙没打开")

        val bytes = payload.toByteArray(Charsets.UTF_8)
        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            return PushResult(false, "设备地址不对：$address")
        }

        val socket = connect(device)
            ?: return PushResult(false, "连不上手表。先在手表上打开「从手机接收」，再回手机点推送。")

        return try {
            val out = DataOutputStream(socket.outputStream)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()

            val input = DataInputStream(socket.inputStream)
            val len = input.readInt()
            val response = if (len in 1..MAX_FRAME) {
                val buf = ByteArray(len)
                input.readFully(buf)
                String(buf, Charsets.UTF_8)
            } else {
                ""
            }

            val courses = Regex("\"courses\"\\s*:\\s*(\\d+)").find(response)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val error = Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(response)?.groupValues?.get(1)
            if (Regex("\"ok\"\\s*:\\s*true").containsMatchIn(response)) {
                PushResult(true, "推送成功", bytes.size, courses)
            } else {
                PushResult(false, error ?: "手表返回了意外响应", bytes.size)
            }
        } catch (e: Exception) {
            PushResult(false, "传输中断：${e.message ?: e.toString()}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 建立连接。先用标准 SDP 查询，失败再退回老的 `createRfcommSocket(1)` 通道 ——
     * 部分定制 ROM 的 SDP 缓存有问题，这一招能救回来。
     */
    private fun connect(device: BluetoothDevice): BluetoothSocket? {
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SERVICE_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID) },
            {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            },
        )
        for (make in attempts) {
            var candidate: BluetoothSocket? = null
            try {
                candidate = make()
                candidate.connect()
                return candidate
            } catch (_: Exception) {
                try {
                    candidate?.close()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }
}
