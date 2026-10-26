package pzopt;

import java.util.ArrayList;
import se.krka.kahlua.vm.KahluaTable;

/**
 * Small stand-ins for the game objects mod Lua touches on its hottest events (LoadGridsquare, OnTick): a cell of
 * squares, the objects on a square, their sprites and properties. Exposed to Lua through the game's own
 * LuaJavaClassExposer by {@link LuaVm}, so a call goes through the real LuaJavaInvoker / MethodCaller / converter path
 * (the part of a mod's cost the Lua VM work can change), while the callee itself costs next to nothing.
 */
public final class LuaVmStubs {
   private LuaVmStubs() {
   }

   public static final class Props {
      private final java.util.HashMap<String, String> map = new java.util.HashMap<>();

      public boolean Is(String flag) {
         return this.map.containsKey(flag);
      }

      public String Val(String key) {
         return this.map.get(key);
      }

      public void Set(String key, String value) {
         this.map.put(key, value);
      }
   }

   public static final class Sprite {
      private final String name;
      private final Props props = new Props();

      public Sprite(String name) {
         this.name = name;
      }

      public String getName() {
         return this.name;
      }

      public Props getProperties() {
         return this.props;
      }
   }

   public static class Obj {
      private final Sprite sprite;
      private final Square square;
      private KahluaTable modData;
      private String objectName;

      public Obj(Square square, Sprite sprite, String objectName) {
         this.square = square;
         this.sprite = sprite;
         this.objectName = objectName;
      }

      public Sprite getSprite() {
         return this.sprite;
      }

      public String getSpriteName() {
         return this.sprite == null ? null : this.sprite.getName();
      }

      public Square getSquare() {
         return this.square;
      }

      public String getObjectName() {
         return this.objectName;
      }

      public boolean hasModData() {
         return this.modData != null && !this.modData.isEmpty();
      }

      public KahluaTable getModData() {
         if (this.modData == null) {
            this.modData = LuaVm.platform().newTable();
         }
         return this.modData;
      }

      public int getType() {
         return this.objectName.length() & 7;
      }

      public float getWaterAmount() {
         return this.objectName.length() * 0.25F;
      }
   }

   public static final class Square {
      private final int x;
      private final int y;
      private final int z;
      final ArrayList<Obj> objects = new ArrayList<>();
      final ArrayList<Obj> special = new ArrayList<>();
      private KahluaTable modData;
      private Square n;
      private Square w;

      public Square(int x, int y, int z) {
         this.x = x;
         this.y = y;
         this.z = z;
      }

      public int getX() {
         return this.x;
      }

      public int getY() {
         return this.y;
      }

      public int getZ() {
         return this.z;
      }

      public ArrayList<Obj> getObjects() {
         return this.objects;
      }

      public ArrayList<Obj> getSpecialObjects() {
         return this.special;
      }

      public Square getN() {
         return this.n;
      }

      public Square getW() {
         return this.w;
      }

      void link(Square n, Square w) {
         this.n = n;
         this.w = w;
      }

      public boolean isOutside() {
         return ((this.x * 31 + this.y) & 3) != 0;
      }

      public Obj getFloor() {
         return this.objects.isEmpty() ? null : this.objects.get(0);
      }

      public KahluaTable getModData() {
         if (this.modData == null) {
            this.modData = LuaVm.platform().newTable();
         }
         return this.modData;
      }

      public boolean hasModData() {
         return this.modData != null && !this.modData.isEmpty();
      }
   }

   public static final class Cell {
      private final int w;
      private final int h;
      private final Square[] squares;

      public Cell(int w, int h) {
         this.w = w;
         this.h = h;
         this.squares = new Square[w * h];
         String[] names = {"floors_exterior_natural_01_12", "walls_exterior_house_01_4", "fixtures_sinks_01_2",
            "appliances_cooking_01_8", "furniture_storage_02_16", "vegetation_trees_01_8", "e_newgrass_1_22",
            "blends_natural_01_64", "location_shop_generic_01_20", "fencing_01_40", "industry_02_96", "street_curbs_01_5"};
         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               Square s = new Square(x, y, 0);
               int k = (x * 7 + y * 13) % names.length;
               s.objects.add(new Obj(s, new Sprite(names[k]), "Floor"));
               int extra = (x + y) % 4;
               for (int i = 0; i < extra; i++) {
                  String nm = names[(k + i * 5 + 1) % names.length];
                  Sprite sp = new Sprite(nm);
                  if ((i & 1) == 0) {
                     sp.getProperties().Set("IsMoveAble", "");
                  }
                  if (nm.startsWith("fixtures")) {
                     sp.getProperties().Set("waterAmount", "10");
                  }
                  s.objects.add(new Obj(s, sp, i == 0 ? "Thumpable" : "IsoObject"));
               }
               if ((x * y) % 9 == 0) {
                  s.special.add(new Obj(s, new Sprite("fencing_01_40"), "Curtain"));
               }
               this.squares[y * w + x] = s;
            }
         }
         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               this.squares[y * w + x].link(y > 0 ? this.squares[(y - 1) * w + x] : null, x > 0 ? this.squares[y * w + x - 1] : null);
            }
         }
      }

      public Square getGridSquare(int x, int y, int z) {
         if (x < 0 || y < 0 || x >= this.w || y >= this.h || z != 0) {
            return null;
         }
         return this.squares[y * this.w + x];
      }

      public Square getGridSquare(double x, double y, double z) {
         return this.getGridSquare((int) x, (int) y, (int) z);
      }

      public int getWidth() {
         return this.w;
      }

      public int getHeight() {
         return this.h;
      }
   }

   /** luaJavaFast cases: one of every argument / result kind, overloads, varargs, ReturnValues, exceptions. */
   public static final class JCalls {
      public int calls;

      public int addI(int a, int b) {
         this.calls++;
         return a + b;
      }

      public long mulL(long a, long b) {
         return a * b;
      }

      public float halfF(float f) {
         return f / 2;
      }

      public double sqrtD(double d) {
         return Math.sqrt(d);
      }

      public double negZero() {
         return -0.0;
      }

      public byte toB(byte b) {
         return b;
      }

      public char toC(char c) {
         return c;
      }

      public short toS(short s) {
         return s;
      }

      public boolean notZ(boolean z) {
         return !z;
      }

      public String cat(String a, String b) {
         return a + "|" + b;
      }

      public Object id(Object o) {
         return o;
      }

      public Double boxD(Double d) {
         return d;
      }

      public Integer boxI(Integer i) {
         return i;
      }

      /** A Java array result (the game's KahluaArrayConverter makes it a table) or, for n < 0, a plain object. */
      public Object arrOrObj(int n) {
         if (n < 0) {
            return new Props();
         }
         int[] a = new int[n];
         for (int i = 0; i < n; i++) {
            a[i] = i * 3;
         }
         return a;
      }

      public void boom(String why) {
         throw new IllegalStateException("boom " + why);
      }

      public void noop() {
         this.calls++;
      }

      public ArrayList<Object> list(Object a, Object b) {
         ArrayList<Object> l = new ArrayList<>();
         l.add(a);
         l.add(b);
         return l;
      }

      public String over(int i) {
         return "int " + i;
      }

      public String over(String s) {
         return "string " + s;
      }

      public String over(double a, double b) {
         return "dd " + a + " " + b;
      }

      public String over(Object o) {
         return "object " + o;
      }

      public String over(boolean z) {
         return "bool " + z;
      }

      public int count(Object... xs) {
         return xs.length;
      }

      public void two(se.krka.kahlua.integration.expose.ReturnValues rv, double a) {
         rv.push(a);
         rv.push(a * 2);
      }

      public static int sAdd(int a, int b) {
         return a + b;
      }

      public int getCalls() {
         return this.calls;
      }
   }
}
