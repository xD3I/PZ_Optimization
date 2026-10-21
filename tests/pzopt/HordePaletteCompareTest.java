package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/** hordePaletteMismatch (tail6 2026-10-14): the MemorySegment compare gives the long-by-long compare's answer (raw bits: -0.0 vs 0.0 and NaN payloads differ). */
public final class HordePaletteCompareTest {
   private static FloatBuffer direct(int n) {
      return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
   }

   public static void main(String[] args) {
      Random r = new Random(42);
      FloatBuffer a = direct(4096);
      FloatBuffer b = direct(4096);
      int cases = 0, same = 0;
      for (int it = 0; it < 20000; it++) {
         int len = 1 + r.nextInt(1000);
         int ia = r.nextInt(4096 - len);
         int ib = r.nextInt(4096 - len);
         for (int t = 0; t < len; t++) {
            float v = r.nextInt(4) == 0 ? 0.0F : r.nextFloat() * 100.0F - 50.0F;
            a.put(ia + t, v);
            b.put(ib + t, v);
         }
         a.position(r.nextInt(64)); // a palette buffer's position must not matter
         int kind = r.nextInt(6);
         int at = r.nextInt(len);
         switch (kind) {
            case 0 -> { } // equal
            case 1 -> b.put(ib + at, Math.nextUp(b.get(ib + at)));
            case 2 -> { a.put(ia + at, 0.0F); b.put(ib + at, -0.0F); }
            case 3 -> { a.put(ia + at, Float.intBitsToFloat(0x7fc00001)); b.put(ib + at, Float.intBitsToFloat(0x7fc00002)); }
            case 4 -> b.put(ib + len - 1, b.get(ib + len - 1) + 1.0F);
            default -> { a.put(ia + at, Float.NaN); b.put(ib + at, Float.NaN); }
         }
         boolean expect = HordeBatch.sameBitsLongs(a, ia, b, ib, len);
         boolean got = HordeBatch.sameBitsSegment(a, ia, b, ib, len);
         Check.check(expect == got, "palette compare differs at case " + it + " kind " + kind + " len " + len);
         cases++;
         if (got) {
            same++;
         }
      }
      System.out.println("HordePaletteCompareTest: " + cases + " compares identical (" + same + " equal ranges)");
   }
}
