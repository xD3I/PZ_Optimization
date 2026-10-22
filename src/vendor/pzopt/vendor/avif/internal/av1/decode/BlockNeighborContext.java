// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.av1.Av1FrameType;
import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.CompoundPredictionType;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.InterMotionVector;
import pzopt.vendor.avif.internal.av1.model.LumaIntraPredictionMode;
import pzopt.vendor.avif.internal.av1.model.MotionVector;
import pzopt.vendor.avif.internal.av1.model.TransformSize;
import pzopt.vendor.avif.internal.av1.model.TransformUnit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Tile-local neighbor context state used to derive block-level syntax contexts while scanning a tile.

public final class BlockNeighborContext {
    /// Shared provisional zero vector used when a decoded block has no stored motion vector.
    private static final InterMotionVector PREDICTED_ZERO_MOTION_VECTOR =
            InterMotionVector.predicted(MotionVector.zero());

    /// The maximum number of provisional motion-vector candidate entries retained locally.
    private static final int PROVISIONAL_CANDIDATE_CAPACITY = 8;

    /// The frame-border extension allowed for reference motion vectors in eighth-pel units.
    private static final int REFERENCE_MOTION_VECTOR_BORDER = 16 * 8;

    /// The number of eighth-pel motion-vector units represented by one 4x4 coding unit.
    private static final int MOTION_VECTOR_UNITS_PER_4X4 = 4 * 8;

    /// Internal reference selector used while scanning same-frame intrabc displacement vectors.
    private static final int INTRABC_REFERENCE_FRAME = -2;

    /// The AV1 coefficient-context byte that marks one transform block as all-zero.
    private static final int ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x40;

    /// The inter var-tx edge state used where no top or left transform neighbor is available.
    private static final byte UNAVAILABLE_INTER_TRANSFORM_LOG2 = (byte) TransformSize.TX_64X64.log2Width4();

    /// The switchable interpolation-filter symbol used for regular 8-tap filtering.
    private static final byte INTERPOLATION_FILTER_REGULAR = 0;

    /// The switchable interpolation-filter symbol used for smooth 8-tap filtering.
    private static final byte INTERPOLATION_FILTER_SMOOTH = 1;

    /// The switchable interpolation-filter symbol used for sharp 8-tap filtering.
    private static final byte INTERPOLATION_FILTER_SHARP = 2;

    /// The switchable interpolation-filter sentinel used when no usable neighbor filter is available.
    private static final byte INTERPOLATION_FILTER_UNSET = 3;

    /// The `dav1d` luma coefficient skip-context lookup table indexed by merged top and left counts.
    private static final int [] [] LUMA_COEFFICIENT_SKIP_CONTEXTS = {
            {1, 2, 2, 2, 3},
            {2, 4, 4, 4, 5},
            {2, 4, 4, 4, 5},
            {2, 4, 4, 4, 5},
            {3, 5, 5, 5, 6}
    };

    /// The tile width rounded up to 4x4 units.
    private final int tileWidth4;

    /// The tile height rounded up to 4x4 units.
    private final int tileHeight4;

    /// The coded frame width rounded up to 4x4 units on the 8x8 motion-vector grid.
    private final int frameWidth4;

    /// The coded frame height rounded up to 4x4 units on the 8x8 motion-vector grid.
    private final int frameHeight4;

    /// The horizontal chroma subsampling shift, or `0` when chroma uses full horizontal resolution.
    private final int chromaSubsamplingX;

    /// The vertical chroma subsampling shift, or `0` when chroma uses full vertical resolution.
    private final int chromaSubsamplingY;

    /// The tile-local temporal motion field produced while decoding the current frame.
    private final TileDecodeContext.TemporalMotionField decodedTemporalMotionField;

    /// The current frame's immutable projected reference motion field.
    private final ReferenceMotionVectorProjection referenceMotionVectorProjection;

    /// The mutable current-frame segment identifiers shared by all tiles.
    private final SegmentIdMap currentSegmentIdMap;

    /// Whether decoded block segment identifiers replace the current map footprint.
    private final boolean updateSegmentIdMap;

    /// The tile's frame-relative horizontal origin in 8x8 units.
    private final int tileStartX8;

    /// The tile's frame-relative vertical origin in 8x8 units.
    private final int tileStartY8;

    /// The stored decoded block map indexed in tile-relative 4x4 units.
    private final StoredBlock[] storedBlocks;

    /// Reusable candidate workspace whose active prefix is copied into each returned context.
    private final ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] provisionalCandidateScratch =
            new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[PROVISIONAL_CANDIDATE_CAPACITY];

    /// Reusable reference-count workspace for the immediately reduced syntax contexts.
    private final int[] referenceCountScratch = new int[4];

    /// The above-edge intra flags indexed in 4x4 units.
    private final byte[] aboveIntra;

    /// The left-edge intra flags indexed in 4x4 units.
    private final byte[] leftIntra;

    /// The above-edge skip flags indexed in 4x4 units.
    private final byte[] aboveSkip;

    /// The left-edge skip flags indexed in 4x4 units.
    private final byte[] leftSkip;

    /// The above-edge skip-mode flags indexed in 4x4 units.
    private final byte[] aboveSkipMode;

    /// The left-edge skip-mode flags indexed in 4x4 units.
    private final byte[] leftSkipMode;

    /// The above-edge compound-reference flags indexed in 4x4 units.
    private final byte[] aboveCompoundReference;

    /// The left-edge compound-reference flags indexed in 4x4 units.
    private final byte[] leftCompoundReference;

    /// The above-edge compound-prediction type context values indexed in 4x4 units.
    private final byte[] aboveCompoundPredictionType;

    /// The left-edge compound-prediction type context values indexed in 4x4 units.
    private final byte[] leftCompoundPredictionType;

    /// The above-edge primary reference-frame indices indexed in 4x4 units.
    private final byte[] aboveReferenceFrame0;

    /// The left-edge primary reference-frame indices indexed in 4x4 units.
    private final byte[] leftReferenceFrame0;

    /// The above-edge secondary reference-frame indices indexed in 4x4 units.
    private final byte[] aboveReferenceFrame1;

    /// The left-edge secondary reference-frame indices indexed in 4x4 units.
    private final byte[] leftReferenceFrame1;

    /// The above-edge primary motion vectors indexed in 4x4 units.
    private final InterMotionVector[] aboveMotionVector0;

    /// The left-edge primary motion vectors indexed in 4x4 units.
    private final InterMotionVector[] leftMotionVector0;

    /// The above-edge secondary motion vectors indexed in 4x4 units.
    private final InterMotionVector[] aboveMotionVector1;

    /// The left-edge secondary motion vectors indexed in 4x4 units.
    private final InterMotionVector[] leftMotionVector1;

    /// The above-edge `NEWMV` usage flags indexed in 4x4 units.
    private final byte[] aboveUsesNewMotionVector;

    /// The left-edge `NEWMV` usage flags indexed in 4x4 units.
    private final byte[] leftUsesNewMotionVector;

    /// The above-edge horizontal switchable interpolation-filter symbols indexed in 4x4 units.
    private final byte[] aboveInterpolationFilterHorizontal;

    /// The left-edge horizontal switchable interpolation-filter symbols indexed in 4x4 units.
    private final byte[] leftInterpolationFilterHorizontal;

    /// The above-edge vertical switchable interpolation-filter symbols indexed in 4x4 units.
    private final byte[] aboveInterpolationFilterVertical;

    /// The left-edge vertical switchable interpolation-filter symbols indexed in 4x4 units.
    private final byte[] leftInterpolationFilterVertical;

    /// The above-edge segmentation-prediction flags indexed in 4x4 units.
    private final byte[] aboveSegmentPredicted;

    /// The left-edge segmentation-prediction flags indexed in 4x4 units.
    private final byte[] leftSegmentPredicted;

    /// The above-edge luma palette sizes indexed in 4x4 units.
    private final byte[] abovePaletteSize;

    /// The left-edge luma palette sizes indexed in 4x4 units.
    private final byte[] leftPaletteSize;

    /// The above-edge chroma palette sizes indexed in 4x4 units.
    private final byte[] aboveChromaPaletteSize;

    /// The left-edge chroma palette sizes indexed in 4x4 units.
    private final byte[] leftChromaPaletteSize;

    /// The above-edge palette entries indexed as plane/x4/palette index.
    private final int[][][] abovePaletteEntries;

    /// The left-edge palette entries indexed as plane/y4/palette index.
    private final int[][][] leftPaletteEntries;

    /// The above-edge luma modes indexed in 4x4 units.
    private final LumaIntraPredictionMode[] aboveMode;

    /// The left-edge luma modes indexed in 4x4 units.
    private final LumaIntraPredictionMode[] leftMode;

    /// The above-edge partition context state indexed in 8x8 units.
    private final byte[] abovePartition;

    /// The left-edge partition context state indexed in 8x8 units.
    private final byte[] leftPartition;

    /// The above-edge transform-context widths indexed in 4x4 units.
    private final byte[] aboveTransformWidthLog2;

    /// The left-edge transform-context heights indexed in 4x4 units.
    private final byte[] leftTransformHeightLog2;

    /// The above-edge inter var-tx widths indexed in 4x4 units.
    private final byte[] aboveInterTransformWidthLog2;

    /// The left-edge inter var-tx heights indexed in 4x4 units.
    private final byte[] leftInterTransformHeightLog2;

    /// The above-edge luma coefficient-context bytes indexed in 4x4 units.
    private final byte[] aboveLumaCoefficientContext;

    /// The left-edge luma coefficient-context bytes indexed in 4x4 units.
    private final byte[] leftLumaCoefficientContext;

    /// The above-edge chroma coefficient-context bytes indexed as plane/chroma-4x4 X.
    private final byte[][] aboveChromaCoefficientContext;

    /// The left-edge chroma coefficient-context bytes indexed as plane/chroma-4x4 Y.
    private final byte[][] leftChromaCoefficientContext;

    /// Creates tile-local neighbor context state.
    ///
    /// @param tileWidth4 the tile width rounded up to 4x4 units
    /// @param tileHeight4 the tile height rounded up to 4x4 units
    /// @param frameWidth4 the coded frame width rounded up to 4x4 units on the 8x8 grid
    /// @param frameHeight4 the coded frame height rounded up to 4x4 units on the 8x8 grid
    /// @param chromaSubsamplingX the horizontal chroma subsampling shift
    /// @param chromaSubsamplingY the vertical chroma subsampling shift
    /// @param decodedTemporalMotionField the tile-local temporal motion field produced while decoding the current frame
    /// @param referenceMotionVectorProjection the current frame's immutable projected reference motion field
    /// @param currentSegmentIdMap the mutable current-frame segment identifiers
    /// @param updateSegmentIdMap whether decoded block identifiers replace their map footprint
    /// @param tileStartX8 the tile's frame-relative horizontal origin in 8x8 units
    /// @param tileStartY8 the tile's frame-relative vertical origin in 8x8 units
    /// @param storedBlocks the stored decoded block map indexed in tile-relative 4x4 units
    /// @param aboveIntra the above-edge intra flags indexed in 4x4 units
    /// @param leftIntra the left-edge intra flags indexed in 4x4 units
    /// @param aboveSkip the above-edge skip flags indexed in 4x4 units
    /// @param leftSkip the left-edge skip flags indexed in 4x4 units
    /// @param aboveSkipMode the above-edge skip-mode flags indexed in 4x4 units
    /// @param leftSkipMode the left-edge skip-mode flags indexed in 4x4 units
    /// @param aboveCompoundReference the above-edge compound-reference flags indexed in 4x4 units
    /// @param leftCompoundReference the left-edge compound-reference flags indexed in 4x4 units
    /// @param aboveCompoundPredictionType the above-edge compound-prediction type context values indexed in 4x4 units
    /// @param leftCompoundPredictionType the left-edge compound-prediction type context values indexed in 4x4 units
    /// @param aboveReferenceFrame0 the above-edge primary reference-frame indices indexed in 4x4 units
    /// @param leftReferenceFrame0 the left-edge primary reference-frame indices indexed in 4x4 units
    /// @param aboveReferenceFrame1 the above-edge secondary reference-frame indices indexed in 4x4 units
    /// @param leftReferenceFrame1 the left-edge secondary reference-frame indices indexed in 4x4 units
    /// @param aboveMotionVector0 the above-edge primary motion vectors indexed in 4x4 units
    /// @param leftMotionVector0 the left-edge primary motion vectors indexed in 4x4 units
    /// @param aboveMotionVector1 the above-edge secondary motion vectors indexed in 4x4 units
    /// @param leftMotionVector1 the left-edge secondary motion vectors indexed in 4x4 units
    /// @param aboveUsesNewMotionVector the above-edge `NEWMV` usage flags indexed in 4x4 units
    /// @param leftUsesNewMotionVector the left-edge `NEWMV` usage flags indexed in 4x4 units
    /// @param aboveInterpolationFilterHorizontal the above-edge horizontal switchable interpolation-filter symbols indexed in 4x4 units
    /// @param leftInterpolationFilterHorizontal the left-edge horizontal switchable interpolation-filter symbols indexed in 4x4 units
    /// @param aboveInterpolationFilterVertical the above-edge vertical switchable interpolation-filter symbols indexed in 4x4 units
    /// @param leftInterpolationFilterVertical the left-edge vertical switchable interpolation-filter symbols indexed in 4x4 units
    /// @param aboveSegmentPredicted the above-edge segmentation-prediction flags indexed in 4x4 units
    /// @param leftSegmentPredicted the left-edge segmentation-prediction flags indexed in 4x4 units
    /// @param abovePaletteSize the above-edge luma palette sizes indexed in 4x4 units
    /// @param leftPaletteSize the left-edge luma palette sizes indexed in 4x4 units
    /// @param aboveChromaPaletteSize the above-edge chroma palette sizes indexed in 4x4 units
    /// @param leftChromaPaletteSize the left-edge chroma palette sizes indexed in 4x4 units
    /// @param abovePaletteEntries the above-edge palette entries indexed as plane/x4/palette index
    /// @param leftPaletteEntries the left-edge palette entries indexed as plane/y4/palette index
    /// @param aboveMode the above-edge luma modes indexed in 4x4 units
    /// @param leftMode the left-edge luma modes indexed in 4x4 units
    /// @param abovePartition the above-edge partition context state indexed in 8x8 units
    /// @param leftPartition the left-edge partition context state indexed in 8x8 units
    /// @param aboveTransformWidthLog2 the above-edge transform-context widths indexed in 4x4 units
    /// @param leftTransformHeightLog2 the left-edge transform-context heights indexed in 4x4 units
    /// @param aboveInterTransformWidthLog2 the above-edge inter var-tx widths indexed in 4x4 units
    /// @param leftInterTransformHeightLog2 the left-edge inter var-tx heights indexed in 4x4 units
    /// @param aboveLumaCoefficientContext the above-edge luma coefficient-context bytes indexed in 4x4 units
    /// @param leftLumaCoefficientContext the left-edge luma coefficient-context bytes indexed in 4x4 units
    /// @param aboveChromaCoefficientContext the above-edge chroma coefficient-context bytes indexed as plane/chroma-4x4 X
    /// @param leftChromaCoefficientContext the left-edge chroma coefficient-context bytes indexed as plane/chroma-4x4 Y
    private BlockNeighborContext(
            int tileWidth4,
            int tileHeight4,
            int frameWidth4,
            int frameHeight4,
            int chromaSubsamplingX,
            int chromaSubsamplingY,
            TileDecodeContext.TemporalMotionField decodedTemporalMotionField,
            ReferenceMotionVectorProjection referenceMotionVectorProjection,
            SegmentIdMap currentSegmentIdMap,
            boolean updateSegmentIdMap,
            int tileStartX8,
            int tileStartY8,
            StoredBlock[] storedBlocks,
            byte[] aboveIntra,
            byte[] leftIntra,
            byte[] aboveSkip,
            byte[] leftSkip,
            byte[] aboveSkipMode,
            byte[] leftSkipMode,
            byte[] aboveCompoundReference,
            byte[] leftCompoundReference,
            byte[] aboveCompoundPredictionType,
            byte[] leftCompoundPredictionType,
            byte[] aboveReferenceFrame0,
            byte[] leftReferenceFrame0,
            byte[] aboveReferenceFrame1,
            byte[] leftReferenceFrame1,
            InterMotionVector[] aboveMotionVector0,
            InterMotionVector[] leftMotionVector0,
            InterMotionVector[] aboveMotionVector1,
            InterMotionVector[] leftMotionVector1,
            byte[] aboveUsesNewMotionVector,
            byte[] leftUsesNewMotionVector,
            byte[] aboveInterpolationFilterHorizontal,
            byte[] leftInterpolationFilterHorizontal,
            byte[] aboveInterpolationFilterVertical,
            byte[] leftInterpolationFilterVertical,
            byte[] aboveSegmentPredicted,
            byte[] leftSegmentPredicted,
            byte[] abovePaletteSize,
            byte[] leftPaletteSize,
            byte[] aboveChromaPaletteSize,
            byte[] leftChromaPaletteSize,
            int[][][] abovePaletteEntries,
            int[][][] leftPaletteEntries,
            LumaIntraPredictionMode[] aboveMode,
            LumaIntraPredictionMode[] leftMode,
            byte[] abovePartition,
            byte[] leftPartition,
            byte[] aboveTransformWidthLog2,
            byte[] leftTransformHeightLog2,
            byte[] aboveInterTransformWidthLog2,
            byte[] leftInterTransformHeightLog2,
            byte[] aboveLumaCoefficientContext,
            byte[] leftLumaCoefficientContext,
            byte[][] aboveChromaCoefficientContext,
            byte[][] leftChromaCoefficientContext
    ) {
        this.tileWidth4 = tileWidth4;
        this.tileHeight4 = tileHeight4;
        this.frameWidth4 = frameWidth4;
        this.frameHeight4 = frameHeight4;
        this.chromaSubsamplingX = chromaSubsamplingX;
        this.chromaSubsamplingY = chromaSubsamplingY;
        this.decodedTemporalMotionField = Objects.requireNonNull(decodedTemporalMotionField, "decodedTemporalMotionField");
        this.referenceMotionVectorProjection = Objects.requireNonNull(
                referenceMotionVectorProjection,
                "referenceMotionVectorProjection"
        );
        this.currentSegmentIdMap = Objects.requireNonNull(currentSegmentIdMap, "currentSegmentIdMap");
        this.updateSegmentIdMap = updateSegmentIdMap;
        this.tileStartX8 = tileStartX8;
        this.tileStartY8 = tileStartY8;
        this.storedBlocks = Objects.requireNonNull(storedBlocks, "storedBlocks");
        this.aboveIntra = Objects.requireNonNull(aboveIntra, "aboveIntra");
        this.leftIntra = Objects.requireNonNull(leftIntra, "leftIntra");
        this.aboveSkip = Objects.requireNonNull(aboveSkip, "aboveSkip");
        this.leftSkip = Objects.requireNonNull(leftSkip, "leftSkip");
        this.aboveSkipMode = Objects.requireNonNull(aboveSkipMode, "aboveSkipMode");
        this.leftSkipMode = Objects.requireNonNull(leftSkipMode, "leftSkipMode");
        this.aboveCompoundReference = Objects.requireNonNull(aboveCompoundReference, "aboveCompoundReference");
        this.leftCompoundReference = Objects.requireNonNull(leftCompoundReference, "leftCompoundReference");
        this.aboveCompoundPredictionType = Objects.requireNonNull(
                aboveCompoundPredictionType,
                "aboveCompoundPredictionType"
        );
        this.leftCompoundPredictionType = Objects.requireNonNull(
                leftCompoundPredictionType,
                "leftCompoundPredictionType"
        );
        this.aboveReferenceFrame0 = Objects.requireNonNull(aboveReferenceFrame0, "aboveReferenceFrame0");
        this.leftReferenceFrame0 = Objects.requireNonNull(leftReferenceFrame0, "leftReferenceFrame0");
        this.aboveReferenceFrame1 = Objects.requireNonNull(aboveReferenceFrame1, "aboveReferenceFrame1");
        this.leftReferenceFrame1 = Objects.requireNonNull(leftReferenceFrame1, "leftReferenceFrame1");
        this.aboveMotionVector0 = Objects.requireNonNull(aboveMotionVector0, "aboveMotionVector0");
        this.leftMotionVector0 = Objects.requireNonNull(leftMotionVector0, "leftMotionVector0");
        this.aboveMotionVector1 = Objects.requireNonNull(aboveMotionVector1, "aboveMotionVector1");
        this.leftMotionVector1 = Objects.requireNonNull(leftMotionVector1, "leftMotionVector1");
        this.aboveUsesNewMotionVector = Objects.requireNonNull(aboveUsesNewMotionVector, "aboveUsesNewMotionVector");
        this.leftUsesNewMotionVector = Objects.requireNonNull(leftUsesNewMotionVector, "leftUsesNewMotionVector");
        this.aboveInterpolationFilterHorizontal = Objects.requireNonNull(
                aboveInterpolationFilterHorizontal,
                "aboveInterpolationFilterHorizontal"
        );
        this.leftInterpolationFilterHorizontal = Objects.requireNonNull(
                leftInterpolationFilterHorizontal,
                "leftInterpolationFilterHorizontal"
        );
        this.aboveInterpolationFilterVertical = Objects.requireNonNull(
                aboveInterpolationFilterVertical,
                "aboveInterpolationFilterVertical"
        );
        this.leftInterpolationFilterVertical = Objects.requireNonNull(
                leftInterpolationFilterVertical,
                "leftInterpolationFilterVertical"
        );
        this.aboveSegmentPredicted = Objects.requireNonNull(aboveSegmentPredicted, "aboveSegmentPredicted");
        this.leftSegmentPredicted = Objects.requireNonNull(leftSegmentPredicted, "leftSegmentPredicted");
        this.abovePaletteSize = Objects.requireNonNull(abovePaletteSize, "abovePaletteSize");
        this.leftPaletteSize = Objects.requireNonNull(leftPaletteSize, "leftPaletteSize");
        this.aboveChromaPaletteSize = Objects.requireNonNull(aboveChromaPaletteSize, "aboveChromaPaletteSize");
        this.leftChromaPaletteSize = Objects.requireNonNull(leftChromaPaletteSize, "leftChromaPaletteSize");
        this.abovePaletteEntries = Objects.requireNonNull(abovePaletteEntries, "abovePaletteEntries");
        this.leftPaletteEntries = Objects.requireNonNull(leftPaletteEntries, "leftPaletteEntries");
        this.aboveMode = Objects.requireNonNull(aboveMode, "aboveMode");
        this.leftMode = Objects.requireNonNull(leftMode, "leftMode");
        this.abovePartition = Objects.requireNonNull(abovePartition, "abovePartition");
        this.leftPartition = Objects.requireNonNull(leftPartition, "leftPartition");
        this.aboveTransformWidthLog2 = Objects.requireNonNull(aboveTransformWidthLog2, "aboveTransformWidthLog2");
        this.leftTransformHeightLog2 = Objects.requireNonNull(leftTransformHeightLog2, "leftTransformHeightLog2");
        this.aboveInterTransformWidthLog2 = Objects.requireNonNull(
                aboveInterTransformWidthLog2,
                "aboveInterTransformWidthLog2"
        );
        this.leftInterTransformHeightLog2 = Objects.requireNonNull(
                leftInterTransformHeightLog2,
                "leftInterTransformHeightLog2"
        );
        this.aboveLumaCoefficientContext = Objects.requireNonNull(
                aboveLumaCoefficientContext,
                "aboveLumaCoefficientContext"
        );
        this.leftLumaCoefficientContext = Objects.requireNonNull(
                leftLumaCoefficientContext,
                "leftLumaCoefficientContext"
        );
        this.aboveChromaCoefficientContext = Objects.requireNonNull(
                aboveChromaCoefficientContext,
                "aboveChromaCoefficientContext"
        );
        this.leftChromaCoefficientContext = Objects.requireNonNull(
                leftChromaCoefficientContext,
                "leftChromaCoefficientContext"
        );
    }

    /// Creates initialized neighbor context state for one tile.
    ///
    /// @param tileContext the tile-local decode context
    /// @return initialized neighbor context state for one tile
    public static BlockNeighborContext create(TileDecodeContext tileContext) {
        TileDecodeContext nonNullTileContext = Objects.requireNonNull(tileContext, "tileContext");
        int tileWidth4 = nonNullTileContext.codedWidth4();
        int tileHeight4 = nonNullTileContext.codedHeight4();
        int frameWidth4 = ((nonNullTileContext.frameHeader().frameSize().codedWidth() + 7) >> 3) << 1;
        int frameHeight4 = ((nonNullTileContext.frameHeader().frameSize().height() + 7) >> 3) << 1;
        int tileWidth8 = (tileWidth4 + 1) >> 1;
        int tileHeight8 = (tileHeight4 + 1) >> 1;
        int chromaSubsamplingX = chromaSubsamplingX(nonNullTileContext.sequenceHeader().colorConfig().chromaFormat());
        int chromaSubsamplingY = chromaSubsamplingY(nonNullTileContext.sequenceHeader().colorConfig().chromaFormat());
        int chromaTileWidth4 = chromaTileSpan(tileWidth4, chromaSubsamplingX);
        int chromaTileHeight4 = chromaTileSpan(tileHeight4, chromaSubsamplingY);
        boolean keyFrame = nonNullTileContext.frameHeader().frameType() == Av1FrameType.KEY;

        byte[] aboveIntra = new byte[tileWidth4];
        byte[] leftIntra = new byte[tileHeight4];
        byte[] aboveReferenceFrame0 = new byte[tileWidth4];
        byte[] leftReferenceFrame0 = new byte[tileHeight4];
        byte[] aboveReferenceFrame1 = new byte[tileWidth4];
        byte[] leftReferenceFrame1 = new byte[tileHeight4];
        byte[] aboveTransformWidthLog2 = new byte[tileWidth4];
        byte[] leftTransformHeightLog2 = new byte[tileHeight4];
        byte[] aboveInterTransformWidthLog2 = new byte[tileWidth4];
        byte[] leftInterTransformHeightLog2 = new byte[tileHeight4];
        byte[] aboveLumaCoefficientContext = new byte[tileWidth4];
        byte[] leftLumaCoefficientContext = new byte[tileHeight4];
        byte[][] aboveChromaCoefficientContext = new byte[2][chromaTileWidth4];
        byte[][] leftChromaCoefficientContext = new byte[2][chromaTileHeight4];
        InterMotionVector[] aboveMotionVector0 = new InterMotionVector[tileWidth4];
        InterMotionVector[] leftMotionVector0 = new InterMotionVector[tileHeight4];
        InterMotionVector[] aboveMotionVector1 = new InterMotionVector[tileWidth4];
        InterMotionVector[] leftMotionVector1 = new InterMotionVector[tileHeight4];
        byte[] aboveInterpolationFilterHorizontal = new byte[tileWidth4];
        byte[] leftInterpolationFilterHorizontal = new byte[tileHeight4];
        byte[] aboveInterpolationFilterVertical = new byte[tileWidth4];
        byte[] leftInterpolationFilterVertical = new byte[tileHeight4];
        LumaIntraPredictionMode[] aboveMode = new LumaIntraPredictionMode[tileWidth4];
        LumaIntraPredictionMode[] leftMode = new LumaIntraPredictionMode[tileHeight4];
        InterMotionVector defaultMotionVector = PREDICTED_ZERO_MOTION_VECTOR;
        Arrays.fill(aboveTransformWidthLog2, (byte) -1);
        Arrays.fill(leftTransformHeightLog2, (byte) -1);
        Arrays.fill(aboveInterTransformWidthLog2, UNAVAILABLE_INTER_TRANSFORM_LOG2);
        Arrays.fill(leftInterTransformHeightLog2, UNAVAILABLE_INTER_TRANSFORM_LOG2);
        Arrays.fill(aboveLumaCoefficientContext, (byte) ALL_ZERO_COEFFICIENT_CONTEXT_BYTE);
        Arrays.fill(leftLumaCoefficientContext, (byte) ALL_ZERO_COEFFICIENT_CONTEXT_BYTE);
        Arrays.fill(aboveChromaCoefficientContext[0], (byte) ALL_ZERO_COEFFICIENT_CONTEXT_BYTE);
        Arrays.fill(aboveChromaCoefficientContext[1], (byte) ALL_ZERO_COEFFICIENT_CONTEXT_BYTE);
        Arrays.fill(leftChromaCoefficientContext[0], (byte) ALL_ZERO_COEFFICIENT_CONTEXT_BYTE);
        Arrays.fill(leftChromaCoefficientContext[1], (byte) ALL_ZERO_COEFFICIENT_CONTEXT_BYTE);
        Arrays.fill(aboveReferenceFrame0, (byte) -1);
        Arrays.fill(leftReferenceFrame0, (byte) -1);
        Arrays.fill(aboveReferenceFrame1, (byte) -1);
        Arrays.fill(leftReferenceFrame1, (byte) -1);
        Arrays.fill(aboveMotionVector0, defaultMotionVector);
        Arrays.fill(leftMotionVector0, defaultMotionVector);
        Arrays.fill(aboveMotionVector1, defaultMotionVector);
        Arrays.fill(leftMotionVector1, defaultMotionVector);
        Arrays.fill(aboveInterpolationFilterHorizontal, INTERPOLATION_FILTER_UNSET);
        Arrays.fill(leftInterpolationFilterHorizontal, INTERPOLATION_FILTER_UNSET);
        Arrays.fill(aboveInterpolationFilterVertical, INTERPOLATION_FILTER_UNSET);
        Arrays.fill(leftInterpolationFilterVertical, INTERPOLATION_FILTER_UNSET);
        Arrays.fill(aboveMode, LumaIntraPredictionMode.DC);
        Arrays.fill(leftMode, LumaIntraPredictionMode.DC);
        if (keyFrame) {
            Arrays.fill(aboveIntra, (byte) 1);
            Arrays.fill(leftIntra, (byte) 1);
        }

        return new BlockNeighborContext(
                tileWidth4,
                tileHeight4,
                frameWidth4,
                frameHeight4,
                chromaSubsamplingX,
                chromaSubsamplingY,
                nonNullTileContext.decodedTemporalMotionField(),
                nonNullTileContext.referenceMotionVectorProjection(),
                nonNullTileContext.currentSegmentIdMap(),
                nonNullTileContext.frameHeader().segmentation().enabled()
                        && nonNullTileContext.frameHeader().segmentation().updateMap(),
                nonNullTileContext.startX() >> 3,
                nonNullTileContext.startY() >> 3,
                new StoredBlock[tileWidth4 * tileHeight4],
                aboveIntra,
                leftIntra,
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                aboveReferenceFrame0,
                leftReferenceFrame0,
                aboveReferenceFrame1,
                leftReferenceFrame1,
                aboveMotionVector0,
                leftMotionVector0,
                aboveMotionVector1,
                leftMotionVector1,
                new byte[tileWidth4],
                new byte[tileHeight4],
                aboveInterpolationFilterHorizontal,
                leftInterpolationFilterHorizontal,
                aboveInterpolationFilterVertical,
                leftInterpolationFilterVertical,
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new int[3][tileWidth4][8],
                new int[3][tileHeight4][8],
                aboveMode,
                leftMode,
                new byte[tileWidth8],
                new byte[tileHeight8],
                aboveTransformWidthLog2,
                leftTransformHeightLog2,
                aboveInterTransformWidthLog2,
                leftInterTransformHeightLog2,
                aboveLumaCoefficientContext,
                leftLumaCoefficientContext,
                aboveChromaCoefficientContext,
                leftChromaCoefficientContext
        );
    }

    /// Returns the horizontal chroma subsampling shift for one decoded chroma format.
    ///
    /// @param chromaFormat the decoded sequence chroma format
    /// @return the horizontal chroma subsampling shift for the supplied chroma format
    private static int chromaSubsamplingX(Av1ChromaFormat chromaFormat) {
        Av1ChromaFormat nonNullChromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        return switch (nonNullChromaFormat) {
            case MONOCHROME, YUV444 -> 0;
            case YUV420, YUV422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift for one decoded chroma format.
    ///
    /// @param chromaFormat the decoded sequence chroma format
    /// @return the vertical chroma subsampling shift for the supplied chroma format
    private static int chromaSubsamplingY(Av1ChromaFormat chromaFormat) {
        Av1ChromaFormat nonNullChromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        return switch (nonNullChromaFormat) {
            case MONOCHROME, YUV422, YUV444 -> 0;
            case YUV420 -> 1;
        };
    }

    /// Returns the rounded chroma-grid span corresponding to one luma-grid span and subsampling shift.
    ///
    /// @param lumaSpan4 the luma-grid span in 4x4 units
    /// @param subsamplingShift the chroma subsampling shift for the relevant axis
    /// @return the rounded chroma-grid span corresponding to the supplied luma-grid span
    private static int chromaTileSpan(int lumaSpan4, int subsamplingShift) {
        if (lumaSpan4 <= 0) {
            return 0;
        }
        return (lumaSpan4 + (1 << subsamplingShift) - 1) >> subsamplingShift;
    }

    /// Returns the tile width rounded up to 4x4 units.
    ///
    /// @return the tile width rounded up to 4x4 units
    public int tileWidth4() {
        return tileWidth4;
    }

    /// Returns the tile height rounded up to 4x4 units.
    ///
    /// @return the tile height rounded up to 4x4 units
    public int tileHeight4() {
        return tileHeight4;
    }

    /// Returns whether the supplied block position has a top neighbor inside the tile.
    ///
    /// @param position the current block position
    /// @return whether the supplied block position has a top neighbor inside the tile
    public boolean hasTopNeighbor(BlockPosition position) {
        return Objects.requireNonNull(position, "position").y4() > 0;
    }

    /// Returns whether the supplied block position has a left neighbor inside the tile.
    ///
    /// @param position the current block position
    /// @return whether the supplied block position has a left neighbor inside the tile
    public boolean hasLeftNeighbor(BlockPosition position) {
        return Objects.requireNonNull(position, "position").x4() > 0;
    }

    /// Returns the intra/inter context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the intra/inter context for the supplied block position
    public int intraContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        boolean haveTop = hasTopNeighbor(nonNullPosition);
        boolean haveLeft = hasLeftNeighbor(nonNullPosition);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        if (haveLeft) {
            if (haveTop) {
                int context = leftIntra[y4] + aboveIntra[x4];
                return context + (context == 2 ? 1 : 0);
            }
            return leftIntra[y4] * 2;
        }
        return haveTop ? aboveIntra[x4] * 2 : 0;
    }

    /// Returns the skip context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the skip context for the supplied block position
    public int skipContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int context = 0;
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSkip[nonNullPosition.x4()];
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSkip[nonNullPosition.y4()];
        }
        return context;
    }

    /// Returns the skip-mode context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the skip-mode context for the supplied block position
    public int skipModeContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int context = 0;
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSkipMode[nonNullPosition.x4()];
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSkipMode[nonNullPosition.y4()];
        }
        return context;
    }

    /// Returns the compound-reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the compound-reference context for the supplied block position
    public int compoundReferenceContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        boolean haveTop = hasTopNeighbor(nonNullPosition);
        boolean haveLeft = hasLeftNeighbor(nonNullPosition);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        if (haveTop) {
            if (haveLeft) {
                if (aboveCompoundReference[x4] != 0) {
                    if (leftCompoundReference[y4] != 0) {
                        return 4;
                    }
                    return 2 + ((leftReferenceFrame0[y4] & 0xFF) >= 4 ? 1 : 0);
                }
                if (leftCompoundReference[y4] != 0) {
                    return 2 + ((aboveReferenceFrame0[x4] & 0xFF) >= 4 ? 1 : 0);
                }
                return ((leftReferenceFrame0[y4] >= 4) ? 1 : 0)
                        ^ ((aboveReferenceFrame0[x4] >= 4) ? 1 : 0);
            }
            return aboveCompoundReference[x4] != 0 ? 3 : (aboveReferenceFrame0[x4] >= 4 ? 1 : 0);
        }
        if (haveLeft) {
            return leftCompoundReference[y4] != 0 ? 3 : (leftReferenceFrame0[y4] >= 4 ? 1 : 0);
        }
        return 1;
    }

    /// Returns the compound-direction context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the compound-direction context for the supplied block position
    public int compoundDirectionContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        boolean haveTop = hasTopNeighbor(nonNullPosition);
        boolean haveLeft = hasLeftNeighbor(nonNullPosition);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        if (haveTop && haveLeft) {
            int aboveIntra = this.aboveIntra[x4];
            int leftIntra = this.leftIntra[y4];
            if (aboveIntra != 0 && leftIntra != 0) {
                return 2;
            }
            if (aboveIntra != 0 || leftIntra != 0) {
                boolean useLeft = aboveIntra != 0;
                int off = useLeft ? y4 : x4;
                if ((useLeft ? leftCompoundReference[off] : aboveCompoundReference[off]) == 0) {
                    return 2;
                }
                return 1 + 2 * (hasUnidirectionalCompoundReference(useLeft, off) ? 1 : 0);
            }

            boolean aboveCompound = aboveCompoundReference[x4] != 0;
            boolean leftCompound = leftCompoundReference[y4] != 0;
            int aboveRef0 = aboveReferenceFrame0[x4];
            int leftRef0 = leftReferenceFrame0[y4];
            if (!aboveCompound && !leftCompound) {
                return 1 + (((aboveRef0 >= 4) == (leftRef0 >= 4)) ? 2 : 0);
            }
            if (!aboveCompound || !leftCompound) {
                boolean useAbove = aboveCompound;
                int off = useAbove ? x4 : y4;
                if (!hasUnidirectionalCompoundReference(!useAbove, off)) {
                    return 1;
                }
                return 3 + ((((aboveRef0 >= 4) == (leftRef0 >= 4)) ? 1 : 0));
            }
            boolean aboveUni = hasUnidirectionalCompoundReference(false, x4);
            boolean leftUni = hasUnidirectionalCompoundReference(true, y4);
            if (!aboveUni && !leftUni) {
                return 0;
            }
            if (!aboveUni || !leftUni) {
                return 2;
            }
            return 3 + (((aboveRef0 == 4) == (leftRef0 == 4)) ? 1 : 0);
        }
        if (haveTop || haveLeft) {
            boolean useLeft = haveLeft;
            int off = useLeft ? nonNullPosition.y4() : nonNullPosition.x4();
            if ((useLeft ? leftIntra[off] : aboveIntra[off]) != 0) {
                return 2;
            }
            if ((useLeft ? leftCompoundReference[off] : aboveCompoundReference[off]) == 0) {
                return 2;
            }
            return hasUnidirectionalCompoundReference(useLeft, off) ? 4 : 0;
        }
        return 2;
    }

    /// Returns the masked-compound context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the masked-compound context for the supplied block position
    public int maskedCompoundContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int context = 0;
        if (hasTopNeighbor(nonNullPosition)) {
            context += maskedCompoundNeighborContext(false, nonNullPosition.x4());
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += maskedCompoundNeighborContext(true, nonNullPosition.y4());
        }
        return Math.min(context, 5);
    }

    /// Returns the joint-compound context for the supplied block and reference pair.
    ///
    /// @param position the current block position
    /// @param currentFrameOffset the current frame order hint
    /// @param referenceFrameOffset0 the primary reference frame order hint
    /// @param referenceFrameOffset1 the secondary reference frame order hint
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @return the joint-compound context for the supplied block and reference pair
    public int jointCompoundContext(
            BlockPosition position,
            int currentFrameOffset,
            int referenceFrameOffset0,
            int referenceFrameOffset1,
            int orderHintBits
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int distance0 = Math.abs(orderHintDifference(orderHintBits, referenceFrameOffset0, currentFrameOffset));
        int distance1 = Math.abs(orderHintDifference(orderHintBits, currentFrameOffset, referenceFrameOffset1));
        int context = distance0 == distance1 ? 3 : 0;
        if (hasTopNeighbor(nonNullPosition)) {
            context += jointCompoundNeighborContext(false, nonNullPosition.x4());
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += jointCompoundNeighborContext(true, nonNullPosition.y4());
        }
        return context;
    }

    /// Returns the switchable interpolation-filter context for the supplied block position and direction.
    ///
    /// The context matches the primary current-block reference against neighboring edge state and
    /// uses the AV1 switchable-filter sentinel when no usable neighbor filter is available.
    ///
    /// @param position the current block position
    /// @param referenceFrame0 the primary current-block reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary current-block reference in internal LAST..ALTREF order, or `-1`
    /// Direction zero selects the vertical filter and direction one selects the horizontal filter,
    /// matching the AV1 syntax order.
    ///
    /// @param direction the zero-based interpolation-filter syntax direction in `[0, 2)`
    /// @return the switchable interpolation-filter context for the supplied block position and direction
    public int interpolationFilterContext(
            BlockPosition position,
            int referenceFrame0,
            int referenceFrame1,
            int direction
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int checkedDirection = requireInterpolationFilterDirection(direction);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        byte aboveFilter = hasTopNeighbor(nonNullPosition)
                ? matchingInterpolationFilterSymbol(
                referenceFrame0,
                aboveIntra[x4] != 0,
                aboveCompoundReference[x4] != 0,
                aboveReferenceFrame0[x4],
                aboveReferenceFrame1[x4],
                checkedDirection == 0 ? aboveInterpolationFilterVertical[x4] : aboveInterpolationFilterHorizontal[x4]
        )
                : INTERPOLATION_FILTER_UNSET;
        byte leftFilter = hasLeftNeighbor(nonNullPosition)
                ? matchingInterpolationFilterSymbol(
                referenceFrame0,
                leftIntra[y4] != 0,
                leftCompoundReference[y4] != 0,
                leftReferenceFrame0[y4],
                leftReferenceFrame1[y4],
                checkedDirection == 0 ? leftInterpolationFilterVertical[y4] : leftInterpolationFilterHorizontal[y4]
        )
                : INTERPOLATION_FILTER_UNSET;
        int contextBase = referenceFrame1 >= 0 ? 4 : 0;
        if (aboveFilter == leftFilter) {
            return contextBase + (aboveFilter & 0xFF);
        }
        if (aboveFilter == INTERPOLATION_FILTER_UNSET) {
            return contextBase + (leftFilter & 0xFF);
        }
        if (leftFilter == INTERPOLATION_FILTER_UNSET) {
            return contextBase + (aboveFilter & 0xFF);
        }
        return contextBase + INTERPOLATION_FILTER_UNSET;
    }

    /// Returns the single-reference primary context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the single-reference primary context for the supplied block position
    public int singleReferenceContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = resetReferenceCountScratch();
        accumulateForwardBackwardCounts(count, false, nonNullPosition);
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the forward-reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the forward-reference context for the supplied block position
    public int forwardReferenceContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = resetReferenceCountScratch();
        accumulateReferenceCounts(count, nonNullPosition, 0, 4);
        count[0] += count[1];
        count[2] += count[3];
        return count[0] == count[2] ? 1 : count[0] < count[2] ? 0 : 2;
    }

    /// Returns the LAST-vs-LAST2 reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the LAST-vs-LAST2 reference context for the supplied block position
    public int forwardReference1Context(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = resetReferenceCountScratch();
        accumulateReferenceCounts(count, nonNullPosition, 0, 2);
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the LAST3-vs-GOLDEN reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the LAST3-vs-GOLDEN reference context for the supplied block position
    public int forwardReference2Context(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = resetReferenceCountScratch();
        accumulateReferenceCounts(count, nonNullPosition, 2, 2);
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the backward-reference primary context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the backward-reference primary context for the supplied block position
    public int backwardReferenceContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = resetReferenceCountScratch();
        accumulateReferenceCounts(count, nonNullPosition, 4, 3);
        count[1] += count[0];
        return count[2] == count[1] ? 1 : count[1] < count[2] ? 0 : 2;
    }

    /// Returns the BWDREF-vs-ALTREF2 reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the BWDREF-vs-ALTREF2 reference context for the supplied block position
    public int backwardReference1Context(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = resetReferenceCountScratch();
        accumulateReferenceCounts(count, nonNullPosition, 4, 3);
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the LAST2/LAST3/GOLDEN unidirectional-reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the LAST2/LAST3/GOLDEN unidirectional-reference context for the supplied block position
    public int unidirectionalReference1Context(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = resetReferenceCountScratch();
        accumulateReferenceCounts(count, nonNullPosition, 1, 3);
        count[1] += count[2];
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the displacement-vector predictor for one intrabc block.
    ///
    /// The scan uses the same direct above/left, top-right, top-left, and secondary spatial order
    /// as the AV1 reference-motion-vector stack. The first non-zero candidate among the nearest
    /// two is returned; when no usable candidate exists, the caller-supplied fallback is returned.
    ///
    /// @param position the current tile-relative block position
    /// @param size the current block size
    /// @param fallback the frame-derived fallback displacement vector
    /// @return the selected displacement-vector predictor in eighth-pel units
    public MotionVector intrabcReferenceMotionVector(
            BlockPosition position,
            BlockSize size,
            MotionVector fallback
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        MotionVector nonNullFallback = Objects.requireNonNull(fallback, "fallback");
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        int blockWidth4 = nonNullSize.width4();
        int blockHeight4 = nonNullSize.height4();
        int visibleWidth4 = Math.min(Math.min(blockWidth4, 16), tileWidth4 - x4);
        int visibleHeight4 = Math.min(Math.min(blockHeight4, 16), tileHeight4 - y4);
        if (visibleWidth4 <= 0 || visibleHeight4 <= 0) {
            throw new IllegalArgumentException("Intrabc block lies outside the tile");
        }

        ExactSpatialGlobalMotion zeroGlobalMotion = new ExactSpatialGlobalMotion(
                MotionVector.zero(),
                MotionVector.zero(),
                false,
                false
        );
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] candidates = provisionalCandidateScratch;
        int candidateCount = 0;
        int maximumRows = 0;
        int scannedRows = -1;
        if (y4 > 0) {
            maximumRows = Math.min((y4 + 1) >> 1, 2 + (blockHeight4 > 1 ? 1 : 0));
            ExactSpatialScanResult topScan = scanExactRefMvsRow(
                    y4 - 1,
                    x4,
                    blockWidth4,
                    visibleWidth4,
                    maximumRows,
                    blockWidth4 >= 16 ? 4 : 1,
                    false,
                    INTRABC_REFERENCE_FRAME,
                    -1,
                    zeroGlobalMotion,
                    candidates,
                    candidateCount
            );
            candidateCount = topScan.candidateCount();
            scannedRows = topScan.scanDistance();
        }

        int maximumColumns = 0;
        int scannedColumns = -1;
        if (x4 > 0) {
            maximumColumns = Math.min((x4 + 1) >> 1, 2 + (blockWidth4 > 1 ? 1 : 0));
            ExactSpatialScanResult leftScan = scanExactRefMvsColumn(
                    x4 - 1,
                    y4,
                    blockHeight4,
                    visibleHeight4,
                    maximumColumns,
                    blockHeight4 >= 16 ? 4 : 1,
                    false,
                    INTRABC_REFERENCE_FRAME,
                    -1,
                    zeroGlobalMotion,
                    candidates,
                    candidateCount
            );
            candidateCount = leftScan.candidateCount();
            scannedColumns = leftScan.scanDistance();
        }

        if (scannedRows >= 0
                && Math.max(blockWidth4, blockHeight4) <= 16
                && x4 + blockWidth4 < tileWidth4) {
            long topRightResult = addExactSpatialCandidate(
                    storedBlockAtOrNull(x4 + blockWidth4, y4 - 1),
                    false,
                    INTRABC_REFERENCE_FRAME,
                    -1,
                    zeroGlobalMotion,
                    4,
                    candidates,
                    candidateCount
            );
            candidateCount = spatialCandidateCount(topRightResult);
        }

        int nearestCandidateCount = candidateCount;
        for (int index = 0; index < nearestCandidateCount; index++) {
            candidates[index].increaseWeight(640);
        }

        if (scannedRows >= 0 && scannedColumns >= 0) {
            long topLeftResult = addExactSpatialCandidate(
                    storedBlockAtOrNull(x4 - 1, y4 - 1),
                    false,
                    INTRABC_REFERENCE_FRAME,
                    -1,
                    zeroGlobalMotion,
                    4,
                    candidates,
                    candidateCount
            );
            candidateCount = spatialCandidateCount(topLeftResult);
        }

        for (int spatialDepth = 2; spatialDepth <= 3; spatialDepth++) {
            if (spatialDepth > scannedRows && spatialDepth <= maximumRows) {
                ExactSpatialScanResult secondaryTop = scanExactRefMvsRow(
                        secondarySpatialOffsetCoordinate(y4, spatialDepth),
                        x4 | 1,
                        blockWidth4,
                        visibleWidth4,
                        1 + maximumRows - spatialDepth,
                        blockWidth4 >= 16 ? 4 : 2,
                        false,
                        INTRABC_REFERENCE_FRAME,
                        -1,
                        zeroGlobalMotion,
                        candidates,
                        candidateCount
                );
                candidateCount = secondaryTop.candidateCount();
                scannedRows += secondaryTop.scanDistance();
            }
            if (spatialDepth > scannedColumns && spatialDepth <= maximumColumns) {
                ExactSpatialScanResult secondaryLeft = scanExactRefMvsColumn(
                        secondarySpatialOffsetCoordinate(x4, spatialDepth),
                        y4 | 1,
                        blockHeight4,
                        visibleHeight4,
                        1 + maximumColumns - spatialDepth,
                        blockHeight4 >= 16 ? 4 : 2,
                        false,
                        INTRABC_REFERENCE_FRAME,
                        -1,
                        zeroGlobalMotion,
                        candidates,
                        candidateCount
                );
                candidateCount = secondaryLeft.candidateCount();
                scannedColumns += secondaryLeft.scanDistance();
            }
        }

        sortDescending(candidates, 0, nearestCandidateCount);
        sortDescending(candidates, nearestCandidateCount, candidateCount);
        int frameX4 = (tileStartX8 << 1) + x4;
        int frameY4 = (tileStartY8 << 1) + y4;
        int minimumColumn = -(frameX4 + blockWidth4) * MOTION_VECTOR_UNITS_PER_4X4
                - REFERENCE_MOTION_VECTOR_BORDER;
        int maximumColumn = (frameWidth4 - frameX4) * MOTION_VECTOR_UNITS_PER_4X4
                + REFERENCE_MOTION_VECTOR_BORDER;
        int minimumRow = -(frameY4 + blockHeight4) * MOTION_VECTOR_UNITS_PER_4X4
                - REFERENCE_MOTION_VECTOR_BORDER;
        int maximumRow = (frameHeight4 - frameY4) * MOTION_VECTOR_UNITS_PER_4X4
                + REFERENCE_MOTION_VECTOR_BORDER;
        for (int index = 0; index < Math.min(2, candidateCount); index++) {
            MotionVector candidate = clampReferenceMotionVector(
                    candidates[index].motionVector0(),
                    minimumRow,
                    maximumRow,
                    minimumColumn,
                    maximumColumn
            ).vector();
            if (candidate.rowEighthPel() != 0 || candidate.columnEighthPel() != 0) {
                return candidate;
            }
        }
        return nonNullFallback;
    }

    /// Builds an inter-mode syntax context from spatial and projected temporal neighbors.
    ///
    /// Non-translation global-motion types affect exact spatial candidates: an eligible
    /// neighboring `GLOBALMV` block contributes the current block's global vector, because an
    /// affine or rotation/zoom vector varies with block position. Translation-only callers may
    /// use the shorter overload.
    ///
    /// @param position the current block position
    /// @param size the current block size
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary current-block reference in internal LAST..ALTREF order, or `-1`
    /// @param globalMotionVector0 the current block's primary global-motion vector
    /// @param globalMotionVector1 the current block's secondary global-motion vector, or zero for single-reference blocks
    /// @param globalMotionType0 the primary reference's global-motion type
    /// @param globalMotionType1 the secondary reference's global-motion type, or identity for single-reference blocks
    /// @return an inter-mode syntax context derived from available neighbors
    public ProvisionalInterModeContext provisionalInterModeContext(
            BlockPosition position,
            BlockSize size,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            MotionVector globalMotionVector0,
            MotionVector globalMotionVector1,
            FrameHeader.GlobalMotionType globalMotionType0,
            FrameHeader.GlobalMotionType globalMotionType1
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        MotionVector nonNullGlobalMotionVector0 = Objects.requireNonNull(globalMotionVector0, "globalMotionVector0");
        MotionVector nonNullGlobalMotionVector1 = Objects.requireNonNull(globalMotionVector1, "globalMotionVector1");
        FrameHeader.GlobalMotionType nonNullGlobalMotionType0 =
                Objects.requireNonNull(globalMotionType0, "globalMotionType0");
        FrameHeader.GlobalMotionType nonNullGlobalMotionType1 =
                Objects.requireNonNull(globalMotionType1, "globalMotionType1");
        if (referenceFrame0 < 0) {
            throw new IllegalArgumentException("referenceFrame0 < 0");
        }
        if (!compoundReference && referenceFrame1 >= 0) {
            throw new IllegalArgumentException("Single-reference blocks must not carry referenceFrame1");
        }
        if (compoundReference && referenceFrame1 < 0) {
            throw new IllegalArgumentException("Compound-reference blocks must carry referenceFrame1");
        }

        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        int blockWidth4 = nonNullSize.width4();
        int blockHeight4 = nonNullSize.height4();
        int visibleWidth4 = Math.min(Math.min(blockWidth4, 16), tileWidth4 - x4);
        int visibleHeight4 = Math.min(Math.min(blockHeight4, 16), tileHeight4 - y4);
        boolean directRowMatch = false;
        boolean directColumnMatch = false;
        boolean rowReferenceMatch = false;
        boolean columnReferenceMatch = false;
        boolean haveNewMotionVectorMatch = false;
        int globalMotionContext = referenceMotionVectorProjection.enabled() ? 1 : 0;
        ExactSpatialGlobalMotion exactSpatialGlobalMotion = new ExactSpatialGlobalMotion(
                nonNullGlobalMotionVector0,
                nonNullGlobalMotionVector1,
                nonNullGlobalMotionType0.ordinal() > FrameHeader.GlobalMotionType.TRANSLATION.ordinal(),
                nonNullGlobalMotionType1.ordinal() > FrameHeader.GlobalMotionType.TRANSLATION.ordinal()
        );
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] candidates = provisionalCandidateScratch;
        int candidateCount = 0;

        int maximumRows = 0;
        int scannedRows = -1;
        if (y4 > 0) {
            maximumRows = Math.min((y4 + 1) >> 1, 2 + (blockHeight4 > 1 ? 1 : 0));
            ExactSpatialScanResult topScan = scanExactRefMvsRow(
                    y4 - 1,
                    x4,
                    blockWidth4,
                    visibleWidth4,
                    maximumRows,
                    blockWidth4 >= 16 ? 4 : 1,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    exactSpatialGlobalMotion,
                    candidates,
                    candidateCount
            );
            candidateCount = topScan.candidateCount();
            scannedRows = topScan.scanDistance();
            directRowMatch = topScan.referenceMatch();
            rowReferenceMatch = topScan.referenceMatch();
            haveNewMotionVectorMatch |= topScan.haveNewMotionVectorMatch();
        }

        int maximumColumns = 0;
        int scannedColumns = -1;
        if (x4 > 0) {
            maximumColumns = Math.min((x4 + 1) >> 1, 2 + (blockWidth4 > 1 ? 1 : 0));
            ExactSpatialScanResult leftScan = scanExactRefMvsColumn(
                    x4 - 1,
                    y4,
                    blockHeight4,
                    visibleHeight4,
                    maximumColumns,
                    blockHeight4 >= 16 ? 4 : 1,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    exactSpatialGlobalMotion,
                    candidates,
                    candidateCount
            );
            candidateCount = leftScan.candidateCount();
            scannedColumns = leftScan.scanDistance();
            directColumnMatch = leftScan.referenceMatch();
            columnReferenceMatch = leftScan.referenceMatch();
            haveNewMotionVectorMatch |= leftScan.haveNewMotionVectorMatch();
        }

        if (scannedRows >= 0
                && Math.max(blockWidth4, blockHeight4) <= 16
                && x4 + blockWidth4 < tileWidth4) {
            long topRight = addExactSpatialCandidate(
                    storedBlockAtOrNull(x4 + blockWidth4, y4 - 1),
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    exactSpatialGlobalMotion,
                    4,
                    candidates,
                    candidateCount
            );
            candidateCount = spatialCandidateCount(topRight);
            directRowMatch |= spatialReferenceMatch(topRight);
            rowReferenceMatch |= spatialReferenceMatch(topRight);
            haveNewMotionVectorMatch |= spatialNewMotionVectorMatch(topRight);
        }

        int nearestCandidateCount = candidateCount;
        for (int i = 0; i < nearestCandidateCount; i++) {
            candidates[i].increaseWeight(640);
        }

        TemporalScanResult temporalScan = scanTemporalMotionField(
                nonNullPosition,
                nonNullSize,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                nonNullGlobalMotionVector0,
                candidates,
                candidateCount,
                globalMotionContext
        );
        candidateCount = temporalScan.candidateCount();
        globalMotionContext = temporalScan.globalMotionContext();

        if (scannedRows >= 0 && scannedColumns >= 0) {
            long topLeft = addExactSpatialCandidate(
                    storedBlockAtOrNull(x4 - 1, y4 - 1),
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    exactSpatialGlobalMotion,
                    4,
                    candidates,
                    candidateCount
            );
            candidateCount = spatialCandidateCount(topLeft);
            rowReferenceMatch |= spatialReferenceMatch(topLeft);
        }

        for (int spatialDepth = 2; spatialDepth <= 3; spatialDepth++) {
            if (spatialDepth > scannedRows && spatialDepth <= maximumRows) {
                ExactSpatialScanResult secondaryTop = scanExactRefMvsRow(
                        secondarySpatialOffsetCoordinate(y4, spatialDepth),
                        x4 | 1,
                        blockWidth4,
                        visibleWidth4,
                        1 + maximumRows - spatialDepth,
                        blockWidth4 >= 16 ? 4 : 2,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        exactSpatialGlobalMotion,
                        candidates,
                        candidateCount
                );
                candidateCount = secondaryTop.candidateCount();
                scannedRows += secondaryTop.scanDistance();
                rowReferenceMatch |= secondaryTop.referenceMatch();
            }
            if (spatialDepth > scannedColumns && spatialDepth <= maximumColumns) {
                ExactSpatialScanResult secondaryLeft = scanExactRefMvsColumn(
                        secondarySpatialOffsetCoordinate(x4, spatialDepth),
                        y4 | 1,
                        blockHeight4,
                        visibleHeight4,
                        1 + maximumColumns - spatialDepth,
                        blockHeight4 >= 16 ? 4 : 2,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        exactSpatialGlobalMotion,
                        candidates,
                        candidateCount
                );
                candidateCount = secondaryLeft.candidateCount();
                scannedColumns += secondaryLeft.scanDistance();
                columnReferenceMatch |= secondaryLeft.referenceMatch();
            }
        }

        sortDescending(candidates, 0, nearestCandidateCount);
        sortDescending(candidates, nearestCandidateCount, candidateCount);
        if (compoundReference && candidateCount < 2) {
            candidateCount = appendCompoundExtendedSpatialCandidates(
                    x4,
                    y4,
                    Math.min(visibleWidth4, visibleHeight4),
                    scannedRows >= 0,
                    scannedColumns >= 0,
                    referenceFrame0,
                    referenceFrame1,
                    nonNullGlobalMotionVector0,
                    nonNullGlobalMotionVector1,
                    candidates,
                    candidateCount
            );
        } else if (candidateCount < 2) {
            candidateCount = appendSingleExtendedSpatialCandidates(
                    x4,
                    y4,
                    Math.min(visibleWidth4, visibleHeight4),
                    scannedRows >= 0,
                    scannedColumns >= 0,
                    referenceFrame0,
                    candidates,
                    candidateCount
            );
        }
        int syntaxCandidateCount = candidateCount;
        int frameX4 = (tileStartX8 << 1) + x4;
        int frameY4 = (tileStartY8 << 1) + y4;
        int minimumColumn = -(frameX4 + blockWidth4) * MOTION_VECTOR_UNITS_PER_4X4
                - REFERENCE_MOTION_VECTOR_BORDER;
        int maximumColumn = (frameWidth4 - frameX4) * MOTION_VECTOR_UNITS_PER_4X4
                + REFERENCE_MOTION_VECTOR_BORDER;
        int minimumRow = -(frameY4 + blockHeight4) * MOTION_VECTOR_UNITS_PER_4X4
                - REFERENCE_MOTION_VECTOR_BORDER;
        int maximumRow = (frameHeight4 - frameY4) * MOTION_VECTOR_UNITS_PER_4X4
                + REFERENCE_MOTION_VECTOR_BORDER;
        for (int index = 0; index < candidateCount; index++) {
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate = candidates[index];
            InterMotionVector secondaryMotionVector = candidate.motionVector1();
            InterMotionVector clampedPrimary = clampReferenceMotionVector(
                    candidate.motionVector0(),
                    minimumRow,
                    maximumRow,
                    minimumColumn,
                    maximumColumn
            );
            InterMotionVector clampedSecondary = secondaryMotionVector == null
                    ? null
                    : clampReferenceMotionVector(
                            secondaryMotionVector,
                            minimumRow,
                            maximumRow,
                            minimumColumn,
                            maximumColumn
                    );
            if (clampedPrimary != candidate.motionVector0() || clampedSecondary != secondaryMotionVector) {
                candidates[index] = new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                        candidate.weight(),
                        clampedPrimary,
                        clampedSecondary,
                        candidate.synthetic()
                );
            }
        }
        while (candidateCount < 2) {
            candidates[candidateCount++] = new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                    2,
                    InterMotionVector.predicted(nonNullGlobalMotionVector0),
                    compoundReference ? InterMotionVector.predicted(nonNullGlobalMotionVector1) : null,
                    true
            );
        }
        if (compoundReference) {
            syntaxCandidateCount = candidateCount;
        }
        RefMvsContextSummary refMvsContextSummary = summarizeDirectRefMvsContexts(
                (directRowMatch ? 1 : 0) + (directColumnMatch ? 1 : 0),
                (rowReferenceMatch ? 1 : 0) + (columnReferenceMatch ? 1 : 0),
                haveNewMotionVectorMatch
        );

        return ProvisionalInterModeContext.fromOwnedCandidates(
                refMvsContextSummary.singleNewMvContext(),
                globalMotionContext,
                refMvsContextSummary.singleReferenceMvContext(),
                refMvsContextSummary.compoundInterModeContext(),
                syntaxCandidateCount,
                Arrays.copyOf(candidates, candidateCount)
        );
    }

    /// Clamps one candidate component to AV1's extended frame boundary.
    ///
    /// @param motionVector the candidate component and its resolved state
    /// @param minimumRow the inclusive minimum row component in eighth-pel units
    /// @param maximumRow the inclusive maximum row component in eighth-pel units
    /// @param minimumColumn the inclusive minimum column component in eighth-pel units
    /// @param maximumColumn the inclusive maximum column component in eighth-pel units
    /// @return the original component when already in range, or a state-preserving clamped component
    private static InterMotionVector clampReferenceMotionVector(
            InterMotionVector motionVector,
            int minimumRow,
            int maximumRow,
            int minimumColumn,
            int maximumColumn
    ) {
        InterMotionVector nonNullMotionVector = Objects.requireNonNull(motionVector, "motionVector");
        MotionVector vector = nonNullMotionVector.vector();
        int row = Math.max(minimumRow, Math.min(maximumRow, vector.rowEighthPel()));
        int column = Math.max(minimumColumn, Math.min(maximumColumn, vector.columnEighthPel()));
        if (row == vector.rowEighthPel() && column == vector.columnEighthPel()) {
            return nonNullMotionVector;
        }
        return new InterMotionVector(new MotionVector(row, column), nonNullMotionVector.resolved());
    }

    /// Completes a short compound-reference candidate stack from direct-edge neighbors.
    ///
    /// Each requested reference first reuses matching neighbor components, then sign-normalized
    /// components associated with other references, and finally its block-center global motion.
    /// The two independently completed component lists are paired by index, matching AV1's
    /// compound extended-candidate construction.
    ///
    /// @param x4 the current block origin X coordinate in tile-relative 4x4 units
    /// @param y4 the current block origin Y coordinate in tile-relative 4x4 units
    /// @param span4 the bounded direct-edge span in 4x4 units
    /// @param hasTop whether a direct top edge is available
    /// @param hasLeft whether a direct left edge is available
    /// @param referenceFrame0 the requested primary reference
    /// @param referenceFrame1 the requested secondary reference
    /// @param globalMotionVector0 the primary global-motion fallback
    /// @param globalMotionVector1 the secondary global-motion fallback
    /// @param destination the destination candidate stack
    /// @param count the number of active candidates already stored
    /// @return the completed candidate count, which is always two
    private int appendCompoundExtendedSpatialCandidates(
            int x4,
            int y4,
            int span4,
            boolean hasTop,
            boolean hasLeft,
            int referenceFrame0,
            int referenceFrame1,
            MotionVector globalMotionVector0,
            MotionVector globalMotionVector1,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        List<MotionVector> matching0 = new ArrayList<>(2);
        List<MotionVector> matching1 = new ArrayList<>(2);
        List<MotionVector> alternate0 = new ArrayList<>(2);
        List<MotionVector> alternate1 = new ArrayList<>(2);

        if (hasTop) {
            for (int offset4 = 0; offset4 < span4; ) {
                StoredBlock block = storedBlockAtOrNull(x4 + offset4, y4 - 1);
                collectCompoundExtendedComponents(
                        block,
                        referenceFrame0,
                        referenceFrame1,
                        matching0,
                        matching1,
                        alternate0,
                        alternate1
                );
                offset4 += block != null ? Math.max(1, block.width4()) : 1;
            }
        }
        if (hasLeft) {
            for (int offset4 = 0; offset4 < span4; ) {
                StoredBlock block = storedBlockAtOrNull(x4 - 1, y4 + offset4);
                collectCompoundExtendedComponents(
                        block,
                        referenceFrame0,
                        referenceFrame1,
                        matching0,
                        matching1,
                        alternate0,
                        alternate1
                );
                offset4 += block != null ? Math.max(1, block.height4()) : 1;
            }
        }

        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate first = compoundExtendedCandidate(
                matching0,
                matching1,
                alternate0,
                alternate1,
                globalMotionVector0,
                globalMotionVector1,
                0
        );
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate second = compoundExtendedCandidate(
                matching0,
                matching1,
                alternate0,
                alternate1,
                globalMotionVector0,
                globalMotionVector1,
                1
        );
        if (count == 0) {
            destination[0] = first;
            destination[1] = second;
        } else if (equivalentMotionVectorCandidate(destination[0], first)) {
            destination[1] = second;
        } else {
            destination[1] = first;
        }
        return 2;
    }

    /// Collects matching and sign-normalized alternate components from one direct-edge block.
    ///
    /// @param block the stored direct-edge block, or `null`
    /// @param referenceFrame0 the requested primary reference
    /// @param referenceFrame1 the requested secondary reference
    /// @param matching0 primary components already using `referenceFrame0`
    /// @param matching1 secondary components already using `referenceFrame1`
    /// @param alternate0 components reusable for the primary reference after sign normalization
    /// @param alternate1 components reusable for the secondary reference after sign normalization
    private void collectCompoundExtendedComponents(
            StoredBlock block,
            int referenceFrame0,
            int referenceFrame1,
            List<MotionVector> matching0,
            List<MotionVector> matching1,
            List<MotionVector> alternate0,
            List<MotionVector> alternate1
    ) {
        if (block == null || block.intra()) {
            return;
        }
        collectCompoundExtendedComponent(
                block.referenceFrame0(),
                block.motionVector0().vector(),
                referenceFrame0,
                referenceFrame1,
                matching0,
                matching1,
                alternate0,
                alternate1
        );
        if (block.compoundReference()) {
            collectCompoundExtendedComponent(
                    block.referenceFrame1(),
                    block.motionVector1().vector(),
                    referenceFrame0,
                    referenceFrame1,
                    matching0,
                    matching1,
                    alternate0,
                    alternate1
            );
        }
    }

    /// Classifies one neighboring motion component for both requested compound references.
    ///
    /// @param neighborReferenceFrame the neighbor component reference
    /// @param neighborMotionVector the neighbor component vector
    /// @param referenceFrame0 the requested primary reference
    /// @param referenceFrame1 the requested secondary reference
    /// @param matching0 primary components already using `referenceFrame0`
    /// @param matching1 secondary components already using `referenceFrame1`
    /// @param alternate0 components reusable for the primary reference after sign normalization
    /// @param alternate1 components reusable for the secondary reference after sign normalization
    private void collectCompoundExtendedComponent(
            int neighborReferenceFrame,
            MotionVector neighborMotionVector,
            int referenceFrame0,
            int referenceFrame1,
            List<MotionVector> matching0,
            List<MotionVector> matching1,
            List<MotionVector> alternate0,
            List<MotionVector> alternate1
    ) {
        if (neighborReferenceFrame < 0) {
            return;
        }
        MotionVector nonNullMotionVector = Objects.requireNonNull(neighborMotionVector, "neighborMotionVector");
        if (neighborReferenceFrame == referenceFrame0) {
            appendExtendedComponent(matching0, nonNullMotionVector);
            appendExtendedComponent(
                    alternate1,
                    normalizeExtendedMotionVector(nonNullMotionVector, neighborReferenceFrame, referenceFrame1)
            );
        } else if (neighborReferenceFrame == referenceFrame1) {
            appendExtendedComponent(matching1, nonNullMotionVector);
            appendExtendedComponent(
                    alternate0,
                    normalizeExtendedMotionVector(nonNullMotionVector, neighborReferenceFrame, referenceFrame0)
            );
        } else {
            appendExtendedComponent(
                    alternate0,
                    normalizeExtendedMotionVector(nonNullMotionVector, neighborReferenceFrame, referenceFrame0)
            );
            appendExtendedComponent(
                    alternate1,
                    normalizeExtendedMotionVector(nonNullMotionVector, neighborReferenceFrame, referenceFrame1)
            );
        }
    }

    /// Appends one component while retaining at most the first two values.
    ///
    /// @param destination the ordered component list
    /// @param motionVector the component to append
    private static void appendExtendedComponent(List<MotionVector> destination, MotionVector motionVector) {
        if (destination.size() < 2) {
            destination.add(Objects.requireNonNull(motionVector, "motionVector"));
        }
    }

    /// Returns one component from matching, alternate, and global fallback sources in priority order.
    ///
    /// @param matching matching-reference components
    /// @param alternate sign-normalized alternate-reference components
    /// @param globalMotionVector the global-motion fallback
    /// @param index the zero-based completed component index in `[0, 2)`
    /// @return the selected component
    private static MotionVector compoundExtendedComponent(
            List<MotionVector> matching,
            List<MotionVector> alternate,
            MotionVector globalMotionVector,
            int index
    ) {
        int sourceIndex = index;
        if (sourceIndex < matching.size()) {
            return matching.get(sourceIndex);
        }
        sourceIndex -= matching.size();
        if (sourceIndex < alternate.size()) {
            return alternate.get(sourceIndex);
        }
        return Objects.requireNonNull(globalMotionVector, "globalMotionVector");
    }

    /// Builds one paired compound extended candidate at the supplied completed-list index.
    ///
    /// @param matching0 matching components for the primary reference
    /// @param matching1 matching components for the secondary reference
    /// @param alternate0 alternate components for the primary reference
    /// @param alternate1 alternate components for the secondary reference
    /// @param globalMotionVector0 the primary global-motion fallback
    /// @param globalMotionVector1 the secondary global-motion fallback
    /// @param index the zero-based completed candidate index in `[0, 2)`
    /// @return the paired candidate
    private static ProvisionalInterModeContext.ProvisionalMotionVectorCandidate compoundExtendedCandidate(
            List<MotionVector> matching0,
            List<MotionVector> matching1,
            List<MotionVector> alternate0,
            List<MotionVector> alternate1,
            MotionVector globalMotionVector0,
            MotionVector globalMotionVector1,
            int index
    ) {
        return new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                2,
                InterMotionVector.predicted(compoundExtendedComponent(matching0, alternate0, globalMotionVector0, index)),
                InterMotionVector.predicted(compoundExtendedComponent(matching1, alternate1, globalMotionVector1, index)),
                false
        );
    }

    /// Returns a neighbor component normalized to the temporal direction of one requested reference.
    ///
    /// @param motionVector the neighbor component
    /// @param neighborReferenceFrame the neighbor component reference
    /// @param requestedReferenceFrame the requested reference
    /// @return the direction-normalized component
    private MotionVector normalizeExtendedMotionVector(
            MotionVector motionVector,
            int neighborReferenceFrame,
            int requestedReferenceFrame
    ) {
        MotionVector nonNullMotionVector = Objects.requireNonNull(motionVector, "motionVector");
        if (referenceMotionVectorProjection.signBias(neighborReferenceFrame)
                == referenceMotionVectorProjection.signBias(requestedReferenceFrame)) {
            return nonNullMotionVector;
        }
        return new MotionVector(
                -nonNullMotionVector.rowEighthPel(),
                -nonNullMotionVector.columnEighthPel()
        );
    }

    /// Returns whether two provisional candidates carry equal primary and secondary vectors.
    ///
    /// @param left the first candidate
    /// @param right the second candidate
    /// @return whether both vector pairs are equal
    private static boolean equivalentMotionVectorCandidate(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate left,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate right
    ) {
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullLeft = Objects.requireNonNull(left, "left");
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullRight = Objects.requireNonNull(right, "right");
        return nonNullLeft.motionVector0().vector().equals(nonNullRight.motionVector0().vector())
                && equivalentMotionVector(nonNullLeft.motionVector1(), nonNullRight.motionVector1());
    }

    /// Appends single-reference extended candidates from the direct top and left edges.
    ///
    /// This fallback scan reuses motion vectors carried by neighbors with any inter reference.
    /// Vectors whose reference lies on the opposite temporal side of the current frame are
    /// negated. Unlike the final global-motion fallback, appended entries are real DRL-visible
    /// candidates.
    ///
    /// @param x4 the current block origin X coordinate in tile-relative 4x4 units
    /// @param y4 the current block origin Y coordinate in tile-relative 4x4 units
    /// @param span4 the bounded direct-edge span in 4x4 units
    /// @param hasTop whether a direct top edge is available
    /// @param hasLeft whether a direct left edge is available
    /// @param referenceFrame the current block reference in internal LAST..ALTREF order
    /// @param destination the destination candidate stack
    /// @param count the number of active candidates already stored
    /// @return the updated real candidate count
    private int appendSingleExtendedSpatialCandidates(
            int x4,
            int y4,
            int span4,
            boolean hasTop,
            boolean hasLeft,
            int referenceFrame,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        if (hasTop) {
            for (int offset4 = 0; offset4 < span4 && count < 2; ) {
                StoredBlock block = storedBlockAtOrNull(x4 + offset4, y4 - 1);
                count = appendSingleExtendedCandidate(block, referenceFrame, destination, count);
                offset4 += block != null ? Math.max(1, block.width4()) : 1;
            }
        }
        if (hasLeft) {
            for (int offset4 = 0; offset4 < span4 && count < 2; ) {
                StoredBlock block = storedBlockAtOrNull(x4 - 1, y4 + offset4);
                count = appendSingleExtendedCandidate(block, referenceFrame, destination, count);
                offset4 += block != null ? Math.max(1, block.height4()) : 1;
            }
        }
        return count;
    }

    /// Appends the unique motion components carried by one extended spatial neighbor.
    ///
    /// @param block the stored inter neighbor, or `null`
    /// @param referenceFrame the current block reference in internal LAST..ALTREF order
    /// @param destination the destination candidate stack
    /// @param count the number of active candidates already stored
    /// @return the updated real candidate count
    private int appendSingleExtendedCandidate(
            StoredBlock block,
            int referenceFrame,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        if (block == null || block.intra()) {
            return count;
        }
        count = appendSingleExtendedComponent(
                block.referenceFrame0(),
                block.motionVector0(),
                referenceFrame,
                destination,
                count
        );
        if (block.compoundReference()) {
            count = appendSingleExtendedComponent(
                    block.referenceFrame1(),
                    block.motionVector1(),
                    referenceFrame,
                    destination,
                    count
            );
        }
        return count;
    }

    /// Appends one sign-normalized extended motion-vector component when it is unique.
    ///
    /// @param neighborReferenceFrame the neighbor component reference in internal LAST..ALTREF order
    /// @param neighborMotionVector the neighbor component motion vector
    /// @param referenceFrame the current block reference in internal LAST..ALTREF order
    /// @param destination the destination candidate stack
    /// @param count the number of active candidates already stored
    /// @return the updated real candidate count
    private int appendSingleExtendedComponent(
            int neighborReferenceFrame,
            InterMotionVector neighborMotionVector,
            int referenceFrame,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        if (neighborReferenceFrame < 0 || count >= destination.length) {
            return count;
        }
        MotionVector vector = Objects.requireNonNull(neighborMotionVector, "neighborMotionVector").vector();
        if (referenceMotionVectorProjection.signBias(referenceFrame)
                != referenceMotionVectorProjection.signBias(neighborReferenceFrame)) {
            vector = new MotionVector(-vector.rowEighthPel(), -vector.columnEighthPel());
        }
        for (int index = 0; index < count; index++) {
            if (destination[index].motionVector0().vector().equals(vector)) {
                return count;
            }
        }
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate =
                new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                        2,
                        InterMotionVector.predicted(vector),
                        null,
                        false
                );
        destination[count] = candidate;
        return count + 1;
    }

    /// Returns the odd-aligned coordinate used for one secondary spatial offset layer.
    ///
    /// @param coordinate4 the current block start coordinate in 4x4 units
    /// @param secondaryOffset the one-based secondary offset layer index starting at `2`
    /// @return the odd-aligned secondary scan coordinate in 4x4 units
    private static int secondarySpatialOffsetCoordinate(int coordinate4, int secondaryOffset) {
        return (coordinate4 - (secondaryOffset << 1) + 1) | 1;
    }

    /// Returns the temporal segmentation-prediction context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the temporal segmentation-prediction context for the supplied block position
    public int segmentPredictionContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int context = 0;
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSegmentPredicted[nonNullPosition.x4()];
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSegmentPredicted[nonNullPosition.y4()];
        }
        return context;
    }

    /// Returns the current-frame predicted segment identifier and context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the current-frame predicted segment identifier and context
    public SegmentPrediction currentSegmentPrediction(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        boolean haveTop = hasTopNeighbor(nonNullPosition);
        boolean haveLeft = hasLeftNeighbor(nonNullPosition);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        int frameX4 = (tileStartX8 << 1) + x4;
        int frameY4 = (tileStartY8 << 1) + y4;
        if (haveLeft && haveTop) {
            int left = currentSegmentIdMap.getOrZero(frameX4 - 1, frameY4);
            int above = currentSegmentIdMap.getOrZero(frameX4, frameY4 - 1);
            int aboveLeft = currentSegmentIdMap.getOrZero(frameX4 - 1, frameY4 - 1);
            int context;
            if (left == above && aboveLeft == left) {
                context = 2;
            } else if (left == above || aboveLeft == left || above == aboveLeft) {
                context = 1;
            } else {
                context = 0;
            }
            return new SegmentPrediction(above == aboveLeft ? above : left, context);
        }

        int predictedSegmentId = haveLeft
                ? currentSegmentIdMap.getOrZero(frameX4 - 1, frameY4)
                : haveTop
                ? currentSegmentIdMap.getOrZero(frameX4, frameY4 - 1)
                : 0;
        return new SegmentPrediction(predictedSegmentId, 0);
    }

    /// Returns the above-edge luma palette size for the supplied X coordinate in 4x4 units.
    ///
    /// @param x4 the X coordinate in 4x4 units
    /// @return the above-edge luma palette size for the supplied X coordinate in 4x4 units
    public int abovePaletteSize(int x4) {
        return abovePaletteSize[x4] & 0xFF;
    }

    /// Returns the left-edge luma palette size for the supplied Y coordinate in 4x4 units.
    ///
    /// @param y4 the Y coordinate in 4x4 units
    /// @return the left-edge luma palette size for the supplied Y coordinate in 4x4 units
    public int leftPaletteSize(int y4) {
        return leftPaletteSize[y4] & 0xFF;
    }

    /// Returns the above-edge chroma palette size for the supplied X coordinate in 4x4 units.
    ///
    /// @param x4 the X coordinate in 4x4 units
    /// @return the above-edge chroma palette size for the supplied X coordinate in 4x4 units
    public int aboveChromaPaletteSize(int x4) {
        return aboveChromaPaletteSize[x4] & 0xFF;
    }

    /// Returns the left-edge chroma palette size for the supplied Y coordinate in 4x4 units.
    ///
    /// @param y4 the Y coordinate in 4x4 units
    /// @return the left-edge chroma palette size for the supplied Y coordinate in 4x4 units
    public int leftChromaPaletteSize(int y4) {
        return leftChromaPaletteSize[y4] & 0xFF;
    }

    /// Returns one above-edge palette entry for the supplied plane, X coordinate, and palette index.
    ///
    /// @param plane the plane index, where `0` is Y, `1` is U, and `2` is V
    /// @param x4 the X coordinate in 4x4 units
    /// @param index the zero-based palette entry index below the active palette size for `plane`
    /// @return one above-edge palette entry for the supplied plane and coordinate
    public int abovePaletteEntry(int plane, int x4, int index) {
        int checkedPlane = Objects.checkIndex(plane, abovePaletteEntries.length);
        int activeSize = checkedPlane == 0 ? abovePaletteSize(x4) : aboveChromaPaletteSize(x4);
        return abovePaletteEntries[checkedPlane][x4][Objects.checkIndex(index, activeSize)];
    }

    /// Returns one left-edge palette entry for the supplied plane, Y coordinate, and palette index.
    ///
    /// @param plane the plane index, where `0` is Y, `1` is U, and `2` is V
    /// @param y4 the Y coordinate in 4x4 units
    /// @param index the zero-based palette entry index below the active palette size for `plane`
    /// @return one left-edge palette entry for the supplied plane and coordinate
    public int leftPaletteEntry(int plane, int y4, int index) {
        int checkedPlane = Objects.checkIndex(plane, leftPaletteEntries.length);
        int activeSize = checkedPlane == 0 ? leftPaletteSize(y4) : leftChromaPaletteSize(y4);
        return leftPaletteEntries[checkedPlane][y4][Objects.checkIndex(index, activeSize)];
    }

    /// Returns the above-edge luma mode for the supplied X coordinate in 4x4 units.
    ///
    /// @param x4 the X coordinate in 4x4 units
    /// @return the above-edge luma mode for the supplied X coordinate in 4x4 units
    public LumaIntraPredictionMode aboveMode(int x4) {
        return aboveMode[x4];
    }

    /// Returns the left-edge luma mode for the supplied Y coordinate in 4x4 units.
    ///
    /// @param y4 the Y coordinate in 4x4 units
    /// @return the left-edge luma mode for the supplied Y coordinate in 4x4 units
    public LumaIntraPredictionMode leftMode(int y4) {
        return leftMode[y4];
    }

    /// Returns the partition context for the supplied block-level shift and block position.
    ///
    /// @param partitionShift the partition-bit shift derived from the current square block level
    /// @param position the current block position
    /// @return the partition context for the supplied block-level shift and block position
    public int partitionContext(int partitionShift, BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int x8 = nonNullPosition.x8();
        int y8 = nonNullPosition.y8();
        return ((abovePartition[x8] >> partitionShift) & 1) + (((leftPartition[y8] >> partitionShift) & 1) << 1);
    }

    /// Returns the transform-size context for one intra-like block and maximum luma transform size.
    ///
    /// This matches the `dav1d` `get_tx_ctx()` rule that compares the stored top and left
    /// transform-context dimensions against the currently allowed maximum transform size.
    ///
    /// @param position the current block position
    /// @param maxTransformSize the largest luma transform size allowed for the current block
    /// @return the transform-size context for one intra-like block
    public int transformSizeContext(BlockPosition position, TransformSize maxTransformSize) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullMaxTransformSize = Objects.requireNonNull(maxTransformSize, "maxTransformSize");
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        return (leftTransformHeightLog2[y4] >= nonNullMaxTransformSize.log2Height4() ? 1 : 0)
                + (aboveTransformWidthLog2[x4] >= nonNullMaxTransformSize.log2Width4() ? 1 : 0);
    }

    /// Returns the inter var-tx split context for one transform region and maximum transform size.
    ///
    /// This matches `dav1d`'s `read_tx_tree()` rule that compares the current top and left inter
    /// transform-context dimensions against the transform width and height being split.
    ///
    /// @param position the local tile-relative origin of the current transform region
    /// @param transformSize the transform size currently being considered for splitting
    /// @return the inter var-tx split context in `[0, 3)`
    public int interTransformSplitContext(BlockPosition position, TransformSize transformSize) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        return (aboveInterTransformWidthLog2[x4] < nonNullTransformSize.log2Width4() ? 1 : 0)
                + (leftInterTransformHeightLog2[y4] < nonNullTransformSize.log2Height4() ? 1 : 0);
    }

    /// Returns the luma coefficient skip-context for one transform unit.
    ///
    /// This matches the luma `dav1d` `get_skip_ctx()` path by merging the already stored top and
    /// left coefficient-context bytes across the current transform span and mapping the merged
    /// counts through the `dav1d_skip_ctx` table.
    ///
    /// @param blockSize the coded block size that owns the current transform unit
    /// @param transformUnit the current luma transform unit
    /// @return the luma coefficient skip-context in `[0, 7)`
    public int lumaCoefficientSkipContext(BlockSize blockSize, TransformUnit transformUnit) {
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        return lumaCoefficientSkipContext(
                blockSize,
                nonNullTransformUnit.position().x4(),
                nonNullTransformUnit.position().y4(),
                nonNullTransformUnit.size()
        );
    }

    /// Returns the luma coefficient skip-context for one tile-local transform span.
    ///
    /// @param blockSize the coded block size that owns the current transform unit
    /// @param x4 the tile-local transform origin on the luma-grid X axis
    /// @param y4 the tile-local transform origin on the luma-grid Y axis
    /// @param transformSize the current luma transform size
    /// @return the luma coefficient skip-context in `[0, 7)`
    int lumaCoefficientSkipContext(BlockSize blockSize, int x4, int y4, TransformSize transformSize) {
        BlockSize nonNullBlockSize = Objects.requireNonNull(blockSize, "blockSize");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (nonNullBlockSize.width4() == nonNullTransformSize.width4()
                && nonNullBlockSize.height4() == nonNullTransformSize.height4()) {
            return 0;
        }

        int aboveContext = mergeCoefficientContext(
                aboveLumaCoefficientContext,
                x4,
                nonNullTransformSize.width4()
        );
        int leftContext = mergeCoefficientContext(
                leftLumaCoefficientContext,
                y4,
                nonNullTransformSize.height4()
        );
        return LUMA_COEFFICIENT_SKIP_CONTEXTS[Math.min(aboveContext & 0x3F, 4)][Math.min(leftContext & 0x3F, 4)];
    }

    /// Returns the luma DC-sign context for one transform unit.
    ///
    /// This follows `dav1d`'s `get_dc_sign_ctx()` rule by merging the sign classes stored in the
    /// current top and left coefficient-context bytes across the visible transform span.
    ///
    /// @param transformUnit the current luma transform unit
    /// @return the luma DC-sign context in `[0, 3)`
    public int lumaDcSignContext(TransformUnit transformUnit) {
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        return lumaDcSignContext(
                nonNullTransformUnit.position().x4(),
                nonNullTransformUnit.position().y4(),
                nonNullTransformUnit.size()
        );
    }

    /// Returns the luma DC-sign context for one tile-local transform span.
    ///
    /// @param x4 the tile-local transform origin on the luma-grid X axis
    /// @param y4 the tile-local transform origin on the luma-grid Y axis
    /// @param transformSize the current luma transform size
    /// @return the luma DC-sign context in `[0, 3)`
    int lumaDcSignContext(int x4, int y4, TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int signBalance = sumDcSignClasses(
                aboveLumaCoefficientContext,
                x4,
                nonNullTransformSize.width4()
        ) + sumDcSignClasses(
                leftLumaCoefficientContext,
                y4,
                nonNullTransformSize.height4()
        );
        return (signBalance != 0 ? 1 : 0) + (signBalance > 0 ? 1 : 0);
    }

    /// Returns the chroma coefficient skip-context for one transform unit on the supplied plane.
    ///
    /// Chroma coefficient-skip syntax uses dav1d's dedicated context range `7..12` instead of the
    /// luma skip-context lookup table. The context is based on whether the coded chroma block spans
    /// multiple transform units and whether top or left chroma coefficient edges contain any
    /// non-zero history for the current plane.
    ///
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @param blockSize the coded block size that owns the current chroma transform unit
    /// @param position the current block origin in tile-relative luma 4x4 units
    /// @param transformSize the current chroma transform size
    /// @return the chroma coefficient skip-context in `[7, 13)`
    public int chromaCoefficientSkipContext(
            int plane,
            BlockSize blockSize,
            BlockPosition position,
            TransformSize transformSize
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        return chromaCoefficientSkipContext(
                plane,
                blockSize,
                nonNullPosition.x4(),
                nonNullPosition.y4(),
                transformSize
        );
    }

    /// Returns the chroma coefficient skip-context for one tile-local transform span.
    ///
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @param blockSize the coded block size that owns the current transform unit
    /// @param x4 the tile-local transform origin on the luma-grid X axis
    /// @param y4 the tile-local transform origin on the luma-grid Y axis
    /// @param transformSize the current chroma transform size
    /// @return the chroma coefficient skip-context in `[7, 13)`
    int chromaCoefficientSkipContext(
            int plane,
            BlockSize blockSize,
            int x4,
            int y4,
            TransformSize transformSize
    ) {
        BlockSize nonNullBlockSize = Objects.requireNonNull(blockSize, "blockSize");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        byte[] aboveContexts = selectAboveChromaCoefficientContext(plane);
        byte[] leftContexts = selectLeftChromaCoefficientContext(plane);
        boolean notOneBlock = chromaBlockLog2Width4(nonNullBlockSize) > nonNullTransformSize.log2Width4()
                || chromaBlockLog2Height4(nonNullBlockSize) > nonNullTransformSize.log2Height4();
        boolean aboveHasNonZero = hasNonZeroCoefficientContext(
                aboveContexts,
                x4 >> chromaSubsamplingX,
                nonNullTransformSize.width4()
        );
        boolean leftHasNonZero = hasNonZeroCoefficientContext(
                leftContexts,
                y4 >> chromaSubsamplingY,
                nonNullTransformSize.height4()
        );
        return 7 + (notOneBlock ? 3 : 0) + (aboveHasNonZero ? 1 : 0) + (leftHasNonZero ? 1 : 0);
    }

    /// Returns the chroma DC-sign context for one tile-local transform span.
    ///
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @param x4 the tile-local transform origin on the luma-grid X axis
    /// @param y4 the tile-local transform origin on the luma-grid Y axis
    /// @param transformSize the current chroma transform size
    /// @return the chroma DC-sign context in `[0, 3)`
    int chromaDcSignContext(int plane, int x4, int y4, TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        byte[] aboveContexts = selectAboveChromaCoefficientContext(plane);
        byte[] leftContexts = selectLeftChromaCoefficientContext(plane);
        int signBalance = sumDcSignClasses(
                aboveContexts,
                x4 >> chromaSubsamplingX,
                nonNullTransformSize.width4()
        ) + sumDcSignClasses(
                leftContexts,
                y4 >> chromaSubsamplingY,
                nonNullTransformSize.height4()
        );
        return (signBalance != 0 ? 1 : 0) + (signBalance > 0 ? 1 : 0);
    }

    /// Updates the default transform-context dimensions after one block header is decoded.
    ///
    /// Inter blocks use their coded block dimensions for subsequent transform-size contexts, which
    /// matches `dav1d`'s `tx_intra` edge-state updates. Intra blocks may later override this with
    /// the chosen luma transform size once transform syntax has been read.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded coded block size
    public void updateDefaultTransformContext(BlockPosition position, BlockSize size) {
        updateTransformContext(
                Objects.requireNonNull(position, "position"),
                Objects.requireNonNull(size, "size"),
                size.log2Width4(),
                size.log2Height4()
        );
    }

    /// Updates the transform-size context after one intra-like block chooses its luma transform size.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded coded block size
    /// @param transformSize the chosen luma transform size
    public void updateIntraTransformContext(BlockPosition position, BlockSize size, TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        updateTransformContext(
                Objects.requireNonNull(position, "position"),
                Objects.requireNonNull(size, "size"),
                nonNullTransformSize.log2Width4(),
                nonNullTransformSize.log2Height4()
        );
    }

    /// Updates the inter var-tx context for one transform region.
    ///
    /// @param position the local tile-relative origin of the current transform region
    /// @param width4 the transform-region width in 4x4 units
    /// @param height4 the transform-region height in 4x4 units
    /// @param transformSize the chosen transform size for the current region
    public void updateInterTransformContext(
            BlockPosition position,
            int width4,
            int height4,
            TransformSize transformSize
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        updateInterTransformContext(
                Objects.requireNonNull(position, "position"),
                width4,
                height4,
                nonNullTransformSize.log2Width4(),
                nonNullTransformSize.log2Height4()
        );
    }

    /// Updates the inter var-tx context for one region using raw width/height log2 values.
    ///
    /// This variant is used when switchable inter transform mode stores the coded block dimensions
    /// rather than a legal transform enum, such as 128x128 superblocks.
    ///
    /// @param position the local tile-relative origin of the current region
    /// @param width4 the region width in 4x4 units
    /// @param height4 the region height in 4x4 units
    /// @param widthLog2 the stored width in `log2(4x4 units)`
    /// @param heightLog2 the stored height in `log2(4x4 units)`
    public void updateInterTransformContext(
            BlockPosition position,
            int width4,
            int height4,
            int widthLog2,
            int heightLog2
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int endX4 = Math.min(tileWidth4, nonNullPosition.x4() + width4);
        int endY4 = Math.min(tileHeight4, nonNullPosition.y4() + height4);
        for (int x4 = nonNullPosition.x4(); x4 < endX4; x4++) {
            aboveInterTransformWidthLog2[x4] = (byte) widthLog2;
        }
        for (int y4 = nonNullPosition.y4(); y4 < endY4; y4++) {
            leftInterTransformHeightLog2[y4] = (byte) heightLog2;
        }
    }

    /// Updates the luma coefficient-context state after one transform residual unit is decoded.
    ///
    /// @param transformUnit the decoded luma transform residual unit
    /// @param coefficientContextByte the coefficient-context byte written back for the decoded unit
    public void updateLumaCoefficientContext(TransformUnit transformUnit, int coefficientContextByte) {
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        updateLumaCoefficientContext(
                nonNullTransformUnit.position().x4(),
                nonNullTransformUnit.position().y4(),
                nonNullTransformUnit.size(),
                coefficientContextByte
        );
    }

    /// Updates the luma coefficient-context state for one tile-local transform span.
    ///
    /// @param x4 the tile-local transform origin on the luma-grid X axis
    /// @param y4 the tile-local transform origin on the luma-grid Y axis
    /// @param transformSize the decoded luma transform size
    /// @param coefficientContextByte the coefficient-context byte written back for the decoded unit
    void updateLumaCoefficientContext(
            int x4,
            int y4,
            TransformSize transformSize,
            int coefficientContextByte
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
        int endX4 = Math.min(tileWidth4, x4 + nonNullTransformSize.width4());
        int endY4 = Math.min(tileHeight4, y4 + nonNullTransformSize.height4());
        byte storedValue = (byte) coefficientContextByte;
        for (int currentX4 = x4; currentX4 < endX4; currentX4++) {
            aboveLumaCoefficientContext[currentX4] = storedValue;
        }
        for (int currentY4 = y4; currentY4 < endY4; currentY4++) {
            leftLumaCoefficientContext[currentY4] = storedValue;
        }
    }

    /// Updates the chroma coefficient-context state after one chroma transform residual unit is decoded.
    ///
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @param position the current block origin in tile-relative luma 4x4 units
    /// @param transformSize the current chroma transform size
    /// @param coefficientContextByte the coefficient-context byte written back for the decoded unit
    public void updateChromaCoefficientContext(
            int plane,
            BlockPosition position,
            TransformSize transformSize,
            int coefficientContextByte
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        updateChromaCoefficientContext(
                plane,
                nonNullPosition.x4(),
                nonNullPosition.y4(),
                transformSize,
                coefficientContextByte
        );
    }

    /// Updates the chroma coefficient-context state for one tile-local transform span.
    ///
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @param x4 the tile-local transform origin on the luma-grid X axis
    /// @param y4 the tile-local transform origin on the luma-grid Y axis
    /// @param transformSize the decoded chroma transform size
    /// @param coefficientContextByte the coefficient-context byte written back for the decoded unit
    void updateChromaCoefficientContext(
            int plane,
            int x4,
            int y4,
            TransformSize transformSize,
            int coefficientContextByte
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
        byte[] aboveContexts = selectAboveChromaCoefficientContext(plane);
        byte[] leftContexts = selectLeftChromaCoefficientContext(plane);
        int startX4 = x4 >> chromaSubsamplingX;
        int startY4 = y4 >> chromaSubsamplingY;
        int endX4 = Math.min(aboveContexts.length, startX4 + nonNullTransformSize.width4());
        int endY4 = Math.min(leftContexts.length, startY4 + nonNullTransformSize.height4());
        byte storedValue = (byte) coefficientContextByte;
        for (int currentX4 = startX4; currentX4 < endX4; currentX4++) {
            aboveContexts[currentX4] = storedValue;
        }
        for (int currentY4 = startY4; currentY4 < endY4; currentY4++) {
            leftContexts[currentY4] = storedValue;
        }
    }

    /// Writes one transform-context width/height pair across the visible edges of one block span.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded coded block size
    /// @param widthLog2 the transform-context width in `log2(4x4 units)`
    /// @param heightLog2 the transform-context height in `log2(4x4 units)`
    private void updateTransformContext(BlockPosition position, BlockSize size, int widthLog2, int heightLog2) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        int endX4 = Math.min(tileWidth4, nonNullPosition.x4() + nonNullSize.width4());
        int endY4 = Math.min(tileHeight4, nonNullPosition.y4() + nonNullSize.height4());
        for (int x4 = nonNullPosition.x4(); x4 < endX4; x4++) {
            aboveTransformWidthLog2[x4] = (byte) widthLog2;
        }
        for (int y4 = nonNullPosition.y4(); y4 < endY4; y4++) {
            leftTransformHeightLog2[y4] = (byte) heightLog2;
        }
    }

    /// Merges one coefficient-context span by OR-ing the stored bytes across the visible edge range.
    ///
    /// @param contexts the stored coefficient-context edge bytes
    /// @param start the inclusive start coordinate in 4x4 units
    /// @param span the requested span length in 4x4 units
    /// @return the merged coefficient-context byte across the requested visible edge range
    private static int mergeCoefficientContext(byte[] contexts, int start, int span) {
        int end = Math.min(contexts.length, start + span);
        int merged = ALL_ZERO_COEFFICIENT_CONTEXT_BYTE;
        for (int index = start; index < end; index++) {
            merged |= contexts[index] & 0xFF;
        }
        return merged;
    }

    /// Returns whether one coefficient-context edge span contains any non-zero residual history.
    ///
    /// @param contexts the stored coefficient-context edge bytes
    /// @param start the inclusive start coordinate in 4x4 units
    /// @param span the requested span length in 4x4 units
    /// @return whether any stored context byte differs from the all-zero sentinel
    private static boolean hasNonZeroCoefficientContext(byte[] contexts, int start, int span) {
        int end = Math.min(contexts.length, start + span);
        for (int index = start; index < end; index++) {
            if ((contexts[index] & 0xFF) != ALL_ZERO_COEFFICIENT_CONTEXT_BYTE) {
                return true;
            }
        }
        return false;
    }

    /// Sums the stored DC-sign classes across one visible coefficient-context edge span.
    ///
    /// Stored coefficient-context bytes encode negative DC blocks as class `0`, all-zero or zero-DC
    /// blocks as class `1`, and positive DC blocks as class `2`. This helper converts the stored
    /// bytes back into the signed balance used by `dav1d`'s `get_dc_sign_ctx()`.
    ///
    /// @param contexts the stored coefficient-context edge bytes
    /// @param start the inclusive start coordinate in 4x4 units
    /// @param span the requested span length in 4x4 units
    /// @return the signed DC-sign balance across the requested visible edge range
    private static int sumDcSignClasses(byte[] contexts, int start, int span) {
        int end = Math.min(contexts.length, start + span);
        int sum = 0;
        for (int index = start; index < end; index++) {
            int signClass = (contexts[index] & 0xFF) >>> 6;
            if (signClass == 0) {
                sum--;
            } else if (signClass == 2) {
                sum++;
            }
        }
        return sum;
    }

    /// Returns the effective chroma-block width log2 in chroma 4x4 units for one coded block size.
    ///
    /// @param blockSize the coded block size to inspect
    /// @return the effective chroma-block width log2 in chroma 4x4 units
    private int chromaBlockLog2Width4(BlockSize blockSize) {
        BlockSize nonNullBlockSize = Objects.requireNonNull(blockSize, "blockSize");
        int log2Width4 = nonNullBlockSize.log2Width4();
        return Math.max(0, log2Width4 - (log2Width4 != 0 ? chromaSubsamplingX : 0));
    }

    /// Returns the effective chroma-block height log2 in chroma 4x4 units for one coded block size.
    ///
    /// @param blockSize the coded block size to inspect
    /// @return the effective chroma-block height log2 in chroma 4x4 units
    private int chromaBlockLog2Height4(BlockSize blockSize) {
        BlockSize nonNullBlockSize = Objects.requireNonNull(blockSize, "blockSize");
        int log2Height4 = nonNullBlockSize.log2Height4();
        return Math.max(0, log2Height4 - (log2Height4 != 0 ? chromaSubsamplingY : 0));
    }

    /// Returns the stored above-edge chroma coefficient-context bytes for one chroma plane.
    ///
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @return the stored above-edge chroma coefficient-context bytes for the supplied plane
    private byte[] selectAboveChromaCoefficientContext(int plane) {
        return aboveChromaCoefficientContext[requireChromaPlaneIndex(plane)];
    }

    /// Returns the stored left-edge chroma coefficient-context bytes for one chroma plane.
    ///
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @return the stored left-edge chroma coefficient-context bytes for the supplied plane
    private byte[] selectLeftChromaCoefficientContext(int plane) {
        return leftChromaCoefficientContext[requireChromaPlaneIndex(plane)];
    }

    /// Validates and returns one chroma plane index used by the chroma coefficient-context helpers.
    ///
    /// @param plane the requested chroma plane index
    /// @return the same chroma plane index after validation
    private static int requireChromaPlaneIndex(int plane) {
        if (plane != 0 && plane != 1) {
            throw new IllegalArgumentException("plane must be 0 (U) or 1 (V): " + plane);
        }
        return plane;
    }

    /// Validates and returns one switchable interpolation-filter direction index.
    ///
    /// Direction `0` selects the horizontal filter symbol and direction `1` selects the vertical
    /// filter symbol.
    ///
    /// @param direction the requested switchable interpolation-filter direction index
    /// @return the same direction index after validation
    private static int requireInterpolationFilterDirection(int direction) {
        if (direction != 0 && direction != 1) {
            throw new IllegalArgumentException("direction must be 0 (horizontal) or 1 (vertical): " + direction);
        }
        return direction;
    }

    /// Returns the edge filter symbol that matches the current primary reference, or the unset sentinel.
    ///
    /// @param referenceFrame0 the current primary inter reference in internal LAST..ALTREF order
    /// @param neighborIntra whether the stored neighbor is intra-coded
    /// @param neighborCompound whether the stored neighbor uses compound references
    /// @param neighborReferenceFrame0 the stored neighbor primary inter reference in internal LAST..ALTREF order
    /// @param neighborReferenceFrame1 the stored neighbor secondary inter reference in internal LAST..ALTREF order, or `-1`
    /// @param storedFilterSymbol the stored edge filter symbol
    /// @return the edge filter symbol that matches the current primary reference, or the unset sentinel
    private static byte matchingInterpolationFilterSymbol(
            int referenceFrame0,
            boolean neighborIntra,
            boolean neighborCompound,
            byte neighborReferenceFrame0,
            byte neighborReferenceFrame1,
            byte storedFilterSymbol
    ) {
        if (neighborIntra || storedFilterSymbol == INTERPOLATION_FILTER_UNSET) {
            return INTERPOLATION_FILTER_UNSET;
        }
        if ((neighborReferenceFrame0 & 0xFF) == referenceFrame0) {
            return storedFilterSymbol;
        }
        if (neighborCompound && (neighborReferenceFrame1 & 0xFF) == referenceFrame0) {
            return storedFilterSymbol;
        }
        return INTERPOLATION_FILTER_UNSET;
    }

    /// Returns the switchable interpolation-filter symbol stored for one decoded filter, or the unset sentinel.
    ///
    /// @param interpolationFilter the decoded interpolation filter, or `null`
    /// @return the switchable interpolation-filter symbol stored for one decoded filter, or the unset sentinel
    private static byte interpolationFilterSymbol(FrameHeader.InterpolationFilter interpolationFilter) {
        if (interpolationFilter == null) {
            return INTERPOLATION_FILTER_UNSET;
        }
        return switch (interpolationFilter) {
            case EIGHT_TAP_REGULAR -> INTERPOLATION_FILTER_REGULAR;
            case EIGHT_TAP_SMOOTH -> INTERPOLATION_FILTER_SMOOTH;
            case EIGHT_TAP_SHARP -> INTERPOLATION_FILTER_SHARP;
            case BILINEAR, SWITCHABLE -> INTERPOLATION_FILTER_UNSET;
        };
    }

    /// Returns whether the current block has an already-decoded inter neighbor usable by OBMC.
    ///
    /// This follows AV1's 8x8-granularity neighbor scan against the causal above and left edges.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the current block size
    /// @return whether at least one above or left neighbor can provide an OBMC predictor
    public boolean hasOverlappableCandidates(BlockPosition position, BlockSize size) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        int startX4 = nonNullPosition.x4();
        int startY4 = nonNullPosition.y4();
        int endX4 = Math.min(tileWidth4, startX4 + nonNullSize.width4());
        int endY4 = Math.min(tileHeight4, startY4 + nonNullSize.height4());

        if (startY4 > 0) {
            for (int x4 = startX4; x4 < endX4; x4 += 2) {
                int sampleX4 = Math.min(aboveIntra.length - 1, x4 | 1);
                if (aboveIntra[sampleX4] == 0 && aboveReferenceFrame0[sampleX4] >= 0) {
                    return true;
                }
            }
        }
        if (startX4 > 0) {
            for (int y4 = startY4; y4 < endY4; y4 += 2) {
                int sampleY4 = Math.min(leftIntra.length - 1, y4 | 1);
                if (leftIntra[sampleY4] == 0 && leftReferenceFrame0[sampleY4] >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Returns whether the current block has already-decoded causal samples usable by local warped motion.
    ///
    /// Local warped motion can only use single-reference inter neighbors that share the current
    /// primary reference. The causal sample set includes direct top and left edges plus eligible
    /// top-left and top-right corner blocks.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the current block size
    /// @param referenceFrame0 the current block primary inter reference in internal LAST..ALTREF order
    /// @return whether at least one compatible causal neighbor can seed local warped motion
    public boolean hasLocalWarpSamples(BlockPosition position, BlockSize size, int referenceFrame0) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        if (referenceFrame0 < 0) {
            return false;
        }

        int startX4 = nonNullPosition.x4();
        int startY4 = nonNullPosition.y4();
        int endX4 = Math.min(tileWidth4, startX4 + nonNullSize.width4());
        int endY4 = Math.min(tileHeight4, startY4 + nonNullSize.height4());
        boolean hasTop = startY4 > 0;
        boolean hasLeft = startX4 > 0;
        if (startY4 > 0 && hasLocalWarpSampleAlongSpan(true, startY4 - 1, startX4, endX4, referenceFrame0)) {
            return true;
        }
        if (startX4 > 0 && hasLocalWarpSampleAlongSpan(false, startX4 - 1, startY4, endY4, referenceFrame0)) {
            return true;
        }

        boolean hasTopLeft = hasTop && hasLeft;
        boolean hasTopRight = hasTop
                && Math.max(nonNullSize.width4(), nonNullSize.height4()) < 32
                && endX4 < tileWidth4;
        if (hasTop) {
            StoredBlock firstTopBlock = storedBlockAtOrNull(startX4, startY4 - 1);
            if (firstTopBlock != null && firstTopBlock.width4() >= nonNullSize.width4()) {
                if (firstTopBlock.originX4() != startX4) {
                    hasTopLeft = false;
                }
                if (firstTopBlock.originX4() + firstTopBlock.width4() > endX4) {
                    hasTopRight = false;
                }
            }
        }
        if (hasLeft) {
            StoredBlock firstLeftBlock = storedBlockAtOrNull(startX4 - 1, startY4);
            if (firstLeftBlock != null
                    && firstLeftBlock.height4() >= nonNullSize.height4()
                    && firstLeftBlock.originY4() != startY4) {
                hasTopLeft = false;
            }
        }
        if (hasTopLeft && isLocalWarpSampleBlock(storedBlockAtOrNull(startX4 - 1, startY4 - 1), referenceFrame0)) {
            return true;
        }
        return hasTopRight
                && isLocalWarpSampleBlock(storedBlockAtOrNull(endX4, startY4 - 1), referenceFrame0);
    }

    /// Updates the neighbor state after decoding one block header.
    ///
    /// @param header the decoded block header that should become the new above/left edge state
    public void updateFromBlockHeader(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        BlockPosition position = nonNullHeader.position();
        BlockSize size = nonNullHeader.size();
        byte intra = (byte) ((nonNullHeader.intra() || nonNullHeader.useIntrabc()) ? 1 : 0);
        byte skip = (byte) (nonNullHeader.skip() ? 1 : 0);
        byte skipMode = (byte) (nonNullHeader.skipMode() ? 1 : 0);
        byte compoundReference = (byte) (nonNullHeader.compoundReference() ? 1 : 0);
        byte compoundPredictionType = compoundPredictionTypeContext(nonNullHeader.compoundPredictionType());
        byte segmentPredicted = (byte) (nonNullHeader.segmentPredicted() ? 1 : 0);
        byte segmentId = (byte) nonNullHeader.segmentId();
        byte paletteSize = (byte) nonNullHeader.yPaletteSize();
        byte chromaPaletteSize = (byte) nonNullHeader.uvPaletteSize();
        byte referenceFrame0 = (byte) nonNullHeader.referenceFrame0();
        byte referenceFrame1 = (byte) nonNullHeader.referenceFrame1();
        InterMotionVector motionVector0 = fallbackMotionVector(nonNullHeader.motionVector0());
        InterMotionVector motionVector1 = fallbackMotionVector(nonNullHeader.motionVector1());
        byte usesNewMotionVector = (byte) (usesNewMotionVector(nonNullHeader) ? 1 : 0);
        boolean usesGlobalMotionMode = usesGlobalMotionMode(nonNullHeader);
        byte horizontalInterpolationFilter = interpolationFilterSymbol(nonNullHeader.horizontalInterpolationFilter());
        byte verticalInterpolationFilter = interpolationFilterSymbol(nonNullHeader.verticalInterpolationFilter());
        LumaIntraPredictionMode mode = nonNullHeader.intra() ? nonNullHeader.yMode() : LumaIntraPredictionMode.DC;
        int endX4 = Math.min(tileWidth4, position.x4() + size.width4());
        int endY4 = Math.min(tileHeight4, position.y4() + size.height4());
        StoredBlock storedBlock = new StoredBlock(
                position.x4(),
                position.y4(),
                size.width4(),
                size.height4(),
                segmentId & 0xFF,
                intra != 0,
                nonNullHeader.useIntrabc(),
                compoundReference != 0,
                nonNullHeader.interIntra(),
                referenceFrame0,
                referenceFrame1,
                motionVector0,
                motionVector1,
                usesNewMotionVector != 0,
                usesGlobalMotionMode
        );
        if (updateSegmentIdMap) {
            currentSegmentIdMap.fill(
                    (tileStartX8 << 1) + position.x4(),
                    (tileStartY8 << 1) + position.y4(),
                    size.width4(),
                    size.height4(),
                    segmentId & 0xFF
            );
        }
        for (int x4 = position.x4(); x4 < endX4; x4++) {
            aboveIntra[x4] = intra;
            aboveSkip[x4] = skip;
            aboveSkipMode[x4] = skipMode;
            aboveCompoundReference[x4] = compoundReference;
            aboveCompoundPredictionType[x4] = compoundPredictionType;
            aboveReferenceFrame0[x4] = referenceFrame0;
            aboveReferenceFrame1[x4] = referenceFrame1;
            aboveMotionVector0[x4] = motionVector0;
            aboveMotionVector1[x4] = motionVector1;
            aboveUsesNewMotionVector[x4] = usesNewMotionVector;
            aboveInterpolationFilterHorizontal[x4] = horizontalInterpolationFilter;
            aboveInterpolationFilterVertical[x4] = verticalInterpolationFilter;
            aboveSegmentPredicted[x4] = segmentPredicted;
            abovePaletteSize[x4] = paletteSize;
            aboveChromaPaletteSize[x4] = chromaPaletteSize;
            copyPaletteEntries(
                    nonNullHeader,
                    abovePaletteEntries[0][x4],
                    abovePaletteEntries[1][x4],
                    abovePaletteEntries[2][x4]
            );
            aboveMode[x4] = mode;
        }
        for (int y4 = position.y4(); y4 < endY4; y4++) {
            leftIntra[y4] = intra;
            leftSkip[y4] = skip;
            leftSkipMode[y4] = skipMode;
            leftCompoundReference[y4] = compoundReference;
            leftCompoundPredictionType[y4] = compoundPredictionType;
            leftReferenceFrame0[y4] = referenceFrame0;
            leftReferenceFrame1[y4] = referenceFrame1;
            leftMotionVector0[y4] = motionVector0;
            leftMotionVector1[y4] = motionVector1;
            leftUsesNewMotionVector[y4] = usesNewMotionVector;
            leftInterpolationFilterHorizontal[y4] = horizontalInterpolationFilter;
            leftInterpolationFilterVertical[y4] = verticalInterpolationFilter;
            leftSegmentPredicted[y4] = segmentPredicted;
            leftPaletteSize[y4] = paletteSize;
            leftChromaPaletteSize[y4] = chromaPaletteSize;
            copyPaletteEntries(
                    nonNullHeader,
                    leftPaletteEntries[0][y4],
                    leftPaletteEntries[1][y4],
                    leftPaletteEntries[2][y4]
            );
            leftMode[y4] = mode;
        }
        for (int y4 = position.y4(); y4 < endY4; y4++) {
            for (int x4 = position.x4(); x4 < endX4; x4++) {
                storedBlocks[blockIndex(x4, y4)] = storedBlock;
            }
        }
        updateDecodedTemporalMotionField(nonNullHeader, endX4, endY4, motionVector0, motionVector1);
    }

    /// Copies one block header's palette entries into fixed-size neighbor caches.
    ///
    /// Only entries below the active palette sizes are observable through the neighbor context.
    ///
    /// @param header the source block header
    /// @param yEntries the luma palette cache
    /// @param uEntries the U chroma palette cache
    /// @param vEntries the V chroma palette cache
    private static void copyPaletteEntries(
            TileBlockHeaderReader.BlockHeader header,
            int[] yEntries,
            int[] uEntries,
            int[] vEntries
    ) {
        for (int index = 0; index < header.yPaletteSize(); index++) {
            yEntries[index] = header.yPaletteColor(index);
        }
        for (int index = 0; index < header.uvPaletteSize(); index++) {
            uEntries[index] = header.uPaletteColor(index);
            vEntries[index] = header.vPaletteColor(index);
        }
    }

    /// Updates the current-frame temporal motion field with one decoded block header.
    ///
    /// This write-back path does not feed samples back into the current frame's spatial candidate
    /// scan. The stored samples remain available for refreshed reference state, although frames
    /// that request temporal `refmvs` projection are rejected before tile decoding.
    ///
    /// @param header the decoded block header that should be projected into the current-frame temporal field
    /// @param endX4 the exclusive end X coordinate of the decoded block in 4x4 units
    /// @param endY4 the exclusive end Y coordinate of the decoded block in 4x4 units
    /// @param motionVector0 the normalized primary motion-vector state chosen for the block
    /// @param motionVector1 the normalized secondary motion-vector state chosen for the block
    private void updateDecodedTemporalMotionField(
            TileBlockHeaderReader.BlockHeader header,
            int endX4,
            int endY4,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        int startX8 = nonNullHeader.position().x8();
        int startY8 = nonNullHeader.position().y8();
        int endX8 = Math.min(decodedTemporalMotionField.width8(), (endX4 + 1) >> 1);
        int endY8 = Math.min(decodedTemporalMotionField.height8(), (endY4 + 1) >> 1);
        if (startX8 >= endX8 || startY8 >= endY8) {
            return;
        }

        TileDecodeContext.TemporalMotionBlock temporalMotionBlock = createDecodedTemporalMotionBlock(
                nonNullHeader,
                motionVector0,
                motionVector1
        );
        for (int y8 = startY8; y8 < endY8; y8++) {
            for (int x8 = startX8; x8 < endX8; x8++) {
                if (temporalMotionBlock == null) {
                    decodedTemporalMotionField.clearBlock(x8, y8);
                } else {
                    decodedTemporalMotionField.setBlock(x8, y8, temporalMotionBlock);
                }
            }
        }
    }

    /// Creates the temporal motion-field sample contributed by one decoded block header, or `null`.
    ///
    /// Intra and `intrabc` blocks do not contribute temporal motion samples. Inter samples are
    /// stored with the already normalized motion-vector states carried by the block header.
    ///
    /// @param header the decoded block header
    /// @param motionVector0 the normalized primary motion-vector state chosen for the block
    /// @param motionVector1 the normalized secondary motion-vector state chosen for the block
    /// @return the temporal motion-field sample contributed by the block, or `null`
    private static TileDecodeContext.TemporalMotionBlock createDecodedTemporalMotionBlock(
            TileBlockHeaderReader.BlockHeader header,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        if (nonNullHeader.intra() || nonNullHeader.useIntrabc() || nonNullHeader.referenceFrame0() < 0) {
            return null;
        }
        if (nonNullHeader.compoundReference()) {
            if (nonNullHeader.referenceFrame1() < 0) {
                return null;
            }
            return TileDecodeContext.TemporalMotionBlock.compoundReference(
                    nonNullHeader.referenceFrame0(),
                    nonNullHeader.referenceFrame1(),
                    Objects.requireNonNull(motionVector0, "motionVector0"),
                    Objects.requireNonNull(motionVector1, "motionVector1")
            );
        }
        return TileDecodeContext.TemporalMotionBlock.singleReference(
                nonNullHeader.referenceFrame0(),
                Objects.requireNonNull(motionVector0, "motionVector0")
        );
    }

    /// Returns the flattened stored-block index for one tile-relative 4x4 coordinate.
    ///
    /// @param x4 the tile-relative X coordinate in 4x4 units
    /// @param y4 the tile-relative Y coordinate in 4x4 units
    /// @return the flattened stored-block index for one tile-relative 4x4 coordinate
    private int blockIndex(int x4, int y4) {
        return y4 * tileWidth4 + x4;
    }

    /// Returns the stored decoded block covering one tile-relative 4x4 coordinate, or `null`.
    ///
    /// @param x4 the tile-relative X coordinate in 4x4 units
    /// @param y4 the tile-relative Y coordinate in 4x4 units
    /// @return the stored decoded block covering one tile-relative 4x4 coordinate, or `null`
    private StoredBlock storedBlockAt(int x4, int y4) {
        return storedBlocks[blockIndex(x4, y4)];
    }

    /// Returns whether a stored row or column contains a compatible local-warp sample block.
    ///
    /// @param rowScan whether the scan walks a fixed row instead of a fixed column
    /// @param fixedCoordinate4 the fixed row or column coordinate in 4x4 units
    /// @param spanStart4 the inclusive start of the scanned span on the varying axis
    /// @param spanEnd4 the exclusive end of the scanned span on the varying axis
    /// @param referenceFrame0 the current block primary inter reference in internal LAST..ALTREF order
    /// @return whether a compatible local-warp sample block was found
    private boolean hasLocalWarpSampleAlongSpan(
            boolean rowScan,
            int fixedCoordinate4,
            int spanStart4,
            int spanEnd4,
            int referenceFrame0
    ) {
        StoredBlock previousBlock = null;
        for (int varyingCoordinate4 = spanStart4; varyingCoordinate4 < spanEnd4; varyingCoordinate4++) {
            StoredBlock storedBlock = rowScan
                    ? storedBlockAt(varyingCoordinate4, fixedCoordinate4)
                    : storedBlockAt(fixedCoordinate4, varyingCoordinate4);
            if (storedBlock == null || storedBlock == previousBlock) {
                continue;
            }
            previousBlock = storedBlock;
            if (isLocalWarpSampleBlock(storedBlock, referenceFrame0)) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether one stored block can seed current-block local warped motion.
    ///
    /// @param storedBlock the stored causal neighbor block
    /// @param referenceFrame0 the current block primary inter reference in internal LAST..ALTREF order
    /// @return whether one stored block can seed current-block local warped motion
    private static boolean isLocalWarpSampleBlock(StoredBlock storedBlock, int referenceFrame0) {
        return storedBlock != null
                && !storedBlock.intra()
                && !storedBlock.compoundReference()
                && !storedBlock.interIntra()
                && storedBlock.referenceFrame0() == referenceFrame0
                && storedBlock.motionVector0().resolved();
    }

    /// Returns one stored edge motion vector, falling back to a provisional zero vector.
    ///
    /// @param motionVector the stored motion vector, or `null`
    /// @return one stored edge motion vector, falling back to a provisional zero vector
    private static InterMotionVector fallbackMotionVector(InterMotionVector motionVector) {
        return motionVector != null ? motionVector : PREDICTED_ZERO_MOTION_VECTOR;
    }

    /// Returns whether one decoded block used any `NEWMV`-carrying inter mode.
    ///
    /// @param header the decoded block header
    /// @return whether the decoded block used any `NEWMV`-carrying inter mode
    private static boolean usesNewMotionVector(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        if (nonNullHeader.singleInterMode() != null) {
            return nonNullHeader.singleInterMode() == pzopt.vendor.avif.internal.av1.model.SingleInterPredictionMode.NEWMV;
        }
        if (nonNullHeader.compoundInterMode() == null) {
            return false;
        }
        return switch (nonNullHeader.compoundInterMode()) {
            case NEARESTMV_NEARESTMV, NEARMV_NEARMV, GLOBALMV_GLOBALMV -> false;
            case NEARESTMV_NEWMV, NEWMV_NEARESTMV, NEARMV_NEWMV, NEWMV_NEARMV, NEWMV_NEWMV -> true;
        };
    }

    /// Returns whether one decoded block selected the all-global inter mode.
    ///
    /// @param header the decoded block header
    /// @return whether the block selected `GLOBALMV` or `GLOBALMV_GLOBALMV`
    private static boolean usesGlobalMotionMode(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        return nonNullHeader.singleInterMode()
                == pzopt.vendor.avif.internal.av1.model.SingleInterPredictionMode.GLOBALMV
                || nonNullHeader.compoundInterMode()
                == pzopt.vendor.avif.internal.av1.model.CompoundInterPredictionMode.GLOBALMV_GLOBALMV;
    }

    /// Returns whether one stored neighbor uses a unidirectional compound reference pair.
    ///
    /// @param leftEdge whether the stored neighbor lives on the left edge instead of the above edge
    /// @param index the edge index in 4x4 units
    /// @return whether one stored neighbor uses a unidirectional compound reference pair
    private boolean hasUnidirectionalCompoundReference(boolean leftEdge, int index) {
        int referenceFrame0 = leftEdge ? leftReferenceFrame0[index] : aboveReferenceFrame0[index];
        int referenceFrame1 = leftEdge ? leftReferenceFrame1[index] : aboveReferenceFrame1[index];
        return (referenceFrame0 < 4) == (referenceFrame1 < 4);
    }

    /// Returns the masked-compound context contribution from one stored edge neighbor.
    ///
    /// @param leftEdge whether the stored neighbor lives on the left edge instead of the above edge
    /// @param index the edge index in 4x4 units
    /// @return the masked-compound context contribution from the stored neighbor
    private int maskedCompoundNeighborContext(boolean leftEdge, int index) {
        int compoundPredictionType =
                leftEdge ? leftCompoundPredictionType[index] : aboveCompoundPredictionType[index];
        int referenceFrame0 = leftEdge ? leftReferenceFrame0[index] : aboveReferenceFrame0[index];
        if (compoundPredictionType >= CompoundPredictionType.SEGMENT.contextValue()) {
            return 1;
        }
        return referenceFrame0 == 6 ? 3 : 0;
    }

    /// Returns the joint-compound context contribution from one stored edge neighbor.
    ///
    /// @param leftEdge whether the stored neighbor lives on the left edge instead of the above edge
    /// @param index the edge index in 4x4 units
    /// @return the joint-compound context contribution from the stored neighbor
    private int jointCompoundNeighborContext(boolean leftEdge, int index) {
        int compoundPredictionType =
                leftEdge ? leftCompoundPredictionType[index] : aboveCompoundPredictionType[index];
        int referenceFrame0 = leftEdge ? leftReferenceFrame0[index] : aboveReferenceFrame0[index];
        return compoundPredictionType >= CompoundPredictionType.AVERAGE.contextValue() || referenceFrame0 == 6 ? 1 : 0;
    }

    /// Returns the edge context value stored for one compound prediction type.
    ///
    /// @param compoundPredictionType the decoded compound prediction type, or `null`
    /// @return the edge context value stored for the supplied type
    private static byte compoundPredictionTypeContext(CompoundPredictionType compoundPredictionType) {
        return (byte) (compoundPredictionType == null ? 0 : compoundPredictionType.contextValue());
    }

    /// Returns the wrapped order-hint difference `poc0 - poc1`.
    ///
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param poc0 the minuend order hint
    /// @param poc1 the subtrahend order hint
    /// @return the wrapped order-hint difference `poc0 - poc1`
    private static int orderHintDifference(int orderHintBits, int poc0, int poc1) {
        if (orderHintBits == 0) {
            return 0;
        }
        int mask = 1 << (orderHintBits - 1);
        int diff = poc0 - poc1;
        return (diff & (mask - 1)) - (diff & mask);
    }

    /// Clears and returns the reusable reference-count workspace.
    ///
    /// @return the zero-filled four-entry reference-count workspace
    private int[] resetReferenceCountScratch() {
        Arrays.fill(referenceCountScratch, 0);
        return referenceCountScratch;
    }

    /// Accumulates forward-vs-backward reference counts from already-decoded neighbors.
    ///
    /// @param count the two-entry destination array for forward and backward reference counts
    /// @param includeIntra whether intra-coded neighbors should also contribute
    /// @param position the current block position
    private void accumulateForwardBackwardCounts(int[] count, boolean includeIntra, BlockPosition position) {
        int x4 = position.x4();
        int y4 = position.y4();
        if (hasTopNeighbor(position) && (includeIntra || aboveIntra[x4] == 0)) {
            count[(aboveReferenceFrame0[x4] & 0xFF) >= 4 ? 1 : 0]++;
            if (aboveCompoundReference[x4] != 0) {
                count[(aboveReferenceFrame1[x4] & 0xFF) >= 4 ? 1 : 0]++;
            }
        }
        if (hasLeftNeighbor(position) && (includeIntra || leftIntra[y4] == 0)) {
            count[(leftReferenceFrame0[y4] & 0xFF) >= 4 ? 1 : 0]++;
            if (leftCompoundReference[y4] != 0) {
                count[(leftReferenceFrame1[y4] & 0xFF) >= 4 ? 1 : 0]++;
            }
        }
    }

    /// Accumulates reference counts for one contiguous range of reference-frame indices.
    ///
    /// @param count the destination count array with at least `length` entries
    /// @param position the current block position
    /// @param startReference the inclusive first tracked reference-frame index
    /// @param length the number of tracked reference-frame indices
    private void accumulateReferenceCounts(int[] count, BlockPosition position, int startReference, int length) {
        int x4 = position.x4();
        int y4 = position.y4();
        if (hasTopNeighbor(position) && aboveIntra[x4] == 0) {
            incrementReferenceCount(count, aboveReferenceFrame0[x4], startReference, length);
            if (aboveCompoundReference[x4] != 0) {
                incrementReferenceCount(count, aboveReferenceFrame1[x4], startReference, length);
            }
        }
        if (hasLeftNeighbor(position) && leftIntra[y4] == 0) {
            incrementReferenceCount(count, leftReferenceFrame0[y4], startReference, length);
            if (leftCompoundReference[y4] != 0) {
                incrementReferenceCount(count, leftReferenceFrame1[y4], startReference, length);
            }
        }
    }

    /// Increments one counted reference bucket when the supplied stored reference falls in range.
    ///
    /// @param count the destination count array
    /// @param referenceFrame the stored reference-frame index
    /// @param startReference the inclusive first tracked reference-frame index
    /// @param length the number of tracked reference-frame indices
    private static void incrementReferenceCount(int[] count, int referenceFrame, int startReference, int length) {
        int index = referenceFrame - startReference;
        if (index >= 0 && index < length) {
            count[index]++;
        }
    }

    /// Scans one AV1 `refmvs` row using decoded block dimensions to advance between candidates.
    ///
    /// @param fixedY4 the fixed tile-relative row coordinate
    /// @param startX4 the first tile-relative column coordinate
    /// @param blockWidth4 the current block width in 4x4 units
    /// @param visibleWidth4 the bounded scan width in 4x4 units
    /// @param maximumRows the maximum spatial row depth used for candidate weighting
    /// @param step4 the minimum horizontal scan step in 4x4 units
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param globalMotion the current block's global-motion candidates
    /// @param destination the destination candidate stack
    /// @param count the number of active candidates already stored
    /// @return the updated candidate state and spatial-depth contribution
    private ExactSpatialScanResult scanExactRefMvsRow(
            int fixedY4,
            int startX4,
            int blockWidth4,
            int visibleWidth4,
            int maximumRows,
            int step4,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            ExactSpatialGlobalMotion globalMotion,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        StoredBlock firstBlock = storedBlockAtOrNull(startX4, fixedY4);
        int firstWidth4 = firstBlock != null ? firstBlock.width4() : 1;
        int length4 = Math.max(step4, Math.min(blockWidth4, firstWidth4));
        if (blockWidth4 <= firstWidth4) {
            int firstHeight4 = firstBlock != null ? firstBlock.height4() : 1;
            int weightFactor = blockWidth4 == 1
                    ? 2
                    : Math.max(2, Math.min(2 * maximumRows, firstHeight4));
            long candidateResult = addExactSpatialCandidate(
                    firstBlock,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    globalMotion,
                    length4 * weightFactor,
                    destination,
                    count
            );
            return new ExactSpatialScanResult(
                    spatialCandidateCount(candidateResult),
                    spatialReferenceMatch(candidateResult),
                    spatialNewMotionVectorMatch(candidateResult),
                    weightFactor >> 1
            );
        }

        boolean referenceMatch = false;
        boolean haveNewMotionVectorMatch = false;
        for (int offset4 = 0; ; ) {
            StoredBlock block = storedBlockAtOrNull(startX4 + offset4, fixedY4);
            long candidateResult = addExactSpatialCandidate(
                    block,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    globalMotion,
                    length4 * 2,
                    destination,
                    count
            );
            count = spatialCandidateCount(candidateResult);
            referenceMatch |= spatialReferenceMatch(candidateResult);
            haveNewMotionVectorMatch |= spatialNewMotionVectorMatch(candidateResult);
            offset4 += length4;
            if (offset4 >= visibleWidth4) {
                return new ExactSpatialScanResult(count, referenceMatch, haveNewMotionVectorMatch, 1);
            }
            StoredBlock nextBlock = storedBlockAtOrNull(startX4 + offset4, fixedY4);
            length4 = Math.max(step4, nextBlock != null ? nextBlock.width4() : 1);
        }
    }

    /// Scans one AV1 `refmvs` column using decoded block dimensions to advance between candidates.
    ///
    /// @param fixedX4 the fixed tile-relative column coordinate
    /// @param startY4 the first tile-relative row coordinate
    /// @param blockHeight4 the current block height in 4x4 units
    /// @param visibleHeight4 the bounded scan height in 4x4 units
    /// @param maximumColumns the maximum spatial column depth used for candidate weighting
    /// @param step4 the minimum vertical scan step in 4x4 units
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param globalMotion the current block's global-motion candidates
    /// @param destination the destination candidate stack
    /// @param count the number of active candidates already stored
    /// @return the updated candidate state and spatial-depth contribution
    private ExactSpatialScanResult scanExactRefMvsColumn(
            int fixedX4,
            int startY4,
            int blockHeight4,
            int visibleHeight4,
            int maximumColumns,
            int step4,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            ExactSpatialGlobalMotion globalMotion,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        StoredBlock firstBlock = storedBlockAtOrNull(fixedX4, startY4);
        int firstHeight4 = firstBlock != null ? firstBlock.height4() : 1;
        int length4 = Math.max(step4, Math.min(blockHeight4, firstHeight4));
        if (blockHeight4 <= firstHeight4) {
            int firstWidth4 = firstBlock != null ? firstBlock.width4() : 1;
            int weightFactor = blockHeight4 == 1
                    ? 2
                    : Math.max(2, Math.min(2 * maximumColumns, firstWidth4));
            long candidateResult = addExactSpatialCandidate(
                    firstBlock,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    globalMotion,
                    length4 * weightFactor,
                    destination,
                    count
            );
            return new ExactSpatialScanResult(
                    spatialCandidateCount(candidateResult),
                    spatialReferenceMatch(candidateResult),
                    spatialNewMotionVectorMatch(candidateResult),
                    weightFactor >> 1
            );
        }

        boolean referenceMatch = false;
        boolean haveNewMotionVectorMatch = false;
        for (int offset4 = 0; ; ) {
            StoredBlock block = storedBlockAtOrNull(fixedX4, startY4 + offset4);
            long candidateResult = addExactSpatialCandidate(
                    block,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    globalMotion,
                    length4 * 2,
                    destination,
                    count
            );
            count = spatialCandidateCount(candidateResult);
            referenceMatch |= spatialReferenceMatch(candidateResult);
            haveNewMotionVectorMatch |= spatialNewMotionVectorMatch(candidateResult);
            offset4 += length4;
            if (offset4 >= visibleHeight4) {
                return new ExactSpatialScanResult(count, referenceMatch, haveNewMotionVectorMatch, 1);
            }
            StoredBlock nextBlock = storedBlockAtOrNull(fixedX4, startY4 + offset4);
            length4 = Math.max(step4, nextBlock != null ? nextBlock.height4() : 1);
        }
    }

    /// Adds one matching spatial candidate to an AV1 `refmvs` stack.
    ///
    /// @param block the decoded spatial block, or `null`
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param globalMotion the current block's global-motion candidates
    /// @param weight the spatial candidate weight
    /// @param destination the destination candidate stack
    /// @param count the number of active candidates already stored
    /// @return the updated stack and reference-match state
    private static long addExactSpatialCandidate(
            StoredBlock block,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            ExactSpatialGlobalMotion globalMotion,
            int weight,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        if (block == null) {
            return spatialCandidateResult(count, false, false);
        }
        if (referenceFrame0 == INTRABC_REFERENCE_FRAME) {
            if (!block.intrabc()) {
                return spatialCandidateResult(count, false, false);
            }
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate =
                    new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                            weight,
                            block.motionVector0().asPredicted(),
                            null,
                            false
                    );
            return spatialCandidateResult(
                    appendProvisionalCandidate(destination, count, candidate),
                    true,
                    false
            );
        }
        if (block.intra()) {
            return spatialCandidateResult(count, false, false);
        }
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate =
                provisionalMotionVectorCandidate(
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        block,
                        globalMotion,
                        weight
                );
        if (candidate == null) {
            return spatialCandidateResult(count, false, false);
        }
        return spatialCandidateResult(
                appendProvisionalCandidate(destination, count, candidate),
                true,
                block.usesNewMotionVector()
        );
    }

    /// Packs one spatial-candidate result into an allocation-free primitive value.
    ///
    /// @param candidateCount the updated candidate count
    /// @param referenceMatch whether the reference selection matched
    /// @param haveNewMotionVectorMatch whether the matching block used a new motion vector
    /// @return the packed spatial-candidate result
    private static long spatialCandidateResult(
            int candidateCount,
            boolean referenceMatch,
            boolean haveNewMotionVectorMatch
    ) {
        return Integer.toUnsignedLong(candidateCount)
                | (referenceMatch ? 1L << 32 : 0L)
                | (haveNewMotionVectorMatch ? 1L << 33 : 0L);
    }

    /// Returns the candidate count from one packed spatial-candidate result.
    ///
    /// @param result the packed result
    /// @return the updated candidate count
    private static int spatialCandidateCount(long result) {
        return (int) result;
    }

    /// Returns whether one packed spatial-candidate result matched the reference selection.
    ///
    /// @param result the packed result
    /// @return whether the reference selection matched
    private static boolean spatialReferenceMatch(long result) {
        return (result & (1L << 32)) != 0;
    }

    /// Returns whether one packed result matched a block using a new motion vector.
    ///
    /// @param result the packed result
    /// @return whether the matching block used a new motion vector
    private static boolean spatialNewMotionVectorMatch(long result) {
        return (result & (1L << 33)) != 0;
    }

    /// Returns one stored block without throwing when a coordinate is outside the tile.
    ///
    /// @param x4 the tile-relative X coordinate in 4x4 units
    /// @param y4 the tile-relative Y coordinate in 4x4 units
    /// @return the stored block covering the coordinate, or `null`
    private StoredBlock storedBlockAtOrNull(int x4, int y4) {
        if (x4 < 0 || x4 >= tileWidth4 || y4 < 0 || y4 >= tileHeight4) {
            return null;
        }
        return storedBlockAt(x4, y4);
    }

    /// Samples projected temporal motion vectors over one current-block footprint.
    ///
    /// Main samples cover at most an 8x8 temporal-grid region and use a two-cell stride for block
    /// dimensions of at least 64 pixels. The three AV1 edge probes are included for eligible block
    /// dimensions inside the current 64-pixel temporal group.
    ///
    /// @param position the current tile-relative block position
    /// @param size the current block size
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param globalMotionVector0 the primary block global-motion vector
    /// @param destination the destination candidate array
    /// @param count the number of active candidates already stored
    /// @param initialGlobalMotionContext the initial temporal `globalmv` context
    /// @return the updated candidate count and temporal `globalmv` context
    private TemporalScanResult scanTemporalMotionField(
            BlockPosition position,
            BlockSize size,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            MotionVector globalMotionVector0,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count,
            int initialGlobalMotionContext
    ) {
        if (!referenceMotionVectorProjection.enabled()) {
            return new TemporalScanResult(count, 0);
        }

        int x4 = position.x4();
        int y4 = position.y4();
        int width4 = Math.min(Math.min(size.width4(), 16), tileWidth4 - x4);
        int height4 = Math.min(Math.min(size.height4(), 16), tileHeight4 - y4);
        int width8 = Math.min((width4 + 1) >> 1, 8);
        int height8 = Math.min((height4 + 1) >> 1, 8);
        int stepX8 = size.width4() >= 16 ? 2 : 1;
        int stepY8 = size.height4() >= 16 ? 2 : 1;
        int originX8 = tileStartX8 + position.x8();
        int originY8 = tileStartY8 + position.y8();
        int globalMotionContext = initialGlobalMotionContext;
        for (int y8 = 0; y8 < height8; y8 += stepY8) {
            for (int x8 = 0; x8 < width8; x8 += stepX8) {
                MotionVector motionVector0 = referenceMotionVectorProjection.motionVectorAt(
                        originX8 + x8,
                        originY8 + y8,
                        referenceFrame0
                );
                if (motionVector0 == null) {
                    continue;
                }
                if (x8 == 0 && y8 == 0) {
                    globalMotionContext = differsFromGlobalMotion(motionVector0, globalMotionVector0) ? 1 : 0;
                }
                count = appendTemporalCandidate(
                        destination,
                        count,
                        motionVector0,
                        compoundReference
                                ? referenceMotionVectorProjection.motionVectorAt(
                                originX8 + x8,
                                originY8 + y8,
                                referenceFrame1
                        )
                                : null,
                        compoundReference
                );
            }
        }

        if (Math.min(size.width4(), size.height4()) >= 2
                && Math.max(size.width4(), size.height4()) < 16) {
            int localX8 = position.x8();
            int localY8 = position.y8();
            int blockWidth8 = size.width4() >> 1;
            int blockHeight8 = size.height4() >> 1;
            int tileEndX8 = tileWidth4 >> 1;
            int tileEndY8 = tileHeight4 >> 1;
            int bottomY8 = localY8 + blockHeight8;
            boolean hasBottom = bottomY8 < Math.min(tileEndY8, (localY8 & ~7) + 8);
            if (hasBottom && localX8 - 1 >= Math.max(0, localX8 & ~7)) {
                count = appendTemporalCandidateAt(
                        destination,
                        count,
                        tileStartX8 + localX8 - 1,
                        tileStartY8 + bottomY8,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1
                );
            }
            int rightX8 = localX8 + blockWidth8;
            if (rightX8 < Math.min(tileEndX8, (localX8 & ~7) + 8)) {
                if (hasBottom) {
                    count = appendTemporalCandidateAt(
                            destination,
                            count,
                            tileStartX8 + rightX8,
                            tileStartY8 + bottomY8,
                            compoundReference,
                            referenceFrame0,
                            referenceFrame1
                    );
                }
                if (localY8 + blockHeight8 - 1 < Math.min(tileEndY8, (localY8 & ~7) + 8)) {
                    count = appendTemporalCandidateAt(
                            destination,
                            count,
                            tileStartX8 + rightX8,
                            tileStartY8 + localY8 + blockHeight8 - 1,
                            compoundReference,
                            referenceFrame0,
                            referenceFrame1
                    );
                }
            }
        }
        return new TemporalScanResult(count, globalMotionContext);
    }

    /// Appends one projected temporal candidate read at a frame-relative coordinate.
    ///
    /// @param destination the destination candidate array
    /// @param count the number of active candidates already stored
    /// @param x8 the frame-relative X coordinate in 8x8 units
    /// @param y8 the frame-relative Y coordinate in 8x8 units
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @return the updated candidate count
    private int appendTemporalCandidateAt(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count,
            int x8,
            int y8,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1
    ) {
        MotionVector motionVector0 = referenceMotionVectorProjection.motionVectorAt(x8, y8, referenceFrame0);
        if (motionVector0 == null) {
            return count;
        }
        return appendTemporalCandidate(
                destination,
                count,
                motionVector0,
                compoundReference ? referenceMotionVectorProjection.motionVectorAt(x8, y8, referenceFrame1) : null,
                compoundReference
        );
    }

    /// Appends or reweights one temporal motion-vector candidate.
    ///
    /// @param destination the destination candidate array
    /// @param count the number of active candidates already stored
    /// @param motionVector0 the projected primary vector
    /// @param motionVector1 the projected secondary vector, or `null`
    /// @param compoundReference whether a secondary vector is required
    /// @return the updated candidate count
    private static int appendTemporalCandidate(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count,
            MotionVector motionVector0,
            MotionVector motionVector1,
            boolean compoundReference
    ) {
        if (compoundReference && motionVector1 == null) {
            return count;
        }
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate =
                new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                        2,
                        InterMotionVector.predicted(motionVector0),
                        compoundReference
                                ? InterMotionVector.predicted(Objects.requireNonNull(motionVector1, "motionVector1"))
                                : null,
                        false
                );
        for (int i = 0; i < count; i++) {
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate existing = destination[i];
            if (!existing.synthetic()
                    && existing.motionVector0().vector().equals(candidate.motionVector0().vector())
                    && equivalentMotionVector(existing.motionVector1(), candidate.motionVector1())) {
                existing.increaseWeight(2);
                return count;
            }
        }
        if (count < destination.length) {
            destination[count++] = candidate;
        }
        return count;
    }

    /// Returns whether two optional inter motion vectors carry equal vector values.
    ///
    /// @param left the first optional motion-vector state
    /// @param right the second optional motion-vector state
    /// @return whether both values are absent or carry equal vectors
    private static boolean equivalentMotionVector(
            InterMotionVector left,
            InterMotionVector right
    ) {
        return left == null ? right == null : right != null && left.vector().equals(right.vector());
    }

    /// Returns whether one projected vector differs materially from the block global vector.
    ///
    /// @param projected the projected temporal vector
    /// @param global the block global-motion vector
    /// @return whether either component differs by at least two luma pixels
    private static boolean differsFromGlobalMotion(MotionVector projected, MotionVector global) {
        return (Math.abs(projected.columnEighthPel() - global.columnEighthPel())
                | Math.abs(projected.rowEighthPel() - global.rowEighthPel())) >= 16;
    }

    /// Builds one provisional motion-vector candidate from one stored neighbor.
    ///
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param block the stored neighboring block
    /// @param globalMotion the current block's global-motion candidates
    /// @param weight the provisional candidate weight to assign
    /// @return one matching motion-vector candidate, or `null` when the neighbor does not carry the requested reference selection
    private static ProvisionalInterModeContext.ProvisionalMotionVectorCandidate provisionalMotionVectorCandidate(
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            StoredBlock block,
            ExactSpatialGlobalMotion globalMotion,
            int weight
    ) {
        StoredBlock nonNullBlock = Objects.requireNonNull(block, "block");
        ExactSpatialGlobalMotion nonNullGlobalMotion = Objects.requireNonNull(globalMotion, "globalMotion");
        boolean replaceGlobalMotion = nonNullBlock.usesGlobalMotionMode()
                && nonNullBlock.width4() >= 2
                && nonNullBlock.height4() >= 2;
        if (compoundReference) {
            if (!nonNullBlock.compoundReference()
                    || referenceFrame0 != nonNullBlock.referenceFrame0()
                    || referenceFrame1 != nonNullBlock.referenceFrame1()) {
                return null;
            }
            return new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                    weight,
                    replaceGlobalMotion && nonNullGlobalMotion.primaryNonTranslation()
                            ? InterMotionVector.resolved(nonNullGlobalMotion.primary())
                            : nonNullBlock.motionVector0(),
                    replaceGlobalMotion && nonNullGlobalMotion.secondaryNonTranslation()
                            ? InterMotionVector.resolved(nonNullGlobalMotion.secondary())
                            : nonNullBlock.motionVector1(),
                    false
            );
        }
        if (referenceFrame0 == nonNullBlock.referenceFrame0()) {
            return new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                    weight,
                    replaceGlobalMotion && nonNullGlobalMotion.primaryNonTranslation()
                            ? InterMotionVector.resolved(nonNullGlobalMotion.primary())
                            : nonNullBlock.motionVector0(),
                    null,
                    false
            );
        }
        if (nonNullBlock.compoundReference() && referenceFrame0 == nonNullBlock.referenceFrame1()) {
            return new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                    weight,
                    replaceGlobalMotion && nonNullGlobalMotion.primaryNonTranslation()
                            ? InterMotionVector.resolved(nonNullGlobalMotion.primary())
                            : nonNullBlock.motionVector1(),
                    null,
                    false
            );
        }
        return null;
    }

    /// Appends one provisional candidate or merges its weight into an equivalent candidate.
    ///
    /// @param destination the destination candidate array
    /// @param count the number of valid candidates currently stored in `destination`
    /// @param candidate the base candidate to append
    /// @return the updated candidate count
    private static int appendProvisionalCandidate(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate
    ) {
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullCandidate = Objects.requireNonNull(candidate, "candidate");
        for (int i = 0; i < count; i++) {
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate existing = destination[i];
            if (!existing.synthetic()
                    && existing.motionVector0().vector().equals(nonNullCandidate.motionVector0().vector())
                    && equivalentMotionVector(existing.motionVector1(), nonNullCandidate.motionVector1())) {
                existing.increaseWeight(nonNullCandidate.weight());
                return count;
            }
        }
        if (count < destination.length) {
            destination[count++] = nonNullCandidate;
        }
        return count;
    }

    /// Sorts a prefix of the supplied provisional candidate array in descending weight order.
    ///
    /// Real neighbor-derived candidates win ties over the synthetic zero baseline.
    ///
    /// @param values the provisional candidate array to sort
    /// @param start the inclusive first candidate to sort
    /// @param end the exclusive end candidate index
    private static void sortDescending(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] values,
            int start,
            int end
    ) {
        for (int i = start + 1; i < end; i++) {
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate current = values[i];
            int j = i - 1;
            while (j >= start && compareCandidates(current, values[j]) < 0) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = current;
        }
    }

    /// Compares two provisional motion-vector candidates for descending sort order.
    ///
    /// @param left the first candidate
    /// @param right the second candidate
    /// @return the comparison result used for descending sort order
    private static int compareCandidates(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate left,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate right
    ) {
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullLeft = Objects.requireNonNull(left, "left");
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullRight = Objects.requireNonNull(right, "right");
        int weightCompare = Integer.compare(nonNullRight.weight(), nonNullLeft.weight());
        if (weightCompare != 0) {
            return weightCompare;
        }
        if (nonNullLeft.synthetic() != nonNullRight.synthetic()) {
            return nonNullLeft.synthetic() ? 1 : -1;
        }
        return 0;
    }

    /// Summarizes AV1 spatial-match counts into `refmvs` syntax contexts.
    ///
    /// This summary mirrors `dav1d_refmvs_find()`'s `nearest_match` / `ref_match_count` /
    /// `have_newmv` handling. Projected temporal candidates do not contribute to these spatial
    /// match counts.
    ///
    /// @param nearestMatchCount the number of direct top/left matches in `[0, 2]`
    /// @param referenceMatchCount the number of spatial row/column match groups in `[0, 2]`
    /// @param haveNewMotionVectorMatch whether any matching spatial neighbor used a `NEWMV`-carrying mode
    /// @return the summarized AV1 `refmvs` syntax contexts
    private static RefMvsContextSummary summarizeDirectRefMvsContexts(
            int nearestMatchCount,
            int referenceMatchCount,
            boolean haveNewMotionVectorMatch
    ) {
        int refmvContext;
        int newmvContext;
        switch (nearestMatchCount) {
            case 0 -> {
                refmvContext = Math.min(2, referenceMatchCount);
                newmvContext = referenceMatchCount > 0 ? 1 : 0;
            }
            case 1 -> {
                refmvContext = Math.min(referenceMatchCount * 3, 4);
                newmvContext = 3 - (haveNewMotionVectorMatch ? 1 : 0);
            }
            default -> {
                refmvContext = 5;
                newmvContext = 5 - (haveNewMotionVectorMatch ? 1 : 0);
            }
        }

        int compoundInterModeContext = switch (refmvContext >> 1) {
            case 0 -> Math.min(newmvContext, 1);
            case 1 -> 1 + Math.min(newmvContext, 3);
            default -> Math.max(4, Math.min(7, 3 + newmvContext));
        };
        return new RefMvsContextSummary(newmvContext, refmvContext, compoundInterModeContext);
    }

    /// Updates the partition edge state after a non-deferred partition decision.
    ///
    /// @param position the current square block position
    /// @param span8 the edge span to update in 8x8 units
    /// @param aboveValue the value to store on the above edge
    /// @param leftValue the value to store on the left edge
    public void updatePartition(BlockPosition position, int span8, int aboveValue, int leftValue) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int startX8 = nonNullPosition.x8();
        int startY8 = nonNullPosition.y8();
        int endX8 = Math.min(abovePartition.length, startX8 + span8);
        int endY8 = Math.min(leftPartition.length, startY8 + span8);
        for (int x8 = startX8; x8 < endX8; x8++) {
            abovePartition[x8] = (byte) aboveValue;
        }
        for (int y8 = startY8; y8 < endY8; y8++) {
            leftPartition[y8] = (byte) leftValue;
        }
    }

    /// Current-block global vectors used when exact spatial candidates came from global-warp blocks.
    ///
    /// @param primary the primary current-block global-motion vector
    /// @param secondary the secondary current-block global-motion vector
    /// @param primaryNonTranslation whether the primary global model varies with block position
    /// @param secondaryNonTranslation whether the secondary global model varies with block position
    
    private record ExactSpatialGlobalMotion(
            MotionVector primary,
            MotionVector secondary,
            boolean primaryNonTranslation,
            boolean secondaryNonTranslation
    ) {
        /// Validates the global-motion vectors.
        private ExactSpatialGlobalMotion {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(secondary, "secondary");
        }
    }

    /// An inter-mode syntax context derived from spatial and projected temporal candidates.
    
    public static final class ProvisionalInterModeContext {
        /// The zero-based provisional `newmv` context index in `[0, 6)`.
        private final int singleNewMvContext;

        /// The zero-based temporal `globalmv` context index in `[0, 2)`.
        private final int singleGlobalMvContext;

        /// The zero-based provisional `refmv` context index in `[0, 6)`.
        private final int singleReferenceMvContext;

        /// The zero-based provisional compound inter-mode context index in `[0, 8)`.
        private final int compoundInterModeContext;

        /// The number of candidates reported to dynamic-reference-list syntax.
        private final int syntaxCandidateCount;

        /// The provisional motion-vector candidates sorted in descending weight order.
        private final ProvisionalMotionVectorCandidate [] candidates;

        /// Creates one provisional inter-mode syntax context.
        ///
        /// @param singleNewMvContext the zero-based provisional `newmv` context index in `[0, 6)`
        /// @param singleGlobalMvContext the zero-based temporal `globalmv` context index in `[0, 2)`
        /// @param singleReferenceMvContext the zero-based provisional `refmv` context index in `[0, 6)`
        /// @param compoundInterModeContext the zero-based provisional compound inter-mode context index in `[0, 8)`
        /// @param syntaxCandidateCount the number of candidates visible to dynamic-reference-list syntax
        /// @param candidates the provisional motion-vector candidates sorted in descending weight order
        public ProvisionalInterModeContext(
                int singleNewMvContext,
                int singleGlobalMvContext,
                int singleReferenceMvContext,
                int compoundInterModeContext,
                int syntaxCandidateCount,
                ProvisionalMotionVectorCandidate[] candidates
        ) {
            this(
                    singleNewMvContext,
                    singleGlobalMvContext,
                    singleReferenceMvContext,
                    compoundInterModeContext,
                    syntaxCandidateCount,
                    candidates,
                    false
            );
        }

        /// Creates one provisional context that owns an exact candidate snapshot.
        ///
        /// @param singleNewMvContext the zero-based provisional `newmv` context index in `[0, 6)`
        /// @param singleGlobalMvContext the zero-based temporal `globalmv` context index in `[0, 2)`
        /// @param singleReferenceMvContext the zero-based provisional `refmv` context index in `[0, 6)`
        /// @param compoundInterModeContext the zero-based provisional compound inter-mode context index in `[0, 8)`
        /// @param syntaxCandidateCount the number of candidates visible to dynamic-reference-list syntax
        /// @param candidates the exclusively owned exact candidate snapshot
        /// @return one provisional inter-mode context backed by `candidates`
        private static ProvisionalInterModeContext fromOwnedCandidates(
                int singleNewMvContext,
                int singleGlobalMvContext,
                int singleReferenceMvContext,
                int compoundInterModeContext,
                int syntaxCandidateCount,
                ProvisionalMotionVectorCandidate[] candidates
        ) {
            return new ProvisionalInterModeContext(
                    singleNewMvContext,
                    singleGlobalMvContext,
                    singleReferenceMvContext,
                    compoundInterModeContext,
                    syntaxCandidateCount,
                    candidates,
                    true
            );
        }

        /// Creates one provisional inter-mode context with configurable candidate ownership.
        ///
        /// @param singleNewMvContext the zero-based provisional `newmv` context index in `[0, 6)`
        /// @param singleGlobalMvContext the zero-based temporal `globalmv` context index in `[0, 2)`
        /// @param singleReferenceMvContext the zero-based provisional `refmv` context index in `[0, 6)`
        /// @param compoundInterModeContext the zero-based provisional compound inter-mode context index in `[0, 8)`
        /// @param syntaxCandidateCount the number of candidates visible to dynamic-reference-list syntax
        /// @param candidates the provisional motion-vector candidates sorted in descending weight order
        /// @param takeOwnership whether `candidates` is an exclusively owned exact snapshot
        private ProvisionalInterModeContext(
                int singleNewMvContext,
                int singleGlobalMvContext,
                int singleReferenceMvContext,
                int compoundInterModeContext,
                int syntaxCandidateCount,
                ProvisionalMotionVectorCandidate[] candidates,
                boolean takeOwnership
        ) {
            this.singleNewMvContext = singleNewMvContext;
            if (singleGlobalMvContext < 0 || singleGlobalMvContext > 1) {
                throw new IllegalArgumentException("singleGlobalMvContext out of range: " + singleGlobalMvContext);
            }
            this.singleGlobalMvContext = singleGlobalMvContext;
            this.singleReferenceMvContext = singleReferenceMvContext;
            this.compoundInterModeContext = compoundInterModeContext;
            ProvisionalMotionVectorCandidate[] checkedCandidates =
                    Objects.requireNonNull(candidates, "candidates");
            this.candidates = takeOwnership
                    ? checkedCandidates
                    : Arrays.copyOf(checkedCandidates, checkedCandidates.length);
            if (syntaxCandidateCount < 0 || syntaxCandidateCount > this.candidates.length) {
                throw new IllegalArgumentException("syntaxCandidateCount out of range: " + syntaxCandidateCount);
            }
            this.syntaxCandidateCount = syntaxCandidateCount;
        }

        /// Returns the zero-based provisional `newmv` context index in `[0, 6)`.
        ///
        /// @return the zero-based provisional `newmv` context index in `[0, 6)`
        public int singleNewMvContext() {
            return singleNewMvContext;
        }

        /// Returns the temporal `globalmv` context.
        ///
        /// @return the zero-based temporal `globalmv` context index in `[0, 2)`
        public int singleGlobalMvContext() {
            return singleGlobalMvContext;
        }

        /// Returns the zero-based provisional `refmv` context index in `[0, 6)`.
        ///
        /// @return the zero-based provisional `refmv` context index in `[0, 6)`
        public int singleReferenceMvContext() {
            return singleReferenceMvContext;
        }

        /// Returns the zero-based provisional compound inter-mode context index in `[0, 8)`.
        ///
        /// @return the zero-based provisional compound inter-mode context index in `[0, 8)`
        public int compoundInterModeContext() {
            return compoundInterModeContext;
        }

        /// Returns the number of candidates visible to dynamic-reference-list syntax.
        ///
        /// Single-reference global-motion fallback slots remain addressable through
        /// [#motionVectorCandidate(int)] but are not included in this count.
        ///
        /// @return the number of candidates visible to dynamic-reference-list syntax
        public int candidateCount() {
            return syntaxCandidateCount;
        }

        /// Returns one provisional candidate weight by index.
        ///
        /// @param index the zero-based candidate index
        /// @return one provisional candidate weight by index
        public int candidateWeight(int index) {
            return candidate(Objects.checkIndex(index, candidates.length)).weight();
        }

        /// Returns one provisional primary motion vector by index.
        ///
        /// @param index the zero-based candidate index
        /// @return one provisional primary motion vector by index
        public InterMotionVector candidateMotionVector0(int index) {
            return candidate(Objects.checkIndex(index, candidates.length)).motionVector0();
        }

        /// Returns one provisional secondary motion vector by index, or `null`.
        ///
        /// @param index the zero-based candidate index
        /// @return one provisional secondary motion vector by index, or `null`
        public InterMotionVector candidateMotionVector1(int index) {
            return candidate(Objects.checkIndex(index, candidates.length)).motionVector1();
        }

        /// Returns the provisional dynamic-reference-list context for one candidate boundary.
        ///
        /// This follows `dav1d`'s threshold rule over the candidate-stack weights.
        ///
        /// @param referenceIndex the zero-based candidate boundary index
        /// @return the zero-based provisional dynamic-reference-list context in `[0, 3)`
        public int drlContext(int referenceIndex) {
            int index = Objects.checkIndex(referenceIndex + 1, candidates.length) - 1;
            if (candidates[index].weight() >= 640) {
                return candidates[index + 1].weight() < 640 ? 1 : 0;
            }
            return candidates[index + 1].weight() < 640 ? 2 : 0;
        }

        /// Returns one stored provisional motion-vector candidate by index.
        ///
        /// @param index the zero-based candidate index
        /// @return one stored provisional motion-vector candidate by index
        public ProvisionalMotionVectorCandidate candidate(int index) {
            return candidates[Objects.checkIndex(index, candidates.length)];
        }

        /// Returns the number of de-duplicated spatial motion-vector candidates currently available.
        ///
        /// The returned stack keeps real neighbor-derived candidates first and appends at most one
        /// synthetic zero fallback at the end.
        ///
        /// @return the number of de-duplicated spatial motion-vector candidates currently available
        public int motionVectorCandidateCount() {
            return candidates.length;
        }

        /// Returns one de-duplicated spatial motion-vector candidate by index.
        ///
        /// @param index the zero-based spatial motion-vector candidate index
        /// @return one de-duplicated spatial motion-vector candidate by index
        public ProvisionalMotionVectorCandidate motionVectorCandidate(int index) {
            return candidates[Objects.checkIndex(index, candidates.length)];
        }

        /// One provisional motion-vector candidate derived from one bounded neighbor source.
        
        public static final class ProvisionalMotionVectorCandidate {
            /// The candidate weight, mutable only while the owning context is being assembled.
            private int weight;

            /// The primary provisional motion vector carried by this candidate.
            private final InterMotionVector motionVector0;

            /// The secondary provisional motion vector carried by this candidate, or `null`.
            private final InterMotionVector motionVector1;

            /// Whether this candidate is the synthetic zero baseline instead of a real neighbor.
            private final boolean synthetic;

            /// Creates one provisional motion-vector candidate.
            ///
            /// @param weight the candidate weight used for DRL-context derivation
            /// @param motionVector0 the primary provisional motion vector carried by this candidate
            /// @param motionVector1 the secondary provisional motion vector carried by this candidate, or `null`
            /// @param synthetic whether this candidate is the synthetic zero baseline
            private ProvisionalMotionVectorCandidate(
                    int weight,
                    InterMotionVector motionVector0,
                    InterMotionVector motionVector1,
                    boolean synthetic
            ) {
                this.weight = weight;
                this.motionVector0 = Objects.requireNonNull(motionVector0, "motionVector0");
                this.motionVector1 = motionVector1;
                this.synthetic = synthetic;
            }

            /// Returns the candidate weight used for DRL-context derivation.
            ///
            /// @return the candidate weight used for DRL-context derivation
            public int weight() {
                return weight;
            }

            /// Returns the primary provisional motion vector carried by this candidate.
            ///
            /// @return the primary provisional motion vector carried by this candidate
            public InterMotionVector motionVector0() {
                return motionVector0;
            }

            /// Returns the secondary provisional motion vector carried by this candidate, or `null`.
            ///
            /// @return the secondary provisional motion vector carried by this candidate, or `null`
            public InterMotionVector motionVector1() {
                return motionVector1;
            }

            /// Returns whether this candidate is the synthetic zero baseline.
            ///
            /// @return whether this candidate is the synthetic zero baseline
            public boolean synthetic() {
                return synthetic;
            }

            /// Increases this candidate's weight while its owning context is being assembled.
            ///
            /// @param increment the non-negative weight increment
            private void increaseWeight(int increment) {
                if (increment < 0) {
                    throw new IllegalArgumentException("increment < 0: " + increment);
                }
                weight += increment;
            }
        }
    }

    /// One stored decoded block reused by the bounded spatial scan.
    
    private static final class StoredBlock {
        /// The block origin X coordinate in tile-relative 4x4 units.
        private final int originX4;

        /// The block origin Y coordinate in tile-relative 4x4 units.
        private final int originY4;

        /// The block width in 4x4 units.
        private final int width4;

        /// The block height in 4x4 units.
        private final int height4;

        /// The decoded segment identifier.
        private final int segmentId;

        /// Whether the stored block is intra-coded.
        private final boolean intra;

        /// Whether the stored block uses same-frame intrabc prediction.
        private final boolean intrabc;

        /// Whether the stored block uses compound inter references.
        private final boolean compoundReference;

        /// Whether the stored block blends inter and intra prediction.
        private final boolean interIntra;

        /// The stored primary inter reference in internal LAST..ALTREF order.
        private final int referenceFrame0;

        /// The stored secondary inter reference in internal LAST..ALTREF order, or `-1`.
        private final int referenceFrame1;

        /// The stored primary motion-vector state.
        private final InterMotionVector motionVector0;

        /// The stored secondary motion-vector state.
        private final InterMotionVector motionVector1;

        /// Whether the stored block used any `NEWMV`-carrying inter mode.
        private final boolean usesNewMotionVector;

        /// Whether the stored block selected the all-global inter mode.
        private final boolean usesGlobalMotionMode;

        /// Creates one stored decoded block reused by the bounded spatial scan.
        ///
        /// @param originX4 the block origin X coordinate in tile-relative 4x4 units
        /// @param originY4 the block origin Y coordinate in tile-relative 4x4 units
        /// @param width4 the block width in 4x4 units
        /// @param height4 the block height in 4x4 units
        /// @param segmentId the decoded segment identifier
        /// @param intra whether the stored block is intra-coded
        /// @param intrabc whether the stored block uses same-frame intrabc prediction
        /// @param compoundReference whether the stored block uses compound inter references
        /// @param interIntra whether the stored block blends inter and intra prediction
        /// @param referenceFrame0 the stored primary inter reference in internal LAST..ALTREF order
        /// @param referenceFrame1 the stored secondary inter reference in internal LAST..ALTREF order, or `-1`
        /// @param motionVector0 the stored primary motion-vector state
        /// @param motionVector1 the stored secondary motion-vector state
        /// @param usesNewMotionVector whether the stored block used any `NEWMV`-carrying inter mode
        /// @param usesGlobalMotionMode whether the stored block selected the all-global inter mode
        private StoredBlock(
                int originX4,
                int originY4,
                int width4,
                int height4,
                int segmentId,
                boolean intra,
                boolean intrabc,
                boolean compoundReference,
                boolean interIntra,
                int referenceFrame0,
                int referenceFrame1,
                InterMotionVector motionVector0,
                InterMotionVector motionVector1,
                boolean usesNewMotionVector,
                boolean usesGlobalMotionMode
        ) {
            this.originX4 = originX4;
            this.originY4 = originY4;
            this.width4 = width4;
            this.height4 = height4;
            this.segmentId = segmentId;
            this.intra = intra;
            this.intrabc = intrabc;
            this.compoundReference = compoundReference;
            this.interIntra = interIntra;
            this.referenceFrame0 = referenceFrame0;
            this.referenceFrame1 = referenceFrame1;
            this.motionVector0 = Objects.requireNonNull(motionVector0, "motionVector0");
            this.motionVector1 = Objects.requireNonNull(motionVector1, "motionVector1");
            this.usesNewMotionVector = usesNewMotionVector;
            this.usesGlobalMotionMode = usesGlobalMotionMode;
        }

        /// Returns the block origin X coordinate in tile-relative 4x4 units.
        ///
        /// @return the block origin X coordinate in tile-relative 4x4 units
        public int originX4() {
            return originX4;
        }

        /// Returns the block origin Y coordinate in tile-relative 4x4 units.
        ///
        /// @return the block origin Y coordinate in tile-relative 4x4 units
        public int originY4() {
            return originY4;
        }

        /// Returns the block width in 4x4 units.
        ///
        /// @return the block width in 4x4 units
        public int width4() {
            return width4;
        }

        /// Returns the block height in 4x4 units.
        ///
        /// @return the block height in 4x4 units
        public int height4() {
            return height4;
        }

        /// Returns whether the stored block is intra-coded.
        ///
        /// @return whether the stored block is intra-coded
        public boolean intra() {
            return intra;
        }

        /// Returns whether the stored block uses same-frame intrabc prediction.
        ///
        /// @return whether the stored block uses same-frame intrabc prediction
        public boolean intrabc() {
            return intrabc;
        }

        /// Returns whether the stored block uses compound inter references.
        ///
        /// @return whether the stored block uses compound inter references
        public boolean compoundReference() {
            return compoundReference;
        }

        /// Returns whether the stored block blends inter and intra prediction.
        ///
        /// @return whether the stored block blends inter and intra prediction
        public boolean interIntra() {
            return interIntra;
        }

        /// Returns the stored primary inter reference in internal LAST..ALTREF order.
        ///
        /// @return the stored primary inter reference in internal LAST..ALTREF order
        public int referenceFrame0() {
            return referenceFrame0;
        }

        /// Returns the stored secondary inter reference in internal LAST..ALTREF order, or `-1`.
        ///
        /// @return the stored secondary inter reference in internal LAST..ALTREF order, or `-1`
        public int referenceFrame1() {
            return referenceFrame1;
        }

        /// Returns the stored primary motion-vector state.
        ///
        /// @return the stored primary motion-vector state
        public InterMotionVector motionVector0() {
            return motionVector0;
        }

        /// Returns the stored secondary motion-vector state.
        ///
        /// @return the stored secondary motion-vector state
        public InterMotionVector motionVector1() {
            return motionVector1;
        }

        /// Returns whether the stored block used any `NEWMV`-carrying inter mode.
        ///
        /// @return whether the stored block used any `NEWMV`-carrying inter mode
        public boolean usesNewMotionVector() {
            return usesNewMotionVector;
        }

        /// Returns whether the stored block selected the all-global inter mode.
        ///
        /// @return whether the stored block selected the all-global inter mode
        public boolean usesGlobalMotionMode() {
            return usesGlobalMotionMode;
        }
    }

    /// The result of one dimension-aware AV1 spatial row or column scan.
    ///
    /// @param candidateCount the updated number of valid candidates
    /// @param referenceMatch whether the scan found the requested reference selection
    /// @param haveNewMotionVectorMatch whether a matching block used a `NEWMV`-carrying mode
    /// @param scanDistance the spatial-depth contribution returned by the scan
    
    private record ExactSpatialScanResult(
            int candidateCount,
            boolean referenceMatch,
            boolean haveNewMotionVectorMatch,
            int scanDistance
    ) {
    }

    /// The result of sampling the current block's projected temporal motion field.
    ///
    /// @param candidateCount the updated number of valid weighted candidates
    /// @param globalMotionContext the temporal `globalmv` context in `[0, 2)`
    
    private record TemporalScanResult(int candidateCount, int globalMotionContext) {
    }

    /// AV1 `refmvs` syntax contexts derived from the scanned neighbors.
    ///
    /// @param singleNewMvContext the zero-based `newmv` context index in `[0, 6)`
    /// @param singleReferenceMvContext the zero-based `refmv` context index in `[0, 6)`
    /// @param compoundInterModeContext the zero-based compound inter-mode context index in `[0, 8)`
    
    private record RefMvsContextSummary(
            int singleNewMvContext,
            int singleReferenceMvContext,
            int compoundInterModeContext
    ) {
    }

    /// The current-frame segment prediction for one block position.
    ///
    /// @param predictedSegmentId the predicted segment identifier derived from already-decoded neighbors
    /// @param context the zero-based segment-id context derived from already-decoded neighbors
    
    public record SegmentPrediction(int predictedSegmentId, int context) {
    }
}
