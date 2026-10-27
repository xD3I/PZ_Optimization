package zombie.worldMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.DefaultShader;
import zombie.core.ShaderHelper;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.textures.TextureID;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.iso.SaveBufferMap;
import zombie.iso.SliceY;
import zombie.iso.Vector2;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerGUI;
import zombie.util.ByteBufferPooledObject;
import zombie.worldMap.styles.WorldMapStyleLayer.RGBAf;

@UsedFromLua
public class WorldMapVisited {
   private static WorldMapVisited instance;
   private static final int VERSION1 = 1;
   private static final int VERSION2 = 2;
   private static final int VERSION = 2;
   private int minX;
   private int minY;
   private int maxX;
   private int maxY;
   byte[] visited;
   int span;
   boolean changed;
   private byte[] pzoptRow; // pzopt: mapVisitedFast, one texture row built before a bulk put
   int changeX1;
   int changeY1;
   int changeX2;
   int changeY2;
   boolean saveChanged;
   int saveChangeX1;
   int saveChangeY1;
   int saveChangeX2;
   int saveChangeY2;
   private final int[] updateMinX = new int[4];
   private final int[] updateMinY = new int[4];
   private final int[] updateMaxX = new int[4];
   private final int[] updateMaxY = new int[4];
   private static final int TEXTURE_BPP = 4;
   private TextureID textureId;
   private int textureW;
   private int textureH;
   private ByteBuffer textureBuffer;
   private boolean textureChanged;
   private final RGBAf color = new RGBAf().init(0.85882354F, 0.84313726F, 0.7529412F, 1.0F);
   private final RGBAf gridColor = new RGBAf().init(this.color.r * 0.85F, this.color.g * 0.85F, this.color.b * 0.85F, 1.0F);
   private boolean mainMenu;
   private boolean client;
   private static ShaderProgram shaderProgram;
   private static ShaderProgram gridShaderProgram;
   static final int HEADER_BYTES = 28;
   static final int RADIUS = 25;
   static final int UNITS_PER_CELL = 8;
   static final int SQUARES_PER_CELL = 256;
   static final int SQUARES_PER_UNIT = 32;
   static final int BITS_PER_UNIT = 2;
   static final int UNITS_PER_BYTE = 4;
   static final int TEXTURE_PAD = 1;
   static final int BIT_VISITED = 1;
   static final int BIT_KNOWN = 2;
   Vector2 vector2 = new Vector2();

   public WorldMapVisited() {
      Arrays.fill(this.updateMinX, -1);
      Arrays.fill(this.updateMinY, -1);
      Arrays.fill(this.updateMaxX, -1);
      Arrays.fill(this.updateMaxY, -1);
   }

   public void setBounds(int minX, int minY, int maxX, int maxY) {
      if (minX > maxX || minY > maxY) {
         minX = maxX = minY = maxY = 0; // pzopt: decompiler fix, one chained assignment as in the jar
         this.mainMenu = true;
      }

      this.minX = minX;
      this.minY = minY;
      this.maxX = maxX;
      this.maxY = maxY;
      this.changed = true;
      this.changeX1 = 0;
      this.changeY1 = 0;
      this.changeX2 = getWidthInCells() * 8 - 1;
      this.changeY2 = getHeightInCells() * 8 - 1;
      this.saveChanged = false;
      this.saveChangeX1 = Integer.MAX_VALUE;
      this.saveChangeY1 = Integer.MAX_VALUE;
      this.saveChangeX2 = Integer.MIN_VALUE;
      this.saveChangeY2 = Integer.MIN_VALUE;
      this.span = getSpan();
      this.visited = new byte[getVisitedLength()];
      this.textureW = this.calcTextureWidth();
      this.textureH = this.calcTextureHeight();
      this.textureBuffer = BufferUtils.createByteBuffer(this.textureW * this.textureH * 4);
      this.textureBuffer.limit(this.textureBuffer.capacity());
      byte r = (byte)(SandboxOptions.getInstance().map.mapAllKnown.getValue() ? 0 : -1);
      byte g = -1;
      byte b = -1;
      byte a = -1;

      for (int i = 0; i < this.textureBuffer.limit(); i += 4) {
         this.textureBuffer.put(i, r);
         this.textureBuffer.put(i + 1, (byte)-1);
         this.textureBuffer.put(i + 2, (byte)-1);
         this.textureBuffer.put(i + 3, (byte)-1);
      }

      if (!GameServer.server || ServerGUI.isCreated()) {
         this.textureId = new TextureID(this.textureW, this.textureH, 0);
      }
   }

   public int getMinX() {
      return this.minX;
   }

   public int getMinY() {
      return this.minY;
   }

   private static int getWidthInCells() {
      IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
      return metaGrid.maxX - metaGrid.minX + 1;
   }

   private static int getHeightInCells() {
      IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
      return metaGrid.maxY - metaGrid.minY + 1;
   }

   private static int getSpan() {
      return getWidthInCells() * 8 / 4;
   }

   public static int getVisitedLength() {
      return getSpan() * getHeightInCells() * 8;
   }

   private int calcTextureWidth() {
      return PZMath.smallestEncompassingPowerOfTwo(getWidthInCells() * 8 + 2);
   }

   private int calcTextureHeight() {
      return PZMath.smallestEncompassingPowerOfTwo(getHeightInCells() * 8 + 2);
   }

   public void setKnownInCells(int minX, int minY, int maxX, int maxY) {
      this.setFlags(minX * 256, minY * 256, (maxX + 1) * 256, (maxY + 1) * 256, 2);
   }

   public void clearKnownInCells(int minX, int minY, int maxX, int maxY) {
      this.clearFlags(minX * 256, minY * 256, (maxX + 1) * 256, (maxY + 1) * 256, 2);
   }

   public void setVisitedInCells(int minX, int minY, int maxX, int maxY) {
      this.setFlags(minX * 256, minY * 256, maxX * 256, maxY * 256, 1);
   }

   public void clearVisitedInCells(int minX, int minY, int maxX, int maxY) {
      this.clearFlags(minX * 256, minY * 256, maxX * 256, maxY * 256, 1);
   }

   public static void setKnownInSquares(int minX, int minY, int maxX, int maxY, byte[] visited) {
      setFlags(minX, minY, maxX, maxY, 2, visited);
   }

   public void setKnownInSquares(int minX, int minY, int maxX, int maxY) {
      this.setFlags(minX, minY, maxX, maxY, 2);
   }

   public void clearKnownInSquares(int minX, int minY, int maxX, int maxY) {
      this.clearFlags(minX, minY, maxX, maxY, 2);
   }

   public void setVisitedInSquares(int minX, int minY, int maxX, int maxY) {
      this.setFlags(minX, minY, maxX, maxY, 1);
   }

   public void clearVisitedInSquares(int minX, int minY, int maxX, int maxY) {
      this.clearFlags(minX, minY, maxX, maxY, 1);
   }

   private void updateVisitedTexture() {
      this.textureId.bind();
      GL11.glTexImage2D(3553, 0, 6408, this.textureW, this.textureH, 0, 6408, 5121, this.textureBuffer);
   }

   public void renderMain() {
      this.textureChanged = this.textureChanged | this.updateTextureData(this.textureBuffer, this.textureW);
   }

   private void initShader() {
      shaderProgram = ShaderProgram.createShaderProgram("worldMapVisited", false, false, true);
      if (shaderProgram.isCompiled()) {
      }
   }

   public void render(float renderX, float renderY, int minX, int minY, int maxX, int maxY, float worldScale, boolean blur) {
      if (!this.mainMenu) {
         GL13.glActiveTexture(33984);
         GL11.glEnable(3553);
         if (this.textureChanged) {
            this.textureChanged = false;
            this.updateVisitedTexture();
         }

         this.textureId.bind();
         int filter = blur ? 9729 : 9728;
         GL11.glTexParameteri(3553, 10241, filter);
         GL11.glTexParameteri(3553, 10240, filter);
         GL11.glEnable(3042);
         GL11.glTexEnvi(8960, 8704, 8448);
         GL11.glTexParameteri(3553, 10242, 33071);
         GL11.glTexParameteri(3553, 10243, 33071);
         GL11.glColor4f(this.color.r, this.color.g, this.color.b, this.color.a);
         if (shaderProgram == null) {
            this.initShader();
         }

         if (shaderProgram.isCompiled()) {
            shaderProgram.Start();
            float u0 = (float)(1 + (minX - this.minX) * 8) / this.textureW;
            float v0 = (float)(1 + (minY - this.minY) * 8) / this.textureH;
            float u1 = (float)(1 + (maxX + 1 - this.minX) * 8) / this.textureW;
            float v1 = (float)(1 + (maxY + 1 - this.minY) * 8) / this.textureH;
            float x0 = (minX - this.minX) * 256 * worldScale;
            float y0 = (minY - this.minY) * 256 * worldScale;
            float x1 = (maxX + 1 - this.minX) * 256 * worldScale;
            float y1 = (maxY + 1 - this.minY) * 256 * worldScale;
            VBORenderer vbor = VBORenderer.getInstance();
            vbor.startRun(vbor.formatPositionColorUv);
            vbor.setShaderProgram(shaderProgram);
            vbor.setMode(7);
            vbor.setTextureID(this.textureId);
            vbor.addQuad(renderX + x0, renderY + y0, u0, v0, renderX + x1, renderY + y1, u1, v1, 0.0F, this.color.r, this.color.g, this.color.b, this.color.a);
            vbor.endRun();
            vbor.flush();
            shaderProgram.End();
            DefaultShader.isActive = false;
            ShaderHelper.forgetCurrentlyBound();
            GL20.glUseProgram(0);
         }
      }
   }

   public void renderGrid(float renderX, float renderY, int minX, int minY, int maxX, int maxY, float worldScale, float zoomF) {
      if (!(zoomF < 11.0F)) {
         if (gridShaderProgram == null) {
            gridShaderProgram = ShaderProgram.createShaderProgram("worldMapGrid", false, false, true);
         }

         if (gridShaderProgram.isCompiled()) {
            float r = this.gridColor.r;
            float g = this.gridColor.g;
            float b = this.gridColor.b;
            float a = this.gridColor.a;
            int mult = 1;
            if (zoomF < 13.0F) {
               mult = 8;
            } else if (zoomF < 14.0F) {
               mult = 4;
            } else if (zoomF < 15.0F) {
               mult = 2;
            }

            VBORenderer vbor = VBORenderer.getInstance();
            vbor.startRun(vbor.formatPositionColor);
            vbor.setMode(1);
            vbor.setLineWidth(0.5F);
            this.renderGridLinesVertical(renderX, renderY, minX * 8, minY * 8, this.minX * 8, (maxY + 1) * 8, worldScale, mult, r, g, b, a);
            this.renderGridLinesVertical(renderX, renderY, this.minX * 8, minY * 8, (this.maxX + 1) * 8 + mult, this.minY * 8, worldScale, mult, r, g, b, a);
            this.renderGridLinesVertical(
               renderX, renderY, this.minX * 8, (this.maxY + 1) * 8, (this.maxX + 1) * 8 + mult, (maxY + 1) * 8, worldScale, mult, r, g, b, a
            );
            this.renderGridLinesVertical(renderX, renderY, (this.maxX + 1) * 8 + mult, minY * 8, (maxX + 1) * 8, (maxY + 1) * 8, worldScale, mult, r, g, b, a);
            this.renderGridLinesHorizontal(renderX, renderY, minX * 8, minY * 8, (maxX + 1) * 8, this.minY * 8, worldScale, mult, r, g, b, a);
            this.renderGridLinesHorizontal(renderX, renderY, minX * 8, this.minY * 8, this.minX * 8, (this.maxY + 1) * 8 + mult, worldScale, mult, r, g, b, a);
            this.renderGridLinesHorizontal(
               renderX, renderY, (this.maxX + 1) * 8, this.minY * 8, (maxX + 1) * 8, (this.maxY + 1) * 8 + mult, worldScale, mult, r, g, b, a
            );
            this.renderGridLinesHorizontal(renderX, renderY, minX * 8, (this.maxY + 1) * 8 + mult, (maxX + 1) * 8, (maxY + 1) * 8, worldScale, mult, r, g, b, a);
            vbor.endRun();
            vbor.flush();
            g = this.gridColor.g;
            minX = PZMath.clamp(minX, this.minX, this.maxX);
            minY = PZMath.clamp(minY, this.minY, this.maxY);
            maxX = PZMath.clamp(maxX, this.minX, this.maxX);
            maxY = PZMath.clamp(maxY, this.minY, this.maxY);
            gridShaderProgram.Start();
            float minXui = renderX + (minX * 256 - this.minX * 256) * worldScale;
            float minYui = renderY + (minY * 256 - this.minY * 256) * worldScale;
            float maxXui = minXui + (maxX - minX + 1) * 256 * worldScale;
            float maxYui = minYui + (maxY - minY + 1) * 256 * worldScale;
            vbor.startRun(vbor.formatPositionColorUv);
            vbor.setMode(1);
            vbor.setLineWidth(0.5F);
            vbor.setShaderProgram(gridShaderProgram);
            vbor.setTextureID(this.textureId);
            vbor.cmdShader2f("UVOffset", 0.5F / this.textureW, 0.0F);

            for (int x = minX * 8; x <= (maxX + 1) * 8; x += mult) {
               vbor.reserve(2);
               vbor.addElement(
                  renderX + (x * 32 - this.minX * 256) * worldScale,
                  minYui,
                  0.0F,
                  (float)(1 + x - this.minX * 8) / this.textureW,
                  1.0F / this.textureH,
                  r,
                  g,
                  b,
                  a
               );
               vbor.addElement(
                  renderX + (x * 32 - this.minX * 256) * worldScale,
                  maxYui,
                  0.0F,
                  (float)(1 + x - this.minX * 8) / this.textureW,
                  (float)(1 + getHeightInCells() * 8) / this.textureH,
                  r,
                  g,
                  b,
                  a
               );
            }

            vbor.endRun();
            vbor.startRun(vbor.formatPositionColorUv);
            vbor.setMode(1);
            vbor.setLineWidth(0.5F);
            vbor.setShaderProgram(gridShaderProgram);
            vbor.setTextureID(this.textureId);
            vbor.cmdShader2f("UVOffset", 0.0F, 0.5F / this.textureH);

            for (int y = minY * 8; y <= (maxY + 1) * 8; y += mult) {
               vbor.reserve(2);
               vbor.addElement(
                  minXui,
                  renderY + (y * 32 - this.minY * 256) * worldScale,
                  0.0F,
                  1.0F / this.textureW,
                  (float)(1 + y - this.minY * 8) / this.textureH,
                  r,
                  g,
                  b,
                  a
               );
               vbor.addElement(
                  maxXui,
                  renderY + (y * 32 - this.minY * 256) * worldScale,
                  0.0F,
                  (float)(1 + getWidthInCells() * 8) / this.textureW,
                  (float)(1 + y - this.minY * 8) / this.textureH,
                  r,
                  g,
                  b,
                  a
               );
            }

            vbor.endRun();
            vbor.flush();
            gridShaderProgram.End();
            DefaultShader.isActive = false;
            ShaderHelper.forgetCurrentlyBound();
            GL20.glUseProgram(0);
         }
      }
   }

   private void renderGridLinesHorizontal(
      float renderX, float renderY, int minX, int minY, int maxX, int maxY, float worldScale, int mult, float r, float g, float b, float a
   ) {
      VBORenderer vbor = VBORenderer.getInstance();
      int y = minY;

      while (y < maxY) {
         vbor.reserve(2);
         vbor.addElement(renderX + minX * 32 * worldScale, renderY + (y * 32 - this.minY * 256) * worldScale, 0.0F, r, g, b, a);
         vbor.addElement(renderX + maxX * 32 * worldScale, renderY + (y * 32 - this.minY * 256) * worldScale, 0.0F, r, g, b, a);
         y += mult;
      }
   }

   private void renderGridLinesVertical(
      float renderX, float renderY, int minX, int minY, int maxX, int maxY, float worldScale, int mult, float r, float g, float b, float a
   ) {
      VBORenderer vbor = VBORenderer.getInstance();
      int x = minX;

      while (x < maxX) {
         vbor.reserve(2);
         vbor.addElement(renderX + (x * 32 - this.minX * 256) * worldScale, renderY + minY * 32 * worldScale, 0.0F, r, g, b, a);
         vbor.addElement(renderX + (x * 32 - this.minX * 256) * worldScale, renderY + maxY * 32 * worldScale, 0.0F, r, g, b, a);
         x += mult;
      }
   }

   private void destroy() {
      if (this.textureId != null) {
         RenderThread.invokeOnRenderContext(this.textureId::destroy);
      }

      this.textureBuffer = null;
      this.visited = null;
   }

   private void saveHeader(ByteBuffer output) {
      output.putInt(251);
      output.putInt(2);
      output.putInt(this.minX);
      output.putInt(this.minY);
      output.putInt(this.maxX);
      output.putInt(this.maxY);
      output.putInt(8);
      if (output.position() != 28) {
         throw new RuntimeException("HEADER_BYTES is incorrect");
      }
   }

   public void save() throws IOException {
      if (!Core.getInstance().isNoSave() && !this.client) {
         File file = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("map_visited.bin"));

         try (
            FileOutputStream fos = new FileOutputStream(file);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
         ) {
            ByteBuffer out = SliceY.SliceBuffer;
            out.clear();
            this.saveHeader(out);
            bos.write(out.array(), 0, out.position());

            for (int i = 0; i < this.visited.length; i += out.capacity()) {
               out.clear();
               out.put(Arrays.copyOfRange(this.visited, i, PZMath.min(i + out.capacity(), this.visited.length)));
               bos.write(out.array(), 0, out.position());
            }
         }
      }
   }

   public void saveToBufferMap(SaveBufferMap bufferMap) {
      if (!Core.getInstance().isNoSave() && !this.client) {
         String fileName = ZomboidFileSystem.instance.getFileNameInCurrentSave("map_visited.bin");
         File file = new File(fileName);
         if (!file.exists()) {
            synchronized (SliceY.SliceBufferLock) {
               ByteBuffer bb = SliceY.SliceBuffer;
               bb.clear();
               this.saveHeader(bb);
               ByteBufferPooledObject buffer = bufferMap.allocate(bb.position() + this.visited.length);
               buffer.put(bb.array(), 0, bb.position());
               buffer.put(this.visited, 0, this.visited.length);
               bufferMap.put(fileName, buffer, null);
            }
         } else if (this.saveChanged) {
            synchronized (SliceY.SliceBufferLock) {
               ByteBuffer bb = SliceY.SliceBuffer;
               bb.clear();
               bb.putInt(this.saveChangeX1);
               bb.putInt(this.saveChangeY1);
               bb.putInt(this.saveChangeX2);
               bb.putInt(this.saveChangeY2);
               int byteOffset1 = this.saveChangeX1 / 4;
               int byteOffset2 = this.saveChangeX2 / 4;
               int span = byteOffset2 - byteOffset1 + 1;
               int numBytes = span * (this.saveChangeY2 - this.saveChangeY1 + 1);
               ByteBufferPooledObject buffer = bufferMap.allocate(bb.position() + numBytes);
               buffer.put(bb.array(), 0, bb.position());

               for (int y = this.saveChangeY1; y <= this.saveChangeY2; y++) {
                  buffer.put(this.visited, byteOffset1 + y * this.span, span);
               }

               bufferMap.put(fileName, buffer, WorldMapVisited::writeBufferMap);
            }

            this.saveChanged = false;
            this.saveChangeX1 = Integer.MAX_VALUE;
            this.saveChangeY1 = Integer.MAX_VALUE;
            this.saveChangeX2 = Integer.MIN_VALUE;
            this.saveChangeY2 = Integer.MIN_VALUE;
         }
      }
   }

   private static void writeBufferMap(String fileName, ByteBufferPooledObject buffer) throws IOException {
      ByteBuffer bb = buffer.slice();
      int x1 = bb.getInt();
      int y1 = bb.getInt();
      int x2 = bb.getInt();
      int y2 = bb.getInt();
      int byteOffset1 = x1 / 4;
      int byteOffset2 = x2 / 4;
      int span = byteOffset2 - byteOffset1 + 1;
      long positionInFile = 28 + byteOffset1 + (long)y1 * instance.span;
      File outFile = new File(fileName);

      try (
         RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
         FileChannel channel = raf.getChannel();
      ) {
         for (int y = y1; y <= y2; y++) {
            bb.limit(bb.position() + span);
            int written = channel.write(bb, positionInFile);
            if (written != span) {
               boolean var17 = true;
            }

            positionInFile += instance.span;
         }
      }
   }

   public void load() throws IOException {
      File file = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("map_visited.bin"));

      try (
         FileInputStream fis = new FileInputStream(file);
         BufferedInputStream bis = new BufferedInputStream(fis);
      ) {
         ByteBuffer in = SliceY.SliceBuffer;
         in.clear();
         int intsSize = 24;
         bis.read(in.array(), 0, 24);
         int worldVersion = in.getInt();
         int version = 1;
         if (worldVersion >= 234) {
            version = in.getInt();
            if (version < 1 || version > 2) {
               throw new IOException("unknown file version " + version);
            }

            bis.read(in.array(), 24, 4);
         }

         int minX = in.getInt();
         int minY = in.getInt();
         int maxX = in.getInt();
         int maxY = in.getInt();
         int upc = in.getInt();
         if (version < 2 || minX != this.minX || minY != this.minY || maxX != this.maxX || maxY != this.maxY || upc != 8) {
            byte[] visitedRow = new byte[(maxX - minX + 1) * upc];
            int spu = 256 / upc;

            for (int y = minY * upc; y <= maxY * upc; y++) {
               in.clear();
               int numBytes = bis.read(in.array(), 0, visitedRow.length);
               in.get(visitedRow);
               in.limit(numBytes);

               for (int x = minX * upc; x <= maxX * upc; x++) {
                  int xx = x - minX * upc;
                  this.setFlags(x * spu, y * spu, x * spu + spu - 1, y * spu + spu - 1, visitedRow[xx]);
               }
            }

            return;
         }

         for (int i = 0; i < this.visited.length; i += in.capacity()) {
            in.clear();
            int sizeRead = bis.read(in.array(), 0, in.capacity());
            System.arraycopy(in.array(), 0, this.visited, i, sizeRead);
         }
      } catch (FileNotFoundException var23) {
      }
   }

   public void processDataChunk(int pos, byte[] chunk) {
      System.arraycopy(chunk, 0, this.visited, pos, chunk.length);
   }

   public void receiveRequestData(ByteBufferReader bb) throws DataFormatException {
      int length = bb.getInt();
      int compressedLength = bb.getInt();
      if (length > 0 && compressedLength > 0) {
         byte[] compressed = new byte[compressedLength];
         bb.get(compressed, 0, compressedLength);
         Inflater inflater = new Inflater();

         try {
            inflater.setInput(compressed);
            int max = PZMath.min(length, this.visited.length);
            int position = 0;

            while (!inflater.finished() && position < max) {
               int count = inflater.inflate(this.visited, position, max - position);
               if (count == 0) {
                  break;
               }

               position += count;
            }
         } finally {
            inflater.end();
         }

         if (SandboxOptions.getInstance().map.mapAllKnown.getValue()) {
            this.setKnownInCells(this.minX, this.minY, this.maxX, this.maxY);
         }
      }
   }

   private int getFlags(int x, int y) {
      return getFlags(x, y, this.visited);
   }

   private static int getFlags(int x, int y, byte[] visited) {
      int span = getSpan();
      int b = visited[x / 4 + y * span];
      return b >> x % 4 * 2 & 3;
   }

   private void setFlags(int x, int y, int flags) {
      setFlags(x, y, flags, this.visited);
   }

   private static void setFlags(int x, int y, int flags, byte[] visited) {
      int b = visited[x / 4 + y * getSpan()];
      int mask = 3 << x % 4 * 2;
      int shift = x % 4 * 2;
      b = b & ~mask | flags << shift;
      visited[x / 4 + y * getSpan()] = (byte)b;
   }

   private void setFlags(int xMin, int yMin, int xMax, int yMax, int flags) {
      boolean changed = setFlags(xMin, yMin, xMax, yMax, flags, this.visited);
      if (changed) {
         this.changed = true;
         int xMinUnit = coordToUnit(normalizeX(xMin), false);
         int xMaxUnit = coordToUnit(normalizeX(xMax), true);
         int yMinUnit = coordToUnit(normalizeY(yMin), false);
         int yMaxUnit = coordToUnit(normalizeY(yMax), true);
         this.changeX1 = PZMath.min(this.changeX1, xMinUnit);
         this.changeY1 = PZMath.min(this.changeY1, yMinUnit);
         this.changeX2 = PZMath.max(this.changeX2, xMaxUnit);
         this.changeY2 = PZMath.max(this.changeY2, yMaxUnit);
         this.saveChanged = true;
         this.saveChangeX1 = PZMath.min(this.saveChangeX1, xMinUnit);
         this.saveChangeY1 = PZMath.min(this.saveChangeY1, yMinUnit);
         this.saveChangeX2 = PZMath.max(this.saveChangeX2, xMaxUnit);
         this.saveChangeY2 = PZMath.max(this.saveChangeY2, yMaxUnit);
      }
   }

   private static int normalizeX(int x) {
      IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
      x -= metaGrid.minX * 256;
      return PZMath.clamp(x, 0, getWidthInCells() * 256 - 1);
   }

   private static int normalizeY(int y) {
      IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
      y -= metaGrid.minY * 256;
      return PZMath.clamp(y, 0, getHeightInCells() * 256 - 1);
   }

   private static int coordToUnit(int coord, boolean isMax) {
      coord /= 32;
      if (isMax && coord % 32 == 0) {
         coord--;
      }

      return coord;
   }

   private static boolean setFlags(int xMin, int yMin, int xMax, int yMax, int flags, byte[] visited) {
      xMin = normalizeX(xMin);
      yMin = normalizeY(yMin);
      xMax = normalizeX(xMax);
      yMax = normalizeY(yMax);
      if (xMin != xMax && yMin != yMax) {
         int xMinUnit = coordToUnit(xMin, false);
         int xMaxUnit = coordToUnit(xMax, true);
         int yMinUnit = coordToUnit(yMin, false);
         int yMaxUnit = coordToUnit(yMax, true);
         boolean changed = false;

         for (int y = yMinUnit; y <= yMaxUnit; y++) {
            for (int x = xMinUnit; x <= xMaxUnit; x++) {
               int it = getFlags(x, y, visited);
               if ((it & flags) != flags) {
                  setFlags(x, y, it | flags, visited);
                  changed = true;
               }
            }
         }

         return changed;
      } else {
         return false;
      }
   }

   private void clearFlags(int xMin, int yMin, int xMax, int yMax, int flags) {
      xMin = normalizeX(xMin);
      yMin = normalizeY(yMin);
      xMax = normalizeX(xMax);
      yMax = normalizeY(yMax);
      if (xMin != xMax && yMin != yMax) {
         int xMinUnit = coordToUnit(xMin, false);
         int xMaxUnit = coordToUnit(xMax, true);
         int yMinUnit = coordToUnit(yMin, false);
         int yMaxUnit = coordToUnit(yMax, true);
         boolean changed = false;

         for (int y = yMinUnit; y <= yMaxUnit; y++) {
            for (int x = xMinUnit; x <= xMaxUnit; x++) {
               int it = getFlags(x, y, this.visited);
               if ((it & flags) != 0) {
                  setFlags(x, y, it & ~flags, this.visited);
                  changed = true;
               }
            }
         }

         if (changed) {
            this.changed = true;
            this.changeX1 = PZMath.min(this.changeX1, xMinUnit);
            this.changeY1 = PZMath.min(this.changeY1, yMinUnit);
            this.changeX2 = PZMath.max(this.changeX2, xMaxUnit);
            this.changeY2 = PZMath.max(this.changeY2, yMaxUnit);
            this.saveChanged = true;
            this.saveChangeX1 = PZMath.min(this.saveChangeX1, xMinUnit);
            this.saveChangeY1 = PZMath.min(this.saveChangeY1, yMinUnit);
            this.saveChangeX2 = PZMath.max(this.saveChangeX2, xMaxUnit);
            this.saveChangeY2 = PZMath.max(this.saveChangeY2, yMaxUnit);
         }
      }
   }

   private boolean updateTextureData(ByteBuffer textureData, int span) {
      if (!this.changed) {
         return false;
      }

      this.changed = false;
      int bpp = 4;

      if (pzopt.Config.MAP_VISITED_FAST && pzopt.Overrides.enabled() && this.changeX2 >= this.changeX1) { // pzopt: mapVisitedFast, the same bytes a row at a time
         int pzoptSpan = getSpan(); // pzopt: mapVisitedFast, the flag rows' span once instead of per texel
         byte[] pzoptVisited = this.visited; // pzopt: mapVisitedFast
         int pzoptW = this.changeX2 - this.changeX1 + 1; // pzopt: mapVisitedFast
         if (this.pzoptRow == null || this.pzoptRow.length < pzoptW * 4) { // pzopt: mapVisitedFast
            this.pzoptRow = new byte[pzoptW * 4]; // pzopt: mapVisitedFast
         } // pzopt: mapVisitedFast
         byte[] pzoptRowBytes = this.pzoptRow; // pzopt: mapVisitedFast
         for (int y = this.changeY1; y <= this.changeY2; y++) { // pzopt: mapVisitedFast
            int pzoptBase = y * pzoptSpan; // pzopt: mapVisitedFast
            int k = 0; // pzopt: mapVisitedFast
            for (int x = this.changeX1; x <= this.changeX2; x++) { // pzopt: mapVisitedFast
               int it = pzoptVisited[x / 4 + pzoptBase] >> x % 4 * 2 & 3; // pzopt: mapVisitedFast, getFlags(x, y)
               pzoptRowBytes[k++] = (byte)((it & 2) != 0 ? 0 : -1); // pzopt: mapVisitedFast
               pzoptRowBytes[k++] = (byte)((it & 1) != 0 ? 0 : -1); // pzopt: mapVisitedFast
               pzoptRowBytes[k++] = -1; // pzopt: mapVisitedFast
               pzoptRowBytes[k++] = -1; // pzopt: mapVisitedFast
            } // pzopt: mapVisitedFast
            textureData.position((1 + this.changeX1) * 4 + (1 + y) * span * 4); // pzopt: mapVisitedFast
            textureData.put(pzoptRowBytes, 0, k); // pzopt: mapVisitedFast
         } // pzopt: mapVisitedFast
         if (pzopt.Config.DEV_MAP_VISITED_CHECK) { // pzopt: devMapVisitedCheck, the stock loop over the same region must give the same bytes
            int pzoptDiff = 0; // pzopt: devMapVisitedCheck
            for (int y = this.changeY1; y <= this.changeY2; y++) { // pzopt: devMapVisitedCheck
               int pzoptPos = (1 + this.changeX1) * 4 + (1 + y) * span * 4; // pzopt: devMapVisitedCheck
               for (int x = this.changeX1; x <= this.changeX2; x++) { // pzopt: devMapVisitedCheck
                  int it = this.getFlags(x, y); // pzopt: devMapVisitedCheck
                  if (textureData.get(pzoptPos) != (byte)((it & 2) != 0 ? 0 : -1) || textureData.get(pzoptPos + 1) != (byte)((it & 1) != 0 ? 0 : -1) // pzopt: devMapVisitedCheck
                     || textureData.get(pzoptPos + 2) != -1 || textureData.get(pzoptPos + 3) != -1) { // pzopt: devMapVisitedCheck
                     pzoptDiff++; // pzopt: devMapVisitedCheck
                  } // pzopt: devMapVisitedCheck
                  pzoptPos += 4; // pzopt: devMapVisitedCheck
               } // pzopt: devMapVisitedCheck
            } // pzopt: devMapVisitedCheck
            pzopt.Log.info("mapVisitedFast check: " + pzoptW + " x " + (this.changeY2 - this.changeY1 + 1) + " texels, " + pzoptDiff + " differ"); // pzopt: devMapVisitedCheck
         } // pzopt: devMapVisitedCheck
      } else { // pzopt: mapVisitedFast
      for (int y = this.changeY1; y <= this.changeY2; y++) {
         textureData.position((1 + this.changeX1) * 4 + (1 + y) * span * 4);

         for (int x = this.changeX1; x <= this.changeX2; x++) {
            int it = this.getFlags(x, y);
            textureData.put((byte)((it & 2) != 0 ? 0 : -1));
            textureData.put((byte)((it & 1) != 0 ? 0 : -1));
            textureData.put((byte)-1);
            textureData.put((byte)-1);
         }
      }
      } // pzopt: mapVisitedFast

      textureData.position(0);
      this.changeX1 = Integer.MAX_VALUE;
      this.changeY1 = Integer.MAX_VALUE;
      this.changeX2 = Integer.MIN_VALUE;
      this.changeY2 = Integer.MIN_VALUE;
      return true;
   }

   void setUnvisitedRGBA(float r, float g, float b, float a) {
      this.color.init(r, g, b, a);
   }

   void setUnvisitedGridRGBA(float r, float g, float b, float a) {
      this.gridColor.init(r, g, b, a);
   }

   boolean hasFlags(int xMin, int yMin, int xMax, int yMax, int flags, boolean bAnyOK) {
      xMin -= this.minX * 256;
      yMin -= this.minY * 256;
      xMax -= this.minX * 256;
      yMax -= this.minY * 256;
      int widthInCells = getWidthInCells();
      int heightInCells = getHeightInCells();
      xMin = PZMath.clamp(xMin, 0, widthInCells * 256 - 1);
      yMin = PZMath.clamp(yMin, 0, heightInCells * 256 - 1);
      xMax = PZMath.clamp(xMax, 0, widthInCells * 256 - 1);
      yMax = PZMath.clamp(yMax, 0, heightInCells * 256 - 1);
      if (xMin != xMax && yMin != yMax) {
         int xMinUnit = xMin / 32;
         int xMaxUnit = xMax / 32;
         int yMinUnit = yMin / 32;
         int yMaxUnit = yMax / 32;
         if (xMax % 32 == 0) {
            xMaxUnit--;
         }

         if (yMax % 32 == 0) {
            yMaxUnit--;
         }

         int span = widthInCells * 8;

         for (int y = yMinUnit; y <= yMaxUnit; y++) {
            for (int x = xMinUnit; x <= xMaxUnit; x++) {
               int it = this.getFlags(x, y);
               if (bAnyOK) {
                  if ((it & flags) != 0) {
                     return true;
                  }
               } else if ((it & flags) != flags) {
                  return false;
               }
            }
         }

         return !bAnyOK;
      } else {
         return false;
      }
   }

   boolean isCellVisible(int cellX, int cellY) {
      boolean blur = true;
      int delta = 1;
      return this.hasFlags(cellX * 256 - 1, cellY * 256 - 1, (cellX + 1) * 256 + 1, (cellY + 1) * 256 + 1, 3, true);
   }

   public boolean isKnown(int x, int y) {
      int delta = 1;
      return this.hasFlags(x - 1, y - 1, x + 1, y + 1, 2, true);
   }

   public boolean isKnown(int x1, int y1, int x2, int y2) {
      int delta = 1;
      return this.hasFlags(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 2, true);
   }

   public boolean isVisited(int x, int y) {
      int delta = 1;
      return this.hasFlags(x - 1, y - 1, x + 1, y + 1, 1, true);
   }

   public boolean isVisited(int x, int y, int x2, int y2) {
      int delta = 1;
      return this.hasFlags(x - 1, y - 1, x2 + 1, y2 + 1, 1, true);
   }

   public static WorldMapVisited getInstance() {
      IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
      if (metaGrid == null) {
         throw new NullPointerException("IsoWorld.instance.MetaGrid is null");
      }

      if (instance == null) {
         instance = new WorldMapVisited();
         instance.client = GameClient.client;
         instance.setBounds(metaGrid.minX, metaGrid.minY, metaGrid.maxX, metaGrid.maxY);
         if (instance.mainMenu || GameClient.client) {
            return instance;
         }

         try {
            instance.load();
            if (SandboxOptions.getInstance().map.mapAllKnown.getValue()) {
               instance.setKnownInCells(metaGrid.minX, metaGrid.minY, metaGrid.maxX, metaGrid.maxY);
            }

            instance.save();
         } catch (Throwable t) {
            ExceptionLogger.logException(t);
         }
      }

      return instance;
   }

   public static void update() {
      if (IsoWorld.instance != null) {
         WorldMapVisited visited = getInstance();
         if (visited != null) {
            for (int i = 0; i < IsoPlayer.numPlayers; i++) {
               IsoPlayer player = IsoPlayer.players[i];
               if (player != null && !player.isDead()) {
                  int updateMinX = ((int)player.getX() - 25) / 32;
                  int updateMinY = ((int)player.getY() - 25) / 32;
                  int updateMaxX = ((int)player.getX() + 25) / 32;
                  int updateMaxY = ((int)player.getY() + 25) / 32;
                  if (((int)player.getX() + 25) % 32 == 0) {
                     updateMaxX--;
                  }

                  if (((int)player.getY() + 25) % 32 == 0) {
                     updateMaxY--;
                  }

                  if (updateMinX != visited.updateMinX[i]
                     || updateMinY != visited.updateMinY[i]
                     || updateMaxX != visited.updateMaxX[i]
                     || updateMaxY != visited.updateMaxY[i]) {
                     visited.updateMinX[i] = updateMinX;
                     visited.updateMinY[i] = updateMinY;
                     visited.updateMaxX[i] = updateMaxX;
                     visited.updateMaxY[i] = updateMaxY;
                     visited.setFlags((int)player.getX() - 25, (int)player.getY() - 25, (int)player.getX() + 25, (int)player.getY() + 25, 3);
                  }
               }
            }
         }
      }
   }

   public static void updatePlayer(IsoPlayer player, byte[] visited) {
      setFlags((int)player.getX() - 25, (int)player.getY() - 25, (int)player.getX() + 25, (int)player.getY() + 25, 3, visited);
   }

   public void forget() {
      this.clearKnownInCells(this.minX, this.minY, this.maxX, this.maxY);
      this.clearVisitedInCells(this.minX, this.minY, this.maxX, this.maxY);
      Arrays.fill(this.updateMinX, -1);
      Arrays.fill(this.updateMinY, -1);
      Arrays.fill(this.updateMaxX, -1);
      Arrays.fill(this.updateMaxY, -1);
   }

   public static void SaveAll() {
      WorldMapVisited visited = instance;
      if (visited != null) {
         try {
            visited.save();
         } catch (Exception ex) {
            ExceptionLogger.logException(ex);
         }
      }
   }

   public static void Reset() {
      WorldMapVisited visited = instance;
      if (visited != null) {
         visited.destroy();
         instance = null;
      }
   }
}
