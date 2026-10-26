package pzopt;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaException;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaUtil;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import zombie.core.BoxedStaticValues;

/**
 * luaPatternFast (2026-10-26, Lua VM pass): Kahlua's pattern matcher (StringLib.match and its helpers, string.find /
 * match / gsub; string.gmatch is find + match in the game's stdlib.lua) on int positions instead of a position object
 * per step and clone. A line-by-line port of the stock code, quirks included: a NUL in the pattern ends it, positions
 * past the end read as NUL, '$' is special only last, '%f' at the subject's start sees NUL, the range test of a set
 * and the error texts are stock's, the same recursion. The one stock path it never takes: a pattern holding '%' and a
 * digit (a back-reference). Kahlua's capture check is inverted there and an index past the open captures reads a capture
 * kept by a pooled MatchState from an earlier call, so the caller keeps the stock matcher for such a pattern
 * ({@link #applies}). LuaStringFastTest compares every call against the stock matcher.
 */
public final class LuaPattern {
   public static boolean on = Config.LUA_PATTERN_FAST;

   private static final int MAXCAPTURES = 32;
   private static final int CAP_UNFINISHED = -1;
   private static final int CAP_POSITION = -2;

   private final String src;
   private final String pat;
   private final int endIndex;
   private final LuaCallFrame callFrame;
   private int level;
   private int[] capInit;
   private int[] capLen;

   private LuaPattern(LuaCallFrame callFrame, String src, String pat) {
      this.callFrame = callFrame;
      this.src = src;
      this.pat = pat;
      this.endIndex = src.length();
   }

   /** luaPatternNoPool: StringLib.findAux / gsub take their pooled matcher state only once the stock matcher needs it. */
   public static boolean poolSkip = Config.LUA_PATTERN_NO_POOL; // not final: LuaStringFastTest runs stock with it off
   /** What findAuxInner / gsub return without a pool entry when the stock matcher has to run (never a result count). */
   public static final int NEED_POOL = Integer.MIN_VALUE;

   /**
    * luaPatternSingle (2026-10-26): a pattern that is one single-character item, optionally quantified (`[_%-%s/\\]`,
    * `%s+`, `.?`, `x*`; after an optional '^'), is matched by a scan instead of the recursive matcher, the item's answer per
    * character < 256 memoised per pattern (every call of a mod's `s:gsub("[_%-%s/\\]", "")` re-parsed the set for every
    * character). Same answer as match(): it calls the same singleMatch and expands as maxExpand / minExpand do with
    * nothing after the item (the longest run for '*' / '+', the empty match for '-').
    */
   public static boolean single = Config.LUA_PATTERN_SINGLE; // not final: LuaStringFastTest compares both

   static final byte ONE = 1, STAR = 2, PLUS = 3, MIN = 4, OPT = 5;

   /** One parsed pattern: kind 0 = not a single item (the recursive matcher). */
   static final class Single {
      final String pat;
      final byte kind;
      final int p;
      final int ep;
      final byte[] memo = new byte[256]; // 0 unknown, 1 no, 2 yes

      Single(String pat, byte kind, int p, int ep) {
         this.pat = pat;
         this.kind = kind;
         this.p = p;
         this.ep = ep;
      }
   }

   private static final Single[] SINGLES = new Single[128];

   /** The single-item form of this matcher's pattern from pp on (pp = 0, or 1 after '^'), or null. */
   private Single single(int pp) {
      if (!single) {
         return null;
      }
      String pt = this.pat;
      int h = (pt.hashCode() * 31 + pp) & (SINGLES.length - 1);
      Single sg = SINGLES[h];
      if (sg == null || sg.p != pp || !sg.pat.equals(pt)) {
         SINGLES[h] = sg = parseSingle(pp); // racing writers store equal entries; a reader sees a whole one (final fields)
      }
      return sg.kind == 0 ? null : sg;
   }

   private Single parseSingle(int pp) {
      char c = p(pp);
      byte kind = 0;
      int ep = 0;
      boolean special = c == '\u0000' || c == '(' || c == ')' || c == '$' && p(pp + 1) == 0
         || c == '%' && (p(pp + 1) == 'b' || p(pp + 1) == 'f' || Character.isDigit(p(pp + 1)));
      if (!special) {
         try {
            ep = classEnd(pp);
            switch (p(ep)) {
               case '\u0000':
                  kind = ONE;
                  break;
               case '*':
                  kind = p(ep + 1) == 0 ? STAR : 0;
                  break;
               case '+':
                  kind = p(ep + 1) == 0 ? PLUS : 0;
                  break;
               case '-':
                  kind = p(ep + 1) == 0 ? MIN : 0;
                  break;
               case '?':
                  kind = p(ep + 1) == 0 ? OPT : 0;
                  break;
               default:
                  kind = 0;
            }
         } catch (RuntimeException e) {
            kind = 0; // malformed: the recursive matcher raises stock's error
         }
      }
      return new Single(this.pat, kind, pp, ep);
   }

   private boolean member(Single sg, char c) {
      if (c < 256) {
         byte m = sg.memo[c];
         if (m == 0) {
            sg.memo[c] = m = singleMatch(c, sg.p, sg.ep) ? (byte)2 : (byte)1;
         }
         return m == 2;
      }
      return singleMatch(c, sg.p, sg.ep);
   }

   /** match(s, sg.p) for a single-item pattern. */
   private int matchSingle(int s, Single sg) {
      switch (sg.kind) {
         case ONE:
            return s < endIndex && member(sg, src.charAt(s)) ? s + 1 : -1;
         case OPT:
            return s < endIndex && member(sg, src.charAt(s)) ? s + 1 : s;
         case MIN:
            return s;
         default: { // STAR, PLUS
            int i = s;
            while (i < endIndex && member(sg, src.charAt(i))) {
               i++;
            }
            return sg.kind == PLUS && i == s ? -1 : i;
         }
      }
   }

   public static boolean noPool() {
      return poolSkip && LuaJit.newOn();
   }

   /** The fast matcher may run this pattern: the key is on and no '%' is followed by a digit (escaped ones included). */
   public static boolean applies(String pattern) {
      if (!on || LuaJit.AB && !ZombAb.on(ZombAb.LUA_JIT)) { // the "off" half of an in-run luaJit A/B takes the stock matcher too
         return false;
      }
      for (int i = pattern.indexOf('%'); i >= 0 && i + 1 < pattern.length(); i = pattern.indexOf('%', i + 1)) {
         if (Character.isDigit(pattern.charAt(i + 1))) {
            return false;
         }
      }
      return true;
   }

   private char s(int i) {
      return i >= src.length() ? '\u0000' : src.charAt(i);
   }

   private char p(int i) {
      return i >= pat.length() ? '\u0000' : pat.charAt(i);
   }

   /** StringLib.findAuxInner's pattern branch from the anchor test on; init is the clamped 0-based start. */
   public static int find(LuaCallFrame callFrame, String source, String pattern, int init, boolean find) {
      LuaPattern ms = new LuaPattern(callFrame, source, pattern);
      int p = 0;
      boolean anchor = false;
      if (ms.p(0) == '^') {
         anchor = true;
         p = 1;
      }
      int s1 = init;
      Single sg = ms.single(p);
      do {
         ms.level = 0;
         int res;
         if ((res = sg != null ? ms.matchSingle(s1, sg) : ms.match(s1, p)) >= 0) {
            if (find) {
               return callFrame.push((double)(s1 + 1), (double)res) + ms.pushCaptures(-1, -1);
            }
            return ms.pushCaptures(s1, res);
         }
      } while (s1++ < ms.endIndex && !anchor);
      return callFrame.pushNil();
   }

   /** StringLib.gsub from the pattern pointers on; repl is checked by the caller first. */
   public static int gsub(LuaCallFrame cf, String srcTemp, String pTemp, Object repl, int maxSubstitutions) {
      LuaPattern ms = new LuaPattern(cf, srcTemp, pTemp);
      int pattern = 0;
      boolean anchor = false;
      if (ms.p(0) == '^') {
         anchor = true;
         pattern = 1;
      }
      int src = 0;
      int n = 0;
      StringBuilder b = new StringBuilder();
      Single sg = ms.single(pattern);
      while (n < maxSubstitutions) {
         ms.level = 0;
         int e = sg != null ? ms.matchSingle(src, sg) : ms.match(src, pattern);
         if (e >= 0) {
            n++;
            ms.addValue(repl, b, src, e);
         }
         if (e >= 0 && e > src) {
            src = e;
         } else {
            if (src >= ms.endIndex) {
               break;
            }
            b.append(ms.s(src++));
         }
         if (anchor) {
            break;
         }
      }
      return cf.push(b.append(src == 0 ? srcTemp : srcTemp.substring(src)).toString(), (double)n);
   }

   private Object pushOneCapture(int i, int s, int e) {
      if (i >= level) {
         if (i == 0) {
            String res = src.substring(s, e);
            callFrame.push(res);
            return res;
         } else {
            throw new RuntimeException("invalid capture index");
         }
      } else {
         int l = capLen[i];
         if (l == CAP_UNFINISHED) {
            throw new RuntimeException("unfinished capture");
         } else if (l == CAP_POSITION) {
            Double res = BoxedStaticValues.toDouble(capInit[i] + 1);
            callFrame.push(res);
            return res;
         } else {
            int index = capInit[i];
            String res = src.substring(index, index + l);
            callFrame.push(res);
            return res;
         }
      }
   }

   /** s < 0: StringLib.push_captures with a null s (find). */
   private int pushCaptures(int s, int e) {
      int nlevels = level == 0 && s >= 0 ? 1 : level;
      KahluaUtil.luaAssert(nlevels <= 32, "too many captures");
      for (int i = 0; i < nlevels; i++) {
         pushOneCapture(i, s, e);
      }
      return nlevels;
   }

   private Object getCapture(int i) {
      if (i >= level) {
         return null;
      }
      return capLen[i] == CAP_POSITION ? (double)(capInit[i] + 1) : src.substring(capInit[i], capInit[i] + capLen[i]);
   }

   private int startCapture(int s, int p, int what) {
      int lv = level;
      KahluaUtil.luaAssert(lv < 32, "too many captures");
      if (capInit == null) {
         capInit = new int[MAXCAPTURES];
         capLen = new int[MAXCAPTURES];
      }
      capInit[lv] = s;
      capLen[lv] = what;
      level = lv + 1;
      int res;
      if ((res = match(s, p)) < 0) {
         level--;
      }
      return res;
   }

   private int captureToClose() {
      int lv = level;
      lv--;
      while (lv >= 0) {
         if (capLen[lv] == CAP_UNFINISHED) {
            return lv;
         }
         lv--;
      }
      throw new RuntimeException("invalid pattern capture");
   }

   private int endCapture(int s, int p) {
      int l = captureToClose();
      capLen[l] = s - capInit[l];
      int res;
      if ((res = match(s, p)) < 0) {
         capLen[l] = CAP_UNFINISHED;
      }
      return res;
   }

   private int matchBalance(int ss, int p) {
      KahluaUtil.luaAssert(p(p) != 0 && p(p + 1) != 0, "unbalanced pattern");
      int s = ss;
      if (s(s) != p(p)) {
         return -1;
      }
      int b = p(p);
      int e = p(p + 1);
      int cont = 1;
      while (++s < endIndex) {
         if (s(s) == e) {
            if (--cont == 0) {
               return s + 1;
            }
         } else if (s(s) == b) {
            cont++;
         }
      }
      return -1;
   }

   private int classEnd(int pp) {
      int p = pp;
      switch (p(p++)) {
         case '%':
            KahluaUtil.luaAssert(p(p) != 0, "malformed pattern (ends with '%')");
            return p + 1;
         case '[':
            if (p(p) == '^') {
               p++;
            }
            do {
               KahluaUtil.luaAssert(p(p) != 0, "malformed pattern (missing ']')");
               if (p(p++) == '%' && p(p) != 0) {
                  p++;
               }
            } while (p(p) != ']');
            return p + 1;
         default:
            return p;
      }
   }

   private boolean singleMatch(char c, int p, int ep) {
      switch (p(p)) {
         case '%':
            return matchClass(p(p + 1), c);
         case '.':
            return true;
         case '[':
            return matchBracketClass(c, p, ep - 1);
         default:
            return p(p) == c;
      }
   }

   private int minExpand(int ss, int p, int ep) {
      int sp = ep + 1;
      int s = ss;
      while (true) {
         int res = match(s, sp);
         if (res >= 0) {
            return res;
         }
         if (s >= endIndex || !singleMatch(s(s), p, ep)) {
            return -1;
         }
         s++;
      }
   }

   private int maxExpand(int s, int p, int ep) {
      int i = 0;
      while (s + i < endIndex && singleMatch(s(s + i), p, ep)) {
         i++;
      }
      while (i >= 0) {
         int res = match(s + i, ep + 1);
         if (res >= 0) {
            return res;
         }
         i--;
      }
      return -1;
   }

   private boolean matchBracketClass(char c, int pp, int ec) {
      int p = pp;
      boolean sig = true;
      if (p(p + 1) == '^') {
         sig = false;
         p++;
      }
      while (++p < ec) {
         if (p(p) == '%') {
            p++;
            if (matchClass(p(p), c)) {
               return sig;
            }
         } else if (p(p + 1) == '-' && p + 2 < ec) {
            p += 2;
            if (p(p - 2) <= c && c <= p(p)) {
               return sig;
            }
         } else if (p(p) == c) {
            return sig;
         }
      }
      return !sig;
   }

   /** StringLib.match: the end of the match, or -1 for no match (stock: null). */
   private int match(int ss, int pp) {
      int s = ss;
      int p = pp;
      while (true) {
         boolean isDefault = false;
         switch (p(p)) {
            case '\u0000':
               return s;
            case '$':
               if (p(p + 1) == 0) {
                  return s == endIndex ? s : -1;
               }
               isDefault = true;
               break;
            case '%':
               switch (p(p + 1)) {
                  case 'b':
                     s = matchBalance(s, p + 2);
                     if (s < 0) {
                        return -1;
                     }
                     p += 4;
                     continue;
                  case 'f': {
                     p += 2;
                     KahluaUtil.luaAssert(p(p) == '[', "missing '[' after '%%f' in pattern");
                     int ep = classEnd(p);
                     char previous = s == 0 ? '\u0000' : s(s - 1);
                     if (!matchBracketClass(previous, p, ep - 1) && matchBracketClass(s(s), p, ep - 1)) {
                        p = ep;
                        continue;
                     }
                     return -1;
                  }
                  default:
                     if (Character.isDigit(p(p + 1))) {
                        throw new IllegalStateException("luaPatternFast: back-reference reached the fast matcher");
                     }
                     isDefault = true;
                     break;
               }
               break;
            case '(':
               if (p(p + 1) == ')') {
                  return startCapture(s, p + 2, CAP_POSITION);
               }
               return startCapture(s, p + 1, CAP_UNFINISHED);
            case ')':
               return endCapture(s, p + 1);
            default:
               isDefault = true;
         }
         if (isDefault) {
            int ep = classEnd(p);
            boolean m = s < endIndex && singleMatch(s(s), p, ep);
            switch (p(ep)) {
               case '*':
                  return maxExpand(s, p, ep);
               case '+':
                  return m ? maxExpand(s + 1, p, ep) : -1;
               case '-':
                  return minExpand(s, p, ep);
               case '?': {
                  int res;
                  if (m && (res = match(s + 1, ep + 1)) >= 0) {
                     return res;
                  }
                  p = ep + 1;
                  break;
               }
               default:
                  if (!m) {
                     return -1;
                  }
                  s++;
                  p = ep;
            }
         }
      }
   }

   private static boolean matchClass(char classIdentifier, char c) {
      char lowerClassIdentifier = Character.toLowerCase(classIdentifier);
      boolean res;
      switch (lowerClassIdentifier) {
         case 'a':
            res = Character.isLowerCase(c) || Character.isUpperCase(c);
            break;
         case 'c':
            res = c >= 0 && c <= 31 || c == 127;
            break;
         case 'd':
            res = Character.isDigit(c);
            break;
         case 'l':
            res = Character.isLowerCase(c);
            break;
         case 'p':
            res = c >= '!' && c <= '/' || c >= ':' && c <= '@' || c >= '[' && c <= '`' || c >= '{' && c <= '~';
            break;
         case 's':
            res = c >= '\t' && c <= '\r' || c == ' ';
            break;
         case 'u':
            res = Character.isUpperCase(c);
            break;
         case 'w':
            res = Character.isLowerCase(c) || Character.isUpperCase(c) || Character.isDigit(c);
            break;
         case 'x':
            res = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            break;
         case 'z':
            res = c == 0;
            break;
         default:
            return classIdentifier == c;
      }
      return lowerClassIdentifier == classIdentifier == res;
   }

   private void addValue(Object repl, StringBuilder b, int src, int e) {
      String replString = KahluaUtil.rawTostring(repl);
      if (replString != null) {
         b.append(addString(replString, src, e));
      } else {
         Object captures = getCapture(0);
         String match;
         if (captures != null) {
            match = KahluaUtil.rawTostring(captures);
         } else {
            match = this.src.substring(src, src + (e - src));
         }
         Object res = null;
         if (repl instanceof KahluaTable kahluaTable) {
            res = kahluaTable.rawget(match);
         } else {
            res = callFrame.getThread().call(repl, match, null, null);
         }
         if (res == null) {
            res = match;
         }
         b.append(KahluaUtil.rawTostring(res));
      }
   }

   private String addString(String repl, int s, int e) {
      StringBuilder buf = new StringBuilder();
      for (int i = 0; i < repl.length(); i++) {
         char c = i >= repl.length() ? '\u0000' : repl.charAt(i);
         if (c != '%') {
            buf.append(c);
         } else {
            ++i;
            c = i >= repl.length() ? '\u0000' : repl.charAt(i);
            if (!Character.isDigit(c)) {
               buf.append(c);
            } else if (c == '0') {
               buf.append(src.substring(s, s + (e - s)));
            } else {
               Object o = getCapture(c - '1');
               if (o == null) {
                  throw new KahluaException("invalid capture index");
               }
               buf.append(KahluaUtil.tostring(o, null));
            }
         }
      }
      return buf.toString();
   }

   /** The repl kinds StringLib.gsub accepts (its check runs before the matcher). */
   public static boolean replOk(Object repl) {
      return repl instanceof Double || repl instanceof String || repl instanceof LuaClosure || repl instanceof JavaFunction
            || repl instanceof KahluaTable;
   }
}
