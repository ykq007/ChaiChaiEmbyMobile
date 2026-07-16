#!/usr/bin/env python3
"""Aggregate Quality Gate results into a machine-readable release report.

Reads the approved gate catalog (docs/release/quality-gates.json) and a
directory of per-gate result files, applies the waiver policy, and emits a
machine-readable quality-gate-report.json plus an optional Markdown summary.

The exit code is the release decision: 0 when release is permitted, 1 when a
gate blocks it. The critical rule from CONTEXT.md is enforced structurally: a
gate whose severity is non-waivable can never be waived, and any attempt to
waive one is reported as an invalid waiver that keeps the gate blocking.

Result files are named ``<result_key>.json`` and look like::

    {"status": "pass", "detail": "...", "artifacts": ["..."], "measurements": {}}

``status`` is one of pass | fail | not-run | error | skip. A missing result
file is treated as ``not-run`` (fail-closed).

Waivers (``--waivers waivers.json``) are a JSON list::

    [{"gate": "performance", "reason": "...", "owner": "@x",
      "target_milestone": "M2", "expires": "2026-09-01"}]
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import sys
from pathlib import Path

PASS = "pass"
BLOCKING = "blocking"
WAIVED = "waived"
NOT_RUN = "not-run"

_PASS_STATUSES = {"pass", "passed", "ok", "success"}
_REQUIRED_WAIVER_FIELDS = ("reason", "owner", "target_milestone", "expires")


def _load_json(path: Path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def _parse_date(value: str) -> _dt.date:
    return _dt.date.fromisoformat(str(value)[:10])


def _valid_waiver(waiver: dict, gate: dict, as_of: _dt.date) -> tuple[bool, str]:
    """Return (is_valid, reason_if_invalid) for a waiver against a gate."""
    if not gate.get("waivable", False):
        return False, "gate severity is non-waivable and can never be waived"
    missing = [f for f in _REQUIRED_WAIVER_FIELDS if not waiver.get(f)]
    if missing:
        return False, f"waiver missing required field(s): {', '.join(missing)}"
    try:
        expires = _parse_date(waiver["expires"])
    except ValueError:
        return False, f"waiver has an unparseable expiry: {waiver['expires']!r}"
    if expires < as_of:
        return False, f"waiver expired on {expires.isoformat()}"
    return True, ""


def evaluate(catalog: dict, results: dict, waivers: dict, as_of: _dt.date) -> dict:
    """Evaluate every gate and produce the report structure."""
    gate_reports = []
    for gate in catalog["gates"]:
        key = gate["result_key"]
        result = results.get(key, {"status": NOT_RUN,
                                   "detail": "no result file produced"})
        raw_status = str(result.get("status", NOT_RUN)).lower()
        passed = raw_status in _PASS_STATUSES

        waiver = waivers.get(gate["id"])
        waiver_note = None
        if passed:
            outcome = PASS
        elif waiver is not None:
            ok, why = _valid_waiver(waiver, gate, as_of)
            if ok:
                outcome, waiver_note = WAIVED, "valid waiver applied"
            else:
                outcome, waiver_note = BLOCKING, f"invalid waiver: {why}"
        else:
            outcome = BLOCKING

        gate_reports.append({
            "id": gate["id"],
            "title": gate["title"],
            "category": gate["category"],
            "severity": gate["severity"],
            "waivable": gate.get("waivable", False),
            "acceptance_criteria": gate.get("acceptance_criteria", []),
            "raw_status": raw_status,
            "outcome": outcome,
            "detail": result.get("detail", ""),
            "artifacts": result.get("artifacts", []),
            "measurements": result.get("measurements", {}),
            "waiver": waiver,
            "waiver_note": waiver_note,
        })

    blocking = [g for g in gate_reports if g["outcome"] == BLOCKING]
    waived = [g for g in gate_reports if g["outcome"] == WAIVED]
    release_permitted = not blocking

    return {
        "milestone": catalog.get("milestone"),
        "spec_issue": catalog.get("spec_issue"),
        "generated_at": as_of.isoformat(),
        "release_permitted": release_permitted,
        "verdict": "PASS" if release_permitted else "BLOCKED",
        "summary": {
            "total": len(gate_reports),
            "passed": sum(1 for g in gate_reports if g["outcome"] == PASS),
            "waived": len(waived),
            "blocking": len(blocking),
        },
        "blocking_gate_ids": [g["id"] for g in blocking],
        "waived_gate_ids": [g["id"] for g in waived],
        "gates": gate_reports,
    }


def render_markdown(report: dict) -> str:
    icon = {PASS: "✅", WAIVED: "⚠️", BLOCKING: "❌"}
    lines = [
        f"# Quality Gate report — {report['milestone']}",
        "",
        f"**Verdict: {report['verdict']}** "
        f"(release {'permitted' if report['release_permitted'] else 'BLOCKED'})  ",
        f"Generated {report['generated_at']} · spec #{report['spec_issue']}",
        "",
        f"{report['summary']['passed']} passed · "
        f"{report['summary']['waived']} waived · "
        f"{report['summary']['blocking']} blocking "
        f"of {report['summary']['total']} gates",
        "",
        "| Gate | AC | Severity | Outcome | Notes |",
        "| --- | --- | --- | --- | --- |",
    ]
    for g in report["gates"]:
        note = g["waiver_note"] or g["detail"] or ""
        note = note.replace("|", "\\|")
        acs = ", ".join(g["acceptance_criteria"])
        lines.append(
            f"| {g['title']} | {acs} | {g['severity']} | "
            f"{icon.get(g['outcome'], g['outcome'])} {g['outcome']} | {note} |"
        )
    if report["blocking_gate_ids"]:
        lines += ["", "## Blocking gates", ""]
        lines += [f"- **{i}**" for i in report["blocking_gate_ids"]]
    return "\n".join(lines) + "\n"


def _load_results(results_dir: Path, catalog: dict) -> dict:
    results = {}
    for gate in catalog["gates"]:
        candidate = results_dir / f"{gate['result_key']}.json"
        if candidate.exists():
            results[gate["result_key"]] = _load_json(candidate)
    return results


def _load_waivers(path: Path | None) -> dict:
    if path is None or not path.exists():
        return {}
    raw = _load_json(path)
    entries = raw.get("waivers", raw) if isinstance(raw, dict) else raw
    # A waiver without a "gate" targets nothing; drop it (fail-closed) rather
    # than raise, so a malformed waiver can never crash qualification.
    return {w["gate"]: w for w in entries if isinstance(w, dict) and w.get("gate")}


def run(args: argparse.Namespace) -> int:
    catalog = _load_json(Path(args.catalog))
    results = _load_results(Path(args.results), catalog) if args.results else {}
    waivers = _load_waivers(Path(args.waivers) if args.waivers else None)
    as_of = _parse_date(args.as_of) if args.as_of else _dt.date.today()

    report = evaluate(catalog, results, waivers, as_of)

    out = Path(args.out)
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if args.summary:
        Path(args.summary).write_text(render_markdown(report), encoding="utf-8")

    print(render_markdown(report))
    if not report["release_permitted"] and not args.no_fail:
        return 1
    return 0


def _self_test() -> int:
    """In-process assertions; no filesystem or network required."""
    catalog = {
        "milestone": "Test",
        "spec_issue": 0,
        "gates": [
            {"id": "privacy", "title": "Privacy", "category": "privacy",
             "severity": "critical", "waivable": False, "result_key": "privacy",
             "acceptance_criteria": ["AC7"]},
            {"id": "perf", "title": "Perf", "category": "performance",
             "severity": "performance", "waivable": True, "result_key": "perf",
             "acceptance_criteria": ["AC5"]},
        ],
    }
    today = _dt.date(2026, 7, 16)
    future = "2026-12-31"
    past = "2026-01-01"

    def outcomes(results, waivers):
        rep = evaluate(catalog, results, waivers, today)
        return {g["id"]: g["outcome"] for g in rep["gates"]}, rep["release_permitted"]

    # 1. All pass -> release permitted.
    o, ok = outcomes({"privacy": {"status": "pass"}, "perf": {"status": "pass"}}, {})
    assert o == {"privacy": PASS, "perf": PASS} and ok, o

    # 2. Missing result is fail-closed (not-run -> blocking).
    o, ok = outcomes({}, {})
    assert o["privacy"] == BLOCKING and not ok, o

    # 3. Waivable perf failure with a valid waiver -> waived, release permitted.
    o, ok = outcomes(
        {"privacy": {"status": "pass"}, "perf": {"status": "fail"}},
        {"perf": {"gate": "perf", "reason": "r", "owner": "@o",
                  "target_milestone": "M2", "expires": future}},
    )
    assert o["perf"] == WAIVED and ok, o

    # 4. Critical privacy failure can NEVER be waived, even with a full waiver.
    o, ok = outcomes(
        {"privacy": {"status": "fail"}, "perf": {"status": "pass"}},
        {"privacy": {"gate": "privacy", "reason": "r", "owner": "@o",
                     "target_milestone": "M2", "expires": future}},
    )
    assert o["privacy"] == BLOCKING and not ok, o

    # 5. Expired waiver does not apply.
    o, ok = outcomes(
        {"privacy": {"status": "pass"}, "perf": {"status": "fail"}},
        {"perf": {"gate": "perf", "reason": "r", "owner": "@o",
                  "target_milestone": "M2", "expires": past}},
    )
    assert o["perf"] == BLOCKING and not ok, o

    # 6. Incomplete waiver (missing owner) does not apply.
    o, ok = outcomes(
        {"privacy": {"status": "pass"}, "perf": {"status": "fail"}},
        {"perf": {"gate": "perf", "reason": "r", "target_milestone": "M2",
                  "expires": future}},
    )
    assert o["perf"] == BLOCKING and not ok, o

    print("quality_gate_report self-test: all 6 cases passed")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", default="docs/release/quality-gates.json")
    parser.add_argument("--results", help="directory of <result_key>.json files")
    parser.add_argument("--waivers", help="JSON file listing active waivers")
    parser.add_argument("--out", default="quality-gate-report.json")
    parser.add_argument("--summary", help="write a Markdown summary to this path")
    parser.add_argument("--as-of", help="ISO date used to evaluate waiver expiry")
    parser.add_argument("--no-fail", action="store_true",
                        help="always exit 0 (report only, do not gate)")
    parser.add_argument("--self-test", action="store_true",
                        help="run built-in assertions and exit")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
