# Deploy notes (Google Play, com.listenai.describe)

The app has no production release. It is distributed on testing tracks only.

## Current Play state (checked 2026-11-25)

| Track | versionCode | targetSdk |
|---|---|---|
| internal | 9 (0.3.0) | 35 |
| closed (alpha) | 2 (0.1.0) | 35 |
| open (beta) | none | |
| production | none | |

## 0.3.1 (versionCode 10): built, NOT released

- targetSdk / compileSdk 36. Signed release bundle built from `android/` tree
  `481cd617cc1014375479f7068ff6d2543761192c`.
- Bundle SHA-256: `bbde384f059fb4b15d27cfc062bdecec16c644c9c6c8f4869820d015718b2ae2`
- The accessibility service declares `isAccessibilityTool="true"`.
- Blocked: the Play API rejects every edit to this app, including one with no
  changes, with "Your accessibility permission declaration needs to be updated."
  The declaration has to be updated in Play Console before any upload.
- Not tested on a device. The repo has no unit tests.

After the declaration is accepted, release versionCode 10 to the internal and
closed testing tracks, then update the table above and tag the commit `v0.3.1`.
If anything under `android/` changed since the tree listed above, rebuild first.

## Building

Release signing needs `ANDROID_UPLOAD_STORE_FILE`, `ANDROID_UPLOAD_STORE_PASSWORD`,
`ANDROID_UPLOAD_KEY_ALIAS` and `ANDROID_UPLOAD_KEY_PASSWORD` in
`~/.gradle/gradle.properties`. Use JDK 17 and initialise the `third_party/llama.cpp`
submodule:

    cd android && ./gradlew :app:bundleRelease
