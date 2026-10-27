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
import com.acousticguard.ml.DistressClassifier
import com.acousticguard.ml.DistressFusionEngine
import com.acousticguard.ml.DetectionPolicy
import com.acousticguard.ml.DetectionPolicyAssets
import com.acousticguard.ml.ModelInfo
import com.acousticguard.ml.ModelSelector
import com.acousticguard.safety.DirectEmergencyDispatcher
import com.acousticguard.safety.EmergencySirenPlayer
import com.acousticguard.safety.NativeSafetyHandoffController
import com.acousticguard.safety.NativeSafetyHandoffRequestResult
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
    private val modelSelector = ModelSelector()
    private val powerPolicy = MonitoringPowerPolicy()
    private val metrics = DetectionMetricsTracker()
    private var activePolicy = DetectionPolicy.CURRENT
    private lateinit var dispatchCoordinator: DispatchCoordinator
    private var sensorManager: android.hardware.SensorManager? = null
    private var motionSamplingMode: MotionSamplingMode? = null
    private var audioRecord: AudioRecord? = null
    private var inferenceJob: Job? = null
    private var pendingConfirmationJob: Job? = null
    private var pendingConfirmation: PendingConfirmation? = null
    private var preserveTerminalStatus = false

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
        if (intent?.action == ACTION_STOP_SIREN) {
            EmergencySirenPlayer.stop()
            return START_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            MonitoringStatusStore.update {
                it.copy(
                    engineState = EngineState.BLOCKED,
                    message = "Microphone permission is required before monitoring can start.",
                )
            }
            stopAfterFailure()
            return START_NOT_STICKY
        }
        if (!notificationsAvailable()) {
            MonitoringStatusStore.update {
                it.copy(
                    engineState = EngineState.BLOCKED,
                    message = "Monitoring and alert notifications must remain enabled so cancellation stays visible.",
                )
            }
            stopAfterFailure()
            return START_NOT_STICKY
        }

        if (runCatching { startAsForegroundService() }.isFailure) {
            MonitoringStatusStore.update {
                it.copy(
                    engineState = EngineState.ERROR,
                    monitoringMode = MonitoringMode.DEGRADED,
                    message = "The monitoring notification could not be started. Monitoring is paused.",
                )
            }
            stopAfterFailure()
            return START_NOT_STICKY
        }
        registerMotionSensor(MotionSamplingMode.LOW_POWER)
        if (inferenceJob?.isActive != true) startAudioLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        EmergencySirenPlayer.stop()
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
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (!preserveTerminalStatus) MonitoringStatusStore.reset()
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

    private fun registerMotionSensor(mode: MotionSamplingMode): Boolean {
        val manager = sensorManager
        if (manager == null) {
            motionSamplingMode = null
            MonitoringStatusStore.update {
                it.copy(
                    motionReady = false,
                    monitoringMode = MonitoringMode.DEGRADED,
                    message = "Monitoring audio locally; motion services are unavailable.",
                )
            }
            return false
        }
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
            return false
        }
        if (motionSamplingMode == mode) return true
        manager.unregisterListener(this)
        val registered = runCatching {
            manager.registerListener(this, sensor, mode.samplingPeriodUs)
        }.getOrDefault(false)
        if (!registered) {
            motionSamplingMode = null
            MonitoringStatusStore.update {
                it.copy(
                    motionReady = false,
                    monitoringMode = MonitoringMode.DEGRADED,
                    message = "Monitoring audio locally; motion sampling could not be started.",
                )
            }
            return false
        }
        motionSamplingMode = mode
        MonitoringStatusStore.update {
            it.copy(
                motionReady = true,
                motionSamplingMode = mode,
            )
        }
        return true
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
                    stopAfterFailure()
                    return@launch
            }
            val classifier: DistressClassifier = selection.classifier
            val policy = runCatching {
                DetectionPolicyAssets.load(this@MonitoringService)
            }.getOrElse { failure ->
                classifier.close()
                MonitoringStatusStore.update {
                    it.copy(
                        engineState = EngineState.ERROR,
                        monitoringMode = MonitoringMode.DEGRADED,
                        lastModelError = failure.message,
                        message = "The detection policy could not be verified. Monitoring is paused.",
                    )
                }
                stopAfterFailure()
                return@launch
            }
            activePolicy = policy
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
                    stopAfterFailure()
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
                stopAfterFailure()
                return@launch
            }
            audioRecord = record
            val pipeline = DetectionPipeline(
                classifier = classifier,
                fusionEngine = DistressFusionEngine(policy),
            )

            try {
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
                    val pipelineResult = pipeline.process(normalizedBuffer, motion)
                    updateMotionSamplingMode(pipelineResult.features, motion)

                    if (!pipelineResult.modelInvoked) {
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
                        val inference = requireNotNull(pipelineResult.inference)
                        metrics.recordModelInvocation(pipelineResult.modelLatencyMs, classifier.modelInfo.modelVersion)
                        val decision = pipelineResult.decision

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
                                    message = if (inference.uncertainty >= activePolicy.uncertaintyThreshold) {
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
                stopAfterFailure()
            } catch (failure: IllegalStateException) {
                metrics.recordDroppedWindow()
                val detail = failure.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(160)
                    ?: "AudioRecord became unavailable."
                MonitoringStatusStore.update {
                    it.copy(
                        engineState = EngineState.ERROR,
                        monitoringMode = MonitoringMode.DEGRADED,
                        detectionMetrics = metrics.snapshot(),
                        message = "Microphone input stopped: $detail Toggle monitoring off and on to retry.",
                    )
                }
                stopAfterFailure()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                metrics.recordDroppedWindow()
                val detail = failure.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(160)
                    ?: failure.javaClass.simpleName
                MonitoringStatusStore.update {
                    it.copy(
                        engineState = EngineState.ERROR,
                        monitoringMode = MonitoringMode.DEGRADED,
                        detectionMetrics = metrics.snapshot(),
                        message = "Local monitoring stopped: $detail Toggle monitoring off and on to retry.",
                    )
                }
                stopAfterFailure()
            } finally {
                runCatching { record.stop() }
                record.release()
                pipeline.close()
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
        triggerAlertHaptic()
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
                if (!postPendingAlert(decision, remaining)) {
                    pendingConfirmation = null
                    pendingConfirmationJob = null
                    MonitoringStatusStore.update { status ->
                        status.copy(
                            engineState = EngineState.BLOCKED,
                            countdownSecondsRemaining = null,
                            message = "The cancellation notification could not be shown; the potential event was not auto-enqueued.",
                        )
                    }
                    return@launch
                }
                delay(1_000L)
            }

            // Re-check the user-visible cancellation path immediately before durable enqueue. This
            // closes the small race where a channel is blocked while the final countdown tick is
            // sleeping.
            if (!notificationsAvailable()) {
                pendingConfirmation = null
                pendingConfirmationJob = null
                cancelAlertNotification()
                MonitoringStatusStore.update { status ->
                    status.copy(
                        engineState = EngineState.BLOCKED,
                        countdownSecondsRemaining = null,
                        message = "The cancellation notification became unavailable; the potential event was not auto-enqueued.",
                    )
                }
                return@launch
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
                dispatchCoordinator.snapshot().pendingCount
            }
            val sosResult = DirectEmergencyDispatcher.executeAutonomousSos(
                context = this@MonitoringService,
                marker = decision.marker.label,
                confidence = decision.confidence,
            )
            val nativeHandoff = NativeSafetyHandoffController.request(
                context = this@MonitoringService,
                incidentId = enqueueResult.getOrNull(),
                marker = decision.marker.label,
            )
            cancelAlertNotification()
            val confirmedMessage = if (sosResult.callInitiated || sosResult.smsSentCount > 0 || sosResult.sirenStarted) {
                "Hands-free SOS: ${sosResult.summary}"
            } else {
                confirmationMessage(enqueueResult.isSuccess, nativeHandoff)
            }
            val stopSirenIntent = Intent(this@MonitoringService, MonitoringService::class.java).apply {
                action = ACTION_STOP_SIREN
            }
            val stopSirenPendingIntent = PendingIntent.getService(
                this@MonitoringService,
                STOP_SIREN_REQUEST_CODE,
                stopSirenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notificationBuilder = NotificationCompat.Builder(this@MonitoringService, NotificationChannels.ALERT_CHANNEL_ID)
                .setSmallIcon(com.acousticguard.R.drawable.ic_acoustic_guard)
                .setContentTitle("Distress alert confirmed")
                .setContentText(confirmedMessage)
                .setStyle(NotificationCompat.BigTextStyle().bigText(confirmedMessage))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)

            if (sosResult.sirenStarted) {
                notificationBuilder.addAction(NotificationCompat.Action(0, "Silence Siren", stopSirenPendingIntent))
            }
            runCatching {
                NotificationManagerCompat.from(this@MonitoringService).notify(CONFIRMED_ALERT_NOTIFICATION_ID, notificationBuilder.build())
            }
            MonitoringStatusStore.update { status ->
                status.copy(
                    engineState = EngineState.MONITORING,
                    lastMarker = decision.marker,
                    lastConfidence = decision.confidence,
                    lastSignalAtEpochMs = System.currentTimeMillis(),
                    pendingIncidentCount = pendingCount,
                    countdownSecondsRemaining = null,
                    message = confirmedMessage,
                )
            }
            pendingConfirmation = null
            pendingConfirmationJob = null
        }
    }

    private fun confirmationMessage(
        stored: Boolean,
        nativeHandoff: NativeSafetyHandoffRequestResult,
    ): String {
        val storageMessage = if (stored) {
            "Confirmed pattern stored locally. No audio was retained."
        } else {
            "Confirmed pattern could not be stored; monitoring continues locally."
        }
        return when (nativeHandoff) {
            NativeSafetyHandoffRequestResult.REQUESTED ->
                "$storageMessage Native emergency-interface assistance was requested; complete any system confirmation on screen."

            NativeSafetyHandoffRequestResult.ACCESSIBILITY_REQUIRED ->
                "$storageMessage Enable Native safety handoff in Settings to open the Android emergency interface."

            NativeSafetyHandoffRequestResult.DISABLED ->
                "$storageMessage Native safety handoff is disabled."

            NativeSafetyHandoffRequestResult.NOT_REQUESTED -> storageMessage
        }
    }

    private fun cancelPendingConfirmation() {
        pendingConfirmationJob?.cancel()
        pendingConfirmationJob = null
        pendingConfirmation = null
        cancelAlertNotification()
        EmergencySirenPlayer.stop()
        MonitoringStatusStore.update {
            it.copy(
                engineState = EngineState.MONITORING,
                countdownSecondsRemaining = null,
                message = "Potential event cancelled. Monitoring continues locally.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun postPendingAlert(decision: com.acousticguard.ml.FusionDecision, secondsRemaining: Int): Boolean {
        if (!notificationsAvailable()) return false
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
        return runCatching {
            NotificationManagerCompat.from(this).notify(PENDING_ALERT_NOTIFICATION_ID, notification)
        }.isSuccess
    }

    private fun cancelAlertNotification() {
        runCatching { NotificationManagerCompat.from(this).cancel(PENDING_ALERT_NOTIFICATION_ID) }
    }

    private fun triggerAlertHaptic() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(android.os.VibratorManager::class.java)
                vibratorManager?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1),
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 400, 200, 400), -1)
                }
            }
        }
    }

    private fun notificationsAvailable(): Boolean =
        NotificationChannels.areRequiredChannelsEnabled(this) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun stopAfterFailure() {
        preserveTerminalStatus = true
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private data class PendingConfirmation(
        val token: Long,
        val decision: com.acousticguard.ml.FusionDecision,
        val modelVersion: String,
    )

    private companion object {
        const val ACTION_CANCEL_PENDING_ALERT = "com.acousticguard.action.CANCEL_PENDING_ALERT"
        const val ACTION_STOP_SIREN = "com.acousticguard.action.STOP_SIREN"
        const val CANCEL_PENDING_ALERT_REQUEST_CODE = 3102
        const val STOP_SIREN_REQUEST_CODE = 3105
        const val PENDING_ALERT_NOTIFICATION_ID = 3103
        const val CONFIRMED_ALERT_NOTIFICATION_ID = 3104
        const val COUNTDOWN_SECONDS = 10
        const val MONITORING_NOTIFICATION_ID = 3100
        const val PCM_SCALE = 32768f
    }
}
