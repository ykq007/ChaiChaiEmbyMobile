#!/usr/bin/env python3
"""Coalesce per-lane acceptance results into instrumentation-backed gates.

The release workflow runs the acceptance suite on two required window lanes
(compact API 26 and expanded API 36) and drops a ``lane-<name>.json`` file per
lane. This reducer maps those lanes onto the gates they jointly prove, using a
fail-closed rule: a gate passes only when *every* contributing lane passed and
produced a result. A missing lane file counts as a failure.

Usage: coalesce_lane_gates.py <quality-gate-dir>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# gate result_key -> lanes that must all pass for the gate to pass.
GATE_LANES = {
    "first-viewing-loop": ["compact", "expanded"],
    "adaptive-layout": ["compact", "expanded"],
    "accessibility": ["compact", "expanded"],
}


def _lane_status(gate_dir: Path, lane: str) -> tuple[bool, str]:
    path = gate_dir / f"lane-{lane}.json"
    if not path.exists():
        return False, f"{lane} lane produced no result (job did not complete)"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return False, f"{lane} lane result unreadable: {exc}"
    status = str(data.get("status", "")).lower()
    return status in {"pass", "passed", "ok", "success"}, data.get("detail", "")


def main(argv: list[str]) -> int:
    gate_dir = Path(argv[1]) if len(argv) > 1 else Path("quality-gate")
    gate_dir.mkdir(parents=True, exist_ok=True)

    for result_key, lanes in GATE_LANES.items():
        details, all_pass = [], True
        for lane in lanes:
            ok, detail = _lane_status(gate_dir, lane)
            all_pass = all_pass and ok
            details.append(f"{lane}: {'pass' if ok else 'FAIL'} — {detail}".rstrip(" —"))
        result = {
            "status": "pass" if all_pass else "fail",
            "detail": "; ".join(details),
        }
        (gate_dir / f"{result_key}.json").write_text(
            json.dumps(result, indent=2) + "\n", encoding="utf-8"
        )
        print(f"{result_key}: {result['status']} ({result['detail']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
