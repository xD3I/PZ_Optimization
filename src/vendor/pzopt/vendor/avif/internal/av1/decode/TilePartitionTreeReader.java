// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.PartitionType;
import pzopt.vendor.avif.internal.av1.model.ResidualLayout;
import pzopt.vendor.avif.internal.av1.model.TransformLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Recursively decodes one tile bitstream into a frame-relative AV1 partition tree.
///
/// Entropy and neighbor-context operations use tile-relative coordinates while decoding. Nodes retained
/// in the returned tree use frame-relative coordinates so reconstruction does not need a second tree.

public final class TilePartitionTreeReader {
    /// The above-edge partition state values copied from `dav1d`.
    private static final int [] [] ABOVE_PARTITION_EDGE_VALUES = {
            {0x00, 0x00, 0x10, -1, 0x00, 0x10, 0x10, 0x10, -1, -1},
            {0x10, 0x10, 0x18, -1, 0x10, 0x18, 0x18, 0x18, 0x10, 0x1C},
            {0x18, 0x18, 0x1C, -1, 0x18, 0x1C, 0x1C, 0x1C, 0x18, 0x1E},
            {0x1C, 0x1C, 0x1E, -1, 0x1C, 0x1E, 0x1E, 0x1E, 0x1C, 0x1F},
            {0x1E, 0x1E, 0x1F, 0x1F, -1, -1, -1, -1, -1, -1}
    };

    /// The left-edge partition state values copied from `dav1d`.
    private static final int [] [] LEFT_PARTITION_EDGE_VALUES = {
            {0x00, 0x10, 0x00, -1, 0x10, 0x10, 0x00, 0x10, -1, -1},
            {0x10, 0x18, 0x10, -1, 0x18, 0x18, 0x10, 0x18, 0x1C, 0x10},
            {0x18, 0x1C, 0x18, -1, 0x1C, 0x1C, 0x18, 0x1C, 0x1E, 0x18},
            {0x1C, 0x1E, 0x1C, -1, 0x1E, 0x1E, 0x1C, 0x1E, 0x1F, 0x1C},
            {0x1E, 0x1F, 0x1E, 0x1F, -1, -1, -1, -1, -1, -1}
    };

    /// The tile-local decode state that owns the active tile bitstream.
    private final TileDecodeContext tileContext;

    /// The mutable neighbor context used while scanning the tile.
    private final BlockNeighborContext neighborContext;

    /// The typed entropy syntax reader used for partition symbols.
    private final TileSyntaxReader syntaxReader;

    /// The leaf block header reader used for terminal blocks.
    private final TileBlockHeaderReader blockHeaderReader;

    /// The transform-layout reader used after terminal block headers are decoded.
    private final TileTransformLayoutReader transformLayoutReader;

    /// The residual syntax reader used after transform layouts are decoded.
    private final TileResidualSyntaxReader residualSyntaxReader;

    /// The loop-restoration syntax reader used before each superblock partition tree.
    private final TileLoopRestorationReader loopRestorationReader;

    /// The tile width in AV1 4x4 coding units after 8x8 frame-grid rounding.
    private final int tileWidth4;

    /// The tile height in AV1 4x4 coding units after 8x8 frame-grid rounding.
    private final int tileHeight4;

    /// The frame-relative X origin of the tile in AV1 4x4 coding units.
    private final int frameOffsetX4;

    /// The frame-relative Y origin of the tile in AV1 4x4 coding units.
    private final int frameOffsetY4;

    /// Creates one recursive tile partition tree reader.
    ///
    /// @param tileContext the tile-local decode state that owns the active tile bitstream
    public TilePartitionTreeReader(TileDecodeContext tileContext) {
        this(tileContext, false);
    }

    /// Creates one recursive tile partition tree reader with a strict conformance policy.
    ///
    /// @param tileContext the tile-local decode state that owns the active tile bitstream
    /// @param strictStdCompliance whether decoded conformance values outside their ranges must be rejected
    public TilePartitionTreeReader(TileDecodeContext tileContext, boolean strictStdCompliance) {
        TileDecodeContext nonNullTileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.tileContext = nonNullTileContext;
        this.neighborContext = BlockNeighborContext.create(nonNullTileContext);
        this.syntaxReader = new TileSyntaxReader(nonNullTileContext);
        this.blockHeaderReader = new TileBlockHeaderReader(nonNullTileContext, strictStdCompliance);
        this.transformLayoutReader = new TileTransformLayoutReader(nonNullTileContext);
        this.residualSyntaxReader = new TileResidualSyntaxReader(nonNullTileContext, strictStdCompliance);
        this.loopRestorationReader = new TileLoopRestorationReader(nonNullTileContext, syntaxReader);
        this.tileWidth4 = nonNullTileContext.codedWidth4();
        this.tileHeight4 = nonNullTileContext.codedHeight4();
        this.frameOffsetX4 = nonNullTileContext.startX() >> 2;
        this.frameOffsetY4 = nonNullTileContext.startY() >> 2;
    }

    /// Returns the tile-local decode state that owns this tree reader.
    ///
    /// @return the tile-local decode state that owns this tree reader
    public TileDecodeContext tileContext() {
        return tileContext;
    }

    /// Returns the mutable neighbor context used while scanning the tile.
    ///
    /// @return the mutable neighbor context used while scanning the tile
    public BlockNeighborContext neighborContext() {
        return neighborContext;
    }

    /// Reads the current tile into top-level superblock nodes in raster order.
    ///
    /// @return the frame-relative top-level superblock nodes in raster order
    public Node[] readTile() {
        SquareBlockLevel rootLevel = tileContext.superblockSize() == 128
                ? SquareBlockLevel.BLOCK_128X128
                : SquareBlockLevel.BLOCK_64X64;
        int rootSize4 = rootLevel.size4();
        int superblockColumns = (tileWidth4 + rootSize4 - 1) / rootSize4;
        int superblockRows = (tileHeight4 + rootSize4 - 1) / rootSize4;
        List<Node> nodes = new ArrayList<>(superblockColumns * superblockRows);
        for (int row = 0; row < superblockRows; row++) {
            for (int column = 0; column < superblockColumns; column++) {
                BlockPosition position = new BlockPosition(column * rootSize4, row * rootSize4);
                loopRestorationReader.readSuperblock(position, rootSize4, rootSize4);
                Node node = readSquare(rootLevel, position);
                if (node != null) {
                    nodes.add(node);
                }
            }
        }
        return nodes.toArray(new Node[0]);
    }

    /// Recursively reads one square partition node.
    ///
    /// @param level the current square block level
    /// @param position the current square block origin
    /// @return the recursively decoded square partition node, or `null` when fully outside the tile
    private Node readSquare(SquareBlockLevel level, BlockPosition position) {
        if (position.x4() >= tileWidth4 || position.y4() >= tileHeight4) {
            return null;
        }

        int childSize4 = level.childSize4();
        boolean haveHorizontalSplit = position.x4() + childSize4 < tileWidth4;
        boolean haveVerticalSplit = position.y4() + childSize4 < tileHeight4;

        if (!haveHorizontalSplit && !haveVerticalSplit) {
            if (level == SquareBlockLevel.BLOCK_8X8) {
                return readSplitAtEight(position, level);
            }
            return readSquare(level.childLevel(), position);
        }

        if (level == SquareBlockLevel.BLOCK_8X8 && (!haveHorizontalSplit || !haveVerticalSplit)) {
            return readSplitAtEight(position, level);
        }

        int partitionContext = neighborContext.partitionContext(level.partitionShift(), position);
        PartitionType partitionType;
        if (haveHorizontalSplit && haveVerticalSplit) {
            partitionType = syntaxReader.readPartition(level.syntaxBlockLevel(), partitionContext);
        } else if (haveHorizontalSplit) {
            partitionType = syntaxReader.readHorizontalSplitOnly(level.syntaxBlockLevel(), partitionContext)
                    ? PartitionType.SPLIT
                    : PartitionType.HORIZONTAL;
        } else {
            partitionType = syntaxReader.readVerticalSplitOnly(level.syntaxBlockLevel(), partitionContext)
                    ? PartitionType.SPLIT
                    : PartitionType.VERTICAL;
        }
        Node node = switch (partitionType) {
            case NONE -> leaf(position, level.noneSize());
            case HORIZONTAL -> partition(position, level, partitionType,
                    leaf(position, level.horizontalTopSize()),
                    leaf(position.offset(0, childSize4), level.horizontalBottomSize()));
            case VERTICAL -> partition(position, level, partitionType,
                    leaf(position, level.verticalLeftSize()),
                    leaf(position.offset(childSize4, 0), level.verticalRightSize()));
            case SPLIT -> {
                if (level == SquareBlockLevel.BLOCK_8X8) {
                    yield readSplitAtEight(position, level);
                }
                yield partition(position, level, partitionType,
                        readSquare(level.childLevel(), position),
                        readSquare(level.childLevel(), position.offset(childSize4, 0)),
                        readSquare(level.childLevel(), position.offset(0, childSize4)),
                        readSquare(level.childLevel(), position.offset(childSize4, childSize4)));
            }
            case T_TOP_SPLIT -> partition(position, level, partitionType,
                    leaf(position, level.topLeftSquareSize()),
                    leaf(position.offset(childSize4, 0), level.topRightSquareSize()),
                    leaf(position.offset(0, childSize4), level.horizontalBottomSize()));
            case T_BOTTOM_SPLIT -> partition(position, level, partitionType,
                    leaf(position, level.horizontalTopSize()),
                    leaf(position.offset(0, childSize4), level.bottomLeftSquareSize()),
                    leaf(position.offset(childSize4, childSize4), level.bottomRightSquareSize()));
            case T_LEFT_SPLIT -> partition(position, level, partitionType,
                    leaf(position, level.topLeftSquareSize()),
                    leaf(position.offset(0, childSize4), level.bottomLeftSquareSize()),
                    leaf(position.offset(childSize4, 0), level.verticalRightSize()));
            case T_RIGHT_SPLIT -> partition(position, level, partitionType,
                    leaf(position, level.verticalLeftSize()),
                    leaf(position.offset(childSize4, 0), level.topRightSquareSize()),
                    leaf(position.offset(childSize4, childSize4), level.bottomRightSquareSize()));
            case HORIZONTAL_4 -> partition(position, level, partitionType,
                    leaf(position, level.horizontalFourWaySize()),
                    leaf(position.offset(0, level.quarterSize4()), level.horizontalFourWaySize()),
                    leaf(position.offset(0, level.quarterSize4() * 2), level.horizontalFourWaySize()),
                    leaf(position.offset(0, level.quarterSize4() * 3), level.horizontalFourWaySize()));
            case VERTICAL_4 -> partition(position, level, partitionType,
                    leaf(position, level.verticalFourWaySize()),
                    leaf(position.offset(level.quarterSize4(), 0), level.verticalFourWaySize()),
                    leaf(position.offset(level.quarterSize4() * 2, 0), level.verticalFourWaySize()),
                    leaf(position.offset(level.quarterSize4() * 3, 0), level.verticalFourWaySize()));
        };

        if (partitionType != PartitionType.SPLIT || level == SquareBlockLevel.BLOCK_8X8) {
            neighborContext.updatePartition(
                    position,
                    level.span8(),
                    ABOVE_PARTITION_EDGE_VALUES[level.tableIndex()][partitionType.symbolIndex()],
                    LEFT_PARTITION_EDGE_VALUES[level.tableIndex()][partitionType.symbolIndex()]
            );
        }
        return node;
    }

    /// Reads the mandatory 8x8 split into 4x4 leaf nodes.
    ///
    /// @param position the current 8x8 block origin
    /// @param level the current 8x8 square block level
    /// @return the partition node that represents the 8x8 split
    private PartitionNode readSplitAtEight(BlockPosition position, SquareBlockLevel level) {
        return partition(position, level, PartitionType.SPLIT,
                leaf(position, BlockSize.SIZE_4X4),
                leaf(position.offset(1, 0), BlockSize.SIZE_4X4),
                leaf(position.offset(0, 1), BlockSize.SIZE_4X4),
                leaf(position.offset(1, 1), BlockSize.SIZE_4X4));
    }

    /// Reads one coded leaf block.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size to read
    /// @return the leaf node for the supplied block, or `null` when fully outside the coded tile grid
    private LeafNode leaf(BlockPosition position, BlockSize size) {
        if (position.x4() >= tileWidth4 || position.y4() >= tileHeight4) {
            return null;
        }
        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, size, neighborContext, false);
        if (header.useIntrabc()) {
            IntrabcDisplacementValidator.requireValid(
                    tileContext,
                    position,
                    size,
                    Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector(),
                    header.hasChroma()
            );
        }
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        neighborContext.updateFromBlockHeader(header);
        neighborContext.updateDefaultTransformContext(position, size);
        if (header.intra() || header.useIntrabc()) {
            pzopt.vendor.avif.internal.av1.model.TransformSize uniformLumaTransformSize =
                    transformLayout.uniformLumaTransformSize();
            if (uniformLumaTransformSize != null) {
                if (header.intra()) {
                    neighborContext.updateIntraTransformContext(position, size, uniformLumaTransformSize);
                    neighborContext.updateInterTransformContext(position, size.width4(), size.height4(), uniformLumaTransformSize);
                }
            }
        }
        if (frameOffsetX4 == 0 && frameOffsetY4 == 0) {
            return new LeafNode(header, transformLayout, residualLayout);
        }
        header.relocatePosition(transformLayout.position());
        return new LeafNode(header, transformLayout, residualLayout);
    }

    /// Creates a partition node and drops children that fell fully outside the tile.
    ///
    /// @param position the current square block origin
    /// @param level the current square block level
    /// @param partitionType the decoded partition type
    /// @param children the partition children, some of which may be `null`
    /// @return the created partition node
    private PartitionNode partition(
            BlockPosition position,
            SquareBlockLevel level,
            PartitionType partitionType,
            Node... children
    ) {
        int visibleChildCount = 0;
        for (Node child : children) {
            if (child != null) {
                visibleChildCount++;
            }
        }
        Node[] visibleChildren;
        if (visibleChildCount == children.length) {
            visibleChildren = children;
        } else {
            visibleChildren = new Node[visibleChildCount];
            int destinationIndex = 0;
            for (Node child : children) {
                if (child != null) {
                    visibleChildren[destinationIndex++] = child;
                }
            }
        }
        return new PartitionNode(
                position.offset(frameOffsetX4, frameOffsetY4),
                level.squareSize(),
                partitionType,
                visibleChildren,
                false
        );
    }

    /// One square block level used by the recursive partition reader.
    
    enum SquareBlockLevel {
        /// 128x128 square blocks.
        BLOCK_128X128(0, 32, 16, 4, TileSyntaxReader.PartitionBlockLevel.BLOCK_128X128, BlockSize.SIZE_128X128,
                BlockSize.SIZE_128X64, BlockSize.SIZE_64X128, BlockSize.SIZE_64X16, BlockSize.SIZE_16X64),
        /// 64x64 square blocks.
        BLOCK_64X64(1, 16, 8, 3, TileSyntaxReader.PartitionBlockLevel.BLOCK_64X64, BlockSize.SIZE_64X64,
                BlockSize.SIZE_64X32, BlockSize.SIZE_32X64, BlockSize.SIZE_64X16, BlockSize.SIZE_16X64),
        /// 32x32 square blocks.
        BLOCK_32X32(2, 8, 4, 2, TileSyntaxReader.PartitionBlockLevel.BLOCK_32X32, BlockSize.SIZE_32X32,
                BlockSize.SIZE_32X16, BlockSize.SIZE_16X32, BlockSize.SIZE_32X8, BlockSize.SIZE_8X32),
        /// 16x16 square blocks.
        BLOCK_16X16(3, 4, 2, 1, TileSyntaxReader.PartitionBlockLevel.BLOCK_16X16, BlockSize.SIZE_16X16,
                BlockSize.SIZE_16X8, BlockSize.SIZE_8X16, BlockSize.SIZE_16X4, BlockSize.SIZE_4X16),
        /// 8x8 square blocks.
        BLOCK_8X8(4, 2, 1, 0, TileSyntaxReader.PartitionBlockLevel.BLOCK_8X8, BlockSize.SIZE_8X8,
                BlockSize.SIZE_8X4, BlockSize.SIZE_4X8, BlockSize.SIZE_4X4, BlockSize.SIZE_4X4);

        /// The index used for the partition-edge lookup tables.
        private final int tableIndex;

        /// The square block size in 4x4 units.
        private final int size4;

        /// The partition edge span in 8x8 units.
        private final int span8;

        /// The bit shift used by `BlockNeighborContext.partitionContext`.
        private final int partitionShift;

        /// The syntax reader partition level used for this square block level.
        private final TileSyntaxReader.PartitionBlockLevel syntaxBlockLevel;

        /// The unsplit square block size.
        private final BlockSize noneSize;

        /// The horizontal partition block size.
        private final BlockSize horizontalSize;

        /// The vertical partition block size.
        private final BlockSize verticalSize;

        /// The four-way horizontal stripe size.
        private final BlockSize horizontalFourWaySize;

        /// The four-way vertical stripe size.
        private final BlockSize verticalFourWaySize;

        /// Creates one square block level entry.
        ///
        /// @param tableIndex the index used for the partition-edge lookup tables
        /// @param size4 the square block size in 4x4 units
        /// @param span8 the partition edge span in 8x8 units
        /// @param partitionShift the bit shift used by `BlockNeighborContext.partitionContext`
        /// @param syntaxBlockLevel the syntax reader partition level used for this square block level
        /// @param noneSize the unsplit square block size
        /// @param horizontalSize the horizontal partition block size
        /// @param verticalSize the vertical partition block size
        /// @param horizontalFourWaySize the four-way horizontal stripe size
        /// @param verticalFourWaySize the four-way vertical stripe size
        SquareBlockLevel(
                int tableIndex,
                int size4,
                int span8,
                int partitionShift,
                TileSyntaxReader.PartitionBlockLevel syntaxBlockLevel,
                BlockSize noneSize,
                BlockSize horizontalSize,
                BlockSize verticalSize,
                BlockSize horizontalFourWaySize,
                BlockSize verticalFourWaySize
        ) {
            this.tableIndex = tableIndex;
            this.size4 = size4;
            this.span8 = span8;
            this.partitionShift = partitionShift;
            this.syntaxBlockLevel = syntaxBlockLevel;
            this.noneSize = noneSize;
            this.horizontalSize = horizontalSize;
            this.verticalSize = verticalSize;
            this.horizontalFourWaySize = horizontalFourWaySize;
            this.verticalFourWaySize = verticalFourWaySize;
        }

        /// Returns the index used for the partition-edge lookup tables.
        ///
        /// @return the index used for the partition-edge lookup tables
        public int tableIndex() {
            return tableIndex;
        }

        /// Returns the square block size in 4x4 units.
        ///
        /// @return the square block size in 4x4 units
        public int size4() {
            return size4;
        }

        /// Returns the child square size in 4x4 units.
        ///
        /// @return the child square size in 4x4 units
        public int childSize4() {
            return size4 >> 1;
        }

        /// Returns the four-way stripe size in 4x4 units.
        ///
        /// @return the four-way stripe size in 4x4 units
        public int quarterSize4() {
            return size4 >> 2;
        }

        /// Returns the partition edge span in 8x8 units.
        ///
        /// @return the partition edge span in 8x8 units
        public int span8() {
            return span8;
        }

        /// Returns the bit shift used by `BlockNeighborContext.partitionContext`.
        ///
        /// @return the bit shift used by `BlockNeighborContext.partitionContext`
        public int partitionShift() {
            return partitionShift;
        }

        /// Returns the syntax reader partition level used for this square block level.
        ///
        /// @return the syntax reader partition level used for this square block level
        public TileSyntaxReader.PartitionBlockLevel syntaxBlockLevel() {
            return syntaxBlockLevel;
        }

        /// Returns the unsplit square block size.
        ///
        /// @return the unsplit square block size
        public BlockSize noneSize() {
            return noneSize;
        }

        /// Returns the square block size model for this level.
        ///
        /// @return the square block size model for this level
        public BlockSize squareSize() {
            return noneSize;
        }

        /// Returns the top block size for a horizontal partition.
        ///
        /// @return the top block size for a horizontal partition
        public BlockSize horizontalTopSize() {
            return horizontalSize;
        }

        /// Returns the bottom block size for a horizontal partition.
        ///
        /// @return the bottom block size for a horizontal partition
        public BlockSize horizontalBottomSize() {
            return horizontalSize;
        }

        /// Returns the left block size for a vertical partition.
        ///
        /// @return the left block size for a vertical partition
        public BlockSize verticalLeftSize() {
            return verticalSize;
        }

        /// Returns the right block size for a vertical partition.
        ///
        /// @return the right block size for a vertical partition
        public BlockSize verticalRightSize() {
            return verticalSize;
        }

        /// Returns the top-left square size for T-shaped partitions.
        ///
        /// @return the top-left square size for T-shaped partitions
        public BlockSize topLeftSquareSize() {
            return childLevel().squareSize();
        }

        /// Returns the top-right square size for T-shaped partitions.
        ///
        /// @return the top-right square size for T-shaped partitions
        public BlockSize topRightSquareSize() {
            return childLevel().squareSize();
        }

        /// Returns the bottom-left square size for T-shaped partitions.
        ///
        /// @return the bottom-left square size for T-shaped partitions
        public BlockSize bottomLeftSquareSize() {
            return childLevel().squareSize();
        }

        /// Returns the bottom-right square size for T-shaped partitions.
        ///
        /// @return the bottom-right square size for T-shaped partitions
        public BlockSize bottomRightSquareSize() {
            return childLevel().squareSize();
        }

        /// Returns the four-way horizontal stripe size.
        ///
        /// @return the four-way horizontal stripe size
        public BlockSize horizontalFourWaySize() {
            return horizontalFourWaySize;
        }

        /// Returns the four-way vertical stripe size.
        ///
        /// @return the four-way vertical stripe size
        public BlockSize verticalFourWaySize() {
            return verticalFourWaySize;
        }

        /// Returns the next smaller square block level.
        ///
        /// @return the next smaller square block level
        public SquareBlockLevel childLevel() {
            return switch (this) {
                case BLOCK_128X128 -> BLOCK_64X64;
                case BLOCK_64X64 -> BLOCK_32X32;
                case BLOCK_32X32 -> BLOCK_16X16;
                case BLOCK_16X16 -> BLOCK_8X8;
                case BLOCK_8X8 -> throw new IllegalStateException("8x8 blocks have no smaller square level");
            };
        }
    }

    /// One node inside the early tile partition tree.
    
    public interface Node {
        /// Returns the local tile-relative origin of this node.
        ///
        /// @return the local tile-relative origin of this node
        BlockPosition position();

        /// Returns the block size represented by this node.
        ///
        /// @return the block size represented by this node
        BlockSize size();
    }

    /// One partition tree leaf that already contains a decoded leaf block header.
    ///
    /// @param header the decoded leaf block header
    /// @param transformLayout the decoded block-level transform layout
    /// @param residualLayout the decoded block-level residual layout
    
    public record LeafNode(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            ResidualLayout residualLayout
    ) implements Node {
        /// Creates one partition tree leaf.
        public LeafNode {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(transformLayout, "transformLayout");
            Objects.requireNonNull(residualLayout, "residualLayout");
        }

        /// Returns the local tile-relative origin of this node.
        ///
        /// @return the local tile-relative origin of this node
        @Override
        public BlockPosition position() {
            return header.position();
        }

        /// Returns the block size represented by this node.
        ///
        /// @return the block size represented by this node
        @Override
        public BlockSize size() {
            return header.size();
        }
    }

    /// One non-leaf partition node with child nodes in bitstream order.
    
    public static final class PartitionNode implements Node {
        /// The local tile-relative origin of this node.
        private final BlockPosition position;

        /// The square block size represented by this node.
        private final BlockSize size;

        /// The decoded partition type.
        private final PartitionType partitionType;

        /// The child nodes in bitstream order.
        private final Node [] children;

        /// Creates one non-leaf partition node.
        ///
        /// @param position the local tile-relative origin of this node
        /// @param size the square block size represented by this node
        /// @param partitionType the decoded partition type
        /// @param children the child nodes in bitstream order
        public PartitionNode(BlockPosition position, BlockSize size, PartitionType partitionType, Node[] children) {
            this(position, size, partitionType, children, true);
        }

        /// Creates one non-leaf partition node with copied or exclusively transferred children.
        ///
        /// @param position the node origin
        /// @param size the square block size
        /// @param partitionType the decoded partition type
        /// @param children the child nodes in bitstream order
        /// @param copyChildren whether to copy the child array
        private PartitionNode(
                BlockPosition position,
                BlockSize size,
                PartitionType partitionType,
                Node[] children,
                boolean copyChildren
        ) {
            this.position = Objects.requireNonNull(position, "position");
            this.size = Objects.requireNonNull(size, "size");
            this.partitionType = Objects.requireNonNull(partitionType, "partitionType");
            Node[] checkedChildren = Objects.requireNonNull(children, "children");
            this.children = copyChildren ? Arrays.copyOf(checkedChildren, checkedChildren.length) : checkedChildren;
        }

        /// Returns the local tile-relative origin of this node.
        ///
        /// @return the local tile-relative origin of this node
        @Override
        public BlockPosition position() {
            return position;
        }

        /// Returns the square block size represented by this node.
        ///
        /// @return the square block size represented by this node
        @Override
        public BlockSize size() {
            return size;
        }

        /// Returns the decoded partition type.
        ///
        /// @return the decoded partition type
        public PartitionType partitionType() {
            return partitionType;
        }

        /// Returns the child nodes in bitstream order.
        ///
        /// @return the child nodes in bitstream order
        public Node[] children() {
            return Arrays.copyOf(children, children.length);
        }

        /// Returns the number of child nodes.
        ///
        /// @return the number of child nodes
        public int childCount() {
            return children.length;
        }

        /// Returns one child node without materializing a copy of the complete child array.
        ///
        /// @param index the zero-based child index
        /// @return the selected child node
        public Node child(int index) {
            return children[index];
        }
    }
}
