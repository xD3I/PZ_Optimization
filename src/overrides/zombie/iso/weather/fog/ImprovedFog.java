package zombie.iso.weather.fog;

import org.joml.Vector2i;
import zombie.GameTime;
import zombie.IndieGL;
import zombie.UsedFromLua;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.textures.Texture;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.input.GameKeyboard;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.fx.SteppedUpdateFloat;

@UsedFromLua
public class ImprovedFog {
   public static final int MAX_FOG_Z = 1;
   private static final ImprovedFog.RectangleIterator rectangleIter = new ImprovedFog.RectangleIterator();
   private static final Vector2i rectangleMatrixPos = new Vector2i();
   private static IsoChunkMap chunkMap;
   private static int minY;
   private static int maxY;
   private static int minX;
   private static int maxX;
   private static int zLayer;
   private static final Vector2i lastIterPos = new Vector2i();
   private static final ImprovedFog.FogRectangle fogRectangle = new ImprovedFog.FogRectangle();
   private static boolean drawingThisLayer;
   private static float zoom = 1.0F;
   private static int playerIndex;
   private static int playerRow;
   private static float screenWidth;
   private static float screenHeight;
   private static float worldOffsetX;
   private static float worldOffsetY;
   private static float topAlphaHeight = 0.38F;
   private static float bottomAlphaHeight = 0.24F;
   private static float secondLayerAlpha = 0.5F;
   private static float scalingX = 1.0F;
   private static float scalingY = 1.0F;
   private static float colorR = 1.0F;
   private static float colorG = 1.0F;
   private static float colorB = 1.0F;
   private static boolean drawDebugColors;
   private static float octaves = 6.0F;
   private static boolean highQuality = true;
   private static boolean enableEditing;
   private static float alphaCircleAlpha = 0.3F;
   private static float alphaCircleRad = 2.25F;
   private static int lastRow = -1;
   private static ClimateManager climateManager;
   private static Texture noiseTexture;
   private static boolean renderOnlyOneRow;
   private static float baseAlpha;
   private static int renderEveryXRow = 1;
   private static int renderXRowsFromCenter;
   private static boolean renderCurrentLayerOnly;
   private static float rightClickOffX;
   private static float rightClickOffY;
   private static float cameraOffscreenLeft;
   private static float cameraOffscreenTop;
   private static float cameraZoom;
   private static int minXOffset = -2;
   private static int maxXOffset = 12;
   private static int maxYOffset = -5;
   private static boolean renderEndOnly;
   private static final SteppedUpdateFloat fogIntensity = new SteppedUpdateFloat(0.0F, 0.005F, 0.0F, 1.0F);
   private static final ImprovedFogDrawer[][] drawers = new ImprovedFogDrawer[4][3];
   private static int keyPause;
   private static final float[] offsets = new float[]{
      0.3F,
      0.8F,
      0.0F,
      0.6F,
      0.3F,
      0.1F,
      0.5F,
      0.9F,
      0.2F,
      0.0F,
      0.7F,
      0.1F,
      0.4F,
      0.2F,
      0.5F,
      0.3F,
      0.8F,
      0.4F,
      0.9F,
      0.5F,
      0.8F,
      0.4F,
      0.7F,
      0.2F,
      0.0F,
      0.6F,
      0.1F,
      0.6F,
      0.9F,
      0.7F
   };

   public static int getMinXOffset() {
      return minXOffset;
   }

   public static void setMinXOffset(int minXOffset) {
      ImprovedFog.minXOffset = minXOffset;
   }

   public static int getMaxXOffset() {
      return maxXOffset;
   }

   public static void setMaxXOffset(int maxXOffset) {
      ImprovedFog.maxXOffset = maxXOffset;
   }

   public static int getMaxYOffset() {
      return maxYOffset;
   }

   public static void setMaxYOffset(int maxYOffset) {
      ImprovedFog.maxYOffset = maxYOffset;
   }

   public static boolean isRenderEndOnly() {
      return renderEndOnly;
   }

   public static void setRenderEndOnly(boolean renderEndOnly) {
      ImprovedFog.renderEndOnly = renderEndOnly;
   }

   public static float getAlphaCircleAlpha() {
      return alphaCircleAlpha;
   }

   public static void setAlphaCircleAlpha(float alphaCircleAlpha) {
      ImprovedFog.alphaCircleAlpha = alphaCircleAlpha;
   }

   public static float getAlphaCircleRad() {
      return alphaCircleRad;
   }

   public static void setAlphaCircleRad(float alphaCircleRad) {
      ImprovedFog.alphaCircleRad = alphaCircleRad;
   }

   public static boolean isHighQuality() {
      return highQuality;
   }

   public static void setHighQuality(boolean highQuality) {
      ImprovedFog.highQuality = highQuality;
   }

   public static boolean isEnableEditing() {
      return enableEditing;
   }

   public static void setEnableEditing(boolean enableEditing) {
      ImprovedFog.enableEditing = enableEditing;
   }

   public static float getTopAlphaHeight() {
      return topAlphaHeight;
   }

   public static void setTopAlphaHeight(float topAlphaHeight) {
      ImprovedFog.topAlphaHeight = topAlphaHeight;
   }

   public static float getBottomAlphaHeight() {
      return bottomAlphaHeight;
   }

   public static void setBottomAlphaHeight(float bottomAlphaHeight) {
      ImprovedFog.bottomAlphaHeight = bottomAlphaHeight;
   }

   public static boolean isDrawDebugColors() {
      return drawDebugColors;
   }

   public static void setDrawDebugColors(boolean drawDebugColors) {
      ImprovedFog.drawDebugColors = drawDebugColors;
   }

   public static float getOctaves() {
      return octaves;
   }

   public static void setOctaves(float octaves) {
      ImprovedFog.octaves = octaves;
   }

   public static float getColorR() {
      return colorR;
   }

   public static void setColorR(float colorR) {
      ImprovedFog.colorR = colorR;
   }

   public static float getColorG() {
      return colorG;
   }

   public static void setColorG(float colorG) {
      ImprovedFog.colorG = colorG;
   }

   public static float getColorB() {
      return colorB;
   }

   public static void setColorB(float colorB) {
      ImprovedFog.colorB = colorB;
   }

   public static float getSecondLayerAlpha() {
      return secondLayerAlpha;
   }

   public static void setSecondLayerAlpha(float secondLayerAlpha) {
      ImprovedFog.secondLayerAlpha = secondLayerAlpha;
   }

   public static float getScalingX() {
      return scalingX;
   }

   public static void setScalingX(float scalingX) {
      ImprovedFog.scalingX = scalingX;
   }

   public static float getScalingY() {
      return scalingY;
   }

   public static void setScalingY(float scalingY) {
      ImprovedFog.scalingY = scalingY;
   }

   public static boolean isRenderOnlyOneRow() {
      return renderOnlyOneRow;
   }

   public static void setRenderOnlyOneRow(boolean renderOnlyOneRow) {
      ImprovedFog.renderOnlyOneRow = renderOnlyOneRow;
   }

   public static float getBaseAlpha() {
      return baseAlpha;
   }

   public static void setBaseAlpha(float baseAlpha) {
      ImprovedFog.baseAlpha = baseAlpha;
   }

   public static int getRenderEveryXRow() {
      return renderEveryXRow;
   }

   public static void setRenderEveryXRow(int renderEveryXRow) {
      ImprovedFog.renderEveryXRow = renderEveryXRow;
   }

   public static boolean isRenderCurrentLayerOnly() {
      return renderCurrentLayerOnly;
   }

   public static void setRenderCurrentLayerOnly(boolean renderCurrentLayerOnly) {
      ImprovedFog.renderCurrentLayerOnly = renderCurrentLayerOnly;
   }

   public static int getRenderXRowsFromCenter() {
      return renderXRowsFromCenter;
   }

   public static void setRenderXRowsFromCenter(int renderXRowsFromCenter) {
      ImprovedFog.renderXRowsFromCenter = renderXRowsFromCenter;
   }

   public static Texture getNoiseTexture() {
      return noiseTexture;
   }

   public static void update() {
      updateKeys();
      if (noiseTexture == null) {
         noiseTexture = Texture.getSharedTexture("media/textures/weather/fognew/fog_noise.png");
      }

      climateManager = ClimateManager.getInstance();
      if (!enableEditing) {
         highQuality = PerformanceSettings.fogQuality == 0;
         fogIntensity.setTarget(climateManager.getFogIntensity());
         fogIntensity.update(GameTime.getInstance().getMultiplier());
         baseAlpha = fogIntensity.value();
         if (highQuality) {
            renderEveryXRow = 1;
            topAlphaHeight = 0.38F;
            bottomAlphaHeight = 0.24F;
            octaves = 6.0F;
            secondLayerAlpha = 0.5F;
         } else {
            renderEveryXRow = 2;
            topAlphaHeight = 0.32F;
            bottomAlphaHeight = 0.32F;
            octaves = 3.0F;
            secondLayerAlpha = 1.0F;
         }

         colorR = climateManager.getColorNewFog().getExterior().r;
         colorG = climateManager.getColorNewFog().getExterior().g;
         colorB = climateManager.getColorNewFog().getExterior().b;
      }

      if (baseAlpha <= 0.0F) {
         scalingX = 0.0F;
         scalingY = 0.0F;
      } else {
         double rad = climateManager.getWindAngleRadians();
         rad -= Math.PI * 3.0 / 4.0;
         rad = Math.PI - rad;
         float x = (float)Math.cos(rad);
         float y = (float)Math.sin(rad);
         scalingX = scalingX + x * climateManager.getWindIntensity() * GameTime.getInstance().getMultiplier();
         scalingY = scalingY + y * climateManager.getWindIntensity() * GameTime.getInstance().getMultiplier();
      }
   }

   public static boolean startRender(int nPlayer, int z) {
      if (!DebugOptions.instance.weather.fog.getValue()) {
         drawingThisLayer = false;
         return false;
      }

      climateManager = ClimateManager.getInstance();
      if (z <= 1 && !(baseAlpha <= 0.0F) && PerformanceSettings.fogQuality != 2) {
         drawingThisLayer = true;
         IsoPlayer player = IsoPlayer.players[nPlayer];
         if (renderCurrentLayerOnly && player.getZ() != z) {
            drawingThisLayer = false;
            return false;
         }

         if (player.isInARoom() && z > 0) {
            drawingThisLayer = false;
            return false;
         }

         playerRow = PZMath.fastfloor(player.getX()) + PZMath.fastfloor(player.getY());
         zoom = Core.getInstance().getZoom(nPlayer);
         zLayer = z;
         playerIndex = nPlayer;
         PlayerCamera camera = IsoCamera.cameras[nPlayer];
         screenWidth = pzopt.RenderScale.scaledPx(IsoCamera.getOffscreenWidth(nPlayer)); // pzopt: upscaler, the shaders map gl_FragCoord of the scaled viewport through these
         screenHeight = pzopt.RenderScale.scaledPxY(IsoCamera.getOffscreenHeight(nPlayer)); // pzopt: dynResAxes, the vertical factor
         worldOffsetX = camera.getOffX() - IsoCamera.getOffscreenLeft(playerIndex) * zoom;
         worldOffsetY = camera.getOffY() + IsoCamera.getOffscreenTop(playerIndex) * zoom;
         rightClickOffX = camera.rightClickX + IsoUtils.XToScreen(camera.deferedX, camera.deferedY, 0.0F, 0);
         rightClickOffY = camera.rightClickY + IsoUtils.YToScreen(camera.deferedX, camera.deferedY, 0.0F, 0);
         cameraOffscreenLeft = pzopt.RenderScale.scaledPx(IsoCamera.getOffscreenLeft(nPlayer)); // pzopt: upscaler
         cameraOffscreenTop = pzopt.RenderScale.scaledPxY(IsoCamera.getOffscreenTop(nPlayer)); // pzopt: dynResAxes, the vertical factor
         cameraZoom = zoom;
         if (!enableEditing) {
            if (player.getVehicle() != null) {
               alphaCircleAlpha = 0.0F;
               alphaCircleRad = highQuality ? 2.0F : 2.6F;
            } else if (player.isInARoom()) {
               alphaCircleAlpha = 0.0F;
               alphaCircleRad = highQuality ? 1.25F : 1.5F;
            } else {
               alphaCircleAlpha = highQuality ? 0.1F : 0.16F;
               alphaCircleRad = highQuality ? 2.5F : 3.0F;
               if (climateManager.getWeatherPeriod().isRunning()
                  && (climateManager.getWeatherPeriod().isTropicalStorm() || climateManager.getWeatherPeriod().isThunderStorm())) {
                  alphaCircleRad *= 0.6F;
               }
            }
         }

         int x1 = 0;
         int y1 = 0;
         int x2 = 0 + IsoCamera.getOffscreenWidth(nPlayer);
         int y2 = 0 + IsoCamera.getOffscreenHeight(nPlayer);
         float topLeftX = IsoUtils.XToIso(0.0F, 0.0F, zLayer);
         float topLeftY = IsoUtils.YToIso(0.0F, 0.0F, zLayer);
         float bottomRightX = IsoUtils.XToIso(x2, y2, zLayer);
         float bottomRightY = IsoUtils.YToIso(x2, y2, zLayer);
         float bottomLeftY = IsoUtils.YToIso(0.0F, y2, zLayer);
         minY = PZMath.fastfloor(topLeftY);
         maxY = PZMath.fastfloor(bottomRightY);
         minX = PZMath.fastfloor(topLeftX);
         maxX = PZMath.fastfloor(bottomRightX);
         if (IsoPlayer.numPlayers > 1) {
            maxX = Math.max(maxX, IsoWorld.instance.currentCell.getMaxX()) + 20;
            maxY = Math.max(maxY, IsoWorld.instance.currentCell.getMaxY()) + 20;
         }

         minX = minX + minXOffset;
         maxX = maxX + maxXOffset;
         maxY = maxY + maxYOffset;
         int rows = maxX - minX;
         int rowlen = rows;
         if (minY != maxY) {
            rowlen += PZMath.abs(minY - maxY);
         }

         rectangleIter.reset(rows, rowlen);
         pzoptRows = rows; // pzopt: the mask walk replays the iterator's geometry
         pzoptRowLen = rowlen;
         lastRow = Integer.MAX_VALUE;
         fogRectangle.hasStarted = false;
         chunkMap = IsoWorld.instance.getCell().getChunkMap(nPlayer);
         return true;
      } else {
         drawingThisLayer = false;
         return false;
      }
   }

   public static void renderRowsBehind(IsoGridSquare squareMax) {
      if (drawingThisLayer) {
         int maxRow = -1;
         if (squareMax != null) {
            maxRow = squareMax.getX() + squareMax.getY();
            if (maxRow < minX + minY) {
               return;
            }
         }

         if (lastRow == Integer.MIN_VALUE || lastRow != maxRow) {
            Vector2i v = rectangleMatrixPos;

            while (rectangleIter.next(v)) {
               if (v != null) {
                  int x = v.x + minX;
                  int y = v.y + minY;
                  int curRow = x + y;
                  if (curRow != lastRow) {
                     if (lastRow != Integer.MIN_VALUE && (!renderEndOnly || squareMax == null)) {
                        endFogRectangle(lastIterPos.x, lastIterPos.y, zLayer);
                     }

                     lastRow = curRow;
                  }

                  IsoGridSquare square = chunkMap.getGridSquare(x, y, zLayer);
                  boolean hasFog = true;
                  if (square != null && (!square.isExteriorCache || square.isInARoom())) {
                     hasFog = false;
                  }

                  if (hasFog) {
                     if (!renderEndOnly || squareMax == null) {
                        startFogRectangle(x, y, zLayer);
                     }
                  } else if (!renderEndOnly || squareMax == null) {
                     endFogRectangle(lastIterPos.x, lastIterPos.y, zLayer);
                  }

                  lastIterPos.set(x, y);
                  if (maxRow != -1 && curRow == maxRow) {
                     break;
                  }
               }
            }
         }
      }
   }

   public static void endRender() {
      if (drawingThisLayer) {
         if (pzopt.FogPass.enabled()) { // pzopt: the whole layer in one walk, chunk looked up once per eight squares
            pzoptRenderAllRows();
         } else
         renderRowsBehind(null);
         if (fogRectangle.hasStarted) {
            endFogRectangle(lastIterPos.x, lastIterPos.y, zLayer);
         }
      }
   }

   /**
    * pzopt: renderRowsBehind(null) with the square lookup hoisted per chunk. Same visits, same rectangles: the
    * iterator, lastRow, lastIterPos and the open rectangle are the stock statics, and a missing chunk, an unloaded
    * chunk or a level the chunk has no squares for count as fog exactly as the stock null square does. The stock
    * IsoChunkMap.getGridSquare cost a chunk-map lookup per square (~10k squares per level per frame at max zoom).
    */
   private static int pzoptRows;
   private static int pzoptRowLen;

   // pzopt: per layer, the segments (start x, start y, end x, end y per segment) of the last mask walk, in world
   // squares, replayed while the visible diamond (minX, minY, maxX, maxY) is unchanged and no mask refresh is due;
   // the screen rectangles and depths are recomputed from them every frame by renderFogSegment
   private static final gnu.trove.list.array.TIntArrayList[] pzoptSegCache = {new gnu.trove.list.array.TIntArrayList(), new gnu.trove.list.array.TIntArrayList()};
   private static final long[] pzoptSegCacheKey = {Long.MIN_VALUE, Long.MIN_VALUE};
   private static final int[] pzoptSegCacheFrame = {Integer.MIN_VALUE, Integer.MIN_VALUE};
   private static boolean pzoptRecording;

   private static void pzoptRenderAllRows() {
      long t0 = System.nanoTime();
      int refresh = pzopt.Config.FOG_MASK_FRAMES;
      if (refresh > 0) {
         int l = zLayer & 1;
         int frame = IsoWorld.instance.getFrameNo();
         long key = ((long)minX & 0xFFFFL) | ((long)minY & 0xFFFFL) << 16 | ((long)maxX & 0xFFFFL) << 32 | ((long)maxY & 0xFFFFL) << 48;
         gnu.trove.list.array.TIntArrayList cache = pzoptSegCache[l];
         if (key == pzoptSegCacheKey[l] && frame - pzoptSegCacheFrame[l] < refresh && frame >= pzoptSegCacheFrame[l]) {
            for (int i = 0; i < cache.size(); i += 4) { // replay: same segments, this frame's camera offset and depths
               fogRectangle.hasStarted = false;
               fogRectangle.startX = cache.get(i);
               fogRectangle.startY = cache.get(i + 1);
               fogRectangle.endX = cache.get(i + 2);
               fogRectangle.endY = cache.get(i + 3);
               fogRectangle.z = zLayer;
               renderFogSegment();
            }
            pzoptSegments = cache.size() / 4;
            pzopt.FogPass.walked(System.nanoTime() - t0, 0L, pzoptSegments, 0);
            pzoptSegments = 0;
            return;
         }
         cache.clear();
         pzoptSegCacheKey[l] = key;
         pzoptSegCacheFrame[l] = frame;
         pzoptRecording = true;
         pzoptWalkMasks();
         pzoptRecording = false;
      } else {
         pzoptWalk();
      }
      pzopt.FogPass.walked(System.nanoTime() - t0, pzoptSegmentNs, pzoptSegments, pzoptSquares);
      pzoptSegmentNs = 0L;
      pzoptSegments = 0;
      pzoptSquares = 0;
   }

   private static long pzoptSegmentNs; // pzopt: game-thread cost split of the fog walk (FogPass.stats)
   private static int pzoptSegments;
   private static int pzoptSquares;

   private static void pzoptWalk() {
      Vector2i v = rectangleMatrixPos;
      IsoChunk chunk = null;
      int chunkX = Integer.MIN_VALUE;
      int chunkY = Integer.MIN_VALUE;
      boolean chunkOk = false;
      while (rectangleIter.next(v)) {
         int x = v.x + minX;
         int y = v.y + minY;
         int curRow = x + y;
         if (curRow != lastRow) {
            if (lastRow != Integer.MIN_VALUE && fogRectangle.hasStarted) {
               pzoptEndSegment();
            }
            lastRow = curRow;
         }
         int cx = x >> 3;
         int cy = y >> 3;
         if (cx != chunkX || cy != chunkY) {
            chunkX = cx;
            chunkY = cy;
            chunk = chunkMap.getChunkForGridSquare(x, y);
            chunkOk = chunk != null && chunk.loaded && chunk.wx == cx && chunk.wy == cy; // the mid-scroll guard of the IsoChunkMap override
         }
         IsoGridSquare square = chunkOk ? chunk.getGridSquare(x & 7, y & 7, zLayer) : null;
         boolean hasFog = square == null || square.isExteriorCache && !square.isInARoom();
         pzoptSquares++;
         if (hasFog) {
            startFogRectangle(x, y, zLayer);
         } else if (fogRectangle.hasStarted) {
            pzoptEndSegment();
         }
         lastIterPos.set(x, y);
      }
   }

   /**
    * pzopt: the same visits as pzoptWalk (the RectangleIterator's rows: alternating lengths ceil(rowlen/2) and
    * ceil(rowlen/2)+1, starts stepping (0,0) (0,1) (1,1) (1,2) ..., the last position of the last row never
    * delivered), but the fog test per square comes from the chunk's per-diagonal masks (pzopt.FogPass.ChunkFog),
    * refreshed every Config.fogMaskFrames frames, so the walk touches one chunk object per up to eight squares
    * and no square objects. Rectangles (start / end squares, rows) are identical to the stock walk's while the
    * masks are current.
    */
   private static void pzoptWalkMasks() {
      int rows = pzoptRows;
      int rowLen = (int)PZMath.ceil((float)pzoptRowLen / 2.0F);
      if (rowLen <= 0 || rows <= 0) {
         return;
      }
      int frame = IsoWorld.instance.getFrameNo();
      int refresh = pzopt.Config.FOG_MASK_FRAMES;
      int z = zLayer;
      int sX = 0;
      int sY = 0;
      boolean altRow = false;
      IsoChunk chunk = null;
      int chunkX = Integer.MIN_VALUE;
      int chunkY = Integer.MIN_VALUE;
      short[] diag = null;
      for (int k = 0; k < rows; k++) {
         int len = rowLen;
         if (k == rows - 1) {
            len--; // the iterator returns false on the last row's last position before it is used
         }
         int x = minX + sX;
         int y = minY + sY;
         int curRow = x + y;
         if (curRow != lastRow) {
            if (lastRow != Integer.MIN_VALUE && fogRectangle.hasStarted) {
               pzoptEndSegment();
            }
            lastRow = curRow;
         }
         int j = 0;
         while (j < len) {
            int cx = x >> 3;
            int cy = y >> 3;
            int lx = x & 7;
            int ly = y & 7;
            int n = Math.min(Math.min(8 - lx, ly + 1), len - j);
            if (cx != chunkX || cy != chunkY) {
               chunkX = cx;
               chunkY = cy;
               chunk = chunkMap.getChunkForGridSquare(x, y);
               if (chunk != null && chunk.loaded && chunk.wx == cx && chunk.wy == cy) {
                  pzopt.FogPass.ChunkFog fog = chunk.pzoptFog;
                  if (fog == null) {
                     fog = chunk.pzoptFog = new pzopt.FogPass.ChunkFog();
                  }
                  diag = fog.diagonals(chunk, z, frame, refresh);
               } else {
                  diag = null; // no chunk: every square takes fog, as the stock null square does
               }
            }
            int full = (1 << n) - 1;
            int bits = diag == null ? full : (diag[lx + ly] >> lx) & full;
            pzoptSquares += n;
            if (bits == full) { // every square of the run takes fog (the common case outdoors): one step
               if (!fogRectangle.hasStarted) {
                  startFogRectangle(x, y, z);
               }
               x += n;
               y -= n;
               lastIterPos.set(x - 1, y + 1);
            } else if (bits == 0) { // none does: the open rectangle ends at the previous square
               if (fogRectangle.hasStarted) {
                  pzoptEndSegment();
               }
               x += n;
               y -= n;
               lastIterPos.set(x - 1, y + 1);
            } else {
               for (int i = 0; i < n; i++) {
                  if ((bits >> i & 1) != 0) {
                     startFogRectangle(x, y, z);
                  } else if (fogRectangle.hasStarted) {
                     pzoptEndSegment();
                  }
                  lastIterPos.set(x, y);
                  x++;
                  y--;
               }
            }
            j += n;
         }
         if (altRow) {
            rowLen--;
            sX++;
         } else {
            rowLen++;
            sY++;
         }
         altRow = !altRow;
      }
   }

   /** pzopt: endFogRectangle with its time and count recorded. */
   private static void pzoptEndSegment() {
      long t0 = System.nanoTime();
      endFogRectangle(lastIterPos.x, lastIterPos.y, zLayer);
      if (pzoptRecording) {
         gnu.trove.list.array.TIntArrayList cache = pzoptSegCache[zLayer & 1];
         cache.add(fogRectangle.startX);
         cache.add(fogRectangle.startY);
         cache.add(fogRectangle.endX);
         cache.add(fogRectangle.endY);
      }
      pzoptSegmentNs += System.nanoTime() - t0;
      pzoptSegments++;
   }

   private static void startFogRectangle(int x, int y, int z) {
      if (!fogRectangle.hasStarted) {
         fogRectangle.hasStarted = true;
         fogRectangle.startX = x;
         fogRectangle.startY = y;
         fogRectangle.z = z;
      }
   }

   private static void endFogRectangle(int x, int y, int z) {
      if (fogRectangle.hasStarted) {
         fogRectangle.hasStarted = false;
         fogRectangle.endX = x;
         fogRectangle.endY = y;
         fogRectangle.z = z;
         renderFogSegment();
      }
   }

   private static void renderFogSegmentOld() {
      int row = fogRectangle.startX + fogRectangle.startY;
      int row2 = fogRectangle.endX + fogRectangle.endY;
      if (Core.debug && row != row2) {
         DebugLog.log("ROWS NOT EQUAL");
      }

      if (renderOnlyOneRow) {
         if (row != playerRow) {
            return;
         }
      } else if (row % renderEveryXRow != 0) {
         return;
      }

      if (!Core.debug || renderXRowsFromCenter < 1 || row >= playerRow - renderXRowsFromCenter && row <= playerRow + renderXRowsFromCenter) {
         float alpha = baseAlpha;
         ImprovedFog.FogRectangle f = fogRectangle;
         float sx = IsoUtils.XToScreenExact(f.startX, f.startY, f.z, 0);
         float sy = IsoUtils.YToScreenExact(f.startX, f.startY, f.z, 0);
         float ex = IsoUtils.XToScreenExact(f.endX, f.endY, f.z, 0);
         float ey = IsoUtils.YToScreenExact(f.endX, f.endY, f.z, 0);
         sx -= 64.0F;
         sy -= 160.0F;
         ex += 64.0F;
         float height = 192.0F;
         float segmentTileWidth = 6.0F;
         float widthTiles = (ex - sx) / 128.0F;
         float offset = f.startX % 6.0F;
         float uvOffset = offset / 6.0F;
         float uvWidth = widthTiles / 6.0F;
         float u0 = uvOffset;
         float v0 = 0.0F;
         float u1 = uvWidth + uvOffset;
         float v1 = 1.0F;
         SpriteRenderer.instance.glEnable(3042);
         IndieGL.glBlendFunc(770, 771);
         if (FogShader.instance.StartShader()) {
            FogShader.instance.setScreenInfo(screenWidth, screenHeight, zoom, zLayer > 0 ? secondLayerAlpha : 1.0F);
            FogShader.instance.setTextureInfo(drawDebugColors ? 1.0F : 0.0F, octaves, alpha, 2.0F);
            FogShader.instance.setRectangleInfo((int)sx, (int)sy, (int)(ex - sx), 192.0F);
            FogShader.instance.setWorldOffset(worldOffsetX, worldOffsetY, rightClickOffX, rightClickOffY);
            FogShader.instance.setScalingInfo(scalingX, scalingY, zLayer, highQuality ? 0.0F : 1.0F);
            FogShader.instance.setColorInfo(colorR, colorG, colorB, 1.0F);
            FogShader.instance.setParamInfo(topAlphaHeight, bottomAlphaHeight, alphaCircleAlpha, alphaCircleRad);
            FogShader.instance.setCameraInfo(cameraOffscreenLeft, cameraOffscreenTop, cameraZoom, offsets[Math.abs(row) % offsets.length]);
            if (PerformanceSettings.fboRenderChunk) {
               IndieGL.glDepthMask(false);
               IndieGL.enableDepthTest();
               IndieGL.glDepthFunc(513);
               float x = fogRectangle.startX + (fogRectangle.endX - fogRectangle.startX) / 2.0F;
               float y = fogRectangle.startY + (fogRectangle.endY - fogRectangle.startY) / 2.0F;
               int z = fogRectangle.z;
               float xm = PZMath.coordmodulof(x, 8) + 0.5F;
               float ym = PZMath.coordmodulof(y, 8) + 0.5F;
               float farDepthZ = IsoSprite.calculateDepth(xm - 1.0F, ym - 1.0F, z);
               float frontDepthZ = IsoSprite.calculateDepth(xm, ym, z);
               float farDepthZ2 = IsoSprite.calculateDepth(xm - 1.0F, ym - 1.0F, z + 1);
               float frontDepthZ2 = IsoSprite.calculateDepth(xm, ym, z + 1);
               float calcDepthZ = farDepthZ - frontDepthZ + frontDepthZ;
               float calcDepthZ2 = farDepthZ2 - frontDepthZ2 + frontDepthZ2;
               float calcDepth = calcDepthZ2 - calcDepthZ + calcDepthZ;
               calcDepth += IsoDepthHelper.getChunkDepthData(
                     PZMath.fastfloor(IsoPlayer.getInstance().getX() / 8.0F),
                     PZMath.fastfloor(IsoPlayer.getInstance().getY() / 8.0F),
                     PZMath.fastfloor(x / 8.0F),
                     PZMath.fastfloor(y / 8.0F),
                     PZMath.fastfloor(z)
                  )
                  .depthStart;
               FogShader.instance.setTargetDepth(calcDepth);
            }

            SpriteRenderer.instance
               .render(noiseTexture, (int)sx, (int)sy, (int)(ex - sx), 192.0F, 1.0F, 1.0F, 1.0F, alpha, u0, 0.0F, u1, 0.0F, u1, 1.0F, u0, 1.0F);
            IndieGL.EndShader();
         }
      }
   }

   private static void renderFogSegment() {
      if (!PerformanceSettings.fboRenderChunk) {
         renderFogSegmentOld();
      } else {
         int row = fogRectangle.startX + fogRectangle.startY;
         int row2 = fogRectangle.endX + fogRectangle.endY;
         if (Core.debug && row != row2) {
            DebugLog.log("ROWS NOT EQUAL");
         }

         if (renderOnlyOneRow) {
            if (row != playerRow) {
               return;
            }
         } else if (row % renderEveryXRow != 0) {
            return;
         }

         if (!Core.debug || renderXRowsFromCenter < 1 || row >= playerRow - renderXRowsFromCenter && row <= playerRow + renderXRowsFromCenter) {
            float alpha = baseAlpha;
            ImprovedFog.FogRectangle f = fogRectangle;
            float sx = IsoUtils.XToScreenExact(f.startX, f.startY, f.z, 0);
            float sy = IsoUtils.YToScreenExact(f.startX, f.startY, f.z, 0);
            float ex = IsoUtils.XToScreenExact(f.endX, f.endY, f.z, 0);
            float ey = IsoUtils.YToScreenExact(f.endX, f.endY, f.z, 0);
            sx -= 64.0F;
            sy -= 160.0F;
            ex += 64.0F;
            float height = 192.0F;
            float segmentTileWidth = 6.0F;
            float widthTiles = (ex - sx) / 128.0F;
            float offset = f.startX % 6.0F;
            float uvOffset = offset / 6.0F;
            float uvWidth = widthTiles / 6.0F;
            float u0 = uvOffset;
            float v0 = 0.0F;
            float u1 = uvWidth + uvOffset;
            float v1 = 1.0F;
            float depthBottom = 0.0F;
            float depthTop = 0.0F;
            if (PerformanceSettings.fboRenderChunk) {
               int z = fogRectangle.z;
               float x = IsoUtils.XToIso(sx + (ex - sx) / 2.0F, sy + (ey - sy) / 2.0F, z);
               float y = IsoUtils.YToIso(sx + (ex - sx) / 2.0F, sy + (ey - sy) / 2.0F, z);
               float xm = x + 1.5F;
               float ym = y + 1.5F;
               float farDepthZ = IsoDepthHelper.getSquareDepthData(
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterX), PZMath.fastfloor(IsoCamera.frameState.camCharacterY), xm - 1.0F, ym - 1.0F, z
                  )
                  .depthStart;
               float frontDepthZ = IsoDepthHelper.getSquareDepthData(
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterX), PZMath.fastfloor(IsoCamera.frameState.camCharacterY), xm, ym, z
                  )
                  .depthStart;
               float farDepthZ2 = IsoDepthHelper.getSquareDepthData(
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterX), PZMath.fastfloor(IsoCamera.frameState.camCharacterY), xm - 1.0F, ym - 1.0F, z + 1
                  )
                  .depthStart;
               float frontDepthZ2 = IsoDepthHelper.getSquareDepthData(
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterX), PZMath.fastfloor(IsoCamera.frameState.camCharacterY), xm, ym, z + 1
                  )
                  .depthStart;
               float calcDepthZ = farDepthZ - frontDepthZ + frontDepthZ;
               float calcDepthZ2 = farDepthZ2 - frontDepthZ2 + frontDepthZ2;
               float calcDepth = calcDepthZ2 - calcDepthZ + calcDepthZ;
               depthBottom = calcDepth;
               depthTop = depthBottom - (ey - sy) / 96.0F * 0.0028867084F * 0.6F;
            }

            ImprovedFogDrawer drawer = getDrawer();
            drawer.addRectangle(
               sx,
               sy,
               ex,
               sy + 192.0F,
               u0,
               0.0F,
               u1,
               1.0F,
               offsets[Math.abs(row) % offsets.length],
               depthBottom,
               depthTop,
               zLayer > 0 ? secondLayerAlpha : 1.0F,
               zLayer
            );
         }
      }
   }

   public static void DrawSubTextureRGBA(
      Texture tex, double subX, double subY, double subW, double subH, double x, double y, double w, double h, double r, double g, double b, double a
   ) {
      if (tex != null && !(subW <= 0.0) && !(subH <= 0.0) && !(w <= 0.0) && !(h <= 0.0)) {
         double drawX = x;
         double drawY = y;
         drawX += tex.offsetX;
         drawY += tex.offsetY;
         if (!(drawY + h < 0.0) && !(drawY > 4096.0)) {
            float fsubX = PZMath.clamp((float)subX, 0.0F, tex.getWidth());
            float fsubY = PZMath.clamp((float)subY, 0.0F, tex.getHeight());
            float fsubW = PZMath.clamp((float)(fsubX + subW), 0.0F, tex.getWidth()) - fsubX;
            float fsubH = PZMath.clamp((float)(fsubY + subH), 0.0F, tex.getHeight()) - fsubY;
            float u0 = fsubX / tex.getWidth();
            float v0 = fsubY / tex.getHeight();
            float u1 = (fsubX + fsubW) / tex.getWidth();
            float v1 = (fsubY + fsubH) / tex.getHeight();
            float uSpan = tex.getXEnd() - tex.getXStart();
            float vSpan = tex.getYEnd() - tex.getYStart();
            u0 = tex.getXStart() + u0 * uSpan;
            u1 = tex.getXStart() + u1 * uSpan;
            v0 = tex.getYStart() + v0 * vSpan;
            v1 = tex.getYStart() + v1 * vSpan;
            SpriteRenderer.instance
               .render(tex, (float)drawX, (float)drawY, (float)w, (float)h, (float)r, (float)g, (float)b, (float)a, u0, v0, u1, v0, u1, v1, u0, v1);
         }
      }
   }

   public static void updateKeys() {
      if (Core.debug) {
         if (keyPause > 0) {
            keyPause--;
         }

         if (keyPause <= 0 && GameKeyboard.isKeyDown(72)) {
            DebugLog.log("Reloading fog shader...");
            keyPause = 30;
            FogShader.instance.reloadShader();
         }
      }
   }

   public static ImprovedFogDrawer getDrawer() {
      int playerIndex = IsoCamera.frameState.playerIndex;
      int stateIndex = SpriteRenderer.instance.getMainStateIndex();
      if (drawers[playerIndex][stateIndex] == null) {
         drawers[playerIndex][stateIndex] = new ImprovedFogDrawer();
      }

      return drawers[playerIndex][stateIndex];
   }

   public static void startFrame(ImprovedFogDrawer drawer) {
      drawer.screenInfo1 = screenWidth;
      drawer.screenInfo2 = screenHeight;
      drawer.screenInfo3 = zoom;
      drawer.screenInfo4 = Float.NaN;
      drawer.textureInfo1 = drawDebugColors ? 1.0F : 0.0F;
      drawer.textureInfo2 = octaves;
      drawer.textureInfo3 = baseAlpha;
      drawer.textureInfo4 = 2.0F;
      drawer.worldOffset1 = worldOffsetX;
      drawer.worldOffset2 = worldOffsetY;
      drawer.worldOffset3 = rightClickOffX;
      drawer.worldOffset4 = rightClickOffY;
      drawer.scalingInfo1 = scalingX;
      drawer.scalingInfo2 = scalingY;
      drawer.scalingInfo3 = Float.NaN;
      drawer.scalingInfo4 = highQuality ? 1.0F : 0.0F;
      drawer.colorInfo1 = colorR;
      drawer.colorInfo2 = colorG;
      drawer.colorInfo3 = colorB;
      drawer.colorInfo4 = 1.0F;
      drawer.paramInfo1 = topAlphaHeight;
      drawer.paramInfo2 = bottomAlphaHeight;
      drawer.paramInfo3 = alphaCircleAlpha;
      drawer.paramInfo4 = alphaCircleRad;
      drawer.cameraInfo1 = cameraOffscreenLeft;
      drawer.cameraInfo2 = cameraOffscreenTop;
      drawer.cameraInfo3 = cameraZoom;
      drawer.cameraInfo4 = Float.NaN;
      drawer.alpha = baseAlpha;
   }

   public static void init() {
      climateManager = ClimateManager.getInstance();
      fogIntensity.setTarget(climateManager.getFogIntensity());
      fogIntensity.overrideCurrentValue(climateManager.getFogIntensity());
      baseAlpha = fogIntensity.value();
   }

   private static class FogRectangle {
      int startX;
      int startY;
      int endX;
      int endY;
      int z;
      boolean hasStarted;
   }

   private static class RectangleIterator {
      private int curX;
      private int curY;
      private int sX;
      private int sY;
      private int rowLen;
      private boolean altRow;
      private int curRow;
      private int rowIndex;
      private int maxRows;

      public void reset(int rows, int rowlen) {
         this.sX = 0;
         this.sY = 0;
         this.curX = 0;
         this.curY = 0;
         this.curRow = 0;
         this.altRow = false;
         this.rowIndex = 0;
         this.rowLen = (int)PZMath.ceil(rowlen / 2.0F);
         this.maxRows = rows;
      }

      public boolean next(Vector2i vec) {
         if (this.rowLen > 0 && this.maxRows > 0 && this.curRow < this.maxRows) {
            vec.set(this.curX, this.curY);
            this.rowIndex++;
            if (this.rowIndex == this.rowLen) {
               this.rowLen = this.altRow ? this.rowLen - 1 : this.rowLen + 1;
               this.rowIndex = 0;
               this.sX = this.altRow ? this.sX + 1 : this.sX;
               this.sY = this.altRow ? this.sY : this.sY + 1;
               this.altRow = !this.altRow;
               this.curX = this.sX;
               this.curY = this.sY;
               this.curRow++;
               return this.curRow != this.maxRows;
            } else {
               this.curX++;
               this.curY--;
               return true;
            }
         } else {
            vec.set(0, 0);
            return false;
         }
      }
   }
}
