#!/usr/bin/env bash
# tail2: read-only memory watcher. Waits for the next game process, then logs every second until it exits:
# epoch_ms VmSwap_kB VmRSS_kB game_majflt sys_pswpin sys_pswpout MemFree_kB MemAvailable_kB zfs_arc_size_MB
# memwatch.sh <out-file> [max-seconds]
out=$1; max=${2:-3600}
end=$(( $(date +%s) + max ))
while [ "$(date +%s)" -lt "$end" ]; do
while [ "$(date +%s)" -lt "$end" ]; do
  P=""
  for q in $(pgrep -f 'ProjectZomboid64'); do [ "$(cat /proc/$q/comm 2>/dev/null)" = "ProjectZomboid6" ] && P=$q; done
  [ -n "$P" ] && break
  sleep 1
done
[ -z "$P" ] && exit 0
echo "# pid $P $(date) $(tr '\0' ' ' < /proc/$P/cmdline | grep -o 'tail1-jvm-[^ ]*\.log' | head -1)" >> "$out"
while [ -d /proc/$P ]; do
  sw=$(awk '/^VmSwap/{print $2}' /proc/$P/status 2>/dev/null); rss=$(awk '/^VmRSS/{print $2}' /proc/$P/status 2>/dev/null)
  mf=$(awk '{print $12}' /proc/$P/stat 2>/dev/null)
  si=$(awk '/^pswpin /{print $2}' /proc/vmstat); so=$(awk '/^pswpout /{print $2}' /proc/vmstat)
  fr=$(awk '/^MemFree:/{print $2}' /proc/meminfo); av=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
  arc=$(awk '$1=="size"{print int($3/1048576)}' /proc/spl/kstat/zfs/arcstats 2>/dev/null)
  thr=$(for t in /proc/$P/task/*; do n=$(tr ' ' '_' < $t/comm 2>/dev/null); f=$(sed 's/.*) //' $t/stat 2>/dev/null | awk '{print $10}'); echo "$n $f"; done | awk '{gsub(/[#0-9-]+$/,"",$1); a[$1]+=$2} END{for(k in a) if(a[k]>0) printf "%s=%d,", k, a[k]}')
  echo "$(date +%s%3N) $sw $rss $mf $si $so $fr $av $arc $thr" >> "$out"
  sleep 1
done
done
