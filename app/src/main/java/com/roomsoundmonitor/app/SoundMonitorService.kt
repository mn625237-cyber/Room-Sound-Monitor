
package com.roomsoundmonitor.app

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioRecord
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.audio.classifier.Classifications
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

class SoundMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.roomsoundmonitor.app.START"
        const val ACTION_STOP = "com.roomsoundmonitor.app.STOP"
        const val ACTION_UPDATE = "com.roomsoundmonitor.app.UPDATE"

        const val CHANNEL_ID = "room_monitor_channel"
        const val NOTIFICATION_ID = 501

        const val PREFS = "monitor_settings"
        const val KEY_ACTIVE = "monitoring_active"
        const val KEY_HISTORY = "events_json"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_ALERT_SOUND = "alert_sound"
        const val KEY_ALERT_VIBRATION = "alert_vibration"
        const val KEY_CATEGORY_SPEECH = "category_speech"
        const val KEY_CATEGORY_SHOUT = "category_shout"
        const val KEY_CATEGORY_CLAP = "category_clap"
        const val KEY_CATEGORY_KNOCK = "category_knock"
        const val KEY_CATEGORY_IMPACT = "category_impact"
        const val KEY_CATEGORY_ALARM = "category_alarm"
        const val KEY_CATEGORY_DOORBELL = "category_doorbell"
        const val KEY_CATEGORY_DOOR = "category_door"
        const val KEY_CATEGORY_CRYING = "category_crying"
        const val KEY_CATEGORY_PHONE = "category_phone"
        const val KEY_CATEGORY_SIREN = "category_siren"
        const val KEY_CATEGORY_HORN = "category_horn"
        const val KEY_CATEGORY_FOOTSTEPS = "category_footsteps"
        const val KEY_CATEGORY_BELL = "category_bell"
        const val KEY_CATEGORY_SIGNAL = "category_signal"

        val HISTORY_LOCK = Any()

        @Volatile
        var serviceRunning = false
            private set

        private const val TAG = "SoundMonitorService"
    }

    @Volatile
    private var reading = false

    private val lifecycleLock = Any()

    /** Changes whenever a monitoring session is started or stopped. */
    @Volatile
    private var sessionId = 0L

    @Volatile
    private var ignoreInputUntil = 0L

    @Volatile
    private var recorder: AudioRecord? = null

    private var worker: Thread? = null
    private val audioFocusLock = Any()
    private var activeAudioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var activeFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var alertReleaseRunnable: Runnable? = null
    private var activeTone: ToneGenerator? = null
    private var nextAlertId = 0L
    private var activeAlertId = 0L

    @Volatile
    private var destroyed = false

    private val handler = Handler(Looper.getMainLooper())

    private val consecutiveHits = mutableMapOf<String, Int>()
    private val lastAlerts = mutableMapOf<String, Long>()

    private var lastLoggedName = ""
    private var lastLoggedTime = 0L

    private data class EventInfo(
        val group: String,
        val name: String,
        val threshold: Float,
        val alert: Boolean,
        val confirmationHits: Int = 2
    )

    private data class DetectedEvent(
        val group: String,
        val name: String,
        val score: Float,
        val alert: Boolean,
        val confirmationHits: Int
    )

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startMonitoring()
            }

            null -> return START_NOT_STICKY

            else -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
        }

        return START_NOT_STICKY
    }

    private fun startMonitoring() {

        val currentSession: Long

        synchronized(lifecycleLock) {
            if (reading || worker?.isAlive == true) return

            currentSession = sessionId + 1
            sessionId = currentSession
            reading = true
            serviceRunning = false
        }

        try {
            val notification = buildNotification("المراقبة تعمل")

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

        } catch (e: Exception) {

            synchronized(lifecycleLock) {
                reading = false
                serviceRunning = false
                sessionId++
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .apply()
            }

            clearLastAlert()

            sendUpdate(
                status = "متوقف",
                error = "تعذر تشغيل خدمة المراقبة: ${e.message}",
                level = 0,
                db = -90
            )

            stopSelf()
            return
        }

        consecutiveHits.clear()
        lastAlerts.clear()
        lastLoggedName = ""
        lastLoggedTime = 0L

        sendUpdate(status = "جارٍ تحميل نموذج الأصوات")

        worker = Thread {

            var classifier: AudioClassifier? = null
            var localRecorder: AudioRecord? = null

            try {

                val options =
                    AudioClassifier.AudioClassifierOptions
                        .builder()
                        .setMaxResults(20)
                        .build()

                classifier =
                    AudioClassifier.createFromFileAndOptions(
                        applicationContext,
                        "yamnet.tflite",
                        options
                    )

                if (!isSessionActive(currentSession)) return@Thread

                localRecorder = classifier.createAudioRecord()

                if (
                    localRecorder.state !=
                    AudioRecord.STATE_INITIALIZED
                ) {
                    throw IllegalStateException(
                        "تعذر تهيئة الميكروفون"
                    )
                }

                val tensor: TensorAudio =
                    classifier.createInputTensorAudio()

                val requiredSamples =
                    classifier.requiredInputBufferSize

                recorder = localRecorder
                synchronized(lifecycleLock) {
                    if (!isSessionActive(currentSession)) return@Thread
                    localRecorder.startRecording()
                }

                if (!isSessionActive(currentSession)) return@Thread

                if (localRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException(
                        "تعذر بدء تسجيل الميكروفون"
                    )
                }

                if (!publishSessionRunning(currentSession)) return@Thread

                updateNotificationIfSessionActive(
                    currentSession,
                    "المراقبة نشطة وتعمل في الخلفية"
                )

                val buffer = FloatArray(1024)

                var totalSamples = 0L
                var lastClassification = 0L
                var lastLevelUpdate = 0L

                while (isSessionActive(currentSession)) {

                    val count = localRecorder.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    )

                    if (count > 0 && isSessionActive(currentSession)) {

                        if (android.os.SystemClock.elapsedRealtime() < ignoreInputUntil) {
                            continue
                        }

                        var sum = 0.0

                        for (i in 0 until count) {
                            val value = buffer[i].toDouble()
                            sum += value * value
                        }

                        val rms = sqrt(sum / count)

                        val db = if (rms > 0.000001) {
                            20.0 * log10(rms)
                        } else {
                            -90.0
                        }

                        tensor.load(buffer, 0, count)

                        totalSamples += count

                        val now =
                            android.os.SystemClock.elapsedRealtime()

                        if (now - lastLevelUpdate >= 500) {

                            lastLevelUpdate = now

                            val level = (
                                ((db + 60.0) / 60.0) * 100
                            ).toInt().coerceIn(0, 100)

                            sendUpdate(
                                level = level,
                                db = db.toInt()
                            )
                        }

                        val enoughSamples =
                            totalSamples >= requiredSamples

                        val enoughNewSamples =
                            totalSamples -
                                lastClassification >= 8000L

                        if (
                            enoughSamples &&
                            enoughNewSamples
                        ) {

                            if (!isSessionActive(currentSession)) break

                            val results =
                                classifier.classify(tensor)

                            lastClassification = totalSamples

                            val detected =
                                findDetectedEvent(results)

                            if (isSessionActive(currentSession)) {
                                processEvent(detected, currentSession)
                            }
                        }

                    } else if (count < 0) {

                        throw IllegalStateException(
                            "خطأ قراءة الميكروفون: $count"
                        )
                    }
                }

            } catch (e: Exception) {

                if (isSessionActive(currentSession)) {
                    synchronized(lifecycleLock) {
                        if (sessionId == currentSession) {
                            reading = false
                            serviceRunning = false
                        }
                    }

                    markSessionInactive(currentSession)

                    handler.post {
                        if (sessionId != currentSession) return@post

                        sendUpdate(
                            status = "حدث خطأ",
                            error = e.message
                                ?: "حدث خطأ أثناء تحليل الصوت",
                            level = 0,
                            db = -90
                        )

                        stopForegroundCompat()
                        stopSelf()
                    }
                }

            } finally {

                try {
                    localRecorder?.stop()
                } catch (_: Exception) {
                }

                try {
                    localRecorder?.release()
                } catch (_: Exception) {
                }

                try {
                    classifier?.close()
                } catch (_: Exception) {
                }

                synchronized(lifecycleLock) {
                    if (recorder === localRecorder) {
                        recorder = null
                    }
                    if (worker === Thread.currentThread()) {
                        worker = null
                    }
                }
            }

        }.apply {
            name = "RoomSoundAnalysis"
            start()
        }
    }

    private fun findDetectedEvent(
        results: List<Classifications>
    ): DetectedEvent? {

        val sensitivity = getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        ).getInt(KEY_SENSITIVITY, 60)

        val factor =
            1.4f - (sensitivity / 100f * 0.6f)

        return results
            .flatMap { it.categories }
            .mapNotNull { category ->

                val label = category.label
                    .trim()
                    .lowercase(Locale.US)

                val info =
                    getEventInfo(label)
                        ?: return@mapNotNull null

                if (!isCategoryEnabled(info.group)) {
                    return@mapNotNull null
                }

                val threshold =
                    info.threshold * factor

                if (category.score < threshold) {
                    return@mapNotNull null
                }

                DetectedEvent(
                    info.group,
                    info.name,
                    category.score,
                    info.alert,
                    info.confirmationHits
                )
            }
            .maxByOrNull { it.score }
    }

    private fun getEventInfo(
        label: String
    ): EventInfo? {

        return when (label) {

            "speech" -> EventInfo(
                "speech", "كلام بشري", 0.35f, false
            )

            "child speech, kid speaking" -> EventInfo(
                "speech", "كلام طفل", 0.35f, false
            )

            "hubbub, speech noise, speech babble" ->
                EventInfo(
                    "speech", "ضوضاء كلام", 0.35f, false
                )

            "crying, sobbing" -> EventInfo(
                "crying", "بكاء", 0.30f, true
            )

            "baby cry, infant cry" -> EventInfo(
                "crying", "بكاء طفل", 0.30f, true
            )

            "shout" -> EventInfo(
                "shout", "صراخ", 0.25f, true
            )

            "children shouting" -> EventInfo(
                "shout", "أطفال يصرخون", 0.25f, true
            )

            "screaming" -> EventInfo(
                "shout", "صراخ حاد", 0.25f, true
            )

            "clapping" -> EventInfo(
                "clap", "تصفيق", 0.25f, true
            )

            "walk, footsteps" -> EventInfo(
                "footsteps", "خطوات مشي", 0.40f, false
            )

            "knock" -> EventInfo(
                "knock", "طرق", 0.25f, true
            )

            "tap" -> EventInfo(
                "knock", "نقر", 0.25f, true
            )

            "thump, thud" -> EventInfo(
                "impact", "ارتطام مكتوم", 0.25f, true, confirmationHits = 1
            )

            "bang" -> EventInfo(
                "impact", "ضربة قوية", 0.25f, true, confirmationHits = 1
            )

            "alarm" -> EventInfo(
                "alarm", "إنذار", 0.25f, true
            )

            "alarm clock" -> EventInfo(
                "alarm", "منبه", 0.25f, true
            )

            "smoke detector, smoke alarm" ->
                EventInfo(
                    "alarm", "إنذار كاشف الدخان", 0.25f, true
                )

            "fire alarm" -> EventInfo(
                "alarm", "إنذار حريق", 0.25f, true
            )

            "car alarm" -> EventInfo(
                "alarm", "إنذار سيارة", 0.25f, true
            )

            "telephone bell ringing" -> EventInfo(
                "phone", "رنين هاتف", 0.30f, true
            )

            "ringtone" -> EventInfo(
                "phone", "نغمة رنين", 0.30f, true
            )

            "siren" -> EventInfo(
                "siren", "صفارة إنذار", 0.25f, true
            )

            "civil defense siren" -> EventInfo(
                "siren", "صفارة دفاع مدني", 0.25f, true
            )

            "police car (siren)" -> EventInfo(
                "siren", "صفارة شرطة", 0.25f, true
            )

            "ambulance (siren)" -> EventInfo(
                "siren", "صفارة إسعاف", 0.25f, true
            )

            "fire engine, fire truck (siren)" -> EventInfo(
                "siren", "صفارة سيارة إطفاء", 0.25f, true
            )

            "vehicle horn, car horn, honking" -> EventInfo(
                "horn", "بوق سيارة", 0.30f, true
            )

            "air horn, truck horn" -> EventInfo(
                "horn", "بوق شاحنة", 0.30f, true
            )

            "doorbell" -> EventInfo(
                "doorbell", "جرس باب", 0.30f, true
            )

            "door" -> EventInfo(
                "door", "صوت باب", 0.35f, false
            )

            "sliding door" -> EventInfo(
                "door", "باب منزلق", 0.35f, false
            )

            "bell" -> EventInfo(
                "bell", "جرس", 0.35f, false
            )

            "church bell" -> EventInfo(
                "bell", "جرس كنيسة", 0.35f, false
            )

            "jingle bell" -> EventInfo(
                "bell", "جرس صغير", 0.35f, false
            )

            "bicycle bell" -> EventInfo(
                "bell", "جرس دراجة", 0.35f, false
            )

            "buzzer" -> EventInfo(
                "signal", "بازر", 0.30f, false
            )

            "whistle" -> EventInfo(
                "signal", "صفير", 0.35f, false
            )

            "smash, crash" -> EventInfo(
                "impact", "تحطم أو اصطدام", 0.25f, true, confirmationHits = 1
            )

            "glass" -> EventInfo(
                "impact", "صوت زجاج", 0.40f, false
            )

            else -> null
        }
    }

    private fun isCategoryEnabled(group: String): Boolean {
        val key = when (group) {
            "speech" -> KEY_CATEGORY_SPEECH
            "shout" -> KEY_CATEGORY_SHOUT
            "clap" -> KEY_CATEGORY_CLAP
            "knock" -> KEY_CATEGORY_KNOCK
            "impact" -> KEY_CATEGORY_IMPACT
            "alarm" -> KEY_CATEGORY_ALARM
            "doorbell" -> KEY_CATEGORY_DOORBELL
            "door" -> KEY_CATEGORY_DOOR
            "crying" -> KEY_CATEGORY_CRYING
            "phone" -> KEY_CATEGORY_PHONE
            "siren" -> KEY_CATEGORY_SIREN
            "horn" -> KEY_CATEGORY_HORN
            "footsteps" -> KEY_CATEGORY_FOOTSTEPS
            "bell" -> KEY_CATEGORY_BELL
            "signal" -> KEY_CATEGORY_SIGNAL
            else -> return true
        }

        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(key, true)
    }

    private fun processEvent(
        event: DetectedEvent?,
        currentSession: Long
    ) {

        if (!isSessionActive(currentSession)) return

        if (event == null) {

            consecutiveHits.clear()

            sendUpdate(
                eventName = "لم يظهر تصنيف مستهدف الآن"
            )

            return
        }

        val hits =
            (consecutiveHits[event.group] ?: 0) + 1

        consecutiveHits.clear()
        consecutiveHits[event.group] = hits

        val percentage =
            (event.score * 100).toInt()

        val now =
            android.os.SystemClock.elapsedRealtime()

        var shouldAlert = false

        if (event.alert && hits >= event.confirmationHits) {

            val previous =
                lastAlerts[event.group] ?: 0L

            if (now - previous >= 7000L) {
                lastAlerts[event.group] = now
                shouldAlert = true
            }
        }

        val eventTime = System.currentTimeMillis()
        val eventId = "$eventTime-${event.group}-${event.name}"
        val shouldLog =
            shouldPersistEvent(
                isAlert = shouldAlert,
                nameChanged = event.name != lastLoggedName,
                elapsedSinceLastLog = now - lastLoggedTime
            )

        if (shouldLog) {
            lastLoggedName = event.name
            lastLoggedTime = now
            saveEvent(
                eventId = eventId,
                name = event.name,
                score = percentage,
                alert = shouldAlert,
                eventTime = eventTime
            )
        }

        val eventLabel = "${event.name} — $percentage%"
        sendUpdate(
            eventName = eventLabel,
            alert = shouldAlert,
            alertEventId = if (shouldAlert) eventId else null,
            alertName = if (shouldAlert) event.name else null,
            alertScore = if (shouldAlert) percentage else null,
            alertTime = if (shouldAlert) eventTime else null
        )

        if (shouldAlert && isSessionActive(currentSession)) {
            playAlert(currentSession)
        }
    }

    private fun saveEvent(
        eventId: String,
        name: String,
        score: Int,
        alert: Boolean,
        eventTime: Long
    ) {

        val prefs = getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )

        synchronized(HISTORY_LOCK) {
            val old = try {
                JSONArray(prefs.getString(KEY_HISTORY, "[]"))
            } catch (_: Exception) {
                JSONArray()
            }

            val updated = JSONArray()
            updated.put(
                JSONObject().apply {
                    put("id", eventId)
                    put("name", name)
                    put("score", score)
                    put("alert", alert)
                    put("time", eventTime)
                }
            )

            for (i in 0 until minOf(old.length(), 19)) {
                updated.put(old.getJSONObject(i))
            }

            val editor = prefs.edit().putString(KEY_HISTORY, updated.toString())
            if (alert) editor.putString("last_alert", "تنبيه: $name")
            editor.apply()
        }

        sendUpdate(historyChanged = true)
    }

    private fun clearLastAlert() {
        synchronized(HISTORY_LOCK) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove("last_alert")
                .apply()
        }
    }

    private fun isSessionActive(expectedSession: Long): Boolean {
        return reading && sessionId == expectedSession
    }

    private fun publishSessionRunning(expectedSession: Long): Boolean {
        synchronized(lifecycleLock) {
            if (!isSessionActive(expectedSession)) return false

            serviceRunning = true
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, true)
                .apply()

            clearLastAlert()

            // Publish while holding the lifecycle lock so stopMonitoring()
            // cannot invalidate this state between the check and the broadcast.
            sendUpdate(status = "المراقبة تعمل")
            return true
        }
    }

    private fun markSessionInactive(expectedSession: Long? = null) {
        synchronized(lifecycleLock) {
            if (expectedSession != null && sessionId != expectedSession) return

            serviceRunning = false
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, false)
                .apply()

            clearLastAlert()
        }
    }

    private fun updateNotificationIfSessionActive(
        expectedSession: Long,
        message: String
    ) {
        handler.post {
            synchronized(lifecycleLock) {
                if (!isSessionActive(expectedSession)) return@synchronized
                updateNotification(message)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun playAlert(currentSession: Long) {

        val soundEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ALERT_SOUND, true)
        val vibrationEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ALERT_VIBRATION, true)

        try {

            releaseAlertAudioFocus()

            if (!soundEnabled) {
                if (vibrationEnabled) {
                    synchronized(lifecycleLock) {
                        if (!destroyed && isSessionActive(currentSession)) {
                            vibrate()
                        }
                    }
                }
                return
            }

            synchronized(lifecycleLock) {
                if (destroyed || !isSessionActive(currentSession)) return

                val alertId = synchronized(audioFocusLock) {
                    nextAlertId += 1L
                    nextAlertId
                }

                // Ignore the short microphone echo caused by the app's own beep.
                ignoreInputUntil =
                    android.os.SystemClock.elapsedRealtime() + 900L

                val audioManager =
                    getSystemService(Context.AUDIO_SERVICE)
                        as AudioManager

                var focusRequest: AudioFocusRequest? = null

                val listener =
                    AudioManager.OnAudioFocusChangeListener {
                        // لا نحتاج معالجة إضافية هنا
                    }

                if (Build.VERSION.SDK_INT >= 26) {

                    val attributes =
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SONIFICATION
                            )
                            .build()

                    focusRequest =
                        AudioFocusRequest.Builder(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                        )
                            .setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener(listener)
                            .build()

                }

                synchronized(audioFocusLock) {
                    activeAudioManager = audioManager
                    activeFocusRequest = focusRequest
                    activeFocusListener = listener
                    activeAlertId = alertId
                }

                val focusResult = if (Build.VERSION.SDK_INT >= 26) {
                    audioManager.requestAudioFocus(focusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        listener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    )
                }

                if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.w(TAG, "Audio focus unavailable; using vibration fallback")
                    releaseAlertAudioFocus(alertId)
                    if (vibrationEnabled && !destroyed && isSessionActive(currentSession)) {
                        vibrate()
                    }
                    return
                }

                val newTone = ToneGenerator(
                    AudioManager.STREAM_MUSIC,
                    90
                )

                synchronized(audioFocusLock) {
                    activeTone = newTone
                }

                newTone.startTone(
                    ToneGenerator.TONE_PROP_BEEP,
                    450
                )

                if (vibrationEnabled) {
                    vibrate()
                }

                val releaseRunnable = Runnable {
                    releaseAlertAudioFocus(alertId)
                }

                synchronized(audioFocusLock) {
                    alertReleaseRunnable = releaseRunnable
                }
                handler.postDelayed(releaseRunnable, 900)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Alert playback failed", e)
            releaseAlertAudioFocus()
            synchronized(lifecycleLock) {
                if (
                    vibrationEnabled &&
                    !destroyed &&
                    isSessionActive(currentSession)
                ) {
                    vibrate()
                }
            }
        }
    }

    private fun releaseAlertAudioFocus(expectedAlertId: Long? = null) {
        val manager: AudioManager?
        val request: AudioFocusRequest?
        val listener: AudioManager.OnAudioFocusChangeListener?
        val releaseRunnable: Runnable?
        val toneToRelease: ToneGenerator?

        synchronized(audioFocusLock) {
            if (expectedAlertId != null && activeAlertId != expectedAlertId) {
                return
            }

            manager = activeAudioManager
            request = activeFocusRequest
            listener = activeFocusListener
            releaseRunnable = alertReleaseRunnable
            toneToRelease = activeTone
            activeAudioManager = null
            activeFocusRequest = null
            activeFocusListener = null
            alertReleaseRunnable = null
            activeTone = null
            activeAlertId = 0L
        }

        releaseRunnable?.let(handler::removeCallbacks)

        if (Build.VERSION.SDK_INT >= 26) {
            request?.let { manager?.abandonAudioFocusRequest(it) }
        } else if (manager != null && listener != null) {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(listener)
        }

        try {
            toneToRelease?.release()
        } catch (_: Exception) {
        }
    }

    private fun vibrate() {

        try {

            val vibrator: Vibrator =
                if (Build.VERSION.SDK_INT >= 31) {

                    val manager =
                        getSystemService(
                            Context.VIBRATOR_MANAGER_SERVICE
                        ) as VibratorManager

                    manager.defaultVibrator

                } else {

                    @Suppress("DEPRECATION")
                    getSystemService(
                        Context.VIBRATOR_SERVICE
                    ) as Vibrator
                }

            if (Build.VERSION.SDK_INT >= 26) {

                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        350,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )

            } else {

                @Suppress("DEPRECATION")
                vibrator.vibrate(350)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun sendUpdate(
        status: String? = null,
        eventName: String? = null,
        level: Int? = null,
        db: Int? = null,
        alert: Boolean = false,
        alertEventId: String? = null,
        alertName: String? = null,
        alertScore: Int? = null,
        alertTime: Long? = null,
        error: String? = null,
        historyChanged: Boolean = false
    ) {

        val intent = Intent(ACTION_UPDATE).apply {

            setPackage(packageName)

            status?.let {
                putExtra("status", it)
            }

            eventName?.let {
                putExtra("event", it)
            }

            level?.let {
                putExtra("level", it)
            }

            db?.let {
                putExtra("db", it)
            }

            putExtra("alert", alert)
            putExtra("history", historyChanged)

            alertEventId?.let { putExtra("alert_event_id", it) }
            alertName?.let { putExtra("alert_name", it) }
            alertScore?.let { putExtra("alert_score", it) }
            alertTime?.let { putExtra("alert_time", it) }

            error?.let {
                putExtra("error", it)
            }
        }

        sendBroadcast(intent)
    }

    private fun stopMonitoring() {

        val threadToStop: Thread?

        synchronized(lifecycleLock) {
            reading = false
            serviceRunning = false
            sessionId++
            threadToStop = worker
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, false)
                .apply()
        }


        clearLastAlert()

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        threadToStop
            ?.takeUnless { it === Thread.currentThread() }
            ?.interrupt()

        releaseAlertAudioFocus()

        sendUpdate(
            status = "متوقف",
            eventName = "لم يبدأ تحليل الأصوات",
            level = 0,
            db = -90
        )

        stopForegroundCompat()
        stopSelf()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "مراقبة صوت الغرفة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "إشعار دائم أثناء مراقبة الأصوات"
            }

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        message: String
    ): Notification {

        val openIntent =
            Intent(this, MainActivity::class.java)

        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val stopIntent =
            Intent(this, SoundMonitorService::class.java)
                .setAction(ACTION_STOP)

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("مراقب صوت الغرفة")
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                "إيقاف",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(
        message: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(message)
        )
    }

    override fun onDestroy() {

        val wasReading: Boolean
        val threadToStop: Thread?

        synchronized(lifecycleLock) {
            destroyed = true
            wasReading = reading
            reading = false
            serviceRunning = false
            sessionId++
            threadToStop = worker
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, false)
                .apply()
        }


        clearLastAlert()

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        threadToStop
            ?.takeUnless { it === Thread.currentThread() }
            ?.interrupt()

        releaseAlertAudioFocus()
        handler.removeCallbacksAndMessages(null)

        if (wasReading) {
            sendUpdate(
                status = "متوقف",
                eventName = "لم يبدأ تحليل الأصوات",
                level = 0,
                db = -90
            )
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
