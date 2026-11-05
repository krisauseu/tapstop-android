#!/usr/bin/env python3
"""Metadata-only service replay. No uninstall, clear-data, preference writes or key output."""
import argparse
from pathlib import Path
import subprocess
import threading
import time
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--faults', action='store_true')
parser.add_argument('--raw', type=int, default=5)
parser.add_argument('--plus', type=int, default=3)
parser.add_argument('--settle-ms', type=int, default=0)
parser.add_argument('--provider', choices=['BOTH', 'GROQ', 'OPENAI'], default='BOTH')
parser.add_argument('--output', default='diagnostics/replay')
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
package = 'de.kf.blitztext'
def adb(*words):
    return subprocess.run(['adb', *words], capture_output=True, text=True, timeout=20).stdout.strip()

original_services = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
service = package + '/' + package + '.TextInsertService'
stop = threading.Event()
def sample():
    with (out / 'resources.txt').open('w') as file:
        while not stop.is_set():
            pid = adb('shell', 'pidof', package).split()
            if pid:
                pid = pid[0]
                status = adb('shell', 'run-as', package, 'cat', f'/proc/{pid}/status')
                fd = adb('shell', 'run-as', package, 'ls', '-l', f'/proc/{pid}/fd')
                threads = adb('shell', 'ps', '-T', '-p', pid)
                memory = adb('shell', 'dumpsys', 'meminfo', package)
                file.write(f'\nSAMPLE epoch={time.time():.3f} pid={pid}\n{status}\n{fd}\n{threads}\n{memory}\n')
                file.flush()
            stop.wait(5)

with (out / 'logcat.txt').open('w') as log:
    # -T 1 avoids clearing the user's log buffers.
    capture = subprocess.Popen(['adb', 'logcat', '-v', 'threadtime', '-T', '1', 'TapStopDiag:I', '*:S'], stdout=log)
    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    try:
        result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-e', 'latencyReplay', 'true',
            '-e', 'faultReplay', str(args.faults).lower(), '-e', 'settleMs', str(args.settle_ms), '-e', 'rawCount', str(args.raw), '-e', 'plusCount', str(args.plus), '-e', 'provider', args.provider,
            package + '.test/de.kf.blitztext.StatsInstrumentation'], capture_output=True, text=True)
        (out / 'results.txt').write_text(result.stdout + result.stderr)
        print(result.stdout)
        failed = result.returncode != 0 or 'FAILED' in result.stdout or 'PASS replay' not in result.stdout
    finally:
        stop.set()
        sampler.join(timeout=25)
        capture.terminate()
        capture.wait(timeout=10)
        if service in original_services.split(':'):
            remaining = ':'.join(x for x in original_services.split(':') if x != service)
            adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', remaining or 'null')
            adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', original_services)
print(adb('shell', 'run-as', package, 'sha256sum', 'shared_prefs/settings.xml', 'databases/usage.db'))
analysis = subprocess.run([sys.executable, str(Path(__file__).with_name('analyze-latency.py')), str(out / 'logcat.txt'),
    *(['--allow-errors'] if args.faults else [])], capture_output=True, text=True)
(out / 'analysis.txt').write_text(analysis.stdout + analysis.stderr)
print(analysis.stdout)
raise SystemExit(1 if failed or analysis.returncode else 0)
