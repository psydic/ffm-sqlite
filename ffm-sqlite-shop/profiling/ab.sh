#!/bin/bash
# A/B: WAL growth with and without periodic TRUNCATE checkpoints. 180 s of load each, no profiler.
set -u
S=$(dirname "$0"); cd "$S"; mkdir -p out
run() {   # $1 = label, $2 = checkpoint interval in seconds (0 = none)
  local label=$1 every=$2 C=shop-ab
  rm -rf data-$label; mkdir -p data-$label
  podman rm -f $C >/dev/null 2>&1
  podman run -d --name $C --cpus 4 --cpuset-cpus 2-5 --memory 1g -p 18080:8080 -v "$PWD/data-$label:/data:Z" ffm-sqlite-shop:prof >/dev/null
  until podman logs $C 2>&1 | grep -q "ffm-sqlite shop:"; do sleep 1; done
  sqlite3 data-$label/shop.db "PRAGMA busy_timeout=5000; UPDATE product SET stock = 1000000;"
  echo "t_s,wal_mb,rss_mb" > out/ab-$label.csv
  ( t0=$(date +%s); while podman exec $C true 2>/dev/null; do
      wal=$(stat -c %s data-$label/shop.db-wal 2>/dev/null || echo 0)
      rss=$(podman exec $C awk '/VmRSS/ {print $2}' /proc/1/status 2>/dev/null)
      echo "$(( $(date +%s) - t0 )),$((wal / 1048576)),$((rss / 1024))" >> out/ab-$label.csv
      if [ "$every" -gt 0 ]; then sqlite3 data-$label/shop.db "PRAGMA busy_timeout=2000; PRAGMA wal_checkpoint(TRUNCATE);" >> out/ab-$label-checkpoints.txt 2>&1; fi
      sleep "${every/#0/5}"
    done ) & local SAMPLER=$!
  taskset -c 8-15 java LoadGen.java http://localhost:18080/api 32 180 > out/ab-$label-load.txt 2>&1
  podman stop $C >/dev/null; wait $SAMPLER; podman rm -f $C >/dev/null
}
run baseline 0
run checkpoint 5
echo done
