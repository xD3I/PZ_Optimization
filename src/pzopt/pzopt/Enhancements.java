package pzopt;

/**
 * The Options > Enhancements tab's keys apply while the game runs (2026-09-25): {@link UserOptions#set} saves the
 * key, Config's live reload reads it again, and this hands it to the class that owns it.
 *
 * <ul>
 *   <li>upscaler, upscalerQuality, upscalerScalePct, dlssPreset, dlssOutputPct, dlssOutputFilter, dlssSharpen:
 *       {@link RenderScale#reconfigure} (new mode and scale from the next frame; the DLSS feature is built again).
 *       fsrSharpnessPct, upscalerObjectMv, dlssWaterCurrent and dlssWaterHistoryPct are read every frame (the water
 *       resources are made on first use).</li>
 *   <li>the HDR sliders: {@link Hdr#retune} (read every frame). hdr and hdrAuto are not live: on Linux they pick the
 *       window (a native Wayland FP16 surface) the game is created with.</li>
 *   <li>ambientOcclusion, aoScalePct, aoRadiusPct, the five aoStrength*Pct: {@link ChunkAo#reconfigure} (every loaded
 *       chunk texture bakes again with the new AO, or without it); sunShadows the same, sunShadowStrengthPct and
 *       sunShadowSoftnessPct through {@link SunShadow#update} (the kept shadows compute again, no re-bake).</li>
 *   <li>reflections, reflectionStrengthPct, reflectionPuddles, reflectionsFlow, reflectionsFlowPct: read every frame by {@link Ssr}.</li>
 *   <li>ambientOcclusion, sunShadows and reflections also switch {@link TileDepthFix}'s fitted tile depth in or out
 *       (tileDepthFix=auto; the chunk textures it touched bake again).</li>
 *   <li>darknessFloorPct, darknessFloorBasements, memoryTint, memoryLightPct: {@link Darkness#reconfigure} (every loaded
 *       square re-derives its light from the native's values, every chunk texture bakes again); memoryTintPct is read
 *       every frame; colorGrading, colorGradingPct, colorGradingNightPct: {@link Grade#reconfigure} (a new LUT).</li>
 *   <li>pplTorchFeetGlow: read every frame by {@link PixelLight} (the only per-pixel lighting key that is live).</li>
 *   <li>the occluded zombie outline keys: read every frame by {@link OccludedOutline} (nothing to rebuild).</li>
 * </ul>
 */
final class Enhancements {
   private Enhancements() {
   }

   /** Is this key one of the Enhancements tab's (as opposed to the Profiler tab's overlay keys)? */
   static boolean owns(String key) {
      return key.startsWith("occluded") || key.startsWith("upscaler") || key.startsWith("dlss") || key.startsWith("fsr") || key.startsWith("dynRes") || key.startsWith("taau") || key.startsWith("hdr")
         || key.equals("ambientOcclusion") || key.startsWith("ao") || key.startsWith("sunShadow") || key.startsWith("reflection")
         || key.startsWith("darknessFloor") || key.startsWith("memory") || key.startsWith("colorGrading")
         || key.startsWith("moonShadow") || key.startsWith("cloud") || key.equals("pplTorchFeetGlow")
         || key.startsWith("ripples") || key.equals("bloodWet") || key.equals("bloodWetMinutes") || key.equals("bloodReflectPct") || key.equals("bloodSheenPct") || key.equals("bloodGlintPct");
   }

   /** Game thread, after Config.reloadLive(key) returned true. */
   static void apply(String key) {
      switch (key) {
         case "fsrSharpnessPct", "upscalerObjectMv", "dlssWaterCurrent", "dlssWaterHistoryPct", "reflections", "reflectionStrengthPct", "reflectionPuddles", "reflectionsFlow", "reflectionsFlowPct",
               "ripples", "ripplesMode", "ripplesStrengthPct", "ripplesVehicles", "ripplesPerSec", "ripplesRain", "ripplesWake", "ripplesRainPct", "bloodWet", "bloodWetMinutes", "bloodReflectPct", "bloodSheenPct", "bloodGlintPct", "occludedZombieOutlines", "occludedOutlineIgnorePlants",
               "occludedOutlineWidth", "occludedOutlineColour", "occludedOutlineOpacityPct" -> {
            // read every frame
         }
         case "upscaler", "upscalerQuality", "upscalerScalePct", "dlssPreset", "dlssOutputPct", "dlssOutputFilter", "dlssSharpen", "dynRes", "dynResUpscaler",
               "dynResMinPct", "dynResMaxPct", "dynResAxes" ->
            RenderScale.reconfigure();
         case "dynResTargetPct", "dynResFps", "dynResController", "dynResStepPx", "dynResUpPerMille", "dynResDownPerMille", "dynResDeadbandPct",
               "dynResNativeBypass", "dynResStarveGate", "dynResSharpenRamp", "dynResBakeFeedforward", "dynResUpDelayFrames", "dynResSigmaPct", "dynResDriftPerMille", "dynResCpuAware", "dynResCpuTargetPct", "dynResProbe", "dynResBakeTerm", "taauMaxFrames", "taauClipPct", "taauSampleSigmaPct", "taauJitter", "taauJitterAdaptive", "taauWarmBypass", "taauWarmEvery" -> {
            // read every frame (DynRes, Taau)
         }
         case "ambientOcclusion", "aoScalePct", "aoRadiusPct", "aoStrengthFloorPct", "aoStrengthWallPct", "aoStrengthObjectPct",
               "aoStrengthVegetationPct", "aoStrengthPlantPct", "sunShadows", "sunShadowTreeCards" -> ChunkAo.reconfigure();
         case "darknessFloorPct", "darknessFloorBasements", "memoryTint", "memoryLightPct" -> Darkness.reconfigure();
         case "memoryTintPct" -> {
            // read every frame by the remembered-places pass
         }
         case "pplTorchFeetGlow" -> {
            // read every frame by the chunk composite's uniforms
         }
         case "torchSource", "torchSourceAim", "torchSourceHold", "torchSourceFresh", "torchSourceWallClamp", "torchSourceVehicles", "torchSourcePitch" -> {
            // read every frame by TorchInfo.set and the light gatherers (pzopt.TorchSource)
         }
         case "colorGrading", "colorGradingPct", "colorGradingNightPct" -> Grade.reconfigure();
         case "sunShadowStrengthPct", "sunShadowSoftnessPct", "sunShadowCharacters", "sunShadowVehicles", "sunShadowTorches",
               "sunShadowMeshes", "sunShadowAnimals", "sunShadowStockFadePct", "sunShadowRate",
               "moonShadows", "moonShadowPct", "cloudShadows", "cloudOpacityPct", "cloudSpeedPct", "cloudScalePct" -> {
            // SunShadow.update sees the new strength / penumbra next frame and recomputes the kept shadows (no re-bake);
            // the capsule pass reads its three switches and the shadow update rate every frame; the moon and the clouds too
         }
         default -> {
            if (key.startsWith("hdr")) {
               Hdr.retune();
            }
         }
      }
      if (key.equals("ambientOcclusion") || key.equals("sunShadows") || key.equals("reflections")) {
         TileDepthFix.sync(); // the fitted cabinet depth follows the features that read the depth (tileDepthFix=auto)
      }
   }
}
