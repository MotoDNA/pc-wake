package io.github.motodna.pcwake

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.ConnectException

/** Wake-on-LAN '매직 패킷'을 만들고 보내는 부분 */
object Wol {

    /** "AA-BB-CC-DD-EE-FF", "aa:bb:..", "AABBCC.." 를 모두 "AA:BB:CC:DD:EE:FF" 로 바꿉니다. 잘못되면 null */
    fun normalizeMac(input: String): String? {
        val hex = input.uppercase().filter { it in "0123456789ABCDEF" }
        if (hex.length != 12) return null
        if (hex == "000000000000" || hex == "FFFFFFFFFFFF") return null
        return hex.chunked(2).joinToString(":")
    }

    fun isValidIp(ip: String): Boolean {
        val parts = ip.trim().split(".")
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun packet(mac: String): ByteArray {
        val macBytes = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val data = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) data[i] = 0xFF.toByte()
        for (r in 0 until 16) System.arraycopy(macBytes, 0, data, 6 + r * 6, 6)
        return data
    }

    /** 이 기기가 붙어 있는 네트워크의 브로드캐스트 주소들 */
    private fun broadcastTargets(): Set<InetAddress> {
        val set = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.interfaceAddresses.forEach { ia ->
                    if (ia.address is Inet4Address) ia.broadcast?.let { set.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return set
    }

    /**
     * 매직 패킷을 같은 네트워크에 보냅니다.
     * 브로드캐스트(모두에게) + IP를 알면 그 IP로도 직접, 포트 9와 7로 3번씩 반복합니다.
     * 보낸 곳 개수를 돌려줍니다.
     */
    fun send(mac: String, ip: String?): Int {
        val data = packet(mac)
        val targets = broadcastTargets().toMutableSet()
        if (!ip.isNullOrBlank() && isValidIp(ip)) {
            try { targets.add(InetAddress.getByName(ip.trim())) } catch (_: Exception) {}
            // 같은 대역의 .255 도 추가 (예: 192.168.0.255)
            val p = ip.trim().split(".")
            try { targets.add(InetAddress.getByName("${p[0]}.${p[1]}.${p[2]}.255")) } catch (_: Exception) {}
        }
        var sent = 0
        DatagramSocket().use { sock ->
            sock.broadcast = true
            repeat(3) {
                for (t in targets) for (port in intArrayOf(9, 7)) {
                    try {
                        sock.send(DatagramPacket(data, data.size, t, port))
                        sent++
                    } catch (_: Exception) {
                    }
                }
                Thread.sleep(150)
            }
        }
        return sent
    }

    /**
     * PC가 켜져 있는지 확인합니다. (윈도우가 보통 열어두는 포트에 노크)
     * '연결 거부'도 PC가 대답한 것이므로 켜진 것으로 봅니다.
     */
    fun isUp(ip: String): Boolean {
        if (!isValidIp(ip)) return false
        val addr = try { InetAddress.getByName(ip.trim()) } catch (e: Exception) { return false }
        for (port in intArrayOf(445, 3389, 135, 139, 22, 5900)) {
            try {
                Socket().use { it.connect(InetSocketAddress(addr, port), 700) }
                return true
            } catch (e: ConnectException) {
                if (e.message?.contains("refused", ignoreCase = true) == true) return true
            } catch (_: Exception) {
            }
        }
        return try { addr.isReachable(1000) } catch (_: Exception) { false }
    }
}
