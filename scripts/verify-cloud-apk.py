#!/usr/bin/env python3
"""Verify a signed TapStop GitHub APK before uploading it."""
import argparse
import hashlib
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

CERT = "858e783c20cd77e9eb5f5c8bbde096aa152752dc5c92d440aba98c677b3b077e"
ASSETS = {
    "assets/dexopt/baseline.prof",
    "assets/dexopt/baseline.profm",
    "assets/notices/LICENSE",
    "assets/notices/THIRD_PARTY_LICENSE_BLITZTEXT_MAC.txt",
}
LIBRARIES = {
    f"lib/{abi}/libandroidx.graphics.path.so"
    for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
}
LOCAL_DEX = re.compile(
    rb"L(?:com/(?:qualcomm|qti)/|de/kf/tapstop/(?:Qualcomm|ComposableSingletons\$Qualcomm|AndroidQualcomm|ReflectiveVoiceAi|"
    rb"NativeWhisper|LocalInspection|LocalTranscript|SafModelSource|PcmAudioRecording|PcmWav))"
)


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("version")
    parser.add_argument("commit")
    parser.add_argument("build_tools", type=Path)
    args = parser.parse_args()
    apk = args.apk.resolve()
    code = int(args.version.split(".")[1])

    def run(tool, *command):
        return subprocess.check_output([args.build_tools / tool, *command], text=True)

    signing = run("apksigner", "verify", "--verbose", "--print-certs", apk)
    require(re.findall(r"certificate SHA-256 digest: (\w+)", signing) == [CERT], "Unexpected signing certificate")
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in signing, "Missing v2 signature")
    run("zipalign", "-c", "-P", "16", "4", apk)
    badging = run("aapt", "dump", "badging", apk)
    require(f"name='de.kf.tapstop' versionCode='{code}' versionName='{args.version}'" in badging, "Unexpected APK version")
    require("sdkVersion:'29'" in badging and "targetSdkVersion:'35'" in badging, "Unexpected SDK versions")
    require("application-debuggable" not in badging, "APK is debuggable")
    manifest = run("aapt", "dump", "xmltree", apk, "AndroidManifest.xml")
    require("uses-native-library" not in manifest, "Unexpected native system library declaration")
    require("launchable-activity: name='de.kf.tapstop.MainActivity'" in badging, "Missing launcher")
    require("de.kf.tapstop.OverlayService" in manifest and "foregroundServiceType(0x01010599)=(type 0x11)0x80" in manifest,
            "Missing microphone foreground service")
    require("de.kf.tapstop.TextInsertService" in manifest and "android.permission.BIND_ACCESSIBILITY_SERVICE" in manifest,
            "Missing protected accessibility service")
    require("allowBackup(0x01010280)=(type 0x12)0x0" in manifest, "Backup must be disabled")

    with ZipFile(apk) as archive:
        names = set(archive.namelist())
        require(len(names) == len(archive.namelist()), "Duplicate APK entries")
        require({n for n in names if n.startswith("assets/")} == ASSETS, "Unexpected asset inventory")
        require({n for n in names if n.startswith("lib/")} == LIBRARIES, "Unexpected native library inventory")
        require(not any(re.search(r"qualcomm|qairt|skeleton|\.(?:jar|pdf|p12|jks|keystore)$", n, re.I)
                        for n in names), "Unexpected APK payload")
        for notice in (ASSETS - {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}):
            expected = Path(__file__).resolve().parent.parent / Path(notice).name
            require(archive.read(notice) == expected.read_bytes(), f"Unexpected project notice: {notice}")
        provenance = archive.read("META-INF/version-control-info.textproto").decode()
        require(re.findall(r'revision: "([0-9a-f]+)"', provenance) == [args.commit], "APK commit differs")
        dex_files = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
        require(dex_files, "APK contains no DEX")
        for name in dex_files:
            dex = archive.read(name)
            require(not LOCAL_DEX.search(dex), f"Local inference class or reference in {name}")
            require(not re.search(rb"com[./](?:qualcomm|qti)[./]|libQnn|lib[^\x00 ]*Skel\.so", dex, re.I),
                    f"Qualcomm SDK reference in {name}")
    print(f"{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}")


if __name__ == "__main__":
    main()
