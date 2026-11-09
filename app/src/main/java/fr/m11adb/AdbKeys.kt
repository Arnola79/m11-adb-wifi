package fr.m11adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Paire de clés RSA 2048 persistante, utilisée pour l'authentification ADB. */
class AdbKeys(context: Context) {
    private val privFile = File(context.filesDir, "adbkey.priv")
    private val pubFile = File(context.filesDir, "adbkey.pub")
    private val keyPair: KeyPair = loadOrCreate()

    private fun loadOrCreate(): KeyPair {
        val kf = KeyFactory.getInstance("RSA")
        if (privFile.exists() && pubFile.exists()) {
            try {
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            } catch (_: Exception) {
                // clés corrompues : on en régénère
            }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }

    /** Signe le jeton ADB (traité comme un condensat SHA-1, PKCS#1 v1.5). */
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    /** Clé publique au format attendu par adbd : base64(struct RSAPublicKey) + " nom\0". */
    fun adbPublicKey(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val words = 2048 / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv)
        for (i in 0 until words) buf.putInt(n.shiftRight(32 * i).toInt())
        for (i in 0 until words) buf.putInt(rr.shiftRight(32 * i).toInt())
        buf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 m11adb@pixel\u0000".toByteArray(Charsets.US_ASCII)
    }

    companion object {
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
    }
}
