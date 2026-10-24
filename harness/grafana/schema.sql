-- Metrics DB for the harness runs (harness/grafana/stack.sh applies it on every `up`; idempotent).
SET client_min_messages = warning;
-- Every per-sample table carries
--   t     the wall-clock instant (timestamptz),
--   rel_s seconds since the run's route start (negative = settle / load before it),
--   rt    "route time": 2000-01-01 00:00:00 UTC + rel_s, so the Run and Compare dashboards put every
--         run on one x axis (00:00:00 = route start, 23:59:xx the day before = the settle).

CREATE TABLE IF NOT EXISTS runs (
  run text PRIMARY KEY,
  label text, machine text, started timestamptz, mode text, preset text, route text, zoom text,
  resolution text, opengl text, gpu text, jvm text, platform text, variant text, props text, flags text,
  enabled boolean, launcher text,
  route_start timestamptz, route_end timestamptz, route_seconds double precision,
  valid boolean, verdict text, verdict_confidence double precision, headroom_finding double precision,
  fps_mean double precision, frame_mean_ms double precision, p50_ms double precision, p90_ms double precision,
  p99_ms double precision, p99_9_ms double precision, max_ms double precision,
  over_33ms integer, over_50ms integer, over_100ms integer,
  presented_p99_ms double precision, presented_p99_9_ms double precision,
  jitter_ms double precision, stdev_ms double precision, fps_1pct_low double precision, under_cap_pct double precision,
  gpu_ms_mean double precision, game_load_pct double precision, render_load_pct double precision,
  cpu_pct double precision, busiest_core_pct double precision, gpu_pct double precision, gpu_p90_pct double precision,
  gpu_w double precision, gpu_c double precision, vram_mib double precision, process_cores double precision,
  game_thread_pct double precision, render_thread_pct double precision,
  chunk_p50_ms double precision, chunk_p90_ms double precision, chunk_p99_ms double precision,
  chunks_per_s double precision, gc_events integer, gc_wall_ms double precision, gc_max_ms double precision,
  zombies_loaded integer,
  scenario jsonb, opts jsonb, summary jsonb, judge jsonb,
  path text, files_mtime double precision, ingested timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS runs_started ON runs (started);
-- route-window power (2026-09-24): the best source of the run (sysmon, else the game's pzopt-power.out, else macpower)
ALTER TABLE runs ADD COLUMN IF NOT EXISTS cpu_w double precision, ADD COLUMN IF NOT EXISTS total_w double precision,
  ADD COLUMN IF NOT EXISTS j_per_frame double precision, ADD COLUMN IF NOT EXISTS power_source text;
-- read by the home / machine dashboards instead of the events and the opts jsonb (2026-10-21): boot = launch to "continuing
-- latest save", load = that to the first "world ready" after it (pzopt-loadtrace.out), run_s = opts run_seconds (launch to exit)
ALTER TABLE runs ADD COLUMN IF NOT EXISTS boot_s double precision, ADD COLUMN IF NOT EXISTS load_s double precision,
  ADD COLUMN IF NOT EXISTS run_s double precision;
-- the CPU power state at launch (2026-10-24, run.opts power_profile= / cpu_power=, ingest.power_state): the desktop sat in
-- power-saver (boost off, 4.7 GHz) for every run before; NULL for runs without the lines
ALTER TABLE runs ADD COLUMN IF NOT EXISTS power_profile text, ADD COLUMN IF NOT EXISTS cpu_power text,
  ADD COLUMN IF NOT EXISTS cpu_governor text, ADD COLUMN IF NOT EXISTS cpu_epp text, ADD COLUMN IF NOT EXISTS cpu_boost text,
  ADD COLUMN IF NOT EXISTS cpu_max_khz bigint;
-- the hero's scenes per machine, refreshed by ingest.py after imports (derived.py: HERO_SCENES, hero_refresh_sql)
CREATE TABLE IF NOT EXISTS hero_scenes (machine text, scene text, st_runs bigint, o_runs bigint, newest timestamptz,
  st_fps double precision, o_fps double precision, st_low double precision, o_low double precision,
  st_p99 double precision, o_p99 double precision, st_p999 double precision, o_p999 double precision,
  st_gpu double precision, o_gpu double precision, st_core double precision, o_core double precision,
  PRIMARY KEY (machine, scene));

-- run videos in the cold-storage bucket (2026-09-27, harness/cold-store.py): its own table, so a re-import of the run
-- (which deletes and rewrites the runs row) keeps the link
CREATE TABLE IF NOT EXISTS run_videos (run text PRIMARY KEY, url text, object text, bytes bigint, uploaded timestamptz DEFAULT now());
-- run folders in the same bucket (2026-10-03, harness/cold-store.py runs / index): url = the folder's index.html (every
-- file, frame data first), frames_url = its pzopt-frames.out (else the first frame log it has)
CREATE TABLE IF NOT EXISTS run_data (run text PRIMARY KEY, url text, frames_url text, files int, bytes bigint, uploaded timestamptz DEFAULT now());

-- pzopt-frames.out: every game frame (Stats), the harness's own frame source on both stock and optimized
CREATE TABLE IF NOT EXISTS frames (run text, t timestamptz, rel_s double precision, rt timestamptz, ms real, in_route boolean);
-- pzopt-overlay.out: every presented frame with the overlay's GPU time and thread loads
CREATE TABLE IF NOT EXISTS overlay (run text, t timestamptz, rel_s double precision, rt timestamptz, fps real, ms real,
  gpu_ms real, cpu_load real, gpu_load real, game_load real, render_load real);
-- sysmon.csv: machine CPU / GPU utilization, clocks, power, VRAM every 0.5 s
CREATE TABLE IF NOT EXISTS sysmon (run text, t timestamptz, rel_s double precision, rt timestamptz, cpu_pct real,
  busiest_core_pct real, gpu_pct real, gpu_sm_mhz real, gpu_mem_mhz real, gpu_w real, gpu_c real, vram_mib real,
  game_cpu_pct real, bat_w real, cpu_w real, soc_w real, total_w real);
-- power (2026-09-24): cpu_w = CPU package (RAPL), soc_w = an AMD APU's socket, total_w = battery on battery, else CPU
-- (or socket) + discrete GPU; NULL where the machine has no readable sensor (harness/sysmon.sh, scripts/power-access.sh)
ALTER TABLE sysmon ADD COLUMN IF NOT EXISTS cpu_w real, ADD COLUMN IF NOT EXISTS soc_w real, ADD COLUMN IF NOT EXISTS total_w real;
-- the other power traces of a run: source game = the in-game profiler (pzopt-power.out, pzopt.Power, same rails as
-- sysmon), mac = harness/macpower.py (power.csv: total_w = the SMC's whole-system load, cpu_w / gpu_w from IOReport)
CREATE TABLE IF NOT EXISTS power (run text, t timestamptz, rel_s double precision, rt timestamptz, source text,
  cpu_w real, gpu_w real, soc_w real, bat_w real, total_w real, fps real);
-- pzopt-gamethread.out: per second, share of the game thread's stack samples per key
-- kind p = phase, s = phase/sub-phase, l = phase/sub/hot method, w = waits
CREATE TABLE IF NOT EXISTS gamethread (run text, t timestamptz, rel_s double precision, rt timestamptz, kind text,
  name text, samples integer, share real);
-- pzopt-chunks.out: one row per streamed chunk, at its enqueue instant
CREATE TABLE IF NOT EXISTS chunks (run text, t timestamptz, rel_s double precision, rt timestamptz, wx integer,
  wy integer, min_level integer, max_level integer, job text, thread text, queue_wait_ms real, load_ms real,
  recalc_wait_ms real, recalc_ms real, publish_wait_ms real, total_ms real);
-- gc.log: every GC pause
CREATE TABLE IF NOT EXISTS gc (run text, t timestamptz, rel_s double precision, rt timestamptz, gc_id integer,
  kind text, pause_ms real, heap_before_mb real, heap_after_mb real);
-- present.txt: interval between the game window's flips on screen (X Present)
CREATE TABLE IF NOT EXISTS present (run text, t timestamptz, rel_s double precision, rt timestamptz, interval_ms real);
-- anything else with a time column (pzopt-drive.out, vrr.txt ...): long format
CREATE TABLE IF NOT EXISTS series (run text, t timestamptz, rel_s double precision, rt timestamptz, source text,
  key text, value double precision);
-- profiler stacks, root first, deduplicated across runs: id = first 8 bytes of md5(frames joined by ';')
CREATE TABLE IF NOT EXISTS stack_defs (id bigint PRIMARY KEY, frames text[]);
-- per second, per sampled thread: source game = the in-game profiler (pzopt-stacks.out, game thread), lua = the Lua
-- profile (pzopt-lua.out), jfr = --jfr (pzopt.jfr), asprof = --asprof (asprof.jfr, every thread, native frames)
CREATE TABLE IF NOT EXISTS stacks (run text, t timestamptz, rel_s double precision, rt timestamptz, source text,
  thread text, stack_id bigint, samples integer);
-- pzopt-pacing.out: one row per frame (ms): game-frame interval, frame done -> render thread took it, the swap call,
-- presentPacing's hold, GPU done after the swap, on glass after the swap (Mac bridge); NULL where the stage has no stamp
CREATE TABLE IF NOT EXISTS pacing (run text, t timestamptz, rel_s double precision, rt timestamptz, sim_step_ms real,
  acquire_lag_ms real, swap_ms real, hold_ms real, gpu_after_swap_ms real, shown_after_swap_ms real);
-- schedmon.txt: per second, per thread: CPU and run-queue wait as % of wall, major faults
CREATE TABLE IF NOT EXISTS sched (run text, t timestamptz, rel_s double precision, rt timestamptz, thread text,
  cpu_pct real, runq_pct real, majflt integer);
-- console lines with their epoch (pzopt-loadtrace.out): boot, load, harness and pzopt messages
CREATE TABLE IF NOT EXISTS events (run text, t timestamptz, rel_s double precision, rt timestamptz, source text,
  level text, category text, text text);
-- pzopt-input.out: every key / mouse / pad change the game thread saw (pzopt.InputRecorder)
CREATE TABLE IF NOT EXISTS inputs (run text, t timestamptz, rel_s double precision, rt timestamptz, device text,
  control text, value double precision);
-- what the harness configured: run.opts, the harness flag file, pzopt.properties, options, env, vmargs, mods
CREATE TABLE IF NOT EXISTS run_inputs (run text, kind text, key text, value text);

-- pzopt-threads.out: CPU per Java thread over the route
CREATE TABLE IF NOT EXISTS threads (run text, thread text, cpu_ms double precision, share double precision);
-- numeric facts of pzopt-bench.out, its bake_counters= / zombie_batches= lines and the summary
CREATE TABLE IF NOT EXISTS counters (run text, grp text, key text, value double precision);

-- the mod Lua profile (2026-10-21): pzopt-luaprof.out / pzopt-luaprof-frames.out (pzopt.HarnessModLua -> ModLuaProfile, the
-- Lua stack sampled at 2 kHz off the game thread, every event handler / UI call counted), the PZ run dashboard's "Mods (Lua)"
-- row = the in-game benchmark report's (pzopt.ModLuaDash, pzopt_mainscreen_report_mods.lua). ms are per game frame; ord keeps
-- the file's order (mods by cost, then the game's, ours, the unknown; functions / events by time); mod = the origin's name
-- as the mods table writes it (ModLuaDash.shortName)
CREATE TABLE IF NOT EXISTS luaprof (run text PRIMARY KEY, frames integer, seconds double precision, sample_hz double precision,
  sample_interval_us double precision, samples bigint, samples_in_lua bigint, lua_ms double precision, lua_pct double precision,
  game_lua_ms double precision, mods_ms double precision, mods_pct double precision, worst_mod text, worst_mod_name text,
  worst_mod_ms double precision, worst_mod_pct double precision, p99_frame_ms double precision, spike_frames integer, counting text,
  counting_off_reason text, hook_calls bigint, hook_ns double precision, overhead_us double precision, sampler_cpu_pct double precision,
  start_epoch_ms bigint);
CREATE TABLE IF NOT EXISTS luaprof_origins (run text, ord integer, id text, kind text, name text, ms double precision, pct double precision,
  calls_per_frame double precision, p99_ms double precision, files integer);
CREATE TABLE IF NOT EXISTS luaprof_functions (run text, ord integer, origin text, mod text, file text, function text, line integer, event text,
  self_ms double precision, total_ms double precision, calls_per_frame double precision);
CREATE TABLE IF NOT EXISTS luaprof_events (run text, ord integer, event text, lua_ms double precision, mods_ms double precision,
  calls_per_frame double precision, top_origin text, top_mod text, top_ms double precision);
CREATE TABLE IF NOT EXISTS luaprof_spikes (run text, ord integer, t_s double precision, frame_ms double precision, lua_ms double precision,
  mods_ms double precision, top_origin text, top_mod text, top_ms double precision);
-- every game frame of the profile (t_s from the profile's start = the route start): its length, all Lua, the mods', the game's
CREATE TABLE IF NOT EXISTS luaprof_frames (run text, t timestamptz, rel_s double precision, rt timestamptz, frame integer, frame_ms real,
  lua_ms real, mods_ms real, game_ms real);
-- panel 65's series as ModLuaDash.series makes them: 0.5 s buckets, the 8 costliest mods, "other mods", the game's Lua, ours,
-- the unknown origin (ord = the stacking order); ms = the bucket's mean per frame
CREATE TABLE IF NOT EXISTS luaprof_series (run text, t timestamptz, rel_s double precision, rt timestamptz, ord integer, name text,
  ms double precision);
CREATE INDEX IF NOT EXISTS luaprof_origins_run ON luaprof_origins (run);
CREATE INDEX IF NOT EXISTS luaprof_functions_run ON luaprof_functions (run);
CREATE INDEX IF NOT EXISTS luaprof_events_run ON luaprof_events (run);
CREATE INDEX IF NOT EXISTS luaprof_spikes_run ON luaprof_spikes (run);
CREATE INDEX IF NOT EXISTS luaprof_frames_run_rt ON luaprof_frames (run, rt);
CREATE INDEX IF NOT EXISTS luaprof_series_run_rt ON luaprof_series (run, rt);

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY['frames','overlay','sysmon','power','gamethread','chunks','gc','present','series','stacks','sched','events','inputs','pacing'] LOOP
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (run, rt)', tbl || '_run_rt', tbl);
  END LOOP;
END $$;
CREATE INDEX IF NOT EXISTS threads_run ON threads (run);
CREATE INDEX IF NOT EXISTS counters_run ON counters (run);
CREATE INDEX IF NOT EXISTS run_inputs_run ON run_inputs (run);
CREATE INDEX IF NOT EXISTS stacks_stack_id ON stacks (stack_id);

-- live: the game that is running now (ingest.py --follow tails ~/Zomboid and the run dir), pruned after 6 h
CREATE TABLE IF NOT EXISTS live_overlay (t timestamptz, fps real, ms real, gpu_ms real, cpu_load real, gpu_load real,
  game_load real, render_load real);
CREATE TABLE IF NOT EXISTS live_frames (t timestamptz, ms real);
CREATE TABLE IF NOT EXISTS live_sysmon (t timestamptz, cpu_pct real, busiest_core_pct real, gpu_pct real,
  gpu_sm_mhz real, gpu_mem_mhz real, gpu_w real, gpu_c real, vram_mib real, game_cpu_pct real, bat_w real,
  cpu_w real, soc_w real, total_w real);
ALTER TABLE live_sysmon ADD COLUMN IF NOT EXISTS cpu_w real, ADD COLUMN IF NOT EXISTS soc_w real, ADD COLUMN IF NOT EXISTS total_w real;
CREATE TABLE IF NOT EXISTS live_power (t timestamptz, cpu_w real, gpu_w real, soc_w real, bat_w real, total_w real, fps real);
CREATE TABLE IF NOT EXISTS live_gamethread (t timestamptz, kind text, name text, samples integer, share real);
CREATE TABLE IF NOT EXISTS live_state (k text PRIMARY KEY, v text, t timestamptz DEFAULT now());
CREATE TABLE IF NOT EXISTS live_stacks (t timestamptz, stack_id bigint, samples integer);
CREATE TABLE IF NOT EXISTS live_inputs (t timestamptz, device text, control text, value double precision);
CREATE INDEX IF NOT EXISTS live_overlay_t ON live_overlay (t);
CREATE INDEX IF NOT EXISTS live_frames_t ON live_frames (t);
CREATE INDEX IF NOT EXISTS live_sysmon_t ON live_sysmon (t);
CREATE INDEX IF NOT EXISTS live_power_t ON live_power (t);
CREATE INDEX IF NOT EXISTS live_gamethread_t ON live_gamethread (t);
CREATE INDEX IF NOT EXISTS live_stacks_t ON live_stacks (t);
CREATE INDEX IF NOT EXISTS live_stacks_stack_id ON live_stacks (stack_id);
CREATE INDEX IF NOT EXISTS live_inputs_t ON live_inputs (t);

-- the Steam Workshop item's public numbers, one snapshot every 30 min (workshop_stats.py, run by the follower): counts from
-- the keyless Web API, rating / comments / awards from the item page (NULL when Steam rate-limits the page)
CREATE TABLE IF NOT EXISTS workshop_stats (t timestamptz PRIMARY KEY, item bigint, subscribers integer,
  lifetime_subscribers integer, favorites integer, lifetime_favorites integer, visitors integer, stars smallint,
  ratings integer, comments integer, awards integer);
-- what else the PZ Workshop page dashboard reads (workshop_stats.py, same 30 min, 2026-10-10): every GitHub release's
-- downloads (re-read each time, the counts keep growing), every comment's time, GitHub's daily views (the API keeps 14 days,
-- the table keeps them all), small counters, each description version, and the page changes (restyles, marks, detected
-- description changes) the before / after table picks from
CREATE TABLE IF NOT EXISTS workshop_releases (tag text PRIMARY KEY, t timestamptz, installer integer, zip integer, uninstaller integer);
CREATE TABLE IF NOT EXISTS workshop_comments (t timestamptz PRIMARY KEY, n integer);
CREATE TABLE IF NOT EXISTS workshop_github_views (day date PRIMARY KEY, views integer, uniques integer);
CREATE TABLE IF NOT EXISTS workshop_extra (t timestamptz PRIMARY KEY, github_stars integer, discord_members integer,
  discord_online integer, steam_referrals integer, steam_referral_uniques integer);
CREATE TABLE IF NOT EXISTS workshop_descriptions (hash text PRIMARY KEY, first_seen timestamptz, chars integer, body text);
CREATE TABLE IF NOT EXISTS workshop_page_changes (t timestamptz PRIMARY KEY, kind text, label text);


DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grafana') THEN
    CREATE ROLE grafana LOGIN PASSWORD 'grafana';
  END IF;
END $$;
GRANT USAGE ON SCHEMA public TO grafana;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana;
