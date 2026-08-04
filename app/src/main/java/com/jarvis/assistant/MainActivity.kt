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
import net.objecthunter.exp4j.ExpressionBuilder
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import java.util.*
import android.provider.ContactsContract
import android.net.Uri
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var logText: TextView
    private lateinit var statusText: TextView
    private var mediaPlayer: MediaPlayer? = null
    private var flashOn = false
    private val client = OkHttpClient()

    // ---- ضيف مفتاح Google Gemini الخاص فيك هون بين علامتي التنصيص ----
    // احصل عليه مجانًا من: https://aistudio.google.com/apikey
    // خليه فاضي "" إذا بدك تبقي جارفس أوفلاين بالكامل
    private val GEMINI_API_KEY = "AQ.Ab8RN6KbrDfuyM3-2804zqWf_KrD3FJvK5jrqj20UG8R3tJNXw' \
  -d '{
    "contents": [
      {
        "parts": [
          {
            "text": "Explain how AI works in a few words"
          }
        ]
      }
    ]
  }'"

    // ---- ضيف مفتاح Google Maps هون لمسافات حقيقية بالطريق ----
    // احصل عليه من: https://console.cloud.google.com/google/maps-apis
    // خليه فاضي "" إذا بدك يستخدم حساب تقريبي (خط مستقيم) بدون مفتاح
    private val GOOGLE_MAPS_API_KEY = ""

    companion object {
        private const val REQ_SPEECH = 100
        private const val REQ_PERMISSIONS = 200
        private const val REQ_CONTACTS = 300
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
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS
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
            cmd.contains("افتح انستقرام") || cmd.contains("افتح انستغرام") -> {
                openApp("com.instagram.android", "انستقرام")
            }
            cmd.contains("افتح يوتيوب") || cmd.contains("افتح يوتوب") -> {
                openApp("com.google.android.youtube", "يوتيوب")
            }
            cmd.contains("افتح فيسبوك") -> {
                openApp("com.facebook.katana", "فيسبوك")
            }
            cmd.contains("اتصل ب") -> {
                val name = extractNameAfter(cmd, "اتصل ب")
                callContact(name)
            }
            cmd.contains("رسمة اليوم") || cmd.contains("اقترح لي رسمة") -> {
                respond(suggestDrawing())
            }
            cmd.contains("فطور") -> {
                respond(suggestBreakfast())
            }
            cmd.contains("المسافة من") || cmd.contains("كم المسافة") -> {
                handleDistanceQuery(cmd)
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
            val resId = resources.getIdentifier("sample_music", "raw", packageName)
            if (resId == 0) {
                log("ما لقيت ملف موسيقى. ضيف mp3 باسم sample_music.mp3 داخل res/raw")
                return
            }
            mediaPlayer = MediaPlayer.create(this, resId)
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

        if (GEMINI_API_KEY.isNotBlank()) {
            askGemini(cmd)
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

    private fun askGemini(message: String) {
        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("parts", JSONArray().put(
                        JSONObject().apply { put("text", message) }
                    ))
                }
            ))
        }

        val body = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            jsonBody.toString()
        )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_API_KEY"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { respond("ما قدرت أوصل للنت") }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseText = response.body?.string() ?: ""
                    val json = JSONObject(responseText)
                    if (json.has("error")) {
                        val errMsg = json.getJSONObject("error").optString("message", "خطأ غير معروف")
                        runOnUiThread { respond("صار خطأ من Gemini: $errMsg") }
                        return
                    }
                    val reply = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    runOnUiThread { respond(reply.trim()) }
                } catch (e: Exception) {
                    runOnUiThread { respond("ما قدرت أفهم رد Gemini") }
                }
            }
        })
    }

    // ---------------- Open apps ----------------

    private fun openApp(packageName: String, appName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
            respond("جاري فتح $appName")
        } else {
            respond("$appName مش مثبت على جهازك")
        }
    }

    // ---------------- Call a contact ----------------

    private fun extractNameAfter(cmd: String, marker: String): String {
        val idx = cmd.indexOf(marker)
        if (idx == -1) return ""
        return cmd.substring(idx + marker.length).trim()
    }

    private fun callContact(name: String) {
        if (name.isBlank()) {
            respond("قلي مين بدك أتصل فيه")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            respond("بدي إذن قراءة جهات الاتصال أول، جرب مرة تانية")
            return
        }
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val contactId = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val phoneCursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )
                phoneCursor?.use { pc ->
                    if (pc.moveToFirst()) {
                        val number = pc.getString(
                            pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        )
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                        startActivity(dialIntent)
                        respond("جاري الاتصال بـ $name")
                    } else {
                        respond("ما لقيت رقم هاتف لـ $name")
                    }
                }
            } else {
                respond("ما لقيت جهة اتصال باسم $name")
            }
        }
    }

    // ---------------- Suggestions ----------------

    private fun suggestDrawing(): String {
        val ideas = listOf(
            "ارسم منظر طبيعي فيه جبال وبحر",
            "جرب ترسم بورتريه لشخص قريب منك",
            "ارسم حيوان أليف بأسلوب كرتوني",
            "جرب رسم مدينة خيالية من خيالك",
            "ارسم لوحة تجريدية بالألوان يلي بتحبها"
        )
        return "فكرة رسمة اليوم: ${ideas.random()}"
    }

    private fun suggestBreakfast(): String {
        val ideas = listOf(
            "بيض مع زعتر وزيت زيتون وخبز طازة",
            "فول مدمس مع خضرة وليمون",
            "لبنة مع خيار وطماطم",
            "مناقيش زعتر أو جبنة",
            "شكشوكة بالبيض والبندورة"
        )
        return "اقتراح فطور اليوم: ${ideas.random()}"
    }

    // ---------------- Distance between cities ----------------

    private val cityCoordinates = mapOf(
        "دمشق" to Pair(33.5138, 36.2765),
        "حلب" to Pair(36.2021, 37.1343),
        "حمص" to Pair(34.7324, 36.7137),
        "حماة" to Pair(35.1318, 36.7578),
        "اللاذقية" to Pair(35.5317, 35.7911),
        "طرطوس" to Pair(34.8890, 35.8866),
        "إدلب" to Pair(35.9306, 36.6339),
        "درعا" to Pair(32.6189, 36.1021),
        "بيروت" to Pair(33.8938, 35.5018),
        "عمان" to Pair(31.9454, 35.9284),
        "القدس" to Pair(31.7683, 35.2137),
        "القاهرة" to Pair(30.0444, 31.2357),
        "بغداد" to Pair(33.3152, 44.3661),
        "الرياض" to Pair(24.7136, 46.6753),
        "اسطنبول" to Pair(41.0082, 28.9784),
        "باريس" to Pair(48.8566, 2.3522),
        "لندن" to Pair(51.5074, -0.1278),
        "الجزائر" to Pair(36.7538, 3.0588),
        "تونس" to Pair(36.8065, 10.1815),
        "الرباط" to Pair(34.0209, -6.8416),
        "الدار البيضاء" to Pair(33.5731, -7.5898),
        "طرابلس" to Pair(32.8872, 13.1913),
        // ولايات الجزائر (58 ولاية)
        "أدرار" to Pair(27.8702, -0.2911),
        "الشلف" to Pair(36.1650, 1.3350),
        "الأغواط" to Pair(33.8000, 2.8650),
        "أم البواقي" to Pair(35.8770, 7.1170),
        "باتنة" to Pair(35.5560, 6.1740),
        "بجاية" to Pair(36.7530, 5.0840),
        "بسكرة" to Pair(34.8500, 5.7280),
        "بشار" to Pair(31.6150, -2.2180),
        "البليدة" to Pair(36.4700, 2.8280),
        "البويرة" to Pair(36.3730, 3.9020),
        "تمنراست" to Pair(22.7850, 5.5220),
        "تبسة" to Pair(35.4040, 8.1240),
        "تلمسان" to Pair(34.8780, -1.3150),
        "تيارت" to Pair(35.3710, 1.3170),
        "تيزي وزو" to Pair(36.7120, 4.0450),
        "الجلفة" to Pair(34.6730, 3.2630),
        "جيجل" to Pair(36.8220, 5.7660),
        "سطيف" to Pair(36.1910, 5.4080),
        "سعيدة" to Pair(34.8300, 0.1510),
        "سكيكدة" to Pair(36.8760, 6.9090),
        "سيدي بلعباس" to Pair(35.1900, -0.6300),
        "عنابة" to Pair(36.9000, 7.7670),
        "قالمة" to Pair(36.4620, 7.4270),
        "قسنطينة" to Pair(36.3650, 6.6150),
        "المدية" to Pair(36.2640, 2.7540),
        "مستغانم" to Pair(35.9350, 0.0890),
        "المسيلة" to Pair(35.7050, 4.5410),
        "معسكر" to Pair(35.3970, 0.1400),
        "ورقلة" to Pair(31.9490, 5.3250),
        "وهران" to Pair(35.6970, -0.6330),
        "البيض" to Pair(33.6860, 1.0190),
        "إليزي" to Pair(26.4830, 8.4670),
        "برج بوعريريج" to Pair(36.0730, 4.7610),
        "بومرداس" to Pair(36.7660, 3.4770),
        "الطارف" to Pair(36.7670, 8.3130),
        "تندوف" to Pair(27.6710, -8.1470),
        "تيسمسيلت" to Pair(35.6070, 1.8110),
        "الوادي" to Pair(33.3680, 6.8670),
        "خنشلة" to Pair(35.4360, 7.1430),
        "سوق أهراس" to Pair(36.2860, 7.9510),
        "تيبازة" to Pair(36.5890, 2.4480),
        "ميلة" to Pair(36.4500, 6.2640),
        "عين الدفلى" to Pair(36.2640, 1.9660),
        "النعامة" to Pair(33.2660, -0.3170),
        "عين تموشنت" to Pair(35.2980, -1.1400),
        "غرداية" to Pair(32.4910, 3.6730),
        "غليزان" to Pair(35.7370, 0.5560),
        "تيميمون" to Pair(29.2630, 0.2310),
        "برج باجي مختار" to Pair(21.3280, 0.9560),
        "أولاد جلال" to Pair(34.4120, 5.0680),
        "بني عباس" to Pair(30.1300, -2.1640),
        "عين صالح" to Pair(27.1940, 2.4780),
        "عين قزام" to Pair(19.5730, 5.7710),
        "تقرت" to Pair(33.1060, 6.0580),
        "جانت" to Pair(24.5540, 9.4830),
        "المغير" to Pair(33.9450, 5.9270),
        "المنيعة" to Pair(30.5790, 2.8820)
    )

    private fun handleDistanceQuery(cmd: String) {
        val regex = Regex("""من\s+(\S+)\s+(?:الى|إلى)\s+(\S+)""")
        val match = regex.find(cmd)
        if (match == null) {
            respond("قلي المسافة بهالصيغة: كم المسافة من دمشق الى حلب")
            return
        }
        val cityA = match.groupValues[1]
        val cityB = match.groupValues[2]

        if (GOOGLE_MAPS_API_KEY.isNotBlank()) {
            respond("بحسب...")
            askGoogleDistance(cityA, cityB)
        } else {
            respond(calculateDistanceOffline(cityA, cityB))
        }
    }

    private fun askGoogleDistance(cityA: String, cityB: String) {
        val originEnc = java.net.URLEncoder.encode(cityA, "UTF-8")
        val destEnc = java.net.URLEncoder.encode(cityB, "UTF-8")
        val url = "https://maps.googleapis.com/maps/api/distancematrix/json" +
                "?origins=$originEnc&destinations=$destEnc&key=$GOOGLE_MAPS_API_KEY"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { respond(calculateDistanceOffline(cityA, cityB)) }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseText = response.body?.string() ?: ""
                    val json = JSONObject(responseText)
                    val status = json.optString("status")
                    if (status != "OK") {
                        runOnUiThread { respond(calculateDistanceOffline(cityA, cityB)) }
                        return
                    }
                    val element = json.getJSONArray("rows")
                        .getJSONObject(0)
                        .getJSONArray("elements")
                        .getJSONObject(0)
                    val elementStatus = element.optString("status")
                    if (elementStatus != "OK") {
                        runOnUiThread { respond(calculateDistanceOffline(cityA, cityB)) }
                        return
                    }
                    val distanceText = element.getJSONObject("distance").getString("text")
                    val durationText = element.getJSONObject("duration").getString("text")
                    runOnUiThread {
                        respond("المسافة من $cityA الى $cityB حوالي $distanceText بالسيارة، ووقت الرحلة تقريبًا $durationText")
                    }
                } catch (e: Exception) {
                    runOnUiThread { respond(calculateDistanceOffline(cityA, cityB)) }
                }
            }
        })
    }

    private fun calculateDistanceOffline(cityA: String, cityB: String): String {
        val coordA = cityCoordinates[cityA]
        val coordB = cityCoordinates[cityB]
        if (coordA == null || coordB == null) {
            return "للأسف ما عندي إحداثيات لهاي المدينة حاليًا"
        }
        val distanceKm = haversine(coordA.first, coordA.second, coordB.first, coordB.second)
        return "المسافة من $cityA الى $cityB حوالي ${distanceKm.toInt()} كم (خط مستقيم تقريبي)"
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusKm * c
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
