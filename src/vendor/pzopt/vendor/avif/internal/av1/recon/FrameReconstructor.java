// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;
import pzopt.vendor.avif.av1.Av1FrameType;
import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import pzopt.vendor.avif.internal.av1.decode.TileBlockHeaderReader;
import pzopt.vendor.avif.internal.av1.decode.TilePartitionTreeReader;
import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.CompoundInterPredictionMode;
import pzopt.vendor.avif.internal.av1.model.CompoundPredictionType;
import pzopt.vendor.avif.internal.av1.model.FilterIntraMode;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.LumaIntraPredictionMode;
import pzopt.vendor.avif.internal.av1.model.InterIntraPredictionMode;
import pzopt.vendor.avif.internal.av1.model.MotionVector;
import pzopt.vendor.avif.internal.av1.model.MotionMode;
import pzopt.vendor.avif.internal.av1.model.ResidualLayout;
import pzopt.vendor.avif.internal.av1.model.SequenceHeader;
import pzopt.vendor.avif.internal.av1.model.SingleInterPredictionMode;
import pzopt.vendor.avif.internal.av1.model.TransformLayout;
import pzopt.vendor.avif.internal.av1.model.TransformResidualUnit;
import pzopt.vendor.avif.internal.av1.model.TransformType;
import pzopt.vendor.avif.internal.av1.model.TransformUnit;
import pzopt.vendor.avif.internal.av1.model.UvIntraPredictionMode;

import java.util.Arrays;
import java.util.Objects;

/// Reconstructs decoded AV1 frame syntax into luma and chroma sample planes.
///
/// Reconstruction supports AV1's 8-, 10-, and 12-bit monochrome and 4:2:0, 4:2:2, and 4:4:4
/// layouts. The pipeline covers intra, inter, compound, inter-intra, `intrabc`, OBMC, warped-motion,
/// palette, residual, and stored-reference prediction paths in the coded frame domain before
/// frame-level filtering and super-resolution are applied by the caller.
/// Instances reuse mutable scratch storage and must not reconstruct frames concurrently. Separate
/// instances have independent storage and may be used concurrently.

public final class FrameReconstructor {
    /// The number of coefficients in one 8-tap interpolation kernel.
    private static final int INTER_FILTER_TAP_COUNT = 8;

    /// The signed source-sample offset of the first tap relative to the integer source position.
    private static final int INTER_FILTER_START_OFFSET = 3;

    /// The AV1 fixed-filter normalization shift.
    private static final int INTER_FILTER_BITS = 6;

    /// The supported AV1 fractional phases for fixed interpolation filters.
    private static final int INTER_FILTER_PHASES = 16;

    /// The fixed-point precision of an AV1 reference-frame scale factor.
    private static final int REFERENCE_SCALE_BITS = 14;

    /// The identity AV1 reference-frame scale factor.
    private static final int REFERENCE_SCALE_IDENTITY = 1 << REFERENCE_SCALE_BITS;

    /// The fixed-point precision of scaled inter-prediction source coordinates.
    private static final int SCALED_INTER_SUBPEL_BITS = 10;

    /// The largest luma block dimension permitted for inter-intra prediction.
    private static final int INTER_INTRA_MAX_BLOCK_DIMENSION = 32;

    /// The identity reference geometry used by unscaled inter and same-frame `intrabc` prediction.
    private static final ReferenceScale IDENTITY_REFERENCE_SCALE =
            new ReferenceScale(REFERENCE_SCALE_IDENTITY, REFERENCE_SCALE_IDENTITY, false);

    /// Scratch storage for separable inter-prediction filtering owned by this reconstructor.
    private final InterPredictionWorkspace interPredictionWorkspace = new InterPredictionWorkspace();

    /// Intra predictor with scratch storage owned by this reconstructor.
    private final IntraPredictor intraPredictor = new IntraPredictor();

    /// Inverse transformer with scratch storage and clip state owned by this reconstructor.
    private final InverseTransformer inverseTransformer = new InverseTransformer();

    /// Reusable per-block flags that track applied chroma residual units.
    private boolean[] appliedChromaResiduals = new boolean[0];

    /// The AV1 OBMC blend masks indexed by overlap length `1, 2, 4, 8, 16, 32, 64`.
    private static final int [] [] OBMC_MASKS = {
            {64},
            {45, 64},
            {39, 50, 59, 64},
            {36, 42, 48, 53, 57, 61, 64, 64},
            {34, 37, 40, 43, 46, 49, 52, 54, 56, 58, 60, 61, 64, 64, 64, 64},
            {
                    33, 35, 36, 38, 40, 41, 43, 44, 45, 47, 48, 50, 51, 52, 53, 55,
                    56, 57, 58, 59, 60, 60, 61, 62, 64, 64, 64, 64, 64, 64, 64, 64
            },
            {
                    33, 34, 35, 35, 36, 37, 38, 39, 40, 40, 41, 42, 43, 44, 44, 44,
                    45, 46, 47, 47, 48, 49, 50, 51, 51, 51, 52, 52, 53, 54, 55, 56,
                    56, 56, 57, 57, 58, 58, 59, 60, 60, 60, 60, 60, 61, 62, 62, 62,
                    62, 62, 63, 63, 63, 63, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64
            }
    };

    /// Tile-local sample boundaries for luma and chroma prediction references.
    ///
    /// @param lumaStartX the first luma sample column available to this tile
    /// @param lumaStartY the first luma sample row available to this tile
    /// @param lumaEndX the exclusive luma sample column available to this tile
    /// @param lumaEndY the exclusive luma sample row available to this tile
    /// @param chromaStartX the first chroma sample column available to this tile
    /// @param chromaStartY the first chroma sample row available to this tile
    /// @param chromaEndX the exclusive chroma sample column available to this tile
    /// @param chromaEndY the exclusive chroma sample row available to this tile
    private record TileSampleBounds(
            int lumaStartX,
            int lumaStartY,
            int lumaEndX,
            int lumaEndY,
            int chromaStartX,
            int chromaStartY,
            int chromaEndX,
            int chromaEndY
    ) {
    }

    /// Subsampled luma footprint populated for one CFL block before edge padding.
    ///
    /// @param width the populated chroma-domain width
    /// @param height the populated chroma-domain height
    private record CflStoredSize(int width, int height) {
    }

    /// AV1 fixed-point scale factors for one stored reference surface.
    ///
    /// @param horizontalFactor the Q14 horizontal reference scale factor
    /// @param verticalFactor the Q14 vertical reference scale factor
    /// @param scaled whether either reference-frame dimension differs from the current coded frame
    private record ReferenceScale(
            int horizontalFactor,
            int verticalFactor,
            boolean scaled
    ) {
    }

    /// Reusable scratch storage for one inter-predicted block.
    
    private static final class InterPredictionWorkspace {
        /// Edge-extended reference samples reused by unscaled prediction passes.
        private short[] referenceSamples = new short[0];

        /// Horizontally filtered samples consumed by the vertical pass.
        private int[] horizontalSamples = new int[0];

        /// Primary compound predictor samples retained until blending.
        private int[] compoundPrediction0 = new int[0];

        /// Secondary compound predictor samples retained until blending.
        private int[] compoundPrediction1 = new int[0];

        /// Creates an empty inter-prediction workspace.
        private InterPredictionWorkspace() {
        }

        /// Returns edge-extended reference storage with at least the requested length.
        ///
        /// @param requiredLength the minimum number of reference samples
        /// @return reusable reference sample storage
        private short[] referenceSamples(int requiredLength) {
            if (referenceSamples.length < requiredLength) {
                referenceSamples = new short[requiredLength];
            }
            return referenceSamples;
        }

        /// Returns horizontal intermediate storage with at least the requested length.
        ///
        /// @param requiredLength the minimum number of intermediate samples
        /// @return reusable horizontal intermediate storage
        private int[] horizontalSamples(int requiredLength) {
            if (horizontalSamples.length < requiredLength) {
                horizontalSamples = new int[requiredLength];
            }
            return horizontalSamples;
        }

        /// Returns primary compound predictor storage with at least the requested length.
        ///
        /// @param requiredLength the minimum number of predictor samples
        /// @return reusable primary compound predictor storage
        private int[] compoundPrediction0(int requiredLength) {
            if (compoundPrediction0.length < requiredLength) {
                compoundPrediction0 = new int[requiredLength];
            }
            return compoundPrediction0;
        }

        /// Returns secondary compound predictor storage with at least the requested length.
        ///
        /// @param requiredLength the minimum number of predictor samples
        /// @return reusable secondary compound predictor storage
        private int[] compoundPrediction1(int requiredLength) {
            if (compoundPrediction1.length < requiredLength) {
                compoundPrediction1 = new int[requiredLength];
            }
            return compoundPrediction1;
        }
    }

    /// The default AV1 regular 8-tap subpel filters in `dav1d_mc_subpel_filters` order.
    private static final int [] [] REGULAR_SUBPEL_FILTERS = {
            {0, 1, -3, 63, 4, -1, 0, 0},
            {0, 1, -5, 61, 9, -2, 0, 0},
            {0, 1, -6, 58, 14, -4, 1, 0},
            {0, 1, -7, 55, 19, -5, 1, 0},
            {0, 1, -7, 51, 24, -6, 1, 0},
            {0, 1, -8, 47, 29, -6, 1, 0},
            {0, 1, -7, 42, 33, -6, 1, 0},
            {0, 1, -7, 38, 38, -7, 1, 0},
            {0, 1, -6, 33, 42, -7, 1, 0},
            {0, 1, -6, 29, 47, -8, 1, 0},
            {0, 1, -6, 24, 51, -7, 1, 0},
            {0, 1, -5, 19, 55, -7, 1, 0},
            {0, 1, -4, 14, 58, -6, 1, 0},
            {0, 0, -2, 9, 61, -5, 1, 0},
            {0, 0, -1, 4, 63, -3, 1, 0}
    };

    /// The default AV1 smooth 8-tap subpel filters in `dav1d_mc_subpel_filters` order.
    private static final int [] [] SMOOTH_SUBPEL_FILTERS = {
            {0, 1, 14, 31, 17, 1, 0, 0},
            {0, 0, 13, 31, 18, 2, 0, 0},
            {0, 0, 11, 31, 20, 2, 0, 0},
            {0, 0, 10, 30, 21, 3, 0, 0},
            {0, 0, 9, 29, 22, 4, 0, 0},
            {0, 0, 8, 28, 23, 5, 0, 0},
            {0, -1, 8, 27, 24, 6, 0, 0},
            {0, -1, 7, 26, 26, 7, -1, 0},
            {0, 0, 6, 24, 27, 8, -1, 0},
            {0, 0, 5, 23, 28, 8, 0, 0},
            {0, 0, 4, 22, 29, 9, 0, 0},
            {0, 0, 3, 21, 30, 10, 0, 0},
            {0, 0, 2, 20, 31, 11, 0, 0},
            {0, 0, 2, 18, 31, 13, 0, 0},
            {0, 0, 1, 17, 31, 14, 1, 0}
    };

    /// The default AV1 sharp 8-tap subpel filters in `dav1d_mc_subpel_filters` order.
    private static final int [] [] SHARP_SUBPEL_FILTERS = {
            {-1, 1, -3, 63, 4, -1, 1, 0},
            {-1, 3, -6, 62, 8, -3, 2, -1},
            {-1, 4, -9, 60, 13, -5, 3, -1},
            {-2, 5, -11, 58, 19, -7, 3, -1},
            {-2, 5, -11, 54, 24, -9, 4, -1},
            {-2, 5, -12, 50, 30, -10, 4, -1},
            {-2, 5, -12, 45, 35, -11, 5, -1},
            {-2, 6, -12, 40, 40, -12, 6, -2},
            {-1, 5, -11, 35, 45, -12, 5, -2},
            {-1, 4, -10, 30, 50, -12, 5, -2},
            {-1, 4, -9, 24, 54, -11, 5, -2},
            {-1, 3, -7, 19, 58, -11, 5, -2},
            {-1, 3, -5, 13, 60, -9, 4, -1},
            {-1, 2, -3, 8, 62, -6, 3, -1},
            {0, 1, -1, 4, 63, -3, 1, -1}
    };

    /// The reduced-width AV1 regular 8-tap subpel filters used when the sampled axis is at most
    /// four samples wide.
    private static final int [] [] SMALL_REGULAR_SUBPEL_FILTERS = {
            {0, 0, -2, 63, 4, -1, 0, 0},
            {0, 0, -4, 61, 9, -2, 0, 0},
            {0, 0, -5, 58, 14, -3, 0, 0},
            {0, 0, -6, 55, 19, -4, 0, 0},
            {0, 0, -6, 51, 24, -5, 0, 0},
            {0, 0, -7, 47, 29, -5, 0, 0},
            {0, 0, -6, 42, 33, -5, 0, 0},
            {0, 0, -6, 38, 38, -6, 0, 0},
            {0, 0, -5, 33, 42, -6, 0, 0},
            {0, 0, -5, 29, 47, -7, 0, 0},
            {0, 0, -5, 24, 51, -6, 0, 0},
            {0, 0, -4, 19, 55, -6, 0, 0},
            {0, 0, -3, 14, 58, -5, 0, 0},
            {0, 0, -2, 9, 61, -4, 0, 0},
            {0, 0, -1, 4, 63, -2, 0, 0}
    };

    /// The reduced-width AV1 smooth 8-tap subpel filters used when the sampled axis is at most
    /// four samples wide.
    private static final int [] [] SMALL_SMOOTH_SUBPEL_FILTERS = {
            {0, 0, 15, 31, 17, 1, 0, 0},
            {0, 0, 13, 31, 18, 2, 0, 0},
            {0, 0, 11, 31, 20, 2, 0, 0},
            {0, 0, 10, 30, 21, 3, 0, 0},
            {0, 0, 9, 29, 22, 4, 0, 0},
            {0, 0, 8, 28, 23, 5, 0, 0},
            {0, 0, 7, 27, 24, 6, 0, 0},
            {0, 0, 6, 26, 26, 6, 0, 0},
            {0, 0, 6, 24, 27, 7, 0, 0},
            {0, 0, 5, 23, 28, 8, 0, 0},
            {0, 0, 4, 22, 29, 9, 0, 0},
            {0, 0, 3, 21, 30, 10, 0, 0},
            {0, 0, 2, 20, 31, 11, 0, 0},
            {0, 0, 2, 18, 31, 13, 0, 0},
            {0, 0, 1, 17, 31, 15, 0, 0}
    };

    /// Creates a frame reconstructor with isolated reusable prediction and transform storage.
    ///
    /// The storage grows to the largest prediction and transform processed by this instance and is
    /// retained for reuse until the reconstructor becomes unreachable.
    public FrameReconstructor() {
    }

    /// Reconstructs one supported structural frame result into decoded planes.
    ///
    /// @param syntaxDecodeResult the structural frame result to reconstruct
    /// @return one decoded-plane snapshot
    public DecodedSurface reconstruct(FrameSyntaxDecodeResult syntaxDecodeResult) {
        return reconstruct(syntaxDecodeResult, new ReferenceSurfaceSnapshot[0]);
    }

    /// Reconstructs one supported structural frame result into decoded planes using the supplied
    /// stored reference surfaces for inter prediction.
    ///
    /// @param syntaxDecodeResult the structural frame result to reconstruct
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @return one decoded-plane snapshot
    public DecodedSurface reconstruct(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        return reconstruct(syntaxDecodeResult, referenceSurfaceSnapshots, false);
    }

    /// Reconstructs one structural frame result with optional strict transform conformance checks.
    ///
    /// @param syntaxDecodeResult the structural frame result to reconstruct
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param strictStdCompliance whether malformed transform values must be rejected
    /// @return one decoded-plane snapshot
    /// @throws InvalidFrameReconstructionException if strict reconstruction detects a nonconformant value
    public DecodedSurface reconstruct(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            boolean strictStdCompliance
    ) {
        return reconstructRegion(syntaxDecodeResult, referenceSurfaceSnapshots, strictStdCompliance, -1);
    }

    /// Reconstructs one decoded tile into compact tile-local output planes.
    ///
    /// The partition syntax and prediction coordinates remain frame-relative, but mutable sample
    /// storage is allocated only for the selected tile. Prediction references remain full-frame
    /// surfaces supplied by the caller.
    ///
    /// @param syntaxDecodeResult the structural frame result containing the decoded tile
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param tileIndex the zero-based tile index to reconstruct
    /// @param strictStdCompliance whether malformed transform values must be rejected
    /// @return compact decoded planes containing only the selected tile
    /// @throws IllegalArgumentException if `tileIndex` is outside the decoded frame's tile range
    /// @throws InvalidFrameReconstructionException if strict reconstruction detects a nonconformant value
    public DecodedSurface reconstructTile(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            int tileIndex,
            boolean strictStdCompliance
    ) {
        return reconstructRegion(syntaxDecodeResult, referenceSurfaceSnapshots, strictStdCompliance, tileIndex);
    }

    /// Reconstructs either a complete frame or one selected tile.
    ///
    /// @param syntaxDecodeResult the structural frame result to reconstruct
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param strictStdCompliance whether malformed transform values must be rejected
    /// @param selectedTileIndex the selected tile index, or `-1` for the complete frame
    /// @return the reconstructed complete-frame or compact tile planes
    private DecodedSurface reconstructRegion(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            boolean strictStdCompliance,
            int selectedTileIndex
    ) {
        FrameSyntaxDecodeResult checkedSyntaxDecodeResult = Objects.requireNonNull(syntaxDecodeResult, "syntaxDecodeResult");
        ReferenceSurfaceSnapshot[] checkedReferenceSurfaceSnapshots =
                Objects.requireNonNull(referenceSurfaceSnapshots, "referenceSurfaceSnapshots");
        FrameAssembly assembly = checkedSyntaxDecodeResult.assembly();
        SequenceHeader sequenceHeader = assembly.sequenceHeader();
        FrameHeader frameHeader = assembly.frameHeader();
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        Av1ChromaFormat chromaFormat = sequenceHeader.colorConfig().chromaFormat();

        validateFrameConfiguration(sequenceHeader, frameHeader);
        if (selectedTileIndex < -1 || selectedTileIndex >= checkedSyntaxDecodeResult.tileCount()) {
            throw new IllegalArgumentException("selectedTileIndex out of range: " + selectedTileIndex);
        }

        int alignedLumaWidth = alignedLumaDimension(frameSize.codedWidth());
        int alignedLumaHeight = alignedLumaDimension(frameSize.height());
        int frameBoundaryWidth = alignedFrameBoundaryDimension(frameSize.codedWidth());
        int frameBoundaryHeight = alignedFrameBoundaryDimension(frameSize.height());
        TileSampleBounds selectedTileBounds = selectedTileIndex >= 0
                ? tileSampleBounds(
                        assembly,
                        selectedTileIndex,
                        chromaFormat,
                        frameBoundaryWidth,
                        frameBoundaryHeight
                )
                : null;
        int storageStartX = selectedTileBounds != null ? selectedTileBounds.lumaStartX() : 0;
        int storageStartY = selectedTileBounds != null ? selectedTileBounds.lumaStartY() : 0;
        int storageEndX = selectedTileBounds != null
                ? paddedTileStorageEnd(selectedTileBounds.lumaEndX(), frameBoundaryWidth, alignedLumaWidth)
                : alignedLumaWidth;
        int storageEndY = selectedTileBounds != null
                ? paddedTileStorageEnd(selectedTileBounds.lumaEndY(), frameBoundaryHeight, alignedLumaHeight)
                : alignedLumaHeight;
        int bitDepth = sequenceHeader.colorConfig().bitDepth().bits();
        MutablePlaneBuffer lumaPlane = new MutablePlaneBuffer(
                alignedLumaWidth,
                alignedLumaHeight,
                bitDepth,
                storageStartX,
                storageStartY,
                storageEndX - storageStartX,
                storageEndY - storageStartY
        );
        MutablePlaneBuffer chromaUPlane = createChromaPlane(
                chromaFormat,
                alignedLumaWidth,
                alignedLumaHeight,
                bitDepth,
                storageStartX,
                storageStartY,
                storageEndX,
                storageEndY
        );
        MutablePlaneBuffer chromaVPlane = createChromaPlane(
                chromaFormat,
                alignedLumaWidth,
                alignedLumaHeight,
                bitDepth,
                storageStartX,
                storageStartY,
                storageEndX,
                storageEndY
        );

        TilePartitionTreeReader.Node[][] tileRootsByTile = checkedSyntaxDecodeResult.tileRoots();
        DecodedBlockMap decodedBlockMap = selectedTileBounds != null
                ? DecodedBlockMap.createRegion(
                        tileRootsByTile,
                        selectedTileBounds.lumaStartX(),
                        selectedTileBounds.lumaStartY(),
                        selectedTileBounds.lumaEndX(),
                        selectedTileBounds.lumaEndY()
                )
                : DecodedBlockMap.create(tileRootsByTile, alignedLumaWidth, alignedLumaHeight);
        int firstTileIndex = selectedTileIndex >= 0 ? selectedTileIndex : 0;
        int lastTileIndex = selectedTileIndex >= 0 ? selectedTileIndex + 1 : tileRootsByTile.length;
        for (int tileIndex = firstTileIndex; tileIndex < lastTileIndex; tileIndex++) {
            TileSampleBounds tileBounds = tileSampleBounds(
                    assembly,
                    tileIndex,
                    chromaFormat,
                    frameBoundaryWidth,
                    frameBoundaryHeight
            );
            TilePartitionTreeReader.Node[] tileRoots = tileRootsByTile[tileIndex];
            for (TilePartitionTreeReader.Node root : tileRoots) {
                reconstructNode(
                        root,
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        chromaFormat,
                        frameHeader,
                        sequenceHeader.features().orderHintBits(),
                        sequenceHeader.features().intraEdgeFilter(),
                        checkedReferenceSurfaceSnapshots,
                        decodedBlockMap,
                        tileBounds,
                        strictStdCompliance
                );
            }
        }

        int outputLumaWidth = selectedTileBounds != null
                ? selectedTileBounds.lumaEndX() - selectedTileBounds.lumaStartX()
                : frameSize.codedWidth();
        int outputLumaHeight = selectedTileBounds != null
                ? selectedTileBounds.lumaEndY() - selectedTileBounds.lumaStartY()
                : frameSize.height();
        int outputChromaWidth = chromaWidth(chromaFormat, outputLumaWidth);
        int outputChromaHeight = chromaHeight(chromaFormat, outputLumaHeight);
        PaddedPlane decodedLumaPlane = lumaPlane.takeStoredDecodedPlane(outputLumaWidth, outputLumaHeight);
        PaddedPlane decodedChromaUPlane = chromaUPlane != null
                ? chromaUPlane.takeStoredDecodedPlane(outputChromaWidth, outputChromaHeight)
                : null;
        PaddedPlane decodedChromaVPlane = chromaVPlane != null
                ? chromaVPlane.takeStoredDecodedPlane(outputChromaWidth, outputChromaHeight)
                : null;

        return new DecodedSurface(
                bitDepth,
                chromaFormat,
                outputLumaWidth,
                outputLumaHeight,
                selectedTileBounds != null ? outputLumaWidth : frameSize.renderWidth(),
                selectedTileBounds != null ? outputLumaHeight : frameSize.renderHeight(),
                decodedLumaPlane,
                decodedChromaUPlane,
                decodedChromaVPlane
        );
    }

    /// Returns the sample boundaries for one frame tile.
    ///
    /// @param assembly the frame assembly that owns tile geometry
    /// @param tileIndex the zero-based tile index in frame order
    /// @param chromaFormat the active decoded chroma layout
    /// @param lumaWidth the MI-grid-aligned luma frame width
    /// @param lumaHeight the MI-grid-aligned luma frame height
    /// @return the sample boundaries for one frame tile
    private TileSampleBounds tileSampleBounds(
            FrameAssembly assembly,
            int tileIndex,
            Av1ChromaFormat chromaFormat,
            int lumaWidth,
            int lumaHeight
    ) {
        FrameHeader.TilingInfo tiling = assembly.frameHeader().tiling();
        int columns = tiling.columns();
        int tileColumn = tileIndex % columns;
        int tileRow = tileIndex / columns;
        int superblockSize = assembly.sequenceHeader().features().use128x128Superblocks() ? 128 : 64;
        int lumaStartX = Math.min(lumaWidth, tiling.columnStartSuperblock(tileColumn) * superblockSize);
        int lumaStartY = Math.min(lumaHeight, tiling.rowStartSuperblock(tileRow) * superblockSize);
        int lumaEndX = Math.min(lumaWidth, tiling.columnStartSuperblock(tileColumn + 1) * superblockSize);
        int lumaEndY = Math.min(lumaHeight, tiling.rowStartSuperblock(tileRow + 1) * superblockSize);
        return new TileSampleBounds(
                lumaStartX,
                lumaStartY,
                lumaEndX,
                lumaEndY,
                chromaWidth(chromaFormat, lumaStartX),
                chromaHeight(chromaFormat, lumaStartY),
                chromaWidth(chromaFormat, lumaEndX),
                chromaHeight(chromaFormat, lumaEndY)
        );
    }

    /// Returns full-frame sample boundaries for callers that reconstruct a standalone tree.
    ///
    /// @param chromaFormat the active decoded chroma layout
    /// @param lumaWidth the internal luma plane width
    /// @param lumaHeight the internal luma plane height
    /// @return full-frame sample boundaries for callers that reconstruct a standalone tree
    private TileSampleBounds fullFrameSampleBounds(Av1ChromaFormat chromaFormat, int lumaWidth, int lumaHeight) {
        return new TileSampleBounds(
                0,
                0,
                lumaWidth,
                lumaHeight,
                0,
                0,
                chromaWidth(chromaFormat, lumaWidth),
                chromaHeight(chromaFormat, lumaHeight)
        );
    }

    /// Validates the sequence and frame configuration used for pixel reconstruction.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the active frame header
    private void validateFrameConfiguration(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader
    ) {
        if (sequenceHeader.colorConfig().chromaFormat() != Av1ChromaFormat.MONOCHROME
                && sequenceHeader.colorConfig().chromaFormat() != Av1ChromaFormat.YUV420
                && sequenceHeader.colorConfig().chromaFormat() != Av1ChromaFormat.YUV422
                && sequenceHeader.colorConfig().chromaFormat() != Av1ChromaFormat.YUV444) {
            throw new IllegalStateException(
                    "Pixel reconstruction requires an MONOCHROME, YUV420, YUV422, or YUV444 chroma format: "
                            + sequenceHeader.colorConfig().chromaFormat()
            );
        }
        if (frameHeader.frameType() != Av1FrameType.KEY && frameHeader.frameType() != Av1FrameType.INTRA) {
            if (frameHeader.frameType() == Av1FrameType.INTER || frameHeader.frameType() == Av1FrameType.SWITCH) {
                return;
            }
            throw new IllegalStateException(
                    "Pixel reconstruction requires a key, intra, inter, or switch frame: "
                            + frameHeader.frameType()
            );
        }
    }

    /// Creates one mutable chroma plane for the supplied chroma format, or `null` for monochrome.
    ///
    /// @param chromaFormat the active decoded chroma layout
    /// @param alignedLumaWidth the aligned luma-plane width in samples
    /// @param alignedLumaHeight the aligned luma-plane height in samples
    /// @param bitDepth the decoded sample bit depth
    /// @param lumaStorageStartX the inclusive luma storage X boundary
    /// @param lumaStorageStartY the inclusive luma storage Y boundary
    /// @param lumaStorageEndX the exclusive luma storage X boundary
    /// @param lumaStorageEndY the exclusive luma storage Y boundary
    /// @return one mutable chroma plane for the supplied chroma format, or `null` for monochrome
    private MutablePlaneBuffer createChromaPlane(
            Av1ChromaFormat chromaFormat,
            int alignedLumaWidth,
            int alignedLumaHeight,
            int bitDepth,
            int lumaStorageStartX,
            int lumaStorageStartY,
            int lumaStorageEndX,
            int lumaStorageEndY
    ) {
        int planeWidth = chromaWidth(chromaFormat, alignedLumaWidth);
        int planeHeight = chromaHeight(chromaFormat, alignedLumaHeight);
        int originX = chromaWidth(chromaFormat, lumaStorageStartX);
        int originY = chromaHeight(chromaFormat, lumaStorageStartY);
        int storageEndX = chromaWidth(chromaFormat, lumaStorageEndX);
        int storageEndY = chromaHeight(chromaFormat, lumaStorageEndY);
        return switch (chromaFormat) {
            case MONOCHROME -> null;
            case YUV420, YUV422, YUV444 -> new MutablePlaneBuffer(
                    planeWidth,
                    planeHeight,
                    bitDepth,
                    originX,
                    originY,
                    storageEndX - originX,
                    storageEndY - originY
            );
        };
    }

    /// Extends an edge tile's retained storage through reconstruction padding.
    ///
    /// @param tileEnd the exclusive tile boundary
    /// @param frameBoundary the exclusive MI-grid-aligned frame boundary
    /// @param paddedFrameEnd the exclusive reconstruction-buffer boundary
    /// @return the retained exclusive storage boundary
    private int paddedTileStorageEnd(int tileEnd, int frameBoundary, int paddedFrameEnd) {
        return tileEnd == frameBoundary ? paddedFrameEnd : tileEnd;
    }

    /// Returns an AV1 luma dimension rounded up far enough to hold a complete edge inter-intra
    /// predictor.
    ///
    /// @param dimension the uncropped luma dimension in samples
    /// @return the dimension rounded up to a multiple of 32 samples
    private int alignedLumaDimension(int dimension) {
        return (dimension + INTER_INTRA_MAX_BLOCK_DIMENSION - 1) & -INTER_INTRA_MAX_BLOCK_DIMENSION;
    }

    /// Returns the luma boundary represented by the AV1 frame MI grid.
    ///
    /// @param dimension the uncropped luma dimension in samples
    /// @return the dimension rounded up to the eight-sample MI-grid boundary
    private int alignedFrameBoundaryDimension(int dimension) {
        return (dimension + 7) & ~7;
    }

    /// Returns the chroma-plane width for one output luma width.
    ///
    /// @param chromaFormat the active decoded chroma format
    /// @param lumaWidth the output luma width in samples
    /// @return the chroma-plane width for the supplied luma width
    private int chromaWidth(Av1ChromaFormat chromaFormat, int lumaWidth) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420, YUV422 -> (lumaWidth + 1) >> 1;
            case YUV444 -> lumaWidth;
        };
    }

    /// Returns the chroma-plane height for one output luma height.
    ///
    /// @param chromaFormat the active decoded chroma format
    /// @param lumaHeight the output luma height in samples
    /// @return the chroma-plane height for the supplied luma height
    private int chromaHeight(Av1ChromaFormat chromaFormat, int lumaHeight) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420 -> (lumaHeight + 1) >> 1;
            case YUV422, YUV444 -> lumaHeight;
        };
    }

    /// Chroma plane selector used when reusing the shared OBMC prediction path.
    private enum ChromaPlane {
        /// Chroma U plane.
        U,
        /// Chroma V plane.
        V
    }

    /// Frame-local leaf lookup table indexed in 4x4 units.
    
    private static final class DecodedBlockMap {
        /// The horizontal map origin in 4x4 units.
        private final int originX4;

        /// The vertical map origin in 4x4 units.
        private final int originY4;

        /// The retained map width in 4x4 units.
        private final int width4;

        /// The retained map height in 4x4 units.
        private final int height4;

        /// The leaf nodes indexed by 4x4 position.
        private final TilePartitionTreeReader.LeafNode[] leaves;

        /// The partition traversal order of each mapped 4x4 position.
        private final int[] decodeOrders;

        /// The traversal order assigned to the next leaf.
        private int nextDecodeOrder;

        /// Creates one decoded block map.
        ///
        /// @param originX4 the horizontal map origin in 4x4 units
        /// @param originY4 the vertical map origin in 4x4 units
        /// @param width4 the retained map width in 4x4 units
        /// @param height4 the retained map height in 4x4 units
        private DecodedBlockMap(int originX4, int originY4, int width4, int height4) {
            if (originX4 < 0) {
                throw new IllegalArgumentException("originX4 < 0: " + originX4);
            }
            if (originY4 < 0) {
                throw new IllegalArgumentException("originY4 < 0: " + originY4);
            }
            if (width4 <= 0) {
                throw new IllegalArgumentException("width4 <= 0: " + width4);
            }
            if (height4 <= 0) {
                throw new IllegalArgumentException("height4 <= 0: " + height4);
            }
            this.originX4 = originX4;
            this.originY4 = originY4;
            this.width4 = width4;
            this.height4 = height4;
            this.leaves = new TilePartitionTreeReader.LeafNode[Math.multiplyExact(width4, height4)];
            this.decodeOrders = new int[this.leaves.length];
            Arrays.fill(this.decodeOrders, -1);
        }

        /// Creates one decoded block map from decoded tile partition roots.
        ///
        /// @param tileRootsByTile the decoded partition roots grouped by tile
        /// @param frameWidth the coded frame width in pixels
        /// @param frameHeight the coded frame height in pixels
        /// @return one decoded block map from decoded tile partition roots
        public static DecodedBlockMap create(
                TilePartitionTreeReader.Node[][] tileRootsByTile,
                int frameWidth,
                int frameHeight
        ) {
            return createRegion(tileRootsByTile, 0, 0, frameWidth, frameHeight);
        }

        /// Creates one decoded block map retaining only one frame-relative rectangular region.
        ///
        /// @param tileRootsByTile the decoded partition roots grouped by tile
        /// @param startX the inclusive horizontal region boundary in pixels
        /// @param startY the inclusive vertical region boundary in pixels
        /// @param endX the exclusive horizontal region boundary in pixels
        /// @param endY the exclusive vertical region boundary in pixels
        /// @return one decoded block map for the selected region
        public static DecodedBlockMap createRegion(
                TilePartitionTreeReader.Node[][] tileRootsByTile,
                int startX,
                int startY,
                int endX,
                int endY
        ) {
            if (startX < 0 || startY < 0 || endX <= startX || endY <= startY) {
                throw new IllegalArgumentException("Invalid decoded block map region");
            }
            int originX4 = startX >> 2;
            int originY4 = startY >> 2;
            int endX4 = (endX + 3) >> 2;
            int endY4 = (endY + 3) >> 2;
            DecodedBlockMap map = new DecodedBlockMap(
                    originX4,
                    originY4,
                    endX4 - originX4,
                    endY4 - originY4
            );
            for (TilePartitionTreeReader.Node[] tileRoots : Objects.requireNonNull(tileRootsByTile, "tileRootsByTile")) {
                for (TilePartitionTreeReader.Node root : tileRoots) {
                    map.addNode(root);
                }
            }
            return map;
        }

        /// Adds one decoded partition node and all descendant leaves to this map.
        ///
        /// @param node the decoded partition node
        private void addNode(TilePartitionTreeReader.Node node) {
            TilePartitionTreeReader.Node nonNullNode = Objects.requireNonNull(node, "node");
            if (nonNullNode instanceof TilePartitionTreeReader.LeafNode leafNode) {
                addLeaf(leafNode);
                return;
            }
            TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) nonNullNode;
            for (int childIndex = 0; childIndex < partitionNode.childCount(); childIndex++) {
                addNode(partitionNode.child(childIndex));
            }
        }

        /// Adds one decoded leaf to every 4x4 position it covers.
        ///
        /// @param leafNode the decoded partition leaf
        private void addLeaf(TilePartitionTreeReader.LeafNode leafNode) {
            TileBlockHeaderReader.BlockHeader header = Objects.requireNonNull(leafNode, "leafNode").header();
            int decodeOrder = nextDecodeOrder++;
            int endX4 = Math.min(originX4 + width4, header.position().x4() + header.size().width4());
            int endY4 = Math.min(originY4 + height4, header.position().y4() + header.size().height4());
            for (int y4 = Math.max(originY4, header.position().y4()); y4 < endY4; y4++) {
                for (int x4 = Math.max(originX4, header.position().x4()); x4 < endX4; x4++) {
                    int index = storageIndex(x4, y4);
                    leaves[index] = leafNode;
                    decodeOrders[index] = decodeOrder;
                }
            }
        }

        /// Returns the decoded leaf that covers one 4x4 position.
        ///
        /// @param x4 the horizontal 4x4 coordinate
        /// @param y4 the vertical 4x4 coordinate
        /// @return the decoded leaf that covers the position, or `null`
        public TilePartitionTreeReader.LeafNode leafAt(int x4, int y4) {
            if (x4 < originX4
                    || y4 < originY4
                    || x4 >= originX4 + width4
                    || y4 >= originY4 + height4) {
                return null;
            }
            return leaves[storageIndex(x4, y4)];
        }

        /// Returns whether one candidate leaf precedes the current leaf in partition traversal.
        ///
        /// @param candidate the candidate causal leaf
        /// @param current the current leaf
        /// @return whether the candidate was decoded before the current leaf
        public boolean isCausal(
                TilePartitionTreeReader.LeafNode candidate,
                TilePartitionTreeReader.LeafNode current
        ) {
            TileBlockHeaderReader.BlockHeader candidateHeader = Objects.requireNonNull(candidate, "candidate").header();
            TileBlockHeaderReader.BlockHeader currentHeader = Objects.requireNonNull(current, "current").header();
            int candidateIndex = storageIndex(candidateHeader.position().x4(), candidateHeader.position().y4());
            int currentIndex = storageIndex(currentHeader.position().x4(), currentHeader.position().y4());
            return decodeOrders[candidateIndex] >= 0 && decodeOrders[candidateIndex] < decodeOrders[currentIndex];
        }

        /// Returns the decoded leaf that owns one chroma-grid position.
        ///
        /// Subsampled chroma syntax may be attached to only one of several luma leaves sharing the
        /// same chroma cell. The search therefore examines the corresponding luma-grid footprint
        /// from bottom-right to top-left and returns the leaf that actually carries chroma state.
        ///
        /// @param chromaX4 the horizontal coordinate in 4x4 chroma units
        /// @param chromaY4 the vertical coordinate in 4x4 chroma units
        /// @param chromaSubsamplingX the horizontal chroma subsampling shift
        /// @param chromaSubsamplingY the vertical chroma subsampling shift
        /// @return the decoded chroma-owning leaf, or `null`
        public TilePartitionTreeReader.LeafNode chromaLeafAt(
                int chromaX4,
                int chromaY4,
                int chromaSubsamplingX,
                int chromaSubsamplingY
        ) {
            if (chromaX4 < 0 || chromaY4 < 0) {
                return null;
            }
            int startX4 = Math.max(originX4, chromaX4 << chromaSubsamplingX);
            int startY4 = Math.max(originY4, chromaY4 << chromaSubsamplingY);
            int endX4 = Math.min(originX4 + width4, (chromaX4 + 1) << chromaSubsamplingX);
            int endY4 = Math.min(originY4 + height4, (chromaY4 + 1) << chromaSubsamplingY);
            for (int y4 = endY4 - 1; y4 >= startY4; y4--) {
                for (int x4 = endX4 - 1; x4 >= startX4; x4--) {
                    TilePartitionTreeReader.LeafNode leafNode = leafAt(x4, y4);
                    if (leafNode != null && leafNode.header().hasChroma()) {
                        return leafNode;
                    }
                }
            }
            return null;
        }

        /// Returns the compact map index for one retained frame-relative 4x4 coordinate.
        ///
        /// @param x4 the horizontal 4x4 coordinate
        /// @param y4 the vertical 4x4 coordinate
        /// @return the compact map index
        private int storageIndex(int x4, int y4) {
            int localX4 = x4 - originX4;
            int localY4 = y4 - originY4;
            if (localX4 < 0 || localX4 >= width4 || localY4 < 0 || localY4 >= height4) {
                throw new IndexOutOfBoundsException("Block coordinate outside retained map: " + x4 + ", " + y4);
            }
            return localY4 * width4 + localX4;
        }
    }

    /// Recursively reconstructs one partition-tree node using the supplied decoded-block map.
    ///
    /// @param node the partition-tree node to reconstruct
    /// @param lumaPlane the mutable luma plane
    /// @param chromaUPlane the mutable chroma U plane, or `null`
    /// @param chromaVPlane the mutable chroma V plane, or `null`
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructNode(
            TilePartitionTreeReader.Node node,
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int orderHintBits,
            boolean intraEdgeFilterEnabled,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds,
            boolean strictStdCompliance
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            reconstructLeaf(
                    leafNode,
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    chromaFormat,
                    frameHeader,
                    orderHintBits,
                    intraEdgeFilterEnabled,
                    referenceSurfaceSnapshots,
                    decodedBlockMap,
                    tileBounds,
                    strictStdCompliance
            );
            return;
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (int childIndex = 0; childIndex < partitionNode.childCount(); childIndex++) {
            reconstructNode(
                    partitionNode.child(childIndex),
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    chromaFormat,
                    frameHeader,
                    orderHintBits,
                    intraEdgeFilterEnabled,
                    referenceSurfaceSnapshots,
                    decodedBlockMap,
                    tileBounds,
                    strictStdCompliance
            );
        }
    }

    /// Reconstructs one supported partition-tree leaf.
    ///
    /// @param leafNode the partition-tree leaf to reconstruct
    /// @param lumaPlane the mutable luma plane
    /// @param chromaUPlane the mutable chroma U plane, or `null`
    /// @param chromaVPlane the mutable chroma V plane, or `null`
    /// @param chromaFormat the active decoded chroma layout
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence
    /// @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructLeaf(
            TilePartitionTreeReader.LeafNode leafNode,
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int orderHintBits,
            boolean intraEdgeFilterEnabled,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds,
            boolean strictStdCompliance
    ) {
        TileBlockHeaderReader.BlockHeader header = leafNode.header();
        TransformLayout transformLayout = leafNode.transformLayout();
        ResidualLayout residualLayout = leafNode.residualLayout();
        validateLeaf(
                header,
                transformLayout,
                residualLayout,
                chromaFormat
        );

        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        boolean lumaResidualsApplied = false;

        if (header.useIntrabc()) {
            reconstructIntrabcPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader
            );
        } else if (header.intra()) {
            if (header.yPaletteSize() != 0) {
                reconstructLumaPalette(
                        lumaPlane,
                        header,
                        lumaX,
                        lumaY,
                        header.size().widthPixels(),
                        header.size().heightPixels()
                );
            } else if (header.filterIntraMode() != null) {
                reconstructFilterIntraLuma(
                        lumaPlane,
                        residualLayout,
                        header,
                        frameHeader,
                        header.filterIntraMode(),
                        tileBounds,
                        strictStdCompliance
                );
                lumaResidualsApplied = true;
            } else {
                boolean smoothEdgeReferences = lumaSmoothEdgeReferences(header, decodedBlockMap, tileBounds);
                reconstructIntraLuma(
                        lumaPlane,
                        residualLayout,
                        header,
                        frameHeader,
                        intraEdgeFilterEnabled,
                        smoothEdgeReferences,
                        tileBounds,
                        strictStdCompliance
                );
                lumaResidualsApplied = true;
            }
        } else {
            reconstructInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader,
                    orderHintBits,
                    referenceSurfaceSnapshots,
                    decodedBlockMap,
                    tileBounds
            );
        }

        if (!lumaResidualsApplied) {
            reconstructLumaResiduals(lumaPlane, residualLayout, header, frameHeader, strictStdCompliance);
        }

        if (header.hasChroma() && chromaUPlane != null && chromaVPlane != null) {
            int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
            int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
            int chromaX = chromaBlockX(header, chromaSubsamplingX);
            int chromaY = chromaBlockY(header, chromaSubsamplingY);
            int chromaLumaX = chromaLumaBlockX(header, chromaSubsamplingX);
            int chromaLumaY = chromaLumaBlockY(header, chromaSubsamplingY);
            int visibleChromaWidth = visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX);
            int visibleChromaHeight = visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY);
            int codedChromaWidth = codedChromaBlockWidth(header, chromaSubsamplingX);
            int codedChromaHeight = codedChromaBlockHeight(header, chromaSubsamplingY);
            boolean chromaResidualsApplied = false;
            if (header.intra()) {
                if (header.uvPaletteSize() != 0) {
                    reconstructChromaPalette(
                            chromaUPlane,
                            chromaVPlane,
                            header,
                            chromaFormat,
                            visibleChromaWidth,
                            visibleChromaHeight
                    );
                } else if (header.uvMode() == UvIntraPredictionMode.CFL) {
                    CflStoredSize cflStoredSize = cflStoredSize(
                            transformLayout,
                            chromaLumaX,
                            chromaLumaY,
                            codedChromaWidth,
                            codedChromaHeight,
                            chromaSubsamplingX,
                            chromaSubsamplingY
                    );
                    intraPredictor.predictChromaCfl(
                            chromaUPlane,
                            lumaPlane,
                            chromaX,
                            chromaY,
                            chromaLumaX,
                            chromaLumaY,
                            codedChromaWidth,
                            codedChromaHeight,
                            header.cflAlphaU(),
                            chromaSubsamplingX,
                            chromaSubsamplingY,
                            cflStoredSize.width(),
                            cflStoredSize.height(),
                            tileBounds.chromaStartX(),
                            tileBounds.chromaStartY(),
                            tileBounds.chromaEndX(),
                            tileBounds.chromaEndY()
                    );
                    intraPredictor.predictChromaCfl(
                            chromaVPlane,
                            lumaPlane,
                            chromaX,
                            chromaY,
                            chromaLumaX,
                            chromaLumaY,
                            codedChromaWidth,
                            codedChromaHeight,
                            header.cflAlphaV(),
                            chromaSubsamplingX,
                            chromaSubsamplingY,
                            cflStoredSize.width(),
                            cflStoredSize.height(),
                            tileBounds.chromaStartX(),
                            tileBounds.chromaStartY(),
                            tileBounds.chromaEndX(),
                            tileBounds.chromaEndY()
                    );
                } else {
                    reconstructIntraChroma(
                            chromaUPlane,
                            chromaVPlane,
                            transformLayout,
                            residualLayout,
                            header,
                            frameHeader,
                            chromaFormat,
                            intraEdgeFilterEnabled,
                            chromaSmoothEdgeReferences(header, decodedBlockMap, chromaFormat, tileBounds),
                            tileBounds,
                            strictStdCompliance
                    );
                    chromaResidualsApplied = true;
                }
            }

            if (!chromaResidualsApplied) {
                reconstructChromaResiduals(
                        chromaUPlane,
                        chromaVPlane,
                        residualLayout,
                        frameHeader,
                        chromaFormat,
                        blockQIndex(header, frameHeader),
                        strictStdCompliance
                );
            }
        }
    }

    /// Returns whether the luma intra-edge references adjacent to one block are marked smooth.
    ///
    /// @param header the current decoded block header
    /// @param decodedBlockMap the decoded leaf map used for causal neighbor lookup
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    /// @return whether an adjacent top or left luma edge comes from a smooth intra predictor
    private boolean lumaSmoothEdgeReferences(
            TileBlockHeaderReader.BlockHeader header,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds
    ) {
        int x4 = header.position().x4();
        int y4 = header.position().y4();
        int lumaX = x4 << 2;
        int lumaY = y4 << 2;
        return (lumaY > tileBounds.lumaStartY() && hasSmoothLumaMode(decodedBlockMap.leafAt(x4, y4 - 1)))
                || (lumaX > tileBounds.lumaStartX() && hasSmoothLumaMode(decodedBlockMap.leafAt(x4 - 1, y4)));
    }

    /// Returns whether the chroma intra-edge references adjacent to one block are marked smooth.
    ///
    /// @param header the current decoded block header
    /// @param decodedBlockMap the decoded leaf map used for causal neighbor lookup
    /// @param chromaFormat the active decoded chroma layout
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    /// @return whether an adjacent top or left chroma edge comes from a smooth intra predictor
    private boolean chromaSmoothEdgeReferences(
            TileBlockHeaderReader.BlockHeader header,
            DecodedBlockMap decodedBlockMap,
            Av1ChromaFormat chromaFormat,
            TileSampleBounds tileBounds
    ) {
        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int chromaX = chromaBlockX(header, chromaSubsamplingX);
        int chromaY = chromaBlockY(header, chromaSubsamplingY);
        int chromaX4 = chromaX >> 2;
        int chromaY4 = chromaY >> 2;
        return (chromaY > tileBounds.chromaStartY()
                && hasSmoothChromaMode(decodedBlockMap.chromaLeafAt(
                        chromaX4,
                        chromaY4 - 1,
                        chromaSubsamplingX,
                        chromaSubsamplingY
                )))
                || (chromaX > tileBounds.chromaStartX()
                && hasSmoothChromaMode(decodedBlockMap.chromaLeafAt(
                        chromaX4 - 1,
                        chromaY4,
                        chromaSubsamplingX,
                        chromaSubsamplingY
                )));
    }

    /// Returns whether one decoded leaf used a smooth luma intra mode.
    ///
    /// @param leafNode the decoded neighbor leaf, or `null`
    /// @return whether the leaf used a smooth luma intra mode
    private boolean hasSmoothLumaMode(TilePartitionTreeReader.LeafNode leafNode) {
        if (leafNode == null || !leafNode.header().intra()) {
            return false;
        }
        return isSmoothLumaMode(leafNode.header().yMode());
    }

    /// Returns whether one decoded leaf used a smooth chroma intra mode.
    ///
    /// @param leafNode the decoded neighbor leaf, or `null`
    /// @return whether the leaf used a smooth chroma intra mode
    private boolean hasSmoothChromaMode(TilePartitionTreeReader.LeafNode leafNode) {
        if (leafNode == null || !leafNode.header().intra() || !leafNode.header().hasChroma()) {
            return false;
        }
        return isSmoothChromaMode(leafNode.header().uvMode());
    }

    /// Returns whether one luma intra mode is a smooth predictor.
    ///
    /// @param mode the luma intra mode, or `null`
    /// @return whether the mode is smooth
    private boolean isSmoothLumaMode(LumaIntraPredictionMode mode) {
        return mode == LumaIntraPredictionMode.SMOOTH
                || mode == LumaIntraPredictionMode.SMOOTH_VERTICAL
                || mode == LumaIntraPredictionMode.SMOOTH_HORIZONTAL;
    }

    /// Returns whether one chroma intra mode is a smooth predictor.
    ///
    /// @param mode the chroma intra mode, or `null`
    /// @return whether the mode is smooth
    private boolean isSmoothChromaMode(UvIntraPredictionMode mode) {
        return mode == UvIntraPredictionMode.SMOOTH
                || mode == UvIntraPredictionMode.SMOOTH_VERTICAL
                || mode == UvIntraPredictionMode.SMOOTH_HORIZONTAL;
    }

    /// Validates one decoded partition-tree leaf before pixel reconstruction.
    ///
    /// @param header the decoded block header
    /// @param transformLayout the decoded block transform layout
    /// @param residualLayout the decoded block residual layout
    /// @param chromaFormat the active decoded chroma layout
    private void validateLeaf(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            ResidualLayout residualLayout,
            Av1ChromaFormat chromaFormat
    ) {
        if (header.useIntrabc()) {
            if (header.motionVector0() == null || !header.motionVector0().resolved()) {
                throw new IllegalStateException("intrabc reconstruction requires one resolved motion vector");
            }
            if (header.compoundReference()) {
                throw new IllegalStateException("intrabc reconstruction does not support compound references");
            }
        }
        if (header.hasChroma() && chromaFormat == Av1ChromaFormat.MONOCHROME) {
            throw new IllegalStateException("Monochrome reconstruction encountered a block with chroma samples");
        }
        if (!header.hasChroma() && residualLayout.hasChromaUnits()) {
            throw new IllegalStateException("Chroma residuals require a block with chroma samples");
        }
        if (header.hasChroma()) {
            if (header.intra() && header.uvPaletteSize() == 0 && header.uvMode() == null) {
                throw new IllegalStateException("Chroma reconstruction requires uvMode");
            }
            if (residualLayout.hasChromaUnits() && transformLayout.chromaTransformSize() == null) {
                throw new IllegalStateException("Chroma residuals require a chroma transform size");
            }
        }
        if (transformLayout.visibleWidth4() <= 0 || transformLayout.visibleHeight4() <= 0) {
            throw new IllegalStateException("Empty transform layout is not reconstructable");
        }
        if (!header.intra() && !header.useIntrabc()) {
            if (header.motionVector0() == null) {
                throw new IllegalStateException("Inter reconstruction requires one resolved primary motion vector");
            }
            if (!header.motionVector0().resolved()) {
                throw new IllegalStateException("Inter reconstruction requires one resolved primary motion vector");
            }
            if (header.compoundReference()) {
                if (header.motionVector1() == null) {
                    throw new IllegalStateException("Compound inter reconstruction requires one resolved secondary motion vector");
                }
                if (!header.motionVector1().resolved()) {
                    throw new IllegalStateException("Compound inter reconstruction requires one resolved secondary motion vector");
                }
            }
            if (header.yPaletteSize() != 0 || header.uvPaletteSize() != 0) {
                throw new IllegalStateException("Inter blocks must not carry palette syntax");
            }
            if (header.interIntra() && !InterIntraMasks.supportsInterIntra(header.size())) {
                throw new IllegalStateException("Inter-intra reconstruction encountered an unsupported block size");
            }
        }
    }

    /// Reconstructs the inter prediction for one decoded block.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    private void reconstructInterPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int orderHintBits,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds
    ) {
        int frameLumaWidth = frameHeader.frameSize().codedWidth();
        int frameLumaHeight = frameHeader.frameSize().height();
        if (header.compoundReference()) {
            reconstructCompoundInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader,
                    frameLumaWidth,
                    frameLumaHeight,
                    orderHintBits,
                    referenceSurfaceSnapshots
            );
        } else {
            if (usesGlobalWarpedPrediction(header, frameHeader)) {
                reconstructGlobalWarpedInterPrediction(
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        transformLayout,
                        chromaFormat,
                        frameHeader,
                        frameLumaWidth,
                        frameLumaHeight,
                        referenceSurfaceSnapshots,
                        decodedBlockMap
                );
            } else if (header.motionMode() == MotionMode.LOCAL_WARPED) {
                reconstructLocalWarpedInterPrediction(
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        transformLayout,
                        chromaFormat,
                        frameHeader,
                        frameLumaWidth,
                        frameLumaHeight,
                        referenceSurfaceSnapshots,
                        decodedBlockMap,
                        tileBounds
                );
            } else {
                reconstructSingleReferenceInterPrediction(
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        transformLayout,
                        chromaFormat,
                        frameHeader,
                        frameLumaWidth,
                        frameLumaHeight,
                        referenceSurfaceSnapshots,
                        decodedBlockMap
                );
            }
            if (header.interIntra()) {
                applyInterIntraPrediction(
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        transformLayout,
                        chromaFormat,
                        tileBounds
                );
            }
            if (header.motionMode() == MotionMode.OBMC) {
                applyObmcPrediction(
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        transformLayout,
                        chromaFormat,
                        frameHeader,
                        referenceSurfaceSnapshots,
                        decodedBlockMap,
                        tileBounds
                );
            }
        }
    }

    /// Applies OBMC blending to a single-reference inter predictor already stored in the output planes.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the OBMC state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to find causal neighbors
    /// @param tileBounds the tile boundaries that constrain causal neighbor lookup
    private void applyObmcPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds
    ) {
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        applyObmcAboveNeighbors(
                lumaPlane,
                null,
                header,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                0,
                0,
                frameHeader,
                chromaFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds
        );
        applyObmcLeftNeighbors(
                lumaPlane,
                null,
                header,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                0,
                0,
                frameHeader,
                chromaFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds
        );

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int chromaX = chromaBlockX(header, chromaSubsamplingX);
        int chromaY = chromaBlockY(header, chromaSubsamplingY);
        int visibleChromaWidth = visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX);
        int visibleChromaHeight = visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY);
        applyObmcAboveNeighbors(
                chromaUPlane,
                ChromaPlane.U,
                header,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                frameHeader,
                chromaFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds
        );
        applyObmcLeftNeighbors(
                chromaUPlane,
                ChromaPlane.U,
                header,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                frameHeader,
                chromaFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds
        );
        applyObmcAboveNeighbors(
                chromaVPlane,
                ChromaPlane.V,
                header,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                frameHeader,
                chromaFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds
        );
        applyObmcLeftNeighbors(
                chromaVPlane,
                ChromaPlane.V,
                header,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                frameHeader,
                chromaFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds
        );
    }

    /// Applies OBMC blending from already-decoded above neighbors.
    ///
    /// @param destinationPlane the mutable destination plane containing the current predictor
    /// @param chromaPlane the chroma plane selector, or `null` for luma
    /// @param header the decoded block header that owns the OBMC state
    /// @param destinationX the plane-local block origin X
    /// @param destinationY the plane-local block origin Y
    /// @param visibleWidth the visible block width in plane samples
    /// @param visibleHeight the visible block height in plane samples
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    /// @param frameHeader the frame header that owns the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to find causal neighbors
    /// @param tileBounds the tile boundaries that constrain causal neighbor lookup
    private void applyObmcAboveNeighbors(
            MutablePlaneBuffer destinationPlane,
            ChromaPlane chromaPlane,
            TileBlockHeaderReader.BlockHeader header,
            int destinationX,
            int destinationY,
            int visibleWidth,
            int visibleHeight,
            int subsamplingX,
            int subsamplingY,
            FrameHeader frameHeader,
            Av1ChromaFormat chromaFormat,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds
    ) {
        int blockX4 = header.position().x4();
        int blockY4 = header.position().y4();
        if (blockY4 <= tileBounds.lumaStartY() >> 2 || visibleWidth <= 0 || visibleHeight <= 0) {
            return;
        }
        if (chromaPlane != null
                && header.size().width4() * (4 >> subsamplingX)
                + header.size().height4() * (4 >> subsamplingY) < 16) {
            return;
        }
        int lumaOverlap = Math.min(header.size().heightPixels(), 64) >> 1;
        int fullOverlap = Math.max(1, lumaOverlap >> subsamplingY);
        int overlap = Math.min(visibleHeight, fullOverlap);
        int[] mask = obmcMask(fullOverlap);
        int maxNeighbors = maximumObmcNeighbors(header.size().width4());
        int processed = 0;
        int endX4 = blockX4 + header.size().width4();
        int scanX4 = blockX4;
        while (scanX4 < endX4 && processed < maxNeighbors) {
            TilePartitionTreeReader.LeafNode neighbor = decodedBlockMap.leafAt(scanX4 + 1, blockY4 - 1);
            if (neighbor == null) {
                scanX4 += 2;
                continue;
            }
            TileBlockHeaderReader.BlockHeader neighborHeader = neighbor.header();
            int step4 = Math.max(2, Math.min(16, neighborHeader.size().width4()));
            if (isObmcNeighbor(neighborHeader)) {
                int relativeLumaX = Math.max(0, scanX4 - blockX4) << 2;
                int planeRelativeX = relativeLumaX >> subsamplingX;
                int overlapWidth4 = Math.min(step4, header.size().width4());
                int predictionWidth = Math.max(1, (overlapWidth4 << 2) >> subsamplingX);
                int planeWidth = Math.min(
                        visibleWidth - planeRelativeX,
                        predictionWidth
                );
                if (planeWidth > 0) {
                    blendObmcRegion(
                            destinationPlane,
                            chromaPlane,
                            neighborHeader,
                            destinationX + planeRelativeX,
                            destinationY,
                            planeWidth,
                            overlap,
                            predictionWidth,
                            fullOverlap,
                            mask,
                            true,
                            frameHeader,
                            chromaFormat,
                            referenceSurfaceSnapshots
                    );
                    processed++;
                }
            }
            scanX4 += step4;
        }
    }

    /// Applies OBMC blending from already-decoded left neighbors.
    ///
    /// @param destinationPlane the mutable destination plane containing the current predictor
    /// @param chromaPlane the chroma plane selector, or `null` for luma
    /// @param header the decoded block header that owns the OBMC state
    /// @param destinationX the plane-local block origin X
    /// @param destinationY the plane-local block origin Y
    /// @param visibleWidth the visible block width in plane samples
    /// @param visibleHeight the visible block height in plane samples
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    /// @param frameHeader the frame header that owns the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to find causal neighbors
    /// @param tileBounds the tile boundaries that constrain causal neighbor lookup
    private void applyObmcLeftNeighbors(
            MutablePlaneBuffer destinationPlane,
            ChromaPlane chromaPlane,
            TileBlockHeaderReader.BlockHeader header,
            int destinationX,
            int destinationY,
            int visibleWidth,
            int visibleHeight,
            int subsamplingX,
            int subsamplingY,
            FrameHeader frameHeader,
            Av1ChromaFormat chromaFormat,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds
    ) {
        int blockX4 = header.position().x4();
        int blockY4 = header.position().y4();
        if (blockX4 <= tileBounds.lumaStartX() >> 2 || visibleWidth <= 0 || visibleHeight <= 0) {
            return;
        }
        int lumaOverlap = Math.min(header.size().widthPixels(), 64) >> 1;
        int fullOverlap = Math.max(1, lumaOverlap >> subsamplingX);
        int overlap = Math.min(visibleWidth, fullOverlap);
        int[] mask = obmcMask(fullOverlap);
        int maxNeighbors = maximumObmcNeighbors(header.size().height4());
        int processed = 0;
        int endY4 = blockY4 + header.size().height4();
        int scanY4 = blockY4;
        while (scanY4 < endY4 && processed < maxNeighbors) {
            TilePartitionTreeReader.LeafNode neighbor = decodedBlockMap.leafAt(blockX4 - 1, scanY4 + 1);
            if (neighbor == null) {
                scanY4 += 2;
                continue;
            }
            TileBlockHeaderReader.BlockHeader neighborHeader = neighbor.header();
            int step4 = Math.max(2, Math.min(16, neighborHeader.size().height4()));
            if (isObmcNeighbor(neighborHeader)) {
                int relativeLumaY = Math.max(0, scanY4 - blockY4) << 2;
                int planeRelativeY = relativeLumaY >> subsamplingY;
                int overlapHeight4 = Math.min(step4, header.size().height4());
                int predictionHeight = Math.max(1, (overlapHeight4 << 2) >> subsamplingY);
                int planeHeight = Math.min(
                        visibleHeight - planeRelativeY,
                        predictionHeight
                );
                if (planeHeight > 0) {
                    blendObmcRegion(
                            destinationPlane,
                            chromaPlane,
                            neighborHeader,
                            destinationX,
                            destinationY + planeRelativeY,
                            overlap,
                            planeHeight,
                            fullOverlap,
                            predictionHeight,
                            mask,
                            false,
                            frameHeader,
                            chromaFormat,
                            referenceSurfaceSnapshots
                    );
                    processed++;
                }
            }
            scanY4 += step4;
        }
    }

    /// Blends one OBMC neighbor predictor into a destination region.
    ///
    /// @param destinationPlane the mutable destination plane containing the current predictor
    /// @param chromaPlane the chroma plane selector, or `null` for luma
    /// @param neighborHeader the decoded neighbor block header supplying the primary predictor
    /// @param destinationX the plane-local region origin X
    /// @param destinationY the plane-local region origin Y
    /// @param width the region width in plane samples
    /// @param height the region height in plane samples
    /// @param predictionWidth the complete neighbor-predictor width used for interpolation-filter selection
    /// @param predictionHeight the complete neighbor-predictor height used for interpolation-filter selection
    /// @param mask the OBMC mask for the varying axis
    /// @param above whether the region is blended from an above neighbor
    /// @param frameHeader the frame header that owns the current block
    /// @param chromaFormat the active decoded chroma layout
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private void blendObmcRegion(
            MutablePlaneBuffer destinationPlane,
            ChromaPlane chromaPlane,
            TileBlockHeaderReader.BlockHeader neighborHeader,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int predictionWidth,
            int predictionHeight,
            int[] mask,
            boolean above,
            FrameHeader frameHeader,
            Av1ChromaFormat chromaFormat,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = requireReferenceSurfaceSnapshot(
                referenceSurfaceSnapshots,
                frameHeader,
                chromaFormat,
                destinationPlane.bitDepth(),
                neighborHeader.referenceFrame0()
        );
        PaddedPlane referencePlane;
        if (chromaPlane == null) {
            referencePlane = referenceSurfaceSnapshot.decodedPlanes().lumaPlane();
        } else if (chromaPlane == ChromaPlane.U) {
            referencePlane = Objects.requireNonNull(
                    referenceSurfaceSnapshot.decodedPlanes().chromaUPlane(),
                    "referencePlanes.chromaUPlane()"
            );
        } else {
            referencePlane = Objects.requireNonNull(
                    referenceSurfaceSnapshot.decodedPlanes().chromaVPlane(),
                    "referencePlanes.chromaVPlane()"
            );
        }
        MotionVector motionVector = Objects.requireNonNull(neighborHeader.motionVector0(), "neighborHeader.motionVector0()").vector();
        int denominatorX = chromaPlane == null ? 8 : 8 << chromaSubsamplingX(chromaFormat);
        int denominatorY = chromaPlane == null ? 8 : 8 << chromaSubsamplingY(chromaFormat);
        FrameHeader.InterpolationFilter horizontalFilter = resolveHorizontalInterpolationFilter(neighborHeader, frameHeader);
        FrameHeader.InterpolationFilter verticalFilter = resolveVerticalInterpolationFilter(neighborHeader, frameHeader);
        ReferenceScale referenceScale = referenceScale(
                frameHeader.frameSize().codedWidth(),
                frameHeader.frameSize().height(),
                referenceSurfaceSnapshot
        );
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int predictor = sampleInterPlaneValue(
                        referencePlane,
                        destinationX,
                        destinationY,
                        x,
                        y,
                        motionVector.columnEighthPel(),
                        motionVector.rowEighthPel(),
                        denominatorX,
                        denominatorY,
                        referenceScale,
                        predictionWidth,
                        predictionHeight,
                        horizontalFilter,
                        verticalFilter,
                        destinationPlane.maxSampleValue()
                );
                int current = destinationPlane.sample(destinationX + x, destinationY + y);
                int currentWeight = mask[above ? y : x];
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        blendMaskedCompoundSamples(predictor, current, currentWeight)
                );
            }
        }
    }

    /// Returns whether one decoded neighbor can provide an OBMC predictor.
    ///
    /// @param header the decoded neighbor block header
    /// @return whether the neighbor can provide an OBMC predictor
    private boolean isObmcNeighbor(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        return !nonNullHeader.intra()
                && !nonNullHeader.useIntrabc()
                && nonNullHeader.referenceFrame0() >= 0
                && nonNullHeader.motionVector0() != null;
    }

    /// Returns the AV1 OBMC neighbor limit for one axis measured in 4x4 units.
    ///
    /// @param size4 the current block axis size in 4x4 units
    /// @return the maximum number of causal neighbors to blend on that axis
    private int maximumObmcNeighbors(int size4) {
        if (size4 <= 2) {
            return 1;
        }
        if (size4 <= 4) {
            return 2;
        }
        if (size4 <= 8) {
            return 3;
        }
        return 4;
    }

    /// Returns the AV1 OBMC mask for the supplied overlap length.
    ///
    /// @param length the overlap length in samples
    /// @return the AV1 OBMC mask for the supplied overlap length
    private int[] obmcMask(int length) {
        return switch (length) {
            case 1 -> OBMC_MASKS[0];
            case 2 -> OBMC_MASKS[1];
            case 4 -> OBMC_MASKS[2];
            case 8 -> OBMC_MASKS[3];
            case 16 -> OBMC_MASKS[4];
            case 32 -> OBMC_MASKS[5];
            case 64 -> OBMC_MASKS[6];
            default -> throw new IllegalStateException("Unsupported OBMC overlap length: " + length);
        };
    }

    /// Reconstructs an `intrabc` block from already reconstructed samples in the current frame.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the intrabc state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that defines the MI-grid reference boundary
    private void reconstructIntrabcPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader
    ) {
        MotionVector motionVector = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        int frameLumaWidth = alignedFrameBoundaryDimension(frameHeader.frameSize().codedWidth());
        int frameLumaHeight = alignedFrameBoundaryDimension(frameHeader.frameSize().height());
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        reconstructIntrabcPlanePrediction(
                lumaPlane,
                frameLumaWidth,
                frameLumaHeight,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                8,
                8
        );

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int chromaX = chromaBlockX(header, chromaSubsamplingX);
        int chromaY = chromaBlockY(header, chromaSubsamplingY);
        int visibleChromaWidth = visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX);
        int visibleChromaHeight = visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY);
        int chromaDenominatorX = 8 << chromaSubsamplingX;
        int chromaDenominatorY = 8 << chromaSubsamplingY;
        int frameChromaWidth = chromaWidth(chromaFormat, frameLumaWidth);
        int frameChromaHeight = chromaHeight(chromaFormat, frameLumaHeight);
        reconstructIntrabcPlanePrediction(
                chromaUPlane,
                frameChromaWidth,
                frameChromaHeight,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                chromaDenominatorX,
                chromaDenominatorY
        );
        reconstructIntrabcPlanePrediction(
                chromaVPlane,
                frameChromaWidth,
                frameChromaHeight,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                chromaDenominatorX,
                chromaDenominatorY
        );
    }

    /// Reconstructs one `intrabc` plane directly from the current mutable reconstruction surface.
    ///
    /// Normative displacement-vector validation guarantees that the complete source precedes and
    /// does not overlap the destination block, so samples may be read and written in one pass.
    ///
    /// @param plane                  the current mutable reconstruction plane
    /// @param framePlaneWidth        the coded-frame plane width used for edge extension
    /// @param framePlaneHeight       the coded-frame plane height used for edge extension
    /// @param destinationX           the zero-based horizontal destination origin
    /// @param destinationY           the zero-based vertical destination origin
    /// @param width                  the visible prediction width in samples
    /// @param height                 the visible prediction height in samples
    /// @param sourceOffsetEighthPelX the horizontal displacement in luma eighth-pel units
    /// @param sourceOffsetEighthPelY the vertical displacement in luma eighth-pel units
    /// @param denominatorX           the plane-local horizontal denominator in luma eighth-pel units
    /// @param denominatorY           the plane-local vertical denominator in luma eighth-pel units
    private void reconstructIntrabcPlanePrediction(
            MutablePlaneBuffer plane,
            int framePlaneWidth,
            int framePlaneHeight,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int sourceOffsetEighthPelX,
            int sourceOffsetEighthPelY,
            int denominatorX,
            int denominatorY
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                plane.setSample(
                        destinationX + x,
                        destinationY + y,
                        sampleIntrabcPlaneValue(
                                plane,
                                framePlaneWidth,
                                framePlaneHeight,
                                destinationX + x,
                                destinationY + y,
                                sourceOffsetEighthPelX,
                                sourceOffsetEighthPelY,
                                denominatorX,
                                denominatorY
                        )
                );
            }
        }
    }

    /// Returns one same-frame bilinear predictor sample from the current mutable plane.
    ///
    /// @param plane                  the current mutable reconstruction plane
    /// @param framePlaneWidth        the coded-frame plane width used for edge extension
    /// @param framePlaneHeight       the coded-frame plane height used for edge extension
    /// @param destinationX           the zero-based horizontal destination coordinate
    /// @param destinationY           the zero-based vertical destination coordinate
    /// @param sourceOffsetEighthPelX the horizontal displacement in luma eighth-pel units
    /// @param sourceOffsetEighthPelY the vertical displacement in luma eighth-pel units
    /// @param denominatorX           the plane-local horizontal denominator in luma eighth-pel units
    /// @param denominatorY           the plane-local vertical denominator in luma eighth-pel units
    /// @return the predicted sample clipped to the plane bit depth
    private int sampleIntrabcPlaneValue(
            MutablePlaneBuffer plane,
            int framePlaneWidth,
            int framePlaneHeight,
            int destinationX,
            int destinationY,
            int sourceOffsetEighthPelX,
            int sourceOffsetEighthPelY,
            int denominatorX,
            int denominatorY
    ) {
        int sourceNumeratorX = destinationX * denominatorX + sourceOffsetEighthPelX;
        int sourceNumeratorY = destinationY * denominatorY + sourceOffsetEighthPelY;
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int fractionX = interpolationPhase(Math.floorMod(sourceNumeratorX, denominatorX), denominatorX);
        int fractionY = interpolationPhase(Math.floorMod(sourceNumeratorY, denominatorY), denominatorY);
        int clampedSourceX0 = clamp(sourceX0, 0, framePlaneWidth - 1);
        int clampedSourceY0 = clamp(sourceY0, 0, framePlaneHeight - 1);
        if (fractionX == 0 && fractionY == 0) {
            return plane.sample(clampedSourceX0, clampedSourceY0);
        }

        int clampedSourceX1 = clamp(sourceX0 + 1, 0, framePlaneWidth - 1);
        int clampedSourceY1 = clamp(sourceY0 + 1, 0, framePlaneHeight - 1);
        int topLeft = plane.sample(clampedSourceX0, clampedSourceY0);
        int topRight = plane.sample(clampedSourceX1, clampedSourceY0);
        int bottomLeft = plane.sample(clampedSourceX0, clampedSourceY1);
        int bottomRight = plane.sample(clampedSourceX1, clampedSourceY1);
        if (fractionY == 0) {
            int intermediateBits = interPredictionIntermediateBits(plane.maxSampleValue());
            int horizontal = roundShift(bilinearFilterSum(topLeft, topRight, fractionX), 4 - intermediateBits);
            return clamp(roundShift(horizontal, intermediateBits), 0, plane.maxSampleValue());
        }
        if (fractionX == 0) {
            return clamp(
                    roundShift(bilinearFilterSum(topLeft, bottomLeft, fractionY), 4),
                    0,
                    plane.maxSampleValue()
            );
        }

        int intermediateBits = interPredictionIntermediateBits(plane.maxSampleValue());
        int top = roundShift(bilinearFilterSum(topLeft, topRight, fractionX), 4 - intermediateBits);
        int bottom = roundShift(bilinearFilterSum(bottomLeft, bottomRight, fractionX), 4 - intermediateBits);
        return clamp(
                roundShift(bilinearFilterSum(top, bottom, fractionY), 4 + intermediateBits),
                0,
                plane.maxSampleValue()
        );
    }

    /// Reconstructs single-reference inter prediction for one decoded block.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used for sub-8x8 chroma motion derivation
    private void reconstructSingleReferenceInterPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int frameLumaWidth,
            int frameLumaHeight,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
    ) {
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                requireReferenceSurfaceSnapshot(
                        referenceSurfaceSnapshots,
                        frameHeader,
                        chromaFormat,
                        lumaPlane.bitDepth(),
                        header.referenceFrame0()
        );
        DecodedSurface referencePlanes = referenceSurfaceSnapshot.decodedPlanes();
        ReferenceScale referenceScale = referenceScale(frameLumaWidth, frameLumaHeight, referenceSurfaceSnapshot);
        MotionVector motionVector = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        FrameHeader.InterpolationFilter horizontalInterpolationFilter = resolveHorizontalInterpolationFilter(header, frameHeader);
        FrameHeader.InterpolationFilter verticalInterpolationFilter = resolveVerticalInterpolationFilter(header, frameHeader);
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        int lumaBlockWidth = header.size().widthPixels();
        int lumaBlockHeight = header.size().heightPixels();
        reconstructInterPlanePrediction(
                lumaPlane,
                referencePlanes.lumaPlane(),
                frameLumaWidth,
                frameLumaHeight,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                8,
                8,
                referenceScale,
                lumaBlockWidth,
                lumaBlockHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );
        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int chromaX = chromaBlockX(header, chromaSubsamplingX);
        int chromaY = chromaBlockY(header, chromaSubsamplingY);
        int visibleChromaWidth = visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX);
        int visibleChromaHeight = visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY);
        int chromaBlockWidth = header.size().widthPixels() >> chromaSubsamplingX;
        int chromaBlockHeight = header.size().heightPixels() >> chromaSubsamplingY;
        int frameChromaWidth = chromaWidth(chromaFormat, frameLumaWidth);
        int frameChromaHeight = chromaHeight(chromaFormat, frameLumaHeight);
        int chromaDenominatorX = 8 << chromaSubsamplingX;
        int chromaDenominatorY = 8 << chromaSubsamplingY;

        if (reconstructSub8x8ChromaInterPrediction(
                chromaUPlane,
                chromaVPlane,
                header,
                chromaFormat,
                frameHeader,
                frameChromaWidth,
                frameChromaHeight,
                visibleChromaWidth,
                visibleChromaHeight,
                referenceSurfaceSnapshots,
                decodedBlockMap
        )) {
            return;
        }

        reconstructInterPlanePrediction(
                chromaUPlane,
                Objects.requireNonNull(referencePlanes.chromaUPlane(), "referencePlanes.chromaUPlane()"),
                frameChromaWidth,
                frameChromaHeight,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                referenceScale,
                chromaBlockWidth,
                chromaBlockHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );
        reconstructInterPlanePrediction(
                chromaVPlane,
                Objects.requireNonNull(referencePlanes.chromaVPlane(), "referencePlanes.chromaVPlane()"),
                frameChromaWidth,
                frameChromaHeight,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                referenceScale,
                chromaBlockWidth,
                chromaBlockHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );
    }

    /// Reconstructs a shared sub-8x8 chroma footprint from its causal luma-block motion vectors.
    ///
    /// AV1 divides an otherwise shared 8x8-luma chroma footprint into two or four prediction
    /// regions when every required causal luma block is inter-coded. If any required neighbor
    /// cannot supply an inter predictor, the caller must use the current block for the complete
    /// chroma footprint instead.
    ///
    /// @param chromaUPlane the mutable chroma U destination plane
    /// @param chromaVPlane the mutable chroma V destination plane
    /// @param header the current decoded block header
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param frameChromaWidth the current frame chroma width in samples
    /// @param frameChromaHeight the current frame chroma height in samples
    /// @param visibleChromaWidth the visible shared chroma-footprint width
    /// @param visibleChromaHeight the visible shared chroma-footprint height
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to resolve causal luma blocks
    /// @return whether sub-8x8 chroma derivation reconstructed the footprint
    private boolean reconstructSub8x8ChromaInterPrediction(
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int frameChromaWidth,
            int frameChromaHeight,
            int visibleChromaWidth,
            int visibleChromaHeight,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
    ) {
        int subsamplingX = chromaSubsamplingX(chromaFormat);
        int subsamplingY = chromaSubsamplingY(chromaFormat);
        boolean splitHorizontally = subsamplingX == 1 && header.size().width4() == 1;
        boolean splitVertically = subsamplingY == 1 && header.size().height4() == 1;
        if (!splitHorizontally && !splitVertically) {
            return false;
        }

        int x4 = header.position().x4();
        int y4 = header.position().y4();
        TileBlockHeaderReader.BlockHeader leftHeader = splitHorizontally
                ? interHeaderAt(decodedBlockMap, x4 - 1, y4)
                : null;
        TileBlockHeaderReader.BlockHeader aboveHeader = splitVertically
                ? interHeaderAt(decodedBlockMap, x4, y4 - 1)
                : null;
        TileBlockHeaderReader.BlockHeader aboveLeftHeader = splitHorizontally && splitVertically
                ? interHeaderAt(decodedBlockMap, x4 - 1, y4 - 1)
                : null;
        if ((splitHorizontally && leftHeader == null)
                || (splitVertically && aboveHeader == null)
                || (splitHorizontally && splitVertically && aboveLeftHeader == null)) {
            return false;
        }

        int chromaX = chromaBlockX(header, subsamplingX);
        int chromaY = chromaBlockY(header, subsamplingY);
        int regionWidth = header.size().width4() * (4 >> subsamplingX);
        int regionHeight = header.size().height4() * (4 >> subsamplingY);
        int currentOffsetX = splitHorizontally ? regionWidth : 0;
        int currentOffsetY = splitVertically ? regionHeight : 0;

        if (aboveLeftHeader != null) {
            reconstructSub8x8ChromaRegion(
                    chromaUPlane,
                    chromaVPlane,
                    aboveLeftHeader,
                    chromaFormat,
                    frameHeader,
                    frameChromaWidth,
                    frameChromaHeight,
                    chromaX,
                    chromaY,
                    Math.min(regionWidth, visibleChromaWidth),
                    Math.min(regionHeight, visibleChromaHeight),
                    referenceSurfaceSnapshots
            );
        }
        if (leftHeader != null) {
            reconstructSub8x8ChromaRegion(
                    chromaUPlane,
                    chromaVPlane,
                    leftHeader,
                    chromaFormat,
                    frameHeader,
                    frameChromaWidth,
                    frameChromaHeight,
                    chromaX,
                    chromaY + currentOffsetY,
                    Math.min(regionWidth, visibleChromaWidth),
                    Math.min(regionHeight, visibleChromaHeight - currentOffsetY),
                    referenceSurfaceSnapshots
            );
        }
        if (aboveHeader != null) {
            reconstructSub8x8ChromaRegion(
                    chromaUPlane,
                    chromaVPlane,
                    aboveHeader,
                    chromaFormat,
                    frameHeader,
                    frameChromaWidth,
                    frameChromaHeight,
                    chromaX + currentOffsetX,
                    chromaY,
                    Math.min(regionWidth, visibleChromaWidth - currentOffsetX),
                    Math.min(regionHeight, visibleChromaHeight),
                    referenceSurfaceSnapshots
            );
        }
        reconstructSub8x8ChromaRegion(
                chromaUPlane,
                chromaVPlane,
                header,
                chromaFormat,
                frameHeader,
                frameChromaWidth,
                frameChromaHeight,
                chromaX + currentOffsetX,
                chromaY + currentOffsetY,
                Math.min(regionWidth, visibleChromaWidth - currentOffsetX),
                Math.min(regionHeight, visibleChromaHeight - currentOffsetY),
                referenceSurfaceSnapshots
        );
        return true;
    }

    /// Returns the inter block header covering one decoded luma coordinate, or `null`.
    ///
    /// @param decodedBlockMap the decoded leaf map to query
    /// @param x4 the tile-relative luma X coordinate in 4x4 units
    /// @param y4 the tile-relative luma Y coordinate in 4x4 units
    /// @return the usable inter block header, or `null`
    private TileBlockHeaderReader.BlockHeader interHeaderAt(
            DecodedBlockMap decodedBlockMap,
            int x4,
            int y4
    ) {
        if (x4 < 0 || y4 < 0) {
            return null;
        }
        TilePartitionTreeReader.LeafNode leafNode = decodedBlockMap.leafAt(x4, y4);
        if (leafNode == null) {
            return null;
        }
        TileBlockHeaderReader.BlockHeader header = leafNode.header();
        return isObmcNeighbor(header) ? header : null;
    }

    /// Reconstructs one sub-8x8 chroma prediction region from a luma block's primary reference.
    ///
    /// Empty regions at a cropped frame edge are ignored.
    ///
    /// @param chromaUPlane the mutable chroma U destination plane
    /// @param chromaVPlane the mutable chroma V destination plane
    /// @param sourceHeader the luma block header supplying the reference and motion vector
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param frameChromaWidth the current frame chroma width in samples
    /// @param frameChromaHeight the current frame chroma height in samples
    /// @param destinationX the chroma-plane destination X coordinate
    /// @param destinationY the chroma-plane destination Y coordinate
    /// @param width the visible region width in chroma samples
    /// @param height the visible region height in chroma samples
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private void reconstructSub8x8ChromaRegion(
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader sourceHeader,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int frameChromaWidth,
            int frameChromaHeight,
            int destinationX,
            int destinationY,
            int width,
            int height,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = requireReferenceSurfaceSnapshot(
                referenceSurfaceSnapshots,
                frameHeader,
                chromaFormat,
                chromaUPlane.bitDepth(),
                sourceHeader.referenceFrame0()
        );
        MotionVector motionVector = Objects.requireNonNull(
                sourceHeader.motionVector0(),
                "sourceHeader.motionVector0()"
        ).vector();
        int subsamplingX = chromaSubsamplingX(chromaFormat);
        int subsamplingY = chromaSubsamplingY(chromaFormat);
        int denominatorX = 8 << subsamplingX;
        int denominatorY = 8 << subsamplingY;
        FrameHeader.InterpolationFilter horizontalFilter = resolveHorizontalInterpolationFilter(sourceHeader, frameHeader);
        FrameHeader.InterpolationFilter verticalFilter = resolveVerticalInterpolationFilter(sourceHeader, frameHeader);
        DecodedSurface referencePlanes = referenceSurfaceSnapshot.decodedPlanes();
        ReferenceScale referenceScale = referenceScale(
                frameHeader.frameSize().codedWidth(),
                frameHeader.frameSize().height(),
                referenceSurfaceSnapshot
        );
        reconstructInterPlanePrediction(
                chromaUPlane,
                Objects.requireNonNull(referencePlanes.chromaUPlane(), "referencePlanes.chromaUPlane()"),
                frameChromaWidth,
                frameChromaHeight,
                destinationX,
                destinationY,
                width,
                height,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                denominatorX,
                denominatorY,
                referenceScale,
                width,
                height,
                horizontalFilter,
                verticalFilter
        );
        reconstructInterPlanePrediction(
                chromaVPlane,
                Objects.requireNonNull(referencePlanes.chromaVPlane(), "referencePlanes.chromaVPlane()"),
                frameChromaWidth,
                frameChromaHeight,
                destinationX,
                destinationY,
                width,
                height,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                denominatorX,
                denominatorY,
                referenceScale,
                width,
                height,
                horizontalFilter,
                verticalFilter
        );
    }

    /// Returns whether a single-reference block uses frame-level affine global warped prediction.
    ///
    /// @param header the decoded block header
    /// @param frameHeader the frame header that owns the block
    /// @return whether the block uses a non-translation global warp
    private boolean usesGlobalWarpedPrediction(
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader
    ) {
        if (header.singleInterMode() != SingleInterPredictionMode.GLOBALMV
                || frameHeader.forceIntegerMotionVectors()
                || Math.min(header.size().widthPixels(), header.size().heightPixels()) < 8) {
            return false;
        }
        FrameHeader.GlobalMotionType type = frameHeader.globalMotion(header.referenceFrame0()).type();
        return type == FrameHeader.GlobalMotionType.ROTATION_ZOOM || type == FrameHeader.GlobalMotionType.AFFINE;
    }

    /// Returns the affine global model used by one reference of a compound prediction plane.
    ///
    /// Global warped prediction is disabled for non-global compound modes, forced-integer motion,
    /// sub-8x8 plane blocks, scaled references, and global models that fail AV1 shear
    /// normalization. In those cases the caller must use the decoded block-center global motion
    /// vector as an ordinary translation predictor.
    ///
    /// @param header the decoded block header
    /// @param frameHeader the frame header that owns the block
    /// @param referenceFrame the zero-based LAST-through-ALTREF reference position
    /// @param referenceScale the current-to-reference frame scale factors
    /// @param planeBlockWidth the coded block width in plane samples
    /// @param planeBlockHeight the coded block height in plane samples
    /// @return the normalized affine model, or `null` when translation prediction is required
    private WarpedMotion.Model compoundGlobalWarpModel(
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader,
            int referenceFrame,
            ReferenceScale referenceScale,
            int planeBlockWidth,
            int planeBlockHeight
    ) {
        if (header.compoundInterMode() != CompoundInterPredictionMode.GLOBALMV_GLOBALMV
                || frameHeader.forceIntegerMotionVectors()
                || planeBlockWidth < 8
                || planeBlockHeight < 8
                || referenceScale.scaled()) {
            return null;
        }
        FrameHeader.GlobalMotionParams parameters = frameHeader.globalMotion(referenceFrame);
        if (parameters.type() != FrameHeader.GlobalMotionType.ROTATION_ZOOM
                && parameters.type() != FrameHeader.GlobalMotionType.AFFINE) {
            return null;
        }
        WarpedMotion.Model model = WarpedMotion.fromGlobalMotion(parameters);
        return model.affine() ? model : null;
    }

    /// Reconstructs a single-reference inter predictor using frame-level affine global motion.
    ///
    /// Scaled references cannot use warped prediction and fall back to the block-center global
    /// motion vector already stored in the decoded block header.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param frameLumaWidth the current coded-frame luma width
    /// @param frameLumaHeight the current coded-frame luma height
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used by the translation fallback
    private void reconstructGlobalWarpedInterPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int frameLumaWidth,
            int frameLumaHeight,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
    ) {
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = requireReferenceSurfaceSnapshot(
                referenceSurfaceSnapshots,
                frameHeader,
                chromaFormat,
                lumaPlane.bitDepth(),
                header.referenceFrame0()
        );
        WarpedMotion.Model model = WarpedMotion.fromGlobalMotion(frameHeader.globalMotion(header.referenceFrame0()));
        if (referenceScale(frameLumaWidth, frameLumaHeight, referenceSurfaceSnapshot).scaled() || !model.affine()) {
            reconstructSingleReferenceInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader,
                    frameLumaWidth,
                    frameLumaHeight,
                    referenceSurfaceSnapshots,
                    decodedBlockMap
            );
            return;
        }
        reconstructWarpedInterPrediction(
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                header,
                transformLayout,
                chromaFormat,
                frameHeader,
                referenceSurfaceSnapshot.decodedPlanes(),
                model
        );
    }

    /// Reconstructs a single-reference inter predictor using an affine local warped motion model.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to find causal local-warp samples
    /// @param tileBounds the tile-local boundaries that constrain causal sample lookup
    private void reconstructLocalWarpedInterPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int frameLumaWidth,
            int frameLumaHeight,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds
    ) {
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                requireReferenceSurfaceSnapshot(
                        referenceSurfaceSnapshots,
                        frameHeader,
                        chromaFormat,
                        lumaPlane.bitDepth(),
                        header.referenceFrame0()
        );
        DecodedSurface referencePlanes = referenceSurfaceSnapshot.decodedPlanes();
        WarpedMotion.Model model = estimateLocalWarpModel(header, decodedBlockMap, tileBounds);
        if (!model.affine()) {
            reconstructSingleReferenceInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader,
                    frameLumaWidth,
                    frameLumaHeight,
                    referenceSurfaceSnapshots,
                    decodedBlockMap
            );
            return;
        }
        reconstructWarpedInterPrediction(
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                header,
                transformLayout,
                chromaFormat,
                frameHeader,
                referencePlanes,
                model
        );
    }

    /// Applies one normalized affine model to all visible planes of a single-reference block.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that supplies resolved interpolation filters
    /// @param referencePlanes the decoded planes of the selected reference frame
    /// @param model the normalized affine warped-motion model
    private void reconstructWarpedInterPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            DecodedSurface referencePlanes,
            WarpedMotion.Model model
    ) {
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        WarpedMotion.predictPlane(
                lumaPlane,
                referencePlanes.lumaPlane(),
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                header.size().widthPixels(),
                header.size().heightPixels(),
                header.position().x4(),
                header.position().y4(),
                0,
                0,
                model
        );

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int chromaX = chromaBlockX(header, chromaSubsamplingX);
        int chromaY = chromaBlockY(header, chromaSubsamplingY);
        int visibleChromaWidth = visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX);
        int visibleChromaHeight = visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY);
        int codedChromaWidth = codedChromaBlockWidth(header, chromaSubsamplingX);
        int codedChromaHeight = codedChromaBlockHeight(header, chromaSubsamplingY);
        PaddedPlane referenceChromaUPlane = Objects.requireNonNull(
                referencePlanes.chromaUPlane(),
                "referencePlanes.chromaUPlane()"
        );
        PaddedPlane referenceChromaVPlane = Objects.requireNonNull(
                referencePlanes.chromaVPlane(),
                "referencePlanes.chromaVPlane()"
        );

        if (codedChromaWidth < 8 || codedChromaHeight < 8) {
            MotionVector motionVector = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
            int chromaDenominatorX = 8 << chromaSubsamplingX;
            int chromaDenominatorY = 8 << chromaSubsamplingY;
            FrameHeader.InterpolationFilter horizontalInterpolationFilter =
                    resolveHorizontalInterpolationFilter(header, frameHeader);
            FrameHeader.InterpolationFilter verticalInterpolationFilter =
                    resolveVerticalInterpolationFilter(header, frameHeader);
            reconstructInterPlanePrediction(
                    chromaUPlane,
                    referenceChromaUPlane,
                    referenceChromaUPlane.width(),
                    referenceChromaUPlane.height(),
                    chromaX,
                    chromaY,
                    visibleChromaWidth,
                    visibleChromaHeight,
                    motionVector.columnEighthPel(),
                    motionVector.rowEighthPel(),
                    chromaDenominatorX,
                    chromaDenominatorY,
                    IDENTITY_REFERENCE_SCALE,
                    codedChromaWidth,
                    codedChromaHeight,
                    horizontalInterpolationFilter,
                    verticalInterpolationFilter
            );
            reconstructInterPlanePrediction(
                    chromaVPlane,
                    referenceChromaVPlane,
                    referenceChromaVPlane.width(),
                    referenceChromaVPlane.height(),
                    chromaX,
                    chromaY,
                    visibleChromaWidth,
                    visibleChromaHeight,
                    motionVector.columnEighthPel(),
                    motionVector.rowEighthPel(),
                    chromaDenominatorX,
                    chromaDenominatorY,
                    IDENTITY_REFERENCE_SCALE,
                    codedChromaWidth,
                    codedChromaHeight,
                    horizontalInterpolationFilter,
                    verticalInterpolationFilter
            );
            return;
        }

        WarpedMotion.predictPlane(
                chromaUPlane,
                referenceChromaUPlane,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                codedChromaWidth,
                codedChromaHeight,
                header.position().x4(),
                header.position().y4(),
                chromaSubsamplingX,
                chromaSubsamplingY,
                model
        );
        WarpedMotion.predictPlane(
                chromaVPlane,
                referenceChromaVPlane,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                codedChromaWidth,
                codedChromaHeight,
                header.position().x4(),
                header.position().y4(),
                chromaSubsamplingX,
                chromaSubsamplingY,
                model
        );
    }

    /// Estimates one local warped affine motion model from causal same-reference neighbors.
    ///
    /// @param header the decoded current block header
    /// @param decodedBlockMap the decoded leaf map used to find causal local-warp samples
    /// @param tileBounds the tile-local boundaries that constrain neighbor lookup
    /// @return one local warped affine motion model for the current block
    private WarpedMotion.Model estimateLocalWarpModel(
            TileBlockHeaderReader.BlockHeader header,
            DecodedBlockMap decodedBlockMap,
            TileSampleBounds tileBounds
    ) {
        MotionVector baseMotionVector = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        int blockX4 = header.position().x4();
        int blockY4 = header.position().y4();
        int blockWidth4 = header.size().width4();
        int blockHeight4 = header.size().height4();
        int tileStartX4 = tileBounds.lumaStartX() >> 2;
        int tileStartY4 = tileBounds.lumaStartY() >> 2;
        int tileEndX4 = tileBounds.lumaEndX() >> 2;
        int tileEndY4 = tileBounds.lumaEndY() >> 2;
        int visibleWidth4 = Math.min(blockWidth4, tileEndX4 - blockX4);
        int visibleHeight4 = Math.min(blockHeight4, tileEndY4 - blockY4);
        TilePartitionTreeReader.LeafNode currentLeaf = Objects.requireNonNull(
                decodedBlockMap.leafAt(blockX4, blockY4),
                "currentLeaf"
        );
        long[] masks = findLocalWarpSampleMasks(
                header,
                currentLeaf,
                decodedBlockMap,
                blockX4,
                blockY4,
                blockWidth4,
                blockHeight4,
                visibleWidth4,
                visibleHeight4,
                tileStartX4,
                tileStartY4,
                tileEndX4
        );
        WarpedMotion.Sample[] samples = new WarpedMotion.Sample[WarpedMotion.SAMPLE_CAPACITY];
        int sampleCount = collectLocalWarpSamples(
                decodedBlockMap,
                blockX4,
                blockY4,
                blockWidth4,
                masks,
                samples
        );
        return WarpedMotion.derive(
                samples,
                sampleCount,
                blockWidth4,
                blockHeight4,
                baseMotionVector,
                blockX4,
                blockY4
        );
    }

    /// Finds the projectable local-warp neighbor masks in AV1 top/left edge order.
    ///
    /// @param currentHeader the current block header
    /// @param currentLeaf the current partition leaf
    /// @param decodedBlockMap the decoded leaf map
    /// @param blockX4 the current block X origin
    /// @param blockY4 the current block Y origin
    /// @param blockWidth4 the current block width
    /// @param blockHeight4 the current block height
    /// @param visibleWidth4 the visible block width
    /// @param visibleHeight4 the visible block height
    /// @param tileStartX4 the tile X origin
    /// @param tileStartY4 the tile Y origin
    /// @param tileEndX4 the exclusive tile X boundary
    /// @return the top and left masks
    private long[] findLocalWarpSampleMasks(
            TileBlockHeaderReader.BlockHeader currentHeader,
            TilePartitionTreeReader.LeafNode currentLeaf,
            DecodedBlockMap decodedBlockMap,
            int blockX4,
            int blockY4,
            int blockWidth4,
            int blockHeight4,
            int visibleWidth4,
            int visibleHeight4,
            int tileStartX4,
            int tileStartY4,
            int tileEndX4
    ) {
        long topMask = 0;
        long leftMask = 0;
        int count = 0;
        boolean haveTop = blockY4 > tileStartY4;
        boolean haveLeft = blockX4 > tileStartX4;
        boolean haveTopLeft = haveTop && haveLeft;
        boolean haveTopRight = Math.max(blockWidth4, blockHeight4) < 32
                && haveTop
                && blockX4 + blockWidth4 < tileEndX4;

        if (haveTop) {
            TilePartitionTreeReader.LeafNode directTop = Objects.requireNonNull(
                    decodedBlockMap.leafAt(blockX4, blockY4 - 1),
                    "directTop"
            );
            if (isLocalWarpReferenceNeighbor(currentHeader, directTop.header())) {
                topMask |= 1;
                count = 1;
            }
            int aboveWidth4 = directTop.header().size().width4();
            if (aboveWidth4 >= blockWidth4) {
                int offset = blockX4 & (aboveWidth4 - 1);
                if (offset != 0) {
                    haveTopLeft = false;
                }
                if (aboveWidth4 - offset > blockWidth4) {
                    haveTopRight = false;
                }
            } else {
                long mask = 1L << aboveWidth4;
                for (int x = aboveWidth4; x < visibleWidth4; ) {
                    TilePartitionTreeReader.LeafNode neighbor = Objects.requireNonNull(
                            decodedBlockMap.leafAt(blockX4 + x, blockY4 - 1),
                            "topNeighbor"
                    );
                    if (isLocalWarpReferenceNeighbor(currentHeader, neighbor.header())) {
                        topMask |= mask;
                        if (++count >= WarpedMotion.SAMPLE_CAPACITY) {
                            return new long[]{topMask, leftMask};
                        }
                    }
                    aboveWidth4 = neighbor.header().size().width4();
                    x += aboveWidth4;
                    mask <<= aboveWidth4;
                }
            }
        }
        if (haveLeft) {
            TilePartitionTreeReader.LeafNode directLeft = Objects.requireNonNull(
                    decodedBlockMap.leafAt(blockX4 - 1, blockY4),
                    "directLeft"
            );
            if (isLocalWarpReferenceNeighbor(currentHeader, directLeft.header())) {
                leftMask |= 1;
                if (++count >= WarpedMotion.SAMPLE_CAPACITY) {
                    return new long[]{topMask, leftMask};
                }
            }
            int leftHeight4 = directLeft.header().size().height4();
            if (leftHeight4 >= blockHeight4) {
                if ((blockY4 & (leftHeight4 - 1)) != 0) {
                    haveTopLeft = false;
                }
            } else {
                long mask = 1L << leftHeight4;
                for (int y = leftHeight4; y < visibleHeight4; ) {
                    TilePartitionTreeReader.LeafNode neighbor = Objects.requireNonNull(
                            decodedBlockMap.leafAt(blockX4 - 1, blockY4 + y),
                            "leftNeighbor"
                    );
                    if (isLocalWarpReferenceNeighbor(currentHeader, neighbor.header())) {
                        leftMask |= mask;
                        if (++count >= WarpedMotion.SAMPLE_CAPACITY) {
                            return new long[]{topMask, leftMask};
                        }
                    }
                    leftHeight4 = neighbor.header().size().height4();
                    y += leftHeight4;
                    mask <<= leftHeight4;
                }
            }
        }
        if (haveTopLeft) {
            TilePartitionTreeReader.LeafNode topLeft = decodedBlockMap.leafAt(blockX4 - 1, blockY4 - 1);
            if (topLeft != null && isLocalWarpReferenceNeighbor(currentHeader, topLeft.header())) {
                leftMask |= 1L << 32;
                if (++count >= WarpedMotion.SAMPLE_CAPACITY) {
                    return new long[]{topMask, leftMask};
                }
            }
        }
        if (haveTopRight) {
            TilePartitionTreeReader.LeafNode topRight = decodedBlockMap.leafAt(
                    blockX4 + blockWidth4,
                    blockY4 - 1
            );
            if (topRight != null
                    && decodedBlockMap.isCausal(topRight, currentLeaf)
                    && isLocalWarpReferenceNeighbor(currentHeader, topRight.header())) {
                topMask |= 1L << 32;
            }
        }
        return new long[]{topMask, leftMask};
    }

    /// Converts top and left projectable masks into affine motion samples.
    ///
    /// @param decodedBlockMap the decoded leaf map
    /// @param blockX4 the current block X origin
    /// @param blockY4 the current block Y origin
    /// @param blockWidth4 the current block width
    /// @param masks the top and left projectable masks
    /// @param samples the destination sample array
    /// @return the populated sample count
    private int collectLocalWarpSamples(
            DecodedBlockMap decodedBlockMap,
            int blockX4,
            int blockY4,
            int blockWidth4,
            long[] masks,
            WarpedMotion.Sample[] samples
    ) {
        long topMask = masks[0];
        long leftMask = masks[1];
        int count = 0;
        if ((topMask & 0xFFFF_FFFFL) == 1 && (leftMask >>> 32) == 0) {
            TileBlockHeaderReader.BlockHeader neighbor = Objects.requireNonNull(
                    decodedBlockMap.leafAt(blockX4, blockY4 - 1),
                    "directTop"
            ).header();
            int offset = blockX4 & (neighbor.size().width4() - 1);
            samples[count++] = localWarpSample(-offset, 0, 1, -1, neighbor);
        } else {
            int offset = 0;
            int mask = (int) topMask;
            while (count < samples.length && mask != 0) {
                int trailingZeros = Integer.numberOfTrailingZeros(mask);
                offset += trailingZeros;
                mask >>>= trailingZeros;
                TileBlockHeaderReader.BlockHeader neighbor = Objects.requireNonNull(
                        decodedBlockMap.leafAt(blockX4 + offset, blockY4 - 1),
                        "topNeighbor"
                ).header();
                samples[count++] = localWarpSample(offset, 0, 1, -1, neighbor);
                mask &= ~1;
            }
        }
        if (count < samples.length && leftMask == 1) {
            TileBlockHeaderReader.BlockHeader directLeft = Objects.requireNonNull(
                    decodedBlockMap.leafAt(blockX4 - 1, blockY4),
                    "directLeft"
            ).header();
            int offset = blockY4 & (directLeft.size().height4() - 1);
            TileBlockHeaderReader.BlockHeader neighbor = Objects.requireNonNull(
                    decodedBlockMap.leafAt(blockX4 - 1, blockY4 - offset),
                    "alignedLeft"
            ).header();
            samples[count++] = localWarpSample(0, -offset, -1, 1, neighbor);
        } else {
            int offset = 0;
            int mask = (int) leftMask;
            while (count < samples.length && mask != 0) {
                int trailingZeros = Integer.numberOfTrailingZeros(mask);
                offset += trailingZeros;
                mask >>>= trailingZeros;
                TileBlockHeaderReader.BlockHeader neighbor = Objects.requireNonNull(
                        decodedBlockMap.leafAt(blockX4 - 1, blockY4 + offset),
                        "leftNeighbor"
                ).header();
                samples[count++] = localWarpSample(0, offset, -1, 1, neighbor);
                mask &= ~1;
            }
        }
        if (count < samples.length && (leftMask >>> 32) != 0) {
            TileBlockHeaderReader.BlockHeader neighbor = Objects.requireNonNull(
                    decodedBlockMap.leafAt(blockX4 - 1, blockY4 - 1),
                    "topLeft"
            ).header();
            samples[count++] = localWarpSample(0, 0, -1, -1, neighbor);
        }
        if (count < samples.length && (topMask >>> 32) != 0) {
            TileBlockHeaderReader.BlockHeader neighbor = Objects.requireNonNull(
                    decodedBlockMap.leafAt(blockX4 + blockWidth4, blockY4 - 1),
                    "topRight"
            ).header();
            samples[count++] = localWarpSample(blockWidth4, 0, 1, -1, neighbor);
        }
        if (count == 0) {
            throw new IllegalStateException("Local warped motion requires at least one projectable sample");
        }
        return count;
    }

    /// Creates one affine sample from a causal neighbor block.
    ///
    /// @param deltaX4 the neighbor center offset X in 4x4 units
    /// @param deltaY4 the neighbor center offset Y in 4x4 units
    /// @param widthSign the signed neighbor-width contribution
    /// @param heightSign the signed neighbor-height contribution
    /// @param neighborHeader the projectable neighbor header
    /// @return one affine projection sample
    private WarpedMotion.Sample localWarpSample(
            int deltaX4,
            int deltaY4,
            int widthSign,
            int heightSign,
            TileBlockHeaderReader.BlockHeader neighborHeader
    ) {
        MotionVector motionVector = Objects.requireNonNull(
                neighborHeader.motionVector0(),
                "neighborHeader.motionVector0()"
        ).vector();
        int sourceX = 16 * (2 * deltaX4 + widthSign * neighborHeader.size().width4()) - 8;
        int sourceY = 16 * (2 * deltaY4 + heightSign * neighborHeader.size().height4()) - 8;
        return new WarpedMotion.Sample(
                sourceX,
                sourceY,
                sourceX + motionVector.columnEighthPel(),
                sourceY + motionVector.rowEighthPel()
        );
    }

    /// Returns whether one neighbor supplies a compatible single-reference motion vector.
    ///
    /// @param currentHeader the current block header
    /// @param neighborHeader the candidate neighbor header
    /// @return whether the neighbor is projectable for local warped motion
    private boolean isLocalWarpReferenceNeighbor(
            TileBlockHeaderReader.BlockHeader currentHeader,
            TileBlockHeaderReader.BlockHeader neighborHeader
    ) {
        TileBlockHeaderReader.BlockHeader checkedNeighbor = Objects.requireNonNull(neighborHeader, "neighborHeader");
        return !checkedNeighbor.intra()
                && !checkedNeighbor.useIntrabc()
                && !checkedNeighbor.compoundReference()
                && !checkedNeighbor.interIntra()
                && checkedNeighbor.referenceFrame0() == currentHeader.referenceFrame0()
                && checkedNeighbor.motionVector0() != null;
    }

    /// Applies AV1 inter-intra blending to the already built single-reference inter predictor.
    ///
    /// @param lumaPlane the mutable luma destination plane containing the inter predictor
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter-intra state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    private void applyInterIntraPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            TileSampleBounds tileBounds
    ) {
        InterIntraPredictionMode mode = Objects.requireNonNull(header.interIntraMode(), "header.interIntraMode()");
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        int lumaPredictionWidth = header.size().widthPixels();
        int lumaPredictionHeight = header.size().heightPixels();

        MutableSamplePlane lumaIntraPlane = new BlockOverlayPlane(
                lumaPlane,
                lumaX,
                lumaY,
                lumaPredictionWidth,
                lumaPredictionHeight
        );
        intraPredictor.predictLuma(
                lumaIntraPlane,
                lumaX,
                lumaY,
                lumaPredictionWidth,
                lumaPredictionHeight,
                mode.toLumaPredictionMode(),
                0,
                false,
                false,
                -1,
                -1,
                tileBounds.lumaStartX(),
                tileBounds.lumaStartY(),
                tileBounds.lumaEndX(),
                tileBounds.lumaEndY()
        );
        blendInterIntraPlane(
                lumaPlane,
                lumaIntraPlane,
                header,
                mode,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                0,
                0
        );

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int chromaX = chromaBlockX(header, chromaSubsamplingX);
        int chromaY = chromaBlockY(header, chromaSubsamplingY);
        int visibleChromaWidth = visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX);
        int visibleChromaHeight = visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY);
        int chromaPredictionWidth = header.size().widthPixels() >> chromaSubsamplingX;
        int chromaPredictionHeight = header.size().heightPixels() >> chromaSubsamplingY;

        MutableSamplePlane chromaUIntraPlane = new BlockOverlayPlane(
                chromaUPlane,
                chromaX,
                chromaY,
                chromaPredictionWidth,
                chromaPredictionHeight
        );
        intraPredictor.predictChroma(
                chromaUIntraPlane,
                chromaX,
                chromaY,
                chromaPredictionWidth,
                chromaPredictionHeight,
                mode.toUvPredictionMode(),
                0,
                false,
                false,
                -1,
                -1,
                tileBounds.chromaStartX(),
                tileBounds.chromaStartY(),
                tileBounds.chromaEndX(),
                tileBounds.chromaEndY()
        );
        blendInterIntraPlane(
                chromaUPlane,
                chromaUIntraPlane,
                header,
                mode,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY
        );

        MutableSamplePlane chromaVIntraPlane = new BlockOverlayPlane(
                chromaVPlane,
                chromaX,
                chromaY,
                chromaPredictionWidth,
                chromaPredictionHeight
        );
        intraPredictor.predictChroma(
                chromaVIntraPlane,
                chromaX,
                chromaY,
                chromaPredictionWidth,
                chromaPredictionHeight,
                mode.toUvPredictionMode(),
                0,
                false,
                false,
                -1,
                -1,
                tileBounds.chromaStartX(),
                tileBounds.chromaStartY(),
                tileBounds.chromaEndX(),
                tileBounds.chromaEndY()
        );
        blendInterIntraPlane(
                chromaVPlane,
                chromaVIntraPlane,
                header,
                mode,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY
        );
    }

    /// Blends one inter predictor plane with its intra predictor using an AV1 inter-intra mask.
    ///
    /// @param destinationPlane the mutable destination plane containing the inter predictor
    /// @param intraPlane the mutable intra predictor plane
    /// @param header the decoded block header that owns the inter-intra state
    /// @param mode the decoded inter-intra prediction mode
    /// @param originX the destination-plane block origin X
    /// @param originY the destination-plane block origin Y
    /// @param width the visible block width in destination-plane samples
    /// @param height the visible block height in destination-plane samples
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    private void blendInterIntraPlane(
            MutablePlaneBuffer destinationPlane,
            MutableSamplePlane intraPlane,
            TileBlockHeaderReader.BlockHeader header,
            InterIntraPredictionMode mode,
            int originX,
            int originY,
            int width,
            int height,
            int subsamplingX,
            int subsamplingY
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int mask = InterIntraMasks.maskValue(
                        mode,
                        header.interIntraWedge(),
                        header.interIntraWedgeIndex(),
                        header.size(),
                        x,
                        y,
                        subsamplingX,
                        subsamplingY
                );
                int interSample = destinationPlane.sample(originX + x, originY + y);
                int intraSample = intraPlane.sample(originX + x, originY + y);
                destinationPlane.setSample(
                        originX + x,
                        originY + y,
                        (interSample * (64 - mask) + intraSample * mask + 32) >> 6
                );
            }
        }
    }

    /// Reconstructs compound-reference inter prediction by averaging or masked-blending two
    /// independently sampled reference surfaces.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private void reconstructCompoundInterPrediction(
            MutablePlaneBuffer lumaPlane,
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            Av1ChromaFormat chromaFormat,
            FrameHeader frameHeader,
            int frameLumaWidth,
            int frameLumaHeight,
            int orderHintBits,
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 =
                requireReferenceSurfaceSnapshot(
                        referenceSurfaceSnapshots,
                        frameHeader,
                        chromaFormat,
                        lumaPlane.bitDepth(),
                        header.referenceFrame0()
                );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 =
                requireReferenceSurfaceSnapshot(
                        referenceSurfaceSnapshots,
                        frameHeader,
                        chromaFormat,
                        lumaPlane.bitDepth(),
                        header.referenceFrame1()
                );
        DecodedSurface referencePlanes0 = referenceSurfaceSnapshot0.decodedPlanes();
        DecodedSurface referencePlanes1 = referenceSurfaceSnapshot1.decodedPlanes();
        ReferenceScale referenceScale0 = referenceScale(frameLumaWidth, frameLumaHeight, referenceSurfaceSnapshot0);
        ReferenceScale referenceScale1 = referenceScale(frameLumaWidth, frameLumaHeight, referenceSurfaceSnapshot1);
        MotionVector motionVector0 = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        MotionVector motionVector1 = Objects.requireNonNull(header.motionVector1(), "header.motionVector1()").vector();
        CompoundPredictionType compoundPredictionType =
                Objects.requireNonNull(header.compoundPredictionType(), "header.compoundPredictionType()");
        int jointWeight = compoundPredictionType == CompoundPredictionType.WEIGHTED_AVERAGE
                ? jointCompoundWeight(
                frameHeader,
                referenceSurfaceSnapshot0.frameHeader(),
                referenceSurfaceSnapshot1.frameHeader(),
                orderHintBits
        )
                : 8;
        FrameHeader.InterpolationFilter horizontalInterpolationFilter = resolveHorizontalInterpolationFilter(header, frameHeader);
        FrameHeader.InterpolationFilter verticalInterpolationFilter = resolveVerticalInterpolationFilter(header, frameHeader);
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        int lumaBlockWidth = header.size().widthPixels();
        int lumaBlockHeight = header.size().heightPixels();
        WarpedMotion.Model lumaGlobalWarpModel0 = compoundGlobalWarpModel(
                header,
                frameHeader,
                header.referenceFrame0(),
                referenceScale0,
                lumaBlockWidth,
                lumaBlockHeight
        );
        WarpedMotion.Model lumaGlobalWarpModel1 = compoundGlobalWarpModel(
                header,
                frameHeader,
                header.referenceFrame1(),
                referenceScale1,
                lumaBlockWidth,
                lumaBlockHeight
        );
        int[] segmentMask = compoundPredictionType == CompoundPredictionType.SEGMENT
                ? new int[visibleLumaWidth * visibleLumaHeight]
                : null;

        reconstructCompoundInterPlanePrediction(
                lumaPlane,
                referencePlanes0.lumaPlane(),
                referencePlanes1.lumaPlane(),
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                header.position().x4(),
                header.position().y4(),
                motionVector0.columnEighthPel(),
                motionVector0.rowEighthPel(),
                motionVector1.columnEighthPel(),
                motionVector1.rowEighthPel(),
                8,
                8,
                referenceScale0,
                referenceScale1,
                lumaGlobalWarpModel0,
                lumaGlobalWarpModel1,
                lumaBlockWidth,
                lumaBlockHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
                compoundPredictionType,
                header.compoundMaskSign(),
                header.compoundWedgeIndex(),
                header.size(),
                0,
                0,
                true,
                lumaPlane.bitDepth(),
                jointWeight,
                segmentMask,
                visibleLumaWidth,
                visibleLumaHeight
        );

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int chromaX = chromaBlockX(header, chromaSubsamplingX);
        int chromaY = chromaBlockY(header, chromaSubsamplingY);
        int visibleChromaWidth = visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX);
        int visibleChromaHeight = visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY);
        int chromaBlockWidth = header.size().widthPixels() >> chromaSubsamplingX;
        int chromaBlockHeight = header.size().heightPixels() >> chromaSubsamplingY;
        int chromaDenominatorX = 8 << chromaSubsamplingX;
        int chromaDenominatorY = 8 << chromaSubsamplingY;
        WarpedMotion.Model chromaGlobalWarpModel0 = compoundGlobalWarpModel(
                header,
                frameHeader,
                header.referenceFrame0(),
                referenceScale0,
                chromaBlockWidth,
                chromaBlockHeight
        );
        WarpedMotion.Model chromaGlobalWarpModel1 = compoundGlobalWarpModel(
                header,
                frameHeader,
                header.referenceFrame1(),
                referenceScale1,
                chromaBlockWidth,
                chromaBlockHeight
        );

        reconstructCompoundInterPlanePrediction(
                chromaUPlane,
                Objects.requireNonNull(referencePlanes0.chromaUPlane(), "referencePlanes0.chromaUPlane()"),
                Objects.requireNonNull(referencePlanes1.chromaUPlane(), "referencePlanes1.chromaUPlane()"),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                header.position().x4(),
                header.position().y4(),
                motionVector0.columnEighthPel(),
                motionVector0.rowEighthPel(),
                motionVector1.columnEighthPel(),
                motionVector1.rowEighthPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                referenceScale0,
                referenceScale1,
                chromaGlobalWarpModel0,
                chromaGlobalWarpModel1,
                chromaBlockWidth,
                chromaBlockHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
                compoundPredictionType,
                header.compoundMaskSign(),
                header.compoundWedgeIndex(),
                header.size(),
                chromaSubsamplingX,
                chromaSubsamplingY,
                false,
                chromaUPlane.bitDepth(),
                jointWeight,
                segmentMask,
                visibleLumaWidth,
                visibleLumaHeight
        );
        reconstructCompoundInterPlanePrediction(
                chromaVPlane,
                Objects.requireNonNull(referencePlanes0.chromaVPlane(), "referencePlanes0.chromaVPlane()"),
                Objects.requireNonNull(referencePlanes1.chromaVPlane(), "referencePlanes1.chromaVPlane()"),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                header.position().x4(),
                header.position().y4(),
                motionVector0.columnEighthPel(),
                motionVector0.rowEighthPel(),
                motionVector1.columnEighthPel(),
                motionVector1.rowEighthPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                referenceScale0,
                referenceScale1,
                chromaGlobalWarpModel0,
                chromaGlobalWarpModel1,
                chromaBlockWidth,
                chromaBlockHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
                compoundPredictionType,
                header.compoundMaskSign(),
                header.compoundWedgeIndex(),
                header.size(),
                chromaSubsamplingX,
                chromaSubsamplingY,
                false,
                chromaVPlane.bitDepth(),
                jointWeight,
                segmentMask,
                visibleLumaWidth,
                visibleLumaHeight
        );
    }

    /// Reconstructs one inter-predicted plane using AV1 reference scaling and subpel filtering.
    ///
    /// Same-size integer-aligned predictions use a direct copy. Differently sized reference frames
    /// use the normative Q14 scale factors and Q10 per-sample stepping before interpolation.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param framePlaneWidth the current coded-frame width in samples for this plane
    /// @param framePlaneHeight the current coded-frame height in samples for this plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param width the copied width in samples
    /// @param height the copied height in samples
    /// @param sourceOffsetEighthPelX the signed horizontal motion-vector component in luma eighth-pel units
    /// @param sourceOffsetEighthPelY the signed vertical motion-vector component in luma eighth-pel units
    /// @param denominatorX the plane-local horizontal denominator expressed in luma eighth-pel units
    /// @param denominatorY the plane-local vertical denominator expressed in luma eighth-pel units
    /// @param referenceScale the scale factors derived from the current and reference luma dimensions
    /// @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    private void reconstructInterPlanePrediction(
            MutablePlaneBuffer destinationPlane,
            PaddedPlane referencePlane,
            int framePlaneWidth,
            int framePlaneHeight,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int sourceOffsetEighthPelX,
            int sourceOffsetEighthPelY,
            int denominatorX,
            int denominatorY,
            ReferenceScale referenceScale,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode
    ) {
        ReferenceScale nonNullReferenceScale = Objects.requireNonNull(referenceScale, "referenceScale");
        if (!nonNullReferenceScale.scaled()
                && framePlaneWidth == referencePlane.width()
                && framePlaneHeight == referencePlane.height()
                && Math.floorMod(sourceOffsetEighthPelX, denominatorX) == 0
                && Math.floorMod(sourceOffsetEighthPelY, denominatorY) == 0) {
            copyReferencePlaneBlock(
                    destinationPlane,
                    referencePlane,
                    destinationX,
                    destinationY,
                    destinationX + sourceOffsetEighthPelX / denominatorX,
                    destinationY + sourceOffsetEighthPelY / denominatorY,
                    width,
                    height
            );
            return;
        }

        if (!nonNullReferenceScale.scaled()) {
            reconstructUnscaledInterPlanePrediction(
                    destinationPlane,
                    referencePlane,
                    destinationX,
                    destinationY,
                    width,
                    height,
                    sourceOffsetEighthPelX,
                    sourceOffsetEighthPelY,
                    denominatorX,
                    denominatorY,
                    widthForFilterSelection,
                    heightForFilterSelection,
                    horizontalFilterMode,
                    verticalFilterMode
            );
            return;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        sampleInterPlaneValue(
                                referencePlane,
                                destinationX,
                                destinationY,
                                x,
                                y,
                                sourceOffsetEighthPelX,
                                sourceOffsetEighthPelY,
                                denominatorX,
                                denominatorY,
                                nonNullReferenceScale,
                                widthForFilterSelection,
                                heightForFilterSelection,
                                horizontalFilterMode,
                                verticalFilterMode,
                                destinationPlane.maxSampleValue()
                        )
                );
            }
        }
    }

    /// Reconstructs one unscaled inter-predicted plane with block-level separable filtering.
    ///
    /// Motion-vector fractions and filter kernels are constant across an unscaled block. For a
    /// two-dimensional fixed filter, the horizontal pass is therefore computed once for each
    /// required source row and reused by all eight vertical taps.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param width the predicted width in samples
    /// @param height the predicted height in samples
    /// @param sourceOffsetEighthPelX the signed horizontal motion-vector component
    /// @param sourceOffsetEighthPelY the signed vertical motion-vector component
    /// @param denominatorX the plane-local horizontal denominator
    /// @param denominatorY the plane-local vertical denominator
    /// @param widthForFilterSelection the sampled block width used for filter selection
    /// @param heightForFilterSelection the sampled block height used for filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter
    /// @param verticalFilterMode the effective vertical interpolation filter
    private void reconstructUnscaledInterPlanePrediction(
            MutablePlaneBuffer destinationPlane,
            PaddedPlane referencePlane,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int sourceOffsetEighthPelX,
            int sourceOffsetEighthPelY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode
    ) {
        int sourceX0 = destinationX + Math.floorDiv(sourceOffsetEighthPelX, denominatorX);
        int sourceY0 = destinationY + Math.floorDiv(sourceOffsetEighthPelY, denominatorY);
        int phaseX = interpolationPhase(Math.floorMod(sourceOffsetEighthPelX, denominatorX), denominatorX);
        int phaseY = interpolationPhase(Math.floorMod(sourceOffsetEighthPelY, denominatorY), denominatorY);
        if (phaseX == 0 && phaseY == 0) {
            copyReferencePlaneBlock(
                    destinationPlane,
                    referencePlane,
                    destinationX,
                    destinationY,
                    sourceX0,
                    sourceY0,
                    width,
                    height
            );
            return;
        }
        if (horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                && verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            reconstructUnscaledBilinearInterPlanePrediction(
                    destinationPlane,
                    referencePlane,
                    destinationX,
                    destinationY,
                    sourceX0,
                    sourceY0,
                    width,
                    height,
                    phaseX,
                    phaseY
            );
            return;
        }
        if (!isConcreteInterpolationFilter(horizontalFilterMode)
                || !isConcreteInterpolationFilter(verticalFilterMode)
                || horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                || verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            throw new IllegalStateException(
                    "Inter reconstruction requires resolved matching BILINEAR or EIGHT_TAP_* filters"
            );
        }

        int[] horizontalFilter = phaseX == 0
                ? null
                : selectSubpelFilter(horizontalFilterMode, phaseX, widthForFilterSelection);
        int[] verticalFilter = phaseY == 0
                ? null
                : selectSubpelFilter(verticalFilterMode, phaseY, heightForFilterSelection);
        int maximumSampleValue = destinationPlane.maxSampleValue();
        int intermediateBits = interPredictionIntermediateBits(maximumSampleValue);
        if (verticalFilter == null) {
            int[] checkedHorizontalFilter = Objects.requireNonNull(horizontalFilter, "horizontalFilter");
            int referenceStride = width + INTER_FILTER_TAP_COUNT - 1;
            short[] referenceSamples = copyExtendedReferenceRegion(
                    referencePlane,
                    sourceX0 - INTER_FILTER_START_OFFSET,
                    sourceY0,
                    referenceStride,
                    height
            );
            for (int y = 0; y < height; y++) {
                int referenceRowOffset = y * referenceStride;
                for (int x = 0; x < width; x++) {
                    long filtered = 0;
                    for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
                        filtered += (long) checkedHorizontalFilter[tapIndex]
                                * (referenceSamples[referenceRowOffset + x + tapIndex] & 0xFFFF);
                    }
                    int intermediate = roundShift(filtered, INTER_FILTER_BITS - intermediateBits);
                    destinationPlane.setSample(
                            destinationX + x,
                            destinationY + y,
                            clamp(roundShift(intermediate, intermediateBits), 0, maximumSampleValue)
                    );
                }
            }
            return;
        }
        if (horizontalFilter == null) {
            int referenceRowCount = height + INTER_FILTER_TAP_COUNT - 1;
            short[] referenceSamples = copyExtendedReferenceRegion(
                    referencePlane,
                    sourceX0,
                    sourceY0 - INTER_FILTER_START_OFFSET,
                    width,
                    referenceRowCount
            );
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    long filtered = 0;
                    for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
                        filtered += (long) verticalFilter[tapIndex]
                                * (referenceSamples[(y + tapIndex) * width + x] & 0xFFFF);
                    }
                    destinationPlane.setSample(
                            destinationX + x,
                            destinationY + y,
                            clamp(
                                    roundShift(filtered, INTER_FILTER_BITS),
                                    0,
                                    maximumSampleValue
                            )
                    );
                }
            }
            return;
        }

        int horizontalRowCount = height + INTER_FILTER_TAP_COUNT - 1;
        int[] horizontalSamples = interPredictionWorkspace
                .horizontalSamples(width * horizontalRowCount);
        int referenceStride = width + INTER_FILTER_TAP_COUNT - 1;
        short[] referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0 - INTER_FILTER_START_OFFSET,
                sourceY0 - INTER_FILTER_START_OFFSET,
                referenceStride,
                horizontalRowCount
        );
        int firstPassRound = INTER_FILTER_BITS - intermediateBits;
        for (int row = 0; row < horizontalRowCount; row++) {
            int rowOffset = row * width;
            int referenceRowOffset = row * referenceStride;
            for (int x = 0; x < width; x++) {
                long filtered = 0;
                for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
                    filtered += (long) horizontalFilter[tapIndex]
                            * (referenceSamples[referenceRowOffset + x + tapIndex] & 0xFFFF);
                }
                horizontalSamples[rowOffset + x] = roundShift(filtered, firstPassRound);
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long combined = 0;
                for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
                    combined += (long) verticalFilter[tapIndex]
                            * horizontalSamples[(y + tapIndex) * width + x];
                }
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        clamp(
                                roundShift(combined, INTER_FILTER_BITS + intermediateBits),
                                0,
                                maximumSampleValue
                        )
                );
            }
        }
    }

    /// Reconstructs one unscaled bilinear inter-predicted plane from constant block phases.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param sourceX0 the integer horizontal source origin
    /// @param sourceY0 the integer vertical source origin
    /// @param width the predicted width in samples
    /// @param height the predicted height in samples
    /// @param phaseX the horizontal interpolation phase in `[0, 15]`
    /// @param phaseY the vertical interpolation phase in `[0, 15]`
    private void reconstructUnscaledBilinearInterPlanePrediction(
            MutablePlaneBuffer destinationPlane,
            PaddedPlane referencePlane,
            int destinationX,
            int destinationY,
            int sourceX0,
            int sourceY0,
            int width,
            int height,
            int phaseX,
            int phaseY
    ) {
        int maximumSampleValue = destinationPlane.maxSampleValue();
        int intermediateBits = interPredictionIntermediateBits(maximumSampleValue);
        int referenceStride = phaseX == 0 ? width : width + 1;
        int referenceRowCount = phaseY == 0 ? height : height + 1;
        short[] referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0,
                sourceY0,
                referenceStride,
                referenceRowCount
        );
        for (int y = 0; y < height; y++) {
            int topRowOffset = y * referenceStride;
            int bottomRowOffset = (y + 1) * referenceStride;
            for (int x = 0; x < width; x++) {
                int topLeft = referenceSamples[topRowOffset + x] & 0xFFFF;
                int sample;
                if (phaseY == 0) {
                    int topRight = referenceSamples[topRowOffset + x + 1] & 0xFFFF;
                    int horizontal = roundShift(
                            bilinearFilterSum(topLeft, topRight, phaseX),
                            4 - intermediateBits
                    );
                    sample = roundShift(horizontal, intermediateBits);
                } else if (phaseX == 0) {
                    int bottomLeft = referenceSamples[bottomRowOffset + x] & 0xFFFF;
                    sample = roundShift(bilinearFilterSum(topLeft, bottomLeft, phaseY), 4);
                } else {
                    int topRight = referenceSamples[topRowOffset + x + 1] & 0xFFFF;
                    int bottomLeft = referenceSamples[bottomRowOffset + x] & 0xFFFF;
                    int bottomRight = referenceSamples[bottomRowOffset + x + 1] & 0xFFFF;
                    int top = roundShift(
                            bilinearFilterSum(topLeft, topRight, phaseX),
                            4 - intermediateBits
                    );
                    int bottom = roundShift(
                            bilinearFilterSum(bottomLeft, bottomRight, phaseX),
                            4 - intermediateBits
                    );
                    sample = roundShift(bilinearFilterSum(top, bottom, phaseY), 4 + intermediateBits);
                }
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        clamp(sample, 0, maximumSampleValue)
                );
            }
        }
    }

    /// Reconstructs one compound inter-predicted plane by blending two independently predicted
    /// reference planes.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane0 the primary immutable reference plane
    /// @param referencePlane1 the secondary immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param width the copied width in samples
    /// @param height the copied height in samples
    /// @param blockX4 the luma block X origin in 4x4 units
    /// @param blockY4 the luma block Y origin in 4x4 units
    /// @param sourceOffsetEighthPelX0 the primary signed horizontal motion-vector component in luma eighth-pel units
    /// @param sourceOffsetEighthPelY0 the primary signed vertical motion-vector component in luma eighth-pel units
    /// @param sourceOffsetEighthPelX1 the secondary signed horizontal motion-vector component in luma eighth-pel units
    /// @param sourceOffsetEighthPelY1 the secondary signed vertical motion-vector component in luma eighth-pel units
    /// @param denominatorX the plane-local horizontal denominator expressed in luma eighth-pel units
    /// @param denominatorY the plane-local vertical denominator expressed in luma eighth-pel units
    /// @param referenceScale0 the primary reference scale factors
    /// @param referenceScale1 the secondary reference scale factors
    /// @param globalWarpModel0 the primary affine global model, or `null` for translation prediction
    /// @param globalWarpModel1 the secondary affine global model, or `null` for translation prediction
    /// @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    /// @param compoundPredictionType the decoded compound prediction blend type
    /// @param maskSign whether the decoded segment or wedge mask uses inverted source order
    /// @param wedgeIndex the decoded compound wedge index, or `-1`
    /// @param blockSize the luma block size that owns this plane prediction
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    /// @param lumaPlane whether this invocation reconstructs the luma plane and must populate the segment mask
    /// @param bitDepth the decoded sample bit depth
    /// @param jointWeight the decoded joint compound weight for weighted average prediction
    /// @param segmentMask the luma-domain segment mask to populate or reuse, or `null`
    /// @param segmentMaskWidth the luma-domain segment mask width
    /// @param segmentMaskHeight the luma-domain segment mask height
    private void reconstructCompoundInterPlanePrediction(
            MutablePlaneBuffer destinationPlane,
            PaddedPlane referencePlane0,
            PaddedPlane referencePlane1,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int blockX4,
            int blockY4,
            int sourceOffsetEighthPelX0,
            int sourceOffsetEighthPelY0,
            int sourceOffsetEighthPelX1,
            int sourceOffsetEighthPelY1,
            int denominatorX,
            int denominatorY,
            ReferenceScale referenceScale0,
            ReferenceScale referenceScale1,
            WarpedMotion.Model globalWarpModel0,
            WarpedMotion.Model globalWarpModel1,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            CompoundPredictionType compoundPredictionType,
            boolean maskSign,
            int wedgeIndex,
            BlockSize blockSize,
            int subsamplingX,
            int subsamplingY,
            boolean lumaPlane,
            int bitDepth,
            int jointWeight,
            int[] segmentMask,
            int segmentMaskWidth,
            int segmentMaskHeight
    ) {
        CompoundPredictionType nonNullCompoundPredictionType =
                Objects.requireNonNull(compoundPredictionType, "compoundPredictionType");
        ReferenceScale nonNullReferenceScale0 = Objects.requireNonNull(referenceScale0, "referenceScale0");
        ReferenceScale nonNullReferenceScale1 = Objects.requireNonNull(referenceScale1, "referenceScale1");
        int[] warpedPrediction0 = globalWarpModel0 == null
                ? null
                : WarpedMotion.predictCompoundPlane(
                referencePlane0,
                width,
                height,
                widthForFilterSelection,
                heightForFilterSelection,
                blockX4,
                blockY4,
                subsamplingX,
                subsamplingY,
                bitDepth,
                globalWarpModel0
        );
        int[] warpedPrediction1 = globalWarpModel1 == null
                ? null
                : WarpedMotion.predictCompoundPlane(
                referencePlane1,
                width,
                height,
                widthForFilterSelection,
                heightForFilterSelection,
                blockX4,
                blockY4,
                subsamplingX,
                subsamplingY,
                bitDepth,
                globalWarpModel1
        );
        int maximumSampleValue = destinationPlane.maxSampleValue();
        int postRoundBits = interPredictionIntermediateBits(maximumSampleValue);
        int predictionLength = width * height;
        boolean useUnscaledPrediction0 = warpedPrediction0 == null && !nonNullReferenceScale0.scaled();
        boolean useUnscaledPrediction1 = warpedPrediction1 == null && !nonNullReferenceScale1.scaled();
        InterPredictionWorkspace workspace = useUnscaledPrediction0 || useUnscaledPrediction1
                ? interPredictionWorkspace
                : null;
        int[] prediction0 = warpedPrediction0;
        if (useUnscaledPrediction0) {
            prediction0 = Objects.requireNonNull(workspace, "workspace")
                    .compoundPrediction0(predictionLength);
            reconstructUnscaledCompoundInterPlanePrediction(
                    referencePlane0,
                    destinationX,
                    destinationY,
                    width,
                    height,
                    sourceOffsetEighthPelX0,
                    sourceOffsetEighthPelY0,
                    denominatorX,
                    denominatorY,
                    widthForFilterSelection,
                    heightForFilterSelection,
                    horizontalFilterMode,
                    verticalFilterMode,
                    maximumSampleValue,
                    prediction0
            );
        }
        int[] prediction1 = warpedPrediction1;
        if (useUnscaledPrediction1) {
            prediction1 = Objects.requireNonNull(workspace, "workspace")
                    .compoundPrediction1(predictionLength);
            reconstructUnscaledCompoundInterPlanePrediction(
                    referencePlane1,
                    destinationX,
                    destinationY,
                    width,
                    height,
                    sourceOffsetEighthPelX1,
                    sourceOffsetEighthPelY1,
                    denominatorX,
                    denominatorY,
                    widthForFilterSelection,
                    heightForFilterSelection,
                    horizontalFilterMode,
                    verticalFilterMode,
                    maximumSampleValue,
                    prediction1
            );
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int predictionIndex = y * width + x;
                int sample0 = prediction0 != null
                        ? prediction0[predictionIndex]
                        : sampleCompoundInterPlaneValue(
                        referencePlane0,
                        destinationX,
                        destinationY,
                        x,
                        y,
                        sourceOffsetEighthPelX0,
                        sourceOffsetEighthPelY0,
                        denominatorX,
                        denominatorY,
                        nonNullReferenceScale0,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        horizontalFilterMode,
                        verticalFilterMode,
                        maximumSampleValue
                );
                int sample1 = prediction1 != null
                        ? prediction1[predictionIndex]
                        : sampleCompoundInterPlaneValue(
                        referencePlane1,
                        destinationX,
                        destinationY,
                        x,
                        y,
                        sourceOffsetEighthPelX1,
                        sourceOffsetEighthPelY1,
                        denominatorX,
                        denominatorY,
                        nonNullReferenceScale1,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        horizontalFilterMode,
                        verticalFilterMode,
                        maximumSampleValue
                );
                int sample = switch (nonNullCompoundPredictionType) {
                    case AVERAGE -> averageCompoundPredictions(sample0, sample1, postRoundBits);
                    case WEIGHTED_AVERAGE ->
                            weightedAverageCompoundPredictions(sample0, sample1, jointWeight, postRoundBits);
                    case WEDGE -> {
                        int mask = InterIntraMasks.compoundWedgeMaskValue(
                                blockSize,
                                wedgeIndex,
                                maskSign,
                                x,
                                y,
                                subsamplingX,
                                subsamplingY
                        );
                        yield blendMaskedCompoundPredictions(sample0, sample1, mask, postRoundBits);
                    }
                    case SEGMENT -> {
                        int mask = segmentCompoundMaskValue(
                                sample0,
                                sample1,
                                bitDepth,
                                postRoundBits,
                                maskSign,
                                x,
                                y,
                                subsamplingX,
                                subsamplingY,
                                lumaPlane,
                                segmentMask,
                                segmentMaskWidth,
                                segmentMaskHeight
                        );
                        yield blendMaskedCompoundPredictions(sample0, sample1, mask, postRoundBits);
                    }
                };
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        clamp(sample, 0, maximumSampleValue)
                );
            }
        }
    }

    /// Writes one unscaled compound predictor using block-level separable filtering.
    ///
    /// The destination retains the AV1 compound post-filter fractional bits. For a
    /// two-dimensional fixed filter, horizontally filtered rows are computed once and reused by
    /// the vertical pass.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal prediction origin
    /// @param destinationY the zero-based vertical prediction origin
    /// @param width the predicted width in samples
    /// @param height the predicted height in samples
    /// @param sourceOffsetEighthPelX the signed horizontal motion-vector component
    /// @param sourceOffsetEighthPelY the signed vertical motion-vector component
    /// @param denominatorX the plane-local horizontal denominator
    /// @param denominatorY the plane-local vertical denominator
    /// @param widthForFilterSelection the sampled block width used for filter selection
    /// @param heightForFilterSelection the sampled block height used for filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter
    /// @param verticalFilterMode the effective vertical interpolation filter
    /// @param maximumSampleValue the maximum legal sample value for the destination bit depth
    /// @param destination predictor storage with room for at least `width * height` samples
    private void reconstructUnscaledCompoundInterPlanePrediction(
            PaddedPlane referencePlane,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int sourceOffsetEighthPelX,
            int sourceOffsetEighthPelY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            int maximumSampleValue,
            int[] destination
    ) {
        int[] nonNullDestination = Objects.requireNonNull(destination, "destination");
        int requiredLength = width * height;
        if (nonNullDestination.length < requiredLength) {
            throw new IllegalArgumentException(
                    "Compound prediction destination too short: " + nonNullDestination.length
            );
        }
        int sourceX0 = destinationX + Math.floorDiv(sourceOffsetEighthPelX, denominatorX);
        int sourceY0 = destinationY + Math.floorDiv(sourceOffsetEighthPelY, denominatorY);
        int phaseX = interpolationPhase(Math.floorMod(sourceOffsetEighthPelX, denominatorX), denominatorX);
        int phaseY = interpolationPhase(Math.floorMod(sourceOffsetEighthPelY, denominatorY), denominatorY);
        int postRoundBits = interPredictionIntermediateBits(maximumSampleValue);
        if (phaseX == 0 && phaseY == 0) {
            short[] referenceSamples = copyExtendedReferenceRegion(
                    referencePlane,
                    sourceX0,
                    sourceY0,
                    width,
                    height
            );
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    nonNullDestination[rowOffset + x] =
                            (referenceSamples[rowOffset + x] & 0xFFFF) << postRoundBits;
                }
            }
            return;
        }
        if (horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                && verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            reconstructUnscaledBilinearCompoundInterPlanePrediction(
                    referencePlane,
                    sourceX0,
                    sourceY0,
                    width,
                    height,
                    phaseX,
                    phaseY,
                    postRoundBits,
                    nonNullDestination
            );
            return;
        }
        if (!isConcreteInterpolationFilter(horizontalFilterMode)
                || !isConcreteInterpolationFilter(verticalFilterMode)
                || horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                || verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            throw new IllegalStateException(
                    "Inter reconstruction requires resolved matching BILINEAR or EIGHT_TAP_* filters"
            );
        }

        int[] horizontalFilter = phaseX == 0
                ? null
                : selectSubpelFilter(horizontalFilterMode, phaseX, widthForFilterSelection);
        int[] verticalFilter = phaseY == 0
                ? null
                : selectSubpelFilter(verticalFilterMode, phaseY, heightForFilterSelection);
        int firstPassRound = INTER_FILTER_BITS - postRoundBits;
        if (verticalFilter == null) {
            int[] checkedHorizontalFilter = Objects.requireNonNull(horizontalFilter, "horizontalFilter");
            int referenceStride = width + INTER_FILTER_TAP_COUNT - 1;
            short[] referenceSamples = copyExtendedReferenceRegion(
                    referencePlane,
                    sourceX0 - INTER_FILTER_START_OFFSET,
                    sourceY0,
                    referenceStride,
                    height
            );
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                int referenceRowOffset = y * referenceStride;
                for (int x = 0; x < width; x++) {
                    long filtered = 0;
                    for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
                        filtered += (long) checkedHorizontalFilter[tapIndex]
                                * (referenceSamples[referenceRowOffset + x + tapIndex] & 0xFFFF);
                    }
                    nonNullDestination[rowOffset + x] = roundShift(filtered, firstPassRound);
                }
            }
            return;
        }
        if (horizontalFilter == null) {
            int referenceRowCount = height + INTER_FILTER_TAP_COUNT - 1;
            short[] referenceSamples = copyExtendedReferenceRegion(
                    referencePlane,
                    sourceX0,
                    sourceY0 - INTER_FILTER_START_OFFSET,
                    width,
                    referenceRowCount
            );
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    long filtered = 0;
                    for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
                        filtered += (long) verticalFilter[tapIndex]
                                * (referenceSamples[(y + tapIndex) * width + x] & 0xFFFF);
                    }
                    nonNullDestination[rowOffset + x] = roundShift(filtered, firstPassRound);
                }
            }
            return;
        }

        int horizontalRowCount = height + INTER_FILTER_TAP_COUNT - 1;
        int[] horizontalSamples = interPredictionWorkspace
                .horizontalSamples(width * horizontalRowCount);
        int referenceStride = width + INTER_FILTER_TAP_COUNT - 1;
        short[] referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0 - INTER_FILTER_START_OFFSET,
                sourceY0 - INTER_FILTER_START_OFFSET,
                referenceStride,
                horizontalRowCount
        );
        for (int row = 0; row < horizontalRowCount; row++) {
            int rowOffset = row * width;
            int referenceRowOffset = row * referenceStride;
            for (int x = 0; x < width; x++) {
                long filtered = 0;
                for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
                    filtered += (long) horizontalFilter[tapIndex]
                            * (referenceSamples[referenceRowOffset + x + tapIndex] & 0xFFFF);
                }
                horizontalSamples[rowOffset + x] = roundShift(filtered, firstPassRound);
            }
        }
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                long combined = 0;
                for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
                    combined += (long) verticalFilter[tapIndex]
                            * horizontalSamples[(y + tapIndex) * width + x];
                }
                nonNullDestination[rowOffset + x] = roundShift(combined, INTER_FILTER_BITS);
            }
        }
    }

    /// Writes one unscaled bilinear compound predictor with constant block phases.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceX0 the integer horizontal source origin
    /// @param sourceY0 the integer vertical source origin
    /// @param width the predicted width in samples
    /// @param height the predicted height in samples
    /// @param phaseX the horizontal interpolation phase in `[0, 15]`
    /// @param phaseY the vertical interpolation phase in `[0, 15]`
    /// @param postRoundBits the fractional bits retained for compound blending
    /// @param destination predictor storage with room for at least `width * height` samples
    private void reconstructUnscaledBilinearCompoundInterPlanePrediction(
            PaddedPlane referencePlane,
            int sourceX0,
            int sourceY0,
            int width,
            int height,
            int phaseX,
            int phaseY,
            int postRoundBits,
            int[] destination
    ) {
        int firstPassRound = 4 - postRoundBits;
        int referenceStride = phaseX == 0 ? width : width + 1;
        int referenceRowCount = phaseY == 0 ? height : height + 1;
        short[] referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0,
                sourceY0,
                referenceStride,
                referenceRowCount
        );
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            int topRowOffset = y * referenceStride;
            int bottomRowOffset = (y + 1) * referenceStride;
            for (int x = 0; x < width; x++) {
                int topLeft = referenceSamples[topRowOffset + x] & 0xFFFF;
                int predictor;
                if (phaseY == 0) {
                    int topRight = referenceSamples[topRowOffset + x + 1] & 0xFFFF;
                    predictor = roundShift(
                            bilinearFilterSum(topLeft, topRight, phaseX),
                            firstPassRound
                    );
                } else if (phaseX == 0) {
                    int bottomLeft = referenceSamples[bottomRowOffset + x] & 0xFFFF;
                    predictor = roundShift(
                            bilinearFilterSum(topLeft, bottomLeft, phaseY),
                            firstPassRound
                    );
                } else {
                    int topRight = referenceSamples[topRowOffset + x + 1] & 0xFFFF;
                    int bottomLeft = referenceSamples[bottomRowOffset + x] & 0xFFFF;
                    int bottomRight = referenceSamples[bottomRowOffset + x + 1] & 0xFFFF;
                    int top = roundShift(
                            bilinearFilterSum(topLeft, topRight, phaseX),
                            firstPassRound
                    );
                    int bottom = roundShift(
                            bilinearFilterSum(bottomLeft, bottomRight, phaseX),
                            firstPassRound
                    );
                    predictor = roundShift(bilinearFilterSum(top, bottom, phaseY), 4);
                }
                destination[rowOffset + x] = predictor;
            }
        }
    }

    /// Returns one compound inter predictor while retaining the AV1 post-filter fractional bits.
    ///
    /// Unlike [#sampleInterPlaneValue(PaddedPlane , int, int, int, int, int, int, int, int,
    /// ReferenceScale, int, int, FrameHeader.InterpolationFilter,
    /// FrameHeader.InterpolationFilter, int)], this method neither rounds to sample precision nor
    /// clips the predictor. Compound blending performs both operations after combining the two
    /// predictors.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal prediction origin
    /// @param destinationY the zero-based vertical prediction origin
    /// @param sampleX the block-local horizontal sample offset
    /// @param sampleY the block-local vertical sample offset
    /// @param sourceOffsetEighthPelX the signed horizontal motion-vector component in luma eighth-pel units
    /// @param sourceOffsetEighthPelY the signed vertical motion-vector component in luma eighth-pel units
    /// @param denominatorX the plane-local horizontal denominator expressed in luma eighth-pel units
    /// @param denominatorY the plane-local vertical denominator expressed in luma eighth-pel units
    /// @param referenceScale the scale factors derived from the current and reference luma dimensions
    /// @param widthForFilterSelection the sampled block width used for reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height used for reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    /// @param maximumSampleValue the maximum legal sample value for the destination bit depth
    /// @return one signed predictor with the AV1 compound post-filter fractional bits retained
    private int sampleCompoundInterPlaneValue(
            PaddedPlane referencePlane,
            int destinationX,
            int destinationY,
            int sampleX,
            int sampleY,
            int sourceOffsetEighthPelX,
            int sourceOffsetEighthPelY,
            int denominatorX,
            int denominatorY,
            ReferenceScale referenceScale,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            int maximumSampleValue
    ) {
        ReferenceScale nonNullReferenceScale = Objects.requireNonNull(referenceScale, "referenceScale");
        int sourceNumeratorX;
        int sourceNumeratorY;
        int interpolationDenominatorX;
        int interpolationDenominatorY;
        if (nonNullReferenceScale.scaled()) {
            sourceNumeratorX = scaledReferenceSourceNumerator(
                    destinationX,
                    sampleX,
                    sourceOffsetEighthPelX,
                    denominatorX,
                    nonNullReferenceScale.horizontalFactor()
            );
            sourceNumeratorY = scaledReferenceSourceNumerator(
                    destinationY,
                    sampleY,
                    sourceOffsetEighthPelY,
                    denominatorY,
                    nonNullReferenceScale.verticalFactor()
            );
            interpolationDenominatorX = 1 << SCALED_INTER_SUBPEL_BITS;
            interpolationDenominatorY = 1 << SCALED_INTER_SUBPEL_BITS;
        } else {
            sourceNumeratorX = (destinationX + sampleX) * denominatorX + sourceOffsetEighthPelX;
            sourceNumeratorY = (destinationY + sampleY) * denominatorY + sourceOffsetEighthPelY;
            interpolationDenominatorX = denominatorX;
            interpolationDenominatorY = denominatorY;
        }
        int postRoundBits = interPredictionIntermediateBits(maximumSampleValue);
        if (Math.floorMod(sourceNumeratorX, interpolationDenominatorX) == 0
                && Math.floorMod(sourceNumeratorY, interpolationDenominatorY) == 0) {
            int sample = referencePlane.sample(
                    clamp(Math.floorDiv(sourceNumeratorX, interpolationDenominatorX), 0, referencePlane.width() - 1),
                    clamp(Math.floorDiv(sourceNumeratorY, interpolationDenominatorY), 0, referencePlane.height() - 1)
            );
            return sample << postRoundBits;
        }
        return filteredCompoundInterpolateAt(
                referencePlane,
                sourceNumeratorX,
                sourceNumeratorY,
                interpolationDenominatorX,
                interpolationDenominatorY,
                widthForFilterSelection,
                heightForFilterSelection,
                horizontalFilterMode,
                verticalFilterMode,
                maximumSampleValue
        );
    }

    /// Returns one fixed-filter compound predictor before the final compound blend and post-round.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal interpolation denominator
    /// @param denominatorY the vertical interpolation denominator
    /// @param widthForFilterSelection the sampled block width in pixels
    /// @param heightForFilterSelection the sampled block height in pixels
    /// @param horizontalFilterMode the effective horizontal interpolation filter
    /// @param verticalFilterMode the effective vertical interpolation filter
    /// @param maximumSampleValue the maximum legal sample value for the destination bit depth
    /// @return one signed predictor retaining the AV1 compound post-filter fractional bits
    private int filteredCompoundInterpolateAt(
            PaddedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            int maximumSampleValue
    ) {
        if (horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                && verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            return bilinearCompoundInterpolateAt(
                    referencePlane,
                    sourceNumeratorX,
                    sourceNumeratorY,
                    denominatorX,
                    denominatorY,
                    maximumSampleValue
            );
        }
        if (!isConcreteInterpolationFilter(horizontalFilterMode)
                || !isConcreteInterpolationFilter(verticalFilterMode)
                || horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                || verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            throw new IllegalStateException(
                    "Inter reconstruction requires resolved matching BILINEAR or EIGHT_TAP_* filters"
            );
        }

        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int phaseY = interpolationPhase(Math.floorMod(sourceNumeratorY, denominatorY), denominatorY);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int phaseX = interpolationPhase(Math.floorMod(sourceNumeratorX, denominatorX), denominatorX);
        int postRoundBits = interPredictionIntermediateBits(maximumSampleValue);
        if (phaseX == 0 && phaseY == 0) {
            return referencePlane.sample(
                    clamp(sourceX0, 0, referencePlane.width() - 1),
                    clamp(sourceY0, 0, referencePlane.height() - 1)
            ) << postRoundBits;
        }
        int firstPassRound = INTER_FILTER_BITS - postRoundBits;
        int[] horizontalFilter =
                phaseX == 0 ? null : selectSubpelFilter(horizontalFilterMode, phaseX, widthForFilterSelection);
        int[] verticalFilter =
                phaseY == 0 ? null : selectSubpelFilter(verticalFilterMode, phaseY, heightForFilterSelection);
        if (verticalFilter == null) {
            return roundShift(
                    horizontalInterpolate(
                            referencePlane,
                            sourceX0,
                            sourceY0,
                            Objects.requireNonNull(horizontalFilter, "horizontalFilter")
                    ),
                    firstPassRound
            );
        }
        if (horizontalFilter == null) {
            return roundShift(
                    verticalInterpolate(
                            referencePlane,
                            sourceX0,
                            sourceY0,
                            verticalFilter
                    ),
                    firstPassRound
            );
        }

        long combined = 0;
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceY = clamp(sourceY0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.height() - 1);
            int horizontallyFiltered = roundShift(
                    horizontalInterpolate(referencePlane, sourceX0, sourceY, horizontalFilter),
                    firstPassRound
            );
            combined += (long) verticalFilter[tapIndex] * horizontallyFiltered;
        }
        return roundShift(combined, INTER_FILTER_BITS);
    }

    /// Returns one bilinear compound predictor before the final compound blend and post-round.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal interpolation denominator
    /// @param denominatorY the vertical interpolation denominator
    /// @param maximumSampleValue the maximum legal sample value for the destination bit depth
    /// @return one signed predictor retaining the AV1 compound post-filter fractional bits
    private int bilinearCompoundInterpolateAt(
            PaddedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY,
            int maximumSampleValue
    ) {
        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int fractionY = interpolationPhase(Math.floorMod(sourceNumeratorY, denominatorY), denominatorY);
        int clampedSourceY0 = clamp(sourceY0, 0, referencePlane.height() - 1);
        int clampedSourceY1 = clamp(sourceY0 + 1, 0, referencePlane.height() - 1);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int fractionX = interpolationPhase(Math.floorMod(sourceNumeratorX, denominatorX), denominatorX);
        int clampedSourceX0 = clamp(sourceX0, 0, referencePlane.width() - 1);
        int clampedSourceX1 = clamp(sourceX0 + 1, 0, referencePlane.width() - 1);
        int topLeft = referencePlane.sample(clampedSourceX0, clampedSourceY0);
        int topRight = referencePlane.sample(clampedSourceX1, clampedSourceY0);
        int bottomLeft = referencePlane.sample(clampedSourceX0, clampedSourceY1);
        int bottomRight = referencePlane.sample(clampedSourceX1, clampedSourceY1);
        int postRoundBits = interPredictionIntermediateBits(maximumSampleValue);
        int firstPassRound = 4 - postRoundBits;
        if (fractionY == 0) {
            return roundShift(bilinearFilterSum(topLeft, topRight, fractionX), firstPassRound);
        }
        if (fractionX == 0) {
            return roundShift(bilinearFilterSum(topLeft, bottomLeft, fractionY), firstPassRound);
        }
        int top = roundShift(bilinearFilterSum(topLeft, topRight, fractionX), firstPassRound);
        int bottom = roundShift(bilinearFilterSum(bottomLeft, bottomRight, fractionX), firstPassRound);
        return roundShift(bilinearFilterSum(top, bottom, fractionY), 4);
    }

    /// Returns one inter-predicted plane sample at a block-local destination offset.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal prediction origin
    /// @param destinationY the zero-based vertical prediction origin
    /// @param sampleX the block-local horizontal sample offset
    /// @param sampleY the block-local vertical sample offset
    /// @param sourceOffsetEighthPelX the signed horizontal motion-vector component in luma eighth-pel units
    /// @param sourceOffsetEighthPelY the signed vertical motion-vector component in luma eighth-pel units
    /// @param denominatorX the plane-local horizontal denominator expressed in luma eighth-pel units
    /// @param denominatorY the plane-local vertical denominator expressed in luma eighth-pel units
    /// @param referenceScale the scale factors derived from the current and reference luma dimensions
    /// @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    /// @param maximumSampleValue the maximum legal output sample value for the destination bit depth
    /// @return one predicted plane sample
    private int sampleInterPlaneValue(
            PaddedPlane referencePlane,
            int destinationX,
            int destinationY,
            int sampleX,
            int sampleY,
            int sourceOffsetEighthPelX,
            int sourceOffsetEighthPelY,
            int denominatorX,
            int denominatorY,
            ReferenceScale referenceScale,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            int maximumSampleValue
    ) {
        ReferenceScale nonNullReferenceScale = Objects.requireNonNull(referenceScale, "referenceScale");
        int sourceNumeratorX;
        int sourceNumeratorY;
        int interpolationDenominatorX;
        int interpolationDenominatorY;
        if (nonNullReferenceScale.scaled()) {
            sourceNumeratorX = scaledReferenceSourceNumerator(
                    destinationX,
                    sampleX,
                    sourceOffsetEighthPelX,
                    denominatorX,
                    nonNullReferenceScale.horizontalFactor()
            );
            sourceNumeratorY = scaledReferenceSourceNumerator(
                    destinationY,
                    sampleY,
                    sourceOffsetEighthPelY,
                    denominatorY,
                    nonNullReferenceScale.verticalFactor()
            );
            interpolationDenominatorX = 1 << SCALED_INTER_SUBPEL_BITS;
            interpolationDenominatorY = 1 << SCALED_INTER_SUBPEL_BITS;
        } else {
            sourceNumeratorX = (destinationX + sampleX) * denominatorX + sourceOffsetEighthPelX;
            sourceNumeratorY = (destinationY + sampleY) * denominatorY + sourceOffsetEighthPelY;
            interpolationDenominatorX = denominatorX;
            interpolationDenominatorY = denominatorY;
        }
        if (Math.floorMod(sourceNumeratorX, interpolationDenominatorX) == 0
                && Math.floorMod(sourceNumeratorY, interpolationDenominatorY) == 0) {
            return referencePlane.sample(
                    clamp(Math.floorDiv(sourceNumeratorX, interpolationDenominatorX), 0, referencePlane.width() - 1),
                    clamp(Math.floorDiv(sourceNumeratorY, interpolationDenominatorY), 0, referencePlane.height() - 1)
            );
        }
        return filteredInterpolateAt(
                referencePlane,
                sourceNumeratorX,
                sourceNumeratorY,
                interpolationDenominatorX,
                interpolationDenominatorY,
                widthForFilterSelection,
                heightForFilterSelection,
                horizontalFilterMode,
                verticalFilterMode,
                maximumSampleValue
        );
    }

    /// Copies one rectangular reference-plane footprint into the destination plane with edge
    /// extension.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param sourceX the zero-based horizontal source coordinate
    /// @param sourceY the zero-based vertical source coordinate
    /// @param width the copied width in samples
    /// @param height the copied height in samples
    private void copyReferencePlaneBlock(
            MutablePlaneBuffer destinationPlane,
            PaddedPlane referencePlane,
            int destinationX,
            int destinationY,
            int sourceX,
            int sourceY,
            int width,
            int height
    ) {
        destinationPlane.copyExtendedBlockFrom(
                referencePlane,
                destinationX,
                destinationY,
                sourceX,
                sourceY,
                width,
                height
        );
    }

    /// Copies one edge-extended reference region into reusable tightly packed storage.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceX the horizontal source origin
    /// @param sourceY the vertical source origin
    /// @param width the positive copied width in samples
    /// @param height the positive copied height in samples
    /// @return reusable row-major reference storage whose active prefix has `width * height` samples
    private short[] copyExtendedReferenceRegion(
            PaddedPlane referencePlane,
            int sourceX,
            int sourceY,
            int width,
            int height
    ) {
        int requiredLength = Math.multiplyExact(width, height);
        short[] referenceSamples = interPredictionWorkspace.referenceSamples(requiredLength);
        for (int row = 0; row < height; row++) {
            referencePlane.copyExtendedRowTo(
                    sourceX,
                    sourceY + row,
                    referenceSamples,
                    row * width,
                    width
            );
        }
        return referenceSamples;
    }

    /// Returns one fixed-filter interpolated unsigned sample at the supplied plane-local source
    /// numerator coordinates.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal interpolation denominator
    /// @param denominatorY the vertical interpolation denominator
    /// @param widthForFilterSelection the sampled block width in pixels
    /// @param heightForFilterSelection the sampled block height in pixels
    /// @param horizontalFilterMode the effective horizontal interpolation filter
    /// @param verticalFilterMode the effective vertical interpolation filter
    /// @param maximumSampleValue the maximum legal output sample value for the destination bit depth
    /// @return one fixed-filter interpolated unsigned sample
    private int filteredInterpolateAt(
            PaddedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            int maximumSampleValue
    ) {
        if (horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                && verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            return bilinearInterpolateAt(
                    referencePlane,
                    sourceNumeratorX,
                    sourceNumeratorY,
                    denominatorX,
                    denominatorY,
                    maximumSampleValue
            );
        }
        if (!isConcreteInterpolationFilter(horizontalFilterMode)
                || !isConcreteInterpolationFilter(verticalFilterMode)
                || horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                || verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            throw new IllegalStateException(
                    "Inter reconstruction requires resolved matching BILINEAR or EIGHT_TAP_* filters"
            );
        }

        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int phaseY = interpolationPhase(Math.floorMod(sourceNumeratorY, denominatorY), denominatorY);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int phaseX = interpolationPhase(Math.floorMod(sourceNumeratorX, denominatorX), denominatorX);
        if (phaseX == 0 && phaseY == 0) {
            return referencePlane.sample(
                    clamp(sourceX0, 0, referencePlane.width() - 1),
                    clamp(sourceY0, 0, referencePlane.height() - 1)
            );
        }

        int[] horizontalFilter =
                phaseX == 0 ? null : selectSubpelFilter(horizontalFilterMode, phaseX, widthForFilterSelection);
        int[] verticalFilter =
                phaseY == 0 ? null : selectSubpelFilter(verticalFilterMode, phaseY, heightForFilterSelection);
        int intermediateBits = interPredictionIntermediateBits(maximumSampleValue);
        if (verticalFilter == null) {
            long filtered = horizontalInterpolate(
                    referencePlane,
                    sourceX0,
                    sourceY0,
                    Objects.requireNonNull(horizontalFilter, "horizontalFilter")
            );
            int intermediate = roundShift(filtered, INTER_FILTER_BITS - intermediateBits);
            return clamp(roundShift(intermediate, intermediateBits), 0, maximumSampleValue);
        }
        if (horizontalFilter == null) {
            long filtered = verticalInterpolate(
                    referencePlane,
                    sourceX0,
                    sourceY0,
                    Objects.requireNonNull(verticalFilter, "verticalFilter")
            );
            return clamp(roundShift(filtered, INTER_FILTER_BITS), 0, maximumSampleValue);
        }

        long combined = 0;
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceY = clamp(sourceY0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.height() - 1);
            long filtered = horizontalInterpolate(referencePlane, sourceX0, sourceY, horizontalFilter);
            int horizontallyFiltered = roundShift(filtered, INTER_FILTER_BITS - intermediateBits);
            combined += (long) verticalFilter[tapIndex] * horizontallyFiltered;
        }
        return clamp(roundShift(combined, INTER_FILTER_BITS + intermediateBits), 0, maximumSampleValue);
    }

    /// Returns one bilinearly interpolated unsigned sample at the supplied plane-local source
    /// numerator coordinates.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal interpolation denominator
    /// @param denominatorY the vertical interpolation denominator
    /// @param maximumSampleValue the maximum legal output sample value for the destination bit depth
    /// @return one bilinearly interpolated unsigned sample
    private int bilinearInterpolateAt(
            PaddedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY,
            int maximumSampleValue
    ) {
        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int fractionY = interpolationPhase(Math.floorMod(sourceNumeratorY, denominatorY), denominatorY);
        int clampedSourceY0 = clamp(sourceY0, 0, referencePlane.height() - 1);
        int clampedSourceY1 = clamp(sourceY0 + 1, 0, referencePlane.height() - 1);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int fractionX = interpolationPhase(Math.floorMod(sourceNumeratorX, denominatorX), denominatorX);
        int clampedSourceX0 = clamp(sourceX0, 0, referencePlane.width() - 1);
        int clampedSourceX1 = clamp(sourceX0 + 1, 0, referencePlane.width() - 1);
        int topLeft = referencePlane.sample(clampedSourceX0, clampedSourceY0);
        int topRight = referencePlane.sample(clampedSourceX1, clampedSourceY0);
        int bottomLeft = referencePlane.sample(clampedSourceX0, clampedSourceY1);
        int bottomRight = referencePlane.sample(clampedSourceX1, clampedSourceY1);
        if (fractionY == 0) {
            int intermediateBits = interPredictionIntermediateBits(maximumSampleValue);
            int horizontal = roundShift(
                    bilinearFilterSum(topLeft, topRight, fractionX),
                    4 - intermediateBits
            );
            return clamp(roundShift(horizontal, intermediateBits), 0, maximumSampleValue);
        }
        if (fractionX == 0) {
            return clamp(
                    roundShift(bilinearFilterSum(topLeft, bottomLeft, fractionY), 4),
                    0,
                    maximumSampleValue
            );
        }

        int intermediateBits = interPredictionIntermediateBits(maximumSampleValue);
        int top = roundShift(bilinearFilterSum(topLeft, topRight, fractionX), 4 - intermediateBits);
        int bottom = roundShift(bilinearFilterSum(bottomLeft, bottomRight, fractionX), 4 - intermediateBits);
        return clamp(
                roundShift(bilinearFilterSum(top, bottom, fractionY), 4 + intermediateBits),
                0,
                maximumSampleValue
        );
    }

    /// Returns one filtered horizontal interpolation sum before normalization.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceX0 the integer horizontal source position
    /// @param sourceY the integer vertical source position
    /// @param filter the selected AV1 subpel filter taps
    /// @return one filtered horizontal interpolation sum before normalization
    private long horizontalInterpolate(
            PaddedPlane referencePlane,
            int sourceX0,
            int sourceY,
            int[] filter
    ) {
        long filtered = 0;
        int clampedY = clamp(sourceY, 0, referencePlane.height() - 1);
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceX = clamp(sourceX0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.width() - 1);
            filtered += (long) filter[tapIndex] * referencePlane.sample(sourceX, clampedY);
        }
        return filtered;
    }

    /// Returns one filtered vertical interpolation sum before normalization.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceX the integer horizontal source position
    /// @param sourceY0 the integer vertical source position
    /// @param filter the selected AV1 subpel filter taps
    /// @return one filtered vertical interpolation sum before normalization
    private long verticalInterpolate(
            PaddedPlane referencePlane,
            int sourceX,
            int sourceY0,
            int[] filter
    ) {
        long filtered = 0;
        int clampedX = clamp(sourceX, 0, referencePlane.width() - 1);
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceY = clamp(sourceY0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.height() - 1);
            filtered += (long) filter[tapIndex] * referencePlane.sample(clampedX, sourceY);
        }
        return filtered;
    }

    /// Returns the selected AV1 fixed-filter taps for one fractional phase and sampled axis size.
    ///
    /// @param filterMode the requested interpolation filter mode
    /// @param phase the normalized AV1 fractional phase in `[1, 15]`
    /// @param axisSize the sampled axis size in pixels
    /// @return the selected AV1 fixed-filter taps
    private int[] selectSubpelFilter(
            FrameHeader.InterpolationFilter filterMode,
            int phase,
            int axisSize
    ) {
        if (phase <= 0 || phase >= INTER_FILTER_PHASES) {
            throw new IllegalStateException("AV1 fixed-filter phase out of range: " + phase);
        }
        return switch (filterMode) {
            case EIGHT_TAP_REGULAR ->
                    (axisSize <= 4 ? SMALL_REGULAR_SUBPEL_FILTERS : REGULAR_SUBPEL_FILTERS)[phase - 1];
            case EIGHT_TAP_SMOOTH -> (axisSize <= 4 ? SMALL_SMOOTH_SUBPEL_FILTERS : SMOOTH_SUBPEL_FILTERS)[phase - 1];
            case EIGHT_TAP_SHARP -> (axisSize <= 4 ? SMALL_REGULAR_SUBPEL_FILTERS : SHARP_SUBPEL_FILTERS)[phase - 1];
            default -> throw new IllegalStateException(
                    "AV1 fixed-filter selection requires an EIGHT_TAP_REGULAR, EIGHT_TAP_SMOOTH, or EIGHT_TAP_SHARP filter"
            );
        };
    }

    /// Returns AV1 reference scale factors for one current/reference frame pair.
    ///
    /// @param currentWidth the current coded luma width
    /// @param currentHeight the current coded luma height
    /// @param referenceSurfaceSnapshot the stored reference surface
    /// @return the fixed-point reference scale factors
    private ReferenceScale referenceScale(
            int currentWidth,
            int currentHeight,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot
    ) {
        ReferenceSurfaceSnapshot nonNullSnapshot =
                Objects.requireNonNull(referenceSurfaceSnapshot, "referenceSurfaceSnapshot");
        FrameHeader.FrameSize referenceSize = nonNullSnapshot.frameHeader().frameSize();
        int referenceWidth = referenceSize.upscaledWidth();
        int referenceHeight = referenceSize.height();
        if (referenceWidth == currentWidth && referenceHeight == currentHeight) {
            return IDENTITY_REFERENCE_SCALE;
        }
        return new ReferenceScale(
                referenceScaleFactor(referenceWidth, currentWidth),
                referenceScaleFactor(referenceHeight, currentHeight),
                true
        );
    }

    /// Returns one AV1 Q14 reference scale factor.
    ///
    /// @param referenceExtent the stored reference luma extent
    /// @param currentExtent the current coded luma extent
    /// @return the rounded Q14 scale factor
    private int referenceScaleFactor(int referenceExtent, int currentExtent) {
        if (referenceExtent <= 0 || currentExtent <= 0) {
            throw new IllegalStateException("Reference scaling requires positive frame dimensions");
        }
        return Math.toIntExact(
                (((long) referenceExtent << REFERENCE_SCALE_BITS) + (currentExtent >> 1)) / currentExtent
        );
    }

    /// Returns one Q10 source coordinate for scaled AV1 inter prediction.
    ///
    /// The block origin uses AV1's signed center-offset scale formula. Subsequent samples advance by
    /// the rounded Q10 scale step, matching the scaled motion-compensation process.
    ///
    /// @param destinationOrigin the prediction origin in plane samples
    /// @param sampleOffset the block-local sample offset
    /// @param motionVectorEighthPel the motion-vector component in luma eighth-pel units
    /// @param planeDenominator the plane-local denominator in luma eighth-pel units
    /// @param scaleFactor the Q14 luma-domain reference scale factor
    /// @return the scaled source coordinate in Q10 plane-sample units
    private int scaledReferenceSourceNumerator(
            int destinationOrigin,
            int sampleOffset,
            int motionVectorEighthPel,
            int planeDenominator,
            int scaleFactor
    ) {
        if (planeDenominator != 8 && planeDenominator != 16) {
            throw new IllegalStateException("Unexpected inter-prediction plane denominator: " + planeDenominator);
        }
        long originalPosition = (long) destinationOrigin * 16
                + (long) motionVectorEighthPel * (16 / planeDenominator);
        long scaledPosition = originalPosition * scaleFactor
                + (long) (scaleFactor - REFERENCE_SCALE_IDENTITY) * 8;
        int blockStart = roundShiftSigned(scaledPosition, 8) + 32;
        int step = (scaleFactor + 8) >> 4;
        return Math.toIntExact((long) blockStart + (long) sampleOffset * step);
    }

    /// Returns the normalized AV1 subpel phase for the supplied plane-local fraction.
    ///
    /// @param fraction the plane-local source fraction
    /// @param denominator the plane-local interpolation denominator
    /// @return the normalized AV1 subpel phase in `[0, 15]`
    private int interpolationPhase(int fraction, int denominator) {
        if (fraction == 0) {
            return 0;
        }
        return Math.multiplyExact(fraction, INTER_FILTER_PHASES) / denominator;
    }

    /// Returns the number of fractional bits retained between horizontal and vertical inter
    /// filtering for the supplied decoded sample range.
    ///
    /// @param maximumSampleValue the maximum legal sample value for the decoded bit depth
    /// @return `4` for 8- and 10-bit samples, or `2` for 12-bit samples
    private int interPredictionIntermediateBits(int maximumSampleValue) {
        return switch (maximumSampleValue) {
            case 255, 1023 -> 4;
            case 4095 -> 2;
            default -> throw new IllegalArgumentException(
                    "Unsupported AV1 inter-prediction sample range: " + maximumSampleValue
            );
        };
    }

    /// Applies AV1 `Round2` to one value using an arithmetic right shift.
    ///
    /// @param value the value to round
    /// @param bits the number of low bits to discard, including zero
    /// @return the rounded value
    private int roundShift(long value, int bits) {
        if (bits == 0) {
            return Math.toIntExact(value);
        }
        return Math.toIntExact((value + (1L << (bits - 1))) >> bits);
    }

    /// Rounds one signed integer by the requested arithmetic right shift.
    ///
    /// @param value the signed value to round
    /// @param bits the number of low bits to discard
    /// @return the rounded signed value
    private int roundShiftSigned(long value, int bits) {
        long roundingOffset = 1L << (bits - 1);
        if (value >= 0) {
            return (int) ((value + roundingOffset) >> bits);
        }
        return (int) -(((-value) + roundingOffset) >> bits);
    }

    /// Returns whether one interpolation filter is resolved to a concrete prediction kernel.
    ///
    /// @param filterMode the interpolation filter mode
    /// @return whether the filter is concrete rather than switchable
    private boolean isConcreteInterpolationFilter(FrameHeader.InterpolationFilter filterMode) {
        return filterMode == FrameHeader.InterpolationFilter.BILINEAR
                || filterMode == FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
                || filterMode == FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH
                || filterMode == FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP;
    }

    /// Returns one unnormalized AV1 bilinear-filter sum.
    ///
    /// @param first the sample at the integer source position
    /// @param second the sample one position after the integer source position
    /// @param phase the subpel phase in `[0, 15]`
    /// @return the filtered sum with four fractional bits
    private int bilinearFilterSum(int first, int second, int phase) {
        return 16 * first + phase * (second - first);
    }

    /// Returns one simple average-compound sample from two higher-precision predictors.
    ///
    /// @param primaryPrediction the primary higher-precision predictor
    /// @param secondaryPrediction the secondary higher-precision predictor
    /// @param postRoundBits the fractional predictor bits discarded after blending
    /// @return the averaged compound sample
    private int averageCompoundPredictions(
            int primaryPrediction,
            int secondaryPrediction,
            int postRoundBits
    ) {
        return roundShift((long) primaryPrediction + secondaryPrediction, 1 + postRoundBits);
    }

    /// Returns one weighted average-compound sample from two higher-precision predictors.
    ///
    /// @param primaryPrediction the primary higher-precision predictor
    /// @param secondaryPrediction the secondary higher-precision predictor
    /// @param primaryWeight the primary predictor weight in sixteenths
    /// @param postRoundBits the fractional predictor bits discarded after blending
    /// @return one weighted average-compound sample
    private int weightedAverageCompoundPredictions(
            int primaryPrediction,
            int secondaryPrediction,
            int primaryWeight,
            int postRoundBits
    ) {
        return roundShift(
                (long) primaryPrediction * primaryWeight
                        + (long) secondaryPrediction * (16 - primaryWeight),
                4 + postRoundBits
        );
    }

    /// Returns one masked compound sample using the supplied secondary-source weight.
    ///
    /// @param primarySample the primary predicted sample
    /// @param secondarySample the secondary predicted sample
    /// @param secondaryWeight the secondary predictor weight in `[0, 64]`
    /// @return one masked compound sample
    private int blendMaskedCompoundSamples(int primarySample, int secondarySample, int secondaryWeight) {
        return (primarySample * (64 - secondaryWeight) + secondarySample * secondaryWeight + 32) >> 6;
    }

    /// Returns one masked compound sample from two higher-precision predictors.
    ///
    /// @param primaryPrediction the primary higher-precision predictor
    /// @param secondaryPrediction the secondary higher-precision predictor
    /// @param secondaryWeight the secondary predictor weight in `[0, 64]`
    /// @param postRoundBits the fractional predictor bits discarded after blending
    /// @return one masked compound sample
    private int blendMaskedCompoundPredictions(
            int primaryPrediction,
            int secondaryPrediction,
            int secondaryWeight,
            int postRoundBits
    ) {
        return roundShift(
                (long) primaryPrediction * (64 - secondaryWeight)
                        + (long) secondaryPrediction * secondaryWeight,
                6 + postRoundBits
        );
    }

    /// Returns one segment-compound mask value as the effective secondary-source blend weight.
    ///
    /// @param primarySample the primary higher-precision predictor
    /// @param secondarySample the secondary higher-precision predictor
    /// @param bitDepth the decoded sample bit depth
    /// @param postRoundBits the fractional predictor bits discarded after blending
    /// @param maskSign whether the decoded segment mask uses inverted source order
    /// @param x the plane-local sample X coordinate inside the block
    /// @param y the plane-local sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    /// @param lumaPlane whether the caller is reconstructing luma and must populate the segment mask
    /// @param segmentMask the luma-domain segment mask to populate or reuse, or `null`
    /// @param segmentMaskWidth the luma-domain segment mask width
    /// @param segmentMaskHeight the luma-domain segment mask height
    /// @return one segment-compound mask value as the effective secondary-source blend weight
    private int segmentCompoundMaskValue(
            int primarySample,
            int secondarySample,
            int bitDepth,
            int postRoundBits,
            boolean maskSign,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY,
            boolean lumaPlane,
            int[] segmentMask,
            int segmentMaskWidth,
            int segmentMaskHeight
    ) {
        int mask;
        if (lumaPlane) {
            mask = lumaSegmentCompoundMaskValue(primarySample, secondarySample, bitDepth, postRoundBits);
            if (segmentMask != null && x < segmentMaskWidth && y < segmentMaskHeight) {
                segmentMask[y * segmentMaskWidth + x] = mask;
            }
        } else {
            if (segmentMask == null) {
                throw new IllegalStateException("Chroma segment compound prediction requires a luma segment mask");
            }
            mask = chromaSegmentCompoundMaskValue(
                    segmentMask,
                    segmentMaskWidth,
                    segmentMaskHeight,
                    x,
                    y,
                    subsamplingX,
                    subsamplingY,
                    maskSign
            );
        }
        return maskSign ? mask : 64 - mask;
    }

    /// Returns one luma-domain segment-compound mask value.
    ///
    /// @param primarySample the primary higher-precision predictor
    /// @param secondarySample the secondary higher-precision predictor
    /// @param bitDepth the decoded sample bit depth
    /// @param postRoundBits the fractional predictor bits retained by each input
    /// @return one luma-domain segment-compound mask value
    private int lumaSegmentCompoundMaskValue(
            int primarySample,
            int secondarySample,
            int bitDepth,
            int postRoundBits
    ) {
        int roundedDifference = roundShift(
                Math.abs((long) primarySample - secondarySample),
                postRoundBits + bitDepth - 8
        );
        int scaledDifference = roundedDifference >> 4;
        return Math.min(38 + scaledDifference, 64);
    }

    /// Returns one chroma segment-compound mask value derived from the luma-domain segment mask.
    ///
    /// @param segmentMask the luma-domain segment mask
    /// @param segmentMaskWidth the luma-domain segment mask width
    /// @param segmentMaskHeight the luma-domain segment mask height
    /// @param x the chroma-plane sample X coordinate inside the block
    /// @param y the chroma-plane sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param maskSign whether the decoded segment mask uses inverted source order
    /// @return one chroma segment-compound mask value
    private int chromaSegmentCompoundMaskValue(
            int[] segmentMask,
            int segmentMaskWidth,
            int segmentMaskHeight,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY,
            boolean maskSign
    ) {
        int lumaX = x << subsamplingX;
        int lumaY = y << subsamplingY;
        int horizontalSpan = 1 << subsamplingX;
        int verticalSpan = 1 << subsamplingY;
        int sum = 0;
        for (int yy = 0; yy < verticalSpan; yy++) {
            int sampleY = Math.min(segmentMaskHeight - 1, lumaY + yy);
            for (int xx = 0; xx < horizontalSpan; xx++) {
                int sampleX = Math.min(segmentMaskWidth - 1, lumaX + xx);
                sum += segmentMask[sampleY * segmentMaskWidth + sampleX];
            }
        }
        int subsamplingShift = subsamplingX + subsamplingY;
        int roundingOffset = subsamplingShift == 0 ? 0 : 1 << (subsamplingShift - 1);
        int invertedMaskAdjustment = maskSign && subsamplingShift != 0 ? 1 : 0;
        return (sum + roundingOffset - invertedMaskAdjustment) >> subsamplingShift;
    }

    /// Returns the joint compound primary weight for one decoded reference pair.
    ///
    /// @param frameHeader the current frame header
    /// @param referenceHeader0 the primary reference frame header
    /// @param referenceHeader1 the secondary reference frame header
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @return the joint compound primary weight for one decoded reference pair
    private int jointCompoundWeight(
            FrameHeader frameHeader,
            FrameHeader referenceHeader0,
            FrameHeader referenceHeader1,
            int orderHintBits
    ) {
        int distance1 = Math.min(
                Math.abs(orderHintDifference(orderHintBits, referenceHeader0.frameOffset(), frameHeader.frameOffset())),
                31
        );
        int distance0 = Math.min(
                Math.abs(orderHintDifference(orderHintBits, referenceHeader1.frameOffset(), frameHeader.frameOffset())),
                31
        );
        boolean order = distance0 <= distance1;
        int[][] quantDistanceWeight = {{2, 3}, {2, 5}, {2, 7}};
        int[][] quantDistanceLookup = {{9, 7}, {11, 5}, {12, 4}, {13, 3}};
        int k;
        for (k = 0; k < quantDistanceWeight.length; k++) {
            int c0 = quantDistanceWeight[k][order ? 1 : 0];
            int c1 = quantDistanceWeight[k][order ? 0 : 1];
            int distance0Scaled = distance0 * c0;
            int distance1Scaled = distance1 * c1;
            if ((distance0 > distance1 && distance0Scaled < distance1Scaled)
                    || (distance0 <= distance1 && distance0Scaled > distance1Scaled)) {
                break;
            }
        }
        return quantDistanceLookup[k][order ? 1 : 0];
    }

    /// Returns the wrapped order-hint difference `poc0 - poc1`.
    ///
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param poc0 the minuend order hint
    /// @param poc1 the subtrahend order hint
    /// @return the wrapped order-hint difference `poc0 - poc1`
    private int orderHintDifference(int orderHintBits, int poc0, int poc1) {
        if (orderHintBits == 0) {
            return 0;
        }
        int mask = 1 << (orderHintBits - 1);
        int diff = poc0 - poc1;
        return (diff & (mask - 1)) - (diff & mask);
    }

    /// Resolves the effective horizontal interpolation filter for one inter block.
    ///
    /// @param header the decoded block header that owns the inter state
    /// @param frameHeader the frame header that owns the block
    /// @return the effective horizontal interpolation filter
    private FrameHeader.InterpolationFilter resolveHorizontalInterpolationFilter(
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader
    ) {
        if (frameHeader.subpelFilterMode() != FrameHeader.InterpolationFilter.SWITCHABLE) {
            return frameHeader.subpelFilterMode();
        }
        FrameHeader.InterpolationFilter interpolationFilter = header.horizontalInterpolationFilter();
        if (interpolationFilter == null || !isConcreteInterpolationFilter(interpolationFilter)) {
            throw new IllegalStateException("Inter reconstruction requires a resolved horizontal interpolation filter");
        }
        return interpolationFilter;
    }

    /// Resolves the effective vertical interpolation filter for one inter block.
    ///
    /// @param header the decoded block header that owns the inter state
    /// @param frameHeader the frame header that owns the block
    /// @return the effective vertical interpolation filter
    private FrameHeader.InterpolationFilter resolveVerticalInterpolationFilter(
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader
    ) {
        if (frameHeader.subpelFilterMode() != FrameHeader.InterpolationFilter.SWITCHABLE) {
            return frameHeader.subpelFilterMode();
        }
        FrameHeader.InterpolationFilter interpolationFilter = header.verticalInterpolationFilter();
        if (interpolationFilter == null || !isConcreteInterpolationFilter(interpolationFilter)) {
            throw new IllegalStateException("Inter reconstruction requires a resolved vertical interpolation filter");
        }
        return interpolationFilter;
    }

    /// Returns one compatible stored reference surface for the supplied internal LAST..ALTREF
    /// reference position.
    ///
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param frameHeader the frame header that owns the block
    /// @param chromaFormat the active decoded chroma layout
    /// @param bitDepth the decoded sample bit depth of the current frame
    /// @param referenceFramePosition the internal LAST..ALTREF reference position
    /// @return one compatible stored reference surface for the supplied reference position
    private ReferenceSurfaceSnapshot requireReferenceSurfaceSnapshot(
            ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            FrameHeader frameHeader,
            Av1ChromaFormat chromaFormat,
            int bitDepth,
            int referenceFramePosition
    ) {
        if (referenceFramePosition < 0 || referenceFramePosition >= 7) {
            throw new IllegalStateException("Inter reconstruction requires one valid reference-frame position");
        }
        int referenceSlot = frameHeader.referenceFrameIndex(referenceFramePosition);
        if (referenceSlot < 0 || referenceSlot >= referenceSurfaceSnapshots.length) {
            throw new IllegalStateException("Inter reconstruction requires one populated stored reference surface");
        }

        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = referenceSurfaceSnapshots[referenceSlot];
        if (referenceSurfaceSnapshot == null) {
            throw new IllegalStateException("Inter reconstruction requires one populated stored reference surface");
        }

        DecodedSurface referencePlanes = referenceSurfaceSnapshot.decodedPlanes();
        if (referencePlanes.bitDepth() != bitDepth) {
            throw new IllegalStateException(
                    "Inter reconstruction requires a stored reference surface whose bit depth matches the current frame"
            );
        }
        if (referencePlanes.chromaFormat() != chromaFormat) {
            throw new IllegalStateException(
                    "Inter reconstruction requires matching reference chroma format: " + chromaFormat
            );
        }
        return referenceSurfaceSnapshot;
    }

    /// Reconstructs one luma palette block directly into the destination plane.
    ///
    /// Palette indices are stored as two packed 4-bit entries per byte in raster order with the
    /// invisible right and bottom edges already replicated by the syntax layer.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param header the decoded block header that owns the luma palette state
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param lumaWidth the stored luma block width in pixels
    /// @param lumaHeight the stored luma block height in pixels
    private void reconstructLumaPalette(
            MutablePlaneBuffer lumaPlane,
            TileBlockHeaderReader.BlockHeader header,
            int lumaX,
            int lumaY,
            int lumaWidth,
            int lumaHeight
    ) {
        reconstructPalettePlane(
                lumaPlane,
                lumaX,
                lumaY,
                lumaWidth,
                lumaHeight,
                header.size().widthPixels(),
                header,
                0
        );
    }

    /// Reconstructs one chroma palette block directly into the destination planes.
    ///
    /// Packed palette indices follow the geometry exposed by `TileBlockHeaderReader`; only the
    /// visible `YUV420`, `YUV422`, or `YUV444` chroma footprint is written to the output planes.
    ///
    /// @param chromaUPlane the mutable chroma U destination plane
    /// @param chromaVPlane the mutable chroma V destination plane
    /// @param header the decoded block header that owns the chroma palette state
    /// @param visibleChromaWidth the exact visible chroma width in pixels
    /// @param visibleChromaHeight the exact visible chroma height in pixels
    private void reconstructChromaPalette(
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            Av1ChromaFormat chromaFormat,
            int visibleChromaWidth,
            int visibleChromaHeight
    ) {
        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int chromaX = chromaBlockX(header, chromaSubsamplingX);
        int chromaY = chromaBlockY(header, chromaSubsamplingY);
        int fullChromaWidth = codedChromaBlockWidth(header, chromaSubsamplingX);
        reconstructPalettePlane(
                chromaUPlane,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                fullChromaWidth,
                header,
                1
        );
        reconstructPalettePlane(
                chromaVPlane,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                fullChromaWidth,
                header,
                2
        );
    }

    /// Reconstructs one palette-mapped sample plane directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param startX the zero-based horizontal destination coordinate
    /// @param startY the zero-based vertical destination coordinate
    /// @param visibleWidth the visible block width in pixels before destination-frame clipping
    /// @param visibleHeight the visible block height in pixels before destination-frame clipping
    /// @param packedFullWidth the coded palette-map width in pixels used to compute the packed stride
    /// @param header the block header that owns the palette state
    /// @param palettePlane the palette plane index, where `0` is Y, `1` is U, and `2` is V
    private void reconstructPalettePlane(
            MutablePlaneBuffer plane,
            int startX,
            int startY,
            int visibleWidth,
            int visibleHeight,
            int packedFullWidth,
            TileBlockHeaderReader.BlockHeader header,
            int palettePlane
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        if (palettePlane < 0 || palettePlane > 2) {
            throw new IllegalArgumentException("Palette plane index out of range: " + palettePlane);
        }
        if (packedFullWidth <= 0 || (packedFullWidth & 1) != 0) {
            throw new IllegalStateException("Palette map width must be a positive even value: " + packedFullWidth);
        }
        int paletteSampleCount = palettePlane == 0
                ? nonNullHeader.yPaletteSampleCount()
                : nonNullHeader.uvPaletteSampleCount();
        if (paletteSampleCount < packedFullWidth * visibleHeight) {
            throw new IllegalStateException("Packed palette index map is shorter than the visible footprint");
        }
        int paletteSize = palettePlane == 0 ? nonNullHeader.yPaletteSize() : nonNullHeader.uvPaletteSize();

        int clippedVisibleWidth = Math.min(visibleWidth, plane.width() - startX);
        int clippedVisibleHeight = Math.min(visibleHeight, plane.height() - startY);
        if (clippedVisibleWidth <= 0 || clippedVisibleHeight <= 0) {
            return;
        }

        for (int y = 0; y < clippedVisibleHeight; y++) {
            int sampleRow = y * packedFullWidth;
            for (int x = 0; x < clippedVisibleWidth; x++) {
                int sampleIndex = sampleRow + x;
                int paletteIndex = palettePlane == 0
                        ? nonNullHeader.yPaletteIndex(sampleIndex)
                        : nonNullHeader.uvPaletteIndex(sampleIndex);
                if (paletteIndex < 0 || paletteIndex >= paletteSize) {
                    throw new IllegalStateException("Palette index out of range: " + paletteIndex);
                }
                int paletteColor = switch (palettePlane) {
                    case 0 -> nonNullHeader.yPaletteColor(paletteIndex);
                    case 1 -> nonNullHeader.uPaletteColor(paletteIndex);
                    case 2 -> nonNullHeader.vPaletteColor(paletteIndex);
                    default -> throw new AssertionError("Unreachable palette plane: " + palettePlane);
                };
                plane.setSample(startX + x, startY + y, paletteColor);
            }
        }
    }

    /// Clamps one integer value to the supplied inclusive bounds.
    ///
    /// @param value the value to clamp
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Reconstructs decoded luma residuals into the destination plane.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param residualLayout the decoded luma residual layout
    /// @param header the decoded block header that owns the residuals
    /// @param frameHeader the frame header that owns the active quantization state
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructLumaResiduals(
            MutablePlaneBuffer lumaPlane,
            ResidualLayout residualLayout,
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader,
            boolean strictStdCompliance
    ) {
        int qIndex = blockQIndex(header, frameHeader);
        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        for (int unitIndex = 0; unitIndex < residualLayout.lumaUnitCount(); unitIndex++) {
            reconstructLumaResidualUnit(
                    lumaPlane,
                    residualLayout.lumaUnit(unitIndex),
                    qIndex,
                    quantization,
                    strictStdCompliance
            );
        }
    }

    /// Reconstructs intra luma by predicting and applying residuals per transform unit.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param residualLayout the decoded luma residual layout
    /// @param header the decoded block header that owns the residuals
    /// @param frameHeader the frame header that owns the active quantization state
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructIntraLuma(
            MutablePlaneBuffer lumaPlane,
            ResidualLayout residualLayout,
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            TileSampleBounds tileBounds,
            boolean strictStdCompliance
    ) {
        LumaIntraPredictionMode yMode = Objects.requireNonNull(header.yMode(), "header.yMode()");
        int qIndex = blockQIndex(header, frameHeader);
        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        for (int unitIndex = 0; unitIndex < residualLayout.lumaUnitCount(); unitIndex++) {
            TransformResidualUnit residualUnit = residualLayout.lumaUnit(unitIndex);
            int predictionX = residualUnit.position().x4() << 2;
            int predictionY = residualUnit.position().y4() << 2;
            int predictionWidth = residualUnit.size().widthPixels();
            int predictionHeight = residualUnit.size().heightPixels();
            intraPredictor.predictLuma(
                    lumaPlane,
                    predictionX,
                    predictionY,
                    predictionWidth,
                    predictionHeight,
                    yMode,
                    header.yAngle(),
                    intraEdgeFilterEnabled,
                    smoothEdgeReferences,
                    availableDirectionalTopReferenceLength(
                            lumaPlane,
                            predictionX,
                            predictionY,
                            predictionWidth,
                            predictionHeight,
                            tileBounds.lumaEndX(),
                            header.size(),
                            header.position().x4() << 2,
                            header.position().y4() << 2,
                            0,
                            0
                    ),
                    availableDirectionalLeftReferenceLength(
                            lumaPlane,
                            predictionX,
                            predictionY,
                            predictionWidth,
                            predictionHeight,
                            tileBounds.lumaEndY(),
                            header.size(),
                            header.position().x4() << 2,
                            header.position().y4() << 2,
                            0,
                            0
                    ),
                    tileBounds.lumaStartX(),
                    tileBounds.lumaStartY(),
                    tileBounds.lumaEndX(),
                    tileBounds.lumaEndY()
            );
            reconstructLumaResidualUnit(lumaPlane, residualUnit, qIndex, quantization, strictStdCompliance);
        }
    }

    /// Reconstructs filter-intra luma by predicting and applying residuals per transform unit.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param residualLayout the decoded luma residual layout
    /// @param header the decoded block header that owns the residuals
    /// @param frameHeader the frame header that owns the active quantization state
    /// @param filterIntraMode the decoded filter-intra mode
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructFilterIntraLuma(
            MutablePlaneBuffer lumaPlane,
            ResidualLayout residualLayout,
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader,
            FilterIntraMode filterIntraMode,
            TileSampleBounds tileBounds,
            boolean strictStdCompliance
    ) {
        int qIndex = blockQIndex(header, frameHeader);
        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        for (int unitIndex = 0; unitIndex < residualLayout.lumaUnitCount(); unitIndex++) {
            TransformResidualUnit residualUnit = residualLayout.lumaUnit(unitIndex);
            intraPredictor.predictFilterIntraLuma(
                    lumaPlane,
                    residualUnit.position().x4() << 2,
                    residualUnit.position().y4() << 2,
                    residualUnit.size().widthPixels(),
                    residualUnit.size().heightPixels(),
                    filterIntraMode,
                    tileBounds.lumaStartX(),
                    tileBounds.lumaStartY(),
                    tileBounds.lumaEndX(),
                    tileBounds.lumaEndY()
            );
            reconstructLumaResidualUnit(lumaPlane, residualUnit, qIndex, quantization, strictStdCompliance);
        }
    }

    /// Reconstructs one decoded luma residual unit into the destination plane.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param residualUnit the decoded residual unit
    /// @param qIndex the block-local quantizer index after delta-q updates
    /// @param quantization the frame-level quantization state
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructLumaResidualUnit(
            MutablePlaneBuffer lumaPlane,
            TransformResidualUnit residualUnit,
            int qIndex,
            FrameHeader.QuantizationInfo quantization,
            boolean strictStdCompliance
    ) {
        if (residualUnit.allZero()) {
            return;
        }
        int destinationX = residualUnit.position().x4() << 2;
        int destinationY = residualUnit.position().y4() << 2;
        int writtenWidth = Math.min(residualUnit.size().widthPixels(), lumaPlane.width() - destinationX);
        int writtenHeight = Math.min(residualUnit.size().heightPixels(), lumaPlane.height() - destinationY);
        if (residualUnit.endOfBlockIndex() == 0 && residualUnit.transformType() == TransformType.DCT_DCT) {
            int dequantizedDcCoefficient = LumaDequantizer.dequantizeDcCoefficient(
                    residualUnit,
                    qIndex,
                    quantization.yDcDelta(),
                    lumaPlane.bitDepth(),
                    quantization.useQuantizationMatrices(),
                    quantization.quantizationMatrixY()
            );
            inverseTransformer.reconstructAndAddDcOnlyDctBlock(
                    lumaPlane,
                    destinationX,
                    destinationY,
                    residualUnit.size(),
                    lumaPlane.bitDepth(),
                    writtenWidth,
                    writtenHeight,
                    strictStdCompliance,
                    dequantizedDcCoefficient
            );
            return;
        }

        int[] dequantizedCoefficients = inverseTransformer.coefficientBuffer(residualUnit.size());
        LumaDequantizer.dequantize(
                residualUnit,
                qIndex,
                quantization.yDcDelta(),
                lumaPlane.bitDepth(),
                quantization.useQuantizationMatrices(),
                quantization.quantizationMatrixY(),
                dequantizedCoefficients
        );
        inverseTransformer.reconstructAndAddResidualBlock(
                lumaPlane,
                destinationX,
                destinationY,
                residualUnit.size(),
                residualUnit.transformType(),
                lumaPlane.bitDepth(),
                writtenWidth,
                writtenHeight,
                strictStdCompliance,
                dequantizedCoefficients
        );
    }

    /// Returns the block-local quantizer index after segment-level delta-q.
    ///
    /// `BlockHeader.qIndex()` carries the superblock delta-q runtime value. AV1 applies the active
    /// segment delta on top of that value when deriving dequantizers for the block.
    ///
    /// @param header the decoded block header
    /// @param frameHeader the frame header that owns the active segmentation state
    /// @return the block-local quantizer index after segment-level delta-q
    private int blockQIndex(TileBlockHeaderReader.BlockHeader header, FrameHeader frameHeader) {
        FrameHeader.SegmentationInfo segmentation = frameHeader.segmentation();
        if (!segmentation.enabled()) {
            return header.qIndex();
        }
        return QuantizerTables.clampedQIndex(header.qIndex() + segmentation.segment(header.segmentId()).deltaQ());
    }

    /// Returns the available top-edge directional reference length for one transform prediction.
    ///
    /// @param plane the mutable plane whose written samples are tracked
    /// @param x the plane-local transform X coordinate
    /// @param y the plane-local transform Y coordinate
    /// @param width the transform width in plane samples
    /// @param height the transform height in plane samples
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param blockSize the luma-grid size of the owning coded block
    /// @param blockX the owning block's plane-local X coordinate
    /// @param blockY the owning block's plane-local Y coordinate
    /// @param subsamplingX the plane's horizontal chroma-subsampling shift
    /// @param subsamplingY the plane's vertical chroma-subsampling shift
    /// @return the available top-edge directional reference length
    private int availableDirectionalTopReferenceLength(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int rightBoundary,
            BlockSize blockSize,
            int blockX,
            int blockY,
            int subsamplingX,
            int subsamplingY
    ) {
        int availableLength = Math.max(0, Math.min(width, rightBoundary - x));
        if (availableLength < width) {
            return availableLength;
        }
        if (!permitsTopRightExtensionWithinBlock(
                x,
                y,
                width,
                blockSize,
                blockX,
                blockY,
                subsamplingX,
                subsamplingY
        )) {
            return availableLength;
        }
        int maximumExtraLength = Math.min(width, height);
        for (int extra = 0; extra < maximumExtraLength; extra++) {
            if (x + width + extra >= rightBoundary) {
                break;
            }
            if (!isDirectionalReferenceSampleDecoded(
                    plane,
                    x + width + extra,
                    y - 1
            )) {
                break;
            }
            availableLength++;
        }
        return availableLength;
    }

    /// Returns the available left-edge directional reference length for one transform prediction.
    ///
    /// @param plane the mutable plane whose written samples are tracked
    /// @param x the plane-local transform X coordinate
    /// @param y the plane-local transform Y coordinate
    /// @param width the transform width in plane samples
    /// @param height the transform height in plane samples
    /// @param bottomBoundary the exclusive sample row available to this tile
    /// @param blockSize the luma-grid size of the owning coded block
    /// @param blockX the owning block's plane-local X coordinate
    /// @param blockY the owning block's plane-local Y coordinate
    /// @param subsamplingX the plane's horizontal chroma-subsampling shift
    /// @param subsamplingY the plane's vertical chroma-subsampling shift
    /// @return the available left-edge directional reference length
    private int availableDirectionalLeftReferenceLength(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int bottomBoundary,
            BlockSize blockSize,
            int blockX,
            int blockY,
            int subsamplingX,
            int subsamplingY
    ) {
        int availableLength = Math.max(0, Math.min(height, bottomBoundary - y));
        if (availableLength < height) {
            return availableLength;
        }
        if (!permitsBottomLeftExtensionWithinBlock(
                x,
                y,
                height,
                blockSize,
                blockX,
                blockY,
                subsamplingX,
                subsamplingY
        )) {
            return availableLength;
        }
        int maximumExtraLength = Math.min(width, height);
        for (int extra = 0; extra < maximumExtraLength; extra++) {
            if (y + height + extra >= bottomBoundary) {
                break;
            }
            if (!isDirectionalReferenceSampleDecoded(
                    plane,
                    x - 1,
                    y + height + extra
            )) {
                break;
            }
            availableLength++;
        }
        return availableLength;
    }

    /// Returns whether the owning block permits top-right references beyond one transform's top edge.
    ///
    /// AV1 decodes blocks wider than 64 luma samples as 64-wide regions in raster order. A sample
    /// may therefore already have been written while still being unavailable to a transform that
    /// would cross one of those causal region boundaries.
    ///
    /// @param transformX the transform's plane-local X coordinate
    /// @param transformY the transform's plane-local Y coordinate
    /// @param transformWidth the transform width in plane samples
    /// @param blockSize the luma-grid size of the owning coded block
    /// @param blockX the owning block's plane-local X coordinate
    /// @param blockY the owning block's plane-local Y coordinate
    /// @param subsamplingX the plane's horizontal chroma-subsampling shift
    /// @param subsamplingY the plane's vertical chroma-subsampling shift
    /// @return whether top-right extension is permitted within the owning block
    private boolean permitsTopRightExtensionWithinBlock(
            int transformX,
            int transformY,
            int transformWidth,
            BlockSize blockSize,
            int blockX,
            int blockY,
            int subsamplingX,
            int subsamplingY
    ) {
        int rowOffset4 = (transformY - blockY) >> 2;
        if (rowOffset4 <= 0) {
            return true;
        }

        int columnOffset4 = (transformX - blockX) >> 2;
        int transformWidth4 = transformWidth >> 2;
        int blockWidth4 = Math.max(1, blockSize.width4() >> subsamplingX);
        if (blockSize.widthPixels() <= 64) {
            return columnOffset4 + transformWidth4 < blockWidth4;
        }

        int regionWidth4 = 16 >> subsamplingX;
        int regionHeight4 = 16 >> subsamplingY;
        if (rowOffset4 == regionHeight4 && columnOffset4 + transformWidth4 == regionWidth4) {
            return true;
        }
        return columnOffset4 % regionWidth4 + transformWidth4 < regionWidth4;
    }

    /// Returns whether the owning block permits bottom-left references beyond one transform's left edge.
    ///
    /// @param transformX the transform's plane-local X coordinate
    /// @param transformY the transform's plane-local Y coordinate
    /// @param transformHeight the transform height in plane samples
    /// @param blockSize the luma-grid size of the owning coded block
    /// @param blockX the owning block's plane-local X coordinate
    /// @param blockY the owning block's plane-local Y coordinate
    /// @param subsamplingX the plane's horizontal chroma-subsampling shift
    /// @param subsamplingY the plane's vertical chroma-subsampling shift
    /// @return whether bottom-left extension is permitted within the owning block
    private boolean permitsBottomLeftExtensionWithinBlock(
            int transformX,
            int transformY,
            int transformHeight,
            BlockSize blockSize,
            int blockX,
            int blockY,
            int subsamplingX,
            int subsamplingY
    ) {
        int columnOffset4 = (transformX - blockX) >> 2;
        if (columnOffset4 <= 0) {
            return true;
        }

        if (blockSize.widthPixels() > 64) {
            int regionWidth4 = 16 >> subsamplingX;
            int columnOffsetWithinRegion4 = columnOffset4 % regionWidth4;
            if (columnOffsetWithinRegion4 == 0) {
                int rowOffset4 = (transformY - blockY) >> 2;
                int regionHeight4 = 16 >> subsamplingY;
                int blockHeight4 = Math.max(1, blockSize.height4() >> subsamplingY);
                int availableRegionHeight4 = Math.min(blockHeight4, regionHeight4);
                int transformHeight4 = transformHeight >> 2;
                return rowOffset4 % regionHeight4 + transformHeight4 < availableRegionHeight4;
            }
        }
        return false;
    }

    /// Returns whether one directional reference sample has already been reconstructed.
    ///
    /// @param plane the mutable plane whose written samples are tracked
    /// @param sampleX the plane-local reference sample X coordinate
    /// @param sampleY the plane-local reference sample Y coordinate
    /// @return whether the reference sample is causally available
    private boolean isDirectionalReferenceSampleDecoded(
            MutablePlaneBuffer plane,
            int sampleX,
            int sampleY
    ) {
        return plane.hasWrittenSample(sampleX, sampleY);
    }

    /// Reconstructs intra chroma by predicting and applying residuals per transform unit.
    ///
    /// @param chromaUPlane the mutable chroma U destination plane
    /// @param chromaVPlane the mutable chroma V destination plane
    /// @param residualLayout the decoded chroma residual layout
    /// @param header the decoded block header that owns the residuals
    /// @param frameHeader the frame header that owns the active quantization state
    /// @param chromaFormat the active decoded chroma layout
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    private void reconstructIntraChroma(
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TransformLayout transformLayout,
            ResidualLayout residualLayout,
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader,
            Av1ChromaFormat chromaFormat,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            TileSampleBounds tileBounds,
            boolean strictStdCompliance
    ) {
        UvIntraPredictionMode uvMode = Objects.requireNonNull(header.uvMode(), "header.uvMode()");
        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        int qIndex = blockQIndex(header, frameHeader);
        reconstructIntraChromaPlane(
                chromaUPlane,
                transformLayout,
                residualLayout,
                true,
                header,
                uvMode,
                header.uvAngle(),
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                qIndex,
                quantization,
                chromaFormat,
                chromaUPlane,
                tileBounds,
                strictStdCompliance
        );
        reconstructIntraChromaPlane(
                chromaVPlane,
                transformLayout,
                residualLayout,
                false,
                header,
                uvMode,
                header.uvAngle(),
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                qIndex,
                quantization,
                chromaFormat,
                chromaVPlane,
                tileBounds,
                strictStdCompliance
        );
    }

    /// Reconstructs one intra chroma plane per transform unit.
    ///
    /// @param chromaPlane the mutable destination chroma plane
    /// @param transformLayout the decoded chroma transform layout
    /// @param residualLayout the decoded transform residual layout
    /// @param chromaU whether to reconstruct the U plane rather than the V plane
    /// @param header the decoded block header that owns the transform units
    /// @param uvMode the chroma intra prediction mode
    /// @param uvAngle the derived chroma directional prediction angle
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param qIndex the block-local quantizer index after delta-q updates
    /// @param quantization the frame-level quantization state
    /// @param chromaFormat the active decoded chroma layout
    /// @param referencePlane the mutable plane whose written samples are tracked
    /// @param tileBounds the tile-local sample boundaries used by intra prediction references
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructIntraChromaPlane(
            MutablePlaneBuffer chromaPlane,
            TransformLayout transformLayout,
            ResidualLayout residualLayout,
            boolean chromaU,
            TileBlockHeaderReader.BlockHeader header,
            UvIntraPredictionMode uvMode,
            int uvAngle,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int qIndex,
            FrameHeader.QuantizationInfo quantization,
            Av1ChromaFormat chromaFormat,
            MutablePlaneBuffer referencePlane,
            TileSampleBounds tileBounds,
            boolean strictStdCompliance
    ) {
        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        int blockX = chromaBlockX(header, chromaSubsamplingX);
        int blockY = chromaBlockY(header, chromaSubsamplingY);
        boolean[] appliedResiduals = resetAppliedChromaResiduals(residualLayout.chromaUnitCount());
        for (int transformIndex = 0; transformIndex < transformLayout.chromaUnitCount(); transformIndex++) {
            TransformUnit transformUnit = transformLayout.chromaUnit(transformIndex);
            int predictionX = transformUnit.position().x4() << (2 - chromaSubsamplingX);
            int predictionY = transformUnit.position().y4() << (2 - chromaSubsamplingY);
            int predictionWidth = transformUnit.size().widthPixels();
            int predictionHeight = transformUnit.size().heightPixels();
            intraPredictor.predictChroma(
                    chromaPlane,
                    predictionX,
                    predictionY,
                    predictionWidth,
                    predictionHeight,
                    uvMode,
                    uvAngle,
                    intraEdgeFilterEnabled,
                    smoothEdgeReferences,
                    availableDirectionalTopReferenceLength(
                            referencePlane,
                            predictionX,
                            predictionY,
                            predictionWidth,
                            predictionHeight,
                            tileBounds.chromaEndX(),
                            header.size(),
                            blockX,
                            blockY,
                            chromaSubsamplingX,
                            chromaSubsamplingY
                    ),
                    availableDirectionalLeftReferenceLength(
                            referencePlane,
                            predictionX,
                            predictionY,
                            predictionWidth,
                            predictionHeight,
                            tileBounds.chromaEndY(),
                            header.size(),
                            blockX,
                            blockY,
                            chromaSubsamplingX,
                            chromaSubsamplingY
                    ),
                    tileBounds.chromaStartX(),
                    tileBounds.chromaStartY(),
                    tileBounds.chromaEndX(),
                    tileBounds.chromaEndY()
            );
            for (int i = 0; i < residualLayout.chromaUnitCount(); i++) {
                TransformResidualUnit residualUnit = chromaResidualUnit(residualLayout, i, chromaU);
                if (!appliedResiduals[i] && sameTransformUnit(transformUnit, residualUnit)) {
                    reconstructChromaResidualUnit(
                            chromaPlane,
                            residualUnit,
                            chromaU,
                            qIndex,
                            quantization,
                            chromaSubsamplingX,
                            chromaSubsamplingY,
                            strictStdCompliance
                    );
                    appliedResiduals[i] = true;
                }
            }
        }

        for (int i = 0; i < residualLayout.chromaUnitCount(); i++) {
            if (!appliedResiduals[i]) {
                reconstructChromaResidualUnit(
                        chromaPlane,
                        chromaResidualUnit(residualLayout, i, chromaU),
                        chromaU,
                        qIndex,
                        quantization,
                        chromaSubsamplingX,
                        chromaSubsamplingY,
                        strictStdCompliance
                );
            }
        }
    }

    /// Returns reusable zero-filled flags for one block's chroma residual units.
    ///
    /// @param requiredLength the number of residual-unit flags required by the current block
    /// @return reusable zero-filled flag storage
    private boolean[] resetAppliedChromaResiduals(int requiredLength) {
        if (appliedChromaResiduals.length < requiredLength) {
            appliedChromaResiduals = new boolean[requiredLength];
        } else {
            Arrays.fill(appliedChromaResiduals, 0, requiredLength, false);
        }
        return appliedChromaResiduals;
    }

    /// Returns whether one residual unit maps exactly to one transform unit.
    ///
    /// @param transformUnit the transform unit
    /// @param residualUnit the residual unit
    /// @return whether both units share the same position and transform size
    private boolean sameTransformUnit(TransformUnit transformUnit, TransformResidualUnit residualUnit) {
        return transformUnit.position().equals(residualUnit.position())
                && transformUnit.size() == residualUnit.size();
    }

    /// Reconstructs decoded chroma residuals into the destination planes.
    ///
    /// @param chromaUPlane the mutable chroma U destination plane
    /// @param chromaVPlane the mutable chroma V destination plane
    /// @param residualLayout the decoded residual layout
    /// @param frameHeader the frame header that owns the active quantization state
    /// @param chromaFormat the active decoded chroma layout
    /// @param qIndex the block-local quantizer index after delta-q updates
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructChromaResiduals(
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            ResidualLayout residualLayout,
            FrameHeader frameHeader,
            Av1ChromaFormat chromaFormat,
            int qIndex,
            boolean strictStdCompliance
    ) {
        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        reconstructChromaPlaneResiduals(
                chromaUPlane,
                residualLayout,
                true,
                qIndex,
                quantization,
                chromaFormat,
                strictStdCompliance
        );
        reconstructChromaPlaneResiduals(
                chromaVPlane,
                residualLayout,
                false,
                qIndex,
                quantization,
                chromaFormat,
                strictStdCompliance
        );
    }

    /// Reconstructs one chroma-plane residual array into the supplied destination plane.
    ///
    /// @param chromaPlane the mutable destination chroma plane
    /// @param residualLayout the decoded transform residual layout
    /// @param chromaU whether to reconstruct the U plane rather than the V plane
    /// @param qIndex the block-local quantizer index after delta-q updates
    /// @param quantization the frame-level quantization state
    /// @param chromaFormat the active decoded chroma layout
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructChromaPlaneResiduals(
            MutablePlaneBuffer chromaPlane,
            ResidualLayout residualLayout,
            boolean chromaU,
            int qIndex,
            FrameHeader.QuantizationInfo quantization,
            Av1ChromaFormat chromaFormat,
            boolean strictStdCompliance
    ) {
        int chromaSubsamplingX = chromaSubsamplingX(chromaFormat);
        int chromaSubsamplingY = chromaSubsamplingY(chromaFormat);
        for (int unitIndex = 0; unitIndex < residualLayout.chromaUnitCount(); unitIndex++) {
            reconstructChromaResidualUnit(
                    chromaPlane,
                    chromaResidualUnit(residualLayout, unitIndex, chromaU),
                    chromaU,
                    qIndex,
                    quantization,
                    chromaSubsamplingX,
                    chromaSubsamplingY,
                    strictStdCompliance
            );
        }
    }

    /// Returns one plane-specific chroma residual unit.
    ///
    /// @param residualLayout the decoded residual layout
    /// @param index the zero-based chroma unit index
    /// @param chromaU whether to select the U plane rather than the V plane
    /// @return the selected chroma residual unit
    private TransformResidualUnit chromaResidualUnit(
            ResidualLayout residualLayout,
            int index,
            boolean chromaU
    ) {
        return chromaU ? residualLayout.chromaUUnit(index) : residualLayout.chromaVUnit(index);
    }

    /// Reconstructs one decoded chroma residual unit into the destination plane.
    ///
    /// @param chromaPlane the mutable destination chroma plane
    /// @param residualUnit the decoded residual unit
    /// @param chromaU whether the residual belongs to the U plane rather than the V plane
    /// @param qIndex the block-local quantizer index after delta-q updates
    /// @param quantization the frame-level quantization state
    /// @param chromaSubsamplingX the horizontal chroma subsampling shift
    /// @param chromaSubsamplingY the vertical chroma subsampling shift
    /// @param strictStdCompliance whether malformed transform values must be rejected
    private void reconstructChromaResidualUnit(
            MutablePlaneBuffer chromaPlane,
            TransformResidualUnit residualUnit,
            boolean chromaU,
            int qIndex,
            FrameHeader.QuantizationInfo quantization,
            int chromaSubsamplingX,
            int chromaSubsamplingY,
            boolean strictStdCompliance
    ) {
        if (residualUnit.allZero()) {
            return;
        }
        int dcDelta = chromaU ? quantization.uDcDelta() : quantization.vDcDelta();
        int acDelta = chromaU ? quantization.uAcDelta() : quantization.vAcDelta();
        int quantizationMatrix = chromaU ? quantization.quantizationMatrixU() : quantization.quantizationMatrixV();
        int destinationX = residualUnit.position().x4() << (2 - chromaSubsamplingX);
        int destinationY = residualUnit.position().y4() << (2 - chromaSubsamplingY);
        int writtenWidth = Math.min(residualUnit.size().widthPixels(), chromaPlane.width() - destinationX);
        int writtenHeight = Math.min(residualUnit.size().heightPixels(), chromaPlane.height() - destinationY);
        if (residualUnit.endOfBlockIndex() == 0 && residualUnit.transformType() == TransformType.DCT_DCT) {
            int dequantizedDcCoefficient = ChromaDequantizer.dequantizeDcCoefficient(
                    residualUnit,
                    qIndex,
                    dcDelta,
                    chromaPlane.bitDepth(),
                    quantization.useQuantizationMatrices(),
                    quantizationMatrix
            );
            inverseTransformer.reconstructAndAddDcOnlyDctBlock(
                    chromaPlane,
                    destinationX,
                    destinationY,
                    residualUnit.size(),
                    chromaPlane.bitDepth(),
                    writtenWidth,
                    writtenHeight,
                    strictStdCompliance,
                    dequantizedDcCoefficient
            );
            return;
        }

        int[] dequantizedCoefficients = inverseTransformer.coefficientBuffer(residualUnit.size());
        ChromaDequantizer.dequantize(
                residualUnit,
                qIndex,
                dcDelta,
                acDelta,
                chromaPlane.bitDepth(),
                quantization.useQuantizationMatrices(),
                quantizationMatrix,
                dequantizedCoefficients
        );
        inverseTransformer.reconstructAndAddResidualBlock(
                chromaPlane,
                destinationX,
                destinationY,
                residualUnit.size(),
                residualUnit.transformType(),
                chromaPlane.bitDepth(),
                writtenWidth,
                writtenHeight,
                strictStdCompliance,
                dequantizedCoefficients
        );
    }

    /// Returns the horizontal chroma subsampling shift for one decoded chroma format.
    ///
    /// @param chromaFormat the active decoded chroma layout
    /// @return the horizontal chroma subsampling shift for one decoded chroma format
    private int chromaSubsamplingX(Av1ChromaFormat chromaFormat) {
        return switch (chromaFormat) {
            case MONOCHROME, YUV444 -> 0;
            case YUV420, YUV422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift for one decoded chroma format.
    ///
    /// @param chromaFormat the active decoded chroma layout
    /// @return the vertical chroma subsampling shift for one decoded chroma format
    private int chromaSubsamplingY(Av1ChromaFormat chromaFormat) {
        return switch (chromaFormat) {
            case MONOCHROME, YUV422, YUV444 -> 0;
            case YUV420 -> 1;
        };
    }

    /// Returns the chroma-plane dimension corresponding to one visible luma span.
    ///
    /// @param lumaDimension the visible luma span in pixels
    /// @param subsamplingShift the chroma subsampling shift for the axis
    /// @return the corresponding chroma-plane dimension
    private int chromaDimension(int lumaDimension, int subsamplingShift) {
        return (lumaDimension + (1 << subsamplingShift) - 1) >> subsamplingShift;
    }

    /// Returns the subsampled luma footprint stored for one CFL block before normative padding.
    ///
    /// AV1 stores each reconstructed luma transform at its full transform size. A partial frame-edge
    /// block may therefore populate either part or all of its chroma transform footprint depending
    /// on the decoded luma transform sizes.
    ///
    /// @param transformLayout the decoded transform layout for the owning block
    /// @param lumaX the luma origin of the shared chroma footprint
    /// @param lumaY the luma origin of the shared chroma footprint
    /// @param codedChromaWidth the complete chroma prediction width
    /// @param codedChromaHeight the complete chroma prediction height
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @return the populated chroma-domain footprint before edge padding
    private CflStoredSize cflStoredSize(
            TransformLayout transformLayout,
            int lumaX,
            int lumaY,
            int codedChromaWidth,
            int codedChromaHeight,
            int subsamplingX,
            int subsamplingY
    ) {
        if (transformLayout.lumaUnitCount() == 0) {
            return new CflStoredSize(codedChromaWidth, codedChromaHeight);
        }

        int storedLumaEndX = lumaX;
        int storedLumaEndY = lumaY;
        for (int unitIndex = 0; unitIndex < transformLayout.lumaUnitCount(); unitIndex++) {
            TransformUnit unit = transformLayout.lumaUnit(unitIndex);
            storedLumaEndX = Math.max(
                    storedLumaEndX,
                    (unit.position().x4() << 2) + unit.size().widthPixels()
            );
            storedLumaEndY = Math.max(
                    storedLumaEndY,
                    (unit.position().y4() << 2) + unit.size().heightPixels()
            );
        }
        int storedWidth = clamp(
                chromaDimension(storedLumaEndX - lumaX, subsamplingX),
                1,
                codedChromaWidth
        );
        int storedHeight = clamp(
                chromaDimension(storedLumaEndY - lumaY, subsamplingY),
                1,
                codedChromaHeight
        );
        return new CflStoredSize(storedWidth, storedHeight);
    }

    /// Returns the luma-grid origin for one chroma block span.
    ///
    /// @param position4 the luma-grid coordinate of the syntax block
    /// @param size4 the luma-grid span of the syntax block
    /// @param subsamplingShift the chroma subsampling shift for the axis
    /// @return the luma-grid origin for the shared chroma footprint
    private int chromaOrigin4(int position4, int size4, int subsamplingShift) {
        if (subsamplingShift == 0 || size4 > subsamplingShift) {
            return position4;
        }
        int mask = (1 << subsamplingShift) - 1;
        return position4 & ~mask;
    }

    /// Returns the chroma-plane X coordinate for one block.
    ///
    /// @param header the decoded block header
    /// @param subsamplingShift the horizontal chroma subsampling shift
    /// @return the chroma-plane X coordinate for the shared chroma footprint
    private int chromaBlockX(TileBlockHeaderReader.BlockHeader header, int subsamplingShift) {
        return chromaOrigin4(header.position().x4(), header.size().width4(), subsamplingShift) << (2 - subsamplingShift);
    }

    /// Returns the chroma-plane Y coordinate for one block.
    ///
    /// @param header the decoded block header
    /// @param subsamplingShift the vertical chroma subsampling shift
    /// @return the chroma-plane Y coordinate for the shared chroma footprint
    private int chromaBlockY(TileBlockHeaderReader.BlockHeader header, int subsamplingShift) {
        return chromaOrigin4(header.position().y4(), header.size().height4(), subsamplingShift) << (2 - subsamplingShift);
    }

    /// Returns the luma-plane X coordinate that backs one chroma block.
    ///
    /// @param header the decoded block header
    /// @param subsamplingShift the horizontal chroma subsampling shift
    /// @return the luma-plane X coordinate for the shared chroma footprint
    private int chromaLumaBlockX(TileBlockHeaderReader.BlockHeader header, int subsamplingShift) {
        return chromaOrigin4(header.position().x4(), header.size().width4(), subsamplingShift) << 2;
    }

    /// Returns the luma-plane Y coordinate that backs one chroma block.
    ///
    /// @param header the decoded block header
    /// @param subsamplingShift the vertical chroma subsampling shift
    /// @return the luma-plane Y coordinate for the shared chroma footprint
    private int chromaLumaBlockY(TileBlockHeaderReader.BlockHeader header, int subsamplingShift) {
        return chromaOrigin4(header.position().y4(), header.size().height4(), subsamplingShift) << 2;
    }

    /// Returns the visible chroma width for one decoded block.
    ///
    /// @param header the decoded block header
    /// @param transformLayout the decoded transform layout for the block
    /// @param subsamplingShift the horizontal chroma subsampling shift
    /// @return the visible chroma width for the shared chroma footprint
    private int visibleChromaBlockWidth(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            int subsamplingShift
    ) {
        int originX4 = chromaOrigin4(header.position().x4(), header.size().width4(), subsamplingShift);
        int visibleLumaEndX = (header.position().x4() << 2) + transformLayout.visibleWidthPixels();
        return chromaDimension(visibleLumaEndX - (originX4 << 2), subsamplingShift);
    }

    /// Returns the visible chroma height for one decoded block.
    ///
    /// @param header the decoded block header
    /// @param transformLayout the decoded transform layout for the block
    /// @param subsamplingShift the vertical chroma subsampling shift
    /// @return the visible chroma height for the shared chroma footprint
    private int visibleChromaBlockHeight(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            int subsamplingShift
    ) {
        int originY4 = chromaOrigin4(header.position().y4(), header.size().height4(), subsamplingShift);
        int visibleLumaEndY = (header.position().y4() << 2) + transformLayout.visibleHeightPixels();
        return chromaDimension(visibleLumaEndY - (originY4 << 2), subsamplingShift);
    }

    /// Returns the coded chroma width for one decoded block.
    ///
    /// @param header the decoded block header
    /// @param subsamplingShift the horizontal chroma subsampling shift
    /// @return the coded chroma width for the shared chroma footprint
    private int codedChromaBlockWidth(TileBlockHeaderReader.BlockHeader header, int subsamplingShift) {
        int originX4 = chromaOrigin4(header.position().x4(), header.size().width4(), subsamplingShift);
        int codedLumaEndX = (header.position().x4() + header.size().width4()) << 2;
        return chromaDimension(codedLumaEndX - (originX4 << 2), subsamplingShift);
    }

    /// Returns the coded chroma height for one decoded block.
    ///
    /// @param header the decoded block header
    /// @param subsamplingShift the vertical chroma subsampling shift
    /// @return the coded chroma height for the shared chroma footprint
    private int codedChromaBlockHeight(TileBlockHeaderReader.BlockHeader header, int subsamplingShift) {
        int originY4 = chromaOrigin4(header.position().y4(), header.size().height4(), subsamplingShift);
        int codedLumaEndY = (header.position().y4() + header.size().height4()) << 2;
        return chromaDimension(codedLumaEndY - (originY4 << 2), subsamplingShift);
    }

}
