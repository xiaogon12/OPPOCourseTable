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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 手机端的同步服务：在局域网里起一个极小的 HTTP 服务，等手表来拉课表。
 *
 * 为什么是「手机做服务端、手表做客户端」：
 * OPPO 官方文档明确写了 **手表不支持应用后台运行**（11282），手表端不可能常驻监听。
 * 所以只能由手表在用户点「从手机接收」时主动发起一次前台短连接。
 *
 * 协议（刻意做得极简，手表端用 `HttpURLConnection` 十几行就能实现）：
 *
 *   GET /info                -> 服务信息（不校验配对码，用于让手表确认找对了设备）
 *   GET /sync?code=123456    -> 返回完整课表负载 JSON
 *   GET /pull?code=123456    -> /sync 的别名
 *   GET /                    -> 纯文本说明，方便用手机浏览器自查
 *
 * 数据只在本机内存里生成，不落盘、不出内网、不需要任何账号。
 */
class SyncServer(private val preferredPort: Int = DEFAULT_PORT) {

    data class LogEntry(val time: String, val text: String, val ok: Boolean)

    var running by mutableStateOf(false)
        private set

    /** 6 位配对码，防止同网段的其他设备误连 */
    var code by mutableStateOf(newCode())
        private set

    /** 成功推送次数 */
    var servedCount by mutableStateOf(0)
        private set

    var lastClient by mutableStateOf<String?>(null)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    val log = mutableStateListOf<LogEntry>()

    @Volatile
    private var boundPort: Int = preferredPort

    @Volatile
    private var payloadProvider: (() -> String)? = null

    @Volatile
    private var infoProvider: (() -> String)? = null

    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    val port: Int get() = boundPort

    fun setProviders(payload: () -> String, info: () -> String) {
        payloadProvider = payload
        infoProvider = info
    }

    fun regenerateCode() {
        code = newCode()
        add("已重新生成配对码", true)
    }

    /** @return null 表示启动成功，否则是失败原因 */
    fun start(): String? {
        if (running) return null
        lastError = null
        // 默认端口被占就往后顺延几个，避免和别的 App 打架
        var socket: ServerSocket? = null
        var lastFailure: String? = null
        for (offset in 0 until 10) {
            val p = preferredPort + offset
            try {
                socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
                boundPort = p
                break
            } catch (e: Exception) {
                lastFailure = e.message ?: e.toString()
            }
        }
        if (socket == null) {
            val msg = "端口 $preferredPort 起不来：${lastFailure ?: "未知错误"}"
            lastError = msg
            add(msg, false)
            return msg
        }

        serverSocket = socket
        running = true
        add("服务已启动，监听 0.0.0.0:$boundPort", true)
        worker = thread(isDaemon = true, name = "coursetable-sync") {
            acceptLoop(socket)
        }
        return null
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        add("服务已停止", true)
    }

    // ------------------------------------------------------------ 内部

    private fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            try {
                handle(client)
            } catch (e: Exception) {
                add("处理请求出错：${e.message}", false)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val target = parts.getOrNull(1).orEmpty().ifEmpty { "/" }

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] =
                    line.substring(i + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength in 1..MAX_BODY) {
                readFully(input, contentLength)
            }

            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val clientIp = s.inetAddress?.hostAddress ?: "未知地址"
            lastClient = clientIp

            when (path) {
                "/", "/index.html" -> respond(s, 200, "text/plain; charset=utf-8", HELP)
                "/info" -> respond(s, 200, JSON_TYPE, infoProvider?.invoke() ?: "{}")
                "/sync", "/pull" -> serve(s, clientIp, query["code"].orEmpty())
                else -> respond(s, 404, JSON_TYPE, """{"ok":false,"error":"未知路径 $path"}""")
            }
        }
    }

    private fun serve(socket: Socket, clientIp: String, givenCode: String) {
        when {
            givenCode.isEmpty() -> {
                add("$clientIp 请求了课表但没给配对码", false)
                respond(socket, 401, JSON_TYPE, """{"ok":false,"error":"缺少配对码"}""")
            }

            givenCode != code -> {
                add("$clientIp 配对码错误：$givenCode", false)
                respond(socket, 403, JSON_TYPE, """{"ok":false,"error":"配对码不正确"}""")
            }

            else -> {
                val payload = payloadProvider?.invoke() ?: "{}"
                servedCount += 1
                add("$clientIp 拉取课表成功（${payload.toByteArray().size} 字节）", true)
                respond(socket, 200, JSON_TYPE, payload)
            }
        }
    }

    private fun respond(socket: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val out = socket.getOutputStream()
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    private fun add(text: String, ok: Boolean) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        log.add(0, LogEntry(time, text, ok))
        while (log.size > 40) log.removeAt(log.size - 1)
    }

    companion object {
        const val DEFAULT_PORT = 8737
        private const val MAX_BODY = 4 shl 20
        private const val JSON_TYPE = "application/json; charset=utf-8"

        private const val HELP = """
课程表同步服务（手机端）

  GET /info               服务信息
  GET /sync?code=XXXXXX   拉取课表（配对码在手机上显示）

手表端：我的 → 从手机接收 → 输入配对码。
"""

        fun newCode(): String = (100000..999999).random().toString()

        private fun statusText(code: Int): String = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }

        /** 本机所有可被同网段设备访问到的 IPv4 地址，常见的 192.168 / 10. / 172. 段排前面。 */
        fun localAddresses(): List<String> {
            val out = mutableListOf<String>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (address in nif.inetAddresses) {
                        if (address !is Inet4Address) continue
                        if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                        address.hostAddress?.let { out += it }
                    }
                }
            } catch (_: Exception) {
            }
            return out.distinct().sortedBy { if (it.startsWith("192.168.") || it.startsWith("10.")) 0 else 1 }
        }

        private fun parseQuery(q: String): Map<String, String> {
            if (q.isEmpty()) return emptyMap()
            val map = HashMap<String, String>()
            for (pair in q.split('&')) {
                if (pair.isEmpty()) continue
                val i = pair.indexOf('=')
                if (i < 0) {
                    map[pair] = ""
                } else {
                    map[pair.substring(0, i)] = java.net.URLDecoder.decode(
                        pair.substring(i + 1), "UTF-8",
                    )
                }
            }
            return map
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) return sb.toString()
            }
        }

        private fun readFully(input: InputStream, size: Int): ByteArray {
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(buffer, offset, size - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == size) buffer else buffer.copyOf(offset)
        }
    }
}
