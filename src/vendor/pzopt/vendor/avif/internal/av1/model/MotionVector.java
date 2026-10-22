// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// One AV1 motion vector stored in eighth-pel units.
///
/// @param rowEighthPel the signed vertical component in eighth-pel units
/// @param columnEighthPel the signed horizontal component in eighth-pel units

public record MotionVector(int rowEighthPel, int columnEighthPel) {
    /// The shared zero motion-vector instance.
    private static final MotionVector ZERO = new MotionVector(0, 0);

    /// Returns the shared zero motion-vector instance.
    ///
    /// @return the shared zero motion-vector instance
    public static MotionVector zero() {
        return ZERO;
    }
}
