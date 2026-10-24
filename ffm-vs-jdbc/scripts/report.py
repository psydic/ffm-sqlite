#!/usr/bin/env python3
"""Turns JMH JSON results (lib = jdbc | ffm-sqlite) into Markdown comparison tables.

usage: report.py results/full.json [results/coldstart.json] > results/report.md

Speedup = sqlite-jdbc time / ffm-sqlite time (for throughput modes: ffm / jdbc), so > 1 means ffm-sqlite
is faster. A row is a win/loss only when the 99.9% confidence intervals (score ± error) do not overlap;
otherwise it is a tie.
"""
import json
import math
import sys
from collections import OrderedDict

CATEGORIES = OrderedDict([
    ("WorkloadBenchmark", "Application workloads (file DB, WAL)"),
    ("ConcurrentReadBenchmark", "Concurrent reads (own connection per thread)"),
    ("SQLiteBenchmark", "Bulk rows: 1,000 x (int, text, int)"),
    ("TypeBenchmark", "Per data type, 1,000 rows"),
    ("BlobBenchmark", "Large values"),
    ("OperationsBenchmark", "Schema, PRAGMAs, functions, backup, serialize"),
    ("UdfBenchmark", "User-defined functions and collations"),
    ("HookBenchmark", "Hooks"),
    ("ColdStartBenchmark", "Cold start (fresh JVM)"),
])


def load(paths):
    rows = []
    for p in paths:
        for b in json.load(open(p)):
            cls, method = b["benchmark"].split(".")[-2:]
            params = dict(b.get("params", {}))
            lib = params.pop("lib", None)
            if lib is None:  # SQLiteBenchmark encodes the library in the method name
                lib = "ffm-sqlite" if "_lib" in method else "jdbc"
                method = method.replace("_lib", "_").replace("_jdbc", "_")
            pm = b["primaryMetric"]
            alloc = b.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
            if params.get("generatedKeys") == "true":
                # sqlite-jdbc's default (extra SELECT per INSERT); ffm-sqlite is paired with its fastest setting.
                method += " (jdbc default: generated keys on)"
                lib = "jdbc-default"
            params.pop("generatedKeys", None)
            key = (cls, method, tuple(sorted(params.items())))
            rows.append((key, lib, b["mode"], pm["score"], pm.get("scoreError") or 0.0, pm["scoreUnit"], alloc))
    return rows


def fmt(x):
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return "–"
    if x >= 1000:
        return f"{x:,.0f}"
    if x >= 100:
        return f"{x:.0f}"
    if x >= 10:
        return f"{x:.1f}"
    return f"{x:.2f}"


def main():
    rows = load(sys.argv[1:])
    pairs = OrderedDict()
    for key, lib, mode, score, err, unit, alloc in rows:
        pairs.setdefault(key, {})[lib] = (mode, score, err, unit, alloc)

    out = []
    summary = []
    all_speedups = []
    for cls, title in CATEGORIES.items():
        entries = [(k, v) for k, v in pairs.items() if k[0] == cls and "jdbc" in v and "ffm-sqlite" in v]
        if not entries:
            continue
        out.append(f"\n### {title}\n")
        out.append("| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |")
        out.append("|---|---|---:|---:|---|---:|:---:|---:|")
        speedups, wins, ties, losses = [], 0, 0, 0
        for (c, method, params), v in entries:
            mode, js, je, unit, ja = v["jdbc"]
            _, fs, fe, _, fa = v["ffm-sqlite"]
            higher_better = mode == "thrpt"
            speedup = fs / js if higher_better else js / fs
            overlap = (js - je) <= (fs + fe) and (fs - fe) <= (js + je)
            if overlap:
                result, ties = "tie", ties + 1
            elif speedup > 1:
                result, wins = "**ffm**", wins + 1
            else:
                result, losses = "jdbc", losses + 1
            speedups.append(speedup)
            p = ", ".join(f"{k}={val}" for k, val in params) or ""
            alloc = f"{fmt(ja)} / {fmt(fa)}" if ja is not None else "–"
            out.append(f"| {method} | {p} | {fmt(js)} ± {fmt(je)} | {fmt(fs)} ± {fmt(fe)} | {unit} | {speedup:.2f}× | {result} | {alloc} |")
        gm = math.exp(sum(math.log(s) for s in speedups) / len(speedups))
        all_speedups += speedups
        summary.append(f"| {title} | {len(speedups)} | {gm:.2f}× | {min(speedups):.2f}× – {max(speedups):.2f}× | {wins} / {ties} / {losses} |")

    defaults = [(k, v["jdbc-default"]) for k, v in pairs.items() if "jdbc-default" in v]
    if defaults:
        out.append("\nsqlite-jdbc with its default configuration (`jdbc.get_generated_keys=true`), for reference:\n")
        for (c, method, params), (mode, score, err, unit, alloc) in defaults:
            out.append(f"- {c}.{method}: {fmt(score)} ± {fmt(err)} {unit}, {fmt(alloc)} B/op")

    total_gm = math.exp(sum(math.log(s) for s in all_speedups) / len(all_speedups))
    print("| Category | Benchmarks | Geo-mean speedup | Range | ffm wins / ties / jdbc wins |")
    print("|---|---:|---:|---|---|")
    print("\n".join(summary))
    print(f"| **All** | **{len(all_speedups)}** | **{total_gm:.2f}×** | {min(all_speedups):.2f}× – {max(all_speedups):.2f}× | |")
    print("\n".join(out))


if __name__ == "__main__":
    main()
