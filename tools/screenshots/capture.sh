#!/usr/bin/env bash
# Captures the raw promo screenshots from a connected device running a debug build.
#
#   tools/screenshots/capture.sh [scene ...]     # default: every scene below
#
# Needs: adb, a debug build installed (./gradlew :app:assembleDebug + adb install -r), an
# unlocked screen. Uses the debug-only PromoActivity (app/src/debug), which renders the real
# screens on made-up demo data; nothing connects to a host or relay.
#
# The status bar is cleaned with SystemUI demo mode; the previous `sysui_demo_allowed` value is
# restored and demo mode is exited on the way out. Set ANDROID_SERIAL to pick a device.
set -euo pipefail

cd "$(dirname "$0")"
RAW=raw
mkdir -p "$RAW"
ACTIVITY=dev.oms.remote/dev.oms.remote.promo.PromoActivity

run() { adb shell "$@"; }
demo() { run am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
shot() { sleep "${2:-1.5}"; adb exec-out screencap -p >"$RAW/$1.png"; echo "captured $RAW/$1.png"; }
scene() { run am start --activity-clear-task -n "$ACTIVITY" --es scene "$1" >/dev/null; sleep 2.5; }
hide_ime() {
	sleep 1.2
	# Back only while the keyboard is up; otherwise it would close the screen.
	local ime
	ime=$(run dumpsys input_method)
	if grep -q "mInputShown=true" <<<"$ime"; then run input keyevent KEYCODE_BACK; fi
}
# Center of the first node whose content-desc matches $1, from a UI dump.
center_of() {
	run uiautomator dump /sdcard/promo-ui.xml >/dev/null 2>&1
	local bounds
	bounds=$(adb exec-out cat /sdcard/promo-ui.xml | tr '>' '\n' | grep -F "content-desc=\"$1" | head -1 |
		sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
	run rm -f /sdcard/promo-ui.xml
	read -r x1 y1 x2 y2 <<<"$bounds"
	echo "$(((x1 + x2) / 2)) $(((y1 + y2) / 2))"
}
# Composer text field: just above the navigation bar, left of the buttons.
COMPOSER_X=450
COMPOSER_Y=2240

previous_demo_allowed=$(run settings get global sysui_demo_allowed | tr -d '\r')
cleanup() {
	demo exit || true
	if [ "$previous_demo_allowed" = "null" ]; then
		run settings delete global sysui_demo_allowed >/dev/null || true
	else
		run settings put global sysui_demo_allowed "$previous_demo_allowed" || true
	fi
}
trap cleanup EXIT

run settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile show -e datatype none -e level 4 -e fully true
demo notifications -e visible false
for slot in volume mute zen alarm sync bluetooth location speakerphone; do demo status -e "$slot" hide; done

capture_scene() {
	case "$1" in
	transcript)
		scene transcript
		shot 01-transcript
		;;
	steer)
		scene steer
		run input tap $COMPOSER_X $COMPOSER_Y
		sleep 1
		run input text "Then%sdocument%sthe%slimit%sin%sdocs/api.md"
		hide_ime
		shot 02-steer
		;;
	commands)
		scene commands
		run input tap $COMPOSER_X $COMPOSER_Y
		sleep 1
		run input text "/compact%s"
		hide_ime
		shot 03-commands
		;;
	models)
		scene models
		run input tap 300 246 # header subtitle opens "Model & thinking"
		shot 04-models 2
		;;
	agents)
		scene agents
		read -r x y <<<"$(center_of "2 subagents running")"
		run input tap "$x" "$y"
		sleep 1.5
		run input swipe 540 1262 540 200 600 # expand the sheet
		shot 05-agents
		;;
	ask)
		scene ask
		shot 06-ask
		;;
	approve)
		scene approve
		shot extra-approve
		;;
	home)
		scene home
		shot extra-home
		;;
	*)
		echo "unknown scene: $1" >&2
		exit 2
		;;
	esac
}

scenes=("$@")
[ ${#scenes[@]} -eq 0 ] && scenes=(transcript steer commands models agents ask approve home)
for s in "${scenes[@]}"; do capture_scene "$s"; done

# Leave the promo screen (a sheet may need one extra back).
for _ in 1 2 3; do
	top=$(run dumpsys activity activities | grep "topResumedActivity" || true)
	grep -q "promo.PromoActivity" <<<"$top" || break
	run input keyevent KEYCODE_BACK
	sleep 0.6
done
