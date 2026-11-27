# Floating Bubble Mode (SwiftKey & Third-Party Keyboard Support)

This fork adds **Floating bubble** mode (`ly.anoma.hex.bubble.*`) on top of official Hex for Android so your existing keyboard (such as **Microsoft SwiftKey**) remains active at all times.

## Key Capabilities
- **Non-focus-stealing Accessibility overlay:** `HexAccessibilityService` renders `DotScopeBubbleView` (`Circle`, `Pill`, `Hexagon`, `Square` in `Small`, `Medium`, `Large`) whenever a soft keyboard opens (or `Always`), without switching Android's default input method.
- **Gestures:**
  - **Tap:** start hands-free dictation; tap again to finish and insert.
  - **Hold (≥350 ms):** push-to-talk (bubble enlarges and rises above your thumb; release finishes and inserts).
  - **Double-tap:** opens the non-focus-stealing **Quick menu** (`Undo dictation`, `Paste last`, `Language`, `Style`, `Next dictation`, `Clean up`, `Translate`, `Bubble settings`).
  - **Long-press (≥550 ms):** cancels recording or aborts an in-flight Whisper decode.
  - **Drag:** moves the bubble and snaps to the nearer screen edge.
- **Overnight reliability (Samsung One UI / Android 14–16):**
  - Zero idle foreground services run overnight (`HexAccessibilityService` records directly and only arms `BubbleDictationService` on demand from a foreground activity, stopping automatically after 3 minutes idle or on screen-off).
  - Setup, Home, and Bubble settings include a one-tap **Unrestricted battery** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) prompt so nightly OEM sleep routines never force-stop the app or reset Android's Accessibility toggle.
- **Multilingual Whisper translation:** `Translate what I say` works for all non-English dictation languages (including Deutsch → English), including alongside **Labs › Automatic listens for the language**.
