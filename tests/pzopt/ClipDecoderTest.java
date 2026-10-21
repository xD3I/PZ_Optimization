package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AVIF clips through javif (pzopt.ClipDecoder): frames, frame times, thinning, the HDR / SDR outputs of a PQ file, an
 * SDR file passed through, a still image, and the size limit. Fixtures in tests/pzopt/fixtures (avifenc, 64x32):
 * clip-hdr-pq.avif = six grey frames at 50 / 200 / 1,000 nits twice (PQ / BT.2020, 10-bit 4:4:4); clip-sdr.avif = red,
 * green, blue, grey 128, white, (20, 40, 60) (sRGB, 8-bit 4:4:4); still-sdr.avif = the red frame as a still image;
 * clip-too-wide.avif = 1040x16.
 */
public class ClipDecoderTest {
   static final class Collect implements ClipDecoder.Sink {
      int width, height;
      int[] delays;
      boolean rgb9e5;
      String describe;
      final List<int[]> frames = new ArrayList<>();

      @Override
      public void begin(int width, int height, int[] delaysMs, boolean rgb9e5, String describe) {
         this.width = width;
         this.height = height;
         this.delays = delaysMs;
         this.rgb9e5 = rgb9e5;
         this.describe = describe;
      }

      @Override
      public void frame(int index, int[] pixels) {
         Check.check(index == frames.size(), "frames in order: " + index);
         frames.add(pixels);
      }

      int total() {
         int t = 0;
         for (int d : delays) {
            t += d;
         }
         return t;
      }
   }

   static Path fixtures() {
      for (Path p : new Path[]{Path.of("tests/pzopt/fixtures"), Path.of("../tests/pzopt/fixtures"), Path.of("../../tests/pzopt/fixtures")}) {
         if (Files.isDirectory(p)) {
            return p;
         }
      }
      try {
         Path here = Path.of(ClipDecoderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
         return here.getParent().getParent().resolve("tests/pzopt/fixtures"); // build/tests -> repo
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   static Collect decode(String name, boolean hdrOut, int maxFrames) throws Exception {
      Collect c = new Collect();
      ClipDecoder.decode(Files.readAllBytes(fixtures().resolve(name)), hdrOut, maxFrames, c);
      return c;
   }

   static int r(int p) {
      return p & 0xFF;
   }

   static int g(int p) {
      return p >>> 8 & 0xFF;
   }

   static int b(int p) {
      return p >>> 16 & 0xFF;
   }

   static boolean near(int p, int rr, int gg, int bb, int tol) {
      return Math.abs(r(p) - rr) <= tol && Math.abs(g(p) - gg) <= tol && Math.abs(b(p) - bb) <= tol && (p >>> 24) == 255;
   }

   public static void main(String[] args) throws Exception {
      // SDR clip: colours through, 24 fps frame times on the running total
      Collect s = decode("clip-sdr.avif", false, 96);
      Check.check(s.width == 64 && s.height == 32 && s.frames.size() == 6 && !s.rgb9e5, "sdr clip: " + s.describe);
      Check.check(s.total() == 250 && s.delays[0] == 42 && s.delays[1] == 41, "6 frames at 24 fps = 250 ms: " + java.util.Arrays.toString(s.delays));
      int[][] want = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {128, 128, 128}, {255, 255, 255}, {20, 40, 60}};
      for (int i = 0; i < 6; i++) {
         int p = s.frames.get(i)[16 * 64 + 32];
         Check.check(near(p, want[i][0], want[i][1], want[i][2], 3), "sdr frame " + i + ": " + r(p) + "," + g(p) + "," + b(p));
      }
      // hdrOut does not change an SDR file
      Collect s2 = decode("clip-sdr.avif", true, 96);
      Check.check(!s2.rgb9e5 && s2.frames.get(0)[0] == s.frames.get(0)[0], "an SDR file stays RGBA8 with HDR output on");

      // thinning to 3 frames keeps the length and takes frames 0, 2, 4
      Collect t = decode("clip-sdr.avif", false, 3);
      Check.check(t.frames.size() == 3 && t.total() == 250, "thinned to 3 frames, 250 ms: " + t.frames.size() + " " + t.total());
      Check.check(near(t.frames.get(1)[0], 0, 0, 255, 3), "second kept frame is source frame 2 (blue)");

      // PQ clip: SDR output = the GIF tone map of the grey levels, HDR output = RGB9_E5 rising past 1 at 1,000 nits
      Collect h = decode("clip-hdr-pq.avif", false, 96);
      Check.check(h.frames.size() == 6 && !h.rgb9e5 && h.describe.contains("PQ"), "pq clip, SDR out: " + h.describe);
      int[] levels = new int[3];
      for (int i = 0; i < 3; i++) {
         int[] one = new int[1];
         long code = ClipColorTest.pqCode(new int[]{50, 200, 1000}[i]);
         ClipColor.toSdr(new long[]{code << 32 | code << 16 | code}, one, 1);
         levels[i] = one[0] & 0xFF;
         int p = h.frames.get(i)[100];
         Check.check(Math.abs(r(p) - levels[i]) <= 3 && Math.abs(g(p) - levels[i]) <= 3, "pq frame " + i + " grey " + r(p) + " vs " + levels[i]);
      }
      Check.check(levels[0] < levels[1] && levels[1] < levels[2] && levels[1] < 200, "tone-mapped greys rise (1,000 nits clips in the GIF chain too): " + java.util.Arrays.toString(levels));
      Collect hh = decode("clip-hdr-pq.avif", true, 96);
      Check.check(hh.rgb9e5 && hh.describe.contains("HDR"), "pq clip, HDR out: " + hh.describe);
      float v200 = ClipColor.rgb9e5Decode(hh.frames.get(1)[100])[1];
      float v1000 = ClipColor.rgb9e5Decode(hh.frames.get(2)[100])[1];
      Check.check(v1000 > 1.2F && v1000 > v200 * 1.5F, "1,000 nits above the UI white in HDR: " + v200 + " -> " + v1000);

      // a still image is a one-frame clip
      Collect st = decode("still-sdr.avif", false, 96);
      Check.check(st.frames.size() == 1 && st.delays.length == 1 && near(st.frames.get(0)[0], 255, 0, 0, 3), "still: " + st.describe);

      // too wide: refused (the GIF plays instead)
      boolean refused = false;
      try {
         decode("clip-too-wide.avif", false, 96);
      } catch (IllegalArgumentException e) {
         refused = true;
      }
      Check.check(refused, "a 1040 px wide file is refused");

      Check.check(GifTextures.avifPath("media/ui/pzopt/compare/fog-opt.gif").equals("media/ui/pzopt/compare/fog-opt.avif"), "avif beside the gif");
      Check.check(GifTextures.frameAt(new int[]{42, 41, 42}, 125, 82) == 1 && GifTextures.frameAt(new int[]{42, 41, 42}, 125, 83) == 2 && GifTextures.frameAt(new int[]{42, 41, 42}, 125, 125) == 0, "frame clock");
      // playback starts once the decode so far predicts the rest in time (10 frames of 42 ms)
      GifTextures.AvifStream as = new GifTextures.AvifStream("x.avif", false);
      as.delaysMs = new int[]{42, 42, 42, 42, 42, 42, 42, 42, 42, 42};
      long now = 1_000_000_000L;
      as.decodeStartNs = now - 100_000_000L;
      Check.check(GifTextures.playable(as, 5, now), "5 frames in 100 ms: the other 5 come in time");
      Check.check(!GifTextures.playable(as, 1, now), "1 frame in 100 ms: too slow to start");
      Check.check(GifTextures.playable(as, 10, now), "all frames up: plays");
      System.out.println("ClipDecoderTest OK");
   }
}
