package com.gamespace.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    private val rate = 16000
    private var pitch = 1.0f
    @Volatile private var recording = false
    private lateinit var gameSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var blockedText: TextView
    private lateinit var memText: TextView
    private lateinit var voiceText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float = 14f, color: Int = Color.parseColor("#9FB3C0"), bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { f() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("gs", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1620"))
            addView(root)
        }
        setContentView(scroll)

        root.addView(tv("Game Space", 30f, Color.WHITE, true))

        // Game mode
        gameSwitch = Switch(this).apply {
            text = "Game mode"
            textSize = 18f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("on", false)
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, on -> setGameMode(on) }
        }
        root.addView(gameSwitch)
        statusText = tv("")
        root.addView(statusText)
        root.addView(btn("Allow message blocking") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(btn("Allow Do Not Disturb") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })

        // Blocked messages
        root.addView(tv("Blocked while gaming", 18f, Color.WHITE, true))
        blockedText = tv("")
        root.addView(blockedText)
        root.addView(btn("Clear list") {
            prefs.edit().putString("blocked", "").apply()
            refresh()
        })

        // Frame booster (memory cleaner)
        root.addView(tv("Booster", 18f, Color.WHITE, true))
        memText = tv("Closes background apps to free memory. It can’t raise a game’s maximum FPS.")
        root.addView(memText)
        root.addView(btn("Free up memory") { freeMemory() })

        // Games
        root.addView(tv("Your games", 18f, Color.WHITE, true))
        val games = installedGames()
        if (games.isEmpty()) root.addView(tv("No games detected."))
        games.forEach { (label, pkg) ->
            root.addView(btn("Play $label") {
                gameSwitch.isChecked = true
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            })
        }

        // Voice changer
        root.addView(tv("Voice changer", 18f, Color.WHITE, true))
        val rg = RadioGroup(this)
        val presets = listOf("Normal" to 1.0f, "Kid" to 1.3f, "Chipmunk" to 1.7f, "Deep" to 0.75f, "Giant" to 0.55f)
        presets.forEachIndexed { i, (name, p) ->
            rg.addView(RadioButton(this).apply {
                text = name
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                setOnClickListener { pitch = p }
                isChecked = (i == 0)
            })
        }
        root.addView(rg)
        voiceText = tv("Hold the button, speak, then let go to hear it back.")
        root.addView(voiceText)
        val talk = Button(this).apply { text = "Hold to speak" }
        talk.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startRecording(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { recording = false; true }
                else -> false
            }
        }
        root.addView(talk)

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun listenerOn(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        statusText.text = "Message blocking: " + (if (listenerOn()) "allowed" else "not allowed yet") +
            "\nDo Not Disturb: " + (if (nm.isNotificationPolicyAccessGranted) "allowed" else "not allowed yet")
        val blocked = getSharedPreferences("gs", MODE_PRIVATE).getString("blocked", "") ?: ""
        blockedText.text = if (blocked.isBlank()) "Nothing blocked yet." else blocked
    }

    private fun setGameMode(on: Boolean) {
        getSharedPreferences("gs", MODE_PRIVATE).edit().putBoolean("on", on).apply()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
        refresh()
    }

    private fun freeMemory() {
        val am = getSystemService(ActivityManager::class.java)
        fun freeMb(): Long {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return mi.availMem / 1048576
        }
        val before = freeMb()
        packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .forEach { am.killBackgroundProcesses(it.packageName) }
        memText.text = "Cleaning…"
        Handler(Looper.getMainLooper()).postDelayed({
            memText.text = "Free memory: $before MB → ${freeMb()} MB"
        }, 1500)
    }

    @Suppress("DEPRECATION")
    private fun installedGames(): List<Pair<String, String>> =
        packageManager.getInstalledApplications(0)
            .filter {
                ((it.flags and ApplicationInfo.FLAG_IS_GAME) != 0 || it.category == ApplicationInfo.CATEGORY_GAME) &&
                    packageManager.getLaunchIntentForPackage(it.packageName) != null
            }
            .map { packageManager.getApplicationLabel(it).toString() to it.packageName }
            .sortedBy { it.first }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        } catch (e: Exception) {
            voiceText.text = "Couldn’t open the microphone."
            return
        }
        recording = true
        voiceText.text = "Recording…"
        Thread {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            rec.startRecording()
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n)
            }
            rec.stop()
            rec.release()
            runOnUiThread { voiceText.text = "Playing…" }
            play(out.toByteArray())
            runOnUiThread { voiceText.text = "Hold the button, speak, then let go to hear it back." }
        }.start()
    }

    private fun play(data: ByteArray) {
        if (data.size < 3200) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(data.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(data, 0, data.size)
        track.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch)
        track.play()
        Thread.sleep(data.size * 1000L / (rate * 2) + 300)
        track.release()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone allowed. Hold the button again.", Toast.LENGTH_SHORT).show()
        }
    }
}

class BlockerService : NotificationListenerService() {

    private val apps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca",
        "com.instagram.android", "com.snapchat.android", "com.discord",
        "com.google.android.apps.messaging", "com.android.mms"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = getSharedPreferences("gs", MODE_PRIVATE)
        if (!p.getBoolean("on", false)) return
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        val cat = n.category
        val isMessage = sbn.packageName in apps ||
            cat == Notification.CATEGORY_MESSAGE ||
            cat == Notification.CATEGORY_EMAIL ||
            cat == Notification.CATEGORY_SOCIAL
        if (!isMessage) return

        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val old = p.getString("blocked", "") ?: ""
        val lines = (listOf("$label: $title") + old.split("\n").filter { it.isNotBlank() }).take(30)
        p.edit().putString("blocked", lines.joinToString("\n")).apply()
        cancelNotification(sbn.key)
    }
}
