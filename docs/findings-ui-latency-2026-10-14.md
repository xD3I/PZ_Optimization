# UI latency pass (2026-10-14)

Maintainer's question: is there input lag when the UI is rendered at another rate than the game? Then: profile every UI
interaction, and fix (1) pointer movement over a window re-rendering it at most 60 times a second (`uiHoverHz`) and (2)
passive changes (an item renamed, a stat, XP arriving) reaching a backed-off window only after up to `uiRetainedMaxMs`
(1 s), "with cutting-edge techniques". Mid-pass: every run includes a big stack of items (200). Worktree
`~/pzopt-wt/ui-latency`, branch `ui-latency` (origin/master 24f32e44).

## The ground truth rig

`devUiTruth` (`pzopt.UiRetained`): the UI renders on every frame with every top-level element fresh — what the screen should
show — while the frame decision of the uiRetained schedule and stock's `uiRenderFPS` accumulator are simulated beside it
(each element's "shown" output hash: kept on a frame the schedule would not render, its recording on a replay, its fresh
output on a fresh render). A frame on which an element's shown hash differs from its truth hash is stale; a stretch of stale
frames is an episode, logged when it ends with the cause seen on its first frame (input = click / key, event = UI Lua event,
hover = the pointer moved over the element, mouse = it moved elsewhere, passive = none of these). Children are not retained
in truth mode (a top-level fresh render is the truth for its subtree); renders the schedule would not have made are kept out
of its learning (table writes, noise, slack accounting). `uiprof.py` prints the episodes and the stale-frame share per scene,
`uiprof-compare.py` tabulates runs. Limits: the truth renders every frame, so a render with side effects per call (none
found) would differ; the episode length is in game frames (4.17 ms at the 240 cap).

The rig (`ui_script=latency`, harness Lua): 5 s scenes driven like a player — the pointer through the showcase aim
override, the left button and the wheel through new rig hooks in `zombie.input.Mouse` (`pzoptUiButton`, `pzoptUiWheel`), so
UIManager dispatches presses, drags and wheel steps as for real input: HUD, HUD with stats changing, inventory, inventory
items renamed / favourited / worn, hover over inventory / loot / container-button rows, a resting item tooltip that follows
the pointer, wheel scrolling, an item dragged to the loot window and back, the inventory window dragged in a circle, a world
context menu swept, the crafting list swept, the skills panel with XP arriving, the map panned by dragging, then a stack of
200 nails (`ui_stack_items`, `ui_stack_type`) collapsed, expanded, hovered, scrolled through and renamed in part. Bench save
standing still, zoom max, zombies off, 240 cap with vsync and VRR off on the desktop (5120x2160, 165 Hz panel),
`uiRenderOffscreen=true uiRenderFPS=60` (stock defaults), an empty options file; arguments in `harness/ui-latency-args.sh.txt`.

## Baseline: what was late, and by how much

Runs `uil-truth-base` (origin/master build, no stack) and `uil7-truth-prev` (this build with every new key off, 200-item
stack). Share of frames on which some visible element differed from the truth, and episode lengths:

| interaction | stock 60 Hz | uiRetained before |
|---|---|---|
| hover over inventory rows | 23 % (p50 12.5 ms) | 25 % (p50 16.6 ms: worse than stock, the 60 Hz hover clock ran out of phase) |
| hover over a 200-item stack | 28 % | 29 % |
| wheel scrolling (smooth scroll) | 41 % | 43 % |
| scrolling through the 200-item stack | 56 % | 58 % |
| dragging an item | 62 % | 62 % |
| dragging a window | 61 % | 62 % |
| item tooltip following the pointer | 21 % | 22 % |
| inventory items changing (no event) | 9 % | 43 % (p50 208-350 ms, p95 950 ms) |
| skills panel, XP arriving | 1 % | 61 % (p50 760-800 ms, up to 1 s) |
| renamed items inside the 200 stack | 0.2 % | 19 % |
| vehicle dashboard, clock | ≤ 16.7 ms | up to 835 ms / 985 ms |

A click or key reached the screen in the same frame with uiRetained (stock waits for the next 60 Hz slot, 1-2 frames). So
yes: when the UI renders at another rate than the game there is lag, 8 ms on average at 60 Hz for anything continuous,
and with the 2026-09-30 retained UI up to a second for changes nobody clicked for.

## What was built

1. **Read sets (`uiVerify`, `pzopt.UiDeps`).** While an element renders fresh, everything its render reads is logged: every
   Lua table field it reads with the value it got (`KahluaTableImpl.rawget` with its class lookup, `#`, `pairs`), and every
   Lua -> Java call (`MethodCaller.call`: method, receiver, arguments, result). A child's log is part of its parent's (also a
   replayed child's). Each frame the log is validated instead of trusting a timer: a table whose write counter or class chain
   changed has the fields the render read re-read and compared; getters (get / is / has / can / are / contains / size /
   Measure / clamp ..., named) are re-invoked in order and their results compared (an argument from an earlier call is then
   unchanged too); updaters the render calls (`updateAge`, `updateWetness`) run again before the reads after them; what
   `UIElement.DrawItemIcon` reads of its item (texture, tint, masks, fluid colour and level) and what an unfocused text box
   shows (text, cursor, editable) are signatures. Anything else with a result (a random number, a remove), a void call
   passing a game object, a throw, or a Java-rendered element (Clock, MoodlesUI, a focused text box, the map) makes the log
   opaque: that element renders at the stock rate at the latest (no back-off any more), never later than stock. The
   technique is the validation half of incremental computation (Adapton / Salsa's red-green verification: re-check the
   inputs of a memoised result, recompute only if one changed) applied to an immediate-mode Lua UI, with early cutoff below.
2. **The pointer as a read.** `getMouseX` / `getMouseY` are getters like the others: a log whose only changed reads are
   pointer reads reports MOUSE, and the element renders that frame if the pointer matters to it (over it or just left it,
   capture, it positions itself from the pointer, or a button pressed over it is still held: a drag). `isMouseOver()` is a
   plain getter, so an element that only asks "is the pointer over me" re-renders only when the answer flips.
3. **Learned noise (early cutoff).** A re-render caused by a changed read that drew exactly its recording teaches that the
   read (a getter, a pointer read, one table field) does not matter at this granularity: it is then checked at the stock rate
   only, and unlearned the moment such a check leads to a different picture. Clock reads (`getMillisSinceLastRender`,
   timestamps) start as noise: clock-driven animation keeps the stock rate, data changes show at once. Field granularity
   matters: learning whole tables let one noisy field (a dashboard timer) hide the pane's hover row (`mouseOverOption`).
4. **Motion (`uiAnimate`).** An element whose last fresh render differed from the one before is in motion (smooth scrolling,
   a dragged item's ghost, a fade): it renders on the next frame too; one identical render ends it.
5. **Frame-slack scheduling (`uiSlack`).** Re-renders nobody clicked for (pointer, motion, read-set changes) are granted
   only from the time the frame has left before the cap: the step length without the UI's own fresh renders
   (`FrameCap.stepDone`) against the cap interval x 0.85, with the measured UI-frame overhead; uncapped, a fifth of the step.
   An element left waiting renders once the stock interval has passed (stock is the floor). `uiMaxHz` (0 = the display
   refresh) keeps such UI frames on a refresh tick grid.
6. **Compositor-style move (`uiMoveReplay`).** A window that, after a move, re-rendered its recording shifted by the move
   (learned per window, glyph positions within 1 px: the game rounds text to whole pixels) is replayed shifted while it is
   dragged instead of running its Lua every frame, and still renders fresh at the stock rate.

## Results

Final build (v13). Cost runs `uil13-cost-new` (defaults), `uil13-cost-prev` (same build, every new key off: the
2026-09-30 behaviour plus the inventory-flag fix), `uil6-cost-stock` (`uiRetained=false`: stock's 60 Hz UI). Truth runs
`uil12-truth-new` (defaults, 240 fps on the 165 Hz panel: optional UI frames on the 165 Hz tick grid), `uil12-truth-nocap`
(`uiMaxHz=-1`: the precision of the read sets), `uil7-truth-prev` (new keys off); "stale % stock*" is stock's 60 Hz
schedule simulated in the prev truth run. UI % = UIManager update + render as a share of the frame; every run has the
200-item stack in the main inventory.

| scene | UI % stock | UI % prev | UI % new | UIfr/s stock | UIfr/s prev | UIfr/s new | fps stock | fps prev | fps new | stale % stock* | stale % prev | stale % new | stale % nocap |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| lat_hud | 2.67 | 1.22 | 2.30 | 60 | 53 | 52 | 240 | 240 | 240 | 0.1 | 0.1 | 0.4 | 0.0 |
| lat_hud_stats | 2.58 | 4.40 | 5.13 | 60 | 128 | 152 | 240 | 240 | 240 | 31.1 | 18.8 | 15.0 | 10.7 |
| lat_inv | 8.13 | 1.76 | 2.33 | 60 | 52 | 52 | 240 | 240 | 240 | 0.3 | 12.3 | 0.1 | 0.1 |
| lat_inv_passive | 8.09 | 2.96 | 3.85 | 60 | 53 | 57 | 240 | 240 | 240 | 8.7 | 42.6 | 2.7 | 0.4 |
| lat_inv_hover | 9.99 | 7.56 | 11.49 | 60 | 53 | 100 | 240 | 240 | 240 | 23.0 | 25.3 | 8.7 | 0.0 |
| lat_loot_hover | 8.82 | 3.05 | 4.40 | 60 | 52 | 52 | 240 | 240 | 240 | 8.2 | 8.6 | 4.2 | 0.2 |
| lat_btn_hover | 8.89 | 2.69 | 4.73 | 60 | 53 | 99 | 240 | 240 | 240 | 15.0 | 15.9 | 7.2 | 0.3 |
| lat_tooltip | 9.59 | 6.44 | 9.87 | 60 | 53 | 72 | 240 | 240 | 240 | 21.1 | 22.3 | 8.9 | 0.0 |
| lat_scroll | 8.08 | 8.24 | 17.25 | 60 | 56 | 149 | 240 | 240 | 240 | 41.1 | 42.8 | 15.8 | 0.1 |
| lat_drag_item | 8.99 | 6.35 | 16.58 | 60 | 55 | 151 | 240 | 240 | 240 | 61.8 | 62.4 | 25.5 | 0.1 |
| lat_window_drag | 8.69 | 6.80 | 9.77 | 60 | 54 | 151 | 240 | 240 | 240 | 60.7 | 62.4 | 23.8 | 0.0 |
| lat_ctx_hover | 2.19 | 0.92 | 1.85 | 60 | 52 | 52 | 240 | 240 | 240 | 0.1 | 0.1 | 0.0 | 0.0 |
| lat_craft_hover | 15.70 | 8.31 | 15.01 | 60 | 52 | 122 | 240 | 240 | 240 | 11.6 | 13.7 | 12.5 | 13.3 |
| lat_skills_xp | 7.17 | 1.19 | 4.41 | 60 | 53 | 101 | 240 | 240 | 240 | 1.0 | 60.8 | 0.3 | 0.7 |
| lat_map_pan | 23.68 | 20.55 | 21.57 | 60 | 54 | 52 | 234 | 240 | 233 | 40.6 | 41.7 | 33.9 | 33.8 |
| lat_stack_collapsed | 7.51 | 1.22 | 2.11 | 60 | 52 | 51 | 240 | 240 | 240 | 0.0 | 0.0 | 0.0 | 0.0 |
| lat_stack | 7.48 | 1.21 | 2.00 | 60 | 52 | 57 | 240 | 240 | 240 | 3.8 | 4.3 | 1.2 | 0.8 |
| lat_stack_hover | 9.07 | 6.07 | 10.00 | 60 | 52 | 104 | 240 | 240 | 240 | 28.0 | 29.2 | 10.3 | 0.0 |
| lat_stack_scroll | 7.61 | 8.19 | 15.08 | 60 | 58 | 143 | 240 | 240 | 240 | 55.7 | 58.4 | 22.1 | 0.0 |
| lat_stack_passive | 7.31 | 1.20 | 1.93 | 60 | 52 | 51 | 240 | 240 | 240 | 0.2 | 19.1 | 0.0 | 0.1 |
| lat_end | 7.26 | 1.10 | 1.82 | 60 | 52 | 51 | 240 | 240 | 240 | 0.0 | 0.2 | 0.1 | 0.0 |

- **Item 1 (pointer, drags, motion):** without the display cap every continuous interaction is current on the frame it
  happens: hover over rows 25-29 % stale frames -> 0.0, item drag 62 -> 0.1, window drag 62 -> 0.0, wheel scrolling 43 ->
  0.1, scrolling through the 200-item stack 58 -> 0.0, the tooltip 22 -> 0.0. With the defaults on this 165 Hz panel the
  remaining 9-26 % are game frames between two refresh ticks: the UI updates at the panel's 165 Hz while the game runs 240.
- **Item 2 (passive changes):** items changing in an open inventory 43 % -> 0.4 %, XP arriving in the skills panel 61 % ->
  0.7 %, renamed items inside the 200 stack 19 % -> 0.1 %, and opaque elements (clock, moodles, dashboard gauges) render
  at the stock rate at the latest instead of backing off to 1 s.
- **Not reached:** the map while panning (34 %: one map render is 3.3 ms, more than the frame's slack at 240, so it stays at
  the stock rate) and the crafting list hover (13 %: the recipe list's render is ~1.3 ms; stock rate when the slack is short).
  HUD stats (11 %) are the Java moodles at the stock rate.
- **Cost:** the frame rate held at the 240 cap in every scene (slack scheduling). Static scenes cost +0.5-1 point of the
  frame over the previous behaviour (read-set validation each frame; still far below stock's 7-8 %); interactions cost what
  rendering the moving window at the display rate costs: hover 7.6 -> 11.5 %, wheel scrolling 8.2 -> 17.3 %, item drag
  6.4 -> 16.6 %, window drag 6.8 -> 9.8 % (move replay: 20 % without it), crafting 8.3 -> 15.0 %.
- **Uncapped** (`uil11-unc-prev` / `uil11-unc-new`, 700 fps): 706-720 -> 698-712 fps in the UI scenes (about -1 %), the
  extra renders limited to a fifth of the step.
- Read-set statistics (V lines): ~6,000-12,000 validations a second, 6-30 ms of validation a second (getters at a
  cost-scaled period, tables and the pointer every frame); move replays 111-150 a second while dragging a window.

## Dead ends and pitfalls

- A global "class table changed" version: some class field (ISInventoryPage.renderDirty) is written ~100 times a second.
- Table-level write counters and table-level noise: tables are written every frame by updates (the dashboard, the
  character window) with fields the render never reads; one noisy field hid others (hover rows). Field-level read sets fixed
  both.
- Java UI elements have empty Lua read sets: "valid forever" until made opaque (the Clock was 985 ms late).
- A display cap written as "one refresh since the last UI frame" gives every second 240 fps frame (120 Hz on 165 Hz); a tick
  grid is needed. The frame cap pacer's `limiterWait` runs several times per frame: step length comes from `stepDone`.
- Validating every getter every frame cost up to 54 ms a second (a 200-row pane re-invokes ~1,000 getters); getters are now
  re-invoked at a period keeping their cost near 1 % (never longer than the stock interval), tables and the pointer each frame,
  class-chain sums cached per class version.
- The truth rig's own renders must stay out of the schedule's accounting (they starved the slack budget in one round) and
  out of its learning; the harness Lua is read live from the worktree (a run queued before an edit runs the edited file).
- Not done: scrolling by translating a pane's recording (rows entering at the edges need an overscan render: a compositor's
  scroll layers); the map's own render (3.3 ms) is larger than the frame's slack at 240, so map panning stays at the stock
  rate; the drag ghost re-renders its whole pane each frame (a cursor-attached layer would be the next step).
