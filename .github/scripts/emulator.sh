#!/usr/bin/env bash
# Laeuft im Emulator-Schritt. Ein Fehler in einem Teil laesst den Schritt scheitern, die anderen laufen trotzdem.
set -u
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
fail=0
adb logcat -c || true
(adb logcat -v time > logcat.txt 2>&1 &)
gradle --continue connectedDebugAndroidTest 2>&1 | tee emu.log || fail=1
grep -q "BUILD SUCCESSFUL" emu.log || fail=1

# Zufallsbedienung (Testebene 6): 5000 Schritte, Kamera-Erlaubnis vorher erteilt.
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant app.cayresim android.permission.CAMERA || true
adb shell monkey -p app.cayresim --pct-syskeys 0 --throttle 50 -s 4711 -v 5000 > monkey.log 2>&1 || fail=1
grep -Eq "CRASH|ANR in app.cayresim|Monkey aborted" monkey.log && fail=1

# Bildschirmfotos fuer die Sichtpruefung durch Claude
mkdir -p screens
adb shell am start -n app.cayresim/.shell.MainActivity >/dev/null; sleep 6
adb exec-out screencap -p > screens/camera.png || true
adb shell input keyevent KEYCODE_BACK; sleep 1
grep -E "FATAL EXCEPTION|StrictMode-Verstoss" logcat.txt && fail=1
exit $fail
