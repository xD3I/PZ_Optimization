// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// The thirteen AV1 luma intra prediction modes in bitstream order.

public enum LumaIntraPredictionMode {
    /// DC prediction.
    DC(0),
    /// Vertical prediction.
    VERTICAL(1),
    /// Horizontal prediction.
    HORIZONTAL(2),
    /// Diagonal-down-left prediction.
    DIAGONAL_DOWN_LEFT(3),
    /// Diagonal-down-right prediction.
    DIAGONAL_DOWN_RIGHT(4),
    /// Vertical-right prediction.
    VERTICAL_RIGHT(4),
    /// Horizontal-down prediction.
    HORIZONTAL_DOWN(4),
    /// Horizontal-up prediction.
    HORIZONTAL_UP(4),
    /// Vertical-left prediction.
    VERTICAL_LEFT(3),
    /// Smooth prediction.
    SMOOTH(0),
    /// Smooth vertical prediction.
    SMOOTH_VERTICAL(1),
    /// Smooth horizontal prediction.
    SMOOTH_HORIZONTAL(2),
    /// Paeth prediction.
    PAETH(0);

    /// The mode constants cached in bitstream symbol order.
    private static final LumaIntraPredictionMode [] VALUES = values();

    /// The coarsened intra-mode context class used by key-frame Y-mode CDFs.
    private final int contextIndex;

    /// Creates one luma intra prediction mode entry.
    ///
    /// @param contextIndex the coarsened intra-mode context class used by key-frame Y-mode CDFs
    LumaIntraPredictionMode(int contextIndex) {
        this.contextIndex = contextIndex;
    }

    /// Returns the coarsened intra-mode context class used by key-frame Y-mode CDFs.
    ///
    /// @return the coarsened intra-mode context class used by key-frame Y-mode CDFs
    public int contextIndex() {
        return contextIndex;
    }

    /// Returns the bitstream symbol index of this mode.
    ///
    /// @return the bitstream symbol index of this mode
    public int symbolIndex() {
        return ordinal();
    }

    /// Returns whether this mode uses the directional angle-delta syntax element.
    ///
    /// @return whether this mode uses the directional angle-delta syntax element
    public boolean isDirectional() {
        return ordinal() >= VERTICAL.ordinal() && ordinal() <= VERTICAL_LEFT.ordinal();
    }

    /// Returns the zero-based directional angle-delta CDF index for this mode.
    ///
    /// @return the zero-based directional angle-delta CDF index for this mode
    public int angleDeltaContextIndex() {
        if (!isDirectional()) {
            throw new IllegalStateException("Mode does not use angle_delta: " + this);
        }
        return ordinal() - VERTICAL.ordinal();
    }

    /// Maps a decoded bitstream symbol index back to a luma intra prediction mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the luma intra prediction mode for the supplied symbol index
    public static LumaIntraPredictionMode fromSymbolIndex(int symbolIndex) {
        if (symbolIndex < 0 || symbolIndex >= VALUES.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return VALUES[symbolIndex];
    }
}
