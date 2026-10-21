package pzopt;

import java.util.ArrayList;

import zombie.iso.Vector2;

/**
 * Per-thread stand-ins for the game's static scratch objects on the frame workers (zombie pass 2026-10-13, the static-state
 * audit after the vehicle-hit crash).
 *
 * <p>The game writes a static {@code Vector2} / list, then reads it back a few lines later; with zombie updates on the
 * {@link FrameBatch} workers two threads interleave there and one reads the other's value (or an {@code ArrayList} breaks).
 * A call site passes the static it used to touch: on a {@link FrameBatch.Worker} it gets this thread's own object for that
 * slot instead, anywhere else the static itself, so the game thread keeps stock's object and stock's carried-over state.
 * Same idiom as {@link UpdateBatch#tempoScratch()}, for the sites outside {@code IsoGameCharacter}.
 */
public final class WorkerScratch {
   private WorkerScratch() {
   }

   public static final int ZOMBIE_TEMPO = 0, ZOMBIE_TEMPO2 = 1, ZOMBIE_LAST_POSITION = 2, ZOMBIE_CURRENT_POSITION = 3, MOVING_TEMPO = 4;
   private static final int VECTORS = 5;

   private static final class Slots {
      final Vector2[] v = new Vector2[VECTORS];
      final ArrayList<Object> bodies = new ArrayList<>();

      Slots() {
         for (int i = 0; i < VECTORS; i++) {
            this.v[i] = new Vector2();
         }
      }
   }

   private static final Tl<Slots> SLOTS = new Tl<>(Slots::new);

   /** The static vector on any thread but a frame worker; there, this thread's vector for {@code slot}. */
   public static Vector2 v2(Vector2 shared, int slot) {
      return Thread.currentThread() instanceof FrameBatch.Worker ? SLOTS.get().v[slot] : shared;
   }

   /** The static corpse list of IsoZombie.updateSearchForCorpse on any thread but a frame worker; there, this thread's own. */
   @SuppressWarnings("unchecked")
   public static <T> ArrayList<T> bodies(ArrayList<T> shared) {
      return Thread.currentThread() instanceof FrameBatch.Worker ? (ArrayList<T>)(ArrayList<?>)SLOTS.get().bodies : shared;
   }
}
