#!/usr/bin/env python3
"""Export private temporary text diagnostics from one explicitly selected device. No network.
The summary never prints transcripts. The optional JSONL export contains sensitive texts.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile

PACKAGE = 'de.kf.blitztext'
DIRECTORY = 'no_backup/insert-delayed-diagnostics'


def select_device(serial=None):
    result = subprocess.run(['adb', 'devices', '-l'], capture_output=True, text=True, check=True)
    devices = [line.split()[0] for line in result.stdout.splitlines()[1:]
               if len(line.split()) > 1 and line.split()[1] == 'device']
    if serial is None:
        if len(devices) != 1:
            raise SystemExit('Select a device explicitly with --serial SERIAL. Available: ' + ', '.join(devices))
        serial = devices[0]
    if serial not in devices:
        raise SystemExit('Selected device is not connected/authorized: ' + serial)
    # Even with a single device, subsequent commands always use -s.
    model = subprocess.check_output(['adb', '-s', serial, 'shell', 'getprop', 'ro.product.model'], text=True).strip()
    device_serial = subprocess.check_output(['adb', '-s', serial, 'shell', 'getprop', 'ro.serialno'], text=True).strip()
    if device_serial != serial:
        raise SystemExit('Device serial mismatch; refusing read.')
    return serial, model


def parse(content):
    rows, invalid = [], 0
    for line in content.splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            if not isinstance(row, dict) or row.get('schema') != 2:
                raise ValueError('Unknown schema')
            rows.append(row)
        except (ValueError, TypeError):
            invalid += 1
    return rows, invalid


def summarize(rows):
    inserts = [r for r in rows if r.get('event') == 'INSERT_RESULT']
    by_id = defaultdict(list)
    for row in rows:
        by_id[row.get('insert_id')].append(row)
    delayed = [r for r in rows if r.get('event') == 'DELAYED_READBACK']
    markers = [r for r in rows if r.get('event') == 'MANUAL_ERROR_MARKER']
    failures = [r for r in delayed if r.get('status') == 'DELAYED_TEXT_MISMATCH']
    report = {
        'retained_insert_results': len(inserts),
        'retained_insert_begins': sum(r.get('event') == 'INSERT_BEGIN' for r in rows),
        'input_connection_successes': sum(r.get('outcome') == 'DIRECT_INPUT_CONNECTION_SUCCESS' for r in inserts),
        'input_connection_accepted_unverified': sum(r.get('outcome') == 'DIRECT_INPUT_CONNECTION_ACCEPTED_UNVERIFIED' for r in inserts),
        'accessibility_successes': sum(r.get('outcome') == 'DIRECT_ACCESSIBILITY_SUCCESS' for r in inserts),
        'clipboard_fallbacks': sum(r.get('outcome') == 'CLIPBOARD' for r in inserts),
        'immediate_confirmed': sum(r.get('event') == 'IMMEDIATE_READBACK' and r.get('status') == 'IMMEDIATE_CONFIRMED' for r in rows),
        'delayed_by_scheduled_ms': {str(ms): dict(Counter(r.get('status', 'UNKNOWN') for r in delayed if r.get('scheduled_ms') == ms)) for ms in (250, 1000, 3000)},
        'unverifiable_reasons': dict(Counter(r.get('reason', 'UNKNOWN') for r in delayed if r.get('status') != 'DELAYED_CONFIRMED' and r.get('status') != 'DELAYED_TEXT_MISMATCH')),
        'target_apps': dict(Counter(r.get('target_package') or 'UNKNOWN' for r in inserts)),
        'text_length_utf16': dict(Counter('0-100' if r.get('text_chars', 0) <= 100 else '101-500' if r['text_chars'] <= 500 else '501-2000' if r['text_chars'] <= 2000 else '>2000' for r in inserts)),
        'observed_differences': [{key: r.get(key) for key in ('insert_id', 'timestamp_ms', 'scheduled_ms', 'read_started_since_commit_ms', 'read_finished_since_commit_ms', 'first_difference_utf16', 'checked_chars', 'text_chars', 'reason')} for r in failures],
        'manual_markers': [{**{key: r.get(key) for key in ('timestamp_ms', 'device', 'associated_insert_id', 'target_package', 'association', 'age_since_insert_ms')},
                            'associated_records_retained': r.get('associated_insert_id') in by_id and any(x.get('event') == 'INSERT_RESULT' for x in by_id[r.get('associated_insert_id')])} for r in markers],
        'proven_tapstop_text_losses': 0,
        'scope': 'Retained events only; partial sessions/queue drops/process death possible. Differences have unknown author (manual/IME/app); no causal proof. Readbacks are editor windows, not guaranteed whole documents.'
    }
    report['missing_delayed_events'] = sum(max(0, 3 - sum(r.get('event') == 'DELAYED_READBACK' for r in by_id[insert.get('insert_id')])) for insert in inserts)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial')
    parser.add_argument('--file', type=Path)
    parser.add_argument('--output', type=Path, help='New private JSONL file (0600); never overwrites an existing export')
    parser.add_argument('--json', action='store_true', help='Print metadata summary as JSON')
    args = parser.parse_args()
    identity = {}
    if args.file:
        content = args.file.read_text(encoding='utf-8')
    else:
        serial, model = select_device(args.serial)
        identity = dict(serial=serial, model=model)
        payload = subprocess.run(['adb', '-s', serial, 'exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', DIRECTORY],
                                 capture_output=True, timeout=45)
        if payload.returncode:
            raise SystemExit('No delayed diagnosis available; enable it and complete a dictation first.')
        with tarfile.open(fileobj=io.BytesIO(payload.stdout), mode='r:') as archive:
            members = sorted((m for m in archive.getmembers() if m.isfile() and m.name.startswith(DIRECTORY + '/events-') and m.name.endswith('.jsonl')), key=lambda m: m.name)
            content = ''.join(archive.extractfile(m).read().decode('utf-8') for m in members)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as output:
            output.write(content)
        print('Private JSONL export: ' + str(args.output.resolve()))
    rows, invalid = parse(content)
    report = summarize(rows)
    report.update(identity, invalid_records=invalid, exported_at_utc=datetime.now(timezone.utc).isoformat())
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"Einfügungen (gespeichert): {report['retained_insert_results']}; InputConnection: {report['input_connection_successes']}; Accessibility: {report['accessibility_successes']}; Clipboard: {report['clipboard_fallbacks']}")
        print(f"Unmittelbar bestätigt: {report['immediate_confirmed']}; fehlende Nachkontrollen: {report['missing_delayed_events']}")
        for ms, states in report['delayed_by_scheduled_ms'].items():
            print(f'{ms} ms: {states}')
        print(f"Beobachtete Zeichenabweichungen: {len(report['observed_differences'])} (Urheber unbekannt, kein Nachweis eines TapStop-Fehlers)")
        print(f"Nicht überprüfbar: {report['unverifiable_reasons']}")
        print(f"Ziel-Apps: {report['target_apps']}; Textlängen: {report['text_length_utf16']}")
        for marker in report['manual_markers']:
            print('Marker: ' + json.dumps(marker, ensure_ascii=False))
        print(f'Ungültige Datensätze: {invalid}. Nur noch gespeicherte Ereignisse; --json für Details.')


if __name__ == '__main__':
    main()
