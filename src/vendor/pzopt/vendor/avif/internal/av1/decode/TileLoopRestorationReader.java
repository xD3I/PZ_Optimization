// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;

import java.util.Objects;

/// Reads AV1 loop-restoration unit syntax that is signaled before each superblock partition tree.

final class TileLoopRestorationReader {
    /// The luma sample size represented by one mode-info unit.
    private static final int MI_SIZE = 4;

    /// The super-resolution numerator used by AV1.
    private static final int SUPERRES_NUMERATOR = 8;

    /// The initial Wiener coefficient references for each pass.
    private static final int [] WIENER_TAPS_MID = {3, -7, 15};

    /// The initial self-guided projection coefficient references.
    private static final int [] SELF_GUIDED_PROJECTION_MID = {-32, 31};

    /// The AV1 self-guided restoration parameter table in `{r0, e0, r1, e1}` order.
    private static final int [] [] SELF_GUIDED_PARAMS = {
            {2, 140, 1, 3236},
            {2, 112, 1, 2158},
            {2, 93, 1, 1618},
            {2, 80, 1, 1438},
            {2, 70, 1, 1295},
            {2, 58, 1, 1177},
            {2, 47, 1, 1079},
            {2, 37, 1, 996},
            {2, 30, 1, 925},
            {2, 25, 1, 863},
            {0, -1, 1, 2589},
            {0, -1, 1, 1618},
            {0, -1, 1, 1177},
            {0, -1, 1, 925},
            {2, 56, 0, -1},
            {2, 22, 0, -1}
    };

    /// The tile-local decode state.
    private final TileDecodeContext tileContext;

    /// The typed entropy reader.
    private final TileSyntaxReader syntaxReader;

    /// The active frame header.
    private final FrameHeader frameHeader;

    /// The active decoded chroma format.
    private final Av1ChromaFormat chromaFormat;

    /// The frame-level restoration types.
    private final FrameHeader.RestorationType[] frameTypes;

    /// The per-plane, per-bitstream-axis, per-coefficient Wiener references.
    ///
    /// The bitstream axis order is vertical followed by horizontal.
    private final int[][][] referenceWienerCoefficients;

    /// The per-plane self-guided projection references.
    private final int[][] referenceSelfGuidedProjectionCoefficients;

    /// Creates one tile loop-restoration reader.
    ///
    /// @param tileContext the tile-local decode state
    /// @param syntaxReader the typed entropy reader
    TileLoopRestorationReader(TileDecodeContext tileContext, TileSyntaxReader syntaxReader) {
        this.tileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.syntaxReader = Objects.requireNonNull(syntaxReader, "syntaxReader");
        this.frameHeader = tileContext.frameHeader();
        this.chromaFormat = tileContext.sequenceHeader().colorConfig().chromaFormat();
        this.frameTypes = frameHeader.restoration().types();
        this.referenceWienerCoefficients = new int[3][2][3];
        this.referenceSelfGuidedProjectionCoefficients = new int[3][2];
        for (int plane = 0; plane < 3; plane++) {
            for (int pass = 0; pass < 2; pass++) {
                System.arraycopy(WIENER_TAPS_MID, 0, referenceWienerCoefficients[plane][pass], 0, WIENER_TAPS_MID.length);
            }
            System.arraycopy(
                    SELF_GUIDED_PROJECTION_MID,
                    0,
                    referenceSelfGuidedProjectionCoefficients[plane],
                    0,
                    SELF_GUIDED_PROJECTION_MID.length
            );
        }
    }

    /// Reads all restoration units intersected by one superblock.
    ///
    /// @param tileRelativePosition the superblock position in tile-relative 4x4 units
    /// @param width4 the superblock width in 4x4 units
    /// @param height4 the superblock height in 4x4 units
    public void readSuperblock(BlockPosition tileRelativePosition, int width4, int height4) {
        BlockPosition checkedPosition = Objects.requireNonNull(tileRelativePosition, "tileRelativePosition");
        if (frameHeader.allowIntrabc()) {
            return;
        }
        int frameRow4 = (tileContext.startY() >> 2) + checkedPosition.y4();
        int frameColumn4 = (tileContext.startX() >> 2) + checkedPosition.x4();
        for (int plane = 0; plane < frameTypes.length; plane++) {
            if (frameTypes[plane] == FrameHeader.RestorationType.NONE) {
                continue;
            }
            int subX = plane == 0 ? 0 : chromaSubsamplingX();
            int subY = plane == 0 ? 0 : chromaSubsamplingY();
            int unitSize = 1 << (plane == 0
                    ? frameHeader.restoration().unitSizeLog2Y()
                    : frameHeader.restoration().unitSizeLog2Uv());
            int unitRows = tileContext.restorationUnitMap().rows(plane);
            int unitColumns = tileContext.restorationUnitMap().columns(plane);
            int unitRowStart = divideCeil(frameRow4 * (MI_SIZE >> subY), unitSize);
            int unitRowEnd = Math.min(unitRows, divideCeil((frameRow4 + height4) * (MI_SIZE >> subY), unitSize));
            int numerator;
            int denominator;
            if (frameHeader.superResolution().enabled()) {
                numerator = (MI_SIZE >> subX) * frameHeader.superResolution().widthScaleDenominator();
                denominator = unitSize * SUPERRES_NUMERATOR;
            } else {
                numerator = MI_SIZE >> subX;
                denominator = unitSize;
            }
            int unitColumnStart = divideCeil(frameColumn4 * numerator, denominator);
            int unitColumnEnd = Math.min(unitColumns, divideCeil((frameColumn4 + width4) * numerator, denominator));
            for (int unitRow = unitRowStart; unitRow < unitRowEnd; unitRow++) {
                for (int unitColumn = unitColumnStart; unitColumn < unitColumnEnd; unitColumn++) {
                    if (!tileContext.restorationUnitMap().hasUnit(plane, unitRow, unitColumn)) {
                        tileContext.restorationUnitMap().setUnit(plane, unitRow, unitColumn, readUnit(plane));
                    }
                }
            }
        }
    }

    /// Reads one restoration unit for a plane.
    ///
    /// @param plane the plane index
    /// @return one decoded restoration unit
    private RestorationUnit readUnit(int plane) {
        FrameHeader.RestorationType unitType = syntaxReader.readRestorationUnitType(frameTypes[plane]);
        if (unitType == FrameHeader.RestorationType.NONE) {
            return RestorationUnit.none();
        }
        if (unitType == FrameHeader.RestorationType.WIENER) {
            return readWienerUnit(plane);
        }
        if (unitType == FrameHeader.RestorationType.SELF_GUIDED) {
            int set = syntaxReader.readUnsignedBits(4);
            return readSelfGuidedUnit(plane, set);
        }
        throw new IllegalStateException("Unsupported concrete restoration unit type: " + unitType);
    }

    /// Reads one Wiener coefficient table into the plane reference state.
    ///
    /// @param plane the plane index
    /// @return one decoded Wiener restoration unit
    private RestorationUnit readWienerUnit(int plane) {
        int[][] bitstreamCoefficients = referenceWienerCoefficients[plane];
        for (int pass = 0; pass < 2; pass++) {
            int firstCoefficient = plane == 0 ? 0 : 1;
            if (firstCoefficient == 1) {
                bitstreamCoefficients[pass][0] = 0;
            }
            for (int coefficient = firstCoefficient; coefficient < 3; coefficient++) {
                int value = syntaxReader.readWienerCoefficient(
                        coefficient,
                        bitstreamCoefficients[pass][coefficient]
                );
                bitstreamCoefficients[pass][coefficient] = value;
            }
        }
        return RestorationUnit.wiener(
                bitstreamCoefficients[1][0],
                bitstreamCoefficients[1][1],
                bitstreamCoefficients[1][2],
                bitstreamCoefficients[0][0],
                bitstreamCoefficients[0][1],
                bitstreamCoefficients[0][2]
        );
    }

    /// Reads one self-guided projection coefficient pair into the plane reference state.
    ///
    /// @param plane the plane index
    /// @param set the self-guided parameter set
    /// @return one decoded self-guided restoration unit
    private RestorationUnit readSelfGuidedUnit(int plane, int set) {
        int checkedSet = Objects.checkIndex(set, SELF_GUIDED_PARAMS.length);
        int[] coefficients = referenceSelfGuidedProjectionCoefficients[plane];
        for (int coefficient = 0; coefficient < 2; coefficient++) {
            int radius = SELF_GUIDED_PARAMS[checkedSet][coefficient * 2];
            int value;
            if (radius != 0) {
                value = syntaxReader.readSelfGuidedProjectionCoefficient(
                        coefficient,
                        coefficients[coefficient]
                );
            } else if (coefficient == 1) {
                value = syntaxReader.derivedSelfGuidedProjectionCoefficient1(
                        coefficients[0]
                );
            } else {
                value = 0;
            }
            coefficients[coefficient] = value;
        }
        return RestorationUnit.selfGuided(checkedSet, coefficients[0], coefficients[1]);
    }

    /// Returns the chroma horizontal subsampling shift for the active chroma format.
    ///
    /// @return the chroma horizontal subsampling shift for the active chroma format
    private int chromaSubsamplingX() {
        return switch (chromaFormat) {
            case MONOCHROME, YUV444 -> 0;
            case YUV420, YUV422 -> 1;
        };
    }

    /// Returns the chroma vertical subsampling shift for the active chroma format.
    ///
    /// @return the chroma vertical subsampling shift for the active chroma format
    private int chromaSubsamplingY() {
        return switch (chromaFormat) {
            case MONOCHROME, YUV422, YUV444 -> 0;
            case YUV420 -> 1;
        };
    }

    /// Divides two positive integers with ceiling rounding.
    ///
    /// @param numerator the numerator
    /// @param denominator the positive denominator
    /// @return the ceiling-rounded quotient
    private static int divideCeil(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
