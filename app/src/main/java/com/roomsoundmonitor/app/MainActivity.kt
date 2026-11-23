
package com.roomsoundmonitor.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefsName = "monitor_settings"

    private var background = Color.rgb(17, 21, 31)
    private var card = Color.rgb(29, 35, 48)
    private var accent = Color.rgb(135, 105, 255)
    private var white = Color.WHITE
    private var muted = Color.rgb(170, 180, 195)
    private var warning = Color.rgb(255, 185, 90)

    private lateinit var statusText: TextView
    private lateinit var sensitivityText: TextView
    private lateinit var startButton: Button

    private var receiverRegistered = false

    private var currentLevel = 0
    private var currentDb = -90
    private var currentEvent = "لم يبدأ تحليل الأصوات"
    private var currentAlert = "التنبيهات متوقفة"

    private var settingsLevelText: TextView? = null
    private var settingsEventText: TextView? = null
    private var settingsAlertText: TextView? = null
    private var settingsHistoryText: TextView? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            val microphoneGranted =
                result[Manifest.permission.RECORD_AUDIO]
                    ?: hasPermission(Manifest.permission.RECORD_AUDIO)

            if (microphoneGranted) {
                startMonitoring()
            } else {
                statusText.text = "إذن الميكروفون مطلوب"

                Toast.makeText(
                    this,
                    "يجب السماح باستخدام الميكروفون",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val receiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {

            if (intent == null) return

            intent.getStringExtra("status")?.let {
                statusText.text = it
            }

            intent.getStringExtra("event")?.let {
                currentEvent = it
                settingsEventText?.text = "آخر صوت: $it"
            }

            intent.getStringExtra("error")?.let {
                currentAlert = it
                settingsAlertText?.text = it
                refreshButton()
            }

            if (intent.hasExtra("level")) {
                currentLevel = intent.getIntExtra("level", 0)
            }

            if (intent.hasExtra("db")) {
                currentDb = intent.getIntExtra("db", -90)
                settingsLevelText?.text =
                    "مستوى الصوت: $currentDb dB"
            }

            if (intent.getBooleanExtra("alert", false)) {
                currentAlert = "تنبيه صوتي جديد!"
                settingsAlertText?.text = currentAlert
            }

            if (intent.getBooleanExtra("history", false)) {
                updateSettingsHistory()
            }

            refreshButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyAppearance()
        buildInterface()
        refreshFromPreferences()
    }

    override fun onStart() {
        super.onStart()

        if (!receiverRegistered) {

            val filter =
                IntentFilter(SoundMonitorService.ACTION_UPDATE)

            if (Build.VERSION.SDK_INT >= 33) {

                registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )

            } else {

                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }

            receiverRegistered = true
        }

        refreshFromPreferences()
    }

    override fun onStop() {

        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }

        super.onStop()
    }

    private fun applyAppearance() {

        val mode = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        ).getString("appearance_mode", "dark")

        val systemDark =
            (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val dark = when (mode) {
            "light" -> false
            "system" -> systemDark
            else -> true
        }

        if (dark) {

            background = Color.rgb(17, 21, 31)
            card = Color.rgb(29, 35, 48)
            accent = Color.rgb(135, 105, 255)
            white = Color.WHITE
            muted = Color.rgb(170, 180, 195)
            warning = Color.rgb(255, 185, 90)

        } else {

            background = Color.rgb(245, 247, 251)
            card = Color.WHITE
            accent = Color.rgb(105, 75, 220)
            white = Color.rgb(30, 35, 48)
            muted = Color.rgb(105, 115, 130)
            warning = Color.rgb(190, 105, 15)
        }

        window.statusBarColor = background
        window.navigationBarColor = background

        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility =
                if (dark) 0
                else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun buildInterface() {

        val scroll = ScrollView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(this@MainActivity.background)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // العنوان والترس

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = makeText(
            "مراقب صوت الغرفة",
            23f,
            white,
            true
        )

        title.gravity = Gravity.RIGHT

        header.addView(
            title,
            LinearLayout.LayoutParams(0, dp(55), 1f)
        )

        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(white)
            contentDescription = "الإعدادات"

            setOnClickListener {
                showSettings()
            }
        }

        header.addView(
            settingsButton,
            LinearLayout.LayoutParams(dp(55), dp(55))
        )

        root.addView(header, fullWidth())

        addSpace(root, 8)

        // حالة المراقبة

        val statusCard = makeCard()

        statusCard.addView(
            makeText("حالة المراقبة", 15f, muted)
        )

        statusText = makeText(
            "متوقف",
            25f,
            white,
            true
        )

        statusCard.addView(statusText)

        root.addView(statusCard, fullWidth())

        addSpace(root, 28)

        // الحساسية

        val sensitivityCard = makeCard()

        sensitivityCard.addView(
            makeText(
                "حساسية اكتشاف الأصوات",
                18f,
                white,
                true
            )
        )

        addSpace(sensitivityCard, 8)

        sensitivityText = makeText(
            "الحساسية: ${getSensitivity()}%",
            15f,
            muted
        )

        sensitivityCard.addView(sensitivityText)

        val sensitivityBar = SeekBar(this).apply {
            max = 100
            progress = getSensitivity()
        }

        sensitivityBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    sensitivityText.text =
                        "الحساسية: $progress%"

                    if (fromUser) {
                        saveSensitivity(progress)
                    }
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {}

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {}
            }
        )

        sensitivityCard.addView(
            sensitivityBar,
            fullWidth()
        )

        root.addView(sensitivityCard, fullWidth())

        addSpace(root, 30)

        // زر التشغيل والإيقاف

        startButton = Button(this).apply {

            text = "تشغيل المراقبة"
            textSize = 18f
            setTextColor(Color.WHITE)

            backgroundTintList =
                android.content.res.ColorStateList.valueOf(accent)

            setOnClickListener {

                if (isActive()) {
                    stopMonitoring()
                } else {
                    requestPermissionsAndStart()
                }
            }
        }

        root.addView(
            startButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(65)
            )
        )

        addSpace(root, 25)

        root.addView(
            makeText(
                "تتم معالجة الصوت محليًا على الهاتف",
                13f,
                muted
            )
        )

        root.addView(
            makeText(
                "لا يتم حفظ التسجيلات أو رفع الصوت إلى الإنترنت",
                12f,
                muted
            )
        )

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun showSettings() {

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(this@MainActivity.background)
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(
            makeText("إعدادات التطبيق", 21f, white, true)
        )

        addSpace(content, 16)

        content.addView(
            makeText("مظهر التطبيق", 17f, white, true)
        )

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val modes = listOf(
            "dark" to "داكن",
            "light" to "فاتح",
            "system" to "تلقائي حسب النظام"
        )

        val currentMode = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        ).getString("appearance_mode", "dark")

        var selectedId = -1

        modes.forEach { (value, label) ->

            val radio = RadioButton(this).apply {
                text = label
                textSize = 15f
                setTextColor(white)
                id = View.generateViewId()
                tag = value
            }

            radioGroup.addView(radio)

            if (value == currentMode) {
                selectedId = radio.id
            }
        }

        radioGroup.check(selectedId)

        content.addView(radioGroup)

        addSpace(content, 16)

        content.addView(
            makeText("معلومات المراقبة", 17f, white, true)
        )

        settingsLevelText = makeText(
            "مستوى الصوت: $currentDb dB",
            15f,
            muted
        )

        content.addView(settingsLevelText!!)

        settingsEventText = makeText(
            "آخر صوت: $currentEvent",
            15f,
            white
        )

        content.addView(settingsEventText!!)

        settingsAlertText = makeText(
            currentAlert,
            14f,
            warning
        )

        content.addView(settingsAlertText!!)

        addSpace(content, 16)

        content.addView(
            makeText("سجل آخر 20 حدثًا", 17f, white, true)
        )

        settingsHistoryText = makeText(
            getHistoryText(),
            13f,
            muted
        )

        settingsHistoryText!!.gravity = Gravity.RIGHT

        content.addView(settingsHistoryText!!)

        addSpace(content, 10)

        val clearButton = Button(this).apply {
            text = "مسح سجل الأحداث"

            setOnClickListener {

                getSharedPreferences(
                    prefsName,
                    MODE_PRIVATE
                ).edit()
                    .putString(
                        SoundMonitorService.KEY_HISTORY,
                        "[]"
                    )
                    .apply()

                updateSettingsHistory()

                Toast.makeText(
                    this@MainActivity,
                    "تم مسح السجل",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        content.addView(clearButton, fullWidth())

        scroll.addView(content)

        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(430)
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setNegativeButton("إغلاق", null)
            .create()

        radioGroup.setOnCheckedChangeListener { group, checkedId ->

            val selected =
                group.findViewById<RadioButton>(checkedId)
                    ?: return@setOnCheckedChangeListener

            val value = selected.tag.toString()

            getSharedPreferences(
                prefsName,
                MODE_PRIVATE
            ).edit()
                .putString("appearance_mode", value)
                .apply()

            dialog.dismiss()
            recreate()
        }

        dialog.setOnDismissListener {
            settingsLevelText = null
            settingsEventText = null
            settingsAlertText = null
            settingsHistoryText = null
        }

        dialog.show()
    }

    private fun updateSettingsHistory() {
        settingsHistoryText?.text = getHistoryText()
    }

    private fun getHistoryText(): String {

        val history = try {

            JSONArray(
                getSharedPreferences(
                    prefsName,
                    MODE_PRIVATE
                ).getString(
                    SoundMonitorService.KEY_HISTORY,
                    "[]"
                )
            )

        } catch (_: Exception) {
            JSONArray()
        }

        if (history.length() == 0) {
            return "لا توجد أحداث مسجلة"
        }

        val formatter = SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        )

        val output = StringBuilder()

        for (i in 0 until history.length()) {

            val item = history.getJSONObject(i)

            val name = item.optString("name")
            val score = item.optInt("score")
            val time = item.optLong("time")
            val alert = item.optBoolean("alert")

            output.append(
                "${formatter.format(Date(time))}  |  $name  |  $score%"
            )

            if (alert) {
                output.append("  ⚠")
            }

            output.append("\n\n")
        }

        return output.toString()
    }

    private fun requestPermissionsAndStart() {

        val needed = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isEmpty()) {
            startMonitoring()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean {

        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startMonitoring() {

        try {

            val intent =
                Intent(this, SoundMonitorService::class.java)
                    .setAction(SoundMonitorService.ACTION_START)

            ContextCompat.startForegroundService(
                this,
                intent
            )

            statusText.text = "جارٍ تشغيل المراقبة"
            startButton.text = "إيقاف المراقبة"

        } catch (e: Exception) {

            statusText.text = "تعذر بدء المراقبة"

            Toast.makeText(
                this,
                e.message ?: "حدث خطأ",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopMonitoring() {

        val intent =
            Intent(this, SoundMonitorService::class.java)
                .setAction(SoundMonitorService.ACTION_STOP)

        startService(intent)

        statusText.text = "متوقف"
        currentEvent = "لم يبدأ تحليل الأصوات"
        currentAlert = "التنبيهات متوقفة"
        currentLevel = 0
        currentDb = -90

        startButton.text = "تشغيل المراقبة"
    }

    private fun isActive(): Boolean {

        return getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        ).getBoolean(
            SoundMonitorService.KEY_ACTIVE,
            false
        )
    }

    private fun refreshFromPreferences() {

        if (isActive()) {
            statusText.text = "المراقبة تعمل"
            startButton.text = "إيقاف المراقبة"
        } else {
            statusText.text = "متوقف"
            startButton.text = "تشغيل المراقبة"
        }

        val lastAlert =
            getSharedPreferences(
                prefsName,
                MODE_PRIVATE
            ).getString("last_alert", null)

        if (lastAlert != null) {
            currentAlert = lastAlert
        }
    }

    private fun refreshButton() {

        startButton.text =
            if (isActive()) {
                "إيقاف المراقبة"
            } else {
                "تشغيل المراقبة"
            }
    }

    private fun getSensitivity(): Int {

        return getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        ).getInt(
            SoundMonitorService.KEY_SENSITIVITY,
            60
        )
    }

    private fun saveSensitivity(value: Int) {

        getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        ).edit()
            .putInt(
                SoundMonitorService.KEY_SENSITIVITY,
                value
            )
            .apply()
    }

    private fun makeCard(): LinearLayout {

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(this@MainActivity.card)
        }
    }

    private fun makeText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {

        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }
    }

    private fun fullWidth(): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun addSpace(
        parent: LinearLayout,
        height: Int
    ) {

        parent.addView(
            View(this),
            LinearLayout.LayoutParams(
                1,
                dp(height)
            )
        )
    }

    private fun dp(value: Int): Int {

        return (
            value * resources.displayMetrics.density
        ).toInt()
    }
}
