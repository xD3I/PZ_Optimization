package zombie.input;

public final class KeyboardStateCache {
   private final Object lock = new Object();
   private int stateIndexUsing;
   private int stateIndexPolling = 1;
   private final KeyboardState[] states = new KeyboardState[]{new KeyboardState(), new KeyboardState()};
   private final ThreadLocal<CurrentEvent> currentEvent = ThreadLocal.withInitial(CurrentEvent::new);

   public void poll() {
      synchronized (this.lock) {
         KeyboardState statePolling = this.getStatePolling();
         if (statePolling.wasPolled()) { // pzopt: decompiler fix, the jar returns from inside the lock (an extra monitorexit)
            return; // pzopt: decompiler fix
         } // pzopt: decompiler fix

         statePolling.poll(); // pzopt: decompiler fix
      }
   }

   public void pzoptRepoll() { // pzopt: late input latch (pzopt.InputLatch): poll again although this state was polled
      synchronized (this.lock) { // pzopt
         KeyboardState statePolling = this.getStatePolling(); // pzopt
         if (!statePolling.wasPolled()) { // pzopt
            statePolling.poll(); // pzopt
         } else { // pzopt
            statePolling.pzoptRepoll(this.getState()); // pzopt
         } // pzopt
      } // pzopt
   } // pzopt

   public void pzoptKeyEvent(int key, int action) {
      synchronized (this.lock) {
         KeyboardState polling = this.getStatePolling();
         polling.getEventQueue().addKeyEvent(key, action);
         polling.pzoptKeyEvent(key, action, this.getState());
      }
   }

   public void pzoptCharEvent(char value) {
      synchronized (this.lock) {
         this.getStatePolling().getEventQueue().addCharEvent(value);
      }
   }

   public void pzoptFocusLost() {
      synchronized (this.lock) {
         this.getStatePolling().pzoptFocusLost();
      }
   }

   /** pzopt: Input.poll discards this queue when no text entry owns it; serialize that with swap. */
   public void pzoptPollDevices() {
      synchronized (this.lock) {
         pzopt.VirtualPad.poll();
         this.poll();
      }
   }
   /** Advance and snapshot one event together so the owner cannot overwrite it before the API getters run. */
   public boolean pzoptNextEvent() {
      synchronized (this.lock) {
         org.lwjglx.input.KeyEventQueue queue = this.getState().getEventQueue();
         if (!queue.next()) return false;
         CurrentEvent event = this.currentEvent.get();
         event.key = queue.getEventKey();
         event.character = queue.getEventCharacter();
         event.keyState = queue.getEventKeyState();
         event.nanoseconds = queue.getEventNanoseconds();
         return true;
      }
   }

   public int pzoptEventKey() {
      synchronized (this.lock) { return this.currentEvent.get().key; }
   }

   public char pzoptEventCharacter() {
      synchronized (this.lock) { return this.currentEvent.get().character; }
   }

   public boolean pzoptEventKeyState() {
      synchronized (this.lock) { return this.currentEvent.get().keyState; }
   }

   public long pzoptEventNanoseconds() {
      synchronized (this.lock) { return this.currentEvent.get().nanoseconds; }
   }


   public void swap() {
      synchronized (this.lock) {
         if (!this.getStatePolling().wasPolled()) { // pzopt: decompiler fix, early return inside the lock as in the jar
            return; // pzopt: decompiler fix
         } // pzopt: decompiler fix

         this.stateIndexUsing = this.stateIndexPolling; // pzopt: decompiler fix
         this.stateIndexPolling = this.stateIndexPolling == 1 ? 0 : 1; // pzopt: decompiler fix
         this.getStatePolling().set(this.getState()); // pzopt: decompiler fix
         this.getStatePolling().reset(); // pzopt: decompiler fix
         pzopt.InputThread.requestSample(); // pzopt: refresh the now-free producer state, not on an 8k mouse cadence
      }
   }

   public KeyboardState getState() {
      synchronized (this.lock) {
         return this.states[this.stateIndexUsing];
      }
   }

   public KeyboardState getStatePolling() {
      synchronized (this.lock) {
         return this.states[this.stateIndexPolling];
      }
   }
   private static final class CurrentEvent {
      int key;
      char character;
      boolean keyState;
      long nanoseconds;
   }

}
