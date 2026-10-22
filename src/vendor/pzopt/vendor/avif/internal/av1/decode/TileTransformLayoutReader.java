// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.TransformLayout;
import pzopt.vendor.avif.internal.av1.model.TransformSize;
import pzopt.vendor.avif.internal.av1.model.TransformUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Reader for block-level transform layouts that sit between block headers and coefficient syntax.
///
/// Handles uniform intra-like transform sizes, switchable transform-depth symbols, explicit chroma
/// transform-unit tiling, lossless 4x4 tiling, and switchable inter variable-transform trees.
/// Entropy contexts use tile-local positions, while returned layouts and units use frame-relative
/// positions so callers can retain them without a second relocation pass.

public final class TileTransformLayoutReader {
    /// Shared empty transform-unit sequence for blocks without modeled chroma transforms.
    private static final TransformUnit [] EMPTY_TRANSFORM_UNITS = new TransformUnit[0];

    /// The width and height of one AV1 transform-processing region in 4x4 luma units.
    private static final int TRANSFORM_REGION_SIZE4 = 16;

    /// The tile-local decode state that owns the active frame and sequence headers.
    private final TileDecodeContext tileContext;

    /// The typed syntax reader used for transform-size symbols.
    private final TileSyntaxReader syntaxReader;

    /// The tile origin on the frame luma-grid X axis.
    private final int frameOffsetX4;

    /// The tile origin on the frame luma-grid Y axis.
    private final int frameOffsetY4;

    /// Creates one block-level transform-layout reader.
    ///
    /// @param tileContext the tile-local decode state that owns the active frame and sequence headers
    public TileTransformLayoutReader(TileDecodeContext tileContext) {
        TileDecodeContext nonNullTileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.tileContext = nonNullTileContext;
        this.syntaxReader = new TileSyntaxReader(nonNullTileContext);
        this.frameOffsetX4 = nonNullTileContext.startX() >> 2;
        this.frameOffsetY4 = nonNullTileContext.startY() >> 2;
    }

    /// Returns the tile-local decode state that owns this transform-layout reader.
    ///
    /// @return the tile-local decode state that owns this transform-layout reader
    public TileDecodeContext tileContext() {
        return tileContext;
    }

    /// Decodes the transform layout for one already-decoded leaf block header.
    ///
    /// @param header the decoded leaf block header whose position is tile-local
    /// @param neighborContext the mutable neighbor context that supplies transform-size contexts
    /// @return the decoded transform layout with frame-relative retained positions
    public TransformLayout read(TileBlockHeaderReader.BlockHeader header, BlockNeighborContext neighborContext) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");
        BlockPosition position = nonNullHeader.position();
        BlockSize size = nonNullHeader.size();
        int visibleWidth4 = Math.min(size.width4(), tileContext.codedWidth4() - position.x4());
        int visibleHeight4 = Math.min(size.height4(), tileContext.codedHeight4() - position.y4());
        int visibleWidthPixels = visibleWidth4 << 2;
        int visibleHeightPixels = visibleHeight4 << 2;

        boolean lossless = tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId());
        TransformSize maxLumaTransformSize = size.maxLumaTransformSize();
        TransformSize chromaTransformSize = nonNullHeader.hasChroma()
                ? size.maxChromaTransformSize(tileContext.sequenceHeader().colorConfig().chromaFormat())
                : null;
        TransformSize effectiveMaxLumaTransformSize = lossless ? TransformSize.TX_4X4 : maxLumaTransformSize;
        if (lossless) {
            chromaTransformSize = TransformSize.TX_4X4;
        }
        TransformUnit[] chromaUnits = chromaTransformSize != null
                ? tileChromaUnits(
                        position,
                        size,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        chromaTransformSize,
                        tileContext.sequenceHeader().colorConfig().chromaFormat()
                )
                : EMPTY_TRANSFORM_UNITS;

        TransformUnit[] lumaUnits;
        boolean variableLumaTransformTree;
        if (nonNullHeader.intra() && !nonNullHeader.useIntrabc()) {
            TransformSize uniformLumaTransformSize = selectUniformIntraTransformSize(
                    nonNullHeader,
                    nonNullNeighborContext,
                    lossless,
                    effectiveMaxLumaTransformSize
            );
            lumaUnits = tileUniformUnits(position, visibleWidth4, visibleHeight4, uniformLumaTransformSize);
            variableLumaTransformTree = false;
        } else {
            InterTransformResult interTransformResult = readInterTransformLayout(
                    nonNullHeader,
                    nonNullNeighborContext,
                    lossless,
                    effectiveMaxLumaTransformSize,
                    visibleWidth4,
                    visibleHeight4
            );
            lumaUnits = interTransformResult.lumaUnits();
            variableLumaTransformTree = interTransformResult.variableTransformTree();
        }

        return TransformLayout.fromOwnedUnits(
                framePosition(position),
                size,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                effectiveMaxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                chromaUnits
        );
    }

    /// Selects the uniform luma transform size used by one intra-like block.
    ///
    /// @param header the decoded leaf block header
    /// @param neighborContext the mutable neighbor context that supplies transform-size contexts
    /// @param lossless whether the current block is lossless through segmentation
    /// @param maxLumaTransformSize the largest luma transform size allowed for this block
    /// @return the uniform luma transform size used by the current block
    private TransformSize selectUniformIntraTransformSize(
            TileBlockHeaderReader.BlockHeader header,
            BlockNeighborContext neighborContext,
            boolean lossless,
            TransformSize maxLumaTransformSize
    ) {
        if (lossless || tileContext.frameHeader().transformMode() == FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY) {
            return TransformSize.TX_4X4;
        }
        if (tileContext.frameHeader().transformMode() != FrameHeader.TransformMode.SWITCHABLE
                || maxLumaTransformSize.maxSquareLevel() <= 0) {
            return maxLumaTransformSize;
        }

        int depth = syntaxReader.readTransformDepth(
                maxLumaTransformSize,
                neighborContext.transformSizeContext(header.position(), maxLumaTransformSize)
        );
        TransformSize selectedSize = maxLumaTransformSize;
        while (depth-- > 0 && selectedSize.hasSubSize()) {
            selectedSize = selectedSize.subSize();
        }
        return selectedSize;
    }

    /// Reads the luma transform layout for one inter-like block.
    ///
    /// @param header the decoded leaf block header
    /// @param neighborContext the mutable neighbor context that supplies inter var-tx contexts
    /// @param lossless whether the current block is lossless through segmentation
    /// @param maxLumaTransformSize the largest luma transform size allowed for this block
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @return the decoded inter-like luma transform layout
    private InterTransformResult readInterTransformLayout(
            TileBlockHeaderReader.BlockHeader header,
            BlockNeighborContext neighborContext,
            boolean lossless,
            TransformSize maxLumaTransformSize,
            int visibleWidth4,
            int visibleHeight4
    ) {
        BlockPosition position = header.position();
        BlockSize size = header.size();
        if (header.skip()) {
            neighborContext.updateInterTransformContext(
                    position,
                    visibleWidth4,
                    visibleHeight4,
                    size.log2Width4(),
                    size.log2Height4()
            );
            TransformSize skippedTransformSize = lossless ? TransformSize.TX_4X4 : maxLumaTransformSize;
            return new InterTransformResult(
                    tileUniformUnits(position, visibleWidth4, visibleHeight4, skippedTransformSize),
                    false
            );
        }
        if (maxLumaTransformSize == TransformSize.TX_4X4) {
            neighborContext.updateInterTransformContext(position, visibleWidth4, visibleHeight4, TransformSize.TX_4X4);
            return new InterTransformResult(tileUniformUnits(position, visibleWidth4, visibleHeight4, TransformSize.TX_4X4), false);
        }
        if (lossless) {
            neighborContext.updateInterTransformContext(position, visibleWidth4, visibleHeight4, TransformSize.TX_4X4);
            return new InterTransformResult(tileUniformUnits(position, visibleWidth4, visibleHeight4, TransformSize.TX_4X4), false);
        }
        if (tileContext.frameHeader().transformMode() != FrameHeader.TransformMode.SWITCHABLE) {
            return new InterTransformResult(tileUniformUnits(position, visibleWidth4, visibleHeight4, maxLumaTransformSize), false);
        }

        List<TransformUnit> units = new ArrayList<>();
        for (int offsetY4 = 0; offsetY4 < visibleHeight4; offsetY4 += maxLumaTransformSize.height4()) {
            int regionVisibleHeight4 = Math.min(maxLumaTransformSize.height4(), visibleHeight4 - offsetY4);
            for (int offsetX4 = 0; offsetX4 < visibleWidth4; offsetX4 += maxLumaTransformSize.width4()) {
                int regionVisibleWidth4 = Math.min(maxLumaTransformSize.width4(), visibleWidth4 - offsetX4);
                readInterTransformTree(
                        position.offset(offsetX4, offsetY4),
                        maxLumaTransformSize,
                        regionVisibleWidth4,
                        regionVisibleHeight4,
                        0,
                        neighborContext,
                        units
                );
            }
        }
        return new InterTransformResult(units.toArray(new TransformUnit[units.size()]), true);
    }

    /// Recursively reads one inter var-tx region and appends its visible luma transform units.
    ///
    /// @param position the local tile-relative origin of the current transform region
    /// @param transformSize the transform size currently being considered for splitting
    /// @param visibleWidth4 the visible region width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible region height in 4x4 units after clipping against tile bounds
    /// @param depth the recursive var-tx depth in `[0, 2]`
    /// @param neighborContext the mutable neighbor context that supplies and stores inter var-tx state
    /// @param destination the destination list that receives visible luma transform units in bitstream order
    private void readInterTransformTree(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidth4,
            int visibleHeight4,
            int depth,
            BlockNeighborContext neighborContext,
            List<TransformUnit> destination
    ) {
        if (visibleWidth4 <= 0 || visibleHeight4 <= 0) {
            return;
        }

        boolean split = false;
        if (depth < 2 && transformSize != TransformSize.TX_4X4) {
            int tableIndex = 2 * (TransformSize.TX_64X64.maxSquareLevel() - transformSize.maxSquareLevel()) - depth;
            split = syntaxReader.readTransformSplitFlag(
                    tableIndex,
                    neighborContext.interTransformSplitContext(position, transformSize)
            );
        }

        if (split && transformSize.maxSquareLevel() > TransformSize.TX_8X8.maxSquareLevel()) {
            TransformSize subSize = transformSize.subSize();
            int childWidth4 = subSize.width4();
            int childHeight4 = subSize.height4();
            readInterTransformTree(
                    position,
                    subSize,
                    Math.min(childWidth4, visibleWidth4),
                    Math.min(childHeight4, visibleHeight4),
                    depth + 1,
                    neighborContext,
                    destination
            );
            if (transformSize.width4() >= transformSize.height4()) {
                readInterTransformTree(
                        position.offset(childWidth4, 0),
                        subSize,
                        Math.min(childWidth4, Math.max(0, visibleWidth4 - childWidth4)),
                        Math.min(childHeight4, visibleHeight4),
                        depth + 1,
                        neighborContext,
                        destination
                );
            }
            if (transformSize.height4() >= transformSize.width4()) {
                readInterTransformTree(
                        position.offset(0, childHeight4),
                        subSize,
                        Math.min(childWidth4, visibleWidth4),
                        Math.min(childHeight4, Math.max(0, visibleHeight4 - childHeight4)),
                        depth + 1,
                        neighborContext,
                        destination
                );
                if (transformSize.width4() >= transformSize.height4()) {
                    readInterTransformTree(
                            position.offset(childWidth4, childHeight4),
                            subSize,
                            Math.min(childWidth4, Math.max(0, visibleWidth4 - childWidth4)),
                            Math.min(childHeight4, Math.max(0, visibleHeight4 - childHeight4)),
                            depth + 1,
                            neighborContext,
                            destination
                    );
                }
            }
            return;
        }

        TransformSize finalTransformSize = split ? TransformSize.TX_4X4 : transformSize;
        neighborContext.updateInterTransformContext(position, visibleWidth4, visibleHeight4, finalTransformSize);
        appendUniformUnits(destination, position, visibleWidth4, visibleHeight4, finalTransformSize);
    }

    /// Tiles one visible block span with repeated luma transform units in AV1 processing order.
    ///
    /// Blocks wider or taller than 64 luma samples are divided into 64x64-or-smaller regions.
    /// Every region is completed before prediction and reconstruction advance to the next region.
    ///
    /// @param position the local tile-relative origin of the owning block
    /// @param visibleWidth4 the visible block width in 4x4 units
    /// @param visibleHeight4 the visible block height in 4x4 units
    /// @param transformSize the repeated luma transform size
    /// @return frame-relative luma transform units that cover the visible block span
    private TransformUnit[] tileUniformUnits(
            BlockPosition position,
            int visibleWidth4,
            int visibleHeight4,
            TransformSize transformSize
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformUnit[] units = new TransformUnit[tiledUnitCount(
                visibleWidth4,
                visibleHeight4,
                TRANSFORM_REGION_SIZE4,
                TRANSFORM_REGION_SIZE4,
                nonNullTransformSize.width4(),
                nonNullTransformSize.height4()
        )];
        int unitIndex = 0;
        for (int regionY4 = 0; regionY4 < visibleHeight4; regionY4 += TRANSFORM_REGION_SIZE4) {
            int regionEndY4 = Math.min(regionY4 + TRANSFORM_REGION_SIZE4, visibleHeight4);
            for (int regionX4 = 0; regionX4 < visibleWidth4; regionX4 += TRANSFORM_REGION_SIZE4) {
                int regionEndX4 = Math.min(regionX4 + TRANSFORM_REGION_SIZE4, visibleWidth4);
                for (int y4 = regionY4; y4 < regionEndY4; y4 += nonNullTransformSize.height4()) {
                    for (int x4 = regionX4; x4 < regionEndX4; x4 += nonNullTransformSize.width4()) {
                        units[unitIndex++] = new TransformUnit(
                                new BlockPosition(
                                        nonNullPosition.x4() + x4 + frameOffsetX4,
                                        nonNullPosition.y4() + y4 + frameOffsetY4
                                ),
                                nonNullTransformSize
                        );
                    }
                }
            }
        }
        return units;
    }

    /// Appends repeated luma transform units in AV1 processing order.
    ///
    /// @param destination the destination list
    /// @param position the local tile-relative origin of the owning block
    /// @param visibleWidth4 the visible block width in 4x4 units
    /// @param visibleHeight4 the visible block height in 4x4 units
    /// @param transformSize the repeated luma transform size
    private void appendUniformUnits(
            List<TransformUnit> destination,
            BlockPosition position,
            int visibleWidth4,
            int visibleHeight4,
            TransformSize transformSize
    ) {
        List<TransformUnit> nonNullDestination = Objects.requireNonNull(destination, "destination");
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        for (int regionY4 = 0; regionY4 < visibleHeight4; regionY4 += TRANSFORM_REGION_SIZE4) {
            int regionEndY4 = Math.min(regionY4 + TRANSFORM_REGION_SIZE4, visibleHeight4);
            for (int regionX4 = 0; regionX4 < visibleWidth4; regionX4 += TRANSFORM_REGION_SIZE4) {
                int regionEndX4 = Math.min(regionX4 + TRANSFORM_REGION_SIZE4, visibleWidth4);
                for (int y4 = regionY4; y4 < regionEndY4; y4 += nonNullTransformSize.height4()) {
                    for (int x4 = regionX4; x4 < regionEndX4; x4 += nonNullTransformSize.width4()) {
                        nonNullDestination.add(new TransformUnit(
                                new BlockPosition(
                                        nonNullPosition.x4() + x4 + frameOffsetX4,
                                        nonNullPosition.y4() + y4 + frameOffsetY4
                                ),
                                nonNullTransformSize
                        ));
                    }
                }
            }
        }
    }

    /// Tiles one visible block span with chroma transform units in AV1 processing order.
    ///
    /// The returned unit positions are widened back into the frame-relative shared luma-grid
    /// `BlockPosition` coordinate space so reconstruction can keep using one position type.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns the chroma units
    /// @param visibleWidthPixels the exact visible luma width in pixels
    /// @param visibleHeightPixels the exact visible luma height in pixels
    /// @param transformSize the repeated chroma transform size
    /// @param chromaFormat the active decoded chroma layout
    /// @return frame-relative chroma transform units covering the visible chroma span
    private TransformUnit[] tileChromaUnits(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize transformSize,
            Av1ChromaFormat chromaFormat
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullBlockSize = Objects.requireNonNull(blockSize, "blockSize");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int subsamplingX = chromaSubsamplingX(chromaFormat);
        int subsamplingY = chromaSubsamplingY(chromaFormat);
        int originX4 = chromaOrigin4(nonNullPosition.x4(), nonNullBlockSize.width4(), subsamplingX);
        int originY4 = chromaOrigin4(nonNullPosition.y4(), nonNullBlockSize.height4(), subsamplingY);
        int visibleChromaWidthPixels = chromaDimension(
                ((nonNullPosition.x4() << 2) + visibleWidthPixels) - (originX4 << 2),
                subsamplingX
        );
        int visibleChromaHeightPixels = chromaDimension(
                ((nonNullPosition.y4() << 2) + visibleHeightPixels) - (originY4 << 2),
                subsamplingY
        );
        int regionWidthPixels = (TRANSFORM_REGION_SIZE4 << 2) >> subsamplingX;
        int regionHeightPixels = (TRANSFORM_REGION_SIZE4 << 2) >> subsamplingY;
        TransformUnit[] units = new TransformUnit[tiledUnitCount(
                visibleChromaWidthPixels,
                visibleChromaHeightPixels,
                regionWidthPixels,
                regionHeightPixels,
                nonNullTransformSize.widthPixels(),
                nonNullTransformSize.heightPixels()
        )];
        int unitIndex = 0;
        for (int regionY = 0; regionY < visibleChromaHeightPixels; regionY += regionHeightPixels) {
            int regionEndY = Math.min(regionY + regionHeightPixels, visibleChromaHeightPixels);
            for (int regionX = 0; regionX < visibleChromaWidthPixels; regionX += regionWidthPixels) {
                int regionEndX = Math.min(regionX + regionWidthPixels, visibleChromaWidthPixels);
                for (int y = regionY; y < regionEndY; y += nonNullTransformSize.heightPixels()) {
                    for (int x = regionX; x < regionEndX; x += nonNullTransformSize.widthPixels()) {
                        units[unitIndex++] = new TransformUnit(
                                new BlockPosition(
                                        originX4 + ((x >> 2) << subsamplingX) + frameOffsetX4,
                                        originY4 + ((y >> 2) << subsamplingY) + frameOffsetY4
                                ),
                                nonNullTransformSize
                        );
                    }
                }
            }
        }
        return units;
    }

    /// Returns the number of repeated units produced by region-local transform tiling.
    ///
    /// @param visibleWidth the visible width in the caller's coordinate units
    /// @param visibleHeight the visible height in the caller's coordinate units
    /// @param regionWidth the processing-region width in the same units
    /// @param regionHeight the processing-region height in the same units
    /// @param unitWidth the transform-unit width in the same units
    /// @param unitHeight the transform-unit height in the same units
    /// @return the exact number of transform units required to cover the visible span
    private static int tiledUnitCount(
            int visibleWidth,
            int visibleHeight,
            int regionWidth,
            int regionHeight,
            int unitWidth,
            int unitHeight
    ) {
        int count = 0;
        for (int regionY = 0; regionY < visibleHeight; regionY += regionHeight) {
            int currentRegionHeight = Math.min(regionHeight, visibleHeight - regionY);
            int verticalUnits = (currentRegionHeight + unitHeight - 1) / unitHeight;
            for (int regionX = 0; regionX < visibleWidth; regionX += regionWidth) {
                int currentRegionWidth = Math.min(regionWidth, visibleWidth - regionX);
                int horizontalUnits = (currentRegionWidth + unitWidth - 1) / unitWidth;
                count += horizontalUnits * verticalUnits;
            }
        }
        return count;
    }

    /// Returns one tile-local position translated into the retained frame coordinate space.
    ///
    /// @param position the tile-local block position
    /// @return the corresponding frame-relative block position
    private BlockPosition framePosition(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        if ((frameOffsetX4 | frameOffsetY4) == 0) {
            return nonNullPosition;
        }
        return new BlockPosition(
                nonNullPosition.x4() + frameOffsetX4,
                nonNullPosition.y4() + frameOffsetY4
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

    /// Returns the horizontal chroma subsampling shift for one decoded chroma format.
    ///
    /// @param chromaFormat the active decoded chroma layout
    /// @return the horizontal chroma subsampling shift
    private static int chromaSubsamplingX(Av1ChromaFormat chromaFormat) {
        return switch (Objects.requireNonNull(chromaFormat, "chromaFormat")) {
            case MONOCHROME, YUV444 -> 0;
            case YUV420, YUV422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift for one decoded chroma format.
    ///
    /// @param chromaFormat the active decoded chroma layout
    /// @return the vertical chroma subsampling shift
    private static int chromaSubsamplingY(Av1ChromaFormat chromaFormat) {
        return switch (Objects.requireNonNull(chromaFormat, "chromaFormat")) {
            case MONOCHROME, YUV422, YUV444 -> 0;
            case YUV420 -> 1;
        };
    }

    /// One decoded inter-like transform result.
    ///
    /// @param lumaUnits the decoded luma transform units in bitstream order
    /// @param variableTransformTree whether the layout came from a variable luma transform tree
    
    private record InterTransformResult(
            TransformUnit [] lumaUnits,
            boolean variableTransformTree
    ) {
        /// Validates one decoded inter-like transform result.
        private InterTransformResult {
            Objects.requireNonNull(lumaUnits, "lumaUnits");
        }
    }
}
