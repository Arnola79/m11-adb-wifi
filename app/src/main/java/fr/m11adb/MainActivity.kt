package fr.m11adb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var button: Button
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private val port = 5555

    // Palette
    private val bg = Color.parseColor("#111412")
    private val surface = Color.parseColor("#1C211D")
    private val onSurface = Color.parseColor("#E6EAE4")
    private val muted = Color.parseColor("#9AA39A")
    private val accent = Color.parseColor("#7DD99A")
    private val onAccent = Color.parseColor("#0B2614")
    private val ok = Color.parseColor("#7DD99A")
    private val err = Color.parseColor("#FF8A80")

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val dev = deviceFrom(intent) ?: return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                runAdb(dev)
            } else {
                setStatus("Permission USB refusée", err)
                setBusy(false)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(20).toFloat() }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun LinearLayout.add(v: View, top: Int = 0, height: Int = WRAP_CONTENT) =
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, height).apply { topMargin = dp(top) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        window.decorView.setBackgroundColor(bg)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.add(text("M11 ADB Wi‑Fi", 28f, onSurface, bold = true))
        content.add(text("Active le contrôle sans fil du FiiO M11 pour scrcpy.", 15f, muted), top = 4)

        val steps = card()
        steps.add(text("AVANT DE COMMENCER", 12f, muted, bold = true))
        listOf(
            "Branche le M11 au Pixel avec un câble USB‑C (données).",
            "Débogage USB activé sur le M11.",
            "Wi‑Fi activé sur le M11.",
        ).forEachIndexed { i, s ->
            steps.add(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text("${i + 1}", 15f, accent, bold = true), LinearLayout.LayoutParams(dp(24), WRAP_CONTENT))
                addView(text(s, 15f, onSurface), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, top = 10)
        }
        content.add(steps, top = 24)

        button = Button(this).apply {
            text = "Activer ADB Wi‑Fi"
            textSize = 17f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onAccent)
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(28).toFloat() }
            setOnClickListener { start() }
        }
        content.add(button, top = 20, height = dp(56))

        statusText = text("", 14f, muted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        content.add(statusText, top = 12)

        resultCard = card().apply { visibility = View.GONE }
        resultCard.add(text("ADRESSE À METTRE DANS SCRCPY", 12f, muted, bold = true))
        resultText = text("", 26f, accent, bold = true).apply { typeface = Typeface.MONOSPACE }
        resultCard.add(resultText, top = 6)
        resultCard.add(text("Touche pour copier · débranche ensuite le câble", 13f, muted), top = 4)
        resultCard.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adresse", resultText.text))
            Toast.makeText(this, "Adresse copiée", Toast.LENGTH_SHORT).show()
        }
        content.add(resultCard, top = 16)

        val logCard = card()
        logCard.add(text("JOURNAL", 12f, muted, bold = true))
        logView = text("", 13f, onSurface).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        logCard.add(logView, top = 8)
        content.add(logCard, top = 16)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        val side = dp(20)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars()); b.top to b.bottom
            } else @Suppress("DEPRECATION") {
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(side, top + dp(24), side, bottom + dp(16))
            insets
        }
        scroll.clipToPadding = false
        setContentView(scroll)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)

        log("Prêt.")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            deviceFrom(intent)?.let { log("Appareil branché : ${it.productName ?: it.deviceName}") }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun deviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun setBusy(busy: Boolean) = runOnUiThread {
        button.isEnabled = !busy
        button.alpha = if (busy) 0.5f else 1f
        button.text = if (busy) "Connexion en cours…" else "Activer ADB Wi‑Fi"
    }

    private fun setStatus(msg: String, color: Int) = runOnUiThread {
        statusText.text = msg
        statusText.setTextColor(color)
        statusText.visibility = View.VISIBLE
    }

    private fun start() {
        resultCard.visibility = View.GONE
        val dev = usb.deviceList.values.firstOrNull { AdbUsb.findAdbInterface(it) != null }
        if (dev == null) {
            setStatus("Aucun appareil ADB trouvé en USB", err)
            log("Aucun appareil ADB trouvé.\n" +
                "• Câble de données (pas charge seule) ?\n" +
                "• Débogage USB activé sur le M11 ?\n" +
                "• Le Pixel doit être « hôte » : notification USB du Pixel → " +
                "« Cet appareil contrôle le périphérique connecté », ou inverse le câble.")
            return
        }
        setBusy(true)
        setStatus("Connexion…", muted)
        log("Appareil : ${dev.manufacturerName ?: ""} ${dev.productName ?: dev.deviceName}".trim())
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
                adb = AdbUsb(conn, iface) { msg ->
                    log(msg)
                    setStatus("Accepte « Toujours autoriser » sur le M11", accent)
                }
                log("Connexion ADB…")
                val banner = adb.connect(AdbKeys(this))
                log("Connecté (${banner.substringBefore(';').take(40)})")

                val ipOut = adb.exec("shell:ip -f inet addr show wlan0")
                val ip = Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(ipOut)?.groupValues?.get(1)
                log(if (ip != null) "IP Wi‑Fi : $ip" else "IP Wi‑Fi introuvable (Wi‑Fi activé ?)")

                log("Passage en mode TCP…")
                val res = adb.exec("tcpip:$port")
                log(res.trim().ifEmpty { "Commande envoyée." })

                setStatus("C'est bon ! Débranche le câble.", ok)
                runOnUiThread {
                    resultText.text = "${ip ?: "<IP du M11>"}:$port"
                    resultCard.visibility = View.VISIBLE
                    scroll.post { scroll.smoothScrollTo(0, resultCard.top) }
                }
            } catch (e: Exception) {
                setStatus("Erreur : ${e.message}", err)
                log("Erreur : ${e.message}")
            } finally {
                adb?.close()
                setBusy(false)
            }
        }.start()
    }

    private fun log(msg: String) = runOnUiThread {
        logView.append(if (logView.text.isEmpty()) msg else "\n$msg")
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "fr.m11adb.USB_PERMISSION"
    }
}
