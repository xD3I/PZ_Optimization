# tools/

Standalone Java programs, compiled ad hoc (not part of `scripts/build.sh`).

| File | Use |
|---|---|
| `ClipDecodeBench.java` | times the menu clip decoders on one file (2026-10-20, `harness/clip-format-eval.py` calls it): `.gif` via `pzopt.GifTextures`, `.avif` via javif, `.jxl` via jxlatte, `.webp` via TwelveMonkeys; prints `format= frames= cold_ms= warm_ms= ms_per_frame=`, optional rgb48 dump for exactness checks |
| `JfrSamples.java` | dumps a run's `pzopt.jfr` execution samples and wait events as lines for `harness/attribute.py` and `harness/waits.py` |
| `mac/coreprobe/CoreProbe.java`, `ShaderRig.java` | macOS OpenGL 4.1 core (`macGlCore`, 2026-10-01), run ON the Mac with the game's JRE and jar (`-XstartOnFirstThread --enable-native-access=ALL-UNNAMED -cp ".:<app>/Contents/Java/projectzomboid.jar"`, classes compiled on the desktop with `--release 25` and scp'd): `CoreProbe` makes a 4.1 core context, rebuilds LWJGL's table with Java upcalls and times them (~70 ns) and lists which GLSL versions Apple compiles; `ShaderRig <media/shaders>` translates every `.vert` / `.frag` with `pzopt.CoreGlsl` (copy `CoreGlsl*.class` beside it under `pzopt/`) and compiles them on Apple's compiler (LINKFAIL lines are mostly the rig: the game links multi-unit programs) |
| `GlfwSwapProbe.java` | tests overlay hooks (MangoHud preload, swap hand-off) in seconds using the game's own LWJGL, without launching the game. MangoHud ignores processes named `java`, so run it under a copied JDK launcher (`/tmp/pjdk/bin/pzprobe` pattern) |
| `ShaderRegs.java` | compiles a vertex + fragment shader pair in a hidden GLFW window (compile only) and prints the fragment program's register use from NVIDIA's program binary (`TEMP R0..Rn` of its assembly): `java -cp "<game>/*" tools/ShaderRegs.java <vert> <frag>... [--dump dir]`; pairs with `devSwayDumpDir` (foliage sway's twins) |
| `StaticAudit.java` | static-state audit of the recalc classes (`docs/recalc-static-audit.md`) |
