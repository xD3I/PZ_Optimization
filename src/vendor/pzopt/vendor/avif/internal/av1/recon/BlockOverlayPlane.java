// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;


import java.util.Arrays;
import java.util.Objects;

/// Stores predicted samples for one block while reading all other samples through a base plane.
///
/// Unwritten positions inside the block also read through to the base plane. This preserves the
/// initial predictor state while allowing recursive intra kernels to observe samples they have
/// already written without copying the containing frame.

final class BlockOverlayPlane implements MutableSamplePlane {
    /// The plane that supplies samples outside the overlay and unwritten samples inside it.
    private final MutableSamplePlane basePlane;

    /// The inclusive horizontal origin of the writable overlay.
    private final int originX;

    /// The inclusive vertical origin of the writable overlay.
    private final int originY;

    /// The writable overlay width in samples.
    private final int overlayWidth;

    /// The writable overlay height in samples.
    private final int overlayHeight;

    /// The maximum legal sample value for the plane bit depth.
    private final int maximumSampleValue;

    /// The compact overlay sample storage.
    private final short[] samples;

    /// Packed written-state bits for compact overlay positions.
    private final long[] writtenSampleBits;

    /// Creates one writable block overlay over the supplied base plane.
    ///
    /// A requested block that reaches beyond the containing plane is clipped at the right and
    /// bottom edges, matching intra prediction's in-plane write behavior.
    ///
    /// @param basePlane     the plane that supplies read-through samples
    /// @param originX       the inclusive horizontal overlay origin
    /// @param originY       the inclusive vertical overlay origin
    /// @param overlayWidth  the overlay width in samples
    /// @param overlayHeight the overlay height in samples
    BlockOverlayPlane(
            MutableSamplePlane basePlane,
            int originX,
            int originY,
            int overlayWidth,
            int overlayHeight
    ) {
        this.basePlane = Objects.requireNonNull(basePlane, "basePlane");
        if (originX < 0 || originX >= basePlane.width()) {
            throw new IllegalArgumentException("originX out of range: " + originX);
        }
        if (originY < 0 || originY >= basePlane.height()) {
            throw new IllegalArgumentException("originY out of range: " + originY);
        }
        if (overlayWidth <= 0) {
            throw new IllegalArgumentException("overlayWidth out of range: " + overlayWidth);
        }
        if (overlayHeight <= 0) {
            throw new IllegalArgumentException("overlayHeight out of range: " + overlayHeight);
        }
        this.originX = originX;
        this.originY = originY;
        this.overlayWidth = Math.min(overlayWidth, basePlane.width() - originX);
        this.overlayHeight = Math.min(overlayHeight, basePlane.height() - originY);
        this.maximumSampleValue = (1 << basePlane.bitDepth()) - 1;
        int sampleCount = Math.multiplyExact(this.overlayWidth, this.overlayHeight);
        this.samples = new short[sampleCount];
        this.writtenSampleBits = new long[writtenWordCount(sampleCount)];
    }

    /// Returns the width of the containing base plane.
    ///
    /// @return the containing plane width in samples
    @Override
    public int width() {
        return basePlane.width();
    }

    /// Returns the height of the containing base plane.
    ///
    /// @return the containing plane height in samples
    @Override
    public int height() {
        return basePlane.height();
    }

    /// Returns the base plane's decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    @Override
    public int bitDepth() {
        return basePlane.bitDepth();
    }

    /// Returns an overlay sample when written, or reads through to the base plane otherwise.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return the overlay or base-plane sample
    @Override
    public int sample(int x, int y) {
        int index = overlayIndex(x, y);
        if (index >= 0 && isWritten(index)) {
            return samples[index] & 0xFFFF;
        }
        return basePlane.sample(x, y);
    }

    /// Stores one clipped sample inside the writable overlay.
    ///
    /// @param x     the zero-based horizontal sample coordinate
    /// @param y     the zero-based vertical sample coordinate
    /// @param value the sample value
    @Override
    public void setSample(int x, int y, int value) {
        int index = overlayIndex(x, y);
        if (index < 0) {
            throw new IndexOutOfBoundsException("coordinate outside block overlay: " + x + ", " + y);
        }
        samples[index] = (short) Math.max(0, Math.min(value, maximumSampleValue));
        writtenSampleBits[index / Long.SIZE] |= 1L << (index % Long.SIZE);
    }

    /// Fills the in-plane portion of one rectangular block inside this overlay.
    ///
    /// @param x the horizontal block origin in containing-plane coordinates
    /// @param y the vertical block origin in containing-plane coordinates
    /// @param blockWidth the positive coded block width in samples
    /// @param blockHeight the positive coded block height in samples
    /// @param value the sample value, clipped to the legal bit-depth range
    @Override
    public void fillBlock(int x, int y, int blockWidth, int blockHeight, int value) {
        if (blockWidth <= 0) {
            throw new IllegalArgumentException("blockWidth <= 0: " + blockWidth);
        }
        if (blockHeight <= 0) {
            throw new IllegalArgumentException("blockHeight <= 0: " + blockHeight);
        }
        int localX = x - originX;
        int localY = y - originY;
        int writtenWidth = Math.min(blockWidth, width() - x);
        int writtenHeight = Math.min(blockHeight, height() - y);
        if (localX < 0 || writtenWidth <= 0 || writtenWidth > overlayWidth - localX) {
            throw new IndexOutOfBoundsException("Block origin is outside overlay horizontal storage");
        }
        if (localY < 0 || writtenHeight <= 0 || writtenHeight > overlayHeight - localY) {
            throw new IndexOutOfBoundsException("Block origin is outside overlay vertical storage");
        }

        short sample = (short) Math.max(0, Math.min(value, maximumSampleValue));
        for (int row = 0; row < writtenHeight; row++) {
            int startIndex = (localY + row) * overlayWidth + localX;
            Arrays.fill(samples, startIndex, startIndex + writtenWidth, sample);
            markWrittenRange(startIndex, writtenWidth);
        }
    }

    /// Returns the number of words needed to store one written-state bit per sample.
    ///
    /// @param sampleCount the positive overlay sample count
    /// @return the required packed-word count
    private static int writtenWordCount(int sampleCount) {
        return (int) (((long) sampleCount + Long.SIZE - 1L) / Long.SIZE);
    }

    /// Marks one non-empty contiguous compact sample range as written.
    ///
    /// @param startIndex the first compact sample index
    /// @param length the positive number of samples to mark
    private void markWrittenRange(int startIndex, int length) {
        int endIndex = startIndex + length;
        int firstWord = startIndex / Long.SIZE;
        int lastWord = (endIndex - 1) / Long.SIZE;
        long firstMask = -1L << (startIndex % Long.SIZE);
        long lastMask = -1L >>> (Long.SIZE - 1 - ((endIndex - 1) % Long.SIZE));
        if (firstWord == lastWord) {
            writtenSampleBits[firstWord] |= firstMask & lastMask;
            return;
        }
        writtenSampleBits[firstWord] |= firstMask;
        Arrays.fill(writtenSampleBits, firstWord + 1, lastWord, -1L);
        writtenSampleBits[lastWord] |= lastMask;
    }

    /// Returns whether one compact overlay position has been written.
    ///
    /// @param index the compact overlay index
    /// @return whether the overlay sample has been written
    private boolean isWritten(int index) {
        return (writtenSampleBits[index / Long.SIZE] & (1L << (index % Long.SIZE))) != 0L;
    }

    /// Returns the compact storage index for one coordinate, or `-1` when it lies outside the overlay.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return the compact storage index, or `-1`
    private int overlayIndex(int x, int y) {
        int localX = x - originX;
        int localY = y - originY;
        if (localX < 0 || localX >= overlayWidth || localY < 0 || localY >= overlayHeight) {
            return -1;
        }
        return localY * overlayWidth + localX;
    }
}
