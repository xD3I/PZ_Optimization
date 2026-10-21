package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLCapabilities;

/**
 * pacingQueryBuffer (tail2 pass 2026-10-13): GL query results without a driver round trip. The Pacing timestamps and the
 * overlay's GPU timer polled their queries every frame with glGetQueryObject(GL_QUERY_RESULT_AVAILABLE); with NVIDIA's
 * threaded driver each such call waits until the driver thread has worked through everything queued before it (in the
 * frames that create many textures that was up to 9 ms of render-thread stall, Louisville run t2-cc-on). Here the result is
 * written by the GL into a persistently mapped coherent buffer bound to GL_QUERY_BUFFER (ARB_query_buffer_object, GL 4.4)
 * when it is available, and read from client memory: a slot holding -1 is not there yet. Render thread only.
 */
public final class QueryBuf {
   private final int buffer;
   private final ByteBuffer map;

   private QueryBuf(int buffer, ByteBuffer map) {
      this.buffer = buffer;
      this.map = map;
   }

   /** Render thread, with a current context: a buffer of {@code slots} results, or null (key off, no GL 4.4 path, failure). */
   public static QueryBuf create(int slots, String who) {
      if (!Config.PACING_QUERY_BUFFER || !Overrides.enabled()) {
         return null;
      }
      try {
         GLCapabilities caps = GL.getCapabilities();
         if (!(caps.OpenGL44 || caps.GL_ARB_query_buffer_object && caps.GL_ARB_buffer_storage)) {
            return null;
         }
         int b = GL15.glGenBuffers();
         GL15.glBindBuffer(GL44.GL_QUERY_BUFFER, b);
         int flags = GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
         GL44.glBufferStorage(GL44.GL_QUERY_BUFFER, (long)slots * 8L, flags);
         ByteBuffer m = GL30.glMapBufferRange(GL44.GL_QUERY_BUFFER, 0L, (long)slots * 8L, flags);
         GL15.glBindBuffer(GL44.GL_QUERY_BUFFER, 0);
         if (m == null) {
            GL15.glDeleteBuffers(b);
            return null;
         }
         m.order(ByteOrder.nativeOrder());
         for (int i = 0; i < slots; i++) {
            m.putLong(i * 8, -1L);
         }
         Log.info(who + ": GL query results through a mapped query buffer (pacingQueryBuffer)");
         return new QueryBuf(b, m);
      } catch (Throwable t) {
         Log.warn(who + ": no query buffer, polling the queries: " + t);
         return null;
      }
   }

   /** After the query ended (glQueryCounter / glEndQuery): its result goes into {@code slot} when the GPU has it. */
   public void request(int slot, int queryId) {
      this.map.putLong(slot * 8, -1L);
      GL15.glBindBuffer(GL44.GL_QUERY_BUFFER, this.buffer);
      GL33.nglGetQueryObjectui64v(queryId, GL15.GL_QUERY_RESULT, (long)slot * 8L);
      GL15.glBindBuffer(GL44.GL_QUERY_BUFFER, 0);
   }

   /** The slot's result, or -1 while the GPU has not written it. */
   public long get(int slot) {
      return this.map.getLong(slot * 8);
   }
}
