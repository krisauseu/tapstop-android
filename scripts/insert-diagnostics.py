#!/usr/bin/env python3
"""Read TapStop's bounded metadata file via ADB; never read text, keys or preferences."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import statistics
import subprocess

PACKAGE = 'de.kf.tapstop'
DEVICE_FILE = 'no_backup/insert-diagnostics.jsonl'
IC_OUTCOMES = {'DIRECT_INPUT_CONNECTION_SUCCESS', 'DIRECT_INPUT_CONNECTION_ACCEPTED_UNVERIFIED'}


def distribution(rows, field):
    values = [row[field] for row in rows if isinstance(row.get(field), (int, float))]
    return {'n': len(values), 'min': min(values) if values else None,
            'median': statistics.median(values) if values else None,
            'max': max(values) if values else None}


def summarize(rows):
    clipboard = [r for r in rows if r.get('outcome') == 'CLIPBOARD']
    success = [r for r in rows if r.get('outcome') in IC_OUTCOMES | {'DIRECT_ACCESSIBILITY_SUCCESS'}]
    def comparison(group):
        return {key: distribution(group, key) for key in ('text_chars', 'recording_ms')}
    return {
        'retained_insert_attempts': len(rows),
        'input_connection_successes': sum(r.get('outcome') in IC_OUTCOMES for r in rows),
        'input_connection_accepted_unverified': sum(r.get('outcome') == 'DIRECT_INPUT_CONNECTION_ACCEPTED_UNVERIFIED' for r in rows),
        'accessibility_successes': sum(r.get('outcome') == 'DIRECT_ACCESSIBILITY_SUCCESS' for r in rows),
        'clipboard_fallbacks': len(clipboard),
        'outcomes': dict(Counter(r.get('outcome', 'UNKNOWN') for r in rows)),
        'clipboard_reasons': dict(Counter(r.get('clipboard_reason') or 'UNKNOWN' for r in clipboard)),
        'all_target_packages': dict(Counter(r.get('target_package') or 'UNKNOWN' for r in rows)),
        'clipboard_target_packages': dict(Counter(r.get('target_package') or 'UNKNOWN' for r in clipboard)),
        'comparison': {'direct_success': comparison(success), 'clipboard': comparison(clipboard)},
        'first_timestamp_ms': min((r['timestamp_ms'] for r in rows), default=None),
        'last_timestamp_ms': max((r['timestamp_ms'] for r in rows), default=None),
        'clipboard_cases': [{k: r.get(k) for k in ('timestamp_ms', 'target_package', 'text_chars', 'recording_ms',
            'mode', 'stt_provider', 'stt_location', 'stt_model', 'clipboard_reason', 'steps')} for r in clipboard],
        'scope': 'Retained records only (1024 entries / 1 MiB; 7-day pruning on write). No causal inference.'
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--file', type=Path, help='Analyze an already exported JSONL file instead of ADB')
    parser.add_argument('--serial', help='ADB device serial when multiple devices are connected')
    parser.add_argument('--output', type=Path, help='Save the metadata-only JSONL snapshot here')
    args = parser.parse_args()
    if args.file:
        content = args.file.read_text(encoding='utf-8')
    else:
        inventory = subprocess.check_output(['adb', 'devices', '-l'], text=True)
        devices = [line.split()[0] for line in inventory.splitlines()[1:] if len(line.split()) > 1 and line.split()[1] == 'device']
        if not args.serial and len(devices) != 1:
            raise SystemExit('Explicit --serial required; connected devices: ' + ', '.join(devices))
        serial = args.serial or devices[0]
        if serial not in devices:
            raise SystemExit('Selected serial is unavailable')
        adb = ['adb', '-s', serial]
        result = subprocess.run(adb + ['exec-out', 'run-as', PACKAGE, 'cat', DEVICE_FILE],
                                capture_output=True, timeout=30)
        if result.returncode:
            raise SystemExit('Diagnosis unavailable: check ADB, debug APK and whether a dictation has finished.')
        content = result.stdout.decode('utf-8')
    rows = [json.loads(line) for line in content.splitlines() if line.strip()]
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding='utf-8')
    report = summarize(rows)
    report['exported_at_utc'] = datetime.now(timezone.utc).isoformat()
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
