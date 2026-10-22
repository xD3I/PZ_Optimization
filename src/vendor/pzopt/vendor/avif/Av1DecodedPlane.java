// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Immutable decoded AV1 sample plane.
///
/// Samples are unsigned values stored in the low bits of each `short`. `stride` is measured in
/// samples, not bytes.

public final class Av1DecodedPlane {
    /// The plane width in samples.
    private final int width;
    /// The plane height in samples.
    private final int height;
    /// The plane row stride in samples.
    private final int stride;
    /// The immutable row-major sample storage.
    private final ShortBuffer samples;

    /// Creates one immutable decoded AV1 plane from an array.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the plane row stride in samples
    /// @param samples the unsigned plane samples in row-major order
    public Av1DecodedPlane(int width, int height, int stride, short[] samples) {
        this(width, height, stride, immutableSamples(Objects.requireNonNull(samples, "samples")));
    }

    /// Creates one immutable decoded AV1 plane from a buffer.
    ///
    /// The sample buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the plane row stride in samples
    /// @param samples the unsigned plane samples in row-major order
    public Av1DecodedPlane(int width, int height, int stride, ShortBuffer samples) {
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
        if (checkedSamples.remaining() != (int) requiredLength) {
            throw new IllegalArgumentException("samples length does not match stride * height");
        }
        this.width = width;
        this.height = height;
        this.stride = stride;
        this.samples = checkedSamples;
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

    /// Returns the plane row stride in samples.
    ///
    /// @return the plane row stride in samples
    public int stride() {
        return stride;
    }

    /// Returns a copy of the unsigned plane samples.
    ///
    /// @return a copy of the unsigned plane samples
    public short [] samples() {
        ShortBuffer buffer = samples.slice();
        short[] result = new short[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Returns a read-only view of the unsigned plane samples.
    ///
    /// @return a read-only view of the unsigned plane samples
    public ShortBuffer sampleBuffer() {
        return samples.slice();
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

    /// Creates immutable sample storage from an array.
    ///
    /// @param samples the source samples
    /// @return immutable sample storage
    private static ShortBuffer immutableSamples(short[] samples) {
        return ShortBuffer.wrap(Arrays.copyOf(samples, samples.length)).asReadOnlyBuffer();
    }
}
