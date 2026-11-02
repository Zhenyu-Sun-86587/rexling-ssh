# Rexlink testing APK

Share `dist/Rexlink-0.1.0-arm64-release.apk`. It supports ARM64 Android devices running Android 9 or newer.

Open the APK on the device and allow installation from that source when prompted. This optimized, non-debuggable test release uses the existing local development signing identity.

Fresh installs start with no preset server. Enter an authorized Rex server URL and connect through Tailscale, or choose **Try preview** to explore offline. No personal connections, credentials or settings are bundled in the APK.

Long-press a tab to close it. Close a pane using its X in the pane picker or **Close pane** in the three-dot menu. Confirmation is required because closing ends the running program; closing a tab ends every program in that tab.

Viewing a pane leaves its shared terminal size alone. Tapping its content takes control and resizes the shared PTY for the phone, which also changes the grid seen by other clients.

The default keyboard accessory is one row. Esc/Ctrl/Alt/Shift/Tab are separate symbol buttons with gaps between them, followed by a shared arrow group and the keyboard toggle. Swipe the arrow group sideways to reveal clipped arrows, then tap to send. Arrow scrolling leaves the modifier buttons fixed. Tap Shift for the next key or hold it while tapping several keys. Scrolling sends no input. Reveal the chevron before Esc to expand F1–F12 and Home/End, paging, Insert and Delete keys.

The header's pointer button takes or releases terminal control. Its outlined state means viewing; its blue-filled state means control. Long-press it for the action label. Tap the eye at the far right of the bottom tab bar to open Activity. The pane button beside it offers Switch pane, New pane and New tab.

Visible `http://`, `https://` and `www.` URLs are underlined. Tap one to open Android's link handler without taking terminal control; long-press still selects text. Printed URLs can span rows that Ghostty marks as genuine soft wraps; fragments outside the available snapshot are not guessed. OSC 8 labeled web links use their stored destination and work across wrapped rows. Non-web destinations are not opened. Localhost links open as written on the phone; addresses are not rewritten.

The key row keeps its contrast while pane navigation temporarily disables input. The header control circle is 32dp with a 16dp pointer.

Long-press a web link for Open link, Copy link address and Share link (some actions may be in the floating toolbar overflow). Copy and Select all still operate on terminal text. Link actions use the frozen destination even if live output changes. Share opens Android's chooser; you select the recipient/app.

While controlling the terminal, quickly tap Ctrl to toggle it for the next key. Hold Ctrl to show the eight label-only shortcuts (^C, ^D, ^Z, ^R, ^L, ^A, ^E, ^W), slide onto one and release to send it once. Releasing outside cancels. The popup shows no shortcut descriptions.

Hold an arrow, Delete or Backspace to repeat. Sliding the arrow strip scrolls it without typing. Releasing, changing panes, losing control or leaving the app cancels repeat.

Activity groups reported program states by connected server. Filter Needs you, Working or Finished; tap an entry to open its exact session and pane without taking control. It refreshes while open and stops scanning when dismissed or backgrounded. Programs must report Rex status; missing reports are not inferred from output.
