// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// One AV1 rectangular transform size.
///
/// Width and height are expressed in luma-grid 4x4 units to match the existing block-position
/// model used by the structural syntax reader.

public enum TransformSize {
    /// A 4x4 transform.
    TX_4X4(1, 1, 0, 0, 0, 0, 0, null),
    /// An 8x8 transform.
    TX_8X8(2, 2, 1, 1, 1, 1, 1, TX_4X4),
    /// A 16x16 transform.
    TX_16X16(4, 4, 2, 2, 2, 2, 2, TX_8X8),
    /// A 32x32 transform.
    TX_32X32(8, 8, 3, 3, 3, 3, 3, TX_16X16),
    /// A 64x64 transform.
    TX_64X64(16, 16, 4, 4, 4, 4, 4, TX_32X32),
    /// A 4x8 transform.
    RTX_4X8(1, 2, 0, 1, 0, 1, 1, TX_4X4),
    /// An 8x4 transform.
    RTX_8X4(2, 1, 1, 0, 0, 1, 1, TX_4X4),
    /// An 8x16 transform.
    RTX_8X16(2, 4, 1, 2, 1, 2, 2, TX_8X8),
    /// A 16x8 transform.
    RTX_16X8(4, 2, 2, 1, 1, 2, 2, TX_8X8),
    /// A 16x32 transform.
    RTX_16X32(4, 8, 2, 3, 2, 3, 3, TX_16X16),
    /// A 32x16 transform.
    RTX_32X16(8, 4, 3, 2, 2, 3, 3, TX_16X16),
    /// A 32x64 transform.
    RTX_32X64(8, 16, 3, 4, 3, 4, 4, TX_32X32),
    /// A 64x32 transform.
    RTX_64X32(16, 8, 4, 3, 3, 4, 4, TX_32X32),
    /// A 4x16 transform.
    RTX_4X16(1, 4, 0, 2, 0, 2, 1, RTX_4X8),
    /// A 16x4 transform.
    RTX_16X4(4, 1, 2, 0, 0, 2, 1, RTX_8X4),
    /// An 8x32 transform.
    RTX_8X32(2, 8, 1, 3, 1, 3, 2, RTX_8X16),
    /// A 32x8 transform.
    RTX_32X8(8, 2, 3, 1, 1, 3, 2, RTX_16X8),
    /// A 16x64 transform.
    RTX_16X64(4, 16, 2, 4, 2, 4, 3, RTX_16X32),
    /// A 64x16 transform.
    RTX_64X16(16, 4, 4, 2, 2, 4, 3, RTX_32X16);

    /// The transform-size constants cached in declaration order.
    private static final TransformSize [] VALUES = values();

    /// The transform width in 4x4 units.
    private final int width4;

    /// The transform height in 4x4 units.
    private final int height4;

    /// The base-2 logarithm of the transform width in 4x4 units.
    private final int log2Width4;

    /// The base-2 logarithm of the transform height in 4x4 units.
    private final int log2Height4;

    /// The smallest square transform level touched by this rectangular size.
    private final int minSquareLevel;

    /// The largest square transform level touched by this rectangular size.
    private final int maxSquareLevel;

    /// The AV1 coefficient-context group index used by coefficient skip and token CDF tables.
    private final int coefficientContextIndex;

    /// The next smaller transform size used when descending transform-depth trees, or `null`.
    private final TransformSize subSize;

    /// Creates one AV1 transform-size entry.
    ///
    /// @param width4 the transform width in 4x4 units
    /// @param height4 the transform height in 4x4 units
    /// @param log2Width4 the base-2 logarithm of the transform width in 4x4 units
    /// @param log2Height4 the base-2 logarithm of the transform height in 4x4 units
    /// @param minSquareLevel the smallest square transform level touched by this rectangular size
    /// @param maxSquareLevel the largest square transform level touched by this rectangular size
    /// @param coefficientContextIndex the AV1 coefficient-context group index used by coefficient CDF tables
    /// @param subSize the next smaller transform size used when descending transform-depth trees, or `null`
    TransformSize(
            int width4,
            int height4,
            int log2Width4,
            int log2Height4,
            int minSquareLevel,
            int maxSquareLevel,
            int coefficientContextIndex,
            TransformSize subSize
    ) {
        this.width4 = width4;
        this.height4 = height4;
        this.log2Width4 = log2Width4;
        this.log2Height4 = log2Height4;
        this.minSquareLevel = minSquareLevel;
        this.maxSquareLevel = maxSquareLevel;
        this.coefficientContextIndex = coefficientContextIndex;
        this.subSize = subSize;
    }

    /// Returns the transform width in 4x4 units.
    ///
    /// @return the transform width in 4x4 units
    public int width4() {
        return width4;
    }

    /// Returns the transform height in 4x4 units.
    ///
    /// @return the transform height in 4x4 units
    public int height4() {
        return height4;
    }

    /// Returns the transform width in pixels.
    ///
    /// @return the transform width in pixels
    public int widthPixels() {
        return width4 << 2;
    }

    /// Returns the transform height in pixels.
    ///
    /// @return the transform height in pixels
    public int heightPixels() {
        return height4 << 2;
    }

    /// Returns the base-2 logarithm of the transform width in 4x4 units.
    ///
    /// @return the base-2 logarithm of the transform width in 4x4 units
    public int log2Width4() {
        return log2Width4;
    }

    /// Returns the base-2 logarithm of the transform height in 4x4 units.
    ///
    /// @return the base-2 logarithm of the transform height in 4x4 units
    public int log2Height4() {
        return log2Height4;
    }

    /// Returns the smallest square transform level touched by this rectangular size.
    ///
    /// @return the smallest square transform level touched by this rectangular size
    public int minSquareLevel() {
        return minSquareLevel;
    }

    /// Returns the largest square transform level touched by this rectangular size.
    ///
    /// @return the largest square transform level touched by this rectangular size
    public int maxSquareLevel() {
        return maxSquareLevel;
    }

    /// Returns the AV1 coefficient-context group index used by coefficient skip and token CDF tables.
    ///
    /// @return the AV1 coefficient-context group index used by coefficient skip and token CDF tables
    public int coefficientContextIndex() {
        return coefficientContextIndex;
    }

    /// Returns whether this transform size is square.
    ///
    /// @return whether this transform size is square
    public boolean isSquare() {
        return width4 == height4;
    }

    /// Returns whether this transform size has a smaller transform-size descendant.
    ///
    /// @return whether this transform size has a smaller transform-size descendant
    public boolean hasSubSize() {
        return subSize != null;
    }

    /// Returns the next smaller transform size used when descending transform-depth trees.
    ///
    /// @return the next smaller transform size used when descending transform-depth trees
    public TransformSize subSize() {
        if (subSize == null) {
            throw new IllegalStateException("Transform size has no smaller descendant: " + this);
        }
        return subSize;
    }

    /// Returns the transform size that exactly matches the supplied 4x4-unit dimensions, or `null`.
    ///
    /// @param width4 the width in 4x4 units
    /// @param height4 the height in 4x4 units
    /// @return the transform size that exactly matches the supplied 4x4-unit dimensions, or `null`
    public static TransformSize forDimensions(int width4, int height4) {
        for (TransformSize value : VALUES) {
            if (value.width4 == width4 && value.height4 == height4) {
                return value;
            }
        }
        return null;
    }
}
