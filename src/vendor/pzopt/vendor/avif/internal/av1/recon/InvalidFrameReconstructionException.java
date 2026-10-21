// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;


/// Signals that reconstructed values violate an AV1 bitstream conformance constraint.

public final class InvalidFrameReconstructionException extends IllegalStateException {
    /// Creates one invalid-frame-reconstruction exception.
    ///
    /// @param message the diagnostic message
    InvalidFrameReconstructionException(String message) {
        super(message);
    }
}
