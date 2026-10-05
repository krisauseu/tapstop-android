#!/usr/bin/env python3
"""Synthetic metadata-only regression checks for the HTP evidence verifier."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("verify_htp", Path(__file__).with_name("verify-htp.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def line(message, tag="Whisper", pid=123):
    return f"10-05 12:00:00.001 {pid:5}   456 V {tag}: {message}\n"


def initialization(htp=75):
    target, soc = ("sm8650-v75", 57) if htp == 75 else ("sm8845-v81", 97)
    return "".join(line(message) for message in [
        f"Successfully opened file /storage/emulated/0/Android/data/de.kf.blitztext/files/qualcomm-runtime/2.50.0.260828/v{htp}/./libQnnHtpV{htp}Skel.so",
        f"remote_handle64_open: opened handle 0xabcdef for file:///libQnnHtpV{htp}Skel.so?qnn_2_50_0_skel_handle_invoke&_dom=cdsp on domain 3",
        "backendCreate successful", "deviceCreate successful", "hf_whisper_encoder", "hf_whisper_decoder", "_is_fp16 1 data_type 1",
    ]) + line(f"runtime_ready target={target} htp={htp} soc_model={soc}", "TapStopQualcomm")


def section(encoder=True, decoder=True, handle="0xabcdef", pid=123):
    return (line("WhisperModel execute_kv_split") +
            (line(f"domain 3 handle {handle}", pid=pid) if encoder else "") +
            line("[performance] encoder latency: 400 ms") +
            line("execute_kv_split input_id 50258 position 0") +
            (line(f"domain 3 handle {handle}", pid=pid) if decoder else ""))


def session(parts=None, htp=75, success=True):
    target = "sm8650-v75" if htp == 75 else "sm8845-v81"
    return (line(f"session_start target={target} audio_ms=30000", "TapStopQualcomm") +
            (section() if parts is None else parts) +
            line(f"session_success target={target} stt_ms=900" if success else "session_failed type=IOException", "TapStopQualcomm"))


class EvidenceTest(unittest.TestCase):
    def test_both_targets_require_real_current_execution(self):
        for htp in (75, 81):
            self.assertTrue(module.verify(initialization(htp) + session(htp=htp), htp)["all_sessions_verified"])

    def test_second_session_cannot_borrow_first_encoder_evidence(self):
        result = module.verify(initialization() + session() + session(section(encoder=False)), 75)
        self.assertTrue(result["sessions"][0]["checks"]["every_encoder_and_decoder_confirmed"])
        self.assertFalse(result["all_sessions_verified"])

    def test_other_pid_or_unopened_handle_does_not_prove_execution(self):
        for part in (section(pid=999), section(handle="0xbbbbbb")):
            self.assertFalse(module.verify(initialization() + session(part), 75)["all_sessions_verified"])

    def test_every_sdk_section_and_decoder_requires_matching_handle(self):
        self.assertTrue(module.verify(initialization() + session(section() + section()), 75)["all_sessions_verified"])
        self.assertFalse(module.verify(initialization() + session(section() + section(decoder=False)), 75)["all_sessions_verified"])
        missing_last_token = section() + line("execute_kv_split input_id 7 position 1")
        self.assertFalse(module.verify(initialization() + session(missing_last_token), 75)["all_sessions_verified"])

    def test_missing_init_failed_session_and_wrong_target_are_unconfirmed(self):
        for text, htp in ((session(), 75), (initialization() + session(success=False), 75),
                          (initialization() + session(), 81), (initialization(), 75)):
            self.assertFalse(module.verify(text, htp)["all_sessions_verified"])


if __name__ == "__main__":
    unittest.main()
