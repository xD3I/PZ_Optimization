package pzopt;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The benchmark report's power fields (BenchPower): Linux sysfs / power-profiles-daemon, Windows and macOS readings. */
public class BenchPowerTest {
   static final String CPU = "/sys/devices/system/cpu/cpu0/cpufreq/";

   static Map<String, String> linux(Map<String, String> fs) {
      return BenchPower.linux(fs::get);
   }

   public static void main(String[] args) {
      // the desktop on 2026-10-24: ppd power-saver, amd-pstate-epp, per-CPU boost 0 while the global switch says 1
      Map<String, String> fs = new HashMap<>();
      fs.put("/var/lib/power-profiles-daemon/state.ini", "[State]\nbattery_aware=true\nCpuDriver=amd_pstate\nPlatformDriver=placeholder\nProfile=power-saver\n\n[Actions]\ntrickle_charge=true");
      fs.put(CPU + "scaling_governor", "powersave");
      fs.put(CPU + "energy_performance_preference", "power");
      fs.put(CPU + "boost", "0");
      fs.put("/sys/devices/system/cpu/cpufreq/boost", "1");
      fs.put(CPU + "scaling_max_freq", "4700000");
      Map<String, String> m = linux(fs);
      Check.check(m.get("power_profile").equals("power-saver"), "ppd profile: " + m);
      Check.check(m.get("cpu_power").equals("governor=powersave epp=power boost=0 max_khz=4700000"), "run.sh's cpu_power: " + m);

      // no per-CPU boost: the global switch; no ppd: the ACPI platform profile
      fs.remove(CPU + "boost");
      fs.remove("/var/lib/power-profiles-daemon/state.ini");
      fs.put("/sys/firmware/acpi/platform_profile", "balanced");
      m = linux(fs);
      Check.check(m.get("cpu_power").contains("boost=1"), "global boost: " + m);
      Check.check(m.get("power_profile").equals("balanced"), "platform profile: " + m);

      // intel_pstate: no boost file, no_turbo=1 -> boost=0; tuned's profile last
      fs.remove("/sys/devices/system/cpu/cpufreq/boost");
      fs.remove("/sys/firmware/acpi/platform_profile");
      fs.put("/sys/devices/system/cpu/intel_pstate/no_turbo", "1");
      fs.put("/etc/tuned/active_profile", "throughput-performance");
      m = linux(fs);
      Check.check(m.get("cpu_power").contains("boost=0"), "no_turbo: " + m);
      Check.check(m.get("power_profile").equals("throughput-performance"), "tuned: " + m);

      // nothing readable (a VM, a container): dashes, no exception
      m = linux(new HashMap<>());
      Check.check(m.get("power_profile").equals("-"), "no profile: " + m);
      Check.check(m.get("cpu_power").equals("governor=- epp=- boost=- max_khz=-"), "no cpufreq: " + m);
      Check.check(BenchPower.ppdProfile("[Actions]\nProfile=performance\n") == null, "Profile outside [State] ignored");
      Check.check(BenchPower.ppdProfile(null) == null, "no state.ini");

      // Windows: GUID layout round trip, built-in scheme names, the power mode overlay, processor settings
      String bal = "381b4222-f694-41f0-9685-ff5bb260df2e";
      byte[] g = BenchPower.guidBytes(bal);
      Check.check(g[0] == 0x22 && g[3] == 0x38 && g[4] == (byte)0x94 && g[5] == (byte)0xf6 && g[8] == (byte)0x96, "GUID Data1-3 little-endian");
      Check.check(BenchPower.guidString(g).equals(bal), "GUID round trip: " + BenchPower.guidString(g));
      m = BenchPower.windowsFields(bal, "Balanced", "ded574b5-45a0-4f42-8737-46345c09c238", true, false, 5, 100, 2);
      Check.check(m.get("power_profile").equals("balanced/best-performance"), "Windows profile: " + m);
      Check.check(m.get("cpu_power").equals("scheme=Balanced mode=best-performance source=ac saver=off min_state=5% max_state=100% boost_mode=aggressive"),
            "Windows detail: " + m);
      m = BenchPower.windowsFields("8C5E7FDA-E8BF-4A96-9A85-A6E23A8C635C", "Höchstleistung", "00000000-0000-0000-0000-000000000000", false, true, 100, 100, 0);
      Check.check(m.get("power_profile").equals("high-performance/battery-saver"), "Windows high performance (German name), saver: " + m);
      Check.check(m.get("cpu_power").contains("scheme=Höchstleistung mode=balanced source=battery saver=on") && m.get("cpu_power").contains("boost_mode=disabled"),
            "Windows detail 2: " + m);
      m = BenchPower.windowsFields("11111111-2222-3333-4444-555555555555", "My gaming plan", null, null, null, -1, -1, -1);
      Check.check(m.get("power_profile").equals("My gaming plan"), "custom scheme: " + m);
      Check.check(m.get("cpu_power").equals("scheme=My_gaming_plan mode=- source=- saver=- min_state=- max_state=- boost_mode=-"), "custom detail: " + m);
      m = BenchPower.windowsFields(null, null, null, null, null, -1, -1, -1);
      Check.check(m.get("power_profile").equals("-"), "nothing read on Windows: " + m);

      // macOS: NSProcessInfo first, pmset's lowpowermode / powermode
      String pmset = "System-wide power settings:\nCurrently in use:\n standby              1\n powermode            0\n lowpowermode         0\n sleep                1\n";
      m = BenchPower.macFields(false, pmset, "AC Power", "nominal");
      Check.check(m.get("power_profile").equals("automatic"), "Mac automatic: " + m);
      Check.check(m.get("cpu_power").equals("source=ac thermal=nominal lowpowermode=0 powermode=0"), "Mac detail: " + m);
      m = BenchPower.macFields(null, pmset.replace("lowpowermode         0", "lowpowermode         1"), "Battery Power", "fair");
      Check.check(m.get("power_profile").equals("low-power") && m.get("cpu_power").startsWith("source=battery thermal=fair lowpowermode=1"), "Mac low power from pmset: " + m);
      m = BenchPower.macFields(false, pmset.replace("powermode            0", "powermode            2"), "AC Power", "nominal");
      Check.check(m.get("power_profile").equals("high-power"), "Mac High Power Mode: " + m);
      m = BenchPower.macFields(null, null, null, null);
      Check.check(m.get("power_profile").equals("-") && m.get("cpu_power").equals("source=- thermal=- lowpowermode=- powermode=-"), "nothing read on macOS: " + m);
      Check.check("nominal".equals(BenchPower.thermalName(0)) && "critical".equals(BenchPower.thermalName(3)) && BenchPower.thermalName(9) == null, "thermal names");

      // the report: the power fields are not part of the comparability key, and the compare table marks them
      Map<String, String> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
      a.put("cpu", "Ryzen");
      a.put("power_profile", "power-saver");
      a.put("cpu_power", "governor=powersave epp=power boost=0 max_khz=4700000");
      b.put("cpu", "Ryzen");
      b.put("power_profile", "balanced");
      b.put("cpu_power", "governor=powersave epp=balance_performance boost=1 max_khz=5271000");
      Check.check(BenchSystem.key(a).equals(BenchSystem.key(b)), "the power state stays out of BenchSystem.key");
      List<String[]> rows = BenchSetup.compare(null, null, a, b);
      String[] p = null, c = null, cpu = null;
      for (String[] r : rows) {
         if (r[1].equals("sys") && r[2].equals("power_profile")) {
            p = r;
         } else if (r[1].equals("sys") && r[2].equals("cpu_power")) {
            c = r;
         } else if (r[1].equals("sys") && r[2].equals("cpu")) {
            cpu = r;
         }
      }
      Check.check(p != null && p[4].equals("power-saver") && p[5].equals("balanced") && p[6].equals("0"), "power profile row marked different");
      Check.check(c != null && c[6].equals("0"), "cpu power row marked different");
      Check.check(cpu != null && cpu[6].equals("1"), "the same CPU stays unmarked");
      System.out.println("BenchPowerTest OK");
   }
}
