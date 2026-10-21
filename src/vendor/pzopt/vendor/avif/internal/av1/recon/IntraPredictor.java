// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.model.FilterIntraMode;
import pzopt.vendor.avif.internal.av1.model.LumaIntraPredictionMode;
import pzopt.vendor.avif.internal.av1.model.UvIntraPredictionMode;

/// Reconstructs AV1 intra-predicted luma and chroma blocks with instance-owned scratch storage.
///
/// This predictor implements non-directional modes, directional prediction with signed
/// `angle_delta`, luma filter-intra, and CFL chroma prediction for `YUV420`, `YUV422`, and `YUV444`.
/// Frame edges use midpoint samples when top or left neighbors are unavailable.
/// One instance must not execute prediction calls concurrently.

final class IntraPredictor {
    /// The AV1 directional base angles in `VERTICAL`-through-`VERTICAL_LEFT` order.
    private static final int [] DIRECTIONAL_BASE_ANGLES = {
            90, 180, 45, 135, 113, 157, 203, 67
    };

    /// The maximum axis length passed to one AV1 intra-prediction kernel invocation.
    private static final int MAX_INTRA_PREDICTION_AXIS_SIZE = 64;

    /// The largest reusable directional edge produced from a 64x64 prediction kernel.
    private static final int MAX_REFERENCE_BUFFER_LENGTH = 255;

    /// Workspace bank for unmodified top-edge references.
    private static final int TOP_REFERENCE_BANK = 0;

    /// Workspace bank for unmodified left-edge references.
    private static final int LEFT_REFERENCE_BANK = 1;

    /// Workspace bank for filtered or upsampled top-edge references.
    private static final int PROCESSED_TOP_REFERENCE_BANK = 2;

    /// Workspace bank for filtered or upsampled left-edge references.
    private static final int PROCESSED_LEFT_REFERENCE_BANK = 3;

    /// Workspace bank for zone-2 top edges that include the top-left sample.
    private static final int TOP_EDGE_BANK = 4;

    /// Workspace bank for zone-2 left edges that include the top-left sample.
    private static final int LEFT_EDGE_BANK = 5;

    /// Number of independently reusable reference-buffer banks.
    private static final int REFERENCE_BUFFER_BANK_COUNT = 6;

    /// Lazily created reusable intra-reference buffers owned by this predictor.
    private Workspace predictionWorkspace;

    /// The AV1 directional derivative table indexed by half-angle.
    private static final int [] DIRECTIONAL_DERIVATIVES = {
            0,
            1023, 0,
            547,
            372, 0, 0,
            273,
            215, 0,
            178,
            151, 0,
            132,
            116, 0,
            102, 0,
            90,
            80, 0,
            71,
            64, 0,
            57,
            51, 0,
            45, 0,
            40,
            35, 0,
            31,
            27, 0,
            23,
            19, 0,
            15, 0,
            11, 0,
            7,
            3
    };

    /// The AV1 filter-intra tap sets in `FilterIntraMode` order.
    ///
    /// Each mode stores eight per-pixel tap vectors for the 4x2 recursive prediction unit in
    /// raster order. Every tap vector multiplies:
    /// 1. the current unit top-left reference
    /// 2. the four top reference samples
    /// 3. the two left reference samples
    private static final int [] [] [] FILTER_INTRA_TAPS = {
            {
                    {-6, 10, 0, 0, 0, 12, 0},
                    {-5, 2, 10, 0, 0, 9, 0},
                    {-3, 1, 1, 10, 0, 7, 0},
                    {-3, 1, 1, 2, 10, 5, 0},
                    {-4, 6, 0, 0, 0, 2, 12},
                    {-3, 2, 6, 0, 0, 2, 9},
                    {-3, 2, 2, 6, 0, 2, 7},
                    {-3, 1, 2, 2, 6, 3, 5}
            },
            {
                    {-10, 16, 0, 0, 0, 10, 0},
                    {-6, 0, 16, 0, 0, 6, 0},
                    {-4, 0, 0, 16, 0, 4, 0},
                    {-2, 0, 0, 0, 16, 2, 0},
                    {-10, 16, 0, 0, 0, 0, 10},
                    {-6, 0, 16, 0, 0, 0, 6},
                    {-4, 0, 0, 16, 0, 0, 4},
                    {-2, 0, 0, 0, 16, 0, 2}
            },
            {
                    {-8, 8, 0, 0, 0, 16, 0},
                    {-8, 0, 8, 0, 0, 16, 0},
                    {-8, 0, 0, 8, 0, 16, 0},
                    {-8, 0, 0, 0, 8, 16, 0},
                    {-4, 4, 0, 0, 0, 0, 16},
                    {-4, 0, 4, 0, 0, 0, 16},
                    {-4, 0, 0, 4, 0, 0, 16},
                    {-4, 0, 0, 0, 4, 0, 16}
            },
            {
                    {-2, 8, 0, 0, 0, 10, 0},
                    {-1, 3, 8, 0, 0, 6, 0},
                    {-1, 2, 3, 8, 0, 4, 0},
                    {0, 1, 2, 3, 8, 2, 0},
                    {-1, 4, 0, 0, 0, 3, 10},
                    {-1, 3, 4, 0, 0, 4, 6},
                    {-1, 2, 3, 4, 0, 4, 4},
                    {-1, 2, 2, 3, 4, 3, 3}
            },
            {
                    {-12, 14, 0, 0, 0, 14, 0},
                    {-10, 0, 14, 0, 0, 12, 0},
                    {-9, 0, 0, 14, 0, 11, 0},
                    {-8, 0, 0, 0, 14, 10, 0},
                    {-10, 12, 0, 0, 0, 0, 14},
                    {-9, 1, 12, 0, 0, 0, 12},
                    {-8, 0, 0, 12, 0, 1, 11},
                    {-7, 0, 0, 1, 12, 1, 9}
            }
    };

    /// The AV1 intra-edge filter kernels indexed by `strength - 1`.
    private static final int [] [] INTRA_EDGE_FILTER_KERNELS = {
            {0, 4, 8, 4, 0},
            {0, 5, 6, 5, 0},
            {2, 4, 4, 4, 2}
    };

    /// The AV1 intra-edge upsampling kernel.
    private static final int [] INTRA_EDGE_UPSAMPLE_KERNEL = {-1, 9, 9, -1};

    /// The smooth predictor weights for a width or height of one sample.
    private static final int [] SMOOTH_WEIGHTS_1 = {255};

    /// The smooth predictor weights for a width or height of two samples.
    private static final int [] SMOOTH_WEIGHTS_2 = {255, 128};

    /// The smooth predictor weights for a width or height of four samples.
    private static final int [] SMOOTH_WEIGHTS_4 = {255, 149, 85, 64};

    /// The smooth predictor weights for a width or height of eight samples.
    private static final int [] SMOOTH_WEIGHTS_8 = {255, 197, 146, 105, 73, 50, 37, 32};

    /// The smooth predictor weights for a width or height of sixteen samples.
    private static final int [] SMOOTH_WEIGHTS_16 = {
            255, 225, 196, 170, 145, 123, 102, 84,
            68, 54, 43, 33, 26, 20, 17, 16
    };

    /// The smooth predictor weights for a width or height of thirty-two samples.
    private static final int [] SMOOTH_WEIGHTS_32 = {
            255, 240, 225, 210, 196, 182, 169, 157,
            145, 133, 122, 111, 101, 92, 83, 74,
            66, 59, 52, 45, 39, 34, 29, 25,
            21, 17, 14, 12, 10, 9, 8, 8
    };

    /// The smooth predictor weights for a width or height of sixty-four samples.
    private static final int [] SMOOTH_WEIGHTS_64 = {
            255, 248, 240, 233, 225, 218, 210, 203,
            196, 189, 182, 176, 169, 163, 156, 150,
            144, 138, 133, 127, 121, 116, 111, 106,
            101, 96, 91, 86, 82, 77, 73, 69,
            65, 61, 57, 54, 50, 47, 44, 41,
            38, 35, 32, 29, 27, 25, 22, 20,
            18, 16, 15, 13, 12, 10, 9, 8,
            7, 6, 6, 5, 5, 4, 4, 4
    };

    /// Creates an intra predictor with isolated reusable scratch storage.
    IntraPredictor() {
    }

    /// Reconstructs one luma intra-predicted block directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the luma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    void predictLuma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            LumaIntraPredictionMode mode,
            int angleDelta
    ) {
        predictLuma(plane, x, y, width, height, mode, angleDelta, false, false);
    }

    /// Reconstructs one luma intra-predicted block directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the luma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    void predictLuma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            LumaIntraPredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences
    ) {
        predictLuma(
                plane,
                x,
                y,
                width,
                height,
                mode,
                angleDelta,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                -1,
                -1,
                0,
                0,
                plane.width(),
                plane.height()
        );
    }

    /// Reconstructs one luma intra-predicted block directly into the destination plane with
    /// explicit directional-edge availability.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the luma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
    /// @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
    void predictLuma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            LumaIntraPredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int directionalLeftReferenceLength
    ) {
        predictLuma(
                plane,
                x,
                y,
                width,
                height,
                mode,
                angleDelta,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalTopReferenceLength,
                directionalLeftReferenceLength,
                0,
                0,
                plane.width(),
                plane.height()
        );
    }

    /// Reconstructs one luma intra-predicted block directly into the destination plane with
    /// explicit directional-edge and tile-boundary availability.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the luma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
    /// @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    void predictLuma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            LumaIntraPredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int directionalLeftReferenceLength,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        predict(
                plane,
                x,
                y,
                width,
                height,
                predictionMode(mode),
                angleDelta,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalTopReferenceLength,
                directionalLeftReferenceLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
    }

    /// Reconstructs one luma filter-intra block directly into the destination plane.
    ///
    /// Implements the AV1 recursive 4x2 filter-intra algorithm for the syntax-legal size range up
    /// to `32x32`, including partially visible right and bottom prediction units.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the filter-intra mode
    void predictFilterIntraLuma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            FilterIntraMode mode
    ) {
        predictFilterIntraLuma(plane, x, y, width, height, mode, 0, 0, plane.width(), plane.height());
    }

    /// Reconstructs one luma filter-intra block directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the filter-intra mode
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    void predictFilterIntraLuma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            FilterIntraMode mode,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (width > 32 || height > 32) {
            throw new IllegalStateException("filter_intra requires block dimensions no larger than 32x32: " + width + "x" + height);
        }

        int defaultSample = 1 << (plane.bitDepth() - 1);
        int[][] taps = FILTER_INTRA_TAPS[mode.symbolIndex()];
        int predictionWidth = (width + 3) & ~3;
        int predictionHeight = (height + 1) & ~1;
        for (int stripeY = 0; stripeY < predictionHeight; stripeY += 2) {
            int topReferenceY = y + stripeY - 1;
            int currentY = y + stripeY;
            for (int blockX = 0; blockX < predictionWidth; blockX += 4) {
                int currentX = x + blockX;
                int leftReferenceX = currentX - 1;
                int p0 = filterIntraTopLeftReference(
                        plane,
                        x,
                        y,
                        currentX,
                        currentY,
                        topReferenceY,
                        defaultSample,
                        leftBoundary,
                        topBoundary,
                        rightBoundary,
                        bottomBoundary
                );
                int p1 = filterIntraTopReference(
                        plane,
                        x,
                        y,
                        currentX,
                        topReferenceY,
                        defaultSample,
                        leftBoundary,
                        topBoundary,
                        rightBoundary,
                        bottomBoundary
                );
                int p2 = filterIntraTopReference(
                        plane,
                        x,
                        y,
                        currentX + 1,
                        topReferenceY,
                        defaultSample,
                        leftBoundary,
                        topBoundary,
                        rightBoundary,
                        bottomBoundary
                );
                int p3 = filterIntraTopReference(
                        plane,
                        x,
                        y,
                        currentX + 2,
                        topReferenceY,
                        defaultSample,
                        leftBoundary,
                        topBoundary,
                        rightBoundary,
                        bottomBoundary
                );
                int p4 = filterIntraTopReference(
                        plane,
                        x,
                        y,
                        currentX + 3,
                        topReferenceY,
                        defaultSample,
                        leftBoundary,
                        topBoundary,
                        rightBoundary,
                        bottomBoundary
                );
                int p5 = filterIntraLeftReference(
                        plane,
                        x,
                        y,
                        leftReferenceX,
                        currentY,
                        defaultSample,
                        leftBoundary,
                        topBoundary,
                        rightBoundary,
                        bottomBoundary
                );
                int p6 = filterIntraLeftReference(
                        plane,
                        x,
                        y,
                        leftReferenceX,
                        currentY + 1,
                        defaultSample,
                        leftBoundary,
                        topBoundary,
                        rightBoundary,
                        bottomBoundary
                );
                for (int yy = 0; yy < 2; yy++) {
                    for (int xx = 0; xx < 4; xx++) {
                        int[] tap = taps[(yy << 2) + xx];
                        int predicted = (tap[0] * p0
                                + tap[1] * p1
                                + tap[2] * p2
                                + tap[3] * p3
                                + tap[4] * p4
                                + tap[5] * p5
                                + tap[6] * p6
                                + 8) >> 4;
                        if (blockX + xx < width && stripeY + yy < height) {
                            setSampleIfInside(plane, currentX + xx, currentY + yy, predicted);
                        }
                    }
                }
            }
        }
    }

    /// Returns one filter-intra top-left reference sample.
    ///
    /// @param plane the mutable destination plane
    /// @param blockX the block origin X coordinate
    /// @param blockY the block origin Y coordinate
    /// @param currentX the current recursive unit origin X coordinate
    /// @param currentY the current recursive unit origin Y coordinate
    /// @param topReferenceY the top reference row for this recursive unit
    /// @param defaultSample the midpoint frame-edge default sample
    /// @return one filter-intra top-left reference sample
    private int filterIntraTopLeftReference(
            MutableSamplePlane plane,
            int blockX,
            int blockY,
            int currentX,
            int currentY,
            int topReferenceY,
            int defaultSample,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        if (currentX > blockX) {
            return filterIntraTopReference(
                    plane,
                    blockX,
                    blockY,
                    currentX - 1,
                    topReferenceY,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
            );
        }
        if (currentY > blockY) {
            return filterIntraLeftReference(
                    plane,
                    blockX,
                    blockY,
                    currentX - 1,
                    currentY - 1,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
            );
        }
        return defaultTopLeft(plane, blockX, blockY, defaultSample, leftBoundary, topBoundary);
    }

    /// Returns one filter-intra top reference sample with AV1 frame-edge fallback.
    ///
    /// @param plane the mutable destination plane
    /// @param blockX the block origin X coordinate
    /// @param blockY the block origin Y coordinate
    /// @param sampleX the top reference X coordinate
    /// @param topReferenceY the top reference row for this recursive unit
    /// @param defaultSample the midpoint frame-edge default sample
    /// @return one filter-intra top reference sample
    private int filterIntraTopReference(
            MutableSamplePlane plane,
            int blockX,
            int blockY,
            int sampleX,
            int topReferenceY,
            int defaultSample,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        if (topReferenceY >= blockY) {
            return edgeExtendedSample(plane, sampleX, topReferenceY);
        }
        if (topReferenceY < topBoundary) {
            return blockX > leftBoundary ? edgeExtendedSample(plane, blockX - 1, blockY) : defaultSample - 1;
        }
        int maxX = Math.max(0, Math.min(rightBoundary, plane.width()) - 1);
        int maxY = Math.max(0, Math.min(bottomBoundary, plane.height()) - 1);
        return plane.sample(Math.min(sampleX, maxX), Math.min(topReferenceY, maxY));
    }

    /// Returns one filter-intra left reference sample with AV1 frame-edge fallback.
    ///
    /// @param plane the mutable destination plane
    /// @param blockX the block origin X coordinate
    /// @param blockY the block origin Y coordinate
    /// @param leftReferenceX the left reference column for this recursive unit
    /// @param sampleY the left reference Y coordinate
    /// @param defaultSample the midpoint frame-edge default sample
    /// @return one filter-intra left reference sample
    private int filterIntraLeftReference(
            MutableSamplePlane plane,
            int blockX,
            int blockY,
            int leftReferenceX,
            int sampleY,
            int defaultSample,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        if (leftReferenceX >= blockX) {
            return edgeExtendedSample(plane, leftReferenceX, sampleY);
        }
        if (leftReferenceX < leftBoundary) {
            return blockY > topBoundary ? edgeExtendedSample(plane, blockX, blockY - 1) : defaultSample + 1;
        }
        int maxX = Math.max(0, Math.min(rightBoundary, plane.width()) - 1);
        int maxY = Math.max(0, Math.min(bottomBoundary, plane.height()) - 1);
        return plane.sample(Math.min(leftReferenceX, maxX), Math.min(sampleY, maxY));
    }

    /// Reconstructs one chroma intra-predicted block directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the chroma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    void predictChroma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            UvIntraPredictionMode mode,
            int angleDelta
    ) {
        predictChroma(plane, x, y, width, height, mode, angleDelta, false, false);
    }

    /// Reconstructs one chroma intra-predicted block directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the chroma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    void predictChroma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            UvIntraPredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences
    ) {
        predictChroma(
                plane,
                x,
                y,
                width,
                height,
                mode,
                angleDelta,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                -1,
                -1
        );
    }

    /// Reconstructs one chroma intra-predicted block directly into the destination plane with
    /// explicit directional-edge availability.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the chroma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
    /// @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
    void predictChroma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            UvIntraPredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int directionalLeftReferenceLength
    ) {
        predictChroma(
                plane,
                x,
                y,
                width,
                height,
                mode,
                angleDelta,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalTopReferenceLength,
                directionalLeftReferenceLength,
                0,
                0,
                plane.width(),
                plane.height()
        );
    }

    /// Reconstructs one chroma intra-predicted block directly into the destination plane with
    /// explicit directional-edge and tile-boundary availability.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the chroma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
    /// @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    void predictChroma(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            UvIntraPredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int directionalLeftReferenceLength,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        predict(
                plane,
                x,
                y,
                width,
                height,
                predictionMode(mode),
                angleDelta,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalTopReferenceLength,
                directionalLeftReferenceLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
    }

    /// Reconstructs one CFL chroma block directly into the destination plane.
    ///
    /// The predictor consumes already reconstructed luma samples, including applied luma
    /// residuals, and accepts the subsampling shifts used by `YUV420`, `YUV422`, and `YUV444`.
    ///
    /// @param chromaPlane the mutable chroma destination plane
    /// @param lumaPlane the already reconstructed luma plane
    /// @param chromaX the zero-based horizontal chroma sample coordinate
    /// @param chromaY the zero-based vertical chroma sample coordinate
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param width the chroma block width in samples
    /// @param height the chroma block height in samples
    /// @param alpha the signed CFL alpha
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    void predictChromaCfl(
            MutableSamplePlane chromaPlane,
            MutableSamplePlane lumaPlane,
            int chromaX,
            int chromaY,
            int lumaX,
            int lumaY,
            int width,
            int height,
            int alpha,
            int subsamplingX,
            int subsamplingY
    ) {
        predictChromaCfl(
                chromaPlane,
                lumaPlane,
                chromaX,
                chromaY,
                lumaX,
                lumaY,
                width,
                height,
                alpha,
                subsamplingX,
                subsamplingY,
                0,
                0,
                chromaPlane.width(),
                chromaPlane.height()
        );
    }

    /// Reconstructs one CFL chroma block directly into the destination plane with tile-boundary
    /// aware DC prediction.
    ///
    /// @param chromaPlane the mutable chroma destination plane
    /// @param lumaPlane the already reconstructed luma plane
    /// @param chromaX the zero-based horizontal chroma sample coordinate
    /// @param chromaY the zero-based vertical chroma sample coordinate
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param width the chroma block width in samples
    /// @param height the chroma block height in samples
    /// @param alpha the signed CFL alpha
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param leftBoundary the first chroma sample column available to this tile
    /// @param topBoundary the first chroma sample row available to this tile
    /// @param rightBoundary the exclusive chroma sample column available to this tile
    /// @param bottomBoundary the exclusive chroma sample row available to this tile
    void predictChromaCfl(
            MutableSamplePlane chromaPlane,
            MutableSamplePlane lumaPlane,
            int chromaX,
            int chromaY,
            int lumaX,
            int lumaY,
            int width,
            int height,
            int alpha,
            int subsamplingX,
            int subsamplingY,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        predictChromaCfl(
                chromaPlane,
                lumaPlane,
                chromaX,
                chromaY,
                lumaX,
                lumaY,
                width,
                height,
                alpha,
                subsamplingX,
                subsamplingY,
                width,
                height,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
    }

    /// Reconstructs one CFL chroma block with explicit stored-luma and tile boundaries.
    ///
    /// The stored dimensions describe the subsampled luma footprint populated by decoded luma
    /// transform units. Values outside that footprint are padded from its last column or row before
    /// the block average is subtracted.
    ///
    /// @param chromaPlane the mutable chroma destination plane
    /// @param lumaPlane the already reconstructed luma plane
    /// @param chromaX the zero-based horizontal chroma sample coordinate
    /// @param chromaY the zero-based vertical chroma sample coordinate
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param width the chroma block width in samples
    /// @param height the chroma block height in samples
    /// @param alpha the signed CFL alpha
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param storedWidth the subsampled luma width populated before right-edge padding
    /// @param storedHeight the subsampled luma height populated before bottom-edge padding
    /// @param leftBoundary the first chroma sample column available to this tile
    /// @param topBoundary the first chroma sample row available to this tile
    /// @param rightBoundary the exclusive chroma sample column available to this tile
    /// @param bottomBoundary the exclusive chroma sample row available to this tile
    void predictChromaCfl(
            MutableSamplePlane chromaPlane,
            MutableSamplePlane lumaPlane,
            int chromaX,
            int chromaY,
            int lumaX,
            int lumaY,
            int width,
            int height,
            int alpha,
            int subsamplingX,
            int subsamplingY,
            int storedWidth,
            int storedHeight,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (subsamplingX < 0 || subsamplingX > 1) {
            throw new IllegalArgumentException("subsamplingX must be 0 or 1: " + subsamplingX);
        }
        if (subsamplingY < 0 || subsamplingY > 1) {
            throw new IllegalArgumentException("subsamplingY must be 0 or 1: " + subsamplingY);
        }
        if (storedWidth <= 0 || storedWidth > width) {
            throw new IllegalArgumentException("storedWidth out of range: " + storedWidth);
        }
        if (storedHeight <= 0 || storedHeight > height) {
            throw new IllegalArgumentException("storedHeight out of range: " + storedHeight);
        }
        int dc = dcPredictionValue(
                chromaPlane,
                chromaX,
                chromaY,
                width,
                height,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
        int[] ac = cflAc(
                lumaPlane,
                lumaX,
                lumaY,
                width,
                height,
                storedWidth,
                storedHeight,
                subsamplingX,
                subsamplingY
        );
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int diff = alpha * ac[row * width + column];
                int predicted = dc + applySign((Math.abs(diff) + 32) >> 6, diff);
                setSampleIfInside(chromaPlane, chromaX + column, chromaY + row, predicted);
            }
        }
    }

    /// Reconstructs one `YUV420` CFL chroma block directly into the destination plane.
    ///
    /// @param chromaPlane the mutable chroma destination plane
    /// @param lumaPlane the already reconstructed luma plane
    /// @param chromaX the zero-based horizontal chroma sample coordinate
    /// @param chromaY the zero-based vertical chroma sample coordinate
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param width the chroma block width in samples
    /// @param height the chroma block height in samples
    /// @param alpha the signed CFL alpha
    void predictChromaCflI420(
            MutableSamplePlane chromaPlane,
            MutableSamplePlane lumaPlane,
            int chromaX,
            int chromaY,
            int lumaX,
            int lumaY,
            int width,
            int height,
            int alpha
    ) {
        predictChromaCfl(chromaPlane, lumaPlane, chromaX, chromaY, lumaX, lumaY, width, height, alpha, 1, 1);
    }

    /// Maps one luma prediction mode to its internal prediction kernel.
    ///
    /// @param mode the luma intra prediction mode
    /// @return the internal prediction mode
    private PredictionMode predictionMode(LumaIntraPredictionMode mode) {
        return switch (mode) {
            case DC -> PredictionMode.DC;
            case VERTICAL -> PredictionMode.VERTICAL;
            case HORIZONTAL -> PredictionMode.HORIZONTAL;
            case DIAGONAL_DOWN_LEFT -> PredictionMode.DIAGONAL_DOWN_LEFT;
            case DIAGONAL_DOWN_RIGHT -> PredictionMode.DIAGONAL_DOWN_RIGHT;
            case VERTICAL_RIGHT -> PredictionMode.VERTICAL_RIGHT;
            case HORIZONTAL_DOWN -> PredictionMode.HORIZONTAL_DOWN;
            case HORIZONTAL_UP -> PredictionMode.HORIZONTAL_UP;
            case VERTICAL_LEFT -> PredictionMode.VERTICAL_LEFT;
            case SMOOTH -> PredictionMode.SMOOTH;
            case SMOOTH_VERTICAL -> PredictionMode.SMOOTH_VERTICAL;
            case SMOOTH_HORIZONTAL -> PredictionMode.SMOOTH_HORIZONTAL;
            case PAETH -> PredictionMode.PAETH;
        };
    }

    /// Maps one chroma prediction mode to its internal prediction kernel.
    ///
    /// @param mode the chroma intra prediction mode
    /// @return the internal prediction mode
    private PredictionMode predictionMode(UvIntraPredictionMode mode) {
        return switch (mode) {
            case DC -> PredictionMode.DC;
            case VERTICAL -> PredictionMode.VERTICAL;
            case HORIZONTAL -> PredictionMode.HORIZONTAL;
            case DIAGONAL_DOWN_LEFT -> PredictionMode.DIAGONAL_DOWN_LEFT;
            case DIAGONAL_DOWN_RIGHT -> PredictionMode.DIAGONAL_DOWN_RIGHT;
            case VERTICAL_RIGHT -> PredictionMode.VERTICAL_RIGHT;
            case HORIZONTAL_DOWN -> PredictionMode.HORIZONTAL_DOWN;
            case HORIZONTAL_UP -> PredictionMode.HORIZONTAL_UP;
            case VERTICAL_LEFT -> PredictionMode.VERTICAL_LEFT;
            case SMOOTH -> PredictionMode.SMOOTH;
            case SMOOTH_VERTICAL -> PredictionMode.SMOOTH_VERTICAL;
            case SMOOTH_HORIZONTAL -> PredictionMode.SMOOTH_HORIZONTAL;
            case PAETH -> PredictionMode.PAETH;
            case CFL -> throw new IllegalArgumentException("CFL requires predictChromaCfl with reconstructed luma");
        };
    }

    /// Reconstructs one supported intra-predicted block directly into the destination plane with
    /// explicit tile-boundary availability.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the supported internal prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
    /// @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    private void predict(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            PredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int directionalLeftReferenceLength,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (width > MAX_INTRA_PREDICTION_AXIS_SIZE || height > MAX_INTRA_PREDICTION_AXIS_SIZE) {
            predictLargeBlock(
                    plane,
                    x,
                    y,
                    width,
                    height,
                    mode,
                    angleDelta,
                    intraEdgeFilterEnabled,
                    smoothEdgeReferences,
                    directionalTopReferenceLength,
                    directionalLeftReferenceLength,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
            );
            return;
        }

        if (mode.usesDirectionalPrediction(angleDelta)) {
            predictDirectional(
                    plane,
                    x,
                    y,
                    width,
                    height,
                    mode,
                    angleDelta,
                    intraEdgeFilterEnabled,
                    smoothEdgeReferences,
                    directionalTopReferenceLength,
                    directionalLeftReferenceLength,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
            );
            return;
        }

        int defaultSample = 1 << (plane.bitDepth() - 1);
        int referenceWidth = mode.usesHorizontalSmoothReference() ? smoothWeightAxisSize(width) : width;
        int referenceHeight = mode.usesVerticalSmoothReference() ? smoothWeightAxisSize(height) : height;
        int[] top = topReferenceSamples(plane, x, y, referenceWidth, defaultSample, leftBoundary, topBoundary, rightBoundary, bottomBoundary);
        int[] left = leftReferenceSamples(plane, x, y, referenceHeight, defaultSample, leftBoundary, topBoundary, rightBoundary, bottomBoundary);

        int topLeft = defaultTopLeft(plane, x, y, defaultSample, leftBoundary, topBoundary);
        switch (mode) {
            case DC -> predictDc(plane, x, y, width, height, top, left, defaultSample, leftBoundary, topBoundary);
            case VERTICAL -> predictVertical(plane, x, y, width, height, top);
            case HORIZONTAL -> predictHorizontal(plane, x, y, width, height, left);
            case PAETH -> {
                if (x <= leftBoundary && y <= topBoundary) {
                    plane.fillBlock(x, y, width, height, defaultSample);
                } else if (x <= leftBoundary) {
                    predictVertical(plane, x, y, width, height, top);
                } else if (y <= topBoundary) {
                    predictHorizontal(plane, x, y, width, height, left);
                } else {
                    predictPaeth(plane, x, y, width, height, top, left, topLeft);
                }
            }
            case SMOOTH -> predictSmooth(plane, x, y, width, height, top, left);
            case SMOOTH_VERTICAL -> predictSmoothVertical(plane, x, y, width, height, top, left);
            case SMOOTH_HORIZONTAL -> predictSmoothHorizontal(plane, x, y, width, height, top, left);
        }
    }

    /// Reconstructs a large intra block through 64x64-or-smaller prediction-kernel regions.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the supported internal prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
    /// @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
    private void predictLargeBlock(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            PredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int directionalLeftReferenceLength,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        for (int offsetY = 0; offsetY < height; offsetY += MAX_INTRA_PREDICTION_AXIS_SIZE) {
            int subHeight = Math.min(MAX_INTRA_PREDICTION_AXIS_SIZE, height - offsetY);
            for (int offsetX = 0; offsetX < width; offsetX += MAX_INTRA_PREDICTION_AXIS_SIZE) {
                int subWidth = Math.min(MAX_INTRA_PREDICTION_AXIS_SIZE, width - offsetX);
                predict(
                        plane,
                        x + offsetX,
                        y + offsetY,
                        subWidth,
                        subHeight,
                        mode,
                        angleDelta,
                        intraEdgeFilterEnabled,
                        smoothEdgeReferences,
                        directionalTopReferenceLength,
                        directionalLeftReferenceLength,
                        leftBoundary,
                        topBoundary,
                        rightBoundary,
                        bottomBoundary
                );
            }
        }
    }

    /// Reconstructs one directional intra-predicted block.
    ///
    /// The implementation follows the AV1 zone-1/2/3 directional interpolation model and applies
    /// the sequence-controlled intra-edge filtering or upsampling pre-pass before interpolation.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the directional-capable prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
    /// @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
    private void predictDirectional(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            PredictionMode mode,
            int angleDelta,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int directionalLeftReferenceLength,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        int angle = mode.directionalBaseAngle() + 3 * angleDelta;
        if (angle < 0 || angle > 270) {
            throw new IllegalStateException("Directional angle is out of range: " + angle);
        }

        int defaultSample = 1 << (plane.bitDepth() - 1);
        if (angle == 90 || (angle < 90 && y <= topBoundary)) {
            predictVertical(
                    plane,
                    x,
                    y,
                    width,
                    height,
                    topDirectionalReferences(
                            plane,
                            x,
                            y,
                            width,
                            height,
                            defaultSample,
                            width,
                            leftBoundary,
                            topBoundary,
                            rightBoundary,
                            bottomBoundary
                    )
            );
            return;
        }
        if (angle == 180 || (angle > 180 && x <= leftBoundary)) {
            predictHorizontal(
                    plane,
                    x,
                    y,
                    width,
                    height,
                    leftDirectionalReferences(
                            plane,
                            x,
                            y,
                            width,
                            height,
                            defaultSample,
                            height,
                            leftBoundary,
                            topBoundary,
                            rightBoundary,
                            bottomBoundary
                    )
            );
            return;
        }
        if (angle < 90) {
            predictDirectionalZone1(
                    plane,
                    x,
                    y,
                    width,
                    height,
                    angle,
                    defaultSample,
                    intraEdgeFilterEnabled,
                    smoothEdgeReferences,
                    directionalTopReferenceLength,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
            );
            return;
        }
        if (angle < 180) {
            predictDirectionalZone2(
                    plane,
                    x,
                    y,
                    width,
                    height,
                    angle,
                    defaultSample,
                    intraEdgeFilterEnabled,
                    smoothEdgeReferences,
                    directionalTopReferenceLength,
                    directionalLeftReferenceLength,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
            );
            return;
        }
        predictDirectionalZone3(
                plane,
                x,
                y,
                width,
                height,
                angle,
                defaultSample,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalLeftReferenceLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
    }

    /// Reconstructs one zone-1 directional block that projects from the top edge.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param angle the absolute AV1 intra prediction angle
    /// @param defaultSample the frame-edge default sample
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    private void predictDirectionalZone1(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int angle,
            int defaultSample,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        int availableTopLength = directionalReferenceLength(
                directionalTopReferenceLength,
                width,
                width + Math.min(width, height)
        );
        int[] topReferences = topDirectionalReferences(
                plane,
                x,
                y,
                width,
                height,
                defaultSample,
                availableTopLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
        int topLeft = defaultTopLeft(plane, x, y, defaultSample, leftBoundary, topBoundary);
        int dx = directionalDerivative(angle >> 1);
        int referenceSpan = width + height;
        int edgeAngle = 90 - angle;
        int[] top;
        int maxBase;
        int baseIncrement;
        if (intraEdgeFilterEnabled && useDirectionalEdgeUpsample(referenceSpan, edgeAngle, smoothEdgeReferences)) {
            top = upsampleDirectionalEdge(
                    topReferences,
                    topLeft,
                    referenceSpan,
                    -1,
                    availableTopLength,
                    plane.bitDepth(),
                    false,
                    PROCESSED_TOP_REFERENCE_BANK
            );
            dx <<= 1;
            maxBase = top.length - 1;
            baseIncrement = 2;
        } else {
            int filterStrength = intraEdgeFilterEnabled
                    ? directionalEdgeFilterStrength(referenceSpan, edgeAngle, smoothEdgeReferences)
                    : 0;
            if (filterStrength != 0) {
                top = filterDirectionalEdge(
                        topReferences,
                        topLeft,
                        referenceSpan,
                        0,
                        referenceSpan,
                        -1,
                        availableTopLength,
                        filterStrength,
                        PROCESSED_TOP_REFERENCE_BANK
                );
                maxBase = referenceSpan - 1;
            } else {
                top = topReferences;
                maxBase = top.length - 1;
            }
            baseIncrement = 1;
        }
        for (int row = 0, xpos = dx; row < height; row++, xpos += dx) {
            int frac = xpos & 0x3E;
            for (int column = 0, base = xpos >> 6; column < width; column++, base += baseIncrement) {
                if (base < maxBase) {
                    setSampleIfInside(plane, x + column, y + row, interpolate(top[base], top[base + 1], frac));
                } else {
                    for (int remaining = column; remaining < width; remaining++) {
                        setSampleIfInside(plane, x + remaining, y + row, top[maxBase]);
                    }
                    break;
                }
            }
        }
    }

    /// Reconstructs one zone-2 directional block that blends the top and left edges.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param angle the absolute AV1 intra prediction angle
    /// @param defaultSample the frame-edge default sample
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    /// @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
    /// @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
    private void predictDirectionalZone2(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int angle,
            int defaultSample,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalTopReferenceLength,
            int directionalLeftReferenceLength,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        int availableTopLength = directionalReferenceAvailability(directionalTopReferenceLength, width);
        int availableLeftLength = directionalReferenceAvailability(directionalLeftReferenceLength, height);
        int[] topReferences = topDirectionalReferences(
                plane,
                x,
                y,
                width,
                height,
                defaultSample,
                width,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
        int[] leftReferences = leftDirectionalReferences(
                plane,
                x,
                y,
                width,
                height,
                defaultSample,
                height,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
        int topLeft = defaultTopLeft(plane, x, y, defaultSample, leftBoundary, topBoundary);
        int dy = directionalDerivative((angle - 90) >> 1);
        int dx = directionalDerivative((180 - angle) >> 1);
        int referenceSpan = width + height;
        if (intraEdgeFilterEnabled && x > leftBoundary && y > topBoundary && referenceSpan >= 24) {
            topLeft = filterDirectionalEdgeCorner(topLeft, topReferences[0], leftReferences[0]);
        }
        boolean upsampleTop = intraEdgeFilterEnabled
                && useDirectionalEdgeUpsample(referenceSpan, angle - 90, smoothEdgeReferences);
        boolean upsampleLeft = intraEdgeFilterEnabled
                && useDirectionalEdgeUpsample(referenceSpan, 180 - angle, smoothEdgeReferences);
        int[] topEdge;
        int topBaseOffset;
        if (upsampleTop) {
            topEdge = upsampleDirectionalEdge(
                    topReferences,
                    topLeft,
                    width + 1,
                    0,
                    width + 1,
                    plane.bitDepth(),
                    true,
                    TOP_EDGE_BANK
            );
            topBaseOffset = 2;
        } else {
            int filterStrength = intraEdgeFilterEnabled
                    ? directionalEdgeFilterStrength(referenceSpan, angle - 90, smoothEdgeReferences)
                    : 0;
            int[] filteredTopReferences = filterStrength != 0 && availableTopLength > 0
                    ? filterDirectionalEdge(
                            topReferences,
                            topLeft,
                            width,
                            0,
                            availableTopLength,
                            -1,
                            availableTopLength,
                            filterStrength,
                            PROCESSED_TOP_REFERENCE_BANK
                    )
                    : topReferences;
            topEdge = edgeWithTopLeft(filteredTopReferences, topLeft, TOP_EDGE_BANK);
            topBaseOffset = 1;
        }
        int[] leftEdge;
        int leftBaseOffset;
        if (upsampleLeft) {
            leftEdge = upsampleDirectionalEdge(
                    leftReferences,
                    topLeft,
                    height + 1,
                    0,
                    height + 1,
                    plane.bitDepth(),
                    true,
                    LEFT_EDGE_BANK
            );
            leftBaseOffset = 2;
        } else {
            int filterStrength = intraEdgeFilterEnabled
                    ? directionalEdgeFilterStrength(referenceSpan, 180 - angle, smoothEdgeReferences)
                    : 0;
            int[] filteredLeftReferences = filterStrength != 0 && availableLeftLength > 0
                    ? filterDirectionalEdge(
                            leftReferences,
                            topLeft,
                            height,
                            0,
                            availableLeftLength,
                            -1,
                            availableLeftLength,
                            filterStrength,
                            PROCESSED_LEFT_REFERENCE_BANK
                    )
                    : leftReferences;
            leftEdge = edgeWithTopLeft(filteredLeftReferences, topLeft, LEFT_EDGE_BANK);
            leftBaseOffset = 1;
        }
        int topMinimumBase = -topBaseOffset;
        int topFractionBits = 6 - (upsampleTop ? 1 : 0);
        int leftFractionBits = 6 - (upsampleLeft ? 1 : 0);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int topPosition = (column << 6) - (row + 1) * dx;
                int baseX = topPosition >> topFractionBits;
                if (baseX >= topMinimumBase) {
                    setSampleIfInside(
                            plane,
                            x + column,
                            y + row,
                            interpolate(
                                    edgeSample(topEdge, baseX + topBaseOffset),
                                    edgeSample(topEdge, baseX + topBaseOffset + 1),
                                    directionalFraction(topPosition, upsampleTop)
                            )
                    );
                } else {
                    int leftPosition = (row << 6) - (column + 1) * dy;
                    int baseY = leftPosition >> leftFractionBits;
                    setSampleIfInside(
                            plane,
                            x + column,
                            y + row,
                            interpolate(
                                    edgeSample(leftEdge, baseY + leftBaseOffset),
                                    edgeSample(leftEdge, baseY + leftBaseOffset + 1),
                                    directionalFraction(leftPosition, upsampleLeft)
                            )
                    );
                }
            }
        }
    }

    /// Reconstructs one zone-3 directional block that projects from the left edge.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param angle the absolute AV1 intra prediction angle
    /// @param defaultSample the frame-edge default sample
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
    /// @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
    private void predictDirectionalZone3(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int angle,
            int defaultSample,
            boolean intraEdgeFilterEnabled,
            boolean smoothEdgeReferences,
            int directionalLeftReferenceLength,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        int availableLeftLength = directionalReferenceLength(
                directionalLeftReferenceLength,
                height,
                height + Math.min(width, height)
        );
        int[] leftReferences = leftDirectionalReferences(
                plane,
                x,
                y,
                width,
                height,
                defaultSample,
                availableLeftLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
        );
        int topLeft = defaultTopLeft(plane, x, y, defaultSample, leftBoundary, topBoundary);
        int dy = directionalDerivative((270 - angle) >> 1);
        int referenceSpan = width + height;
        int edgeAngle = angle - 180;
        int[] left;
        int maxBase;
        int baseIncrement;
        if (intraEdgeFilterEnabled && useDirectionalEdgeUpsample(referenceSpan, edgeAngle, smoothEdgeReferences)) {
            left = upsampleDirectionalEdge(
                    leftReferences,
                    topLeft,
                    referenceSpan,
                    -1,
                    availableLeftLength,
                    plane.bitDepth(),
                    false,
                    PROCESSED_LEFT_REFERENCE_BANK
            );
            dy <<= 1;
            maxBase = left.length - 1;
            baseIncrement = 2;
        } else {
            int filterStrength = intraEdgeFilterEnabled
                    ? directionalEdgeFilterStrength(referenceSpan, edgeAngle, smoothEdgeReferences)
                    : 0;
            if (filterStrength != 0) {
                left = filterDirectionalEdge(
                        leftReferences,
                        topLeft,
                        referenceSpan,
                        0,
                        referenceSpan,
                        -1,
                        availableLeftLength,
                        filterStrength,
                        PROCESSED_LEFT_REFERENCE_BANK
                );
                maxBase = referenceSpan - 1;
            } else {
                left = leftReferences;
                maxBase = left.length - 1;
            }
            baseIncrement = 1;
        }
        for (int column = 0, ypos = dy; column < width; column++, ypos += dy) {
            int frac = ypos & 0x3E;
            for (int row = 0, base = ypos >> 6; row < height; row++, base += baseIncrement) {
                if (base < maxBase) {
                    int predicted = interpolate(left[base], left[base + 1], frac);
                    setSampleIfInside(plane, x + column, y + row, predicted);
                } else {
                    for (int remaining = row; remaining < height; remaining++) {
                        setSampleIfInside(plane, x + column, y + remaining, left[maxBase]);
                    }
                    break;
                }
            }
        }
    }

    /// Returns a bounded directional reference length.
    ///
    /// @param requestedLength the requested available reference length, or a negative value for default
    /// @param minimumLength the minimum number of references required by the predictor
    /// @param defaultLength the default reference length when availability is not explicitly constrained
    /// @return the bounded directional reference length
    private int directionalReferenceLength(int requestedLength, int minimumLength, int defaultLength) {
        if (requestedLength < 0) {
            return defaultLength;
        }
        return clamp(requestedLength, minimumLength, defaultLength);
    }

    /// Returns the available portion of one zone-2 directional reference edge.
    ///
    /// @param requestedLength the requested available reference length, or a negative value for default
    /// @param defaultLength the full edge length when availability is not explicitly constrained
    /// @return the bounded number of available reference samples
    private int directionalReferenceAvailability(int requestedLength, int defaultLength) {
        if (requestedLength < 0) {
            return defaultLength;
        }
        return clamp(requestedLength, 0, defaultLength);
    }

    /// Returns the fallback top-left predictor sample for one block origin with tile boundaries.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param defaultSample the frame-edge default sample
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @return the fallback top-left predictor sample for one block origin with tile boundaries
    private int defaultTopLeft(
            MutableSamplePlane plane,
            int x,
            int y,
            int defaultSample,
            int leftBoundary,
            int topBoundary
    ) {
        if (x > leftBoundary) {
            return y > topBoundary ? plane.sample(x - 1, y - 1) : plane.sample(x - 1, y);
        }
        if (y > topBoundary) {
            return plane.sample(x, y - 1);
        }
        return defaultSample;
    }

    /// Reconstructs one DC-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples, including the smooth right-edge reference when clipped
    /// @param left the left reference samples, including the smooth bottom-edge reference when clipped
    /// @param defaultSample the frame-edge default sample
    private void predictDc(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left,
            int defaultSample,
            int leftBoundary,
            int topBoundary
    ) {
        int value = dcPredictionValue(width, height, top, left, defaultSample, y > topBoundary, x > leftBoundary);
        plane.fillBlock(x, y, width, height, value);
    }

    /// Returns the stable DC predictor value for one block with tile-boundary availability.
    ///
    /// @param plane the destination plane that supplies already reconstructed neighbors
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    /// @return the stable DC predictor value for one block with tile-boundary availability
    private int dcPredictionValue(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        int defaultSample = 1 << (plane.bitDepth() - 1);
        int[] top = topReferenceSamples(plane, x, y, width, defaultSample, leftBoundary, topBoundary, rightBoundary, bottomBoundary);
        int[] left = leftReferenceSamples(plane, x, y, height, defaultSample, leftBoundary, topBoundary, rightBoundary, bottomBoundary);
        return dcPredictionValue(width, height, top, left, defaultSample, y > topBoundary, x > leftBoundary);
    }

    /// Returns the stable DC predictor value for one block using caller-supplied edge samples and
    /// explicit availability flags.
    ///
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    /// @param defaultSample the frame-edge default sample
    /// @param haveTop whether the top edge is available
    /// @param haveLeft whether the left edge is available
    /// @return the stable DC predictor value for one block
    private int dcPredictionValue(
            int width,
            int height,
            int[] top,
            int[] left,
            int defaultSample,
            boolean haveTop,
            boolean haveLeft
    ) {
        if (haveTop && haveLeft) {
            int sum = 0;
            for (int sample : top) {
                sum += sample;
            }
            for (int sample : left) {
                sum += sample;
            }
            return (sum + ((width + height) >> 1)) / (width + height);
        }
        if (haveTop) {
            int sum = 0;
            for (int sample : top) {
                sum += sample;
            }
            return (sum + (width >> 1)) / width;
        }
        if (haveLeft) {
            int sum = 0;
            for (int sample : left) {
                sum += sample;
            }
            return (sum + (height >> 1)) / height;
        }
        return defaultSample;
    }

    /// Returns the signed CFL AC buffer for one subsampled chroma block.
    ///
    /// The returned buffer has one entry per chroma sample in raster order and accepts the
    /// subsampling shifts used by `YUV420`, `YUV422`, and `YUV444`.
    ///
    /// @param lumaPlane the already reconstructed luma plane
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param chromaWidth the chroma block width in samples
    /// @param chromaHeight the chroma block height in samples
    /// @param storedWidth the width populated by reconstructed luma before right-edge padding
    /// @param storedHeight the height populated by reconstructed luma before bottom-edge padding
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @return the signed CFL AC buffer for one subsampled chroma block
    private int[] cflAc(
            MutableSamplePlane lumaPlane,
            int lumaX,
            int lumaY,
            int chromaWidth,
            int chromaHeight,
            int storedWidth,
            int storedHeight,
            int subsamplingX,
            int subsamplingY
    ) {
        int[] ac = new int[chromaWidth * chromaHeight];
        int horizontalSpan = 1 << subsamplingX;
        int verticalSpan = 1 << subsamplingY;
        int valueShift = 3 - subsamplingX - subsamplingY;
        for (int row = 0; row < storedHeight; row++) {
            int rowOffset = row * chromaWidth;
            int sourceY = lumaY + (row << subsamplingY);
            for (int column = 0; column < storedWidth; column++) {
                int sourceX = lumaX + (column << subsamplingX);
                int acSum = 0;
                for (int sampleY = 0; sampleY < verticalSpan; sampleY++) {
                    for (int sampleX = 0; sampleX < horizontalSpan; sampleX++) {
                        acSum += lumaPlane.sample(sourceX + sampleX, sourceY + sampleY);
                    }
                }
                ac[rowOffset + column] = acSum << valueShift;
            }
            int lastValue = ac[rowOffset + storedWidth - 1];
            for (int column = storedWidth; column < chromaWidth; column++) {
                ac[rowOffset + column] = lastValue;
            }
        }
        for (int row = storedHeight; row < chromaHeight; row++) {
            int rowOffset = row * chromaWidth;
            int previousRowOffset = rowOffset - chromaWidth;
            System.arraycopy(ac, previousRowOffset, ac, rowOffset, chromaWidth);
        }

        int sum = 1 << (Integer.numberOfTrailingZeros(chromaWidth) + Integer.numberOfTrailingZeros(chromaHeight) - 1);
        for (int value : ac) {
            sum += value;
        }
        int shift = Integer.numberOfTrailingZeros(chromaWidth) + Integer.numberOfTrailingZeros(chromaHeight);
        int average = sum >> shift;
        for (int i = 0; i < ac.length; i++) {
            ac[i] -= average;
        }
        return ac;
    }

    /// Applies the sign of one source value to one magnitude.
    ///
    /// @param magnitude the unsigned magnitude
    /// @param signedSource the value whose sign should be copied
    /// @return the signed magnitude
    private int applySign(int magnitude, int signedSource) {
        return signedSource < 0 ? -magnitude : magnitude;
    }

    /// Returns one reconstructed sample with right and bottom buffer-edge extension.
    ///
    /// @param plane the source plane
    /// @param x the requested horizontal coordinate
    /// @param y the requested vertical coordinate
    /// @return the nearest in-buffer sample
    private int edgeExtendedSample(MutableSamplePlane plane, int x, int y) {
        int clampedX = Math.max(0, Math.min(x, plane.width() - 1));
        int clampedY = Math.max(0, Math.min(y, plane.height() - 1));
        return plane.sample(clampedX, clampedY);
    }

    /// Stores one predicted sample when it lies inside the destination plane.
    ///
    /// Intra prediction is evaluated over the coded block size. Right and bottom frame-edge blocks
    /// may therefore generate samples outside the visible plane; AV1 simply clips those writes.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param value the predicted sample value
    private void setSampleIfInside(MutableSamplePlane plane, int x, int y, int value) {
        if (x >= 0 && x < plane.width() && y >= 0 && y < plane.height()) {
            plane.setSample(x, y, value);
        }
    }

    /// Reconstructs one vertical-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    private void predictVertical(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int[] top
    ) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                setSampleIfInside(plane, x + column, y + row, top[column]);
            }
        }
    }

    /// Reconstructs one horizontal-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param left the left reference samples
    private void predictHorizontal(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int[] left
    ) {
        for (int row = 0; row < height; row++) {
            int value = left[row];
            for (int column = 0; column < width; column++) {
                setSampleIfInside(plane, x + column, y + row, value);
            }
        }
    }

    /// Reconstructs one Paeth-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    /// @param topLeft the top-left reference sample
    private void predictPaeth(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left,
            int topLeft
    ) {
        for (int row = 0; row < height; row++) {
            int leftValue = left[row];
            for (int column = 0; column < width; column++) {
                int topValue = top[column];
                int base = leftValue + topValue - topLeft;
                int leftDiff = Math.abs(leftValue - base);
                int topDiff = Math.abs(topValue - base);
                int topLeftDiff = Math.abs(topLeft - base);
                int predicted = leftDiff <= topDiff && leftDiff <= topLeftDiff
                        ? leftValue
                        : topDiff <= topLeftDiff ? topValue : topLeft;
                setSampleIfInside(plane, x + column, y + row, predicted);
            }
        }
    }

    /// Reconstructs one smooth-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    private void predictSmooth(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left
    ) {
        int[] horizontalWeights = smoothWeights(top.length);
        int[] verticalWeights = smoothWeights(left.length);
        int right = top[horizontalWeights.length - 1];
        int bottom = left[verticalWeights.length - 1];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int predicted = verticalWeights[row] * top[column]
                        + (256 - verticalWeights[row]) * bottom
                        + horizontalWeights[column] * left[row]
                        + (256 - horizontalWeights[column]) * right;
                setSampleIfInside(plane, x + column, y + row, (predicted + 256) >> 9);
            }
        }
    }

    /// Reconstructs one vertically smoothed block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples, including the smooth bottom-edge reference when clipped
    private void predictSmoothVertical(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left
    ) {
        int[] verticalWeights = smoothWeights(left.length);
        int bottom = left[verticalWeights.length - 1];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int predicted = verticalWeights[row] * top[column]
                        + (256 - verticalWeights[row]) * bottom;
                setSampleIfInside(plane, x + column, y + row, (predicted + 128) >> 8);
            }
        }
    }

    /// Reconstructs one horizontally smoothed block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples, including the smooth right-edge reference when clipped
    /// @param left the left reference samples
    private void predictSmoothHorizontal(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left
    ) {
        int[] horizontalWeights = smoothWeights(top.length);
        int right = top[horizontalWeights.length - 1];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int predicted = horizontalWeights[column] * left[row]
                        + (256 - horizontalWeights[column]) * right;
                setSampleIfInside(plane, x + column, y + row, (predicted + 128) >> 8);
            }
        }
    }

    /// Returns one top-edge directional reference buffer with tile-bounded top-right extension.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param defaultSample the frame-edge default sample
    /// @param length the required reference-buffer length
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    /// @return one top-edge directional reference buffer with tile-bounded top-right extension
    private int[] topDirectionalReferences(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int defaultSample,
            int length,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        return topReferenceSamples(plane, x, y, length, defaultSample, leftBoundary, topBoundary, rightBoundary, bottomBoundary);
    }

    /// Returns one left-edge directional reference buffer with tile-bounded bottom-left extension.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param defaultSample the frame-edge default sample
    /// @param length the required reference-buffer length
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    /// @return one left-edge directional reference buffer with tile-bounded bottom-left extension
    private int[] leftDirectionalReferences(
            MutableSamplePlane plane,
            int x,
            int y,
            int width,
            int height,
            int defaultSample,
            int length,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        return leftReferenceSamples(plane, x, y, length, defaultSample, leftBoundary, topBoundary, rightBoundary, bottomBoundary);
    }

    /// Returns one top-edge reference buffer with AV1 tile-edge fallback and right extension.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param length the required reference-buffer length
    /// @param defaultSample the midpoint frame-edge default sample
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    /// @return one top-edge reference buffer with AV1 tile-edge fallback and right extension
    private int[] topReferenceSamples(
            MutableSamplePlane plane,
            int x,
            int y,
            int length,
            int defaultSample,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        int[] references = referenceBuffer(TOP_REFERENCE_BANK, length);
        if (y <= topBoundary) {
            int sample = x > leftBoundary ? plane.sample(x - 1, y) : defaultSample - 1;
            fillReferences(references, sample);
            return references;
        }
        int maxX = Math.max(0, Math.min(rightBoundary, plane.width()) - 1);
        for (int i = 0; i < references.length; i++) {
            int sampleX = x + i;
            if (sampleX > maxX) {
                sampleX = maxX;
            }
            references[i] = plane.sample(sampleX, y - 1);
        }
        return references;
    }

    /// Returns one left-edge reference buffer with AV1 tile-edge fallback and bottom extension.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param length the required reference-buffer length
    /// @param defaultSample the midpoint frame-edge default sample
    /// @param leftBoundary the first sample column available to this tile
    /// @param topBoundary the first sample row available to this tile
    /// @param rightBoundary the exclusive sample column available to this tile
    /// @param bottomBoundary the exclusive sample row available to this tile
    /// @return one left-edge reference buffer with AV1 tile-edge fallback and bottom extension
    private int[] leftReferenceSamples(
            MutableSamplePlane plane,
            int x,
            int y,
            int length,
            int defaultSample,
            int leftBoundary,
            int topBoundary,
            int rightBoundary,
            int bottomBoundary
    ) {
        int[] references = referenceBuffer(LEFT_REFERENCE_BANK, length);
        if (x <= leftBoundary) {
            int sample = y > topBoundary ? plane.sample(x, y - 1) : defaultSample + 1;
            fillReferences(references, sample);
            return references;
        }
        int maxY = Math.max(0, Math.min(bottomBoundary, plane.height()) - 1);
        for (int i = 0; i < references.length; i++) {
            int sampleY = y + i;
            if (sampleY > maxY) {
                sampleY = maxY;
            }
            references[i] = plane.sample(x - 1, sampleY);
        }
        return references;
    }

    /// Fills a reference buffer with one sample.
    ///
    /// @param references the reference buffer to fill
    /// @param sample the sample value to store
    private void fillReferences(int[] references, int sample) {
        for (int i = 0; i < references.length; i++) {
            references[i] = sample;
        }
    }

    /// Returns whether an intra directional edge should be upsampled before interpolation.
    ///
    /// @param edgeSpan the sum of the prediction block width and height
    /// @param edgeAngle the acute angle between the prediction ray and the sampled reference edge
    /// @param smoothEdgeReferences whether neighboring smooth predictors reduce filtering strength
    /// @return whether the edge should be upsampled
    private boolean useDirectionalEdgeUpsample(int edgeSpan, int edgeAngle, boolean smoothEdgeReferences) {
        return edgeAngle < 40 && edgeSpan <= (16 >> (smoothEdgeReferences ? 1 : 0));
    }

    /// Returns the AV1 intra-edge filter strength for one directional edge.
    ///
    /// @param edgeSpan the sum of the prediction block width and height
    /// @param edgeAngle the acute angle between the prediction ray and the sampled reference edge
    /// @param smoothEdgeReferences whether neighboring smooth predictors reduce filtering strength
    /// @return the filter strength in `[0, 3]`
    private int directionalEdgeFilterStrength(int edgeSpan, int edgeAngle, boolean smoothEdgeReferences) {
        if (smoothEdgeReferences) {
            if (edgeSpan <= 8) {
                if (edgeAngle >= 64) {
                    return 2;
                }
                if (edgeAngle >= 40) {
                    return 1;
                }
            } else if (edgeSpan <= 16) {
                if (edgeAngle >= 48) {
                    return 2;
                }
                if (edgeAngle >= 20) {
                    return 1;
                }
            } else if (edgeSpan <= 24) {
                if (edgeAngle >= 4) {
                    return 3;
                }
            } else {
                return 3;
            }
        } else {
            if (edgeSpan <= 8) {
                if (edgeAngle >= 56) {
                    return 1;
                }
            } else if (edgeSpan <= 16) {
                if (edgeAngle >= 40) {
                    return 1;
                }
            } else if (edgeSpan <= 24) {
                if (edgeAngle >= 32) {
                    return 3;
                }
                if (edgeAngle >= 16) {
                    return 2;
                }
                if (edgeAngle >= 8) {
                    return 1;
                }
            } else if (edgeSpan <= 32) {
                if (edgeAngle >= 32) {
                    return 3;
                }
                if (edgeAngle >= 4) {
                    return 2;
                }
                return 1;
            } else {
                return 3;
            }
        }
        return 0;
    }

    /// Returns the filtered top-left corner sample used by AV1 zone-2 directional prediction.
    ///
    /// @param topLeft the original shared top-left reference sample
    /// @param topReference the first top-edge reference sample
    /// @param leftReference the first left-edge reference sample
    /// @return the filtered shared top-left corner sample
    private int filterDirectionalEdgeCorner(int topLeft, int topReference, int leftReference) {
        return (leftReference * 5 + topLeft * 6 + topReference * 5 + 8) >> 4;
    }

    /// Returns a filtered directional reference edge.
    ///
    /// The source edge uses conceptual index `-1` for the top-left sample and non-negative indices
    /// for the top or left samples extending away from it.
    ///
    /// @param references the edge references excluding the top-left sample
    /// @param topLeft the top-left reference sample
    /// @param outputLength the filtered output length
    /// @param limitFrom the first output index that may be filtered
    /// @param limitTo the exclusive last output index that may be filtered
    /// @param from the inclusive source clamp lower bound
    /// @param to the exclusive source clamp upper bound
    /// @param strength the AV1 intra-edge filter strength
    /// @param bufferBank the workspace bank that owns the returned edge
    /// @return the filtered directional reference edge
    private int[] filterDirectionalEdge(
            int[] references,
            int topLeft,
            int outputLength,
            int limitFrom,
            int limitTo,
            int from,
            int to,
            int strength,
            int bufferBank
    ) {
        int[] output = referenceBuffer(bufferBank, outputLength);
        int[] kernel = INTRA_EDGE_FILTER_KERNELS[strength - 1];
        int index = 0;
        for (; index < Math.min(outputLength, limitFrom); index++) {
            output[index] = directionalEdgeSourceSample(references, topLeft, -1, clamp(index, from, to - 1));
        }
        for (; index < Math.min(outputLength, limitTo); index++) {
            int sum = 0;
            for (int tap = 0; tap < kernel.length; tap++) {
                int sourceIndex = clamp(index - 2 + tap, from, to - 1);
                sum += directionalEdgeSourceSample(references, topLeft, -1, sourceIndex) * kernel[tap];
            }
            output[index] = (sum + 8) >> 4;
        }
        for (; index < outputLength; index++) {
            output[index] = directionalEdgeSourceSample(references, topLeft, -1, clamp(index, from, to - 1));
        }
        return output;
    }

    /// Returns an upsampled directional reference edge.
    ///
    /// @param references the edge references excluding the top-left sample
    /// @param topLeft the top-left reference sample
    /// @param outputEvenCount the number of original even-position samples to expose
    /// @param from the inclusive source clamp lower bound
    /// @param to the exclusive source clamp upper bound
    /// @param bitDepth the decoded sample bit depth
    /// @param includeTopLeft whether conceptual source index `0` addresses the top-left sample
    /// @param bufferBank the workspace bank that owns the returned edge
    /// @return the upsampled directional reference edge
    private int[] upsampleDirectionalEdge(
            int[] references,
            int topLeft,
            int outputEvenCount,
            int from,
            int to,
            int bitDepth,
            boolean includeTopLeft,
            int bufferBank
    ) {
        int[] output = referenceBuffer(bufferBank, outputEvenCount * 2 - 1);
        int topLeftIndex = includeTopLeft ? 0 : -1;
        int maximumSample = (1 << bitDepth) - 1;
        int index = 0;
        for (; index < outputEvenCount - 1; index++) {
            output[index << 1] = directionalEdgeSourceSample(
                    references,
                    topLeft,
                    topLeftIndex,
                    clamp(index, from, to - 1)
            );
            int sum = 0;
            for (int tap = 0; tap < INTRA_EDGE_UPSAMPLE_KERNEL.length; tap++) {
                int sourceIndex = clamp(index + tap - 1, from, to - 1);
                sum += directionalEdgeSourceSample(references, topLeft, topLeftIndex, sourceIndex)
                        * INTRA_EDGE_UPSAMPLE_KERNEL[tap];
            }
            output[(index << 1) + 1] = clamp((sum + 8) >> 4, 0, maximumSample);
        }
        output[index << 1] = directionalEdgeSourceSample(
                references,
                topLeft,
                topLeftIndex,
                clamp(index, from, to - 1)
        );
        return output;
    }

    /// Returns an edge array whose first entry is the top-left reference sample.
    ///
    /// @param references the top or left references excluding the top-left sample
    /// @param topLeft the top-left reference sample
    /// @param bufferBank the workspace bank that owns the returned edge
    /// @return a reference edge with the top-left sample at index `0`
    private int[] edgeWithTopLeft(int[] references, int topLeft, int bufferBank) {
        int[] edge = referenceBuffer(bufferBank, references.length + 1);
        edge[0] = topLeft;
        System.arraycopy(references, 0, edge, 1, references.length);
        return edge;
    }

    /// Returns one sample from an edge array, extending the outermost entries.
    ///
    /// @param edge the edge array to read
    /// @param index the requested conceptual edge index
    /// @return the edge sample at the requested index
    private int edgeSample(int[] edge, int index) {
        if (index <= 0) {
            return edge[0];
        }
        if (index >= edge.length) {
            return edge[edge.length - 1];
        }
        return edge[index];
    }

    /// Returns one conceptual directional-edge source sample.
    ///
    /// @param references the edge references excluding the top-left sample
    /// @param topLeft the top-left reference sample
    /// @param topLeftIndex the conceptual source index that addresses the top-left sample
    /// @param index the requested conceptual source index
    /// @return the requested directional-edge source sample
    private int directionalEdgeSourceSample(int[] references, int topLeft, int topLeftIndex, int index) {
        if (index == topLeftIndex) {
            return topLeft;
        }
        int referenceIndex = index - topLeftIndex - 1;
        if (referenceIndex <= 0) {
            return references[0];
        }
        if (referenceIndex >= references.length) {
            return references[references.length - 1];
        }
        return references[referenceIndex];
    }

    /// Returns one directional interpolation result between two edge samples.
    ///
    /// @param sample0 the first edge sample
    /// @param sample1 the second edge sample
    /// @param fraction the AV1 fractional interpolation position in `[0, 62]`
    /// @return one directional interpolation result between two edge samples
    private int interpolate(int sample0, int sample1, int fraction) {
        return (sample0 * (64 - fraction) + sample1 * fraction + 32) >> 6;
    }

    /// Returns the AV1 directional interpolation fraction for one projected coordinate.
    ///
    /// @param projectedCoordinate the signed projected coordinate in 1/64 sample units
    /// @param upsampled whether the sampled edge has been upsampled by two
    /// @return the interpolation fraction in `[0, 62]`
    private int directionalFraction(int projectedCoordinate, boolean upsampled) {
        return (projectedCoordinate << (upsampled ? 1 : 0)) & 0x3E;
    }

    /// Clamps one integer to an inclusive range.
    ///
    /// @param value the value to clamp
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Returns one exact-length reference buffer owned by this predictor.
    ///
    /// @param bank the independently reusable workspace bank
    /// @param length the exact required buffer length
    /// @return the reusable reference buffer
    private int[] referenceBuffer(int bank, int length) {
        Workspace workspace = predictionWorkspace;
        if (workspace == null) {
            workspace = new Workspace();
            predictionWorkspace = workspace;
        }
        return workspace.referenceBuffer(bank, length);
    }

    /// Returns the smooth-predictor weight array for one supported block dimension.
    ///
    /// @param size the block width or height in samples
    /// @return the smooth-predictor weight array for one supported block dimension
    private int[] smoothWeights(int size) {
        return switch (size) {
            case 1 -> SMOOTH_WEIGHTS_1;
            case 2 -> SMOOTH_WEIGHTS_2;
            case 4 -> SMOOTH_WEIGHTS_4;
            case 8 -> SMOOTH_WEIGHTS_8;
            case 16 -> SMOOTH_WEIGHTS_16;
            case 32 -> SMOOTH_WEIGHTS_32;
            case 64 -> SMOOTH_WEIGHTS_64;
            default -> throw new IllegalStateException("Unsupported smooth predictor size: " + size);
        };
    }

    /// Returns the coded smooth-prediction reference axis for one visible edge length.
    ///
    /// Smooth prediction weights are tabled by coded block axis. A clipped right or bottom edge may
    /// expose fewer visible samples, so reconstruction uses the next legal smooth axis and writes
    /// only the visible footprint.
    ///
    /// @param visibleSize the visible edge length in samples
    /// @return the smooth-prediction axis used to select weights and the far-edge reference
    private int smoothWeightAxisSize(int visibleSize) {
        if (visibleSize <= 1) {
            return 1;
        }
        if (visibleSize <= 2) {
            return 2;
        }
        if (visibleSize <= 4) {
            return 4;
        }
        if (visibleSize <= 8) {
            return 8;
        }
        if (visibleSize <= 16) {
            return 16;
        }
        if (visibleSize <= 32) {
            return 32;
        }
        if (visibleSize <= 64) {
            return 64;
        }
        throw new IllegalStateException("Unsupported smooth predictor size: " + visibleSize);
    }

    /// Returns one AV1 directional derivative table entry.
    ///
    /// @param halfAngleIndex the zero-based half-angle table index
    /// @return one AV1 directional derivative table entry
    private int directionalDerivative(int halfAngleIndex) {
        if (halfAngleIndex < 0 || halfAngleIndex >= DIRECTIONAL_DERIVATIVES.length) {
            throw new IllegalStateException("Directional derivative index out of range: " + halfAngleIndex);
        }
        return DIRECTIONAL_DERIVATIVES[halfAngleIndex];
    }

    /// Lazily allocated reference arrays separated by simultaneous prediction role and length.
    
    private static final class Workspace {
        /// Nullable exact-length buffers indexed first by bank and then by buffer length.
        private final int [][][] referenceBuffers =
                new int[REFERENCE_BUFFER_BANK_COUNT][MAX_REFERENCE_BUFFER_LENGTH + 1][];

        /// Creates an initially empty prediction workspace.
        private Workspace() {
        }

        /// Returns one exact-length buffer from an independently reusable bank.
        ///
        /// @param bank the zero-based buffer bank
        /// @param length the exact required buffer length
        /// @return the selected reusable buffer
        private int[] referenceBuffer(int bank, int length) {
            if (bank < 0 || bank >= referenceBuffers.length) {
                throw new IllegalArgumentException("Reference buffer bank out of range: " + bank);
            }
            if (length <= 0 || length > MAX_REFERENCE_BUFFER_LENGTH) {
                throw new IllegalArgumentException("Reference buffer length out of range: " + length);
            }
            int [] buffer = referenceBuffers[bank][length];
            if (buffer == null) {
                buffer = new int[length];
                referenceBuffers[bank][length] = buffer;
            }
            return buffer;
        }
    }

    /// Internal intra-prediction kernel identifiers.
    
    private enum PredictionMode {
        /// DC prediction.
        DC,

        /// Vertical prediction.
        VERTICAL,

        /// Horizontal prediction.
        HORIZONTAL,

        /// Diagonal-down-left directional prediction.
        DIAGONAL_DOWN_LEFT,

        /// Diagonal-down-right directional prediction.
        DIAGONAL_DOWN_RIGHT,

        /// Vertical-right directional prediction.
        VERTICAL_RIGHT,

        /// Horizontal-down directional prediction.
        HORIZONTAL_DOWN,

        /// Horizontal-up directional prediction.
        HORIZONTAL_UP,

        /// Vertical-left directional prediction.
        VERTICAL_LEFT,

        /// Paeth prediction.
        PAETH,

        /// Smooth prediction.
        SMOOTH,

        /// Smooth vertical prediction.
        SMOOTH_VERTICAL,

        /// Smooth horizontal prediction.
        SMOOTH_HORIZONTAL;

        /// Returns whether this prediction mode currently routes through the directional predictor.
        ///
        /// @param angleDelta the signed directional angle delta
        /// @return whether this prediction mode currently routes through the directional predictor
        private boolean usesDirectionalPrediction(int angleDelta) {
            if (angleDelta != 0 && (this == VERTICAL || this == HORIZONTAL)) {
                return true;
            }
            return switch (this) {
                case DIAGONAL_DOWN_LEFT,
                        DIAGONAL_DOWN_RIGHT,
                        VERTICAL_RIGHT,
                        HORIZONTAL_DOWN,
                        HORIZONTAL_UP,
                        VERTICAL_LEFT -> true;
                default -> false;
            };
        }

        /// Returns whether this mode needs a smooth horizontal axis for clipped-edge prediction.
        ///
        /// @return whether this mode needs a smooth horizontal axis for clipped-edge prediction
        private boolean usesHorizontalSmoothReference() {
            return this == SMOOTH || this == SMOOTH_HORIZONTAL;
        }

        /// Returns whether this mode needs a smooth vertical axis for clipped-edge prediction.
        ///
        /// @return whether this mode needs a smooth vertical axis for clipped-edge prediction
        private boolean usesVerticalSmoothReference() {
            return this == SMOOTH || this == SMOOTH_VERTICAL;
        }

        /// Returns the AV1 directional base angle for one directional-capable mode.
        ///
        /// @return the AV1 directional base angle for one directional-capable mode
        private int directionalBaseAngle() {
            return switch (this) {
                case VERTICAL -> DIRECTIONAL_BASE_ANGLES[0];
                case HORIZONTAL -> DIRECTIONAL_BASE_ANGLES[1];
                case DIAGONAL_DOWN_LEFT -> DIRECTIONAL_BASE_ANGLES[2];
                case DIAGONAL_DOWN_RIGHT -> DIRECTIONAL_BASE_ANGLES[3];
                case VERTICAL_RIGHT -> DIRECTIONAL_BASE_ANGLES[4];
                case HORIZONTAL_DOWN -> DIRECTIONAL_BASE_ANGLES[5];
                case HORIZONTAL_UP -> DIRECTIONAL_BASE_ANGLES[6];
                case VERTICAL_LEFT -> DIRECTIONAL_BASE_ANGLES[7];
                default -> throw new IllegalStateException("Mode does not use directional prediction: " + this);
            };
        }
    }
}
