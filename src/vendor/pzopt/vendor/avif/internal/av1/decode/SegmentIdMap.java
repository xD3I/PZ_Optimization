// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;

import java.util.Arrays;
import java.util.Objects;

/// Stores one frame's segment identifiers on the padded AV1 4x4 coding grid.

final class SegmentIdMap {
    /// The padded frame width in 4x4 coding units.
    private final int width4;

    /// The padded frame height in 4x4 coding units.
    private final int height4;

    /// The segment identifiers in row-major order.
    private final byte[] segmentIds;

    /// Creates a zero-filled segment-id map.
    ///
    /// @param width4 the padded frame width in 4x4 coding units
    /// @param height4 the padded frame height in 4x4 coding units
    private SegmentIdMap(int width4, int height4) {
        if (width4 < 0 || height4 < 0) {
            throw new IllegalArgumentException("Negative segment-id map dimensions: " + width4 + "x" + height4);
        }
        this.width4 = width4;
        this.height4 = height4;
        this.segmentIds = new byte[Math.multiplyExact(width4, height4)];
    }

    /// Creates a segment-id map containing a copy of the supplied values.
    ///
    /// @param width4 the padded frame width in 4x4 coding units
    /// @param height4 the padded frame height in 4x4 coding units
    /// @param segmentIds the row-major segment identifiers to copy
    private SegmentIdMap(int width4, int height4, byte[] segmentIds) {
        this.width4 = width4;
        this.height4 = height4;
        this.segmentIds = Arrays.copyOf(
                Objects.requireNonNull(segmentIds, "segmentIds"),
                Math.multiplyExact(width4, height4)
        );
    }

    /// Creates a zero-filled map sized for the supplied frame assembly.
    ///
    /// @param assembly the frame assembly whose padded coding grid determines the map dimensions
    /// @return a zero-filled segment-id map
    static SegmentIdMap create(FrameAssembly assembly) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        int width4 = ((nonNullAssembly.frameHeader().frameSize().codedWidth() + 7) >> 3) << 1;
        int height4 = ((nonNullAssembly.frameHeader().frameSize().height() + 7) >> 3) << 1;
        return new SegmentIdMap(width4, height4);
    }

    /// Reconstructs a segment-id map from decoded partition-tree leaves.
    ///
    /// This fallback is used for results assembled directly by callers. Decoder-produced results
    /// retain their exact map separately so `segmentation_update_map = 0` does not lose per-cell
    /// identifiers inside a block.
    ///
    /// @param assembly the frame assembly that owns the decoded trees
    /// @param tileRoots the decoded partition roots for each tile in frame order
    /// @return the reconstructed segment-id map
    static SegmentIdMap fromDecodedBlocks(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots
    ) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        TilePartitionTreeReader.Node[][] nonNullTileRoots = Objects.requireNonNull(tileRoots, "tileRoots");
        SegmentIdMap map = create(nonNullAssembly);
        int tileColumns = nonNullAssembly.frameHeader().tiling().columns();
        int superblockSize4 = nonNullAssembly.sequenceHeader().features().use128x128Superblocks() ? 32 : 16;
        FrameHeader.TilingInfo tiling = nonNullAssembly.frameHeader().tiling();
        for (int tileIndex = 0; tileIndex < nonNullTileRoots.length; tileIndex++) {
            int tileColumn = tileIndex % tileColumns;
            int tileRow = tileIndex / tileColumns;
            int startX4 = tiling.columnStartSuperblock(tileColumn) * superblockSize4;
            int startY4 = tiling.rowStartSuperblock(tileRow) * superblockSize4;
            for (TilePartitionTreeReader.Node root : Objects.requireNonNull(
                    nonNullTileRoots[tileIndex],
                    "tileRoots[" + tileIndex + "]"
            )) {
                map.fillDecodedNode(Objects.requireNonNull(root, "tile root"), startX4, startY4);
            }
        }
        return map;
    }

    /// Returns an independent copy of this map.
    ///
    /// @return an independent segment-id map copy
    SegmentIdMap copy() {
        return new SegmentIdMap(width4, height4, segmentIds);
    }

    /// Returns whether this map has the supplied dimensions.
    ///
    /// @param expectedWidth4 the expected padded width in 4x4 coding units
    /// @param expectedHeight4 the expected padded height in 4x4 coding units
    /// @return whether both dimensions match
    boolean hasDimensions(int expectedWidth4, int expectedHeight4) {
        return width4 == expectedWidth4 && height4 == expectedHeight4;
    }

    /// Returns a segment identifier, or zero when the coordinate is outside the map.
    ///
    /// @param x4 the frame-relative X coordinate in 4x4 coding units
    /// @param y4 the frame-relative Y coordinate in 4x4 coding units
    /// @return the stored segment identifier, or zero outside the map
    int getOrZero(int x4, int y4) {
        if (x4 < 0 || x4 >= width4 || y4 < 0 || y4 >= height4) {
            return 0;
        }
        return segmentIds[y4 * width4 + x4] & 0xFF;
    }

    /// Returns the minimum segment identifier in a clipped rectangular block footprint.
    ///
    /// @param startX4 the frame-relative block origin X in 4x4 coding units
    /// @param startY4 the frame-relative block origin Y in 4x4 coding units
    /// @param blockWidth4 the block width in 4x4 coding units
    /// @param blockHeight4 the block height in 4x4 coding units
    /// @return the minimum segment identifier, or zero when the footprint does not intersect the map
    int minimum(int startX4, int startY4, int blockWidth4, int blockHeight4) {
        int endX4 = Math.min(width4, startX4 + blockWidth4);
        int endY4 = Math.min(height4, startY4 + blockHeight4);
        int clippedStartX4 = Math.max(0, startX4);
        int clippedStartY4 = Math.max(0, startY4);
        if (clippedStartX4 >= endX4 || clippedStartY4 >= endY4) {
            return 0;
        }

        int minimum = 7;
        for (int y4 = clippedStartY4; y4 < endY4; y4++) {
            int rowOffset = y4 * width4;
            for (int x4 = clippedStartX4; x4 < endX4; x4++) {
                minimum = Math.min(minimum, segmentIds[rowOffset + x4] & 0xFF);
                if (minimum == 0) {
                    return 0;
                }
            }
        }
        return minimum;
    }

    /// Fills a clipped rectangular block footprint with one segment identifier.
    ///
    /// @param startX4 the frame-relative block origin X in 4x4 coding units
    /// @param startY4 the frame-relative block origin Y in 4x4 coding units
    /// @param blockWidth4 the block width in 4x4 coding units
    /// @param blockHeight4 the block height in 4x4 coding units
    /// @param segmentId the segment identifier in `[0, 7]`
    void fill(int startX4, int startY4, int blockWidth4, int blockHeight4, int segmentId) {
        if (segmentId < 0 || segmentId >= 8) {
            throw new IllegalArgumentException("segmentId out of range: " + segmentId);
        }
        int endX4 = Math.min(width4, startX4 + blockWidth4);
        int endY4 = Math.min(height4, startY4 + blockHeight4);
        int clippedStartX4 = Math.max(0, startX4);
        int clippedStartY4 = Math.max(0, startY4);
        for (int y4 = clippedStartY4; y4 < endY4; y4++) {
            Arrays.fill(segmentIds, y4 * width4 + clippedStartX4, y4 * width4 + endX4, (byte) segmentId);
        }
    }

    /// Replaces this map with another map having identical dimensions.
    ///
    /// @param source the source map to copy
    void copyFrom(SegmentIdMap source) {
        SegmentIdMap nonNullSource = Objects.requireNonNull(source, "source");
        if (!nonNullSource.hasDimensions(width4, height4)) {
            throw new IllegalArgumentException("Segment-id map dimensions differ");
        }
        System.arraycopy(nonNullSource.segmentIds, 0, segmentIds, 0, segmentIds.length);
    }

    /// Fills the map from one decoded tree node.
    ///
    /// @param node the decoded node to visit
    /// @param tileStartX4 the tile's frame-relative X origin in 4x4 coding units
    /// @param tileStartY4 the tile's frame-relative Y origin in 4x4 coding units
    private void fillDecodedNode(TilePartitionTreeReader.Node node, int tileStartX4, int tileStartY4) {
        if (node instanceof TilePartitionTreeReader.LeafNode leaf) {
            BlockPosition position = leaf.position();
            BlockSize size = leaf.size();
            fill(
                    tileStartX4 + position.x4(),
                    tileStartY4 + position.y4(),
                    size.width4(),
                    size.height4(),
                    leaf.header().segmentId()
            );
            return;
        }
        TilePartitionTreeReader.PartitionNode partition = (TilePartitionTreeReader.PartitionNode) node;
        for (int childIndex = 0; childIndex < partition.childCount(); childIndex++) {
            fillDecodedNode(partition.child(childIndex), tileStartX4, tileStartY4);
        }
    }
}
