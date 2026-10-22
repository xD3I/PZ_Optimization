# javif (vendored)

Pure-Java AV1 / AVIF decoder by Glavo, https://github.com/Glavo/javif, commit `1bf5b827f563077b762eefde344e33f7585b44a1`, licensed under the Mozilla
Public License 2.0 (`LICENSE` here). Vendored by `scripts/vendor-javif.sh` for the options menu's AVIF clips
(`pzopt.ClipDecoder`). Changes, all mechanical: package `org.glavo.avif` renamed to `pzopt.vendor.avif`, the
JetBrains nullability annotations (compile-time only) removed, the JavaFX package and `module-info.java` left out.
The modified files remain under the MPL-2.0; their source is this directory.
