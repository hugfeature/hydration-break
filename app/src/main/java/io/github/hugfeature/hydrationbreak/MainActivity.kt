package io.github.hugfeature.hydrationbreak

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private var diagnosticStatus: TextView? = null
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
        diagnosticStatus?.text = lines.joinToString("\n")
        val scheduleText = kinds.joinToString("\n") { kind ->
            val name = if (kind == Scheduler.WATER) "喝水" else "运动"
            val next = settings.getLong("next_$kind", 0)
            when {
                !Scheduler.enabled(this, kind) -> "$name · 已关闭"
                next <= 0 -> "$name · 尚未安排"
                next <= now -> "$name · 等待系统触发"
                else -> "$name · 下次 ${time(next)}"
            }
        }
        if (scheduleStatus.text.toString() != scheduleText) scheduleStatus.text = scheduleText
        kinds.mapNotNull { Scheduler.prefs(this).getString("error_$it", null) }.distinct().forEach {
            notificationText += "\n$it"
        }
        if (notificationStatus.text.toString() != notificationText) notificationStatus.text = notificationText
    }
    private fun settingsMenu() {
        android.app.AlertDialog.Builder(this).setTitle("设置")
            .setItems(arrayOf("提醒诊断", "隐私说明", "关于与使用说明")) { _, which ->
                when (which) {
                    0 -> showDiagnostics()
                    1 -> android.app.AlertDialog.Builder(this).setTitle("隐私说明")
                        .setMessage("完全离线，无账号、广告、分析或上传。设置、动作选择和最近触发记录仅保存在本机；卸载后删除。系统通知可能在锁屏展示内容，可在系统设置中关闭。应用仅使用通知、开机恢复和精确闹钟权限。")
                        .setPositiveButton("知道了", null).show()
                    else -> android.app.AlertDialog.Builder(this).setTitle("休息与运动")
                        .setMessage("版本 " + packageManager.getPackageInfo(packageName, 0).versionName +
                            "\n\n提醒开关与间隔立即生效；每日时段需要保存。请允许通知和精确提醒。\n\n动作适合短暂活动，不代替规律运动或医疗建议。内容参考 NHS，查看动作可见来源。\n\n反馈：github.com/hugfeature/hydration-break/issues")
                        .setPositiveButton("知道了", null).show()
                }
            }.setNegativeButton("关闭", null).show()
    }
    private fun showDiagnostics() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        diagnosticStatus = TextView(this).also { panel.addView(it) }
        panel.addView(Button(this).apply {
            text = "立即发送测试通知"
            setOnClickListener {
                Toast.makeText(this@MainActivity, ReminderNotifications.post(this@MainActivity, Scheduler.TEST), Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        })
        panel.addView(Button(this).apply {
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

        android.app.AlertDialog.Builder(this).setTitle("提醒诊断")
            .setView(ScrollView(this).apply { addView(panel) }).setPositiveButton("关闭", null)
            .setOnDismissListener { diagnosticStatus = null }.show()
        refreshStatus()
    }
    private fun chooseExercises() {
        val selected = OfficeExercises.selected(this).toSet()
        val checks = BooleanArray(OfficeExercises.moves.size) { it in selected }
        android.app.AlertDialog.Builder(this).setTitle("选择通知中的动作")
            .setMultiChoiceItems(OfficeExercises.moves.map { it.title }.toTypedArray(), checks) { _, which, checked -> checks[which] = checked }
            .setPositiveButton("保存") { _, _ ->
                Scheduler.prefs(this).edit().putStringSet("exercise_ids", OfficeExercises.moves.indices.filter { checks[it] }.map { OfficeExercises.moves[it].id }.toSet()).apply()
                Toast.makeText(this, "已保存；未选择动作时仅发送一般活动提醒", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }
    private fun showExercise(index: Int) {
        val move = OfficeExercises.at(index)
        android.app.AlertDialog.Builder(this)
            .setTitle(move.title)
            .setMessage("${move.steps}\n\n${OfficeExercises.CAUTION}\n\n参考来源：${move.source}")
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
        root.setBackgroundColor(android.graphics.Color.rgb(245, 248, 247))
        val density = resources.displayMetrics.density
        root.setPadding((22 * density).toInt(), (24 * density).toInt(), (22 * density).toInt(), (24 * density).toInt())
        root.addView(TextView(this).apply { text = "休息与运动"; textSize = 28f; setTextColor(android.graphics.Color.rgb(26, 77, 69)) })
        root.addView(TextView(this).apply { text = "给忙碌留一点空隙"; textSize = 15f; setPadding(0, 8, 0, 20) })
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
        root.addView(Button(this).apply { text = "选择提醒动作"; setOnClickListener { chooseExercises() } })
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
        root.addView(Button(this).apply { text = "设置与帮助"; setOnClickListener { settingsMenu() } })
    }
}
