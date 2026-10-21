// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


/// The AV1 inter-intra prediction modes in bitstream order.

public enum InterIntraPredictionMode {
    /// DC inter-intra prediction.
    DC,
    /// Vertical inter-intra prediction.
    VERTICAL,
    /// Horizontal inter-intra prediction.
    HORIZONTAL,
    /// Smooth inter-intra prediction.
    SMOOTH;

    /// The mode constants cached in bitstream symbol order.
    private static final InterIntraPredictionMode [] VALUES = values();

    /// Maps one decoded bitstream symbol index back to an inter-intra prediction mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the inter-intra prediction mode for the supplied symbol index
    public static InterIntraPredictionMode fromSymbolIndex(int symbolIndex) {
        if (symbolIndex < 0 || symbolIndex >= VALUES.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return VALUES[symbolIndex];
    }

    /// Returns the matching luma intra prediction mode used to build the secondary predictor.
    ///
    /// @return the matching luma intra prediction mode used to build the secondary predictor
    public LumaIntraPredictionMode toLumaPredictionMode() {
        return switch (this) {
            case DC -> LumaIntraPredictionMode.DC;
            case VERTICAL -> LumaIntraPredictionMode.VERTICAL;
            case HORIZONTAL -> LumaIntraPredictionMode.HORIZONTAL;
            case SMOOTH -> LumaIntraPredictionMode.SMOOTH;
        };
    }

    /// Returns the matching chroma intra prediction mode used to build the secondary predictor.
    ///
    /// @return the matching chroma intra prediction mode used to build the secondary predictor
    public UvIntraPredictionMode toUvPredictionMode() {
        return switch (this) {
            case DC -> UvIntraPredictionMode.DC;
            case VERTICAL -> UvIntraPredictionMode.VERTICAL;
            case HORIZONTAL -> UvIntraPredictionMode.HORIZONTAL;
            case SMOOTH -> UvIntraPredictionMode.SMOOTH;
        };
    }
}
