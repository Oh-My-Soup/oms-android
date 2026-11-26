# Screenshots

Store-style portrait screenshots (1080×1920) of the Android app. Each one is a real device capture
of the app's own Compose screens running on made-up demo data, framed with a short caption.

- `raw/` (not committed) holds the unframed device captures (1080×2400, Pixel 6a, dark theme).
- [`docs/screenshots/`](../../docs/screenshots) holds the framed images; `extras/` there has two alternates.
- `capture.sh` produces `raw/`, `build.py` + `frame.html` produce `docs/screenshots/`.

## Shots

| File | Caption | What it shows |
| --- | --- | --- |
| `01-transcript.png` | **Your agent, in your pocket.** Follow the live transcript and every tool call while oms works on your computer. | A live turn: prompt, thinking, read/grep/write/edit cards, a running `bash` test run. |
| `02-steer.png` | **Steer it mid-turn.** On a full-control link, a message sent while oms works steers the current turn. | An earlier steer from the phone (guest badge) and a new one typed in the composer, with Stop and Send. |
| `03-commands.png` | **Run slash commands.** Run host commands like /todo and /compact, with completions and output. | A finished `/todo` run with its output, and `/compact ` showing its subcommand completions. |
| `04-models.png` | **Switch model and thinking.** Pick from your host's models and thinking levels for this session. | The Model & thinking sheet: thinking chips, roles, enabled models, providers. |
| `05-agents.png` | **Keep an eye on subagents.** See what each one is doing, message it, kill it or revive it. | The Agents sheet with the main agent, two running subagents and a parked one. |
| `06-ask.png` | **Answer when it asks.** Questions and tool approvals reach your phone, with a notification when you're in another app. | A multi-select `ask` question in the pending panel. |
| `extras/extra-approve.png` | **Approve tool calls.** Read the exact command before it runs on your computer. | A `bash` approval (`git push`) with Approve / Approve for session / Deny. |
| `extras/extra-home.png` | **Pair with a QR code.** Run /collab in oms and scan the code. Saved hosts are one tap away. | Home with three made-up saved hosts. |

Captions live in `SHOTS` in `build.py`. Only slash commands a full-control guest can run are shown
(`COLLAB_GUEST_HOST_BUILTINS` in oh-my-soup's `packages/coding-agent/src/collab/guest-commands.ts`).

## Demo content

Everything on screen is invented: the project (`~/code/kestrel`, a hut-booking API), its files and
test output, the relay (`relay.example.dev`), the saved hosts and the device name ("Pixel 9").
Model names come from the bundled model catalog. No share link or QR code appears anywhere.

The data lives in debug-only code that never ships in a release build:

- `app/src/debug/AndroidManifest.xml` registers `PromoActivity` (own task, hidden from Recents).
- `app/src/debug/kotlin/dev/oms/remote/promo/PromoActivity.kt` shows the real `SessionScreen` or
  `HomeScreen` for the scene named in the `scene` extra.
- `app/src/debug/kotlin/dev/oms/remote/promo/PromoDemo.kt` builds the demo state per scene.
- `app/src/debug/kotlin/dev/oms/remote/promo/PromoSession.kt` is a fake `RemoteActions` that answers
  commands, completions and model loading from that data, so the UI's own code paths run.
  Nothing connects to a host or relay, and no model is called.

Scenes: `transcript`, `steer`, `commands`, `models`, `agents`, `ask`, `approve`, `home`.

## Rebuild

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # restarts the app; a live session drops
ANDROID_SERIAL=<device> tools/screenshots/capture.sh        # all scenes -> tools/screenshots/raw/
tools/screenshots/build.py                                  # raw/ -> docs/screenshots/
```

`capture.sh <scene...>` and `build.py <id...>` redo single shots. `build.py --keep-html` also
writes the filled frame pages to `tools/screenshots/.html/` for previewing layout changes in a browser.

Notes:

- `capture.sh` needs an unlocked screen. It turns on SystemUI demo mode for a clean status bar
  (9:41, full battery and signal, no notification icons), then exits demo mode and restores the
  previous `sysui_demo_allowed` value, also on failure. Taps assume a 1080×2400 screen at 420 dpi;
  the composer tap and the sheet swipe need new coordinates on other devices.
- Demo mode on this Pixel does not hide two status icons (a Digital Wellbeing glyph and the
  silent-ringer bell). `build.py` paints them over with the status bar color for the final
  images; `raw/` keeps the untouched captures. Adjust `STATUS_PATCHES` for another device.
- Colors are Material You dynamic colors, so they follow the capture device's wallpaper; the
  device was in dark mode.
- The app only posts notifications while it is in the background, so no shot shows a notification
  over the app.
- `build.py` needs ImageMagick (`magick`) and Chromium or Chrome (`CHROME=/path` to override). The
  frame uses Inter (or the Inter-based Adwaita Sans) when installed, else Noto Sans or system fonts.
