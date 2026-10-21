// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.FilterIntraMode;
import pzopt.vendor.avif.internal.av1.model.LumaIntraPredictionMode;
import pzopt.vendor.avif.internal.av1.model.ResidualLayout;
import pzopt.vendor.avif.internal.av1.model.TransformLayout;
import pzopt.vendor.avif.internal.av1.model.TransformResidualUnit;
import pzopt.vendor.avif.internal.av1.model.TransformSize;
import pzopt.vendor.avif.internal.av1.model.TransformType;
import pzopt.vendor.avif.internal.av1.model.TransformUnit;
import pzopt.vendor.avif.internal.av1.model.UvIntraPredictionMode;

import java.util.Arrays;
import java.util.Objects;

/// Reads AV1 luma and chroma transform-coefficient syntax from one tile bitstream.
///
/// The reader decodes the luma, chroma-U, and chroma-V residual units exposed by
/// `TransformLayout`, including pixel-clipped chroma footprints along the frame fringe.
/// Retained transform and residual positions are frame-relative, while coefficient-neighbor
/// contexts remain tile-local as required by AV1 tile entropy independence.
/// `TX_4X4` keeps its dedicated context helpers, while larger transform sizes use the same
/// coefficient-neighbor token contexts so multi-coefficient blocks do not fall back to fixed
/// probabilities once AC residuals are present.

public final class TileResidualSyntaxReader {
    /// Shared empty residual-unit sequence for blocks without modeled chroma residuals.
    private static final TransformResidualUnit [] EMPTY_RESIDUAL_UNITS = new TransformResidualUnit[0];

    /// The AV1 coefficient-context byte written for all-zero transform blocks.
    private static final int ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x40;

    /// The AV1 20-bit coefficient-magnitude mask applied after Golomb extension.
    private static final int COEFFICIENT_MAGNITUDE_MASK = 0xFFFFF;

    /// The chroma-U plane index used by chroma coefficient-context helpers.
    private static final int CHROMA_PLANE_U = 0;

    /// The chroma-V plane index used by chroma coefficient-context helpers.
    private static final int CHROMA_PLANE_V = 1;

    /// The `dav1d` two-dimensional `TX_4X4` scan converted into row-major coefficient storage.
    private static final int [] FOUR_BY_FOUR_STORAGE_SCAN = {
            0, 1, 4, 8,
            5, 2, 3, 6,
            9, 12, 13, 10,
            7, 11, 14, 15
    };

    /// The `dav1d` scratch-layout `TX_4X4` scan used by coefficient-token contexts.
    private static final int [] FOUR_BY_FOUR_CONTEXT_SCAN = {
            0, 4, 1, 2,
            5, 8, 12, 9,
            6, 3, 7, 10,
            13, 14, 11, 15
    };

    /// The `dav1d`-compatible two-dimensional storage scan tables for every modeled transform size.
    private static final int [] [] DEFAULT_STORAGE_SCANS = createDefaultScans(false);

    /// The `dav1d` scratch-layout two-dimensional context scan tables for every modeled transform size.
    private static final int [] [] DEFAULT_CONTEXT_SCANS = createDefaultScans(true);

    /// The `dav1d` transform types inferred from chroma intra prediction modes.
    private static final TransformType [] UV_INTRA_TRANSFORM_TYPES = {
            TransformType.DCT_DCT,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.DCT_DCT,
            TransformType.ADST_ADST,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.DCT_ADST,
            TransformType.ADST_DCT,
            TransformType.ADST_ADST,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.ADST_ADST,
            TransformType.DCT_DCT
    };

    /// The `dav1d` luma modes used as transform-type context for filter-intra blocks.
    private static final LumaIntraPredictionMode [] FILTER_INTRA_TRANSFORM_MODES = {
            LumaIntraPredictionMode.DC,
            LumaIntraPredictionMode.VERTICAL,
            LumaIntraPredictionMode.HORIZONTAL,
            LumaIntraPredictionMode.HORIZONTAL_DOWN,
            LumaIntraPredictionMode.DC
    };

    /// The `dav1d` low-token context offsets for square, wide, and tall 2D transforms.
    private static final int [] [] [] LEVEL_CONTEXT_OFFSETS = {
            {
                    {0, 1, 6, 6, 21},
                    {1, 6, 6, 21, 21},
                    {6, 6, 21, 21, 21},
                    {6, 21, 21, 21, 21},
                    {21, 21, 21, 21, 21}
            },
            {
                    {0, 16, 6, 6, 21},
                    {16, 16, 6, 21, 21},
                    {16, 16, 21, 21, 21},
                    {16, 16, 21, 21, 21},
                    {16, 16, 21, 21, 21}
            },
            {
                    {0, 11, 11, 11, 11},
                    {11, 11, 11, 11, 11},
                    {6, 6, 21, 21, 21},
                    {6, 21, 21, 21, 21},
                    {21, 21, 21, 21, 21}
            }
    };

    /// The padded `levels` grid size used by the `TX_4X4` token-context helpers.
    private static final int FOUR_BY_FOUR_LEVEL_GRID_SIZE = 8;

    /// The tile-local decode state that owns the active sequence and frame headers.
    private final TileDecodeContext tileContext;

    /// The typed syntax reader used to consume coefficient-side entropy symbols.
    private final TileSyntaxReader syntaxReader;

    /// The tile origin on the frame luma-grid X axis.
    private final int frameOffsetX4;

    /// The tile origin on the frame luma-grid Y axis.
    private final int frameOffsetY4;

    /// Reusable zero-filled coefficient-level context grid for the current residual unit.
    private final CoefficientLevelGrid coefficientLevelGrid = new CoefficientLevelGrid();

    /// Creates one tile-local residual syntax reader.
    ///
    /// @param tileContext the tile-local decode state that owns the active tile bitstream
    public TileResidualSyntaxReader(TileDecodeContext tileContext) {
        this(tileContext, false);
    }

    /// Creates one tile-local residual syntax reader with a strict conformance policy.
    ///
    /// @param tileContext the tile-local decode state that owns the active tile bitstream
    /// @param strictStdCompliance whether malformed coefficient syntax must be rejected
    public TileResidualSyntaxReader(TileDecodeContext tileContext, boolean strictStdCompliance) {
        this.tileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.syntaxReader = new TileSyntaxReader(this.tileContext, strictStdCompliance);
        this.frameOffsetX4 = this.tileContext.startX() >> 2;
        this.frameOffsetY4 = this.tileContext.startY() >> 2;
    }

    /// Decodes residual syntax for one already-decoded block.
    ///
    /// @param header the decoded leaf block header whose position is tile-local
    /// @param transformLayout the frame-relative decoded transform layout for the same block
    /// @param neighborContext the mutable neighbor context that supplies coefficient skip contexts
    /// @return the decoded residual layout for the supplied block
    public ResidualLayout read(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            BlockNeighborContext neighborContext
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");
        if (nonNullHeader.position().x4() + frameOffsetX4 != nonNullTransformLayout.position().x4()
                || nonNullHeader.position().y4() + frameOffsetY4 != nonNullTransformLayout.position().y4()
                || nonNullHeader.size() != nonNullTransformLayout.blockSize()) {
            throw new IllegalArgumentException("Header and transform layout must describe the same block");
        }

        TransformResidualUnit[] residualUnits = new TransformResidualUnit[nonNullTransformLayout.lumaUnitCount()];
        boolean hasChromaResiduals = nonNullHeader.hasChroma() && nonNullTransformLayout.chromaUnitCount() != 0;
        int chromaUnitCount = hasChromaResiduals ? nonNullTransformLayout.chromaUnitCount() : 0;
        TransformResidualUnit[] chromaUUnits = chromaUnitCount == 0
                ? EMPTY_RESIDUAL_UNITS
                : new TransformResidualUnit[chromaUnitCount];
        TransformResidualUnit[] chromaVUnits = chromaUnitCount == 0
                ? EMPTY_RESIDUAL_UNITS
                : new TransformResidualUnit[chromaUnitCount];

        BlockPosition blockPosition = nonNullTransformLayout.position();
        int visibleWidth4 = nonNullTransformLayout.visibleWidth4();
        int visibleHeight4 = nonNullTransformLayout.visibleHeight4();
        boolean onlyCoefficientRegion = visibleWidth4 <= 16 && visibleHeight4 <= 16;
        for (int offsetY4 = 0; offsetY4 < visibleHeight4; offsetY4 += 16) {
            int regionStartY4 = blockPosition.y4() + offsetY4;
            int regionEndY4 = blockPosition.y4() + Math.min(offsetY4 + 16, visibleHeight4);
            for (int offsetX4 = 0; offsetX4 < visibleWidth4; offsetX4 += 16) {
                int regionStartX4 = blockPosition.x4() + offsetX4;
                int regionEndX4 = blockPosition.x4() + Math.min(offsetX4 + 16, visibleWidth4);
                readLumaResidualRegion(
                        nonNullHeader,
                        nonNullTransformLayout,
                        residualUnits,
                        nonNullNeighborContext,
                        regionStartX4,
                        regionStartY4,
                        regionEndX4,
                        regionEndY4
                );
                if (hasChromaResiduals) {
                    readChromaResidualRegion(
                            nonNullHeader,
                            nonNullTransformLayout,
                            residualUnits,
                            chromaUUnits,
                            CHROMA_PLANE_U,
                            nonNullNeighborContext,
                            onlyCoefficientRegion,
                            regionStartX4,
                            regionStartY4,
                            regionEndX4,
                            regionEndY4
                    );
                    readChromaResidualRegion(
                            nonNullHeader,
                            nonNullTransformLayout,
                            residualUnits,
                            chromaVUnits,
                            CHROMA_PLANE_V,
                            nonNullNeighborContext,
                            onlyCoefficientRegion,
                            regionStartX4,
                            regionStartY4,
                            regionEndX4,
                            regionEndY4
                    );
                }
            }
        }
        return ResidualLayout.fromOwnedUnits(
                nonNullTransformLayout.position(),
                nonNullTransformLayout.blockSize(),
                residualUnits,
                chromaUUnits,
                chromaVUnits
        );
    }

    /// Decodes the luma transform units whose origins lie in one 64x64 coefficient region.
    ///
    /// @param header the owning leaf block header
    /// @param transformLayout the owning block-level transform layout
    /// @param residualUnits the destination array parallel to the layout's luma units
    /// @param neighborContext the mutable neighbor context that supplies entropy contexts
    /// @param regionStartX4 the inclusive region start on the luma-grid X axis
    /// @param regionStartY4 the inclusive region start on the luma-grid Y axis
    /// @param regionEndX4 the exclusive region end on the luma-grid X axis
    /// @param regionEndY4 the exclusive region end on the luma-grid Y axis
    private void readLumaResidualRegion(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            TransformResidualUnit[] residualUnits,
            BlockNeighborContext neighborContext,
            int regionStartX4,
            int regionStartY4,
            int regionEndX4,
            int regionEndY4
    ) {
        for (int i = 0; i < transformLayout.lumaUnitCount(); i++) {
            TransformUnit transformUnit = transformLayout.lumaUnit(i);
            if (!isInCoefficientRegion(
                    transformUnit,
                    regionStartX4,
                    regionStartY4,
                    regionEndX4,
                    regionEndY4
            )) {
                continue;
            }

            int visibleWidthPixels = visibleLumaWidthPixels(transformLayout, transformUnit);
            int visibleHeightPixels = visibleLumaHeightPixels(transformLayout, transformUnit);
            TransformResidualUnit residualUnit = header.skip()
                    ? createAllZeroUnit(transformUnit.position(), transformUnit.size(), visibleWidthPixels, visibleHeightPixels)
                    : readLumaResidualUnit(header, transformLayout, transformUnit, neighborContext);
            residualUnits[i] = residualUnit;
            neighborContext.updateLumaCoefficientContext(
                    transformUnit.position().x4() - frameOffsetX4,
                    transformUnit.position().y4() - frameOffsetY4,
                    transformUnit.size(),
                    residualUnit.coefficientContextByte()
            );
        }
    }

    /// Returns the horizontal chroma subsampling shift used by the active sequence chroma format.
    ///
    /// @return the horizontal chroma subsampling shift used by the active sequence chroma format
    private int chromaSubsamplingX() {
        return switch (tileContext.sequenceHeader().colorConfig().chromaFormat()) {
            case MONOCHROME, YUV444 -> 0;
            case YUV420, YUV422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift used by the active sequence chroma format.
    ///
    /// @return the vertical chroma subsampling shift used by the active sequence chroma format
    private int chromaSubsamplingY() {
        return switch (tileContext.sequenceHeader().colorConfig().chromaFormat()) {
            case MONOCHROME, YUV422, YUV444 -> 0;
            case YUV420 -> 1;
        };
    }

    /// Decodes one non-skipped luma transform residual unit.
    ///
    /// @param header the owning leaf block header
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the luma transform unit to decode
    /// @param neighborContext the mutable neighbor context that supplies entropy contexts
    /// @return the decoded luma transform residual unit
    private TransformResidualUnit readLumaResidualUnit(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            TransformUnit transformUnit,
            BlockNeighborContext neighborContext
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");

        int visibleWidthPixels = visibleLumaWidthPixels(nonNullTransformLayout, nonNullTransformUnit);
        int visibleHeightPixels = visibleLumaHeightPixels(nonNullTransformLayout, nonNullTransformUnit);
        int localX4 = nonNullTransformUnit.position().x4() - frameOffsetX4;
        int localY4 = nonNullTransformUnit.position().y4() - frameOffsetY4;
        int coefficientSkipContext = nonNullNeighborContext.lumaCoefficientSkipContext(
                nonNullHeader.size(),
                localX4,
                localY4,
                nonNullTransformUnit.size()
        );
        if (syntaxReader.readCoefficientSkipFlag(nonNullTransformUnit.size(), coefficientSkipContext)) {
            return createAllZeroUnit(
                    nonNullTransformUnit.position(),
                    nonNullTransformUnit.size(),
                    visibleWidthPixels,
                    visibleHeightPixels
            );
        }

        return readNonSkippedResidualUnit(
                nonNullTransformUnit.position(),
                nonNullTransformUnit.size(),
                visibleWidthPixels,
                visibleHeightPixels,
                false,
                lumaTransformType(nonNullHeader, nonNullTransformUnit.size()),
                nonNullNeighborContext.lumaDcSignContext(localX4, localY4, nonNullTransformUnit.size())
        );
    }

    /// Decodes the chroma transform units whose origins lie in one 64x64 luma-grid coefficient region.
    ///
    /// @param header the owning leaf block header
    /// @param transformLayout the owning block-level transform layout
    /// @param lumaResidualUnits the decoded luma residual units used for inter transform-type inference
    /// @param residualUnits the destination array parallel to the layout's chroma units
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @param neighborContext the mutable neighbor context that supplies and stores coefficient contexts
    /// @param includeAllUnits whether this is the block's only coefficient region
    /// @param regionStartX4 the inclusive region start on the shared luma-grid X axis
    /// @param regionStartY4 the inclusive region start on the shared luma-grid Y axis
    /// @param regionEndX4 the exclusive region end on the shared luma-grid X axis
    /// @param regionEndY4 the exclusive region end on the shared luma-grid Y axis
    private void readChromaResidualRegion(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            TransformResidualUnit[] lumaResidualUnits,
            TransformResidualUnit[] residualUnits,
            int plane,
            BlockNeighborContext neighborContext,
            boolean includeAllUnits,
            int regionStartX4,
            int regionStartY4,
            int regionEndX4,
            int regionEndY4
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformResidualUnit[] nonNullLumaResidualUnits = Objects.requireNonNull(lumaResidualUnits, "lumaResidualUnits");
        TransformResidualUnit[] nonNullResidualUnits = Objects.requireNonNull(residualUnits, "residualUnits");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");

        for (int i = 0; i < nonNullTransformLayout.chromaUnitCount(); i++) {
            TransformUnit transformUnit = nonNullTransformLayout.chromaUnit(i);
            if (!includeAllUnits && !isInCoefficientRegion(
                    transformUnit,
                    regionStartX4,
                    regionStartY4,
                    regionEndX4,
                    regionEndY4
            )) {
                continue;
            }
            BlockPosition unitPosition = transformUnit.position();
            TransformSize unitSize = transformUnit.size();
            int localX4 = unitPosition.x4() - frameOffsetX4;
            int localY4 = unitPosition.y4() - frameOffsetY4;
            int visibleUnitWidthPixels = visibleChromaUnitWidthPixels(nonNullTransformLayout, transformUnit);
            int visibleUnitHeightPixels = visibleChromaUnitHeightPixels(nonNullTransformLayout, transformUnit);
            TransformResidualUnit residualUnit;
            if (nonNullHeader.skip()) {
                residualUnit = createAllZeroUnit(
                        unitPosition,
                        unitSize,
                        visibleUnitWidthPixels,
                        visibleUnitHeightPixels
                );
            } else {
                int coefficientSkipContext = nonNullNeighborContext.chromaCoefficientSkipContext(
                        plane,
                        nonNullHeader.size(),
                        localX4,
                        localY4,
                        unitSize
                );
                boolean coefficientSkip = syntaxReader.readCoefficientSkipFlag(unitSize, coefficientSkipContext);
                if (coefficientSkip) {
                    residualUnit = createAllZeroUnit(
                            unitPosition,
                            unitSize,
                            visibleUnitWidthPixels,
                            visibleUnitHeightPixels
                    );
                } else {
                    residualUnit = readNonSkippedResidualUnit(
                            unitPosition,
                            unitSize,
                            visibleUnitWidthPixels,
                            visibleUnitHeightPixels,
                            true,
                            chromaTransformType(
                                    nonNullHeader,
                                    unitSize,
                                    lumaTransformTypeAt(
                                            nonNullLumaResidualUnits,
                                            transformUnit,
                                            nonNullTransformLayout.position()
                                    )
                            ),
                            nonNullNeighborContext.chromaDcSignContext(
                                    plane,
                                    localX4,
                                    localY4,
                                    unitSize
                            )
                    );
                }
            }
            nonNullNeighborContext.updateChromaCoefficientContext(
                    plane,
                    localX4,
                    localY4,
                    unitSize,
                    residualUnit.coefficientContextByte()
            );
            nonNullResidualUnits[i] = residualUnit;
        }
    }

    /// Returns whether a transform-unit origin lies inside one coefficient region.
    ///
    /// @param transformUnit the transform unit to test
    /// @param regionStartX4 the inclusive region start on the shared luma-grid X axis
    /// @param regionStartY4 the inclusive region start on the shared luma-grid Y axis
    /// @param regionEndX4 the exclusive region end on the shared luma-grid X axis
    /// @param regionEndY4 the exclusive region end on the shared luma-grid Y axis
    /// @return `true` when the unit origin lies inside the region
    private static boolean isInCoefficientRegion(
            TransformUnit transformUnit,
            int regionStartX4,
            int regionStartY4,
            int regionEndX4,
            int regionEndY4
    ) {
        BlockPosition position = Objects.requireNonNull(transformUnit, "transformUnit").position();
        return position.x4() >= regionStartX4
                && position.x4() < regionEndX4
                && position.y4() >= regionStartY4
                && position.y4() < regionEndY4;
    }

    /// Decodes one non-skipped transform residual unit using the supplied plane-specific contexts.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the active transform size
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param transformType the active transform type
    /// @param dcSignContext the DC-sign context for the current unit
    /// @return the decoded non-skipped transform residual unit
    private TransformResidualUnit readNonSkippedResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            TransformType transformType,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");

        int endOfBlockIndex = syntaxReader.readEndOfBlockIndex(
                nonNullTransformSize,
                chroma,
                nonNullTransformType.oneDimensional()
        );
        if (nonNullTransformSize == TransformSize.TX_4X4) {
            return readFourByFourResidualUnit(
                    nonNullPosition,
                    visibleWidthPixels,
                    visibleHeightPixels,
                    chroma,
                    nonNullTransformType,
                    endOfBlockIndex,
                    dcSignContext
            );
        }
        return readGenericResidualUnit(
                nonNullPosition,
                nonNullTransformSize,
                visibleWidthPixels,
                visibleHeightPixels,
                chroma,
                nonNullTransformType,
                endOfBlockIndex,
                dcSignContext
        );
    }

    /// Decodes the fully supported two-dimensional `TX_4X4` residual syntax for one plane.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param transformType the active transform type
    /// @param endOfBlockIndex the already-decoded end-of-block scan index
    /// @param dcSignContext the plane-specific DC-sign context
    /// @return the decoded `TX_4X4` residual unit
    private TransformResidualUnit readFourByFourResidualUnit(
            BlockPosition position,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            TransformType transformType,
            int endOfBlockIndex,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int[] coefficients = endOfBlockIndex > 0 ? new int[16] : null;
        CoefficientLevelGrid levelBytes = coefficientLevelGrid.reset(
                FOUR_BY_FOUR_LEVEL_GRID_SIZE,
                FOUR_BY_FOUR_LEVEL_GRID_SIZE
        );

        if (endOfBlockIndex > 0) {
            int[] decodedCoefficients = Objects.requireNonNull(coefficients, "coefficients");
            int lastX = fourByFourLevelX(nonNullTransformType, endOfBlockIndex);
            int lastY = fourByFourLevelY(nonNullTransformType, endOfBlockIndex);
            int lastToken = syntaxReader.readEndOfBlockBaseToken(
                    TransformSize.TX_4X4,
                    chroma,
                    endOfBlockTokenContext(endOfBlockIndex, TransformSize.TX_4X4)
            );
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                        TransformSize.TX_4X4,
                        chroma,
                        fourByFourEndOfBlockHighTokenContext(nonNullTransformType, lastX, lastY)
                );
            }
            decodedCoefficients[fourByFourCoefficientIndex(nonNullTransformType, endOfBlockIndex)] = lastToken;
            levelBytes.set(lastX, lastY, coefficientLevelByte(lastToken));

            for (int scanIndex = endOfBlockIndex - 1; scanIndex > 0; scanIndex--) {
                int x = fourByFourLevelX(nonNullTransformType, scanIndex);
                int y = fourByFourLevelY(nonNullTransformType, scanIndex);
                int token = syntaxReader.readBaseToken(
                        TransformSize.TX_4X4,
                        chroma,
                        fourByFourBaseTokenContext(nonNullTransformType, levelBytes, x, y)
                );
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                            TransformSize.TX_4X4,
                            chroma,
                            fourByFourHighTokenContext(nonNullTransformType, levelBytes, x, y)
                    );
                }
                decodedCoefficients[fourByFourCoefficientIndex(nonNullTransformType, scanIndex)] = token;
                levelBytes.set(x, y, coefficientLevelByte(token));
            }
        }

        int dcBaseContext = nonNullTransformType.oneDimensional()
                ? fourByFourBaseTokenContext(nonNullTransformType, levelBytes, 0, 0)
                : 0;
        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(TransformSize.TX_4X4, chroma, 0)
                : syntaxReader.readBaseToken(TransformSize.TX_4X4, chroma, dcBaseContext);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(
                    TransformSize.TX_4X4,
                    chroma,
                    fourByFourDcHighTokenContext(nonNullTransformType, levelBytes)
            );
        }
        if (endOfBlockIndex == 0) {
            return createDcOnlyResidualUnit(
                    nonNullPosition,
                    TransformSize.TX_4X4,
                    nonNullTransformType,
                    visibleWidthPixels,
                    visibleHeightPixels,
                    chroma,
                    dcSignContext,
                    dcToken
            );
        }

        int[] decodedCoefficients = Objects.requireNonNull(coefficients, "coefficients");
        int cumulativeLevel = 0;
        int dcSign = 0;
        if (dcToken != 0) {
            boolean negative = syntaxReader.readDcSignFlag(chroma, dcSignContext);
            dcSign = negative ? -1 : 1;
            if (dcToken == 15) {
                dcToken = maskCoefficientMagnitude(dcToken + syntaxReader.readCoefficientGolomb());
            }
            decodedCoefficients[0] = negative ? -dcToken : dcToken;
            cumulativeLevel += dcToken;
        }

        for (int scanIndex = 1; scanIndex <= endOfBlockIndex; scanIndex++) {
            int coefficientIndex = fourByFourCoefficientIndex(nonNullTransformType, scanIndex);
            int token = decodedCoefficients[coefficientIndex];
            if (token == 0) {
                continue;
            }
            boolean negative = syntaxReader.readCoefficientSignFlag();
            if (token == 15) {
                token = maskCoefficientMagnitude(token + syntaxReader.readCoefficientGolomb());
            }
            decodedCoefficients[coefficientIndex] = negative ? -token : token;
            cumulativeLevel += token;
        }

        return TransformResidualUnit.fromOwnedCoefficients(
                nonNullPosition,
                TransformSize.TX_4X4,
                nonNullTransformType,
                endOfBlockIndex,
                decodedCoefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                createNonZeroCoefficientContextByte(cumulativeLevel, dcSign)
        );
    }

    /// Decodes one non-`TX_4X4` residual unit with coefficient-neighbor token contexts.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the active transform size
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param transformType the active transform type
    /// @param endOfBlockIndex the already-decoded end-of-block scan index
    /// @param dcSignContext the plane-specific DC-sign context
    /// @return the decoded larger-transform residual unit
    private TransformResidualUnit readGenericResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            TransformType transformType,
            int endOfBlockIndex,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int[] coefficients = endOfBlockIndex > 0
                ? new int[nonNullTransformSize.widthPixels() * nonNullTransformSize.heightPixels()]
                : null;
        CoefficientLevelGrid levelBytes = resetGenericLevelGrid(nonNullTransformSize, nonNullTransformType);
        if (endOfBlockIndex > 0) {
            int[] decodedCoefficients = Objects.requireNonNull(coefficients, "coefficients");
            int lastX = genericLevelX(nonNullTransformSize, nonNullTransformType, endOfBlockIndex);
            int lastY = genericLevelY(nonNullTransformSize, nonNullTransformType, endOfBlockIndex);
            int lastToken = syntaxReader.readEndOfBlockBaseToken(
                    nonNullTransformSize,
                    chroma,
                    endOfBlockTokenContext(endOfBlockIndex, nonNullTransformSize)
            );
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                        nonNullTransformSize,
                        chroma,
                        genericEndOfBlockHighTokenContext(nonNullTransformType, lastX, lastY)
                );
            }
            decodedCoefficients[coefficientIndex(nonNullTransformSize, nonNullTransformType, endOfBlockIndex)] = lastToken;
            levelBytes.set(lastX, lastY, coefficientLevelByte(lastToken));

            for (int scanIndex = endOfBlockIndex - 1; scanIndex > 0; scanIndex--) {
                int x = genericLevelX(nonNullTransformSize, nonNullTransformType, scanIndex);
                int y = genericLevelY(nonNullTransformSize, nonNullTransformType, scanIndex);
                int token = syntaxReader.readBaseToken(
                        nonNullTransformSize,
                        chroma,
                        genericBaseTokenContext(nonNullTransformSize, nonNullTransformType, levelBytes, x, y)
                );
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                            nonNullTransformSize,
                            chroma,
                            genericHighTokenContext(nonNullTransformType, levelBytes, x, y)
                    );
                }
                decodedCoefficients[coefficientIndex(nonNullTransformSize, nonNullTransformType, scanIndex)] = token;
                levelBytes.set(x, y, coefficientLevelByte(token));
            }
        }

        int dcBaseContext = endOfBlockIndex > 0 && nonNullTransformType.oneDimensional()
                ? genericBaseTokenContext(nonNullTransformSize, nonNullTransformType, levelBytes, 0, 0)
                : 0;
        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(nonNullTransformSize, chroma, 0)
                : syntaxReader.readBaseToken(nonNullTransformSize, chroma, dcBaseContext);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(
                    nonNullTransformSize,
                    chroma,
                    genericDcHighTokenContext(nonNullTransformType, levelBytes)
            );
        }
        if (endOfBlockIndex == 0) {
            return createDcOnlyResidualUnit(
                    nonNullPosition,
                    nonNullTransformSize,
                    nonNullTransformType,
                    visibleWidthPixels,
                    visibleHeightPixels,
                    chroma,
                    dcSignContext,
                    dcToken
            );
        }

        int[] decodedCoefficients = Objects.requireNonNull(coefficients, "coefficients");
        int cumulativeLevel = 0;
        int dcSign = 0;
        if (dcToken != 0) {
            boolean negative = syntaxReader.readDcSignFlag(chroma, dcSignContext);
            dcSign = negative ? -1 : 1;
            if (dcToken == 15) {
                dcToken = maskCoefficientMagnitude(dcToken + syntaxReader.readCoefficientGolomb());
            }
            decodedCoefficients[0] = negative ? -dcToken : dcToken;
            cumulativeLevel += dcToken;
        }

        for (int scanIndex = 1; scanIndex <= endOfBlockIndex; scanIndex++) {
            int coefficientIndex = coefficientIndex(nonNullTransformSize, nonNullTransformType, scanIndex);
            int token = decodedCoefficients[coefficientIndex];
            if (token == 0) {
                continue;
            }
            boolean negative = syntaxReader.readCoefficientSignFlag();
            if (token == 15) {
                token = maskCoefficientMagnitude(token + syntaxReader.readCoefficientGolomb());
            }
            decodedCoefficients[coefficientIndex] = negative ? -token : token;
            cumulativeLevel += token;
        }

        return TransformResidualUnit.fromOwnedCoefficients(
                nonNullPosition,
                nonNullTransformSize,
                nonNullTransformType,
                endOfBlockIndex,
                decodedCoefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                createNonZeroCoefficientContextByte(cumulativeLevel, dcSign)
        );
    }

    /// Finishes one residual unit whose end-of-block index identifies only the DC coefficient.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param dcSignContext the plane-specific DC-sign context
    /// @param dcToken the decoded unsigned DC token before sign and Golomb extension
    /// @return one compact DC-only residual unit
    private TransformResidualUnit createDcOnlyResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            TransformType transformType,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            int dcSignContext,
            int dcToken
    ) {
        int magnitude = dcToken;
        int signedDcCoefficient = 0;
        int dcSign = 0;
        if (magnitude != 0) {
            boolean negative = syntaxReader.readDcSignFlag(chroma, dcSignContext);
            dcSign = negative ? -1 : 1;
            if (magnitude == 15) {
                magnitude = maskCoefficientMagnitude(magnitude + syntaxReader.readCoefficientGolomb());
            }
            signedDcCoefficient = negative ? -magnitude : magnitude;
        }
        return TransformResidualUnit.dcOnly(
                position,
                transformSize,
                transformType,
                signedDcCoefficient,
                visibleWidthPixels,
                visibleHeightPixels,
                createNonZeroCoefficientContextByte(magnitude, dcSign)
        );
    }

    /// Creates one all-zero transform residual unit.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the transform size whose residual syntax is all-zero
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @return one all-zero transform residual unit
    private static TransformResidualUnit createAllZeroUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return TransformResidualUnit.allZero(
                nonNullPosition,
                nonNullTransformSize,
                visibleWidthPixels,
                visibleHeightPixels,
                ALL_ZERO_COEFFICIENT_CONTEXT_BYTE
        );
    }

    /// Returns the exact visible luma width in pixels for one transform unit inside the current
    /// block-level transform layout.
    ///
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the transform unit whose visible width should be measured
    /// @return the exact visible luma width in pixels for the supplied transform unit
    private static int visibleLumaWidthPixels(TransformLayout transformLayout, TransformUnit transformUnit) {
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        int relativeX = (nonNullTransformUnit.position().x4() - nonNullTransformLayout.position().x4()) << 2;
        return Math.min(
                nonNullTransformUnit.size().widthPixels(),
                Math.max(0, nonNullTransformLayout.visibleWidthPixels() - relativeX)
        );
    }

    /// Returns the exact visible luma height in pixels for one transform unit inside the current
    /// block-level transform layout.
    ///
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the transform unit whose visible height should be measured
    /// @return the exact visible luma height in pixels for the supplied transform unit
    private static int visibleLumaHeightPixels(TransformLayout transformLayout, TransformUnit transformUnit) {
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        int relativeY = (nonNullTransformUnit.position().y4() - nonNullTransformLayout.position().y4()) << 2;
        return Math.min(
                nonNullTransformUnit.size().heightPixels(),
                Math.max(0, nonNullTransformLayout.visibleHeightPixels() - relativeY)
        );
    }

    /// Returns the exact visible chroma width in pixels for one chroma transform unit.
    ///
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the chroma transform unit whose visible width should be measured
    /// @return the exact visible chroma width in pixels for the supplied unit
    private int visibleChromaUnitWidthPixels(TransformLayout transformLayout, TransformUnit transformUnit) {
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        int shift = chromaSubsamplingX();
        int originX4 = chromaOrigin4(
                nonNullTransformLayout.position().x4(),
                nonNullTransformLayout.blockSize().width4(),
                shift
        );
        int visibleBlockWidthPixels = chromaDimension(
                ((nonNullTransformLayout.position().x4() << 2) + nonNullTransformLayout.visibleWidthPixels()) - (originX4 << 2),
                shift
        );
        int relativeLumaX = (nonNullTransformUnit.position().x4() - originX4) << 2;
        int relativeChromaX = relativeLumaX >> shift;
        return Math.min(
                nonNullTransformUnit.size().widthPixels(),
                Math.max(0, visibleBlockWidthPixels - relativeChromaX)
        );
    }

    /// Returns the exact visible chroma height in pixels for one chroma transform unit.
    ///
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the chroma transform unit whose visible height should be measured
    /// @return the exact visible chroma height in pixels for the supplied unit
    private int visibleChromaUnitHeightPixels(TransformLayout transformLayout, TransformUnit transformUnit) {
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        int shift = chromaSubsamplingY();
        int originY4 = chromaOrigin4(
                nonNullTransformLayout.position().y4(),
                nonNullTransformLayout.blockSize().height4(),
                shift
        );
        int visibleBlockHeightPixels = chromaDimension(
                ((nonNullTransformLayout.position().y4() << 2) + nonNullTransformLayout.visibleHeightPixels()) - (originY4 << 2),
                shift
        );
        int relativeLumaY = (nonNullTransformUnit.position().y4() - originY4) << 2;
        int relativeChromaY = relativeLumaY >> shift;
        return Math.min(
                nonNullTransformUnit.size().heightPixels(),
                Math.max(0, visibleBlockHeightPixels - relativeChromaY)
        );
    }

    /// Returns the luma-grid origin for one chroma block span.
    ///
    /// @param position4 the luma-grid coordinate of the syntax block
    /// @param size4 the luma-grid span of the syntax block
    /// @param subsamplingShift the chroma subsampling shift for the axis
    /// @return the luma-grid origin for the shared chroma footprint
    private static int chromaOrigin4(int position4, int size4, int subsamplingShift) {
        if (subsamplingShift == 0 || size4 > subsamplingShift) {
            return position4;
        }
        int mask = (1 << subsamplingShift) - 1;
        return position4 & ~mask;
    }

    /// Returns the chroma-plane dimension corresponding to one visible luma span.
    ///
    /// @param lumaDimension the visible luma span in pixels
    /// @param subsamplingShift the chroma subsampling shift for the axis
    /// @return the corresponding chroma-plane dimension
    private static int chromaDimension(int lumaDimension, int subsamplingShift) {
        return (lumaDimension + (1 << subsamplingShift) - 1) >> subsamplingShift;
    }

    /// Selects the luma transform type for one non-skipped luma residual unit.
    ///
    /// @param header the owning leaf block header
    /// @param transformSize the active luma transform size
    /// @return the luma transform type for the supplied residual unit
    private TransformType lumaTransformType(TileBlockHeaderReader.BlockHeader header, TransformSize transformSize) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        boolean intraTransform = intraTransformType(nonNullHeader);
        if (tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId())) {
            return TransformType.WHT_WHT;
        }
        if (nonNullTransformSize.maxSquareLevel() + (intraTransform ? 1 : 0)
                >= TransformSize.TX_64X64.maxSquareLevel()) {
            return TransformType.DCT_DCT;
        }
        if (tileContext.frameHeader().segmentation().qIndex(nonNullHeader.segmentId()) == 0) {
            return TransformType.DCT_DCT;
        }
        if (intraTransform) {
            return syntaxReader.readIntraTransformType(
                    nonNullTransformSize,
                    lumaModeForTransformType(nonNullHeader),
                    tileContext.frameHeader().reducedTransformSet()
            );
        }
        return syntaxReader.readInterTransformType(
                nonNullTransformSize,
                tileContext.frameHeader().reducedTransformSet()
        );
    }

    /// Selects the chroma transform type for one non-skipped chroma residual unit.
    ///
    /// @param header the owning leaf block header
    /// @param transformSize the active chroma transform size
    /// @param lumaTransformType the colocated luma transform type for inter-derived chroma transforms
    /// @return the chroma transform type for the supplied residual unit
    private TransformType chromaTransformType(
            TileBlockHeaderReader.BlockHeader header,
            TransformSize transformSize,
            TransformType lumaTransformType
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullLumaTransformType = Objects.requireNonNull(lumaTransformType, "lumaTransformType");
        boolean intraTransform = intraTransformType(nonNullHeader);
        if (tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId())) {
            return TransformType.WHT_WHT;
        }
        if (nonNullTransformSize.maxSquareLevel() + (intraTransform ? 1 : 0)
                >= TransformSize.TX_64X64.maxSquareLevel()) {
            return TransformType.DCT_DCT;
        }
        if (intraTransform) {
            return uvIntraTransformType(nonNullHeader.uvMode());
        }
        return chromaInterTransformType(nonNullTransformSize, nonNullLumaTransformType);
    }

    /// Returns whether this block follows the intra transform-type branch.
    ///
    /// @param header the owning leaf block header
    /// @return whether this block follows the intra transform-type branch
    private static boolean intraTransformType(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        return nonNullHeader.intra() && !nonNullHeader.useIntrabc();
    }

    /// Returns the luma intra mode used as transform-type context.
    ///
    /// @param header the owning leaf block header
    /// @return the luma intra mode used as transform-type context
    private static LumaIntraPredictionMode lumaModeForTransformType(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        FilterIntraMode filterIntraMode = nonNullHeader.filterIntraMode();
        if (filterIntraMode != null) {
            return FILTER_INTRA_TRANSFORM_MODES[filterIntraMode.symbolIndex()];
        }
        LumaIntraPredictionMode yMode = nonNullHeader.yMode();
        return yMode != null ? yMode : LumaIntraPredictionMode.DC;
    }

    /// Returns the transform type inferred from one chroma intra prediction mode.
    ///
    /// @param uvMode the chroma intra prediction mode, or `null`
    /// @return the transform type inferred from the supplied chroma intra prediction mode
    private static TransformType uvIntraTransformType(UvIntraPredictionMode uvMode) {
        if (uvMode == null) {
            return TransformType.DCT_DCT;
        }
        return UV_INTRA_TRANSFORM_TYPES[uvMode.symbolIndex()];
    }

    /// Returns the chroma inter transform type inferred from the colocated luma transform type.
    ///
    /// @param transformSize the active chroma transform size
    /// @param lumaTransformType the colocated luma transform type
    /// @return the chroma inter transform type inferred from the supplied luma transform type
    private static TransformType chromaInterTransformType(TransformSize transformSize, TransformType lumaTransformType) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullLumaTransformType = Objects.requireNonNull(lumaTransformType, "lumaTransformType");
        if (nonNullTransformSize.maxSquareLevel() == TransformSize.TX_32X32.maxSquareLevel()) {
            return nonNullLumaTransformType == TransformType.IDTX ? TransformType.IDTX : TransformType.DCT_DCT;
        }
        if (nonNullTransformSize.minSquareLevel() == TransformSize.TX_16X16.minSquareLevel()
                && isLargeChromaInterTransformExcluded(nonNullLumaTransformType)) {
            return TransformType.DCT_DCT;
        }
        return nonNullLumaTransformType;
    }

    /// Returns whether a luma transform type is excluded from 16x16 chroma inter transforms.
    ///
    /// @param transformType the luma transform type
    /// @return whether the supplied transform type is excluded from 16x16 chroma inter transforms
    private static boolean isLargeChromaInterTransformExcluded(TransformType transformType) {
        return switch (Objects.requireNonNull(transformType, "transformType")) {
            case V_ADST, H_ADST, V_FLIPADST, H_FLIPADST -> true;
            default -> false;
        };
    }

    /// Returns the transform type of the luma residual unit colocated with one chroma transform unit.
    ///
    /// Entries outside the current coefficient region may not have been decoded yet and are
    /// ignored while locating the colocated unit.
    ///
    /// @param lumaResidualUnits the partially decoded luma residual-unit array
    /// @param chromaUnit the chroma transform unit whose colocated luma type should be found
    /// @param blockPosition the owning syntax block's luma-grid origin
    /// @return the transform type of the colocated decoded luma unit, or `DCT_DCT` if none exists
    private static TransformType lumaTransformTypeAt(
            TransformResidualUnit[] lumaResidualUnits,
            TransformUnit chromaUnit,
            BlockPosition blockPosition
    ) {
        TransformResidualUnit[] nonNullLumaResidualUnits = Objects.requireNonNull(lumaResidualUnits, "lumaResidualUnits");
        TransformUnit nonNullChromaUnit = Objects.requireNonNull(chromaUnit, "chromaUnit");
        BlockPosition nonNullBlockPosition = Objects.requireNonNull(blockPosition, "blockPosition");
        int x4 = Math.max(nonNullChromaUnit.position().x4(), nonNullBlockPosition.x4());
        int y4 = Math.max(nonNullChromaUnit.position().y4(), nonNullBlockPosition.y4());
        for (TransformResidualUnit lumaResidualUnit : nonNullLumaResidualUnits) {
            if (lumaResidualUnit == null) {
                continue;
            }
            BlockPosition lumaPosition = lumaResidualUnit.position();
            TransformSize lumaSize = lumaResidualUnit.size();
            if (x4 >= lumaPosition.x4()
                    && x4 < lumaPosition.x4() + lumaSize.width4()
                    && y4 >= lumaPosition.y4()
                    && y4 < lumaPosition.y4() + lumaSize.height4()) {
                return lumaResidualUnit.transformType();
            }
        }
        return TransformType.DCT_DCT;
    }

    /// Returns the natural coefficient index addressed by one scan index and transform class.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the natural coefficient index addressed by the supplied scan index
    private static int coefficientIndex(TransformSize transformSize, TransformType transformType, int scanIndex) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (!nonNullTransformType.oneDimensional()) {
            return DEFAULT_STORAGE_SCANS[nonNullTransformSize.ordinal()][scanIndex];
        }
        if (verticalOneDimensional(nonNullTransformType)) {
            return scanIndex;
        }
        int row = scanIndex & (nonNullTransformSize.heightPixels() - 1);
        int column = scanIndex >> (nonNullTransformSize.log2Height4() + 2);
        return (row << (nonNullTransformSize.log2Width4() + 2)) | column;
    }

    /// Resets the padded larger-transform `levels` grid used by coefficient token contexts.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @return the reusable padded zero-filled level grid
    private CoefficientLevelGrid resetGenericLevelGrid(
            TransformSize transformSize,
            TransformType transformType
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int width;
        int height;
        if (!nonNullTransformType.oneDimensional()) {
            width = clippedCoefficientWidth(nonNullTransformSize);
            height = clippedCoefficientHeight(nonNullTransformSize);
        } else if (verticalOneDimensional(nonNullTransformType)) {
            width = 4 << Math.min(nonNullTransformSize.log2Width4(), 3);
            height = coefficientCount(nonNullTransformSize) / width;
        } else {
            width = 4 << Math.min(nonNullTransformSize.log2Height4(), 3);
            height = coefficientCount(nonNullTransformSize) / width;
        }
        return coefficientLevelGrid.reset(width + 5, height + 5);
    }

    /// Returns the coefficient-context grid X coordinate for one larger-transform scan index.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the coefficient-context grid X coordinate
    private static int genericLevelX(TransformSize transformSize, TransformType transformType, int scanIndex) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (!nonNullTransformType.oneDimensional()) {
            int contextIndex = DEFAULT_CONTEXT_SCANS[nonNullTransformSize.ordinal()][scanIndex];
            int contextHeight = clippedCoefficientHeight(nonNullTransformSize);
            return contextIndex >> Integer.numberOfTrailingZeros(contextHeight);
        }
        int log2Primary = verticalOneDimensional(nonNullTransformType)
                ? Math.min(nonNullTransformSize.log2Width4(), 3)
                : Math.min(nonNullTransformSize.log2Height4(), 3);
        return scanIndex & ((4 << log2Primary) - 1);
    }

    /// Returns the coefficient-context grid Y coordinate for one larger-transform scan index.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the coefficient-context grid Y coordinate
    private static int genericLevelY(TransformSize transformSize, TransformType transformType, int scanIndex) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (!nonNullTransformType.oneDimensional()) {
            int contextIndex = DEFAULT_CONTEXT_SCANS[nonNullTransformSize.ordinal()][scanIndex];
            return contextIndex & (clippedCoefficientHeight(nonNullTransformSize) - 1);
        }
        int log2Primary = verticalOneDimensional(nonNullTransformType)
                ? Math.min(nonNullTransformSize.log2Width4(), 3)
                : Math.min(nonNullTransformSize.log2Height4(), 3);
        return scanIndex >> (log2Primary + 2);
    }

    /// Returns the base-token context for one larger-transform coefficient.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the base-token context for the supplied coefficient
    private static int genericBaseTokenContext(
            TransformSize transformSize,
            TransformType transformType,
            CoefficientLevelGrid levelBytes,
            int x,
            int y
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = genericHighMagnitude(nonNullTransformType, levelBytes, x, y);
        int offset;
        if (nonNullTransformType.oneDimensional()) {
            magnitude += levelBytes.get(x, y + 3) + levelBytes.get(x, y + 4);
            offset = 26 + (y > 1 ? 10 : y * 5);
        } else {
            magnitude += levelBytes.get(x, y + 2) + levelBytes.get(x + 2, y);
            offset = LEVEL_CONTEXT_OFFSETS[levelContextOffsetIndex(nonNullTransformSize)]
                    [Math.min(y, 4)][Math.min(x, 4)];
        }
        return offset + (magnitude > 512 ? 4 : (magnitude + 64) >> 7);
    }

    /// Returns the high-token context for one larger-transform non-DC coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-token context for the supplied coefficient
    private static int genericHighTokenContext(
            TransformType transformType,
            CoefficientLevelGrid levelBytes,
            int x,
            int y
    ) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = genericHighMagnitude(nonNullTransformType, levelBytes, x, y) & 0x3F;
        int baseContext = genericEndOfBlockHighTokenContext(nonNullTransformType, x, y);
        return baseContext + (magnitude > 12 ? 6 : (magnitude + 1) >> 1);
    }

    /// Returns the high-token context for one larger-transform end-of-block coefficient.
    ///
    /// @param transformType the active transform type
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-token context for the supplied end-of-block coefficient
    private static int genericEndOfBlockHighTokenContext(TransformType transformType, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (nonNullTransformType.oneDimensional()) {
            return y != 0 ? 14 : 7;
        }
        return ((x | y) > 1) ? 14 : 7;
    }

    /// Returns the high-token context for one larger-transform DC coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @return the high-token context for the DC coefficient
    private static int genericDcHighTokenContext(
            TransformType transformType,
            CoefficientLevelGrid levelBytes
    ) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = nonNullTransformType.oneDimensional()
                ? genericHighMagnitude(nonNullTransformType, levelBytes, 0, 0)
                : levelBytes.get(0, 1) + levelBytes.get(1, 0) + levelBytes.get(1, 1);
        magnitude &= 0x3F;
        return magnitude > 12 ? 6 : (magnitude + 1) >> 1;
    }

    /// Returns the `dav1d` high-magnitude accumulator for one larger-transform coefficient position.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-magnitude accumulator for the supplied coefficient position
    private static int genericHighMagnitude(
            TransformType transformType,
            CoefficientLevelGrid levelBytes,
            int x,
            int y
    ) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = levelBytes.get(x, y + 1) + levelBytes.get(x + 1, y);
        if (!nonNullTransformType.oneDimensional()) {
            magnitude += levelBytes.get(x + 1, y + 1);
        } else {
            magnitude += levelBytes.get(x, y + 2);
        }
        return magnitude;
    }

    /// Returns the `dav1d` low-context offset table index for one two-dimensional transform size.
    ///
    /// @param transformSize the active transform size
    /// @return `0` for square, `1` for wide, or `2` for tall
    private static int levelContextOffsetIndex(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (nonNullTransformSize.widthPixels() == nonNullTransformSize.heightPixels()) {
            return 0;
        }
        return nonNullTransformSize.widthPixels() > nonNullTransformSize.heightPixels() ? 1 : 2;
    }

    /// Returns the coefficient count modeled by entropy syntax for the supplied transform size.
    ///
    /// @param transformSize the active transform size
    /// @return the modeled coefficient count
    private static int coefficientCount(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return clippedCoefficientWidth(nonNullTransformSize) * clippedCoefficientHeight(nonNullTransformSize);
    }

    /// Returns the natural `TX_4X4` coefficient index addressed by one scan index and transform class.
    ///
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the natural `TX_4X4` coefficient index addressed by the supplied scan index
    private static int fourByFourCoefficientIndex(TransformType transformType, int scanIndex) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (!nonNullTransformType.oneDimensional()) {
            return FOUR_BY_FOUR_STORAGE_SCAN[scanIndex];
        }
        if (!verticalOneDimensional(nonNullTransformType)) {
            int x = scanIndex & 3;
            int y = scanIndex >> 2;
            return (x << 2) | y;
        }
        return scanIndex;
    }

    /// Returns the `levels` grid X coordinate for one `TX_4X4` scan index.
    ///
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the `levels` grid X coordinate for the supplied scan index
    private static int fourByFourLevelX(TransformType transformType, int scanIndex) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (nonNullTransformType.oneDimensional()) {
            return scanIndex & 3;
        }
        return FOUR_BY_FOUR_CONTEXT_SCAN[scanIndex] >> 2;
    }

    /// Returns the `levels` grid Y coordinate for one `TX_4X4` scan index.
    ///
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the `levels` grid Y coordinate for the supplied scan index
    private static int fourByFourLevelY(TransformType transformType, int scanIndex) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (nonNullTransformType.oneDimensional()) {
            return scanIndex >> 2;
        }
        return FOUR_BY_FOUR_CONTEXT_SCAN[scanIndex] & 3;
    }

    /// Returns whether a transform type belongs to the vertical one-dimensional class.
    ///
    /// @param transformType the transform type to test
    /// @return whether the supplied transform type belongs to the vertical one-dimensional class
    private static boolean verticalOneDimensional(TransformType transformType) {
        return switch (Objects.requireNonNull(transformType, "transformType")) {
            case V_DCT, V_ADST, V_FLIPADST -> true;
            default -> false;
        };
    }

    /// Returns the end-of-block base-token context for one supported end-of-block index.
    ///
    /// @param endOfBlockIndex the supported end-of-block index
    /// @param transformSize the active transform size
    /// @return the end-of-block base-token context for the supplied index
    private static int endOfBlockTokenContext(int endOfBlockIndex, TransformSize transformSize) {
        if (endOfBlockIndex < 0) {
            throw new IllegalArgumentException("endOfBlockIndex < 0: " + endOfBlockIndex);
        }
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int tx2dSizeContext = Math.min(nonNullTransformSize.log2Width4(), 3)
                + Math.min(nonNullTransformSize.log2Height4(), 3);
        return 1 + (endOfBlockIndex > (2 << tx2dSizeContext) ? 1 : 0)
                + (endOfBlockIndex > (4 << tx2dSizeContext) ? 1 : 0);
    }

    /// Returns the stored level byte written into the local `levels` grid for one coefficient token.
    ///
    /// @param token the decoded coefficient token before the optional Golomb extension
    /// @return the stored level byte written into the local `levels` grid
    private static int coefficientLevelByte(int token) {
        if (token == 0) {
            return 0;
        }
        if (token < 3) {
            return token * 0x41;
        }
        return token + (3 << 6);
    }

    /// Returns the `br_tok` high-token context for the last non-zero `TX_4X4` coefficient.
    ///
    /// @param transformType the active transform type
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the `br_tok` high-token context for the supplied coefficient
    private static int fourByFourEndOfBlockHighTokenContext(TransformType transformType, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (nonNullTransformType.oneDimensional()) {
            return y != 0 ? 14 : 7;
        }
        return ((x | y) > 1) ? 14 : 7;
    }

    /// Returns the base-token context for one non-EOB `TX_4X4` coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the base-token context for the supplied coefficient
    private static int fourByFourBaseTokenContext(
            TransformType transformType,
            CoefficientLevelGrid levelBytes,
            int x,
            int y
    ) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = fourByFourHighMagnitude(nonNullTransformType, levelBytes, x, y);
        int offset;
        if (nonNullTransformType.oneDimensional()) {
            magnitude += levelBytes.get(x, y + 3) + levelBytes.get(x, y + 4);
            offset = 26 + (y > 1 ? 10 : y * 5);
        } else {
            magnitude += levelBytes.get(x, y + 2) + levelBytes.get(x + 2, y);
            offset = LEVEL_CONTEXT_OFFSETS[0][Math.min(y, 4)][Math.min(x, 4)];
        }
        return offset + (magnitude > 512 ? 4 : (magnitude + 64) >> 7);
    }

    /// Returns the high-token context for one non-EOB `TX_4X4` coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the high-token context for the supplied coefficient
    private static int fourByFourHighTokenContext(
            TransformType transformType,
            CoefficientLevelGrid levelBytes,
            int x,
            int y
    ) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = fourByFourHighMagnitude(nonNullTransformType, levelBytes, x, y) & 0x3F;
        int baseContext = nonNullTransformType.oneDimensional()
                ? (y > 0 ? 14 : 7)
                : (((x | y) > 1) ? 14 : 7);
        return baseContext + (magnitude > 12 ? 6 : (magnitude + 1) >> 1);
    }

    /// Returns the DC high-token context for one `TX_4X4` residual block.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @return the DC high-token context for the current residual block
    private static int fourByFourDcHighTokenContext(
            TransformType transformType,
            CoefficientLevelGrid levelBytes
    ) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = fourByFourHighMagnitude(nonNullTransformType, levelBytes, 0, 0) & 0x3F;
        return magnitude > 12 ? 6 : (magnitude + 1) >> 1;
    }

    /// Returns the `dav1d` high-magnitude accumulator for one `TX_4X4` coefficient position.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the `dav1d` high-magnitude accumulator for the supplied coefficient position
    private static int fourByFourHighMagnitude(
            TransformType transformType,
            CoefficientLevelGrid levelBytes,
            int x,
            int y
    ) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = levelBytes.get(x, y + 1) + levelBytes.get(x + 1, y);
        if (!nonNullTransformType.oneDimensional()) {
            magnitude += levelBytes.get(x + 1, y + 1);
        } else {
            magnitude += levelBytes.get(x, y + 2);
        }
        return magnitude;
    }

    /// Creates the stored coefficient-context byte for one non-zero transform unit.
    ///
    /// @param cumulativeLevel the sum of decoded absolute coefficient levels, clamped later to six bits
    /// @param dcSign the decoded DC sign as `-1`, `0`, or `1`; this remains non-zero when a Golomb-extended
    /// DC magnitude wraps to zero under the AV1 20-bit coefficient mask
    /// @return the stored coefficient-context byte for one non-zero transform unit
    static int createNonZeroCoefficientContextByte(int cumulativeLevel, int dcSign) {
        int magnitude = Math.min(cumulativeLevel, 63);
        if (dcSign < 0) {
            return magnitude;
        }
        if (dcSign > 0) {
            return magnitude | 0x80;
        }
        return magnitude | 0x40;
    }

    /// Applies the AV1 20-bit coefficient-magnitude wrap after Golomb extension.
    ///
    /// @param magnitude the unmasked coefficient magnitude
    /// @return the low 20 bits of the supplied magnitude
    static int maskCoefficientMagnitude(int magnitude) {
        return magnitude & COEFFICIENT_MAGNITUDE_MASK;
    }

    /// Returns the entropy-coded coefficient width for the supplied transform size.
    ///
    /// AV1 codes at most a 32-sample coefficient span on either axis for 64-wide or 64-high
    /// transform sizes. The remaining inverse-transform coefficients stay zero.
    ///
    /// @param transformSize the active transform size
    /// @return the entropy-coded coefficient width
    private static int clippedCoefficientWidth(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return 4 << Math.min(nonNullTransformSize.log2Width4(), 3);
    }

    /// Returns the entropy-coded coefficient height for the supplied transform size.
    ///
    /// AV1 codes at most a 32-sample coefficient span on either axis for 64-wide or 64-high
    /// transform sizes. The remaining inverse-transform coefficients stay zero.
    ///
    /// @param transformSize the active transform size
    /// @return the entropy-coded coefficient height
    private static int clippedCoefficientHeight(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return 4 << Math.min(nonNullTransformSize.log2Height4(), 3);
    }

    /// Creates the `dav1d`-compatible two-dimensional scan table for every modeled transform size.
    ///
    /// @return the `dav1d`-compatible two-dimensional scan table for every modeled transform size
    private static int[][] createDefaultScans(boolean contextLayout) {
        TransformSize[] transformSizes = TransformSize.values();
        int[][] scans = new int[transformSizes.length][];
        for (TransformSize transformSize : transformSizes) {
            scans[transformSize.ordinal()] = createDefaultScan(transformSize, contextLayout);
        }
        return scans;
    }

    /// Creates the `dav1d`-compatible two-dimensional scan table for one modeled transform size.
    ///
    /// `dav1d` stores two-dimensional coefficient scan entries in the transform scratch layout
    /// addressed as `coeff[y + x * sh]`. This method can emit either those raw scratch indices for
    /// coefficient-token contexts or the same scan converted into this decoder's row-major natural
    /// coefficient layout for residual storage.
    ///
    /// @param transformSize the modeled transform size whose scan should be created
    /// @param contextLayout whether to emit the raw `dav1d` scratch-layout scan indices
    /// @return the `dav1d`-compatible two-dimensional scan table for the supplied transform size
    static int[] createDefaultScan(TransformSize transformSize, boolean contextLayout) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int codedWidth = clippedCoefficientWidth(nonNullTransformSize);
        int codedHeight = clippedCoefficientHeight(nonNullTransformSize);
        int outputWidth = nonNullTransformSize.widthPixels();
        int[] scan = new int[codedWidth * codedHeight];
        int nextIndex = 0;
        for (int diagonal = 0; diagonal < codedWidth + codedHeight - 1; diagonal++) {
            int rowStart = Math.max(0, diagonal - (codedWidth - 1));
            int rowEnd = Math.min(codedHeight - 1, diagonal);
            boolean descendingRows = codedWidth == codedHeight
                    ? (diagonal & 1) == 0
                    : codedWidth > codedHeight;
            if (descendingRows) {
                for (int row = rowEnd; row >= rowStart; row--) {
                    int column = diagonal - row;
                    scan[nextIndex++] = scanIndex(row, column, outputWidth, codedHeight, contextLayout);
                }
            } else {
                for (int row = rowStart; row <= rowEnd; row++) {
                    int column = diagonal - row;
                    scan[nextIndex++] = scanIndex(row, column, outputWidth, codedHeight, contextLayout);
                }
            }
        }
        return scan;
    }

    /// Converts one generated scan coordinate into this decoder's row-major coefficient index.
    ///
    /// @param row the generated diagonal row coordinate
    /// @param column the generated diagonal column coordinate
    /// @param outputWidth the row-major coefficient row stride
    /// @param codedHeight the entropy-coded coefficient height used as the scratch stride
    /// @param contextLayout whether to emit the raw `dav1d` scratch-layout scan index
    /// @return the selected coefficient index
    private static int scanIndex(
            int row,
            int column,
            int outputWidth,
            int codedHeight,
            boolean contextLayout
    ) {
        if (contextLayout) {
            return column * codedHeight + row;
        }
        return row * outputWidth + column;
    }

    /// Reusable flat coefficient-level grid addressed in AV1's X-major scratch layout.
    
    private static final class CoefficientLevelGrid {
        /// The number of stored Y coordinates per X coordinate.
        private int height;

        /// The flattened X-major level values.
        private int[] values = new int[0];

        /// Creates an empty reusable coefficient-level grid.
        private CoefficientLevelGrid() {
        }

        /// Resizes this grid if necessary and clears the active extent.
        ///
        /// @param width the stored X extent
        /// @param height the stored Y extent
        /// @return this zero-filled grid
        private CoefficientLevelGrid reset(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Coefficient level-grid dimensions must be positive");
            }
            int requiredLength = Math.multiplyExact(width, height);
            if (values.length < requiredLength) {
                values = new int[requiredLength];
            } else {
                Arrays.fill(values, 0, requiredLength, 0);
            }
            this.height = height;
            return this;
        }

        /// Returns one stored level value.
        ///
        /// @param x the zero-based X coordinate
        /// @param y the zero-based Y coordinate
        /// @return the stored level value
        private int get(int x, int y) {
            return values[x * height + y];
        }

        /// Stores one level value.
        ///
        /// @param x the zero-based X coordinate
        /// @param y the zero-based Y coordinate
        /// @param value the replacement level value
        private void set(int x, int y, int value) {
            values[x * height + y] = value;
        }
    }
}
