# Releasing TapStop

## Version and prerequisites

Use JDK 17, Android SDK Platform 36, Build Tools 35.0.0 for the Gradle build, and Build Tools 36.0.0 for the checks below. Use the checked-in Gradle wrapper and keep the JDK, SDK, and tool versions in the release record. Python 3 runs the offline checks and runtime staging. Use ADB and unlocked USB-debugging devices for acceptance.

Both v0.12 and v0.13 use `de.kf.tapstop`. Their version codes are `12` and `13` respectively. Release the v0.12 package transition first, then publish v0.13 as an in-place update signed with the same certificate. Keep the application ID, namespace, and component names aligned. Check the intended release commit, a clean Git status, the version fields, and the absence of existing conflicting tags. The published v0.11 baseline points to `00f40d54d78d48b398c5662eeed42be3d108c26c`.

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

## Public GitHub APK

The default release uses OpenAI, Groq, and Gemini. It excludes the internal SDK, on-device inference and model importer. With no signing environment, `assembleRelease` produces an unsigned Cloud APK. The source CI workflow uses that path. For public distribution, build with the dedicated release key from a clean checkout at the intended commit.

```sh
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/android-sdk
export TAPSTOP_SIGNING_PROPERTIES=/absolute/private/path/keystore.properties
./gradlew --no-daemon -PreleaseDeviceTests=true :app:packageTapStop \
  :app:assembleReleaseAndroidTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s tools/qualcomm-whisper -p 'test_*.py'
python3 tools/qualcomm-whisper/test-verify-htp.py
```

The public packaging task requires the signing configuration. Keep the instrumentation APK local. The Qualcomm tools' Python tests cover the retained internal source. Device acceptance for the public APK uses cloud transcription.

## Internal development build

Stage the pinned SDK bundle using the internal tooling and use `-PqualcommRuntimeDir=/absolute/private/path/runtime :app:assembleDebug` with `TAPSTOP_SIGNING_PROPERTIES` unset. This enables experimental Local STT and model import. Keep the staged runtime, SDK notices, model files, and resulting APKs in the internal development environment. [Internal runtime staging](tools/qualcomm-whisper/README.md)

## Verify the release artifact

Run from the release checkout and substitute `0.12` or `0.13`.

```sh
version=0.13
bt="$ANDROID_HOME/build-tools/36.0.0"
out=app/build/outputs/apk/tapstop
apk="TapStop-Android-$version.apk"
commit="$(git rev-parse HEAD)"
python3 scripts/verify-cloud-apk.py "$out/$apk" "$version" "$commit" "$bt" > "$out/$apk.sha256"
(cd "$out" && shasum -a 256 -c "$apk.sha256")
"$bt/aapt" dump xmltree "$out/$apk" AndroidManifest.xml
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

The verifier checks the release certificate, v2 signature, ZIP alignment, application ID, SDK and version fields, source commit, APK contents, and DEX. Review the manifest for the expected launcher, microphone foreground service, accessibility service protected by `android.permission.BIND_ACCESSIBILITY_SERVICE`, and disabled backup. The public release excludes the encrypted Google Play dependency block.

## Package migration from v0.11

Android installs v0.12 alongside `de.kf.blitztext`. Before installing, identify the device using `adb devices -l` and confirm its model and SoC. Record only presence and status of legacy data and configuration. Use `adb -s SERIAL install -r PATH_TO_APK` with the verified artifact and the explicitly identified serial.

| Data | Handling |
| --- | --- |
| Settings, provider and model selection, mode and bubble position | Recreate in v0.12. The `settings` preference name and existing stored values such as `BLITZTEXT` for Roh remain valid within their respective installs. |
| API keys | Re-enter from their original source. Android Keystore AES-GCM keys are package-scoped, even when the alias `blitztext_api_key` matches. Do not export decrypted keys or copy the entire preference file. |
| Personal dictionary | Copy entries manually from the old settings into the new dictionary editor. |
| Statistics | Keep the old `usage.db` and its history in the old app. v0.12 starts a new database with schema version 1. |
| Private configuration and diagnostics | Preserve them in the old installation. Android backup and device-transfer extraction are disabled. |
| Android grants and service | Grant microphone access and enable the new `de.kf.tapstop/de.kf.tapstop.TextInsertService`. The older service entry can coexist. The notification channel ID remains `blitztext` within the new app. |

There is no automatic migration. Keep the older installation and its data until the new version passes device checks and the user explicitly approves removal. Stop an old running bubble from its own settings if it interferes with testing.

## Device tests

Install `app/build/outputs/apk/androidTest/release/app-release-androidTest.apk` on the test device alongside the matching signed release. Enable the new accessibility service on the test device and run the checks with the correct serial. On a fresh install use the flag below. An already configured installation can run without it to check that the selected provider's key still decrypts.

```sh
python3 scripts/device-tests.py --serial SERIAL --fresh-install
```

The fixture uses an isolated temporary database and synthetic editor text. It tests native insertion and the clipboard fallback and temporarily rebinds only the new accessibility service. Use the Chrome checks in [INSERT_DEVICE_TESTS.md](INSERT_DEVICE_TESTS.md) with the updated scripts for broader insertion coverage. ADB tools that read private files via `run-as` require a debug build. Run device smoke tests on the signed artifact itself.

On an isolated test device, install v0.12 first and then use `adb -s SERIAL install -r` for v0.13. Verify the installed APK hashes and compare settings, decryptable keys, dictionary, and schema 1 statistics after the update. Exercise the legacy Local choice on an isolated test profile. The app should wait for an explicit cloud provider selection and preserve the rest of the profile.

Check the three providers in settings, microphone, overlay, the four modes, insertion and clipboard fallback, and statistics. Use a synthetic recording for live provider checks with existing credentials. Exercise Groq or OpenAI rewriting in Plus, Chat, and Formal and ensure Roh skips rewriting. For v0.13, check English, German, and the system default through Android’s app language settings where supported.

## GitHub publication

Publish v0.12 first and confirm its public downloads. Then repeat for v0.13. Each tag points at the exact clean commit recorded in its APK. Keep existing tags and releases intact.

Create an annotated tag, push the commit and the new tag, and create a draft GitHub Release using the version's release notes. Attach the verified APK and its `.sha256` file. Verify the uploaded asset digest before publishing, then check the public download and checksum. Include the package migration from v0.11, supported cloud providers, and known TalkBack limitation in the release notes. Keep signing files and device-test artifacts out of releases.

Historical verification reports describe earlier candidates. Use the signed Cloud APKs and their own acceptance evidence for publication.

## Future F-Droid integration

Use the unsigned free build as the source recipe and let F-Droid review the build inputs, dependencies, and signing strategy. The APK has no bundled Qualcomm component. Its speech recognition requires one of three external, non-free services. Disclose the user-supplied key and network use and discuss the `NonFreeNet` anti-feature with F-Droid. The two optional local diagnostics are off by default and have no telemetry path. [F-Droid Anti-Features](https://f-droid.org/en/docs/Anti-Features/)

A separately signed F-Droid APK with the same application ID cannot update the GitHub APK directly. Establish the distribution/signing policy before submission. Check the upstream acceptance criteria and reproducible-build tooling then. [F-Droid inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/) No official submission has been made.
