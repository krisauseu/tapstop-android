import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import subprocess

spec = importlib.util.spec_from_file_location('delayed', Path(__file__).with_name('delayed-insert-diagnostics.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SummaryTest(unittest.TestCase):
    def test_observation_is_not_proven_loss_and_markers_have_ids(self):
        rows = [dict(schema=2, event='INSERT_BEGIN', insert_id='x'),
                dict(schema=2, event='INSERT_RESULT', insert_id='x', outcome='DIRECT_INPUT_CONNECTION_SUCCESS', text_chars=110, target_package='app'),
                dict(schema=2, event='IMMEDIATE_READBACK', insert_id='x', status='IMMEDIATE_CONFIRMED'),
                dict(schema=2, event='DELAYED_READBACK', insert_id='x', scheduled_ms=250, status='DELAYED_CONFIRMED'),
                dict(schema=2, event='DELAYED_READBACK', insert_id='x', scheduled_ms=1000, status='DELAYED_TEXT_MISMATCH', first_difference_utf16=3, reason='UNKNOWN_AUTHOR'),
                dict(schema=2, event='DELAYED_READBACK', insert_id='x', scheduled_ms=3000, status='DELAYED_NOT_VERIFIABLE', reason='SESSION_CHANGED'),
                dict(schema=2, event='MANUAL_ERROR_MARKER', insert_id='x', associated_insert_id='x', association='LAST_INSERT_CANDIDATE_UNCONFIRMED')]
        report = module.summarize(rows)
        self.assertEqual(1, report['retained_insert_results'])
        self.assertEqual(1, report['input_connection_successes'])
        self.assertEqual(1, report['immediate_confirmed'])
        self.assertEqual(0, report['missing_delayed_events'])
        self.assertEqual(1, len(report['observed_differences']))
        self.assertEqual(0, report['proven_tapstop_text_losses'])
        self.assertTrue(report['manual_markers'][0]['associated_records_retained'])
        self.assertEqual({'SESSION_CHANGED': 1}, report['unverifiable_reasons'])

    def test_partial_session_and_invalid_json_are_reported_without_text_output(self):
        rows, invalid = module.parse('{bad}\n' + json.dumps(dict(schema=2, event='INSERT_RESULT', insert_id='x', text_chars=4, outcome='CLIPBOARD', raw_transcript='SECRET')))
        report = module.summarize(rows)
        self.assertEqual(1, invalid)
        self.assertEqual(3, report['missing_delayed_events'])
        self.assertNotIn('SECRET', json.dumps(report))
        self.assertEqual(1, report['clipboard_fallbacks'])

    @patch.object(module.subprocess, 'run')
    def test_multiple_devices_require_explicit_serial(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, 'List of devices attached\na device model:Samsung\nb device model:HONOR\n')
        with self.assertRaises(SystemExit):
            module.select_device()

    @patch.object(module.subprocess, 'check_output')
    @patch.object(module.subprocess, 'run')
    def test_selected_device_identity_is_checked_and_every_command_addressed(self, run, check):
        run.return_value = subprocess.CompletedProcess([], 0, 'List of devices attached\na device\nb device\n')
        check.side_effect = ['HONOR\n', 'b\n']
        self.assertEqual(('b', 'HONOR'), module.select_device('b'))
        for call in check.call_args_list:
            self.assertEqual(['adb', '-s', 'b'], call.args[0][:3])

    def test_missing_marker_association_never_invented(self):
        report = module.summarize([dict(event='MANUAL_ERROR_MARKER', insert_id='m', associated_insert_id='old')])
        self.assertFalse(report['manual_markers'][0]['associated_records_retained'])
        self.assertEqual({}, module.summarize([])['target_apps'])


if __name__ == '__main__':
    unittest.main()
