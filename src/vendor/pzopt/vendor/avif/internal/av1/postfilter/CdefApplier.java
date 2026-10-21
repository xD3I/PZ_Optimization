// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.postfilter;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import pzopt.vendor.avif.internal.av1.decode.TilePartitionTreeReader;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;

import java.util.Arrays;
import java.util.Objects;

/// Applies the AV1 CDEF stage of the postfilter pipeline.
///
/// The implementation mirrors the dav1d CDEF scalar path at decoded-plane granularity: strength
/// values are scaled by bit depth, dominant directions are detected from luma 8x8 units, chroma
/// primary filtering reuses the luma direction, stored edge padding participates up to the aligned
/// CDEF processing boundary, and fully skipped CDEF units are preserved.

final class CdefApplier {
    /// Prevents instantiation of this stateless filter.
    private CdefApplier() {
    }

    /// The luma CDEF unit size in samples.
    private static final int CDEF_UNIT_SIZE = 8;

    /// Sentinel used for samples beyond the aligned CDEF processing grid.
    private static final int MISSING_SAMPLE = Integer.MIN_VALUE;

    /// Direction-search divisors copied from the AV1 CDEF direction estimator.
    private static final int [] DIRECTION_DIVISORS = {840, 420, 280, 210, 168, 140, 120};

    /// Direction vectors for the two primary or secondary CDEF tap distances.
    private static final int [] [] [] FILTER_DIRECTIONS = {
            {{1, -1}, {2, -2}},
            {{1, 0}, {2, -1}},
            {{1, 0}, {2, 0}},
            {{1, 0}, {2, 1}},
            {{1, 1}, {2, 2}},
            {{0, 1}, {1, 2}},
            {{0, 1}, {0, 2}},
            {{0, 1}, {-1, 2}}
    };

    /// Chroma direction remapping used by AV1 for `YUV422`.
    private static final int [] I422_UV_DIRECTIONS = {7, 0, 2, 4, 5, 6, 6, 6};

    /// Applies CDEF to one decoded frame using block-level syntax for CDEF index selection.
    ///
    /// @param decodedPlanes the decoded planes after loop filtering
    /// @param cdef the normalized frame-level CDEF state
    /// @param syntaxDecodeResult the decoded block syntax that carries CDEF indices, or `null`
    /// @return the post-CDEF planes
    static DecodedSurface apply(
            DecodedSurface decodedPlanes,
            FrameHeader.CdefInfo cdef,
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        return applyPrepared(
                checkedDecodedPlanes,
                prepare(checkedDecodedPlanes, Objects.requireNonNull(cdef, "cdef"), syntaxDecodeResult)
        );
    }

    /// Extracts the compact frame state required while applying CDEF.
    ///
    /// The returned state does not retain `syntaxDecodeResult`. Callers may therefore release the
    /// complete frame syntax tree before [#applyPrepared(DecodedSurface, PreparedApplication)]
    /// allocates destination planes.
    ///
    /// @param decodedPlanes the decoded planes after loop filtering
    /// @param cdef the normalized frame-level CDEF state
    /// @param syntaxDecodeResult the decoded block syntax that carries CDEF indices, or `null`
    /// @return the compact prepared CDEF state
    static PreparedApplication prepare(
            DecodedSurface decodedPlanes,
            FrameHeader.CdefInfo cdef,
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader.CdefInfo checkedCdef = Objects.requireNonNull(cdef, "cdef");
        int[] yStrengths = checkedCdef.yStrengths();
        int[] uvStrengths = checkedCdef.uvStrengths();
        if (!hasActiveStrengths(yStrengths, uvStrengths, checkedDecodedPlanes.hasChroma())) {
            return PreparedApplication.inactive(checkedDecodedPlanes);
        }
        if (syntaxDecodeResult == null) {
            throw new IllegalStateException("Active AV1 CDEF filtering requires decoded block CDEF indices");
        }

        int unitColumns = cdefUnitCount(checkedDecodedPlanes.codedWidth());
        int unitRows = cdefUnitCount(checkedDecodedPlanes.codedHeight());
        CdefUnitMap cdefUnitMap = buildCdefUnitMap(syntaxDecodeResult, unitColumns, unitRows);
        int bitDepthShift = checkedDecodedPlanes.bitDepth() - 8;
        int damping = checkedCdef.damping() + bitDepthShift;
        return new PreparedApplication(
                checkedDecodedPlanes,
                yStrengths,
                uvStrengths,
                unitColumns,
                unitRows,
                bitDepthShift,
                damping,
                cdefUnitMap
        );
    }

    /// Applies one previously prepared CDEF operation.
    ///
    /// @param decodedPlanes the same loop-filtered surface used to prepare the operation
    /// @param preparedApplication the compact prepared CDEF state
    /// @return the post-CDEF planes
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

        int[] yStrengths = prepared.yStrengths;
        int[] uvStrengths = prepared.uvStrengths;
        int unitColumns = prepared.unitColumns;
        int unitRows = prepared.unitRows;
        int bitDepthShift = prepared.bitDepthShift;
        int damping = prepared.damping;
        CdefUnitMap cdefUnitMap = Objects.requireNonNull(prepared.cdefUnitMap, "cdefUnitMap");
        CdefDirectionMap directionMap = buildDirectionMap(
                checkedDecodedPlanes.lumaPlane(),
                checkedDecodedPlanes.bitDepth(),
                yStrengths,
                uvStrengths,
                cdefUnitMap,
                unitColumns,
                unitRows,
                unitColumns * CDEF_UNIT_SIZE,
                unitRows * CDEF_UNIT_SIZE,
                checkedDecodedPlanes.hasChroma(),
                new CdefWorkspace()
        );

        PaddedPlane lumaPlane = applyPlane(
                checkedDecodedPlanes.lumaPlane(),
                bitDepthShift,
                damping,
                yStrengths,
                cdefUnitMap,
                directionMap,
                unitColumns,
                unitRows,
                CDEF_UNIT_SIZE,
                CDEF_UNIT_SIZE,
                true,
                false
        );
        PaddedPlane chromaUPlane = checkedDecodedPlanes.chromaUPlane();
        PaddedPlane chromaVPlane = checkedDecodedPlanes.chromaVPlane();
        if (checkedDecodedPlanes.hasChroma()) {
            int chromaUnitWidth = Math.max(
                    1,
                    CDEF_UNIT_SIZE >> chromaSubsamplingX(checkedDecodedPlanes.chromaFormat())
            );
            int chromaUnitHeight = Math.max(
                    1,
                    CDEF_UNIT_SIZE >> chromaSubsamplingY(checkedDecodedPlanes.chromaFormat())
            );
            boolean i422Chroma = checkedDecodedPlanes.chromaFormat() == Av1ChromaFormat.YUV422;
            chromaUPlane = applyPlane(
                    Objects.requireNonNull(chromaUPlane, "chromaUPlane"),
                    bitDepthShift,
                    damping - 1,
                    uvStrengths,
                    cdefUnitMap,
                    directionMap,
                    unitColumns,
                    unitRows,
                    chromaUnitWidth,
                    chromaUnitHeight,
                    false,
                    i422Chroma
            );
            chromaVPlane = applyPlane(
                    Objects.requireNonNull(chromaVPlane, "chromaVPlane"),
                    bitDepthShift,
                    damping - 1,
                    uvStrengths,
                    cdefUnitMap,
                    directionMap,
                    unitColumns,
                    unitRows,
                    chromaUnitWidth,
                    chromaUnitHeight,
                    false,
                    i422Chroma
            );
        }

        if (lumaPlane == checkedDecodedPlanes.lumaPlane()
                && chromaUPlane == checkedDecodedPlanes.chromaUPlane()
                && chromaVPlane == checkedDecodedPlanes.chromaVPlane()) {
            return checkedDecodedPlanes;
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

    /// Returns whether any CDEF strength can change visible samples.
    ///
    /// @param yStrengths the encoded luma CDEF strength table
    /// @param uvStrengths the encoded chroma CDEF strength table
    /// @param hasChroma whether the frame has chroma planes
    /// @return whether any CDEF strength can change visible samples
    private static boolean hasActiveStrengths(int[] yStrengths, int[] uvStrengths, boolean hasChroma) {
        return hasActiveStrength(yStrengths) || (hasChroma && hasActiveStrength(uvStrengths));
    }

    /// Returns whether any encoded strength contains a non-zero primary or secondary component.
    ///
    /// @param strengths the encoded CDEF strength table
    /// @return whether any encoded strength is non-zero
    private static boolean hasActiveStrength(int[] strengths) {
        for (int strength : strengths) {
            if (strength != 0) {
                return true;
            }
        }
        return false;
    }

    /// Builds the luma CDEF-unit map from decoded partition-tree leaves.
    ///
    /// @param syntaxDecodeResult the decoded block syntax that carries CDEF indices
    /// @param unitColumns the luma CDEF-unit column count
    /// @param unitRows the luma CDEF-unit row count
    /// @return the row-major CDEF-unit map
    private static CdefUnitMap buildCdefUnitMap(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            int unitColumns,
            int unitRows
    ) {
        int[] cdefIndices = new int[unitColumns * unitRows];
        boolean[] nonSkipUnits = new boolean[unitColumns * unitRows];
        for (int tileIndex = 0; tileIndex < syntaxDecodeResult.tileCount(); tileIndex++) {
            TilePartitionTreeReader.Node[] frameLocalRoots = syntaxDecodeResult.tileRoots(tileIndex);
            for (TilePartitionTreeReader.Node root : frameLocalRoots) {
                fillCdefUnitMap(root, cdefIndices, nonSkipUnits, unitColumns, unitRows);
            }
        }
        return new CdefUnitMap(cdefIndices, nonSkipUnits);
    }

    /// Fills CDEF-unit syntax state covered by one partition-tree node.
    ///
    /// @param node the partition-tree node to visit
    /// @param cdefIndices the mutable row-major CDEF index map
    /// @param nonSkipUnits the mutable row-major non-skip map
    /// @param unitColumns the luma CDEF-unit column count
    /// @param unitRows the luma CDEF-unit row count
    private static void fillCdefUnitMap(
            TilePartitionTreeReader.Node node,
            int[] cdefIndices,
            boolean[] nonSkipUnits,
            int unitColumns,
            int unitRows
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            int blockCdefIndex = leafNode.header().cdefIndex();
            if (blockCdefIndex < 0) {
                blockCdefIndex = 0;
            }
            int startX = leafNode.header().position().x4() << 2;
            int startY = leafNode.header().position().y4() << 2;
            int endX = startX + leafNode.transformLayout().visibleWidthPixels();
            int endY = startY + leafNode.transformLayout().visibleHeightPixels();
            int startUnitX = clamp(startX / CDEF_UNIT_SIZE, 0, unitColumns - 1);
            int startUnitY = clamp(startY / CDEF_UNIT_SIZE, 0, unitRows - 1);
            int endUnitX = clamp((endX + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE, 0, unitColumns);
            int endUnitY = clamp((endY + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE, 0, unitRows);
            boolean skip = leafNode.header().skip();
            for (int unitY = startUnitY; unitY < endUnitY; unitY++) {
                for (int unitX = startUnitX; unitX < endUnitX; unitX++) {
                    int unitIndex = unitY * unitColumns + unitX;
                    if (!skip) {
                        cdefIndices[unitIndex] = blockCdefIndex;
                        nonSkipUnits[unitIndex] = true;
                    } else if (!nonSkipUnits[unitIndex]) {
                        cdefIndices[unitIndex] = blockCdefIndex;
                    }
                }
            }
            return;
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (int childIndex = 0; childIndex < partitionNode.childCount(); childIndex++) {
            fillCdefUnitMap(partitionNode.child(childIndex), cdefIndices, nonSkipUnits, unitColumns, unitRows);
        }
    }

    /// Builds luma-derived direction and variance state for each CDEF unit.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param bitDepth the decoded bit depth
    /// @param yStrengths the encoded luma CDEF strength table
    /// @param uvStrengths the encoded chroma CDEF strength table
    /// @param cdefUnitMap the row-major CDEF-unit syntax map
    /// @param unitColumns the luma CDEF-unit column count
    /// @param unitRows the luma CDEF-unit row count
    /// @param processingWidth the CDEF-grid-aligned luma processing width
    /// @param processingHeight the CDEF-grid-aligned luma processing height
    /// @param hasChroma whether the frame has chroma planes
    /// @param workspace the reusable per-frame CDEF workspace
    /// @return the row-major luma-derived CDEF direction map
    private static CdefDirectionMap buildDirectionMap(
            PaddedPlane lumaPlane,
            int bitDepth,
            int[] yStrengths,
            int[] uvStrengths,
            CdefUnitMap cdefUnitMap,
            int unitColumns,
            int unitRows,
            int processingWidth,
            int processingHeight,
            boolean hasChroma,
            CdefWorkspace workspace
    ) {
        int[] directions = new int[unitColumns * unitRows];
        int[] variances = new int[unitColumns * unitRows];
        int bitDepthShift = bitDepth - 8;
        for (int unitY = 0; unitY < unitRows; unitY++) {
            int startY = unitY * CDEF_UNIT_SIZE;
            if (startY >= lumaPlane.height()) {
                continue;
            }
            for (int unitX = 0; unitX < unitColumns; unitX++) {
                int startX = unitX * CDEF_UNIT_SIZE;
                if (startX >= lumaPlane.width()) {
                    continue;
                }
                int unitIndex = unitY * unitColumns + unitX;
                if (!cdefUnitMap.nonSkip(unitIndex)) {
                    continue;
                }
                if (!requiresDirection(
                        yStrengths,
                        uvStrengths,
                        cdefUnitMap.cdefIndex(unitIndex),
                        hasChroma
                )) {
                    continue;
                }
                detectDirection(
                        lumaPlane,
                        startX,
                        startY,
                        processingWidth,
                        processingHeight,
                        bitDepthShift,
                        workspace
                );
                directions[unitIndex] = workspace.detectedDirection;
                variances[unitIndex] = workspace.detectedVariance;
            }
        }
        return new CdefDirectionMap(directions, variances);
    }

    /// Returns whether the selected CDEF strengths require luma direction detection.
    ///
    /// @param yStrengths the encoded luma CDEF strength table
    /// @param uvStrengths the encoded chroma CDEF strength table
    /// @param cdefIndex the selected CDEF index
    /// @param hasChroma whether the frame has chroma planes
    /// @return whether the selected strengths contain an active primary filter
    private static boolean requiresDirection(
            int[] yStrengths,
            int[] uvStrengths,
            int cdefIndex,
            boolean hasChroma
    ) {
        if (hasSelectedPrimaryStrength(yStrengths, cdefIndex)) {
            return true;
        }
        return hasChroma && hasSelectedPrimaryStrength(uvStrengths, cdefIndex);
    }

    /// Returns whether one selected encoded strength has an active primary component.
    ///
    /// @param strengths the encoded CDEF strength table
    /// @param cdefIndex the selected CDEF index
    /// @return whether the selected encoded strength has an active primary component
    private static boolean hasSelectedPrimaryStrength(int[] strengths, int cdefIndex) {
        if (strengths.length == 0) {
            return false;
        }
        return (strengthForIndex(strengths, cdefIndex) >> 2) != 0;
    }

    /// Applies CDEF to one plane.
    ///
    /// @param plane the source plane to filter
    /// @param bitDepthShift the decoded bit-depth shift from 8-bit samples
    /// @param damping the bit-depth-adjusted CDEF damping value
    /// @param strengths the encoded strength table for the plane
    /// @param cdefUnitMap the row-major luma CDEF-unit syntax map
    /// @param directionMap the row-major luma-derived CDEF direction map
    /// @param unitColumns the luma CDEF-unit column count
    /// @param unitRows the luma CDEF-unit row count
    /// @param unitWidth the plane-local CDEF-unit width
    /// @param unitHeight the plane-local CDEF-unit height
    /// @param luma whether this invocation filters the luma plane
    /// @param i422Chroma whether this invocation filters an `YUV422` chroma plane
    /// @return the filtered plane, or the original plane when all selected units are inactive
    private static PaddedPlane applyPlane(
            PaddedPlane plane,
            int bitDepthShift,
            int damping,
            int[] strengths,
            CdefUnitMap cdefUnitMap,
            CdefDirectionMap directionMap,
            int unitColumns,
            int unitRows,
            int unitWidth,
            int unitHeight,
            boolean luma,
            boolean i422Chroma
    ) {
        if (!hasActiveStrength(strengths)) {
            return plane;
        }
        short[] outputSamples = null;
        int maximumSample = (1 << (bitDepthShift + 8)) - 1;
        int processingWidth = Math.min(unitColumns * unitWidth, plane.stride());
        int processingHeight = Math.min(unitRows * unitHeight, plane.storageHeight());
        for (int unitY = 0; unitY < unitRows; unitY++) {
            int planeStartY = unitY * unitHeight;
            if (planeStartY >= processingHeight) {
                continue;
            }
            int planeEndY = Math.min(processingHeight, planeStartY + unitHeight);
            for (int unitX = 0; unitX < unitColumns; unitX++) {
                int planeStartX = unitX * unitWidth;
                if (planeStartX >= processingWidth) {
                    continue;
                }
                int unitIndex = unitY * unitColumns + unitX;
                if (!cdefUnitMap.nonSkip(unitIndex)) {
                    continue;
                }
                int encodedStrength = strengthForIndex(strengths, cdefUnitMap.cdefIndex(unitIndex));
                int primaryStrength = decodePrimaryStrength(encodedStrength, bitDepthShift);
                int secondaryStrength = decodeSecondaryStrength(encodedStrength, bitDepthShift);
                if (primaryStrength == 0 && secondaryStrength == 0) {
                    continue;
                }
                int direction = 0;
                if (primaryStrength != 0) {
                    direction = directionMap.direction(unitIndex);
                    if (luma) {
                        primaryStrength = adjustStrength(primaryStrength, directionMap.variance(unitIndex));
                    } else if (i422Chroma) {
                        direction = I422_UV_DIRECTIONS[direction];
                    }
                }
                if (primaryStrength == 0 && secondaryStrength == 0) {
                    continue;
                }
                if (outputSamples == null) {
                    outputSamples = plane.samples();
                }
                filterUnit(
                        plane,
                        outputSamples,
                        planeStartX,
                        planeStartY,
                        Math.min(processingWidth, planeStartX + unitWidth),
                        planeEndY,
                        processingWidth,
                        processingHeight,
                        Math.max(0, damping),
                        primaryStrength,
                        secondaryStrength,
                        direction,
                        bitDepthShift,
                        maximumSample
                );
            }
        }
        return outputSamples != null
                ? PaddedPlane.fromOwnedSamples(plane.width(), plane.height(), plane.stride(), outputSamples)
                : plane;
    }

    /// Returns one encoded CDEF strength selected by the decoded block CDEF index.
    ///
    /// @param strengths the encoded strength table
    /// @param cdefIndex the decoded block CDEF index
    /// @return the selected encoded CDEF strength
    private static int strengthForIndex(int[] strengths, int cdefIndex) {
        if (cdefIndex < 0) {
            throw new IllegalStateException("Active AV1 CDEF filtering requires a decoded CDEF index for every filtered unit");
        }
        if (cdefIndex >= strengths.length) {
            throw new IllegalStateException("Decoded AV1 CDEF index exceeds the frame strength table: " + cdefIndex);
        }
        return strengths[cdefIndex];
    }

    /// Decodes one packed primary CDEF strength and scales it for the frame bit depth.
    ///
    /// @param strength the packed AV1 CDEF strength
    /// @param bitDepthShift the decoded bit-depth shift from 8-bit samples
    /// @return the decoded primary CDEF strength
    private static int decodePrimaryStrength(int strength, int bitDepthShift) {
        return (strength >> 2) << bitDepthShift;
    }

    /// Decodes one packed secondary CDEF strength and scales it for the frame bit depth.
    ///
    /// @param strength the packed AV1 CDEF strength
    /// @param bitDepthShift the decoded bit-depth shift from 8-bit samples
    /// @return the decoded secondary CDEF strength
    private static int decodeSecondaryStrength(int strength, int bitDepthShift) {
        int secondary = strength & 3;
        if (secondary == 3) {
            secondary++;
        }
        return secondary << bitDepthShift;
    }

    /// Applies CDEF to one rectangular unit using decoded scalar strengths.
    ///
    /// @param plane the source plane metadata
    /// @param outputSamples the mutable output samples
    /// @param startX the inclusive unit start X coordinate
    /// @param startY the inclusive unit start Y coordinate
    /// @param endX the exclusive unit end X coordinate
    /// @param endY the exclusive unit end Y coordinate
    /// @param processingWidth the exclusive CDEF-grid processing boundary in X
    /// @param processingHeight the exclusive CDEF-grid processing boundary in Y
    /// @param damping the strength-adjusted CDEF damping value
    /// @param primaryStrength the decoded primary CDEF strength
    /// @param secondaryStrength the decoded secondary CDEF strength
    /// @param direction the luma-derived dominant CDEF direction
    /// @param bitDepthShift the decoded bit-depth shift from 8-bit samples
    /// @param maximumSample the maximum legal output sample value
    private static void filterUnit(
            PaddedPlane plane,
            short[] outputSamples,
            int startX,
            int startY,
            int endX,
            int endY,
            int processingWidth,
            int processingHeight,
            int damping,
            int primaryStrength,
            int secondaryStrength,
            int direction,
            int bitDepthShift,
            int maximumSample
    ) {
        int primaryTap = 4 - ((primaryStrength >> bitDepthShift) & 1);
        int primaryDamping = primaryStrength > 0
                ? Math.max(0, damping - floorLog2(primaryStrength))
                : 0;
        int secondaryDamping = secondaryStrength > 0
                ? Math.max(0, damping - floorLog2(secondaryStrength))
                : 0;
        boolean clipToNeighborRange = primaryStrength > 0 && secondaryStrength > 0;

        for (int y = startY; y < endY; y++) {
            int rowOffset = y * plane.stride();
            for (int x = startX; x < endX; x++) {
                int center = plane.storedSample(x, y);
                int sum = 0;
                int minimum = center;
                int maximum = center;
                if (primaryStrength > 0) {
                    int tap = primaryTap;
                    for (int distanceIndex = 0; distanceIndex < 2; distanceIndex++) {
                        int[] step = FILTER_DIRECTIONS[direction][distanceIndex];
                        int positive = sampleOrMissing(
                                plane, x + step[0], y + step[1], processingWidth, processingHeight);
                        int negative = sampleOrMissing(
                                plane, x - step[0], y - step[1], processingWidth, processingHeight);
                        sum += tap * constrainSample(positive, center, primaryStrength, primaryDamping);
                        sum += tap * constrainSample(negative, center, primaryStrength, primaryDamping);
                        if (clipToNeighborRange) {
                            minimum = includeMinimum(minimum, positive);
                            minimum = includeMinimum(minimum, negative);
                            maximum = includeMaximum(maximum, positive);
                            maximum = includeMaximum(maximum, negative);
                        }
                        tap = (tap & 3) | 2;
                    }
                }
                if (secondaryStrength > 0) {
                    int secondaryDirection0 = (direction + 2) & 7;
                    int secondaryDirection1 = (direction + 6) & 7;
                    for (int distanceIndex = 0; distanceIndex < 2; distanceIndex++) {
                        int tap = 2 - distanceIndex;
                        int[] step0 = FILTER_DIRECTIONS[secondaryDirection0][distanceIndex];
                        int[] step1 = FILTER_DIRECTIONS[secondaryDirection1][distanceIndex];
                        int positive0 = sampleOrMissing(
                                plane, x + step0[0], y + step0[1], processingWidth, processingHeight);
                        int negative0 = sampleOrMissing(
                                plane, x - step0[0], y - step0[1], processingWidth, processingHeight);
                        int positive1 = sampleOrMissing(
                                plane, x + step1[0], y + step1[1], processingWidth, processingHeight);
                        int negative1 = sampleOrMissing(
                                plane, x - step1[0], y - step1[1], processingWidth, processingHeight);
                        sum += tap * constrainSample(positive0, center, secondaryStrength, secondaryDamping);
                        sum += tap * constrainSample(negative0, center, secondaryStrength, secondaryDamping);
                        sum += tap * constrainSample(positive1, center, secondaryStrength, secondaryDamping);
                        sum += tap * constrainSample(negative1, center, secondaryStrength, secondaryDamping);
                        if (clipToNeighborRange) {
                            minimum = includeMinimum(includeMinimum(minimum, positive0), negative0);
                            minimum = includeMinimum(includeMinimum(minimum, positive1), negative1);
                            maximum = includeMaximum(includeMaximum(maximum, positive0), negative0);
                            maximum = includeMaximum(includeMaximum(maximum, positive1), negative1);
                        }
                    }
                }
                int filtered = center + ((sum - (sum < 0 ? 1 : 0) + 8) >> 4);
                if (clipToNeighborRange) {
                    filtered = clamp(filtered, minimum, maximum);
                }
                outputSamples[rowOffset + x] = (short) clamp(filtered, 0, maximumSample);
            }
        }
    }

    /// Detects one luma unit's dominant direction using reusable partial sums.
    ///
    /// @param plane the source plane metadata
    /// @param startX the inclusive unit start X coordinate
    /// @param startY the inclusive unit start Y coordinate
    /// @param processingWidth the CDEF-grid-aligned luma processing width
    /// @param processingHeight the CDEF-grid-aligned luma processing height
    /// @param bitDepthShift the decoded bit-depth shift from 8-bit samples
    /// @param workspace the reusable per-frame CDEF workspace that receives the result
    private static void detectDirection(
            PaddedPlane plane,
            int startX,
            int startY,
            int processingWidth,
            int processingHeight,
            int bitDepthShift,
            CdefWorkspace workspace
    ) {
        int[][] partialSumHv = workspace.partialSumHv;
        int[][] partialSumDiag = workspace.partialSumDiag;
        int[][] partialSumAlt = workspace.partialSumAlt;
        for (int[] row : partialSumHv) {
            Arrays.fill(row, 0);
        }
        for (int[] row : partialSumDiag) {
            Arrays.fill(row, 0);
        }
        for (int[] row : partialSumAlt) {
            Arrays.fill(row, 0);
        }

        for (int y = 0; y < CDEF_UNIT_SIZE; y++) {
            for (int x = 0; x < CDEF_UNIT_SIZE; x++) {
                int sample = samplePaddedOrClamped(
                        plane,
                        startX + x,
                        startY + y,
                        processingWidth,
                        processingHeight
                );
                int px = (sample >> bitDepthShift) - 128;
                partialSumDiag[0][y + x] += px;
                partialSumAlt[0][y + (x >> 1)] += px;
                partialSumHv[0][y] += px;
                partialSumAlt[1][3 + y - (x >> 1)] += px;
                partialSumDiag[1][7 + y - x] += px;
                partialSumAlt[2][3 - (y >> 1) + x] += px;
                partialSumHv[1][x] += px;
                partialSumAlt[3][(y >> 1) + x] += px;
            }
        }

        long[] cost = workspace.directionCosts;
        Arrays.fill(cost, 0);
        for (int n = 0; n < 8; n++) {
            cost[2] += (long) partialSumHv[0][n] * partialSumHv[0][n];
            cost[6] += (long) partialSumHv[1][n] * partialSumHv[1][n];
        }
        cost[2] *= 105;
        cost[6] *= 105;

        for (int n = 0; n < 7; n++) {
            int divisor = DIRECTION_DIVISORS[n];
            cost[0] += ((long) partialSumDiag[0][n] * partialSumDiag[0][n]
                    + (long) partialSumDiag[0][14 - n] * partialSumDiag[0][14 - n]) * divisor;
            cost[4] += ((long) partialSumDiag[1][n] * partialSumDiag[1][n]
                    + (long) partialSumDiag[1][14 - n] * partialSumDiag[1][14 - n]) * divisor;
        }
        cost[0] += (long) partialSumDiag[0][7] * partialSumDiag[0][7] * 105;
        cost[4] += (long) partialSumDiag[1][7] * partialSumDiag[1][7] * 105;

        for (int n = 0; n < 4; n++) {
            int costIndex = n * 2 + 1;
            for (int m = 0; m < 5; m++) {
                cost[costIndex] += (long) partialSumAlt[n][3 + m] * partialSumAlt[n][3 + m];
            }
            cost[costIndex] *= 105;
            for (int m = 0; m < 3; m++) {
                int divisor = DIRECTION_DIVISORS[2 * m + 1];
                cost[costIndex] += ((long) partialSumAlt[n][m] * partialSumAlt[n][m]
                        + (long) partialSumAlt[n][10 - m] * partialSumAlt[n][10 - m]) * divisor;
            }
        }

        int bestDirection = 0;
        long bestCost = cost[0];
        for (int direction = 1; direction < cost.length; direction++) {
            if (cost[direction] > bestCost) {
                bestCost = cost[direction];
                bestDirection = direction;
            }
        }
        long variance = (bestCost - cost[bestDirection ^ 4]) >> 10;
        workspace.detectedDirection = bestDirection;
        workspace.detectedVariance = (int) Math.min(Integer.MAX_VALUE, variance);
    }

    /// Applies the luma primary-strength variance adjustment.
    ///
    /// @param strength the bit-depth-scaled primary strength
    /// @param variance the CDEF direction estimator variance
    /// @return the adjusted primary strength
    private static int adjustStrength(int strength, int variance) {
        if (variance == 0) {
            return 0;
        }
        int scaledVariance = variance >> 6;
        int shift = scaledVariance != 0 ? Math.min(floorLog2(scaledVariance), 12) : 0;
        return (strength * (4 + shift) + 8) >> 4;
    }

    /// Applies the AV1 CDEF constraint function to one sample.
    ///
    /// @param sample the neighbor sample, or `MISSING_SAMPLE`
    /// @param center the current center sample
    /// @param threshold the active CDEF threshold
    /// @param damping the strength-adjusted CDEF damping value
    /// @return the constrained signed delta
    private static int constrainSample(int sample, int center, int threshold, int damping) {
        if (sample == MISSING_SAMPLE) {
            return 0;
        }
        return constrain(sample - center, threshold, damping);
    }

    /// Applies the AV1 CDEF constraint function to one neighbor delta.
    ///
    /// @param difference the signed neighbor-center delta
    /// @param threshold the active CDEF threshold
    /// @param damping the strength-adjusted CDEF damping value
    /// @return the constrained signed delta
    private static int constrain(int difference, int threshold, int damping) {
        if (threshold == 0) {
            return 0;
        }
        int magnitude = Math.abs(difference);
        int constrainedMagnitude = Math.min(magnitude, Math.max(0, threshold - (magnitude >> damping)));
        return difference < 0 ? -constrainedMagnitude : constrainedMagnitude;
    }

    /// Returns the minimum after optionally including one non-missing neighbor sample.
    ///
    /// @param current the current minimum
    /// @param sample the candidate neighbor sample, or `MISSING_SAMPLE`
    /// @return the updated minimum
    private static int includeMinimum(int current, int sample) {
        return sample == MISSING_SAMPLE ? current : Math.min(current, sample);
    }

    /// Returns the maximum after optionally including one non-missing neighbor sample.
    ///
    /// @param current the current maximum
    /// @param sample the candidate neighbor sample, or `MISSING_SAMPLE`
    /// @return the updated maximum
    private static int includeMaximum(int current, int sample) {
        return sample == MISSING_SAMPLE ? current : Math.max(current, sample);
    }

    /// Returns one source sample or the AV1 missing-edge sentinel.
    ///
    /// @param plane the source plane metadata
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @param processingWidth the exclusive CDEF-grid processing boundary in X
    /// @param processingHeight the exclusive CDEF-grid processing boundary in Y
    /// @return one source sample or `MISSING_SAMPLE`
    private static int sampleOrMissing(
            PaddedPlane plane,
            int x,
            int y,
            int processingWidth,
            int processingHeight
    ) {
        if (x < 0 || x >= processingWidth || y < 0 || y >= processingHeight) {
            return MISSING_SAMPLE;
        }
        return plane.storedSample(x, y);
    }

    /// Returns one stored source sample with processing-boundary extension for direction estimation.
    ///
    /// @param plane the source plane metadata
    /// @param x the unclamped X coordinate
    /// @param y the unclamped Y coordinate
    /// @param processingWidth the available CDEF-grid processing width
    /// @param processingHeight the available CDEF-grid processing height
    /// @return one edge-extended source sample
    private static int samplePaddedOrClamped(
            PaddedPlane plane,
            int x,
            int y,
            int processingWidth,
            int processingHeight
    ) {
        int clampedX = clamp(x, 0, processingWidth - 1);
        int clampedY = clamp(y, 0, processingHeight - 1);
        return plane.storedSample(clampedX, clampedY);
    }

    /// Returns the number of luma CDEF units needed to cover one plane extent.
    ///
    /// @param extent the luma plane extent in samples
    /// @return the number of luma CDEF units
    private static int cdefUnitCount(int extent) {
        return (extent + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE;
    }

    /// Returns the horizontal chroma subsampling shift for one chroma format.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @return the horizontal chroma subsampling shift
    private static int chromaSubsamplingX(Av1ChromaFormat chromaFormat) {
        return switch (Objects.requireNonNull(chromaFormat, "chromaFormat")) {
            case MONOCHROME, YUV444 -> 0;
            case YUV420, YUV422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift for one chroma format.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @return the vertical chroma subsampling shift
    private static int chromaSubsamplingY(Av1ChromaFormat chromaFormat) {
        return switch (Objects.requireNonNull(chromaFormat, "chromaFormat")) {
            case MONOCHROME, YUV422, YUV444 -> 0;
            case YUV420 -> 1;
        };
    }

    /// Returns the base-2 floor logarithm of a positive integer.
    ///
    /// @param value the positive integer
    /// @return the base-2 floor logarithm
    private static int floorLog2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }

    /// Clamps one signed value into one inclusive integer range.
    ///
    /// @param value the candidate value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Compact CDEF state that remains valid after the complete frame syntax tree is released.
    
    static final class PreparedApplication {
        /// The expected decoded bit depth.
        private final int bitDepth;

        /// The expected chroma format.
        private final Av1ChromaFormat chromaFormat;

        /// The expected coded luma width.
        private final int codedWidth;

        /// The expected coded luma height.
        private final int codedHeight;

        /// The encoded luma strength table.
        private final int [] yStrengths;

        /// The encoded chroma strength table.
        private final int [] uvStrengths;

        /// The luma CDEF-unit column count.
        private final int unitColumns;

        /// The luma CDEF-unit row count.
        private final int unitRows;

        /// The decoded bit-depth shift from eight-bit samples.
        private final int bitDepthShift;

        /// The bit-depth-adjusted luma damping value.
        private final int damping;

        /// The compact decoded CDEF-unit syntax, or `null` when CDEF is inactive.
        private final CdefUnitMap cdefUnitMap;

        /// Creates one prepared CDEF operation.
        ///
        /// The supplied strength arrays and compact maps are retained without copying. Their
        /// callers must relinquish them after construction.
        ///
        /// @param decodedPlanes the surface for which the operation was prepared
        /// @param yStrengths the exclusively owned luma strength table
        /// @param uvStrengths the exclusively owned chroma strength table
        /// @param unitColumns the luma CDEF-unit column count
        /// @param unitRows the luma CDEF-unit row count
        /// @param bitDepthShift the decoded bit-depth shift
        /// @param damping the bit-depth-adjusted luma damping value
        /// @param cdefUnitMap the compact decoded CDEF-unit syntax, or `null` for inactive CDEF
        private PreparedApplication(
                DecodedSurface decodedPlanes,
                int[] yStrengths,
                int[] uvStrengths,
                int unitColumns,
                int unitRows,
                int bitDepthShift,
                int damping,
                CdefUnitMap cdefUnitMap
        ) {
            DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
            this.bitDepth = checkedDecodedPlanes.bitDepth();
            this.chromaFormat = checkedDecodedPlanes.chromaFormat();
            this.codedWidth = checkedDecodedPlanes.codedWidth();
            this.codedHeight = checkedDecodedPlanes.codedHeight();
            this.yStrengths = Objects.requireNonNull(yStrengths, "yStrengths");
            this.uvStrengths = Objects.requireNonNull(uvStrengths, "uvStrengths");
            this.unitColumns = unitColumns;
            this.unitRows = unitRows;
            this.bitDepthShift = bitDepthShift;
            this.damping = damping;
            this.cdefUnitMap = cdefUnitMap;
        }

        /// Creates an inactive prepared operation for one decoded surface.
        ///
        /// @param decodedPlanes the surface for which CDEF is inactive
        /// @return the inactive prepared operation
        private static PreparedApplication inactive(DecodedSurface decodedPlanes) {
            return new PreparedApplication(
                    decodedPlanes,
                    new int[0],
                    new int[0],
                    0,
                    0,
                    0,
                    0,
                    null
            );
        }

        /// Returns whether this operation applies any CDEF filtering.
        ///
        /// @return whether CDEF is active
        private boolean active() {
            return cdefUnitMap != null;
        }

        /// Verifies that an operation is applied to the surface for which it was prepared.
        ///
        /// @param decodedPlanes the candidate loop-filtered surface
        private void validateSurface(DecodedSurface decodedPlanes) {
            DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
            if (checkedDecodedPlanes.bitDepth() != bitDepth
                    || checkedDecodedPlanes.chromaFormat() != chromaFormat
                    || checkedDecodedPlanes.codedWidth() != codedWidth
                    || checkedDecodedPlanes.codedHeight() != codedHeight) {
                throw new IllegalArgumentException("Prepared CDEF state does not match decoded surface");
            }
        }
    }

    /// Reusable per-frame storage for CDEF direction detection.
    
    private static final class CdefWorkspace {
        /// Horizontal and vertical direction partial sums.
        private final int[][] partialSumHv = new int[2][8];

        /// Diagonal direction partial sums.
        private final int[][] partialSumDiag = new int[2][15];

        /// Near-horizontal and near-vertical direction partial sums.
        private final int[][] partialSumAlt = new int[4][11];

        /// Direction costs accumulated from the partial sums.
        private final long[] directionCosts = new long[8];

        /// The dominant direction produced by the most recent direction search.
        private int detectedDirection;

        /// The directional variance produced by the most recent direction search.
        private int detectedVariance;

        /// Creates one empty reusable CDEF workspace.
        private CdefWorkspace() {
        }
    }

    /// Row-major decoded CDEF syntax state for luma CDEF units.
    ///
    /// The caller must relinquish both arrays after construction.
    ///
    /// @param cdefIndices the exclusively owned CDEF index array for each luma CDEF unit
    /// @param nonSkipUnits the exclusively owned non-skip array for each luma CDEF unit
    
    private record CdefUnitMap(
            int [] cdefIndices,
            boolean [] nonSkipUnits
    ) {
        /// Creates one row-major decoded CDEF syntax map.
        private CdefUnitMap {
            Objects.requireNonNull(cdefIndices, "cdefIndices");
            Objects.requireNonNull(nonSkipUnits, "nonSkipUnits");
        }

        /// Returns the decoded CDEF index for one luma CDEF unit.
        ///
        /// @param unitIndex the row-major CDEF unit index
        /// @return the decoded CDEF index for one luma CDEF unit
        private int cdefIndex(int unitIndex) {
            return cdefIndices[unitIndex];
        }

        /// Returns whether one luma CDEF unit contains at least one non-skipped block.
        ///
        /// @param unitIndex the row-major CDEF unit index
        /// @return whether one luma CDEF unit contains at least one non-skipped block
        private boolean nonSkip(int unitIndex) {
            return nonSkipUnits[unitIndex];
        }
    }

    /// Row-major luma-derived CDEF direction state.
    ///
    /// The caller must relinquish both arrays after construction.
    ///
    /// @param directions the exclusively owned dominant-direction array for each luma CDEF unit
    /// @param variances the exclusively owned directional-variance array for each luma CDEF unit
    
    private record CdefDirectionMap(
            int [] directions,
            int [] variances
    ) {
        /// Creates one row-major luma-derived CDEF direction map.
        private CdefDirectionMap {
            Objects.requireNonNull(directions, "directions");
            Objects.requireNonNull(variances, "variances");
        }

        /// Returns the dominant direction for one luma CDEF unit.
        ///
        /// @param unitIndex the row-major CDEF unit index
        /// @return the dominant direction for one luma CDEF unit
        private int direction(int unitIndex) {
            return directions[unitIndex];
        }

        /// Returns the directional variance for one luma CDEF unit.
        ///
        /// @param unitIndex the row-major CDEF unit index
        /// @return the directional variance for one luma CDEF unit
        private int variance(int unitIndex) {
            return variances[unitIndex];
        }
    }
}
