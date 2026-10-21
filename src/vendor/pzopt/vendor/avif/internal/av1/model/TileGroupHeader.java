// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// Parsed AV1 tile-group header state for a single OBU.
///
/// @param explicitTilePositions whether explicit tile positions were signaled
/// @param startTileIndex the first tile index covered by this tile group
/// @param endTileIndex the last tile index covered by this tile group
/// @param totalTileCount the total tile count declared by the frame header

public record TileGroupHeader(
        boolean explicitTilePositions,
        int startTileIndex,
        int endTileIndex,
        int totalTileCount
) {
    /// Creates parsed tile-group header state.
    public TileGroupHeader {
        if (totalTileCount <= 0) {
            throw new IllegalArgumentException("totalTileCount <= 0: " + totalTileCount);
        }
        if (startTileIndex < 0 || startTileIndex >= totalTileCount) {
            throw new IllegalArgumentException("startTileIndex out of range: " + startTileIndex);
        }
        if (endTileIndex < startTileIndex || endTileIndex >= totalTileCount) {
            throw new IllegalArgumentException("endTileIndex out of range: " + endTileIndex);
        }
    }

    /// Returns the number of tiles covered by this tile group.
    ///
    /// @return the number of tiles covered by this tile group
    public int tileCount() {
        return endTileIndex - startTileIndex + 1;
    }
}
