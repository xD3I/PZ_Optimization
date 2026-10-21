// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.av1;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.AvifBitDepth;

import java.util.Objects;

/// Describes the color and chroma configuration declared by an AV1 sequence header.
///
/// Integer color-description values use the code points defined by the AV1 bitstream
/// specification. When [#colorDescriptionPresent()] is `false`, those values contain the AV1
/// defaults parsed for the sequence rather than externally supplied metadata.
///
/// @param bitDepth the decoded AV1 bit depth
/// @param monochrome whether the sequence is monochrome
/// @param colorDescriptionPresent whether explicit color description fields are present
/// @param colorPrimaries the AV1 color primaries code
/// @param transferCharacteristics the AV1 transfer characteristics code
/// @param matrixCoefficients the AV1 matrix coefficients code
/// @param colorRange whether full-range color samples are used
/// @param chromaFormat the decoded chroma layout
/// @param chromaSamplePosition the AV1 chroma sample position code
/// @param chromaSubsamplingX whether chroma is subsampled horizontally
/// @param chromaSubsamplingY whether chroma is subsampled vertically
/// @param separateUvDeltaQ whether separate UV delta quantization is enabled

public record Av1ColorConfig(
        AvifBitDepth bitDepth,
        boolean monochrome,
        boolean colorDescriptionPresent,
        int colorPrimaries,
        int transferCharacteristics,
        int matrixCoefficients,
        boolean colorRange,
        Av1ChromaFormat chromaFormat,
        int chromaSamplePosition,
        boolean chromaSubsamplingX,
        boolean chromaSubsamplingY,
        boolean separateUvDeltaQ
) {
    /// Creates an AV1 color configuration.
    public Av1ColorConfig {
        Objects.requireNonNull(bitDepth, "bitDepth");
        Objects.requireNonNull(chromaFormat, "chromaFormat");
        if (bitDepth == AvifBitDepth.SIXTEEN_BITS) {
            throw new IllegalArgumentException("AV1 does not support 16-bit coded samples");
        }
        checkByteCodePoint(colorPrimaries, "colorPrimaries");
        checkByteCodePoint(transferCharacteristics, "transferCharacteristics");
        checkByteCodePoint(matrixCoefficients, "matrixCoefficients");
        if (chromaSamplePosition < 0 || chromaSamplePosition > 3) {
            throw new IllegalArgumentException("chromaSamplePosition out of range: " + chromaSamplePosition);
        }
        if (monochrome != (chromaFormat == Av1ChromaFormat.MONOCHROME)) {
            throw new IllegalArgumentException("monochrome and chromaFormat are inconsistent");
        }
        boolean expectedSubsamplingX = chromaFormat != Av1ChromaFormat.YUV444;
        boolean expectedSubsamplingY = chromaFormat == Av1ChromaFormat.MONOCHROME
                || chromaFormat == Av1ChromaFormat.YUV420;
        if (chromaSubsamplingX != expectedSubsamplingX || chromaSubsamplingY != expectedSubsamplingY) {
            throw new IllegalArgumentException("chromaFormat and subsampling flags are inconsistent");
        }
    }

    /// Creates an AV1 color configuration from a numeric bit depth.
    ///
    /// @param bitDepth the decoded AV1 bit count
    /// @param monochrome whether the sequence is monochrome
    /// @param colorDescriptionPresent whether explicit color description fields are present
    /// @param colorPrimaries the AV1 color primaries code
    /// @param transferCharacteristics the AV1 transfer characteristics code
    /// @param matrixCoefficients the AV1 matrix coefficients code
    /// @param colorRange whether full-range color samples are used
    /// @param chromaFormat the decoded chroma layout
    /// @param chromaSamplePosition the AV1 chroma sample position code
    /// @param chromaSubsamplingX whether chroma is subsampled horizontally
    /// @param chromaSubsamplingY whether chroma is subsampled vertically
    /// @param separateUvDeltaQ whether separate UV delta quantization is enabled
    public Av1ColorConfig(
            int bitDepth,
            boolean monochrome,
            boolean colorDescriptionPresent,
            int colorPrimaries,
            int transferCharacteristics,
            int matrixCoefficients,
            boolean colorRange,
            Av1ChromaFormat chromaFormat,
            int chromaSamplePosition,
            boolean chromaSubsamplingX,
            boolean chromaSubsamplingY,
            boolean separateUvDeltaQ
    ) {
        this(AvifBitDepth.fromBits(bitDepth), monochrome, colorDescriptionPresent, colorPrimaries,
                transferCharacteristics, matrixCoefficients, colorRange, chromaFormat,
                chromaSamplePosition, chromaSubsamplingX, chromaSubsamplingY, separateUvDeltaQ);
    }

    /// Validates an AV1 eight-bit color-description code point.
    ///
    /// @param value the code point
    /// @param name the component name
    private static void checkByteCodePoint(int value, String name) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException(name + " out of range: " + value);
        }
    }
}
