// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// A block origin expressed in 4x4 units within the caller's active coordinate space.
///
/// @param x4 the X coordinate in 4x4 units
/// @param y4 the Y coordinate in 4x4 units

public record BlockPosition(int x4, int y4) {
    /// Creates a block origin expressed in 4x4 units.
    public BlockPosition {
        if (x4 < 0) {
            throw new IllegalArgumentException("x4 < 0: " + x4);
        }
        if (y4 < 0) {
            throw new IllegalArgumentException("y4 < 0: " + y4);
        }
    }

    /// Returns the X coordinate in 8x8 units.
    ///
    /// @return the X coordinate in 8x8 units
    public int x8() {
        return x4 >> 1;
    }

    /// Returns the Y coordinate in 8x8 units.
    ///
    /// @return the Y coordinate in 8x8 units
    public int y8() {
        return y4 >> 1;
    }

    /// Returns this position offset by the supplied 4x4-unit delta.
    ///
    /// @param deltaX4 the X-axis offset in 4x4 units
    /// @param deltaY4 the Y-axis offset in 4x4 units
    /// @return the offset position, or this position when both deltas are zero
    public BlockPosition offset(int deltaX4, int deltaY4) {
        if (deltaX4 == 0 && deltaY4 == 0) {
            return this;
        }
        return new BlockPosition(x4 + deltaX4, y4 + deltaY4);
    }
}
