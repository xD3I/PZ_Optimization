// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


/// Supported AVIF decoded sample bit depths.

public enum AvifBitDepth {
    /// Eight bits per decoded sample.
    EIGHT_BITS(8, AvifPixelFormat.ARGB_8888),
    /// Ten bits per decoded sample.
    TEN_BITS(10, AvifPixelFormat.ARGB_16161616),
    /// Twelve bits per decoded sample.
    TWELVE_BITS(12, AvifPixelFormat.ARGB_16161616),
    /// Sixteen bits per reconstructed sample.
    SIXTEEN_BITS(16, AvifPixelFormat.ARGB_16161616);

    /// The decoded sample bit count.
    private final int bits;
    /// The default packed output pixel format for this bit depth.
    private final AvifPixelFormat defaultPixelFormat;

    /// Creates a supported decoded sample bit depth.
    ///
    /// @param bits the decoded sample bit count
    /// @param defaultPixelFormat the default packed output pixel format
    AvifBitDepth(int bits, AvifPixelFormat defaultPixelFormat) {
        this.bits = bits;
        this.defaultPixelFormat = defaultPixelFormat;
    }

    /// Returns the decoded sample bit count.
    ///
    /// @return the decoded sample bit count
    public int bits() {
        return bits;
    }

    /// Returns the largest sample value representable by this bit depth.
    ///
    /// @return the largest sample value
    public int maxSampleValue() {
        return (1 << bits) - 1;
    }

    /// Returns the default packed output pixel format for this bit depth.
    ///
    /// Readers may use another format when the caller explicitly selects one.
    ///
    /// @return `ARGB_8888` for 8-bit samples, otherwise `ARGB_16161616`
    public AvifPixelFormat defaultPixelFormat() {
        return defaultPixelFormat;
    }

    /// Returns whether this is the 8-bit output path.
    ///
    /// @return `true` for 8-bit output
    public boolean isEightBit() {
        return this == EIGHT_BITS;
    }

    /// Returns whether this is a high-bit-depth output path.
    ///
    /// @return `true` for 10-bit, 12-bit, or 16-bit output
    public boolean isHighBitDepth() {
        return this != EIGHT_BITS;
    }

    /// Maps a numeric bit count to a supported decoded sample bit depth.
    ///
    /// @param bits the decoded sample bit count
    /// @return the matching bit depth
    public static AvifBitDepth fromBits(int bits) {
        return switch (bits) {
            case 8 -> EIGHT_BITS;
            case 10 -> TEN_BITS;
            case 12 -> TWELVE_BITS;
            case 16 -> SIXTEEN_BITS;
            default -> throw new IllegalArgumentException("Unsupported bit depth: " + bits);
        };
    }

    /// Returns the numeric bit depth text.
    ///
    /// @return the numeric bit depth text
    @Override
    public String toString() {
        return Integer.toString(bits);
    }
}
