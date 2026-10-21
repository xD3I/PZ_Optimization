// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.entropy.CdfContext;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.SequenceHeader;

import java.util.Objects;

/// Stores the compact syntax state required when a decoded frame remains in a reference slot.
///
/// Partition trees, transform layouts, and coefficient arrays are reconstruction-only data and are
/// intentionally excluded. Instances retain the saved frame CDF, segment identifiers, temporal
/// motion field, and frame-header history required by later AV1 syntax decoding.

public final class ReferenceFrameSyntaxState {
    /// The sequence header active for the stored frame.
    private final SequenceHeader sequenceHeader;

    /// The stored frame header.
    private final FrameHeader frameHeader;

    /// The reference-frame headers indexed by the stored frame's runtime reference slots.
    private final FrameHeader [] referenceFrameHeaders;

    /// The tile-local temporal motion fields produced by the stored frame.
    private final TileDecodeContext.TemporalMotionField [] decodedTemporalMotionFields;

    /// The exact stored-frame segment identifiers.
    private final SegmentIdMap segmentIdMap;

    /// The inheritable frame CDF context with adaptive counters reset.
    private final CdfContext savedFrameCdfContext;

    /// Creates a compact reference state from one complete structural decode result.
    ///
    /// @param syntaxDecodeResult the complete structural decode result
    /// @return a compact reference state using the result's saved frame CDF
    public static ReferenceFrameSyntaxState from(FrameSyntaxDecodeResult syntaxDecodeResult) {
        FrameSyntaxDecodeResult checkedResult = Objects.requireNonNull(syntaxDecodeResult, "syntaxDecodeResult");
        return fromOwnedSavedFrameCdfContext(checkedResult, checkedResult.savedFrameCdfContext());
    }

    /// Creates a compact reference state with an explicitly selected saved frame CDF.
    ///
    /// @param syntaxDecodeResult the complete structural decode result
    /// @param savedFrameCdfContext the CDF context to inherit from the stored frame
    /// @return a compact reference state independent of the supplied mutable snapshots
    public static ReferenceFrameSyntaxState from(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            CdfContext savedFrameCdfContext
    ) {
        return fromResult(syntaxDecodeResult, savedFrameCdfContext, true);
    }

    /// Creates compact reference state by taking ownership of a fresh saved frame CDF.
    ///
    /// The caller must not access or modify `ownedSavedFrameCdfContext` after this method returns.
    /// Other retained state is obtained as independent snapshots from `syntaxDecodeResult`.
    ///
    /// @param syntaxDecodeResult the complete structural decode result
    /// @param ownedSavedFrameCdfContext the fresh CDF context transferred to the reference state
    /// @return compact reference state backed by the transferred saved CDF
    public static ReferenceFrameSyntaxState fromOwnedSavedFrameCdfContext(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            CdfContext ownedSavedFrameCdfContext
    ) {
        return fromResult(syntaxDecodeResult, ownedSavedFrameCdfContext, false);
    }

    /// Creates compact reference state from one structural result.
    ///
    /// @param syntaxDecodeResult the complete structural decode result
    /// @param savedFrameCdfContext the copied or transferred saved frame CDF
    /// @param copySavedFrameCdfContext whether to copy the supplied saved frame CDF
    /// @return compact reference state independent of the structural result
    private static ReferenceFrameSyntaxState fromResult(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            CdfContext savedFrameCdfContext,
            boolean copySavedFrameCdfContext
    ) {
        FrameSyntaxDecodeResult checkedResult = Objects.requireNonNull(syntaxDecodeResult, "syntaxDecodeResult");
        FrameAssembly assembly = checkedResult.assembly();
        FrameHeader frameHeader = assembly.frameHeader();
        FrameHeader[] referenceFrameHeaders = new FrameHeader[8];
        for (int referenceFrame = 0; referenceFrame < 7; referenceFrame++) {
            int slot = frameHeader.referenceFrameIndex(referenceFrame);
            if (slot >= 0 && slot < referenceFrameHeaders.length) {
                referenceFrameHeaders[slot] = assembly.referenceFrameHeader(referenceFrame);
            }
        }
        return new ReferenceFrameSyntaxState(
                assembly.sequenceHeader(),
                frameHeader,
                referenceFrameHeaders,
                checkedResult.decodedTemporalMotionFields(),
                checkedResult.segmentIdMap(),
                copySavedFrameCdfContext
                        ? Objects.requireNonNull(savedFrameCdfContext, "savedFrameCdfContext").copy()
                        : Objects.requireNonNull(savedFrameCdfContext, "savedFrameCdfContext")
        );
    }

    /// Creates one validated compact reference state from freshly owned snapshots.
    ///
    /// @param sequenceHeader the sequence header active for the stored frame
    /// @param frameHeader the stored frame header
    /// @param referenceFrameHeaders reference-frame headers indexed by runtime slot
    /// @param decodedTemporalMotionFields tile-local temporal motion fields
    /// @param segmentIdMap exact stored-frame segment identifiers
    /// @param savedFrameCdfContext the inheritable saved frame CDF
    private ReferenceFrameSyntaxState(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            FrameHeader[] referenceFrameHeaders,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            SegmentIdMap segmentIdMap,
            CdfContext savedFrameCdfContext
    ) {
        this.sequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        FrameHeader[] checkedHeaders = Objects.requireNonNull(
                referenceFrameHeaders,
                "referenceFrameHeaders"
        );
        if (checkedHeaders.length != 8) {
            throw new IllegalArgumentException("referenceFrameHeaders.length != 8: " + checkedHeaders.length);
        }
        this.referenceFrameHeaders = checkedHeaders;

        TileDecodeContext.TemporalMotionField[] checkedFields = Objects.requireNonNull(
                decodedTemporalMotionFields,
                "decodedTemporalMotionFields"
        );
        int expectedTileCount = frameHeader.tiling().columns() * frameHeader.tiling().rows();
        if (checkedFields.length != expectedTileCount) {
            throw new IllegalArgumentException(
                    "decodedTemporalMotionFields.length != tile count: " + checkedFields.length
            );
        }
        for (int index = 0; index < checkedFields.length; index++) {
            Objects.requireNonNull(
                    checkedFields[index],
                    "decodedTemporalMotionFields[" + index + "]"
            );
        }
        this.decodedTemporalMotionFields = checkedFields;
        this.segmentIdMap = Objects.requireNonNull(segmentIdMap, "segmentIdMap");
        this.savedFrameCdfContext = Objects.requireNonNull(savedFrameCdfContext, "savedFrameCdfContext");
    }

    /// Returns the sequence header active for the stored frame.
    ///
    /// @return the stored sequence header
    public SequenceHeader sequenceHeader() {
        return sequenceHeader;
    }

    /// Returns the stored frame header.
    ///
    /// @return the stored frame header
    public FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns one reference-frame header used by the stored frame.
    ///
    /// @param referenceFrame the internal LAST..ALTREF reference index
    /// @return the referenced frame header, or `null` when unavailable
    public FrameHeader referenceFrameHeader(int referenceFrame) {
        int slot = frameHeader.referenceFrameIndex(referenceFrame);
        return slot >= 0 && slot < referenceFrameHeaders.length ? referenceFrameHeaders[slot] : null;
    }

    /// Returns an independent copy of the stored segment-id map.
    ///
    /// @return the stored segment identifiers
    SegmentIdMap segmentIdMap() {
        return segmentIdMap.copy();
    }

    /// Returns an independent inheritable frame CDF context.
    ///
    /// @return the saved frame CDF
    public CdfContext savedFrameCdfContext() {
        return savedFrameCdfContext.copy();
    }

    /// Returns the internal saved CDF template for immediate package-local copying.
    ///
    /// The caller must not modify or retain the returned context. This view exists so tile setup
    /// can copy the immutable template once instead of first creating an intermediate snapshot.
    ///
    /// @return the internal saved frame CDF template
    CdfContext savedFrameCdfContextTemplate() {
        return savedFrameCdfContext;
    }

    /// Returns the temporal motion block at one frame-relative 8x8 coordinate.
    ///
    /// @param x8 the frame-relative X coordinate in 8x8 units
    /// @param y8 the frame-relative Y coordinate in 8x8 units
    /// @return the immutable temporal motion block, or `null` outside the stored field
    public TileDecodeContext.TemporalMotionBlock decodedTemporalMotionBlockAt(int x8, int y8) {
        if (x8 < 0 || y8 < 0) {
            return null;
        }
        int frameWidth8 = (frameHeader.frameSize().codedWidth() + 7) >> 3;
        int frameHeight8 = (frameHeader.frameSize().height() + 7) >> 3;
        if (x8 >= frameWidth8 || y8 >= frameHeight8) {
            return null;
        }

        int superblockSize8 = sequenceHeader.features().use128x128Superblocks() ? 16 : 8;
        FrameHeader.TilingInfo tiling = frameHeader.tiling();
        int tileColumn = containingTileColumn(tiling, x8 / superblockSize8);
        int tileRow = containingTileRow(tiling, y8 / superblockSize8);
        int tileIndex = tileRow * tiling.columns() + tileColumn;
        int localX8 = x8 - tiling.columnStartSuperblock(tileColumn) * superblockSize8;
        int localY8 = y8 - tiling.rowStartSuperblock(tileRow) * superblockSize8;
        TileDecodeContext.TemporalMotionField field = decodedTemporalMotionFields[tileIndex];
        if (localX8 >= field.width8() || localY8 >= field.height8()) {
            return null;
        }
        return field.block(localX8, localY8);
    }

    /// Returns the tile-column interval containing one superblock coordinate.
    ///
    /// @param tiling the frame tile layout
    /// @param coordinate the superblock coordinate to locate
    /// @return the zero-based containing interval
    private static int containingTileColumn(FrameHeader.TilingInfo tiling, int coordinate) {
        int low = 0;
        int high = tiling.columns();
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (coordinate < tiling.columnStartSuperblock(middle)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return low;
    }

    /// Returns the tile-row interval containing one superblock coordinate.
    ///
    /// @param tiling the frame tile layout
    /// @param coordinate the superblock coordinate to locate
    /// @return the zero-based containing interval
    private static int containingTileRow(FrameHeader.TilingInfo tiling, int coordinate) {
        int low = 0;
        int high = tiling.rows();
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (coordinate < tiling.rowStartSuperblock(middle)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return low;
    }
}
