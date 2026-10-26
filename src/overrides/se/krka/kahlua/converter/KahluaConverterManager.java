package se.krka.kahlua.converter;

import java.util.HashMap;
import java.util.Map;

public class KahluaConverterManager {
   private static final Map<Class<?>, Class<?>> PRIMITIVE_CLASS = new HashMap<>();
   private static final Map<Class, LuaToJavaConverter> LUA_NULL_MAP = new HashMap<>();
   private final Map<Class, Map<Class, LuaToJavaConverter>> luaToJava = new HashMap<>();
   private final Map<Class, Map<Class, LuaToJavaConverter>> luatoJavaCache = new HashMap<>();
   private static final JavaToLuaConverter NULL_CONVERTER = new JavaToLuaConverter<Object>() {
      public Object fromJavaToLua(Object javaObject) {
         return null;
      }

      public Class<Object> getJavaType() {
         return Object.class;
      }
   };
   private final Map<Class, JavaToLuaConverter> javaToLua = new HashMap<>();
   private final Map<Class, JavaToLuaConverter> javaToLuaCache = new HashMap<>();
   /** pzopt: luaJavaConvertCache, bumped where addJavaConverter clears javaToLuaCache (a generated Java call's cached converter is stale). */
   public int pzoptEpoch;

   public void addLuaConverter(LuaToJavaConverter converter) {
      Map<Class, LuaToJavaConverter> map = this.getOrCreate(this.luaToJava, converter.getLuaType());
      Class javaType = converter.getJavaType();
      LuaToJavaConverter current = map.get(javaType);
      if (current != null) {
         if (current instanceof MultiLuaToJavaConverter luaToJavaConverter) {
            luaToJavaConverter.add(converter);
         } else {
            MultiLuaToJavaConverter multiLuaToJavaConverter = new MultiLuaToJavaConverter(converter.getLuaType(), javaType);
            multiLuaToJavaConverter.add(current);
            multiLuaToJavaConverter.add(converter);
            map.put(javaType, multiLuaToJavaConverter);
         }
      } else {
         map.put(javaType, converter);
      }

      this.luatoJavaCache.clear();
   }

   public void addJavaConverter(JavaToLuaConverter converter) {
      Class javaType = converter.getJavaType();
      JavaToLuaConverter current = this.javaToLua.get(javaType);
      if (current != null) {
         if (current instanceof MultiJavaToLuaConverter multiJavaToLuaConverter) {
            multiJavaToLuaConverter.add(converter);
         } else {
            MultiJavaToLuaConverter multiConverter = new MultiJavaToLuaConverter(javaType);
            multiConverter.add(current);
            multiConverter.add(converter);
            this.javaToLua.put(javaType, multiConverter);
         }
      } else {
         this.javaToLua.put(javaType, converter);
      }

      this.javaToLuaCache.clear();
      this.pzoptEpoch++; // pzopt: luaJavaConvertCache
   }

   /** pzopt: luaJavaConvertCache, the converter fromJavaToLua uses for an object of this class (getJavaCache). */
   public JavaToLuaConverter pzoptJavaConverter(Class<?> clazz) { // pzopt: luaJavaConvertCache
      return this.getJavaCache(clazz); // pzopt: luaJavaConvertCache
   } // pzopt: luaJavaConvertCache

   /**
    * pzopt: luaJavaConvertCache, true when the converter answers null for every object of this class, so fromJavaToLua
    * hands such an object to Lua as it is: the null converter, or the game's KahluaArrayConverter Object converter (null
    * for anything that is not an array).
    */
   public boolean pzoptPassThrough(Class<?> clazz, JavaToLuaConverter conv) { // pzopt: luaJavaConvertCache
      return conv == NULL_CONVERTER || !clazz.isArray() && conv.getClass().getName().equals("zombie.Lua.KahluaArrayConverter$1"); // pzopt: luaJavaConvertCache
   } // pzopt: luaJavaConvertCache

   private Map<Class, LuaToJavaConverter> getOrCreate(Map<Class, Map<Class, LuaToJavaConverter>> luaToJava2, Class luaType) {
      Map<Class, LuaToJavaConverter> map = luaToJava2.get(luaType);
      if (map == null) {
         map = new HashMap<>();
         luaToJava2.put(luaType, map);
      }

      return map;
   }

   public <T> T fromLuaToJava(Object luaObject, Class<T> javaClass) {
      if (luaObject == null) {
         return null;
      }

      if (javaClass.isPrimitive()) {
         javaClass = (Class<T>)PRIMITIVE_CLASS.get(javaClass);
      }

      if (javaClass.isInstance(luaObject)) {
         return (T)luaObject;
      }

      Class<?> luaClass = luaObject.getClass();
      Map<Class, LuaToJavaConverter> map = this.getLuaCache(luaClass);

      for (Class<?> scanClass = javaClass; scanClass != null; scanClass = scanClass.getSuperclass()) {
         LuaToJavaConverter converter = map.get(scanClass);
         if (converter != null) {
            Object o = converter.fromLuaToJava(luaObject, javaClass);
            if (o != null) {
               return (T)o;
            }
         }
      }

      return this.tryInterfaces(map, javaClass, luaObject);
   }

   private <T> T tryInterfaces(Map<Class, LuaToJavaConverter> map, Class<T> javaClass, Object luaObject) {
      if (javaClass == null) {
         return null;
      }

      LuaToJavaConverter converter = map.get(javaClass);
      if (converter != null) {
         Object o = converter.fromLuaToJava(luaObject, javaClass);
         if (o != null) {
            return (T)o;
         }
      }

      for (Class<?> aClass : javaClass.getInterfaces()) {
         Object o = this.tryInterfaces(map, aClass, luaObject);
         if (o != null) {
            return (T)o;
         }
      }

      return this.tryInterfaces(map, (Class<T>)javaClass.getSuperclass(), luaObject);
   }

   private Map<Class, LuaToJavaConverter> createLuaCache(Class<?> luaClass) {
      HashMap<Class, LuaToJavaConverter> map = new HashMap<>();
      this.luatoJavaCache.put(luaClass, map);
      map.putAll(this.getLuaCache(luaClass.getSuperclass()));

      for (Class clazz : luaClass.getInterfaces()) {
         map.putAll(this.getLuaCache(clazz));
      }

      Map<Class, LuaToJavaConverter> directMap = this.luaToJava.get(luaClass);
      if (directMap != null) {
         map.putAll(directMap);
      }

      return map;
   }

   private Map<Class, LuaToJavaConverter> getLuaCache(Class<?> clazz) {
      if (clazz == null) {
         return LUA_NULL_MAP;
      }

      Map<Class, LuaToJavaConverter> map = this.luatoJavaCache.get(clazz);
      if (map == null) {
         map = this.createLuaCache(clazz);
      }

      return map;
   }

   public Object fromJavaToLua(Object javaObject) {
      if (javaObject == null) {
         return null;
      }

      Class clazz = javaObject.getClass();
      JavaToLuaConverter converter = this.getJavaCache(clazz);

      try {
         Object o = converter.fromJavaToLua(javaObject);
         return o == null ? javaObject : o;
      } catch (StackOverflowError e) {
         throw new RuntimeException("Could not convert " + javaObject + ": it contained recursive elements.");
      }
   }

   private JavaToLuaConverter getJavaCache(Class clazz) {
      if (clazz == null) {
         return NULL_CONVERTER;
      }

      JavaToLuaConverter converter = this.javaToLuaCache.get(clazz);
      if (converter == null) {
         converter = this.createJavaCache(clazz);
         this.javaToLuaCache.put(clazz, converter);
      }

      return converter;
   }

   private JavaToLuaConverter createJavaCache(Class javaClass) {
      JavaToLuaConverter converter = this.javaToLua.get(javaClass);
      if (converter != null) {
         return converter;
      }

      for (Class clazz : javaClass.getInterfaces()) {
         converter = this.getJavaCache(clazz);
         if (converter != NULL_CONVERTER) {
            return converter;
         }
      }

      return this.getJavaCache(javaClass.getSuperclass());
   }

   static {
      PRIMITIVE_CLASS.put(boolean.class, Boolean.class);
      PRIMITIVE_CLASS.put(byte.class, Byte.class);
      PRIMITIVE_CLASS.put(char.class, Character.class);
      PRIMITIVE_CLASS.put(short.class, short.class);
      PRIMITIVE_CLASS.put(int.class, Integer.class);
      PRIMITIVE_CLASS.put(long.class, Long.class);
      PRIMITIVE_CLASS.put(float.class, Float.class);
      PRIMITIVE_CLASS.put(double.class, Double.class);
   }
}
