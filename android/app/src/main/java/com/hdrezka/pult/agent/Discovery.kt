package com.hdrezka.pult.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UDP-поиск приставок в локальной сети — клиентская сторона discovery из
 * agent.py. Шлём широковещательно "HDREZKA_DISCOVER" на порт агента и
 * собираем JSON-ответы в течение окна ожидания.
 */
object Discovery {

    private const val MESSAGE = "HDREZKA_DISCOVER"

    suspend fun discover(port: Int, timeoutMs: Int = 1500): List<AgentDevice> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, AgentDevice>()
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 300
                }
                val payload = MESSAGE.toByteArray(Charsets.UTF_8)
                val bcast = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(payload, payload.size, bcast, port))

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val o = JSONObject(text)
                        if (o.optString("service") == "hdrezka-agent") {
                            val host = packet.address.hostAddress ?: continue
                            found[host] = AgentDevice(
                                name = o.optString("name", host),
                                host = host,
                                port = o.optInt("port", port),
                                version = o.optString("version", ""),
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                        // повторный тик до дедлайна
                    } catch (_: Exception) {
                        // мусорный пакет — игнор
                    }
                }
            } catch (_: Exception) {
                // сеть недоступна — вернём что успели
            } finally {
                socket?.close()
            }
            found.values.toList()
        }
}
