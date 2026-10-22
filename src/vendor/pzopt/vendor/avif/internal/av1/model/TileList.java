// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


import java.util.List;
import java.util.Objects;

/// Describes one parsed AV1 Large Scale Tile list OBU.
///
/// @param outputTileColumns the output-frame width in tiles
/// @param outputTileRows the output-frame height in tiles
/// @param entries the coded tiles in output raster order

public record TileList(
        int outputTileColumns,
        int outputTileRows,
        List<Entry> entries
) {
    /// Creates a validated immutable tile list.
    public TileList {
        if (outputTileColumns <= 0 || outputTileColumns > 256) {
            throw new IllegalArgumentException("outputTileColumns out of range: " + outputTileColumns);
        }
        if (outputTileRows <= 0 || outputTileRows > 256) {
            throw new IllegalArgumentException("outputTileRows out of range: " + outputTileRows);
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.isEmpty() || entries.size() > (long) outputTileColumns * outputTileRows) {
            throw new IllegalArgumentException("Tile-list entry count exceeds its output tile grid");
        }
    }

    /// Describes one coded tile and the external anchor frame used for its inter prediction.
    ///
    /// @param anchorFrameIndex the zero-based external anchor-frame index
    /// @param tileRow the source tile row in the common camera frame
    /// @param tileColumn the source tile column in the common camera frame
    /// @param bitstream the coded tile payload
    
    public record Entry(
            int anchorFrameIndex,
            int tileRow,
            int tileColumn,
            TileBitstream bitstream
    ) {
        /// Creates one validated tile-list entry.
        public Entry {
            if (anchorFrameIndex < 0 || anchorFrameIndex >= 128) {
                throw new IllegalArgumentException("anchorFrameIndex out of range: " + anchorFrameIndex);
            }
            if (tileRow < 0 || tileRow >= 64) {
                throw new IllegalArgumentException("tileRow out of range: " + tileRow);
            }
            if (tileColumn < 0 || tileColumn >= 64) {
                throw new IllegalArgumentException("tileColumn out of range: " + tileColumn);
            }
            Objects.requireNonNull(bitstream, "bitstream");
        }
    }
}
