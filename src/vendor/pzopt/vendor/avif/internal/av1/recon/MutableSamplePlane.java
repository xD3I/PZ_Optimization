// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;


/// Provides the mutable sample operations required by intra prediction.
///
/// Coordinates use the containing plane's sample grid. Implementations may store the whole plane,
/// retain only the reconstruction region supplied to callers, or expose a writable overlay backed
/// by another plane.

interface MutableSamplePlane {
    /// Returns the plane width in samples.
    ///
    /// @return the plane width in samples
    int width();

    /// Returns the plane height in samples.
    ///
    /// @return the plane height in samples
    int height();

    /// Returns the decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    int bitDepth();

    /// Returns the sample at one in-range plane coordinate.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return the sample value
    int sample(int x, int y);

    /// Stores one sample at an in-range writable coordinate.
    ///
    /// @param x     the zero-based horizontal sample coordinate
    /// @param y     the zero-based vertical sample coordinate
    /// @param value the sample value, clipped to the legal bit-depth range
    void setSample(int x, int y, int value);

    /// Fills the in-plane portion of one rectangular block with a constant sample value.
    ///
    /// The origin must be an in-range writable coordinate. Samples beyond the right or bottom
    /// plane edge are ignored, matching coded-block reconstruction at visible frame boundaries.
    ///
    /// @param x the zero-based horizontal block origin
    /// @param y the zero-based vertical block origin
    /// @param blockWidth the positive coded block width in samples
    /// @param blockHeight the positive coded block height in samples
    /// @param value the sample value, clipped to the legal bit-depth range
    void fillBlock(int x, int y, int blockWidth, int blockHeight, int value);
}
