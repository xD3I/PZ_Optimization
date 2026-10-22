// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.postfilter;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;

import java.util.Objects;

/// Applies the normative AV1 horizontal super-resolution stage to decoded planes.
///
/// This stage consumes coded-width samples after loop filtering and CDEF and produces the
/// upscaled-width sample domain consumed by loop restoration and stored reference surfaces.

final class SuperResolutionUpscaler {
    /// The number of coefficients in one normative super-resolution filter.
    private static final int FILTER_TAP_COUNT = 8;

    /// The normative super-resolution filter normalization shift.
    private static final int FILTER_BITS = 7;

    /// The number of AV1 super-resolution fractional phase bits.
    private static final int SUBPEL_BITS = 6;

    /// The fixed-point precision used by AV1 super-resolution coordinate stepping.
    private static final int SCALE_SUBPEL_BITS = 14;

    /// The mask for one AV1 super-resolution fixed-point coordinate.
    private static final int SCALE_SUBPEL_MASK = (1 << SCALE_SUBPEL_BITS) - 1;

    /// The extra fixed-point bits above the normative filter phase precision.
    private static final int SCALE_EXTRA_BITS = SCALE_SUBPEL_BITS - SUBPEL_BITS;

    /// The half-phase offset used when deriving the first output sample position.
    private static final int SCALE_EXTRA_OFFSET = 1 << (SCALE_EXTRA_BITS - 1);

    /// The signed source-sample offset of the first filter tap.
    ///
    /// The normative convolution receives the source pointer one sample before the coded tile
    /// origin and then applies the usual three-sample filter offset.
    private static final int FILTER_START_OFFSET = FILTER_TAP_COUNT / 2;

    /// The AV1 normative 64-phase horizontal super-resolution filters.
    private static final int [] [] FILTERS = {
            {0, 0, 0, 128, 0, 0, 0, 0},
            {0, 0, -1, 128, 2, -1, 0, 0},
            {0, 1, -3, 127, 4, -2, 1, 0},
            {0, 1, -4, 127, 6, -3, 1, 0},
            {0, 2, -6, 126, 8, -3, 1, 0},
            {0, 2, -7, 125, 11, -4, 1, 0},
            {-1, 2, -8, 125, 13, -5, 2, 0},
            {-1, 3, -9, 124, 15, -6, 2, 0},
            {-1, 3, -10, 123, 18, -6, 2, -1},
            {-1, 3, -11, 122, 20, -7, 3, -1},
            {-1, 4, -12, 121, 22, -8, 3, -1},
            {-1, 4, -13, 120, 25, -9, 3, -1},
            {-1, 4, -14, 118, 28, -9, 3, -1},
            {-1, 4, -15, 117, 30, -10, 4, -1},
            {-1, 5, -16, 116, 32, -11, 4, -1},
            {-1, 5, -16, 114, 35, -12, 4, -1},
            {-1, 5, -17, 112, 38, -12, 4, -1},
            {-1, 5, -18, 111, 40, -13, 5, -1},
            {-1, 5, -18, 109, 43, -14, 5, -1},
            {-1, 6, -19, 107, 45, -14, 5, -1},
            {-1, 6, -19, 105, 48, -15, 5, -1},
            {-1, 6, -19, 103, 51, -16, 5, -1},
            {-1, 6, -20, 101, 53, -16, 6, -1},
            {-1, 6, -20, 99, 56, -17, 6, -1},
            {-1, 6, -20, 97, 58, -17, 6, -1},
            {-1, 6, -20, 95, 61, -18, 6, -1},
            {-2, 7, -20, 93, 64, -18, 6, -2},
            {-2, 7, -20, 91, 66, -19, 6, -1},
            {-2, 7, -20, 88, 69, -19, 6, -1},
            {-2, 7, -20, 86, 71, -19, 6, -1},
            {-2, 7, -20, 84, 74, -20, 7, -2},
            {-2, 7, -20, 81, 76, -20, 7, -1},
            {-2, 7, -20, 79, 79, -20, 7, -2},
            {-1, 7, -20, 76, 81, -20, 7, -2},
            {-2, 7, -20, 74, 84, -20, 7, -2},
            {-1, 6, -19, 71, 86, -20, 7, -2},
            {-1, 6, -19, 69, 88, -20, 7, -2},
            {-1, 6, -19, 66, 91, -20, 7, -2},
            {-2, 6, -18, 64, 93, -20, 7, -2},
            {-1, 6, -18, 61, 95, -20, 6, -1},
            {-1, 6, -17, 58, 97, -20, 6, -1},
            {-1, 6, -17, 56, 99, -20, 6, -1},
            {-1, 6, -16, 53, 101, -20, 6, -1},
            {-1, 5, -16, 51, 103, -19, 6, -1},
            {-1, 5, -15, 48, 105, -19, 6, -1},
            {-1, 5, -14, 45, 107, -19, 6, -1},
            {-1, 5, -14, 43, 109, -18, 5, -1},
            {-1, 5, -13, 40, 111, -18, 5, -1},
            {-1, 4, -12, 38, 112, -17, 5, -1},
            {-1, 4, -12, 35, 114, -16, 5, -1},
            {-1, 4, -11, 32, 116, -16, 5, -1},
            {-1, 4, -10, 30, 117, -15, 4, -1},
            {-1, 3, -9, 28, 118, -14, 4, -1},
            {-1, 3, -9, 25, 120, -13, 4, -1},
            {-1, 3, -8, 22, 121, -12, 4, -1},
            {-1, 3, -7, 20, 122, -11, 3, -1},
            {-1, 2, -6, 18, 123, -10, 3, -1},
            {0, 2, -6, 15, 124, -9, 3, -1},
            {0, 2, -5, 13, 125, -8, 2, -1},
            {0, 1, -4, 11, 125, -7, 2, 0},
            {0, 1, -3, 8, 126, -6, 2, 0},
            {0, 1, -3, 6, 127, -4, 1, 0},
            {0, 1, -2, 4, 127, -3, 1, 0},
            {0, 0, -1, 2, 128, -1, 0, 0}
    };

    /// Prevents instantiation of this stateless upscaler.
    private SuperResolutionUpscaler() {
    }

    /// Applies super-resolution when enabled by the supplied frame header.
    ///
    /// The input must use the frame's coded width and height. When super-resolution is disabled,
    /// this method returns the input object unchanged.
    ///
    /// @param decodedPlanes the decoded planes after CDEF in the coded-width domain
    /// @param frameHeader the frame header that owns the decoded planes
    /// @return the input planes or a new post-super-resolution snapshot
    static DecodedSurface apply(DecodedSurface decodedPlanes, FrameHeader frameHeader) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        if (!checkedFrameHeader.superResolution().enabled()) {
            return checkedDecodedPlanes;
        }

        FrameHeader.FrameSize frameSize = checkedFrameHeader.frameSize();
        if (checkedDecodedPlanes.codedWidth() != frameSize.codedWidth()
                || checkedDecodedPlanes.codedHeight() != frameSize.height()) {
            throw new IllegalArgumentException("Decoded plane dimensions do not match the coded frame dimensions");
        }

        int upscaledWidth = frameSize.upscaledWidth();
        Av1ChromaFormat chromaFormat = checkedDecodedPlanes.chromaFormat();
        int bitDepth = checkedDecodedPlanes.bitDepth();
        PaddedPlane upscaledLumaPlane = upscalePlaneHorizontally(
                checkedDecodedPlanes.lumaPlane(),
                upscaledWidth,
                bitDepth,
                8
        );
        PaddedPlane chromaUPlane = checkedDecodedPlanes.chromaUPlane();
        PaddedPlane chromaVPlane = checkedDecodedPlanes.chromaVPlane();
        PaddedPlane upscaledChromaUPlane = chromaUPlane != null
                ? upscalePlaneHorizontally(
                        chromaUPlane,
                        chromaWidth(chromaFormat, upscaledWidth),
                        bitDepth,
                        chromaFormat == Av1ChromaFormat.YUV444 ? 8 : 4
                )
                : null;
        PaddedPlane upscaledChromaVPlane = chromaVPlane != null
                ? upscalePlaneHorizontally(
                        chromaVPlane,
                        chromaWidth(chromaFormat, upscaledWidth),
                        bitDepth,
                        chromaFormat == Av1ChromaFormat.YUV444 ? 8 : 4
                )
                : null;
        return new DecodedSurface(
                bitDepth,
                chromaFormat,
                upscaledWidth,
                frameSize.height(),
                frameSize.renderWidth(),
                frameSize.renderHeight(),
                upscaledLumaPlane,
                upscaledChromaUPlane,
                upscaledChromaVPlane
        );
    }

    /// Returns the post-super-resolution chroma width for one chroma format.
    ///
    /// @param chromaFormat the active decoded chroma layout
    /// @param lumaWidth the post-super-resolution luma width
    /// @return the post-super-resolution chroma width
    private static int chromaWidth(Av1ChromaFormat chromaFormat, int lumaWidth) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420, YUV422 -> (lumaWidth + 1) >> 1;
            case YUV444 -> lumaWidth;
        };
    }

    /// Upscales one decoded plane horizontally with the normative filter.
    ///
    /// @param plane the decoded plane to upscale
    /// @param targetWidth the post-upscale plane width
    /// @param bitDepth the decoded sample bit depth
    /// @param miWidth the plane-local width of one AV1 eight-luma-sample frame-grid unit
    /// @return one horizontally upscaled decoded plane
    private static PaddedPlane upscalePlaneHorizontally(
            PaddedPlane plane,
            int targetWidth,
            int bitDepth,
            int miWidth
    ) {
        PaddedPlane checkedPlane = Objects.requireNonNull(plane, "plane");
        if (targetWidth <= 0) {
            throw new IllegalArgumentException("targetWidth <= 0: " + targetWidth);
        }
        if (targetWidth == checkedPlane.width()) {
            return checkedPlane;
        }
        if (targetWidth < checkedPlane.width()) {
            throw new IllegalArgumentException(
                    "targetWidth is smaller than the source width: " + targetWidth + " < " + checkedPlane.width()
            );
        }

        short[] upscaledSamples = new short[targetWidth * checkedPlane.height()];
        int sourceWidth = checkedPlane.width();
        int sourceStride = checkedPlane.stride();
        int sourceProcessingWidth = Math.min(sourceStride, roundUp(sourceWidth, miWidth));
        int maximumSample = (1 << bitDepth) - 1;
        int step = convolveStep(sourceWidth, targetWidth);
        int firstPosition = convolveInitialPosition(sourceWidth, targetWidth, step);

        for (int y = 0; y < checkedPlane.height(); y++) {
            int upscaledRowOffset = y * targetWidth;
            int position = firstPosition;
            for (int x = 0; x < targetWidth; x++) {
                int integerPosition = position >> SCALE_SUBPEL_BITS;
                int filterIndex = (position & SCALE_SUBPEL_MASK) >> SCALE_EXTRA_BITS;
                int[] filter = FILTERS[filterIndex];
                long sum = 0;
                for (int tap = 0; tap < FILTER_TAP_COUNT; tap++) {
                    int sourceX = clamp(
                            integerPosition + tap - FILTER_START_OFFSET,
                            0,
                            sourceProcessingWidth - 1
                    );
                    int sourceSample = checkedPlane.storedSample(sourceX, y);
                    sum += (long) sourceSample * filter[tap];
                }
                int filteredSample = roundShift(sum, FILTER_BITS);
                upscaledSamples[upscaledRowOffset + x] = (short) clamp(filteredSample, 0, maximumSample);
                position += step;
            }
        }

        return PaddedPlane.fromOwnedSamples(targetWidth, checkedPlane.height(), targetWidth, upscaledSamples);
    }

    /// Returns the fixed-point step for one source and target width.
    ///
    /// @param sourceWidth the downscaled coded-domain source width
    /// @param targetWidth the post-super-resolution target width
    /// @return the fixed-point horizontal step
    private static int convolveStep(int sourceWidth, int targetWidth) {
        return (int) ((((long) sourceWidth << SCALE_SUBPEL_BITS) + (targetWidth >> 1)) / targetWidth);
    }

    /// Returns the fixed-point position for the first output sample.
    ///
    /// @param sourceWidth the downscaled coded-domain source width
    /// @param targetWidth the post-super-resolution target width
    /// @param step the fixed-point horizontal step
    /// @return the initial fixed-point horizontal position
    private static int convolveInitialPosition(int sourceWidth, int targetWidth, int step) {
        long error = (long) targetWidth * step - ((long) sourceWidth << SCALE_SUBPEL_BITS);
        int position = (int) (((-((long) targetWidth - sourceWidth) << (SCALE_SUBPEL_BITS - 1))
                + (targetWidth >> 1)) / targetWidth)
                + SCALE_EXTRA_OFFSET
                - (int) (error / 2);
        return position & SCALE_SUBPEL_MASK;
    }

    /// Rounds one positive dimension up to a power-of-two alignment.
    ///
    /// @param value the positive dimension
    /// @param alignment the power-of-two alignment
    /// @return the aligned dimension
    private static int roundUp(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    /// Applies AV1 `Round2` to one value using an arithmetic right shift.
    ///
    /// @param value the value to round
    /// @param bits the number of low bits to discard
    /// @return the rounded value
    private static int roundShift(long value, int bits) {
        return Math.toIntExact((value + (1L << (bits - 1))) >> bits);
    }

    /// Clamps one integer value to inclusive bounds.
    ///
    /// @param value the value to clamp
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
