#!/usr/bin/env bash
set -euxo pipefail
pkg=io.github.hugfeature.hydrationbreak
mkdir -p device-evidence
# Reinstall a clean copy: this also checks installation of the exact downloadable APK.
if adb shell pm list packages | tr -d '\r' | grep -qx "package:$pkg"; then
  adb uninstall "$pkg"
fi
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell am start -W -n "$pkg/.MainActivity" | tee device-evidence/launch.txt
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys alarm > device-evidence/alarms-background.txt
grep -q "$pkg" device-evidence/alarms-background.txt
adb shell input keyevent KEYCODE_SLEEP
adb shell dumpsys battery unplug
adb shell cmd power set-mode 1
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle > device-evidence/idle.txt
adb shell dumpsys alarm > device-evidence/alarms-idle.txt
grep -q "$pkg" device-evidence/alarms-idle.txt
# These checks prove registration survives idle, NOT that a timed reminder was delivered.
adb shell dumpsys deviceidle unforce
adb shell cmd power set-mode 0
adb shell dumpsys battery reset
adb reboot
adb wait-for-device
for attempt in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent 82
for attempt in $(seq 1 30); do
  adb shell dumpsys alarm > device-evidence/alarms-reboot.txt
  if grep -q "$pkg" device-evidence/alarms-reboot.txt; then break; fi
  sleep 2
done
grep -q "$pkg" device-evidence/alarms-reboot.txt
adb shell pm revoke "$pkg" android.permission.POST_NOTIFICATIONS
adb shell pm set-permission-flags "$pkg" android.permission.POST_NOTIFICATIONS user-set user-fixed
adb shell am start -W -n "$pkg/.MainActivity"
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml device-evidence/permission-denied.xml
grep -q '通知未开启' device-evidence/permission-denied.xml
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell input keyevent KEYCODE_HOME
adb shell am start -W -n "$pkg/.MainActivity"
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml device-evidence/permission-granted.xml
grep -q '通知已开启' device-evidence/permission-granted.xml
adb shell logcat -d -b crash > device-evidence/crashes.txt
if grep -q "$pkg" device-evidence/crashes.txt; then exit 1; fi
