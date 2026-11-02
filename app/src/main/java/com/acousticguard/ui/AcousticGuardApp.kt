package com.acousticguard.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import com.acousticguard.safety.SosConfig
import com.acousticguard.safety.EmergencySirenPlayer
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.acousticguard.dispatch.DispatchSettings
import com.acousticguard.monitoring.EngineState
import com.acousticguard.monitoring.MonitoringStatus
import com.acousticguard.monitoring.NotificationChannels
import com.acousticguard.safety.NativeSafetyHandoffPhase
import com.acousticguard.safety.NativeSafetyHandoffState
import com.acousticguard.ui.theme.SignalAmber
import com.acousticguard.ui.theme.SignalBlue
import com.acousticguard.ui.theme.SignalGreen
import com.acousticguard.ui.theme.SignalRed

@Composable
fun AcousticGuardApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val status by viewModel.monitoringStatus.collectAsState()
    val dispatchUi by viewModel.dispatchUi.collectAsState()
    val sosConfig by viewModel.sosConfig.collectAsState()
    val nativeSafetyUi by viewModel.nativeSafetyUi.collectAsState()
    val nativeSafetyStatus by viewModel.nativeSafetyStatus.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshNativeSafety()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val microphoneGranted = result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(context, Manifest.permission.RECORD_AUDIO)
        val notificationsGranted = notificationsAvailable(context)
        if (microphoneGranted && notificationsGranted) {
            permissionMessage = null
            viewModel.startMonitoring()
        } else {
            permissionMessage = if (!microphoneGranted) {
                "Microphone access is required for local acoustic analysis. Nothing is recorded."
            } else {
                "Notifications are required so the monitoring service stays visible and transparent."
            }
        }
    }

    fun toggleMonitoring(enabled: Boolean) {
        if (!enabled) {
            permissionMessage = null
            viewModel.stopMonitoring()
            return
        }
        if (hasRequiredPermissions(context)) {
            viewModel.startMonitoring()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Protection") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                0 -> MonitorScreen(
                    status = status,
                    sosConfig = sosConfig,
                    permissionMessage = permissionMessage,
                    onToggleMonitoring = ::toggleMonitoring,
                    onOpenSettings = { selectedTab = 1 },
                )
                else -> SettingsScreen(
                    status = status,
                    dispatchUi = dispatchUi,
                    sosConfig = sosConfig,
                    nativeSafetyUi = nativeSafetyUi,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                    onSaveDispatchSettings = viewModel::saveDispatchSettings,
                    onSaveSosConfig = viewModel::saveSosConfig,
                    onTestSos = viewModel::triggerTestSos,
                    onSilenceSiren = viewModel::stopSiren,
                    onNativeSafetyEnabled = viewModel::setNativeSafetyEnabled,
                    onOpenAccessibilitySettings = {
                        accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onClearDispatchQueue = viewModel::clearDispatchQueue,
                    onQueueTestEvent = viewModel::queueTestEvent,
                )
            }
        }
    }
}

@Composable
private fun MonitorScreen(
    status: MonitoringStatus,
    sosConfig: SosConfig,
    permissionMessage: String?,
    onToggleMonitoring: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppHeader(
                eyebrow = "PROTECTION",
                title = "Quiet protection.",
                subtitle = "Stay covered without giving up your privacy. AcousticGuard analyzes sound on this device and never saves recordings.",
            )
        }
        item {
            MonitoringHero(
                status = status,
                onToggleMonitoring = onToggleMonitoring,
            )
        }
        permissionMessage?.let { message ->
            item { InlineNotice(message = message, isError = true) }
        }
        item {
            EmergencyPlanCard(config = sosConfig, onOpenSettings = onOpenSettings)
        }
        item {
            ProtectionSignalsCard()
        }
        item {
            PrivacyCard()
        }
    }
}

@Composable
private fun MonitoringHero(
    status: MonitoringStatus,
    onToggleMonitoring: (Boolean) -> Unit,
) {
    val active = status.isMonitoring || status.engineState == EngineState.STARTING
    val title = when (status.engineState) {
        EngineState.MONITORING -> "Protection is on"
        EngineState.STARTING -> "Preparing protection"
        EngineState.BLOCKED -> "Finish setup"
        EngineState.ERROR -> "Protection is paused"
        EngineState.OFF -> "Protection is off"
    }
    val description = when (status.engineState) {
        EngineState.MONITORING -> "Listening locally for signs of distress."
        EngineState.STARTING -> "Getting your local protection ready…"
        EngineState.BLOCKED -> status.message
        EngineState.ERROR -> status.message
        EngineState.OFF -> "Turn it on when you’re ready."
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatusOrb(active = active, state = status.engineState)
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onToggleMonitoring(!active) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (active) "Turn off protection" else "Turn on protection")
            }
        }
    }
}

@Composable
private fun StatusOrb(active: Boolean, state: EngineState) {
    val color = when {
        active -> SignalGreen
        state == EngineState.BLOCKED || state == EngineState.ERROR -> SignalRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(88.dp)
            .semantics { contentDescription = "Monitoring status indicator" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = color.copy(alpha = 0.16f),
                radius = size.minDimension / 2f - 2.dp.toPx(),
            )
            drawCircle(
                color = color,
                radius = size.minDimension / 2f - 7.dp.toPx(),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawCircle(color = color, radius = 6.dp.toPx())
        }
    }
}

@Composable
private fun DetectorCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon = icon, tint = color)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Detector ready",
                tint = SignalGreen,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PrivacyCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = SignalBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Your audio stays in volatile memory", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Audio is analyzed in short-lived memory for local detection. Raw recordings are not written, uploaded, or logged.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmergencyPlanCard(
    config: SosConfig,
    onOpenSettings: () -> Unit,
) {
    val callReady = config.autoCallEnabled && config.primaryCallNumber.isNotBlank()
    val smsCount = config.emergencySmsNumbers
        .split(',', ';', '\n')
        .count { it.isNotBlank() }
    val smsReady = config.autoSmsEnabled && smsCount > 0
    val planReady = callReady || smsReady || config.sirenEnabled

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Emergency plan", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        if (planReady) "Ready to respond if a confirmed event occurs." else "Add a response option so protection is ready when you need it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (planReady) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                    contentDescription = if (planReady) "Emergency plan ready" else "Emergency plan needs attention",
                    tint = if (planReady) SignalGreen else SignalAmber,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            PlanStatusRow(Icons.Filled.Phone, "Call", if (callReady) "Primary contact ready" else "Not configured", callReady)
            PlanStatusRow(Icons.Filled.Sms, "SMS", if (smsReady) "$smsCount contact${if (smsCount == 1) "" else "s"} ready" else "Not configured", smsReady)
            PlanStatusRow(Icons.AutoMirrored.Filled.VolumeUp, "Siren", if (config.sirenEnabled) "Deterrent alarm ready" else "Turned off", config.sirenEnabled)
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Review emergency plan")
            }
        }
    }
}

@Composable
private fun PlanStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    ready: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon = icon, tint = if (ready) SignalGreen else SignalAmber)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = if (ready) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = if (ready) "Ready" else "Needs attention",
            tint = if (ready) SignalGreen else SignalAmber,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ProtectionSignalsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("What protection watches for", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(10.dp))
            SignalRow(Icons.Filled.GraphicEq, "Vocal distress", SignalBlue)
            SignalRow(Icons.Filled.Sensors, "Breathing changes", SignalAmber)
            SignalRow(Icons.Filled.Vibration, "Impact with motion", SignalRed)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Signals are corroborated locally before the cancellation window begins.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SignalRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon = icon, tint = tint)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SafetyScreen(
    status: MonitoringStatus,
    dispatchUi: DispatchUiState,
    sosConfig: SosConfig,
    nativeSafetyUi: NativeSafetyUiState,
    nativeSafetyStatus: NativeSafetyHandoffState,
    onOpenSettings: () -> Unit,
    onSilenceSiren: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppHeader(
                eyebrow = "SAFETY CENTER",
                title = "Hands-free protection.",
                subtitle = "Corroborated distress triggers direct emergency calling, SMS, and deterrent alarm.",
            )
        }
        item {
            AutonomousSosCard(
                config = sosConfig,
                onOpenSettings = onOpenSettings,
                onSilenceSiren = onSilenceSiren,
            )
        }
        item {
            SafetyPolicyCard()
        }
        item {
            ModelStatusCard(status = status)
        }
        item {
            QueueCard(pendingIncidentCount = dispatchUi.snapshot.pendingCount)
        }
        item {
            NativeSafetyStatusCard(
                settings = nativeSafetyUi,
                handoff = nativeSafetyStatus,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = SignalBlue)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Offline-safe by default", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (dispatchUi.settings.enabled) {
                            "Confirmed metadata is encrypted before delivery to your configured HTTPS receiver. Receiver acceptance is not emergency-service response."
                        } else {
                            "Confirmed event metadata is encrypted with Android Keystore and kept local-only until you explicitly enable a receiver."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("Review permissions")
                    }
                }
            }
        }
        item {
            InlineNotice(
                message = "Prototype boundary: this app is not a certified emergency device. Validate false-positive behavior with responsible adults before relying on it.",
                isError = false,
            )
        }
    }
}

@Composable
private fun AutonomousSosCard(
    config: SosConfig,
    onOpenSettings: () -> Unit,
    onSilenceSiren: () -> Unit,
) {
    val isSirenActive = EmergencySirenPlayer.isPlaying
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (isSirenActive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isSirenActive) Icons.Filled.WarningAmber else Icons.Filled.Shield,
                    contentDescription = null,
                    tint = if (isSirenActive) SignalRed else SignalGreen,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hands-Free Autonomous SOS", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        if (isSirenActive) "EMERGENCY SIREN ACTIVE" else "Armed on confirmed distress",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSirenActive) SignalRed else SignalGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (isSirenActive) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSilenceSiren,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = SignalRed),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SILENCE SIREN")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))
            SettingRow(
                label = "Auto Emergency Call",
                value = if (config.autoCallEnabled && config.primaryCallNumber.isNotBlank()) config.primaryCallNumber else "Disabled",
            )
            SettingRow(
                label = "Emergency SMS Alerts",
                value = if (config.autoSmsEnabled && config.emergencySmsNumbers.isNotBlank()) {
                    "${config.emergencySmsNumbers.split(',', ';', '\n').filter { it.isNotBlank() }.size} contact(s)"
                } else {
                    "Disabled"
                },
            )
            SettingRow(
                label = "Deterrent Alarm Siren",
                value = if (config.sirenEnabled) "Enabled" else "Disabled",
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Configure SOS Contacts & Calling")
            }
        }
    }
}

@Composable
private fun NativeSafetyStatusCard(
    settings: NativeSafetyUiState,
    handoff: NativeSafetyHandoffState,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val needsAttention = handoff.phase == NativeSafetyHandoffPhase.ACCESSIBILITY_REQUIRED ||
        handoff.phase == NativeSafetyHandoffPhase.FAILED
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (needsAttention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (needsAttention) Icons.Filled.WarningAmber else Icons.Filled.Security,
                    contentDescription = null,
                    tint = if (needsAttention) SignalRed else SignalBlue,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Native safety handoff", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        when {
                            !settings.enabled -> "Off"
                            !settings.accessibilityEnabled -> "Accessibility access needed"
                            else -> "Armed for confirmed alerts"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                handoff.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "After the 10-second cancel window, AcousticGuard can wake the screen and open the Android emergency interface. It never places an SOS call automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!settings.accessibilityEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenAccessibilitySettings) {
                    Text("Open accessibility settings")
                }
            }
        }
    }
}

@Composable
private fun SafetyPolicyCard() {
    val policies = listOf(
        "Audio is processed in RAM and cleared after each window.",
        "The local neural network emits probabilities, not recordings.",
        "Inertial motion corroborates acoustic anomalies before confirmation.",
        "Confirmed metadata is encrypted locally; optional receiver delivery is separate from emergency services.",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Protection model", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(14.dp))
            policies.forEach { policy ->
                Row(
                    modifier = Modifier.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = SignalGreen,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(top = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(policy, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ModelStatusCard(status: MonitoringStatus) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Local model health", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(10.dp))
            SettingRow(label = "Active version", value = status.modelInfo.modelVersion)
            SettingRow(label = "Runtime", value = status.modelInfo.runtime)
            SettingRow(label = "Quantization", value = status.modelInfo.quantization)
            SettingRow(label = "Mode", value = status.monitoringMode.label)
            SettingRow(label = "Source", value = status.modelInfo.source.label)
            SettingRow(label = "Motion", value = if (status.motionReady) "Available" else "Unavailable")
            SettingRow(
                label = "Model calls",
                value = "${status.detectionMetrics.modelInvocations} / ${status.detectionMetrics.audioWindowsRead} windows",
            )
            SettingRow(
                label = "Latency",
                value = "p50 ${status.detectionMetrics.inferenceLatencyP50Ms} ms · p95 ${status.detectionMetrics.inferenceLatencyP95Ms} ms",
            )
            status.lastModelError?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QueueCard(pendingIncidentCount: Int) {
    val hasQueue = pendingIncidentCount > 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (hasQueue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (hasQueue) Icons.Filled.WarningAmber else Icons.Filled.Security,
                contentDescription = null,
                tint = if (hasQueue) SignalRed else SignalBlue,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (hasQueue) "$pendingIncidentCount encrypted event(s) queued" else "No confirmed events queued",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Queue contains metadata only; raw audio is never part of an incident record.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    status: MonitoringStatus,
    dispatchUi: DispatchUiState,
    sosConfig: SosConfig,
    nativeSafetyUi: NativeSafetyUiState,
    onRequestPermissions: () -> Unit,
    onSaveDispatchSettings: (DispatchSettings) -> Unit,
    onSaveSosConfig: (SosConfig) -> Unit,
    onTestSos: () -> Unit,
    onSilenceSiren: () -> Unit,
    onNativeSafetyEnabled: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onClearDispatchQueue: () -> Unit,
    onQueueTestEvent: () -> Unit,
) {
    val context = LocalContext.current
    val microphoneGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
    val notificationsGranted = notificationsAvailable(context)
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppHeader(
                eyebrow = "SETTINGS",
                title = "Your safety plan.",
                subtitle = "Choose what should happen if AcousticGuard confirms distress.",
            )
        }
        item {
            AutonomousSosSettingsCard(
                config = sosConfig,
                onSaveConfig = onSaveSosConfig,
                onTestSos = onTestSos,
                onSilenceSiren = onSilenceSiren,
                onRequestPermissions = onRequestPermissions,
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(10.dp))
                    PermissionRow(
                        icon = Icons.Filled.Mic,
                        title = "Microphone",
                        detail = if (microphoneGranted) "Ready for on-device protection" else "Needed for protection",
                        granted = microphoneGranted,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    PermissionRow(
                        icon = Icons.Filled.Info,
                        title = "Notifications",
                        detail = if (notificationsGranted) "Protection status stays visible" else "Needed to keep protection visible",
                        granted = notificationsGranted,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Review access")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open system settings")
                    }
                }
            }
        }
        item {
            TextButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (advancedExpanded) "Hide advanced settings" else "Show advanced settings")
            }
        }
        if (advancedExpanded) {
            item {
                NativeSafetySettingsCard(
                    settings = nativeSafetyUi,
                    onEnabledChange = onNativeSafetyEnabled,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                )
            }
            item {
                DispatchSettingsCard(
                    state = dispatchUi,
                    onSave = onSaveDispatchSettings,
                    onClearQueue = onClearDispatchQueue,
                    onQueueTestEvent = onQueueTestEvent,
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        SettingRow(label = "Model", value = status.modelInfo.modelVersion)
                        SettingRow(label = "Inference", value = "On-device")
                        SettingRow(label = "Monitoring state", value = status.monitoringMode.label)
                        SettingRow(label = "Audio retention", value = "None")
                        SettingRow(label = "Motion corroboration", value = if (status.motionReady) "Available" else "Standby")
                        status.lastModelError?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Private by default. No account is required, and recordings never leave this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun AutonomousSosSettingsCard(
    config: SosConfig,
    onSaveConfig: (SosConfig) -> Unit,
    onTestSos: () -> Unit,
    onSilenceSiren: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    var primaryCallNumber by remember(config.primaryCallNumber) { mutableStateOf(config.primaryCallNumber) }
    var autoCallEnabled by remember(config.autoCallEnabled) { mutableStateOf(config.autoCallEnabled) }
    var emergencySmsNumbers by remember(config.emergencySmsNumbers) { mutableStateOf(config.emergencySmsNumbers) }
    var autoSmsEnabled by remember(config.autoSmsEnabled) { mutableStateOf(config.autoSmsEnabled) }
    var sirenEnabled by remember(config.sirenEnabled) { mutableStateOf(config.sirenEnabled) }
    var customSmsMessage by remember(config.customSmsMessage) { mutableStateOf(config.customSmsMessage) }
    var savedNotice by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val callPermissionGranted = hasPermission(context, Manifest.permission.CALL_PHONE)
    val smsPermissionGranted = hasPermission(context, Manifest.permission.SEND_SMS)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = SignalBlue)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Emergency response", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose who to contact and what response to trigger after a confirmed event.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = primaryCallNumber,
                onValueChange = {
                    primaryCallNumber = it
                    savedNotice = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Primary contact") },
                placeholder = { Text("Phone number") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Call this contact automatically", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = autoCallEnabled,
                    onCheckedChange = {
                        autoCallEnabled = it
                        savedNotice = false
                    },
                )
            }
            PermissionRow(
                icon = Icons.Filled.Phone,
                title = "Calling access",
                detail = if (callPermissionGranted) "Ready" else "Needed for automatic calling",
                granted = callPermissionGranted,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = emergencySmsNumbers,
                onValueChange = {
                    emergencySmsNumbers = it
                    savedNotice = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SMS contacts") },
                placeholder = { Text("Separate phone numbers with commas") },
                leadingIcon = { Icon(Icons.Filled.Sms, contentDescription = null) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Send an SMS automatically", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = autoSmsEnabled,
                    onCheckedChange = {
                        autoSmsEnabled = it
                        savedNotice = false
                    },
                )
            }
            PermissionRow(
                icon = Icons.Filled.Sms,
                title = "Messaging access",
                detail = if (smsPermissionGranted) "Ready" else "Needed for automatic SMS",
                granted = smsPermissionGranted,
            )

            if (!callPermissionGranted || !smsPermissionGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow calling and messaging")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Play a deterrent alarm", style = MaterialTheme.typography.bodyLarge)
                    Text("Sound an alarm through the phone speaker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = sirenEnabled,
                    onCheckedChange = {
                        sirenEnabled = it
                        savedNotice = false
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    onSaveConfig(
                        SosConfig(
                            primaryCallNumber = primaryCallNumber,
                            autoCallEnabled = autoCallEnabled,
                            emergencySmsNumbers = emergencySmsNumbers,
                            autoSmsEnabled = autoSmsEnabled,
                            sirenEnabled = sirenEnabled,
                            customSmsMessage = customSmsMessage,
                        )
                    )
                    savedNotice = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (savedNotice) "Plan saved" else "Save emergency plan")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onTestSos,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Test response")
                }
                if (EmergencySirenPlayer.isPlaying) {
                    Button(
                        onClick = onSilenceSiren,
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = SignalRed),
                    ) {
                        Text("Stop Siren")
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeSafetySettingsCard(
    settings: NativeSafetyUiState,
    onEnabledChange: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Native safety handoff", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Optional special access for confirmed alerts only. AcousticGuard can wake the screen and open the Android emergency interface; it does not place calls automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics { contentDescription = "Enable native safety handoff" },
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            PermissionRow(
                icon = Icons.Filled.Security,
                title = "Accessibility service",
                detail = if (settings.accessibilityEnabled) {
                    "Enabled for the confirmed-alert handoff"
                } else {
                    "Required before native handoff can run"
                },
                granted = settings.accessibilityEnabled,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open accessibility settings")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "The service stays idle during normal monitoring and reads the visible system window only after a confirmed detection requests the handoff. Google Personal Safety remains controlled by Android and may require your final tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DispatchSettingsCard(
    state: DispatchUiState,
    onSave: (DispatchSettings) -> Unit,
    onClearQueue: () -> Unit,
    onQueueTestEvent: () -> Unit,
) {
    var enabled by remember(state.settings.enabled) { mutableStateOf(state.settings.enabled) }
    var endpoint by remember(state.settings.endpoint) { mutableStateOf(state.settings.endpoint) }
    var publicKeyPem by remember(state.settings.publicKeyPem) { mutableStateOf(state.settings.publicKeyPem) }
    var bearerToken by remember(state.settings.bearerToken) { mutableStateOf(state.settings.bearerToken) }
    var showTestDialog by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Remote delivery", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Optional and off by default. The app sends encrypted metadata only to a receiver you configure.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    modifier = Modifier.semantics { contentDescription = "Enable optional remote delivery" },
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("HTTPS receiver endpoint") },
                placeholder = { Text("https://receiver.example/events") },
                singleLine = true,
                enabled = !state.saving,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = publicKeyPem,
                onValueChange = { publicKeyPem = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Receiver RSA public key") },
                placeholder = { Text("-----BEGIN PUBLIC KEY-----") },
                minLines = 4,
                maxLines = 8,
                enabled = !state.saving,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = bearerToken,
                onValueChange = { bearerToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bearer token (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.saving,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onSave(
                        DispatchSettings(
                            enabled = enabled,
                            endpoint = endpoint.trim(),
                            publicKeyPem = publicKeyPem.trim(),
                            bearerToken = bearerToken.trim(),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving,
            ) {
                Text(if (state.saving) "Saving…" else "Save delivery settings")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showTestDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving,
            ) {
                Text(if (state.settings.enabled) "Send a test event" else "Queue a local test event")
            }
            state.message?.let { message ->
                Spacer(modifier = Modifier.height(10.dp))
                InlineNotice(message = message, isError = state.snapshot.error != null)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                if (state.snapshot.records.isEmpty()) {
                    "No local incident records are waiting."
                } else {
                    "${state.snapshot.records.size} local incident record(s) retained for up to 24 hours."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.snapshot.records.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                state.snapshot.records.take(3).forEach { incident ->
                    SettingRow(label = incident.marker, value = incident.status)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onClearQueue, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear local incident queue")
                }
            }
        }
    }

    if (showTestDialog) {
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text("Confirm test event") },
            text = {
                Text(
                    if (state.settings.enabled) {
                        "This sends encrypted test metadata to your configured receiver. It does not contact emergency services. Continue?"
                    } else {
                        "Remote delivery is disabled, so this test will remain local-only. Continue?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTestDialog = false
                        onQueueTestEvent()
                    },
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun AppHeader(eyebrow: String, title: String, subtitle: String) {
    Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelLarge,
            color = SignalBlue,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.displaySmall)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String, caption: String) {
    Column(modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(3.dp))
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MetricCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    granted: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon = icon, tint = if (granted) SignalGreen else SignalAmber)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = if (granted) "Allowed" else "Needs attention",
            tint = if (granted) SignalGreen else SignalAmber,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InlineNotice(message: String, isError: Boolean) {
    val tint = if (isError) SignalRed else SignalBlue
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = tint.copy(alpha = 0.10f),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (isError) Icons.Filled.WarningAmber else Icons.Filled.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.SEND_SMS)
}.toTypedArray()

private fun hasRequiredPermissions(context: Context): Boolean {
    val microphoneGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
    val notificationsGranted = notificationsAvailable(context)
    return microphoneGranted && notificationsGranted
}

private fun notificationsAvailable(context: Context): Boolean =
    NotificationChannels.areRequiredChannelsEnabled(context) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS))

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
