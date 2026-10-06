package pzopt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import zombie.core.Translator;
import zombie.ui.UIFont;

/** Simplified Chinese overlay display text; sampler names and log formats stay unchanged. */
final class LocalizedText {
   private record Template(Pattern pattern, String prefix, String[] required, String[] parts) {}
   private static final Map<String, String> TEXT = new HashMap<>();
   private static final List<Template> TEMPLATES = new ArrayList<>();
   private static final Map<Character, List<String>> FRAGMENTS = new HashMap<>();
   private static final Map<String, String> CACHE = new LinkedHashMap<>(512, .75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) { return size() > 512; }
   };
   static {
      TEXT.put("performance overlay: waiting for frames", "性能浮层：等待帧数据");
      TEXT.put("   %5.2f ms   cap %s%s%s", "   %5.2f ms   帧率上限 %s%s%s");
      TEMPLATES.add(new Template(Pattern.compile("^\\ \\ \\ (.*?)\\ ms\\ \\ \\ cap\\ (.*?)(.*?)(.*?)$", Pattern.DOTALL), "   ", new String[]{" ms   cap "}, new String[]{"   ", " ms   帧率上限 ", "", "", ""}));
      TEXT.put("   88.88 ms   cap 8888 fps   res 888 %", "   88.88 ms   帧率上限 8888 fps   分辨率 888 %");
      TEXT.put("p50 %.2f   p99 %.2f   p99.9 %.2f   max %.1f ms   (%d frames / %d s)", "p50 %.2f   p99 %.2f   p99.9 %.2f   最大 %.1f ms   （%d 帧 / %d 秒）");
      TEMPLATES.add(new Template(Pattern.compile("^p50\\ (.*?)\\ \\ \\ p99\\ (.*?)\\ \\ \\ p99\\.9\\ (.*?)\\ \\ \\ max\\ (.*?)\\ ms\\ \\ \\ \\((.*?)\\ frames\\ /\\ (.*?)\\ s\\)$", Pattern.DOTALL), "p50 ", new String[]{"p50 ", "   p99 ", "   p99.9 ", "   max ", " ms   (", " frames / ", " s)"}, new String[]{"p50 ", "   p99 ", "   p99.9 ", "   最大 ", " ms   （", " 帧 / ", " 秒）"}));
      TEXT.put("p50 88.88   p99 88.88   p99.9 888.88   max 8888.8 ms   (88888 frames / 8 s)", "p50 88.88   p99 88.88   p99.9 888.88   最大 8888.8 ms   （88888 帧 / 8 秒）");
      TEXT.put("1%%-low %.0f fps   jitter %.2f ms   spikes >2x median %d", "1%%低帧 %.0f fps   抖动 %.2f ms   超过中位数2倍的尖峰 %d");
      TEMPLATES.add(new Template(Pattern.compile("^1%\\-low\\ (.*?)\\ fps\\ \\ \\ jitter\\ (.*?)\\ ms\\ \\ \\ spikes\\ >2x\\ median\\ (.*?)$", Pattern.DOTALL), "1%-low ", new String[]{"1%-low ", " fps   jitter ", " ms   spikes >2x median "}, new String[]{"1%低帧 ", " fps   抖动 ", " ms   超过中位数2倍的尖峰 ", ""}));
      TEXT.put("1%-low 8888 fps   jitter 88.88 ms   spikes >2x median 8888", "1%低帧 8888 fps   抖动 88.88 ms   超过中位数2倍的尖峰 8888");
      TEXT.put("GPU %s   game thread %.0f %%   render thread %.0f %%   process %.0f %% of %d cores   machine %.0f %%   heap %.1f/%.1f GB", "GPU %s   游戏线程 %.0f %%   渲染线程 %.0f %%   进程 %.0f %%（%d核心）   整机 %.0f %%   堆内存 %.1f/%.1f GB");
      TEMPLATES.add(new Template(Pattern.compile("^GPU\\ (.*?)\\ \\ \\ game\\ thread\\ (.*?)\\ %\\ \\ \\ render\\ thread\\ (.*?)\\ %\\ \\ \\ process\\ (.*?)\\ %\\ of\\ (.*?)\\ cores\\ \\ \\ machine\\ (.*?)\\ %\\ \\ \\ heap\\ (.*?)/(.*?)\\ GB$", Pattern.DOTALL), "GPU ", new String[]{"GPU ", "   game thread ", " %   render thread ", " %   process ", " % of ", " cores   machine ", " %   heap ", "/", " GB"}, new String[]{"GPU ", "   游戏线程 ", " %   渲染线程 ", " %   进程 ", " %（", "核心）   整机 ", " %   堆内存 ", "/", " GB"}));
      TEXT.put("GPU 888 %   game thread 888 %   render thread 888 %   process 888 % of 88 cores   machine 888 %   heap 88.8/88.8 GB", "GPU 888 %   游戏线程 888 %   渲染线程 888 %   进程 888 %（88核心）   整机 888 %   堆内存 88.8/88.8 GB");
      TEXT.put("at the cap", "已达帧率上限");
      TEXT.put("game thread", "游戏线程");
      TEXT.put("render thread", "渲染线程");
      TEXT.put("below cap: ", "未达帧率上限：");
      TEXT.put("below cap, ", "未达帧率上限，");
      TEXT.put("\u0001\u0001 bound", "\u0001受\u0001限制");
      TEXT.put("game thread bound", "受游戏线程限制");
      TEXT.put("game thread bound: ", "受游戏线程限制: ");
      TEXT.put("render thread bound", "受渲染线程限制");
      TEXT.put("render thread bound: ", "受渲染线程限制: ");
      TEXT.put("GPU bound", "受GPU限制");
      TEXT.put("GPU bound: ", "受GPU限制: ");
      TEXT.put("below cap: game thread bound", "未达帧率上限：受游戏线程限制");
      TEXT.put("below cap: game thread bound: ", "未达帧率上限：受游戏线程限制: ");
      TEXT.put("below cap: render thread bound", "未达帧率上限：受渲染线程限制");
      TEXT.put("below cap: render thread bound: ", "未达帧率上限：受渲染线程限制: ");
      TEXT.put("below cap: GPU bound", "未达帧率上限：受GPU限制");
      TEXT.put("below cap: GPU bound: ", "未达帧率上限：受GPU限制: ");
      TEXT.put("\u0001nothing saturated: waits or sync", "\u0001未达到硬件上限：等待或同步");
      TEMPLATES.add(new Template(Pattern.compile("^(.*?)nothing\\ saturated:\\ waits\\ or\\ sync$", Pattern.DOTALL), "", new String[]{"nothing saturated: waits or sync"}, new String[]{"", "未达到硬件上限：等待或同步"}));
      TEXT.put("  waiting %.0f %%", "  等待 %.0f %%");
      TEMPLATES.add(new Template(Pattern.compile("^\\ \\ waiting\\ (.*?)\\ %$", Pattern.DOTALL), "  waiting ", new String[]{"  waiting ", " %"}, new String[]{"  等待 ", " %"}));
      TEXT.put("  waiting 88 %", "  等待 88 %");
      TEXT.put("game thread (\u0001 stacks / 5 s), most time first   waiting \u0001", "游戏线程（\u0001个调用栈 / 5秒），按耗时降序   等待 \u0001");
      TEMPLATES.add(new Template(Pattern.compile("^game\\ thread\\ \\((.*?)\\ stacks\\ /\\ 5\\ s\\),\\ most\\ time\\ first\\ \\ \\ waiting\\ (.*?)$", Pattern.DOTALL), "game thread (", new String[]{"game thread (", " stacks / 5 s), most time first   waiting "}, new String[]{"游戏线程（", "个调用栈 / 5秒），按耗时降序   等待 ", ""}));
      TEXT.put("flame graph, last 5 s (\u0001 stacks): root at the bottom, width = share, biggest first", "最近5秒的火焰图（\u0001个调用栈）：底部为根，宽度表示占比，按占比降序");
      TEMPLATES.add(new Template(Pattern.compile("^flame\\ graph,\\ last\\ 5\\ s\\ \\((.*?)\\ stacks\\):\\ root\\ at\\ the\\ bottom,\\ width\\ =\\ share,\\ biggest\\ first$", Pattern.DOTALL), "flame graph, last 5 s (", new String[]{"flame graph, last 5 s (", " stacks): root at the bottom, width = share, biggest first"}, new String[]{"最近5秒的火焰图（", "个调用栈）：底部为根，宽度表示占比，按占比降序"}));
      TEXT.put("last %d frames (%.2f s), oldest to newest", "最近 %d 帧（%.2f 秒），从旧到新");
      TEMPLATES.add(new Template(Pattern.compile("^last\\ (.*?)\\ frames\\ \\((.*?)\\ s\\),\\ oldest\\ to\\ newest$", Pattern.DOTALL), "last ", new String[]{"last ", " frames (", " s), oldest to newest"}, new String[]{"最近 ", " 帧（", " 秒），从旧到新"}));
      TEXT.put("last 9999 frames (99.99 s), oldest to newest", "最近9999帧（99.99秒），从旧到新");
      TEXT.put("bars: frame ms, green under 1.1x the %.2f ms budget, amber under 2x, red above; blue: GPU ms; line: the budget", "柱形表示帧耗时：绿色低于 %.2f ms预算的1.1倍，橙色低于2倍，红色超过2倍；蓝色为GPU耗时，横线为预算");
      TEMPLATES.add(new Template(Pattern.compile("^bars:\\ frame\\ ms,\\ green\\ under\\ 1\\.1x\\ the\\ (.*?)\\ ms\\ budget,\\ amber\\ under\\ 2x,\\ red\\ above;\\ blue:\\ GPU\\ ms;\\ line:\\ the\\ budget$", Pattern.DOTALL), "bars: frame ms, green under 1.1x the ", new String[]{"bars: frame ms, green under 1.1x the ", " ms budget, amber under 2x, red above; blue: GPU ms; line: the budget"}, new String[]{"柱形表示帧耗时：绿色低于 ", " ms预算的1.1倍，橙色低于2倍，红色超过2倍；蓝色为GPU耗时，横线为预算"}));
      TEXT.put("; blue", "；蓝色");
      TEXT.put("Performance overlay: sampling is off.", "性能浮层：采样已关闭。");
      TEXT.put("Performance overlay: the profiler is switched off.", "性能浮层：性能分析已关闭。");
      TEXT.put("apply, then toggle the overlay again (no restart needed).", "点击应用，然后重新切换浮层（无需重启）。");
      TEXT.put("none", "无");
      TEXT.put("n/a", "不可用");
      TEXT.put("off", "关闭");
      TEXT.put("lighting", "光照");
      TEXT.put("update", "更新");
      TEXT.put("render", "渲染");
      TEXT.put("(no game frame)", "（无游戏帧）");
      TEXT.put("animals", "动物");
      TEXT.put("audio", "音频");
      TEXT.put("characters draw", "角色绘制");
      TEXT.put("chunk bakes", "区块烘焙");
      TEXT.put("chunk checks", "区块检查");
      TEXT.put("chunk composite", "区块合成");
      TEXT.put("chunk lighting", "区块光照");
      TEXT.put("chunk map", "区块地图");
      TEXT.put("chunk stream", "区块流式加载");
      TEXT.put("cutaways", "遮挡透视");
      TEXT.put("ecs", "实体组件系统");
      TEXT.put("file system", "文件系统");
      TEXT.put("fog", "雾效");
      TEXT.put("frame hand-off", "帧交接");
      TEXT.put("frame other", "其他帧任务");
      TEXT.put("game profiler", "游戏性能分析");
      TEXT.put("lighting jni", "光照JNI");
      TEXT.put("logic", "逻辑");
      TEXT.put("lua events", "Lua事件");
      TEXT.put("main-thread queue", "主线程队列");
      TEXT.put("objects update", "对象更新");
      TEXT.put("outside frame", "帧外任务");
      TEXT.put("overlay", "浮层");
      TEXT.put("player", "玩家");
      TEXT.put("prepare chunks", "准备区块");
      TEXT.put("puddles", "水洼");
      TEXT.put("render-thread call", "渲染线程调用");
      TEXT.put("saving", "保存");
      TEXT.put("terrain", "地形");
      TEXT.put("translucent", "半透明对象");
      TEXT.put("translucent floor", "半透明地面");
      TEXT.put("tree bake", "树木烘焙");
      TEXT.put("ui draw", "界面绘制");
      TEXT.put("ui update", "界面更新");
      TEXT.put("vehicles", "车辆");
      TEXT.put("vispoly", "可见性多边形");
      TEXT.put("water", "水体");
      TEXT.put("weather fx", "天气特效");
      TEXT.put("weather update", "天气更新");
      TEXT.put("world items", "世界物品");
      TEXT.put("world sounds", "世界声音");
      TEXT.put("zombie population", "僵尸种群");
      TEXT.put("zombies", "僵尸");
      TEXT.put("translucent floor objects x", "半透明地面对象 ×");
      TEXT.put("power 88.8 W on battery (whole machine): SoC 88.8 W, GPU 888 W   8.888 J/frame", "功耗88.8 W，电池供电（整机）：SoC 88.8 W，GPU 888 W   8.888 J/帧");
      TEXT.put("every frame", "每帧");
      TEXT.put("Tick \"Profiler enabled (master switch)\" on the home page of Options > PZ Optimization,", "在“选项 > PZ优化”的首页勾选“启用性能分析（总开关）”，");
      TEXT.put("Tick \"Sample frame times and utilization\" under Options > PZ Optimization > Tools > Performance overlay,", "在“选项 > PZ优化 > 工具 > 性能浮层”中勾选“采样帧耗时和利用率”，");

      for (String source : TEXT.keySet()) {
         if (source.isEmpty() || source.indexOf('\u0001') >= 0 || source.indexOf('%') >= 0) continue;
         FRAGMENTS.computeIfAbsent(source.charAt(0), ignored -> new ArrayList<>()).add(source);
      }
      for (List<String> values : FRAGMENTS.values()) values.sort(Comparator.comparingInt(String::length).reversed());
   }

   static boolean chinese() {
      return Translator.getLanguage() != null && Translator.getLanguage().name().equals("CN");
   }

   static UIFont font(UIFont value) {
      if (value == null || !chinese()) return value;
      return switch (value) {
         case CodeSmall -> UIFont.Small;
         case CodeMedium -> UIFont.Medium;
         case CodeLarge -> UIFont.Large;
         default -> value;
      };
   }

   static String text(String source) { return text(source, chinese()); }

   static String text(String source, boolean chinese) {
      if (source == null || source.isEmpty() || !chinese) return source;
      String exact = TEXT.get(source);
      if (exact != null) return exact;
      synchronized (CACHE) { String cached = CACHE.get(source); if (cached != null) return cached; }
      boolean letters = false;
      for (int i = 0; i < source.length(); i++) {
         char c = source.charAt(i);
         if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') { letters = true; break; }
      }
      if (!letters) return source;
      for (Template template : TEMPLATES) {
         if (!source.startsWith(template.prefix())) continue;
         boolean candidate = true;
         for (String part : template.required()) if (!source.contains(part)) { candidate = false; break; }
         if (!candidate) continue;
         Matcher match = template.pattern().matcher(source);
         if (!match.matches()) continue;
         String[] parts = template.parts();
         StringBuilder out = new StringBuilder(parts[0]);
         for (int i = 1; i < parts.length; i++) out.append(text(match.group(i), true)).append(parts[i]);
         return cache(source, out.toString());
      }
      StringBuilder out = null;
      int copied = 0;
      for (int pos = 0; pos < source.length();) {
         String found = null;
         for (String fragment : FRAGMENTS.getOrDefault(source.charAt(pos), List.of())) {
            if (!source.startsWith(fragment, pos)) continue;
            int end = pos + fragment.length();
            if (pos > 0 && identifier(fragment.charAt(0)) && identifier(source.charAt(pos - 1))) continue;
            if (end < source.length() && identifier(fragment.charAt(fragment.length() - 1)) && identifier(source.charAt(end))) continue;
            found = fragment; break;
         }
         if (found == null) pos++;
         else {
            if (out == null) out = new StringBuilder(source.length() + 16);
            out.append(source, copied, pos).append(TEXT.get(found));
            pos += found.length(); copied = pos;
         }
      }
      return cache(source, out == null ? source : out.append(source, copied, source.length()).toString());
   }

   private static boolean identifier(char c) {
      return c < 128 && (Character.isLetterOrDigit(c) || "_.$/\\:@-".indexOf(c) >= 0);
   }

   private static String cache(String source, String value) {
      synchronized (CACHE) { CACHE.put(source, value); }
      return value;
   }
}
