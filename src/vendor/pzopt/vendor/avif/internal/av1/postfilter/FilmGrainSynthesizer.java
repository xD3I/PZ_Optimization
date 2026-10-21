// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.postfilter;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;
import pzopt.vendor.avif.av1.Av1ColorConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/// Applies AV1 film-grain synthesis in the dav1d scalar order.
///
/// Stored reference surfaces remain post-filter and pre-grain. Synthesis is applied only when one
/// frame is presented, using the normative Gaussian sequence, auto-regressive grain LUTs,
/// per-32x32 block offsets, optional overlap blending, and per-plane scaling functions.

public final class FilmGrainSynthesizer {
    /// Prevents instantiation of this stateless synthesizer.
    private FilmGrainSynthesizer() {
    }

    /// The luma grain lookup width.
    private static final int GRAIN_WIDTH = 82;

    /// The luma grain lookup height.
    private static final int GRAIN_HEIGHT = 73;

    /// The subsampled chroma grain lookup width.
    private static final int SUBSAMPLED_GRAIN_WIDTH = 44;

    /// The subsampled chroma grain lookup height.
    private static final int SUBSAMPLED_GRAIN_HEIGHT = 38;

    /// The AV1 film-grain application block size in luma samples.
    private static final int BLOCK_SIZE = 32;

    /// The border skipped before auto-regressive filtering inside grain lookup tables.
    private static final int AUTO_REGRESSIVE_PAD = 3;

    /// The Cb plane index used by AV1 film-grain syntax.
    private static final int CHROMA_CB = 0;

    /// The Cr plane index used by AV1 film-grain syntax.
    private static final int CHROMA_CR = 1;

    /// The XOR seed offset for the Cb grain table.
    private static final int CHROMA_CB_SEED_OFFSET = 0xB524;

    /// The XOR seed offset for the Cr grain table.
    private static final int CHROMA_CR_SEED_OFFSET = 0x49D8;

    /// The AV1 matrix-coefficients code whose chroma planes use the luma restricted range.
    private static final int MATRIX_IDENTITY = 0;

    /// The compressed AV1 Gaussian sequence from the AV1 specification.
    private static final String GAUSSIAN_SEQUENCE_GZIP_BASE64 = """
            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
            """;

    /// The decoded AV1 Gaussian sequence.
    private static final int [] GAUSSIAN_SEQUENCE = decodeGaussianSequence();

    /// Applies film grain to one post-filter decoded frame.
    ///
    /// @param decodedPlanes the post-filter, pre-grain planes to present
    /// @param frameHeader the normalized frame header that owns the grain parameters
    /// @param colorConfig the active sequence color configuration
    /// @return the grain-applied presentation planes
    public static DecodedSurface apply(
            DecodedSurface decodedPlanes,
            FrameHeader frameHeader,
            Av1ColorConfig colorConfig
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        Av1ColorConfig checkedColorConfig = Objects.requireNonNull(colorConfig, "colorConfig");
        FrameHeader.FilmGrainParams filmGrain = checkedFrameHeader.filmGrain();
        if (!filmGrain.applyGrain()) {
            return checkedDecodedPlanes;
        }

        int bitDepth = checkedDecodedPlanes.bitDepth();
        int[] lumaScaling = generateScaling(bitDepth, filmGrain.yPoints());
        int[][] lumaGrain = generateLumaGrain(bitDepth, filmGrain);
        PaddedPlane lumaPlane = filmGrain.yPoints().length != 0
                ? applyLumaGrain(checkedDecodedPlanes.lumaPlane(), bitDepth, filmGrain, lumaScaling, lumaGrain)
                : checkedDecodedPlanes.lumaPlane();

        PaddedPlane chromaUPlane = checkedDecodedPlanes.chromaUPlane();
        PaddedPlane chromaVPlane = checkedDecodedPlanes.chromaVPlane();
        if (checkedDecodedPlanes.hasChroma()) {
            Av1ChromaFormat chromaFormat = checkedDecodedPlanes.chromaFormat();
            int subsamplingX = chromaSubsamplingX(chromaFormat);
            int subsamplingY = chromaSubsamplingY(chromaFormat);
            boolean identityMatrixCoefficients = checkedColorConfig.matrixCoefficients() == MATRIX_IDENTITY;
            PaddedPlane sourceLumaPlane = checkedDecodedPlanes.lumaPlane();

            PaddedPlane sourceChromaUPlane = Objects.requireNonNull(chromaUPlane, "chromaUPlane");
            PaddedPlane sourceChromaVPlane = Objects.requireNonNull(chromaVPlane, "chromaVPlane");
            chromaUPlane = applyChromaPlaneIfNeeded(
                    sourceChromaUPlane,
                    sourceLumaPlane,
                    bitDepth,
                    filmGrain,
                    lumaScaling,
                    lumaGrain,
                    CHROMA_CB,
                    subsamplingX,
                    subsamplingY,
                    identityMatrixCoefficients
            );
            chromaVPlane = applyChromaPlaneIfNeeded(
                    sourceChromaVPlane,
                    sourceLumaPlane,
                    bitDepth,
                    filmGrain,
                    lumaScaling,
                    lumaGrain,
                    CHROMA_CR,
                    subsamplingX,
                    subsamplingY,
                    identityMatrixCoefficients
            );
        }

        return new DecodedSurface(
                bitDepth,
                checkedDecodedPlanes.chromaFormat(),
                checkedDecodedPlanes.codedWidth(),
                checkedDecodedPlanes.codedHeight(),
                checkedDecodedPlanes.renderWidth(),
                checkedDecodedPlanes.renderHeight(),
                lumaPlane,
                chromaUPlane,
                chromaVPlane
        );
    }

    /// Applies chroma grain when the selected chroma plane has an active scaling source.
    ///
    /// @param chromaPlane the post-filter chroma plane
    /// @param lumaPlane the post-filter luma plane
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @param lumaScaling the generated luma scaling table
    /// @param lumaGrain the generated luma grain lookup table
    /// @param chromaPlaneIndex the AV1 chroma plane index, `0` for Cb and `1` for Cr
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param identityMatrixCoefficients whether chroma uses the luma restricted range
    /// @return the grain-applied chroma plane, or the source plane when it has no active grain
    private static PaddedPlane applyChromaPlaneIfNeeded(
            PaddedPlane chromaPlane,
            PaddedPlane lumaPlane,
            int bitDepth,
            FrameHeader.FilmGrainParams filmGrain,
            int[] lumaScaling,
            int[][] lumaGrain,
            int chromaPlaneIndex,
            int subsamplingX,
            int subsamplingY,
            boolean identityMatrixCoefficients
    ) {
        FrameHeader.FilmGrainPoint[] points = chromaPlaneIndex == CHROMA_CB
                ? filmGrain.cbPoints()
                : filmGrain.crPoints();
        if (!filmGrain.chromaScalingFromLuma() && points.length == 0) {
            return chromaPlane;
        }

        int[] scaling = filmGrain.chromaScalingFromLuma()
                ? lumaScaling
                : generateScaling(bitDepth, points);
        int[][] chromaGrain = generateChromaGrain(
                bitDepth,
                filmGrain,
                lumaGrain,
                chromaPlaneIndex,
                subsamplingX,
                subsamplingY
        );
        return applyChromaGrain(
                chromaPlane,
                lumaPlane,
                bitDepth,
                filmGrain,
                scaling,
                chromaGrain,
                chromaPlaneIndex,
                subsamplingX,
                subsamplingY,
                identityMatrixCoefficients
        );
    }

    /// Applies generated grain to one luma plane.
    ///
    /// @param lumaPlane the post-filter luma plane
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @param scaling the generated luma scaling table
    /// @param grainLookup the generated luma grain lookup table
    /// @return the grain-applied luma plane
    private static PaddedPlane applyLumaGrain(
            PaddedPlane lumaPlane,
            int bitDepth,
            FrameHeader.FilmGrainParams filmGrain,
            int[] scaling,
            int[][] grainLookup
    ) {
        short[] outputSamples = lumaPlane.samples();
        int rowCount = (lumaPlane.height() + BLOCK_SIZE - 1) / BLOCK_SIZE;
        for (int row = 0; row < rowCount; row++) {
            applyLumaGrainRow(lumaPlane, outputSamples, bitDepth, filmGrain, scaling, grainLookup, row);
        }
        return PaddedPlane.fromOwnedSamples(lumaPlane.width(), lumaPlane.height(), lumaPlane.stride(), outputSamples);
    }

    /// Applies generated grain to one luma film-grain block row.
    ///
    /// @param lumaPlane the post-filter luma plane
    /// @param outputSamples the mutable output samples
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @param scaling the generated luma scaling table
    /// @param grainLookup the generated luma grain lookup table
    /// @param rowNumber the zero-based film-grain block row
    private static void applyLumaGrainRow(
            PaddedPlane lumaPlane,
            short[] outputSamples,
            int bitDepth,
            FrameHeader.FilmGrainParams filmGrain,
            int[] scaling,
            int[][] grainLookup,
            int rowNumber
    ) {
        int blockY = rowNumber * BLOCK_SIZE;
        int blockHeight = Math.min(BLOCK_SIZE, lumaPlane.height() - blockY);
        int rowOffsetCount = filmGrain.overlapEnabled() && rowNumber > 0 ? 2 : 1;
        int[] seeds = createBlockRowSeeds(filmGrain.grainSeed(), rowNumber, rowOffsetCount);
        int[][] offsets = new int[2][2];
        int minimumSample = restrictedRangeMinimum(bitDepth, filmGrain.clipToRestrictedRange());
        int maximumSample = restrictedRangeMaximum(bitDepth, true, filmGrain.clipToRestrictedRange());
        int grainMinimum = -(128 << (bitDepth - 8));
        int grainMaximum = (128 << (bitDepth - 8)) - 1;

        for (int blockX = 0; blockX < lumaPlane.width(); blockX += BLOCK_SIZE) {
            int blockWidth = Math.min(BLOCK_SIZE, lumaPlane.width() - blockX);
            updateOffsets(filmGrain.overlapEnabled(), blockX, seeds, offsets, rowOffsetCount);

            int yStart = filmGrain.overlapEnabled() && rowNumber > 0 ? Math.min(2, blockHeight) : 0;
            int xStart = filmGrain.overlapEnabled() && blockX > 0 ? Math.min(2, blockWidth) : 0;
            for (int y = yStart; y < blockHeight; y++) {
                for (int x = xStart; x < blockWidth; x++) {
                    int grain = sampleLookup(grainLookup, offsets, 0, 0, 0, 0, x, y);
                    addLumaNoise(lumaPlane, outputSamples, blockX, blockY, x, y, scaling, filmGrain, grain,
                            minimumSample, maximumSample);
                }
                for (int x = 0; x < xStart; x++) {
                    int grain = sampleLookup(grainLookup, offsets, 0, 0, 0, 0, x, y);
                    int old = sampleLookup(grainLookup, offsets, 0, 0, 1, 0, x, y);
                    grain = clamp(round2(old * overlapWeight(0, x, true) + grain * overlapWeight(0, x, false), 5),
                            grainMinimum, grainMaximum);
                    addLumaNoise(lumaPlane, outputSamples, blockX, blockY, x, y, scaling, filmGrain, grain,
                            minimumSample, maximumSample);
                }
            }
            for (int y = 0; y < yStart; y++) {
                for (int x = xStart; x < blockWidth; x++) {
                    int grain = sampleLookup(grainLookup, offsets, 0, 0, 0, 0, x, y);
                    int old = sampleLookup(grainLookup, offsets, 0, 0, 0, 1, x, y);
                    grain = clamp(round2(old * overlapWeight(0, y, true) + grain * overlapWeight(0, y, false), 5),
                            grainMinimum, grainMaximum);
                    addLumaNoise(lumaPlane, outputSamples, blockX, blockY, x, y, scaling, filmGrain, grain,
                            minimumSample, maximumSample);
                }
                for (int x = 0; x < xStart; x++) {
                    int top = sampleLookup(grainLookup, offsets, 0, 0, 0, 1, x, y);
                    int old = sampleLookup(grainLookup, offsets, 0, 0, 1, 1, x, y);
                    top = clamp(round2(old * overlapWeight(0, x, true) + top * overlapWeight(0, x, false), 5),
                            grainMinimum, grainMaximum);

                    int grain = sampleLookup(grainLookup, offsets, 0, 0, 0, 0, x, y);
                    old = sampleLookup(grainLookup, offsets, 0, 0, 1, 0, x, y);
                    grain = clamp(round2(old * overlapWeight(0, x, true) + grain * overlapWeight(0, x, false), 5),
                            grainMinimum, grainMaximum);

                    grain = clamp(round2(top * overlapWeight(0, y, true) + grain * overlapWeight(0, y, false), 5),
                            grainMinimum, grainMaximum);
                    addLumaNoise(lumaPlane, outputSamples, blockX, blockY, x, y, scaling, filmGrain, grain,
                            minimumSample, maximumSample);
                }
            }
        }
    }

    /// Applies generated grain to one chroma plane.
    ///
    /// @param chromaPlane the post-filter chroma plane
    /// @param lumaPlane the post-filter luma plane
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @param scaling the active scaling table
    /// @param grainLookup the generated chroma grain lookup table
    /// @param chromaPlaneIndex the AV1 chroma plane index
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param identityMatrixCoefficients whether chroma uses the luma restricted range
    /// @return the grain-applied chroma plane
    private static PaddedPlane applyChromaGrain(
            PaddedPlane chromaPlane,
            PaddedPlane lumaPlane,
            int bitDepth,
            FrameHeader.FilmGrainParams filmGrain,
            int[] scaling,
            int[][] grainLookup,
            int chromaPlaneIndex,
            int subsamplingX,
            int subsamplingY,
            boolean identityMatrixCoefficients
    ) {
        short[] outputSamples = chromaPlane.samples();
        int rowCount = (lumaPlane.height() + BLOCK_SIZE - 1) / BLOCK_SIZE;
        for (int row = 0; row < rowCount; row++) {
            applyChromaGrainRow(
                    chromaPlane,
                    lumaPlane,
                    outputSamples,
                    bitDepth,
                    filmGrain,
                    scaling,
                    grainLookup,
                    chromaPlaneIndex,
                    subsamplingX,
                    subsamplingY,
                    identityMatrixCoefficients,
                    row
            );
        }
        return PaddedPlane.fromOwnedSamples(chromaPlane.width(), chromaPlane.height(), chromaPlane.stride(), outputSamples);
    }

    /// Applies generated grain to one chroma film-grain block row.
    ///
    /// @param chromaPlane the post-filter chroma plane
    /// @param lumaPlane the post-filter luma plane
    /// @param outputSamples the mutable output samples
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @param scaling the active scaling table
    /// @param grainLookup the generated chroma grain lookup table
    /// @param chromaPlaneIndex the AV1 chroma plane index
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param identityMatrixCoefficients whether chroma uses the luma restricted range
    /// @param rowNumber the zero-based film-grain block row
    private static void applyChromaGrainRow(
            PaddedPlane chromaPlane,
            PaddedPlane lumaPlane,
            short[] outputSamples,
            int bitDepth,
            FrameHeader.FilmGrainParams filmGrain,
            int[] scaling,
            int[][] grainLookup,
            int chromaPlaneIndex,
            int subsamplingX,
            int subsamplingY,
            boolean identityMatrixCoefficients,
            int rowNumber
    ) {
        int blockY = (rowNumber * BLOCK_SIZE) >> subsamplingY;
        if (blockY >= chromaPlane.height()) {
            return;
        }

        int lumaRowsInBlock = Math.min(BLOCK_SIZE, lumaPlane.height() - rowNumber * BLOCK_SIZE);
        int blockHeight = (lumaRowsInBlock + subsamplingY) >> subsamplingY;
        int rowOffsetCount = filmGrain.overlapEnabled() && rowNumber > 0 ? 2 : 1;
        int[] seeds = createBlockRowSeeds(filmGrain.grainSeed(), rowNumber, rowOffsetCount);
        int[][] offsets = new int[2][2];
        int minimumSample = restrictedRangeMinimum(bitDepth, filmGrain.clipToRestrictedRange());
        int maximumSample = restrictedRangeMaximum(
                bitDepth,
                identityMatrixCoefficients,
                filmGrain.clipToRestrictedRange()
        );
        int grainMinimum = -(128 << (bitDepth - 8));
        int grainMaximum = (128 << (bitDepth - 8)) - 1;
        int chromaBlockSize = BLOCK_SIZE >> subsamplingX;

        for (int blockX = 0; blockX < chromaPlane.width(); blockX += chromaBlockSize) {
            int blockWidth = Math.min(chromaBlockSize, chromaPlane.width() - blockX);
            updateOffsets(filmGrain.overlapEnabled(), blockX, seeds, offsets, rowOffsetCount);

            int yStart = filmGrain.overlapEnabled() && rowNumber > 0
                    ? Math.min(2 >> subsamplingY, blockHeight)
                    : 0;
            int xStart = filmGrain.overlapEnabled() && blockX > 0
                    ? Math.min(2 >> subsamplingX, blockWidth)
                    : 0;
            for (int y = yStart; y < blockHeight; y++) {
                for (int x = xStart; x < blockWidth; x++) {
                    int grain = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 0, x, y);
                    addChromaNoise(chromaPlane, lumaPlane, outputSamples, blockX, blockY, x, y, scaling,
                            filmGrain, grain, chromaPlaneIndex, subsamplingX, subsamplingY,
                            minimumSample, maximumSample, bitDepth);
                }
                for (int x = 0; x < xStart; x++) {
                    int grain = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 0, x, y);
                    int old = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 1, 0, x, y);
                    grain = clamp(round2(old * overlapWeight(subsamplingX, x, true)
                                    + grain * overlapWeight(subsamplingX, x, false), 5),
                            grainMinimum, grainMaximum);
                    addChromaNoise(chromaPlane, lumaPlane, outputSamples, blockX, blockY, x, y, scaling,
                            filmGrain, grain, chromaPlaneIndex, subsamplingX, subsamplingY,
                            minimumSample, maximumSample, bitDepth);
                }
            }
            for (int y = 0; y < yStart; y++) {
                for (int x = xStart; x < blockWidth; x++) {
                    int grain = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 0, x, y);
                    int old = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 1, x, y);
                    grain = clamp(round2(old * overlapWeight(subsamplingY, y, true)
                                    + grain * overlapWeight(subsamplingY, y, false), 5),
                            grainMinimum, grainMaximum);
                    addChromaNoise(chromaPlane, lumaPlane, outputSamples, blockX, blockY, x, y, scaling,
                            filmGrain, grain, chromaPlaneIndex, subsamplingX, subsamplingY,
                            minimumSample, maximumSample, bitDepth);
                }
                for (int x = 0; x < xStart; x++) {
                    int top = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 1, x, y);
                    int old = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 1, 1, x, y);
                    top = clamp(round2(old * overlapWeight(subsamplingX, x, true)
                                    + top * overlapWeight(subsamplingX, x, false), 5),
                            grainMinimum, grainMaximum);

                    int grain = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 0, x, y);
                    old = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 1, 0, x, y);
                    grain = clamp(round2(old * overlapWeight(subsamplingX, x, true)
                                    + grain * overlapWeight(subsamplingX, x, false), 5),
                            grainMinimum, grainMaximum);

                    grain = clamp(round2(top * overlapWeight(subsamplingY, y, true)
                                    + grain * overlapWeight(subsamplingY, y, false), 5),
                            grainMinimum, grainMaximum);
                    addChromaNoise(chromaPlane, lumaPlane, outputSamples, blockX, blockY, x, y, scaling,
                            filmGrain, grain, chromaPlaneIndex, subsamplingX, subsamplingY,
                            minimumSample, maximumSample, bitDepth);
                }
            }
        }
    }

    /// Adds one luma grain sample to the output plane.
    ///
    /// @param lumaPlane the post-filter luma plane
    /// @param outputSamples the mutable output samples
    /// @param blockX the block's horizontal plane coordinate
    /// @param blockY the block's vertical plane coordinate
    /// @param x the horizontal coordinate inside the block
    /// @param y the vertical coordinate inside the block
    /// @param scaling the generated scaling table
    /// @param filmGrain the normalized film-grain parameters
    /// @param grain the signed grain value
    /// @param minimumSample the inclusive output minimum
    /// @param maximumSample the inclusive output maximum
    private static void addLumaNoise(
            PaddedPlane lumaPlane,
            short[] outputSamples,
            int blockX,
            int blockY,
            int x,
            int y,
            int[] scaling,
            FrameHeader.FilmGrainParams filmGrain,
            int grain,
            int minimumSample,
            int maximumSample
    ) {
        int source = lumaPlane.sample(blockX + x, blockY + y);
        int noise = round2(scaling[source] * grain, filmGrain.scalingShift());
        int offset = (blockY + y) * lumaPlane.stride() + blockX + x;
        outputSamples[offset] = (short) clamp(source + noise, minimumSample, maximumSample);
    }

    /// Adds one chroma grain sample to the output plane.
    ///
    /// @param chromaPlane the post-filter chroma plane
    /// @param lumaPlane the post-filter luma plane
    /// @param outputSamples the mutable output samples
    /// @param blockX the block's horizontal chroma coordinate
    /// @param blockY the block's vertical chroma coordinate
    /// @param x the horizontal coordinate inside the block
    /// @param y the vertical coordinate inside the block
    /// @param scaling the active scaling table
    /// @param filmGrain the normalized film-grain parameters
    /// @param grain the signed grain value
    /// @param chromaPlaneIndex the AV1 chroma plane index
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param minimumSample the inclusive output minimum
    /// @param maximumSample the inclusive output maximum
    /// @param bitDepth the decoded bit depth
    private static void addChromaNoise(
            PaddedPlane chromaPlane,
            PaddedPlane lumaPlane,
            short[] outputSamples,
            int blockX,
            int blockY,
            int x,
            int y,
            int[] scaling,
            FrameHeader.FilmGrainParams filmGrain,
            int grain,
            int chromaPlaneIndex,
            int subsamplingX,
            int subsamplingY,
            int minimumSample,
            int maximumSample,
            int bitDepth
    ) {
        int chromaX = blockX + x;
        int chromaY = blockY + y;
        if (chromaX >= chromaPlane.width() || chromaY >= chromaPlane.height()) {
            return;
        }

        int source = chromaPlane.sample(chromaX, chromaY);
        int lumaX = chromaX << subsamplingX;
        int lumaY = chromaY << subsamplingY;
        int averageLuma = lumaPlane.sample(Math.min(lumaX, lumaPlane.width() - 1), Math.min(lumaY, lumaPlane.height() - 1));
        if (subsamplingX != 0) {
            averageLuma = (averageLuma + lumaPlane.sample(
                    Math.min(lumaX + 1, lumaPlane.width() - 1),
                    Math.min(lumaY, lumaPlane.height() - 1)
            ) + 1) >> 1;
        }

        int scalingIndex = averageLuma;
        if (!filmGrain.chromaScalingFromLuma()) {
            int combined = averageLuma * chromaLumaMultiplier(filmGrain, chromaPlaneIndex)
                    + source * chromaMultiplier(filmGrain, chromaPlaneIndex);
            scalingIndex = clamp(
                    (combined >> 6) + (chromaOffset(filmGrain, chromaPlaneIndex) << (bitDepth - 8)),
                    0,
                    (1 << bitDepth) - 1
            );
        }

        int noise = round2(scaling[scalingIndex] * grain, filmGrain.scalingShift());
        int offset = chromaY * chromaPlane.stride() + chromaX;
        outputSamples[offset] = (short) clamp(source + noise, minimumSample, maximumSample);
    }

    /// Generates the luma grain lookup table.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @return the generated luma grain lookup table
    private static int[][] generateLumaGrain(int bitDepth, FrameHeader.FilmGrainParams filmGrain) {
        int[][] grain = new int[GRAIN_HEIGHT][GRAIN_WIDTH];
        int[] state = new int[]{filmGrain.grainSeed()};
        int shift = 4 - (bitDepth - 8) + filmGrain.grainScaleShift();
        int grainMinimum = -(128 << (bitDepth - 8));
        int grainMaximum = (128 << (bitDepth - 8)) - 1;
        for (int y = 0; y < GRAIN_HEIGHT; y++) {
            for (int x = 0; x < GRAIN_WIDTH; x++) {
                grain[y][x] = round2(GAUSSIAN_SEQUENCE[randomNumber(11, state)], shift);
            }
        }

        int lag = filmGrain.arCoeffLag();
        int[] coefficients = filmGrain.arCoefficientsY();
        if (coefficients.length == 0) {
            return grain;
        }
        for (int y = AUTO_REGRESSIVE_PAD; y < GRAIN_HEIGHT; y++) {
            for (int x = AUTO_REGRESSIVE_PAD; x < GRAIN_WIDTH - AUTO_REGRESSIVE_PAD; x++) {
                int coefficientIndex = 0;
                int sum = 0;
                for (int dy = -lag; dy <= 0; dy++) {
                    boolean reachedCurrent = false;
                    for (int dx = -lag; dx <= lag; dx++) {
                        if (dx == 0 && dy == 0) {
                            reachedCurrent = true;
                            break;
                        }
                        sum += coefficients[coefficientIndex++] * grain[y + dy][x + dx];
                    }
                    if (reachedCurrent) {
                        break;
                    }
                }
                grain[y][x] = clamp(grain[y][x] + round2(sum, filmGrain.arCoeffShift()),
                        grainMinimum, grainMaximum);
            }
        }
        return grain;
    }

    /// Generates one chroma grain lookup table.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @param lumaGrain the generated luma grain lookup table
    /// @param chromaPlaneIndex the AV1 chroma plane index
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @return the generated chroma grain lookup table
    private static int[][] generateChromaGrain(
            int bitDepth,
            FrameHeader.FilmGrainParams filmGrain,
            int[][] lumaGrain,
            int chromaPlaneIndex,
            int subsamplingX,
            int subsamplingY
    ) {
        int grainWidth = subsamplingX != 0 ? SUBSAMPLED_GRAIN_WIDTH : GRAIN_WIDTH;
        int grainHeight = subsamplingY != 0 ? SUBSAMPLED_GRAIN_HEIGHT : GRAIN_HEIGHT;
        int[][] grain = new int[grainHeight][grainWidth];
        int[] state = new int[]{
                filmGrain.grainSeed() ^ (chromaPlaneIndex == CHROMA_CR ? CHROMA_CR_SEED_OFFSET : CHROMA_CB_SEED_OFFSET)
        };
        int shift = 4 - (bitDepth - 8) + filmGrain.grainScaleShift();
        int grainMinimum = -(128 << (bitDepth - 8));
        int grainMaximum = (128 << (bitDepth - 8)) - 1;
        for (int y = 0; y < grainHeight; y++) {
            for (int x = 0; x < grainWidth; x++) {
                grain[y][x] = round2(GAUSSIAN_SEQUENCE[randomNumber(11, state)], shift);
            }
        }

        int lag = filmGrain.arCoeffLag();
        int[] coefficients = chromaPlaneIndex == CHROMA_CB
                ? filmGrain.arCoefficientsCb()
                : filmGrain.arCoefficientsCr();
        boolean hasLumaScalingPoints = filmGrain.yPoints().length != 0;
        for (int y = AUTO_REGRESSIVE_PAD; y < grainHeight; y++) {
            for (int x = AUTO_REGRESSIVE_PAD; x < grainWidth - AUTO_REGRESSIVE_PAD; x++) {
                int coefficientIndex = 0;
                int sum = 0;
                for (int dy = -lag; dy <= 0; dy++) {
                    boolean reachedCurrent = false;
                    for (int dx = -lag; dx <= lag; dx++) {
                        if (dx == 0 && dy == 0) {
                            if (hasLumaScalingPoints) {
                                sum += averagedLumaGrain(lumaGrain, x, y, subsamplingX, subsamplingY)
                                        * coefficients[coefficientIndex];
                            }
                            reachedCurrent = true;
                            break;
                        }
                        sum += coefficients[coefficientIndex++] * grain[y + dy][x + dx];
                    }
                    if (reachedCurrent) {
                        break;
                    }
                }
                grain[y][x] = clamp(grain[y][x] + round2(sum, filmGrain.arCoeffShift()),
                        grainMinimum, grainMaximum);
            }
        }
        return grain;
    }

    /// Returns the averaged colocated luma grain contribution for chroma AR synthesis.
    ///
    /// @param lumaGrain the generated luma grain lookup table
    /// @param x the chroma grain x coordinate
    /// @param y the chroma grain y coordinate
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @return the averaged colocated luma grain value
    private static int averagedLumaGrain(int[][] lumaGrain, int x, int y, int subsamplingX, int subsamplingY) {
        int lumaX = ((x - AUTO_REGRESSIVE_PAD) << subsamplingX) + AUTO_REGRESSIVE_PAD;
        int lumaY = ((y - AUTO_REGRESSIVE_PAD) << subsamplingY) + AUTO_REGRESSIVE_PAD;
        int sum = 0;
        for (int dy = 0; dy <= subsamplingY; dy++) {
            for (int dx = 0; dx <= subsamplingX; dx++) {
                sum += lumaGrain[lumaY + dy][lumaX + dx];
            }
        }
        return round2(sum, subsamplingX + subsamplingY);
    }

    /// Generates one full sample-domain scaling table.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param points the ordered film-grain scaling points
    /// @return one full sample-domain scaling table
    private static int[] generateScaling(int bitDepth, FrameHeader.FilmGrainPoint[] points) {
        int scalingSize = 1 << bitDepth;
        int[] scaling = new int[scalingSize];
        if (points.length == 0) {
            return scaling;
        }

        int shiftX = bitDepth - 8;
        int firstLimit = points[0].value() << shiftX;
        fill(scaling, 0, firstLimit, points[0].scaling());
        for (int index = 0; index < points.length - 1; index++) {
            int startX = points[index].value();
            int startY = points[index].scaling();
            int endX = points[index + 1].value();
            int endY = points[index + 1].scaling();
            int deltaX = endX - startX;
            int deltaY = endY - startY;
            int delta = deltaY * ((0x10000 + (deltaX >> 1)) / deltaX);
            for (int x = 0, d = 0x8000; x < deltaX; x++) {
                scaling[(startX + x) << shiftX] = startY + (d >> 16);
                d += delta;
            }
        }
        int finalStart = points[points.length - 1].value() << shiftX;
        fill(scaling, finalStart, scaling.length, points[points.length - 1].scaling());

        if (shiftX != 0) {
            int pad = 1 << shiftX;
            int round = pad >> 1;
            for (int index = 0; index < points.length - 1; index++) {
                int start = points[index].value() << shiftX;
                int end = points[index + 1].value() << shiftX;
                for (int x = 0; x < end - start; x += pad) {
                    int range = scaling[start + x + pad] - scaling[start + x];
                    for (int n = 1, r = round; n < pad; n++) {
                        r += range;
                        scaling[start + x + n] = scaling[start + x] + (r >> shiftX);
                    }
                }
            }
        }
        return scaling;
    }

    /// Fills a half-open integer range in one array.
    ///
    /// @param array the destination array
    /// @param start the inclusive start index
    /// @param end the exclusive end index
    /// @param value the value to write
    private static void fill(int[] array, int start, int end, int value) {
        for (int index = start; index < end; index++) {
            array[index] = value;
        }
    }

    /// Samples one generated grain lookup table with dav1d block offsets.
    ///
    /// @param grainLookup the generated grain lookup table
    /// @param offsets the current and neighboring block offsets
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param blockOffsetX the neighbor block column offset
    /// @param blockOffsetY the neighbor block row offset
    /// @param x the horizontal coordinate inside the current block
    /// @param y the vertical coordinate inside the current block
    /// @return one signed grain lookup sample
    private static int sampleLookup(
            int[][] grainLookup,
            int[][] offsets,
            int subsamplingX,
            int subsamplingY,
            int blockOffsetX,
            int blockOffsetY,
            int x,
            int y
    ) {
        int randomValue = offsets[blockOffsetX][blockOffsetY];
        int offsetX = AUTO_REGRESSIVE_PAD + (2 >> subsamplingX) * (AUTO_REGRESSIVE_PAD + (randomValue >> 4));
        int offsetY = AUTO_REGRESSIVE_PAD + (2 >> subsamplingY) * (AUTO_REGRESSIVE_PAD + (randomValue & 0xF));
        return grainLookup[offsetY + y + (BLOCK_SIZE >> subsamplingY) * blockOffsetY]
                [offsetX + x + (BLOCK_SIZE >> subsamplingX) * blockOffsetX];
    }

    /// Updates the current block offset cache.
    ///
    /// @param overlapEnabled whether overlap between neighboring blocks is enabled
    /// @param blockX the block's horizontal coordinate
    /// @param seeds the mutable row seeds
    /// @param offsets the mutable block offsets
    /// @param rowOffsetCount the number of active row offsets
    private static void updateOffsets(boolean overlapEnabled, int blockX, int[] seeds, int[][] offsets, int rowOffsetCount) {
        if (overlapEnabled && blockX > 0) {
            for (int row = 0; row < rowOffsetCount; row++) {
                offsets[1][row] = offsets[0][row];
            }
        }
        for (int row = 0; row < rowOffsetCount; row++) {
            offsets[0][row] = randomNumber(8, seeds, row);
        }
    }

    /// Creates the row-local LFSR seeds used to choose block offsets.
    ///
    /// @param grainSeed the frame grain seed
    /// @param rowNumber the current film-grain block row
    /// @param rowOffsetCount the number of active row offsets
    /// @return the row-local LFSR seeds
    private static int[] createBlockRowSeeds(int grainSeed, int rowNumber, int rowOffsetCount) {
        int[] seeds = new int[rowOffsetCount];
        for (int rowOffset = 0; rowOffset < rowOffsetCount; rowOffset++) {
            int seedRow = rowNumber - rowOffset;
            int seed = grainSeed;
            seed ^= (((seedRow * 37 + 178) & 0xFF) << 8);
            seed ^= ((seedRow * 173 + 105) & 0xFF);
            seeds[rowOffset] = seed;
        }
        return seeds;
    }

    /// Returns one pseudo-random number and updates the selected seed.
    ///
    /// @param bits the requested bit count
    /// @param states the mutable LFSR states
    /// @param index the selected state index
    /// @return one pseudo-random number
    private static int randomNumber(int bits, int[] states, int index) {
        int current = states[index];
        int bit = ((current >>> 0) ^ (current >>> 1) ^ (current >>> 3) ^ (current >>> 12)) & 1;
        int next = (current >>> 1) | (bit << 15);
        states[index] = next;
        return (next >>> (16 - bits)) & ((1 << bits) - 1);
    }

    /// Returns one pseudo-random number and updates the seed.
    ///
    /// @param bits the requested bit count
    /// @param state the mutable single-element LFSR state
    /// @return one pseudo-random number
    private static int randomNumber(int bits, int[] state) {
        return randomNumber(bits, state, 0);
    }

    /// Returns the overlap blending weight.
    ///
    /// @param subsampling the active subsampling shift for the blended axis
    /// @param offset the coordinate inside the overlapped area
    /// @param oldSample whether the weight is for the neighboring block
    /// @return the overlap blending weight
    private static int overlapWeight(int subsampling, int offset, boolean oldSample) {
        if (subsampling != 0) {
            return oldSample ? 23 : 22;
        }
        if (offset == 0) {
            return oldSample ? 27 : 17;
        }
        return oldSample ? 17 : 27;
    }

    /// Returns the horizontal chroma subsampling shift for one chroma format.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @return the horizontal chroma subsampling shift
    private static int chromaSubsamplingX(Av1ChromaFormat chromaFormat) {
        return chromaFormat == Av1ChromaFormat.YUV420 || chromaFormat == Av1ChromaFormat.YUV422 ? 1 : 0;
    }

    /// Returns the vertical chroma subsampling shift for one chroma format.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @return the vertical chroma subsampling shift
    private static int chromaSubsamplingY(Av1ChromaFormat chromaFormat) {
        return chromaFormat == Av1ChromaFormat.YUV420 ? 1 : 0;
    }

    /// Returns the chroma component multiplier for one chroma plane.
    ///
    /// @param filmGrain the normalized film-grain parameters
    /// @param chromaPlaneIndex the AV1 chroma plane index
    /// @return the chroma component multiplier
    private static int chromaMultiplier(FrameHeader.FilmGrainParams filmGrain, int chromaPlaneIndex) {
        return chromaPlaneIndex == CHROMA_CB ? filmGrain.cbMult() : filmGrain.crMult();
    }

    /// Returns the chroma luma multiplier for one chroma plane.
    ///
    /// @param filmGrain the normalized film-grain parameters
    /// @param chromaPlaneIndex the AV1 chroma plane index
    /// @return the chroma luma multiplier
    private static int chromaLumaMultiplier(FrameHeader.FilmGrainParams filmGrain, int chromaPlaneIndex) {
        return chromaPlaneIndex == CHROMA_CB ? filmGrain.cbLumaMult() : filmGrain.crLumaMult();
    }

    /// Returns the chroma scaling offset for one chroma plane.
    ///
    /// @param filmGrain the normalized film-grain parameters
    /// @param chromaPlaneIndex the AV1 chroma plane index
    /// @return the chroma scaling offset
    private static int chromaOffset(FrameHeader.FilmGrainParams filmGrain, int chromaPlaneIndex) {
        return chromaPlaneIndex == CHROMA_CB ? filmGrain.cbOffset() : filmGrain.crOffset();
    }

    /// Returns the inclusive minimum sample after optional restricted-range clipping.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param restrictedRange whether restricted-range clipping is enabled
    /// @return the inclusive minimum sample after optional restricted-range clipping
    private static int restrictedRangeMinimum(int bitDepth, boolean restrictedRange) {
        return restrictedRange ? 16 << (bitDepth - 8) : 0;
    }

    /// Returns the inclusive maximum sample after optional restricted-range clipping.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param useLumaRange whether the restricted-range maximum is 235 rather than 240
    /// @param restrictedRange whether restricted-range clipping is enabled
    /// @return the inclusive maximum sample after optional restricted-range clipping
    private static int restrictedRangeMaximum(int bitDepth, boolean useLumaRange, boolean restrictedRange) {
        if (!restrictedRange) {
            return (1 << bitDepth) - 1;
        }
        return (useLumaRange ? 235 : 240) << (bitDepth - 8);
    }

    /// Rounds one signed integer by a power-of-two divisor with positive bias.
    ///
    /// @param value the signed input value
    /// @param shift the divisor shift
    /// @return the rounded value
    private static int round2(int value, int shift) {
        return (value + ((1 << shift) >> 1)) >> shift;
    }

    /// Clamps one signed value into one inclusive integer range.
    ///
    /// @param value the candidate value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Decodes the compressed AV1 Gaussian sequence payload.
    ///
    /// @return the decoded AV1 Gaussian sequence
    private static int [] decodeGaussianSequence() {
        byte[] compressed = Base64.getMimeDecoder().decode(GAUSSIAN_SEQUENCE_GZIP_BASE64);
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length != 4096) {
                throw new IllegalStateException("Decoded Gaussian sequence has length " + bytes.length + ", expected 4096");
            }

            int[] sequence = new int[2048];
            for (int index = 0; index < sequence.length; index++) {
                sequence[index] = (short) ((bytes[index * 2] & 0xFF) | ((bytes[index * 2 + 1] & 0xFF) << 8));
            }
            return sequence;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
