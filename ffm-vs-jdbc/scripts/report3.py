#!/usr/bin/env python3
"""Three-way Markdown report: sqlite-jdbc vs ffm-sqlite (direct API) vs ffm-sqlite-jdbc (the JDBC wrapper).

usage: report3.py results/three-way.json > results/three-way.md

Speedups are against sqlite-jdbc (> 1 = faster; throughput modes inverted). "Wrapper cost" is how much slower the
JDBC wrapper is than calling ffm-sqlite directly (negative = faster, i.e. noise).
"""
import json
import math
import sys
from collections import OrderedDict

LIBS = ("jdbc", "ffm-sqlite", "ffm-sqlite-jdbc")
TITLES = OrderedDict([
    ("WorkloadBenchmark", "Application workloads (file DB in WAL mode, on tmpfs)"),
    ("ConcurrentReadBenchmark", "Concurrent reads (own connection per thread; throughput)"),
    ("TypeBenchmark", "Per data type, 1,000 rows"),
])


def fmt(x):
    return f"{x:,.0f}" if x >= 1000 else f"{x:.0f}" if x >= 100 else f"{x:.1f}" if x >= 10 else f"{x:.2f}"


def geomean(xs):
    return math.exp(sum(math.log(x) for x in xs) / len(xs))


def main():
    pairs = OrderedDict()
    for b in json.load(open(sys.argv[1])):
        cls, method = b["benchmark"].split(".")[-2:]
        params = dict(b["params"])
        lib = params.pop("lib")
        key = (cls, method, ", ".join(f"{k}={v}" for k, v in sorted(params.items())))
        pm = b["primaryMetric"]
        alloc = b.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
        pairs.setdefault(key, {})[lib] = (b["mode"], pm["score"], pm["scoreError"], pm["scoreUnit"], alloc)

    summary, body = [], []
    for cls, title in TITLES.items():
        rows = [(k, v) for k, v in pairs.items() if k[0] == cls and all(l in v for l in LIBS)]
        if not rows:
            continue
        body.append(f"\n### {title}\n")
        body.append("| Benchmark | Params | sqlite-jdbc | ffm-sqlite | ffm-sqlite-jdbc | Unit | ffm-sqlite vs jdbc | wrapper vs jdbc | wrapper cost vs ffm-sqlite |")
        body.append("|---|---|---:|---:|---:|---|---:|---:|---:|")
        s_ffm, s_wrap, costs = [], [], []
        for (c, method, params), v in rows:
            mode, j, je, unit, _ = v["jdbc"]
            _, f, fe, _, _ = v["ffm-sqlite"]
            _, w, we, _, _ = v["ffm-sqlite-jdbc"]
            thr = mode == "thrpt"
            sf = f / j if thr else j / f
            sw = w / j if thr else j / w
            cost = (f / w - 1) if thr else (w / f - 1)
            s_ffm.append(sf)
            s_wrap.append(sw)
            costs.append(cost)
            body.append(f"| {method} | {params} | {fmt(j)} ± {fmt(je)} | {fmt(f)} ± {fmt(fe)} | {fmt(w)} ± {fmt(we)} | {unit} | "
                        f"{sf:.2f}× | {sw:.2f}× | {cost * 100:+.0f}% |")
        summary.append(f"| {title} | {len(rows)} | {geomean(s_ffm):.2f}× | {geomean(s_wrap):.2f}× | "
                       f"{(geomean([1 + c for c in costs]) - 1) * 100:+.1f}% |")

    print("| Category | Benchmarks | ffm-sqlite vs sqlite-jdbc (geo-mean) | ffm-sqlite-jdbc vs sqlite-jdbc (geo-mean) | Wrapper cost vs ffm-sqlite (geo-mean) |")
    print("|---|---:|---:|---:|---:|")
    print("\n".join(summary))
    print("\n".join(body))


if __name__ == "__main__":
    main()
