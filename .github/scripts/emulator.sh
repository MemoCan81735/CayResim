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
# Systemdialoge (z. B. "System UI reagiert nicht") ausblenden, damit die Zufallsbedienung sie nicht
# antippt und dabei die System-UI beendet. Abstuerze und ANRs werden weiter erkannt und protokolliert.
adb shell settings put global hide_error_dialogs 1 || true

# Emulator nach den Gerätetests zur Ruhe kommen lassen (Last unter 6, hoechstens 90 s)
for i in $(seq 1 18); do
  load=$(adb shell cat /proc/loadavg | cut -d' ' -f1 | cut -d. -f1)
  [ "${load:-99}" -lt 6 ] && break
  sleep 5
done
echo "Last vor der Zufallsbedienung: $(adb shell cat /proc/loadavg)"

run_monkey() {
  adb logcat -c || true
  adb logcat -b crash -c || true
  adb shell am force-stop app.cayresim || true
  adb shell monkey -p app.cayresim --pct-syskeys 0 --pct-appswitch 0 --throttle 50 -s 4711 -v 5000 > monkey.log 2>&1 || true
  adb logcat -d -b crash > crash.txt 2>&1 || true
  adb logcat -d > logcat-monkey.txt 2>&1 || true
  adb shell dumpsys meminfo app.cayresim > meminfo.txt 2>&1 || true
}
monkey_failed() {
  grep -q "Process: app.cayresim" crash.txt && return 0
  grep -q "ANR in app.cayresim" logcat-monkey.txt && return 0
  grep -q "System appears to have crashed" monkey.log && return 0
  return 1
}
run_monkey
# Ist die System-UI des Emulators selbst gestorben, ist der Lauf nicht aussagekraeftig: genau einmal wiederholen.
if monkey_failed && grep -q "Process com.android.systemui (pid [0-9]*) has died" logcat-monkey.txt; then
  echo "System-UI des Emulators ist ausgefallen, Zufallsbedienung wird einmal wiederholt"
  mv monkey.log monkey-1.log; mv logcat-monkey.txt logcat-monkey-1.txt
  sleep 20
  run_monkey
fi
grep -q "Process: app.cayresim" crash.txt && { echo "App-Absturz in der Zufallsbedienung"; fail=1; }
grep -q "ANR in app.cayresim" logcat-monkey.txt && { echo "App-ANR in der Zufallsbedienung"; fail=1; }
grep -q "System appears to have crashed" monkey.log && { echo "Abbruch der Zufallsbedienung"; fail=1; }

# Bildschirmfotos fuer die Sichtpruefung durch Claude
mkdir -p screens
adb shell am start -n app.cayresim/.shell.MainActivity >/dev/null; sleep 6
adb exec-out screencap -p > screens/camera.png || true
adb shell input keyevent KEYCODE_BACK; sleep 1
grep -E "FATAL EXCEPTION|StrictMode-Verstoss" logcat.txt && fail=1
exit $fail
