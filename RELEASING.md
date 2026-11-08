# Releasing TapStop

## Version and prerequisites

Use JDK 17, Android SDK Platform 36, Build Tools 35.0.0 for the Gradle build, and Build Tools 36.0.0 for the checks below. Use the checked-in Gradle wrapper and keep the JDK, SDK, and tool versions in the release record. Python 3 runs the offline checks and runtime staging. Use ADB and unlocked USB-debugging devices for acceptance.

The v0.12 release target is `de.kf.tapstop`, version code `12`, version name `0.12`. Keep the application ID, namespace, and component names aligned. Check the intended release commit, a clean Git status, the version fields, and the absence of existing conflicting tags. The published v0.11 baseline points to `00f40d54d78d48b398c5662eeed42be3d108c26c`.

## Signing key

Use the same dedicated release signing key for later GitHub updates to this package. Keep a password-protected keystore outside the checkout, with an encrypted backup and a recovery copy of the password in the maintainer's password manager. Verify recovery by checking the certificate fingerprint from the backed-up key. The key created for v0.12 uses a PKCS#12 keystore with an RSA 4096-bit key and alias `tapstop-release`.

For first-time setup only, create a keystore in a private directory using `keytool -genkeypair` with `-storetype PKCS12 -keyalg RSA -keysize 4096 -validity 14600`. Let `keytool` prompt for the password. Set directory permissions to 0700 and file permissions to 0600. Never put passwords on the command line or enable shell tracing around signing.

Point `TAPSTOP_SIGNING_PROPERTIES` at an external file with these properties. Enter the real passwords locally. For a PKCS#12 keystore use the same password in both password fields.

```properties
storeFile=/absolute/private/path/tapstop-release.p12
storePassword=<keystore password>
keyAlias=tapstop-release
keyPassword=<key password>
```

The certificate is public. The key, properties file, passwords, and backups are private. Keep signing local. The test APK built with `releaseDeviceTests` uses the same certificate to run against the release APK. It is for controlled testing only. Debug builds use Android's debug certificate for development.

## Full GitHub APK

Stage the pinned VoiceAI ASR Community 2.7.1.0 and QAIRT 2.50.0.260828 bundle using the [staging instructions](tools/qualcomm-whisper/README.md). Gradle checks the exact file inventory, binary hashes, and hashes of all six notices against the checked-in manifests. For distribution, review the license conditions for the actual SDKs and distributing account. Section 1(iv) of the reviewed AI Stack agreement covers integrated object code subject to the other terms. Keep all original notices and comply with the other agreement conditions, including applicable export restrictions. The [license inventory](docs/QUALCOMM_LICENSING.md) records the SDK and model provenance and the limits on standalone redistribution.

```sh
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/android-sdk
export TAPSTOP_SIGNING_PROPERTIES=/absolute/private/path/keystore.properties
./gradlew --no-daemon -PqualcommRuntimeDir=/absolute/private/path/runtime \
  -PreleaseDeviceTests=true :app:packageTapStop :app:assembleReleaseAndroidTest \
  :app:testDebugUnitTest :app:lint
```

The output to distribute is `app/build/outputs/apk/tapstop/TapStop-Android-0.12.apk`. Use a clean checkout or a clean build for release provenance. Keep inputs, tool versions, and the resulting checksum with the release. Byte-for-byte reproducibility across hosts has yet to be established.

## Free source build

Use a clean checkout without the optional runtime property or signing environment. The resulting unsigned release uses the same app ID and version and includes OpenAI, Groq, and Gemini. The Local option and model importer are hidden. If switching from a same-signer full build with a saved Local choice, select a cloud provider explicitly before starting the bubble.

```sh
unset TAPSTOP_SIGNING_PROPERTIES
./gradlew --no-daemon :app:assembleRelease :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug :app:lintRelease
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s tools/qualcomm-whisper -p 'test_*.py'
python3 tools/qualcomm-whisper/test-verify-htp.py
```

The APK is `app/build/outputs/apk/release/app-release-unsigned.apk`. The [source CI workflow](.github/workflows/source-build.yml) checks the free build, test APK compilation, JVM tests, lint and Python tests. Its read-only GitHub token suffices. It uses no project secrets and has no artifact upload. Keep proprietary staging and release signing out of that workflow.

## Verify the release artifact

```sh
bt="$ANDROID_HOME/build-tools/36.0.0"
cd app/build/outputs/apk/tapstop
apk=TapStop-Android-0.12.apk
"$bt/apksigner" verify --verbose --print-certs "$apk"
"$bt/zipalign" -c -P 16 4 "$apk"
"$bt/aapt" dump badging "$apk"
"$bt/aapt" dump xmltree "$apk" AndroidManifest.xml
unzip -Z1 "$apk"
shasum -a 256 "$apk" > "$apk.sha256"
shasum -a 256 -c "$apk.sha256"
```

Confirm the certificate SHA-256 against the recorded release fingerprint. Confirm `de.kf.tapstop`, version code 12, version name 0.12, minSdk 29, targetSdk 35, a non-debuggable APK, and the expected launcher, microphone foreground service, and accessibility service. The accessibility service must require `android.permission.BIND_ACCESSIBILITY_SERVICE` and point to the service XML. Check that backup remains disabled.

The full APK must have both V75 and V81 stubs and skeletons. Compare decompressed native libraries and notices to the SHA-256 pins in [runtime-files.json](tools/qualcomm-whisper/runtime-files.json) and [runtime-notices.json](tools/qualcomm-whisper/runtime-notices.json). The SDK JAR is compiled to DEX. Check for its SDK classes there. Confirm both project notices in `assets/notices/`. Inspect the archive for model packages, signing material, test fixtures, or other unexpected files. For the free APK, confirm the absence of the SDK classes, native runtime, skeletons, and Qualcomm notices.

## Package migration from v0.11

Android installs v0.12 alongside `de.kf.blitztext`. Before installing, identify the device using `adb devices -l` and confirm its model and SoC. Record only presence and status of legacy data and configuration. Use `adb -s SERIAL install -r PATH_TO_APK` with the verified artifact and the explicitly identified serial.

| Data | Handling |
| --- | --- |
| Settings, provider and model selection, mode and bubble position | Recreate in v0.12. The `settings` preference name and existing stored values such as `BLITZTEXT` for Roh remain valid within their respective installs. |
| API keys | Re-enter from their original source. Android Keystore AES-GCM keys are package-scoped, even when the alias `blitztext_api_key` matches. Do not export decrypted keys or copy the entire preference file. |
| Personal dictionary | Copy entries manually from the old settings into the new dictionary editor. |
| Statistics | Keep the old `usage.db` and its history in the old app. v0.12 starts a new database with schema version 1. |
| Private configuration and diagnostics | Preserve them in the old installation. Android backup and device-transfer extraction are disabled. |
| Qualcomm model packages | Reimport a validated original folder for the detected target. Allow disk space for another model copy. TapStop rechecks target and hashes. |
| Android grants and service | Grant microphone access and enable the new `de.kf.tapstop/de.kf.tapstop.TextInsertService`. The older service entry can coexist. The notification channel ID remains `blitztext` within the new app. |

There is no automatic migration. Keep the older installation and its data until the new version passes device checks and the user explicitly approves removal. Stop an old running bubble from its own settings if it interferes with testing.

## Device tests

Install `app/build/outputs/apk/androidTest/release/app-release-androidTest.apk` on the test device alongside the matching signed release. Enable the new accessibility service on the test device and run the checks with the correct serial. On a fresh install use the flag below. An already configured installation can run without it to check that the selected provider's key still decrypts.

```sh
python3 scripts/device-tests.py --serial SERIAL --fresh-install
```

The fixture uses an isolated temporary database and synthetic editor text. It tests native insertion and the clipboard fallback and temporarily rebinds only the new accessibility service. Use the Chrome checks in [INSERT_DEVICE_TESTS.md](INSERT_DEVICE_TESTS.md) with the updated scripts for broader insertion coverage. ADB tools that read private files via `run-as` require a debug build. Run device smoke tests on the signed artifact itself.

On the Galaxy S24 Ultra (SM8650/V75) and HONOR MagicPad 4 (SM8845/SM8845P/V81), check app launch, provider settings, microphone, accessibility connection, overlay, model import, and statistics. Dictate in Roh with Local and in Plus with a configured rewrite provider. For an HTP acceptance claim, capture the same CDSP domain-3 handle in both encoder and decoder execution for each test session. Use the historical evidence procedure in [DEVICE_TESTS.md](DEVICE_TESTS.md). Lower release log verbosity can limit evidence from the signed APK.

## GitHub publication

Check that the distributing maintainer can apply Apache 2.0 to the original project code. The Git history attributes all five baseline commits to `krisauseu`. That identifies the recorded author, while ownership of any contributions originating outside Git still requires maintainer confirmation. The LICENSE file contains the canonical Apache text. Its appendix is the Apache license's template, and no owner or year has been guessed. Preserve the separate Blitztext MIT attribution and all third-party terms.

After rights, tests, APK verification, and both device acceptance checks are complete, commit the release sources, build from that exact commit, and tag it with `git tag -a v0.12 -m "TapStop Android 0.12"`. Push the intended commit and tag. Create a draft GitHub Release for `v0.12`, attach the verified APK and `.sha256` file, verify the uploaded checksum, and publish after review. Keep the instrumentation APK, model packages, and standalone SDK files out of the release.

Suggested release notes cover the new package and durable release signature, parallel installation and manual data setup, the English and German documentation, the scope of the two validated Qualcomm targets and 30-second limit, and the availability of a separate free source build for future F-Droid work.

## Future F-Droid integration

Use the unsigned free build as the source recipe and let F-Droid review the build inputs, dependencies, and signing strategy. The APK has no bundled Qualcomm component. Its speech recognition requires one of three external, non-free services. Disclose the user-supplied key and network use and discuss the `NonFreeNet` anti-feature with F-Droid. The two optional local diagnostics are off by default and have no telemetry path. [F-Droid Anti-Features](https://f-droid.org/en/docs/Anti-Features/)

A separately signed F-Droid APK with the same application ID cannot update the GitHub APK directly. Establish the distribution/signing policy before submission. Check the upstream acceptance criteria and reproducible-build tooling then. [F-Droid inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/) No official submission has been made.
