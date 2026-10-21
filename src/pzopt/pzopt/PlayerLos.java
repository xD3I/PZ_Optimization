package pzopt;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Stack;

/**
 * Per-player state of the {@code IsoPlayer.updateLOS} override ({@code playerLosFast}, 2026-09-22).
 *
 * <p>Stock keeps the objects a player has spotted since the last quiet spell in a {@link Stack} ({@code lastSpotted})
 * and asks it {@code contains} for every object spotted this frame, plus once more for every zombie within a few
 * tiles: a linear walk of a synchronized {@code Vector}. The list only empties when a whole frame spots no zombie at
 * all, so in a horde it holds every zombie the player ever saw, and the walk is spotted × remembered identity
 * compares per frame — 2,400 × 2,400 with the Louisville preset's spectator view, 12 % of the game thread and
 * growing with the horde. This keeps an identity set beside the stack and answers the same question in one probe.
 *
 * <p>The stack stays the source of truth (it is exposed through {@code getLastSpotted} / {@code setLastSpotted}):
 * {@link #sync} rebuilds the set whenever the stack object or its size is not what the set last mirrored, so any
 * outside clear, add or replacement is picked up before the set is consulted, and the override adds / clears through
 * {@link #add} / {@link #clear} so both stay equal within a frame. Game thread only, like the stack. Generic so the
 * JVM-only test needs no game objects; the override uses it with {@code IsoMovingObject}.
 */
public final class PlayerLos<T> {
   private final Set<T> remembered = Collections.newSetFromMap(new IdentityHashMap<>());
   private Stack<T> mirrored;
   private int mirroredSize = -1;

   /** Makes the set mirror {@code stack} (rebuilds it when the stack was replaced or changed size behind our back). */
   public void sync(Stack<T> stack) {
      if (stack != this.mirrored || stack.size() != this.mirroredSize) {
         this.remembered.clear();
         for (int i = 0; i < stack.size(); i++) {
            this.remembered.add(stack.get(i));
         }
         this.mirrored = stack;
         this.mirroredSize = stack.size();
      }
   }

   /** Forgets what the set mirrors, so the next {@link #sync} rebuilds it (f9-los: frames of the stock path changed the stack). */
   public void invalidate() {
      this.mirrored = null;
      this.mirroredSize = -1;
   }

   /** {@code stack.contains(o)} in one hash probe; {@link #sync} must have run this frame. */
   public boolean contains(T o) {
      return this.remembered.contains(o);
   }

   /** Appends {@code o} to the stack unless it is already remembered; returns true when it was added. */
   public boolean add(Stack<T> stack, T o) {
      if (!this.remembered.add(o)) {
         return false;
      }
      stack.add(o);
      this.mirroredSize = stack.size();
      return true;
   }

   /** Empties both. */
   public void clear(Stack<T> stack) {
      stack.clear();
      this.remembered.clear();
      this.mirrored = stack;
      this.mirroredSize = 0;
   }

   /** How many objects are remembered (for the log line). */
   public int size() {
      return this.remembered.size();
   }
}
