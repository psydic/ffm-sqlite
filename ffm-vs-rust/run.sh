#!/usr/bin/env bash
# Runs the rusqlite benchmarks and the matching ffm-sqlite JMH benchmarks back to back, suite by suite, pinned to
# the same cores and on the same SQLite build (../ffm-vs-jdbc/native/libsqlite3.so). Results go to results/.
# Usage: ./run.sh [suite...]   (default: workload concurrent types udf bulk wide)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
LIB=$ROOT/../ffm-vs-jdbc/native/libsqlite3.so
JAR=$ROOT/../ffm-vs-jdbc/target/benchmarks.jar
DATA=$ROOT/../ffm-vs-jdbc/data           # holds wide-1m.db, the 1M-row template (built by the Java benchmarks)
CORES=2-15
FORKS=2
export LD_LIBRARY_PATH=$ROOT/../ffm-vs-jdbc/native
cargo build --release -q
RUST=$ROOT/target/release/ffm-vs-rust
mkdir -p results

jmh() {   # jmh <name> <jmh args...>
    local name=$1; shift
    taskset -c $CORES java -jar "$JAR" "$@" -p lib=ffm-sqlite -rf json -rff "results/java-$name.json" \
        -jvmArgsAppend "-Dffmsqlite.lib=$LIB -Djava.io.tmpdir=/dev/shm -Dwide.dir=${WIDE_DIR:-$DATA}" \
        > "results/java-$name.txt" 2>&1
}
rust() {  # rust <name> <args...>: FORKS separate processes
    local name=$1; shift
    : > "results/rust-$name.txt"
    for f in $(seq $FORKS); do
        taskset -c $CORES "$RUST" "$@" >> "results/rust-$name.txt" 2>&1
    done
}

for suite in "${@:-workload concurrent types udf bulk wide}"; do
  for s in $suite; do
    echo "== $s $(date +%T)"
    case $s in
        workload)   jmh workload WorkloadBenchmark -f $FORKS;              rust workload workload ;;
        concurrent) jmh concurrent ConcurrentReadBenchmark -f $FORKS;      rust concurrent concurrent ;;
        types)      jmh types TypeBenchmark -p type=INTEGER,REAL,TEXT,BLOB -f $FORKS; rust types types ;;
        udf)        jmh udf UdfBenchmark -f $FORKS;                        rust udf udf ;;
        bulk)       mkdir -p /dev/shm/ffm-vs-rust-bulk
                    WIDE_DIR=/dev/shm/ffm-vs-rust-bulk jmh bulk BulkLoadBenchmark -f $FORKS
                    rust bulk bulk /dev/shm/ffm-vs-rust-bulk "$DATA/wide-1m.db"
                    rm -rf /dev/shm/ffm-vs-rust-bulk ;;
        wide)       for g in point range; do
                        jmh wide-$g "WideConcurrentBenchmark.$g" -tg 4,1 -f $FORKS -wi 8
                        WIDE_WARMUP=8 rust wide-$g wide "$DATA" "$DATA/wide-1m.db" 4 $g
                    done ;;
    esac
  done
done
echo "== done $(date +%T)"
