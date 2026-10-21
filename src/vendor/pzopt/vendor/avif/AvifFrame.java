// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;

import pzopt.vendor.avif.internal.PixelBuffers;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Decoded AVIF frame output.

public final class AvifFrame {
    /// The frame width in pixels.
    private final int width;
    /// The frame height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final AvifBitDepth bitDepth;
    /// The decoded AV1 chroma sampling layout.
    private final Av1ChromaFormat chromaFormat;
    /// The zero-based frame index.
    private final int frameIndex;
    /// The packed ARGB pixel format used by this frame's native storage.
    private final AvifPixelFormat pixelFormat;
    /// Packed non-premultiplied ARGB pixels in `0xAARRGGBB` format, or `null` until converted.
    private IntBuffer intPixels;
    /// Packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format, or `null` until converted.
    private LongBuffer longPixels;

    /// Creates an AVIF frame from packed `int` ARGB pixels.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAARRGGBB` format
    public AvifFrame(int width, int height, AvifBitDepth bitDepth, Av1ChromaFormat chromaFormat, int frameIndex, int[] pixels) {
        this(width, height, bitDepth, chromaFormat, frameIndex,
                PixelBuffers.immutableIntPixels(Objects.requireNonNull(pixels, "pixels")), null);
    }

    /// Creates an AVIF frame from packed `long` ARGB pixels.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public AvifFrame(int width, int height, AvifBitDepth bitDepth, Av1ChromaFormat chromaFormat, int frameIndex, long[] pixels) {
        this(width, height, bitDepth, chromaFormat, frameIndex,
                null, PixelBuffers.immutableLongPixels(Objects.requireNonNull(pixels, "pixels")));
    }

    /// Creates an AVIF frame from a packed `int` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAARRGGBB` format
    public AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            IntBuffer pixels
    ) {
        this(width, height, bitDepth, chromaFormat, frameIndex, PixelBuffers.immutableIntPixels(pixels), null);
    }

    /// Creates an AVIF frame from a packed `long` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            LongBuffer pixels
    ) {
        this(width, height, bitDepth, chromaFormat, frameIndex, null, PixelBuffers.immutableLongPixels(pixels));
    }

    /// Creates an AVIF frame by taking exclusive ownership of packed `int` ARGB pixels.
    ///
    /// The caller must not retain or mutate `pixels` after this method returns.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex the zero-based frame index
    /// @param pixels exclusively owned packed non-premultiplied ARGB pixels
    /// @return a frame backed directly by `pixels`
    static AvifFrame fromOwnedPixels(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            int [] pixels
    ) {
        IntBuffer storage = IntBuffer.wrap(Objects.requireNonNull(pixels, "pixels")).asReadOnlyBuffer();
        return new AvifFrame(width, height, bitDepth, chromaFormat, frameIndex, storage, null);
    }

    /// Creates an AVIF frame by taking exclusive ownership of packed `long` ARGB pixels.
    ///
    /// The caller must not retain or mutate `pixels` after this method returns.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex the zero-based frame index
    /// @param pixels exclusively owned packed non-premultiplied ARGB pixels
    /// @return a frame backed directly by `pixels`
    static AvifFrame fromOwnedPixels(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            long [] pixels
    ) {
        LongBuffer storage = LongBuffer.wrap(Objects.requireNonNull(pixels, "pixels")).asReadOnlyBuffer();
        return new AvifFrame(width, height, bitDepth, chromaFormat, frameIndex, null, storage);
    }

    /// Creates a decoded AVIF frame descriptor with one available pixel representation.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param intPixels   packed `int` pixels, or `null`
    /// @param longPixels  packed `long` pixels, or `null`
    private AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            IntBuffer intPixels,
            LongBuffer longPixels
    ) {
        if (intPixels == null && longPixels == null) {
            throw new IllegalArgumentException("At least one pixel representation is required");
        }
        int pixelCount = checkedPixelCount(width, height);
        if (intPixels != null && intPixels.remaining() != pixelCount) {
            throw new IllegalArgumentException(
                    "Int pixel count does not match dimensions: " + intPixels.remaining()
            );
        }
        if (longPixels != null && longPixels.remaining() != pixelCount) {
            throw new IllegalArgumentException(
                    "Long pixel count does not match dimensions: " + longPixels.remaining()
            );
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frameIndex < 0: " + frameIndex);
        }
        this.width = width;
        this.height = height;
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        this.frameIndex = frameIndex;
        this.pixelFormat = intPixels != null ? AvifPixelFormat.ARGB_8888 : AvifPixelFormat.ARGB_16161616;
        this.intPixels = intPixels;
        this.longPixels = longPixels;
    }

    /// Returns the frame width in pixels.
    ///
    /// @return the frame width in pixels
    public int width() {
        return width;
    }

    /// Returns the frame height in pixels.
    ///
    /// @return the frame height in pixels
    public int height() {
        return height;
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the decoded AV1 chroma sampling layout.
    ///
    /// @return the decoded AV1 chroma sampling layout
    public Av1ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /// Returns the zero-based frame index.
    ///
    /// @return the zero-based frame index
    public int frameIndex() {
        return frameIndex;
    }

    /// Returns the packed ARGB pixel format used by this frame's native storage.
    ///
    /// @return the packed ARGB pixel format
    public AvifPixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// Returns packed non-premultiplied ARGB pixels in `0xAARRGGBB` format.
    ///
    /// If this frame was constructed from `long` pixels, the returned data is created lazily by
    /// reducing each unsigned 16-bit channel to 8 bits with rounding.
    ///
    /// @return packed non-premultiplied ARGB pixels
    public int[] intPixels() {
        IntBuffer buffer = intPixelBuffer();
        int[] result = new int[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Returns a read-only view of packed non-premultiplied ARGB pixels in `0xAARRGGBB` format.
    ///
    /// If this frame was constructed from `long` pixels, the buffer is created lazily and cached.
    ///
    /// @return a read-only view of packed non-premultiplied ARGB pixels
    public IntBuffer intPixelBuffer() {
        IntBuffer pixels = intPixels;
        if (pixels == null) {
            pixels = PixelBuffers.convertLongPixelsToIntPixels(requireLongPixels());
            intPixels = pixels;
        }
        return pixels.slice();
    }

    /// Returns packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
    ///
    /// If this frame was constructed from `int` pixels, the returned data is created lazily by
    /// expanding each unsigned 8-bit channel to 16 bits.
    ///
    /// @return packed non-premultiplied ARGB pixels
    public long[] longPixels() {
        LongBuffer buffer = longPixelBuffer();
        long[] result = new long[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Returns a read-only view of packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
    ///
    /// If this frame was constructed from `int` pixels, the buffer is created lazily and cached.
    ///
    /// @return a read-only view of packed non-premultiplied ARGB pixels
    public LongBuffer longPixelBuffer() {
        LongBuffer pixels = longPixels;
        if (pixels == null) {
            pixels = PixelBuffers.convertIntPixelsToLongPixels(requireIntPixels());
            longPixels = pixels;
        }
        return pixels.slice();
    }

    /// Returns the cached `int` pixels or throws if they are unavailable.
    ///
    /// @return the cached `int` pixels
    private IntBuffer requireIntPixels() {
        IntBuffer pixels = intPixels;
        if (pixels == null) {
            throw new IllegalStateException("Int pixels are unavailable");
        }
        return pixels;
    }

    /// Returns the cached `long` pixels or throws if they are unavailable.
    ///
    /// @return the cached `long` pixels
    private LongBuffer requireLongPixels() {
        LongBuffer pixels = longPixels;
        if (pixels == null) {
            throw new IllegalStateException("Long pixels are unavailable");
        }
        return pixels;
    }

    /// Returns the exact pixel count for validated positive dimensions.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @return the pixel count
    private static int checkedPixelCount(int width, int height) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        long pixelCount = (long) width * height;
        if (pixelCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Frame dimensions are too large: " + width + "x" + height);
        }
        return (int) pixelCount;
    }
}
