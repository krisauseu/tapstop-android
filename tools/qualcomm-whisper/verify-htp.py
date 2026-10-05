#!/usr/bin/env python3
"""Verify TapStop's current Whisper sessions from complete `adb logcat -v threadtime`.

Capture before initializing the local runtime and keep streaming through each
session's completion. Native VERBOSE logs require a debug build. This outputs
only metadata, never transcript text, token IDs, audio, or device identifiers.
Missing/rotated logs mean UNCONFIRMED, not proof of a CPU fallback.
"""
import argparse
import json
import re
from pathlib import Path


THREADTIME = re.compile(r"^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[VDIWEFAS]\s+(.+?)\s*:\s?(.*)$")
START = re.compile(r"session_start target=(\S+) audio_ms=(\d+)")
READY = re.compile(r"runtime_ready target=(\S+) htp=(\d+) soc_model=(\d+)")
ENCODER_START = re.compile(r"WhisperModel execute_kv_split\s*$")
ENCODER_TIME = re.compile(r"encoder latency:\s*([\d.]+) ms")
DECODER_START = re.compile(r"execute_kv_split input_id\s+\d+\s+position\s+\d+")


def parse(text):
    entries = []
    for index, line in enumerate(text.splitlines(), 1):
        match = THREADTIME.match(line)
        if match:
            entries.append((index, int(match[1]), match[2].strip(), match[3]))
    return entries


def used_handles(entries):
    return set(re.findall(r"domain 3 handle (0x[0-9a-f]+)\b", "\n".join(row[3] for row in entries)))


def verify(text, htp, pid=None):
    if htp not in (75, 81):
        raise ValueError("Only validated HTP V75/V81 targets are supported")
    target = "sm8650-v75" if htp == 75 else "sm8845-v81"
    soc_model = 57 if htp == 75 else 97
    entries = parse(text)
    process_ids = {row[1] for row in entries if row[2] == "TapStopQualcomm" and START.fullmatch(row[3])}
    if pid is not None:
        process_ids &= {pid}
    sessions = []
    for process_id in sorted(process_ids):
        process = [row for row in entries if row[1] == process_id]
        starts = [i for i, row in enumerate(process) if row[2] == "TapStopQualcomm" and START.fullmatch(row[3])]
        for number, start in enumerate(starts):
            end = starts[number + 1] if number + 1 < len(starts) else len(process)
            terminal = next((i for i in range(start + 1, end)
                             if process[i][2] == "TapStopQualcomm" and
                             process[i][3].startswith(("session_success ", "session_failed "))), end)
            run = process[start + 1:terminal]
            prefix = "\n".join(row[3] for row in process[:start])
            marker = START.fullmatch(process[start][3])
            readiness = [READY.fullmatch(row[3]) for row in process[:start] if row[2] == "TapStopQualcomm"]
            ready = next((m for m in reversed(readiness) if m), None)
            skeleton = f"libQnnHtpV{htp}Skel.so"
            app_skeleton = bool(re.search(
                r"Successfully opened file (?:/storage/emulated/\d+/Android/data/de\.kf\.blitztext/files|"
                r"/data/(?:user/\d+|data)/de\.kf\.blitztext/files)/qualcomm-runtime/"
                + re.escape(f"2.50.0.260828/v{htp}/") + r"(?:\./)?" + re.escape(skeleton) + r"(?:\s|$)", prefix))
            handles = set(re.findall(
                r"remote_handle64_open: opened handle (0x[0-9a-f]+).*" + re.escape(skeleton)
                + r".*qnn_2_50_0.*_dom=cdsp on domain 3\b", prefix))
            checks = {
                "target_matches": marker[1] == target,
                "audio_within_30_seconds": 0 < int(marker[2]) <= 30_000,
                "runtime_initialized": bool(ready and ready[1] == target and int(ready[2]) == htp and int(ready[3]) == soc_model),
                "app_local_skeleton": app_skeleton,
                "cdsp_domain3_opened": bool(handles),
                "qnn_backend_and_device": "backendCreate successful" in prefix and "deviceCreate successful" in prefix,
                "encoder_graph": "hf_whisper_encoder" in prefix,
                "decoder_graph": "hf_whisper_decoder" in prefix,
                "fp16_decoder": "_is_fp16 1" in prefix,
                "session_succeeded": terminal < end and bool(re.fullmatch(
                    r"session_success target=" + re.escape(target) + r" stt_ms=\d+", process[terminal][3])),
            }
            encoder_starts = [i for i, row in enumerate(run) if ENCODER_START.search(row[3])]
            sections = []
            for index, begin in enumerate(encoder_starts):
                until = encoder_starts[index + 1] if index + 1 < len(encoder_starts) else len(run)
                section = run[begin:until]
                latency = next((i for i, row in enumerate(section) if ENCODER_TIME.search(row[3])), None)
                tokens = [i for i, row in enumerate(section) if DECODER_START.search(row[3])]
                encoder_handles = handles & used_handles(section[:latency]) if latency is not None else set()
                common_handles = encoder_handles.copy()
                for token_index, token in enumerate(tokens):
                    next_token = tokens[token_index + 1] if token_index + 1 < len(tokens) else len(section)
                    common_handles &= used_handles(section[token + 1:next_token])
                section_verified = bool(latency is not None and tokens and common_handles)
                sections.append({
                    "encoder_ms": float(ENCODER_TIME.search(section[latency][3])[1]) if latency is not None else None,
                    "decoder_calls": len(tokens),
                    "same_cdsp_handle_for_encoder_and_every_decoder": section_verified,
                })
            checks["every_encoder_and_decoder_confirmed"] = bool(sections) and all(
                section["same_cdsp_handle_for_encoder_and_every_decoder"] for section in sections)
            sessions.append({
                "start_line": process[start][0], "target": target,
                "audio_ms": int(marker[2]), "htp_evidence": "CONFIRMED" if all(checks.values()) else "UNCONFIRMED",
                "checks": checks, "internal_sdk_sections": sections,
            })
    return {"all_sessions_verified": bool(sessions) and all(s["htp_evidence"] == "CONFIRMED" for s in sessions),
            "htp": htp, "sessions": sessions}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--htp", type=int, choices=(75, 81), required=True)
    parser.add_argument("--pid", type=int, help="Optional PID filter for a log containing several app processes")
    parser.add_argument("--output", type=Path, help="Write the same sanitized JSON report to this file")
    args = parser.parse_args()
    report = verify(args.log.read_text(encoding="utf-8", errors="replace"), args.htp, args.pid)
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(output, encoding="utf-8")
    print(output, end="")
    raise SystemExit(0 if report["all_sessions_verified"] else 1)


if __name__ == "__main__":
    main()
