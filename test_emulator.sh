#!/bin/bash
# ChromeClone screen-share test on emulator
ADB=/home/jetsonnano/android-sdk/platform-tools/adb
APK=/home/jetsonnano/chromeclone/app-release.apk

echo "=== 1. Install APK ==="
$ADB -e install -r "$APK" 2>&1 | tail -2

echo "=== 2. Grant permissions ==="
$ADB -e shell pm grant co.carryai.chromeclone android.permission.CAMERA 2>&1
$ADB -e shell pm grant co.carryai.chromeclone android.permission.RECORD_AUDIO 2>&1
$ADB -e shell pm grant co.carryai.chromeclone android.permission.POST_NOTIFICATIONS 2>&1

echo "=== 3. Clear logcat, launch app ==="
$ADB -e logcat -c
$ADB -e shell am start -n co.carryai.chromeclone/.MainActivity 2>&1 | tail -1
sleep 15

echo "=== 4. Grant MediaProjection via UI (tap Start / allow) ==="
# The MediaProjection prompt appears as a system dialog; tap the start button.
# First try: find the dialog and tap "Start now"/"Start recording" button.
$ADB -e shell uiautomator dump /sdcard/ui.xml 2>/dev/null
$ADB -e shell cat /sdcard/ui.xml 2>/dev/null | grep -o 'text="[^"]*"' | head -20

echo "=== 5. Wait and capture logcat ==="
sleep 10
$ADB -e logcat -d -s ChromeClone ScreenCaptureService 2>&1 | tail -40
