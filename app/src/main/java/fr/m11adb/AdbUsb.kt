package fr.m11adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Implémentation minimale du protocole ADB (côté hôte) sur USB. */
class AdbUsb(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val log: (String) -> Unit,
) {
    class Message(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private var nextLocalId = 1

    init {
        var i: UsbEndpoint? = null
        var o: UsbEndpoint? = null
        for (k in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(k)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) i = ep else o = ep
        }
        epIn = i ?: throw IOException("Pas d'endpoint IN sur l'interface ADB")
        epOut = o ?: throw IOException("Pas d'endpoint OUT sur l'interface ADB")
        if (!conn.claimInterface(iface, true)) throw IOException("Impossible de réserver l'interface USB")
    }

    fun close() {
        try { conn.releaseInterface(iface) } catch (_: Exception) {}
        conn.close()
    }

    // ---- bas niveau ----

    private fun writeAll(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = conn.bulkTransfer(epOut, data, off, data.size - off, 5000)
            if (n < 0) throw IOException("Écriture USB échouée")
            off += n
        }
    }

    private fun readExactly(len: Int, deadline: Long): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            if (System.currentTimeMillis() > deadline) throw IOException("Délai dépassé en lecture USB")
            val n = conn.bulkTransfer(epIn, buf, off, len - off, 1000)
            if (n > 0) off += n
        }
        return buf
    }

    fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xff)
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(cmd xor -1)
        writeAll(h.array())
        if (data.isNotEmpty()) writeAll(data)
    }

    fun read(timeoutMs: Long): Message {
        val deadline = System.currentTimeMillis() + timeoutMs
        val h = ByteBuffer.wrap(readExactly(24, deadline)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int; h.int; val magic = h.int
        if (magic != (cmd xor -1)) throw IOException("En-tête ADB invalide")
        val data = if (len > 0) readExactly(len, deadline + 5000) else ByteArray(0)
        return Message(cmd, a0, a1, data)
    }

    // ---- protocole ----

    /** Connexion + authentification. Retourne la bannière de l'appareil. */
    fun connect(keys: AdbKeys): String {
        send(A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var signatureSent = false
        var deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw IOException("Pas de réponse de l'appareil (autorisation refusée ?)")
            val m = read(remaining)
            when (m.cmd) {
                A_CNXN -> return String(m.data).trimEnd('\u0000')
                A_AUTH -> if (m.arg0 == AUTH_TOKEN) {
                    if (!signatureSent) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, keys.sign(m.data))
                        signatureSent = true
                    } else {
                        log("➡ Sur le M11 : coche « Toujours autoriser » puis OK (60 s).")
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, keys.adbPublicKey())
                        deadline = System.currentTimeMillis() + 60_000
                    }
                }
            }
        }
    }

    /** Ouvre un service (ex. "shell:…", "tcpip:5555") et renvoie sa sortie. */
    fun exec(service: String, timeoutMs: Long = 10_000): String {
        val localId = nextLocalId++
        var remoteId = 0
        val out = StringBuilder()
        send(A_OPEN, localId, 0, "$service\u0000".toByteArray())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return out.toString()
            val m = try { read(remaining) } catch (e: IOException) {
                if (out.isNotEmpty()) return out.toString() else throw e
            }
            if (m.arg1 != localId) continue
            when (m.cmd) {
                A_OKAY -> remoteId = m.arg0
                A_WRTE -> {
                    out.append(String(m.data))
                    remoteId = m.arg0
                    try { send(A_OKAY, localId, remoteId) } catch (_: IOException) {}
                }
                A_CLSE -> {
                    try { send(A_CLSE, localId, remoteId) } catch (_: IOException) {}
                    return out.toString()
                }
            }
        }
    }

    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val VERSION = 0x01000000
        const val MAX_DATA = 4096
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        fun findAdbInterface(dev: UsbDevice): UsbInterface? {
            for (k in 0 until dev.interfaceCount) {
                val i = dev.getInterface(k)
                if (i.interfaceClass == 0xff && i.interfaceSubclass == 0x42 && i.interfaceProtocol == 0x01) return i
            }
            return null
        }
    }
}
