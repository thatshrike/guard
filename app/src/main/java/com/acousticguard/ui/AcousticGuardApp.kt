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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.acousticguard.dispatch.DispatchSettings
import com.acousticguard.monitoring.EngineState
import com.acousticguard.monitoring.MonitoringStatus
import com.acousticguard.ui.theme.SignalAmber
import com.acousticguard.ui.theme.SignalBlue
import com.acousticguard.ui.theme.SignalGreen
import com.acousticguard.ui.theme.SignalRed

@Composable
fun AcousticGuardApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val status by viewModel.monitoringStatus.collectAsState()
    val dispatchUi by viewModel.dispatchUi.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }

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
                    label = { Text("Monitor") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                    label = { Text("Safety") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
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
                    permissionMessage = permissionMessage,
                    onToggleMonitoring = ::toggleMonitoring,
                    onOpenSafety = { selectedTab = 1 },
                )
                1 -> SafetyScreen(
                    status = status,
                    dispatchUi = dispatchUi,
                    onOpenSettings = { selectedTab = 2 },
                )
                else -> SettingsScreen(
                    status = status,
                    dispatchUi = dispatchUi,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                    onSaveDispatchSettings = viewModel::saveDispatchSettings,
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
    permissionMessage: String?,
    onToggleMonitoring: (Boolean) -> Unit,
    onOpenSafety: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppHeader(
                eyebrow = "PRIVATE BY DESIGN",
                title = "AcousticGuard",
                subtitle = "Quiet protection that stays on your device.",
            )
        }
        item {
            MonitoringHero(status = status, onToggleMonitoring = onToggleMonitoring)
        }
        permissionMessage?.let { message ->
            item { InlineNotice(message = message, isError = true) }
        }
        item {
            SectionHeader(
                title = "Local detector",
                caption = "Sustained screams or loud speech in any language start a 10-second cancel window.",
            )
        }
        item {
            DetectorCard(
                icon = Icons.Filled.GraphicEq,
                title = "Vocal distress",
                description = "Screams and loud shouted speech, without transcribing or storing words.",
                color = SignalBlue,
            )
        }
        item {
            DetectorCard(
                icon = Icons.Filled.Sensors,
                title = "Respiratory distress",
                description = "Stridor, hyperventilation, and agonal-gasping patterns.",
                color = SignalAmber,
            )
        }
        item {
            DetectorCard(
                icon = Icons.Filled.Vibration,
                title = "Impact transient",
                description = "Short acoustic impulses corroborated by inertial motion.",
                color = SignalRed,
            )
        }
        item {
            PrivacyCard(onOpenSafety = onOpenSafety)
        }
    }
}

@Composable
private fun MonitoringHero(status: MonitoringStatus, onToggleMonitoring: (Boolean) -> Unit) {
    val active = status.isMonitoring || status.engineState == EngineState.STARTING
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
                Switch(
                    checked = active,
                    onCheckedChange = onToggleMonitoring,
                    modifier = Modifier.semantics { contentDescription = "Enable AcousticGuard monitoring" },
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = when (status.engineState) {
                    EngineState.MONITORING -> "Monitoring is active"
                    EngineState.STARTING -> "Starting local monitoring"
                    EngineState.BLOCKED -> "Monitoring needs access"
                    EngineState.ERROR -> "Monitoring is paused"
                    EngineState.OFF -> "Monitoring is off"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCell(
                    value = if (status.motionReady) "Ready" else "Standby",
                    label = "Motion",
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    value = "Local",
                    label = "Inference",
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    value = if (status.pendingIncidentCount == 0) "Clear" else status.pendingIncidentCount.toString(),
                    label = "Queue",
                    modifier = Modifier.weight(1f),
                )
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
private fun PrivacyCard(onOpenSafety: () -> Unit) {
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
                "AcousticGuard keeps only short-lived features for inference. Raw audio is not written, uploaded, or logged.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = onOpenSafety, contentPadding = PaddingValues(0.dp)) {
                Text("Review the safety model")
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SafetyScreen(
    status: MonitoringStatus,
    dispatchUi: DispatchUiState,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppHeader(
                eyebrow = "SAFETY CENTER",
                title = "Designed to fail closed.",
                subtitle = "A single unusual sound is never enough to trigger a confirmed event.",
            )
        }
        item {
            SafetyPolicyCard()
        }
        item {
            ModelStatusCard(status = status)
        }
        item {
            QueueCard(status = status)
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
private fun QueueCard(status: MonitoringStatus) {
    val hasQueue = status.pendingIncidentCount > 0
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
                    if (hasQueue) "${status.pendingIncidentCount} encrypted event(s) queued" else "No confirmed events queued",
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
    onRequestPermissions: () -> Unit,
    onSaveDispatchSettings: (DispatchSettings) -> Unit,
    onClearDispatchQueue: () -> Unit,
    onQueueTestEvent: () -> Unit,
) {
    val context = LocalContext.current
    val microphoneGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
    val notificationsGranted = notificationsAvailable(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppHeader(
                eyebrow = "SETTINGS",
                title = "Quiet controls.",
                subtitle = "Everything important stays explicit and close at hand.",
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
                        detail = if (microphoneGranted) "Allowed for local inference" else "Required to monitor",
                        granted = microphoneGranted,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    PermissionRow(
                        icon = Icons.Filled.Info,
                        title = "Notifications",
                        detail = if (notificationsGranted) "Monitoring status stays visible" else "Required for the foreground service",
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
                    Text("Engine", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingRow(label = "Model", value = status.modelInfo.modelVersion)
                    SettingRow(
                        label = "Runtime",
                        value = "${status.modelInfo.runtime} / ${status.modelInfo.quantization}",
                    )
                    SettingRow(label = "Inference", value = "On-device")
                    SettingRow(label = "Monitoring state", value = status.monitoringMode.label)
                    SettingRow(label = "Model source", value = status.modelInfo.source.label)
                    SettingRow(
                        label = "Fallback active",
                        value = if (status.fallbackModelActive) "Yes" else "No",
                    )
                    SettingRow(label = "Audio retention", value = "None")
                    SettingRow(
                        label = "Network transport",
                        value = if (dispatchUi.settings.enabled) "Enabled for new events" else "Disabled · local-only",
                    )
                    SettingRow(label = "Motion corroboration", value = if (status.motionReady) "Available" else "Standby")
                    status.lastModelError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Model note: $error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "AcousticGuard does not create an account, sync recordings, or use a cloud model. Optional receiver delivery sends encrypted metadata only; it is not a direct emergency-service connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
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
}.toTypedArray()

private fun hasRequiredPermissions(context: Context): Boolean {
    val microphoneGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
    val notificationsGranted = notificationsAvailable(context)
    return microphoneGranted && notificationsGranted
}

private fun notificationsAvailable(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS))

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
