// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.output;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;

import java.nio.ShortBuffer;
import java.util.Objects;

/// Converts complete decoded surfaces into opaque ARGB pixel arrays.
///
/// Conversion covers every stored luma sample and uses point-sampled chroma expansion for `YUV420`,
/// `YUV422`, and `YUV444`. AV1 render dimensions are display hints and do not crop or resample the
/// decoded planes.

public final class ArgbOutput {
    /// Prevents instantiation of this utility class.
    private ArgbOutput() {
    }

    /// Converts one decoded-plane snapshot into opaque ARGB pixels.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return packed opaque non-premultiplied ARGB pixels in presentation order
    public static int[] toOpaqueArgbPixels(DecodedSurface decodedPlanes, YuvToRgbTransform transform) {
        DecodedSurface checkedDecodedPlanes = requireIntOutputDecodedPlanes(decodedPlanes);
        YuvToRgbTransform checkedTransform = Objects.requireNonNull(transform, "transform");

        int outputWidth = checkedDecodedPlanes.codedWidth();
        int outputHeight = checkedDecodedPlanes.codedHeight();
        int pixelCount = checkedPixelCount(outputWidth, outputHeight);
        int[] pixels = new int[pixelCount];

        Av1ChromaFormat chromaFormat = checkedDecodedPlanes.chromaFormat();
        int bitDepth = checkedDecodedPlanes.bitDepth();
        return switch (chromaFormat) {
            case MONOCHROME -> convertOpaqueI400(
                    checkedDecodedPlanes.lumaPlane(),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV420 -> convertOpaqueI420(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV422 -> convertOpaqueI422(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV444 -> convertOpaqueI444(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
        };
    }

    /// Converts one decoded-plane snapshot into opaque 16-bit-per-channel ARGB pixels.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return packed opaque non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public static long[] toOpaqueArgbLongPixels(DecodedSurface decodedPlanes, YuvToRgbTransform transform) {
        DecodedSurface checkedDecodedPlanes = requireLongOutputDecodedPlanes(decodedPlanes);
        YuvToRgbTransform checkedTransform = Objects.requireNonNull(transform, "transform");

        int outputWidth = checkedDecodedPlanes.codedWidth();
        int outputHeight = checkedDecodedPlanes.codedHeight();
        int pixelCount = checkedPixelCount(outputWidth, outputHeight);
        long[] pixels = new long[pixelCount];

        Av1ChromaFormat chromaFormat = checkedDecodedPlanes.chromaFormat();
        int bitDepth = checkedDecodedPlanes.bitDepth();
        return switch (chromaFormat) {
            case MONOCHROME -> convertOpaqueLongI400(
                    checkedDecodedPlanes.lumaPlane(),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV420 -> convertOpaqueLongI420(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV422 -> convertOpaqueLongI422(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV444 -> convertOpaqueLongI444(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
        };
    }

    /// Validates that one decoded-plane snapshot is supported by 8-bit output.
    ///
    /// @param decodedPlanes the decoded planes to validate
    /// @return the validated decoded planes
    private static DecodedSurface requireIntOutputDecodedPlanes(DecodedSurface decodedPlanes) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        if (checkedDecodedPlanes.bitDepth() != 8
                && checkedDecodedPlanes.bitDepth() != 10
                && checkedDecodedPlanes.bitDepth() != 12
                && checkedDecodedPlanes.bitDepth() != 16) {
            throw new IllegalArgumentException(
                    "8-bit ARGB output requires 8-bit, 10-bit, 12-bit, or 16-bit decoded planes: "
                            + checkedDecodedPlanes.bitDepth()
            );
        }
        return checkedDecodedPlanes;
    }

    /// Validates that one decoded-plane snapshot is supported by high-bit-depth output.
    ///
    /// @param decodedPlanes the decoded planes to validate
    /// @return the validated decoded planes
    private static DecodedSurface requireLongOutputDecodedPlanes(DecodedSurface decodedPlanes) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        if (checkedDecodedPlanes.bitDepth() != 8
                && checkedDecodedPlanes.bitDepth() != 10
                && checkedDecodedPlanes.bitDepth() != 12
                && checkedDecodedPlanes.bitDepth() != 16) {
            throw new IllegalArgumentException(
                    "High-bit-depth ARGB output requires 8-bit, 10-bit, 12-bit, or 16-bit decoded planes: "
                            + checkedDecodedPlanes.bitDepth()
            );
        }
        return checkedDecodedPlanes;
    }

    /// Validates one output size and returns its pixel count.
    ///
    /// @param outputWidth the output width
    /// @param outputHeight the output height
    /// @return the output pixel count
    private static int checkedPixelCount(int outputWidth, int outputHeight) {
        try {
            return Math.multiplyExact(outputWidth, outputHeight);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("output area is too large", exception);
        }
    }

    /// Returns one required chroma plane or throws an explanatory failure.
    ///
    /// @param plane the candidate chroma plane
    /// @param name the argument name used in the failure message
    /// @return the required chroma plane
    private static PaddedPlane requireChromaPlane(PaddedPlane plane, String name) {
        if (plane == null) {
            throw new IllegalArgumentException(name + " is required for chroma ARGB output");
        }
        return plane;
    }

    /// Converts one monochrome plane snapshot into opaque grayscale ARGB pixels.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for grayscale expansion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI400(
            PaddedPlane lumaPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        ShortBuffer lumaSamples = lumaPlane.sampleBuffer();
        int lumaStride = lumaPlane.stride();
        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int pixelRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueGrayArgb(lumaSamples.get(lumaRow + x) & 0xFFFF, bitDepth);
            }
        }
        return pixels;
    }

    /// Converts one monochrome plane snapshot into opaque 16-bit-per-channel grayscale ARGB pixels.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for grayscale expansion
    /// @return the filled destination pixel buffer
    private static long[] convertOpaqueLongI400(
            PaddedPlane lumaPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            long[] pixels,
            YuvToRgbTransform transform
    ) {
        ShortBuffer lumaSamples = lumaPlane.sampleBuffer();
        int lumaStride = lumaPlane.stride();
        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int pixelRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueGrayArgb64(lumaSamples.get(lumaRow + x) & 0xFFFF, bitDepth);
            }
        }
        return pixels;
    }

    /// Converts one `YUV420` plane snapshot into opaque ARGB pixels with point-sampled chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI420(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        ShortBuffer lumaSamples = lumaPlane.sampleBuffer();
        ShortBuffer chromaUSamples = chromaUPlane.sampleBuffer();
        ShortBuffer chromaVSamples = chromaVPlane.sampleBuffer();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = (y >> 1) * chromaUStride;
            int chromaVRow = (y >> 1) * chromaVStride;
            int pixelRow = y * outputWidth;

            int x = 0;
            for (; x + 1 < outputWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples.get(chromaIndexU) & 0xFFFF;
                int vSample = chromaVSamples.get(chromaIndexV) & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples.get(lumaRow + x) & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb(
                        lumaSamples.get(lumaRow + x + 1) & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
            }

            if (x < outputWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples.get(lumaRow + x) & 0xFFFF,
                        chromaUSamples.get(chromaIndexU) & 0xFFFF,
                        chromaVSamples.get(chromaIndexV) & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV420` plane snapshot into opaque 16-bit-per-channel ARGB pixels with
    /// point-sampled chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static long[] convertOpaqueLongI420(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            long[] pixels,
            YuvToRgbTransform transform
    ) {
        ShortBuffer lumaSamples = lumaPlane.sampleBuffer();
        ShortBuffer chromaUSamples = chromaUPlane.sampleBuffer();
        ShortBuffer chromaVSamples = chromaVPlane.sampleBuffer();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = (y >> 1) * chromaUStride;
            int chromaVRow = (y >> 1) * chromaVStride;
            int pixelRow = y * outputWidth;

            int x = 0;
            for (; x + 1 < outputWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples.get(chromaIndexU) & 0xFFFF;
                int vSample = chromaVSamples.get(chromaIndexV) & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb64(lumaSamples.get(lumaRow + x) & 0xFFFF, uSample, vSample, bitDepth);
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb64(
                        lumaSamples.get(lumaRow + x + 1) & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
            }

            if (x < outputWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                        lumaSamples.get(lumaRow + x) & 0xFFFF,
                        chromaUSamples.get(chromaIndexU) & 0xFFFF,
                        chromaVSamples.get(chromaIndexV) & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV422` plane snapshot into opaque ARGB pixels with horizontally shared chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI422(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        ShortBuffer lumaSamples = lumaPlane.sampleBuffer();
        ShortBuffer chromaUSamples = chromaUPlane.sampleBuffer();
        ShortBuffer chromaVSamples = chromaVPlane.sampleBuffer();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = y * chromaUStride;
            int chromaVRow = y * chromaVStride;
            int pixelRow = y * outputWidth;

            int x = 0;
            for (; x + 1 < outputWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples.get(chromaIndexU) & 0xFFFF;
                int vSample = chromaVSamples.get(chromaIndexV) & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples.get(lumaRow + x) & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb(
                        lumaSamples.get(lumaRow + x + 1) & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
            }

            if (x < outputWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples.get(lumaRow + x) & 0xFFFF,
                        chromaUSamples.get(chromaIndexU) & 0xFFFF,
                        chromaVSamples.get(chromaIndexV) & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV422` plane snapshot into opaque 16-bit-per-channel ARGB pixels with
    /// horizontally shared chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static long[] convertOpaqueLongI422(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            long[] pixels,
            YuvToRgbTransform transform
    ) {
        ShortBuffer lumaSamples = lumaPlane.sampleBuffer();
        ShortBuffer chromaUSamples = chromaUPlane.sampleBuffer();
        ShortBuffer chromaVSamples = chromaVPlane.sampleBuffer();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = y * chromaUStride;
            int chromaVRow = y * chromaVStride;
            int pixelRow = y * outputWidth;

            int x = 0;
            for (; x + 1 < outputWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples.get(chromaIndexU) & 0xFFFF;
                int vSample = chromaVSamples.get(chromaIndexV) & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb64(lumaSamples.get(lumaRow + x) & 0xFFFF, uSample, vSample, bitDepth);
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb64(
                        lumaSamples.get(lumaRow + x + 1) & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
            }

            if (x < outputWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                        lumaSamples.get(lumaRow + x) & 0xFFFF,
                        chromaUSamples.get(chromaIndexU) & 0xFFFF,
                        chromaVSamples.get(chromaIndexV) & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV444` plane snapshot into opaque ARGB pixels with one chroma sample per pixel.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI444(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        ShortBuffer lumaSamples = lumaPlane.sampleBuffer();
        ShortBuffer chromaUSamples = chromaUPlane.sampleBuffer();
        ShortBuffer chromaVSamples = chromaVPlane.sampleBuffer();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = y * chromaUStride;
            int chromaVRow = y * chromaVStride;
            int pixelRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples.get(lumaRow + x) & 0xFFFF,
                        chromaUSamples.get(chromaURow + x) & 0xFFFF,
                        chromaVSamples.get(chromaVRow + x) & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV444` plane snapshot into opaque 16-bit-per-channel ARGB pixels with one
    /// chroma sample per luma sample.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static long[] convertOpaqueLongI444(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            long[] pixels,
            YuvToRgbTransform transform
    ) {
        ShortBuffer lumaSamples = lumaPlane.sampleBuffer();
        ShortBuffer chromaUSamples = chromaUPlane.sampleBuffer();
        ShortBuffer chromaVSamples = chromaVPlane.sampleBuffer();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = y * chromaUStride;
            int chromaVRow = y * chromaVStride;
            int pixelRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                        lumaSamples.get(lumaRow + x) & 0xFFFF,
                        chromaUSamples.get(chromaURow + x) & 0xFFFF,
                        chromaVSamples.get(chromaVRow + x) & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }
}
