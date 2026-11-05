import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('insert_diagnostics', Path(__file__).with_name('insert-diagnostics.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SummaryTest(unittest.TestCase):
    def test_counts_reasons_packages_and_comparison(self):
        rows = [
            dict(timestamp_ms=1, outcome='DIRECT_INPUT_CONNECTION_SUCCESS', text_chars=10, recording_ms=100, target_package='a'),
            dict(timestamp_ms=2, outcome='DIRECT_INPUT_CONNECTION_ACCEPTED_UNVERIFIED', text_chars=20, recording_ms=200, target_package='a'),
            dict(timestamp_ms=3, outcome='DIRECT_ACCESSIBILITY_SUCCESS', text_chars=30, recording_ms=None, target_package='b'),
            dict(timestamp_ms=4, outcome='CLIPBOARD', clipboard_reason='ACCESSIBILITY_REJECTED', text_chars=40, recording_ms=400, target_package='b', steps=['INPUT_CONNECTION_UNCONFIRMED', 'ACCESSIBILITY_REJECTED', 'CLIPBOARD_WRITTEN']),
            dict(timestamp_ms=5, outcome='CLIPBOARD', clipboard_reason='NO_RETAINED_TARGET', text_chars=5, target_package=None),
        ]
        report = module.summarize(rows)
        self.assertEqual(5, report['retained_insert_attempts'])
        self.assertEqual(2, report['input_connection_successes'])
        self.assertEqual(1, report['accessibility_successes'])
        self.assertEqual(2, report['clipboard_fallbacks'])
        self.assertEqual({'ACCESSIBILITY_REJECTED': 1, 'NO_RETAINED_TARGET': 1}, report['clipboard_reasons'])
        self.assertEqual({'b': 1, 'UNKNOWN': 1}, report['clipboard_target_packages'])
        self.assertEqual(20, report['comparison']['direct_success']['text_chars']['median'])
        self.assertEqual(2, report['comparison']['direct_success']['recording_ms']['n'])
        self.assertEqual(rows[3]['steps'], report['clipboard_cases'][0]['steps'])

    def test_empty_and_missing_metadata(self):
        report = module.summarize([])
        self.assertEqual(0, report['retained_insert_attempts'])
        self.assertIsNone(report['comparison']['clipboard']['recording_ms']['median'])
        self.assertIsNone(report['first_timestamp_ms'])

    def test_failed_clipboard_write_is_not_counted_as_written(self):
        report = module.summarize([dict(timestamp_ms=1, outcome='CLIPBOARD_WRITE_FAILED')])
        self.assertEqual(0, report['clipboard_fallbacks'])
        self.assertEqual(0, report['input_connection_successes'])


if __name__ == '__main__':
    unittest.main()
