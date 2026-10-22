// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.av1.Av1FrameType;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.InterMotionVector;
import pzopt.vendor.avif.internal.av1.model.MotionVector;

import java.util.Objects;

/// Projects saved reference-frame motion fields onto the current frame's 8x8 grid.
///
/// The projection follows the temporal-motion setup and scaling rules from AV1 section 7.9.3.
/// Instances are immutable after construction and may be shared by every tile in one frame.

final class ReferenceMotionVectorProjection {
    /// Fixed-point reciprocals used by AV1 temporal motion-vector projection.
    private static final int [] DIV_MULTIPLIERS = {
            0, 16384, 8192, 5461, 4096, 3276, 2730, 2340,
            2048, 1820, 1638, 1489, 1365, 1260, 1170, 1092,
            1024, 963, 910, 862, 819, 780, 744, 712,
            682, 655, 630, 606, 585, 564, 546, 528
    };

    /// The current frame header whose precision and reference distances apply to projected candidates.
    private final FrameHeader frameHeader;

    /// The projected field width in 8x8 units.
    private final int width8;

    /// The projected field height in 8x8 units.
    private final int height8;

    /// The current-reference distances indexed in internal LAST..ALTREF order.
    private final int [] referenceToCurrentDistances;

    /// The future-reference sign biases indexed in internal LAST..ALTREF order.
    private final boolean [] referenceSignBiases;

    /// The projected temporal blocks indexed in frame-relative 8x8 units.
    private final ProjectedTemporalBlock [] blocks;

    /// Creates one immutable current-frame projection.
    ///
    /// @param frameHeader the current frame header
    /// @param width8 the projected field width in 8x8 units
    /// @param height8 the projected field height in 8x8 units
    /// @param referenceToCurrentDistances the current-reference distances in LAST..ALTREF order
    /// @param referenceSignBiases the future-reference sign biases in LAST..ALTREF order
    /// @param blocks the projected temporal blocks in row-major order
    private ReferenceMotionVectorProjection(
            FrameHeader frameHeader,
            int width8,
            int height8,
            int[] referenceToCurrentDistances,
            boolean[] referenceSignBiases,
            ProjectedTemporalBlock[] blocks
    ) {
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        this.width8 = width8;
        this.height8 = height8;
        this.referenceToCurrentDistances = referenceToCurrentDistances.clone();
        this.referenceSignBiases = referenceSignBiases.clone();
        this.blocks = blocks.clone();
    }

    /// Creates the projected temporal field for one completed frame assembly.
    ///
    /// Missing reference syntax snapshots are treated as unavailable temporal sources. This is
    /// required for legal random-access points whose runtime reference slots have not been filled.
    ///
    /// @param assembly the current completed frame assembly
    /// @param referenceSyntaxResults the syntax snapshots indexed by runtime reference slot
    /// @return the immutable projected temporal field for the current frame
    static ReferenceMotionVectorProjection create(
            FrameAssembly assembly,
            ReferenceFrameSyntaxState[] referenceSyntaxResults
    ) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        ReferenceFrameSyntaxState[] nonNullReferenceSyntaxResults =
                Objects.requireNonNull(referenceSyntaxResults, "referenceSyntaxResults");
        if (nonNullReferenceSyntaxResults.length != 8) {
            throw new IllegalArgumentException(
                    "referenceSyntaxResults.length != 8: " + nonNullReferenceSyntaxResults.length
            );
        }

        FrameHeader currentHeader = nonNullAssembly.frameHeader();
        int width8 = (currentHeader.frameSize().codedWidth() + 7) >> 3;
        int height8 = (currentHeader.frameSize().height() + 7) >> 3;
        int orderHintBits = nonNullAssembly.sequenceHeader().features().orderHintBits();
        int[] referenceDistances = new int[7];
        boolean[] referenceSignBiases = new boolean[7];
        for (int referenceFrame = 0; referenceFrame < referenceDistances.length; referenceFrame++) {
            FrameHeader referenceHeader = nonNullAssembly.referenceFrameHeader(referenceFrame);
            if (referenceHeader != null) {
                referenceDistances[referenceFrame] = clamp(
                        orderHintDifference(orderHintBits, currentHeader.frameOffset(), referenceHeader.frameOffset()),
                        -31,
                        31
                );
                referenceSignBiases[referenceFrame] = hasFutureSignBias(
                        orderHintBits,
                        referenceHeader.frameOffset(),
                        currentHeader.frameOffset()
                );
            }
        }
        ProjectedTemporalBlock[] blocks = new ProjectedTemporalBlock[width8 * height8];
        ReferenceMotionVectorProjection result = new ReferenceMotionVectorProjection(
                currentHeader,
                width8,
                height8,
                referenceDistances,
                referenceSignBiases,
                blocks
        );
        if (!currentHeader.useReferenceFrameMotionVectors() || orderHintBits == 0) {
            return result;
        }

        int[] sourceReferences = selectTemporalSources(
                nonNullAssembly,
                nonNullReferenceSyntaxResults,
                orderHintBits
        );
        for (int sourceReference : sourceReferences) {
            int slot = currentHeader.referenceFrameIndex(sourceReference);
            ReferenceFrameSyntaxState sourceResult = nonNullReferenceSyntaxResults[slot];
            if (sourceResult != null) {
                result.projectSource(nonNullAssembly, sourceResult, sourceReference, orderHintBits, blocks);
            }
        }
        return new ReferenceMotionVectorProjection(
                currentHeader,
                width8,
                height8,
                referenceDistances,
                referenceSignBiases,
                blocks
        );
    }

    /// Returns whether the current frame enables reference-frame motion vectors.
    ///
    /// @return whether the current frame enables reference-frame motion vectors
    boolean enabled() {
        return frameHeader.useReferenceFrameMotionVectors();
    }

    /// Returns the temporal sign bias for one current-frame reference.
    ///
    /// References whose order hint lies after the current frame have the opposite motion-vector
    /// direction from past references when reused as extended spatial candidates.
    ///
    /// @param referenceFrame the current reference in internal LAST..ALTREF order
    /// @return whether the supplied reference has future-frame sign bias
    boolean signBias(int referenceFrame) {
        return referenceSignBiases[Objects.checkIndex(referenceFrame, referenceSignBiases.length)];
    }

    /// Returns the sign bias obtained from one reference-to-current order-hint difference.
    ///
    /// Computing the reference-to-current direction directly is required when the two offsets are
    /// exactly half of the modular order-hint range apart; reversing and negating the operands is
    /// not equivalent at that boundary.
    ///
    /// @param orderHintBits the number of order-hint bits
    /// @param referenceOffset the reference frame order hint
    /// @param currentOffset the current frame order hint
    /// @return whether the reference lies in the positive future direction
    static boolean hasFutureSignBias(int orderHintBits, int referenceOffset, int currentOffset) {
        return orderHintDifference(orderHintBits, referenceOffset, currentOffset) > 0;
    }

    /// Returns the projected predictor for one current reference at an 8x8 coordinate, or `null`.
    ///
    /// @param x8 the frame-relative X coordinate in 8x8 units
    /// @param y8 the frame-relative Y coordinate in 8x8 units
    /// @param referenceFrame the current reference in internal LAST..ALTREF order
    /// @return the projected motion-vector predictor, or `null`
    MotionVector motionVectorAt(int x8, int y8, int referenceFrame) {
        if (referenceFrame < 0 || referenceFrame >= referenceToCurrentDistances.length) {
            throw new IndexOutOfBoundsException("referenceFrame out of range: " + referenceFrame);
        }
        ProjectedTemporalBlock block = blockAt(x8, y8);
        if (block == null) {
            return null;
        }
        MotionVector projected = project(
                block.motionVector(),
                referenceToCurrentDistances[referenceFrame],
                block.denominator()
        );
        return fixPrecision(projected, frameHeader);
    }

    /// Returns one projected block, or `null` when the coordinate is outside or empty.
    ///
    /// @param x8 the frame-relative X coordinate in 8x8 units
    /// @param y8 the frame-relative Y coordinate in 8x8 units
    /// @return one projected temporal block, or `null`
    private ProjectedTemporalBlock blockAt(int x8, int y8) {
        if (x8 < 0 || x8 >= width8 || y8 < 0 || y8 >= height8) {
            return null;
        }
        return blocks[y8 * width8 + x8];
    }

    /// Projects one selected reference frame's saved motion field into the current frame.
    ///
    /// @param currentAssembly the current frame assembly
    /// @param sourceResult the selected source-frame syntax snapshot
    /// @param sourceReference the source in current-frame LAST..ALTREF order
    /// @param orderHintBits the sequence order-hint width
    /// @param destination the mutable destination used only during construction
    private void projectSource(
            FrameAssembly currentAssembly,
            ReferenceFrameSyntaxState sourceResult,
            int sourceReference,
            int orderHintBits,
            ProjectedTemporalBlock[] destination
    ) {
        FrameHeader currentHeader = currentAssembly.frameHeader();
        FrameHeader sourceHeader = currentAssembly.referenceFrameHeader(sourceReference);
        if (sourceHeader == null) {
            return;
        }
        FrameHeader sourceSyntaxHeader = sourceResult.frameHeader();
        if (!canProjectSourceMotionField(
                sourceSyntaxHeader.frameType(),
                sourceSyntaxHeader.frameSize().codedWidth(),
                sourceSyntaxHeader.frameSize().height(),
                currentHeader.frameSize().codedWidth(),
                currentHeader.frameSize().height()
        )) {
            return;
        }
        int sourceToCurrent = orderHintDifference(
                orderHintBits,
                sourceHeader.frameOffset(),
                currentHeader.frameOffset()
        );
        if (Math.abs(sourceToCurrent) > 31) {
            return;
        }
        int projectionDistance = sourceReference < 4 ? -sourceToCurrent : sourceToCurrent;
        int sourceSign = sourceReference - 4;
        for (int y8 = 0; y8 < height8; y8++) {
            int projectedRowStart = y8 & ~7;
            int projectedRowEnd = Math.min(projectedRowStart + 8, height8);
            for (int x8 = 0; x8 < width8; x8++) {
                TileDecodeContext.TemporalMotionBlock temporalBlock =
                        sourceResult.decodedTemporalMotionBlockAt(x8, y8);
                SavedTemporalMotion saved = selectSavedMotion(sourceResult, temporalBlock, orderHintBits);
                if (saved == null) {
                    continue;
                }

                int referenceOffset = saved.referenceOffset();
                if (referenceOffset <= 0 || referenceOffset > 31) {
                    continue;
                }

                MotionVector offset = project(saved.motionVector(), projectionDistance, referenceOffset);
                int projectedX8 = x8 + applySign(
                        Math.abs(offset.columnEighthPel()) >> 6,
                        offset.columnEighthPel() ^ sourceSign
                );
                int projectedY8 = y8 + applySign(
                        Math.abs(offset.rowEighthPel()) >> 6,
                        offset.rowEighthPel() ^ sourceSign
                );
                int sourceColumnGroup = x8 & ~7;
                int projectedColumnStart = Math.max(sourceColumnGroup - 8, 0);
                int projectedColumnEnd = Math.min(sourceColumnGroup + 16, width8);
                if (projectedY8 >= projectedRowStart
                        && projectedY8 < projectedRowEnd
                        && projectedX8 >= projectedColumnStart
                        && projectedX8 < projectedColumnEnd) {
                    destination[projectedY8 * width8 + projectedX8] = new ProjectedTemporalBlock(
                            saved.motionVector(),
                            referenceOffset
                    );
                }
            }
        }
    }

    /// Returns whether a saved frame can seed the current frame's temporal motion field.
    ///
    /// Motion fields use an 8x8-aligned 4x4 frame grid, so pixel dimensions may differ within the
    /// same final 8-pixel span without making the saved field incompatible.
    ///
    /// @param sourceFrameType the saved frame type
    /// @param sourceWidth the saved coded width in pixels
    /// @param sourceHeight the saved coded height in pixels
    /// @param currentWidth the current coded width in pixels
    /// @param currentHeight the current coded height in pixels
    /// @return whether the saved frame has an inter motion field with compatible grid dimensions
    static boolean canProjectSourceMotionField(
            Av1FrameType sourceFrameType,
            int sourceWidth,
            int sourceHeight,
            int currentWidth,
            int currentHeight
    ) {
        Av1FrameType nonNullSourceFrameType = Objects.requireNonNull(sourceFrameType, "sourceFrameType");
        return nonNullSourceFrameType != Av1FrameType.KEY
                && nonNullSourceFrameType != Av1FrameType.INTRA
                && ((sourceWidth + 7) >> 3) == ((currentWidth + 7) >> 3)
                && ((sourceHeight + 7) >> 3) == ((currentHeight + 7) >> 3);
    }

    /// Selects the component that a decoded block saves in its reference-frame motion field.
    ///
    /// Eligible components are visited in primary-to-secondary order, so an eligible secondary
    /// component replaces the primary component. Selection intentionally precedes projection
    /// validity checks: a selected half-range component may later make the saved sample unusable,
    /// but must not expose the overwritten primary component as a fallback.
    ///
    /// @param sourceResult the source-frame syntax snapshot
    /// @param block the source temporal block, or `null`
    /// @param orderHintBits the sequence order-hint width
    /// @return the selected projectable motion component, or `null`
    private static SavedTemporalMotion selectSavedMotion(
            ReferenceFrameSyntaxState sourceResult,
            TileDecodeContext.TemporalMotionBlock block,
            int orderHintBits
    ) {
        if (block == null) {
            return null;
        }
        SavedTemporalMotion selected = savedMotion(
                sourceResult,
                block.referenceFrame0(),
                block.motionVector0(),
                orderHintBits
        );
        if (block.compoundReference()) {
            InterMotionVector secondaryVector = block.motionVector1();
            SavedTemporalMotion secondary = savedMotion(
                    sourceResult,
                    block.referenceFrame1(),
                    secondaryVector,
                    orderHintBits
            );
            if (secondary != null) {
                selected = secondary;
            }
        }
        return selected;
    }

    /// Returns one component eligible for reference-frame motion-field storage, or `null`.
    ///
    /// Storage uses the reference-to-source direction test from `ref_frame_side`. The returned
    /// source-to-reference offset remains signed because the modular half-range is negative in
    /// both directions; projection performs its stricter positive-distance check afterward.
    ///
    /// @param sourceResult the source-frame syntax snapshot
    /// @param referenceFrame the source-frame reference in internal LAST..ALTREF order
    /// @param interMotionVector the saved motion-vector state, or `null`
    /// @param orderHintBits the sequence order-hint width
    /// @return one storable motion component and its signed reference offset, or `null`
    private static SavedTemporalMotion savedMotion(
            ReferenceFrameSyntaxState sourceResult,
            int referenceFrame,
            InterMotionVector interMotionVector,
            int orderHintBits
    ) {
        if (interMotionVector == null || referenceFrame < 0 || referenceFrame >= 7) {
            return null;
        }
        FrameHeader sourceHeader = sourceResult.frameHeader();
        FrameHeader referencedHeader = sourceResult.referenceFrameHeader(referenceFrame);
        if (referencedHeader == null) {
            return null;
        }
        MotionVector vector = interMotionVector.vector();
        if (!isSavedTemporalReference(
                orderHintBits,
                sourceHeader.frameOffset(),
                referencedHeader.frameOffset()
        ) || (Math.abs(vector.rowEighthPel()) | Math.abs(vector.columnEighthPel())) >= 4096) {
            return null;
        }
        return new SavedTemporalMotion(
                vector,
                orderHintDifference(
                        orderHintBits,
                        sourceHeader.frameOffset(),
                        referencedHeader.frameOffset()
                )
        );
    }

    /// Returns whether one decoded reference is stored in a source frame's saved motion field.
    ///
    /// The direction must be evaluated as `referenceOffset - sourceOffset`. At exactly half of the
    /// modular order-hint range, reversing the operands still produces a negative value, so this
    /// predicate is not equivalent to requiring a positive source-to-reference distance.
    ///
    /// @param orderHintBits the number of order-hint bits
    /// @param sourceOffset the decoded source frame order hint
    /// @param referenceOffset the decoded component's reference-frame order hint
    /// @return whether the component survives the AV1 reference-side storage filter
    static boolean isSavedTemporalReference(int orderHintBits, int sourceOffset, int referenceOffset) {
        return sourceOffset != referenceOffset
                && orderHintDifference(orderHintBits, referenceOffset, sourceOffset) <= 0;
    }

    /// Selects up to three reference frames whose temporal fields seed the current projection.
    ///
    /// @param assembly the current frame assembly
    /// @param referenceSyntaxResults the syntax snapshots indexed by runtime reference slot
    /// @param orderHintBits the sequence order-hint width
    /// @return the selected references in AV1 overwrite order
    private static int[] selectTemporalSources(
            FrameAssembly assembly,
            ReferenceFrameSyntaxState[] referenceSyntaxResults,
            int orderHintBits
    ) {
        FrameHeader currentHeader = assembly.frameHeader();
        int[] selected = new int[3];
        int count = 0;
        int targetCount = 2;

        ReferenceFrameSyntaxState lastResult = syntaxResult(currentHeader, referenceSyntaxResults, 0);
        FrameHeader currentGoldenHeader = assembly.referenceFrameHeader(3);
        FrameHeader lastAltHeader = lastResult == null
                ? null
                : lastResult.referenceFrameHeader(6);
        if (lastResult != null
                && currentGoldenHeader != null
                && lastAltHeader != null
                && lastAltHeader.frameOffset() != currentGoldenHeader.frameOffset()) {
            selected[count++] = 0;
            targetCount = 3;
        }

        for (int referenceFrame = 4; referenceFrame <= 5 && count < selected.length; referenceFrame++) {
            if (isProjectableFutureTemporalSource(
                    assembly,
                    syntaxResult(currentHeader, referenceSyntaxResults, referenceFrame),
                    referenceFrame,
                    orderHintBits
            )) {
                selected[count++] = referenceFrame;
            }
        }
        if (count < targetCount && isProjectableFutureTemporalSource(
                assembly,
                syntaxResult(currentHeader, referenceSyntaxResults, 6),
                6,
                orderHintBits
        )) {
            selected[count++] = 6;
        }
        if (count < targetCount && syntaxResult(currentHeader, referenceSyntaxResults, 1) != null) {
            selected[count++] = 1;
        }

        int[] result = new int[count];
        System.arraycopy(selected, 0, result, 0, count);
        return result;
    }

    /// Returns the stored syntax result for one current-frame reference, or `null`.
    ///
    /// @param currentHeader the current frame header
    /// @param referenceSyntaxResults the syntax snapshots indexed by runtime reference slot
    /// @param referenceFrame the current reference in internal LAST..ALTREF order
    /// @return the stored syntax result, or `null`
    private static ReferenceFrameSyntaxState syntaxResult(
            FrameHeader currentHeader,
            ReferenceFrameSyntaxState[] referenceSyntaxResults,
            int referenceFrame
    ) {
        int slot = currentHeader.referenceFrameIndex(referenceFrame);
        return slot >= 0 && slot < referenceSyntaxResults.length ? referenceSyntaxResults[slot] : null;
    }

    /// Returns whether one backward reference is a projectable future temporal source.
    ///
    /// An intra frame or incompatible motion-field grid does not consume one of the bounded
    /// temporal projection slots, even when its order hint lies after the current frame.
    ///
    /// @param assembly the current frame assembly
    /// @param sourceResult the candidate source syntax result, or `null`
    /// @param referenceFrame the candidate reference in internal LAST..ALTREF order
    /// @param orderHintBits the sequence order-hint width
    /// @return whether the candidate is a populated and projectable future temporal source
    private static boolean isProjectableFutureTemporalSource(
            FrameAssembly assembly,
            ReferenceFrameSyntaxState sourceResult,
            int referenceFrame,
            int orderHintBits
    ) {
        if (sourceResult == null) {
            return false;
        }
        FrameHeader sourceHeader = assembly.referenceFrameHeader(referenceFrame);
        FrameHeader currentHeader = assembly.frameHeader();
        FrameHeader sourceSyntaxHeader = sourceResult.frameHeader();
        return sourceHeader != null
                && orderHintDifference(
                orderHintBits,
                sourceHeader.frameOffset(),
                currentHeader.frameOffset()
        ) > 0
                && canProjectSourceMotionField(
                sourceSyntaxHeader.frameType(),
                sourceSyntaxHeader.frameSize().codedWidth(),
                sourceSyntaxHeader.frameSize().height(),
                currentHeader.frameSize().codedWidth(),
                currentHeader.frameSize().height()
        );
    }

    /// Scales one motion vector by a signed order-hint ratio and applies AV1 rounding and clipping.
    ///
    /// @param vector the source motion vector in eighth-pel units
    /// @param numerator the signed projection numerator in `[-31, 31]`
    /// @param denominator the positive projection denominator in `[1, 31]`
    /// @return the projected motion vector in eighth-pel units
    private static MotionVector project(MotionVector vector, int numerator, int denominator) {
        MotionVector nonNullVector = Objects.requireNonNull(vector, "vector");
        if (numerator < -31 || numerator > 31) {
            throw new IllegalArgumentException("numerator out of range: " + numerator);
        }
        if (denominator <= 0 || denominator >= DIV_MULTIPLIERS.length) {
            throw new IllegalArgumentException("denominator out of range: " + denominator);
        }
        int fraction = numerator * DIV_MULTIPLIERS[denominator];
        return new MotionVector(
                projectComponent(nonNullVector.rowEighthPel(), fraction),
                projectComponent(nonNullVector.columnEighthPel(), fraction)
        );
    }

    /// Projects one motion-vector component with AV1 signed rounding and clipping.
    ///
    /// @param component the source component in eighth-pel units
    /// @param fraction the signed Q14 scale factor
    /// @return the projected component in eighth-pel units
    private static int projectComponent(int component, int fraction) {
        long scaled = (long) component * fraction;
        long rounded = (scaled + 8192 + (scaled >> 63)) >> 14;
        return clamp((int) rounded, -16383, 16383);
    }

    /// Applies the current frame's signaled motion-vector precision restrictions.
    ///
    /// @param vector the projected motion vector
    /// @param frameHeader the current frame header
    /// @return the precision-normalized motion vector
    private static MotionVector fixPrecision(MotionVector vector, FrameHeader frameHeader) {
        MotionVector nonNullVector = Objects.requireNonNull(vector, "vector");
        FrameHeader nonNullHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        int row = nonNullVector.rowEighthPel();
        int column = nonNullVector.columnEighthPel();
        if (nonNullHeader.forceIntegerMotionVectors()) {
            row = (row - (row >> 15) + 3) & ~7;
            column = (column - (column >> 15) + 3) & ~7;
        } else if (!nonNullHeader.allowHighPrecisionMotionVectors()) {
            row = (row - (row >> 15)) & ~1;
            column = (column - (column >> 15)) & ~1;
        }
        return row == 0 && column == 0 ? MotionVector.zero() : new MotionVector(row, column);
    }

    /// Applies the sign of one signed value to a non-negative magnitude.
    ///
    /// @param magnitude the non-negative magnitude
    /// @param signSource the signed value whose sign is applied
    /// @return the signed magnitude
    private static int applySign(int magnitude, int signSource) {
        return signSource < 0 ? -magnitude : magnitude;
    }

    /// Returns the wrapped order-hint difference `left - right`.
    ///
    /// @param orderHintBits the number of order-hint bits
    /// @param left the minuend order hint
    /// @param right the subtrahend order hint
    /// @return the wrapped signed order-hint difference
    private static int orderHintDifference(int orderHintBits, int left, int right) {
        if (orderHintBits == 0) {
            return 0;
        }
        int signBit = 1 << (orderHintBits - 1);
        int difference = left - right;
        return (difference & (signBit - 1)) - (difference & signBit);
    }

    /// Clamps one integer to an inclusive range.
    ///
    /// @param value the value to clamp
    /// @param minimum the inclusive minimum
    /// @param maximum the inclusive maximum
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// One saved temporal motion component and its signed source-to-reference offset.
    ///
    /// @param motionVector the saved motion vector in eighth-pel units
    /// @param referenceOffset the wrapped source-to-reference order-hint difference
    private record SavedTemporalMotion(MotionVector motionVector, int referenceOffset) {
        /// Creates one validated saved temporal motion component.
        private SavedTemporalMotion {
            Objects.requireNonNull(motionVector, "motionVector");
            if (referenceOffset == 0) {
                throw new IllegalArgumentException("referenceOffset == 0");
            }
        }
    }

    /// One saved temporal motion component projected onto the current frame grid.
    ///
    /// @param motionVector the original saved motion vector in eighth-pel units
    /// @param denominator the positive source-reference distance in `[1, 31]`
    private record ProjectedTemporalBlock(MotionVector motionVector, int denominator) {
        /// Creates one validated projected temporal block.
        private ProjectedTemporalBlock {
            Objects.requireNonNull(motionVector, "motionVector");
            if (denominator <= 0 || denominator > 31) {
                throw new IllegalArgumentException("denominator out of range: " + denominator);
            }
        }
    }
}
