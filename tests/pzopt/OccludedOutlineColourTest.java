package pzopt;

/** Picker RGB decoding without a rendering context. */
public final class OccludedOutlineColourTest {
  public static void main(String[] args) {
    check("FFC740", 0xFFC740);
    check("#12abEF", 0x12ABEF);
    check(" 12abef ", 0x12ABEF);
    check("000000", 0);
    check("FFFFFF", 0xFFFFFF);
    for (String invalid : new String[] {null, "", "12345", "1234567", "GGGGGG", "-12345", "red"}) {
      check(invalid, 0xFFC740);
    }
    System.out.println("OccludedOutlineColourTest passed");
  }

  private static void check(String text, int expected) {
    int actual = OccludedOutline.parseColour(text);
    if (actual != expected) throw new AssertionError(text + ": " + Integer.toHexString(actual));
  }
}
