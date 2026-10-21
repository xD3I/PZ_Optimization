#!/bin/bash
# tail2: run.sh --wrap prefix: PR_SET_THP_DISABLE (prctl 41) for the game process (inherited across exec), then the game.
# Tests whether the game's transparent-huge-page faults (THP=always, defrag=defer+madvise on the desktop) wake kswapd,
# which then swaps the game's own pages out with 15 GB free and the frames page them back in (major faults).
exec python3 -c 'import ctypes, os, sys
libc = ctypes.CDLL(None, use_errno=True)
r = libc.prctl(41, 1, 0, 0, 0)
sys.stderr.write("nothp-wrap: prctl(PR_SET_THP_DISABLE) = %d\n" % r)
os.execvp(sys.argv[1], sys.argv[1:])' "$@"
