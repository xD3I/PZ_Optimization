package pzopt;

import java.util.List;

/** The built-in benchmark's pass order: warm-up first, ABBA with the stock comparison, scenarios interleaved by round. */
public class BenchOrderTest {
   public static void main(String[] args) {
      String plain = BenchOrder.steps(List.of("spin", "horde"), 2, false, false);
      Check.check(plain.equals("spin/A/1,horde/A/1,spin/A/2,horde/A/2"), "plain order: " + plain);
      String ab = BenchOrder.steps(List.of("spin"), 4, true, true);
      Check.check(ab.equals("spin/W/0,spin/A/1,spin/B/1,spin/B/2,spin/A/2,spin/A/3,spin/B/3,spin/B/4,spin/A/4"), "ABBA with warm-up: " + ab);
      // ABBA balances the positions: A and B have the same mean index over a full ABBA cycle
      String[] st = BenchOrder.steps(List.of("x"), 2, true, false).split(",");
      double a = 0, b = 0;
      for (int i = 0; i < st.length; i++) {
         if (st[i].contains("/A/")) {
            a += i;
         } else {
            b += i;
         }
      }
      Check.check(a == b, "ABBA: A and B at the same mean position (" + a + " vs " + b + ")");
      System.out.println("BenchOrderTest ok");
   }
}
