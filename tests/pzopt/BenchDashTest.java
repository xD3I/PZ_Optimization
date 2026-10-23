package pzopt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The menu's report page data (BenchDash, the Grafana run page's panels from a benchmark run folder) and the pass
 * timelines it reads (BenchTimeline: the 2 Hz samples, the GC pauses, the game-thread log's window).
 */
public class BenchDashTest {
   static List<String[]> panel(String text, int id) {
      List<String[]> out = new ArrayList<>();
      boolean in = false;
      for (String line : text.split("\n")) {
         String[] c = line.split("\t", -1);
         if (c[0].equals("P")) {
            in = c[1].equals(Integer.toString(id));
            continue;
         }
         if (in) {
            out.add(c);
         }
      }
      return out;
   }

   static String value(List<String[]> rows, String kind, String name) {
      for (String[] r : rows) {
         if (r[0].equals(kind) && r[1].equals(name)) {
            return r.length > 2 ? r[2] : "";
         }
      }
      return null;
   }

   public static void main(String[] args) throws Exception {
      java.nio.file.Path home = Files.createTempDirectory("pzopt-dash-test");
      System.setProperty("deployment.user.cachedir", home.toString()); // UserOptions.zomboidDir -> <home>/Zomboid

      // BenchTimeline: samples with a missing reading, GC pauses, the CSV, a log's window
      BenchTimeline tl = new BenchTimeline();
      tl.sample(0.5, 40, 12, 80, 20, 95, Double.NaN, 60, 300, Double.NaN, Double.NaN, 1500);
      tl.sample(1.0, 42, 13, 82, 21, 96, Double.NaN, 61, 310, Double.NaN, Double.NaN, 1510);
      tl.gcPause(0.75, 4);
      Check.check(tl.size() == 2 && "0.750:4.0".equals(tl.gcText()), "two samples, one pause: " + tl.gcText());
      String run = "20261021-120000";
      File dir = BenchPlan.runDir(run);
      dir.mkdirs();
      String base = "step-00-spin-A-1";
      tl.writeCsv(new File(dir, base + "-samples.csv"));
      List<String> csv = Files.readAllLines(new File(dir, base + "-samples.csv").toPath());
      Check.check(csv.size() == 3 && csv.get(0).equals(BenchTimeline.HEADER), "header + two rows: " + csv);
      Check.check(csv.get(1).startsWith("0.500,40.00,12.00,80.00,20.00,95.00,,60.00,300.00,,,1500.00"), "an unknown reading is an empty cell: " + csv.get(1));
      tl.clear();
      Check.check(tl.size() == 0 && tl.gcText().isEmpty(), "clear empties the timeline");

      long start = 1792500000000L;
      File log = new File(home.toFile(), "gt.out");
      Files.writeString(log.toPath(), "# header\n" + (start - 2000) + "\t25\tp:update=25\n" + (start + 1000) + "\t25\tp:update=15\tp:render=10\ts:update/zombies=12\t"
            + "s:render/frame hand-off=10\tl:update/zombies/IsoZombie.update=6\tw:render/frame hand-off/SpriteRenderer.wait=9\n"
            + (start + 2000) + "\t25\tp:update=20\tp:render=5\ts:update/zombies=14\tl:update/zombies/IsoZombie.update=8\n", StandardCharsets.UTF_8);
      BenchTimeline.copyWindow(log, new File(dir, base + "-gamethread.out"), start, start + 3000);
      List<String> gt = Files.readAllLines(new File(dir, base + "-gamethread.out").toPath());
      Check.check(gt.size() == 3 && gt.get(0).startsWith("#"), "the comment and the two lines of the window: " + gt);

      // a pass: its properties, frames (2 s at 1 ms with one 40 ms frame), the setup
      Map<String, String> p = new LinkedHashMap<>();
      p.put("scenario", "spin");
      p.put("config", "A");
      p.put("pass", "1");
      p.put("status", "ok");
      p.put("fps", "950.5");
      p.put("median_ms", "1.0");
      p.put("p99_ms", "1.2");
      p.put("p999_ms", "3.5");
      p.put("max_ms", "40");
      p.put("low1_avg", "300");
      p.put("jitter_ms", "0.2");
      p.put("cpu_system_pct", "41");
      p.put("gpu_busy_pct", "95.5");
      p.put("game_thread_pct", "81");
      p.put("render_thread_pct", "20.5");
      p.put("gc_count", "1");
      p.put("gc_max_pause_ms", "4");
      p.put("gc_pauses", "0.750:4.0");
      p.put("seconds", "2.0");
      p.put("started_ms", Long.toString(start));
      p.put("sys.cpu", "Test CPU");
      p.put("sys.gpu", "Test GPU");
      p.put("sys.cores", "16");
      p.put("horde_shots", "12");
      p.put("per_second", "1000,1000");
      BenchResults.writeStep(run, 0, p);
      StringBuilder frames = new StringBuilder("Application,ProcessID,TimeInSeconds,MsBetweenPresents,MsCPUBusy,MsGPUBusy,MsRenderThread,MsInPresentAPI\n");
      double t = 0;
      for (int i = 0; i < 1960; i++) {
         double ms = i == 1000 ? 40 : 1.0;
         t += ms / 1000;
         frames.append(String.format(java.util.Locale.ROOT, "ProjectZomboid,1,%.6f,%.4f,0.8000,0.9000,0.3000,0.0100\n", t, ms));
      }
      Files.writeString(new File(dir, base + "-frames.csv").toPath(), frames.toString(), StandardCharsets.UTF_8);
      Files.writeString(new File(dir, "setup.txt").toPath(), "R\tmode\tstandard\nR\tpasses\t1\nO\thdrAuto\tfalse\ttrue\nG\tvsync\ttrue\nM\tsomeMod\tSome Mod\t1.2\n", StandardCharsets.UTF_8);

      String text = BenchDash.text(run + "\t\t\t");
      Check.check(text.contains("SEL\tspin\tA\t1\t0\t"), "the first scene, PZ Optimization, pass 1: " + text.substring(0, Math.min(300, text.length())));
      Check.check(text.contains("VAR\tscene\tspin\t"), "the scene variable");
      List<String[]> p1 = panel(text, 1);
      Check.check("1".equals(value(p1, "V", "p50")) && "40".equals(value(p1, "V", "max")), "frame time tile: p50 1, max 40");
      List<String[]> p2 = panel(text, 2);
      Check.check("1".equals(value(p2, "V", ">33 ms")), "one frame over 33 ms: " + value(p2, "V", ">33 ms"));
      List<String[]> p3 = panel(text, 3);
      Check.check("".equals(value(p3, "V", "busiest core")) && "95.5".equals(value(p3, "V", "GPU")), "no busiest core in game, GPU 95.5");
      Check.check("GPU-bound".equals(value(panel(text, 4), "V", "bound")), "95 % GPU busy: GPU-bound");
      Check.check("valid".equals(value(panel(text, 4), "V", "route")), "an ok pass is valid");
      Check.check("Test CPU | Test GPU".equals(value(panel(text, 7), "R", "machine")), "the machine fact: " + value(panel(text, 7), "R", "machine"));
      Check.check(value(panel(text, 7), "R", "zoom") == null, "a fact the game does not have is left out");
      Check.check(value(panel(text, 11), "B", "presented") != null && value(panel(text, 11), "B", "GPU") != null, "every-frame bands");
      Check.check(value(panel(text, 11), "B", "game frame") == null, "no game frames kept by this run: no game-frame band");
      String band = panel(text, 11).get(0)[4];
      Check.check(band.contains("40"), "the band's maximum keeps the 40 ms frame");
      Check.check(value(panel(text, 12), "S", "p99") != null && value(panel(text, 13), "S", "fps") != null, "per-second charts");
      List<String[]> hist = panel(text, 14);
      Check.check(hist.size() == 1 && "HIST".equals(hist.get(0)[0]) && "2".equals(hist.get(0)[2]), "the histogram starts at bucket 2 (1.0 ms / 0.5)");
      String[] counts = hist.get(0)[3].split(",");
      Check.check(Integer.parseInt(counts[0]) == 1959 && Integer.parseInt(counts[counts.length - 1]) == 1, "1959 frames at 1 ms, one at 40 ms");
      List<String[]> pct = panel(text, 15);
      Check.check(pct.size() == 5 && "presented".equals(pct.get(2)[1]) && "1960".equals(pct.get(2)[2]) && "1".equals(pct.get(2)[9]), "percentile rows: " + String.join(",", pct.get(2)));
      Check.check("game frame".equals(pct.get(1)[1]) && "0".equals(pct.get(1)[2]), "no game frames kept: 0 frames");
      Check.check(value(panel(text, 17), "S", "CPU") != null && value(panel(text, 17), "S", "busiest core") == null, "machine chart from the samples");
      Check.check(value(panel(text, 18), "S", "game thread") != null, "thread loads");
      String cores = value(panel(text, 19), "S", "game process cores");
      Check.check(cores != null && panel(text, 19).get(0)[3].startsWith("1.92"), "12 % of 16 cores = 1.92 cores: " + String.join("|", panel(text, 19).get(0)));
      Check.check(value(panel(text, 25), "S", "game GPU") != null && value(panel(text, 25), "S", "game total") == null, "power: GPU and CPU only, no total reading");
      Check.check(panel(text, 26).isEmpty(), "no total power: no J/frame");
      Check.check(value(panel(text, 28), "S", "update") != null && value(panel(text, 28), "S", "render") != null, "phases from the game-thread log");
      Check.check(panel(text, 30).stream().anyMatch(r -> r[1].equals("update/zombies") && r[2].equals("26") && r[3].equals("52")), "sub-phase 26 of 50 samples over the route");
      Check.check(panel(text, 31).stream().anyMatch(r -> r[1].equals("update/zombies/IsoZombie.update") && r[2].equals("14") && r[3].equals("28")), "hot method 14 of 50 samples = 28 %");
      Check.check(panel(text, 32).stream().anyMatch(r -> r[1].equals("render/frame hand-off/SpriteRenderer.wait")), "waits");
      Check.check(value(panel(text, 36), "S", "pause") != null, "GC pauses");
      List<String[]> conf = panel(text, 55);
      Check.check(conf.size() == 6 && "prop".equals(conf.get(1)[1]) && "option".equals(conf.get(2)[1]) && "mods".equals(conf.get(3)[1]), "configured rows, props first: " + conf.size());
      Check.check(panel(text, 59).stream().anyMatch(r -> r[1].equals("horde") && r[2].equals("horde_shots")), "counters grouped");
      Check.check(panel(text, 59).stream().noneMatch(r -> r[2].equals("per_second")), "the chart lists stay out of the counters");
      Check.check(panel(text, 8).stream().allMatch(r -> r[0].equals("WHY")) && panel(text, 34).stream().allMatch(r -> r[0].equals("WHY")), "no recording, no chunk trace: no data, a reason");

      // a run before the timelines (no samples, no game-thread log): the old game_phases table, no utilization charts
      new File(dir, base + "-samples.csv").delete();
      new File(dir, base + "-gamethread.out").delete();
      p.put("game_phases", "update/zombies=15.8;render/frame hand-off=15.2");
      BenchResults.writeStep(run, 0, p);
      text = BenchDash.text(run + "\tspin\tA\t1");
      Check.check(panel(text, 17).isEmpty() && panel(text, 28).isEmpty(), "no samples: no machine chart, no phases");
      Check.check(panel(text, 30).stream().anyMatch(r -> r[1].equals("update/zombies") && r[3].equals("15.8")), "the route's sub-phases from game_phases");
      Check.check(BenchDash.text("../x").startsWith("error"), "a path is not a run");
      Check.check("12 MB".equals(BenchDash.prettySize(12L * 1024 * 1024)) && "900 bytes".equals(BenchDash.prettySize(900)), "sizes as pg_size_pretty");
      System.out.println("BenchDashTest: ok");
   }
}
