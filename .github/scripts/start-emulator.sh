#!/usr/bin/env bash
# Startet einen Emulator selbst, damit jede Ausgabe im Protokoll landet (statt im unsichtbaren Action-Log).
set -u
API=34; IMG="system-images;android-$API;google_apis;x86_64"
SDK=$ANDROID_HOME; export PATH=$SDK/cmdline-tools/latest/bin:$SDK/emulator:$SDK/platform-tools:$PATH
{
  echo "== KVM"; ls -l /dev/kvm; (command -v kvm-ok && kvm-ok) || true
  echo "== Pakete"; yes | sdkmanager --licenses >/dev/null 2>&1; sdkmanager --install "emulator" "platform-tools" "$IMG" 2>&1 | tail -3
  echo "== AVD"; echo no | avdmanager create avd -n ci -k "$IMG" -d pixel_6 --force 2>&1 | tail -2
  printf 'hw.camera.back=emulated\nhw.camera.front=none\nhw.ramSize=4096\nhw.cpu.ncore=4\ndisk.dataPartition.size=6G\n' >> ~/.android/avd/ci.avd/config.ini
  echo "== Emulator-Version"; emulator -version | head -2
} 2>&1 | tee emu-start.log
nohup emulator -avd ci -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -camera-back emulated > emulator.log 2>&1 &
timeout 300 adb wait-for-device || { echo 'adb sieht keinen Emulator'; tail -80 emulator.log | tee -a emu-start.log; exit 1; }
for i in $(seq 1 180); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo "Gestartet nach $((i*5)) s" | tee -a emu-start.log; break; }
  sleep 5
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { echo "Emulator startet nicht" | tee -a emu-start.log; tail -80 emulator.log | tee -a emu-start.log; exit 1; }
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0; adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82
