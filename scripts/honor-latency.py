#!/usr/bin/env python3
"""HONOR collector: existing replay, serial live calls, metadata only."""
import argparse, json, os, subprocess, threading, time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--name', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--provider', default='BOTH')
p.add_argument('--raw', type=int, default=20)
p.add_argument('--manual', action='store_true')
p.add_argument('--settle', type=int, default=95000)
a = p.parse_args()
root = Path('diagnostics/honor') / a.name
root.mkdir(parents=True, exist_ok=True)
base = ['adb', '-s', a.serial]
pkg = 'de.kf.blitztext'
def adb(*args):
    r = subprocess.run(base + list(args), capture_output=True, text=True, timeout=20)
    return r.stdout.strip() + ('\nERROR ' + r.stderr.strip() if r.stderr.strip() else '')

original = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
service = pkg + '/' + pkg + '.TextInsertService'
halt = threading.Event()
def sample():
    with (root / 'resources.jsonl').open('w') as f:
        n = 0
        while not halt.is_set():
            pid = adb('shell', 'pidof', pkg).split()
            if pid and pid[0].isdigit():
                pid = pid[0]
                status = adb('shell', 'run-as', pkg, 'cat', '/proc/' + pid + '/status')
                fd = adb('shell', 'run-as', pkg, 'ls', '-l', '/proc/' + pid + '/fd')
                mem = adb('shell', 'dumpsys', 'meminfo', pkg)
                item = dict(epoch=time.time(), pid=pid,
                    status=[x for x in status.splitlines() if x.startswith(('Threads:', 'VmRSS:', 'VmSize:'))],
                    fds=sum(' -> ' in x for x in fd.splitlines()),
                    sockets=sum('socket:[' in x for x in fd.splitlines()),
                    fd_targets=[x.split(' -> ', 1)[1] for x in fd.splitlines() if ' -> ' in x],
                    memory=[x for x in mem.splitlines() if any(k in x for k in ('TOTAL PSS:', 'Java Heap:', 'Native Heap:', ' Dalvik ', 'TOTAL:', ' Heap '))])
                f.write(json.dumps(item) + '\n'); f.flush()
                if n % 5 == 0:
                    with (root / 'network-power.txt').open('a') as net:
                        net.write(f'\nepoch={time.time()} pid={pid}\n')
                        conn = adb('shell', 'dumpsys', 'connectivity')
                        net.write('\n'.join(x for x in conn.splitlines() if 'NetworkAgentInfo{' in x or 'Active default network' in x) + '\n')
                        idle = adb('shell', 'dumpsys', 'deviceidle')
                        net.write('\n'.join(x for x in idle.splitlines() if any(k in x for k in ('mState=', 'mLightState=', 'mScreenOn=', 'mCharging='))) + '\n')
                        net.write('standby=' + adb('shell', 'am', 'get-standby-bucket', pkg) + '\n')
                        net.write(adb('shell', 'dumpsys', 'activity', 'services', pkg) + '\n')
                n += 1
            halt.wait(2)

with (root / 'logcat.txt').open('w') as log:
    capture = subprocess.Popen(base + ['logcat', '-v', 'threadtime', '-T', '1', 'TapStopDiag:I', '*:S'], stdout=log)
    sampler = threading.Thread(target=sample, daemon=True); sampler.start()
    args = ['shell', 'am', 'instrument', '-w', '-e', 'latencyReplay', 'true',
        '-e', 'rawCount', str(a.raw), '-e', 'plusCount', '0', '-e', 'provider', a.provider,
        '-e', 'groqFirst', 'true', '-e', 'settleMs', str(a.settle)]
    if a.manual: args += ['-e', 'manualRawCount', '3', '-e', 'manualPlusCount', '2']
    args += [pkg + '.test/de.kf.blitztext.StatsInstrumentation']
    try:
        with (root / 'results.txt').open('w') as output:
            child = subprocess.Popen(base + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
            for line in child.stdout:
                output.write(line); output.flush()
                print(line, end='', flush=True)
            code = child.wait()
    finally:
        halt.set(); sampler.join(timeout=25)
        capture.terminate(); capture.wait(timeout=10)
        if service in original.split(':'):
            remaining = ':'.join(x for x in original.split(':') if x != service)
            adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', remaining or 'null')
            adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', original)
    (root / 'checksums-after.txt').write_text(adb('shell', 'run-as', pkg, 'sha256sum', 'shared_prefs/settings.xml', 'databases/usage.db') + '\n')
    analysis = subprocess.run(['python3', 'scripts/analyze-latency.py', str(root / 'logcat.txt')], capture_output=True, text=True)
    (root / 'analysis.txt').write_text(analysis.stdout + analysis.stderr)
    print(analysis.stdout, flush=True)
    raise SystemExit(code)
