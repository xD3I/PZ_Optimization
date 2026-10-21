// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;

import pzopt.vendor.avif.internal.color.CicpColorPrimaries;

/// Pure-Java CICP transfer-function and RGB-primary conversion helpers.
///
/// The helper covers every CICP transfer characteristic defined for normalized non-negative
/// channels and the RGB primary sets described by [CicpColorPrimaries]. Missing or unspecified
/// signaling uses BT.709/sRGB defaults; reserved or unknown explicit signaling is rejected.

final class AvifCicpColorTransforms {
    /// BT.709/sRGB color primaries.
    private static final int PRIMARIES_BT709 = 1;
    /// Unspecified CICP color primaries.
    private static final int PRIMARIES_UNSPECIFIED = 2;
    /// SMPTE ST 428 CIE XYZ color coordinates.
    private static final int PRIMARIES_XYZ = 10;
    /// Unspecified CICP transfer characteristics.
    private static final int TRANSFER_UNSPECIFIED = 2;
    /// sRGB transfer characteristics.
    private static final int TRANSFER_SRGB = 13;
    /// Linear transfer characteristics.
    private static final int TRANSFER_LINEAR = 8;
    /// H.273 BT.709-family transfer-function scale.
    private static final double BT709_ALPHA = 1.099296826809442;
    /// H.273 BT.709-family linear-light threshold.
    private static final double BT709_BETA = 0.018053968510807;
    /// H.273 BT.709-family encoded threshold.
    private static final double BT709_ENCODED_THRESHOLD = 4.5 * BT709_BETA;
    /// H.273 SMPTE 240M transfer-function scale.
    private static final double SMPTE240_ALPHA = 1.111572195921731;
    /// H.273 SMPTE 240M linear-light threshold.
    private static final double SMPTE240_BETA = 0.022821585529445;
    /// H.273 SMPTE 240M encoded threshold.
    private static final double SMPTE240_ENCODED_THRESHOLD = 4.0 * SMPTE240_BETA;
    /// Lower linear-light endpoint represented by transfer characteristic 9.
    private static final double LOG100_LINEAR_THRESHOLD = 0.01;
    /// Lower linear-light endpoint represented by transfer characteristic 10.
    private static final double LOG316_LINEAR_THRESHOLD = Math.sqrt(10.0) / 1000.0;
    /// SMPTE ST 428 reference-output scale from H.273.
    private static final double SMPTE428_ENCODE_SCALE = 48.0 / 52.37;
    /// SMPTE ST 2084 perceptual quantizer transfer characteristics.
    private static final int TRANSFER_PQ = 16;
    /// ARIB STD-B67 hybrid log-gamma transfer characteristics.
    private static final int TRANSFER_HLG = 18;
    /// sRGB linear-to-gamma threshold.
    private static final double SRGB_LINEAR_THRESHOLD = 0.0031308;
    /// sRGB gamma-to-linear threshold.
    private static final double SRGB_GAMMA_THRESHOLD = 0.04045;
    /// HLG transfer function constant `a`.
    private static final double HLG_A = 0.17883277;
    /// HLG transfer function constant `b`.
    private static final double HLG_B = 0.28466892;
    /// HLG transfer function constant `c`.
    private static final double HLG_C = 0.55991073;
    /// PQ transfer function constant `m1`.
    private static final double PQ_M1 = 2610.0 / 16384.0;
    /// PQ transfer function constant `m2`.
    private static final double PQ_M2 = 2523.0 / 32.0;
    /// PQ transfer function constant `c1`.
    private static final double PQ_C1 = 3424.0 / 4096.0;
    /// PQ transfer function constant `c2`.
    private static final double PQ_C2 = 2413.0 / 128.0;
    /// PQ transfer function constant `c3`.
    private static final double PQ_C3 = 2392.0 / 128.0;
    /// Identity RGB conversion matrix.
    private static final RgbMatrix IDENTITY = new RgbMatrix(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
    );

    /// Prevents instantiation of this utility class.
    private AvifCicpColorTransforms() {
    }

    /// Converts one gamma-encoded RGB channel to linear light.
    ///
    /// @param value the normalized gamma-encoded value
    /// @param colorInfo the CICP color information, or `null` for sRGB fallback
    /// @return the normalized linear-light value
    /// @throws UnsupportedOperationException if an explicit transfer characteristic is unsupported
    static double gammaToLinear(double value, AvifColorInfo colorInfo) {
        double clamped = clampUnit(value);
        int transferCharacteristics = transferCharacteristics(colorInfo);
        return switch (transferCharacteristics) {
            case TRANSFER_LINEAR -> clamped;
            case 1, 6, 11, 12, 14, 15 -> bt709ToLinear(clamped);
            case 4 -> Math.pow(clamped, 2.2);
            case 5 -> Math.pow(clamped, 2.8);
            case 7 -> smpte240ToLinear(clamped);
            case 9 -> logarithmicToLinear(clamped, 2.0);
            case 10 -> logarithmicToLinear(clamped, 2.5);
            case TRANSFER_PQ -> pqToLinear(clamped);
            case 17 -> smpte428ToLinear(clamped);
            case TRANSFER_HLG -> hlgToLinear(clamped);
            case TRANSFER_SRGB -> srgbToLinear(clamped);
            default -> throw new UnsupportedOperationException(
                    "Unsupported CICP transfer characteristics: " + transferCharacteristics
            );
        };
    }

    /// Converts one linear-light RGB channel to gamma encoding.
    ///
    /// @param value the normalized linear-light value
    /// @param colorInfo the CICP color information, or `null` for sRGB fallback
    /// @return the normalized gamma-encoded value
    /// @throws UnsupportedOperationException if an explicit transfer characteristic is unsupported
    static double linearToGamma(double value, AvifColorInfo colorInfo) {
        double clamped = clampUnit(value);
        int transferCharacteristics = transferCharacteristics(colorInfo);
        return switch (transferCharacteristics) {
            case TRANSFER_LINEAR -> clamped;
            case 1, 6, 11, 12, 14, 15 -> linearToBt709(clamped);
            case 4 -> Math.pow(clamped, 1.0 / 2.2);
            case 5 -> Math.pow(clamped, 1.0 / 2.8);
            case 7 -> linearToSmpte240(clamped);
            case 9 -> linearToLogarithmic(clamped, LOG100_LINEAR_THRESHOLD, 2.0);
            case 10 -> linearToLogarithmic(clamped, LOG316_LINEAR_THRESHOLD, 2.5);
            case TRANSFER_PQ -> linearToPq(clamped);
            case 17 -> linearToSmpte428(clamped);
            case TRANSFER_HLG -> linearToHlg(clamped);
            case TRANSFER_SRGB -> linearToSrgb(clamped);
            default -> throw new UnsupportedOperationException(
                    "Unsupported CICP transfer characteristics: " + transferCharacteristics
            );
        };
    }

    /// Builds a linear-light RGB conversion matrix between two CICP primary sets.
    ///
    /// @param sourceInfo the source color information, or `null` for BT.709/sRGB fallback
    /// @param targetInfo the target color information, or `null` for BT.709/sRGB fallback
    /// @return an RGB conversion matrix
    /// @throws UnsupportedOperationException if either explicit primary set is unsupported
    /// @throws IllegalStateException if a supported primary definition cannot produce an invertible matrix
    static RgbMatrix conversionMatrix(AvifColorInfo sourceInfo, AvifColorInfo targetInfo) {
        int sourcePrimaries = colorPrimaries(sourceInfo);
        int targetPrimaries = colorPrimaries(targetInfo);
        RgbMatrix sourceToXyz = primarySetToXyzMatrix(sourcePrimaries);
        RgbMatrix targetToXyz = primarySetToXyzMatrix(targetPrimaries);
        if (sourceToXyz == null || targetToXyz == null) {
            throw new IllegalStateException("Supported CICP primaries produced a singular RGB conversion matrix");
        }
        if (sourcePrimaries == targetPrimaries) {
            return IDENTITY;
        }
        RgbMatrix xyzToTarget = targetToXyz.inverse();
        if (xyzToTarget == null) {
            throw new IllegalStateException("Supported target CICP primaries produced a singular RGB conversion matrix");
        }
        return xyzToTarget.multiply(sourceToXyz);
    }

    /// Returns whether two CICP descriptors have the same effective RGB color space.
    ///
    /// RGB frame output is affected by color primaries and transfer characteristics. Matrix
    /// coefficients and sample range describe YUV storage and are intentionally ignored here.
    ///
    /// @param first the first color information, or `null`
    /// @param second the second color information, or `null`
    /// @return whether both descriptors resolve to the same effective RGB color space
    static boolean sameEffectiveRgbColorSpace(AvifColorInfo first, AvifColorInfo second) {
        return colorPrimaries(first) == colorPrimaries(second)
                && transferCharacteristics(first) == transferCharacteristics(second);
    }

    /// Clamps a sample to the closed unit interval.
    ///
    /// @param value the sample value
    /// @return the clamped value
    static double clampUnit(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= 1.0) {
            return 1.0;
        }
        return value;
    }

    /// Returns the CICP color primaries with a BT.709 fallback.
    ///
    /// @param colorInfo the color information, or `null`
    /// @return the effective color primaries
    private static int colorPrimaries(AvifColorInfo colorInfo) {
        if (colorInfo == null || colorInfo.colorPrimaries() == PRIMARIES_UNSPECIFIED) {
            return PRIMARIES_BT709;
        }
        return colorInfo.colorPrimaries();
    }

    /// Returns the CICP transfer characteristics with an sRGB fallback.
    ///
    /// @param colorInfo the color information, or `null`
    /// @return the effective transfer characteristics
    private static int transferCharacteristics(AvifColorInfo colorInfo) {
        if (colorInfo == null || colorInfo.transferCharacteristics() == TRANSFER_UNSPECIFIED) {
            return TRANSFER_SRGB;
        }
        return colorInfo.transferCharacteristics();
    }

    /// Returns the primary definition for one supported effective CICP code.
    ///
    /// @param colorPrimaries the effective CICP color primaries value
    /// @return the matching primary definition
    /// @throws UnsupportedOperationException if the explicit primary set is unsupported
    private static CicpColorPrimaries.Definition requirePrimaries(int colorPrimaries) {
        CicpColorPrimaries.Definition result = CicpColorPrimaries.find(colorPrimaries);
        if (result == null) {
            throw new UnsupportedOperationException("Unsupported CICP color primaries: " + colorPrimaries);
        }
        return result;
    }

    /// Builds a primary-coordinate-set-to-XYZ matrix for one supported CICP code.
    ///
    /// CICP code `10` already labels its three channels as CIE XYZ and therefore uses the identity
    /// matrix. Other supported codes are conventional RGB primary definitions.
    ///
    /// @param colorPrimaries the effective CICP color-primary value
    /// @return the coordinate-set-to-XYZ matrix, or `null` when the definition is singular
    /// @throws UnsupportedOperationException if the explicit primary set is unsupported
    private static RgbMatrix primarySetToXyzMatrix(int colorPrimaries) {
        if (colorPrimaries == PRIMARIES_XYZ) {
            return IDENTITY;
        }
        return rgbToXyzMatrix(requirePrimaries(colorPrimaries));
    }

    /// Computes an RGB-to-XYZ matrix for one primary definition.
    ///
    /// @param primaries the primary and white-point chromaticities
    /// @return the RGB-to-XYZ matrix, or `null` when the chromaticities are singular
    private static RgbMatrix rgbToXyzMatrix(CicpColorPrimaries.Definition primaries) {
        double redZ = 1.0 - primaries.redX() - primaries.redY();
        double greenZ = 1.0 - primaries.greenX() - primaries.greenY();
        double blueZ = 1.0 - primaries.blueX() - primaries.blueY();
        double whiteZ = 1.0 - primaries.whiteX() - primaries.whiteY();
        if (primaries.redY() == 0.0
                || primaries.greenY() == 0.0
                || primaries.blueY() == 0.0
                || primaries.whiteY() == 0.0) {
            return null;
        }
        RgbMatrix matrix = new RgbMatrix(
                primaries.redX() / primaries.redY(),
                primaries.greenX() / primaries.greenY(),
                primaries.blueX() / primaries.blueY(),
                1.0, 1.0, 1.0,
                redZ / primaries.redY(),
                greenZ / primaries.greenY(),
                blueZ / primaries.blueY()
        );
        RgbMatrix inverse = matrix.inverse();
        if (inverse == null) {
            return null;
        }
        double[] white = {
                primaries.whiteX() / primaries.whiteY(),
                1.0,
                whiteZ / primaries.whiteY()
        };
        inverse.apply(white);
        double redScale = white[0];
        double greenScale = white[1];
        double blueScale = white[2];
        return new RgbMatrix(
                redScale * primaries.redX() / primaries.redY(),
                greenScale * primaries.greenX() / primaries.greenY(),
                blueScale * primaries.blueX() / primaries.blueY(),
                redScale,
                greenScale,
                blueScale,
                redScale * redZ / primaries.redY(),
                greenScale * greenZ / primaries.greenY(),
                blueScale * blueZ / primaries.blueY()
        );
    }

    /// Converts an sRGB gamma-encoded sample to linear light.
    ///
    /// @param value the normalized gamma-encoded sample
    /// @return the normalized linear-light sample
    private static double srgbToLinear(double value) {
        if (value <= SRGB_GAMMA_THRESHOLD) {
            return value / 12.92;
        }
        return Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /// Converts a linear-light sample to sRGB gamma encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized gamma-encoded sample
    private static double linearToSrgb(double value) {
        if (value <= SRGB_LINEAR_THRESHOLD) {
            return value * 12.92;
        }
        return 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
    }

    /// Converts a BT.709-family gamma-encoded sample to linear light.
    ///
    /// @param value the normalized gamma-encoded sample
    /// @return the normalized linear-light sample
    private static double bt709ToLinear(double value) {
        if (value < BT709_ENCODED_THRESHOLD) {
            return value / 4.5;
        }
        return Math.pow((value + BT709_ALPHA - 1.0) / BT709_ALPHA, 1.0 / 0.45);
    }

    /// Converts a linear-light sample to BT.709-family gamma encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized gamma-encoded sample
    private static double linearToBt709(double value) {
        if (value < BT709_BETA) {
            return value * 4.5;
        }
        return BT709_ALPHA * Math.pow(value, 0.45) - (BT709_ALPHA - 1.0);
    }

    /// Converts a SMPTE 240M gamma-encoded sample to linear light.
    ///
    /// @param value the normalized gamma-encoded sample
    /// @return the normalized linear-light sample
    private static double smpte240ToLinear(double value) {
        if (value < SMPTE240_ENCODED_THRESHOLD) {
            return value / 4.0;
        }
        return Math.pow((value + SMPTE240_ALPHA - 1.0) / SMPTE240_ALPHA, 1.0 / 0.45);
    }

    /// Converts a linear-light sample to SMPTE 240M gamma encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized gamma-encoded sample
    private static double linearToSmpte240(double value) {
        if (value < SMPTE240_BETA) {
            return value * 4.0;
        }
        return SMPTE240_ALPHA * Math.pow(value, 0.45) - (SMPTE240_ALPHA - 1.0);
    }

    /// Converts a logarithmically encoded sample to linear light.
    ///
    /// Encoded black represents an interval of linear-light values. This inverse selects zero,
    /// the lower endpoint of that interval, so exact black remains black.
    ///
    /// @param value the normalized logarithmically encoded sample
    /// @param divisor the base-10 logarithm divisor from H.273
    /// @return the normalized linear-light sample
    private static double logarithmicToLinear(double value, double divisor) {
        if (value <= 0.0) {
            return 0.0;
        }
        return Math.pow(10.0, divisor * (value - 1.0));
    }

    /// Converts a linear-light sample to logarithmic encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @param threshold the inclusive lower endpoint of the logarithmic segment
    /// @param divisor the base-10 logarithm divisor from H.273
    /// @return the normalized logarithmically encoded sample
    private static double linearToLogarithmic(double value, double threshold, double divisor) {
        if (value <= threshold) {
            return 0.0;
        }
        return 1.0 + Math.log10(value) / divisor;
    }

    /// Converts a SMPTE ST 428 gamma-encoded sample to normalized output light.
    ///
    /// @param value the normalized gamma-encoded sample
    /// @return the normalized linear-light sample
    private static double smpte428ToLinear(double value) {
        return Math.pow(value, 2.6) / SMPTE428_ENCODE_SCALE;
    }

    /// Converts normalized output light to SMPTE ST 428 gamma encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized gamma-encoded sample
    private static double linearToSmpte428(double value) {
        return Math.pow(SMPTE428_ENCODE_SCALE * value, 1.0 / 2.6);
    }

    /// Converts a PQ-encoded sample to normalized linear light.
    ///
    /// @param value the normalized PQ-encoded sample
    /// @return the normalized linear-light sample
    private static double pqToLinear(double value) {
        double powered = Math.pow(value, 1.0 / PQ_M2);
        double numerator = Math.max(powered - PQ_C1, 0.0);
        double denominator = PQ_C2 - PQ_C3 * powered;
        if (denominator <= 0.0) {
            return 1.0;
        }
        return Math.pow(numerator / denominator, 1.0 / PQ_M1);
    }

    /// Converts a normalized linear-light sample to PQ encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized PQ-encoded sample
    private static double linearToPq(double value) {
        double powered = Math.pow(value, PQ_M1);
        return Math.pow((PQ_C1 + PQ_C2 * powered) / (1.0 + PQ_C3 * powered), PQ_M2);
    }

    /// Converts an HLG-encoded sample to normalized linear light.
    ///
    /// @param value the normalized HLG-encoded sample
    /// @return the normalized linear-light sample
    private static double hlgToLinear(double value) {
        if (value <= 0.5) {
            return value * value / 3.0;
        }
        return (Math.exp((value - HLG_C) / HLG_A) + HLG_B) / 12.0;
    }

    /// Converts a normalized linear-light sample to HLG encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized HLG-encoded sample
    private static double linearToHlg(double value) {
        if (value <= 1.0 / 12.0) {
            return Math.sqrt(3.0 * value);
        }
        return HLG_A * Math.log(12.0 * value - HLG_B) + HLG_C;
    }

    /// A 3x3 RGB conversion matrix.
    
    static final class RgbMatrix {
        /// Matrix entry at row 0, column 0.
        private final double m00;
        /// Matrix entry at row 0, column 1.
        private final double m01;
        /// Matrix entry at row 0, column 2.
        private final double m02;
        /// Matrix entry at row 1, column 0.
        private final double m10;
        /// Matrix entry at row 1, column 1.
        private final double m11;
        /// Matrix entry at row 1, column 2.
        private final double m12;
        /// Matrix entry at row 2, column 0.
        private final double m20;
        /// Matrix entry at row 2, column 1.
        private final double m21;
        /// Matrix entry at row 2, column 2.
        private final double m22;

        /// Creates a 3x3 RGB conversion matrix.
        ///
        /// @param m00 matrix entry at row 0, column 0
        /// @param m01 matrix entry at row 0, column 1
        /// @param m02 matrix entry at row 0, column 2
        /// @param m10 matrix entry at row 1, column 0
        /// @param m11 matrix entry at row 1, column 1
        /// @param m12 matrix entry at row 1, column 2
        /// @param m20 matrix entry at row 2, column 0
        /// @param m21 matrix entry at row 2, column 1
        /// @param m22 matrix entry at row 2, column 2
        private RgbMatrix(
                double m00,
                double m01,
                double m02,
                double m10,
                double m11,
                double m12,
                double m20,
                double m21,
                double m22
        ) {
            this.m00 = m00;
            this.m01 = m01;
            this.m02 = m02;
            this.m10 = m10;
            this.m11 = m11;
            this.m12 = m12;
            this.m20 = m20;
            this.m21 = m21;
            this.m22 = m22;
        }

        /// Applies this matrix in place to an RGB triplet.
        ///
        /// @param rgb the mutable RGB triplet
        void apply(double[] rgb) {
            double red = rgb[0];
            double green = rgb[1];
            double blue = rgb[2];
            rgb[0] = m00 * red + m01 * green + m02 * blue;
            rgb[1] = m10 * red + m11 * green + m12 * blue;
            rgb[2] = m20 * red + m21 * green + m22 * blue;
        }

        /// Multiplies this matrix by another matrix.
        ///
        /// @param other the right-hand matrix
        /// @return the matrix product
        private RgbMatrix multiply(RgbMatrix other) {
            return new RgbMatrix(
                    m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
                    m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
                    m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
                    m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
                    m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
                    m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
                    m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
                    m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
                    m20 * other.m02 + m21 * other.m12 + m22 * other.m22
            );
        }

        /// Computes the inverse matrix.
        ///
        /// @return the inverse matrix, or `null` for singular matrices
        private RgbMatrix inverse() {
            double c00 = m11 * m22 - m12 * m21;
            double c01 = m12 * m20 - m10 * m22;
            double c02 = m10 * m21 - m11 * m20;
            double determinant = m00 * c00 + m01 * c01 + m02 * c02;
            if (Math.abs(determinant) < 1.0e-12) {
                return null;
            }
            double scale = 1.0 / determinant;
            return new RgbMatrix(
                    c00 * scale,
                    (m02 * m21 - m01 * m22) * scale,
                    (m01 * m12 - m02 * m11) * scale,
                    c01 * scale,
                    (m00 * m22 - m02 * m20) * scale,
                    (m02 * m10 - m00 * m12) * scale,
                    c02 * scale,
                    (m01 * m20 - m00 * m21) * scale,
                    (m00 * m11 - m01 * m10) * scale
            );
        }
    }

}
