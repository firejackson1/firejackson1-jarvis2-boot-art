# Jarvis Boot Art

An Android TV app for the rooted onn 4K Pro (jarvis2). Import a boot
animation ZIP, preview its frames with the D-pad, and use Magisk to apply the
animation or restore the Android default. The initial onn splash logo uses a
separate firmware asset.

## Status

**Tested on the onn. Human visual verification on the TV is pending.**

| Check | Result |
| --- | --- |
| Signed APK installation and launch | Passed on jarvis2 |
| D-pad ZIP import and frame preview | Passed with the stock animation. The pirate chase ZIP also imported successfully |
| Apply, restore and reapply shell commands | Passed in an isolated directory on the onn |
| Pirate chase animation through a real boot | Installed through authorized ADB root. Android finished booting, Magisk mounted the expected ZIP, the SHA-256 matched, and Android logged the animation starting |
| Seeing the animation on the physical TV | Awaiting human verification |
| In-app Magisk approval and live Restore stock | Pending end-to-end verification |

Device build URO3.260203.035.A1.15640252, Android 14. Last tested November 17, 2026.

## Import and apply

Open **Jarvis Boot Art → Import ZIP**. Choose a file if a document picker is
installed. The onn ships a picker stub, so choose **Use ZIP copied to the TV**
to create the app's import directory, then copy the ZIP from your computer:

```sh
adb -s DEVICE push bootanimation.zip /sdcard/Android/data/dev.jarvis.bootart/files/bootanimation.zip
```

Choose **Use ZIP copied to the TV** again, then **Preview import** or
**Apply import**. Approve Magisk root when prompted. Reboot to see the change.
**Restore stock** disables the override for the next boot.

The app accepts Android animation ZIPs with simple `p`, `c`, or `f` parts
and PNG or JPEG frames. Its preview plays each frame once in part order.
Android applies repeat, pause and fade instructions during startup.

## Pirate chase animation

The [ready-to-install pirate chase ZIP](animations/pirate-chase-bootanimation.zip)
contains 72 frames at 18 fps. To convert a 1920×1080 animated PNG into the same
boot animation format, install Pillow and run:

```sh
python3 tools/make-pirate-chase-animation.py input.apng bootanimation.zip
```

## Build

Use JDK 21, `aapt`, `apksigner`, the API 34 Android SDK `android.jar`, an
R8 JAR with D8, and your signing keystore. Set the paths and run:

```sh
export ANDROID_JAR=/path/to/android.jar R8_JAR=/path/to/r8.jar
export APK_KEYSTORE=/path/to/signing.p12 APK_KEY_ALIAS=boot-art
sh build.sh
```

The signed APK is written to `build/Jarvis-Boot-Art.apk`. Use the same signing
key to install upgrades over an existing APK.
