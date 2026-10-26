package se.krka.kahlua.vm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class Prototype {
   public int[] code;
   public Object[] constants;
   public Prototype[] prototypes;
   public int numParams;
   public boolean isVararg;
   public String name;
   public int[] lines;
   public int numUpvalues;
   public int maxStacksize;
   public String file;
   public String filename;
   public String[] locvars;
   public int[] locvarlines;
   /** pzopt: luaJit, entries counted by the interpreter until the function is queued for the compiler. */
   public int pzoptJitCount;
   /** pzopt: luaJit, the compiled code (null: the interpreter runs it); written once by pzopt.LuaJit. */
   public volatile pzopt.LuaJitCode pzoptJit;
   /** pzopt: luaJit, 0 cold, 1 queued, 2 compiled, 3 not compilable. */
   public volatile int pzoptJitState;
   public long pzoptJitWindow; // pzopt: luaJitRateMs, System.nanoTime when the current count of entries started (0: not yet)
   public int pzoptJitLoops; // pzopt: luaJitOsr, loop back-edges the interpreter ran before the function was queued
   public String pzoptJitWhy; // pzopt: luaJit, why the compiler refused it (logged with the hot interpreted functions)
   public int pzoptDualSeen; // pzopt: devLuaJitDual, compiled entries seen by the in-game differential rig

   public Prototype() {
   }

   public Prototype(DataInputStream in, boolean littleEndian, String parentName, int size_t) throws IOException {
      this.name = readLuaString(in, size_t, littleEndian);
      if (this.name == null) {
         this.name = parentName;
      }

      if (this.name != null) {
         this.name = this.name.intern();
      }

      in.readInt();
      in.readInt();
      this.numUpvalues = in.read();
      this.numParams = in.read();
      int isVararg = in.read();
      this.isVararg = (isVararg & 2) != 0;
      this.maxStacksize = in.read();
      int codeLen = toInt(in.readInt(), littleEndian);
      this.code = new int[codeLen];

      for (int i = 0; i < codeLen; i++) {
         int op = toInt(in.readInt(), littleEndian);
         this.code[i] = op;
      }

      int constantsLen = toInt(in.readInt(), littleEndian);
      this.constants = new Object[constantsLen];

      for (int i = 0; i < constantsLen; i++) {
         Object o = null;
         int type = in.read();

         switch (type) {
            case 0:
               break;
            case 1: {
               int b = in.read();
               o = b == 0 ? Boolean.FALSE : Boolean.TRUE;
               break;
            }
            case 2:
            default:
               throw new IOException("unknown constant type: " + type);
            case 3: {
               long bits = in.readLong();
               if (littleEndian) {
                  bits = rev(bits);
               }

               o = KahluaUtil.toDouble(Double.longBitsToDouble(bits));
               break;
            }
            case 4:
               o = readLuaString(in, size_t, littleEndian);
         }

         this.constants[i] = o;
      }

      int prototypesLen = toInt(in.readInt(), littleEndian);
      this.prototypes = new Prototype[prototypesLen];

      for (int i = 0; i < prototypesLen; i++) {
         this.prototypes[i] = new Prototype(in, littleEndian, this.name, size_t);
      }

      int tmp = toInt(in.readInt(), littleEndian);
      this.lines = new int[tmp];

      for (int i = 0; i < tmp; i++) {
         int tmp2 = toInt(in.readInt(), littleEndian);
         this.lines[i] = tmp2;
      }

      tmp = toInt(in.readInt(), littleEndian);

      for (int i = 0; i < tmp; i++) {
         readLuaString(in, size_t, littleEndian);
         in.readInt();
         in.readInt();
      }

      tmp = toInt(in.readInt(), littleEndian);

      for (int i = 0; i < tmp; i++) {
         readLuaString(in, size_t, littleEndian);
      }
   }

   @Override
   public String toString() {
      return this.name;
   }

   private static String readLuaString(DataInputStream in, int size_t, boolean littleEndian) throws IOException {
      long len = 0L;
      if (size_t == 4) {
         int i = in.readInt();
         len = toInt(i, littleEndian);
      } else if (size_t == 8) {
         len = toLong(in.readLong(), littleEndian);
      } else {
         loadAssert(false, "Bad string size");
      }

      if (len == 0L) {
         return null;
      }

      len--;
      loadAssert(len < 65536L, "Too long string:" + len);
      int iLen = (int)len;
      byte[] stringData = new byte[3 + iLen];
      stringData[0] = (byte)(iLen >> 8 & 0xFF);
      stringData[1] = (byte)(iLen & 0xFF);
      in.readFully(stringData, 2, iLen + 1);
      loadAssert(stringData[2 + iLen] == 0, "String loading");
      DataInputStream dis = new DataInputStream(new ByteArrayInputStream(stringData));
      String s = dis.readUTF();
      dis.close();
      return s.intern();
   }

   public static int rev(int v) {
      int a = v >>> 24 & 0xFF;
      int b = v >>> 16 & 0xFF;
      int c = v >>> 8 & 0xFF;
      int d = v & 0xFF;
      return d << 24 | c << 16 | b << 8 | a;
   }

   public static long rev(long v) {
      long a = v >>> 56 & 255L;
      long b = v >>> 48 & 255L;
      long c = v >>> 40 & 255L;
      long d = v >>> 32 & 255L;
      long e = v >>> 24 & 255L;
      long f = v >>> 16 & 255L;
      long g = v >>> 8 & 255L;
      long h = v & 255L;
      return h << 56 | g << 48 | f << 40 | e << 32 | d << 24 | c << 16 | b << 8 | a;
   }

   public static int toInt(int bits, boolean littleEndian) {
      return littleEndian ? rev(bits) : bits;
   }

   public static long toLong(long bits, boolean littleEndian) {
      return littleEndian ? rev(bits) : bits;
   }

   public static LuaClosure loadByteCode(DataInputStream in, KahluaTable env) throws IOException {
      int tmp = in.read();
      loadAssert(tmp == 27, "Signature 1");
      tmp = in.read();
      loadAssert(tmp == 76, "Signature 2");
      tmp = in.read();
      loadAssert(tmp == 117, "Signature 3");
      tmp = in.read();
      loadAssert(tmp == 97, "Signature 4");
      tmp = in.read();
      loadAssert(tmp == 81, "Version");
      tmp = in.read();
      loadAssert(tmp == 0, "Format");
      boolean littleEndian = in.read() == 1;
      tmp = in.read();
      loadAssert(tmp == 4, "Size int");
      int size_t = in.read();
      loadAssert(size_t == 4 || size_t == 8, "Size t");
      tmp = in.read();
      loadAssert(tmp == 4, "Size instr");
      tmp = in.read();
      loadAssert(tmp == 8, "Size number");
      tmp = in.read();
      loadAssert(tmp == 0, "Integral");
      Prototype mainPrototype = new Prototype(in, littleEndian, null, size_t);
      return new LuaClosure(mainPrototype, env);
   }

   private static void loadAssert(boolean c, String message) throws IOException {
      if (!c) {
         throw new IOException("Could not load bytecode:" + message);
      }
   }

   public static LuaClosure loadByteCode(InputStream in, KahluaTable env) throws IOException {
      if (!(in instanceof DataInputStream)) {
         in = new DataInputStream(in);
      }

      return loadByteCode((DataInputStream)in, env);
   }

   public void dump(OutputStream os) throws IOException {
      DataOutputStream dos;
      if (os instanceof DataOutputStream dataOutputStream) {
         dos = dataOutputStream;
      } else {
         dos = new DataOutputStream(os);
      }

      dos.write(27);
      dos.write(76);
      dos.write(117);
      dos.write(97);
      dos.write(81);
      dos.write(0);
      dos.write(0);
      dos.write(4);
      dos.write(4);
      dos.write(4);
      dos.write(8);
      dos.write(0);
      this.dumpPrototype(dos);
   }

   private void dumpPrototype(DataOutputStream dos) throws IOException {
      dumpString(this.name, dos);
      dos.writeInt(0);
      dos.writeInt(0);
      dos.write(this.numUpvalues);
      dos.write(this.numParams);
      dos.write(this.isVararg ? 2 : 0);
      dos.write(this.maxStacksize);
      int codeLen = this.code.length;
      dos.writeInt(codeLen);

      for (int i = 0; i < codeLen; i++) {
         dos.writeInt(this.code[i]);
      }

      int constantsLen = this.constants.length;
      dos.writeInt(constantsLen);

      for (int i = 0; i < constantsLen; i++) {
         Object o = this.constants[i];
         if (o == null) {
            dos.write(0);
         } else if (o instanceof Boolean b) {
            dos.write(1);
            dos.write(b ? 1 : 0);
         } else if (o instanceof Double d) {
            dos.write(3);
            dos.writeLong(Double.doubleToLongBits(d));
         } else {
            if (!(o instanceof String s)) {
               throw new RuntimeException("Bad type in constant pool");
            }

            dos.write(4);
            dumpString(s, dos);
         }
      }

      int prototypesLen = this.prototypes.length;
      dos.writeInt(prototypesLen);

      for (int i = 0; i < prototypesLen; i++) {
         this.prototypes[i].dumpPrototype(dos);
      }

      int linesLen = this.lines.length;
      dos.writeInt(linesLen);

      for (int i = 0; i < linesLen; i++) {
         dos.writeInt(this.lines[i]);
      }

      dos.writeInt(0);
      dos.writeInt(0);
   }

   private static void dumpString(String name, DataOutputStream dos) throws IOException {
      if (name == null) {
         dos.writeShort(0);
      } else {
         ByteArrayOutputStream baos = new ByteArrayOutputStream();
         new DataOutputStream(baos).writeUTF(name);
         byte[] bytes = baos.toByteArray();
         int numBytes = bytes.length - 2;
         dos.writeInt(numBytes + 1);
         dos.write(bytes, 2, numBytes);
         dos.write(0);
      }
   }
}
