#!/usr/bin/env bash
set -euxo pipefail
pkg=io.github.hugfeature.hydrationbreak
mkdir -p device-evidence
# Reinstall a clean copy: this also checks installation of the exact downloadable APK.
if adb shell pm list packages | tr -d '\r' | grep -qx "package:$pkg"; then
  adb uninstall "$pkg"
fi
# Install base, write non-default settings, then upgrade without uninstalling.
adb install upgrade-fixture/base.apk
adb shell am start -W -n "$pkg/.MainActivity"
adb shell am force-stop "$pkg"
printf '%s' '<map><boolean name="enabled_1" value="false" /><int name="minutes_2" value="45" /><int name="start" value="8" /><int name="end" value="20" /></map>' | adb shell "run-as $pkg sh -c 'cat > shared_prefs/reminder_settings.xml'"
adb shell run-as "$pkg" cat shared_prefs/reminder_settings.xml > device-evidence/settings-before-upgrade.xml
adb install -r upgrade-fixture/upgrade.apk
adb shell run-as "$pkg" cat shared_prefs/reminder_settings.xml > device-evidence/settings-after-upgrade.xml
python3 - <<'PYXML'
import xml.etree.ElementTree as ET
before = {e.attrib['name']: e.attrib.get('value', e.text) for e in ET.parse('device-evidence/settings-before-upgrade.xml').getroot()}
after = {e.attrib['name']: e.attrib.get('value', e.text) for e in ET.parse('device-evidence/settings-after-upgrade.xml').getroot()}
for key in ('enabled_1', 'minutes_2', 'start', 'end'):
    assert before[key] == after[key], f'Upgrade lost setting: {key}'
PYXML
adb shell am start -W -n "$pkg/.MainActivity"
adb shell dumpsys package "$pkg" > device-evidence/package-upgrade.txt
grep -q 'versionCode=6' device-evidence/package-upgrade.txt
# The rest of the smoke test uses the exact distributable build.
adb uninstall "$pkg"
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell am start -W -n "$pkg/.MainActivity" | tee device-evidence/launch.txt
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys alarm > device-evidence/alarms-background.txt
grep -q "$pkg" device-evidence/alarms-background.txt
adb shell input keyevent KEYCODE_SLEEP
adb shell dumpsys battery unplug
adb shell cmd power set-mode 1
adb shell dumpsys deviceidle enable
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle > device-evidence/idle.txt
grep -q 'mState=IDLE' device-evidence/idle.txt
adb shell dumpsys alarm > device-evidence/alarms-idle.txt
grep -q "$pkg" device-evidence/alarms-idle.txt
# These checks prove registration survives idle, NOT that a timed reminder was delivered.
adb shell dumpsys deviceidle unforce
adb shell cmd power set-mode 0
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent 82
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
# Reboot from the normal background/locked state, without reopening after boot.
adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_SLEEP
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
adb shell dumpsys package "$pkg" > device-evidence/package-reboot.txt
adb shell dumpsys user > device-evidence/users-reboot.txt
adb shell dumpsys activity broadcasts > device-evidence/broadcasts-reboot.txt
adb shell logcat -d > device-evidence/logcat-reboot.txt
adb shell run-as "$pkg" cat shared_prefs/reminder_settings.xml > device-evidence/settings-reboot.xml
if ! grep -q "$pkg" device-evidence/alarms-reboot.txt; then
  cat device-evidence/package-reboot.txt
  cat device-evidence/users-reboot.txt
  cat device-evidence/settings-reboot.xml
  grep -E "$pkg|BOOT_COMPLETED|Background execution|BroadcastQueue" device-evidence/logcat-reboot.txt | tail -100 || true
  exit 1
fi
adb shell logcat -d -b crash > device-evidence/crashes.txt
if grep -q "$pkg" device-evidence/crashes.txt; then exit 1; fi
