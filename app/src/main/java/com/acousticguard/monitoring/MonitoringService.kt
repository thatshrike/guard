package com.acousticguard.monitoring

import android.annotation.SuppressLint
import android.Manifest
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.acousticguard.dispatch.DispatchCoordinator
import com.acousticguard.ml.AudioSignalProcessor
import com.acousticguard.ml.DistressFusionEngine
import com.acousticguard.ml.YamnetDistressClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Foreground microphone service. It holds one short PCM window in RAM, extracts scalar features,
 * clears the buffer, and never writes audio to disk or sends it to a network.
 */
class MonitoringService : Service(), android.hardware.SensorEventListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val motionTracker = MotionFeatureTracker()
    private val featureProcessor = AudioSignalProcessor()
    private val fusionEngine = DistressFusionEngine()
    private lateinit var dispatchCoordinator: DispatchCoordinator
    private var sensorManager: android.hardware.SensorManager? = null
    private var audioRecord: AudioRecord? = null
    private var inferenceJob: Job? = null
    private var pendingConfirmationJob: Job? = null
    private var pendingConfirmation: PendingConfirmation? = null

    override fun onCreate() {
        super.onCreate()
        dispatchCoordinator = DispatchCoordinator(applicationContext)
        NotificationChannels.create(this)
        sensorManager = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        serviceScope.launch(Dispatchers.IO) { dispatchCoordinator.retry() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_PENDING_ALERT) {
            cancelPendingConfirmation()
            return START_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            MonitoringStatusStore.update {
                it.copy(
                    engineState = EngineState.BLOCKED,
                    message = "Microphone permission is required before monitoring can start.",
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!notificationsAvailable()) {
            MonitoringStatusStore.update {
                it.copy(
                    engineState = EngineState.BLOCKED,
                    message = "Notifications must remain enabled so monitoring and alert cancellation stay visible.",
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForegroundService()
        registerMotionSensor()
        if (inferenceJob?.isActive != true) startAudioLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        pendingConfirmationJob?.cancel()
        pendingConfirmation = null
        cancelAlertNotification()
        inferenceJob?.cancel()
        audioRecord?.let { record ->
            runCatching { record.stop() } // Unblocks a blocking read; the inference job releases it.
        }
        audioRecord = null
        sensorManager?.unregisterListener(this)
        serviceScope.cancel()
        MonitoringStatusStore.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
            motionTracker.update(event.values[0], event.values[1], event.values[2])
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit

    private fun startAsForegroundService() {
        val notification = android.app.Notification.Builder(this, NotificationChannels.MONITORING_CHANNEL_ID)
            .setSmallIcon(com.acousticguard.R.drawable.ic_acoustic_guard)
            .setContentTitle("AcousticGuard is monitoring")
            .setContentText("Local acoustic and motion analysis is active")
            .setOngoing(true)
            .setCategory(android.app.Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this,
                MONITORING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(MONITORING_NOTIFICATION_ID, notification)
        }
    }

    private fun registerMotionSensor() {
        val manager = sensorManager ?: return
        val sensor = manager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            MonitoringStatusStore.update { it.copy(motionReady = false, message = "Monitoring audio locally; motion sensor unavailable.") }
            return
        }
        manager.registerListener(this, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        MonitoringStatusStore.update { it.copy(motionReady = true) }
    }

    private fun startAudioLoop() {
        MonitoringStatusStore.update {
            it.copy(engineState = EngineState.STARTING, message = "Starting the local detector…")
        }
        inferenceJob = serviceScope.launch {
            val classifier = runCatching { YamnetDistressClassifier(this@MonitoringService) }
                .getOrElse {
                    MonitoringStatusStore.update {
                        it.copy(engineState = EngineState.ERROR, message = "The local audio model could not be loaded.")
                    }
                    stopSelf()
                    return@launch
                }
            val windowSamples = classifier.requiredInputBufferSize
            val buffer = ShortArray(windowSamples)
            val record = runCatching { classifier.createAudioRecord() }
                .getOrElse {
                    classifier.close()
                    MonitoringStatusStore.update {
                        it.copy(engineState = EngineState.ERROR, message = "The microphone could not be initialized.")
                    }
                    stopSelf()
                    return@launch
                }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                classifier.close()
                MonitoringStatusStore.update {
                    it.copy(engineState = EngineState.ERROR, message = "The microphone could not be initialized.")
                }
                stopSelf()
                return@launch
            }
            audioRecord = record

            try {
                record.startRecording()
                MonitoringStatusStore.update {
                    it.copy(engineState = EngineState.MONITORING, message = "Monitoring locally. No audio is stored.")
                }

                while (isActive) {
                    var filled = 0
                    while (filled < buffer.size && isActive) {
                        val read = record.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
                        if (read <= 0) throw IllegalStateException("The microphone returned no samples.")
                        filled += read
                    }
                    if (!isActive) break

                    val motion = motionTracker.snapshot()
                    val features = featureProcessor.extract(buffer, buffer.size, motion)
                    val inference = classifier.infer(buffer, motion, features)
                    val decision = fusionEngine.update(inference, motion, System.currentTimeMillis())

                    if (decision?.confirmed == true) {
                        beginConfirmationCountdown(decision, inference.modelVersion)
                    } else {
                        MonitoringStatusStore.update {
                            it.copy(
                                engineState = EngineState.MONITORING,
                                lastConfidence = maxOf(
                                    inference.screamProbability,
                                    inference.respiratoryProbability,
                                    inference.impactProbability,
                                ),
                                message = "Listening in volatile memory. Awaiting corroboration.",
                            )
                        }
                    }

                    // Make the temporary PCM window eligible for collection without retaining audio.
                    buffer.fill(0)
                }
            } catch (_: SecurityException) {
                MonitoringStatusStore.update { it.copy(engineState = EngineState.BLOCKED, message = "Microphone permission was revoked.") }
            } catch (_: IllegalStateException) {
                MonitoringStatusStore.update { it.copy(engineState = EngineState.ERROR, message = "The microphone stopped unexpectedly.") }
            } finally {
                runCatching { record.stop() }
                record.release()
                classifier.close()
                audioRecord = null
                buffer.fill(0)
                inferenceJob = null
            }
        }
    }

    private fun beginConfirmationCountdown(
        decision: com.acousticguard.ml.FusionDecision,
        modelVersion: String,
    ) {
        if (pendingConfirmationJob?.isActive == true) return
        if (!notificationsAvailable()) {
            MonitoringStatusStore.update {
                it.copy(
                    engineState = EngineState.BLOCKED,
                    countdownSecondsRemaining = null,
                    message = "Notifications are unavailable; the potential event was not auto-enqueued.",
                )
            }
            return
        }

        val confirmation = PendingConfirmation(
            token = System.currentTimeMillis(),
            decision = decision,
            modelVersion = modelVersion,
        )
        pendingConfirmation = confirmation
        pendingConfirmationJob = serviceScope.launch {
            for (remaining in COUNTDOWN_SECONDS downTo 1) {
                if (!isActive || pendingConfirmation?.token != confirmation.token) return@launch
                MonitoringStatusStore.update { status ->
                    status.copy(
                        engineState = EngineState.MONITORING,
                        lastMarker = decision.marker,
                        lastConfidence = decision.confidence,
                        countdownSecondsRemaining = remaining,
                        message = "Potential ${decision.marker.label.lowercase()} detected. Automatic alert in ${remaining}s; tap Cancel if this is a false alarm.",
                    )
                }
                postPendingAlert(decision, remaining)
                delay(1_000L)
            }

            val enqueueResult = withContext(Dispatchers.IO) {
                dispatchCoordinator.enqueue(
                    marker = decision.marker.label,
                    confidence = decision.confidence,
                    modelVersion = modelVersion,
                    test = false,
                )
            }
            val pendingCount = withContext(Dispatchers.IO) {
                dispatchCoordinator.snapshot().records.count {
                    it.status != "ACCEPTED" && it.status != "FAILED"
                }
            }
            cancelAlertNotification()
            MonitoringStatusStore.update { status ->
                status.copy(
                    engineState = EngineState.MONITORING,
                    lastMarker = decision.marker,
                    lastConfidence = decision.confidence,
                    lastSignalAtEpochMs = System.currentTimeMillis(),
                    pendingIncidentCount = pendingCount,
                    countdownSecondsRemaining = null,
                    message = if (enqueueResult.isSuccess) {
                        "Confirmed pattern stored locally. No audio was retained."
                    } else {
                        "Confirmed pattern could not be stored; monitoring continues locally."
                    },
                )
            }
            pendingConfirmation = null
            pendingConfirmationJob = null
        }
    }

    private fun cancelPendingConfirmation() {
        pendingConfirmationJob?.cancel()
        pendingConfirmationJob = null
        pendingConfirmation = null
        cancelAlertNotification()
        MonitoringStatusStore.update {
            it.copy(
                engineState = EngineState.MONITORING,
                countdownSecondsRemaining = null,
                message = "Potential event cancelled. Monitoring continues locally.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun postPendingAlert(decision: com.acousticguard.ml.FusionDecision, secondsRemaining: Int) {
        if (!notificationsAvailable()) return
        val cancelIntent = Intent(this, MonitoringService::class.java).apply {
            action = ACTION_CANCEL_PENDING_ALERT
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            CANCEL_PENDING_ALERT_REQUEST_CODE,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.ALERT_CHANNEL_ID)
            .setSmallIcon(com.acousticguard.R.drawable.ic_acoustic_guard)
            .setContentTitle("Potential distress pattern detected")
            .setContentText("Automatic alert in ${secondsRemaining}s. Tap Cancel if this is a false alarm.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${decision.marker.label} was corroborated by the local model and motion sensors. Raw audio is not retained."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(NotificationCompat.Action(0, "Cancel", cancelPendingIntent))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(PENDING_ALERT_NOTIFICATION_ID, notification) }
    }

    private fun cancelAlertNotification() {
        runCatching { NotificationManagerCompat.from(this).cancel(PENDING_ALERT_NOTIFICATION_ID) }
    }

    private fun notificationsAvailable(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private data class PendingConfirmation(
        val token: Long,
        val decision: com.acousticguard.ml.FusionDecision,
        val modelVersion: String,
    )

    private companion object {
        const val ACTION_CANCEL_PENDING_ALERT = "com.acousticguard.action.CANCEL_PENDING_ALERT"
        const val CANCEL_PENDING_ALERT_REQUEST_CODE = 3102
        const val PENDING_ALERT_NOTIFICATION_ID = 3103
        const val COUNTDOWN_SECONDS = 10
        const val MONITORING_NOTIFICATION_ID = 3100
    }
}
