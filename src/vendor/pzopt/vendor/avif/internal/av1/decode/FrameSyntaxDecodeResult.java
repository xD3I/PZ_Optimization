// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.entropy.CdfContext;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;

import java.util.Arrays;
import java.util.Objects;

/// Structural frame-decode result produced before pixel reconstruction begins.

public final class FrameSyntaxDecodeResult {
    /// The fully assembled frame that was structurally decoded.
    private final FrameAssembly assembly;

    /// The frame-relative decoded top-level partition roots for each tile in frame order.
    private final TilePartitionTreeReader.Node [] [] tileRoots;

    /// The tile-local temporal motion fields produced while decoding the current frame.
    private final TileDecodeContext.TemporalMotionField [] decodedTemporalMotionFields;

    /// The decoded frame-level loop-restoration unit syntax.
    private final RestorationUnitMap restorationUnitMap;

    /// The exact current-frame segment identifiers on the padded 4x4 coding grid.
    private final SegmentIdMap segmentIdMap;

    /// The final tile-local CDF contexts produced while decoding the current frame.
    private final CdfContext [] finalTileCdfContexts;

    /// Creates a frame result by taking ownership of freshly decoded frame-relative state.
    ///
    /// The caller must not access or modify any supplied array, map, field, or CDF context after
    /// this method returns. This entry point is reserved for the structural decoder's completed
    /// tile state; public constructors continue to make independent snapshots.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the owned frame-relative partition roots for each tile
    /// @param decodedTemporalMotionFields the owned tile-local temporal motion fields
    /// @param restorationUnitMap the owned frame-level loop-restoration unit syntax
    /// @param finalTileCdfContexts the owned final tile-local CDF contexts
    /// @param segmentIdMap the owned exact current-frame segment identifiers
    /// @return a structural frame result backed by the supplied state
    static FrameSyntaxDecodeResult fromOwnedFrameRelativeState(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            RestorationUnitMap restorationUnitMap,
            CdfContext[] finalTileCdfContexts,
            SegmentIdMap segmentIdMap
    ) {
        return new FrameSyntaxDecodeResult(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                restorationUnitMap,
                finalTileCdfContexts,
                segmentIdMap,
                true,
                true
        );
    }

    /// Creates one structural frame-decode result.
    ///
    /// Final tile CDF contexts default to fresh `CdfContext.createDefault()` copies.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the tile-relative decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
    public FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields
    ) {
        this(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                RestorationUnitMap.createEmpty(Objects.requireNonNull(assembly, "assembly")),
                createDefaultTileCdfContexts(Objects.requireNonNull(assembly, "assembly").totalTiles())
        );
    }

    /// Creates one structural frame-decode result.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the tile-relative decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
    /// @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
    public FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            CdfContext[] finalTileCdfContexts
    ) {
        this(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                RestorationUnitMap.createEmpty(Objects.requireNonNull(assembly, "assembly")),
                finalTileCdfContexts
        );
    }

    /// Creates one structural frame-decode result.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the tile-relative decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
    /// @param restorationUnitMap the decoded frame-level loop-restoration unit syntax
    /// @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
    public FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            RestorationUnitMap restorationUnitMap,
            CdfContext[] finalTileCdfContexts
    ) {
        this(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                restorationUnitMap,
                finalTileCdfContexts,
                SegmentIdMap.fromDecodedBlocks(assembly, tileRoots)
        );
    }

    /// Creates one structural frame-decode result with an exact segment-id map.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the tile-relative decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
    /// @param restorationUnitMap the decoded frame-level loop-restoration unit syntax
    /// @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
    /// @param segmentIdMap the exact current-frame segment identifiers
    FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            RestorationUnitMap restorationUnitMap,
            CdfContext[] finalTileCdfContexts,
            SegmentIdMap segmentIdMap
    ) {
        this(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                restorationUnitMap,
                finalTileCdfContexts,
                segmentIdMap,
                false
        );
    }

    /// Creates one structural frame-decode result with an exact segment-id map.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
    /// @param restorationUnitMap the decoded frame-level loop-restoration unit syntax
    /// @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
    /// @param segmentIdMap the exact current-frame segment identifiers
    /// @param frameRelativeTileRoots whether `tileRoots` already use frame-relative coordinates
    FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            RestorationUnitMap restorationUnitMap,
            CdfContext[] finalTileCdfContexts,
            SegmentIdMap segmentIdMap,
            boolean frameRelativeTileRoots
    ) {
        this(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                restorationUnitMap,
                finalTileCdfContexts,
                segmentIdMap,
                frameRelativeTileRoots,
                false
        );
    }

    /// Creates one structural frame-decode result with configurable internal ownership.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced by the frame
    /// @param restorationUnitMap the decoded frame-level loop-restoration unit syntax
    /// @param finalTileCdfContexts the final tile-local CDF contexts produced by the frame
    /// @param segmentIdMap the exact current-frame segment identifiers
    /// @param frameRelativeTileRoots whether `tileRoots` already use frame-relative coordinates
    /// @param takeOwnership whether all supplied decoded state is freshly owned and may be retained
    private FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            RestorationUnitMap restorationUnitMap,
            CdfContext[] finalTileCdfContexts,
            SegmentIdMap segmentIdMap,
            boolean frameRelativeTileRoots,
            boolean takeOwnership
    ) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(tileRoots, "tileRoots");
        Objects.requireNonNull(decodedTemporalMotionFields, "decodedTemporalMotionFields");
        Objects.requireNonNull(finalTileCdfContexts, "finalTileCdfContexts");
        if (tileRoots.length != assembly.totalTiles()) {
            throw new IllegalArgumentException("tileRoots.length != totalTiles: " + tileRoots.length);
        }
        if (decodedTemporalMotionFields.length != assembly.totalTiles()) {
            throw new IllegalArgumentException(
                    "decodedTemporalMotionFields.length != totalTiles: " + decodedTemporalMotionFields.length
            );
        }
        if (finalTileCdfContexts.length != assembly.totalTiles()) {
            throw new IllegalArgumentException("finalTileCdfContexts.length != totalTiles: " + finalTileCdfContexts.length);
        }

        TilePartitionTreeReader.Node[][] normalizedTileRoots = frameRelativeTileRoots
                ? tileRoots
                : FrameLocalPartitionTrees.create(assembly, tileRoots);
        for (int i = 0; i < normalizedTileRoots.length; i++) {
            Objects.requireNonNull(
                    normalizedTileRoots[i],
                    "tileRoots[" + i + "]"
            );
        }
        for (int i = 0; i < decodedTemporalMotionFields.length; i++) {
            Objects.requireNonNull(
                    decodedTemporalMotionFields[i],
                    "decodedTemporalMotionFields[" + i + "]"
            );
        }
        for (int i = 0; i < finalTileCdfContexts.length; i++) {
            Objects.requireNonNull(finalTileCdfContexts[i], "finalTileCdfContexts[" + i + "]");
        }

        if (takeOwnership) {
            this.tileRoots = normalizedTileRoots;
            this.decodedTemporalMotionFields = decodedTemporalMotionFields;
            this.restorationUnitMap = Objects.requireNonNull(restorationUnitMap, "restorationUnitMap");
            this.segmentIdMap = Objects.requireNonNull(segmentIdMap, "segmentIdMap");
            this.finalTileCdfContexts = finalTileCdfContexts;
            return;
        }

        this.tileRoots = new TilePartitionTreeReader.Node[normalizedTileRoots.length][];
        for (int i = 0; i < normalizedTileRoots.length; i++) {
            this.tileRoots[i] = Arrays.copyOf(normalizedTileRoots[i], normalizedTileRoots[i].length);
        }
        this.decodedTemporalMotionFields = new TileDecodeContext.TemporalMotionField[decodedTemporalMotionFields.length];
        for (int i = 0; i < decodedTemporalMotionFields.length; i++) {
            this.decodedTemporalMotionFields[i] = decodedTemporalMotionFields[i].copy();
        }
        this.restorationUnitMap = Objects.requireNonNull(restorationUnitMap, "restorationUnitMap").copy();
        this.segmentIdMap = Objects.requireNonNull(segmentIdMap, "segmentIdMap").copy();
        this.finalTileCdfContexts = new CdfContext[finalTileCdfContexts.length];
        for (int i = 0; i < finalTileCdfContexts.length; i++) {
            this.finalTileCdfContexts[i] = finalTileCdfContexts[i].copy();
        }
    }

    /// Returns the fully assembled frame that was structurally decoded.
    ///
    /// @return the fully assembled frame that was structurally decoded
    public FrameAssembly assembly() {
        return assembly;
    }

    /// Returns the number of decoded tiles in this frame result.
    ///
    /// @return the number of decoded tiles in this frame result
    public int tileCount() {
        return tileRoots.length;
    }

    /// Returns a snapshot of the frame-relative decoded top-level partition roots for every tile.
    ///
    /// @return a snapshot of the frame-relative decoded top-level partition roots for every tile
    public TilePartitionTreeReader.Node[][] tileRoots() {
        TilePartitionTreeReader.Node[][] copy = new TilePartitionTreeReader.Node[tileRoots.length][];
        for (int i = 0; i < tileRoots.length; i++) {
            copy[i] = Arrays.copyOf(tileRoots[i], tileRoots[i].length);
        }
        return copy;
    }

    /// Returns a snapshot of the frame-relative decoded top-level partition roots for one tile.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return a snapshot of the frame-relative decoded top-level partition roots for one tile
    public TilePartitionTreeReader.Node[] tileRoots(int tileIndex) {
        int checkedTileIndex = checkedTileIndex(tileIndex);
        return Arrays.copyOf(tileRoots[checkedTileIndex], tileRoots[checkedTileIndex].length);
    }

    /// Returns an independent copy of the decoded frame's segment-id map.
    ///
    /// @return an independent segment-id map copy
    SegmentIdMap segmentIdMap() {
        return segmentIdMap.copy();
    }

    /// Returns a snapshot of the tile-local temporal motion fields for every tile.
    ///
    /// @return a snapshot of the tile-local temporal motion fields for every tile
    public TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields() {
        TileDecodeContext.TemporalMotionField[] copy = new TileDecodeContext.TemporalMotionField[decodedTemporalMotionFields.length];
        for (int i = 0; i < decodedTemporalMotionFields.length; i++) {
            copy[i] = decodedTemporalMotionFields[i].copy();
        }
        return copy;
    }

    /// Returns a snapshot of the tile-local temporal motion field for one tile.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return a snapshot of the tile-local temporal motion field for one tile
    public TileDecodeContext.TemporalMotionField decodedTemporalMotionField(int tileIndex) {
        return decodedTemporalMotionFields[checkedTileIndex(tileIndex)].copy();
    }

    /// Returns a snapshot of the decoded loop-restoration unit syntax.
    ///
    /// @return a snapshot of the decoded loop-restoration unit syntax
    public RestorationUnitMap restorationUnitMap() {
        return restorationUnitMap.copy();
    }

    /// Returns a snapshot of the final tile-local CDF contexts for every tile.
    ///
    /// @return a snapshot of the final tile-local CDF contexts for every tile
    public CdfContext[] finalTileCdfContexts() {
        CdfContext[] copy = new CdfContext[finalTileCdfContexts.length];
        for (int i = 0; i < finalTileCdfContexts.length; i++) {
            copy[i] = finalTileCdfContexts[i].copy();
        }
        return copy;
    }

    /// Returns a snapshot of the final tile-local CDF context for one tile.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return a snapshot of the final tile-local CDF context for one tile
    public CdfContext finalTileCdfContext(int tileIndex) {
        return finalTileCdfContexts[checkedTileIndex(tileIndex)].copy();
    }

    /// Returns the frame CDF context saved for later `primary_ref_frame` inheritance.
    ///
    /// The context is selected by `context_update_tile_id`. Its thresholds retain the selected
    /// tile's final values, while every adaptive-symbol counter is reset as required at the frame
    /// context update boundary. The returned context is independent of this result.
    ///
    /// @return an independent inheritable frame CDF context
    public CdfContext savedFrameCdfContext() {
        return finalTileCdfContexts[assembly.frameHeader().tiling().updateTileIndex()]
                .copyWithResetSymbolCounters();
    }

    /// Validates and returns one tile index.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return the validated tile index
    private int checkedTileIndex(int tileIndex) {
        if (tileIndex < 0 || tileIndex >= tileRoots.length) {
            throw new IndexOutOfBoundsException("tileIndex out of range: " + tileIndex);
        }
        return tileIndex;
    }

    /// Creates default tile-local CDF contexts for the supplied tile count.
    ///
    /// @param tileCount the number of tiles in the frame
    /// @return default tile-local CDF contexts for the supplied tile count
    private static CdfContext[] createDefaultTileCdfContexts(int tileCount) {
        CdfContext[] contexts = new CdfContext[tileCount];
        for (int i = 0; i < tileCount; i++) {
            contexts[i] = CdfContext.createDefault();
        }
        return contexts;
    }
}
