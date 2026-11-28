package pzopt;

import gnu.trove.list.array.TIntArrayList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjglx.BufferUtils;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;

/**
 * Weather particle tiles (Config key {@code rainTiles}).
 *
 * <p>Stock {@code ParticleRectangle.render} walks the whole particle array once per screen cell
 * (a 512x512 cell of 1024 rain particles is laid 13x7 times over a 5120x2160 offscreen) and hands
 * every copy to the drawer as its own quad; the render thread then packs each of those ~90k quads
 * into VBORenderer one vertex at a time. Every cell is the same picture shifted by the cell origin.
 *
 * <p>With tiles the game thread renders the particles once at the origin (the template) and adds
 * the list of cell origins; on the render thread the template is packed once into a
 * position/colour/uv vertex buffer, uploaded once, and drawn once per origin with the
 * ModelViewProjection uniform translated by the origin. Same shader as VBORenderer
 * ({@code vboRenderer_PositionColorUV}), same texture and blend state, same vertex bytes; the
 * per-particle on-screen cull of the stock loop is replaced by GPU clipping.
 */
public final class RainTiles {
   /** One tiled group: per texture index the particle range [start, end) in the drawer's lists, and the origins. */
   public static final class Tile {
      final int[] start = new int[8];
      final int[] end = new int[8];
      float[] origins = new float[64];
      int numOrigins;

      public void addOrigin(float x, float y) {
         if (this.numOrigins * 2 + 2 > this.origins.length) {
            this.origins = java.util.Arrays.copyOf(this.origins, this.origins.length * 2);
         }
         this.origins[this.numOrigins * 2] = x;
         this.origins[this.numOrigins * 2 + 1] = y;
         this.numOrigins++;
      }

      /** Is list index {@code j} of texture {@code textureIndex} inside this group? */
      public boolean covers(int textureIndex, int j) {
         return j >= this.start[textureIndex] && j < this.end[textureIndex];
      }

      public int rangeSize(int textureIndex) {
         return this.end[textureIndex] - this.start[textureIndex];
      }
   }

   private static final int STRIDE = 36; // vec3 position, vec4 colour, vec2 uv, like VBORenderer.formatPositionColorUv
   private static final Matrix4f base = new Matrix4f();
   private static final Matrix4f mvp = new Matrix4f();
   private static long tilesDrawn;
   private static long quadsUploaded;
   private static long drawCalls;

   public static boolean enabled() {
      return Overrides.enabled() && Config.RAIN_TILES;
   }

   public static Tile begin(TIntArrayList[] positions) {
      Tile t = new Tile();
      for (int i = 0; i < 8; i++) {
         t.start[i] = t.end[i] = positions[i].size();
      }
      return t;
   }

   public static void end(Tile t, TIntArrayList[] positions) {
      for (int i = 0; i < 8; i++) {
         t.end[i] = positions[i].size();
      }
   }

   /** Per-drawer render-thread state: the vertex staging buffer and the GL buffer object. */
   public static final class Gl {
      private ByteBuffer staging;
      private int vbo;

      public void draw(ArrayList<Tile> tiles, ByteBuffer particles, ArrayList<Texture> textures, TIntArrayList[] positions) {
         if (tiles.isEmpty()) {
            return;
         }
         VBORenderer vbor = VBORenderer.getInstance();
         ShaderProgram program = vbor.pzoptShaderPositionColorUv().getProgram();
         if (program == null || !program.isCompiled()) {
            return;
         }
         if (this.vbo == 0) {
            this.vbo = GL15.glGenBuffers();
         }
         program.Start();
         GL13.glActiveTexture(GL13.GL_TEXTURE0); // match VBORenderer.flush before binding the particle texture
         VertexBufferObject.setModelViewProjection(program); // also caches the stack matrices on the program
         base.set(program.projection).mul(program.modelView);
         program.setValue("userDepth", 0.0F);
         GL11.glDisable(2929); // depth test off, as VBORenderer's default run
         GL11.glEnable(3553);
         GL15.glBindBuffer(34962, this.vbo);
         GL20.glEnableVertexAttribArray(0);
         GL20.glEnableVertexAttribArray(1);
         GL20.glEnableVertexAttribArray(2);
         GL20.glDisableVertexAttribArray(3);
         GL20.glDisableVertexAttribArray(4);
         GL20.glVertexAttribPointer(0, 3, 5126, false, STRIDE, 0L);
         GL20.glVertexAttribPointer(1, 4, 5126, true, STRIDE, 12L);
         GL20.glVertexAttribPointer(2, 2, 5126, false, STRIDE, 28L);

         for (int ti = 0; ti < tiles.size(); ti++) {
            Tile t = tiles.get(ti);
            if (t.numOrigins == 0) {
               continue;
            }
            for (int i = 0; i < 8 && i < textures.size(); i++) {
               int n = t.end[i] - t.start[i];
               if (n <= 0) {
                  continue;
               }
               Texture texture = textures.get(i);
               int bytes = n * 4 * STRIDE;
               if (this.staging == null || this.staging.capacity() < bytes) {
                  this.staging = BufferUtils.createByteBuffer(Math.max(bytes, 64 * 1024));
               }
               ByteBuffer v = this.staging;
               v.clear();
               float u0 = texture.getXStart();
               float v0 = texture.getYStart();
               float u1 = texture.getXEnd();
               float v1 = texture.getYEnd();
               TIntArrayList list = positions[i];
               for (int j = t.start[i]; j < t.end[i]; j++) {
                  int p = list.get(j);
                  float r = particles.getFloat(p + 32);
                  float g = particles.getFloat(p + 36);
                  float b = particles.getFloat(p + 40);
                  float a = particles.getFloat(p + 44);
                  vertex(v, particles.getFloat(p), particles.getFloat(p + 4), r, g, b, a, u0, v0);
                  vertex(v, particles.getFloat(p + 8), particles.getFloat(p + 12), r, g, b, a, u1, v0);
                  vertex(v, particles.getFloat(p + 16), particles.getFloat(p + 20), r, g, b, a, u1, v1);
                  vertex(v, particles.getFloat(p + 24), particles.getFloat(p + 28), r, g, b, a, u0, v1);
               }
               v.flip();
               GL15.glBufferData(34962, v, 35040); // GL_STREAM_DRAW
               texture.getTextureId().bind();
               quadsUploaded += n;
               for (int o = 0; o < t.numOrigins; o++) {
                  mvp.set(base).translate(t.origins[o * 2], t.origins[o * 2 + 1], 0.0F);
                  program.setValue("ModelViewProjection", mvp);
                  CoreGl.drawArraysQuads(0, n * 4); // GL_QUADS, the mode VBORenderer used for these (triangles under macGlCore)
                  drawCalls++;
               }
            }
            tilesDrawn++;
         }

         program.setValue("ModelViewProjection", base); // back to what the program's cached matrices say
         GL15.glBindBuffer(34962, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         GL11.glEnable(2929);
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      private static void vertex(ByteBuffer v, float x, float y, float r, float g, float b, float a, float u, float t) {
         v.putFloat(x).putFloat(y).putFloat(0.0F).putFloat(r).putFloat(g).putFloat(b).putFloat(a).putFloat(u).putFloat(t);
      }
   }

   public static String stats() {
      return "rain tiles: tiles=" + tilesDrawn + " template quads=" + quadsUploaded + " draws=" + drawCalls;
   }
}
