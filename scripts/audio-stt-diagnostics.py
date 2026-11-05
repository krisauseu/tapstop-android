#!/usr/bin/env python3
"""Read-only, explicit-device export of private STT audio and linked text evidence. No uploads."""
import argparse
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
from zoneinfo import ZoneInfo

PACKAGE = 'de.kf.blitztext'
ROOT = 'no_backup/audio-stt-diagnostics'
UUID = r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}'


def adb(serial, *args):
    result = subprocess.run(['adb', '-s', serial, *args], capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError('ADB operation failed (private stderr suppressed)')
    return result.stdout


def identify(serial, expected_model):
    model = adb(serial, 'shell', 'getprop', 'ro.product.model').decode().strip()
    actual = adb(serial, 'shell', 'getprop', 'ro.serialno').decode().strip()
    if model != expected_model or actual != serial:
        raise ValueError('Device identity mismatch')
    return model


def unpack(payload):
    files = {}
    with tarfile.open(fileobj=io.BytesIO(payload), mode='r:') as archive:
        for member in archive.getmembers():
            name = member.name
            path = PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts or member.issym() or member.islnk():
                raise ValueError('Unsafe archive member')
            if not member.isfile():
                continue
            allowed = (re.fullmatch(ROOT + '/' + UUID + r'/(case\.json|input\.(m4a|wav))', name)
                       or re.fullmatch(r'no_backup/insert-delayed-diagnostics/events-[0-9]+\.jsonl', name)
                       or name == 'no_backup/insert-diagnostics.jsonl')
            if allowed:
                if name in files:
                    raise ValueError('Duplicate archive member')
                files[name] = archive.extractfile(member).read()
    return files


def rows(files):
    for name, content in files.items():
        if name.endswith('.jsonl'):
            for line in content.splitlines():
                if line.strip():
                    yield json.loads(line)


def select(files, date=None, insert_id=None, diagnostic_id=None, marked=False):
    result = []
    for name, content in sorted(files.items()):
        if not name.startswith(ROOT + '/') or not name.endswith('/case.json'):
            continue
        record = json.loads(content)
        case_id = name.split('/')[-2]
        if record.get('schema') != 1 or record.get('diagnostic_id') != case_id:
            raise ValueError('Invalid case manifest')
        if date and datetime.fromtimestamp(record['timestamp_ms'] / 1000, ZoneInfo('Europe/Berlin')).date().isoformat() != date:
            continue
        if insert_id and record.get('insert_id') != insert_id:
            continue
        if diagnostic_id and case_id != diagnostic_id:
            continue
        if marked and not record.get('marked'):
            continue
        result.append((name, record))
    return result


def private_write(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
        output.write(data)


def export(files, selected, output, identity):
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    evidence = list(rows(files))
    checksums = {}
    for name, record in selected:
        case_id = record['diagnostic_id']
        target = output / case_id
        target.mkdir(mode=0o700)
        private_write(target / 'case.json', files[name])
        checksums[f'{case_id}/case.json'] = hashlib.sha256(files[name]).hexdigest()
        audio_name = record.get('audio_file')
        if audio_name:
            if audio_name not in ('input.m4a', 'input.wav'):
                raise ValueError('Invalid audio name')
            content = files.get(str(PurePosixPath(name).parent / audio_name))
            if content is None or len(content) != record['audio_bytes']:
                raise ValueError('Missing or changed audio; retry while app is idle')
            digest = hashlib.sha256(content).hexdigest()
            if digest != record['audio_sha256']:
                raise ValueError('Audio integrity mismatch')
            upload = [e for e in record['events'] if e['event'] == 'STT_UPLOAD_FILE']
            if any(e['sha256'] != digest or e['bytes'] != len(content) for e in upload):
                raise ValueError('STT upload and audio copy differ')
            private_write(target / audio_name, content)
            checksums[f'{case_id}/{audio_name}'] = digest
        insert = record.get('insert_id')
        linked = [r for r in evidence if (insert and (r.get('insert_id') == insert or r.get('associated_insert_id') == insert))
                  or r.get('diagnostic_id') == case_id]
        data = ''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in linked).encode()
        private_write(target / 'insert-events.jsonl', data)
        checksums[f'{case_id}/insert-events.jsonl'] = hashlib.sha256(data).hexdigest()
        # A convenient local comparison file, never printed or uploaded.
        text = '\n\n'.join(e['event'] + '\n' + e.get('raw_transcript', e.get('final_text', ''))
                           for e in record['events'] if e['event'] in ('STT_RESULT', 'PROCESSED_TEXT'))
        private_write(target / 'transcripts.txt', text.encode())
        checksums[f'{case_id}/transcripts.txt'] = hashlib.sha256(text.encode()).hexdigest()
    private_write(output / 'sha256.json', json.dumps(checksums, indent=2).encode())
    private_write(output / 'export.json', json.dumps(identity, indent=2).encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--model', required=True, help='Expected Android product model')
    parser.add_argument('--date', help='YYYY-MM-DD, Europe/Berlin')
    parser.add_argument('--insert-id')
    parser.add_argument('--diagnostic-id')
    parser.add_argument('--marked', action='store_true')
    parser.add_argument('--screenshot-time', help='User-supplied ISO timestamp with UTC offset; local sidecar only, exactly one selected case')
    parser.add_argument('--output', type=Path, help='New directory; without it list metadata only')
    args = parser.parse_args()
    model = identify(args.serial, args.model)
    # Probe individually: missing optional legacy folders must not turn a valid audio export into an error.
    paths = [ROOT]
    for path in ('no_backup/insert-delayed-diagnostics', 'no_backup/insert-diagnostics.jsonl'):
        result = subprocess.run(['adb', '-s', args.serial, 'shell', 'run-as', PACKAGE, 'test', '-e', path], capture_output=True)
        if result.returncode == 0:
            paths.append(path)
    files = unpack(adb(args.serial, 'exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', *paths))
    selected = select(files, args.date, args.insert_id, args.diagnostic_id, args.marked)
    identity = dict(serial=args.serial, model=model)
    if args.screenshot_time:
        timestamp = datetime.fromisoformat(args.screenshot_time)
        if timestamp.tzinfo is None or len(selected) != 1 or not args.output:
            raise ValueError('Screenshot annotation requires one selected case, output and explicit timezone')
        identity['user_supplied_screenshot'] = dict(diagnostic_id=selected[0][1]['diagnostic_id'], timestamp=timestamp.isoformat(), association='USER_SUPPLIED_UNCONFIRMED')
    if args.output:
        export(files, selected, args.output, identity)
    print(json.dumps(dict(serial=args.serial, model=model, cases=[dict(diagnostic_id=r['diagnostic_id'],
        insert_id=r.get('insert_id'), timestamp_ms=r['timestamp_ms'], marked=r['marked'], audio_bytes=r.get('audio_bytes'),
        upload_hash_available=any(e['event'] == 'STT_UPLOAD_FILE' for e in r['events'])) for _, r in selected]), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, tarfile.TarError, json.JSONDecodeError):
        raise SystemExit('Export failed; private contents suppressed. Check device, idle state and available diagnosis.')
