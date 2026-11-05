#!/usr/bin/env python3
"""Fetch private TapStop text traces via ADB, or summarize an exported JSONL file.

Text stays in the output file; terminal output contains only metadata and lengths.
"""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
from collections import defaultdict
from datetime import datetime


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial')
    parser.add_argument('--from-file', type=Path)
    parser.add_argument('--output', type=Path, default=Path('diagnostics/transcripts') / (datetime.now().strftime('%Y%m%d-%H%M%S') + '.jsonl'))
    args = parser.parse_args()
    if args.from_file:
        payload = args.from_file.read_bytes()
    else:
        if not args.serial:
            parser.error('--serial is required when reading from a device')
        result = subprocess.run(['adb', '-s', args.serial, 'exec-out', 'run-as', 'de.kf.blitztext',
                                 'tar', '-cf', '-', 'no_backup/transcript-diagnostics'], capture_output=True, timeout=30)
        if result.returncode:
            raise SystemExit('No diagnostic archive available. Enable logging in TapStop and dictate first.')
        with tarfile.open(fileobj=io.BytesIO(result.stdout), mode='r:') as archive:
            files = sorted((entry for entry in archive.getmembers() if entry.isfile() and
                            entry.name.startswith('no_backup/transcript-diagnostics/') and entry.name.endswith('.jsonl')),
                           key=lambda entry: entry.name)
            payload = b''.join(archive.extractfile(entry).read() for entry in files)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            output.write(payload)
        print(f'Saved private transcript capture: {args.output.resolve()}')
    traces = defaultdict(list)
    incomplete = 0
    for line in payload.decode('utf-8').splitlines():
        try:
            record = json.loads(line)
            traces[record['trace_id']].append(record)
        except (json.JSONDecodeError, KeyError):
            incomplete += 1
    print(f'Traces: {len(traces)}; incomplete/invalid records: {incomplete}')
    for trace_id, records in traces.items():
        records.sort(key=lambda record: record['sequence'])
        config = next((record for record in records if record['stage'] == 'pipeline_configuration'), {})
        time = datetime.fromtimestamp(records[0]['timestamp_ms'] / 1000).astimezone().isoformat(timespec='seconds')
        print(f"\n{time} {trace_id} STT={config.get('stt_provider', '?')}/{config.get('stt_model', '?')} "
              f"mode={config.get('mode', '?')} rewrite={config.get('rewrite_provider')} recording_ms={config.get('recording_ms', '?')}")
        for record in records:
            stage = record['stage']
            if 'text' in record:
                print(f"  {stage}: chars={record['utf16_chars']} bytes={record['utf8_bytes']} "
                      f"replacement={record['replacement_chars']} nul={record['nul_chars']} "
                      f"final={record.get('final', '-')} code={record.get('code', '-')} "
                      f"finish={record.get('finish_reason', '-')} outcome={record.get('outcome', '-')}")
            elif 'error' in stage or 'timeout' in stage or stage in ('output_discarded_stale_callback', 'capture_limit_reached'):
                print(f'  {stage}')
        raw = next((record['text'] for record in records if record['stage'] == 'stt_result'), None)
        final = next((record['text'] for record in records if record['stage'] == 'processed_output'), None)
        delivery = next((record['text'] for record in records if record['stage'] == 'delivery_requested'), None)
        if raw is not None and final is not None and config.get('mode') == 'BLITZTEXT':
            print(f'  Raw-mode STT/output identical: {raw == final}')
        if final is not None and delivery is not None:
            expected = final + ' ' if final and not final[-1].isspace() else final
            print(f'  Delivered text matches processed output + existing trailing-space rule: {delivery == expected}')
        if any(record.get('finish_reason') == 'length' for record in records):
            print('  ATTENTION: rewrite provider reported length-limited output')


if __name__ == '__main__':
    main()
