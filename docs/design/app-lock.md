# App lock and screenshot block

Settings › Security has two switches, both off until the person turns them on.
Settings' Security row names each that is on ("Locked with the phone's lock · Screenshots
blocked"), so the second is not hidden behind the first (`securityDetail`); the page has the
sheet's back arrow to Settings, as Settings' other pages do (design-review.md, 1).

## Lock RexPocket

The app asks the phone who is there - fingerprint, face, or the screen lock's PIN, pattern or
password - when it comes back after being away, and shows a lock screen in its place until the
phone says yes.

- **Turning it on** asks the phone first. A person who cannot answer the prompt is never locked
  out by their own switch; a "no" leaves the lock off and says so. A phone with no screen lock
  cannot turn it on: the row says to set one in Android's settings first. Turning it off asks
  nothing. The whole row takes the tap, and a tap that cannot go ahead always says why, on the row
  and in a toast TalkBack reads: no screen lock, a no, or the phone failing to ask (in its words).
- **Lock after**: at once, or after 1, 5 or 15 minutes or an hour away (a minute at first, so a
  trip to the file picker or the share sheet does not lock it). "Away" is the app's activity
  stopped: another app on screen, the screen off. A process start with the lock on starts locked.
- **The prompt.** From Android 10 the platform's biometric prompt
  (`android.hardware.biometrics.BiometricPrompt`), with the screen lock as its fallback
  (`BIOMETRIC_WEAK | DEVICE_CREDENTIAL` from Android 11, `setDeviceCredentialAllowed` on 10);
  before that, the keyguard's own confirm screen (`createConfirmDeviceCredentialIntent`). Both are
  the framework's, so no library is added; the prompt needs `USE_BIOMETRIC` in the manifest, without
  which Android refuses it before anything shows (what a phone found: the switch did nothing). The
  prompt opens by itself once per lock, once the activity is resumed (Android refuses a prompt from
  an app not in front); Unlock opens it again, and a prompt that could not ask says why there. While it is up, the activity stopping for its PIN screen is not counted as leaving.
- **The lock screen** stands in for the whole app: the app is left out of the composition rather
  than drawn over, so none of its sheets or dialogs (each a window of its own) float above the lock,
  and its saved state comes back with it. The view model, and so every connection, is untouched.
- **Recent apps:** with the lock on, the app's picture in recent apps is left blank
  (`setRecentsScreenshotEnabled(false)`, Android 13 and later; `SecurityPrefs.recentsPicture`): it is
  taken as the app leaves, and showed the terminal to anyone holding the phone. Before Android 13
  only Block screenshots blanks it, as that call does not exist there.
- **Not locked:** notifications and their Reply and Interrupt (Android's own lock screen settings
  decide what they show), the Quick Settings tiles, and background mode's connections. A cold start
  connects the hosts as the activity is created, while the prompt is still up, so they are live by
  the time it is answered; nothing of them is shown before (`StartConnectsTest`).

## Block screenshots

`FLAG_SECURE` on the app's window, set and cleared as the switch changes: screenshots and screen
recordings show nothing of it, and its picture in recent apps is blank. The share and tile picker
activities show no terminal content and are left as they are.

## In the backup

`security` (settings-backup.md): `lock`, `lockAfterSeconds`, `blockScreenshots`. Only the choices:
no biometric data or key exists to export, as the phone keeps those. An import turns the lock on
only on a phone with a screen lock; otherwise it stays off and the app says why.

## Code and tests

- `core/…/AppLock.kt`: `SecurityPrefs` and `AppLockState` (when to lock, given the times it is
  told). `AppLockTest`: off never locks, a start is locked, the wait, the prompt's own PIN screen,
  turning it on and off.
- `app/…/ui/Security.kt`: `LockGate`, `LockScreen`, `rememberDeviceAuthenticator`, `SecuritySheet`.
  `MainActivity` wraps the app in `LockGate`, follows `blockScreenshots` with `FLAG_SECURE`, and tells
  the view model when it stops and starts. `HostStore.security`.
- `AppLockScreenTest` (JVM and emulator): the permission, the real prompt not refused on the
  emulator, a cold start locked, the wait, a yes unlocking. `AppLockRowTest` (JVM, keyguard
  shadowed): a tap without a screen lock says why; a prompt that cannot ask says its reason.
- `HostsAndSecurityTest` (JVM and emulator): the lock stands in for the app until the prompt says
  yes; the sheet turns the lock on only after a yes, and blocks screenshots; a backup's lock stays
  off on a phone without a screen lock.
