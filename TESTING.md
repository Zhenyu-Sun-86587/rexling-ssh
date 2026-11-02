# Rexlink testing APK

Share `dist/Rexlink-0.1.0-arm64-release.apk`. It supports ARM64 Android devices running Android 9 or newer.

Open the APK on the device and allow installation from that source when prompted. This optimized, non-debuggable test release uses the existing local development signing identity.

Fresh installs start with no preset server. Enter an authorized Rex server URL and connect through Tailscale, or choose **Try preview** to explore offline. No personal connections, credentials or settings are bundled in the APK.

Long-press a tab to close it. Close a pane using its X in the pane picker or **Close pane** in the three-dot menu. Confirmation is required because closing ends the running program; closing a tab ends every program in that tab.

Viewing a pane leaves its shared terminal size alone. Tapping its content takes control and resizes the shared PTY for the phone, which also changes the grid seen by other clients.

The default keyboard accessory is one row. Esc/Ctrl/Alt/Shift/Tab are separate symbol buttons with gaps between them, followed by a shared arrow group and the keyboard toggle. Swipe the arrow group sideways to reveal clipped arrows, then tap to send. Arrow scrolling leaves the modifier buttons fixed. Tap Shift for the next key or hold it while tapping several keys. Scrolling sends no input. Reveal the chevron before Esc to expand F1–F12 and Home/End, paging, Insert and Delete keys.

The header's pointer button takes or releases terminal control. Its outlined state means viewing; its blue-filled state means control. Long-press it for the action label. The eye icon is reserved for future activity viewing.

Visible `http://`, `https://` and `www.` URLs are underlined. Tap one to open Android's link handler without taking terminal control; long-press still selects text. Links must be visible within one terminal row. Wrapped destinations and hidden OSC 8 links are not exported by the current Android cell snapshot and are not reconstructed. Localhost links open as written on the phone; addresses are not rewritten.
