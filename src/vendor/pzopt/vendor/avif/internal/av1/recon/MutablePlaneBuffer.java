// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.image.PaddedPlane;

import java.util.Arrays;
import java.util.Objects;

/// Mutable decoded-plane buffer used while reconstruction is still in progress.
///
/// Samples are stored as unsigned values in the low bits of each `short`. The mutable buffer uses
/// tightly packed storage for either the complete plane or one coordinate-preserving subregion.

final class MutablePlaneBuffer implements MutableSamplePlane {
    /// The containing plane width in samples.
    private final int width;

    /// The containing plane height in samples.
    private final int height;

    /// The horizontal origin of the retained storage region.
    private final int originX;

    /// The vertical origin of the retained storage region.
    private final int originY;

    /// The retained storage width in samples.
    private final int storageWidth;

    /// The retained storage height in samples.
    private final int storageHeight;

    /// The decoded sample bit depth.
    private final int bitDepth;

    /// The maximum legal sample value for this bit depth.
    private final int maxSampleValue;

    /// The tightly packed mutable sample buffer.
    private final short[] samples;

    /// Packed written-state bits for retained sample positions.
    private final long[] writtenSampleBits;

    /// Creates one mutable decoded-plane buffer.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param bitDepth the decoded sample bit depth
    MutablePlaneBuffer(int width, int height, int bitDepth) {
        this(width, height, bitDepth, 0, 0, width, height);
    }

    /// Creates a mutable buffer that retains one subregion in containing-plane coordinates.
    ///
    /// @param width the containing plane width in samples
    /// @param height the containing plane height in samples
    /// @param bitDepth the decoded sample bit depth
    /// @param originX the horizontal storage origin in containing-plane coordinates
    /// @param originY the vertical storage origin in containing-plane coordinates
    /// @param storageWidth the retained storage width in samples
    /// @param storageHeight the retained storage height in samples
    MutablePlaneBuffer(
            int width,
            int height,
            int bitDepth,
            int originX,
            int originY,
            int storageWidth,
            int storageHeight
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (bitDepth <= 0 || bitDepth > 15) {
            throw new IllegalArgumentException("bitDepth out of range: " + bitDepth);
        }
        if (originX < 0 || storageWidth <= 0 || originX + storageWidth > width) {
            throw new IllegalArgumentException("Horizontal storage region is outside the plane");
        }
        if (originY < 0 || storageHeight <= 0 || originY + storageHeight > height) {
            throw new IllegalArgumentException("Vertical storage region is outside the plane");
        }
        this.width = width;
        this.height = height;
        this.originX = originX;
        this.originY = originY;
        this.storageWidth = storageWidth;
        this.storageHeight = storageHeight;
        this.bitDepth = bitDepth;
        this.maxSampleValue = (1 << bitDepth) - 1;
        int sampleCount = Math.multiplyExact(storageWidth, storageHeight);
        this.samples = new short[sampleCount];
        this.writtenSampleBits = new long[writtenWordCount(sampleCount)];
    }

    /// Returns the plane width in samples.
    ///
    /// @return the plane width in samples
    @Override
    public int width() {
        return width;
    }

    /// Returns the plane height in samples.
    ///
    /// @return the plane height in samples
    @Override
    public int height() {
        return height;
    }

    /// Returns the decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    @Override
    public int bitDepth() {
        return bitDepth;
    }

    /// Returns the maximum legal sample value for this bit depth.
    ///
    /// @return the maximum legal sample value for this bit depth
    int maxSampleValue() {
        return maxSampleValue;
    }

    /// Returns one already reconstructed sample.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return one already reconstructed sample
    @Override
    public int sample(int x, int y) {
        return samples[storageIndex(x, y)] & 0xFFFF;
    }

    /// Stores one reconstructed sample after clipping it into the legal bit-depth range.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param value the reconstructed sample value
    @Override
    public void setSample(int x, int y, int value) {
        int index = storageIndex(x, y);
        samples[index] = (short) clipped(value);
        markWritten(index);
    }

    /// Fills the in-plane portion of one rectangular block and marks it as written.
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
        int writtenWidth = Math.min(blockWidth, width - x);
        int writtenHeight = Math.min(blockHeight, height - y);
        if (localX < 0 || writtenWidth <= 0 || writtenWidth > storageWidth - localX) {
            throw new IndexOutOfBoundsException("Block origin is outside retained horizontal storage");
        }
        if (localY < 0 || writtenHeight <= 0 || writtenHeight > storageHeight - localY) {
            throw new IndexOutOfBoundsException("Block origin is outside retained vertical storage");
        }

        short sample = (short) clipped(value);
        for (int row = 0; row < writtenHeight; row++) {
            int startIndex = (localY + row) * storageWidth + localX;
            Arrays.fill(samples, startIndex, startIndex + writtenWidth, sample);
            markWrittenRange(startIndex, writtenWidth);
        }
    }

    /// Adds one constant signed value to every sample in a retained rectangular block.
    ///
    /// Each result is clipped independently to the legal bit-depth range and marked as written.
    ///
    /// @param x the horizontal block origin in containing-plane coordinates
    /// @param y the vertical block origin in containing-plane coordinates
    /// @param blockWidth the positive block width in samples
    /// @param blockHeight the positive block height in samples
    /// @param value the signed value to add
    void addConstantBlock(int x, int y, int blockWidth, int blockHeight, int value) {
        int localX = x - originX;
        int localY = y - originY;
        if (blockWidth <= 0 || localX < 0 || blockWidth > storageWidth - localX) {
            throw new IndexOutOfBoundsException("Block is outside retained horizontal storage");
        }
        if (blockHeight <= 0 || localY < 0 || blockHeight > storageHeight - localY) {
            throw new IndexOutOfBoundsException("Block is outside retained vertical storage");
        }

        for (int row = 0; row < blockHeight; row++) {
            int startIndex = (localY + row) * storageWidth + localX;
            int index = startIndex;
            int rowEnd = startIndex + blockWidth;
            while (index < rowEnd) {
                samples[index] = (short) clipped((samples[index] & 0xFFFF) + value);
                index++;
            }
            markWrittenRange(startIndex, blockWidth);
        }
    }

    /// Copies one edge-extended source block into retained storage and marks it as written.
    ///
    /// @param source the immutable source plane
    /// @param destinationX the horizontal destination origin in containing-plane coordinates
    /// @param destinationY the vertical destination origin in containing-plane coordinates
    /// @param sourceX the horizontal source origin
    /// @param sourceY the vertical source origin
    /// @param blockWidth the positive block width in samples
    /// @param blockHeight the positive block height in samples
    void copyExtendedBlockFrom(
            PaddedPlane source,
            int destinationX,
            int destinationY,
            int sourceX,
            int sourceY,
            int blockWidth,
            int blockHeight
    ) {
        PaddedPlane checkedSource = Objects.requireNonNull(source, "source");
        int localX = destinationX - originX;
        int localY = destinationY - originY;
        if (blockWidth <= 0 || localX < 0 || blockWidth > storageWidth - localX) {
            throw new IndexOutOfBoundsException("Block is outside retained horizontal storage");
        }
        if (blockHeight <= 0 || localY < 0 || blockHeight > storageHeight - localY) {
            throw new IndexOutOfBoundsException("Block is outside retained vertical storage");
        }

        for (int row = 0; row < blockHeight; row++) {
            int destinationIndex = (localY + row) * storageWidth + localX;
            checkedSource.copyExtendedRowTo(
                    sourceX,
                    sourceY + row,
                    samples,
                    destinationIndex,
                    blockWidth
            );
            markWrittenRange(destinationIndex, blockWidth);
        }
    }

    /// Returns whether one retained-region sample has been written by reconstruction.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return whether the retained sample has been written, or `false` outside retained storage
    boolean hasWrittenSample(int x, int y) {
        int localX = x - originX;
        int localY = y - originY;
        return localX >= 0
                && localX < storageWidth
                && localY >= 0
                && localY < storageHeight
                && isWritten(localY * storageWidth + localX);
    }

    /// Returns one sample when it lies inside retained storage, or the fallback value otherwise.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param fallbackValue the fallback value returned outside the plane bounds
    /// @return one retained sample, or the supplied fallback value
    int sampleOrFallback(int x, int y, int fallbackValue) {
        int localX = x - originX;
        int localY = y - originY;
        if (localX < 0 || localX >= storageWidth || localY < 0 || localY >= storageHeight) {
            return fallbackValue;
        }
        return samples[localY * storageWidth + localX] & 0xFFFF;
    }

    /// Converts this mutable reconstruction buffer into one immutable decoded-plane snapshot.
    ///
    /// @return one immutable decoded-plane snapshot
    /// @throws IllegalStateException if this buffer retains only a plane subregion
    PaddedPlane toDecodedPlane() {
        requireCompletePlaneStorage();
        return new PaddedPlane(width, height, width, samples);
    }

    /// Transfers the retained storage region into one immutable coordinate-local decoded plane.
    ///
    /// The caller must permanently discard this mutable buffer after the transfer. The requested
    /// dimensions are relative to the retained region and may omit its right or bottom padding.
    ///
    /// @param croppedWidth the visible retained-region width in samples
    /// @param croppedHeight the visible retained-region height in samples
    /// @return one immutable plane that owns the retained sample storage
    PaddedPlane takeStoredDecodedPlane(int croppedWidth, int croppedHeight) {
        if (croppedWidth <= 0 || croppedWidth > storageWidth) {
            throw new IllegalArgumentException("croppedWidth out of range: " + croppedWidth);
        }
        if (croppedHeight <= 0 || croppedHeight > storageHeight) {
            throw new IllegalArgumentException("croppedHeight out of range: " + croppedHeight);
        }
        return PaddedPlane.fromOwnedSamples(croppedWidth, croppedHeight, storageWidth, samples);
    }

    /// Creates an independent mutable copy of this plane buffer.
    ///
    /// @return an independent mutable copy of this plane buffer
    MutablePlaneBuffer copy() {
        MutablePlaneBuffer copy = new MutablePlaneBuffer(
                width,
                height,
                bitDepth,
                originX,
                originY,
                storageWidth,
                storageHeight
        );
        System.arraycopy(samples, 0, copy.samples, 0, samples.length);
        System.arraycopy(writtenSampleBits, 0, copy.writtenSampleBits, 0, writtenSampleBits.length);
        return copy;
    }

    /// Returns the number of words needed to store one written-state bit per sample.
    ///
    /// @param sampleCount the positive retained sample count
    /// @return the required packed-word count
    private static int writtenWordCount(int sampleCount) {
        return (int) (((long) sampleCount + Long.SIZE - 1L) / Long.SIZE);
    }

    /// Marks one compact sample position as written.
    ///
    /// @param index the compact sample index
    private void markWritten(int index) {
        writtenSampleBits[index / Long.SIZE] |= 1L << (index % Long.SIZE);
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

    /// Returns whether one compact sample position has been written.
    ///
    /// @param index the compact sample index
    /// @return whether the sample has been written
    private boolean isWritten(int index) {
        return (writtenSampleBits[index / Long.SIZE] & (1L << (index % Long.SIZE))) != 0L;
    }

    /// Returns the compact storage index for one containing-plane coordinate.
    ///
    /// @param x the horizontal containing-plane coordinate
    /// @param y the vertical containing-plane coordinate
    /// @return the compact storage index
    private int storageIndex(int x, int y) {
        int localX = x - originX;
        int localY = y - originY;
        if (localX < 0 || localX >= storageWidth) {
            throw new IndexOutOfBoundsException("x outside retained storage: " + x);
        }
        if (localY < 0 || localY >= storageHeight) {
            throw new IndexOutOfBoundsException("y outside retained storage: " + y);
        }
        return localY * storageWidth + localX;
    }

    /// Ensures that snapshot operations requiring a top-left plane own complete storage.
    private void requireCompletePlaneStorage() {
        if (originX != 0 || originY != 0 || storageWidth != width || storageHeight != height) {
            throw new IllegalStateException("Operation requires complete plane storage");
        }
    }

    /// Clips one sample value into the legal bit-depth range.
    ///
    /// @param value the sample value to clip
    /// @return the clipped sample value
    private int clipped(int value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= maxSampleValue) {
            return maxSampleValue;
        }
        return value;
    }
}
