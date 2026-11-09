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

    // Порог склеивания случайных обрывов: до 120 секунд (2 минуты) обрыв игнорируется
    private const val MERGE_GAP_SEC = 120
    // Минимальная длительность завершённого включения (случайные всплески короче 60 сек отсеиваются)
    private const val MIN_VALID_BLIP_SEC = 60

    private fun formatDay(timeMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return "day_" + sdf.format(Date(timeMs))
    }

    fun getTodayKey(): String = formatDay(System.currentTimeMillis())

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
            val dayKey = formatDay(cursor)

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
                val rawList = loadRawDayIntervals(prefs, dayKey).toMutableList()
                if (rawList.isNotEmpty() && startSec - rawList.last()[1] <= MERGE_GAP_SEC) {
                    rawList.last()[1] = maxOf(rawList.last()[1], endSec)
                } else {
                    rawList.add(intArrayOf(startSec, endSec))
                }
                editor.putString(dayKey, serializeIntervals(rawList))
            }
            cursor = segEnd
        }
        editor.apply()
    }

    private fun loadRawDayIntervals(prefs: android.content.SharedPreferences, dayKey: String): List<IntArray> {
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

    // Возвращает сглаженные интервалы без минутных обрывов и случайных секундных всплесков
    fun loadDayIntervals(prefs: android.content.SharedPreferences, dayKey: String): List<IntArray> {
        val raw = loadRawDayIntervals(prefs, dayKey)
        if (raw.isEmpty()) return emptyList()

        val isToday = (dayKey == getTodayKey())
        val cal = Calendar.getInstance()
        val nowSec = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                cal.get(Calendar.MINUTE) * 60 +
                cal.get(Calendar.SECOND)

        // 1. Склеиваем интервалы, между которыми разрыв не более MERGE_GAP_SEC (2 минут)
        val merged = mutableListOf<IntArray>()
        for (pair in raw) {
            val s = pair[0]
            val e = pair[1]
            if (merged.isNotEmpty() && s - merged.last()[1] <= MERGE_GAP_SEC) {
                merged.last()[1] = maxOf(merged.last()[1], e)
            } else {
                merged.add(intArrayOf(s, e))
            }
        }

        // 2. Убираем случайные короткие всплески (< 60 сек), кроме текущего идущего подключения или переходов на границе суток
        return merged.filter { pair ->
            val duration = pair[1] - pair[0]
            val touchesMidnight = (pair[0] == 0 || pair[1] >= 86400)
            val isCurrentlyActive = isToday && (nowSec - pair[1] <= MERGE_GAP_SEC)
            duration >= MIN_VALID_BLIP_SEC || touchesMidnight || isCurrentlyActive
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
        val today = getTodayKey()
        val keys = prefs.all.keys.filter { it.startsWith("day_") }.toMutableSet()
        keys.add(today)
        return keys.sortedDescending()
    }

    fun getIntervalsForDay(context: Context, dayKey: String): List<IntArray> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return loadDayIntervals(prefs, dayKey)
    }

    fun getTotalOnSecondsForDay(context: Context, dayKey: String): Int {
        val intervals = getIntervalsForDay(context, dayKey)
        val isToday = (dayKey == getTodayKey())
        val cal = Calendar.getInstance()
        val maxSec = if (isToday) {
            (cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                    cal.get(Calendar.MINUTE) * 60 +
                    cal.get(Calendar.SECOND)).coerceIn(0, 86400)
        } else 86400

        var total = 0
        for (pair in intervals) {
            val s = pair[0].coerceIn(0, maxSec)
            val e = pair[1].coerceIn(0, maxSec)
            if (e > s) total += (e - s)
        }
        return total
    }

    // Вычисляет длительность текущего состояния (ON или OFF) в секундах с учётом перехода через полночь
    fun getCurrentStateDurationSec(context: Context, isOnNow: Boolean): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        val nowSec = (cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                cal.get(Calendar.MINUTE) * 60 +
                cal.get(Calendar.SECOND)).coerceIn(0, 86400)

        val todayKey = getTodayKey()
        val todayIntervals = loadDayIntervals(prefs, todayKey)

        if (isOnNow) {
            var totalOn = 0
            if (todayIntervals.isNotEmpty()) {
                val last = todayIntervals.last()
                if (nowSec - last[1] <= MERGE_GAP_SEC) {
                    totalOn += (nowSec - last[0]).coerceAtLeast(0)
                    if (last[0] > MERGE_GAP_SEC) return totalOn
                } else {
                    return 0
                }
            } else {
                return 0
            }

            // Если подключение началось ещё вчера (до 00:00), прибавляем хвост предыдущих суток
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            var dayStartMs = cal.timeInMillis

            for (back in 1..7) {
                dayStartMs -= 86400_000L
                val prevKey = formatDay(dayStartMs)
                val prevIntervals = loadDayIntervals(prefs, prevKey)
                if (prevIntervals.isEmpty()) break
                val lastPrev = prevIntervals.last()
                if (86400 - lastPrev[1] <= MERGE_GAP_SEC) {
                    totalOn += (86400 - lastPrev[0]).coerceAtLeast(0)
                    if (lastPrev[0] > MERGE_GAP_SEC) break
                } else {
                    break
                }
            }
            return totalOn
        } else {
            // Сейчас OFF — считаем, сколько прошло с конца последнего ON
            if (todayIntervals.isNotEmpty()) {
                val lastEnd = todayIntervals.last()[1]
                return (nowSec - lastEnd).coerceAtLeast(0)
            }

            var totalOff = nowSec
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            var dayStartMs = cal.timeInMillis

            for (back in 1..7) {
                dayStartMs -= 86400_000L
                val prevKey = formatDay(dayStartMs)
                if (!prefs.contains(prevKey)) break
                val prevIntervals = loadDayIntervals(prefs, prevKey)
                if (prevIntervals.isNotEmpty()) {
                    val lastEnd = prevIntervals.last()[1]
                    totalOff += (86400 - lastEnd).coerceAtLeast(0)
                    break
                } else {
                    totalOff += 86400
                }
            }
            return totalOff
        }
    }

    fun getRolling11hIntervals(context: Context, selectedDayKey: String, isToday: Boolean): List<IntArray> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!isToday) {
            return loadDayIntervals(prefs, selectedDayKey)
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val yesterdayKey = formatDay(cal.timeInMillis - 12L * 3600L * 1000L)

        val yesterdayList = loadDayIntervals(prefs, yesterdayKey)
        val todayList = loadDayIntervals(prefs, selectedDayKey)

        val combined = mutableListOf<IntArray>()
        for (pair in yesterdayList) {
            combined.add(intArrayOf(pair[0] - 86400, pair[1] - 86400))
        }
        for (pair in todayList) {
            if (combined.isNotEmpty() && pair[0] - combined.last()[1] <= MERGE_GAP_SEC) {
                combined.last()[1] = maxOf(combined.last()[1], pair[1])
            } else {
                combined.add(intArrayOf(pair[0], pair[1]))
            }
        }
        return combined
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

    // Отложенная фиксация разрыва, чтобы секундные провалы связи не дёргали базу
    private val disconnectDebounceRunnable = Runnable {
        val stillConnected = WifiStorage.isConnected(applicationContext)
        WifiStorage.syncState(applicationContext, stillConnected)
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.removeCallbacks(disconnectDebounceRunnable)
            WifiStorage.syncState(applicationContext)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val isOn = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isOn) {
                mainHandler.removeCallbacks(disconnectDebounceRunnable)
                WifiStorage.syncState(applicationContext, true)
            }
        }

        override fun onLost(network: Network) {
            mainHandler.removeCallbacks(disconnectDebounceRunnable)
            mainHandler.postDelayed(disconnectDebounceRunnable, 3000L)
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
        mainHandler.removeCallbacks(disconnectDebounceRunnable)
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
    private var lastObservedTodayKey: String = ""
    private val uiHandler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            WifiStorage.syncState(this@MainActivity)
            val currentToday = WifiStorage.getTodayKey()

            if (currentToday != lastObservedTodayKey) {
                lastObservedTodayKey = currentToday
                selectedDayKey = currentToday
                refreshUI()
            } else {
                val isToday = selectedDayKey == currentToday
                updateDashboard(WifiStorage.isConnected(this@MainActivity), isToday)
            }
            uiHandler.postDelayed(this, 1000L)
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
        val currentToday = WifiStorage.getTodayKey()
        if (lastObservedTodayKey != currentToday) {
            lastObservedTodayKey = currentToday
            selectedDayKey = currentToday
        }
        refreshUI()
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.postDelayed(tickRunnable, 1000L)
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
                selectedDayKey = WifiStorage.getTodayKey()
                refreshUI()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun formatHoursMins(totalSec: Int): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return String.format(Locale.US, "%dч %02dм", h, m)
    }

    private fun refreshUI() {
        WifiStorage.syncState(this)
        val isOn = WifiStorage.isConnected(this)
        val days = WifiStorage.getAvailableDays(this)
        val todayKey = WifiStorage.getTodayKey()

        if (selectedDayKey.isEmpty() || !days.contains(selectedDayKey)) {
            selectedDayKey = todayKey
        }

        val dateLabels = days.map { key ->
            val rawDate = key.removePrefix("day_")
            if (key == todayKey) "$rawDate (Сегодня)" else rawDate
        }
        val onHoursLabels = days.map { key ->
            val sec = WifiStorage.getTotalOnSecondsForDay(this, key)
            "ON: ${formatHoursMins(sec)}"
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = days.size
            override fun getItem(position: Int): Any = days[position]
            override fun getItemId(position: Int): Long = position.toLong()

            private fun buildRow(position: Int, isDropDown: Boolean): View {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    if (isDropDown) {
                        setPadding(24, 18, 24, 18)
                        setBackgroundColor(
                            if (position == daySpinner.selectedItemPosition) Color.parseColor("#CBD5E1")
                            else Color.parseColor("#E2E8F0")
                        )
                    } else {
                        setPadding(12, 10, 12, 10)
                    }
                }

                val leftDate = TextView(this@MainActivity).apply {
                    text = dateLabels[position]
                    setTextColor(Color.parseColor("#0F172A"))
                    textSize = 15f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val rightHours = TextView(this@MainActivity).apply {
                    text = onHoursLabels[position]
                    setTextColor(Color.parseColor("#065F46"))
                    textSize = 14f
                    paint.isFakeBoldText = true
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(16, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                row.addView(leftDate)
                row.addView(rightHours)
                return row
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                return buildRow(position, false)
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
                return buildRow(position, true)
            }
        }

        daySpinner.adapter = adapter
