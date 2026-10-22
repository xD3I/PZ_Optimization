// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.entropy.CdfContext;
import pzopt.vendor.avif.internal.av1.entropy.MsacDecoder;
import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.InterMotionVector;
import pzopt.vendor.avif.internal.av1.model.SequenceHeader;
import pzopt.vendor.avif.internal.av1.model.TileBitstream;

import java.util.Objects;

/// Tile-local decode state derived from a fully assembled frame and one collected tile bitstream.
///
/// Pixel-space end coordinates are exclusive.

public final class TileDecodeContext {
    /// The frame assembly that owns the tile.
    private final FrameAssembly assembly;

    /// The active sequence header for the tile.
    private final SequenceHeader sequenceHeader;

    /// The active frame header for the tile.
    private final FrameHeader frameHeader;

    /// The selected tile bitstream.
    private final TileBitstream tileBitstream;

    /// The tile-local arithmetic decoder.
    private final MsacDecoder msacDecoder;

    /// The tile-local mutable CDF context.
    private final CdfContext cdfContext;

    /// The tile-local temporal motion field being produced while decoding the current frame.
    private final TemporalMotionField decodedTemporalMotionField;

    /// The immutable reference-frame motion-vector projection shared by the frame's tiles.
    private final ReferenceMotionVectorProjection referenceMotionVectorProjection;

    /// The mutable current-frame segment-id map shared by the frame's tiles.
    private final SegmentIdMap currentSegmentIdMap;

    /// The immutable primary-reference segment-id map, or `null` when none is compatible.
    private final SegmentIdMap referenceSegmentIdMap;

    /// The mutable tile-local block syntax state shared across superblocks.
    private final BlockSyntaxState blockSyntaxState;

    /// The tile-local loop-restoration units decoded from this tile.
    private final RestorationUnitMap restorationUnitMap;

    /// The zero-based tile index within the frame.
    private final int tileIndex;

    /// The zero-based tile row within the frame.
    private final int tileRow;

    /// The zero-based tile column within the frame.
    private final int tileColumn;

    /// The superblock size in pixels for the active sequence.
    private final int superblockSize;

    /// The inclusive start superblock column for the tile.
    private final int columnStartSuperblock;

    /// The exclusive end superblock column for the tile.
    private final int columnEndSuperblock;

    /// The inclusive start superblock row for the tile.
    private final int rowStartSuperblock;

    /// The exclusive end superblock row for the tile.
    private final int rowEndSuperblock;

    /// The inclusive start X coordinate in pixels.
    private final int startX;

    /// The exclusive end X coordinate in pixels.
    private final int endX;

    /// The inclusive start Y coordinate in pixels.
    private final int startY;

    /// The exclusive end Y coordinate in pixels.
    private final int endY;

    /// The tile width in AV1 4x4 coding units after 8x8 frame-grid rounding.
    private final int codedWidth4;

    /// The tile height in AV1 4x4 coding units after 8x8 frame-grid rounding.
    private final int codedHeight4;

    /// Creates tile-local decode state.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param sequenceHeader the active sequence header for the tile
    /// @param frameHeader the active frame header for the tile
    /// @param tileBitstream the selected tile bitstream
    /// @param msacDecoder the tile-local arithmetic decoder
    /// @param cdfContext the tile-local mutable CDF context
    /// @param decodedTemporalMotionField the tile-local temporal motion field produced while decoding the current frame
    /// @param referenceMotionVectorProjection the immutable reference-frame motion-vector projection
    /// @param currentSegmentIdMap the mutable current-frame segment-id map
    /// @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
    /// @param blockSyntaxState the mutable tile-local block syntax state shared across superblocks
    /// @param restorationUnitMap the tile-local loop-restoration units decoded from this tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param tileRow the zero-based tile row within the frame
    /// @param tileColumn the zero-based tile column within the frame
    /// @param superblockSize the superblock size in pixels
    /// @param columnStartSuperblock the inclusive start superblock column
    /// @param columnEndSuperblock the exclusive end superblock column
    /// @param rowStartSuperblock the inclusive start superblock row
    /// @param rowEndSuperblock the exclusive end superblock row
    /// @param startX the inclusive start X coordinate in pixels
    /// @param endX the exclusive end X coordinate in pixels
    /// @param startY the inclusive start Y coordinate in pixels
    /// @param endY the exclusive end Y coordinate in pixels
    /// @param codedWidth4 the tile width in AV1 4x4 coding units after 8x8 frame-grid rounding
    /// @param codedHeight4 the tile height in AV1 4x4 coding units after 8x8 frame-grid rounding
    private TileDecodeContext(
            FrameAssembly assembly,
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            TileBitstream tileBitstream,
            MsacDecoder msacDecoder,
            CdfContext cdfContext,
            TemporalMotionField decodedTemporalMotionField,
            ReferenceMotionVectorProjection referenceMotionVectorProjection,
            SegmentIdMap currentSegmentIdMap,
            SegmentIdMap referenceSegmentIdMap,
            BlockSyntaxState blockSyntaxState,
            RestorationUnitMap restorationUnitMap,
            int tileIndex,
            int tileRow,
            int tileColumn,
            int superblockSize,
            int columnStartSuperblock,
            int columnEndSuperblock,
            int rowStartSuperblock,
            int rowEndSuperblock,
            int startX,
            int endX,
            int startY,
            int endY,
            int codedWidth4,
            int codedHeight4
    ) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.sequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        this.tileBitstream = Objects.requireNonNull(tileBitstream, "tileBitstream");
        this.msacDecoder = Objects.requireNonNull(msacDecoder, "msacDecoder");
        this.cdfContext = Objects.requireNonNull(cdfContext, "cdfContext");
        this.decodedTemporalMotionField = Objects.requireNonNull(decodedTemporalMotionField, "decodedTemporalMotionField");
        this.referenceMotionVectorProjection = Objects.requireNonNull(
                referenceMotionVectorProjection,
                "referenceMotionVectorProjection"
        );
        this.currentSegmentIdMap = Objects.requireNonNull(currentSegmentIdMap, "currentSegmentIdMap");
        this.referenceSegmentIdMap = referenceSegmentIdMap;
        this.blockSyntaxState = Objects.requireNonNull(blockSyntaxState, "blockSyntaxState");
        this.restorationUnitMap = Objects.requireNonNull(restorationUnitMap, "restorationUnitMap");
        this.tileIndex = tileIndex;
        this.tileRow = tileRow;
        this.tileColumn = tileColumn;
        this.superblockSize = superblockSize;
        this.columnStartSuperblock = columnStartSuperblock;
        this.columnEndSuperblock = columnEndSuperblock;
        this.rowStartSuperblock = rowStartSuperblock;
        this.rowEndSuperblock = rowEndSuperblock;
        this.startX = startX;
        this.endX = endX;
        this.startY = startY;
        this.endY = endY;
        this.codedWidth4 = codedWidth4;
        this.codedHeight4 = codedHeight4;
    }

    /// Creates tile-local decode state with a fresh default CDF context.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @return tile-local decode state for the selected tile
    public static TileDecodeContext create(FrameAssembly assembly, int tileIndex) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        return create(
                nonNullAssembly,
                tileIndex,
                CdfContext.createDefault(nonNullAssembly.frameHeader().quantization().baseQIndex())
        );
    }

    /// Creates tile-local decode state with a copy of the supplied base CDF context.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param baseCdfContext the base CDF context template to copy for this tile
    /// @return tile-local decode state for the selected tile
    public static TileDecodeContext create(FrameAssembly assembly, int tileIndex, CdfContext baseCdfContext) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        return create(
                nonNullAssembly,
                tileIndex,
                baseCdfContext,
                ReferenceMotionVectorProjection.create(nonNullAssembly, new ReferenceFrameSyntaxState[8]),
                SegmentIdMap.create(nonNullAssembly),
                null
        );
    }

    /// Creates tile-local decode state with supplied entropy and temporal-projection state.
    ///
    /// The CDF context is copied for the tile. The immutable projection is shared without copying.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param baseCdfContext the base CDF context template to copy for this tile
    /// @param referenceMotionVectorProjection the immutable current-frame temporal projection
    /// @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
    /// @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
    /// @return tile-local decode state for the selected tile
    static TileDecodeContext create(
            FrameAssembly assembly,
            int tileIndex,
            CdfContext baseCdfContext,
            ReferenceMotionVectorProjection referenceMotionVectorProjection,
            SegmentIdMap currentSegmentIdMap,
            SegmentIdMap referenceSegmentIdMap
    ) {
        return create(
                assembly,
                tileIndex,
                baseCdfContext,
                referenceMotionVectorProjection,
                currentSegmentIdMap,
                referenceSegmentIdMap,
                true
        );
    }

    /// Creates tile-local decode state by taking ownership of a fresh CDF context.
    ///
    /// The caller must not access or modify `ownedCdfContext` after this method returns. The
    /// immutable projection is shared, and the segment-id maps retain their existing ownership.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param ownedCdfContext the fresh CDF context transferred to this tile
    /// @param referenceMotionVectorProjection the immutable current-frame temporal projection
    /// @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
    /// @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
    /// @return tile-local decode state for the selected tile
    static TileDecodeContext createWithOwnedCdfContext(
            FrameAssembly assembly,
            int tileIndex,
            CdfContext ownedCdfContext,
            ReferenceMotionVectorProjection referenceMotionVectorProjection,
            SegmentIdMap currentSegmentIdMap,
            SegmentIdMap referenceSegmentIdMap
    ) {
        return create(
                assembly,
                tileIndex,
                ownedCdfContext,
                referenceMotionVectorProjection,
                currentSegmentIdMap,
                referenceSegmentIdMap,
                false
        );
    }

    /// Creates tile-local decode state with either copied or transferred CDF storage.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param baseCdfContext the base or transferred CDF context
    /// @param referenceMotionVectorProjection the immutable current-frame temporal projection
    /// @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
    /// @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
    /// @param copyCdfContext whether to copy the supplied CDF context
    /// @return tile-local decode state for the selected tile
    private static TileDecodeContext create(
            FrameAssembly assembly,
            int tileIndex,
            CdfContext baseCdfContext,
            ReferenceMotionVectorProjection referenceMotionVectorProjection,
            SegmentIdMap currentSegmentIdMap,
            SegmentIdMap referenceSegmentIdMap,
            boolean copyCdfContext
    ) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        CdfContext checkedCdfContext = Objects.requireNonNull(baseCdfContext, "baseCdfContext");
        CdfContext tileCdfContext = copyCdfContext ? checkedCdfContext.copy() : checkedCdfContext;
        ReferenceMotionVectorProjection nonNullReferenceMotionVectorProjection = Objects.requireNonNull(
                referenceMotionVectorProjection,
                "referenceMotionVectorProjection"
        );
        SegmentIdMap nonNullCurrentSegmentIdMap = Objects.requireNonNull(currentSegmentIdMap, "currentSegmentIdMap");
        TileBitstream tileBitstream = nonNullAssembly.tileBitstream(tileIndex);
        SequenceHeader sequenceHeader = nonNullAssembly.sequenceHeader();
        FrameHeader frameHeader = nonNullAssembly.frameHeader();
        FrameHeader.TilingInfo tiling = frameHeader.tiling();

        int columns = tiling.columns();
        int tileRow = tileIndex / columns;
        int tileColumn = tileIndex % columns;
        int columnStartSuperblock = tiling.columnStartSuperblock(tileColumn);
        int columnEndSuperblock = tiling.columnStartSuperblock(tileColumn + 1);
        int rowStartSuperblock = tiling.rowStartSuperblock(tileRow);
        int rowEndSuperblock = tiling.rowStartSuperblock(tileRow + 1);
        int superblockSize = sequenceHeader.features().use128x128Superblocks() ? 128 : 64;
        int startX = columnStartSuperblock * superblockSize;
        int endX = Math.min(frameHeader.frameSize().codedWidth(), columnEndSuperblock * superblockSize);
        int startY = rowStartSuperblock * superblockSize;
        int endY = Math.min(frameHeader.frameSize().height(), rowEndSuperblock * superblockSize);
        int superblockSize4 = superblockSize >> 2;
        int frameWidth4 = ((frameHeader.frameSize().codedWidth() + 7) >> 3) << 1;
        int frameHeight4 = ((frameHeader.frameSize().height() + 7) >> 3) << 1;
        int startX4 = columnStartSuperblock * superblockSize4;
        int endX4 = Math.min(frameWidth4, columnEndSuperblock * superblockSize4);
        int startY4 = rowStartSuperblock * superblockSize4;
        int endY4 = Math.min(frameHeight4, rowEndSuperblock * superblockSize4);
        int codedWidth4 = endX4 - startX4;
        int codedHeight4 = endY4 - startY4;
        int width8 = (codedWidth4 + 1) >> 1;
        int height8 = (codedHeight4 + 1) >> 1;

        return new TileDecodeContext(
                nonNullAssembly,
                sequenceHeader,
                frameHeader,
                tileBitstream,
                tileBitstream.openMsacDecoder(frameHeader.disableCdfUpdate()),
                tileCdfContext,
                new TemporalMotionField(width8, height8),
                nonNullReferenceMotionVectorProjection,
                nonNullCurrentSegmentIdMap,
                referenceSegmentIdMap,
                new BlockSyntaxState(frameHeader.quantization().baseQIndex()),
                RestorationUnitMap.createEmpty(nonNullAssembly),
                tileIndex,
                tileRow,
                tileColumn,
                superblockSize,
                columnStartSuperblock,
                columnEndSuperblock,
                rowStartSuperblock,
                rowEndSuperblock,
                startX,
                endX,
                startY,
                endY,
                codedWidth4,
                codedHeight4
        );
    }

    /// Returns the frame assembly that owns the tile.
    ///
    /// @return the frame assembly that owns the tile
    public FrameAssembly assembly() {
        return assembly;
    }

    /// Returns the active sequence header for the tile.
    ///
    /// @return the active sequence header for the tile
    public SequenceHeader sequenceHeader() {
        return sequenceHeader;
    }

    /// Returns the active frame header for the tile.
    ///
    /// @return the active frame header for the tile
    public FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns the refreshed reference-frame header for one internal LAST..ALTREF reference index.
    ///
    /// @param referenceFrame the internal LAST..ALTREF reference index
    /// @return the refreshed reference-frame header for the supplied reference, or `null`
    public FrameHeader referenceFrameHeader(int referenceFrame) {
        return assembly.referenceFrameHeader(referenceFrame);
    }

    /// Returns the selected tile bitstream.
    ///
    /// @return the selected tile bitstream
    public TileBitstream tileBitstream() {
        return tileBitstream;
    }

    /// Returns the tile-local arithmetic decoder.
    ///
    /// @return the tile-local arithmetic decoder
    public MsacDecoder msacDecoder() {
        return msacDecoder;
    }

    /// Returns the tile-local mutable CDF context.
    ///
    /// @return the tile-local mutable CDF context
    public CdfContext cdfContext() {
        return cdfContext;
    }

    /// Returns the tile-local temporal motion field produced while decoding the current frame.
    ///
    /// @return the tile-local temporal motion field produced while decoding the current frame
    public TemporalMotionField decodedTemporalMotionField() {
        return decodedTemporalMotionField;
    }

    /// Returns the immutable reference-frame motion-vector projection for the current frame.
    ///
    /// @return the immutable reference-frame motion-vector projection for the current frame
    ReferenceMotionVectorProjection referenceMotionVectorProjection() {
        return referenceMotionVectorProjection;
    }

    /// Returns the mutable current-frame segment-id map shared by all tiles.
    ///
    /// @return the current-frame segment-id map
    SegmentIdMap currentSegmentIdMap() {
        return currentSegmentIdMap;
    }

    /// Returns the minimum primary-reference segment identifier covered by one local block.
    ///
    /// A missing or dimension-incompatible primary-reference map is represented as an all-zero map.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size whose previous-frame footprint is queried
    /// @return the minimum primary-reference segment identifier, or zero when no map is available
    int referenceSegmentId(BlockPosition position, BlockSize size) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        if (referenceSegmentIdMap == null) {
            return 0;
        }
        return referenceSegmentIdMap.minimum(
                (startX >> 2) + nonNullPosition.x4(),
                (startY >> 2) + nonNullPosition.y4(),
                nonNullSize.width4(),
                nonNullSize.height4()
        );
    }

    /// Returns the mutable tile-local block syntax state shared across superblocks.
    ///
    /// @return the mutable tile-local block syntax state shared across superblocks
    public BlockSyntaxState blockSyntaxState() {
        return blockSyntaxState;
    }

    /// Returns the tile-local loop-restoration units decoded from this tile.
    ///
    /// @return the tile-local loop-restoration units decoded from this tile
    public RestorationUnitMap restorationUnitMap() {
        return restorationUnitMap;
    }

    /// Returns the zero-based tile index within the frame.
    ///
    /// @return the zero-based tile index within the frame
    public int tileIndex() {
        return tileIndex;
    }

    /// Returns the zero-based tile row within the frame.
    ///
    /// @return the zero-based tile row within the frame
    public int tileRow() {
        return tileRow;
    }

    /// Returns the zero-based tile column within the frame.
    ///
    /// @return the zero-based tile column within the frame
    public int tileColumn() {
        return tileColumn;
    }

    /// Returns the superblock size in pixels for the active sequence.
    ///
    /// @return the superblock size in pixels for the active sequence
    public int superblockSize() {
        return superblockSize;
    }

    /// Returns the inclusive start superblock column for the tile.
    ///
    /// @return the inclusive start superblock column for the tile
    public int columnStartSuperblock() {
        return columnStartSuperblock;
    }

    /// Returns the exclusive end superblock column for the tile.
    ///
    /// @return the exclusive end superblock column for the tile
    public int columnEndSuperblock() {
        return columnEndSuperblock;
    }

    /// Returns the inclusive start superblock row for the tile.
    ///
    /// @return the inclusive start superblock row for the tile
    public int rowStartSuperblock() {
        return rowStartSuperblock;
    }

    /// Returns the exclusive end superblock row for the tile.
    ///
    /// @return the exclusive end superblock row for the tile
    public int rowEndSuperblock() {
        return rowEndSuperblock;
    }

    /// Returns the inclusive start X coordinate in pixels.
    ///
    /// @return the inclusive start X coordinate in pixels
    public int startX() {
        return startX;
    }

    /// Returns the exclusive end X coordinate in pixels.
    ///
    /// @return the exclusive end X coordinate in pixels
    public int endX() {
        return endX;
    }

    /// Returns the inclusive start Y coordinate in pixels.
    ///
    /// @return the inclusive start Y coordinate in pixels
    public int startY() {
        return startY;
    }

    /// Returns the exclusive end Y coordinate in pixels.
    ///
    /// @return the exclusive end Y coordinate in pixels
    public int endY() {
        return endY;
    }

    /// Returns the tile width in pixels.
    ///
    /// @return the tile width in pixels
    public int width() {
        return endX - startX;
    }

    /// Returns the tile height in pixels.
    ///
    /// @return the tile height in pixels
    public int height() {
        return endY - startY;
    }

    /// Returns the tile width in AV1 4x4 coding units after 8x8 frame-grid rounding.
    ///
    /// @return the tile width in AV1 4x4 coding units after 8x8 frame-grid rounding
    public int codedWidth4() {
        return codedWidth4;
    }

    /// Returns the tile height in AV1 4x4 coding units after 8x8 frame-grid rounding.
    ///
    /// @return the tile height in AV1 4x4 coding units after 8x8 frame-grid rounding
    public int codedHeight4() {
        return codedHeight4;
    }

    /// A tile-local temporal motion field sampled in 8x8 units.
    
    public static final class TemporalMotionField {
        /// The tile width rounded up to 8x8 units.
        private final int width8;

        /// The tile height rounded up to 8x8 units.
        private final int height8;

        /// The temporal motion blocks indexed in tile-relative 8x8 units.
        private final TemporalMotionBlock[] blocks;

        /// Creates an empty tile-local temporal motion field.
        ///
        /// @param width8 the tile width rounded up to 8x8 units
        /// @param height8 the tile height rounded up to 8x8 units
        public TemporalMotionField(int width8, int height8) {
            if (width8 < 0) {
                throw new IllegalArgumentException("width8 < 0: " + width8);
            }
            if (height8 < 0) {
                throw new IllegalArgumentException("height8 < 0: " + height8);
            }
            this.width8 = width8;
            this.height8 = height8;
            this.blocks = new TemporalMotionBlock[width8 * height8];
        }

        /// Returns the tile width rounded up to 8x8 units.
        ///
        /// @return the tile width rounded up to 8x8 units
        public int width8() {
            return width8;
        }

        /// Returns the tile height rounded up to 8x8 units.
        ///
        /// @return the tile height rounded up to 8x8 units
        public int height8() {
            return height8;
        }

        /// Stores one temporal motion block at the supplied tile-relative 8x8 coordinate.
        ///
        /// @param x8 the tile-relative X coordinate in 8x8 units
        /// @param y8 the tile-relative Y coordinate in 8x8 units
        /// @param block the temporal motion block to store
        public void setBlock(int x8, int y8, TemporalMotionBlock block) {
            blocks[index(x8, y8)] = Objects.requireNonNull(block, "block");
        }

        /// Clears the temporal motion block stored at the supplied tile-relative 8x8 coordinate.
        ///
        /// @param x8 the tile-relative X coordinate in 8x8 units
        /// @param y8 the tile-relative Y coordinate in 8x8 units
        public void clearBlock(int x8, int y8) {
            blocks[index(x8, y8)] = null;
        }

        /// Returns the temporal motion block stored at the supplied tile-relative 8x8 coordinate, or `null`.
        ///
        /// @param x8 the tile-relative X coordinate in 8x8 units
        /// @param y8 the tile-relative Y coordinate in 8x8 units
        /// @return the temporal motion block stored at the supplied tile-relative 8x8 coordinate, or `null`
        public TemporalMotionBlock block(int x8, int y8) {
            return blocks[index(x8, y8)];
        }

        /// Returns a shallow copy of this temporal motion field.
        ///
        /// The copied field owns a new storage array while reusing immutable temporal-motion block
        /// entries.
        ///
        /// @return a shallow copy of this temporal motion field
        public TemporalMotionField copy() {
            TemporalMotionField copy = new TemporalMotionField(width8, height8);
            System.arraycopy(blocks, 0, copy.blocks, 0, blocks.length);
            return copy;
        }

        /// Returns the flat array index for one tile-relative 8x8 coordinate.
        ///
        /// @param x8 the tile-relative X coordinate in 8x8 units
        /// @param y8 the tile-relative Y coordinate in 8x8 units
        /// @return the flat array index for one tile-relative 8x8 coordinate
        private int index(int x8, int y8) {
            if (x8 < 0 || x8 >= width8) {
                throw new IndexOutOfBoundsException("x8 out of range: " + x8);
            }
            if (y8 < 0 || y8 >= height8) {
                throw new IndexOutOfBoundsException("y8 out of range: " + y8);
            }
            return y8 * width8 + x8;
        }
    }

    /// One temporal motion-field sample projected into the current tile.
    ///
    /// @param compoundReference whether the temporal sample carries compound references
    /// @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary reference frame in internal LAST..ALTREF order, or `-1`
    /// @param motionVector0 the primary temporal motion-vector state
    /// @param motionVector1 the secondary temporal motion-vector state, or `null`
    
    public record TemporalMotionBlock(
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1
    ) {
        /// Creates one single-reference temporal motion-field sample.
        ///
        /// @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
        /// @param motionVector0 the primary temporal motion-vector state
        /// @return one single-reference temporal motion-field sample
        public static TemporalMotionBlock singleReference(int referenceFrame0, InterMotionVector motionVector0) {
            return new TemporalMotionBlock(false, referenceFrame0, -1, motionVector0, null);
        }

        /// Creates one compound-reference temporal motion-field sample.
        ///
        /// @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
        /// @param referenceFrame1 the secondary reference frame in internal LAST..ALTREF order
        /// @param motionVector0 the primary temporal motion-vector state
        /// @param motionVector1 the secondary temporal motion-vector state
        /// @return one compound-reference temporal motion-field sample
        public static TemporalMotionBlock compoundReference(
                int referenceFrame0,
                int referenceFrame1,
                InterMotionVector motionVector0,
                InterMotionVector motionVector1
        ) {
            return new TemporalMotionBlock(true, referenceFrame0, referenceFrame1, motionVector0, motionVector1);
        }

        /// Creates one temporal motion-field sample.
        public TemporalMotionBlock {
            if (referenceFrame0 < 0) {
                throw new IllegalArgumentException("referenceFrame0 < 0: " + referenceFrame0);
            }
            if (compoundReference) {
                if (referenceFrame1 < 0) {
                    throw new IllegalArgumentException("Compound temporal motion blocks must carry referenceFrame1");
                }
                if (motionVector1 == null) {
                    throw new IllegalArgumentException("Compound temporal motion blocks must carry motionVector1");
                }
            } else {
                if (referenceFrame1 >= 0) {
                    throw new IllegalArgumentException("Single-reference temporal motion blocks must not carry referenceFrame1");
                }
                if (motionVector1 != null) {
                    throw new IllegalArgumentException("Single-reference temporal motion blocks must not carry motionVector1");
                }
            }
            Objects.requireNonNull(motionVector0, "motionVector0");
        }
    }

    /// Mutable tile-local block syntax state shared across superblocks.
    
    public static final class BlockSyntaxState {
        /// The current luma AC quantizer index carried across superblocks.
        private int currentQIndex;

        /// The current delta-lf state carried across superblocks.
        private final int[] currentDeltaLfValues;

        /// The current superblock origin X coordinate in tile-relative 4x4 units, or `-1`.
        private int currentSuperblockOriginX4;

        /// The current superblock origin Y coordinate in tile-relative 4x4 units, or `-1`.
        private int currentSuperblockOriginY4;

        /// The current superblock CDEF indices in raster quadrant order.
        private final int[] cdefIndices;

        /// Creates one tile-local block syntax state.
        ///
        /// @param baseQIndex the initial frame-level base quantizer index
        public BlockSyntaxState(int baseQIndex) {
            this.currentQIndex = baseQIndex;
            this.currentDeltaLfValues = new int[4];
            this.currentSuperblockOriginX4 = -1;
            this.currentSuperblockOriginY4 = -1;
            this.cdefIndices = new int[]{-1, -1, -1, -1};
        }

        /// Returns the current luma AC quantizer index carried across superblocks.
        ///
        /// @return the current luma AC quantizer index carried across superblocks
        public int currentQIndex() {
            return currentQIndex;
        }

        /// Sets the current luma AC quantizer index carried across superblocks.
        ///
        /// @param currentQIndex the replacement current luma AC quantizer index
        public void setCurrentQIndex(int currentQIndex) {
            this.currentQIndex = currentQIndex;
        }

        /// Returns the current delta-lf value for one runtime slot.
        ///
        /// @param index the zero-based delta-lf runtime slot index in `[0, 4)`
        /// @return the current delta-lf value for the supplied runtime slot
        public int currentDeltaLfValue(int index) {
            return currentDeltaLfValues[Objects.checkIndex(index, currentDeltaLfValues.length)];
        }

        /// Updates the current delta-lf value for one runtime slot.
        ///
        /// @param index the zero-based delta-lf runtime slot index in `[0, 4)`
        /// @param value the replacement delta-lf value
        public void setCurrentDeltaLfValue(int index, int value) {
            currentDeltaLfValues[Objects.checkIndex(index, currentDeltaLfValues.length)] = value;
        }

        /// Returns the live delta-lf runtime slots for immediate package-local inspection.
        ///
        /// The caller must not modify or retain the returned array.
        ///
        /// @return the current delta-lf runtime slots
        int[] currentDeltaLfValuesView() {
            return currentDeltaLfValues;
        }

        /// Resets the current-superblock CDEF cache when decoding enters a different superblock.
        ///
        /// @param position the current block origin in tile-relative 4x4 units
        /// @param superblockSize the active superblock size in pixels
        public void enterSuperblock(BlockPosition position, int superblockSize) {
            BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
            int superblockSize4 = superblockSize >> 2;
            int superblockOriginX4 = (nonNullPosition.x4() / superblockSize4) * superblockSize4;
            int superblockOriginY4 = (nonNullPosition.y4() / superblockSize4) * superblockSize4;
            if (superblockOriginX4 != currentSuperblockOriginX4 || superblockOriginY4 != currentSuperblockOriginY4) {
                currentSuperblockOriginX4 = superblockOriginX4;
                currentSuperblockOriginY4 = superblockOriginY4;
                java.util.Arrays.fill(cdefIndices, -1);
            }
        }

        /// Returns the current cached CDEF index for one superblock quadrant, or `-1`.
        ///
        /// @param index the zero-based quadrant index in `[0, 4)`
        /// @return the current cached CDEF index for one superblock quadrant, or `-1`
        public int cdefIndex(int index) {
            return cdefIndices[Objects.checkIndex(index, cdefIndices.length)];
        }

        /// Updates the cached CDEF index for one superblock quadrant.
        ///
        /// @param index the zero-based quadrant index in `[0, 4)`
        /// @param value the replacement cached CDEF index, or `-1`
        public void setCdefIndex(int index, int value) {
            cdefIndices[Objects.checkIndex(index, cdefIndices.length)] = value;
        }
    }
}
