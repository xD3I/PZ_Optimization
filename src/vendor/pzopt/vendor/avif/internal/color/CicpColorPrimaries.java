// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.color;


/// Provides chromaticity definitions for standardized CICP color-primary codes.

public final class CicpColorPrimaries {
    /// BT.709/sRGB primaries with a D65 white point.
    private static final Definition BT709 =
            new Definition(0.640, 0.330, 0.300, 0.600, 0.150, 0.060, 0.3127, 0.3290);
    /// BT.470 System M primaries with a C white point.
    private static final Definition BT470M =
            new Definition(0.670, 0.330, 0.210, 0.710, 0.140, 0.080, 0.3100, 0.3160);
    /// BT.470 System B/G primaries with a D65 white point.
    private static final Definition BT470BG =
            new Definition(0.640, 0.330, 0.290, 0.600, 0.150, 0.060, 0.3127, 0.3290);
    /// SMPTE 170M primaries with a D65 white point.
    private static final Definition SMPTE170M =
            new Definition(0.630, 0.340, 0.310, 0.595, 0.155, 0.070, 0.3127, 0.3290);
    /// SMPTE 240M primaries with a D65 white point.
    private static final Definition SMPTE240M = SMPTE170M;
    /// Generic film primaries with a C white point.
    private static final Definition GENERIC_FILM =
            new Definition(0.681, 0.319, 0.243, 0.692, 0.145, 0.049, 0.3100, 0.3160);
    /// BT.2020 primaries with a D65 white point.
    private static final Definition BT2020 =
            new Definition(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290);
    /// SMPTE RP 431-2 primaries with the DCI white point.
    private static final Definition SMPTE431 =
            new Definition(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3140, 0.3510);
    /// SMPTE EG 432-1 Display-P3 primaries with a D65 white point.
    private static final Definition SMPTE432 =
            new Definition(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3127, 0.3290);
    /// EBU Tech. 3213-E primaries with a D65 white point.
    private static final Definition EBU3213 =
            new Definition(0.630, 0.340, 0.295, 0.605, 0.155, 0.077, 0.3127, 0.3290);

    /// Prevents instantiation of this utility class.
    private CicpColorPrimaries() {
    }

    /// Returns the chromaticity definition for one CICP color-primary code.
    ///
    /// @param colorPrimaries the CICP color-primary code
    /// @return the matching definition, or `null` for an unspecified, reserved, or unknown code
    public static Definition find(int colorPrimaries) {
        return switch (colorPrimaries) {
            case 1 -> BT709;
            case 4 -> BT470M;
            case 5 -> BT470BG;
            case 6 -> SMPTE170M;
            case 7 -> SMPTE240M;
            case 8 -> GENERIC_FILM;
            case 9 -> BT2020;
            case 11 -> SMPTE431;
            case 12 -> SMPTE432;
            case 22 -> EBU3213;
            default -> null;
        };
    }

    /// Returns the effective definition for chromaticity-derived matrix conversion.
    ///
    /// Unspecified primary code `2` uses BT.709, matching the default color-primary behavior used
    /// by AVIF conversion. Other unknown codes are rejected rather than silently substituted.
    ///
    /// @param colorPrimaries the CICP color-primary code
    /// @return the matching definition, or BT.709 for code `2`
    /// @throws UnsupportedOperationException if the code has no supported chromaticity definition
    public static Definition resolveForMatrix(int colorPrimaries) {
        if (colorPrimaries == 2) {
            return BT709;
        }
        Definition definition = find(colorPrimaries);
        if (definition == null) {
            throw new UnsupportedOperationException("Unsupported CICP color primaries: " + colorPrimaries);
        }
        return definition;
    }

    /// Defines RGB primary and white-point chromaticities.
    ///
    /// @param redX red-primary x chromaticity
    /// @param redY red-primary y chromaticity
    /// @param greenX green-primary x chromaticity
    /// @param greenY green-primary y chromaticity
    /// @param blueX blue-primary x chromaticity
    /// @param blueY blue-primary y chromaticity
    /// @param whiteX white-point x chromaticity
    /// @param whiteY white-point y chromaticity
    public record Definition(
            double redX,
            double redY,
            double greenX,
            double greenY,
            double blueX,
            double blueY,
            double whiteX,
            double whiteY
    ) {
        /// Computes normalized RGB luma coefficients from these chromaticities.
        ///
        /// @return the red, green, and blue luma coefficients
        /// @throws IllegalStateException if the chromaticities do not define an invertible RGB space
        public LumaCoefficients lumaCoefficients() {
            double redZ = 1.0 - redX - redY;
            double greenZ = 1.0 - greenX - greenY;
            double blueZ = 1.0 - blueX - blueY;
            double whiteZ = 1.0 - whiteX - whiteY;
            double determinant = whiteY * (
                    redX * (greenY * blueZ - blueY * greenZ)
                            + greenX * (blueY * redZ - redY * blueZ)
                            + blueX * (redY * greenZ - greenY * redZ)
            );
            if (Math.abs(determinant) < 1.0e-12) {
                throw new IllegalStateException("CICP primaries do not define invertible luma coefficients");
            }
            double red = redY * (
                    whiteX * (greenY * blueZ - blueY * greenZ)
                            + whiteY * (blueX * greenZ - greenX * blueZ)
                            + whiteZ * (greenX * blueY - blueX * greenY)
            ) / determinant;
            double blue = blueY * (
                    whiteX * (redY * greenZ - greenY * redZ)
                            + whiteY * (greenX * redZ - redX * greenZ)
                            + whiteZ * (redX * greenY - greenX * redY)
            ) / determinant;
            return new LumaCoefficients(red, 1.0 - red - blue, blue);
        }
    }

    /// Holds normalized RGB luma coefficients.
    ///
    /// @param red the red luma coefficient
    /// @param green the green luma coefficient
    /// @param blue the blue luma coefficient
    public record LumaCoefficients(double red, double green, double blue) {
    }
}
