package com.acousticguard.safety

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

/**
 * Opt-in, event-driven bridge to the Android emergency interface.
 *
 * The service does not inspect other windows during normal monitoring. It only reads the active
 * system window after a confirmed detection requests a handoff. It may click a conservative
 * "open emergency interface" entry, but it never clicks an SOS/call action or places a call.
 */
@Suppress("DEPRECATION")
class NativeSafetyAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scanToken = Any()
    private val matcher = EmergencyUiTargetMatcher()
    private var receiverRegistered = false
    private var activeRequest: HandoffRequest? = null
    private var scanAttempts = 0

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NativeSafetyHandoffController.ACTION_REQUEST_NATIVE_HANDOFF) return
            NativeSafetyPendingRequestStore.clear(context)
            beginHandoff(
                incidentId = intent.getStringExtra(NativeSafetyHandoffController.EXTRA_INCIDENT_ID),
                marker = intent.getStringExtra(NativeSafetyHandoffController.EXTRA_MARKER).orEmpty(),
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ContextCompat.registerReceiver(
            this,
            requestReceiver,
            IntentFilter(NativeSafetyHandoffController.ACTION_REQUEST_NATIVE_HANDOFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        NativeSafetyHandoffController.refreshAvailability(this)
        NativeSafetyPendingRequestStore.consumeIfFresh(this)?.let { pending ->
            beginHandoff(incidentId = pending.incidentId, marker = pending.marker)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (activeRequest == null || event == null) return
        if (event.eventType in RELEVANT_EVENT_TYPES) {
            scheduleScan(SCAN_AFTER_EVENT_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun beginHandoff(incidentId: String?, marker: String) {
        if (!NativeSafetyHandoffPreferences.isEnabled(this)) {
            NativeSafetyHandoffController.refreshAvailability(this)
            return
        }
        if (activeRequest != null) return

        activeRequest = HandoffRequest(incidentId = incidentId, marker = marker)
        scanAttempts = 0
        NativeSafetyHandoffStatusStore.update(
            NativeSafetyHandoffState(
                phase = NativeSafetyHandoffPhase.HANDOFF_REQUESTED,
                incidentId = incidentId,
                message = "Waking the screen and opening the Android power menu.",
            ),
        )

        // Waking is best effort. The system may already be interactive or may apply OEM policy.
        ScreenWakeController.request(this)
        val opened = runCatching {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }.getOrDefault(false)
        if (!opened) {
            finishWithUserAction(
                phase = NativeSafetyHandoffPhase.FAILED,
                message = "Android did not expose the power menu. Use the device's emergency shortcut manually.",
            )
            return
        }
        scheduleScan(INITIAL_SCAN_DELAY_MS)
    }

    private fun scheduleScan(delayMs: Long) {
        handler.removeCallbacksAndMessages(scanToken)
        handler.postDelayed({ scanCurrentSystemWindow() }, scanToken, delayMs)
    }

    private fun scanCurrentSystemWindow() {
        if (activeRequest == null) return
        val root = rootInActiveWindow
        if (root == null || !isSystemWindow(root)) {
            retryOrRequireUser()
            return
        }

        val match = findTarget(root)
        if (match == null) {
            retryOrRequireUser()
            return
        }

        when (match.target.kind) {
            EmergencyUiTargetKind.OPEN_EMERGENCY_INTERFACE -> {
                val clicked = runCatching {
                    match.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                recycle(match)
                if (clicked) {
                    finishWithUserAction(
                        phase = NativeSafetyHandoffPhase.EMERGENCY_INTERFACE_OPENED,
                        message = "The Android emergency interface was opened. Finish the emergency action yourself; AcousticGuard will not place a call automatically.",
                    )
                } else {
                    finishWithUserAction(
                        phase = NativeSafetyHandoffPhase.USER_ACTION_REQUIRED,
                        message = "The emergency entry was found but Android rejected the click. Tap the visible emergency action yourself.",
                    )
                }
            }

            EmergencyUiTargetKind.SOS_ACTION_REQUIRES_USER -> {
                recycle(match)
                finishWithUserAction(
                    phase = NativeSafetyHandoffPhase.USER_ACTION_REQUIRED,
                    message = "An SOS action is visible. Tap it yourself to use your preconfigured Android/Google safety settings.",
                )
            }
        }
    }

    private fun retryOrRequireUser() {
        scanAttempts += 1
        if (scanAttempts < MAX_SCAN_ATTEMPTS) {
            scheduleScan(SCAN_RETRY_DELAY_MS)
            return
        }
        finishWithUserAction(
            phase = NativeSafetyHandoffPhase.USER_ACTION_REQUIRED,
            message = "The system emergency controls were not exposed to accessibility. Use the visible lock-screen emergency shortcut yourself.",
        )
    }

    private fun findTarget(node: AccessibilityNodeInfo, isRoot: Boolean = true): NodeMatch? {
        val target = matcher.classify(node.text, node.contentDescription)
        if (target != null && node.isVisibleToUser && node.isEnabled) {
            return NodeMatch(node = node, target = target, isRoot = isRoot)
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findTarget(child, isRoot = false)
            if (match != null) {
                if (match.node !== child) child.recycle()
                return match
            }
            child.recycle()
        }
        return null
    }

    private fun isSystemWindow(root: AccessibilityNodeInfo): Boolean = runCatching {
        root.window?.type == AccessibilityWindowInfo.TYPE_SYSTEM
    }.getOrDefault(false)

    private fun recycle(match: NodeMatch) {
        if (!match.isRoot) runCatching { match.node.recycle() }
    }

    private fun finishWithUserAction(
        phase: NativeSafetyHandoffPhase,
        message: String,
    ) {
        val request = activeRequest
        activeRequest = null
        handler.removeCallbacksAndMessages(scanToken)
        NativeSafetyHandoffStatusStore.update(
            NativeSafetyHandoffState(
                phase = phase,
                incidentId = request?.incidentId,
                message = message,
            ),
        )
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(scanToken)
        activeRequest = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(requestReceiver) }
            receiverRegistered = false
        }
        NativeSafetyHandoffController.refreshAvailability(this)
    }

    private data class HandoffRequest(
        val incidentId: String?,
        val marker: String,
    )

    private data class NodeMatch(
        val node: AccessibilityNodeInfo,
        val target: EmergencyUiTarget,
        val isRoot: Boolean,
    )

    private companion object {
        const val INITIAL_SCAN_DELAY_MS = 250L
        const val SCAN_AFTER_EVENT_MS = 80L
        const val SCAN_RETRY_DELAY_MS = 250L
        const val MAX_SCAN_ATTEMPTS = 12
        val RELEVANT_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
    }
}
