// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.av1;

import pzopt.vendor.avif.AvifBitDepth;
import pzopt.vendor.avif.AvifPixelFormat;
import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.PixelBuffers;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Decoded AV1 frame output exposed by the public API.

public final class Av1DecodedFrame {
    /// The output frame width in pixels.
    private final int width;
    /// The output frame height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final AvifBitDepth bitDepth;
    /// The chroma layout of the decoded frame.
    private final Av1ChromaFormat chromaFormat;
    /// The AV1 frame type.
    private final Av1FrameType frameType;
    /// Whether the frame is visible.
    private final boolean visible;
    /// The zero-based presentation index of the frame.
    private final long presentationIndex;
    /// The packed ARGB pixel format used by this frame's native storage.
    private final AvifPixelFormat pixelFormat;
    /// The AV1 temporal-layer identifier carried by the frame OBU.
    private final int temporalId;
    /// The AV1 spatial-layer identifier carried by the frame OBU.
    private final int spatialId;
    /// Packed non-premultiplied ARGB pixels in `0xAARRGGBB` format, or `null` until converted.
    private IntBuffer intPixels;
    /// Packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format, or `null` until converted.
    private LongBuffer longPixels;

    /// Creates a decoded frame from a packed `int` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width             the output frame width in pixels
    /// @param height            the output frame height in pixels
    /// @param bitDepth          the decoded bit depth
    /// @param chromaFormat       the chroma layout
    /// @param frameType         the AV1 frame type
    /// @param visible           whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param pixels            the packed non-premultiplied ARGB pixels
    public Av1DecodedFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            IntBuffer pixels
    ) {
        this(width, height, bitDepth, chromaFormat, frameType, visible, presentationIndex,
                0, 0, PixelBuffers.immutableIntPixels(pixels), null);
    }

    /// Creates a decoded frame from a packed `int` ARGB pixel buffer with AV1 layer identifiers.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width             the output frame width in pixels
    /// @param height            the output frame height in pixels
    /// @param bitDepth          the decoded bit depth
    /// @param chromaFormat       the chroma layout
    /// @param frameType         the AV1 frame type
    /// @param visible           whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param temporalId        the AV1 temporal-layer identifier in `[0, 7]`
    /// @param spatialId         the AV1 spatial-layer identifier in `[0, 3]`
    /// @param pixels            the packed non-premultiplied ARGB pixels
    public Av1DecodedFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            int temporalId,
            int spatialId,
            IntBuffer pixels
    ) {
        this(width, height, bitDepth, chromaFormat, frameType, visible, presentationIndex,
                temporalId, spatialId, PixelBuffers.immutableIntPixels(pixels), null);
    }

    /// Creates a decoded frame from a packed `long` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width             the output frame width in pixels
    /// @param height            the output frame height in pixels
    /// @param bitDepth          the decoded bit depth
    /// @param chromaFormat       the chroma layout
    /// @param frameType         the AV1 frame type
    /// @param visible           whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param pixels            the packed non-premultiplied ARGB pixels
    public Av1DecodedFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            LongBuffer pixels
    ) {
        this(width, height, bitDepth, chromaFormat, frameType, visible, presentationIndex,
                0, 0, null, PixelBuffers.immutableLongPixels(pixels));
    }

    /// Creates a decoded frame from a packed `long` ARGB pixel buffer with AV1 layer identifiers.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width             the output frame width in pixels
    /// @param height            the output frame height in pixels
    /// @param bitDepth          the decoded bit depth
    /// @param chromaFormat       the chroma layout
    /// @param frameType         the AV1 frame type
    /// @param visible           whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param temporalId        the AV1 temporal-layer identifier in `[0, 7]`
    /// @param spatialId         the AV1 spatial-layer identifier in `[0, 3]`
    /// @param pixels            the packed non-premultiplied ARGB pixels
    public Av1DecodedFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            int temporalId,
            int spatialId,
            LongBuffer pixels
    ) {
        this(width, height, bitDepth, chromaFormat, frameType, visible, presentationIndex,
                temporalId, spatialId, null, PixelBuffers.immutableLongPixels(pixels));
    }

    /// Creates a decoded frame descriptor with one available pixel representation.
    ///
    /// @param width             the output frame width in pixels
    /// @param height            the output frame height in pixels
    /// @param bitDepth          the decoded bit depth
    /// @param chromaFormat       the chroma layout
    /// @param frameType         the AV1 frame type
    /// @param visible           whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param temporalId        the AV1 temporal-layer identifier
    /// @param spatialId         the AV1 spatial-layer identifier
    /// @param intPixels         packed `int` pixels, or `null`
    /// @param longPixels        packed `long` pixels, or `null`
    private Av1DecodedFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            int temporalId,
            int spatialId,
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
        if (presentationIndex < 0) {
            throw new IllegalArgumentException("presentationIndex < 0: " + presentationIndex);
        }
        if (temporalId < 0 || temporalId > 7) {
            throw new IllegalArgumentException("temporalId out of range: " + temporalId);
        }
        if (spatialId < 0 || spatialId > 3) {
            throw new IllegalArgumentException("spatialId out of range: " + spatialId);
        }
        this.width = width;
        this.height = height;
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        this.frameType = Objects.requireNonNull(frameType, "frameType");
        this.visible = visible;
        this.presentationIndex = presentationIndex;
        this.pixelFormat = intPixels != null ? AvifPixelFormat.ARGB_8888 : AvifPixelFormat.ARGB_16161616;
        this.temporalId = temporalId;
        this.spatialId = spatialId;
        this.intPixels = intPixels;
        this.longPixels = longPixels;
    }

    /// Returns the output frame width in pixels.
    ///
    /// @return the output frame width
    public int width() {
        return width;
    }

    /// Returns the output frame height in pixels.
    ///
    /// @return the output frame height
    public int height() {
        return height;
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the chroma layout of the decoded frame.
    ///
    /// @return the chroma layout
    public Av1ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /// Returns the AV1 frame type.
    ///
    /// @return the AV1 frame type
    public Av1FrameType frameType() {
        return frameType;
    }

    /// Returns whether the frame is visible.
    ///
    /// @return whether the frame is visible
    public boolean visible() {
        return visible;
    }

    /// Returns the zero-based presentation index of the frame.
    ///
    /// @return the presentation index
    public long presentationIndex() {
        return presentationIndex;
    }

    /// Returns the packed ARGB pixel format used by this frame's native storage.
    ///
    /// @return the packed ARGB pixel format
    public AvifPixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// Returns the AV1 temporal-layer identifier carried by the frame OBU.
    ///
    /// @return the temporal-layer identifier in `[0, 7]`
    public int temporalId() {
        return temporalId;
    }

    /// Returns the AV1 spatial-layer identifier carried by the frame OBU.
    ///
    /// @return the spatial-layer identifier in `[0, 3]`
    public int spatialId() {
        return spatialId;
    }

    /// Returns packed non-premultiplied ARGB pixels in `0xAARRGGBB` format.
    ///
    /// If this frame was constructed from `long` pixels, the returned data is created lazily by
    /// reducing each unsigned 16-bit channel to 8 bits with rounding.
    ///
    /// @return the packed ARGB pixels
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
    /// @return the packed ARGB pixels
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
