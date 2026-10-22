// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// The AV1 recursive filter-intra modes in bitstream order.

public enum FilterIntraMode {
    /// Recursive DC filter intra prediction.
    DC,
    /// Recursive vertical filter intra prediction.
    VERTICAL,
    /// Recursive horizontal filter intra prediction.
    HORIZONTAL,
    /// Recursive diagonal-157 filter intra prediction.
    DIAGONAL_157,
    /// Recursive Paeth-style filter intra prediction.
    PAETH;

    /// The mode constants cached in bitstream symbol order.
    private static final FilterIntraMode [] VALUES = values();

    /// Returns the bitstream symbol index of this filter intra mode.
    ///
    /// @return the bitstream symbol index of this filter intra mode
    public int symbolIndex() {
        return ordinal();
    }

    /// Maps a decoded bitstream symbol index back to a filter intra mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the filter intra mode for the supplied symbol index
    public static FilterIntraMode fromSymbolIndex(int symbolIndex) {
        if (symbolIndex < 0 || symbolIndex >= VALUES.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return VALUES[symbolIndex];
    }
}
