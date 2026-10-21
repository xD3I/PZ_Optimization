// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;

import java.util.Arrays;
import java.util.Objects;

/// Converts decoded tile-local partition trees into frame-local immutable views.
///
/// AV1 entropy decoding uses tile-local block coordinates for neighbor contexts. Pixel
/// reconstruction and postfilters write into full-frame planes, so they must consume equivalent
/// trees whose block, transform, and residual positions are offset by each tile origin.

public final class FrameLocalPartitionTrees {
    /// Prevents construction of this utility class.
    private FrameLocalPartitionTrees() {
    }

    /// Returns frame-local partition trees for every decoded tile.
    ///
    /// @param assembly the frame assembly that owns the tile geometry
    /// @param tileRootsByTile the tile-local partition trees grouped by tile index
    /// @return frame-local partition trees for every decoded tile
    public static TilePartitionTreeReader.Node[][] create(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRootsByTile
    ) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        TilePartitionTreeReader.Node[][] nonNullTileRootsByTile =
                Objects.requireNonNull(tileRootsByTile, "tileRootsByTile");
        if (nonNullTileRootsByTile.length != nonNullAssembly.totalTiles()) {
            throw new IllegalArgumentException(
                    "tileRootsByTile.length != totalTiles: " + nonNullTileRootsByTile.length
            );
        }

        TilePartitionTreeReader.Node[][] frameLocalRoots = new TilePartitionTreeReader.Node[nonNullTileRootsByTile.length][];
        for (int tileIndex = 0; tileIndex < nonNullTileRootsByTile.length; tileIndex++) {
            frameLocalRoots[tileIndex] = create(nonNullAssembly, tileIndex, nonNullTileRootsByTile[tileIndex]);
        }
        return frameLocalRoots;
    }

    /// Returns frame-local partition roots for one decoded tile.
    ///
    /// @param assembly the frame assembly that owns the tile geometry
    /// @param tileIndex the zero-based tile index in frame order
    /// @param tileRoots the tile-local partition roots for that tile
    /// @return frame-local partition roots for one decoded tile
    public static TilePartitionTreeReader.Node[] create(
            FrameAssembly assembly,
            int tileIndex,
            TilePartitionTreeReader.Node[] tileRoots
    ) {
        TilePartitionTreeReader.Node[] nonNullTileRoots = Objects.requireNonNull(tileRoots, "tileRoots");
        BlockPosition tileOrigin = tileOrigin4(assembly, tileIndex);
        if (tileOrigin.x4() == 0 && tileOrigin.y4() == 0) {
            return Arrays.copyOf(nonNullTileRoots, nonNullTileRoots.length);
        }

        TilePartitionTreeReader.Node[] frameLocalRoots = new TilePartitionTreeReader.Node[nonNullTileRoots.length];
        for (int i = 0; i < nonNullTileRoots.length; i++) {
            frameLocalRoots[i] = offsetNode(nonNullTileRoots[i], tileOrigin.x4(), tileOrigin.y4());
        }
        return frameLocalRoots;
    }

    /// Returns the frame-local tile origin in 4x4 units.
    ///
    /// @param assembly the frame assembly that owns the tile geometry
    /// @param tileIndex the zero-based tile index in frame order
    /// @return the frame-local tile origin in 4x4 units
    public static BlockPosition tileOrigin4(FrameAssembly assembly, int tileIndex) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        FrameHeader.TilingInfo tiling = nonNullAssembly.frameHeader().tiling();
        int columns = tiling.columns();
        int rows = tiling.rows();
        if (tileIndex < 0 || tileIndex >= columns * rows) {
            throw new IndexOutOfBoundsException("tileIndex out of range: " + tileIndex);
        }

        int tileColumn = tileIndex % columns;
        int tileRow = tileIndex / columns;
        int superblockSize4 = nonNullAssembly.sequenceHeader().features().use128x128Superblocks() ? 32 : 16;
        return new BlockPosition(
                tiling.columnStartSuperblock(tileColumn) * superblockSize4,
                tiling.rowStartSuperblock(tileRow) * superblockSize4
        );
    }

    /// Returns a frame-local copy of one decoded partition node.
    ///
    /// @param node the tile-local partition node
    /// @param deltaX4 the X-axis tile-origin offset in 4x4 units
    /// @param deltaY4 the Y-axis tile-origin offset in 4x4 units
    /// @return a frame-local copy of one decoded partition node
    private static TilePartitionTreeReader.Node offsetNode(
            TilePartitionTreeReader.Node node,
            int deltaX4,
            int deltaY4
    ) {
        TilePartitionTreeReader.Node nonNullNode = Objects.requireNonNull(node, "node");
        if (deltaX4 == 0 && deltaY4 == 0) {
            return nonNullNode;
        }
        if (nonNullNode instanceof TilePartitionTreeReader.LeafNode leafNode) {
            TileBlockHeaderReader.BlockHeader header = leafNode.header();
            return new TilePartitionTreeReader.LeafNode(
                    header.withPosition(header.position().offset(deltaX4, deltaY4)),
                    leafNode.transformLayout().withOffset(deltaX4, deltaY4),
                    leafNode.residualLayout().withOffset(deltaX4, deltaY4)
            );
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) nonNullNode;
        TilePartitionTreeReader.Node[] offsetChildren = new TilePartitionTreeReader.Node[partitionNode.childCount()];
        for (int i = 0; i < offsetChildren.length; i++) {
            offsetChildren[i] = offsetNode(partitionNode.child(i), deltaX4, deltaY4);
        }
        return new TilePartitionTreeReader.PartitionNode(
                partitionNode.position().offset(deltaX4, deltaY4),
                partitionNode.size(),
                partitionNode.partitionType(),
                offsetChildren
        );
    }
}
