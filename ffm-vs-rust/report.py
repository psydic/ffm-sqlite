#!/usr/bin/env python3
"""Builds results/report.md from the JMH JSON (results/java-*.json) and the rusqlite output (results/rust-*.txt).

Both sides are summarised the same way: mean over all measured iterations of all forks, and the 99.9% confidence
interval from Student's t over those iterations (what JMH reports as the score error). A difference counts as a win
only when the intervals do not overlap.
"""
import json
import math
import pathlib
import statistics

RES = pathlib.Path(__file__).parent / "results"
# Two-sided 99.9% Student's t quantiles by degrees of freedom (JMH uses the same interval).
T999 = {1: 636.6, 2: 31.60, 3: 12.92, 4: 8.610, 5: 6.869, 6: 5.959, 7: 5.408, 8: 5.041, 9: 4.781, 10: 4.587,
        11: 4.437, 12: 4.318, 13: 4.221, 14: 4.140, 15: 4.073, 19: 3.883, 29: 3.659}


def t999(df):
    return T999.get(df) or T999[max(k for k in T999 if k <= df)]


def summary(values):
    m = statistics.fmean(values)
    if len(values) < 2:
        return m, float("nan")
    return m, t999(len(values) - 1) * statistics.stdev(values) / math.sqrt(len(values))


def rust(name):
    out = {}
    for line in (RES / f"rust-{name}.txt").read_text().splitlines():
        if line.startswith("RESULT\t"):
            _, bench, unit, scores = line.split("\t")
            out.setdefault(bench, []).extend(float(v) for v in scores.split(","))
    return {k: summary(v) for k, v in out.items()}


def java(name, secondary=False):
    out = {}
    for b in json.loads((RES / f"java-{name}.json").read_text()):
        bench = b["benchmark"].rsplit(".", 1)[1]
        params = {k: v for k, v in b.get("params", {}).items() if k == "type"}
        key = bench + "".join(f"_{v}" for v in params.values())
        if secondary:
            for sec, m in b["secondaryMetrics"].items():
                out[sec] = summary([v for fork in m["rawData"] for v in fork])
        else:
            out[key] = summary([v for fork in b["primaryMetric"]["rawData"] for v in fork])
    return out


def fmt(ms):
    m, e = ms
    digits = 0 if m >= 100 else 1 if m >= 10 else 2
    return f"{m:,.{digits}f} ± {e:,.{digits}f}"


def verdict(j, r, lower_is_better):
    (jm, je), (rm, re) = j, r
    if abs(jm - rm) <= je + re:
        return "tie"
    faster_java = jm < rm if lower_is_better else jm > rm
    ratio = max(jm, rm) / min(jm, rm)
    return f"**ffm-sqlite {ratio:.2f}×**" if faster_java else f"**rusqlite {ratio:.2f}×**"


def table(title, unit, rows, lower_is_better=True, note=None):
    lines = [f"### {title}", "", f"| Benchmark | ffm-sqlite ({unit}) | rusqlite ({unit}) | Result |", "|---|---:|---:|---|"]
    for label, j, r in rows:
        if j is None or r is None:
            lines.append(f"| {label} | {fmt(j) if j else '–'} | {fmt(r) if r else '–'} | – |")
        else:
            lines.append(f"| {label} | {fmt(j)} | {fmt(r)} | {verdict(j, r, lower_is_better)} |")
    if note:
        lines += ["", note]
    return "\n".join(lines) + "\n"


def main():
    parts = ["# Results: ffm-sqlite vs rusqlite", ""]

    j, r = java("workload"), rust("workload")
    names = ["pointSelectById", "pointSelectByIndexedEmail", "insertAutoCommit", "updateAutoCommit",
             "businessTransaction", "rangeScan100Rows", "joinGroupBy50Users", "prepareInsideLoop", "connectQueryClose"]
    rows = [(n, j.get(n), r.get(n)) for n in names]
    rows.insert(8, ("prepareInsideLoop (rusqlite `prepare_cached`)", j.get("prepareInsideLoop"), r.get("prepareInsideLoop_cached")))
    parts.append(table("Application workloads (WAL, synchronous=NORMAL, tmpfs)", "µs/op", rows,
                       note="`prepareInsideLoop`: ffm-sqlite caches every prepared statement automatically; rusqlite's "
                            "`prepare` parses every time, its opt-in `prepare_cached` uses an LRU cache."))

    j, r = java("concurrent"), rust("concurrent")
    rows = [(f"{t} thread{'s' if t > 1 else ''}", j.get(f"pointSelect_{t}thread{'s' if t > 1 else ''}"), r.get(f"pointSelect_{t}threads"))
            for t in (1, 4, 8)]
    parts.append(table("Concurrent point selects, one connection per thread", "ops/ms total", rows, lower_is_better=False))

    j, r = java("types"), rust("types")
    rows = []
    for op in ("read", "write"):
        for ty in ("INTEGER", "REAL", "TEXT", "BLOB"):
            rows.append((f"{op} {ty}", j.get(f"{op}_{ty}"), r.get(f"{op}_{ty}")))
    parts.append(table("Per data type, 1,000 rows (in-memory)", "ns/row", rows))

    j, r = java("udf"), rust("udf")
    pairs = [("Built-in expression (baseline)", "builtin", "builtinExpressionBaseline"),
             ("Scalar function, integer", "scalarLong", "scalarLong"),
             ("Scalar function, text", "scalarText", "scalarText"),
             ("Aggregate function", "aggregate", "aggregate"),
             ("Window function", "windowSliding", "windowFunction"),
             ("Built-in window (baseline)", "windowSlidingBuiltin", "windowBuiltinBaseline"),
             ("Collation, max()", "collationMax", "collationCompare"),
             ("Built-in NOCASE (baseline)", "collationMaxBuiltin", "collationBuiltinBaseline"),
             ("Collation, ORDER BY (per row)", "collationOrderBy", "collationOrderBy"),
             ("Scalar result per row", "scalarPerRowResult", "scalarPerRowResult")]
    parts.append(table("User-defined functions and collations (10,000 rows)", "ns/call",
                       [(label, j.get(jn), r.get(rn)) for label, jn, rn in pairs]))

    j, r = java("bulk"), rust("bulk")
    parts.append(table("Bulk load, 1M rows × 20 columns (WAL + FULL, tmpfs)", "s", [("Load", j.get("load"), r.get("bulkLoad1M"))]))

    rows = []
    for g in ("point", "range"):
        j, r = java(f"wide-{g}", secondary=True), rust(f"wide-{g}")
        rows.append((f"{g} reads, 4 readers", j.get(f"{g}Read"), r.get(f"wide_{g}_r4_read")))
        rows.append((f"{g}: writer commits", j.get(f"{g}Write"), r.get(f"wide_{g}_r4_write")))
    parts.append(table("1M rows: 1 writer + 4 readers (WAL + FULL, disk)", "ops/s", rows, lower_is_better=False))

    (RES / "report.md").write_text("\n".join(parts))
    print((RES / "report.md").read_text())


if __name__ == "__main__":
    main()
