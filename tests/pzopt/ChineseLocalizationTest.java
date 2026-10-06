package pzopt;

import static pzopt.Check.check;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.j2se.MathLib;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.stdlib.CoroutineLib;
import se.krka.kahlua.stdlib.StringLib;
import se.krka.kahlua.stdlib.TableLib;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;

/** Runs localization and option-value checks with the same Lua VM as the game. */
public class ChineseLocalizationTest {
   public static void main(String[] args) throws Exception {
      String source = "GPU 35 %   game thread 75 %   render thread 20 %   process 15 % of 16 cores   machine 22 %   heap 2.5/8.0 GB";
      check(LocalizedText.text(source, true).equals("GPU 35 %   游戏线程 75 %   渲染线程 20 %   进程 15 %（16核心）   整机 22 %   堆内存 2.5/8.0 GB"), "overlay translates labels and preserves every number");
      check(LocalizedText.text("LightingThread.update 25 %", true).equals("LightingThread.update 25 %"), "method identifiers stay intact");
      check(LocalizedText.text("game thread", true).equals("游戏线程"), "phase label translated");
      check(LocalizedText.text("game thread bound", true).equals("受游戏线程限制"), "bound verdict keeps the subject");
      check(LocalizedText.text("render thread bound: zombies 9 %", true).equals("受渲染线程限制: 僵尸 9 %"), "detailed verdict keeps its sample values");
      check(LocalizedText.text(source, false) == source, "other languages retain the original string");
      check(LocalizedText.text(null, true) == null, "null display text is unchanged");

      Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
      JSONObject ui = new JSONObject(Files.readString(root.resolve("src/lua/shared/Translate/CN/UI.json")));
      J2SEPlatform platform = new J2SEPlatform();
      KahluaTable env = platform.newTable();
      env.rawset("_G", env);
      BaseLib.register(env);
      MathLib.register(platform, env);
      StringLib.register(platform, env);
      TableLib.register(platform, env);
      CoroutineLib.register(platform, env);
      KahluaThread thread = new KahluaThread(platform, env);
      thread.debugOwnerThread = Thread.currentThread();
      LuaCompiler.rewriteEvents = false;
      Path game = Path.of(J2SEPlatform.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
      run(thread, env, Files.readString(game.resolve("stdlib.lua")), "stdlib.lua");
      env.rawset("getText", (JavaFunction)(frame, count) -> {
         String key = (String)frame.get(0);
         return frame.push(key.equals(env.rawget("MISSING_KEY")) ? key : ui.optString(key, key));
      });
      env.rawset("measure", (JavaFunction)(frame, count) -> frame.push((double)((String)frame.get(0)).codePoints().map(cp -> cp > 127 ? 12 : 6).sum()));
      // The game's lexer truncates non-ASCII Lua literals; translated characters come from Java / JSON.
      env.rawset("CN_VISUALS", "画面");
      env.rawset("CN_ULTRA", "极致性能");
      env.rawset("CN_MORE", "显示另外17项设置（高级视图）");
      env.rawset("CN_MATCH", "5项设置匹配“light on”，按匹配度排序");
      env.rawset("CN_QUERY", "光照 阴影");
      env.rawset("CN_LIGHT", "光照");
      env.rawset("CN_SENTENCES", "第一句。第二句。");
      env.rawset("CN_FIRST", "第一句。");
      run(thread, env, "LANG='CN'; require=function()end; Translator={getLanguage=function()return {name=function()return LANG end}end};"
            + "UIFont={Small='Small',Medium='Medium',Large='Large',CodeSmall='CodeSmall',CodeMedium='CodeMedium',CodeLarge='CodeLarge'};"
            + "getTextManager=function()return {MeasureStringX=function(_,font,value)return measure(value)end}end", "fixture");
      for (String name : new String[]{"pzopt_text_cn.lua", "pzopt_text.lua"}) {
         run(thread, env, Files.readString(root.resolve("src/lua/shared/pzopt/" + name)), name);
      }
      String options = Files.readString(root.resolve("src/lua/client/pzopt/pzopt_optimizations_options.lua"));
      String layout = slice(options, "local function fontH(font)", "-- the part of a section title in brackets");
      String combos = slice(options, "local function comboLabels(entry, default, saved)", "-- The zoom curve");
      run(thread, env, "local function perf()return TEST_PERF end; local function afterStore()end; local function tooltipFor(e)return e.tip end; " + layout + combos
            + "\nLocalizationFixture={clip=clipText,wrap=wrapLines,first=firstSentence,combo=comboLabels,option=addIntOption}", "options-display-helpers");
      run(thread, env, Files.readString(root.resolve("tests/lua/chinese-localization.lua")), "chinese-localization-test");
      System.out.println("ChineseLocalizationTest ok");
   }

   private static String slice(String source, String start, String end) {
      int from = source.indexOf(start), to = source.indexOf(end, from);
      check(from >= 0 && to > from, "options display helpers found");
      return source.substring(from, to);
   }

   private static void run(KahluaThread thread, KahluaTable env, String source, String name) throws Exception {
      Object[] result = thread.pcall(LuaCompiler.loadis(new java.io.StringReader(source), name, env), new Object[0]);
      check(Boolean.TRUE.equals(result[0]), name + ": " + java.util.Arrays.toString(result));
   }
}
