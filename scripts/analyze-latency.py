#!/usr/bin/env python3
"""Analyze metadata-only TapStopDiag logs; nonzero on latency/resource violations."""
import argparse
import csv
from pathlib import Path
import re
from statistics import median

parser = argparse.ArgumentParser()
parser.add_argument('logcat')
parser.add_argument('--allow-errors', action='store_true')
parser.add_argument('--max-stt-ms', type=int, default=10000)
parser.add_argument('--max-growth-ratio', type=float, default=3)
args = parser.parse_args()
requests = {}
for line in Path(args.logcat).read_text().splitlines():
    match = re.search(r'TapStopDiag: (TS-\d+) t=(\d+) dt=(\d+) event=(\S+) thread=(\S+)(.*)', line)
    if not match:
        continue
    rid, time, elapsed, event, thread, data = match.groups()
    row = requests.setdefault(rid, {'id': rid, 'events': {}})
    row['events'][event] = int(time)
    meta = dict(re.findall(r'(\w+)=([^\s]+)', data))
    if event == 'configuration': row.update(meta)
    if event == 'cleanup_complete': row.update(meta)

def difference(row, end, start):
    events = row['events']
    return events[end] - events[start] if end in events and start in events else None

rows = []
violations = []
for row in requests.values():
    if 'provider' not in row: continue
    for name, end, start in [
        ('queue_ms', 'worker_started', 'worker_submitted'),
        ('stt_ms', 'stt_parsing_complete', 'stt_begin'),
        ('stream_ready_ms', 'stt_request_stream_ready', 'stt_http_execute'),
        ('upload_ms', 'stt_upload_complete', 'stt_request_stream_ready'),
        ('headers_wait_ms', 'stt_headers_received', 'stt_upload_complete'),
        ('body_ms', 'stt_body_complete', 'stt_headers_received'),
        ('rewrite_ms', 'rewrite_parsing_complete', 'rewrite_begin'),
        ('total_ms', 'result_ready', 'recording_stop_requested'),
    ]: row[name] = difference(row, end, start)
    if row['stt_ms'] is not None and row['stt_ms'] > args.max_stt_ms:
        violations.append(f"SLOW {row['id']} STT={row['stt_ms']}ms headers_wait={row['headers_wait_ms']}ms queue={row['queue_ms']}ms")
    if 'processing_error' in row['events'] and not args.allow_errors:
        violations.append(f"REQUEST_ERROR {row['id']}")
    if 'processing_complete' not in row['events'] or 'cleanup_complete' not in row['events']:
        violations.append(f"INCOMPLETE {row['id']}")
    if row.get('connections_owned') != '0' or row.get('workers') != '0':
        violations.append(f"RESOURCES {row['id']}")
    rows.append(row)

columns = ['id', 'provider', 'model', 'mode', 'queue_ms', 'stt_ms', 'stream_ready_ms', 'upload_ms', 'headers_wait_ms', 'body_ms', 'rewrite_ms', 'total_ms', 'threads', 'fds', 'workers', 'submitted', 'watchdogs', 'connections_owned', 'heap_bytes', 'native_bytes', 'rss']
with Path(args.logcat).with_suffix('.csv').open('w') as output:
    writer = csv.DictWriter(output, fieldnames=columns, extrasaction='ignore')
    writer.writeheader(); writer.writerows(rows)
for provider in sorted({r['provider'] for r in rows}):
    for mode in sorted({r['mode'] for r in rows}):
        group = [r for r in rows if r['provider'] == provider and r['mode'] == mode and r['stt_ms'] is not None]
        if not group: continue
        times = [r['stt_ms'] for r in group]
        growth = median(times[-5:]) / max(1, median(times[:5]))
        print(f'{provider}/{mode}: n={len(group)} median={median(times)} max={max(times)} first5={median(times[:5])} last5={median(times[-5:])} ratio={growth:.2f}')
        if len(times) >= 10 and growth > args.max_growth_ratio: violations.append(f'PROGRESSIVE {provider}/{mode} ratio={growth:.2f}')
print('\n'.join(violations) if violations else 'PASS latency and app-owned resources')
raise SystemExit(1 if violations or not rows else 0)
