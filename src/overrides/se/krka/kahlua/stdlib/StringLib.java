package se.krka.kahlua.stdlib;

import java.util.ArrayList;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaException;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaUtil;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Platform;
import zombie.Lua.LuaManager;
import zombie.core.BoxedStaticValues;
import zombie.popman.ObjectPool;

public final class StringLib implements JavaFunction {
   private static final int SUB = 0;
   private static final int CHAR = 1;
   private static final int BYTE = 2;
   private static final int LOWER = 3;
   private static final int UPPER = 4;
   private static final int REVERSE = 5;
   private static final int FORMAT = 6;
   private static final int FIND = 7;
   private static final int MATCH = 8;
   private static final int GSUB = 9;
   private static final int TRIM = 10;
   private static final int SPLIT = 11;
   private static final int SORT = 12;
   private static final int CONTAINS = 13;
   private static final int NUM_FUNCTIONS = 14;
   private static final boolean[] SPECIALS = new boolean[256];
   private static final int LUA_MAXCAPTURES = 32;
   private static final char L_ESC = '%';
   private static final int CAP_UNFINISHED = -1;
   private static final int CAP_POSITION = -2;
   private static final String[] names;
   private static final StringLib[] functions;
   private static final Class<?> STRING_CLASS;
   private final int methodId;
   private static final char[] digits;
   private static final int STRING_POINTER_POOL_LOADING = 65536;
   private static final int STRING_POINTER_POOL_RUNTIME = 512;
   private static final int MATCH_STATE_POOL_SIZE = 64;
   private static final int ALLOCATIONS_POOL_SIZE = 16;
   private static final ObjectPool<StringLib.MatchState> s_MatchStatePool;
   private static final ObjectPool<StringLib.StringPointer> s_StringPointerPool;
   private static final ObjectPool<StringLib.Allocations> s_AllocationsPool;

   public StringLib(int index) {
      this.methodId = index;
   }

   public static void register(Platform platform, KahluaTable env) {
      KahluaTable string = platform.newTable();

      for (int i = 0; i < 14; i++) {
         string.rawset(names[i], functions[i]);
      }

      string.rawset("__index", string);
      KahluaTable metatables = KahluaUtil.getClassMetatables(platform, env);
      metatables.rawset(STRING_CLASS, string);
      env.rawset("string", string);
   }

   @Override
   public String toString() {
      return names[this.methodId];
   }

   public int call(LuaCallFrame callFrame, int nArguments) {
      switch (this.methodId) {
         case 0:
            return this.sub(callFrame, nArguments);
         case 1:
            return this.stringChar(callFrame, nArguments);
         case 2:
            return this.stringByte(callFrame, nArguments);
         case 3:
            return this.lower(callFrame, nArguments);
         case 4:
            return this.upper(callFrame, nArguments);
         case 5:
            return this.reverse(callFrame, nArguments);
         case 6:
            return this.format(callFrame, nArguments);
         case 7:
            return findAux(callFrame, true);
         case 8:
            return findAux(callFrame, false);
         case 9:
            return gsub(callFrame, nArguments);
         case 10:
            return trim(callFrame, nArguments);
         case 11:
            return split(callFrame, nArguments);
         case 12:
            return sort(callFrame, nArguments);
         case 13:
            return this.contains(callFrame, nArguments);
         default:
            return 0;
      }
   }

   private long unsigned(long v) {
      if (v < 0L) {
         v += 4294967296L;
      }

      return v;
   }

   private int format(LuaCallFrame callFrame, int nArguments) {
      String f = KahluaUtil.getStringArg(callFrame, 1, names[6]);
      int len = f.length();
      int argc = 2;
      StringBuilder result = new StringBuilder();

      label323:
      for (int i = 0; i < len; i++) {
         char c = f.charAt(i);
         if (c == '%') {
            i++;
            KahluaUtil.luaAssert(i < len, "incomplete option to 'format'");
            c = f.charAt(i);
            if (c == '%') {
               result.append('%');
            } else {
               boolean repr = false;
               boolean zeroPadding = false;
               boolean leftJustify = false;
               boolean showPlus = false;
               boolean spaceForSign = false;

               while (true) {
                  switch (c) {
                     case ' ':
                        spaceForSign = true;
                        break;
                     case '#':
                        repr = true;
                        break;
                     case '+':
                        showPlus = true;
                        break;
                     case '-':
                        leftJustify = true;
                        break;
                     case '0':
                        zeroPadding = true;
                        break;
                     default:
                        int width = 0;

                        while (c >= '0' && c <= '9') {
                           width = 10 * width + c - 48;
                           i++;
                           KahluaUtil.luaAssert(i < len, "incomplete option to 'format'");
                           c = f.charAt(i);
                        }

                        int precision = 0;
                        boolean hasPrecision = false;
                        if (c == '.') {
                           hasPrecision = true;
                           i++;
                           KahluaUtil.luaAssert(i < len, "incomplete option to 'format'");

                           for (c = f.charAt(i); c >= '0' && c <= '9'; c = f.charAt(i)) {
                              precision = 10 * precision + c - 48;
                              i++;
                              KahluaUtil.luaAssert(i < len, "incomplete option to 'format'");
                           }
                        }

                        if (leftJustify) {
                           zeroPadding = false;
                        }

                        int base = 10;
                        boolean upperCase = false;
                        int defaultPrecision = 6;
                        String basePrepend = "";
                        switch (c) {
                           case 'E':
                              upperCase = true;
                              break;
                           case 'F':
                           case 'H':
                           case 'I':
                           case 'J':
                           case 'K':
                           case 'L':
                           case 'M':
                           case 'N':
                           case 'O':
                           case 'P':
                           case 'Q':
                           case 'R':
                           case 'S':
                           case 'T':
                           case 'U':
                           case 'V':
                           case 'W':
                           case 'Y':
                           case 'Z':
                           case '[':
                           case '\\':
                           case ']':
                           case '^':
                           case '_':
                           case '`':
                           case 'a':
                           case 'b':
                           case 'h':
                           case 'j':
                           case 'k':
                           case 'l':
                           case 'm':
                           case 'n':
                           case 'p':
                           case 'r':
                           case 't':
                           case 'v':
                           case 'w':
                           default:
                              throw new RuntimeException("invalid option '%" + c + "' to 'format'");
                           case 'G':
                              upperCase = true;
                              break;
                           case 'X':
                              base = 16;
                              defaultPrecision = 1;
                              upperCase = true;
                              basePrepend = "0X";
                              break;
                           case 'c':
                              zeroPadding = false;
                              break;
                           case 'd':
                           case 'i':
                              defaultPrecision = 1;
                           case 'e':
                           case 'f':
                           case 'g':
                              break;
                           case 'o':
                              base = 8;
                              defaultPrecision = 1;
                              basePrepend = "0";
                              break;
                           case 'q':
                              width = 0;
                              break;
                           case 's':
                              zeroPadding = false;
                              break;
                           case 'u':
                              defaultPrecision = 1;
                              break;
                           case 'x':
                              base = 16;
                              defaultPrecision = 1;
                              basePrepend = "0x";
                        }

                        if (!hasPrecision) {
                           precision = defaultPrecision;
                        }

                        if (hasPrecision && base != 10) {
                           zeroPadding = false;
                        }

                        char padCharacter = (char)(zeroPadding ? 48 : 32);
                        int resultStartLength = result.length();
                        if (!leftJustify) {
                           this.extend(result, width, padCharacter);
                        }

                        switch (c) {
                           case 'E':
                           case 'e':
                           case 'f':
                              Double v = this.getDoubleArg(callFrame, argc);
                              boolean isNaN = v.isInfinite() || v.isNaN();
                              double vDouble = v;
                              if (KahluaUtil.isNegative(vDouble)) {
                                 if (!isNaN) {
                                    result.append('-');
                                 }

                                 vDouble = -vDouble;
                              } else if (showPlus) {
                                 result.append('+');
                              } else if (spaceForSign) {
                                 result.append(' ');
                              }

                              if (isNaN) {
                                 result.append(KahluaUtil.numberToString(v));
                              } else if (c == 'f') {
                                 this.appendPrecisionNumber(result, vDouble, precision, repr);
                              } else {
                                 this.appendScientificNumber(result, vDouble, precision, repr, false);
                              }
                              break;
                           case 'F':
                           case 'H':
                           case 'I':
                           case 'J':
                           case 'K':
                           case 'L':
                           case 'M':
                           case 'N':
                           case 'O':
                           case 'P':
                           case 'Q':
                           case 'R':
                           case 'S':
                           case 'T':
                           case 'U':
                           case 'V':
                           case 'W':
                           case 'Y':
                           case 'Z':
                           case '[':
                           case '\\':
                           case ']':
                           case '^':
                           case '_':
                           case '`':
                           case 'a':
                           case 'b':
                           case 'h':
                           case 'j':
                           case 'k':
                           case 'l':
                           case 'm':
                           case 'n':
                           case 'p':
                           case 'r':
                           case 't':
                           case 'v':
                           case 'w':
                           default:
                              throw new RuntimeException("Internal error");
                           case 'G':
                           case 'g':
                              if (precision <= 0) {
                                 precision = 1;
                              }

                              Double vG = this.getDoubleArg(callFrame, argc);
                              boolean isNaNG = vG.isInfinite() || vG.isNaN();
                              double vDoubleG = vG;
                              if (KahluaUtil.isNegative(vDoubleG)) {
                                 if (!isNaNG) {
                                    result.append('-');
                                 }

                                 vDoubleG = -vDoubleG;
                              } else if (showPlus) {
                                 result.append('+');
                              } else if (spaceForSign) {
                                 result.append(' ');
                              }

                              if (isNaNG) {
                                 result.append(KahluaUtil.numberToString(vG));
                              } else {
                                 double x = roundToSignificantNumbers(vDoubleG, precision);
                                 if (x == 0.0 || x >= 1.0E-4 && x < KahluaUtil.ipow(10L, precision)) {
                                    int iPartSize;
                                    if (x == 0.0) {
                                       iPartSize = 1;
                                    } else if (Math.floor(x) == 0.0) {
                                       iPartSize = 0;
                                    } else {
                                       double longValue = x;

                                       for (iPartSize = 1; longValue >= 10.0; iPartSize++) {
                                          longValue /= 10.0;
                                       }
                                    }

                                    this.appendSignificantNumber(result, x, precision - iPartSize, repr);
                                    break;
                                 }

                                 this.appendScientificNumber(result, x, precision - 1, repr, true);
                              }
                              break;
                           case 'X':
                           case 'o':
                           case 'u':
                           case 'x':
                              long vLong = this.getDoubleArg(callFrame, argc).longValue();
                              vLong = this.unsigned(vLong);
                              if (repr) {
                                 if (base == 8) {
                                    int digits = 0;

                                    for (long vLong2 = vLong; vLong2 > 0L; digits++) {
                                       vLong2 /= 8L;
                                    }

                                    if (precision <= digits) {
                                       result.append(basePrepend);
                                    }
                                 } else if (base == 16 && vLong != 0L) {
                                    result.append(basePrepend);
                                 }
                              }

                              if (vLong != 0L || precision > 0) {
                                 stringBufferAppend(result, vLong, base, false, precision);
                              }
                              break;
                           case 'c':
                              result.append((char)this.getDoubleArg(callFrame, argc).shortValue());
                              break;
                           case 'd':
                           case 'i':
                              Double vD = this.getDoubleArg(callFrame, argc);
                              long vLongD = vD.longValue();
                              if (vLongD < 0L) {
                                 result.append('-');
                                 vLongD = -vLongD;
                              } else if (showPlus) {
                                 result.append('+');
                              } else if (spaceForSign) {
                                 result.append(' ');
                              }

                              if (vLongD != 0L || precision > 0) {
                                 stringBufferAppend(result, vLongD, base, false, precision);
                              }
                              break;
                           case 'q':
                              String q = this.getStringArg(callFrame, argc);
                              result.append('"');

                              for (int j = 0; j < q.length(); j++) {
                                 char d = q.charAt(j);
                                 switch (d) {
                                    case '\n':
                                       result.append("\\\n");
                                       break;
                                    case '\r':
                                       result.append("\\r");
                                       break;
                                    case '"':
                                       result.append("\\\"");
                                       break;
                                    case '\\':
                                       result.append("\\");
                                       break;
                                    default:
                                       result.append(d);
                                 }
                              }

                              result.append('"');
                              break;
                           case 's':
                              String s = this.getStringArg(callFrame, argc);
                              int n = s.length();
                              if (hasPrecision) {
                                 n = Math.min(precision, s.length());
                              }

                              this.append(result, s, 0, n);
                        }

                        if (leftJustify) {
                           int currentResultLength = result.length();
                           int d = width - (currentResultLength - resultStartLength);
                           if (d > 0) {
                              this.extend(result, d, ' ');
                           }
                        } else {
                           int currentResultLength = result.length();
                           int d = currentResultLength - resultStartLength - width;
                           d = Math.min(d, width);
                           if (d > 0) {
                              result.delete(resultStartLength, resultStartLength + d);
                           }

                           if (zeroPadding) {
                              int signPos = resultStartLength + width - d;
                              char ch = result.charAt(signPos);
                              if (ch == '+' || ch == '-' || ch == ' ') {
                                 result.setCharAt(signPos, '0');
                                 result.setCharAt(resultStartLength, ch);
                              }
                           }
                        }

                        if (upperCase) {
                           this.stringBufferUpperCase(result, resultStartLength);
                        }

                        argc++;
                        continue label323;
                  }

                  i++;
                  KahluaUtil.luaAssert(i < len, "incomplete option to 'format'");
                  c = f.charAt(i);
               }
            }
         } else {
            result.append(c);
         }
      }

      callFrame.push(result.toString());
      return 1;
   }

   private void append(StringBuilder buffer, String s, int start, int end) {
      for (int i = start; i < end; i++) {
         buffer.append(s.charAt(i));
      }
   }

   private void extend(StringBuilder buffer, int extraWidth, char padCharacter) {
      int preLength = buffer.length();
      buffer.setLength(preLength + extraWidth);

      for (int i = extraWidth - 1; i >= 0; i--) {
         buffer.setCharAt(preLength + i, padCharacter);
      }
   }

   private void stringBufferUpperCase(StringBuilder buffer, int start) {
      int length = buffer.length();

      for (int i = start; i < length; i++) {
         char c = buffer.charAt(i);
         if (c >= 'a' && c <= 'z') {
            buffer.setCharAt(i, (char)(c - ' '));
         }
      }
   }

   private static void stringBufferAppend(StringBuilder sb, double value, int base, boolean printZero, int minDigits) {
      int startPos = sb.length();

      while (value > 0.0 || minDigits > 0) {
         double newValue = Math.floor(value / base);
         sb.append(digits[(int)(value - newValue * base)]);
         value = newValue;
         minDigits--;
      }

      int endPos = sb.length() - 1;
      if (startPos > endPos && printZero) {
         sb.append('0');
      } else {
         int swapCount = (1 + endPos - startPos) / 2;

         for (int i = swapCount - 1; i >= 0; i--) {
            int leftPos = startPos + i;
            int rightPos = endPos - i;
            char left = sb.charAt(leftPos);
            char right = sb.charAt(rightPos);
            sb.setCharAt(leftPos, right);
            sb.setCharAt(rightPos, left);
         }
      }
   }

   private void appendPrecisionNumber(StringBuilder buffer, double number, int precision, boolean requirePeriod) {
      number = roundToPrecision(number, precision);
      double iPart = Math.floor(number);
      double fPart = number - iPart;

      for (int i = 0; i < precision; i++) {
         fPart *= 10.0;
      }

      fPart = KahluaUtil.round(iPart + fPart) - iPart;
      stringBufferAppend(buffer, iPart, 10, true, 0);
      if (requirePeriod || precision > 0) {
         buffer.append('.');
      }

      stringBufferAppend(buffer, fPart, 10, false, precision);
   }

   private void appendSignificantNumber(StringBuilder buffer, double number, int significantDecimals, boolean includeTrailingZeros) {
      double iPart = Math.floor(number);
      stringBufferAppend(buffer, iPart, 10, true, 0);
      double fPart = roundToSignificantNumbers(number - iPart, significantDecimals);
      boolean hasNotStarted = iPart == 0.0 && fPart != 0.0;
      int zeroPaddingBefore = 0;
      int scanLength = significantDecimals;

      for (int i = 0; i < scanLength; i++) {
         fPart *= 10.0;
         if (Math.floor(fPart) == 0.0 && fPart != 0.0) {
            zeroPaddingBefore++;
            if (hasNotStarted) {
               scanLength++;
            }
         }
      }

      fPart = KahluaUtil.round(fPart);
      if (!includeTrailingZeros) {
         while (fPart > 0.0 && fPart % 10.0 == 0.0) {
            fPart /= 10.0;
            significantDecimals--;
         }
      }

      buffer.append('.');
      int periodPos = buffer.length();
      this.extend(buffer, zeroPaddingBefore, '0');
      int prePos = buffer.length();
      stringBufferAppend(buffer, fPart, 10, false, 0);
      int postPos = buffer.length();
      int len = postPos - prePos;
      if (includeTrailingZeros && len < significantDecimals) {
         int padRightSize = significantDecimals - len - zeroPaddingBefore;
         this.extend(buffer, padRightSize, '0');
      }

      if (!includeTrailingZeros && periodPos == buffer.length()) {
         buffer.delete(periodPos - 1, buffer.length());
      }
   }

   private void appendScientificNumber(StringBuilder buffer, double x, int precision, boolean repr, boolean useSignificantNumbers) {
      int exponent = 0;

      for (int i = 0; i < 2; i++) {
         if (x >= 1.0) {
            while (x >= 10.0) {
               x /= 10.0;
               exponent++;
            }
         } else {
            while (x > 0.0 && x < 1.0) {
               x *= 10.0;
               exponent--;
            }
         }

         x = roundToPrecision(x, precision);
      }

      int absExponent = Math.abs(exponent);
      char expSign;
      if (exponent >= 0) {
         expSign = '+';
      } else {
         expSign = '-';
      }

      if (useSignificantNumbers) {
         this.appendSignificantNumber(buffer, x, precision, repr);
      } else {
         this.appendPrecisionNumber(buffer, x, precision, repr);
      }

      buffer.append('e');
      buffer.append(expSign);
      stringBufferAppend(buffer, absExponent, 10, true, 2);
   }

   private String getStringArg(LuaCallFrame callFrame, int argc) {
      return this.getStringArg(callFrame, argc, names[6]);
   }

   private String getStringArg(LuaCallFrame callFrame, int argc, String funcname) {
      return KahluaUtil.getStringArg(callFrame, argc, funcname);
   }

   private Double getDoubleArg(LuaCallFrame callFrame, int argc) {
      return this.getDoubleArg(callFrame, argc, names[6]);
   }

   private Double getDoubleArg(LuaCallFrame callFrame, int argc, String name) {
      return KahluaUtil.getNumberArg(callFrame, argc, name);
   }

   private int lower(LuaCallFrame callFrame, int nArguments) {
      KahluaUtil.luaAssert(nArguments >= 1, "not enough arguments");
      String s = this.getStringArg(callFrame, 1, names[3]);
      callFrame.push(s.toLowerCase());
      return 1;
   }

   private int upper(LuaCallFrame callFrame, int nArguments) {
      KahluaUtil.luaAssert(nArguments >= 1, "not enough arguments");
      String s = this.getStringArg(callFrame, 1, names[4]);
      callFrame.push(s.toUpperCase());
      return 1;
   }

   private int contains(LuaCallFrame callFrame, int nArguments) {
      KahluaUtil.luaAssert(nArguments >= 2, "not enough arguments");
      String s = this.getStringArg(callFrame, 1, names[13]);
      String s2 = this.getStringArg(callFrame, 2, names[13]);
      callFrame.push(s.contains(s2));
      return 1;
   }

   private int reverse(LuaCallFrame callFrame, int nArguments) {
      KahluaUtil.luaAssert(nArguments >= 1, "not enough arguments");
      String s = this.getStringArg(callFrame, 1, names[5]);
      s = new StringBuilder(s).reverse().toString();
      callFrame.push(s);
      return 1;
   }

   private int stringByte(LuaCallFrame callFrame, int nArguments) {
      KahluaUtil.luaAssert(nArguments >= 1, "not enough arguments");
      String s = this.getStringArg(callFrame, 1, names[2]);
      int i = this.nullDefault(1, KahluaUtil.getOptionalNumberArg(callFrame, 2));
      int j = this.nullDefault(i, KahluaUtil.getOptionalNumberArg(callFrame, 3));
      int len = s.length();
      if (i < 0) {
         i += len + 1;
      }

      if (i <= 0) {
         i = 1;
      }

      if (j < 0) {
         j += len + 1;
      } else if (j > len) {
         j = len;
      }

      int nReturns = 1 + j - i;
      if (nReturns <= 0) {
         return 0;
      }

      callFrame.setTop(nReturns);
      int offset = i - 1;

      for (int i2 = 0; i2 < nReturns; i2++) {
         char c = s.charAt(offset + i2);
         callFrame.set(i2, KahluaUtil.toDouble(c));
      }

      return nReturns;
   }

   private int nullDefault(int defaultValue, Double val) {
      return val == null ? defaultValue : val.intValue();
   }

   private int stringChar(LuaCallFrame callFrame, int nArguments) {
      StringBuilder sb = new StringBuilder();

      for (int i = 0; i < nArguments; i++) {
         int num = this.getDoubleArg(callFrame, i + 1, names[1]).intValue();
         sb.append((char)num);
      }

      return callFrame.push(sb.toString());
   }

   private int sub(LuaCallFrame callFrame, int nArguments) {
      String s = this.getStringArg(callFrame, 1, names[0]);
      double start = this.getDoubleArg(callFrame, 2, names[0]);
      double end = -1.0;
      if (nArguments >= 3) {
         end = this.getDoubleArg(callFrame, 3, names[0]);
      }

      int istart = (int)start;
      int iend = (int)end;
      int len = s.length();
      if (istart < 0) {
         istart = Math.max(len + istart + 1, 1);
      } else if (istart == 0) {
         istart = 1;
      }

      if (iend < 0) {
         iend = Math.max(0, iend + len + 1);
      } else if (iend > len) {
         iend = len;
      }

      if (istart > iend) {
         return callFrame.push("");
      }

      String res = s.substring(istart - 1, iend);
      return callFrame.push(res);
   }

   public static double roundToPrecision(double x, int precision) {
      double roundingOffset = KahluaUtil.ipow(10L, precision);
      return KahluaUtil.round(x * roundingOffset) / roundingOffset;
   }

   public static double roundToSignificantNumbers(double x, int precision) {
      if (x == 0.0) {
         return 0.0;
      }

      if (x < 0.0) {
         return -roundToSignificantNumbers(-x, precision);
      }

      double lowerLimit = KahluaUtil.ipow(10L, precision - 1);
      double upperLimit = lowerLimit * 10.0;
      double multiplier = 1.0;

      while (multiplier * x < lowerLimit) {
         multiplier *= 10.0;
      }

      while (multiplier * x >= upperLimit) {
         multiplier /= 10.0;
      }

      return KahluaUtil.round(x * multiplier) / multiplier;
   }

   private static Object push_onecapture(StringLib.MatchState ms, int i, StringLib.StringPointer s, StringLib.StringPointer e) {
      if (i >= ms.level) {
         if (i == 0) {
            String res = s.string.substring(s.index, e.index);
            ms.callFrame.push(res);
            return res;
         } else {
            throw new RuntimeException("invalid capture index");
         }
      } else {
         int l = ms.capture[i].len;
         if (l == -1) {
            throw new RuntimeException("unfinished capture");
         } else if (l == -2) {
            Double res = BoxedStaticValues.toDouble(ms.srcInit.length() - ms.capture[i].init.length() + 1);
            ms.callFrame.push(res);
            return res;
         } else {
            int index = ms.capture[i].init.index;
            String res = ms.capture[i].init.string.substring(index, index + l);
            ms.callFrame.push(res);
            return res;
         }
      }
   }

   private static int push_captures(StringLib.MatchState ms, StringLib.StringPointer s, StringLib.StringPointer e) {
      int nlevels = ms.level == 0 && s != null ? 1 : ms.level;
      KahluaUtil.luaAssert(nlevels <= 32, "too many captures");

      for (int i = 0; i < nlevels; i++) {
         push_onecapture(ms, i, s, e);
      }

      return nlevels;
   }

   private static boolean noSpecialChars(String pattern) {
      for (int i = 0; i < pattern.length(); i++) {
         char c = pattern.charAt(i);
         if (c < 256 && SPECIALS[c]) {
            return false;
         }
      }

      return true;
   }

   public static void onLoadingFinished() {
      s_StringPointerPool.setMaxSize(512);
      s_StringPointerPool.clear();
   }

   private static int findAux(LuaCallFrame callFrame, boolean find) {
      if (pzopt.LuaPattern.noPool()) { // pzopt: luaPatternNoPool, the pooled matcher state only for the stock matcher
         try { // pzopt: luaPatternNoPool
            int pzoptR = findAuxInner(null, callFrame, find); // pzopt: luaPatternNoPool
            if (pzoptR != pzopt.LuaPattern.NEED_POOL) { // pzopt: luaPatternNoPool
               return pzoptR; // pzopt: luaPatternNoPool
            } // pzopt: luaPatternNoPool
         } catch (Exception pzoptE) { // pzopt: luaPatternNoPool
            throw new RuntimeException(pzoptE); // pzopt: luaPatternNoPool, as the stock catch below
         } // pzopt: luaPatternNoPool
      } // pzopt: luaPatternNoPool
      try (StringLib.Allocations allocations = (StringLib.Allocations)s_AllocationsPool.alloc()) {
         return findAuxInner(allocations, callFrame, find);
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   private static int findAuxInner(StringLib.Allocations allocations, LuaCallFrame callFrame, boolean find) {
      String f = find ? names[7] : names[8];
      String source = KahluaUtil.getStringArg(callFrame, 1, f);
      String pattern = KahluaUtil.getStringArg(callFrame, 2, f);
      Double i = KahluaUtil.getOptionalNumberArg(callFrame, 3);
      boolean plain = KahluaUtil.boolEval(KahluaUtil.getOptionalArg(callFrame, 4));
      int init = i == null ? 0 : i.intValue() - 1;
      if (init < 0) {
         init += source.length();
         if (init < 0) {
            init = 0;
         }
      } else if (init > source.length()) {
         init = source.length();
      }

      if (!find || !plain && !noSpecialChars(pattern)) {
         if (pzopt.LuaPattern.applies(pattern)) { // pzopt: luaPatternFast, the same matcher on int positions (no back-reference)
            return pzopt.LuaPattern.find(callFrame, source, pattern, init, find); // pzopt: luaPatternFast
         } // pzopt: luaPatternFast
         if (allocations == null) { // pzopt: luaPatternNoPool
            return pzopt.LuaPattern.NEED_POOL; // pzopt: luaPatternNoPool, findAux runs this again with a pool entry
         } // pzopt: luaPatternNoPool
         StringLib.StringPointer s = allocations.allocStringPointer().set(source);
         StringLib.StringPointer p = allocations.allocStringPointer().set(pattern);
         boolean anchor = false;
         if (p.getChar() == '^') {
            anchor = true;
            p.postIncrString(1);
         }

         StringLib.StringPointer s1 = s.getClone();
         s1.postIncrString(init);
         StringLib.MatchState ms = allocations.allocMatchState().set(callFrame, s.getClone(), s.getStringLength());

         do {
            ms.level = 0;
            StringLib.StringPointer res;
            if ((res = match(ms, s1, p)) != null) {
               if (find) {
                  return callFrame.push((double)(s.length() - s1.length() + 1), (double)(s.length() - res.length())) + push_captures(ms, null, null);
               }

               return push_captures(ms, s1, res);
            }
         } while (s1.postIncrStringI(1) < ms.endIndex && !anchor);
      } else {
         int pos = source.indexOf(pattern, init);
         if (pos > -1) {
            return callFrame.push(KahluaUtil.toDouble(pos + 1), KahluaUtil.toDouble(pos + pattern.length()));
         }
      }

      return callFrame.pushNil();
   }

   private static StringLib.StringPointer startCapture(StringLib.MatchState ms, StringLib.StringPointer s, StringLib.StringPointer p, int what) {
      int level = ms.level;
      KahluaUtil.luaAssert(level < 32, "too many captures");
      ms.capture[level].init = s.getClone();
      ms.capture[level].init.setIndex(s.getIndex());
      ms.capture[level].len = what;
      ms.level = level + 1;
      StringLib.StringPointer res;
      if ((res = match(ms, s, p)) == null) {
         ms.level--;
      }

      return res;
   }

   private static int captureToClose(StringLib.MatchState ms) {
      int level = ms.level;
      level--;

      while (level >= 0) {
         if (ms.capture[level].len == -1) {
            return level;
         }

         level--;
      }

      throw new RuntimeException("invalid pattern capture");
   }

   private static StringLib.StringPointer endCapture(StringLib.MatchState ms, StringLib.StringPointer s, StringLib.StringPointer p) {
      int l = captureToClose(ms);
      ms.capture[l].len = ms.capture[l].init.length() - s.length();
      StringLib.StringPointer res;
      if ((res = match(ms, s, p)) == null) {
         ms.capture[l].len = -1;
      }

      return res;
   }

   private static int checkCapture(StringLib.MatchState ms, int l) {
      l -= 49;
      KahluaUtil.luaAssert(l < 0 || l >= ms.level || ms.capture[l].len == -1, "invalid capture index");
      return l;
   }

   private static StringLib.StringPointer matchCapture(StringLib.MatchState ms, StringLib.StringPointer s, int l) {
      l = checkCapture(ms, l);
      int len = ms.capture[l].len;
      if (ms.endIndex - s.length() >= len && ms.capture[l].init.compareTo(s, len) == 0) {
         StringLib.StringPointer sp = s.getClone();
         sp.postIncrString(len);
         return sp;
      } else {
         return null;
      }
   }

   private static StringLib.StringPointer matchBalance(StringLib.MatchState ms, StringLib.StringPointer ss, StringLib.StringPointer p) {
      KahluaUtil.luaAssert(p.getChar() != 0 && p.getChar(1) != 0, "unbalanced pattern");
      StringLib.StringPointer s = ss.getClone();
      if (s.getChar() != p.getChar()) {
         return null;
      }

      int b = p.getChar();
      int e = p.getChar(1);
      int cont = 1;

      while (s.preIncrStringI(1) < ms.endIndex) {
         if (s.getChar() == e) {
            if (--cont == 0) {
               StringLib.StringPointer sp = s.getClone();
               sp.postIncrString(1);
               return sp;
            }
         } else if (s.getChar() == b) {
            cont++;
         }
      }

      return null;
   }

   private static StringLib.StringPointer classEnd(StringLib.StringPointer pp) {
      StringLib.StringPointer p = pp.getClone();
      switch (p.postIncrString(1)) {
         case '%':
            KahluaUtil.luaAssert(p.getChar() != 0, "malformed pattern (ends with '%')");
            p.postIncrString(1);
            return p;
         case '[':
            if (p.getChar() == '^') {
               p.postIncrString(1);
            }

            do {
               KahluaUtil.luaAssert(p.getChar() != 0, "malformed pattern (missing ']')");
               if (p.postIncrString(1) == '%' && p.getChar() != 0) {
                  p.postIncrString(1);
               }
            } while (p.getChar() != ']');

            p.postIncrString(1);
            return p;
         default:
            return p;
      }
   }

   private static boolean singleMatch(char c, StringLib.StringPointer p, StringLib.StringPointer ep) {
      switch (p.getChar()) {
         case '%':
            return matchClass(p.getChar(1), c);
         case '.':
            return true;
         case '[':
            StringLib.StringPointer sp = ep.getClone();
            sp.postIncrString(-1);
            return matchBracketClass(c, p, sp);
         default:
            return p.getChar() == c;
      }
   }

   private static StringLib.StringPointer minExpand(StringLib.MatchState ms, StringLib.StringPointer ss, StringLib.StringPointer p, StringLib.StringPointer ep) {
      StringLib.StringPointer sp = ep.getClone();
      StringLib.StringPointer s = ss.getClone();
      sp.postIncrString(1);

      while (true) {
         StringLib.StringPointer res = match(ms, s, sp);
         if (res != null) {
            return res;
         }

         if (s.getIndex() >= ms.endIndex || !singleMatch(s.getChar(), p, ep)) {
            return null;
         }

         s.postIncrString(1);
      }
   }

   private static StringLib.StringPointer maxExpand(StringLib.MatchState ms, StringLib.StringPointer s, StringLib.StringPointer p, StringLib.StringPointer ep) {
      int i = 0;

      while (s.getIndex() + i < ms.endIndex && singleMatch(s.getChar(i), p, ep)) {
         i++;
      }

      while (i >= 0) {
         StringLib.StringPointer sp1 = s.getClone();
         sp1.postIncrString(i);
         StringLib.StringPointer sp2 = ep.getClone();
         sp2.postIncrString(1);
         StringLib.StringPointer res = match(ms, sp1, sp2);
         if (res != null) {
            return res;
         }

         i--;
      }

      return null;
   }

   private static boolean matchBracketClass(char c, StringLib.StringPointer pp, StringLib.StringPointer ecc) {
      StringLib.StringPointer p = pp.getClone();
      StringLib.StringPointer ec = ecc.getClone();
      boolean sig = true;
      if (p.getChar(1) == '^') {
         sig = false;
         p.postIncrString(1);
      }

      while (p.preIncrStringI(1) < ec.getIndex()) {
         if (p.getChar() == '%') {
            p.postIncrString(1);
            if (matchClass(p.getChar(), c)) {
               return sig;
            }
         } else if (p.getChar(1) == '-' && p.getIndex() + 2 < ec.getIndex()) {
            p.postIncrString(2);
            if (p.getChar(-2) <= c && c <= p.getChar()) {
               return sig;
            }
         } else if (p.getChar() == c) {
            return sig;
         }
      }

      return !sig;
   }

   private static StringLib.StringPointer match(StringLib.MatchState ms, StringLib.StringPointer ss, StringLib.StringPointer pp) {
      StringLib.StringPointer s = ss.getClone();
      StringLib.StringPointer p = pp.getClone();
      boolean isContinue = true;
      boolean isDefault = false;

      while (isContinue) {
         isContinue = false;
         isDefault = false;
         label87:
         switch (p.getChar()) {
            case '\u0000':
               return s;
            case '$':
               if (p.getChar(1) == 0) {
                  return s.getIndex() == ms.endIndex ? s : null;
               }
            default:
               isDefault = true;
               break;
            case '%':
               switch (p.getChar(1)) {
                  case 'b':
                     StringLib.StringPointer p1x = p.getClone();
                     p1x.postIncrString(2);
                     s = matchBalance(ms, s, p1x);
                     if (s == null) {
                        return null;
                     }

                     p.postIncrString(4);
                     isContinue = true;
                     continue;
                  case 'f':
                     p.postIncrString(2);
                     KahluaUtil.luaAssert(p.getChar() == '[', "missing '[' after '%%f' in pattern");
                     StringLib.StringPointer ep = classEnd(p);
                     char previous = s.getIndex() == ms.srcInit.getIndex() ? '\u0000' : s.getChar(-1);
                     StringLib.StringPointer ep1 = ep.getClone();
                     ep1.postIncrString(-1);
                     if (!matchBracketClass(previous, p, ep1) && matchBracketClass(s.getChar(), p, ep1)) {
                        p = ep;
                        isContinue = true;
                        continue;
                     }

                     return null;
                  default:
                     if (Character.isDigit(p.getChar(1))) {
                        s = matchCapture(ms, s, p.getChar(1));
                        if (s == null) {
                           return null;
                        }

                        p.postIncrString(2);
                        isContinue = true;
                        continue;
                     }

                     isDefault = true;
                     break label87;
               }
            case '(': {
               StringLib.StringPointer p1 = p.getClone();
               if (p.getChar(1) == ')') {
                  p1.postIncrString(2);
                  return startCapture(ms, s, p1, -2);
               }

               p1.postIncrString(1);
               return startCapture(ms, s, p1, -1);
            }
            case ')': {
               StringLib.StringPointer p1 = p.getClone();
               p1.postIncrString(1);
               return endCapture(ms, s, p1);
            }
         }

         if (isDefault) {
            StringLib.StringPointer ep = classEnd(p);
            boolean m = s.getIndex() < ms.endIndex && singleMatch(s.getChar(), p, ep);
            switch (ep.getChar()) {
               case '*':
                  return maxExpand(ms, s, p, ep);
               case '+': {
                  StringLib.StringPointer s1 = s.getClone();
                  s1.postIncrString(1);
                  return m ? maxExpand(ms, s1, p, ep) : null;
               }
               case '-':
                  return minExpand(ms, s, p, ep);
               case '?': {
                  StringLib.StringPointer s1 = s.getClone();
                  s1.postIncrString(1);
                  StringLib.StringPointer ep1 = ep.getClone();
                  ep1.postIncrString(1);
                  StringLib.StringPointer res;
                  if (m && (res = match(ms, s1, ep1)) != null) {
                     return res;
                  }

                  p = ep;
                  p.postIncrString(1);
                  isContinue = true;
                  break;
               }
               default:
                  if (!m) {
                     return null;
                  }

                  s.postIncrString(1);
                  p = ep;
                  isContinue = true;
            }
         }
      }

      return null;
   }

   private static boolean matchClass(char classIdentifier, char c) {
      char lowerClassIdentifier = Character.toLowerCase(classIdentifier);
      boolean res;
      switch (lowerClassIdentifier) {
         case 'a':
            res = Character.isLowerCase(c) || Character.isUpperCase(c);
            break;
         case 'b':
         case 'e':
         case 'f':
         case 'g':
         case 'h':
         case 'i':
         case 'j':
         case 'k':
         case 'm':
         case 'n':
         case 'o':
         case 'q':
         case 'r':
         case 't':
         case 'v':
         case 'y':
         default:
            return classIdentifier == c;
         case 'c':
            res = isControl(c);
            break;
         case 'd':
            res = Character.isDigit(c);
            break;
         case 'l':
            res = Character.isLowerCase(c);
            break;
         case 'p':
            res = isPunct(c);
            break;
         case 's':
            res = isSpace(c);
            break;
         case 'u':
            res = Character.isUpperCase(c);
            break;
         case 'w':
            res = Character.isLowerCase(c) || Character.isUpperCase(c) || Character.isDigit(c);
            break;
         case 'x':
            res = isHex(c);
            break;
         case 'z':
            res = c == 0;
      }

      return lowerClassIdentifier == classIdentifier == res;
   }

   private static boolean isPunct(char c) {
      return c >= '!' && c <= '/' || c >= ':' && c <= '@' || c >= '[' && c <= '`' || c >= '{' && c <= '~';
   }

   private static boolean isSpace(char c) {
      return c >= '\t' && c <= '\r' || c == ' ';
   }

   private static boolean isControl(char c) {
      return c >= 0 && c <= 31 || c == 127;
   }

   private static boolean isHex(char c) {
      return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
   }

   private static int gsub(LuaCallFrame cf, int nargs) {
      if (pzopt.LuaPattern.noPool()) { // pzopt: luaPatternNoPool, the pooled matcher state only for the stock matcher
         try { // pzopt: luaPatternNoPool
            int pzoptR = gsub(null, cf, nargs); // pzopt: luaPatternNoPool
            if (pzoptR != pzopt.LuaPattern.NEED_POOL) { // pzopt: luaPatternNoPool
               return pzoptR; // pzopt: luaPatternNoPool
            } // pzopt: luaPatternNoPool
         } catch (Exception pzoptE) { // pzopt: luaPatternNoPool
            throw new RuntimeException(pzoptE); // pzopt: luaPatternNoPool, as the stock catch below
         } // pzopt: luaPatternNoPool
      } // pzopt: luaPatternNoPool
      try (StringLib.Allocations allocations = (StringLib.Allocations)s_AllocationsPool.alloc()) {
         return gsub(allocations, cf, nargs);
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   private static int gsub(StringLib.Allocations allocations, LuaCallFrame cf, int nargs) {
      String srcTemp = KahluaUtil.getStringArg(cf, 1, names[9]);
      String pTemp = KahluaUtil.getStringArg(cf, 2, names[9]);
      Object repl = KahluaUtil.getArg(cf, 3, names[9]);
      String tmp = KahluaUtil.rawTostring(repl);
      if (tmp != null) {
         repl = tmp;
      }

      Double num = KahluaUtil.getOptionalNumberArg(cf, 4);
      int maxSubstitutions = num == null ? Integer.MAX_VALUE : num.intValue();
      if (pzopt.LuaPattern.applies(pTemp)) { // pzopt: luaPatternFast, the same matcher on int positions (no back-reference)
         if (!pzopt.LuaPattern.replOk(repl)) { // pzopt: luaPatternFast, the repl check below
            KahluaUtil.fail("string/function/table expected, got " + repl); // pzopt: luaPatternFast
         } // pzopt: luaPatternFast
         return pzopt.LuaPattern.gsub(cf, srcTemp, pTemp, repl, maxSubstitutions); // pzopt: luaPatternFast
      } // pzopt: luaPatternFast
      if (allocations == null) { // pzopt: luaPatternNoPool
         return pzopt.LuaPattern.NEED_POOL; // pzopt: luaPatternNoPool, gsub runs this again with a pool entry
      } // pzopt: luaPatternNoPool
      StringLib.StringPointer pattern = allocations.allocStringPointer().set(pTemp);
      StringLib.StringPointer src = allocations.allocStringPointer().set(srcTemp);
      boolean anchor = false;
      if (pattern.getChar() == '^') {
         anchor = true;
         pattern.postIncrString(1);
      }

      if (!(repl instanceof Double)
         && !(repl instanceof String)
         && !(repl instanceof LuaClosure)
         && !(repl instanceof JavaFunction)
         && !(repl instanceof KahluaTable)) {
         KahluaUtil.fail("string/function/table expected, got " + repl);
      }

      StringLib.MatchState ms = allocations.allocMatchState().set(cf, src.getClone(), src.length());
      int n = 0;
      StringBuilder b = new StringBuilder();

      while (n < maxSubstitutions) {
         ms.level = 0;
         StringLib.StringPointer e = match(ms, src, pattern);
         if (e != null) {
            n++;
            addValue(ms, repl, b, src, e);
         }

         if (e != null && e.getIndex() > src.getIndex()) {
            src.setIndex(e.getIndex());
         } else {
            if (src.getIndex() >= ms.endIndex) {
               break;
            }

            b.append(src.postIncrString(1));
         }

         if (anchor) {
            break;
         }
      }

      return cf.push(b.append(src.getString()).toString(), (double)n);
   }

   private static int trim(LuaCallFrame cf, int nargs) {
      String srcTemp = KahluaUtil.getStringArg(cf, 1, names[10]);
      return cf.push(srcTemp.trim());
   }

   private static int split(LuaCallFrame cf, int nargs) {
      String srcTemp = KahluaUtil.getStringArg(cf, 1, names[11]);
      String srcTemp2 = KahluaUtil.getStringArg(cf, 2, names[11]);
      String[] spl = srcTemp.split(srcTemp2);
      KahluaTable t = LuaManager.platform.newTable();

      for (int n = 0; n < spl.length; n++) {
         t.rawset(n + 1, spl[n]);
      }

      return cf.push(t);
   }

   private static int sort(LuaCallFrame cf, int nargs) {
      String srcTemp = KahluaUtil.getStringArg(cf, 1, names[12]);
      String srcTemp2 = KahluaUtil.getStringArg(cf, 2, names[12]);
      return cf.push(srcTemp.compareTo(srcTemp2) > 0);
   }

   private static void addValue(StringLib.MatchState ms, Object repl, StringBuilder b, StringLib.StringPointer src, StringLib.StringPointer e) {
      String replString = KahluaUtil.rawTostring(repl);
      if (replString != null) {
         b.append(addString(ms, replString, src, e));
      } else {
         Object captures = ms.getCapture(0);
         String match;
         if (captures != null) {
            match = KahluaUtil.rawTostring(captures);
         } else {
            match = src.getStringSubString(e.getIndex() - src.getIndex());
         }

         Object res = null;
         if (repl instanceof KahluaTable kahluaTable) {
            res = kahluaTable.rawget(match);
         } else {
            res = ms.callFrame.getThread().call(repl, match, null, null);
         }

         if (res == null) {
            res = match;
         }

         b.append(KahluaUtil.rawTostring(res));
      }
   }

   private static String addString(StringLib.MatchState ms, String repl, StringLib.StringPointer s, StringLib.StringPointer e) {
      StringLib.StringPointer replStr = ms.allocations.allocStringPointer().set(repl);
      StringBuilder buf = new StringBuilder();

      for (int i = 0; i < repl.length(); i++) {
         char c = replStr.getChar(i);
         if (c != '%') {
            buf.append(c);
         } else {
            c = replStr.getChar(++i);
            if (!Character.isDigit(c)) {
               buf.append(c);
            } else if (c == '0') {
               int len = s.getStringLength() - e.length();
               buf.append(s.getStringSubString(len));
            } else {
               Object o = ms.getCapture(c - '1');
               if (o == null) {
                  throw new KahluaException("invalid capture index");
               }

               buf.append(KahluaUtil.tostring(o, null));
            }
         }
      }

      return buf.toString();
   }

   static {
      String s = "^$*+?.([%-";

      for (int i = 0; i < "^$*+?.([%-".length(); i++) {
         SPECIALS["^$*+?.([%-".charAt(i)] = true;
      }

      STRING_CLASS = "".getClass();
      names = new String[14];
      names[0] = "sub";
      names[1] = "char";
      names[2] = "byte";
      names[3] = "lower";
      names[4] = "upper";
      names[5] = "reverse";
      names[6] = "format";
      names[7] = "find";
      names[8] = "match";
      names[9] = "gsub";
      names[10] = "trim";
      names[11] = "split";
      names[12] = "sort";
      names[13] = "contains";
      functions = new StringLib[14];

      for (int i = 0; i < 14; i++) {
         functions[i] = new StringLib(i);
      }

      digits = new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
      s_MatchStatePool = new ObjectPool(StringLib.MatchState::new, "StringLib.s_MatchStatePool", 64);
      s_StringPointerPool = new ObjectPool(StringLib.StringPointer::new, "StringLib.s_StringPointerPool", 65536);
      s_AllocationsPool = new ObjectPool(StringLib.Allocations::new, "StringLib.s_AllocationsPool", 16);
   }

   private static final class Allocations implements AutoCloseable {
      private static final int TRIM_THRESHOLD = 1024;
      final ArrayList<StringLib.MatchState> matchState = new ArrayList<>();
      final ArrayList<StringLib.StringPointer> stringPointer = new ArrayList<>();

      StringLib.MatchState allocMatchState() {
         StringLib.MatchState matchState1 = (StringLib.MatchState)StringLib.s_MatchStatePool.alloc();
         matchState1.allocations = this;
         this.matchState.add(matchState1);
         return matchState1;
      }

      StringLib.StringPointer allocStringPointer() {
         if (pzopt.LuaStringFast.on()) { // pzopt: luaStringFast, a plain allocation (a TLAB bump) instead of the synchronized pool and the release list
            StringLib.StringPointer pzoptP = new StringLib.StringPointer(); // pzopt: luaStringFast
            pzoptP.allocations = this; // pzopt: luaStringFast
            return pzoptP; // pzopt: luaStringFast
         } // pzopt: luaStringFast
         StringLib.StringPointer stringPointer1 = (StringLib.StringPointer)StringLib.s_StringPointerPool.alloc();
         stringPointer1.allocations = this;
         this.stringPointer.add(stringPointer1);
         return stringPointer1;
      }

      void release() {
         boolean oversized = this.matchState.size() > 1024 || this.stringPointer.size() > 1024;
         StringLib.s_MatchStatePool.releaseAll(this.matchState);
         this.matchState.clear();
         StringLib.s_StringPointerPool.releaseAll(this.stringPointer);
         this.stringPointer.clear();
         if (oversized) {
            this.matchState.trimToSize();
            this.stringPointer.trimToSize();
         }

         StringLib.s_AllocationsPool.release(this);
      }

      @Override
      public void close() throws Exception {
         this.release();
      }
   }

   public static class MatchState {
      StringLib.Allocations allocations;
      public LuaCallFrame callFrame;
      public StringLib.StringPointer srcInit;
      public int endIndex;
      public final StringLib.MatchState.Capture[] capture = new StringLib.MatchState.Capture[32];
      public int level;

      MatchState() {
         for (int i = 0; i < 32; i++) {
            this.capture[i] = new StringLib.MatchState.Capture();
         }
      }

      public StringLib.MatchState set(LuaCallFrame callFrame, StringLib.StringPointer srcInit, int endIndex) {
         this.callFrame = callFrame;
         this.srcInit = srcInit;
         this.endIndex = endIndex;
         return this;
      }

      public Object getCapture(int i) {
         if (i >= this.level) {
            return null;
         } else {
            return this.capture[i].len == -2
               ? (double)(this.srcInit.length() - this.capture[i].init.length() + 1)
               : this.capture[i].init.getStringSubString(this.capture[i].len);
         }
      }

      public static class Capture {
         public StringLib.StringPointer init;
         public int len;
      }
   }

   public static class StringPointer {
      StringLib.Allocations allocations;
      private String string;
      private int index;

      public StringLib.StringPointer set(String original) {
         this.string = original;
         this.index = 0;
         return this;
      }

      public StringLib.StringPointer set(String original, int index) {
         this.string = original;
         this.index = index;
         return this;
      }

      public StringLib.StringPointer getClone() {
         return this.allocations.allocStringPointer().set(this.string, this.index);
      }

      public int getIndex() {
         return this.index;
      }

      public void setIndex(int ind) {
         this.index = ind;
      }

      public String getString() {
         return this.index == 0 ? this.string : this.string.substring(this.index);
      }

      public int getStringLength() {
         return this.getStringLength(0);
      }

      public int getStringLength(int i) {
         return this.string.length() - (this.index + i);
      }

      public String getStringSubString(int len) {
         return this.string.substring(this.index, this.index + len);
      }

      public char getChar() {
         return this.getChar(0);
      }

      public char getChar(int strIndex) {
         int i = this.index + strIndex;
         return i >= this.string.length() ? '\u0000' : this.string.charAt(i);
      }

      public int length() {
         return this.string.length() - this.index;
      }

      public int postIncrStringI(int num) {
         int oldIndex = this.index;
         this.index += num;
         return oldIndex;
      }

      public int preIncrStringI(int num) {
         this.index += num;
         return this.index;
      }

      public char postIncrString(int num) {
         char c = this.getChar();
         this.index += num;
         return c;
      }

      public int compareTo(StringLib.StringPointer cmp, int len) {
         for (int i = 0; i < len; i++) {
            int val = this.getChar(i) - cmp.getChar(i);
            if (val != 0) {
               return val;
            }
         }

         return 0;
      }
   }
}
