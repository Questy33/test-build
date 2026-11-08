package com.example.nettracker

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object WifiStorage {
    private const val PREFS_NAME = "wifi_clock_history"
    private const val KEY_ACTIVE_START = "active_wifi_start"
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    @Synchronized
    fun syncState(context: Context, forceWifiState: Boolean? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val isWifi = forceWifiState ?: isWifiConnected(context)
        val activeStart = prefs.getLong(KEY_ACTIVE_START, 0L)

        if (activeStart in 1 until now) {
            recordIntervalAcrossDays(prefs, activeStart, now)
        }

        prefs.edit()
            .putLong(KEY_ACTIVE_START, if (isWifi) now else 0L)
            .apply()
    }

    // Разбивает интервал Wi-Fi по суткам и склеивает соседние отрезки
    private fun recordIntervalAcrossDays(
        prefs: android.content.SharedPreferences,
        startMs: Long,
        endMs: Long
    ) {
        var cursor = startMs
        val cal = Calendar.getInstance()
        val editor = prefs.edit()

        while (cursor < endMs) {
            cal.timeInMillis = cursor
            val dayKey = "day_" + dayFormat.format(cal.time)

            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStartMs = cal.timeInMillis

            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val dayEndMs = cal.timeInMillis + 1L

            val segEnd = min(endMs, dayEndMs)
            val startSec = ((cursor - dayStartMs) / 1000L).toInt().coerceIn(0, 86400)
            val endSec = ((segEnd - dayStartMs) / 1000L).toInt().coerceIn(0, 86400)

            if (endSec > startSec) {
                val list = loadDayIntervals(prefs, dayKey).toMutableList()
                if (list.isNotEmpty() && startSec - list.last()[1] <= 5) {
                    // Склеиваем с предыдущим интервалом, если разрыв меньше 5 секунд
                    list.last()[1] = maxOf(list.last()[1], endSec)
                } else {
                    list.add(intArrayOf(startSec, endSec))
                }
                editor.putString(dayKey, serializeIntervals(list))
            }
            cursor = segEnd
        }
        editor.apply()
    }

    fun loadDayIntervals(prefs: android.content.SharedPreferences, dayKey: String): List<IntArray> {
        val raw = prefs.getString(dayKey, null) ?: return emptyList()
        val result = mutableListOf<IntArray>()
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                val s = pair.optInt(0, 0).coerceIn(0, 86400)
                val e = pair.optInt(1, 0).coerceIn(0, 86400)
                if (e > s) result.add(intArrayOf(s, e))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeIntervals(list: List<IntArray>): String {
        val root = JSONArray()
        for (pair in list) {
            val arr = JSONArray()
            arr.put(pair[0])
            arr.put(pair[1])
            root.put(arr)
        }
        return root.toString()
    }

    fun getAvailableDays(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = "day_" + dayFormat.format(Date())
        val keys = prefs.all.keys.filter { it.startsWith("day_") }.toMutableSet()
        keys.add(today)
        return keys.sortedDescending()
    }

    fun getIntervalsForDay(context: Context, dayKey: String): List<IntArray> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return loadDayIntervals(prefs, dayKey)
    }

    @Synchronized
    fun clearAllHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isWifi = isWifiConnected(context)
        prefs.edit()
            .clear()
            .putLong(KEY_ACTIVE_START, if (isWifi) System.currentTimeMillis() else 0L)
            .apply()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val srv = Intent(context, NetMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(srv)
            } else {
                context.startService(srv)
            }
        }
    }
}

class NetMonitorService : Service() {
    private lateinit var cm: ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            WifiStorage.syncState(applicationContext)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            WifiStorage.syncState(applicationContext, isWifi)
        }

        override fun onLost(network: Network) {
            WifiStorage.syncState(applicationContext)
            mainHandler.postDelayed({ WifiStorage.syncState(applicationContext) }, 600)
        }
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        startForegroundNotification()
        WifiStorage.syncState(this)
        cm.registerDefaultNetworkCallback(netCallback)
    }

    private fun startForegroundNotification() {
        val channelId = "wifi_clock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Учёт времени Wi-Fi",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

        val notif = builder
            .setContentTitle("Мониторинг Wi-Fi активен")
            .setContentText("Запись времени подключения к Wi-Fi")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        WifiStorage.syncState(this)
        try { cm.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class MainActivity : Activity() {
    private lateinit var clockView: CircularWifiClockView
    private lateinit var daySpinner: Spinner
    private lateinit var intervalsText: TextView
    private var selectedDayKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val srv = Intent(this, NetMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(srv)
        } else {
            startService(srv)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(28, 28, 28, 28)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleLabel = TextView(this).apply {
            text = "Дата:"
            textSize = 16f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 12, 0)
        }

        daySpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val refreshBtn = Button(this).apply {
            text = "Обновить"
            setOnClickListener { refreshUI() }
        }

        val clearBtn = Button(this).apply {
            text = "Очистить"
            setOnClickListener { confirmClearHistory() }
        }

        topBar.addView(titleLabel)
        topBar.addView(daySpinner)
        topBar.addView(refreshBtn)
        topBar.addView(clearBtn)

        clockView = CircularWifiClockView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        intervalsText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 4)
        }

        root.addView(topBar)
        root.addView(clockView)
        root.addView(intervalsText)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Очистить историю?")
            .setMessage("Все записанные дни и интервалы подключений будут удалены.")
            .setPositiveButton("Очистить") { _, _ ->
                WifiStorage.clearAllHistory(this)
                selectedDayKey = ""
                refreshUI()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun refreshUI() {
        WifiStorage.syncState(this)
        val isWifi = WifiStorage.isWifiConnected(this)
        val days = WifiStorage.getAvailableDays(this)
        val displayNames = days.mapIndexed { idx, key ->
            val rawDate = key.removePrefix("day_")
            if (idx == 0) "$rawDate (Сегодня)" else rawDate
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayNames)
        daySpinner.adapter = adapter

        if (selectedDayKey.isEmpty() || !days.contains(selectedDayKey)) {
            selectedDayKey = days.first()
        } else {
            daySpinner.setSelection(days.indexOf(selectedDayKey))
        }

        daySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedDayKey = days[pos]
                updateClockForSelectedDay(isWifi, selectedDayKey == days.first())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateClockForSelectedDay(isWifi, selectedDayKey == days.first())
    }

    private fun updateClockForSelectedDay(isWifi: Boolean, isToday: Boolean) {
        val intervals = WifiStorage.getIntervalsForDay(this, selectedDayKey)
        clockView.setData(intervals, isWifi, isToday)

        if (intervals.isEmpty()) {
            intervalsText.text = "За выбранный день подключений к Wi-Fi не зафиксировано"
        } else {
            val formatted = intervals.joinToString(",  ") { pair ->
                "${formatSec(pair[0])}–${formatSec(pair[1])}"
            }
            intervalsText.text = "Интервалы Wi-Fi: $formatted"
        }
    }

    private fun formatSec(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return String.format(Locale.US, "%02d:%02d", h, m)
    }
}

// Круглый 24-часовой график с зелёными зонами Wi-Fi
class CircularWifiClockView(context: Context) : View(context) {
    private var intervals: List<IntArray> = emptyList()
    private var isWifiNow: Boolean = false
    private var isToday: Boolean = true

    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
    }
    private val wifiArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        strokeWidth = 3f
    }
    private val hourTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textAlign = Paint.Align.CENTER
    }
    private val centerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        textAlign = Paint.Align.CENTER
    }
    private val nowMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.FILL
    }
    private val arcRect = RectF()

    fun setData(newIntervals: List<IntArray>, wifiConnected: Boolean, todayFlag: Boolean) {
        intervals = newIntervals
        isWifiNow = wifiConnected
        isToday = todayFlag
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val minDim = min(width, height).toFloat()
        if (minDim <= 0f) return

        val ringStroke = (minDim * 0.11f).coerceIn(28f, 80f)
        val radius = (minDim / 2f) - ringStroke * 1.35f

        ringBgPaint.strokeWidth = ringStroke
        wifiArcPaint.strokeWidth = ringStroke
        hourTextPaint.textSize = (minDim * 0.033f).coerceIn(20f, 38f)
        centerTitlePaint.textSize = (minDim * 0.048f).coerceIn(28f, 56f)
        centerSubPaint.textSize = (minDim * 0.036f).coerceIn(22f, 42f)

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 1. Базовое тёмное кольцо 24 часов
        canvas.drawOval(arcRect, ringBgPaint)

        // 2. Зелёные зоны подключения по Wi-Fi (00:00 сверху = -90 градусов)
        var totalWifiSec = 0
        for (pair in intervals) {
            val startSec = pair[0]
            val endSec = pair[1]
            totalWifiSec += (endSec - startSec)

            val startAngle = -90f + (startSec / 86400f) * 360f
            val sweepAngle = ((endSec - startSec) / 86400f) * 360f
            canvas.drawArc(arcRect, startAngle, sweepAngle.coerceAtLeast(0.6f), false, wifiArcPaint)
        }

        // 3. Почасовые деления и подписи 00..23 по кругу
        val innerTickR = radius - ringStroke / 2f
        val outerTickR = radius + ringStroke / 2f
        val labelR = outerTickR + ringStroke * 0.55f

        for (h in 0 until 24) {
            val angleDeg = -90.0 + h * 15.0
            val rad = Math.toRadians(angleDeg)
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            // Деление на кольце
            tickPaint.strokeWidth = if (h % 6 == 0) 4.5f else 2f
            tickPaint.color = if (h % 6 == 0) Color.parseColor("#94A3B8") else Color.parseColor("#334155")
            canvas.drawLine(
                cx + innerTickR * cosA,
                cy + innerTickR * sinA,
                cx + outerTickR * cosA,
                cy + outerTickR * sinA,
                tickPaint
            )

            // Цифра часа (00, 01, ... 23)
            val hourLabel = String.format(Locale.US, "%02d", h)
            val textOffsetY = (hourTextPaint.descent() + hourTextPaint.ascent()) / 2f
            canvas.drawText(
                hourLabel,
                cx + labelR * cosA,
                cy + labelR * sinA - textOffsetY,
                hourTextPaint
            )
        }

        // 4. Маркер текущего времени (синяя точка на кольце, если открыт сегодняшний день)
        if (isToday) {
            val cal = Calendar.getInstance()
            val nowSec = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                    cal.get(Calendar.MINUTE) * 60 +
                    cal.get(Calendar.SECOND)
            val nowRad = Math.toRadians(-90.0 + (nowSec / 86400.0) * 360.0)
            val mx = cx + radius * cos(nowRad).toFloat()
            val my = cy + radius * sin(nowRad).toFloat()
            canvas.drawCircle(mx, my, ringStroke * 0.22f, nowMarkerPaint)
        }

        // 5. Информация в центре круга
        val hours = totalWifiSec / 3600
        val mins = (totalWifiSec % 3600) / 60
        val timeText = "Wi-Fi: ${hours} ч ${mins} мин"
        canvas.drawText(timeText, cx, cy - 10f, centerTitlePaint)

        val statusStr = if (isWifiNow) "Сейчас: подключен к Wi-Fi" else "Сейчас: вне Wi-Fi"
        centerSubPaint.color = if (isWifiNow) Color.parseColor("#10B981") else Color.parseColor("#94A3B8")
        canvas.drawText(statusStr, cx, cy + centerTitlePaint.textSize * 0.95f, centerSubPaint)
    }
}
