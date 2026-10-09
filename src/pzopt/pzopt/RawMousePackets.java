package pzopt;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/** Decodes Windows x64 RAWINPUT mouse blocks returned by GetRawInputBuffer. */
public final class RawMousePackets {
   private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN).withByteAlignment(1);
   private static final ValueLayout.OfLong J = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).withByteAlignment(1);
   private static final ValueLayout.OfShort S = ValueLayout.JAVA_SHORT.withOrder(ByteOrder.LITTLE_ENDIAN).withByteAlignment(1);

   private static final int HEADER_SIZE = 24;
   private static final int MOUSE_SIZE = 24;
   private static final int MOUSE_TYPE = 0;

   private RawMousePackets() { }

   public interface Sink {
      void mouse(int flags, int buttons, int buttonData, int x, int y, long device);
   }

   /**
    * Dispatches exactly {@code count} RAWINPUT records in {@code buffer[0, byteCount)}.
    *
    * @throws IllegalArgumentException if the batch is truncated or contains an invalid record
    */
   public static void dispatch(MemorySegment buffer, int byteCount, int count, Sink sink) {
      if (byteCount < 0 || count < 0 || byteCount > buffer.byteSize()) {
         throw new IllegalArgumentException("Invalid raw input batch bounds");
      }

      long offset = 0;
      long end = byteCount;
      for (int record = 0; record < count; record++) {
         if (end - offset < HEADER_SIZE) {
            throw new IllegalArgumentException("Truncated RAWINPUT header");
         }

         int type = buffer.get(I, offset);
         int size = buffer.get(I, offset + 4);
         if (size < HEADER_SIZE || size > end - offset) {
            throw new IllegalArgumentException("Invalid RAWINPUT size");
         }

         if (type == MOUSE_TYPE) {
            if (size < HEADER_SIZE + MOUSE_SIZE) {
               throw new IllegalArgumentException("Truncated RAWMOUSE payload");
            }
            long payload = offset + HEADER_SIZE;
            int flags = buffer.get(S, payload) & 0xffff;
            int buttons = buffer.get(S, payload + 4) & 0xffff;
            int buttonData = buffer.get(S, payload + 6);
            int x = buffer.get(I, payload + 12);
            int y = buffer.get(I, payload + 16);
            long device = buffer.get(J, offset + 8);
            sink.mouse(flags, buttons, buttonData, x, y, device);
         }

         if (record + 1 < count) {
            long next = ((long)size + 7) & ~7L;
            if (next > end - offset) {
               throw new IllegalArgumentException("Truncated RAWINPUT alignment padding");
            }
            offset += next;
         }
      }
   }
}
