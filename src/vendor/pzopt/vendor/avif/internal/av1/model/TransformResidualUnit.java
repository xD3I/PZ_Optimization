// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


import java.util.Arrays;
import java.util.Objects;

/// One decoded transform residual unit.
///
/// Coefficients are stored as signed transform-domain levels in natural raster order under the
/// explicit transform type carried by this unit. The metadata also retains the visible write-back
/// rectangle and coefficient-neighbor context produced by AV1 entropy decoding.

public final class TransformResidualUnit {
    /// Shared internal coefficient storage for transform units signaled as all-zero.
    private static final int [] EMPTY_COEFFICIENTS = new int[0];

    /// The block origin of this transform residual unit in luma 4x4 units.
    private final BlockPosition position;

    /// The transform size used by this residual unit.
    private final TransformSize size;

    /// The transform type used by this residual unit.
    private final TransformType transformType;

    /// The scan index of the last non-zero coefficient, or `-1` for all-zero units.
    private final int endOfBlockIndex;

    /// The signed transform-domain coefficients in natural raster order, or empty internal
    /// storage for all-zero and compact DC-only units.
    private final int [] coefficients;

    /// The signed DC coefficient, or zero for an all-zero unit.
    private final int dcCoefficient;

    /// The exact portion of the residual width that lies inside the coded frame or tile boundary.
    private final int visibleWidthPixels;

    /// The exact portion of the residual height that lies inside the coded frame or tile boundary.
    private final int visibleHeightPixels;

    /// The coefficient-context byte written back to neighbor state.
    private final int coefficientContextByte;

    /// Creates one transform residual unit.
    ///
    /// @param position the tile-relative block origin of this transform residual unit in luma 4x4 units
    /// @param size the transform size used by this residual unit
    /// @param transformType the transform type used by this residual unit
    /// @param endOfBlockIndex the scan index of the last non-zero coefficient, or `-1` for all-zero units
    /// @param coefficients the signed transform-domain coefficients in natural raster order
    /// @param visibleWidthPixels the exact portion of the residual width inside the coded frame or tile boundary
    /// @param visibleHeightPixels the exact portion of the residual height inside the coded frame or tile boundary
    /// @param coefficientContextByte the coefficient-context byte written back to neighbor state
    public TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            TransformType transformType,
            int endOfBlockIndex,
            int[] coefficients,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte
    ) {
        this(
                position,
                size,
                transformType,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte,
                true
        );
    }

    /// Creates one residual unit by taking exclusive ownership of its coefficient array.
    ///
    /// The caller must not access or modify `coefficients` after this method returns.
    ///
    /// @param position the residual-unit origin in luma 4x4 units
    /// @param size the transform size
    /// @param transformType the transform type
    /// @param endOfBlockIndex the last non-zero scan index
    /// @param coefficients the exclusively owned natural-order coefficients
    /// @param visibleWidthPixels the visible residual width
    /// @param visibleHeightPixels the visible residual height
    /// @param coefficientContextByte the coefficient-context byte
    /// @return one residual unit backed by `coefficients`
    public static TransformResidualUnit fromOwnedCoefficients(
            BlockPosition position,
            TransformSize size,
            TransformType transformType,
            int endOfBlockIndex,
            int[] coefficients,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte
    ) {
        return new TransformResidualUnit(
                position,
                size,
                transformType,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte,
                false
        );
    }

    /// Creates one residual unit with copied or exclusively transferred coefficients.
    ///
    /// @param position the residual-unit origin
    /// @param size the transform size
    /// @param transformType the transform type
    /// @param endOfBlockIndex the last non-zero scan index
    /// @param coefficients the natural-order coefficients
    /// @param visibleWidthPixels the visible residual width
    /// @param visibleHeightPixels the visible residual height
    /// @param coefficientContextByte the coefficient-context byte
    /// @param copyCoefficients whether to copy non-zero coefficient storage
    private TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            TransformType transformType,
            int endOfBlockIndex,
            int[] coefficients,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte,
            boolean copyCoefficients
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.size = Objects.requireNonNull(size, "size");
        this.transformType = Objects.requireNonNull(transformType, "transformType");
        if (endOfBlockIndex < -1 || endOfBlockIndex >= size.widthPixels() * size.heightPixels()) {
            throw new IllegalArgumentException("endOfBlockIndex out of range: " + endOfBlockIndex);
        }
        this.endOfBlockIndex = endOfBlockIndex;
        int[] checkedCoefficients = Objects.requireNonNull(coefficients, "coefficients");
        if (checkedCoefficients.length != size.widthPixels() * size.heightPixels()) {
            throw new IllegalArgumentException("coefficients length does not match transform area");
        }
        if (visibleWidthPixels <= 0 || visibleWidthPixels > size.widthPixels()) {
            throw new IllegalArgumentException("visibleWidthPixels out of range: " + visibleWidthPixels);
        }
        if (visibleHeightPixels <= 0 || visibleHeightPixels > size.heightPixels()) {
            throw new IllegalArgumentException("visibleHeightPixels out of range: " + visibleHeightPixels);
        }
        if (endOfBlockIndex < 0) {
            for (int coefficient : checkedCoefficients) {
                if (coefficient != 0) {
                    throw new IllegalArgumentException("all-zero residual units must not carry coefficients");
                }
            }
        }
        this.coefficients = endOfBlockIndex < 0
                ? EMPTY_COEFFICIENTS
                : copyCoefficients ? Arrays.copyOf(checkedCoefficients, checkedCoefficients.length) : checkedCoefficients;
        this.dcCoefficient = endOfBlockIndex < 0 ? 0 : checkedCoefficients[0];
        this.visibleWidthPixels = visibleWidthPixels;
        this.visibleHeightPixels = visibleHeightPixels;
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
        this.coefficientContextByte = coefficientContextByte;
    }

    /// Creates one DC-only residual unit without allocating a full coefficient block.
    ///
    /// [#coefficient(int)] and [#coefficients()] continue to expose the complete natural-order
    /// coefficient block, with every AC coefficient equal to zero.
    ///
    /// @param position the residual-unit origin in luma 4x4 units
    /// @param size the transform size
    /// @param transformType the transform type
    /// @param dcCoefficient the signed DC coefficient
    /// @param visibleWidthPixels the visible residual width
    /// @param visibleHeightPixels the visible residual height
    /// @param coefficientContextByte the coefficient-context byte
    /// @return one compact DC-only residual unit
    public static TransformResidualUnit dcOnly(
            BlockPosition position,
            TransformSize size,
            TransformType transformType,
            int dcCoefficient,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte
    ) {
        return new TransformResidualUnit(
                position,
                size,
                transformType,
                dcCoefficient,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte
        );
    }

    /// Creates one compact DC-only transform residual unit.
    ///
    /// @param position the residual-unit origin in luma 4x4 units
    /// @param size the transform size
    /// @param transformType the transform type
    /// @param dcCoefficient the signed DC coefficient
    /// @param visibleWidthPixels the visible residual width
    /// @param visibleHeightPixels the visible residual height
    /// @param coefficientContextByte the coefficient-context byte
    private TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            TransformType transformType,
            int dcCoefficient,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.size = Objects.requireNonNull(size, "size");
        this.transformType = Objects.requireNonNull(transformType, "transformType");
        this.endOfBlockIndex = 0;
        this.coefficients = EMPTY_COEFFICIENTS;
        this.dcCoefficient = dcCoefficient;
        if (visibleWidthPixels <= 0 || visibleWidthPixels > size.widthPixels()) {
            throw new IllegalArgumentException("visibleWidthPixels out of range: " + visibleWidthPixels);
        }
        if (visibleHeightPixels <= 0 || visibleHeightPixels > size.heightPixels()) {
            throw new IllegalArgumentException("visibleHeightPixels out of range: " + visibleHeightPixels);
        }
        this.visibleWidthPixels = visibleWidthPixels;
        this.visibleHeightPixels = visibleHeightPixels;
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
        this.coefficientContextByte = coefficientContextByte;
    }

    /// Creates one all-zero `DCT_DCT` residual unit without allocating coefficient storage.
    ///
    /// @param position the tile-relative block origin in luma 4x4 units
    /// @param size the transform size used by this residual unit
    /// @param visibleWidthPixels the exact portion of the residual width inside the coded frame or tile boundary
    /// @param visibleHeightPixels the exact portion of the residual height inside the coded frame or tile boundary
    /// @param coefficientContextByte the coefficient-context byte written back to neighbor state
    /// @return one all-zero transform residual unit
    public static TransformResidualUnit allZero(
            BlockPosition position,
            TransformSize size,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte
    ) {
        return new TransformResidualUnit(
                position,
                size,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte
        );
    }

    /// Creates one all-zero transform residual unit using the shared empty coefficient representation.
    ///
    /// @param position the tile-relative block origin in luma 4x4 units
    /// @param size the transform size used by this residual unit
    /// @param visibleWidthPixels the exact portion of the residual width inside the coded frame or tile boundary
    /// @param visibleHeightPixels the exact portion of the residual height inside the coded frame or tile boundary
    /// @param coefficientContextByte the coefficient-context byte written back to neighbor state
    private TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.size = Objects.requireNonNull(size, "size");
        this.transformType = TransformType.DCT_DCT;
        this.endOfBlockIndex = -1;
        this.coefficients = EMPTY_COEFFICIENTS;
        this.dcCoefficient = 0;
        if (visibleWidthPixels <= 0 || visibleWidthPixels > size.widthPixels()) {
            throw new IllegalArgumentException("visibleWidthPixels out of range: " + visibleWidthPixels);
        }
        if (visibleHeightPixels <= 0 || visibleHeightPixels > size.heightPixels()) {
            throw new IllegalArgumentException("visibleHeightPixels out of range: " + visibleHeightPixels);
        }
        this.visibleWidthPixels = visibleWidthPixels;
        this.visibleHeightPixels = visibleHeightPixels;
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
        this.coefficientContextByte = coefficientContextByte;
    }

    /// Creates a position-adjusted unit that shares the source unit's immutable coefficients.
    ///
    /// @param position the replacement residual-unit position
    /// @param source the residual unit whose non-position state is retained
    private TransformResidualUnit(BlockPosition position, TransformResidualUnit source) {
        this.position = Objects.requireNonNull(position, "position");
        TransformResidualUnit nonNullSource = Objects.requireNonNull(source, "source");
        this.size = nonNullSource.size;
        this.transformType = nonNullSource.transformType;
        this.endOfBlockIndex = nonNullSource.endOfBlockIndex;
        this.coefficients = nonNullSource.coefficients;
        this.dcCoefficient = nonNullSource.dcCoefficient;
        this.visibleWidthPixels = nonNullSource.visibleWidthPixels;
        this.visibleHeightPixels = nonNullSource.visibleHeightPixels;
        this.coefficientContextByte = nonNullSource.coefficientContextByte;
    }

    /// Creates one transform residual unit whose exact visible footprint matches the coded
    /// transform size.
    ///
    /// @param position the tile-relative block origin of this transform residual unit in luma 4x4 units
    /// @param size the transform size used by this residual unit
    /// @param transformType the transform type used by this residual unit
    /// @param endOfBlockIndex the scan index of the last non-zero coefficient, or `-1` for all-zero units
    /// @param coefficients the signed transform-domain coefficients in natural raster order
    /// @param coefficientContextByte the coefficient-context byte written back to neighbor state
    public TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            TransformType transformType,
            int endOfBlockIndex,
            int[] coefficients,
            int coefficientContextByte
    ) {
        this(
                position,
                size,
                transformType,
                endOfBlockIndex,
                coefficients,
                size.widthPixels(),
                size.heightPixels(),
                coefficientContextByte
        );
    }

    /// Returns the block origin of this transform residual unit in luma 4x4 units.
    ///
    /// @return the block origin of this transform residual unit in luma 4x4 units
    public BlockPosition position() {
        return position;
    }

    /// Returns a copy of this residual unit with a replaced position.
    ///
    /// @param position the replacement residual-unit position
    /// @return a copy of this residual unit with a replaced position
    public TransformResidualUnit withPosition(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        if (this.position.x4() == nonNullPosition.x4() && this.position.y4() == nonNullPosition.y4()) {
            return this;
        }
        return new TransformResidualUnit(nonNullPosition, this);
    }

    /// Returns the transform size used by this residual unit.
    ///
    /// @return the transform size used by this residual unit
    public TransformSize size() {
        return size;
    }

    /// Returns the transform type used by this residual unit.
    ///
    /// @return the transform type used by this residual unit
    public TransformType transformType() {
        return transformType;
    }

    /// Returns whether the transform block is signaled as all-zero through `txb_skip`.
    ///
    /// @return whether the transform block is signaled as all-zero through `txb_skip`
    public boolean allZero() {
        return endOfBlockIndex < 0;
    }

    /// Returns the scan index of the last non-zero coefficient, or `-1` for all-zero units.
    ///
    /// @return the scan index of the last non-zero coefficient, or `-1` for all-zero units
    public int endOfBlockIndex() {
        return endOfBlockIndex;
    }

    /// Returns the signed transform-domain coefficients in natural raster order.
    ///
    /// @return the signed transform-domain coefficients in natural raster order
    public int[] coefficients() {
        if (coefficients.length != 0) {
            return Arrays.copyOf(coefficients, coefficients.length);
        }
        int[] result = new int[coefficientCount()];
        if (!allZero()) {
            result[0] = dcCoefficient;
        }
        return result;
    }

    /// Returns the number of coefficients in this transform unit.
    ///
    /// @return the transform area in coefficients
    public int coefficientCount() {
        return size.widthPixels() * size.heightPixels();
    }

    /// Returns one signed transform-domain coefficient without copying the coefficient block.
    ///
    /// All-zero units return `0` for every valid coefficient index.
    ///
    /// @param coefficientIndex the natural raster-order coefficient index
    /// @return the signed transform-domain coefficient at `coefficientIndex`
    public int coefficient(int coefficientIndex) {
        Objects.checkIndex(coefficientIndex, coefficientCount());
        if (coefficients.length == 0) {
            return coefficientIndex == 0 ? dcCoefficient : 0;
        }
        return coefficients[coefficientIndex];
    }

    /// Returns the exact portion of the residual width inside the coded frame or tile boundary.
    ///
    /// @return the exact visible residual width in pixels
    public int visibleWidthPixels() {
        return visibleWidthPixels;
    }

    /// Returns the exact portion of the residual height inside the coded frame or tile boundary.
    ///
    /// @return the exact visible residual height in pixels
    public int visibleHeightPixels() {
        return visibleHeightPixels;
    }

    /// Returns the decoded DC coefficient in natural raster order.
    ///
    /// @return the decoded DC coefficient in natural raster order
    public int dcCoefficient() {
        return dcCoefficient;
    }

    /// Returns the coefficient-context byte written back to neighbor state.
    ///
    /// @return the coefficient-context byte written back to neighbor state
    public int coefficientContextByte() {
        return coefficientContextByte;
    }
}
