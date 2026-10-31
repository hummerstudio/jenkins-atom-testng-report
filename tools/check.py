#!/usr/bin/env python3
"""Independent recomputation of what testng-report should have said.

Deliberately a second implementation: Python + ElementTree instead of Java + DOM, and the threshold rules written out
again from the plugin's Publisher.perform() (failed-fails, failed-skips, unstable-fails, unstable-skips, strict >).
It never calls the atom; it reads the same XML and compares the numbers with output.json.
"""
import json
import sys
import xml.etree.ElementTree as ET


def args():
    """--flag value pairs, then -- , then the XML files."""
    path, files = {}, []
    argv = sys.argv[1:]
    i = 0
    while i < len(argv):
        item = argv[i]
        if item == "--":
            files = argv[i + 1:]
            break
        if item.startswith("--"):
            path[item[2:]] = argv[i + 1]
            i += 2
        else:
            i += 1
    return path, files


DEFAULTS = {
    "thresholdMode": 2,
    "failedFails": 100,
    "failedSkips": 100,
    "unstableFails": 0,
    "unstableSkips": 100,
    "failureOnFailedTestConfig": False,
}


def num(value, default):
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def main():
    opts, files = args()
    params = {}
    with open(opts["input"], encoding="utf-8") as handle:
        raw = json.load(handle)
    for key, default in DEFAULTS.items():
        params[key] = raw.get(key, default)
    mode = num(params["thresholdMode"], 2)
    failed_fails = num(params["failedFails"], 100)
    failed_skips = num(params["failedSkips"], 100)
    unstable_fails = num(params["unstableFails"], 0)
    unstable_skips = num(params["unstableSkips"], 100)
    config_fails_fail = str(params["failureOnFailedTestConfig"]).lower() == "true"

    passed = failed = skipped = cfg_fail = cfg_skip = 0
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue  # the atom logs and moves on; the unreadable file contributes nothing
        for method in root.iter("test-method"):
            status = method.get("status")
            is_config = method.get("is-config") is not None
            if status == "FAIL":
                if is_config:
                    cfg_fail += 1
                else:
                    failed += 1
            elif status == "SKIP":
                if is_config:
                    cfg_skip += 1
                else:
                    skipped += 1
            elif not is_config:
                passed += 1

    total = passed + failed + skipped
    verdict, why = "NONE", "within every threshold"
    if config_fails_fail and cfg_fail > 0:
        verdict = "FAILURE"
    elif mode == 1:
        if failed > failed_fails:
            verdict = "FAILURE"
        elif skipped > failed_skips:
            verdict = "FAILURE"
        elif failed > unstable_fails or skipped > unstable_skips:
            verdict = "UNSTABLE"
    else:
        fp = 100.0 * failed / total if total else 0.0
        sp = 100.0 * skipped / total if total else 0.0
        if fp > failed_fails or sp > failed_skips:
            verdict = "FAILURE"
        elif fp > unstable_fails or sp > unstable_skips:
            verdict = "UNSTABLE"

    with open(opts["output"], encoding="utf-8") as handle:
        out = json.load(handle)
    data = out.get("data", {})

    def value(key):
        field = data.get(key)
        if isinstance(field, dict):
            return str(field.get("value", ""))
        return str(field) if field is not None else ""

    expected = {
        "total": str(total),
        "passed": str(passed),
        "failed": str(failed),
        "skipped": str(skipped),
        "failedConfigMethods": str(cfg_fail),
        "skippedConfigMethods": str(cfg_skip),
        "verdict": verdict,
    }
    wrong = {k: (v, value(k)) for k, v in expected.items() if value(k) != v}
    if wrong:
        print(f"check: {opts['output']}: expected/actual mismatch {wrong}", file=sys.stderr)
        return 1
    if verdict == "FAILURE" and out.get("status") != "failure":
        print(f"check: verdict FAILURE but status is {out.get('status')!r}", file=sys.stderr)
        return 1
    if verdict == "UNSTABLE" and value("build.result") != "UNSTABLE":
        print("check: verdict UNSTABLE but build.result was not set", file=sys.stderr)
        return 1
    print(f"check ok ({why}): total={total} passed={passed} failed={failed} skipped={skipped} "
          f"cfg_fail={cfg_fail} verdict={verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
