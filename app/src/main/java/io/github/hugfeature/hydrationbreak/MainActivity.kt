package io.github.hugfeature.hydrationbreak

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var notificationStatus: TextView
    private lateinit var scheduleStatus: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private var lastExact: Boolean? = null
    private val kinds = listOf(Scheduler.WATER, Scheduler.BREAK)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        Scheduler.rescheduleAll(this, preserveFuture = true)
        render()
        if (intent.hasExtra(OfficeExercises.EXTRA_MOVE)) showExercise(intent.getIntExtra(OfficeExercises.EXTRA_MOVE, 0))
    }
    override fun onResume() {
        super.onResume()
        val exact = Scheduler.exactAllowed(this)
        if (lastExact != null && lastExact != exact) Scheduler.rescheduleAll(this, preserveFuture = true)
        lastExact = exact
        if (::notificationStatus.isInitialized) handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }
    private fun refreshStatus() {
        val blocked = ReminderNotifications.blockedReason(this) != null
        var notificationText = if (blocked) "通知未开启，提醒不会显示。请点击下方按钮开启应用通知及健康提醒通知类别。"
            else "通知已开启；锁屏显示、声音和省电限制由系统设置决定。"
        notificationText += if (Scheduler.exactAllowed(this)) "\n精确提醒已授权；仍需允许系统后台运行。"
            else "\n精确提醒未授权：当前使用普通提醒，无法保证延迟少于一个周期。"
        val settings = Scheduler.prefs(this)
        val now = System.currentTimeMillis()
        fun time(value: Long) = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(value))
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val lines = mutableListOf(if (hour < Scheduler.start(this) || hour >= Scheduler.end(this))
            "当前不在提醒时段内；常规提醒将在下个时段继续。" else "当前在提醒时段内。")
        (kinds + Scheduler.TEST).forEach { kind ->
            val name = when (kind) { Scheduler.WATER -> "喝水"; Scheduler.BREAK -> "运动"; else -> "锁屏测试" }
            val next = settings.getLong("next_$kind", 0)
            if (kind != Scheduler.TEST || next > 0 || settings.contains("result_$kind")) {
                lines += when {
                    kind != Scheduler.TEST && !Scheduler.enabled(this, kind) -> "$name：已关闭"
                    next > now -> "$name：计划 ${time(next)}（可能延迟）"
                    next > 0 -> "$name：计划 ${time(next)}，尚未收到系统触发"
                    else -> "$name：暂无待执行计划"
                }
                val received = settings.getLong("received_$kind", 0)
                if (received > 0) {
                    lines += "上次触发 ${time(received)}"
                    val due = settings.getLong("due_$kind", 0)
                    if (due > 0) lines += "该次计划 ${time(due)}，延迟 ${((received - due).coerceAtLeast(0) / 1000)} 秒"
                }
                if (next > 0) lines += if (settings.getBoolean("exact_$kind", false)) "调度：精确提醒" else "调度：普通提醒（延迟上限无法保证）"
                settings.getString("result_$kind", null)?.let { lines += it }
            }
        }
        val scheduleText = lines.joinToString("\n")
        if (scheduleStatus.text.toString() != scheduleText) scheduleStatus.text = scheduleText
        kinds.mapNotNull { Scheduler.prefs(this).getString("error_$it", null) }.distinct().forEach {
            notificationText += "\n$it"
        }
        if (notificationStatus.text.toString() != notificationText) notificationStatus.text = notificationText
    }
    private fun showExercise(index: Int) {
        val move = OfficeExercises.at(index)
        android.app.AlertDialog.Builder(this)
            .setTitle(move.title)
            .setMessage("${move.steps}\n\n${OfficeExercises.CAUTION}")
            .setPositiveButton("知道了", null)
            .setNeutralButton("换一个动作") { _, _ -> showExercise(index + 1) }
            .show()
    }
    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 50, 42, 30)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(TextView(this).apply { text = "休息与运动"; textSize = 27f })
        root.addView(TextView(this).apply { text = "离线运行 · 本地提醒 · v${packageManager.getPackageInfo(packageName, 0).versionName}"; textSize = 14f })
        root.addView(Button(this).apply {
            text = "办公室小幅运动 · 查看动作"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("选择一个动作")
                    .setItems(OfficeExercises.moves.map { it.title }.toTypedArray()) { _, index -> showExercise(index) }
                    .setNegativeButton("关闭", null).show()
            }
        })
        notificationStatus = TextView(this)
        root.addView(notificationStatus)
        root.addView(Button(this).apply {
            text = "通知设置"
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "请在系统设置中打开本应用的通知设置", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "开启精确提醒"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 31 && !Scheduler.exactAllowed(this@MainActivity)) {
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:$packageName")))
                    } catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "请在系统设置中允许本应用设置闹钟和提醒", Toast.LENGTH_LONG).show()
                    }
                } else Toast.makeText(this@MainActivity, "精确提醒已可用", Toast.LENGTH_SHORT).show()
            }
        })
        scheduleStatus = TextView(this).apply { textSize = 14f }
        root.addView(scheduleStatus)
        root.addView(Button(this).apply {
            text = "立即发送测试通知"
            setOnClickListener {
                Toast.makeText(this@MainActivity, ReminderNotifications.post(this@MainActivity, Scheduler.TEST), Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        })
        root.addView(Button(this).apply {
            text = "安排锁屏测试（约 1 分钟）"
            setOnClickListener {
                val blocked = ReminderNotifications.blockedReason(this@MainActivity)
                if (blocked != null) Toast.makeText(this@MainActivity, blocked, Toast.LENGTH_LONG).show()
                else {
                    val scheduled = Scheduler.scheduleTest(this@MainActivity)
                    refreshStatus()
                    Toast.makeText(this@MainActivity, if (scheduled) "请现在锁屏。测试不受提醒时段限制，系统可能延迟；解锁后可查看触发记录。" else "测试安排失败，请查看状态", Toast.LENGTH_LONG).show()
                }
            }
        })
        kinds.forEach { kind ->
            val name = if (kind == Scheduler.WATER) "喝水提醒" else "运动提醒"
            root.addView(Switch(this).apply {
                text = name
                textSize = 19f
                isChecked = Scheduler.enabled(this@MainActivity, kind)
                setOnCheckedChangeListener { _, checked ->
                    Scheduler.prefs(this@MainActivity).edit().putBoolean("enabled_$kind", checked).apply()
                    Scheduler.schedule(this@MainActivity, kind)
                    refreshStatus()
                }
            })
            root.addView(TextView(this).apply { text = "提醒间隔"; textSize = 15f })
            val options = listOf(15, 30, 45, 60, 90, 120)
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { "$it 分钟" })
            spinner.setSelection(options.indexOf(Scheduler.minutes(this, kind)).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val value = options[position]
                    if (value != Scheduler.minutes(this@MainActivity, kind)) {
                        Scheduler.prefs(this@MainActivity).edit().putInt("minutes_$kind", value).apply()
                        Scheduler.schedule(this@MainActivity, kind)
                        refreshStatus()
                    }
                }
            }
            root.addView(spinner)
        }
        root.addView(TextView(this).apply { text = "提醒时段（每天）"; textSize = 19f })
        var draftStart = Scheduler.start(this)
        var draftEnd = Scheduler.end(this)
        val hours = (0..23).toList()
        fun hourSpinner(selected: Int, callback: (Int) -> Unit) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, hours.map { "%02d:00".format(it) })
            setSelection(selected)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { callback(position) }
            }
        }
        root.addView(TextView(this).apply { text = "开始时间" })
        root.addView(hourSpinner(Scheduler.start(this)) {
            draftStart = it
        })
        root.addView(TextView(this).apply { text = "结束时间（不包含）" })
        root.addView(hourSpinner(Scheduler.end(this)) {
            draftEnd = it
        })
        root.addView(TextView(this).apply {
            text = "说明：当前支持每天相同时段；开始时间须早于结束时间。开关与间隔立即生效，时段需点击保存。Android 省电机制可能延迟通知，厂商后台管理也可能影响提醒。"
            textSize = 13f
        })
        root.addView(Button(this).apply {
            text = "保存提醒时段"
            setOnClickListener {
                if (!Scheduler.saveWindow(this@MainActivity, draftStart, draftEnd)) {
                    Toast.makeText(this@MainActivity, "开始时间必须早于结束时间", Toast.LENGTH_LONG).show()
                } else {
                    refreshStatus()
                    Toast.makeText(this@MainActivity, "时段已保存，提醒按各自开关安排", Toast.LENGTH_SHORT).show()
                }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
