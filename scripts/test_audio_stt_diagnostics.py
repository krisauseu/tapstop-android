import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('audio_diag', Path(__file__).with_name('audio-stt-diagnostics.py'))
diag = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diag)


class ExportTests(unittest.TestCase):
    def fixture(self):
        case = '12345678-1234-1234-1234-123456789abc'
        content = bytes(range(255))
        record = dict(schema=1, diagnostic_id=case, timestamp_ms=1792576800000, marked=True,
                      insert_id='old-insert', audio_file='input.m4a', audio_bytes=len(content),
                      audio_sha256=hashlib.sha256(content).hexdigest(), events=[dict(event='STT_RESULT', raw_transcript='PRIVATE Äß😀'),
                      dict(event='STT_UPLOAD_FILE', sha256=hashlib.sha256(content).hexdigest(), bytes=len(content))])
        files = {f'{diag.ROOT}/{case}/case.json': json.dumps(record).encode(),
                 f'{diag.ROOT}/{case}/input.m4a': content,
                 'no_backup/insert-delayed-diagnostics/events-00001.jsonl': b'{"insert_id":"old-insert","event":"IMMEDIATE_READBACK","private":"TEXT"}\n'}
        return files, record

    def test_unchanged_export_hash_linked_events_and_private_modes(self):
        files, record = self.fixture()
        with tempfile.TemporaryDirectory() as parent:
            output = Path(parent) / 'export'
            diag.export(files, diag.select(files, insert_id='old-insert', marked=True), output, {})
            case = output / record['diagnostic_id']
            self.assertEqual((case / 'input.m4a').read_bytes(), bytes(range(255)))
            self.assertEqual((case / 'case.json').stat().st_mode & 0o777, 0o600)
            self.assertEqual(case.stat().st_mode & 0o777, 0o700)
            self.assertIn('IMMEDIATE_READBACK', (case / 'insert-events.jsonl').read_text())
            self.assertIn('PRIVATE Äß😀', (case / 'transcripts.txt').read_text())
            with self.assertRaises(FileExistsError):
                diag.export(files, diag.select(files), output, {})

    def test_selections_and_missing_insert(self):
        files, record = self.fixture()
        self.assertEqual(len(diag.select(files, diagnostic_id=record['diagnostic_id'])), 1)
        self.assertEqual(diag.select(files, insert_id='different'), [])
        self.assertEqual(len(diag.select(files, marked=True)), 1)
        self.assertEqual(diag.select(files, date='2000-01-01'), [])
        name = next(n for n in files if n.endswith('case.json'))
        record.pop('insert_id'); record.pop('audio_file'); record['events'] = [dict(event='PROCESSING_FAILED')]
        files[name] = json.dumps(record).encode()
        with tempfile.TemporaryDirectory() as parent:
            diag.export(files, diag.select(files), Path(parent) / 'network-failure', {})

    def test_corrupt_copy_and_upload_mismatch_fail(self):
        for field in ('audio_sha256', 'upload'):
            files, record = self.fixture()
            if field == 'upload':
                record['events'][1]['sha256'] = 'bad'
            else:
                record[field] = 'bad'
            files[next(n for n in files if n.endswith('case.json'))] = json.dumps(record).encode()
            with tempfile.TemporaryDirectory() as parent:
                with self.assertRaises(ValueError):
                    diag.export(files, diag.select(files), Path(parent) / 'bad', {})

    def test_archive_path_traversal_and_links_rejected(self):
        for name, kind in [('../secret', tarfile.REGTYPE), ('safe', tarfile.SYMTYPE)]:
            payload = io.BytesIO()
            with tarfile.open(fileobj=payload, mode='w') as archive:
                info = tarfile.TarInfo(name); info.type = kind; archive.addfile(info)
            with self.assertRaises(ValueError):
                diag.unpack(payload.getvalue())

    def test_device_identity_requires_both_expected_serial_and_model(self):
        with patch.object(diag, 'adb', side_effect=[b'YLE-W09\n', b'WRONG\n']):
            with self.assertRaises(ValueError):
                diag.identify('TEST-SERIAL', 'YLE-W09')
        with patch.object(diag, 'adb', side_effect=[b'WRONG\n', b'TEST-SERIAL\n']):
            with self.assertRaises(ValueError):
                diag.identify('TEST-SERIAL', 'YLE-W09')

    def test_all_adb_operations_explicitly_select_serial(self):
        with patch.object(diag.subprocess, 'run') as run:
            run.return_value.returncode = 0; run.return_value.stdout = b'ok'
            self.assertEqual(diag.adb('TEST-SERIAL', 'get-state'), b'ok')
            self.assertEqual(run.call_args.args[0][:3], ['adb', '-s', 'TEST-SERIAL'])


if __name__ == '__main__':
    unittest.main()
