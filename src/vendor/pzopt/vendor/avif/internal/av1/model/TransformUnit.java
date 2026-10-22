// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


import java.util.Objects;

/// One transform unit inside a decoded block-level transform layout.
///
/// @param position the tile-relative shared luma-grid origin of this transform unit
/// @param size the transform size used by this transform unit

public record TransformUnit(BlockPosition position, TransformSize size) {
    /// Creates one transform unit.
    public TransformUnit {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(size, "size");
    }

    /// Returns a copy of this transform unit with a replaced position.
    ///
    /// @param position the replacement transform-unit position
    /// @return a copy of this transform unit with a replaced position
    public TransformUnit withPosition(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        if (this.position.x4() == nonNullPosition.x4() && this.position.y4() == nonNullPosition.y4()) {
            return this;
        }
        return new TransformUnit(nonNullPosition, size);
    }
}
