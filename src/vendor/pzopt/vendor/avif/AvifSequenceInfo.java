// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


/// Immutable metadata for an AVIF animated image sequence.

public final class AvifSequenceInfo {
    /// Repetition count used when an animated sequence has no edit list.
    public static final int REPETITION_COUNT_UNKNOWN = -1;
    /// Repetition count used when an animated sequence repeats indefinitely.
    public static final int REPETITION_COUNT_INFINITE = -2;

    /// The number of frames advertised by the container.
    private final int frameCount;
    /// The media timescale for frame durations.
    private final int mediaTimescale;
    /// The total media duration in media timescale units.
    private final long mediaDuration;
    /// The sequence repetition count.
    private final int repetitionCount;
    /// Per-frame durations in media timescale units.
    private final int [] frameDurations;

    /// Creates immutable sequence metadata.
    ///
    /// @param frameCount the number of frames advertised by the container
    /// @param mediaTimescale the media timescale for frame durations, or zero when absent
    /// @param mediaDuration the total media duration in media timescale units, or zero when absent
    /// @param repetitionCount the repetition count, [#REPETITION_COUNT_UNKNOWN],
    /// or [#REPETITION_COUNT_INFINITE]
    /// @param frameDurations per-frame durations in media timescale units
    public AvifSequenceInfo(
            int frameCount,
            int mediaTimescale,
            long mediaDuration,
            int repetitionCount,
            int[] frameDurations
    ) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount <= 0: " + frameCount);
        }
        if (mediaTimescale < 0) {
            throw new IllegalArgumentException("mediaTimescale < 0: " + mediaTimescale);
        }
        if (mediaDuration < 0) {
            throw new IllegalArgumentException("mediaDuration < 0: " + mediaDuration);
        }
        if (repetitionCount < 0
                && repetitionCount != REPETITION_COUNT_UNKNOWN
                && repetitionCount != REPETITION_COUNT_INFINITE) {
            throw new IllegalArgumentException("Invalid repetition count: " + repetitionCount);
        }

        int[] checkedFrameDurations = frameDurations.clone();
        if (checkedFrameDurations.length != 0 && checkedFrameDurations.length != frameCount) {
            throw new IllegalArgumentException(
                    "frameDurations length must match frameCount: " + checkedFrameDurations.length
            );
        }
        for (int duration : checkedFrameDurations) {
            if (duration < 0) {
                throw new IllegalArgumentException("frameDurations contains a negative duration: " + duration);
            }
        }

        this.frameCount = frameCount;
        this.mediaTimescale = mediaTimescale;
        this.mediaDuration = mediaDuration;
        this.repetitionCount = repetitionCount;
        this.frameDurations = checkedFrameDurations;
    }

    /// Returns the number of frames advertised by the container.
    ///
    /// @return the number of frames advertised by the container
    public int frameCount() {
        return frameCount;
    }

    /// Returns the media timescale for frame durations.
    ///
    /// A value of zero means the container did not expose sequence timing.
    ///
    /// @return the media timescale, or zero when absent
    public int mediaTimescale() {
        return mediaTimescale;
    }

    /// Returns the total media duration.
    ///
    /// The value is expressed in `mediaTimescale()` units. A value of zero means the container did
    /// not expose sequence timing.
    ///
    /// @return the total media duration, or zero when absent
    public long mediaDuration() {
        return mediaDuration;
    }

    /// Returns the animated-sequence repetition count.
    ///
    /// A non-negative value is the number of repetitions after the first playback. Zero means the
    /// sequence should play once. [#REPETITION_COUNT_UNKNOWN] means the container did not expose
    /// an edit list, and [#REPETITION_COUNT_INFINITE] means the sequence
    /// repeats indefinitely.
    ///
    /// @return the repetition count, [#REPETITION_COUNT_UNKNOWN], or [#REPETITION_COUNT_INFINITE]
    public int repetitionCount() {
        return repetitionCount;
    }

    /// Returns per-frame durations for animated sequences.
    ///
    /// Values are expressed in `mediaTimescale()` units. Inputs without timing metadata return an
    /// empty array.
    ///
    /// @return per-frame durations in media timescale units
    public int [] frameDurations() {
        return frameDurations.clone();
    }
}
