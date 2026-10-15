# Mi Scale Monitor

Android 8+ (API 26+), Kotlin, Xiaomi Mi Body Composition Scale 2 XMTZC05HM.

## Build APK (no local Android Studio)

1. Create a new GitHub repository and upload **all files** from this folder, including `.github/workflows/build-apk.yml`.
2. Open **Actions** → **Build Android APK** → **Run workflow**.
3. Once the build finishes, download the artifact **MiScaleMonitor-debug-apk**. Extract the ZIP and install `app-debug.apk` on your Android phone.
4. Enable Bluetooth. Launch the app and grant nearby-device/Bluetooth permission. Step on the scale barefoot for impedance.

## Behavior

The app scans service data with UUID `0x181B` and parses the 13-byte Xiaomi broadcast. No pairing, Internet, account, database, or history. Profile is fixed in source: male, 175 cm, 51 years. BMI is exact given the weight and fixed height. Fat, muscle, and water are **approximate estimates** from a non-Xiaomi empirical BIA model, not direct scale readings or clinical measurements. No metric is shown until a valid impedance arrives with a stabilized weight in the same frame.

Note: some Android phones may restrict BLE scanning or filter advertisements when the app is in the background. Keep the app in the foreground. Android 12+ requires Nearby Devices permission; Android 8–11 may require Location permission and enabled Location services for BLE scanning.

## Privacy

No INTERNET permission. No measurement history or persistent storage. App only receives BLE broadcasts while open.

## Limitations

This is an initial untested implementation: physical device testing is required. `AGE_YEARS` is fixed but the current empirical BIA approximation does not include age; this is deliberate rather than claiming an unsupported age adjustment. Body composition formulas may diverge significantly from Zepp Life. The estimated muscle percentage is a rough lean-mass proxy, not directly measured skeletal muscle.
