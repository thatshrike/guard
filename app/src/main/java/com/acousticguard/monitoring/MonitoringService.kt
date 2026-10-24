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
import com.acousticguard.ml.AudioRecordFactory
import com.acousticguard.ml.AudioSignalProcessor
import com.acousticguard.ml.DistressClassifier
import com.acousticguard.ml.DistressFusionEngine
import com.acousticguard.ml.ModelInfo
import com.acousticguard.ml.ModelSelector
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
    private val modelSelector = ModelSelector()
    private val powerPolicy = MonitoringPowerPolicy()
    private val metrics = DetectionMetricsTracker()
    private lateinit var dispatchCoordinator: DispatchCoordinator
    private var sensorManager: android.hardware.SensorManager? = null
    private var motionSamplingMode: MotionSamplingMode? = null
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
        registerMotionSensor(MotionSamplingMode.LOW_POWER)
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
        motionSamplingMode = null
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

    private fun registerMotionSensor(mode: MotionSamplingMode) {
        val manager = sensorManager ?: return
        val sensor = manager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            motionSamplingMode = null
            MonitoringStatusStore.update {
                it.copy(
                    motionReady = false,
                    monitoringMode = MonitoringMode.DEGRADED,
                    message = "Monitoring audio locally; motion sensor unavailable.",
                )
            }
            return
        }
        if (motionSamplingMode == mode) return
        manager.unregisterListener(this)
        manager.registerListener(this, sensor, mode.samplingPeriodUs)
        motionSamplingMode = mode
        MonitoringStatusStore.update {
            it.copy(
                motionReady = true,
                motionSamplingMode = mode,
            )
        }
    }

    private fun startAudioLoop() {
        MonitoringStatusStore.update {
            it.copy(engineState = EngineState.STARTING, message = "Starting the local detector…")
        }
        inferenceJob = serviceScope.launch {
            val selection = runCatching { modelSelector.select(this@MonitoringService) }
                .getOrElse { failure ->
                    MonitoringStatusStore.update {
                        it.copy(
                            engineState = EngineState.ERROR,
                            monitoringMode = MonitoringMode.DEGRADED,
                            modelInfo = ModelInfo.UNAVAILABLE,
                            lastModelError = failure.message,
                            message = "The local audio models could not be loaded. Monitoring is paused.",
                        )
                    }
                    stopSelf()
                    return@launch
                }
            val classifier: DistressClassifier = selection.classifier
            metrics.reset()
            MonitoringStatusStore.update {
                it.copy(
                    modelInfo = classifier.modelInfo,
                    fallbackModelActive = selection.fallbackActive,
                    lastModelError = selection.fallbackReason,
                    monitoringMode = if (it.motionReady) MonitoringMode.NORMAL else MonitoringMode.DEGRADED,
                    message = if (selection.fallbackActive) {
                        "Monitoring locally with the verified YAMNet fallback. No audio is stored."
                    } else {
                        "Starting the domain-specific local detector…"
                    },
                )
            }

            val windowSamples = classifier.requiredInputBufferSize
            val pcmBuffer = ShortArray(windowSamples)
            val normalizedBuffer = FloatArray(windowSamples)
            val record = runCatching {
                AudioRecordFactory.create(classifier.sampleRateHz, classifier.requiredInputBufferSize)
            }
                .getOrElse {
                    classifier.close()
                    MonitoringStatusStore.update {
                        it.copy(
                            engineState = EngineState.ERROR,
                            monitoringMode = MonitoringMode.DEGRADED,
                            lastModelError = "The microphone could not be initialized.",
                            message = "The microphone could not be initialized.",
                        )
                    }
                    stopSelf()
                    return@launch
                }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                classifier.close()
                MonitoringStatusStore.update {
                    it.copy(
                        engineState = EngineState.ERROR,
                        monitoringMode = MonitoringMode.DEGRADED,
                        lastModelError = "The microphone could not be initialized.",
                        message = "The microphone could not be initialized.",
                    )
                }
                stopSelf()
                return@launch
            }
            audioRecord = record

            try {
                val scheduler = InferenceScheduler()
                record.startRecording()
                MonitoringStatusStore.update {
                    it.copy(
                        engineState = EngineState.MONITORING,
                        monitoringMode = if (it.motionReady) MonitoringMode.NORMAL else MonitoringMode.DEGRADED,
                        message = if (selection.fallbackActive) {
                            "Monitoring locally with the verified YAMNet fallback. No audio is stored."
                        } else {
                            "Monitoring locally. No audio is stored."
                        },
                    )
                }

                while (isActive) {
                    var filled = 0
                    while (filled < pcmBuffer.size && isActive) {
                        when (val read = record.read(pcmBuffer, filled, pcmBuffer.size - filled, AudioRecord.READ_BLOCKING)) {
                            in 1..Int.MAX_VALUE -> filled += read
                            0 -> delay(10L)
                            else -> throw IllegalStateException("AudioRecord read failed with code $read.")
                        }
                    }
                    if (!isActive) break
                    metrics.recordAudioWindowRead()

                    for (index in normalizedBuffer.indices) {
                        normalizedBuffer[index] = pcmBuffer[index] / PCM_SCALE
                    }

                    val motion = motionTracker.snapshot()
                    val features = featureProcessor.extract(normalizedBuffer, normalizedBuffer.size, motion)
                    val nowMs = System.currentTimeMillis()
                    updateMotionSamplingMode(features, motion)
                    val shouldRunModel = scheduler.shouldRunImmediately(features, motion) ||
                        scheduler.quietScanDue(nowMs) ||
                        scheduler.previousResultWasUncertain()

                    if (!shouldRunModel) {
                        metrics.recordSkippedModelInvocation()
                        MonitoringStatusStore.update { status ->
                            status.copy(
                                engineState = EngineState.MONITORING,
                                monitoringMode = if (status.motionReady) MonitoringMode.LOW_POWER else MonitoringMode.DEGRADED,
                                motionSamplingMode = motionSamplingMode ?: MotionSamplingMode.LOW_POWER,
                                detectionMetrics = metrics.snapshot(),
                                message = "Low-power local scan active. Awaiting a candidate event.",
                            )
                        }
                    } else {
                        val inferenceStartedAt = System.nanoTime()
                        val inference = classifier.infer(normalizedBuffer, motion, features)
                        val latencyMs = (System.nanoTime() - inferenceStartedAt)
                            .coerceAtLeast(0L) / NANOS_PER_MILLISECOND
                        metrics.recordModelInvocation(latencyMs, classifier.modelInfo.modelVersion)
                        scheduler.recordInference(nowMs, inference)
                        val decision = fusionEngine.update(inference, motion, nowMs)

                        if (decision?.confirmed == true) {
                            beginConfirmationCountdown(decision, classifier.modelInfo.modelVersion)
                        } else if (decision != null) {
                            MonitoringStatusStore.update { status ->
                                status.copy(
                                    engineState = EngineState.MONITORING,
                                    monitoringMode = if (status.motionReady) MonitoringMode.NORMAL else MonitoringMode.DEGRADED,
                                    motionSamplingMode = motionSamplingMode ?: MotionSamplingMode.LOW_POWER,
                                    lastMarker = decision.marker,
                                    lastConfidence = decision.confidence,
                                    detectionMetrics = metrics.snapshot(),
                                    message = "Potential ${decision.marker.label.lowercase()} detected (${(decision.confidence * 100).toInt()}%). Awaiting corroboration.",
                                )
                            }
                        } else {
                            MonitoringStatusStore.update { status ->
                                status.copy(
                                    engineState = EngineState.MONITORING,
                                    monitoringMode = if (status.motionReady) MonitoringMode.NORMAL else MonitoringMode.DEGRADED,
                                    motionSamplingMode = motionSamplingMode ?: MotionSamplingMode.LOW_POWER,
                                    lastConfidence = maxOf(
                                        inference.screamProbability,
                                        inference.respiratoryProbability,
                                        inference.impactProbability,
                                    ),
                                    detectionMetrics = metrics.snapshot(),
                                    message = if (inference.uncertainty >= UNCERTAIN_RESULT_THRESHOLD) {
                                        "The local model was uncertain; no event was advanced."
                                    } else {
                                        "Listening in volatile memory. Awaiting corroboration."
                                    },
                                )
                            }
                        }
                    }

                    // Make the temporary PCM window eligible for collection without retaining audio.
                    pcmBuffer.fill(0)
                    normalizedBuffer.fill(0f)
                }
            } catch (_: SecurityException) {
                MonitoringStatusStore.update {
                    it.copy(
                        engineState = EngineState.BLOCKED,
                        monitoringMode = MonitoringMode.DEGRADED,
                        detectionMetrics = metrics.snapshot(),
                        message = "Microphone permission was revoked.",
                    )
                }
            } catch (_: IllegalStateException) {
                metrics.recordDroppedWindow()
                MonitoringStatusStore.update {
                    it.copy(
                        engineState = EngineState.ERROR,
                        monitoringMode = MonitoringMode.DEGRADED,
                        detectionMetrics = metrics.snapshot(),
                        message = "Microphone input stopped. Toggle monitoring off and on to retry.",
                    )
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                classifier.close()
                audioRecord = null
                pcmBuffer.fill(0)
                normalizedBuffer.fill(0f)
                inferenceJob = null
            }
        }
    }

    private fun updateMotionSamplingMode(
        features: com.acousticguard.ml.AcousticFeatureVector,
        motion: com.acousticguard.ml.MotionSnapshot,
    ) {
        if (motionSamplingMode == null && MonitoringStatusStore.state.value.motionReady) {
            registerMotionSensor(MotionSamplingMode.LOW_POWER)
        }
        val desiredMode = powerPolicy.modeFor(features, motion)
        if (desiredMode != motionSamplingMode) registerMotionSensor(desiredMode)
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
        const val PCM_SCALE = 32768f
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val UNCERTAIN_RESULT_THRESHOLD = 0.85f
    }
}
