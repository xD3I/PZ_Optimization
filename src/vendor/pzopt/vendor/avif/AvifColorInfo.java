// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


/// AVIF `nclx` color information from a `colr` item property.

public final class AvifColorInfo {
    /// The CICP color primaries value.
    private final int colorPrimaries;
    /// The CICP transfer characteristics value.
    private final int transferCharacteristics;
    /// The CICP matrix coefficients value.
    private final int matrixCoefficients;
    /// Whether samples are full range.
    private final boolean fullRange;

    /// Creates color information.
    ///
    /// @param colorPrimaries the CICP color primaries value
    /// @param transferCharacteristics the CICP transfer characteristics value
    /// @param matrixCoefficients the CICP matrix coefficients value
    /// @param fullRange whether samples are full range
    public AvifColorInfo(int colorPrimaries, int transferCharacteristics, int matrixCoefficients, boolean fullRange) {
        this.colorPrimaries = checkedCodePoint(colorPrimaries, "colorPrimaries");
        this.transferCharacteristics = checkedCodePoint(transferCharacteristics, "transferCharacteristics");
        this.matrixCoefficients = checkedCodePoint(matrixCoefficients, "matrixCoefficients");
        this.fullRange = fullRange;
    }

    /// Returns the CICP color primaries value.
    ///
    /// @return the CICP color primaries value
    public int colorPrimaries() {
        return colorPrimaries;
    }

    /// Returns the CICP transfer characteristics value.
    ///
    /// @return the CICP transfer characteristics value
    public int transferCharacteristics() {
        return transferCharacteristics;
    }

    /// Returns the CICP matrix coefficients value.
    ///
    /// @return the CICP matrix coefficients value
    public int matrixCoefficients() {
        return matrixCoefficients;
    }

    /// Returns whether samples are full range.
    ///
    /// @return whether samples are full range
    public boolean fullRange() {
        return fullRange;
    }

    /// Returns a validated unsigned 16-bit `nclx` code point.
    ///
    /// @param value the code point
    /// @param name the parameter name
    /// @return the validated code point
    private static int checkedCodePoint(int value, String name) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(name + " out of range: " + value);
        }
        return value;
    }
}
