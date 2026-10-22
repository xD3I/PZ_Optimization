// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal;


import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Shared helpers for immutable ARGB pixel buffers.

public final class PixelBuffers {
    /// Prevents instantiation.
    private PixelBuffers() {
    }

    /// Creates immutable storage for `int` pixel arrays.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    public static IntBuffer immutableIntPixels(int[] pixels) {
        return IntBuffer.wrap(Arrays.copyOf(pixels, pixels.length)).asReadOnlyBuffer();
    }

    /// Creates immutable storage for `long` pixel arrays.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    public static LongBuffer immutableLongPixels(long[] pixels) {
        return LongBuffer.wrap(Arrays.copyOf(pixels, pixels.length)).asReadOnlyBuffer();
    }

    /// Creates immutable storage for an `int` pixel buffer.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    public static IntBuffer immutableIntPixels(IntBuffer pixels) {
        return Objects.requireNonNull(pixels, "pixels").slice().asReadOnlyBuffer();
    }

    /// Creates immutable storage for a `long` pixel buffer.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    public static LongBuffer immutableLongPixels(LongBuffer pixels) {
        return Objects.requireNonNull(pixels, "pixels").slice().asReadOnlyBuffer();
    }

    /// Converts `int` ARGB pixels into `long` ARGB pixels.
    ///
    /// @param pixels the source pixels
    /// @return converted immutable pixel storage
    public static LongBuffer convertIntPixelsToLongPixels(IntBuffer pixels) {
        IntBuffer source = pixels.slice();
        long[] converted = new long[source.remaining()];
        for (int i = 0; i < converted.length; i++) {
            int pixel = source.get();
            converted[i] = (long) byteToWord((pixel >>> 24) & 0xFF) << 48
                    | (long) byteToWord((pixel >>> 16) & 0xFF) << 32
                    | (long) byteToWord((pixel >>> 8) & 0xFF) << 16
                    | byteToWord(pixel & 0xFF);
        }
        return LongBuffer.wrap(converted).asReadOnlyBuffer();
    }

    /// Converts `long` ARGB pixels into `int` ARGB pixels.
    ///
    /// @param pixels the source pixels
    /// @return converted immutable pixel storage
    public static IntBuffer convertLongPixelsToIntPixels(LongBuffer pixels) {
        LongBuffer source = pixels.slice();
        int[] converted = new int[source.remaining()];
        for (int i = 0; i < converted.length; i++) {
            long pixel = source.get();
            converted[i] = wordToByte((int) ((pixel >>> 48) & 0xFFFFL)) << 24
                    | wordToByte((int) ((pixel >>> 32) & 0xFFFFL)) << 16
                    | wordToByte((int) ((pixel >>> 16) & 0xFFFFL)) << 8
                    | wordToByte((int) (pixel & 0xFFFFL));
        }
        return IntBuffer.wrap(converted).asReadOnlyBuffer();
    }

    /// Expands an unsigned 8-bit channel to an unsigned 16-bit channel.
    ///
    /// @param value the unsigned 8-bit channel
    /// @return the unsigned 16-bit channel
    private static int byteToWord(int value) {
        return value * 257;
    }

    /// Reduces an unsigned 16-bit channel to an unsigned 8-bit channel with rounding.
    ///
    /// @param value the unsigned 16-bit channel
    /// @return the unsigned 8-bit channel
    private static int wordToByte(int value) {
        return (value * 255 + 32_767) / 65_535;
    }
}
