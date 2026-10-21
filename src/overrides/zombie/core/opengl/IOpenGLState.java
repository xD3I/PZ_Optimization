package zombie.core.opengl;

public abstract class IOpenGLState<T extends IOpenGLState.Value> {
   protected final T currentValue = this.defaultValue();
   private boolean dirty = true;
   public final int pzoptSlot = pzopt.DrawRecorder.nextGlSlot(); // pzopt: tileRecordParallel, this state's index in a recorder's own cache

   public void set(T value) {
      if (pzopt.DrawRecorder.recording && pzopt.DrawRecorder.glSet(this, value)) { // pzopt: a recording thread checks its own cache, not the game thread's
         return; // pzopt
      } // pzopt
      if (this.dirty || !value.equals(this.currentValue)) {
         this.setCurrentValue(value);
         this.Set(value);
      }
   }

   void setCurrentValue(T value) {
      this.dirty = false;
      this.currentValue.set(value);
   }

   public void setDirty() {
      this.dirty = true;
   }

   public void restore() {
      this.dirty = false;
      this.Set(this.getCurrentValue());
   }

   T getCurrentValue() {
      return this.currentValue;
   }

   /** pzopt: hordeBatch, the cached value restore() would issue (render thread). */
   public final IOpenGLState.Value pzoptCurrent() { // pzopt
      return this.currentValue; // pzopt
   } // pzopt

   /** pzopt: a fresh value of this state's type (a recorder's own cache slot). */
   public final IOpenGLState.Value pzoptNewValue() { // pzopt
      return this.defaultValue(); // pzopt
   } // pzopt

   /** pzopt: issue the state entry for {@code value} without touching this cache (the recorder keeps its own). */
   @SuppressWarnings("unchecked") // pzopt
   public final void pzoptEmit(IOpenGLState.Value value) { // pzopt
      this.Set((T)value); // pzopt
   } // pzopt

   /** pzopt: whether the stock set would issue an entry for {@code value} against this (game-thread) cache. */
   public final boolean pzoptWouldEmit(IOpenGLState.Value value) { // pzopt
      return this.dirty || !value.equals(this.currentValue); // pzopt
   } // pzopt

   /** pzopt: take {@code value} as the current one, as the stock set does after issuing it. */
   @SuppressWarnings("unchecked") // pzopt
   public final void pzoptAdopt(IOpenGLState.Value value) { // pzopt
      this.setCurrentValue((T)value); // pzopt
   } // pzopt

   /** pzopt: the cache's state for the draw-list check: the current value (a copy goes into {@code into}) and whether it is dirty. */
   public final boolean pzoptSnapshot(IOpenGLState.Value into) { // pzopt
      into.set(this.currentValue); // pzopt
      return this.dirty; // pzopt
   } // pzopt

   /** pzopt: put back a {@link #pzoptSnapshot} (the draw-list check rolls the stock pass back). */
   public final void pzoptRestore(IOpenGLState.Value from, boolean wasDirty) { // pzopt
      this.currentValue.set(from); // pzopt
      this.dirty = wasDirty; // pzopt
   } // pzopt

   abstract T defaultValue();

   abstract void Set(T var1);

   public interface Value {
      IOpenGLState.Value set(IOpenGLState.Value var1);
   }
}
