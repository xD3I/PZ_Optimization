"""Results the public dashboards would otherwise recompute on every page view, kept in small tables that ingest.py
refreshes after each import (local DB first, then the same rows to the public dashboard's DB): the Cloud Run Grafana's
e2-micro Postgres needed 90-190 s for the hero's scenes, which the desktop computes in 0.2 s (profiled 2026-10-21).

  hero_scenes  HERO_SCENES's `s` per machine: every scene run both ways, medians of the newest 10 runs per side
"""

# Stock vs optimized on one machine (the desktop on the home page), for public visitors. A run counts when it is a valid measurement (analyze.py and
# Jev's card; not Jev's verdict, whose "invalid" also means "below the cap with hardware idle"), path drives clean, no profiler (vmargs,
# JFR) and no pzopt key beyond the measurement ones below (so A/B runs with a key switched off never count as
# "optimized"); a stock run is enabled=false. Only exact scenes (mode, preset, flags, cap, game options, resolution,
# renderer) run both ways enter, then they pool under a readable scene name; each side is the median of its newest 10.
HERO_SCENES = r"""
WITH r0 AS (
  SELECT r.*, NOT enabled AS stock,
    coalesce(substring(props from 'frameCapFps=(\d+)'), substring(opts->>'game_options' from 'frameRate=(\d+)'))::int AS cap_fps,
    trim(regexp_replace(coalesce(opts->>'game_options', ''), '(frameRate|uncappedFPS)=\S*', '', 'g')) AS other_options
  FROM runs r
  WHERE machine = 'desktop' AND valid AND fps_mean IS NOT NULL
    AND coalesce(judge #>> '{state,run,facts,valid_measurement}', 'true') = 'true'
    AND coalesce(judge #>> '{state,run,drive,verdict}', 'valid') = 'valid'
    -- no profiler attached (run.sh's own launch options, such as the released -Dpzopt.jit=steady, are fine)
    AND coalesce(opts->>'vmargs', '') !~ '(agentpath|agentlib|javaagent|StartFlightRecording|NativeMemoryTracking)'
    AND coalesce(opts->>'jfr', '0') = '0'
    AND NOT EXISTS (SELECT 1 FROM regexp_split_to_table(coalesce(props, ''), '\s+') t
                    WHERE t <> '' AND t !~ '^(instrument|overlay|uncappedFps|hdrAuto|frameCapFps|enabled)='
                      AND t NOT IN ('upscaler=off', 'hotsaveStaged=false'))
    -- the Optimizations tab file applies too and is not in props: an upscaler renders the world smaller, not a default
    AND (NOT enabled OR coalesce(substring(scenario->>'settings' from 'upscaler=([a-z0-9]+)'), 'off') = 'off')
    -- the daily re-measures of older builds (<bench>-<MMDD>-<n>, DAILY_BENCHES) are history: only the newest day's build is "current"
    AND (label !~ '^(daily|dstorm|dspin|dlou|dhorde)-[0-9]{4}-' OR substring(label from '^[a-z]+-([0-9]{4})-')
         = (SELECT max(substring(label from '^[a-z]+-([0-9]{4})-')) FROM runs WHERE label ~ '^(daily|dstorm|dspin|dlou|dhorde)-[0-9]{4}-'))
),
r1 AS (SELECT *, CASE WHEN props ~ 'uncappedFps=true' OR fps_mean < 0.9 * cap_fps THEN 'uncapped'  -- below its cap = uncapped
                      WHEN cap_fps IS NOT NULL THEN cap_fps || ' fps cap' ELSE 'capped' END AS cap_label FROM r0),
c AS (
  SELECT r1.*,
    concat_ws('|', mode, preset, flags, cap_label, other_options, resolution, opts->>'renderer') AS exact,
    concat_ws(' · ',
      -- the daily re-measures pass the scene flags instead of the preset: name those scenes by their start square
      CASE WHEN flags ~ 'start=12450,1280' THEN 'Louisville horde' WHEN flags ~ 'showcase=horde' THEN 'Horde shooting, riverside'
      ELSE CASE coalesce(nullif(preset, 'none'), mode)
        WHEN 'louisville' THEN 'Louisville horde' WHEN 'storm' THEN 'Thunderstorm' WHEN 'storm-fog' THEN 'Storm + fog'
        WHEN 'fog' THEN 'Heavy fog' WHEN 'night-torch' THEN 'Night, torch' WHEN 'night-dark' THEN 'Night'
        WHEN 'helicopter' THEN 'Helicopter event' WHEN 'bench' THEN 'Rosewood'
        -- kmh=193 is flat out: the car tops out near 122 km/h
        WHEN 'drive' THEN 'Driving' || coalesce(' ' || least(substring(flags from 'kmh=(\d+)')::int, 120) || ' km/h', '')
        ELSE preset END END,
      CASE WHEN flags ~ 'weather=storm' AND coalesce(preset, '') NOT LIKE 'storm%' THEN 'thunderstorm' END,
      CASE WHEN flags ~ 'fog=heavy' AND coalesce(preset, '') NOT IN ('fog', 'storm-fog') THEN 'heavy fog' END,
      CASE WHEN flags ~ 'turn=' AND flags !~ 'start=12450,1280' AND mode = 'bench' AND coalesce(preset, '') IN ('', 'none') THEN 'spinning walk' END,
      CASE WHEN flags ~ '(house|car)_alarm=' THEN 'alarms' END,
      CASE WHEN flags ~ 'gunshots=[1-9]' THEN 'gunfire' END,
      CASE WHEN flags ~ 'helicopter=true' AND coalesce(preset, '') <> 'helicopter' THEN 'helicopter' END,
      cap_label) AS scene
  FROM r1
),
m AS (SELECT *, row_number() OVER (PARTITION BY scene, stock ORDER BY started DESC) AS n FROM c
      WHERE exact IN (SELECT exact FROM c GROUP BY 1 HAVING bool_or(stock) AND bool_or(NOT stock))),
side AS (SELECT scene, stock, count(*) AS runs, max(started) AS newest,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY fps_mean) AS fps,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY fps_1pct_low) AS low,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY p99_ms) AS p99,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY p99_9_ms) AS p999,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY gpu_pct) AS gpu,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY busiest_core_pct) AS core
         FROM m WHERE n <= 10 GROUP BY 1, 2),
s AS (SELECT o.scene, st.runs AS st_runs, o.runs AS o_runs, greatest(st.newest, o.newest) AS newest,
        st.fps AS st_fps, o.fps AS o_fps, st.low AS st_low, o.low AS o_low, st.p99 AS st_p99, o.p99 AS o_p99,
        st.p999 AS st_p999, o.p999 AS o_p999, st.gpu AS st_gpu, o.gpu AS o_gpu, st.core AS st_core, o.core AS o_core
      FROM side o JOIN side st ON st.scene = o.scene AND st.stock AND NOT o.stock)"""

HERO_COLS = ["machine", "scene", "st_runs", "o_runs", "newest", "st_fps", "o_fps", "st_low", "o_low", "st_p99", "o_p99",
             "st_p999", "o_p999", "st_gpu", "o_gpu", "st_core", "o_core"]
# the headline row: the clear 120 km/h drive (the daily chart's main line) when it was run both ways, else the scene with most pairs
HERO_HEADLINE = ("(SELECT * FROM hero_scenes WHERE machine = 'desktop' "
                 "ORDER BY scene = 'Driving 120 km/h · uncapped' DESC, least(st_runs, o_runs) DESC, newest DESC LIMIT 1) h")


def hero_refresh_sql(machines):
    """Replace hero_scenes with every machine's scenes (one transaction)."""
    parts = ["BEGIN;\nDELETE FROM hero_scenes;\n"]
    for m in (m.replace("'", "''") for m in machines):
        sql = HERO_SCENES.replace("machine = 'desktop'", f"machine = '{m}'")
        parts.append(f"INSERT INTO hero_scenes ({', '.join(HERO_COLS)})\n{sql}\nSELECT '{m}', {', '.join(HERO_COLS[1:])} FROM s;\n")
    parts.append("COMMIT;\n")
    return "".join(parts)
