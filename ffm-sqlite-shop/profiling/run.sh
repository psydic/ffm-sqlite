#!/bin/bash
# Profiles the shop under load. Output in out/ (mounted at /data/out in the container).
set -u
S=$(dirname "$0"); cd "$S"
rm -rf data out; mkdir -p data out
C=shop-prof
podman rm -f $C >/dev/null 2>&1
podman run -d --name $C --cpus 4 --cpuset-cpus 2-5 --memory 1g -p 18080:8080 -v "$PWD/data:/data:Z" -v "$PWD/out:/data/out:Z" ffm-sqlite-shop:prof >/dev/null
until podman logs $C 2>&1 | grep -q "ffm-sqlite shop:"; do sleep 1; done
podman logs $C 2>&1 | grep "ffm-sqlite shop:" | tee out/startup.txt
sqlite3 data/shop.db "PRAGMA busy_timeout=5000; UPDATE product SET stock = 1000000;"
ex() { podman exec $C "$@"; }
sample() {   # every 5 s: RSS, NMT committed total, heap used, GC count
  echo "t_s,rss_kb,nmt_committed_kb,heap_used_kb,young_gcs,full_gcs,threads,open_fds" > out/memory.csv
  local t0=$(date +%s)
  while [ -f out/.sampling ]; do
    rss=$(ex awk '/VmRSS/ {print $2}' /proc/1/status)
    nmt=$(ex jcmd 1 VM.native_memory summary 2>/dev/null | awk '/^Total:/ {for(i=1;i<=NF;i++) if($i ~ /^committed=/){sub("committed=","",$i); sub("KB","",$i); print $i}}')
    gc=$(ex jstat -gc 1 2>/dev/null | tail -1)
    heap=$(echo "$gc" | awk '{printf "%d", $4+$6+$8}')   # S0U + S1U + EU ... approximate young used
    old=$(echo "$gc" | awk '{printf "%d", $10}')
    ygc=$(echo "$gc" | awk '{print $13}'); fgc=$(echo "$gc" | awk '{print $15}')
    thr=$(ex ls /proc/1/task | wc -l); fds=$(ex ls /proc/1/fd | wc -l)
    echo "$(( $(date +%s) - t0 )),$rss,$nmt,$((heap + old)),$ygc,$fgc,$thr,$fds" >> out/memory.csv
    sleep 5
  done
}
touch out/.sampling; sample & SAMPLER=$!
echo "== warmup 60 s"; java LoadGen.java http://localhost:18080/api 32 60 > out/load-warmup.txt 2>&1
ex jcmd 1 VM.native_memory baseline > /dev/null
ex sh -c 'cat /proc/1/smaps_rollup' > out/smaps-before.txt
ex /opt/async-profiler/bin/asprof start -e itimer --alloc 512k --lock 1ms --nativemem 32k -f /data/out/profile.jfr 1 > out/asprof-start.txt 2>&1
echo "== load 480 s (profiled)"; taskset -c 8-15 java LoadGen.java http://localhost:18080/api 32 480 > out/load-main.txt 2>&1
ex /opt/async-profiler/bin/asprof stop 1 > out/asprof-stop.txt 2>&1
ex jcmd 1 VM.native_memory summary.diff > out/nmt-summary-diff-load.txt
ex jcmd 1 VM.native_memory detail.diff > out/nmt-detail-diff-load.txt
ex sh -c 'cat /proc/1/smaps_rollup' > out/smaps-after-load.txt
ex sh -c 'cat /proc/1/smaps' > out/smaps-after-load-full.txt
ex jcmd 1 Thread.print > out/threads-after-load.txt
echo "== idle 90 s"; sleep 90
ex jcmd 1 VM.native_memory summary.diff > out/nmt-summary-diff-idle.txt
ex sh -c 'cat /proc/1/smaps_rollup' > out/smaps-after-idle.txt
rm out/.sampling; wait $SAMPLER
ex sh -c 'ls -la /data' > out/data-files.txt
for kind in "--cpu cpu" "--alloc alloc" "--lock lock" "--nativemem nativemem-total" "--nativemem --leak nativemem-leak"; do
  set -- $kind; name=${!#}; opts=${kind% *}
  ex /opt/async-profiler/bin/jfrconv $opts /data/out/profile.jfr /data/out/$name.html > /dev/null 2>&1
  ex /opt/async-profiler/bin/jfrconv $opts --total -o collapsed /data/out/profile.jfr /data/out/$name.collapsed > /dev/null 2>&1
done
podman logs $C > out/container.log 2>&1
podman stop $C > /dev/null
echo "== done"
