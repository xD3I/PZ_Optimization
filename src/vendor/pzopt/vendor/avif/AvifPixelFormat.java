// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


/// Packed non-premultiplied ARGB formats exposed by decoded AVIF frames.

public enum AvifPixelFormat {
    /// Stores each pixel in an `IntBuffer` element as `0xAARRGGBB`.
    ARGB_8888,
    /// Stores each pixel in a `LongBuffer` element as `0xAAAA_RRRR_GGGG_BBBB`.
    ARGB_16161616
}
