package zombie.core.skinnedmodel.advancedanimation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import zombie.util.StringUtils;

public class AnimationVariableHandlePool {
   private static final Object s_threadLock = "AnimationVariableHandlePool.ThreadLock";
   private static final Map<String, AnimationVariableHandle> s_handlePoolMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
   private static final ArrayList<AnimationVariableHandle> s_handlePool = new ArrayList<>();
   private static volatile int globalIndexGenerator;

   public static AnimationVariableHandle getOrCreate(String name) {
      if (pzopt.LookupMemo.ON && name != null) { // pzopt: lookupMemo (f8-resid): a name asked before has its handle, never replaced
         AnimationVariableHandle h = (AnimationVariableHandle)pzopt.LookupMemo.HANDLES.get(name); // pzopt: the memo lives in pzopt (the class initializer stays stock)
         if (h != null) { // pzopt
            pzopt.LookupMemo.hit(); // pzopt
            if (pzopt.LookupMemo.checking()) { // pzopt: devLookupMemoCheck
               synchronized (s_threadLock) { // pzopt
                  pzopt.LookupMemo.check(h, getOrCreateInternal(name)); // pzopt: the name exists, so this only looks it up
               } // pzopt
            } // pzopt
            return h; // pzopt
         } // pzopt
         pzopt.LookupMemo.miss(); // pzopt
      } // pzopt
      synchronized (s_threadLock) {
         AnimationVariableHandle pzoptH = getOrCreateInternal(name); // pzopt: kept for the memo
         if (pzopt.LookupMemo.ON && name != null && pzoptH != null) { // pzopt
            pzopt.LookupMemo.HANDLES.put(name, pzoptH); // pzopt
         } // pzopt
         return pzoptH; // pzopt
      }
   }

   private static AnimationVariableHandle getOrCreateInternal(String name) {
      if (StringUtils.isNullOrWhitespace(name)) {
         return null;
      }

      String key = name.trim();
      if (!isVariableNameValid(key)) {
         return null;
      }

      AnimationVariableHandle handle = s_handlePoolMap.get(key);
      if (handle != null) {
         return handle;
      }

      AnimationVariableHandle newHandle = new AnimationVariableHandle();
      newHandle.setVariableName(key);
      newHandle.setVariableIndex(generateNewVariableIndex());
      String keyLc = key.toLowerCase();
      s_handlePoolMap.put(keyLc, newHandle);
      s_handlePool.add(newHandle);
      return newHandle;
   }

   private static boolean isVariableNameValid(String name) {
      return !StringUtils.isNullOrWhitespace(name);
   }

   private static int generateNewVariableIndex() {
      return ++globalIndexGenerator;
   }

   public static Iterable<AnimationVariableHandle> all() {
      return () -> new Iterator<AnimationVariableHandle>() {
         private int currentIndex = -1;

         @Override
         public boolean hasNext() {
            return this.currentIndex + 1 < AnimationVariableHandlePool.globalIndexGenerator;
         }

         public AnimationVariableHandle next() {
            synchronized (AnimationVariableHandlePool.s_threadLock) {
               if (!this.hasNext()) {
                  throw new NoSuchElementException();
               }

               this.currentIndex++;
               return AnimationVariableHandlePool.s_handlePool.get(this.currentIndex);
            }
         }
      };
   }
}
