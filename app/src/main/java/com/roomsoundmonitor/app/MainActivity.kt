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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefsName = SoundMonitorService.PREFS
    private val primaryBlue = Color.rgb(29, 86, 230)
    private val cardLight = Color.rgb(233, 237, 242)
    private val navLight = Color.rgb(242, 240, 250)
    private val selectedPill = Color.rgb(157, 184, 242)
    private val selectedIcon = Color.rgb(26, 58, 138)
    private val activeGreen = Color.rgb(34, 197, 94)
    private val warningAmber = Color.rgb(180, 112, 12)

    private var background = Color.WHITE
    private var cardBackground = cardLight
    private var primaryText = Color.rgb(17, 17, 17)
    private var secondaryText = Color.rgb(95, 99, 104)

    private lateinit var root: LinearLayout
    private lateinit var screenContainer: FrameLayout
    private lateinit var bottomNavigation: LinearLayout
    private var receiverRegistered = false
    private var currentTab = TAB_MONITOR
    private var historyFilter = FILTER_ALL
    private val scrollPositions = mutableMapOf<Int, Int>()
    private var activeScrollView: ScrollView? = null
    private var renderedTab = TAB_MONITOR

    private var monitorStatusText: TextView? = null
    private var monitorStatusDot: TextView? = null
    private var monitorMicrophone: ImageView? = null
    private var monitorLevelText: TextView? = null
    private var monitorLastText: TextView? = null
    private var monitorActionButton: Button? = null
    private var monitorEventsContainer: LinearLayout? = null

    private var currentDb = -90
    private var currentEvent = "لا يوجد اكتشاف بعد"
    private var currentAlert = "لا توجد تنبيهات جديدة"
    private var alertItem: HistoryItem? = null

    private data class HistoryItem(
        val name: String,
        val score: Int,
        val time: Long,
        val alert: Boolean
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.RECORD_AUDIO]
            ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        if (granted) {
            startMonitoring()
        } else {
            showToast("يجب السماح باستخدام الميكروفون")
            refreshRenderedState()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            intent.getStringExtra("status")?.let { currentAlert = it }
            intent.getStringExtra("event")?.let { currentEvent = it }
            intent.getStringExtra("error")?.let { currentAlert = it }
            if (intent.hasExtra("db")) {
                currentDb = intent.getIntExtra("db", -90)
            }
            if (intent.getBooleanExtra("alert", false)) {
                currentAlert = "تم اكتشاف صوت"
                alertItem = latestHistoryItem()
                currentTab = TAB_ALERT
            }
            if (intent.getBooleanExtra("history", false)) {
                alertItem = latestHistoryItem() ?: alertItem
            }
            refreshRenderedState(
                renderTab = intent.getBooleanExtra("alert", false) ||
                    (currentTab == TAB_ALERT && intent.getBooleanExtra("history", false))
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppearance()
        buildShell()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != TAB_MONITOR) {
                    currentTab = TAB_MONITOR
                    renderCurrentTab()
                } else {
                    finish()
                }
            }
        })
        refreshFromPreferences()
        renderCurrentTab()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(SoundMonitorService.ACTION_UPDATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        refreshFromPreferences()
        refreshRenderedState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(this@MainActivity.background)
        }

        screenContainer = FrameLayout(this)
        root.addView(
            screenContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        bottomNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(if (isDarkMode()) Color.rgb(31, 31, 38) else navLight)
        }
        root.addView(
            bottomNavigation,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            )
        )

        setContentView(root)
        updateBottomNavigation()
    }

    private fun renderCurrentTab() {
        if (!::screenContainer.isInitialized) return
        activeScrollView?.let { scrollPositions[renderedTab] = it.scrollY }
        screenContainer.removeAllViews()
        activeScrollView = null
        when (currentTab) {
            TAB_ALERT -> renderAlertScreen()
            TAB_HISTORY -> renderHistoryScreen()
            TAB_SETTINGS -> renderSettingsScreen()
            else -> renderMonitorScreen()
        }
        renderedTab = currentTab
        updateBottomNavigation()
        activeScrollView?.let { scroll ->
            screenContainer.post { scroll.scrollTo(0, scrollPositions[currentTab] ?: 0) }
        }
    }

    private fun renderMonitorScreen() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        activeScrollView = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(18), dp(16), dp(36))
            setBackgroundColor(this@MainActivity.background)
        }

        content.addView(makeHeader("Room Sound Monitor", "مساعدة على الانتباه للأصوات المهمة"))
        addSpace(content, 16)

        val statusCard = makeCard()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val microphone = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "حالة الميكروفون"
            setColorFilter(if (isActive()) activeGreen else Color.rgb(125, 125, 125))
        }
        monitorMicrophone = microphone
        statusRow.addView(microphone, LinearLayout.LayoutParams(dp(64), dp(64)))
        val statusTextColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), 0, 0, 0)
        }
        val statusTitle = makeText(if (isActive()) "المراقبة نشطة" else "المراقبة متوقفة", 20f, primaryText, true)
        monitorStatusText = statusTitle
        statusTextColumn.addView(statusTitle, fullWidth())
        val dot = makeText(if (isActive()) "● تعمل الآن" else "● غير نشطة", 14f, if (isActive()) activeGreen else secondaryText)
        monitorStatusDot = dot
        statusTextColumn.addView(dot, fullWidth())
        statusRow.addView(statusTextColumn, LinearLayout.LayoutParams(0, dp(76), 1f))
        statusCard.addView(statusRow, fullWidth())
        statusCard.addView(makeText("تتم المعالجة محليًا على هذا الجهاز دون حفظ التسجيلات أو رفعها.", 14f, secondaryText))
        content.addView(statusCard, fullWidth())
        addSpace(content, 16)

        monitorActionButton = makePrimaryButton(if (isActive()) "إيقاف المراقبة" else "بدء المراقبة")
        monitorActionButton?.setOnClickListener {
            if (isActive()) stopMonitoring() else requestPermissionsAndStart()
        }
        content.addView(monitorActionButton, fullWidth())
        addSpace(content, 16)

        val lastCard = makeCard()
        lastCard.addView(makeSectionTitle("آخر اكتشاف"), fullWidth())
        monitorLastText = makeText(currentEvent, 17f, primaryText, true)
        lastCard.addView(monitorLastText, fullWidth())
        monitorLevelText = makeText("مستوى الصوت: $currentDb dB", 14f, secondaryText)
        lastCard.addView(monitorLevelText, fullWidth())
        content.addView(lastCard, fullWidth())
        addSpace(content, 16)

        content.addView(makeSectionTitle("الأحداث الأخيرة"), fullWidth())
        monitorEventsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        content.addView(monitorEventsContainer, fullWidth())
        renderRecentEvents(monitorEventsContainer!!, 5)

        scroll.addView(content)
        screenContainer.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun renderAlertScreen() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        activeScrollView = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(36))
            setBackgroundColor(this@MainActivity.background)
        }
        content.addView(makeHeader("تنبيه اكتشاف صوت", "مراجعة الحدث الأخير"), fullWidth())
        addSpace(content, 24)
        val item = alertItem ?: latestHistoryItem()
        if (item == null) {
            val empty = makeCard()
            empty.addView(makeText("لا يوجد تنبيه حالي", 18f, primaryText, true), fullWidth())
            empty.addView(makeText("ستظهر التنبيهات هنا عند اكتشاف صوت مؤهل.", 14f, secondaryText), fullWidth())
            content.addView(empty, fullWidth())
        } else {
        val card = makeCard(warningAmber)
        card.addView(makeSectionTitle("تم اكتشاف صوت"), fullWidth())
        card.addView(makeText(item.name, 19f, primaryText, true), fullWidth())
        card.addView(makeText("الثقة التقديرية: ${item.score}%", 15f, secondaryText), fullWidth())
        card.addView(makeText(formatDateTime(item.time), 13f, secondaryText), fullWidth())
        card.addView(makeText("نسبة الثقة تقدير من نموذج التصنيف وليست ضمانًا لوقوع الحدث.", 13f, secondaryText), fullWidth())
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val details = makeSecondaryButton("عرض التفاصيل")
        details.setOnClickListener {
            currentTab = TAB_HISTORY
            renderCurrentTab()
        }
        val dismiss = makeSecondaryButton("إغلاق التنبيه")
        dismiss.setOnClickListener {
            alertItem = null
            currentTab = TAB_MONITOR
            renderCurrentTab()
        }
        actions.addView(details, weightedButtonParams())
        actions.addView(dismiss, weightedButtonParams())
        card.addView(actions, fullWidth())
            content.addView(card, fullWidth())
        }
        scroll.addView(content)
        screenContainer.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun renderHistoryScreen() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        activeScrollView = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(18), dp(16), dp(36))
            setBackgroundColor(this@MainActivity.background)
        }
        content.addView(makeHeader("سجل الأحداث", "الأحداث التي رصدها التطبيق محليًا"), fullWidth())
        addSpace(content, 16)
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
        }
        listOf(FILTER_ALL to "الكل", FILTER_HIGH to "ثقة عالية", FILTER_TODAY to "اليوم").forEach { (filter, label) ->
            val button = makeFilterButton(label, historyFilter == filter)
            button.setOnClickListener {
                historyFilter = filter
                renderCurrentTab()
            }
            filters.addView(button, weightedButtonParams())
        }
        content.addView(filters, fullWidth())
        addSpace(content, 16)
        val clearHistory = makeSecondaryButton("مسح السجل")
        clearHistory.setOnClickListener { showClearHistoryConfirmation() }
        content.addView(clearHistory, fullWidth())
        addSpace(content, 16)
        val history = filteredHistory()
        if (history.isEmpty()) {
            val empty = makeCard()
            empty.addView(makeText("لا توجد أحداث مطابقة", 17f, primaryText, true), fullWidth())
            empty.addView(makeText("ستظهر هنا الأصوات المهمة التي يكتشفها التطبيق.", 14f, secondaryText), fullWidth())
            content.addView(empty, fullWidth())
        } else {
            history.forEach { item -> content.addView(makeHistoryCard(item), fullWidth()) }
        }
        scroll.addView(content)
        screenContainer.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun renderSettingsScreen() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        activeScrollView = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(18), dp(16), dp(36))
            setBackgroundColor(this@MainActivity.background)
        }
        content.addView(makeHeader("الإعدادات", "اضبط تجربة التنبيه والمراقبة"), fullWidth())
        addSpace(content, 16)

        val sensitivityCard = makeCard()
        sensitivityCard.addView(makeSectionTitle("حساسية اكتشاف الأصوات"), fullWidth())
        val sensitivityLabel = makeText("الحساسية: ${getSensitivity()}%", 15f, secondaryText)
        sensitivityCard.addView(sensitivityLabel, fullWidth())
        val bar = SeekBar(this).apply {
            max = 100
            progress = getSensitivity()
            contentDescription = "حساسية اكتشاف الأصوات"
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivityLabel.text = "الحساسية: $progress%"
                if (fromUser) saveSensitivity(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        sensitivityCard.addView(bar, fullWidth())
        content.addView(sensitivityCard, fullWidth())
        addSpace(content, 16)

        val alertCard = makeCard()
        alertCard.addView(makeSectionTitle("التنبيهات"), fullWidth())
        addSettingSwitch(alertCard, "صوت التنبيه", "alert_sound", true)
        addSettingSwitch(alertCard, "الاهتزاز", "alert_vibration", true)
        content.addView(alertCard, fullWidth())
        addSpace(content, 16)

        val appearanceCard = makeCard()
        appearanceCard.addView(makeSectionTitle("المظهر"), fullWidth())
        val modes = listOf("system" to "تلقائي", "light" to "فاتح", "dark" to "داكن")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val currentMode = appearanceMode()
        var selectedId = -1
        modes.forEach { (value, label) ->
            val radio = RadioButton(this).apply {
                text = label
                textSize = 15f
                setTextColor(primaryText)
                tag = value
                id = View.generateViewId()
            }
            group.addView(radio)
            if (value == currentMode) selectedId = radio.id
        }
        group.check(selectedId)
        group.setOnCheckedChangeListener { checkedGroup, checkedId ->
            val selected = checkedGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            getSharedPreferences(prefsName, MODE_PRIVATE).edit().putString("appearance_mode", selected.tag.toString()).apply()
            recreate()
        }
        appearanceCard.addView(group, fullWidth())
        content.addView(appearanceCard, fullWidth())
        addSpace(content, 16)

        val categoriesCard = makeCard()
        categoriesCard.addView(makeSectionTitle("أنواع الأصوات المدعومة"), fullWidth())
        categoriesCard.addView(makeText("الفئات التالية هي الفئات التي يعرّفها نظام التصنيف الحالي:", 14f, secondaryText), fullWidth())
        listOf(
            SoundMonitorService.KEY_CATEGORY_SPEECH to "كلام بشري",
            SoundMonitorService.KEY_CATEGORY_SHOUT to "صراخ",
            SoundMonitorService.KEY_CATEGORY_CLAP to "تصفيق",
            SoundMonitorService.KEY_CATEGORY_KNOCK to "طرق ونقر",
            SoundMonitorService.KEY_CATEGORY_IMPACT to "ارتطام",
            SoundMonitorService.KEY_CATEGORY_ALARM to "إنذارات",
            SoundMonitorService.KEY_CATEGORY_DOORBELL to "جرس الباب",
            SoundMonitorService.KEY_CATEGORY_DOOR to "أصوات الأبواب"
        ).forEach { (key, label) ->
            addCategorySwitch(categoriesCard, label, key)
        }
        content.addView(categoriesCard, fullWidth())
        addSpace(content, 16)

        val privacyCard = makeCard()
        privacyCard.addView(makeSectionTitle("الخصوصية والأذونات"), fullWidth())
        privacyCard.addView(makeText("تتم معالجة الصوت محليًا على الجهاز ولا يتم تسجيله أو رفعه إلى الإنترنت.", 14f, secondaryText), fullWidth())
        val permissionText = if (hasPermission(Manifest.permission.RECORD_AUDIO)) "إذن الميكروفون: مسموح" else "إذن الميكروفون: مطلوب"
        privacyCard.addView(makeText(permissionText, 15f, if (hasPermission(Manifest.permission.RECORD_AUDIO)) activeGreen else warningAmber, true), fullWidth())
        val permissionButton = makeSecondaryButton("فتح إعدادات التطبيق")
        permissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        privacyCard.addView(permissionButton, fullWidth())
        content.addView(privacyCard, fullWidth())
        addSpace(content, 16)

        val clear = makeSecondaryButton("مسح سجل الأحداث")
        clear.setOnClickListener { showClearHistoryConfirmation() }
        content.addView(clear, fullWidth())

        scroll.addView(content)
        screenContainer.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun addSettingSwitch(parent: LinearLayout, label: String, key: String, default: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val text = makeText(label, 15f, primaryText)
        text.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        row.addView(text, LinearLayout.LayoutParams(0, dp(54), 1f))
        val toggle = Switch(this).apply {
            isChecked = getSharedPreferences(prefsName, MODE_PRIVATE).getBoolean(key, default)
            contentDescription = label
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(prefsName, MODE_PRIVATE).edit().putBoolean(key, checked).apply()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(64), dp(54)))
        parent.addView(row, fullWidth())
    }

    private fun addCategorySwitch(parent: LinearLayout, label: String, key: String) {
        addSettingSwitch(parent, label, key, true)
    }

    private fun makeHistoryCard(item: HistoryItem): LinearLayout {
        val card = makeCard()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val icon = makeText(iconFor(item.name), 25f, primaryBlue, true)
        icon.contentDescription = "نوع الصوت: ${item.name}"
        row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        details.addView(makeText(item.name, 16f, primaryText, true), fullWidth())
        details.addView(makeText("الثقة ${item.score}% • ${formatDateTime(item.time)}", 13f, secondaryText), fullWidth())
        row.addView(details, LinearLayout.LayoutParams(0, dp(64), 1f))
        card.addView(row, fullWidth())
        addSpace(card, 2)
        return card
    }

    private fun renderRecentEvents(container: LinearLayout, limit: Int) {
        container.removeAllViews()
        val items = readHistory().take(limit)
        if (items.isEmpty()) {
            val empty = makeCard()
            empty.addView(makeText("لا توجد أحداث بعد", 16f, primaryText, true), fullWidth())
            empty.addView(makeText("ابدأ المراقبة لتظهر الأصوات المهمة هنا.", 14f, secondaryText), fullWidth())
            container.addView(empty, fullWidth())
            return
        }
        items.forEach { container.addView(makeHistoryCard(it), fullWidth()) }
    }

    private fun showClearHistoryConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("مسح سجل الأحداث؟")
            .setMessage("سيتم حذف جميع الأحداث المحفوظة. لا يمكن التراجع عن هذا الإجراء.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("مسح السجل") { _, _ ->
                getSharedPreferences(prefsName, MODE_PRIVATE)
                    .edit()
                    .putString(SoundMonitorService.KEY_HISTORY, "[]")
                    .remove("last_alert")
                    .apply()
                currentEvent = "لا يوجد اكتشاف بعد"
                currentAlert = "لا توجد تنبيهات جديدة"
                alertItem = null
                showToast("تم مسح سجل الأحداث")
                refreshFromPreferences()
                renderCurrentTab()
            }
            .show()
    }

    private fun makeHeader(title: String, subtitle: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        header.addView(makeText(title, 24f, primaryText, true), fullWidth())
        header.addView(makeText(subtitle, 14f, secondaryText), fullWidth())
        return header
    }

    private fun makeCard(borderColor: Int? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(cardBackground)
                cornerRadius = dp(16).toFloat()
                borderColor?.let { setStroke(dp(1), it) }
            }
        }
    }

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun makeSectionTitle(value: String): TextView = makeText(value, 18f, primaryText, true)

    private fun makePrimaryButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            minHeight = dp(56)
            isAllCaps = false
            background = GradientDrawable().apply { setColor(primaryBlue); cornerRadius = dp(20).toFloat() }
            contentDescription = label
        }
    }

    private fun makeSecondaryButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(if (isDarkMode()) Color.WHITE else selectedIcon)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(if (isDarkMode()) Color.rgb(55, 55, 64) else Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), if (isDarkMode()) Color.rgb(100, 100, 110) else selectedPill)
            }
            contentDescription = label
        }
    }

    private fun makeFilterButton(label: String, selected: Boolean): Button {
        val button = makeSecondaryButton(label)
        button.background = GradientDrawable().apply {
            setColor(if (selected) selectedPill else cardBackground)
            cornerRadius = dp(12).toFloat()
        }
        button.setTextColor(if (selected) selectedIcon else primaryText)
        return button
    }

    private fun updateBottomNavigation() {
        if (!::bottomNavigation.isInitialized) return
        bottomNavigation.removeAllViews()
        listOf(
            Triple(TAB_MONITOR, "المراقبة", "⌂"),
            Triple(TAB_HISTORY, "السجل", "≡"),
            Triple(TAB_SETTINGS, "الإعدادات", "⚙")
        ).forEach { entry ->
            val tab = entry.first
            val label = entry.second
            val icon = entry.third
            val selected = tab == if (currentTab == TAB_ALERT) TAB_MONITOR else currentTab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                background = GradientDrawable().apply {
                    setColor(if (selected) selectedPill else Color.TRANSPARENT)
                    cornerRadius = dp(14).toFloat()
                }
                contentDescription = label
                setOnClickListener {
                    currentTab = tab
                    renderCurrentTab()
                }
            }
            val iconText = makeText(icon, 22f, if (selected) selectedIcon else secondaryText, true).apply {
                gravity = Gravity.CENTER
            }
            val labelText = makeText(label, 12f, if (selected) selectedIcon else secondaryText, selected).apply {
                gravity = Gravity.CENTER
            }
            item.addView(iconText, LinearLayout.LayoutParams(-1, dp(28)))
            item.addView(labelText, LinearLayout.LayoutParams(-1, dp(28)))
            bottomNavigation.addView(item, weightedButtonParams())
        }
    }

    private fun refreshRenderedState(renderTab: Boolean = false) {
        if (!::screenContainer.isInitialized) return
        syncMonitorVisualState()
        monitorLevelText?.text = "مستوى الصوت: $currentDb dB"
        monitorLastText?.text = currentEvent
        monitorEventsContainer?.let { renderRecentEvents(it, 5) }
        if (renderTab) renderCurrentTab()
    }

    private fun refreshFromPreferences() {
        val last = getSharedPreferences(prefsName, MODE_PRIVATE).getString("last_alert", null)
        if (!last.isNullOrBlank()) currentAlert = last
        val latest = latestHistoryItem()
        currentEvent = latest?.let { "${it.name} — ${it.score}%" } ?: "لا يوجد اكتشاف بعد"
        if (!isActive() && currentTab != TAB_ALERT) alertItem = null
        syncMonitorVisualState()
    }

    private fun syncMonitorVisualState() {
        val active = isActive()
        monitorStatusText?.text = if (active) "المراقبة نشطة" else "المراقبة متوقفة"
        monitorStatusDot?.apply {
            text = if (active) "● تعمل الآن" else "● غير نشطة"
            setTextColor(if (active) activeGreen else secondaryText)
        }
        monitorMicrophone?.setColorFilter(
            if (active) activeGreen else Color.rgb(125, 125, 125)
        )
        monitorActionButton?.text = if (active) "إيقاف المراقبة" else "بدء المراقبة"
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startMonitoring() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startMonitoring() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, SoundMonitorService::class.java).setAction(SoundMonitorService.ACTION_START)
            )
            refreshRenderedState()
        } catch (e: Exception) {
            showToast(e.message ?: "تعذر بدء المراقبة")
        }
    }

    private fun stopMonitoring() {
        startService(Intent(this, SoundMonitorService::class.java).setAction(SoundMonitorService.ACTION_STOP))
        currentDb = -90
        alertItem = null
        refreshRenderedState()
    }

    private fun isActive(): Boolean = getSharedPreferences(prefsName, MODE_PRIVATE).getBoolean(SoundMonitorService.KEY_ACTIVE, false)

    private fun getSensitivity(): Int = getSharedPreferences(prefsName, MODE_PRIVATE).getInt(SoundMonitorService.KEY_SENSITIVITY, 60)

    private fun saveSensitivity(value: Int) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().putInt(SoundMonitorService.KEY_SENSITIVITY, value).apply()
    }

    private fun appearanceMode(): String = getSharedPreferences(prefsName, MODE_PRIVATE).getString("appearance_mode", "system") ?: "system"

    private fun applyAppearance() {
        val dark = when (appearanceMode()) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        background = if (dark) Color.rgb(18, 18, 22) else Color.WHITE
        cardBackground = if (dark) Color.rgb(38, 38, 46) else cardLight
        primaryText = if (dark) Color.WHITE else Color.rgb(17, 17, 17)
        secondaryText = if (dark) Color.rgb(190, 190, 198) else Color.rgb(95, 99, 104)
        window.statusBarColor = background
        window.navigationBarColor = background
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun readHistory(): List<HistoryItem> {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE).getString(SoundMonitorService.KEY_HISTORY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                HistoryItem(item.optString("name", "صوت غير معروف"), item.optInt("score", 0), item.optLong("time", 0L), item.optBoolean("alert", false))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun latestHistoryItem(): HistoryItem? = readHistory().firstOrNull()

    private fun filteredHistory(): List<HistoryItem> {
        val all = readHistory()
        return when (historyFilter) {
            FILTER_HIGH -> all.filter { it.score >= 80 }
            FILTER_TODAY -> {
                val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                all.filter { it.time >= start }
            }
            else -> all
        }
    }

    private fun iconFor(name: String): String = when {
        name.contains("إنذار") || name.contains("منبه") -> "!"
        name.contains("طرق") || name.contains("نقر") || name.contains("باب") -> "⌕"
        name.contains("صراخ") -> "!"
        name.contains("تصفيق") -> "✦"
        name.contains("ارتطام") || name.contains("ضربة") -> "◆"
        else -> "●"
    }

    private fun formatDateTime(time: Long): String = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.getDefault()).format(Date(time))

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isDarkMode(): Boolean = background != Color.WHITE

    private fun weightedButtonParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }

    private fun fullWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun addSpace(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAB_MONITOR = 0
        private const val TAB_HISTORY = 1
        private const val TAB_SETTINGS = 2
        private const val TAB_ALERT = 3
        private const val FILTER_ALL = 0
        private const val FILTER_HIGH = 1
        private const val FILTER_TODAY = 2
    }
}
