// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.image;


import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Immutable snapshot of one decoded image plane.
///
/// Samples are stored as unsigned values in the low bits of each `short`. `stride` is measured in
/// samples, not bytes.

public final class PaddedPlane {
    /// The plane width in samples.
    private final int width;

    /// The plane height in samples.
    private final int height;

    /// The sample stride of one plane row.
    private final int stride;

    /// The number of stored rows, including internal bottom padding.
    private final int storageHeight;

    /// The stored unsigned sample values in row-major order.
    private final ShortBuffer samples;

    /// Creates one immutable decoded-plane snapshot.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the sample stride of one plane row
    /// @param samples the stored unsigned sample values in row-major order; the array may include
    ///                complete padded rows below the visible plane
    public PaddedPlane(int width, int height, int stride, short[] samples) {
        this(width, height, stride, immutableSamples(Objects.requireNonNull(samples, "samples")));
    }

    /// Creates one immutable padded plane over immutable sample storage.
    ///
    /// The buffer is retained as a read-only slice without copying. The caller must not modify the
    /// underlying storage after construction.
    ///
    /// @param width the visible plane width in samples
    /// @param height the visible plane height in samples
    /// @param stride the stored row stride in samples
    /// @param samples the complete stored rows, including any bottom padding
    public PaddedPlane(int width, int height, int stride, ShortBuffer samples) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (stride < width) {
            throw new IllegalArgumentException("stride < width: " + stride);
        }
        long requiredLength = (long) stride * height;
        if (requiredLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("plane storage is too large");
        }
        ShortBuffer checkedSamples = Objects.requireNonNull(samples, "samples").slice().asReadOnlyBuffer();
        if (checkedSamples.remaining() < (int) requiredLength || checkedSamples.remaining() % stride != 0) {
            throw new IllegalArgumentException("samples length does not contain complete stored rows");
        }
        this.width = width;
        this.height = height;
        this.stride = stride;
        this.storageHeight = checkedSamples.remaining() / stride;
        this.samples = checkedSamples;
    }

    /// Creates one immutable decoded plane by taking exclusive ownership of its sample storage.
    ///
    /// The caller must not access or modify `samples` after this method returns. This method exists
    /// for decoder stages that have finished producing an otherwise unaliased output buffer;
    /// callers that retain their array must use [#PaddedPlane(int, int, int, short[])].
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the sample stride of one plane row
    /// @param samples the exclusively owned stored samples, including any complete padded rows
    /// @return one immutable decoded plane backed by `samples`
    public static PaddedPlane fromOwnedSamples(int width, int height, int stride, short[] samples) {
        return new PaddedPlane(
                width,
                height,
                stride,
                ShortBuffer.wrap(Objects.requireNonNull(samples, "samples"))
        );
    }

    /// Returns the plane width in samples.
    ///
    /// @return the plane width in samples
    public int width() {
        return width;
    }

    /// Returns the plane height in samples.
    ///
    /// @return the plane height in samples
    public int height() {
        return height;
    }

    /// Returns the sample stride of one plane row.
    ///
    /// @return the sample stride of one plane row
    public int stride() {
        return stride;
    }

    /// Returns the number of stored rows, including internal bottom padding.
    ///
    /// @return the stored row count, which is at least [#height()]
    public int storageHeight() {
        return storageHeight;
    }

    /// Returns the stored unsigned sample values in row-major order, including internal padding.
    ///
    /// @return the stored unsigned sample values in row-major order
    public short [] samples() {
        ShortBuffer buffer = samples.slice();
        short[] result = new short[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Returns a read-only view of the visible stored rows.
    ///
    /// The view includes right-hand row padding described by [#stride()] but excludes rows below
    /// [#height()].
    ///
    /// @return a read-only view of the stored unsigned sample values
    public ShortBuffer sampleBuffer() {
        return samples.slice(0, stride * height);
    }

    /// Returns one unsigned sample value.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return one unsigned sample value
    public int sample(int x, int y) {
        if (x < 0 || x >= width) {
            throw new IndexOutOfBoundsException("x out of range: " + x);
        }
        if (y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("y out of range: " + y);
        }
        return samples.get(y * stride + x) & 0xFFFF;
    }

    /// Returns one stored sample, including right or bottom padding outside the visible plane.
    ///
    /// @param x the zero-based stored sample coordinate in `[0, stride)`
    /// @param y the zero-based stored row coordinate in `[0, storageHeight)`
    /// @return one unsigned stored sample value
    public int storedSample(int x, int y) {
        if (x < 0 || x >= stride) {
            throw new IndexOutOfBoundsException("x out of stored range: " + x);
        }
        if (y < 0 || y >= storageHeight) {
            throw new IndexOutOfBoundsException("y out of stored range: " + y);
        }
        return samples.get(y * stride + x) & 0xFFFF;
    }

    /// Copies one visible row segment with nearest-edge extension into mutable storage.
    ///
    /// Source coordinates outside the visible plane are replaced by the nearest visible sample.
    /// The destination range is validated before any samples are written.
    ///
    /// @param startX the horizontal coordinate of the first requested sample
    /// @param y the vertical coordinate of the requested row
    /// @param destination the destination sample array
    /// @param destinationOffset the first destination index
    /// @param length the non-negative number of samples to copy
    public void copyExtendedRowTo(
            int startX,
            int y,
            short[] destination,
            int destinationOffset,
            int length
    ) {
        short[] checkedDestination = Objects.requireNonNull(destination, "destination");
        Objects.checkFromIndexSize(destinationOffset, length, checkedDestination.length);
        if (length == 0) {
            return;
        }

        int sourceY = Math.max(0, Math.min(y, height - 1));
        int rowOffset = sourceY * stride;
        int firstVisibleIndex = (int) Math.min(length, Math.max(0L, -(long) startX));
        int visibleEndIndex = (int) Math.min(
                length,
                Math.max((long) firstVisibleIndex, (long) width - startX)
        );
        if (firstVisibleIndex > 0) {
            Arrays.fill(
                    checkedDestination,
                    destinationOffset,
                    destinationOffset + firstVisibleIndex,
                    samples.get(rowOffset)
            );
        }
        int visibleLength = visibleEndIndex - firstVisibleIndex;
        if (visibleLength > 0) {
            samples.get(
                    rowOffset + startX + firstVisibleIndex,
                    checkedDestination,
                    destinationOffset + firstVisibleIndex,
                    visibleLength
            );
        }
        if (visibleEndIndex < length) {
            Arrays.fill(
                    checkedDestination,
                    destinationOffset + visibleEndIndex,
                    destinationOffset + length,
                    samples.get(rowOffset + width - 1)
            );
        }
    }

    /// Copies array input into immutable read-only buffer storage.
    ///
    /// @param samples the source samples
    /// @return immutable copied sample storage
    private static ShortBuffer immutableSamples(short[] samples) {
        return ShortBuffer.wrap(samples.clone()).asReadOnlyBuffer();
    }
}
