// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// One one-dimensional kernel used by a separable AV1 transform type.

public enum TransformKernel {
    /// The inverse discrete cosine transform kernel.
    DCT,
    /// The inverse asymmetric discrete sine transform kernel.
    ADST,
    /// The inverse asymmetric discrete sine transform kernel with flipped spatial output.
    FLIPADST,
    /// The inverse Walsh-Hadamard transform kernel used by AV1 lossless 4x4 blocks.
    WHT,
    /// The identity transform kernel.
    IDENTITY
}
