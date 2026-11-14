# JARVIS Blue Theme and Termux Setup V1

## Visual direction

The Android app uses a dark navy surface palette with blue primary and cyan secondary accents. The JARVIS wordmark and primary actions use the blue accent. This is a restrained blue-lit interface, not a claim of animated neon/glow effects.

## Android Termux companion setup

Termux is optional. The Android app's built-in chat and in-process signal pass do not require it.

1. Install Termux from F-Droid or the official Termux GitHub releases. Avoid obsolete Play Store builds.
2. In Termux run:

```sh
pkg update && pkg upgrade
pkg install python git
```

3. Clone the repository:

```sh
git clone https://github.com/maksrugu123-sys/jarvis-ans100-alpha.git
cd jarvis-ans100-alpha
```

4. Start the Android-local companion bridge:

```sh
python -m tools.jarvis_app --port 8765 --platform android
```

5. Copy the one-time bearer token printed by the service. In the Android app open System → Optional Termux Extension, paste the token, save it, then connect.

Keep Termux and the bridge process running while using its extra capabilities. The app stores the token protected by Android Keystore and connects only to the fixed loopback endpoint. Do not share the token.

If the Python command reports a missing dependency, consult the repository's current dependency/install instructions rather than installing arbitrary packages. The bridge is source-level and still needs runtime verification on a real device.

## PC behavior

Termux is an Android terminal environment and is not the PC extension. On a PC, run the Python JARVIS local console/runtime directly using the desktop quick-start instructions. The desktop Python runtime already hosts its own local API/UI; installing Termux on Windows, Linux or macOS is neither required nor the intended setup.

Android and PC currently use separate local data directories. Cross-device transfer is an explicit encrypted JWS/JWB export/import workflow, not automatic sync.

## Verification

Source-only update. No Gradle build, tests, APK, Termux install, bridge launch or PC runtime test was performed.
