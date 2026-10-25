# Native safety handoff

AcousticGuard now has an optional post-countdown handoff that helps a person reach Android's
system emergency UI. It is intentionally not described as a direct Google Personal Safety API or
as a guaranteed emergency-service connection.

## User-visible flow

1. The local detector requires the existing corroborated confirmation and 10-second cancellation
   window.
2. If the user enabled **Native safety handoff** and enabled the AcousticGuard accessibility
   service in Android Settings, the monitoring service sends a one-shot handoff request.
3. The accessibility service requests a short best-effort screen wake and invokes Android's
   `GLOBAL_ACTION_POWER_DIALOG`.
4. While that handoff is armed, it reads only the active system window and looks for a conservative
   localized emergency-entry label.
5. It may click an entry that opens the emergency interface. It never clicks an SOS/call action and
   never places a call automatically. If Android exposes an SOS action, the status tells the user
   to finish the action themselves.
6. The existing encrypted metadata outbox still records the confirmed event. Optional HTTPS
   delivery remains separate and is not emergency-service delivery.

## Why this boundary exists

Android exposes accessibility global actions such as the power dialog, but it does not expose a
public third-party API that starts Google's Personal Safety emergency workflow. The power-button
SOS gesture, lock-screen UI, OEM labels, and Personal Safety configuration remain system/OEM
behavior. Consequently, the app cannot promise that the same UI exists on every device or that a
native SOS action will contact a configured person.

The service therefore has two safety constraints:

- it is off by default and requires a user-controlled special-access toggle;
- it may open the system emergency interface, but a person must complete the final SOS/call action.

This prevents a false-positive model decision or a vendor label collision from silently placing an
emergency call. It also keeps the service aligned with a deterministic, user-visible accessibility
workflow rather than an autonomous call decision.

## Setup and test checklist

1. Open **Settings → Native safety handoff** in AcousticGuard.
2. Turn on the toggle after reading the disclosure.
3. Open Android accessibility settings and enable **AcousticGuard native safety handoff**.
4. Return to AcousticGuard and confirm the Safety screen reports **Armed for confirmed alerts**.
5. Use a controlled, non-emergency test path. Do not test by creating a real distress event near an
   emergency number. Verify that the screen wakes, the power menu appears, and any emergency
   action remains user-confirmed.
6. Verify the local incident queue and, if configured, the user-owned HTTPS receiver separately.

## Device coverage

The matcher includes common English, Spanish, French, German, Italian, Portuguese, Hindi, Chinese,
Japanese, and Korean labels. It uses exact normalized labels rather than broad substring matches.
OEMs can still use different wording or window types; those cases fall back to a visible
**User action required** status instead of a blind click.

## Files

- `NativeSafetyAccessibilityService.kt`: opt-in system-window bridge and bounded scan loop.
- `EmergencyUiTargetMatcher.kt`: deterministic, localized label policy.
- `NativeSafetyHandoffController.kt`: consent and one-shot request boundary.
- `ScreenWakeController.kt`: best-effort timed screen wake.
- `native_safety_accessibility_service.xml`: accessibility service declaration.
