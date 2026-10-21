package zombie.core.skinnedmodel.animation;

import java.util.ArrayList;
import java.util.List;
import zombie.util.list.PZArrayUtil;

public final class AnimationMultiTrack {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy (quiet: loaded before the logger)
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.core.skinnedmodel.animation.AnimationMultiTrack");
   }

   private final ArrayList<AnimationTrack> tracks = new ArrayList<>();
   private static final pzopt.Tl<ArrayList<AnimationTrack>> pzoptTempTracks = new pzopt.Tl<>(ArrayList::new); // pzopt: animatorParallel, was one static list

   public void addTrack(AnimationTrack track) {
      this.tracks.add(track);
   }

   public void removeTrack(AnimationTrack track) {
      int indexOf = this.getIndexOfTrack(track);
      if (indexOf > -1) {
         this.removeTrackAt(indexOf);
      }
   }

   public void removeTracks(List<AnimationTrack> tracks) {
      ArrayList<AnimationTrack> tempTracks = pzoptTempTracks.get();
      tempTracks.clear();
      PZArrayUtil.addAll(tempTracks, tracks);

      for (int i = 0; i < tempTracks.size(); i++) {
         this.removeTrack(tempTracks.get(i));
      }
   }

   public void removeTrackAt(int indexOf) {
      AnimationTrack removed = this.tracks.remove(indexOf);
      // pzopt: animatorParallel. On a frame worker the anim events of this update are captured and dispatched later on the
      // game thread, and an event can name the track; its release (pool return, listeners told) waits until then.
      if (!pzopt.AnimParallel.deferRelease(removed)) {
         removed.release();
      }
   }

   public int getIndexOfTrack(AnimationTrack track) {
      if (track == null) {
         return -1;
      }

      int indexOf = -1;

      for (int i = 0; i < this.tracks.size(); i++) {
         AnimationTrack animationTrack = this.tracks.get(i);
         if (animationTrack == track) {
            indexOf = i;
            break;
         }
      }

      return indexOf;
   }

   public void Update(float time) {
      for (int n = 0; n < this.tracks.size(); n++) {
         AnimationTrack track = this.tracks.get(n);
         track.Update(time);
         if (track.currentClip == null) {
            this.removeTrackAt(n);
            n--;
         }
      }
   }

   public float getDuration() {
      float longestDuration = 0.0F;

      for (int i = 0; i < this.tracks.size(); i++) {
         AnimationTrack track = this.tracks.get(i);
         float trackDuration = track.getDuration();
         if (track.currentClip != null && trackDuration > longestDuration) {
            longestDuration = trackDuration;
         }
      }

      return longestDuration;
   }

   public void reset() {
      int i = 0;

      for (int trackCount = this.tracks.size(); i < trackCount; i++) {
         AnimationTrack track = this.tracks.get(i);
         track.reset();
      }

      AnimationPlayer.releaseTracks(this.tracks);
      this.tracks.clear();
   }

   public List<AnimationTrack> getTracks() {
      return this.tracks;
   }

   public int getTrackCount() {
      return this.tracks.size();
   }

   public AnimationTrack getTrackAt(int i) {
      return this.tracks.get(i);
   }

   public boolean containsAnyRagdollTracks() {
      List<AnimationTrack> tracks = this.getTracks();

      for (int trackIdx = 0; trackIdx < tracks.size(); trackIdx++) {
         AnimationTrack track = tracks.get(trackIdx);
         if (track.isRagdoll()) {
            return true;
         }
      }

      return false;
   }

   public boolean anyRagdollFirstFrame() {
      List<AnimationTrack> tracks = this.getTracks();

      for (int trackIdx = 0; trackIdx < tracks.size(); trackIdx++) {
         AnimationTrack track = tracks.get(trackIdx);
         if (track.isRagdollFirstFrame()) {
            return true;
         }
      }

      return false;
   }

   public void initRagdollTransforms(TwistableBoneTransform[] boneTransforms, boolean bForce) {
      List<AnimationTrack> tracks = this.getTracks();

      for (int trackIdx = 0; trackIdx < tracks.size(); trackIdx++) {
         AnimationTrack track = tracks.get(trackIdx);
         if (!bForce && track.isRagdollFirstFrame()) {
            track.initRagdollTransforms(boneTransforms);
         }
      }
   }

   public AnimationTrack getActiveRagdollTrack() {
      AnimationTrack foundTrack = null;
      List<AnimationTrack> tracks = this.getTracks();

      for (int trackIdx = 0; trackIdx < tracks.size(); trackIdx++) {
         AnimationTrack track = tracks.get(trackIdx);
         if (track.isRagdoll()
            && track.isPlaying
            && !(track.getBlendWeight() <= 0.0F)
            && (foundTrack == null || foundTrack.getBlendWeight() > track.getBlendWeight())) {
            foundTrack = track;
         }
      }

      return foundTrack;
   }
}
