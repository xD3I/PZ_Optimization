package pzopt;

/**
 * The postupdate stream (zombie redesign pass, 2026-10-13, f8-zomb2): the FrameBatch stream of ActionEval's transition
 * evaluations published during the bucket loop ({@code postupdateFuse}). Built to also carry the movement computes as its first
 * tasks ({@code moves}, the measured and dropped postupdateMoveStream: the loop then waited for them as long as the
 * synchronous batch had taken, run zomb2-ab6 / zomb2-prof2); {@code moves} is 0 now.
 */
public final class PuStream {
   private PuStream() {
   }

   private static boolean open; // game thread: this frame's stream is on the workers
   private static Object token; // f8-streamfix: this frame's stream batch; publish / seal / isOpen act on it alone
   private static int moves;
   private static int evals;

   /** Game thread: open the stream with {@code moveCount} movement tasks (0 when ActionEval opens it). */
   static void open(int moveCount) {
      moves = moveCount;
      evals = 0;
      token = FrameBatch.runStream(PuStream::task, PuStream::done);
      open = true;
      FrameBatch.publish(token, moves);
   }

   /** Game thread: true while this frame's stream takes tasks. */
   static boolean isOpen() {
      if (open && !FrameBatch.streamOpen(token)) {
         open = false; // another batch user's join sealed it (f8-streamfix: this stream, not whichever batch is pending now)
         token = null;
      }
      return open;
   }

   /** Game thread: ActionEval's evaluation count published so far. */
   static void publishEvals(int count) {
      evals = count;
      FrameBatch.publish(token, moves + count);
   }

   /** Game thread: the final size; the stream stays pending until the next FrameBatch join. */
   static void seal() {
      if (open) {
         FrameBatch.seal(token, moves + evals);
         open = false;
         token = null;
      }
   }

   /** Game thread, the end of the frame's postupdate users: nothing more is published. */
   static void close() {
      seal();
      moves = 0;
      evals = 0;
   }

   private static void task(int j) {
      ActionEval.streamTask(j - moves);
   }

   private static int failuresLogged;

   private static void done(Throwable t) {
      if (t != null) {
         Log.warn("postupdate stream: a task failed: " + t);
         if (failuresLogged++ < 3) { // f8-streamfix: the stack, so a failure names its place
            StringBuilder b = new StringBuilder("postupdate stream: stack of the failure:");
            for (StackTraceElement e : t.getStackTrace()) {
               b.append("\n    at ").append(e);
            }
            Log.warn(b.toString());
         }
      }
   }
}
