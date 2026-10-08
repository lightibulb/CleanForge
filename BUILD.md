# Building CleanForge

> This project was patched in an environment with **no Android SDK, no Gradle, no Kotlin compiler and no network**, so none of the commands below have been run against it yet. Treat the first build as the real test, and read "If the first build fails" below. A successful CI run from a clean checkout (with the Gradle wrapper committed) is the proof the audit asks for; nothing else is.

## Prerequisites

- JDK 17
- Android SDK with platform **35** and build-tools (Android Studio installs these)
- Gradle 8.9 (only to generate the wrapper once), or just open the project in Android Studio (Ladybug or newer)

## One-time: generate and COMMIT the Gradle wrapper

`gradle/wrapper/gradle-wrapper.properties` is in the repository, but the wrapper **jar and scripts are not** (they are binaries that could not be produced where this project was patched). The independent audit requires them to be committed, because a repository that cannot be built with `./gradlew` from a clean checkout cannot be verified.

```bash
gradle wrapper --gradle-version 8.9      # needs Gradle 8.9 installed once; Android Studio does the same on import
git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
git commit -m "Add Gradle wrapper"
```

No Gradle locally? Push to GitHub: the CI job generates a temporary wrapper, builds everything, uploads it as the `gradle-wrapper` artifact, and then **fails on purpose** until you commit those files. Optionally pin the Gradle download with `distributionSha256Sum` in `gradle-wrapper.properties` (value from <https://gradle.org/release-checksums/>), and keep `gradle/actions/wrapper-validation` in CI, which checks the committed jar against Gradle's official releases.

Point `local.properties` at your SDK if needed: `sdk.dir=/path/to/Android/Sdk`.

## Commands

```bash
./gradlew clean :app:testDebugUnitTest   # 91 unit tests (JVM, Linux/macOS)
./gradlew :app:lintDebug                 # report: app/build/reports/lint-results-debug.html
./gradlew :app:lintDebug -PstrictLint    # same, but lint errors FAIL the build (CI uses this)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release-unsigned.apk (or app-release.apk if signed)
```

Rename outputs to `CleanForge-debug.apk` / `CleanForge-release.apk` if you like; the build uses Gradle's default names.

Unit tests use the JDK "unix" file-attribute view, so run them on Linux/macOS/WSL (or in the included CI).

## Signing a release build

```bash
keytool -genkeypair -v -keystore cleanforge-release.jks -alias cleanforge \
        -keyalg RSA -keysize 4096 -validity 10000
```

Create `keystore.properties` in the project root (git-ignored):

```
storeFile=cleanforge-release.jks
storePassword=...
keyAlias=cleanforge
keyPassword=...
```

Without it, `assembleRelease` produces an **unsigned** APK that Android will not install. Keep the keystore safe: losing it means you can never ship an update.

Verify a signed APK: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`

## Install on the phone

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then grant All-files access (and Shizuku permission if you use it). See `README.md`.

## No SDK locally? Use CI

`.github/workflows/build.yml` compiles, tests, lints (strict, non-blocking until the first clean baseline: then delete `continue-on-error`) and builds both APKs on GitHub's runners and uploads them (plus reports) as artifacts. Push the project to a GitHub repository and open the *Actions* tab. It runs the exact commands above through `./gradlew`.

## If the first build fails

Library versions were chosen without registry access (AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.27, Hilt 2.56, Compose BOM 2024.11.00, Shizuku 13.1.5). If Gradle cannot resolve one, bump it in `gradle/libs.versions.toml` (keep KSP's prefix equal to the Kotlin version).

The places most likely to need a small adjustment because they touch APIs I could not check against a compiler:

1. `core/shizuku/ShizukuFileServiceConnector.kt`: `Shizuku.UserServiceArgs` builder methods.
2. `core/shizuku/ShizukuManager.kt`: Shizuku listener signatures.
3. `di/AppModule.kt` and the `@HiltViewModel`: Hilt/KSP generation errors are verbose; fix the first one listed.
4. `ui/*`: Material 3 parameter names if you bump the Compose BOM.
5. `app/src/main/aidl/.../IFileService.aidl`: explicit transaction ids (`= 16777114`) need AGP's AIDL support enabled (`buildFeatures.aidl = true`, already set).

After it compiles, run the tests; a failing test is information about a real behaviour, not noise, because every test encodes a safety rule from `SECURITY.md`.

## Verifying on the phone (recommended before real use)

1. Create a throwaway folder in Download with some files and run a Review scan; confirm the dialog lists exactly what you ticked.
2. With Shizuku running, scan, and confirm that cache and leftover findings appear and that deleting one clears only that app's cache contents.
3. Try to break it: tick a photo in DCIM, then edit or replace the file before confirming; the report should say it was skipped.
