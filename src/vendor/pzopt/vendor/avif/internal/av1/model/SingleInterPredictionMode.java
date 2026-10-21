// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// The AV1 single-reference inter prediction modes in bitstream order.

public enum SingleInterPredictionMode {
    /// The nearest-motion-vector mode.
    NEARESTMV,
    /// The near-motion-vector mode.
    NEARMV,
    /// The global-motion mode.
    GLOBALMV,
    /// The new-motion-vector mode.
    NEWMV;

    /// The mode constants cached in bitstream symbol order.
    private static final SingleInterPredictionMode [] VALUES = values();

    /// Returns the bitstream symbol index of this mode.
    ///
    /// @return the bitstream symbol index of this mode
    public int symbolIndex() {
        return ordinal();
    }

    /// Maps one decoded bitstream symbol index back to a single-reference inter prediction mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the single-reference inter prediction mode for the supplied symbol index
    public static SingleInterPredictionMode fromSymbolIndex(int symbolIndex) {
        if (symbolIndex < 0 || symbolIndex >= VALUES.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return VALUES[symbolIndex];
    }
}
