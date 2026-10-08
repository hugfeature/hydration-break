package io.github.hugfeature.hydrationbreak

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val kinds = listOf(Scheduler.WATER, Scheduler.BREAK)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        render()
    }
    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 50, 42, 30)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(TextView(this).apply { text = "喝水与休息"; textSize = 27f })
        root.addView(TextView(this).apply { text = "离线运行 · 本地提醒 · 无需账号"; textSize = 14f })
        kinds.forEach { kind ->
            val name = if (kind == Scheduler.WATER) "喝水提醒" else "活动休息"
            root.addView(Switch(this).apply {
                text = name
                textSize = 19f
                isChecked = Scheduler.enabled(this@MainActivity, kind)
                setOnCheckedChangeListener { _, checked ->
                    Scheduler.prefs(this@MainActivity).edit().putBoolean("enabled_$kind", checked).apply()
                    Scheduler.schedule(this@MainActivity, kind)
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
                    }
                }
            }
            root.addView(spinner)
        }
        root.addView(TextView(this).apply { text = "提醒时段（每天）"; textSize = 19f })
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
            if (it != Scheduler.start(this)) { Scheduler.prefs(this).edit().putInt("start", it).apply(); Scheduler.rescheduleAll(this) }
        })
        root.addView(TextView(this).apply { text = "结束时间（不包含）" })
        root.addView(hourSpinner(Scheduler.end(this)) {
            if (it != Scheduler.end(this)) { Scheduler.prefs(this).edit().putInt("end", it).apply(); Scheduler.rescheduleAll(this) }
        })
        root.addView(TextView(this).apply {
            text = "说明：当前支持每天相同时段；开始时间须早于结束时间。Android 省电机制可能延迟通知，厂商后台管理也可能影响提醒。"
            textSize = 13f
        })
        root.addView(Button(this).apply {
            text = "保存并启用提醒"
            setOnClickListener {
                if (Scheduler.start(this@MainActivity) >= Scheduler.end(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "开始时间必须早于结束时间", Toast.LENGTH_LONG).show()
                } else {
                    Scheduler.rescheduleAll(this@MainActivity)
                    Toast.makeText(this@MainActivity, "已重新安排提醒", Toast.LENGTH_SHORT).show()
                }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
