package fr.m11adb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var button: Button
    private var port = 5555

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val dev = deviceFrom(intent) ?: return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                runAdb(dev)
            } else {
                log("Permission USB refusée.")
                button.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        button = Button(this).apply {
            text = "Activer ADB Wi-Fi (port $port)"
            textSize = 18f
            setOnClickListener { start() }
        }
        logView = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(button, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = pad })
        setContentView(root)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)

        log("1. Branche le M11 au Pixel (câble USB-C données).\n2. Débogage USB activé sur le M11.\n3. Appuie sur le bouton.\n")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            deviceFrom(intent)?.let { log("Appareil détecté : ${it.productName ?: it.deviceName}") }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun deviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun start() {
        val dev = usb.deviceList.values.firstOrNull { AdbUsb.findAdbInterface(it) != null }
        if (dev == null) {
            log("❌ Aucun appareil ADB trouvé en USB.\n" +
                "• Vérifie le câble (données, pas charge seule).\n" +
                "• Débogage USB activé sur le M11 ?\n" +
                "• Le Pixel doit être « hôte » : dans la notification USB du Pixel, " +
                "choisis « Cet appareil contrôle le périphérique connecté », ou inverse le câble.")
            return
        }
        button.isEnabled = false
        log("Appareil : ${dev.manufacturerName ?: ""} ${dev.productName ?: dev.deviceName}")
        if (usb.hasPermission(dev)) {
            runAdb(dev)
        } else {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
        }
    }

    private fun runAdb(dev: UsbDevice) {
        Thread {
            var adb: AdbUsb? = null
            try {
                val iface = AdbUsb.findAdbInterface(dev) ?: error("Interface ADB introuvable")
                val conn = usb.openDevice(dev) ?: error("Impossible d'ouvrir l'appareil USB")
                adb = AdbUsb(conn, iface, ::log)
                log("Connexion ADB…")
                val banner = adb.connect(AdbKeys(this))
                log("✅ Connecté (${banner.take(60)})")

                val ipOut = adb.exec("shell:ip -f inet addr show wlan0")
                val ip = Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(ipOut)?.groupValues?.get(1)
                log(if (ip != null) "IP Wi-Fi du M11 : $ip" else "IP Wi-Fi introuvable (Wi-Fi activé sur le M11 ?)")

                log("Passage en mode TCP…")
                val res = adb.exec("tcpip:$port")
                log(res.trim().ifEmpty { "Commande envoyée." })
                log("\n🎉 C'est bon ! Débranche le câble et connecte scrcpy sur :\n" +
                    "   ${ip ?: "<IP du M11>"}:$port\n")
            } catch (e: Exception) {
                log("❌ Erreur : ${e.message}")
            } finally {
                adb?.close()
                runOnUiThread { button.isEnabled = true }
            }
        }.start()
    }

    private fun log(msg: String) = runOnUiThread {
        logView.append(msg + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "fr.m11adb.USB_PERMISSION"
    }
}
