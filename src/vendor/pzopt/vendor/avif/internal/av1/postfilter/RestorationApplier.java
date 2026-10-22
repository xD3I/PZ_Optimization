// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.postfilter;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.decode.RestorationUnit;
import pzopt.vendor.avif.internal.av1.decode.RestorationUnitMap;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;

import java.util.Objects;

/// Applies the loop-restoration stage of the postfilter pipeline.
///
/// Inactive restoration preserves samples exactly. Active restoration consumes decoded
/// restoration-unit syntax and applies per-unit Wiener or self-guided filtering. Wiener filtering
/// uses the AV1 two-stage horizontal/vertical rounding model, while self-guided filtering uses the
/// AV1 self-guided A/B projection model.

final class RestorationApplier {
    /// Prevents instantiation of this stateless filter.
    private RestorationApplier() {
    }

    /// The AV1 restoration processing stripe size in luma samples.
    private static final int RESTORATION_PROC_UNIT_SIZE = 64;

    /// The AV1 vertical offset between restoration units and processing stripes.
    private static final int RESTORATION_UNIT_OFFSET = 8;

    /// The number of rows overwritten above and below an internal processing stripe.
    private static final int RESTORATION_STRIPE_BORDER = 3;

    /// The AV1 Wiener coefficient precision.
    private static final int FILTER_BITS = 7;

    /// The AV1 Wiener filter tap count.
    private static final int WIENER_TAP_COUNT = 7;

    /// The signed source-sample offset of the first Wiener tap.
    private static final int WIENER_TAP_OFFSET = 3;

    /// The final self-guided projection rounding shift used by dav1d.
    private static final int SELF_GUIDED_WEIGHT_BITS = 11;

    /// The self-guided 3x3 A/B finish rounding shift.
    private static final int SELF_GUIDED_FILTER_3_BITS = 9;

    /// The self-guided paired 5x5 A/B finish rounding shift.
    private static final int SELF_GUIDED_FILTER_5_PAIR_BITS = 9;

    /// The self-guided single-row 5x5 A/B finish rounding shift.
    private static final int SELF_GUIDED_FILTER_5_SINGLE_BITS = 8;

    /// The maximum self-guided variance table index.
    private static final int SELF_GUIDED_MAX_Z = 255;

    /// The AV1 self-guided restoration parameter table in `{r0, e0, r1, e1}` order.
    private static final int [] [] SELF_GUIDED_PARAMS = {
            {2, 140, 1, 3236},
            {2, 112, 1, 2158},
            {2, 93, 1, 1618},
            {2, 80, 1, 1438},
            {2, 70, 1, 1295},
            {2, 58, 1, 1177},
            {2, 47, 1, 1079},
            {2, 37, 1, 996},
            {2, 30, 1, 925},
            {2, 25, 1, 863},
            {0, -1, 1, 2589},
            {0, -1, 1, 1618},
            {0, -1, 1, 1177},
            {0, -1, 1, 925},
            {2, 56, 0, -1},
            {2, 22, 0, -1}
    };

    /// The dav1d `sgr_x_by_x` reciprocal values indexed by normalized variance.
    private static final int [] SELF_GUIDED_X_BY_X = createSelfGuidedXByXTable();

    /// Applies restoration using a compact restoration-unit map extracted from frame syntax.
    ///
    /// @param decodedPlanes the decoded planes after CDEF
    /// @param boundaryPlanes the decoded planes after loop filtering and before CDEF
    /// @param restoration the normalized frame-level restoration state
    /// @param unitMap the compact restoration-unit map, or `null` when syntax is unavailable
    /// @return the post-restoration planes
    static DecodedSurface applyPrepared(
            DecodedSurface decodedPlanes,
            DecodedSurface boundaryPlanes,
            FrameHeader.RestorationInfo restoration,
            RestorationUnitMap unitMap
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        DecodedSurface checkedBoundaryPlanes = Objects.requireNonNull(boundaryPlanes, "boundaryPlanes");
        FrameHeader.RestorationInfo checkedRestoration = Objects.requireNonNull(restoration, "restoration");
        if (!hasActiveRestoration(checkedRestoration, checkedDecodedPlanes.hasChroma())) {
            return checkedDecodedPlanes;
        }
        if (checkedBoundaryPlanes.bitDepth() != checkedDecodedPlanes.bitDepth()
                || checkedBoundaryPlanes.chromaFormat() != checkedDecodedPlanes.chromaFormat()) {
            throw new IllegalArgumentException("Boundary planes must match decoded plane format");
        }
        if (unitMap == null) {
            throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
        }

        RestorationWorkspace workspace = new RestorationWorkspace();
        PaddedPlane lumaPlane = applyPlane(
                checkedDecodedPlanes.lumaPlane(),
                checkedBoundaryPlanes.lumaPlane(),
                checkedDecodedPlanes.bitDepth(),
                checkedDecodedPlanes.chromaFormat(),
                checkedRestoration,
                unitMap,
                0,
                workspace
        );

        PaddedPlane chromaUPlane = checkedDecodedPlanes.chromaUPlane();
        PaddedPlane chromaVPlane = checkedDecodedPlanes.chromaVPlane();
        if (checkedDecodedPlanes.hasChroma()) {
            chromaUPlane = applyPlane(
                    Objects.requireNonNull(chromaUPlane, "chromaUPlane"),
                    Objects.requireNonNull(checkedBoundaryPlanes.chromaUPlane(), "boundaryChromaUPlane"),
                    checkedDecodedPlanes.bitDepth(),
                    checkedDecodedPlanes.chromaFormat(),
                    checkedRestoration,
                    unitMap,
                    1,
                    workspace
            );
            chromaVPlane = applyPlane(
                    Objects.requireNonNull(chromaVPlane, "chromaVPlane"),
                    Objects.requireNonNull(checkedBoundaryPlanes.chromaVPlane(), "boundaryChromaVPlane"),
                    checkedDecodedPlanes.bitDepth(),
                    checkedDecodedPlanes.chromaFormat(),
                    checkedRestoration,
                    unitMap,
                    2,
                    workspace
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

    /// Returns whether any plane has active frame-level restoration.
    ///
    /// @param restoration the frame-level restoration state
    /// @param hasChroma whether chroma planes are present
    /// @return whether any plane has active frame-level restoration
    static boolean hasActiveRestoration(FrameHeader.RestorationInfo restoration, boolean hasChroma) {
        FrameHeader.RestorationType[] types = restoration.types();
        return types[0] != FrameHeader.RestorationType.NONE
                || (hasChroma && (types[1] != FrameHeader.RestorationType.NONE
                || types[2] != FrameHeader.RestorationType.NONE));
    }

    /// Applies restoration to one plane.
    ///
    /// @param plane the source plane
    /// @param boundaryPlane the source plane before CDEF, used for internal stripe boundaries
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the decoded chroma format
    /// @param restoration the frame-level restoration state
    /// @param unitMap the decoded restoration-unit map
    /// @param planeIndex the plane index
    /// @param workspace the reusable per-frame restoration workspace
    /// @return the restored plane, or the original plane when all selected units are disabled
    private static PaddedPlane applyPlane(
            PaddedPlane plane,
            PaddedPlane boundaryPlane,
            int bitDepth,
            Av1ChromaFormat chromaFormat,
            FrameHeader.RestorationInfo restoration,
            RestorationUnitMap unitMap,
            int planeIndex,
            RestorationWorkspace workspace
    ) {
        FrameHeader.RestorationType frameType = restoration.types()[planeIndex];
        if (frameType == FrameHeader.RestorationType.NONE) {
            return plane;
        }
        if (boundaryPlane.width() != plane.width() || boundaryPlane.height() != plane.height()) {
            throw new IllegalArgumentException("Boundary plane dimensions must match decoded plane dimensions");
        }

        int unitSize = 1 << (planeIndex == 0 ? restoration.unitSizeLog2Y() : restoration.unitSizeLog2Uv());
        PlaneSampleSource source = new DecodedPlaneSource(plane, bitDepth);
        PlaneSampleSource boundarySource = boundaryPlane == plane
                ? source
                : new DecodedPlaneSource(boundaryPlane, bitDepth);
        PlaneBuffer destination = null;
        int rows = unitMap.rows(planeIndex);
        int columns = unitMap.columns(planeIndex);
        if (rows == 0 || columns == 0) {
            throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
        }
        int subX = chromaSubsamplingX(chromaFormat, planeIndex);
        int subY = chromaSubsamplingY(chromaFormat, planeIndex);
        int verticalOffset = RESTORATION_UNIT_OFFSET >> subY;
        int processingStripeHeight = RESTORATION_PROC_UNIT_SIZE >> subY;
        int processingUnitWidth = RESTORATION_PROC_UNIT_SIZE >> subX;
        for (int unitRow = 0; unitRow < rows; unitRow++) {
            UnitLimits baseVerticalLimits = unitLimits(unitRow, unitSize, plane.height());
            int startY = Math.max(0, baseVerticalLimits.start() - verticalOffset);
            int endY = baseVerticalLimits.end() < plane.height()
                    ? baseVerticalLimits.end() - verticalOffset
                    : baseVerticalLimits.end();
            for (int unitColumn = 0; unitColumn < columns; unitColumn++) {
                RestorationUnit unit = unitMap.unit(planeIndex, unitRow, unitColumn);
                if (unit == null) {
                    throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
                }
                if (unit.type() == FrameHeader.RestorationType.NONE) {
                    continue;
                }
                if (destination == null) {
                    destination = PlaneBuffer.create(plane, bitDepth);
                }
                UnitLimits horizontalLimits = unitLimits(unitColumn, unitSize, plane.width());
                applyRestorationUnit(
                        source,
                        boundarySource,
                        destination,
                        unit,
                        horizontalLimits.start(),
                        startY,
                        horizontalLimits.end(),
                        endY,
                        processingStripeHeight,
                        verticalOffset,
                        processingUnitWidth,
                        workspace
                );
            }
        }
        return destination != null ? destination.toDecodedPlane() : plane;
    }

    /// Applies one restoration unit one processing stripe at a time.
    ///
    /// @param source the immutable post-CDEF source plane view
    /// @param boundarySource the immutable pre-CDEF boundary plane view
    /// @param destination the mutable destination plane view
    /// @param unit the decoded restoration unit
    /// @param startX the inclusive unit start X
    /// @param startY the inclusive unit start Y
    /// @param endX the exclusive unit end X
    /// @param endY the exclusive unit end Y
    /// @param processingStripeHeight the processing stripe height for this plane
    /// @param verticalOffset the vertical processing stripe offset for this plane
    /// @param processingUnitWidth the horizontal processing unit width for this plane
    /// @param workspace the reusable per-frame restoration workspace
    private static void applyRestorationUnit(
            PlaneSampleSource source,
            PlaneSampleSource boundarySource,
            PlaneBuffer destination,
            RestorationUnit unit,
            int startX,
            int startY,
            int endX,
            int endY,
            int processingStripeHeight,
            int verticalOffset,
            int processingUnitWidth,
            RestorationWorkspace workspace
    ) {
        int stripeStart = startY;
        while (stripeStart < endY) {
            int frameStripe = (stripeStart + verticalOffset) / processingStripeHeight;
            int nominalStripeHeight = processingStripeHeight
                    - (frameStripe == 0 ? verticalOffset : 0);
            int stripeEnd = Math.min(endY, stripeStart + nominalStripeHeight);
            boolean copyAbove = stripeStart != 0;
            int stripeBoundaryHeight = processingStripeHeight - (stripeStart == 0 ? verticalOffset : 0);
            boolean copyBelow = stripeStart + stripeBoundaryHeight < source.height();
            StripeBoundarySource stripeSource = new StripeBoundarySource(
                    source,
                    boundarySource,
                    stripeStart,
                    stripeEnd,
                    copyAbove,
                    copyBelow
            );
            if (unit.type() == FrameHeader.RestorationType.WIENER) {
                applyWienerUnit(stripeSource, destination, unit, startX, stripeStart, endX, stripeEnd, workspace);
            } else if (unit.type() == FrameHeader.RestorationType.SELF_GUIDED) {
                applySelfGuidedUnit(
                        stripeSource,
                        destination,
                        unit,
                        startX,
                        stripeStart,
                        endX,
                        stripeEnd,
                        processingUnitWidth,
                        workspace
                );
            } else {
                throw new IllegalStateException("Unsupported restoration unit type: " + unit.type());
            }
            stripeStart = stripeEnd;
        }
    }

    /// Applies one Wiener restoration unit.
    ///
    /// @param source the immutable source plane view
    /// @param destination the mutable destination plane view
    /// @param unit the decoded Wiener unit
    /// @param startX the inclusive unit start X
    /// @param startY the inclusive unit start Y
    /// @param endX the exclusive unit end X
    /// @param endY the exclusive unit end Y
    /// @param workspace the reusable per-frame restoration workspace
    private static void applyWienerUnit(
            PlaneSampleSource source,
            PlaneBuffer destination,
            RestorationUnit unit,
            int startX,
            int startY,
            int endX,
            int endY,
            RestorationWorkspace workspace
    ) {
        int[] horizontalKernel = workspace.horizontalWienerKernel;
        int[] verticalKernel = workspace.verticalWienerKernel;
        fillWienerKernel(unit, 0, horizontalKernel);
        fillWienerKernel(unit, 1, verticalKernel);

        int width = endX - startX;
        int height = endY - startY;
        int horizontalRowCount = height + WIENER_TAP_COUNT - 1;
        int[] horizontalSamples = workspace.wienerIntermediate(width * horizontalRowCount);
        int sourceRowLength = width + WIENER_TAP_COUNT - 1;
        int[] sourceRow = workspace.sourceRow(sourceRowLength);
        int bitDepth = source.bitDepth();
        int roundBitsH = 3 + (bitDepth == 12 ? 2 : 0);
        int roundingOffsetH = 1 << (roundBitsH - 1);
        int clipLimit = 1 << (bitDepth + 1 + FILTER_BITS - roundBitsH);
        int horizontalInitialSum = 1 << (bitDepth + 6);

        // The vertical stage consumes seven adjacent horizontal rows. Compute each row once
        // instead of recomputing the horizontal convolution for every vertical output tap.
        for (int row = 0; row < horizontalRowCount; row++) {
            int sourceY = startY + row - WIENER_TAP_OFFSET;
            int rowOffset = row * width;
            source.copyExtendedRow(
                    startX - WIENER_TAP_OFFSET,
                    sourceY,
                    sourceRowLength,
                    sourceRow,
                    0
            );
            for (int localX = 0; localX < width; localX++) {
                int sum = horizontalInitialSum;
                for (int tap = 0; tap < WIENER_TAP_COUNT; tap++) {
                    sum += horizontalKernel[tap] * sourceRow[localX + tap];
                }
                horizontalSamples[rowOffset + localX] =
                        clamp((sum + roundingOffsetH) >> roundBitsH, 0, clipLimit - 1);
            }
        }

        int roundBitsV = 11 - (bitDepth == 12 ? 2 : 0);
        int roundingOffsetV = 1 << (roundBitsV - 1);
        int roundOffset = 1 << (bitDepth + roundBitsV - 1);
        for (int y = startY; y < endY; y++) {
            int firstHorizontalRow = y - startY;
            for (int localX = 0; localX < width; localX++) {
                int sum = -roundOffset;
                for (int tap = 0; tap < WIENER_TAP_COUNT; tap++) {
                    sum += verticalKernel[tap]
                            * horizontalSamples[(firstHorizontalRow + tap) * width + localX];
                }
                destination.setSample(
                        startX + localX,
                        y,
                        (sum + roundingOffsetV) >> roundBitsV
                );
            }
        }
    }

    /// Applies one self-guided restoration unit.
    ///
    /// @param source the immutable source plane view
    /// @param destination the mutable destination plane view
    /// @param unit the decoded self-guided unit
    /// @param startX the inclusive unit start X
    /// @param startY the inclusive unit start Y
    /// @param endX the exclusive unit end X
    /// @param endY the exclusive unit end Y
    /// @param processingUnitWidth the horizontal processing unit width for this plane
    /// @param workspace the reusable per-frame restoration workspace
    private static void applySelfGuidedUnit(
            PlaneSampleSource source,
            PlaneBuffer destination,
            RestorationUnit unit,
            int startX,
            int startY,
            int endX,
            int endY,
            int processingUnitWidth,
            RestorationWorkspace workspace
    ) {
        int [] params = SELF_GUIDED_PARAMS[unit.selfGuidedSet()];
        int projection0 = unit.selfGuidedProjectionCoefficient(0);
        int projection1 = unit.selfGuidedProjectionCoefficient(1);
        for (int chunkStartX = startX; chunkStartX < endX; chunkStartX += processingUnitWidth) {
            int chunkEndX = Math.min(endX, chunkStartX + processingUnitWidth);
            int chunkWidth = chunkEndX - chunkStartX;
            int stripeHeight = endY - startY;
            SelfGuidedIntermediate radius2Filter = params[0] != 0
                    ? workspace.radius2Intermediate.compute(
                            source, chunkStartX, startY, chunkWidth, stripeHeight, 2, params[1])
                    : null;
            SelfGuidedIntermediate radius1Filter = params[2] != 0
                    ? workspace.radius1Intermediate.compute(
                            source, chunkStartX, startY, chunkWidth, stripeHeight, 1, params[3])
                    : null;
            int weight0 = radius2Filter != null ? projection0 : 0;
            int weight1 = radius1Filter != null ? 128 - projection0 - projection1 : 0;
            int[] baseSamples = workspace.sourceRow(chunkWidth);
            for (int y = startY; y < endY; y++) {
                int localY = y - startY;
                source.copyExtendedRow(chunkStartX, y, chunkWidth, baseSamples, 0);
                for (int localX = 0; localX < chunkWidth; localX++) {
                    int base = baseSamples[localX];
                    int adjustment = 0;
                    if (radius2Filter != null && weight0 != 0) {
                        adjustment += weight0 * radius2Filter.residual5(localX, localY, base);
                    }
                    if (radius1Filter != null && weight1 != 0) {
                        adjustment += weight1 * radius1Filter.residual3(localX, localY, base);
                    }
                    destination.setSample(
                            chunkStartX + localX,
                            y,
                            base + round2(adjustment, SELF_GUIDED_WEIGHT_BITS)
                    );
                }
            }
        }
    }

    /// Creates the immutable dav1d `sgr_x_by_x` reciprocal table.
    ///
    /// @return all reciprocal values for normalized variance indices `[0, 255]`
    private static int[] createSelfGuidedXByXTable() {
        int[] values = new int[SELF_GUIDED_MAX_Z + 1];
        for (int z = 0; z < SELF_GUIDED_MAX_Z; z++) {
            values[z] = Math.min(255, (256 + (z >> 1)) / (z + 1));
        }
        return values;
    }

    /// Fills one symmetric seven-tap Wiener filter kernel.
    ///
    /// @param unit the decoded Wiener restoration unit
    /// @param pass the pass index, where zero is horizontal and one is vertical
    /// @param kernel the seven-element destination kernel
    private static void fillWienerKernel(RestorationUnit unit, int pass, int[] kernel) {
        int c0 = unit.wienerCoefficient(pass, 0);
        int c1 = unit.wienerCoefficient(pass, 1);
        int c2 = unit.wienerCoefficient(pass, 2);
        kernel[0] = c0;
        kernel[1] = c1;
        kernel[2] = c2;
        kernel[3] = (1 << FILTER_BITS) - 2 * (c0 + c1 + c2);
        kernel[4] = c2;
        kernel[5] = c1;
        kernel[6] = c0;
    }

    /// Clips one integer into inclusive bounds.
    ///
    /// @param value the input value
    /// @param minimum the inclusive minimum
    /// @param maximum the inclusive maximum
    /// @return the clipped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /// Rounds one integer right shift by adding half the divisor.
    ///
    /// @param value the value to round
    /// @param bits the number of bits to discard
    /// @return the rounded shifted value
    private static int round2(int value, int bits) {
        if (bits == 0) {
            return value;
        }
        return (value + (1 << (bits - 1))) >> bits;
    }

    /// Returns one restoration-unit span using AV1's shortened final-unit rule.
    ///
    /// @param unitIndex the zero-based restoration unit index
    /// @param unitSize the nominal restoration unit size
    /// @param extent the plane extent in samples
    /// @return the inclusive-exclusive sample limits for the unit
    private static UnitLimits unitLimits(int unitIndex, int unitSize, int extent) {
        int start = 0;
        for (int index = 0; index < unitIndex; index++) {
            start += unitSpan(start, unitSize, extent);
        }
        return new UnitLimits(start, start + unitSpan(start, unitSize, extent));
    }

    /// Returns one restoration-unit span length from a starting coordinate.
    ///
    /// @param start the unit start coordinate
    /// @param unitSize the nominal restoration unit size
    /// @param extent the plane extent in samples
    /// @return the unit span length
    private static int unitSpan(int start, int unitSize, int extent) {
        int remaining = extent - start;
        int extendedSize = unitSize * 3 / 2;
        return remaining < extendedSize ? remaining : unitSize;
    }

    /// Returns the chroma horizontal subsampling shift for one plane.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @param planeIndex the plane index
    /// @return the chroma horizontal subsampling shift for one plane
    private static int chromaSubsamplingX(Av1ChromaFormat chromaFormat, int planeIndex) {
        if (planeIndex == 0) {
            return 0;
        }
        return switch (chromaFormat) {
            case MONOCHROME, YUV444 -> 0;
            case YUV420, YUV422 -> 1;
        };
    }

    /// Returns the chroma vertical subsampling shift for one plane.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @param planeIndex the plane index
    /// @return the chroma vertical subsampling shift for one plane
    private static int chromaSubsamplingY(Av1ChromaFormat chromaFormat, int planeIndex) {
        if (planeIndex == 0) {
            return 0;
        }
        return switch (chromaFormat) {
            case MONOCHROME, YUV422, YUV444 -> 0;
            case YUV420 -> 1;
        };
    }

    /// Reusable scratch storage shared by all restoration units in one frame.
    
    private static final class RestorationWorkspace {
        /// The horizontal Wiener intermediate rows.
        private int[] wienerIntermediate = new int[0];

        /// One frame-border-extended source row used by Wiener filtering.
        private int[] sourceRow = new int[0];

        /// The reusable horizontal Wiener kernel.
        private final int[] horizontalWienerKernel = new int[WIENER_TAP_COUNT];

        /// The reusable vertical Wiener kernel.
        private final int[] verticalWienerKernel = new int[WIENER_TAP_COUNT];

        /// The reusable radius-two self-guided fields.
        private final SelfGuidedIntermediate radius2Intermediate = new SelfGuidedIntermediate();

        /// The reusable radius-one self-guided fields.
        private final SelfGuidedIntermediate radius1Intermediate = new SelfGuidedIntermediate();

        /// Creates an empty per-frame restoration workspace.
        private RestorationWorkspace() {
        }

        /// Returns horizontal Wiener storage with at least the requested length.
        ///
        /// @param requiredLength the minimum number of intermediate samples
        /// @return reusable intermediate storage
        private int[] wienerIntermediate(int requiredLength) {
            if (wienerIntermediate.length < requiredLength) {
                wienerIntermediate = new int[requiredLength];
            }
            return wienerIntermediate;
        }

        /// Returns source-row storage with at least the requested length.
        ///
        /// @param requiredLength the minimum number of source samples
        /// @return reusable source-row storage
        private int[] sourceRow(int requiredLength) {
            if (sourceRow.length < requiredLength) {
                sourceRow = new int[requiredLength];
            }
            return sourceRow;
        }
    }

    /// Self-guided A/B projection fields for one filter radius.
    
    private static final class SelfGuidedIntermediate {
        /// The processing block width in samples.
        private int width;

        /// The inverted A field used by the dav1d finish equations.
        private int[] a = new int[0];

        /// The inverted B field used by the dav1d finish equations.
        private int[] b = new int[0];

        /// The reusable vertical box-filter column sums.
        private int[] columnSums = new int[0];

        /// The reusable vertical box-filter squared-sample sums.
        private int[] columnSquareSums = new int[0];

        /// One frame-border-extended source row used to update vertical column sums.
        private int[] sourceRow = new int[0];

        /// Creates an empty reusable self-guided intermediate field.
        private SelfGuidedIntermediate() {
        }

        /// Computes one self-guided intermediate field.
        ///
        /// @param source the source plane
        /// @param startX the global processing block start X
        /// @param startY the global processing stripe start Y
        /// @param width the processing block width in samples
        /// @param height the processing stripe height in samples
        /// @param radius the self-guided filter radius
        /// @param strength the self-guided strength value from the AV1 parameter table
        /// @return this populated reusable intermediate field
        private SelfGuidedIntermediate compute(
                PlaneSampleSource source,
                int startX,
                int startY,
                int width,
                int height,
                int radius,
                int strength
        ) {
            if (radius != 1 && radius != 2) {
                throw new IllegalArgumentException("radius must be 1 or 2: " + radius);
            }
            this.width = width;
            int count = radius == 1 ? 9 : 25;
            int oneByX = radius == 1 ? 455 : 164;
            int bitDepthShift = source.bitDepth() - 8;
            int fieldWidth = width + 2;
            int fieldHeight = height + 2;
            int fieldLength = fieldWidth * fieldHeight;
            if (a.length < fieldLength) {
                a = new int[fieldLength];
                b = new int[fieldLength];
            }
            int windowSize = radius * 2 + 1;
            int extendedWidth = fieldWidth + radius * 2;
            int firstSourceX = startX - 1 - radius;
            int firstCenterY = startY - 1;
            if (columnSums.length < extendedWidth) {
                columnSums = new int[extendedWidth];
                columnSquareSums = new int[extendedWidth];
            }
            if (sourceRow.length < extendedWidth) {
                sourceRow = new int[extendedWidth];
            }
            // Keep vertical column totals and slide them horizontally so each field sample is O(1).
            for (int column = 0; column < extendedWidth; column++) {
                columnSums[column] = 0;
                columnSquareSums[column] = 0;
            }
            for (int dy = -radius; dy <= radius; dy++) {
                source.copyExtendedRow(firstSourceX, firstCenterY + dy, extendedWidth, sourceRow, 0);
                for (int column = 0; column < extendedWidth; column++) {
                    int sample = sourceRow[column];
                    columnSums[column] += sample;
                    columnSquareSums[column] += sample * sample;
                }
            }

            for (int fieldY = 0; fieldY < fieldHeight; fieldY++) {
                if (fieldY != 0) {
                    int centerY = firstCenterY + fieldY;
                    int removedY = centerY - radius - 1;
                    int addedY = centerY + radius;
                    source.copyExtendedRow(firstSourceX, removedY, extendedWidth, sourceRow, 0);
                    for (int column = 0; column < extendedWidth; column++) {
                        int removedSample = sourceRow[column];
                        columnSums[column] -= removedSample;
                        columnSquareSums[column] -= removedSample * removedSample;
                    }
                    source.copyExtendedRow(firstSourceX, addedY, extendedWidth, sourceRow, 0);
                    for (int column = 0; column < extendedWidth; column++) {
                        int addedSample = sourceRow[column];
                        columnSums[column] += addedSample;
                        columnSquareSums[column] += addedSample * addedSample;
                    }
                }

                int sum = 0;
                int sumSquares = 0;
                for (int column = 0; column < windowSize; column++) {
                    sum += columnSums[column];
                    sumSquares += columnSquareSums[column];
                }
                for (int fieldX = 0; fieldX < fieldWidth; fieldX++) {
                    Projection projection = projection(sum, sumSquares, count, strength, oneByX, bitDepthShift);
                    int index = fieldY * fieldWidth + fieldX;
                    a[index] = projection.a();
                    b[index] = projection.b();
                    if (fieldX + 1 < fieldWidth) {
                        sum += columnSums[fieldX + windowSize] - columnSums[fieldX];
                        sumSquares += columnSquareSums[fieldX + windowSize] - columnSquareSums[fieldX];
                    }
                }
            }
            return this;
        }

        /// Returns one 3x3 residual from the inverted A/B fields.
        ///
        /// @param x the sample X coordinate relative to the processing block
        /// @param y the sample Y coordinate relative to the processing stripe
        /// @param sourceSample the original source sample
        /// @return one 3x3 residual
        public int residual3(int x, int y, int sourceSample) {
            int weightedB = eightNeighborWeight(b, x, y);
            int weightedA = eightNeighborWeight(a, x, y);
            return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_3_BITS);
        }

        /// Returns one 5x5 residual from the inverted A/B fields.
        ///
        /// @param x the sample X coordinate relative to the processing block
        /// @param y the sample Y coordinate relative to the processing stripe
        /// @param sourceSample the original source sample
        /// @return one 5x5 residual
        public int residual5(int x, int y, int sourceSample) {
            if ((y & 1) == 0) {
                int weightedB = sixNeighborPairWeight(b, x, y);
                int weightedA = sixNeighborPairWeight(a, x, y);
                return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_5_PAIR_BITS);
            }
            int weightedB = sixNeighborSingleWeight(b, x, y);
            int weightedA = sixNeighborSingleWeight(a, x, y);
            return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_5_SINGLE_BITS);
        }

        /// Returns one field value from the stored one-sample halo.
        ///
        /// @param values the field storage
        /// @param x the sample X coordinate in `[-1, width]`
        /// @param y the sample Y coordinate in `[-1, height]`
        /// @return one field value
        private int fieldValue(int[] values, int x, int y) {
            return values[(y + 1) * (width + 2) + x + 1];
        }

        /// Returns the dav1d eight-neighbor weighted sum for one field.
        ///
        /// @param values the field storage
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return the weighted field sum
        private int eightNeighborWeight(int[] values, int x, int y) {
            return (fieldValue(values, x, y)
                    + fieldValue(values, x - 1, y)
                    + fieldValue(values, x + 1, y)
                    + fieldValue(values, x, y - 1)
                    + fieldValue(values, x, y + 1)) * 4
                    + (fieldValue(values, x - 1, y - 1)
                    + fieldValue(values, x + 1, y - 1)
                    + fieldValue(values, x - 1, y + 1)
                    + fieldValue(values, x + 1, y + 1)) * 3;
        }

        /// Returns the dav1d paired-row weighted sum for one 5x5 field.
        ///
        /// @param values the field storage
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return the weighted field sum
        private int sixNeighborPairWeight(int[] values, int x, int y) {
            int topY = y - 1;
            int bottomY = y + 1;
            return (fieldValue(values, x, topY) + fieldValue(values, x, bottomY)) * 6
                    + (fieldValue(values, x - 1, topY)
                    + fieldValue(values, x + 1, topY)
                    + fieldValue(values, x - 1, bottomY)
                    + fieldValue(values, x + 1, bottomY)) * 5;
        }

        /// Returns the dav1d single-row weighted sum for one 5x5 field.
        ///
        /// @param values the field storage
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return the weighted field sum
        private int sixNeighborSingleWeight(int[] values, int x, int y) {
            return fieldValue(values, x, y) * 6
                    + (fieldValue(values, x - 1, y) + fieldValue(values, x + 1, y)) * 5;
        }

        /// Computes the inverted dav1d A/B projection values for one box.
        ///
        /// @param sum the source box sample sum
        /// @param sumSquares the source box squared-sample sum
        /// @param count the number of samples in the box
        /// @param strength the self-guided strength value
        /// @param oneByX the reciprocal normalization constant
        /// @param bitDepthShift the decoded bit depth minus eight
        /// @return the inverted A/B projection values
        private static Projection projection(
                int sum,
                int sumSquares,
                int count,
                int strength,
                int oneByX,
                int bitDepthShift
        ) {
            int scaledSumSquares = roundForBitDepth(sumSquares, bitDepthShift * 2);
            int scaledSum = roundForBitDepth(sum, bitDepthShift);
            long variance = Math.max((long) scaledSumSquares * count - (long) scaledSum * scaledSum, 0L);
            int z = (int) Math.min(SELF_GUIDED_MAX_Z, (variance * strength + (1 << 19)) >> 20);
            int xByX = SELF_GUIDED_X_BY_X[z];
            int a = (int) (((long) xByX * sum * oneByX + (1 << 11)) >> 12);
            return new Projection(a, xByX);
        }

        /// Rounds one box statistic down to the normalized 8-bit domain.
        ///
        /// @param value the source statistic
        /// @param bits the number of bits to remove
        /// @return the rounded statistic
        private static int roundForBitDepth(int value, int bits) {
            if (bits == 0) {
                return value;
            }
            return (value + ((1 << bits) >> 1)) >> bits;
        }

    }

    /// Inverted self-guided projection values.
    ///
    /// @param a the inverted A value
    /// @param b the inverted B value
    
    private record Projection(int a, int b) {
    }

    /// Inclusive-exclusive restoration unit limits.
    ///
    /// @param start the inclusive start coordinate
    /// @param end the exclusive end coordinate
    
    private record UnitLimits(int start, int end) {
    }

    /// Read-only plane source with AV1 frame-border extension.
    
    private interface PlaneSampleSource {
        /// Returns the plane width in samples.
        ///
        /// @return the plane width in samples
        int width();

        /// Returns the plane height in samples.
        ///
        /// @return the plane height in samples
        int height();

        /// Returns the decoded bit depth.
        ///
        /// @return the decoded bit depth
        int bitDepth();

        /// Returns one frame-border-extended sample.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return one sample
        int sample(int x, int y);

        /// Copies one frame-border-extended source row into caller-provided storage.
        ///
        /// @param startX the first source X coordinate
        /// @param y the source Y coordinate
        /// @param length the number of samples to copy
        /// @param destination the destination storage
        /// @param destinationOffset the first destination index
        default void copyExtendedRow(
                int startX,
                int y,
                int length,
                int[] destination,
                int destinationOffset
        ) {
            for (int index = 0; index < length; index++) {
                destination[destinationOffset + index] = sample(startX + index, y);
            }
        }
    }

    /// Read-only restoration source backed by one immutable decoded plane.
    
    private static final class DecodedPlaneSource implements PlaneSampleSource {
        /// The immutable decoded plane.
        private final PaddedPlane plane;

        /// The decoded bit depth.
        private final int bitDepth;

        /// Creates one read-only decoded-plane source.
        ///
        /// @param plane the immutable decoded plane
        /// @param bitDepth the decoded bit depth
        private DecodedPlaneSource(PaddedPlane plane, int bitDepth) {
            this.plane = Objects.requireNonNull(plane, "plane");
            this.bitDepth = bitDepth;
        }

        /// Returns the visible plane width.
        ///
        /// @return the visible plane width in samples
        @Override
        public int width() {
            return plane.width();
        }

        /// Returns the visible plane height.
        ///
        /// @return the visible plane height in samples
        @Override
        public int height() {
            return plane.height();
        }

        /// Returns the decoded bit depth.
        ///
        /// @return the decoded bit depth
        @Override
        public int bitDepth() {
            return bitDepth;
        }

        /// Returns one frame-border-extended sample.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return the nearest visible-plane sample
        @Override
        public int sample(int x, int y) {
            return plane.sample(clamp(x, 0, plane.width() - 1), clamp(y, 0, plane.height() - 1));
        }

        /// Copies one frame-border-extended decoded-plane row.
        ///
        /// @param startX the first source X coordinate
        /// @param y the source Y coordinate
        /// @param length the number of samples to copy
        /// @param destination the destination storage
        /// @param destinationOffset the first destination index
        @Override
        public void copyExtendedRow(
                int startX,
                int y,
                int length,
                int[] destination,
                int destinationOffset
        ) {
            int clampedY = clamp(y, 0, plane.height() - 1);
            int sourceWidth = plane.width();
            int firstVisibleIndex = clamp(-startX, 0, length);
            int visibleEndIndex = clamp(sourceWidth - startX, firstVisibleIndex, length);
            int leftSample = plane.sample(0, clampedY);
            for (int index = 0; index < firstVisibleIndex; index++) {
                destination[destinationOffset + index] = leftSample;
            }
            for (int index = firstVisibleIndex; index < visibleEndIndex; index++) {
                destination[destinationOffset + index] = plane.sample(startX + index, clampedY);
            }
            int rightSample = plane.sample(sourceWidth - 1, clampedY);
            for (int index = visibleEndIndex; index < length; index++) {
                destination[destinationOffset + index] = rightSample;
            }
        }
    }

    /// Source view that substitutes AV1 internal restoration stripe boundary rows.
    
    private static final class StripeBoundarySource implements PlaneSampleSource {
        /// The post-CDEF source plane.
        private final PlaneSampleSource source;

        /// The pre-CDEF boundary source plane.
        private final PlaneSampleSource boundarySource;

        /// The inclusive processing stripe start Y.
        private final int stripeStart;

        /// The exclusive processing stripe end Y.
        private final int stripeEnd;

        /// Whether rows above this stripe use saved boundary lines.
        private final boolean copyAbove;

        /// Whether rows below this stripe use saved boundary lines.
        private final boolean copyBelow;

        /// Creates one stripe boundary source.
        ///
        /// @param source the post-CDEF source plane
        /// @param boundarySource the pre-CDEF boundary source plane
        /// @param stripeStart the inclusive processing stripe start Y
        /// @param stripeEnd the exclusive processing stripe end Y
        /// @param copyAbove whether rows above this stripe use saved boundary lines
        /// @param copyBelow whether rows below this stripe use saved boundary lines
        private StripeBoundarySource(
                PlaneSampleSource source,
                PlaneSampleSource boundarySource,
                int stripeStart,
                int stripeEnd,
                boolean copyAbove,
                boolean copyBelow
        ) {
            this.source = Objects.requireNonNull(source, "source");
            this.boundarySource = Objects.requireNonNull(boundarySource, "boundarySource");
            this.stripeStart = stripeStart;
            this.stripeEnd = stripeEnd;
            this.copyAbove = copyAbove;
            this.copyBelow = copyBelow;
        }

        /// Returns the plane width in samples.
        ///
        /// @return the plane width in samples
        @Override
        public int width() {
            return source.width();
        }

        /// Returns the plane height in samples.
        ///
        /// @return the plane height in samples
        @Override
        public int height() {
            return source.height();
        }

        /// Returns the decoded bit depth.
        ///
        /// @return the decoded bit depth
        @Override
        public int bitDepth() {
            return source.bitDepth();
        }

        /// Returns one sample with internal stripe boundary rows substituted.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return one sample
        @Override
        public int sample(int x, int y) {
            if (copyAbove && y >= stripeStart - RESTORATION_STRIPE_BORDER && y < stripeStart) {
                int boundaryY = y <= stripeStart - 2 ? stripeStart - 2 : stripeStart - 1;
                return boundarySource.sample(x, boundaryY);
            }
            if (copyBelow && y >= stripeEnd && y < stripeEnd + RESTORATION_STRIPE_BORDER) {
                int boundaryY = y == stripeEnd ? stripeEnd : stripeEnd + 1;
                return boundarySource.sample(x, boundaryY);
            }
            return source.sample(x, y);
        }

        /// Copies one source row after resolving internal stripe-boundary substitution once.
        ///
        /// @param startX the first source X coordinate
        /// @param y the source Y coordinate
        /// @param length the number of samples to copy
        /// @param destination the destination storage
        /// @param destinationOffset the first destination index
        @Override
        public void copyExtendedRow(
                int startX,
                int y,
                int length,
                int[] destination,
                int destinationOffset
        ) {
            if (copyAbove && y >= stripeStart - RESTORATION_STRIPE_BORDER && y < stripeStart) {
                int boundaryY = y <= stripeStart - 2 ? stripeStart - 2 : stripeStart - 1;
                boundarySource.copyExtendedRow(startX, boundaryY, length, destination, destinationOffset);
                return;
            }
            if (copyBelow && y >= stripeEnd && y < stripeEnd + RESTORATION_STRIPE_BORDER) {
                int boundaryY = y == stripeEnd ? stripeEnd : stripeEnd + 1;
                boundarySource.copyExtendedRow(startX, boundaryY, length, destination, destinationOffset);
                return;
            }
            source.copyExtendedRow(startX, y, length, destination, destinationOffset);
        }
    }

    /// Mutable plane storage used by restoration filtering.
    
    private static final class PlaneBuffer implements PlaneSampleSource {
        /// The plane width in samples.
        private final int width;

        /// The plane height in samples.
        private final int height;

        /// The sample stride of one plane row.
        private final int stride;

        /// The decoded bit depth.
        private final int bitDepth;

        /// The maximum legal sample value.
        private final int maxSampleValue;

        /// The mutable sample storage in row-major order.
        private final short[] samples;

        /// Creates one mutable plane buffer.
        ///
        /// @param width the plane width in samples
        /// @param height the plane height in samples
        /// @param stride the sample stride of one plane row
        /// @param bitDepth the decoded bit depth
        /// @param samples the mutable sample storage
        private PlaneBuffer(int width, int height, int stride, int bitDepth, short[] samples) {
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.bitDepth = bitDepth;
            this.maxSampleValue = (1 << bitDepth) - 1;
            this.samples = Objects.requireNonNull(samples, "samples");
        }

        /// Creates a mutable copy of one decoded plane.
        ///
        /// @param plane the decoded plane
        /// @param bitDepth the decoded bit depth
        /// @return a mutable copy of one decoded plane
        private static PlaneBuffer create(PaddedPlane plane, int bitDepth) {
            PaddedPlane checkedPlane = Objects.requireNonNull(plane, "plane");
            return new PlaneBuffer(
                    checkedPlane.width(),
                    checkedPlane.height(),
                    checkedPlane.stride(),
                    bitDepth,
                    checkedPlane.samples()
            );
        }

        /// Returns the plane width in samples.
        ///
        /// @return the plane width in samples
        @Override
        public int width() {
            return width;
        }

        /// Returns the plane height in samples.
        ///
        /// @return the plane height in samples
        @Override
        public int height() {
            return height;
        }

        /// Returns the decoded bit depth.
        ///
        /// @return the decoded bit depth
        @Override
        public int bitDepth() {
            return bitDepth;
        }

        /// Returns one sample.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return one sample
        @Override
        public int sample(int x, int y) {
            int clampedX = clamp(x, 0, width - 1);
            int clampedY = clamp(y, 0, height - 1);
            return samples[clampedY * stride + clampedX] & 0xFFFF;
        }

        /// Stores one clipped sample.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @param value the replacement value
        public void setSample(int x, int y, int value) {
            samples[y * stride + x] = (short) clamp(value, 0, maxSampleValue);
        }

        /// Returns one immutable decoded plane from the current samples.
        ///
        /// @return one immutable decoded plane from the current samples
        public PaddedPlane toDecodedPlane() {
            return PaddedPlane.fromOwnedSamples(width, height, stride, samples);
        }
    }
}
