package com.v2ray.ang.util

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds the largest IP packet size that reaches a public resolver without
 * fragmentation (DF bit) and returns that path MTU for the VPN TUN.
 */
object MtuPathProbe {
    enum class Transport {
        WIFI,
        CELLULAR,
    }

    private const val MIN_MTU = 1280
    private const val MAX_MTU = 1500
    /** IPv4 header + UDP header. */
    private const val IPV4_UDP_OVERHEAD = 28
    private const val PROBE_TIMEOUT_MS = 1_200
    private const val PROBE_ATTEMPTS = 2
    private const val DNS_PORT = 53

    /** Linux/Android: force don't-fragment for IPv4 UDP probes. */
    private const val IP_MTU_DISCOVER = 10
    private const val IP_PMTUDISC_DO = 2

    private val probeTargets = listOf("1.1.1.1", "8.8.8.8")

    fun detectActiveTransport(connectivity: ConnectivityManager): Transport? {
        val network = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> null
        }
    }

    fun linkMtuHint(connectivity: ConnectivityManager): Int {
        val network = connectivity.activeNetwork ?: return MAX_MTU
        val props = connectivity.getLinkProperties(network) ?: return MAX_MTU
        return props.mtu.takeIf { it in MIN_MTU..9_000 } ?: MAX_MTU
    }

    /**
     * @return recommended TUN MTU for the current transport, or null on failure
     */
    fun probeTunMtu(
        serviceControl: ServiceControl?,
        upperBound: Int = MAX_MTU,
    ): Int? {
        val highLimit = upperBound.coerceIn(MIN_MTU, MAX_MTU)
        var low = MIN_MTU
        var high = highLimit
        var bestPath = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pathAcceptsIpPacketSize(serviceControl, mid)) {
                bestPath = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (bestPath < MIN_MTU) return null
        return bestPath
    }

    private fun pathAcceptsIpPacketSize(
        serviceControl: ServiceControl?,
        ipPacketSize: Int,
    ): Boolean {
        val payloadSize = ipPacketSize - IPV4_UDP_OVERHEAD
        if (payloadSize < 32) return false
        return probeTargets.any { host ->
            repeat(PROBE_ATTEMPTS) {
                if (sendDnsProbe(serviceControl, host, payloadSize)) return true
            }
            false
        }
    }

    private fun sendDnsProbe(
        serviceControl: ServiceControl?,
        host: String,
        payloadSize: Int,
    ): Boolean {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = PROBE_TIMEOUT_MS
            if (!protectSocket(serviceControl, socket)) return false
            enableDontFragment(socket)

            val query = buildDnsQuery(payloadSize)
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(address, DNS_PORT)))
            val buffer = ByteArray(512)
            socket.receive(DatagramPacket(buffer, buffer.size))
            true
        } catch (_: ErrnoException) {
            false
        } catch (_: Exception) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun protectSocket(serviceControl: ServiceControl?, socket: DatagramSocket): Boolean {
        if (serviceControl == null) return true
        val service = serviceControl.getService()
        if (service is VpnService) {
            return service.protect(socket)
        }
        return serviceControl.vpnProtect(fdInt(datagramFileDescriptor(socket) ?: return false))
    }

    private fun enableDontFragment(socket: DatagramSocket) {
        val fd = datagramFileDescriptor(socket) ?: return
        try {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, IP_MTU_DISCOVER, IP_PMTUDISC_DO)
        } catch (_: Exception) {
            // Some devices reject the option; UDP is still commonly sent with DF.
        }
    }

    private fun datagramFileDescriptor(socket: DatagramSocket): FileDescriptor? = try {
        val method = DatagramSocket::class.java.getDeclaredMethod("getFileDescriptor\$").apply {
            isAccessible = true
        }
        method.invoke(socket) as? FileDescriptor
    } catch (_: Exception) {
        try {
            val implField = DatagramSocket::class.java.getDeclaredField("impl").apply {
                isAccessible = true
            }
            val impl = implField.get(socket) ?: return null
            val getFd = impl.javaClass.getDeclaredMethod("getFileDescriptor").apply {
                isAccessible = true
            }
            getFd.invoke(impl) as? FileDescriptor
        } catch (_: Exception) {
            null
        }
    }

    private fun fdInt(fd: FileDescriptor): Int = try {
        val field = FileDescriptor::class.java.getDeclaredField("descriptor").apply {
            isAccessible = true
        }
        field.getInt(fd)
    } catch (_: Exception) {
        try {
            FileDescriptor::class.java.getDeclaredMethod("getInt\$").apply {
                isAccessible = true
            }.invoke(fd) as Int
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Builds a valid DNS A-query for example.com with EDNS Padding (RFC 7830)
     * so the UDP payload is exactly [payloadSize] bytes.
     */
    private fun buildDnsQuery(payloadSize: Int): ByteArray {
        val name = byteArrayOf(
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0,
        )
        // header(12) + question + OPT header(11) + padding option header(4)
        val minSize = 12 + name.size + 4 + 11 + 4
        val size = payloadSize.coerceAtLeast(minSize)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(0x1234.toShort()) // id
        buffer.putShort(0x0100.toShort()) // recursion desired
        buffer.putShort(1) // questions
        buffer.putShort(0) // answers
        buffer.putShort(0) // authority
        buffer.putShort(1) // additional (OPT)
        buffer.put(name)
        buffer.putShort(1) // A
        buffer.putShort(1) // IN
        // OPT RR
        buffer.put(0) // root name
        buffer.putShort(41) // OPT
        buffer.putShort(4096) // UDP payload size
        buffer.putInt(0) // extended rcode / flags
        val rdlenPos = buffer.position()
        buffer.putShort(0) // rdlen placeholder
        // Padding option (code 12)
        buffer.putShort(12)
        val padLenPos = buffer.position()
        buffer.putShort(0) // pad length placeholder
        val padBytes = size - buffer.position()
        if (padBytes > 0) {
            buffer.put(ByteArray(padBytes))
        }
        buffer.putShort(padLenPos, padBytes.toShort())
        buffer.putShort(rdlenPos, (4 + padBytes).toShort())
        return buffer.array()
    }
}
