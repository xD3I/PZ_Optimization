// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.entropy.CdfContext;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;

import java.util.Objects;

/// Structural frame decoder that expands every tile into partition trees and saved motion-vector fields.

public final class FrameSyntaxDecoder {
    /// The optional compact reference state that provides the inherited frame CDF context.
    private final ReferenceFrameSyntaxState referenceCdfFrameSyntaxState;

    /// The compact reference-frame syntax states indexed by runtime reference slot.
    private final ReferenceFrameSyntaxState [] referenceFrameSyntaxStates;

    /// Whether decoded conformance values outside their specified ranges must be rejected.
    private final boolean strictStdCompliance;

    /// Creates one structural frame decoder.
    ///
    /// @param referenceCdfFrameSyntaxResult the optional reference-frame syntax result that provides the inherited frame CDF context
    public FrameSyntaxDecoder(FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult) {
        this(
                referenceCdfFrameSyntaxResult == null
                        ? null
                        : ReferenceFrameSyntaxState.from(referenceCdfFrameSyntaxResult),
                new ReferenceFrameSyntaxState[8]
        );
    }

    /// Creates one structural frame decoder with compact runtime reference syntax states.
    ///
    /// @param referenceCdfFrameSyntaxState the optional compact reference state that provides the inherited frame CDF context
    /// @param referenceFrameSyntaxStates compact syntax states indexed by runtime reference slot
    public FrameSyntaxDecoder(
            ReferenceFrameSyntaxState referenceCdfFrameSyntaxState,
            ReferenceFrameSyntaxState[] referenceFrameSyntaxStates
    ) {
        this(referenceCdfFrameSyntaxState, referenceFrameSyntaxStates, false);
    }

    /// Creates one structural frame decoder with runtime references and strict conformance policy.
    ///
    /// @param referenceCdfFrameSyntaxState the optional compact reference state that provides the inherited frame CDF context
    /// @param referenceFrameSyntaxStates compact syntax states indexed by runtime reference slot
    /// @param strictStdCompliance whether decoded conformance values outside their ranges must be rejected
    public FrameSyntaxDecoder(
            ReferenceFrameSyntaxState referenceCdfFrameSyntaxState,
            ReferenceFrameSyntaxState[] referenceFrameSyntaxStates,
            boolean strictStdCompliance
    ) {
        this.referenceCdfFrameSyntaxState = referenceCdfFrameSyntaxState;
        ReferenceFrameSyntaxState[] nonNullReferenceFrameSyntaxStates =
                Objects.requireNonNull(referenceFrameSyntaxStates, "referenceFrameSyntaxStates");
        if (nonNullReferenceFrameSyntaxStates.length != 8) {
            throw new IllegalArgumentException(
                    "referenceFrameSyntaxStates.length != 8: " + nonNullReferenceFrameSyntaxStates.length
            );
        }
        this.referenceFrameSyntaxStates = nonNullReferenceFrameSyntaxStates.clone();
        this.strictStdCompliance = strictStdCompliance;
    }

    /// Structurally decodes every collected tile in one completed frame assembly.
    ///
    /// @param assembly the completed frame assembly to decode
    /// @return the structural frame-decode result
    /// @throws InvalidFrameSyntaxException if decoded tile syntax violates AV1 structural constraints
    public FrameSyntaxDecodeResult decode(FrameAssembly assembly) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        if (!nonNullAssembly.isComplete()) {
            throw new IllegalArgumentException("Frame assembly is incomplete");
        }
        return decodeTiles(nonNullAssembly, -1);
    }

    /// Structurally decodes one tile from a partial Large Scale Tile camera-frame assembly.
    ///
    /// Tiles other than `tileIndex` are represented by empty structural state in the returned
    /// frame-sized result. The assembly must contain the selected tile bitstream but need not cover
    /// any other camera-frame tile.
    ///
    /// @param assembly the partial camera-frame assembly containing the selected tile
    /// @param tileIndex the zero-based source tile index in frame raster order
    /// @return a frame-sized structural result containing the selected decoded tile
    /// @throws InvalidFrameSyntaxException if decoded tile syntax violates AV1 constraints
    public FrameSyntaxDecodeResult decodeTile(FrameAssembly assembly, int tileIndex) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        if (tileIndex < 0 || tileIndex >= nonNullAssembly.totalTiles()) {
            throw new IllegalArgumentException("tileIndex out of range: " + tileIndex);
        }
        nonNullAssembly.tileBitstream(tileIndex);
        return decodeTiles(nonNullAssembly, tileIndex);
    }

    /// Decodes all tiles or one selected tile into frame-sized structural arrays.
    ///
    /// @param assembly the complete or partial frame assembly
    /// @param selectedTileIndex the selected tile index, or `-1` to decode every tile
    /// @return the frame-sized structural decode result
    private FrameSyntaxDecodeResult decodeTiles(FrameAssembly assembly, int selectedTileIndex) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        int tileCount = nonNullAssembly.totalTiles();
        ReferenceMotionVectorProjection referenceMotionVectorProjection =
                ReferenceMotionVectorProjection.create(nonNullAssembly, referenceFrameSyntaxStates);
        SegmentIdMap referenceSegmentIdMap = referenceSegmentIdMap(nonNullAssembly);
        SegmentIdMap currentSegmentIdMap = SegmentIdMap.create(nonNullAssembly);
        FrameHeader.SegmentationInfo segmentation = nonNullAssembly.frameHeader().segmentation();
        if (segmentation.enabled() && !segmentation.updateMap() && referenceSegmentIdMap != null) {
            currentSegmentIdMap.copyFrom(referenceSegmentIdMap);
        }
        TilePartitionTreeReader.Node[][] tileRoots = new TilePartitionTreeReader.Node[tileCount][];
        TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields =
                new TileDecodeContext.TemporalMotionField[tileCount];
        CdfContext[] finalTileCdfContexts = new CdfContext[tileCount];
        RestorationUnitMap restorationUnitMap = RestorationUnitMap.createEmpty(nonNullAssembly);
        CdfContext inheritedCdfContext = referenceCdfContext();
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            tileRoots[tileIndex] = new TilePartitionTreeReader.Node[0];
            decodedTemporalMotionFields[tileIndex] = new TileDecodeContext.TemporalMotionField(0, 0);
            if (selectedTileIndex >= 0 && tileIndex != selectedTileIndex) {
                finalTileCdfContexts[tileIndex] = CdfContext.createDefault(
                        nonNullAssembly.frameHeader().quantization().baseQIndex()
                );
                continue;
            }
            TileDecodeContext tileContext = createTileContext(
                    nonNullAssembly,
                    tileIndex,
                    referenceMotionVectorProjection,
                    currentSegmentIdMap,
                    referenceSegmentIdMap,
                    inheritedCdfContext
            );
            TilePartitionTreeReader treeReader = new TilePartitionTreeReader(tileContext, strictStdCompliance);
            try {
                tileRoots[tileIndex] = treeReader.readTile();
            } catch (IntrabcDisplacementValidator.InvalidDisplacementVectorException exception) {
                throw new InvalidFrameSyntaxException(exception.getMessage(), exception);
            }
            restorationUnitMap.mergeFrom(tileContext.restorationUnitMap());
            decodedTemporalMotionFields[tileIndex] = tileContext.decodedTemporalMotionField();
            finalTileCdfContexts[tileIndex] = tileContext.cdfContext();
        }
        return FrameSyntaxDecodeResult.fromOwnedFrameRelativeState(
                nonNullAssembly,
                tileRoots,
                decodedTemporalMotionFields,
                restorationUnitMap,
                finalTileCdfContexts,
                currentSegmentIdMap
        );
    }

    /// Creates one tile-local decode context with compatible inherited entropy state.
    ///
    /// @param assembly the completed frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index in frame order
    /// @param referenceMotionVectorProjection the immutable current-frame temporal projection
    /// @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
    /// @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
    /// @param inheritedCdfContext the inherited frame CDF context, or `null` to use defaults
    /// @return one tile-local decode context
    private TileDecodeContext createTileContext(
            FrameAssembly assembly,
            int tileIndex,
            ReferenceMotionVectorProjection referenceMotionVectorProjection,
            SegmentIdMap currentSegmentIdMap,
            SegmentIdMap referenceSegmentIdMap,
            CdfContext inheritedCdfContext
    ) {
        if (inheritedCdfContext == null) {
            return TileDecodeContext.createWithOwnedCdfContext(
                    assembly,
                    tileIndex,
                    CdfContext.createDefault(assembly.frameHeader().quantization().baseQIndex()),
                    referenceMotionVectorProjection,
                    currentSegmentIdMap,
                    referenceSegmentIdMap
            );
        }
        return TileDecodeContext.create(
                assembly,
                tileIndex,
                inheritedCdfContext,
                referenceMotionVectorProjection,
                currentSegmentIdMap,
                referenceSegmentIdMap
        );
    }

    /// Returns the compatible primary-reference segment-id map required by the current frame.
    ///
    /// AV1 temporal segmentation prediction and non-updating segmentation maps use the syntax
    /// snapshot selected by `primary_ref_frame`. A map from differently sized coded dimensions is
    /// not compatible and is treated as absent.
    ///
    /// @param assembly the current completed frame assembly
    /// @return an independent compatible reference map, or `null`
    private SegmentIdMap referenceSegmentIdMap(FrameAssembly assembly) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        FrameHeader frameHeader = nonNullAssembly.frameHeader();
        FrameHeader.SegmentationInfo segmentation = frameHeader.segmentation();
        if (!segmentation.enabled() || (segmentation.updateMap() && !segmentation.temporalUpdate())) {
            return null;
        }

        int primaryRefFrame = frameHeader.primaryRefFrame();
        if (primaryRefFrame < 0 || primaryRefFrame >= 7) {
            return null;
        }
        int primarySlot = frameHeader.referenceFrameIndex(primaryRefFrame);
        if (primarySlot < 0 || primarySlot >= referenceFrameSyntaxStates.length) {
            return null;
        }
        ReferenceFrameSyntaxState referenceState = referenceFrameSyntaxStates[primarySlot];
        if (referenceState == null) {
            return null;
        }

        SegmentIdMap referenceMap = referenceState.segmentIdMap();
        int width4 = ((frameHeader.frameSize().codedWidth() + 7) >> 3) << 1;
        int height4 = ((frameHeader.frameSize().height() + 7) >> 3) << 1;
        return referenceMap.hasDimensions(width4, height4) ? referenceMap : null;
    }

    /// Returns the single frame CDF context selected by the reference frame, or `null` when no
    /// reference context exists.
    ///
    /// @return the inherited frame CDF context, or `null`
    private CdfContext referenceCdfContext() {
        if (referenceCdfFrameSyntaxState == null) {
            return null;
        }
        return referenceCdfFrameSyntaxState.savedFrameCdfContextTemplate();
    }
}
