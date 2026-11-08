package com.example.nettracker

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object WifiStorage {
    private const val PREFS_NAME = "wifi_clock_history"
    private const val KEY_ACTIVE_START = "active_wifi_start"
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    @Synchronized
    fun syncState(context: Context, forceState: Boolean? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val isOn = forceState ?: isConnected(context)
        val activeStart = prefs.getLong(KEY_ACTIVE_START, 0L)

        if (activeStart in 1 until now) {
            recordIntervalAcrossDays(prefs, activeStart, now)
        }

        prefs.edit()
            .putLong(KEY_ACTIVE_START, if (isOn) now else 0L)
            .apply()
    }

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
        val isOn = isConnected(context)
        prefs.edit()
            .clear()
            .putLong(KEY_ACTIVE_START, if (isOn) System.currentTimeMillis() else 0L)
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
            val isOn = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            WifiStorage.syncState(applicationContext, isOn)
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
            .setContentTitle("ON / OFF Monitor")
            .setContentText("Фоновый учёт активен")
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
    private lateinit var dashboardView: DashboardChartView
    private lateinit var daySpinner: Spinner
    private var selectedDayKey: String = ""
    private val uiHandler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            WifiStorage.syncState(this@MainActivity)
            val days = WifiStorage.getAvailableDays(this@MainActivity)
            val isToday = selectedDayKey.isEmpty() || selectedDayKey == days.first()
            updateDashboard(WifiStorage.isConnected(this@MainActivity), isToday)
            uiHandler.postDelayed(this, 15000L)
        }
    }

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
            setPadding(28, 24, 28, 24)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        val titleLabel = TextView(this).apply {
            text = "Архив дат:"
            textSize = 15f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 14, 0)
        }

        val spinnerBg = GradientDrawable().apply {
            setColor(Color.parseColor("#CBD5E1"))
            cornerRadius = 12f
        }

        daySpinner = Spinner(this).apply {
            background = spinnerBg
            setPopupBackgroundDrawable(ColorDrawable(Color.parseColor("#E2E8F0")))
            setPadding(16, 6, 16, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 16
            }
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

        dashboardView = DashboardChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(topBar)
        root.addView(dashboardView)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
        uiHandler.postDelayed(tickRunnable, 15000L)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(tickRunnable)
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Очистить историю?")
            .setMessage("Все записанные дни будут удалены.")
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
        val isOn = WifiStorage.isConnected(this)
        val days = WifiStorage.getAvailableDays(this)
        val displayNames = days.mapIndexed { idx, key ->
            val rawDate = key.removePrefix("day_")
            if (idx == 0) "$rawDate (Сегодня)" else rawDate
        }

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, displayNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.parseColor("#0F172A"))
                v.textSize = 15f
                v.setPadding(12, 10, 12, 10)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.parseColor("#0F172A"))
                v.setBackgroundColor(
                    if (position == daySpinner.selectedItemPosition) Color.parseColor("#CBD5E1")
                    else Color.parseColor("#E2E8F0")
                )
                v.textSize = 15f
                v.setPadding(24, 18, 24, 18)
                return v
            }
        }
        daySpinner.adapter = adapter

        if (selectedDayKey.isEmpty() || !days.contains(selectedDayKey)) {
            selectedDayKey = days.first()
        } else {
            daySpinner.setSelection(days.indexOf(selectedDayKey))
        }

        daySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedDayKey = days[pos]
                updateDashboard(WifiStorage.isConnected(this@MainActivity), selectedDayKey == days.first())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateDashboard(isOn, selectedDayKey == days.first())
    }

    private fun updateDashboard(isOn: Boolean, isToday: Boolean) {
        val intervals = WifiStorage.getIntervalsForDay(this, selectedDayKey)
        dashboardView.setData(intervals, isOn, isToday)
    }
}

class DashboardChartView(context: Context) : View(context) {
    private var intervals: List<IntArray> = emptyList()
    private var isOnNow: Boolean = false
    private var isToday: Boolean = true

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.FILL
    }
    private val redOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
    }
    private val greenOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
    }
    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
    }
    private val activeRingBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val greenArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        strokeWidth = 2.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textAlign = Paint.Align.CENTER
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }
    private val centerStatusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Контур и заливка для активной часовой стрелки
    private val handOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#090D16")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val handOutlineCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#090D16")
        style = Paint.Style.FILL
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val handCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()
    private val arcRect = RectF()

    fun setData(newIntervals: List<IntArray>, connected: Boolean, todayFlag: Boolean) {
        intervals = newIntervals
        isOnNow = connected
        isToday = todayFlag
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cal = Calendar.getInstance()
        val nowSec = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                cal.get(Calendar.MINUTE) * 60 +
                cal.get(Calendar.SECOND)

        val elapsedSec = if (isToday) nowSec.coerceIn(0, 86400) else 86400

        var totalOnSec = 0
        for (pair in intervals) {
            val s = pair[0].coerceIn(0, elapsedSec)
            val e = pair[1].coerceIn(0, elapsedSec)
            if (e > s) totalOnSec += (e - s)
        }
        val totalOffSec = (elapsedSec - totalOnSec).coerceAtLeast(0)

        // ================= 1. ВЕРХНИЙ ПРЯМОЛИНЕЙНЫЙ ГРАФИК (0..24, ~1/4 экрана) =================
        val topAreaHeight = h * 0.25f
        val padX = (w * 0.03f).coerceAtLeast(24f)
        val barLeft = padX
        val barRight = w - padX
        val barWidth = (barRight - barLeft).coerceAtLeast(100f)

        statusPaint.textSize = (topAreaHeight * 0.19f).coerceIn(24f, 44f)
        statsPaint.textSize = (topAreaHeight * 0.15f).coerceIn(20f, 34f)
        labelPaint.textSize = (topAreaHeight * 0.13f).coerceIn(18f, 30f)

        val headerY = topAreaHeight * 0.24f

        statusPaint.color = if (isOnNow) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
        canvas.drawText(if (isOnNow) "● ON" else "● OFF", barLeft, headerY, statusPaint)

        if (!isToday) {
            val onH = totalOnSec / 3600
            val onM = (totalOnSec % 3600) / 60
            val offH = totalOffSec / 3600
            val offM = (totalOffSec % 3600) / 60
            val onPct = ((totalOnSec / 86400f) * 100f).toInt()
            val statsText = "24ч итог — ON: ${onH}ч ${onM}м ($onPct%)  |  OFF: ${offH}ч ${offM}м"
            canvas.drawText(statsText, barRight, headerY, statsPaint)
        }

        val barTop = topAreaHeight * 0.36f
        val barBottom = topAreaHeight * 0.68f

        rect.set(barLeft, barTop, barRight, barBottom)
        canvas.drawRoundRect(rect, 8f, 8f, trackPaint)

        if (elapsedSec > 0) {
            val elapsedRight = barLeft + (elapsedSec / 86400f) * barWidth
            rect.set(barLeft, barTop, elapsedRight, barBottom)
            canvas.drawRect(rect, redOffPaint)
        }

        for (pair in intervals) {
            val x1 = barLeft + (pair[0] / 86400f) * barWidth
            val x2 = barLeft + (pair[1] / 86400f) * barWidth
            if (x2 > x1) {
                rect.set(x1, barTop, max(x2, x1 + 2f), barBottom)
                canvas.drawRect(rect, greenOnPaint)
            }
        }

        val tickBottom = barBottom + (topAreaHeight * 0.08f)
        val textY = tickBottom + labelPaint.textSize * 1.05f
        for (hour in 0..24) {
            val x = barLeft + (hour / 24f) * barWidth
            val isMajor = hour % 6 == 0
            tickPaint.strokeWidth = if (isMajor) 3.5f else 2f
            tickPaint.color = if (isMajor) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
            canvas.drawLine(x, barBottom, x, tickBottom, tickPaint)

            if (w >= 700f || hour % 2 == 0) {
                canvas.drawText(hour.toString(), x, textY, labelPaint)
            }
        }

        // ================= 2. ДВА КРУГЛЫХ ЧАСОВЫХ ГРАФИКА РЯДОМ =================
        val bottomTop = topAreaHeight + 8f
        val bottomHeight = h - bottomTop
        val halfW = w / 2f

        val isFirstHalfActive = isToday && (nowSec < 43200)
        val isSecondHalfActive = isToday && (nowSec >= 43200)

        // Левые часы (разметка 1..12)
        drawClockDial(
            canvas = canvas,
            cx = halfW * 0.5f,
            cy = bottomTop + bottomHeight * 0.52f,
            availableW = halfW,
            availableH = bottomHeight,
            periodStartSec = 0,
            periodEndSec = 43200,
            isSecondHalf = false,
            isActiveClock = isFirstHalfActive,
            nowSec = nowSec
        )

        // Правые часы (разметка 13..24)
        drawClockDial(
            canvas = canvas,
            cx = halfW * 1.5f,
            cy = bottomTop + bottomHeight * 0.52f,
            availableW = halfW,
            availableH = bottomHeight,
            periodStartSec = 43200,
            periodEndSec = 86400,
            isSecondHalf = true,
            isActiveClock = isSecondHalfActive,
            nowSec = nowSec
        )
    }

    private fun drawClockDial(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        availableW: Float,
        availableH: Float,
        periodStartSec: Int,
        periodEndSec: Int,
        isSecondHalf: Boolean,
        isActiveClock: Boolean,
        nowSec: Int
    ) {
        val minDim = min(availableW, availableH)
        val strokeW = (minDim * 0.11f).coerceIn(20f, 60f)
        val radius = (minDim * 0.5f) - strokeW * 1.45f
        if (radius <= 20f) return

        ringBgPaint.strokeWidth = strokeW
        greenArcPaint.strokeWidth = strokeW
        labelPaint.textSize = (minDim * 0.045f).coerceIn(18f, 34f)
        centerStatusPaint.textSize = (minDim * 0.052f).coerceIn(20f, 38f)

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Тёмное базовое кольцо
        canvas.drawOval(arcRect, ringBgPaint)

        // Зелёные дуги ON
        for (pair in intervals) {
            val s = max(pair[0], periodStartSec)
            val e = min(pair[1], periodEndSec)
            if (e > s) {
                val startAngle = -90f + ((s - periodStartSec) / 43200f) * 360f
                val sweepAngle = ((e - s) / 43200f) * 360f
                canvas.drawArc(arcRect, startAngle, sweepAngle.coerceAtLeast(0.8f), false, greenArcPaint)
            }
        }

        if (isActiveClock) {
            canvas.drawCircle(cx, cy, radius + strokeW * 0.56f, activeRingBorderPaint)
        }

        // Деления и цифры: 1..12 на левом, 13..24 на правом
        val innerR = radius - strokeW / 2f
        val outerR = radius + strokeW / 2f
        val textR = outerR + strokeW * 0.55f

        for (h in 0 until 12) {
            val deg = -90.0 + h * 30.0
            val rad = Math.toRadians(deg)
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            tickPaint.strokeWidth = if (h % 3 == 0) 4f else 2f
            tickPaint.color = if (h % 3 == 0) Color.parseColor("#94A3B8") else Color.parseColor("#334155")
            canvas.drawLine(
                cx + innerR * cosA,
                cy + innerR * sinA,
                cx + outerR * cosA,
                cy + outerR * sinA,
                tickPaint
            )

            val hourStr = if (!isSecondHalf) {
                if (h == 0) "12" else h.toString()
            } else {
                if (h == 0) "24" else (h + 12).toString()
            }

            val textOffsetY = (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(hourStr, cx + textR * cosA, cy + textR * sinA - textOffsetY, labelPaint)
        }

        // Активная стрелка с тёмной обводкой (контуром), чтобы не сливалась с зелёной шкалой
        if (isActiveClock) {
            val localSec = (nowSec - periodStartSec).coerceIn(0, 43200)
            val handDeg = -90.0 + (localSec / 43200.0) * 360.0
            val handRad = Math.toRadians(handDeg)

            val handColor = if (isOnNow) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
            val handWidth = (strokeW * 0.24f).coerceIn(7f, 15f)
            val outlineExtra = (strokeW * 0.16f).coerceIn(5f, 10f)
            val centerRadius = (strokeW * 0.32f).coerceIn(10f, 18f)

            handOutlinePaint.strokeWidth = handWidth + outlineExtra
            handPaint.color = handColor
            handPaint.strokeWidth = handWidth
            handCenterPaint.color = handColor

            // Кончик стрелки заходит на середину кольца, а тёмный контур отделяет её от зелёной дуги
            val handLength = radius
            val tipX = cx + handLength * cos(handRad).toFloat()
            val tipY = cy + handLength * sin(handRad).toFloat()

            // Статус ON / OFF внутри активного циферблата
            centerStatusPaint.color = handColor
            canvas.drawText(if (isOnNow) "ON" else "OFF", cx, cy - radius * 0.32f, centerStatusPaint)

            // 1. Рисуем тёмный контур стрелки и центрального кружка
            canvas.drawLine(cx, cy, tipX, tipY, handOutlinePaint)
            canvas.drawCircle(cx, cy, centerRadius + outlineExtra * 0.55f, handOutlineCirclePaint)

            // 2. Рисуем саму цветную стрелку поверх тёмного контура
            canvas.drawLine(cx, cy, tipX, tipY, handPaint)
            canvas.drawCircle(cx, cy, centerRadius, handCenterPaint)
        }
    }
}
