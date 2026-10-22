// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// The AV1 block partition symbols in bitstream order.

public enum PartitionType {
    /// No split.
    NONE,
    /// Horizontal split.
    HORIZONTAL,
    /// Vertical split.
    VERTICAL,
    /// Four-way split.
    SPLIT,
    /// Top half split plus bottom horizontal partition.
    T_TOP_SPLIT,
    /// Top horizontal partition plus bottom split.
    T_BOTTOM_SPLIT,
    /// Left half split plus right vertical partition.
    T_LEFT_SPLIT,
    /// Left vertical partition plus right split.
    T_RIGHT_SPLIT,
    /// Four horizontal stripes.
    HORIZONTAL_4,
    /// Four vertical stripes.
    VERTICAL_4;

    /// The partition constants cached in bitstream symbol order.
    private static final PartitionType [] VALUES = values();

    /// Returns the bitstream symbol index of this partition type.
    ///
    /// @return the bitstream symbol index of this partition type
    public int symbolIndex() {
        return ordinal();
    }

    /// Maps a decoded bitstream symbol index back to a partition type.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the partition type for the supplied symbol index
    public static PartitionType fromSymbolIndex(int symbolIndex) {
        if (symbolIndex < 0 || symbolIndex >= VALUES.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return VALUES[symbolIndex];
    }
}
