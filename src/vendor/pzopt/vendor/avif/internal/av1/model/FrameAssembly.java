// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


import java.util.Arrays;
import java.util.Objects;

/// Incrementally assembled AV1 frame state before pixel decoding begins.

public final class FrameAssembly {
    /// The active sequence header for this frame.
    private final SequenceHeader sequenceHeader;
    /// The parsed frame header for this frame.
    private final FrameHeader frameHeader;
    /// The refreshed reference-frame headers indexed by runtime reference slot.
    private final FrameHeader [] referenceFrameHeaders;
    /// The byte offset of the first OBU that belongs to this frame.
    private final long streamOffset;
    /// The zero-based index of the first OBU that belongs to this frame.
    private final int obuIndex;
    /// The total number of tiles declared by the frame header.
    private final int totalTiles;
    /// The tile bitstreams collected so far, indexed by frame tile index.
    private final TileBitstream[] tileBitstreams;
    /// The number of tile groups collected so far.
    private int tileGroupCount;
    /// The number of tile bitstreams collected so far.
    private int collectedTileCount;
    /// The next tile index that must be covered by the next tile group.
    private int nextTileIndex;

    /// Creates a new in-progress frame assembly.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed frame header
    /// @param streamOffset the byte offset of the first frame OBU
    /// @param obuIndex the zero-based index of the first frame OBU
    public FrameAssembly(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            long streamOffset,
            int obuIndex
    ) {
        this(sequenceHeader, frameHeader, new FrameHeader[8], streamOffset, obuIndex);
    }

    /// Creates a new in-progress frame assembly with the current reference-frame headers.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed frame header
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by runtime reference slot
    /// @param streamOffset the byte offset of the first frame OBU
    /// @param obuIndex the zero-based index of the first frame OBU
    public FrameAssembly(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            FrameHeader[] referenceFrameHeaders,
            long streamOffset,
            int obuIndex
    ) {
        this.sequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        FrameHeader[] nonNullReferenceFrameHeaders =
                Objects.requireNonNull(referenceFrameHeaders, "referenceFrameHeaders");
        if (nonNullReferenceFrameHeaders.length != 8) {
            throw new IllegalArgumentException("referenceFrameHeaders.length != 8: " + nonNullReferenceFrameHeaders.length);
        }
        this.referenceFrameHeaders = Arrays.copyOf(nonNullReferenceFrameHeaders, nonNullReferenceFrameHeaders.length);
        if (streamOffset < 0) {
            throw new IllegalArgumentException("streamOffset < 0: " + streamOffset);
        }
        if (obuIndex < 0) {
            throw new IllegalArgumentException("obuIndex < 0: " + obuIndex);
        }

        int tileCount = frameHeader.tiling().columns() * frameHeader.tiling().rows();
        if (tileCount <= 0) {
            throw new IllegalArgumentException("Frame header does not describe any tiles");
        }

        this.streamOffset = streamOffset;
        this.obuIndex = obuIndex;
        this.totalTiles = tileCount;
        this.tileBitstreams = new TileBitstream[tileCount];
    }

    /// Returns the active sequence header for this frame.
    ///
    /// @return the active sequence header for this frame
    public SequenceHeader sequenceHeader() {
        return sequenceHeader;
    }

    /// Returns the parsed frame header for this frame.
    ///
    /// @return the parsed frame header for this frame
    public FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns the refreshed reference-frame header for one internal LAST..ALTREF reference index.
    ///
    /// @param referenceFrame the internal LAST..ALTREF reference index
    /// @return the refreshed reference-frame header for the supplied reference, or `null`
    public FrameHeader referenceFrameHeader(int referenceFrame) {
        int referenceSlot = frameHeader.referenceFrameIndex(referenceFrame);
        if (referenceSlot < 0 || referenceSlot >= referenceFrameHeaders.length) {
            return null;
        }
        return referenceFrameHeaders[referenceSlot];
    }

    /// Returns the byte offset of the first OBU that belongs to this frame.
    ///
    /// @return the byte offset of the first frame OBU
    public long streamOffset() {
        return streamOffset;
    }

    /// Returns the zero-based index of the first OBU that belongs to this frame.
    ///
    /// @return the zero-based index of the first frame OBU
    public int obuIndex() {
        return obuIndex;
    }

    /// Returns the total number of tiles declared by the frame header.
    ///
    /// @return the total number of tiles declared by the frame header
    public int totalTiles() {
        return totalTiles;
    }

    /// Returns the next tile index that must be covered by the next tile group.
    ///
    /// @return the next tile index that must be covered
    public int nextTileIndex() {
        return nextTileIndex;
    }

    /// Returns whether the tile groups collected so far cover the whole frame.
    ///
    /// @return whether the tile groups collected so far cover the whole frame
    public boolean isComplete() {
        return nextTileIndex >= totalTiles;
    }

    /// Returns the number of tile groups collected so far.
    ///
    /// @return the number of tile groups collected so far
    public int tileGroupCount() {
        return tileGroupCount;
    }

    /// Returns the number of tile bitstreams collected so far.
    ///
    /// @return the number of collected tile bitstreams
    public int collectedTileCount() {
        return collectedTileCount;
    }

    /// Returns a collected tile bitstream by tile index.
    ///
    /// @param tileIndex the zero-based tile index within the frame
    /// @return the collected tile bitstream
    /// @throws IllegalArgumentException if the index is outside the frame or its tile bitstream
    /// has not been collected
    public TileBitstream tileBitstream(int tileIndex) {
        if (tileIndex < 0 || tileIndex >= totalTiles) {
            throw new IllegalArgumentException("Tile index out of frame range: " + tileIndex);
        }
        TileBitstream tile = tileBitstreams[tileIndex];
        if (tile == null) {
            throw new IllegalArgumentException("Tile bitstream has not been collected: " + tileIndex);
        }
        return tile;
    }

    /// Adds one arbitrary tile bitstream for partial camera-frame decoding.
    ///
    /// This operation may be repeated for distinct tile indices, but it must not be mixed with
    /// [#addTileGroup(TileGroupHeader, TileBitstream[])].
    ///
    /// @param tile the tile bitstream to add
    /// @throws IllegalArgumentException if the tile index is outside the frame or was already added
    /// @throws IllegalStateException if a sequential tile group was already added
    public void addTileForPartialDecode(TileBitstream tile) {
        if (tileGroupCount != 0) {
            throw new IllegalStateException("Partial tiles cannot be added after tile groups");
        }
        TileBitstream checkedTile = Objects.requireNonNull(tile, "tile");
        int tileIndex = checkedTile.tileIndex();
        if (tileIndex < 0 || tileIndex >= totalTiles) {
            throw new IllegalArgumentException("Tile index out of frame range: " + tileIndex);
        }
        if (tileBitstreams[tileIndex] != null) {
            throw new IllegalArgumentException("Tile bitstream has already been collected: " + tileIndex);
        }
        tileBitstreams[tileIndex] = checkedTile;
        collectedTileCount++;
    }

    /// Appends one tile group's bitstreams and advances the expected tile cursor.
    ///
    /// @param header the parsed tile-group header
    /// @param tiles the parsed per-tile bitstream views
    /// @throws IllegalArgumentException if the group does not continue the frame's sequential tile
    /// coverage or its tile entries do not match the header
    /// @throws IllegalStateException if an arbitrary tile was already added for partial decoding
    public void addTileGroup(TileGroupHeader header, TileBitstream[] tiles) {
        if (collectedTileCount != nextTileIndex) {
            throw new IllegalStateException("Tile groups cannot be added after partial tiles");
        }
        TileGroupHeader checkedHeader = Objects.requireNonNull(header, "header");
        if (checkedHeader.totalTileCount() != totalTiles) {
            throw new IllegalArgumentException("Tile-group header belongs to a different frame layout");
        }
        if (checkedHeader.startTileIndex() != nextTileIndex) {
            throw new IllegalArgumentException("Tile groups must be added in frame order");
        }
        TileBitstream[] checkedTiles = Objects.requireNonNull(tiles, "tiles");
        if (checkedTiles.length != checkedHeader.tileCount()) {
            throw new IllegalArgumentException("Tile entry count does not match the tile-group header");
        }
        for (int i = 0; i < checkedTiles.length; i++) {
            int expectedTileIndex = checkedHeader.startTileIndex() + i;
            TileBitstream tile = Objects.requireNonNull(checkedTiles[i], "tiles[" + i + "]");
            if (tile.tileIndex() != expectedTileIndex) {
                throw new IllegalArgumentException(
                        "Tile entry index mismatch: expected " + expectedTileIndex + " but was " + tile.tileIndex()
                );
            }
            tileBitstreams[expectedTileIndex] = tile;
        }
        tileGroupCount++;
        collectedTileCount += checkedTiles.length;
        nextTileIndex = checkedHeader.endTileIndex() + 1;
    }
}
