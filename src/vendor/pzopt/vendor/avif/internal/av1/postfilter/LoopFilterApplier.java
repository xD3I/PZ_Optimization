// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.postfilter;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import pzopt.vendor.avif.internal.av1.decode.TileBlockHeaderReader;
import pzopt.vendor.avif.internal.av1.decode.TilePartitionTreeReader;
import pzopt.vendor.avif.internal.av1.model.CompoundInterPredictionMode;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.SingleInterPredictionMode;
import pzopt.vendor.avif.internal.av1.model.TransformLayout;
import pzopt.vendor.avif.internal.av1.model.TransformSize;
import pzopt.vendor.avif.internal.av1.model.TransformUnit;
import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;

import java.util.Objects;

/// Applies the loop-filter stage of the AV1 postfilter pipeline.
///
/// Inactive loop filtering preserves samples exactly. Active loop filtering uses decoded block and
/// transform boundaries to run the deblocking sample filters before CDEF sees the frame.

final class LoopFilterApplier {
    /// Prevents instantiation of this stateless filter.
    private LoopFilterApplier() {
    }

    /// The number of luma samples represented by one AV1 mode-info unit.
    private static final int MI_SIZE = 4;

    /// The maximum AV1 loop-filter level.
    private static final int MAX_LOOP_FILTER_LEVEL = 63;

    /// Packed filter-mask bit indicating high edge variance.
    private static final int FILTER_MASK_HIGH_EDGE_VARIANCE = 1;

    /// Packed filter-mask bit indicating that filtering should be applied.
    private static final int FILTER_MASK_FILTER = 1 << 1;

    /// Packed filter-mask bit indicating a flat immediate neighborhood.
    private static final int FILTER_MASK_FLAT = 1 << 2;

    /// Packed filter-mask bit indicating a flat extended neighborhood.
    private static final int FILTER_MASK_FLAT2 = 1 << 3;

    /// Bit mask for one effective loop-filter level in compact block state.
    private static final int FILTER_STATE_LEVEL_MASK = 0x3F;

    /// Bit shift for the second effective loop-filter level in compact block state.
    private static final int FILTER_STATE_SECOND_LEVEL_SHIFT = 6;

    /// Compact block-state bit indicating a skipped inter block.
    private static final int FILTER_STATE_SKIPPED_INTER = 1 << 12;

    /// Boundary-map bit indicating that one plane covers the indexed luma-grid cell.
    private static final int BOUNDARY_STATE_COVERED = 1;

    /// Boundary-map bit indicating the left edge of one decoded block.
    private static final int BOUNDARY_STATE_BLOCK_LEFT = 1 << 1;

    /// Boundary-map bit indicating the top edge of one decoded block.
    private static final int BOUNDARY_STATE_BLOCK_TOP = 1 << 2;

    /// Boundary-map bit indicating the left edge of one transform unit.
    private static final int BOUNDARY_STATE_TRANSFORM_LEFT = 1 << 3;

    /// Boundary-map bit indicating the top edge of one transform unit.
    private static final int BOUNDARY_STATE_TRANSFORM_TOP = 1 << 4;

    /// Transform-size constants indexed by their declaration ordinal.
    private static final TransformSize [] TRANSFORM_SIZES = TransformSize.values();

    /// Applies loop filtering to one reconstructed frame using decoded block syntax.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @param syntaxDecodeResult the decoded frame syntax that carries block and transform edges
    /// @return the post-loop-filter planes
    static DecodedSurface apply(
            DecodedSurface decodedPlanes,
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        return applyPrepared(
                checkedDecodedPlanes,
                prepare(checkedDecodedPlanes, Objects.requireNonNull(frameHeader, "frameHeader"), syntaxDecodeResult)
        );
    }

    /// Extracts the compact frame state required while applying loop filtering.
    ///
    /// The returned state does not retain `syntaxDecodeResult`. Callers may therefore release the
    /// complete frame syntax tree before [#applyPrepared(DecodedSurface, PreparedApplication)]
    /// allocates destination planes.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @param syntaxDecodeResult the decoded frame syntax that carries block and transform edges, or `null`
    /// @return the compact prepared loop-filter state
    static PreparedApplication prepare(
            DecodedSurface decodedPlanes,
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        FrameHeader.LoopFilterInfo loopFilter = checkedFrameHeader.loopFilter();
        if (!hasActiveLevels(loopFilter, checkedDecodedPlanes.hasChroma())) {
            return PreparedApplication.inactive(checkedDecodedPlanes);
        }
        if (syntaxDecodeResult == null) {
            throw new IllegalStateException("Active AV1 loop filtering requires decoded block edge state");
        }

        int activePlaneMask = 0;
        if (loopFilter.levelY(0) != 0 || loopFilter.levelY(1) != 0) {
            activePlaneMask |= 1;
        }
        if (checkedDecodedPlanes.hasChroma() && loopFilter.levelU() != 0) {
            activePlaneMask |= 1 << 1;
        }
        if (checkedDecodedPlanes.hasChroma() && loopFilter.levelV() != 0) {
            activePlaneMask |= 1 << 2;
        }
        return new PreparedApplication(
                checkedDecodedPlanes,
                loopFilter.sharpness(),
                activePlaneMask,
                LoopFilterBlockMap.create(syntaxDecodeResult, checkedDecodedPlanes, checkedFrameHeader)
        );
    }

    /// Applies one previously prepared loop-filter operation.
    ///
    /// @param decodedPlanes the same reconstructed surface used to prepare the operation
    /// @param preparedApplication the compact prepared loop-filter state
    /// @return the post-loop-filter planes
    static DecodedSurface applyPrepared(
            DecodedSurface decodedPlanes,
            PreparedApplication preparedApplication
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        PreparedApplication prepared = Objects.requireNonNull(preparedApplication, "preparedApplication");
        prepared.validateSurface(checkedDecodedPlanes);
        if (!prepared.active()) {
            return checkedDecodedPlanes;
        }

        LoopFilterBlockMap blockMap = Objects.requireNonNull(prepared.blockMap, "blockMap");
        PaddedPlane lumaPlane = checkedDecodedPlanes.lumaPlane();
        if (prepared.isPlaneActive(0)) {
            lumaPlane = applyPlane(
                    lumaPlane,
                    checkedDecodedPlanes.bitDepth(),
                    checkedDecodedPlanes.chromaFormat(),
                    blockMap,
                    prepared.sharpness,
                    0
            );
        }

        PaddedPlane chromaUPlane = checkedDecodedPlanes.chromaUPlane();
        PaddedPlane chromaVPlane = checkedDecodedPlanes.chromaVPlane();
        if (checkedDecodedPlanes.hasChroma()) {
            if (prepared.isPlaneActive(1)) {
                chromaUPlane = applyPlane(
                        Objects.requireNonNull(chromaUPlane, "decodedPlanes.chromaUPlane()"),
                        checkedDecodedPlanes.bitDepth(),
                        checkedDecodedPlanes.chromaFormat(),
                        blockMap,
                        prepared.sharpness,
                        1
                );
            }
            if (prepared.isPlaneActive(2)) {
                chromaVPlane = applyPlane(
                        Objects.requireNonNull(chromaVPlane, "decodedPlanes.chromaVPlane()"),
                        checkedDecodedPlanes.bitDepth(),
                        checkedDecodedPlanes.chromaFormat(),
                        blockMap,
                        prepared.sharpness,
                        2
                );
            }
        }

        return new DecodedSurface(
                checkedDecodedPlanes.bitDepth(),
                checkedDecodedPlanes.chromaFormat(),
                checkedDecodedPlanes.codedWidth(),
                checkedDecodedPlanes.codedHeight(),
                checkedDecodedPlanes.renderWidth(),
                checkedDecodedPlanes.renderHeight(),
                lumaPlane,
                chromaUPlane,
                chromaVPlane
        );
    }

    /// Copies one active plane and applies both loop-filter passes.
    ///
    /// @param plane the immutable source plane
    /// @param bitDepth the decoded sample bit depth
    /// @param chromaFormat the decoded chroma format
    /// @param blockMap the decoded block and transform map
    /// @param sharpness the frame loop-filter sharpness
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @return the filtered immutable plane
    private static PaddedPlane applyPlane(
            PaddedPlane plane,
            int bitDepth,
            Av1ChromaFormat chromaFormat,
            LoopFilterBlockMap blockMap,
            int sharpness,
            int planeIndex
    ) {
        PlaneBuffer destination = PlaneBuffer.create(plane, bitDepth);
        int subX = planeIndex == 0 ? 0 : chromaSubsamplingX(chromaFormat);
        int subY = planeIndex == 0 ? 0 : chromaSubsamplingY(chromaFormat);
        destination.setProcessingExtent(
                alignedPlaneBoundaryDimension(destination.width(), subX),
                alignedPlaneBoundaryDimension(destination.height(), subY)
        );
        int[] edgeSamples = new int[14];
        applyPass(destination, chromaFormat, blockMap, sharpness, planeIndex, 0, edgeSamples);
        applyPass(destination, chromaFormat, blockMap, sharpness, planeIndex, 1, edgeSamples);
        return destination.toDecodedPlane();
    }

    /// Applies one vertical or horizontal loop-filter pass to one plane.
    ///
    /// @param plane the mutable plane buffer
    /// @param chromaFormat the decoded chroma format
    /// @param blockMap the decoded block and transform map
    /// @param sharpness the frame loop-filter sharpness
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    /// @param edgeSamples reusable boundary-relative sample storage
    private static void applyPass(
            PlaneBuffer plane,
            Av1ChromaFormat chromaFormat,
            LoopFilterBlockMap blockMap,
            int sharpness,
            int planeIndex,
            int pass,
            int[] edgeSamples
    ) {
        int subX = planeIndex == 0 ? 0 : chromaSubsamplingX(chromaFormat);
        int subY = planeIndex == 0 ? 0 : chromaSubsamplingY(chromaFormat);
        int rowStep = Math.max(1, 1 << subY);
        int colStep = Math.max(1, 1 << subX);
        if (pass == 0) {
            for (int row4 = 0; row4 < blockMap.height4(); row4 += rowStep) {
                for (int col4 = colStep; col4 < blockMap.width4(); col4 += colStep) {
                    filterEdge(
                            plane,
                            blockMap,
                            sharpness,
                            planeIndex,
                            pass,
                            row4,
                            col4,
                            subX,
                            subY,
                            edgeSamples
                    );
                }
            }
        } else {
            for (int row4 = rowStep; row4 < blockMap.height4(); row4 += rowStep) {
                for (int col4 = 0; col4 < blockMap.width4(); col4 += colStep) {
                    filterEdge(
                            plane,
                            blockMap,
                            sharpness,
                            planeIndex,
                            pass,
                            row4,
                            col4,
                            subX,
                            subY,
                            edgeSamples
                    );
                }
            }
        }
    }

    /// Applies loop filtering along one 4x4-grid edge when the decoded syntax permits it.
    ///
    /// @param plane the mutable plane buffer
    /// @param blockMap the decoded block and transform map
    /// @param sharpness the frame loop-filter sharpness
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    /// @param row4 the luma 4x4 row coordinate of the edge
    /// @param col4 the luma 4x4 column coordinate of the edge
    /// @param subX the plane horizontal subsampling shift
    /// @param subY the plane vertical subsampling shift
    /// @param edgeSamples reusable boundary-relative sample storage
    private static void filterEdge(
            PlaneBuffer plane,
            LoopFilterBlockMap blockMap,
            int sharpness,
            int planeIndex,
            int pass,
            int row4,
            int col4,
            int subX,
            int subY,
            int[] edgeSamples
    ) {
        int prevCol4 = col4 - (pass == 0 ? Math.max(1, 1 << subX) : 0);
        int prevRow4 = row4 - (pass == 1 ? Math.max(1, 1 << subY) : 0);
        int currentIndex = blockMap.indexAt(col4, row4);
        int previousIndex = blockMap.indexAt(prevCol4, prevRow4);
        if (currentIndex < 0 || previousIndex < 0) {
            return;
        }

        if (!blockMap.isCoveredAt(currentIndex, planeIndex)
                || !blockMap.isCoveredAt(previousIndex, planeIndex)) {
            return;
        }

        boolean blockEdge = blockMap.isBlockBoundaryAt(currentIndex, planeIndex, pass);
        boolean transformEdge = blockMap.isTransformBoundaryAt(currentIndex, planeIndex, pass);
        if (!blockEdge && !transformEdge) {
            return;
        }
        if (!blockEdge
                && blockMap.isSkippedInterAt(currentIndex, planeIndex)
                && blockMap.isSkippedInterAt(previousIndex, planeIndex)) {
            return;
        }

        int x = (col4 * MI_SIZE) >> subX;
        int y = (row4 * MI_SIZE) >> subY;
        if (pass == 0) {
            if (x <= 0 || x >= plane.processingWidth() || y < 0 || y >= plane.processingHeight()) {
                return;
            }
        } else {
            if (y <= 0 || y >= plane.processingHeight() || x < 0 || x >= plane.processingWidth()) {
                return;
            }
        }

        int dx = pass == 0 ? 1 : 0;
        int dy = pass == 0 ? 0 : 1;
        int filterSize = filterSize(
                blockMap.transformSizeAt(currentIndex, planeIndex),
                blockMap.transformSizeAt(previousIndex, planeIndex),
                planeIndex,
                pass
        );
        int level = blockMap.filterLevelAt(currentIndex, planeIndex, pass);
        int strength = filterStrength(level, sharpness);
        if (strengthLevel(strength) == 0) {
            strength = filterStrength(
                    blockMap.filterLevelAt(previousIndex, planeIndex, pass),
                    sharpness
            );
        }
        if (strengthLevel(strength) == 0) {
            return;
        }

        int samples = Math.min(
                MI_SIZE,
                pass == 0 ? plane.processingHeight() - y : plane.processingWidth() - x
        );
        for (int i = 0; i < samples; i++) {
            int sampleX = x + (pass == 0 ? 0 : i);
            int sampleY = y + (pass == 0 ? i : 0);
            applySampleFilter(plane, sampleX, sampleY, dx, dy, filterSize, strength, edgeSamples);
        }
    }

    /// Returns whether one decoded block skips transforms while using inter prediction.
    ///
    /// @param header the decoded block header
    /// @return whether the block is a skipped inter block
    private static boolean isSkippedInter(TileBlockHeaderReader.BlockHeader header) {
        return header.skip() && !header.intra() && !header.useIntrabc();
    }

    /// Packs two effective filter levels and the skipped-inter flag into one compact cell value.
    ///
    /// @param firstLevel the first effective filter level
    /// @param secondLevel the second effective filter level
    /// @param skippedInter whether the owning block is a skipped inter block
    /// @return the packed compact cell value
    private static short packFilterState(int firstLevel, int secondLevel, boolean skippedInter) {
        int state = firstLevel | (secondLevel << FILTER_STATE_SECOND_LEVEL_SHIFT);
        if (skippedInter) {
            state |= FILTER_STATE_SKIPPED_INTER;
        }
        return (short) state;
    }

    /// Applies one sample filter at a block edge.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param filterSize the selected maximum filter size in samples
    /// @param strength the derived filter strength parameters
    /// @param edgeSamples reusable boundary-relative sample storage
    private static void applySampleFilter(
            PlaneBuffer plane,
            int x,
            int y,
            int dx,
            int dy,
            int filterSize,
            int strength,
            int[] edgeSamples
    ) {
        int mask = filterMask(plane, x, y, dx, dy, filterSize, strength, edgeSamples);
        if ((mask & FILTER_MASK_FILTER) == 0) {
            return;
        }
        if (filterSize == 4 || (mask & FILTER_MASK_FLAT) == 0) {
            narrowFilter(plane, x, y, dx, dy, (mask & FILTER_MASK_HIGH_EDGE_VARIANCE) != 0, edgeSamples);
        } else if (filterSize == 6) {
            wideFilterSix(plane, x, y, dx, dy, edgeSamples);
        } else if (filterSize == 8 || (mask & FILTER_MASK_FLAT2) == 0) {
            wideFilterEight(plane, x, y, dx, dy, edgeSamples);
        } else {
            wideFilterSixteen(plane, x, y, dx, dy, edgeSamples);
        }
    }

    /// Computes the filter masks for one boundary sample.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param filterSize the selected maximum filter size
    /// @param strength the derived filter strength parameters
    /// @param edgeSamples reusable boundary-relative sample storage
    /// @return the filter masks for one boundary sample
    private static int filterMask(
            PlaneBuffer plane,
            int x,
            int y,
            int dx,
            int dy,
            int filterSize,
            int strength,
            int[] edgeSamples
    ) {
        int firstOffset = filterSize == 4 ? -2 : filterSize == 6 ? -3 : -4;
        int lastOffset = filterSize == 4 ? 1 : filterSize == 6 ? 2 : 3;
        loadEdgeSamples(plane, x, y, dx, dy, firstOffset, lastOffset, edgeSamples);
        int bitDepthShift = plane.bitDepth() - 8;
        int thresholdBd = 1 << bitDepthShift;
        int limitBd = strengthLimit(strength) << bitDepthShift;
        int blimitBd = strengthBoundaryLimit(strength) << bitDepthShift;
        int threshBd = strengthThreshold(strength) << bitDepthShift;
        int p0 = edgeSamples[6];
        int p1 = edgeSamples[5];
        int q0 = edgeSamples[7];
        int q1 = edgeSamples[8];

        boolean highEdgeVariance = Math.abs(p1 - p0) > threshBd || Math.abs(q1 - q0) > threshBd;
        boolean filtered = Math.abs(p1 - p0) <= limitBd
                && Math.abs(q1 - q0) <= limitBd
                && Math.abs(p0 - q0) * 2 + (Math.abs(p1 - q1) >> 1) <= blimitBd;
        boolean flat = false;
        boolean flat2 = false;
        if (filterSize > 4) {
            int p2 = edgeSamples[4];
            int q2 = edgeSamples[9];
            filtered = filtered
                    && Math.abs(p2 - p1) <= limitBd
                    && Math.abs(q2 - q1) <= limitBd;
            flat = Math.abs(p2 - p0) <= thresholdBd
                    && Math.abs(p1 - p0) <= thresholdBd
                    && Math.abs(q1 - q0) <= thresholdBd
                    && Math.abs(q2 - q0) <= thresholdBd;
            if (filterSize > 6) {
                int p3 = edgeSamples[3];
                int q3 = edgeSamples[10];
                filtered = filtered
                        && Math.abs(p3 - p2) <= limitBd
                        && Math.abs(q3 - q2) <= limitBd;
                flat = flat
                        && Math.abs(p3 - p0) <= thresholdBd
                        && Math.abs(q3 - q0) <= thresholdBd;
            }
        }
        if (filterSize >= 16 && flat) {
            loadEdgeSamples(plane, x, y, dx, dy, -7, -5, edgeSamples);
            loadEdgeSamples(plane, x, y, dx, dy, 4, 6, edgeSamples);
            flat2 = Math.abs(edgeSamples[0] - p0) <= thresholdBd
                    && Math.abs(edgeSamples[13] - q0) <= thresholdBd
                    && Math.abs(edgeSamples[1] - p0) <= thresholdBd
                    && Math.abs(edgeSamples[12] - q0) <= thresholdBd
                    && Math.abs(edgeSamples[2] - p0) <= thresholdBd
                    && Math.abs(edgeSamples[11] - q0) <= thresholdBd;
        }
        return (highEdgeVariance ? FILTER_MASK_HIGH_EDGE_VARIANCE : 0)
                | (filtered ? FILTER_MASK_FILTER : 0)
                | (flat ? FILTER_MASK_FLAT : 0)
                | (flat2 ? FILTER_MASK_FLAT2 : 0);
    }

    /// Applies the AV1 narrow loop filter to one boundary sample.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param highEdgeVariance whether high edge variance was detected
    /// @param edgeSamples boundary-relative samples loaded while computing the filter mask
    private static void narrowFilter(
            PlaneBuffer plane,
            int x,
            int y,
            int dx,
            int dy,
            boolean highEdgeVariance,
            int[] edgeSamples
    ) {
        int bitDepth = plane.bitDepth();
        int signedLimit = 1 << (bitDepth - 1);
        int offset = 0x80 << (bitDepth - 8);
        int qs0 = edgeSamples[7] - offset;
        int qs1 = edgeSamples[8] - offset;
        int ps0 = edgeSamples[6] - offset;
        int ps1 = edgeSamples[5] - offset;
        int filter = highEdgeVariance ? filter4Clamp(ps1 - qs1, signedLimit) : 0;
        filter = filter4Clamp(filter + 3 * (qs0 - ps0), signedLimit);
        int filter1 = filter4Clamp(filter + 4, signedLimit) >> 3;
        int filter2 = filter4Clamp(filter + 3, signedLimit) >> 3;
        setSampleRelative(plane, x, y, dx, dy, 0, filter4Clamp(qs0 - filter1, signedLimit) + offset);
        setSampleRelative(plane, x, y, dx, dy, -1, filter4Clamp(ps0 + filter2, signedLimit) + offset);
        if (!highEdgeVariance) {
            int secondaryFilter = round2(filter1, 1);
            setSampleRelative(plane, x, y, dx, dy, 1, filter4Clamp(qs1 - secondaryFilter, signedLimit) + offset);
            setSampleRelative(plane, x, y, dx, dy, -2, filter4Clamp(ps1 + secondaryFilter, signedLimit) + offset);
        }
    }

    /// Applies the AV1 6-tap wide loop filter to one boundary sample.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param edgeSamples boundary-relative samples loaded while computing the filter mask
    private static void wideFilterSix(PlaneBuffer plane, int x, int y, int dx, int dy, int[] edgeSamples) {
        int p2 = edgeSamples[4];
        int p1 = edgeSamples[5];
        int p0 = edgeSamples[6];
        int q0 = edgeSamples[7];
        int q1 = edgeSamples[8];
        int q2 = edgeSamples[9];
        setSampleRelative(plane, x, y, dx, dy, -2, (p2 + 2 * p2 + 2 * p1 + 2 * p0 + q0 + 4) >> 3);
        setSampleRelative(plane, x, y, dx, dy, -1, (p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) >> 3);
        setSampleRelative(plane, x, y, dx, dy, 0, (p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) >> 3);
        setSampleRelative(plane, x, y, dx, dy, 1, (p0 + 2 * q0 + 2 * q1 + 2 * q2 + q2 + 4) >> 3);
    }

    /// Applies the AV1 8-tap wide loop filter to one boundary sample.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param edgeSamples boundary-relative samples loaded while computing the filter mask
    private static void wideFilterEight(PlaneBuffer plane, int x, int y, int dx, int dy, int[] edgeSamples) {
        int p3 = edgeSamples[3];
        int p2 = edgeSamples[4];
        int p1 = edgeSamples[5];
        int p0 = edgeSamples[6];
        int q0 = edgeSamples[7];
        int q1 = edgeSamples[8];
        int q2 = edgeSamples[9];
        int q3 = edgeSamples[10];
        setSampleRelative(plane, x, y, dx, dy, -3, (p3 + p3 + p3 + 2 * p2 + p1 + p0 + q0 + 4) >> 3);
        setSampleRelative(plane, x, y, dx, dy, -2, (p3 + p3 + p2 + 2 * p1 + p0 + q0 + q1 + 4) >> 3);
        setSampleRelative(plane, x, y, dx, dy, -1, (p3 + p2 + p1 + 2 * p0 + q0 + q1 + q2 + 4) >> 3);
        setSampleRelative(plane, x, y, dx, dy, 0, (p2 + p1 + p0 + 2 * q0 + q1 + q2 + q3 + 4) >> 3);
        setSampleRelative(plane, x, y, dx, dy, 1, (p1 + p0 + q0 + 2 * q1 + q2 + q3 + q3 + 4) >> 3);
        setSampleRelative(plane, x, y, dx, dy, 2, (p0 + q0 + q1 + 2 * q2 + q3 + q3 + q3 + 4) >> 3);
    }

    /// Applies the AV1 16-tap wide loop filter to one boundary sample.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param edgeSamples boundary-relative samples loaded while computing the filter mask
    private static void wideFilterSixteen(PlaneBuffer plane, int x, int y, int dx, int dy, int[] edgeSamples) {
        int p6 = edgeSamples[0];
        int p5 = edgeSamples[1];
        int p4 = edgeSamples[2];
        int p3 = edgeSamples[3];
        int p2 = edgeSamples[4];
        int p1 = edgeSamples[5];
        int p0 = edgeSamples[6];
        int q0 = edgeSamples[7];
        int q1 = edgeSamples[8];
        int q2 = edgeSamples[9];
        int q3 = edgeSamples[10];
        int q4 = edgeSamples[11];
        int q5 = edgeSamples[12];
        int q6 = edgeSamples[13];
        setSampleRelative(plane, x, y, dx, dy, -6,
                (p6 + p6 + p6 + p6 + p6 + p6 * 2 + p5 * 2 + p4 * 2 + p3 + p2 + p1 + p0 + q0 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, -5,
                (p6 + p6 + p6 + p6 + p6 + p5 * 2 + p4 * 2 + p3 * 2 + p2 + p1 + p0 + q0 + q1 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, -4,
                (p6 + p6 + p6 + p6 + p5 + p4 * 2 + p3 * 2 + p2 * 2 + p1 + p0 + q0 + q1 + q2 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, -3,
                (p6 + p6 + p6 + p5 + p4 + p3 * 2 + p2 * 2 + p1 * 2 + p0 + q0 + q1 + q2 + q3 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, -2,
                (p6 + p6 + p5 + p4 + p3 + p2 * 2 + p1 * 2 + p0 * 2 + q0 + q1 + q2 + q3 + q4 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, -1,
                (p6 + p5 + p4 + p3 + p2 + p1 * 2 + p0 * 2 + q0 * 2 + q1 + q2 + q3 + q4 + q5 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, 0,
                (p5 + p4 + p3 + p2 + p1 + p0 * 2 + q0 * 2 + q1 * 2 + q2 + q3 + q4 + q5 + q6 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, 1,
                (p4 + p3 + p2 + p1 + p0 + q0 * 2 + q1 * 2 + q2 * 2 + q3 + q4 + q5 + q6 + q6 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, 2,
                (p3 + p2 + p1 + p0 + q0 + q1 * 2 + q2 * 2 + q3 * 2 + q4 + q5 + q6 + q6 + q6 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, 3,
                (p2 + p1 + p0 + q0 + q1 + q2 * 2 + q3 * 2 + q4 * 2 + q5 + q6 + q6 + q6 + q6 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, 4,
                (p1 + p0 + q0 + q1 + q2 + q3 * 2 + q4 * 2 + q5 * 2 + q6 + q6 + q6 + q6 + q6 + 8) >> 4);
        setSampleRelative(plane, x, y, dx, dy, 5,
                (p0 + q0 + q1 + q2 + q3 + q4 * 2 + q5 * 2 + q6 * 2 + q6 + q6 + q6 + q6 + q6 + 8) >> 4);
    }

    /// Returns the loop-filter level for one block, plane, and pass.
    ///
    /// @param frameHeader the normalized frame header that owns the block
    /// @param header the decoded block header
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    /// @return the loop-filter level for one block, plane, and pass
    private static int filterLevel(
            FrameHeader frameHeader,
            TileBlockHeaderReader.BlockHeader header,
            int planeIndex,
            int pass
    ) {
        int componentIndex = planeIndex == 0 ? pass : planeIndex + 1;
        FrameHeader.LoopFilterInfo loopFilter = frameHeader.loopFilter();
        int baseLevel = planeFilterLevel(loopFilter, planeIndex, pass);
        int deltaLf = frameHeader.delta().deltaLfMulti()
                ? header.deltaLfValue(componentIndex)
                : header.deltaLfValue(0);
        int level = clamp(baseLevel + deltaLf, 0, MAX_LOOP_FILTER_LEVEL);
        if (frameHeader.segmentation().enabled()) {
            FrameHeader.SegmentData segment = frameHeader.segmentation().segment(header.segmentId());
            level = clamp(level + segmentLoopFilterDelta(segment, componentIndex), 0, MAX_LOOP_FILTER_LEVEL);
        }
        if (loopFilter.modeRefDeltaEnabled()) {
            int shift = level >> 5;
            int referenceIndex = header.intra() ? 0 : header.referenceFrame0() + 1;
            if (referenceIndex >= 0 && referenceIndex < loopFilter.referenceDeltaCount()) {
                level += loopFilter.referenceDelta(referenceIndex) << shift;
            }
            if (!header.intra() && !header.useIntrabc()) {
                int modeIndex = loopFilterModeIndex(header);
                if (modeIndex >= 0 && modeIndex < loopFilter.modeDeltaCount()) {
                    level += loopFilter.modeDelta(modeIndex) << shift;
                }
            }
            level = clamp(level, 0, MAX_LOOP_FILTER_LEVEL);
        }
        return level;
    }

    /// Returns the component-specific frame loop-filter level.
    ///
    /// @param loopFilter the normalized loop-filter state
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    /// @return the component-specific frame loop-filter level
    private static int planeFilterLevel(FrameHeader.LoopFilterInfo loopFilter, int planeIndex, int pass) {
        if (planeIndex == 0) {
            return loopFilter.levelY(pass);
        }
        return planeIndex == 1 ? loopFilter.levelU() : loopFilter.levelV();
    }

    /// Returns the segment-level loop-filter delta for one component index.
    ///
    /// @param segment the segment feature data
    /// @param componentIndex the loop-filter component index
    /// @return the segment-level loop-filter delta for one component index
    private static int segmentLoopFilterDelta(FrameHeader.SegmentData segment, int componentIndex) {
        return switch (componentIndex) {
            case 0 -> segment.deltaLfYVertical();
            case 1 -> segment.deltaLfYHorizontal();
            case 2 -> segment.deltaLfU();
            case 3 -> segment.deltaLfV();
            default -> 0;
        };
    }

    /// Returns the two-entry loop-filter mode-delta index for one block.
    ///
    /// @param header the decoded block header
    /// @return the two-entry loop-filter mode-delta index for one block
    private static int loopFilterModeIndex(TileBlockHeaderReader.BlockHeader header) {
        if (header.intra() || header.useIntrabc()) {
            return 0;
        }
        SingleInterPredictionMode singleMode = header.singleInterMode();
        if (singleMode != null) {
            return singleMode == SingleInterPredictionMode.GLOBALMV ? 0 : 1;
        }
        CompoundInterPredictionMode compoundMode = header.compoundInterMode();
        if (compoundMode == null) {
            return 0;
        }
        return compoundMode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV ? 0 : 1;
    }

    /// Derives AV1 loop-filter strength parameters from one filter level.
    ///
    /// @param level the effective loop-filter level
    /// @param sharpness the frame loop-filter sharpness
    /// @return AV1 loop-filter strength parameters
    private static int filterStrength(int level, int sharpness) {
        if (level <= 0) {
            return 0;
        }
        int shift = sharpness > 4 ? 2 : (sharpness > 0 ? 1 : 0);
        int limit = level >> shift;
        if (sharpness > 0) {
            limit = clamp(limit, 1, 9 - sharpness);
        } else {
            limit = Math.max(1, limit);
        }
        return level | (limit << 6) | ((2 * (level + 2) + limit) << 12) | ((level >> 4) << 20);
    }

    /// Returns the effective level from packed loop-filter strength parameters.
    ///
    /// @param strength the packed strength parameters
    /// @return the effective loop-filter level
    private static int strengthLevel(int strength) {
        return strength & 0x3F;
    }

    /// Returns the mask limit from packed loop-filter strength parameters.
    ///
    /// @param strength the packed strength parameters
    /// @return the filter mask limit
    private static int strengthLimit(int strength) {
        return (strength >>> 6) & 0x3F;
    }

    /// Returns the boundary limit from packed loop-filter strength parameters.
    ///
    /// @param strength the packed strength parameters
    /// @return the boundary limit
    private static int strengthBoundaryLimit(int strength) {
        return (strength >>> 12) & 0xFF;
    }

    /// Returns the high-edge-variance threshold from packed loop-filter strength parameters.
    ///
    /// @param strength the packed strength parameters
    /// @return the high-edge-variance threshold
    private static int strengthThreshold(int strength) {
        return (strength >>> 20) & 0x03;
    }

    /// Returns the maximum filter size allowed by the transform sizes across one edge.
    ///
    /// @param txSize the current transform size
    /// @param previousTxSize the transform size on the other side of the edge
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    /// @return the maximum filter size allowed by the transform sizes across one edge
    private static int filterSize(TransformSize txSize, TransformSize previousTxSize, int planeIndex, int pass) {
        int baseSize = pass == 0
                ? Math.min(txSize.widthPixels(), previousTxSize.widthPixels())
                : Math.min(txSize.heightPixels(), previousTxSize.heightPixels());
        if (planeIndex != 0) {
            return baseSize >= 8 ? 6 : 4;
        }
        if (baseSize >= 16) {
            return 16;
        }
        if (baseSize >= 8) {
            return 8;
        }
        return 4;
    }

    /// Returns whether any loop-filter level can affect the decoded frame.
    ///
    /// @param loopFilter the normalized loop-filter state
    /// @param hasChroma whether the decoded frame has chroma planes
    /// @return whether any loop-filter level can affect the decoded frame
    private static boolean hasActiveLevels(FrameHeader.LoopFilterInfo loopFilter, boolean hasChroma) {
        int[] levelY = loopFilter.levelY();
        return (levelY.length > 0 && levelY[0] != 0)
                || (levelY.length > 1 && levelY[1] != 0)
                || (hasChroma && (loopFilter.levelU() != 0 || loopFilter.levelV() != 0));
    }

    /// Loads a contiguous range of boundary-relative samples.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param firstOffset the first signed boundary-relative sample offset
    /// @param lastOffset the last signed boundary-relative sample offset
    /// @param edgeSamples destination storage indexed by `offset + 7`
    private static void loadEdgeSamples(
            PlaneBuffer plane,
            int x,
            int y,
            int dx,
            int dy,
            int firstOffset,
            int lastOffset,
            int[] edgeSamples
    ) {
        plane.loadRelativeSamples(x, y, dx, dy, firstOffset, lastOffset, edgeSamples);
    }

    /// Stores one sample at a boundary-relative offset.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param offset the signed boundary-relative sample offset
    /// @param value the replacement sample value
    private static void setSampleRelative(PlaneBuffer plane, int x, int y, int dx, int dy, int offset, int value) {
        int sampleX = x + dx * offset;
        int sampleY = y + dy * offset;
        if (plane.contains(sampleX, sampleY)) {
            plane.setSample(sampleX, sampleY, value);
        }
    }

    /// Returns the chroma horizontal subsampling shift for one chroma format.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @return the chroma horizontal subsampling shift for one chroma format
    private static int chromaSubsamplingX(Av1ChromaFormat chromaFormat) {
        return switch (chromaFormat) {
            case MONOCHROME, YUV444 -> 0;
            case YUV420, YUV422 -> 1;
        };
    }

    /// Returns the chroma vertical subsampling shift for one chroma format.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @return the chroma vertical subsampling shift for one chroma format
    private static int chromaSubsamplingY(Av1ChromaFormat chromaFormat) {
        return switch (chromaFormat) {
            case MONOCHROME, YUV422, YUV444 -> 0;
            case YUV420 -> 1;
        };
    }

    /// Returns the padded plane boundary available to loop-filter taps along one axis.
    ///
    /// @param dimension the visible plane dimension in samples
    /// @param subsampling the plane subsampling shift for this axis
    /// @return the dimension rounded up to the AV1 eight-luma-sample boundary
    private static int alignedPlaneBoundaryDimension(int dimension, int subsampling) {
        int alignment = 8 >> subsampling;
        return (dimension + alignment - 1) & -alignment;
    }

    /// Clips one integer into inclusive bounds.
    ///
    /// @param value the input value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clipped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /// Rounds one integer right shift by adding half the divisor.
    ///
    /// @param value the input value
    /// @param bits the number of low bits to discard
    /// @return the rounded shifted value
    private static int round2(int value, int bits) {
        return (value + (1 << (bits - 1))) >> bits;
    }

    /// Clips one signed narrow-filter intermediate to the decoded bit-depth range.
    ///
    /// @param value the input value
    /// @param signedLimit the positive exclusive bound for the signed intermediate
    /// @return the clipped narrow-filter intermediate
    private static int filter4Clamp(int value, int signedLimit) {
        return clamp(value, -signedLimit, signedLimit - 1);
    }

    /// Mutable plane storage used by loop filtering.
    
    private static final class PlaneBuffer {
        /// The plane width in samples.
        private final int width;

        /// The plane height in samples.
        private final int height;

        /// The sample stride of one stored row.
        private final int stride;

        /// The number of stored rows, including bottom padding.
        private final int storageHeight;

        /// The horizontal sample extent filtered for the current plane.
        private int processingWidth;

        /// The vertical sample extent filtered for the current plane.
        private int processingHeight;

        /// The decoded sample bit depth.
        private final int bitDepth;

        /// The maximum legal sample value for this bit depth.
        private final int maxSampleValue;

        /// The mutable sample storage in row-major order.
        private final short[] samples;

        /// Creates one mutable plane buffer.
        ///
        /// @param width the plane width in samples
        /// @param height the plane height in samples
        /// @param stride the sample stride of one stored row
        /// @param storageHeight the number of stored rows
        /// @param bitDepth the decoded sample bit depth
        /// @param samples the mutable sample storage in row-major order
        private PlaneBuffer(
                int width,
                int height,
                int stride,
                int storageHeight,
                int bitDepth,
                short[] samples
        ) {
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.storageHeight = storageHeight;
            this.processingWidth = width;
            this.processingHeight = height;
            this.bitDepth = bitDepth;
            this.maxSampleValue = (1 << bitDepth) - 1;
            this.samples = Objects.requireNonNull(samples, "samples");
        }

        /// Creates a mutable copy of one decoded plane.
        ///
        /// @param plane the immutable decoded plane
        /// @param bitDepth the decoded sample bit depth
        /// @return a mutable copy of one decoded plane
        private static PlaneBuffer create(PaddedPlane plane, int bitDepth) {
            PaddedPlane checkedPlane = Objects.requireNonNull(plane, "plane");
            return new PlaneBuffer(
                    checkedPlane.width(),
                    checkedPlane.height(),
                    checkedPlane.stride(),
                    checkedPlane.storageHeight(),
                    bitDepth,
                    checkedPlane.samples()
            );
        }

        /// Returns the plane width in samples.
        ///
        /// @return the plane width in samples
        private int width() {
            return width;
        }

        /// Returns the plane height in samples.
        ///
        /// @return the plane height in samples
        private int height() {
            return height;
        }

        /// Sets the padded sample extent processed by loop filtering.
        ///
        /// @param requestedWidth the requested processing width
        /// @param requestedHeight the requested processing height
        private void setProcessingExtent(int requestedWidth, int requestedHeight) {
            processingWidth = Math.min(stride, requestedWidth);
            processingHeight = Math.min(storageHeight, requestedHeight);
        }

        /// Returns the horizontal sample extent processed by loop filtering.
        ///
        /// @return the processing width
        private int processingWidth() {
            return processingWidth;
        }

        /// Returns the vertical sample extent processed by loop filtering.
        ///
        /// @return the processing height
        private int processingHeight() {
            return processingHeight;
        }

        /// Returns the decoded sample bit depth.
        ///
        /// @return the decoded sample bit depth
        private int bitDepth() {
            return bitDepth;
        }

        /// Returns whether one sample coordinate is inside this plane.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return whether one sample coordinate is inside this plane
        private boolean contains(int x, int y) {
            return x >= 0 && y >= 0 && x < processingWidth && y < processingHeight;
        }

        /// Returns one mutable sample.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return one mutable sample
        private int sample(int x, int y) {
            return samples[y * stride + x] & 0xFFFF;
        }

        /// Returns one sample after extending the nearest frame-edge sample beyond the plane.
        ///
        /// @param x the sample X coordinate, which may be outside the plane
        /// @param y the sample Y coordinate, which may be outside the plane
        /// @return the nearest in-plane sample
        private int sampleClamped(int x, int y) {
            return sample(clamp(x, 0, processingWidth - 1), clamp(y, 0, processingHeight - 1));
        }

        /// Loads one contiguous boundary-relative sample range into edge-sample storage.
        ///
        /// @param x the boundary origin x coordinate
        /// @param y the boundary origin y coordinate
        /// @param dx the horizontal offset across the boundary
        /// @param dy the vertical offset across the boundary
        /// @param firstOffset the first signed boundary-relative offset
        /// @param lastOffset the last signed boundary-relative offset
        /// @param destination destination storage indexed by `offset + 7`
        private void loadRelativeSamples(
                int x,
                int y,
                int dx,
                int dy,
                int firstOffset,
                int lastOffset,
                int[] destination
        ) {
            int firstX = x + dx * firstOffset;
            int firstY = y + dy * firstOffset;
            int lastX = x + dx * lastOffset;
            int lastY = y + dy * lastOffset;
            if (contains(firstX, firstY) && contains(lastX, lastY)) {
                int sampleIndex = firstY * stride + firstX;
                int sampleStep = dy * stride + dx;
                for (int offset = firstOffset; offset <= lastOffset; offset++) {
                    destination[offset + 7] = samples[sampleIndex] & 0xFFFF;
                    sampleIndex += sampleStep;
                }
                return;
            }

            for (int offset = firstOffset; offset <= lastOffset; offset++) {
                destination[offset + 7] = sampleClamped(x + dx * offset, y + dy * offset);
            }
        }

        /// Stores one sample after clipping it to this plane bit depth.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @param value the replacement sample value
        private void setSample(int x, int y, int value) {
            samples[y * stride + x] = (short) clamp(value, 0, maxSampleValue);
        }

        /// Returns one immutable decoded-plane snapshot from the current samples.
        ///
        /// @return one immutable decoded-plane snapshot from the current samples
        private PaddedPlane toDecodedPlane() {
            return PaddedPlane.fromOwnedSamples(width, height, stride, samples);
        }
    }

    /// Compact loop-filter state that remains valid after the complete frame syntax tree is released.
    
    static final class PreparedApplication {
        /// The expected decoded bit depth.
        private final int bitDepth;

        /// The expected chroma format.
        private final Av1ChromaFormat chromaFormat;

        /// The expected coded luma width.
        private final int codedWidth;

        /// The expected coded luma height.
        private final int codedHeight;

        /// The frame loop-filter sharpness.
        private final int sharpness;

        /// The bit mask of planes with active frame-level filtering.
        private final int activePlaneMask;

        /// The compact decoded block map, or `null` when loop filtering is inactive.
        private final LoopFilterBlockMap blockMap;

        /// Creates one prepared loop-filter operation.
        ///
        /// @param decodedPlanes the surface for which the operation was prepared
        /// @param sharpness the frame loop-filter sharpness
        /// @param activePlaneMask the bit mask of planes with active frame-level filtering
        /// @param blockMap the exclusively owned compact decoded block map, or `null`
        private PreparedApplication(
                DecodedSurface decodedPlanes,
                int sharpness,
                int activePlaneMask,
                LoopFilterBlockMap blockMap
        ) {
            DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
            this.bitDepth = checkedDecodedPlanes.bitDepth();
            this.chromaFormat = checkedDecodedPlanes.chromaFormat();
            this.codedWidth = checkedDecodedPlanes.codedWidth();
            this.codedHeight = checkedDecodedPlanes.codedHeight();
            this.sharpness = sharpness;
            this.activePlaneMask = activePlaneMask;
            this.blockMap = blockMap;
        }

        /// Creates an inactive prepared operation for one decoded surface.
        ///
        /// @param decodedPlanes the surface for which loop filtering is inactive
        /// @return the inactive prepared operation
        private static PreparedApplication inactive(DecodedSurface decodedPlanes) {
            return new PreparedApplication(decodedPlanes, 0, 0, null);
        }

        /// Returns whether this operation applies any loop filtering.
        ///
        /// @return whether loop filtering is active
        private boolean active() {
            return blockMap != null;
        }

        /// Returns whether one image plane has a nonzero frame-level filter.
        ///
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @return whether the selected plane requires loop filtering
        private boolean isPlaneActive(int planeIndex) {
            return (activePlaneMask & (1 << Objects.checkIndex(planeIndex, 3))) != 0;
        }

        /// Verifies that an operation is applied to the surface for which it was prepared.
        ///
        /// @param decodedPlanes the candidate reconstructed surface
        private void validateSurface(DecodedSurface decodedPlanes) {
            DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
            if (checkedDecodedPlanes.bitDepth() != bitDepth
                    || checkedDecodedPlanes.chromaFormat() != chromaFormat
                    || checkedDecodedPlanes.codedWidth() != codedWidth
                    || checkedDecodedPlanes.codedHeight() != codedHeight) {
                throw new IllegalArgumentException("Prepared loop-filter state does not match decoded surface");
            }
        }
    }

    /// Compact block and transform boundary state indexed in luma 4x4 units.
    ///
    /// The map stores only primitive values so it does not retain decoded partition, block, or
    /// transform objects after preparation completes.
    
    private static final class LoopFilterBlockMap {
        /// The frame width rounded up to 4x4 units.
        private final int width4;

        /// The frame height rounded up to 4x4 units.
        private final int height4;

        /// The packed luma coverage and block/transform boundaries for each 4x4 cell.
        private final byte [] lumaBoundaryStates;

        /// The luma transform-size ordinal covering each 4x4 cell.
        private final byte [] lumaTransformSizeOrdinals;

        /// The packed luma filter levels and skipped-inter flag covering each 4x4 cell.
        private final short [] lumaFilterStates;

        /// The packed chroma coverage and block/transform boundaries for each luma-grid 4x4 cell.
        private final byte [] chromaBoundaryStates;

        /// The chroma transform-size ordinal covering each luma-grid 4x4 cell.
        private final byte [] chromaTransformSizeOrdinals;

        /// The packed U/V filter levels and skipped-inter flag covering each luma-grid 4x4 cell.
        private final short [] chromaFilterStates;

        /// The chroma horizontal subsampling shift.
        private final int chromaSubsamplingX;

        /// The chroma vertical subsampling shift.
        private final int chromaSubsamplingY;

        /// Creates one compact block and transform lookup map.
        ///
        /// @param width4 the frame width rounded up to 4x4 units
        /// @param height4 the frame height rounded up to 4x4 units
        /// @param chromaSubsamplingX the chroma horizontal subsampling shift
        /// @param chromaSubsamplingY the chroma vertical subsampling shift
        private LoopFilterBlockMap(int width4, int height4, int chromaSubsamplingX, int chromaSubsamplingY) {
            this.width4 = width4;
            this.height4 = height4;
            int cellCount = width4 * height4;
            this.lumaBoundaryStates = new byte[cellCount];
            this.lumaTransformSizeOrdinals = new byte[cellCount];
            this.lumaFilterStates = new short[cellCount];
            this.chromaBoundaryStates = new byte[cellCount];
            this.chromaTransformSizeOrdinals = new byte[cellCount];
            this.chromaFilterStates = new short[cellCount];
            this.chromaSubsamplingX = chromaSubsamplingX;
            this.chromaSubsamplingY = chromaSubsamplingY;
        }

        /// Creates one compact block and transform lookup map.
        ///
        /// @param syntaxDecodeResult the decoded frame syntax
        /// @param decodedPlanes the reconstructed planes to post-process
        /// @param frameHeader the normalized frame header used to derive effective filter levels
        /// @return one compact block and transform lookup map
        private static LoopFilterBlockMap create(
                FrameSyntaxDecodeResult syntaxDecodeResult,
                DecodedSurface decodedPlanes,
                FrameHeader frameHeader
        ) {
            LoopFilterBlockMap map = new LoopFilterBlockMap(
                    (decodedPlanes.codedWidth() + MI_SIZE - 1) / MI_SIZE,
                    (decodedPlanes.codedHeight() + MI_SIZE - 1) / MI_SIZE,
                    chromaSubsamplingX(decodedPlanes.chromaFormat()),
                    chromaSubsamplingY(decodedPlanes.chromaFormat())
            );
            TilePartitionTreeReader.Node[][] frameLocalTileRoots = syntaxDecodeResult.tileRoots();
            for (TilePartitionTreeReader.Node[] tileRoots : frameLocalTileRoots) {
                for (TilePartitionTreeReader.Node root : tileRoots) {
                    map.addNode(root, frameHeader);
                }
            }
            return map;
        }

        /// Returns the frame width rounded up to 4x4 units.
        ///
        /// @return the frame width rounded up to 4x4 units
        private int width4() {
            return width4;
        }

        /// Returns the frame height rounded up to 4x4 units.
        ///
        /// @return the frame height rounded up to 4x4 units
        private int height4() {
            return height4;
        }

        /// Returns the flat cell index for one luma-grid coordinate.
        ///
        /// @param x4 the luma 4x4 X coordinate
        /// @param y4 the luma 4x4 Y coordinate
        /// @return the flat cell index, or `-1` when outside the map
        private int indexAt(int x4, int y4) {
            if (x4 < 0 || y4 < 0 || x4 >= width4 || y4 >= height4) {
                return -1;
            }
            return y4 * width4 + x4;
        }

        /// Returns whether one plane covers the indexed luma-grid cell.
        ///
        /// @param index the flat cell index
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @return whether the selected plane covers the cell
        private boolean isCoveredAt(int index, int planeIndex) {
            return (boundaryStateAt(index, planeIndex) & BOUNDARY_STATE_COVERED) != 0;
        }

        /// Returns whether the indexed cell begins a plane-specific block along one filter pass.
        ///
        /// @param index the flat cell index
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
        /// @return whether the cell begins a decoded block along the selected pass
        private boolean isBlockBoundaryAt(int index, int planeIndex, int pass) {
            int mask = pass == 0 ? BOUNDARY_STATE_BLOCK_LEFT : BOUNDARY_STATE_BLOCK_TOP;
            return (boundaryStateAt(index, planeIndex) & mask) != 0;
        }

        /// Returns whether the indexed cell begins a plane-specific transform along one filter pass.
        ///
        /// @param index the flat cell index
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
        /// @return whether the cell begins a transform unit along the selected pass
        private boolean isTransformBoundaryAt(int index, int planeIndex, int pass) {
            int mask = pass == 0 ? BOUNDARY_STATE_TRANSFORM_LEFT : BOUNDARY_STATE_TRANSFORM_TOP;
            return (boundaryStateAt(index, planeIndex) & mask) != 0;
        }

        /// Returns the packed plane-specific boundary state at one cell.
        ///
        /// @param index the flat cell index
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @return the packed coverage and boundary state
        private int boundaryStateAt(int index, int planeIndex) {
            return Byte.toUnsignedInt((planeIndex == 0 ? lumaBoundaryStates : chromaBoundaryStates)[index]);
        }

        /// Returns the plane-specific transform size at one covered cell.
        ///
        /// @param index the flat cell index
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @return the transform size covering the cell
        private TransformSize transformSizeAt(int index, int planeIndex) {
            int ordinal = Byte.toUnsignedInt(
                    (planeIndex == 0 ? lumaTransformSizeOrdinals : chromaTransformSizeOrdinals)[index]
            );
            return TRANSFORM_SIZES[ordinal];
        }

        /// Returns one effective plane/pass filter level at one covered cell.
        ///
        /// @param index the flat cell index
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
        /// @return the effective filter level
        private int filterLevelAt(int index, int planeIndex, int pass) {
            int state = Short.toUnsignedInt((planeIndex == 0 ? lumaFilterStates : chromaFilterStates)[index]);
            int levelIndex = planeIndex == 0 ? pass : planeIndex - 1;
            return (state >> (levelIndex * FILTER_STATE_SECOND_LEVEL_SHIFT)) & FILTER_STATE_LEVEL_MASK;
        }

        /// Returns whether the plane-specific block at one cell is a skipped inter block.
        ///
        /// @param index the flat cell index
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @return whether the block is a skipped inter block
        private boolean isSkippedInterAt(int index, int planeIndex) {
            int state = Short.toUnsignedInt((planeIndex == 0 ? lumaFilterStates : chromaFilterStates)[index]);
            return (state & FILTER_STATE_SKIPPED_INTER) != 0;
        }

        /// Adds one partition node and all descendant leaves to this map.
        ///
        /// @param node the decoded partition node
        /// @param frameHeader the normalized frame header used to derive effective filter levels
        private void addNode(TilePartitionTreeReader.Node node, FrameHeader frameHeader) {
            if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
                addLeaf(leafNode, frameHeader);
                return;
            }
            TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
            for (int childIndex = 0; childIndex < partitionNode.childCount(); childIndex++) {
                addNode(partitionNode.child(childIndex), frameHeader);
            }
        }

        /// Adds one leaf to every 4x4 cell that it covers.
        ///
        /// @param leafNode the decoded partition leaf
        /// @param frameHeader the normalized frame header used to derive effective filter levels
        private void addLeaf(TilePartitionTreeReader.LeafNode leafNode, FrameHeader frameHeader) {
            TileBlockHeaderReader.BlockHeader header = leafNode.header();
            int startX4 = Math.max(0, header.position().x4());
            int startY4 = Math.max(0, header.position().y4());
            TransformLayout transformLayout = leafNode.transformLayout();
            int endX4 = Math.min(width4, startX4 + transformLayout.visibleWidth4());
            int endY4 = Math.min(height4, startY4 + transformLayout.visibleHeight4());
            boolean skippedInter = isSkippedInter(header);
            short lumaFilterState = packFilterState(
                    filterLevel(frameHeader, header, 0, 0),
                    filterLevel(frameHeader, header, 0, 1),
                    skippedInter
            );
            for (int y4 = startY4; y4 < endY4; y4++) {
                for (int x4 = startX4; x4 < endX4; x4++) {
                    int index = y4 * width4 + x4;
                    int boundaryState = BOUNDARY_STATE_COVERED;
                    if (x4 == startX4) {
                        boundaryState |= BOUNDARY_STATE_BLOCK_LEFT;
                    }
                    if (y4 == startY4) {
                        boundaryState |= BOUNDARY_STATE_BLOCK_TOP;
                    }
                    lumaBoundaryStates[index] |= (byte) boundaryState;
                    lumaFilterStates[index] = lumaFilterState;
                }
            }

            if (transformLayout.lumaUnitCount() == 0) {
                fillDefaultLumaTransform(
                        startX4,
                        startY4,
                        endX4,
                        endY4,
                        transformLayout.maxLumaTransformSize()
                );
            } else {
                TransformUnit firstUnit = transformLayout.lumaUnit(0);
                fillDefaultLumaTransform(
                        startX4,
                        startY4,
                        endX4,
                        endY4,
                        firstUnit.size()
                );
                for (int unitIndex = 0; unitIndex < transformLayout.lumaUnitCount(); unitIndex++) {
                    TransformUnit transformUnit = transformLayout.lumaUnit(unitIndex);
                    fillLumaTransform(
                            startX4,
                            startY4,
                            endX4,
                            endY4,
                            transformUnit
                    );
                }
            }
            if (header.hasChroma() && transformLayout.chromaUnitCount() != 0) {
                short chromaFilterState = packFilterState(
                        filterLevel(frameHeader, header, 1, 0),
                        filterLevel(frameHeader, header, 2, 0),
                        skippedInter
                );
                TransformUnit firstChromaUnit = transformLayout.chromaUnit(0);
                int chromaStartX4 = firstChromaUnit.position().x4();
                int chromaStartY4 = firstChromaUnit.position().y4();
                int chromaEndX4 = chromaStartX4
                        + (firstChromaUnit.size().width4() << chromaSubsamplingX);
                int chromaEndY4 = chromaStartY4
                        + (firstChromaUnit.size().height4() << chromaSubsamplingY);
                for (int unitIndex = 0; unitIndex < transformLayout.chromaUnitCount(); unitIndex++) {
                    TransformUnit transformUnit = transformLayout.chromaUnit(unitIndex);
                    chromaStartX4 = Math.min(chromaStartX4, transformUnit.position().x4());
                    chromaStartY4 = Math.min(chromaStartY4, transformUnit.position().y4());
                    chromaEndX4 = Math.max(
                            chromaEndX4,
                            transformUnit.position().x4() + (transformUnit.size().width4() << chromaSubsamplingX)
                    );
                    chromaEndY4 = Math.max(
                            chromaEndY4,
                            transformUnit.position().y4() + (transformUnit.size().height4() << chromaSubsamplingY)
                    );
                    fillChromaTransform(
                            chromaFilterState,
                            transformUnit
                    );
                }
                markChromaBlockBoundaries(chromaStartX4, chromaStartY4, chromaEndX4, chromaEndY4);
            }
        }

        /// Fills the luma-grid coverage of one decoded chroma transform unit.
        ///
        /// @param filterState the packed U/V filter levels and skipped-inter flag
        /// @param transformUnit the decoded chroma transform unit
        private void fillChromaTransform(
                short filterState,
                TransformUnit transformUnit
        ) {
            int unitStartX4 = Math.max(0, transformUnit.position().x4());
            int unitStartY4 = Math.max(0, transformUnit.position().y4());
            int unitEndX4 = Math.min(
                    width4,
                    transformUnit.position().x4() + (transformUnit.size().width4() << chromaSubsamplingX)
            );
            int unitEndY4 = Math.min(
                    height4,
                    transformUnit.position().y4() + (transformUnit.size().height4() << chromaSubsamplingY)
            );
            byte transformSizeOrdinal = (byte) transformUnit.size().ordinal();
            for (int y4 = unitStartY4; y4 < unitEndY4; y4++) {
                for (int x4 = unitStartX4; x4 < unitEndX4; x4++) {
                    int index = y4 * width4 + x4;
                    int boundaryState = BOUNDARY_STATE_COVERED;
                    if (x4 == unitStartX4) {
                        boundaryState |= BOUNDARY_STATE_TRANSFORM_LEFT;
                    }
                    if (y4 == unitStartY4) {
                        boundaryState |= BOUNDARY_STATE_TRANSFORM_TOP;
                    }
                    chromaBoundaryStates[index] |= (byte) boundaryState;
                    chromaFilterStates[index] = filterState;
                    chromaTransformSizeOrdinals[index] = transformSizeOrdinal;
                }
            }
        }

        /// Marks the outer left and top boundaries of one rectangular chroma block footprint.
        ///
        /// @param startX4 the inclusive chroma footprint start X in luma 4x4 units
        /// @param startY4 the inclusive chroma footprint start Y in luma 4x4 units
        /// @param endX4 the exclusive chroma footprint end X in luma 4x4 units
        /// @param endY4 the exclusive chroma footprint end Y in luma 4x4 units
        private void markChromaBlockBoundaries(int startX4, int startY4, int endX4, int endY4) {
            int boundedStartX4 = Math.max(0, startX4);
            int boundedStartY4 = Math.max(0, startY4);
            int boundedEndX4 = Math.min(width4, endX4);
            int boundedEndY4 = Math.min(height4, endY4);
            if (boundedStartX4 >= boundedEndX4 || boundedStartY4 >= boundedEndY4) {
                return;
            }
            for (int y4 = boundedStartY4; y4 < boundedEndY4; y4++) {
                int index = y4 * width4 + boundedStartX4;
                if ((chromaBoundaryStates[index] & BOUNDARY_STATE_COVERED) != 0) {
                    chromaBoundaryStates[index] |= (byte) BOUNDARY_STATE_BLOCK_LEFT;
                }
            }
            int rowStart = boundedStartY4 * width4;
            for (int x4 = boundedStartX4; x4 < boundedEndX4; x4++) {
                int index = rowStart + x4;
                if ((chromaBoundaryStates[index] & BOUNDARY_STATE_COVERED) != 0) {
                    chromaBoundaryStates[index] |= (byte) BOUNDARY_STATE_BLOCK_TOP;
                }
            }
        }

        /// Initializes every cell in one leaf with one default luma transform.
        ///
        /// @param startX4 the leaf start X in luma 4x4 units
        /// @param startY4 the leaf start Y in luma 4x4 units
        /// @param endX4 the exclusive leaf end X in luma 4x4 units
        /// @param endY4 the exclusive leaf end Y in luma 4x4 units
        /// @param transformSize the default transform size
        private void fillDefaultLumaTransform(
                int startX4,
                int startY4,
                int endX4,
                int endY4,
                TransformSize transformSize
        ) {
            byte transformSizeOrdinal = (byte) transformSize.ordinal();
            for (int y4 = startY4; y4 < endY4; y4++) {
                for (int x4 = startX4; x4 < endX4; x4++) {
                    int index = y4 * width4 + x4;
                    int boundaryState = BOUNDARY_STATE_COVERED;
                    if (x4 == startX4) {
                        boundaryState |= BOUNDARY_STATE_TRANSFORM_LEFT;
                    }
                    if (y4 == startY4) {
                        boundaryState |= BOUNDARY_STATE_TRANSFORM_TOP;
                    }
                    lumaBoundaryStates[index] |= (byte) boundaryState;
                    lumaTransformSizeOrdinals[index] = transformSizeOrdinal;
                }
            }
        }

        /// Fills bounded luma-grid coverage with one decoded transform.
        ///
        /// @param startX4 the inclusive coverage bound in luma 4x4 units
        /// @param startY4 the inclusive coverage bound in luma 4x4 units
        /// @param endX4 the exclusive coverage bound in luma 4x4 units
        /// @param endY4 the exclusive coverage bound in luma 4x4 units
        /// @param transformUnit the transform unit to fill
        private void fillLumaTransform(
                int startX4,
                int startY4,
                int endX4,
                int endY4,
                TransformUnit transformUnit
        ) {
            int unitStartX4 = Math.max(startX4, transformUnit.position().x4());
            int unitStartY4 = Math.max(startY4, transformUnit.position().y4());
            int unitEndX4 = Math.min(endX4, transformUnit.position().x4() + transformUnit.size().width4());
            int unitEndY4 = Math.min(endY4, transformUnit.position().y4() + transformUnit.size().height4());
            byte transformSizeOrdinal = (byte) transformUnit.size().ordinal();
            for (int y4 = unitStartY4; y4 < unitEndY4; y4++) {
                for (int x4 = unitStartX4; x4 < unitEndX4; x4++) {
                    int index = y4 * width4 + x4;
                    int boundaryState = BOUNDARY_STATE_COVERED;
                    if (x4 == unitStartX4) {
                        boundaryState |= BOUNDARY_STATE_TRANSFORM_LEFT;
                    }
                    if (y4 == unitStartY4) {
                        boundaryState |= BOUNDARY_STATE_TRANSFORM_TOP;
                    }
                    lumaBoundaryStates[index] |= (byte) boundaryState;
                    lumaTransformSizeOrdinals[index] = transformSizeOrdinal;
                }
            }
        }
    }


}
