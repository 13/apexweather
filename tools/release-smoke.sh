#!/usr/bin/env bash
#
# Runs the *release* APK on a device and checks it survives the things R8 is most likely to break.
#
# The instrumented suite runs against the debug build, which is not the artifact anyone installs.
# Three things in this app only fail once R8 has been over them: Glance picks its generated widget
# layouts by name at runtime, kotlinx-serialization resolves serializers reflectively, and Hilt's
# generated graph is stitched together from annotations. Assembling the APK exercises none of that,
# so this installs it, drives it, and reads back what the screen actually shows.
#
# What it does not do: place a widget. APPWIDGET_UPDATE is a protected broadcast that adb cannot
# send, and dragging a widget onto a launcher is too fragile to run unattended, so the widget is
# covered here only as far as "the provider survived R8 and is registered". Placing one and looking
# at it stays a manual check.
#
# Usage: tools/release-smoke.sh [serial]
set -euo pipefail

SERIAL="${1:-${ANDROID_SERIAL:-}}"
if [ -z "$SERIAL" ]; then
    SERIAL=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
fi
[ -n "$SERIAL" ] || { echo "no device attached"; exit 1; }
ADB=(adb -s "$SERIAL")

APK="app/build/outputs/apk/release/ApexWeather-release.apk"
[ -f "$APK" ] || { echo "missing $APK — run ./gradlew :app:assembleRelease first"; exit 1; }

PKG=it.apexweather
OUT="${SMOKE_OUT:-build/release-smoke}"
mkdir -p "$OUT"

echo "== installing the release build on $SERIAL"
"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || true
"${ADB[@]}" install -r "$APK"

# Everything from here on has to appear in a log that starts empty, or an older crash would pass
# for a new one and, worse, a new one could hide in the noise.
"${ADB[@]}" logcat -c 2>/dev/null || true

echo "== launching"
"${ADB[@]}" shell am start -W -n "$PKG/.MainActivity" >/dev/null
# Captured now, while the app is certainly up: every check below that reads the log has to be able
# to tell this app's output from the rest of the device's.
PID=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
# Long enough for the launch refresh to reach all five upstreams and come back through every mapper.
sleep 20

FAILED=0

echo "== reading the screen back"
"${ADB[@]}" shell uiautomator dump /sdcard/apex-smoke.xml >/dev/null 2>&1 || true
"${ADB[@]}" pull /sdcard/apex-smoke.xml "$OUT/ui.xml" >/dev/null 2>&1 || true
"${ADB[@]}" exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true

# "Tirol" and not "Dorf Tirol": the default place is named Dorf Tirol in German, Tirolo in Italian
# and Tirol in English, and this runs on whatever locale the device is set to. The stem the three
# share is the only spelling that is not a bet on the emulator's language.
if grep -q "Tirol" "$OUT/ui.xml" 2>/dev/null; then
    echo "  the home screen rendered"
else
    echo "FAIL: the home screen never showed the location; see $OUT/ui.xml"
    FAILED=1
fi

# A temperature on screen means the forecast came back, was deserialised, blended and formatted —
# the whole chain R8 could have broken, checked by its result rather than by its parts.
if grep -qE '°' "$OUT/ui.xml" 2>/dev/null; then
    echo "  a temperature reached the screen"
else
    echo "FAIL: nothing on screen looks like a temperature; the forecast never arrived"
    FAILED=1
fi

echo "== checking the widget provider survived"
# The full class name, not the manifest's ".widget.…" shorthand: dumpsys prints what the platform
# resolved, so matching it also proves R8 did not rename the receiver out from under the manifest.
if "${ADB[@]}" shell dumpsys appwidget 2>/dev/null | grep -q "$PKG.widget.ApexWidgetReceiver"; then
    echo "  provider registered"
else
    echo "FAIL: the widget provider is not registered; R8 or the manifest lost it"
    FAILED=1
fi

echo "== checking the log"
if ! "${ADB[@]}" shell pidof "$PKG" >/dev/null; then
    echo "FAIL: the app is not running any more"
    FAILED=1
fi

"${ADB[@]}" logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
# Only this app's own lines. The full log is full of other processes' problems — a CI emulator's
# Settings app throws ClassNotFoundException on its own slice controllers at boot — and a smoke test
# that greps all of it reports someone else's trouble as ours.
if [ -n "$PID" ]; then
    "${ADB[@]}" logcat -d --pid="$PID" > "$OUT/logcat-app.txt" 2>/dev/null || true
else
    : > "$OUT/logcat-app.txt"
fi

CRASHES=$(grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" "$OUT/logcat.txt" || true)
if [ -n "$CRASHES" ]; then
    echo "FAIL: the app crashed"
    echo "$CRASHES" | head -40
    FAILED=1
fi

# ClassNotFound and NoSuchMethod are how a missing keep rule reports itself, and this app catches
# enough of its own exceptions that one can be logged without taking the process down. Scoped to the
# app's own pid, for the reason above.
MISSING=$(grep -E "ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|SerializationException" "$OUT/logcat-app.txt" || true)
if [ -n "$MISSING" ]; then
    echo "FAIL: something R8 removed or renamed was looked up at runtime"
    echo "$MISSING" | head -40
    FAILED=1
fi

echo "installed: $("${ADB[@]}" shell dumpsys package "$PKG" | grep -m1 versionName= | tr -d ' \r')"

if [ "$FAILED" -eq 0 ]; then
    echo "release smoke: OK"
else
    echo "release smoke: FAILED (artifacts in $OUT)"
    exit 1
fi
