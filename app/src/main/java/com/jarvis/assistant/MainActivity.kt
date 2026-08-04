package com.jarvis.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import net.objecthunt.exp4j.ExpressionBuilder
import okhttp3.*
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var logText: TextView
    private lateinit var statusText: TextView
    private var mediaPlayer: MediaPlayer? = null
    private var flashOn = false
    private val client = OkHttpClient()

    // ---- CHANGE THIS if you want online fallback replies ----
    // Point this at your own backend or a hosted LLM endpoint.
    // Leave blank to keep the assistant fully offline.
    private val ONLINE_CHAT_ENDPOINT = ""

    companion object {
        private const val REQ_SPEECH = 100
        private const val REQ_PERMISSIONS = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        tts = TextToSpeech(this, this)

        requestNeededPermissions()

        findViewById<Button>(R.id.micButton).setOnClickListener {
            startListening()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("ar")
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        for (p in listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
        try {
            startActivityForResult(intent, REQ_SPEECH)
        } catch (e: Exception) {
            log("ما في تطبيق تعرف صوتي متاح على هالجهاز")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = results?.get(0) ?: return
            log("أنت: $spoken")
            handleCommand(spoken)
        }
    }

    // ---------------- Command routing ----------------

    private fun handleCommand(text: String) {
        val cmd = text.lowercase(Locale("ar")).trim()

        when {
            cmd.contains("شغل الفلاش") || cmd.contains("افتح الفلاش") -> {
                setFlashlight(true)
                respond("تم تشغيل الفلاش")
            }
            cmd.contains("طفي الفلاش") || cmd.contains("اطفي الفلاش") -> {
                setFlashlight(false)
                respond("تم إطفاء الفلاش")
            }
            cmd.contains("شغل موسيقى") || cmd.contains("شغل الموسيقى") -> {
                playMusic()
                respond("تشغيل الموسيقى")
            }
            cmd.contains("وقف الموسيقى") || cmd.contains("طفي الموسيقى") -> {
                stopMusic()
                respond("تم إيقاف الموسيقى")
            }
            cmd.contains("احسب") || containsMath(cmd) -> {
                val result = calculate(cmd)
                respond(result)
            }
            cmd.contains("ذكرني") || cmd.contains("تذكير") -> {
                // Expects something like: "ذكرني بعد 10 دقايق اشرب مي"
                val minutes = extractMinutes(cmd) ?: 5
                scheduleReminder(minutes, cmd)
                respond("تمام، رح ذكرك بعد $minutes دقيقة")
            }
            else -> {
                respond(chatReply(cmd))
            }
        }
    }

    // ---------------- Flashlight ----------------

    private fun setFlashlight(on: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cameraManager.setTorchMode(cameraId, on)
            flashOn = on
        } catch (e: Exception) {
            log("تعذر التحكم بالفلاش: ${e.message}")
        }
    }

    // ---------------- Music ----------------
    // Place an mp3 file named "sample_music.mp3" inside app/src/main/res/raw/

    private fun playMusic() {
        stopMusic()
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.sample_music)
            mediaPlayer?.start()
        } catch (e: Exception) {
            log("ما لقيت ملف موسيقى. ضيف mp3 باسم sample_music.mp3 داخل res/raw")
        }
    }

    private fun stopMusic() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ---------------- Calculator ----------------

    private fun containsMath(cmd: String): Boolean {
        return cmd.any { it.isDigit() } && (cmd.contains("+") || cmd.contains("-") ||
                cmd.contains("*") || cmd.contains("/") || cmd.contains("زائد") ||
                cmd.contains("ناقص") || cmd.contains("ضرب") || cmd.contains("قسمة"))
    }

    private fun calculate(cmd: String): String {
        return try {
            var expr = cmd
                .replace("احسب", "")
                .replace("زائد", "+")
                .replace("ناقص", "-")
                .replace("ضرب", "*")
                .replace("قسمة", "/")
                .trim()
            val result = ExpressionBuilder(expr).build().evaluate()
            "النتيجة هي $result"
        } catch (e: Exception) {
            "ما قدرت أفهم العملية الحسابية"
        }
    }

    // ---------------- Reminders ----------------

    private fun extractMinutes(cmd: String): Int? {
        val regex = Regex("""(\d+)\s*(دقيقة|دقايق|دقائق)""")
        val match = regex.find(cmd) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun scheduleReminder(minutes: Int, message: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java)
        intent.putExtra("message", message)
        val pendingIntent = PendingIntent.getBroadcast(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } catch (e: SecurityException) {
            log("لازم تسمح بصلاحية 'Schedule Exact Alarm' من إعدادات النظام")
        }
    }

    // ---------------- Simple chat (offline rules + optional online fallback) ----------------

    private fun chatReply(cmd: String): String {
        val offlineReply = offlineRules(cmd)
        if (offlineReply != null) return offlineReply

        if (ONLINE_CHAT_ENDPOINT.isNotBlank()) {
            askOnline(cmd)
            return "بفكر..."
        }
        return "ما فهمت عليك تمامًا، جرب صيغة تانية"
    }

    private fun offlineRules(cmd: String): String? {
        return when {
            cmd.contains("مرحبا") || cmd.contains("هلا") || cmd.contains("السلام") ->
                "أهلاً فيك، كيف بقدر أساعدك؟"
            cmd.contains("كيفك") || cmd.contains("شخبارك") ->
                "تمام الحمد لله، وأنت؟"
            cmd.contains("الساعة") ->
                "الساعة هلق ${java.text.SimpleDateFormat("HH:mm").format(Date())}"
            cmd.contains("مين انت") || cmd.contains("شو اسمك") ->
                "أنا جارفس، مساعدك الشخصي"
            else -> null
        }
    }

    private fun askOnline(message: String) {
        val json = """{"message": "$message"}"""
        val body = RequestBody.create("application/json".toMediaTypeOrNull(), json)
        val request = Request.Builder().url(ONLINE_CHAT_ENDPOINT).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { respond("ما قدرت أوصل للنت") }
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = response.body?.string() ?: "ما وصلني رد"
                runOnUiThread { respond(reply) }
            }
        })
    }

    // ---------------- Output helpers ----------------

    private fun respond(text: String) {
        log("جارفس: $text")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun log(text: String) {
        logText.append("\n$text")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        stopMusic()
    }
}
