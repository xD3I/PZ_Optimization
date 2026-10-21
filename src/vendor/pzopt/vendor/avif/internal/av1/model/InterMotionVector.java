// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


import java.util.Objects;

/// One inter motion-vector state that may already be final or still provisional.
///
/// @param vector the underlying motion-vector value in eighth-pel units
/// @param resolved whether the vector is final for the current block

public record InterMotionVector(MotionVector vector, boolean resolved) {
    /// Creates one inter motion-vector state.
    public InterMotionVector {
        Objects.requireNonNull(vector, "vector");
    }

    /// Creates one final inter motion-vector state.
    ///
    /// @param vector the underlying motion-vector value in eighth-pel units
    /// @return one final inter motion-vector state
    public static InterMotionVector resolved(MotionVector vector) {
        return new InterMotionVector(vector, true);
    }

    /// Creates one provisional inter motion-vector state.
    ///
    /// @param vector the underlying motion-vector value in eighth-pel units
    /// @return one provisional inter motion-vector state
    public static InterMotionVector predicted(MotionVector vector) {
        return new InterMotionVector(vector, false);
    }

    /// Returns this motion vector downgraded to a provisional predictor.
    ///
    /// @return this motion vector downgraded to a provisional predictor
    public InterMotionVector asPredicted() {
        return resolved ? new InterMotionVector(vector, false) : this;
    }

    /// Returns this motion vector promoted to a final block vector.
    ///
    /// @return this motion vector promoted to a final block vector
    public InterMotionVector asResolved() {
        return resolved ? this : new InterMotionVector(vector, true);
    }
}
