# GADS-Android-settings

## Building release APKs

The app has two product flavors — `gads` and `hf` (HelioFleet) — each with its own application ID and branding.

Build a single flavor:

```bash
./gradlew assembleGadsRelease   # GADS  (com.gads.settings)
./gradlew assembleHfRelease     # HelioFleet (com.heliofleet.settings)
```

Or build both at once:

```bash
./gradlew assembleRelease
```

The APKs are written to:

- `app/build/outputs/apk/gads/release/gads-settings.apk`
- `app/build/outputs/apk/hf/release/hf-settings.apk`

Release builds are signed with the local debug keystore (`~/.android/debug.keystore`), so they can be installed directly with `adb install`.