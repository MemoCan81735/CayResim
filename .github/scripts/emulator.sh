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
adb logcat -c || true
adb logcat -b crash -c || true
adb shell monkey -p app.cayresim --pct-syskeys 0 --pct-appswitch 0 --throttle 50 -s 4711 -v 5000 > monkey.log 2>&1 || true
adb logcat -d -b crash > crash.txt 2>&1 || true
adb logcat -d > logcat-monkey.txt 2>&1 || true
adb shell dumpsys meminfo app.cayresim > meminfo.txt 2>&1 || true
# Rot nur bei Absturz oder ANR der App selbst; ein Systemausfall wird ausgewiesen und ebenfalls rot gewertet
grep -q "Process: app.cayresim" crash.txt && { echo "App-Absturz in der Zufallsbedienung"; fail=1; }
grep -q "ANR in app.cayresim" logcat-monkey.txt && { echo "App-ANR in der Zufallsbedienung"; fail=1; }
grep -q "System appears to have crashed" monkey.log && { echo "Systemausfall in der Zufallsbedienung"; fail=1; }

# Bildschirmfotos fuer die Sichtpruefung durch Claude
mkdir -p screens
adb shell am start -n app.cayresim/.shell.MainActivity >/dev/null; sleep 6
adb exec-out screencap -p > screens/camera.png || true
adb shell input keyevent KEYCODE_BACK; sleep 1
grep -E "FATAL EXCEPTION|StrictMode-Verstoss" logcat.txt && fail=1
exit $fail
