#!/usr/bin/env python3
"""Check captured TapStop transcript boundaries without printing private text.

The editor confirmation flag covers only the immediate InputConnection readback.
Later app/keyboard/rendering changes cannot be tested from this capture.
"""
import argparse
from collections import Counter, defaultdict
import copy
import json
from pathlib import Path


def compare(records):
    groups = defaultdict(list)
    for record in records:
        groups[record['trace_id']].append(record)
    errors = []
    for trace_id, group in groups.items():
        if any(record['stage'] == 'diagnostic_fixture' for record in group):
            continue
        stages = {record['stage']: record for record in group}
        config = stages.get('pipeline_configuration', {})
        def text(stage):
            return stages.get(stage, {}).get('text')
        raw, final = text('stt_result'), text('processed_output')
        cloud = text('cloud_stt_response')
        requested, outcome = text('delivery_requested'), text('delivery_result')
        if cloud is not None and raw is not None and cloud != raw:
            errors.append((trace_id, 'cloud_response_to_stt_changed'))
        if config.get('mode') == 'BLITZTEXT' and raw is not None and final is not None and raw != final:
            errors.append((trace_id, 'raw_mode_output_changed'))
        expected = final + ' ' if final and not final[-1].isspace() else final
        if final is not None and requested is not None and requested != expected:
            errors.append((trace_id, 'processed_to_delivery_changed'))
        if requested is not None and outcome is not None and requested != outcome:
            errors.append((trace_id, 'delivery_request_result_changed'))
        clipboard = text('clipboard_written')
        if requested is not None and clipboard is not None and requested != clipboard:
            errors.append((trace_id, 'clipboard_text_changed'))
        if any(record.get('replacement_chars', 0) or record.get('nul_chars', 0) for record in group):
            errors.append((trace_id, 'replacement_or_nul_character'))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path)
    parser.add_argument('--self-check', action='store_true', help='prove that removing an umlaut at delivery is detected')
    args = parser.parse_args()
    blob = args.file.read_bytes()
    records, offsets, offset = [], {}, 0
    for line in blob.splitlines(keepends=True):
        record = json.loads(line)
        records.append(record)
        offsets[id(record)] = offset
        offset += len(line)
    errors = compare(records)
    fixtures = {record['trace_id'] for record in records if record['stage'] == 'diagnostic_fixture'}
    production = [record for record in records if record['trace_id'] not in fixtures]
    configs = [record for record in production if record['stage'] == 'pipeline_configuration']
    deliveries = [record for record in production if record['stage'] == 'delivery_result']
    last = [record for record in deliveries if offsets[id(record)] >= len(blob) * 3 // 4]
    print(f'Records={len(records)} synthetic_traces={len(fixtures)} production_attempts={len(configs)} successes={len(deliveries)}')
    print('Provider/model/mode:', dict(Counter((record['stt_provider'], record['stt_model'], record['mode']) for record in configs)))
    for name, selected in [('All', deliveries), ('Last quarter by bytes', last)]:
        print(name, 'outcomes:', dict(Counter(record['outcome'] for record in selected)))
        confirmed = sum('INPUT_CONNECTION_CONFIRMED' in json.loads(record['insert_details'])['steps'] for record in selected if record.get('insert_details'))
        umlauts = sum(any(char in record['text'] for char in 'äöüÄÖÜßẞ') for record in selected)
        print(f'  immediate_editor_confirmations={confirmed} outputs_with_umlauts_or_sz={umlauts}')
    for trace_id, reason in errors:
        print(f'FAIL {trace_id}: {reason}')
    if args.self_check:
        mutated = copy.deepcopy(records)
        sample = next(record for record in mutated if record['stage'] == 'delivery_requested' and
                      record['trace_id'] not in fixtures and any(char in record['text'] for char in 'äöü'))
        sample['text'] = sample['text'].translate(str.maketrans('äöü', 'aou'))
        found = compare(mutated)
        if not any(trace_id == sample['trace_id'] and reason == 'processed_to_delivery_changed' for trace_id, reason in found):
            raise SystemExit('FAIL: detector did not catch simulated umlaut loss')
        print('PASS: in-memory simulated umlaut removal is detected; original capture unchanged')
    print('FAIL' if errors else 'PASS: all captured text boundaries preserve the original strings')
    raise SystemExit(bool(errors))


if __name__ == '__main__':
    main()
