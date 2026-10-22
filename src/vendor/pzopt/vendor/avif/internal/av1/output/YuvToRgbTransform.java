// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.output;

import pzopt.vendor.avif.AvifColorInfo;
import pzopt.vendor.avif.av1.Av1ColorConfig;
import pzopt.vendor.avif.internal.color.CicpColorPrimaries;

import java.util.Objects;

/// Fixed-point YUV-to-RGB transform for opaque ARGB output.
///
/// Coefficients are stored in the nominal 8-bit sample domain and use 16 fractional bits. The
/// transform can emit either packed `0xAARRGGBB` pixels for 8-bit output or packed
/// `0xAAAA_RRRR_GGGG_BBBB` pixels for higher-bit-depth output. The transform does not model
/// alpha; callers always receive non-premultiplied opaque pixels.

public final class YuvToRgbTransform {
    /// The fixed-point coefficient scaling factor.
    private static final int FRACTION_BITS = 16;

    /// The rounding term applied before shifting back to 8-bit RGB.
    private static final int ROUNDING = 1 << (FRACTION_BITS - 1);

    /// Full-range `BT.601` coefficients for 8-bit YUV samples.
    public static final YuvToRgbTransform BT601_FULL_RANGE = new YuvToRgbTransform(
            0,
            128,
            65_536,
            91_881,
            -22_554,
            -46_802,
            116_130
    );

    /// Limited-range `BT.601` coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform BT601_LIMITED_RANGE = createMatrixTransform(0.2990, 0.1140, false);

    /// Full-range `BT.709` coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform BT709_FULL_RANGE = createMatrixTransform(0.2126, 0.0722, true);

    /// Limited-range `BT.709` coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform BT709_LIMITED_RANGE = createMatrixTransform(0.2126, 0.0722, false);

    /// Full-range FCC coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform FCC_FULL_RANGE = createMatrixTransform(0.30, 0.11, true);

    /// Limited-range FCC coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform FCC_LIMITED_RANGE = createMatrixTransform(0.30, 0.11, false);

    /// Full-range `BT.2020` non-constant-luminance coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform BT2020_NCL_FULL_RANGE = createMatrixTransform(0.2627, 0.0593, true);

    /// Limited-range `BT.2020` non-constant-luminance coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform BT2020_NCL_LIMITED_RANGE = createMatrixTransform(0.2627, 0.0593, false);

    /// Full-range `SMPTE 240M` coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform SMPTE240M_FULL_RANGE = createMatrixTransform(0.2120, 0.0870, true);

    /// Limited-range `SMPTE 240M` coefficients for nominal 8-bit YUV samples.
    public static final YuvToRgbTransform SMPTE240M_LIMITED_RANGE = createMatrixTransform(0.2120, 0.0870, false);

    /// Full-range `YCgCo` inverse transform for equal-depth component samples.
    public static final YuvToRgbTransform YCGCO_FULL_RANGE = createYcgcoTransform(true);

    /// Limited-range `YCgCo` inverse transform for equal-depth component samples.
    public static final YuvToRgbTransform YCGCO_LIMITED_RANGE = createYcgcoTransform(false);

    /// Full-range RGB identity mapping used by AV1 identity matrix signaling.
    public static final YuvToRgbTransform RGB_IDENTITY = new YuvToRgbTransform(
            0,
            0,
            65_536,
            0,
            0,
            0,
            0,
            0,
            0,
            true
    );

    /// The luma offset removed before matrix multiplication.
    private final int lumaOffset;

    /// The neutral chroma sample value.
    private final int chromaCenter;

    /// The fixed-point luma coefficient shared by every output channel.
    private final int lumaCoefficient;

    /// The fixed-point red-from-U coefficient.
    private final int redCoefficientU;

    /// The fixed-point red-from-V coefficient.
    private final int redCoefficientV;

    /// The fixed-point green-from-U coefficient.
    private final int greenCoefficientU;

    /// The fixed-point green-from-V coefficient.
    private final int greenCoefficientV;

    /// The fixed-point blue-from-U coefficient.
    private final int blueCoefficientU;

    /// The fixed-point blue-from-V coefficient.
    private final int blueCoefficientV;

    /// Whether this transform maps AV1 identity-matrix planes directly as RGB samples.
    private final boolean identityMatrix;

    /// Creates one fixed-point YUV-to-RGB transform for 8-bit sample domains.
    ///
    /// @param lumaOffset the luma offset removed before matrix multiplication
    /// @param chromaCenter the neutral chroma sample value
    /// @param lumaCoefficient the fixed-point luma coefficient shared by every output channel
    /// @param redCoefficientV the fixed-point red-from-V coefficient
    /// @param greenCoefficientU the fixed-point green-from-U coefficient
    /// @param greenCoefficientV the fixed-point green-from-V coefficient
    /// @param blueCoefficientU the fixed-point blue-from-U coefficient
    public YuvToRgbTransform(
            int lumaOffset,
            int chromaCenter,
            int lumaCoefficient,
            int redCoefficientV,
            int greenCoefficientU,
            int greenCoefficientV,
            int blueCoefficientU
    ) {
        this(
                lumaOffset,
                chromaCenter,
                lumaCoefficient,
                0,
                redCoefficientV,
                greenCoefficientU,
                greenCoefficientV,
                blueCoefficientU,
                0,
                false
        );
    }

    /// Creates one fixed-point component-to-RGB transform for 8-bit sample domains.
    ///
    /// @param lumaOffset the luma offset removed before matrix multiplication
    /// @param chromaCenter the neutral chroma sample value
    /// @param lumaCoefficient the fixed-point luma coefficient shared by every output channel
    /// @param redCoefficientU the fixed-point red-from-U coefficient
    /// @param redCoefficientV the fixed-point red-from-V coefficient
    /// @param greenCoefficientU the fixed-point green-from-U coefficient
    /// @param greenCoefficientV the fixed-point green-from-V coefficient
    /// @param blueCoefficientU the fixed-point blue-from-U coefficient
    /// @param blueCoefficientV the fixed-point blue-from-V coefficient
    /// @param identityMatrix whether this transform maps identity-matrix planes directly as RGB
    private YuvToRgbTransform(
            int lumaOffset,
            int chromaCenter,
            int lumaCoefficient,
            int redCoefficientU,
            int redCoefficientV,
            int greenCoefficientU,
            int greenCoefficientV,
            int blueCoefficientU,
            int blueCoefficientV,
            boolean identityMatrix
    ) {
        if (lumaOffset < 0) {
            throw new IllegalArgumentException("lumaOffset < 0: " + lumaOffset);
        }
        if (chromaCenter < 0) {
            throw new IllegalArgumentException("chromaCenter < 0: " + chromaCenter);
        }
        if (lumaCoefficient <= 0) {
            throw new IllegalArgumentException("lumaCoefficient <= 0: " + lumaCoefficient);
        }
        this.lumaOffset = lumaOffset;
        this.chromaCenter = chromaCenter;
        this.lumaCoefficient = lumaCoefficient;
        this.redCoefficientU = redCoefficientU;
        this.redCoefficientV = redCoefficientV;
        this.greenCoefficientU = greenCoefficientU;
        this.greenCoefficientV = greenCoefficientV;
        this.blueCoefficientU = blueCoefficientU;
        this.blueCoefficientV = blueCoefficientV;
        this.identityMatrix = identityMatrix;
    }

    /// Selects a display transform for one parsed AV1 color configuration.
    ///
    /// Unspecified matrix coefficients use `BT.601` while preserving the signaled sample range.
    /// Matrix coefficients are irrelevant for monochrome planes. Explicit matrix families that
    /// this implementation does not model are rejected instead of being rendered with a different
    /// matrix.
    ///
    /// @param colorConfig the AV1 color configuration
    /// @return the selected fixed-point YUV-to-RGB transform
    /// @throws UnsupportedOperationException if a chroma stream signals an unsupported explicit matrix family
    public static YuvToRgbTransform fromColorConfig(Av1ColorConfig colorConfig) {
        Av1ColorConfig checkedColorConfig = Objects.requireNonNull(colorConfig, "colorConfig");
        boolean fullRange = checkedColorConfig.colorRange();
        return fromMatrixCoefficients(
                checkedColorConfig.matrixCoefficients(),
                checkedColorConfig.colorPrimaries(),
                fullRange,
                checkedColorConfig.monochrome()
        );
    }

    /// Selects a display transform for one AVIF container `nclx` color property.
    ///
    /// The pure Java output path currently models YUV matrix and range conversion. ICC profile
    /// application and transfer-function adaptation remain metadata-only because production code
    /// in this repository cannot depend on modules outside `java.base`.
    ///
    /// @param colorInfo the AVIF container color information
    /// @param monochrome whether the decoded AV1 planes are monochrome
    /// @return the selected fixed-point YUV-to-RGB transform
    /// @throws UnsupportedOperationException if chroma planes use an unsupported explicit matrix family
    public static YuvToRgbTransform fromColorInfo(AvifColorInfo colorInfo, boolean monochrome) {
        AvifColorInfo checkedColorInfo = Objects.requireNonNull(colorInfo, "colorInfo");
        return fromMatrixCoefficients(
                checkedColorInfo.matrixCoefficients(),
                checkedColorInfo.colorPrimaries(),
                checkedColorInfo.fullRange(),
                monochrome
        );
    }

    /// Selects a display transform from CICP matrix coefficients and sample-range signaling.
    ///
    /// @param matrixCoefficients the CICP matrix coefficients value
    /// @param colorPrimaries the CICP color-primary value used by chromaticity-derived matrices
    /// @param fullRange whether samples are full range
    /// @param monochrome whether the decoded planes are monochrome
    /// @return the selected fixed-point YUV-to-RGB transform
    /// @throws UnsupportedOperationException if chroma planes use an unsupported explicit matrix family
    private static YuvToRgbTransform fromMatrixCoefficients(
            int matrixCoefficients,
            int colorPrimaries,
            boolean fullRange,
            boolean monochrome
    ) {
        if (monochrome) {
            return defaultTransform(fullRange);
        }
        return switch (matrixCoefficients) {
            case 0 -> RGB_IDENTITY;
            case 1 -> fullRange ? BT709_FULL_RANGE : BT709_LIMITED_RANGE;
            case 2 -> defaultTransform(fullRange);
            case 4 -> fullRange ? FCC_FULL_RANGE : FCC_LIMITED_RANGE;
            case 5, 6 -> fullRange ? BT601_FULL_RANGE : BT601_LIMITED_RANGE;
            case 7 -> fullRange ? SMPTE240M_FULL_RANGE : SMPTE240M_LIMITED_RANGE;
            case 8 -> fullRange ? YCGCO_FULL_RANGE : YCGCO_LIMITED_RANGE;
            case 9 -> fullRange ? BT2020_NCL_FULL_RANGE : BT2020_NCL_LIMITED_RANGE;
            case 12 -> chromaticityDerivedTransform(colorPrimaries, fullRange);
            default -> throw new UnsupportedOperationException(
                    "Unsupported CICP matrix coefficients: " + matrixCoefficients
            );
        };
    }

    /// Creates a non-constant-luminance transform from signaled color-primary chromaticities.
    ///
    /// @param colorPrimaries the CICP color-primary value
    /// @param fullRange whether samples are full range
    /// @return the chromaticity-derived transform
    /// @throws UnsupportedOperationException if the color-primary code is unsupported
    /// @throws IllegalStateException if the primary definition cannot produce luma coefficients
    private static YuvToRgbTransform chromaticityDerivedTransform(int colorPrimaries, boolean fullRange) {
        CicpColorPrimaries.LumaCoefficients coefficients =
                CicpColorPrimaries.resolveForMatrix(colorPrimaries).lumaCoefficients();
        return createMatrixTransform(coefficients.red(), coefficients.blue(), fullRange);
    }

    /// Returns the luma offset removed before matrix multiplication.
    ///
    /// @return the luma offset removed before matrix multiplication
    public int lumaOffset() {
        return lumaOffset;
    }

    /// Returns the neutral chroma sample value.
    ///
    /// @return the neutral chroma sample value
    public int chromaCenter() {
        return chromaCenter;
    }

    /// Returns the fixed-point luma coefficient shared by every output channel.
    ///
    /// @return the fixed-point luma coefficient shared by every output channel
    public int lumaCoefficient() {
        return lumaCoefficient;
    }

    /// Returns the fixed-point red-from-U coefficient.
    ///
    /// @return the fixed-point red-from-U coefficient
    public int redCoefficientU() {
        return redCoefficientU;
    }

    /// Returns the fixed-point red-from-V coefficient.
    ///
    /// @return the fixed-point red-from-V coefficient
    public int redCoefficientV() {
        return redCoefficientV;
    }

    /// Returns the fixed-point green-from-U coefficient.
    ///
    /// @return the fixed-point green-from-U coefficient
    public int greenCoefficientU() {
        return greenCoefficientU;
    }

    /// Returns the fixed-point green-from-V coefficient.
    ///
    /// @return the fixed-point green-from-V coefficient
    public int greenCoefficientV() {
        return greenCoefficientV;
    }

    /// Returns the fixed-point blue-from-U coefficient.
    ///
    /// @return the fixed-point blue-from-U coefficient
    public int blueCoefficientU() {
        return blueCoefficientU;
    }

    /// Returns the fixed-point blue-from-V coefficient.
    ///
    /// @return the fixed-point blue-from-V coefficient
    public int blueCoefficientV() {
        return blueCoefficientV;
    }

    /// Converts one luma sample with neutral chroma into an opaque grayscale ARGB pixel.
    ///
    /// @param ySample the 8-bit luma sample
    /// @return the packed opaque grayscale ARGB pixel
    public int toOpaqueGrayArgb(int ySample) {
        if (identityMatrix) {
            int gray = clampToByte(ySample);
            return packOpaqueArgb(gray, gray, gray);
        }
        return toOpaqueArgb(ySample, chromaCenter, chromaCenter);
    }

    /// Converts one luma sample with neutral chroma into an opaque grayscale ARGB pixel.
    ///
    /// @param ySample the luma sample in the caller-supplied bit depth
    /// @param bitDepth the decoded sample bit depth
    /// @return the packed opaque grayscale ARGB pixel
    public int toOpaqueGrayArgb(int ySample, int bitDepth) {
        if (bitDepth == 8) {
            return toOpaqueGrayArgb(ySample);
        }
        if (identityMatrix) {
            int gray = sampleToByte(clampToSampleDepth(ySample, maxSample(bitDepth)), bitDepth);
            return packOpaqueArgb(gray, gray, gray);
        }
        int neutralChroma = scaleNominalCodeValue(chromaCenter, bitDepth);
        return toOpaqueArgb(ySample, neutralChroma, neutralChroma, bitDepth);
    }

    /// Converts one YUV sample triplet into a packed opaque ARGB pixel.
    ///
    /// @param ySample the 8-bit luma sample
    /// @param uSample the 8-bit chroma U sample
    /// @param vSample the 8-bit chroma V sample
    /// @return the packed opaque non-premultiplied ARGB pixel
    public int toOpaqueArgb(int ySample, int uSample, int vSample) {
        if (identityMatrix) {
            return packOpaqueArgb(clampToByte(vSample), clampToByte(ySample), clampToByte(uSample));
        }

        int scaledLuma = Math.max(0, ySample - lumaOffset) * lumaCoefficient;
        int centeredU = uSample - chromaCenter;
        int centeredV = vSample - chromaCenter;

        int red = clampToByte(
                (scaledLuma + centeredU * redCoefficientU + centeredV * redCoefficientV + ROUNDING) >> FRACTION_BITS
        );
        int green = clampToByte(
                (scaledLuma + centeredU * greenCoefficientU + centeredV * greenCoefficientV + ROUNDING) >> FRACTION_BITS
        );
        int blue = clampToByte(
                (scaledLuma + centeredU * blueCoefficientU + centeredV * blueCoefficientV + ROUNDING) >> FRACTION_BITS
        );
        return 0xFF00_0000 | (red << 16) | (green << 8) | blue;
    }

    /// Converts one YUV sample triplet into a packed opaque ARGB pixel.
    ///
    /// The YUV samples are interpreted in their original decoded bit depth. RGB channels are first
    /// computed in that same sample domain, then reduced to unsigned 8-bit output channels.
    ///
    /// @param ySample the luma sample in the caller-supplied bit depth
    /// @param uSample the chroma U sample in the caller-supplied bit depth
    /// @param vSample the chroma V sample in the caller-supplied bit depth
    /// @param bitDepth the decoded sample bit depth
    /// @return the packed opaque non-premultiplied ARGB pixel
    public int toOpaqueArgb(int ySample, int uSample, int vSample, int bitDepth) {
        if (bitDepth == 8) {
            return toOpaqueArgb(ySample, uSample, vSample);
        }

        int sampleMax = maxSample(bitDepth);
        if (identityMatrix) {
            return packOpaqueArgb(
                    sampleToByte(clampToSampleDepth(vSample, sampleMax), bitDepth),
                    sampleToByte(clampToSampleDepth(ySample, sampleMax), bitDepth),
                    sampleToByte(clampToSampleDepth(uSample, sampleMax), bitDepth)
            );
        }

        int scaledLumaOffset = scaleNominalCodeValue(lumaOffset, bitDepth);
        int scaledChromaCenter = scaleNominalCodeValue(chromaCenter, bitDepth);

        long scaledLuma = (long) Math.max(0, ySample - scaledLumaOffset) * lumaCoefficient;
        int centeredU = uSample - scaledChromaCenter;
        int centeredV = vSample - scaledChromaCenter;

        int red = normalizeHighBitDepthChannel(
                (int) ((scaledLuma
                        + (long) centeredU * redCoefficientU
                        + (long) centeredV * redCoefficientV
                        + ROUNDING) >> FRACTION_BITS),
                bitDepth,
                sampleMax
        );
        int green = normalizeHighBitDepthChannel(
                (int) ((scaledLuma
                        + (long) centeredU * greenCoefficientU
                        + (long) centeredV * greenCoefficientV
                        + ROUNDING) >> FRACTION_BITS),
                bitDepth,
                sampleMax
        );
        int blue = normalizeHighBitDepthChannel(
                (int) ((scaledLuma
                        + (long) centeredU * blueCoefficientU
                        + (long) centeredV * blueCoefficientV
                        + ROUNDING) >> FRACTION_BITS),
                bitDepth,
                sampleMax
        );
        return packOpaqueArgb(sampleToByte(red, bitDepth), sampleToByte(green, bitDepth), sampleToByte(blue, bitDepth));
    }

    /// Converts one luma sample with neutral chroma into an opaque 16-bit-per-channel grayscale
    /// ARGB pixel.
    ///
    /// @param ySample the luma sample in the caller-supplied bit depth
    /// @param bitDepth the decoded sample bit depth
    /// @return the packed opaque grayscale ARGB pixel in `0xAAAA_RRRR_GGGG_BBBB` format
    public long toOpaqueGrayArgb64(int ySample, int bitDepth) {
        if (identityMatrix) {
            int gray = expandSampleToWord(clampToSampleDepth(ySample, maxSample(bitDepth)), bitDepth);
            return packOpaqueArgb64(gray, gray, gray);
        }
        int neutralChroma = scaleNominalCodeValue(chromaCenter, bitDepth);
        return toOpaqueArgb64(ySample, neutralChroma, neutralChroma, bitDepth);
    }

    /// Converts one YUV sample triplet into a packed opaque 16-bit-per-channel ARGB pixel.
    ///
    /// The YUV samples are interpreted in their original decoded bit depth. RGB channels are first
    /// computed in that same sample domain, then expanded to unsigned 16-bit output channels.
    ///
    /// @param ySample the luma sample in the caller-supplied bit depth
    /// @param uSample the chroma U sample in the caller-supplied bit depth
    /// @param vSample the chroma V sample in the caller-supplied bit depth
    /// @param bitDepth the decoded sample bit depth
    /// @return the packed opaque non-premultiplied ARGB pixel in `0xAAAA_RRRR_GGGG_BBBB` format
    public long toOpaqueArgb64(int ySample, int uSample, int vSample, int bitDepth) {
        int sampleMax = maxSample(bitDepth);
        if (identityMatrix) {
            return packOpaqueArgb64(
                    expandSampleToWord(clampToSampleDepth(vSample, sampleMax), bitDepth),
                    expandSampleToWord(clampToSampleDepth(ySample, sampleMax), bitDepth),
                    expandSampleToWord(clampToSampleDepth(uSample, sampleMax), bitDepth)
            );
        }

        int scaledLumaOffset = scaleNominalCodeValue(lumaOffset, bitDepth);
        int scaledChromaCenter = scaleNominalCodeValue(chromaCenter, bitDepth);

        long scaledLuma = (long) Math.max(0, ySample - scaledLumaOffset) * lumaCoefficient;
        int centeredU = uSample - scaledChromaCenter;
        int centeredV = vSample - scaledChromaCenter;

        int red = normalizeHighBitDepthChannel(
                (int) ((scaledLuma
                        + (long) centeredU * redCoefficientU
                        + (long) centeredV * redCoefficientV
                        + ROUNDING) >> FRACTION_BITS),
                bitDepth,
                sampleMax
        );
        int green = normalizeHighBitDepthChannel(
                (int) ((scaledLuma
                        + (long) centeredU * greenCoefficientU
                        + (long) centeredV * greenCoefficientV
                        + ROUNDING) >> FRACTION_BITS),
                bitDepth,
                sampleMax
        );
        int blue = normalizeHighBitDepthChannel(
                (int) ((scaledLuma
                        + (long) centeredU * blueCoefficientU
                        + (long) centeredV * blueCoefficientV
                        + ROUNDING) >> FRACTION_BITS),
                bitDepth,
                sampleMax
        );
        return packOpaqueArgb64(
                expandSampleToWord(red, bitDepth),
                expandSampleToWord(green, bitDepth),
                expandSampleToWord(blue, bitDepth)
        );
    }

    /// Creates one matrix transform from luma coefficients and range signaling.
    ///
    /// @param redLumaCoefficient the red channel luma coefficient `Kr`
    /// @param blueLumaCoefficient the blue channel luma coefficient `Kb`
    /// @param fullRange whether nominal full-range sample values are used
    /// @return one fixed-point transform
    private static YuvToRgbTransform createMatrixTransform(
            double redLumaCoefficient,
            double blueLumaCoefficient,
            boolean fullRange
    ) {
        double greenLumaCoefficient = 1.0 - redLumaCoefficient - blueLumaCoefficient;
        double lumaScale = fullRange ? 1.0 : 255.0 / 219.0;
        double chromaScale = fullRange ? 1.0 : 255.0 / 224.0;
        return new YuvToRgbTransform(
                fullRange ? 0 : 16,
                128,
                fixed(lumaScale),
                fixed(2.0 * (1.0 - redLumaCoefficient) * chromaScale),
                fixed(-2.0 * blueLumaCoefficient * (1.0 - blueLumaCoefficient) / greenLumaCoefficient * chromaScale),
                fixed(-2.0 * redLumaCoefficient * (1.0 - redLumaCoefficient) / greenLumaCoefficient * chromaScale),
                fixed(2.0 * (1.0 - blueLumaCoefficient) * chromaScale)
        );
    }

    /// Creates the equal-bit-depth inverse `YCgCo` transform defined by H.273.
    ///
    /// Limited-range `YCgCo` applies the luma-range scale to every reconstructed RGB component;
    /// its reversible color-difference samples do not use the conventional 224-code chroma range.
    ///
    /// @param fullRange whether nominal full-range component values are used
    /// @return one fixed-point inverse `YCgCo` transform
    private static YuvToRgbTransform createYcgcoTransform(boolean fullRange) {
        double componentScale = fullRange ? 1.0 : 255.0 / 219.0;
        return new YuvToRgbTransform(
                fullRange ? 0 : 16,
                128,
                fixed(componentScale),
                fixed(-componentScale),
                fixed(componentScale),
                fixed(componentScale),
                0,
                fixed(-componentScale),
                fixed(-componentScale),
                false
        );
    }

    /// Converts a floating-point coefficient into 16-bit fixed point.
    ///
    /// @param value the floating-point coefficient
    /// @return the rounded fixed-point coefficient
    private static int fixed(double value) {
        return (int) Math.round(value * (1 << FRACTION_BITS));
    }

    /// Returns the default transform for unspecified matrix coefficients or monochrome output.
    ///
    /// @param fullRange whether the signaled samples are full range
    /// @return the default transform
    private static YuvToRgbTransform defaultTransform(boolean fullRange) {
        return fullRange ? BT601_FULL_RANGE : BT601_LIMITED_RANGE;
    }

    /// Packs three unsigned 8-bit channels into opaque `0xAARRGGBB` output.
    ///
    /// @param red the 8-bit red channel
    /// @param green the 8-bit green channel
    /// @param blue the 8-bit blue channel
    /// @return the packed opaque ARGB pixel
    private static int packOpaqueArgb(int red, int green, int blue) {
        return 0xFF00_0000 | (red << 16) | (green << 8) | blue;
    }

    /// Normalizes one computed high-bit-depth channel into the decoded sample domain.
    ///
    /// Limited-range transforms compute channels in a nominal 8-bit scale expanded by shifting,
    /// so high-bit-depth output must map that nominal maximum onto the real sample maximum.
    ///
    /// @param value the computed channel value before final clamping
    /// @param bitDepth the decoded sample bit depth
    /// @param sampleMax the inclusive maximum sample value for the decoded bit depth
    /// @return the normalized and clamped channel value
    private int normalizeHighBitDepthChannel(int value, int bitDepth, int sampleMax) {
        if (lumaOffset == 0) {
            return clampToSampleDepth(value, sampleMax);
        }
        int nominalMax = scaleNominalCodeValue(255, bitDepth);
        int normalized = (int) (((long) value * sampleMax + (nominalMax >>> 1)) / nominalMax);
        return clampToSampleDepth(normalized, sampleMax);
    }

    /// Clamps one integer channel value to the unsigned 8-bit range.
    ///
    /// @param value the channel value before clamping
    /// @return the clamped unsigned 8-bit channel value
    private static int clampToByte(int value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= 255) {
            return 255;
        }
        return value;
    }

    /// Clamps one integer channel value to the caller-supplied decoded sample range.
    ///
    /// @param value the channel value before clamping
    /// @param sampleMax the inclusive maximum sample value for the decoded bit depth
    /// @return the clamped channel value
    private static int clampToSampleDepth(int value, int sampleMax) {
        if (value <= 0) {
            return 0;
        }
        if (value >= sampleMax) {
            return sampleMax;
        }
        return value;
    }

    /// Reduces one decoded sample value to an unsigned 8-bit output channel.
    ///
    /// @param sample the decoded sample value
    /// @param bitDepth the decoded sample bit depth
    /// @return the reduced unsigned 8-bit channel
    private static int sampleToByte(int sample, int bitDepth) {
        int sampleMax = maxSample(bitDepth);
        return (int) (((long) sample * 255 + (sampleMax >>> 1)) / sampleMax);
    }

    /// Packs three unsigned 16-bit channels into opaque `0xAAAA_RRRR_GGGG_BBBB` output.
    ///
    /// @param red the 16-bit red channel
    /// @param green the 16-bit green channel
    /// @param blue the 16-bit blue channel
    /// @return the packed opaque ARGB pixel
    private static long packOpaqueArgb64(int red, int green, int blue) {
        return 0xFFFF_0000_0000_0000L
                | ((long) red << 32)
                | ((long) green << 16)
                | (blue & 0xFFFFL);
    }

    /// Expands one decoded sample to an unsigned 16-bit output channel.
    ///
    /// @param sample the decoded sample value
    /// @param bitDepth the decoded sample bit depth
    /// @return the expanded unsigned 16-bit output channel
    private static int expandSampleToWord(int sample, int bitDepth) {
        int sampleMax = maxSample(bitDepth);
        return (int) (((long) sample * 65_535 + (sampleMax >>> 1)) / sampleMax);
    }

    /// Scales one nominal 8-bit code value into the caller-supplied decoded bit depth.
    ///
    /// @param nominalCodeValue the nominal 8-bit code value
    /// @param bitDepth the decoded sample bit depth
    /// @return the scaled code value in the decoded sample domain
    private static int scaleNominalCodeValue(int nominalCodeValue, int bitDepth) {
        if (bitDepth < 8 || bitDepth > 16) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        return nominalCodeValue << (bitDepth - 8);
    }

    /// Returns the inclusive maximum sample value for one decoded bit depth.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @return the inclusive maximum sample value
    private static int maxSample(int bitDepth) {
        if (bitDepth < 1 || bitDepth > 16) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        return (1 << bitDepth) - 1;
    }
}
