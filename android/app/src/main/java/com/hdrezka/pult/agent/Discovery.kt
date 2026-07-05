package com.hdrezka.pult.agent

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * UDP-поиск приставок в локальной сети — клиентская сторона discovery из
 * agent.py. Шлём широковещательно "HDREZKA_DISCOVER" на порт агента и
 * собираем JSON-ответы в течение окна ожидания.
 *
 * Надёжность (частый баг «приставка не находится»):
 *  - шлём не только на 255.255.255.255 (его режут многие роутеры), но и на
 *    subnet-broadcast каждого активного интерфейса (Wi-Fi/Ethernet);
 *  - берём MulticastLock — иначе часть прошивок не отдаёт входящие
 *    broadcast-пакеты приложению при энергосбережении Wi-Fi.
 */
object Discovery {

    private const val MESSAGE = "HDREZKA_DISCOVER"

    suspend fun discover(
        context: Context?,
        port: Int,
        timeoutMs: Int = 2000,
    ): List<AgentDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, AgentDevice>()
        val wifi = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("hdrezka-discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 300
            }
            val payload = MESSAGE.toByteArray(Charsets.UTF_8)
            for (addr in broadcastTargets()) {
                runCatching { socket.send(DatagramPacket(payload, payload.size, addr, port)) }
            }

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
            lock?.let { runCatching { it.release() } }
        }
        found.values.toList()
    }

    /** Все разумные широковещательные адреса: глобальный + subnet каждого интерфейса. */
    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashMap<String, InetAddress>()
        runCatching { InetAddress.getByName("255.255.255.255") }
            .getOrNull()?.let { out[it.hostAddress ?: "global"] = it }

        // subnet-broadcast по всем активным интерфейсам (Wi-Fi, Ethernet-донгл и т.п.)
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.interfaceAddresses.forEach { ia ->
                    val b = ia.broadcast ?: return@forEach
                    out[b.hostAddress ?: return@forEach] = b
                }
            }
        }
        return out.values.toList()
    }
}
