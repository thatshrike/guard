# Emergency SOS and Native Safety Handoff

AcousticGuard provides a multi-tier emergency response system upon confirmed distress detection (following a 10-second user cancellation window):

1. **Autonomous Direct Hands-Free SOS**: Initiates immediate cellular phone calls, broadcasts real-time multi-recipient emergency SMS messages with distress context, and triggers a synthesized high-decibel alarm siren without requiring manual screen interactions.
2. **Native System Safety Handoff (Accessibility Fallback)**: An optional bridge that triggers Android's system power dialog and navigates to the OEM/Google Personal Safety interface.

---

## 1. Autonomous Hands-Free Emergency SOS Engine

When a user is incapacitated, restrained, or in acute distress, hands-free operation is essential. The Direct SOS Engine executes configured actions autonomously once the post-detection countdown completes.

### Capabilities:
- **Direct Cellular Emergency Calling (`CALL_PHONE` via `Intent.ACTION_CALL`)**:
  - Automatically dials a configured trusted primary contact, ICE number, campus security, or emergency dispatcher without requiring manual confirmation on the dialer keypad.
  - Sanitizes international and local numbers, launching directly from the background service with `FLAG_ACTIVITY_NEW_TASK`.
- **Emergency SMS Broadcast (`SEND_SMS` via `SmsManager`)**:
  - Sends immediate SMS notifications across cellular networks to one or multiple emergency contacts (delimited by commas, semicolons, or newlines).
  - Operates completely offline with zero internet dependency.
  - Generates localized timestamps and distress classifications (e.g. Scream, Shouting, Respiratory Distress) along with custom user emergency instructions.
- **Synthesized High-Decibel Deterrent Siren (`EmergencySirenPlayer`)**:
  - Direct PCM generation of an alternating two-tone alarm siren (850 Hz / 1250 Hz) output directly to `AudioAttributes.USAGE_ALARM`.
  - Pierces device silent/vibrate profiles and requires zero bundled audio assets or network downloads.
  - Can be silenced at any time via a prominent action on the confirmed notification or directly inside the app.
- **Timed Screen Wake (`ScreenWakeController`)**:
  - Acquires a temporary partial screen wake to illuminate the display during emergency dispatch.

### Configuration:
In AcousticGuard **Settings → Autonomous Emergency SOS**:
- **Primary Phone Call Number**: Specify trusted phone number.
- **Auto-Call on Alert**: Toggle automatic calling on confirmed distress.
- **Emergency SMS Recipients**: Specify one or more phone numbers.
- **Auto-SMS on Alert**: Toggle automatic SMS broadcast.
- **Deterrent Siren**: Toggle siren alarm audio playback.
- **Custom SMS Message**: Optional personal message (e.g., medical info, home address).

---

## 2. Native System Safety Handoff (Accessibility Bridge)

AcousticGuard also maintains an optional post-countdown handoff that helps a person reach Android's system emergency UI.

### User-visible flow:
1. The local detector requires corroborated confirmation and the 10-second cancellation window.
2. If the user enabled **Native safety handoff** and enabled the AcousticGuard accessibility service in Android Settings, the monitoring service sends a one-shot handoff request.
3. The accessibility service requests a short best-effort screen wake and invokes Android's `GLOBAL_ACTION_POWER_DIALOG`.
4. While that handoff is armed, it reads only the active system window and looks for a conservative localized emergency-entry label.
5. It opens the system emergency interface. It never blindly clicks an OEM SOS trigger that would interfere with user intent.
6. The existing encrypted metadata outbox still records the confirmed event. Optional HTTPS delivery remains separate and is not emergency-service delivery.

### Why this boundary exists:
Android exposes accessibility global actions such as the power dialog, but it does not expose a public third-party API that starts Google's Personal Safety emergency workflow. The power-button SOS gesture, lock-screen UI, OEM labels, and Personal Safety configuration remain system/OEM behavior. Consequently, the app cannot promise that the same UI exists on every device.

---

## 3. Setup and Test Checklist

1. Open **Settings → Autonomous Emergency SOS** in AcousticGuard.
2. Configure trusted emergency phone and SMS numbers.
3. Grant `CALL_PHONE` and `SEND_SMS` permissions when prompted.
4. Use the in-app **Test SOS Dispatch (Non-Emergency)** button to verify phone call, SMS formatting, and siren audio in a controlled environment without triggering actual emergency services.
5. (Optional) If native power menu navigation is desired, enable the Accessibility service in Android Settings.

---

## 4. Key Files

- `DirectEmergencyDispatcher.kt`: Orchestrates autonomous calling, SMS broadcasts, and siren triggers.
- `EmergencyContactsPreferences.kt`: Encrypted/private persistence of emergency SOS configuration.
- `EmergencySirenPlayer.kt`: Low-latency PCM dual-tone alarm siren generator on `USAGE_ALARM`.
- `MonitoringService.kt`: Background service coordinating detection, countdown, and SOS execution.
- `NativeSafetyAccessibilityService.kt`: Opt-in system-window bridge and bounded scan loop.
- `EmergencyUiTargetMatcher.kt`: Deterministic, localized label policy for system dialogs.
- `ScreenWakeController.kt`: Best-effort timed screen wake.
