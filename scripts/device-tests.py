#!/usr/bin/env python3
"""Run the separate instrumentation APK; preserve all enabled accessibility services."""
import subprocess


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True).strip()


original = adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
service = "de.kf.blitztext/de.kf.blitztext.TextInsertService"
if service not in original.split(":"):
    raise SystemExit("Enable TapStop accessibility before running this test.")
process = subprocess.Popen([
    "adb", "shell", "am", "instrument", "-w",
    "de.kf.blitztext.test/de.kf.blitztext.StatsInstrumentation"
], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
output = []
try:
    for line in process.stdout:
        print(line, end="", flush=True)
        output.append(line)
        if "READY_ACCESSIBILITY" in line:
            remaining = ":".join(x for x in original.split(":") if x != service)
            adb("shell", "settings", "put", "secure", "enabled_accessibility_services", remaining or "null")
            adb("shell", "settings", "put", "secure", "enabled_accessibility_services", original)
    process.wait()
finally:
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services", original)
if process.returncode or "FAILED" in "".join(output) or "PASS: Original deliver" not in "".join(output):
    raise SystemExit(1)
