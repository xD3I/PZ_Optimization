#!/usr/bin/env bash
# repro-ui (2026-10-16): the clips and stills for the maintainer, a queue `media` job. Cuts / stacks / slows to a 10-bit
# PQ FFV1 intermediate, then harness/encode-av1-hdr.sh (AV1 10-bit PQ / BT.2020). Stills are tone-mapped PNGs.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=/home/diegov/pzopt-wt/repro-1016/repro/ui
TMP=/tmp/claude-1000/repro-ui-clips; rm -rf "$TMP"; mkdir -p "$OUT" "$TMP"
RUNS=$PWD/harness/runs
DEF=$RUNS/ui-tips-ingame-20261016-152051          # defaults (8b0268f5 release)
NORET=$RUNS/ui-tips-ingame-noret-20261016-154357   # --prop uiRetained=false
MENU=$RUNS/ui-menu-fixture3-20261016-122922        # main menu with the re-layout fixture mod
FONT=/usr/share/fonts/noto/NotoSans-Regular.ttf
[[ -f $FONT ]] || FONT=$(fc-match -f '%{file}' sans)
TAGS=(-color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv)
TXT="fontfile=$FONT:fontcolor=0xB0B0B0:fontsize=34:box=1:boxcolor=0x101010@0.8:boxborderw=8"
TONE="zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=rgb24"

# 1. In-game Options tooltip flicker, A/B: the same pointer sweep over the Display tab's "Simple Clothing Textures" combo,
#    165 fps captures, 2.5 s slowed 4x. Top: defaults (tooltip alternates between the pointer and the screen's top-left
#    corner every frame); bottom: --prop uiRetained=false (steady).
ffmpeg -v error -y -hwaccel cuda -ss 5.0 -t 2.5 -i "$DEF/recording-hi.mp4" -hwaccel cuda -ss 5.0 -t 2.5 -i "$NORET/recording-hi.mp4" \
  -filter_complex "[0:v]crop=3600:1100:0:0,scale=1920:-2,format=yuv420p10le,drawtext=$TXT:x=20:y=h-60:text='8b0268f5 defaults - in-game Options tooltip (165 fps capture; 4x slower)'[a];\
[1:v]crop=3600:1100:0:0,scale=1920:-2,format=yuv420p10le,drawtext=$TXT:x=20:y=h-60:text='same build, --prop uiRetained=false'[b];\
[a][b]vstack,setpts=4*PTS,fps=41.25,format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv" \
  -c:v ffv1 -level 3 "${TAGS[@]}" "$TMP/tooltip-ab.mkv"
NOJPG=1 harness/encode-av1-hdr.sh "$TMP/tooltip-ab.mkv" "$OUT/tooltip-flicker-ab-4x.mp4"

# 2. The same moment in the run's own 60 fps recording, real time (what a player sees, sampled at 60 Hz).
ffmpeg -v error -y -hwaccel cuda -ss 21.5 -t 8 -i "$DEF/recording.mp4" \
  -vf "crop=3600:1100:0:0,scale=1920:-2,format=yuv420p10le,drawtext=$TXT:x=20:y=h-60:text='8b0268f5 defaults - real time (60 fps recording)'" \
  -an -c:v ffv1 -level 3 "${TAGS[@]}" "$TMP/tooltip-rt.mkv"
NOJPG=1 harness/encode-av1-hdr.sh "$TMP/tooltip-rt.mkv" "$OUT/tooltip-flicker-realtime.mp4"

# 3. Main menu: our SHOW / HIDE PERFORMANCE OVERLAY item over MODS once a menu mod lays the stock items out again; the
#    real pointer hovers MODS (our item lights up) and clicks it at ~14.8 s: the overlay toggles, Mods never opens.
ffmpeg -v error -y -hwaccel cuda -ss 9.5 -t 8 -i "$MENU/recording.mp4" \
  -vf "crop=1400:900:380:1130,format=yuv420p10le,drawtext=$TXT:x=20:y=20:text='main menu + a menu re-layout mod (fixture) - click on MODS'" \
  -an -c:v ffv1 -level 3 "${TAGS[@]}" "$TMP/menu.mkv"
NOJPG=1 harness/encode-av1-hdr.sh "$TMP/menu.mkv" "$OUT/menu-overlay-item-covers-mods.mp4"

# Stills: two consecutive 165 fps frames of the default run (tooltip at the top-left / at the pointer), and the menu.
ffmpeg -v error -y -hwaccel cuda -ss 19.1 -i "$DEF/recording-hi.mp4" -fps_mode passthrough \
  -vf "select='between(n,0,1)',crop=3600:1100:0:0,scale=1800:-2,$TONE,tile=1x2" -frames:v 1 "$OUT/tooltip-flicker-two-frames.png"
ffmpeg -v error -y -hwaccel cuda -ss 14.0 -i "$MENU/recording.mp4" -vf "crop=1400:900:380:1130,$TONE" -frames:v 1 "$OUT/menu-overlay-item-covers-mods.png"
ls -la "$OUT"
