#!/usr/bin/env bash
# Workshop description animations of the stitch-efficiency.py videos (2026-10-06): a 10 s window of each side-by-side as
# animated AVIF (60 fps, 1260 px wide, harness/anim-encode.py; CRF 30 / 35 / 40 scored, 35 copied to workshop-media/) and the
# results card as a still JPG. The tone-map is the headline GIF's (hable, 200 nits). Run as a queue `media` job.
#
#   harness/efficiency-anim.sh [name ...]     names: the workshop-media/<name>.mp4 below (default: all)
set -euo pipefail
cd "$(dirname "$0")/.."

declare -A T0=(
  [flip-efficient-cores-vs-stock-60fps]=12
  [flip-max-efficiency-vs-stock]=12
  [mac-opengl-2.1-vs-4.1-day]=4
  [dell-low-end-vs-stock]=26
)
LEN=10
TONEMAP='zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709'
names=("$@")
[[ ${#names[@]} -gt 0 ]] || names=("${!T0[@]}")

for n in "${names[@]}"; do
  in="workshop-media/$n.mp4"
  [[ -f "$in" && -n "${T0[$n]:-}" ]] || { echo "skip $n (no video or no window)"; continue; }
  work="build/anim/$n"
  rm -rf "$work"; mkdir -p "$work/frames"
  ffmpeg -hide_banner -v error -y -ss "${T0[$n]}" -t "$LEN" -i "$in" \
    -vf "fps=60,$TONEMAP,scale=2520:-2:flags=lanczos,format=rgb24" "$work/frames/f%05d.png"
  echo "== $n: $(ls "$work/frames" | wc -l) frames from +${T0[$n]} s"
  python3 harness/anim-encode.py --frames "$work/frames" --src-fps 60 --out "$work" \
    "avif:60:1260x-2:crf=30" "avif:60:1260x-2:crf=35" "avif:60:1260x-2:crf=40"
  cp "$work/avif_60_1260x-2_crf=35.avif" "workshop-media/$n.avif"
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$in")
  ffmpeg -hide_banner -v error -y -ss "$(python3 -c "print($dur - 3)")" -i "$in" -frames:v 1 \
    -vf "$TONEMAP,scale=1260:-2:flags=lanczos,format=yuv420p" -q:v 2 "workshop-media/$n-results.jpg"
  rm -rf "$work/frames"
done
ls -la workshop-media/*.avif workshop-media/*-results.jpg
