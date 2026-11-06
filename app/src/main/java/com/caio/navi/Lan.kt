package com.caio.navi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Utilidades de rede local: IP do celular, procurar aparelhos e Wake-on-LAN. */
object Lan {

    /** IPv4 do celular no Wi-Fi (ex.: 192.168.0.23), ou null. */
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    fun isPortOpen(host: String, port: Int, timeoutMs: Int = 400): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    /** Testa todos os IPs da rede /24 do celular e devolve os que têm a porta aberta. */
    suspend fun scan(port: Int, timeoutMs: Int = 450): List<String> = withContext(Dispatchers.IO) {
        val me = localIpv4() ?: return@withContext emptyList<String>()
        val prefix = me.substringBeforeLast('.')
        val gate = Semaphore(48)
        coroutineScope {
            (1..254).map { n ->
                async {
                    val host = "$prefix.$n"
                    gate.withPermit { if (host != me && isPortOpen(host, port, timeoutMs)) host else null }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Envia o "pacote mágico" para ligar um aparelho pela rede. */
    fun wakeOnLan(mac: String): Boolean {
        val hex = mac.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) return false
        val macBytes = ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
        val targets = mutableListOf("255.255.255.255")
        localIpv4()?.let { targets += it.substringBeforeLast('.') + ".255" }
        var sent = false
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                for (t in targets) for (port in listOf(9, 7)) {
                    try {
                        s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port))
                        sent = true
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return sent
    }
}
