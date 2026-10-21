// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;

import pzopt.vendor.avif.internal.av1.output.YuvToRgbTransform;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Applies parsed AVIF gain-map metadata to decoded RGB frame output.
///
/// This implementation follows the ISO gain-map equation over normalized RGB samples, uses CICP
/// transfer functions where available, converts between supported RGB primary sets in linear
/// light, and leaves ICC transforms metadata-only under the repository's `java.base` runtime
/// boundary.

final class AvifGainMapToneMapper {
    /// Prevents instantiation of this utility class.
    private AvifGainMapToneMapper() {
    }

    /// Applies one gain map to a decoded base frame.
    ///
    /// @param baseFrame the decoded base frame
    /// @param gainMapPlanes the decoded gain-map planes, or `null` when the gain-map weight is zero
    /// @param metadata the parsed gain-map metadata
    /// @param baseColorInfo the base image CICP color information, or `null`
    /// @param toneMappedColorInfo the tone-mapped item CICP color information, or `null`
    /// @param gainMapColorInfo the gain-map image item CICP color information, or `null`
    /// @param outputColorInfo the requested output CICP color information, or `null` to preserve the base color space
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @return the tone-mapped frame, or the original frame when no pixel transform is needed
    static AvifFrame apply(
            AvifFrame baseFrame,
            Av1DecodedPlanes gainMapPlanes,
            AvifGainMapMetadata metadata,
            AvifColorInfo baseColorInfo,
            AvifColorInfo toneMappedColorInfo,
            AvifColorInfo gainMapColorInfo,
            AvifColorInfo outputColorInfo,
            double hdrHeadroom
    ) {
        AvifFrame checkedBaseFrame = Objects.requireNonNull(baseFrame, "baseFrame");
        GainMapMath math = new GainMapMath(
                Objects.requireNonNull(metadata, "metadata"),
                baseColorInfo,
                toneMappedColorInfo,
                outputColorInfo,
                hdrHeadroom
        );
        if (!math.requiresPixelTransform()) {
            return checkedBaseFrame;
        }
        GainMapSampler sampler = null;
        if (math.requiresGainMap()) {
            sampler = new GainMapSampler(
                    Objects.requireNonNull(gainMapPlanes, "gainMapPlanes"),
                    checkedBaseFrame.width(),
                    checkedBaseFrame.height(),
                    gainMapColorInfo
            );
        }
        if (checkedBaseFrame.pixelFormat() == AvifPixelFormat.ARGB_8888) {
            return applyToIntFrame(checkedBaseFrame, sampler, math);
        }
        if (checkedBaseFrame.pixelFormat() == AvifPixelFormat.ARGB_16161616) {
            return applyToLongFrame(checkedBaseFrame, sampler, math);
        }
        throw new IllegalArgumentException("Unsupported pixel format: " + checkedBaseFrame.pixelFormat());
    }

    /// Returns whether the gain-map planes must be decoded for one requested headroom.
    ///
    /// @param metadata the parsed gain-map metadata
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @return whether the gain-map planes must be decoded
    static boolean requiresGainMap(AvifGainMapMetadata metadata, double hdrHeadroom) {
        if (!Double.isFinite(hdrHeadroom) || hdrHeadroom < 0.0) {
            throw new IllegalArgumentException("hdrHeadroom must be a finite non-negative value");
        }
        AvifGainMapMetadata checkedMetadata = Objects.requireNonNull(metadata, "metadata");
        return GainMapMath.computeWeight(
                hdrHeadroom,
                checkedMetadata.baseHdrHeadroom(),
                checkedMetadata.alternateHdrHeadroom()
        ) != 0.0;
    }

    /// Applies gain-map metadata to an 8-bit ARGB frame.
    ///
    /// @param baseFrame the decoded base frame
    /// @param sampler the gain-map sampler, or `null` when only output color conversion is needed
    /// @param math the precomputed gain-map math state
    /// @return the tone-mapped frame
    private static AvifFrame applyToIntFrame(
            AvifFrame baseFrame,
            GainMapSampler sampler,
            GainMapMath math
    ) {
        IntBuffer basePixels = baseFrame.intPixelBuffer();
        int width = baseFrame.width();
        int height = baseFrame.height();
        int[] toneMapped = new int[width * height];
        double[] mappedRgb = new double[3];
        boolean useGainMap = math.requiresGainMap();
        GainMapSampler checkedSampler = useGainMap ? Objects.requireNonNull(sampler, "sampler") : null;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = basePixels.get(row + x);
                int alpha = pixel & 0xFF00_0000;
                double redSample = ((pixel >>> 16) & 0xFF) / 255.0;
                double greenSample = ((pixel >>> 8) & 0xFF) / 255.0;
                double blueSample = (pixel & 0xFF) / 255.0;
                if (useGainMap) {
                    math.applyGainMap(
                            redSample,
                            greenSample,
                            blueSample,
                            checkedSampler.channel(x, y, 0),
                            checkedSampler.channel(x, y, 1),
                            checkedSampler.channel(x, y, 2),
                            mappedRgb
                    );
                } else {
                    math.convertBaseOnly(redSample, greenSample, blueSample, mappedRgb);
                }
                int red = unitToByte(mappedRgb[0]);
                int green = unitToByte(mappedRgb[1]);
                int blue = unitToByte(mappedRgb[2]);
                toneMapped[row + x] = alpha | (red << 16) | (green << 8) | blue;
            }
        }
        return AvifFrame.fromOwnedPixels(
                width,
                height,
                baseFrame.bitDepth(),
                baseFrame.chromaFormat(),
                baseFrame.frameIndex(),
                toneMapped
        );
    }

    /// Applies gain-map metadata to a 16-bit-per-channel ARGB frame.
    ///
    /// @param baseFrame the decoded base frame
    /// @param sampler the gain-map sampler, or `null` when only output color conversion is needed
    /// @param math the precomputed gain-map math state
    /// @return the tone-mapped frame
    private static AvifFrame applyToLongFrame(
            AvifFrame baseFrame,
            GainMapSampler sampler,
            GainMapMath math
    ) {
        LongBuffer basePixels = baseFrame.longPixelBuffer();
        int width = baseFrame.width();
        int height = baseFrame.height();
        long[] toneMapped = new long[width * height];
        double[] mappedRgb = new double[3];
        boolean useGainMap = math.requiresGainMap();
        GainMapSampler checkedSampler = useGainMap ? Objects.requireNonNull(sampler, "sampler") : null;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                long pixel = basePixels.get(row + x);
                long alpha = pixel & 0xFFFF_0000_0000_0000L;
                double redSample = ((pixel >>> 32) & 0xFFFFL) / 65_535.0;
                double greenSample = ((pixel >>> 16) & 0xFFFFL) / 65_535.0;
                double blueSample = (pixel & 0xFFFFL) / 65_535.0;
                if (useGainMap) {
                    math.applyGainMap(
                            redSample,
                            greenSample,
                            blueSample,
                            checkedSampler.channel(x, y, 0),
                            checkedSampler.channel(x, y, 1),
                            checkedSampler.channel(x, y, 2),
                            mappedRgb
                    );
                } else {
                    math.convertBaseOnly(redSample, greenSample, blueSample, mappedRgb);
                }
                long red = unitToWord(mappedRgb[0]);
                long green = unitToWord(mappedRgb[1]);
                long blue = unitToWord(mappedRgb[2]);
                toneMapped[row + x] = alpha | (red << 32) | (green << 16) | blue;
            }
        }
        return AvifFrame.fromOwnedPixels(
                width,
                height,
                baseFrame.bitDepth(),
                baseFrame.chromaFormat(),
                baseFrame.frameIndex(),
                toneMapped
        );
    }

    /// Converts a normalized value to an unsigned 8-bit channel.
    ///
    /// @param value the normalized value
    /// @return the unsigned 8-bit channel
    private static int unitToByte(double value) {
        return (int) Math.round(AvifCicpColorTransforms.clampUnit(value) * 255.0);
    }

    /// Converts a normalized value to an unsigned 16-bit channel.
    ///
    /// @param value the normalized value
    /// @return the unsigned 16-bit channel
    private static long unitToWord(double value) {
        return Math.round(AvifCicpColorTransforms.clampUnit(value) * 65_535.0);
    }

    /// Precomputed gain-map equation state.
    
    private static final class GainMapMath {
        /// The gain-map application weight.
        private final double weight;
        /// Per-channel minimum gain-map values in log2 space.
        private final double [] gainMapMin;
        /// Per-channel maximum gain-map values in log2 space.
        private final double [] gainMapMax;
        /// Per-channel inverse gamma values.
        private final double [] gammaInverse;
        /// Per-channel base image offsets.
        private final double [] baseOffset;
        /// Per-channel alternate image offsets.
        private final double [] alternateOffset;
        /// Base gamma-to-linear transfer color information.
        private final AvifColorInfo baseColorInfo;
        /// Output linear-to-gamma transfer color information.
        private final AvifColorInfo outputColorInfo;
        /// Base-to-gain-map-math linear RGB primary conversion.
        private final AvifCicpColorTransforms.RgbMatrix inputConversion;
        /// Gain-map-math-to-output linear RGB primary conversion.
        private final AvifCicpColorTransforms.RgbMatrix outputConversion;
        /// Base-to-output linear RGB primary conversion used when gain-map weight is zero.
        private final AvifCicpColorTransforms.RgbMatrix baseOutputConversion;
        /// Whether output color conversion is required when the gain-map weight is zero.
        private final boolean outputColorConversionRequired;

        /// Creates precomputed gain-map equation state.
        ///
        /// @param metadata the parsed gain-map metadata
        /// @param baseColorInfo the base image CICP color information, or `null`
        /// @param toneMappedColorInfo the tone-mapped item CICP color information, or `null`
        /// @param outputColorInfo the requested output CICP color information, or `null`
        /// @param hdrHeadroom the requested display HDR headroom in log2 space
        private GainMapMath(
                AvifGainMapMetadata metadata,
                AvifColorInfo baseColorInfo,
                AvifColorInfo toneMappedColorInfo,
                AvifColorInfo outputColorInfo,
                double hdrHeadroom
        ) {
            if (!Double.isFinite(hdrHeadroom) || hdrHeadroom < 0.0) {
                throw new IllegalArgumentException("hdrHeadroom must be a finite non-negative value");
            }
            AvifColorInfo gainMapMathColorInfo =
                    metadata.useBaseColorSpace() || toneMappedColorInfo == null ? baseColorInfo : toneMappedColorInfo;
            AvifColorInfo effectiveOutputColorInfo = outputColorInfo != null ? outputColorInfo : baseColorInfo;
            this.weight = computeWeight(hdrHeadroom, metadata.baseHdrHeadroom(), metadata.alternateHdrHeadroom());
            this.gainMapMin = signedFractionsToDoubles(metadata.gainMapMin());
            this.gainMapMax = signedFractionsToDoubles(metadata.gainMapMax());
            this.gammaInverse = inverseUnsignedFractions(metadata.gainMapGamma());
            this.baseOffset = signedFractionsToDoubles(metadata.baseOffset());
            this.alternateOffset = signedFractionsToDoubles(metadata.alternateOffset());
            this.baseColorInfo = baseColorInfo;
            this.outputColorInfo = effectiveOutputColorInfo;
            this.inputConversion = AvifCicpColorTransforms.conversionMatrix(baseColorInfo, gainMapMathColorInfo);
            this.outputConversion = AvifCicpColorTransforms.conversionMatrix(gainMapMathColorInfo, effectiveOutputColorInfo);
            this.baseOutputConversion = AvifCicpColorTransforms.conversionMatrix(baseColorInfo, effectiveOutputColorInfo);
            this.outputColorConversionRequired =
                    !AvifCicpColorTransforms.sameEffectiveRgbColorSpace(baseColorInfo, effectiveOutputColorInfo);
        }

        /// Returns whether the gain map must be sampled.
        ///
        /// @return whether the gain map must be sampled
        private boolean requiresGainMap() {
            return weight != 0.0;
        }

        /// Returns whether any pixel transform is required.
        ///
        /// @return whether any pixel transform is required
        private boolean requiresPixelTransform() {
            return requiresGainMap() || outputColorConversionRequired;
        }

        /// Converts one normalized base RGB triplet into the requested output color space.
        ///
        /// @param baseRed the normalized base red channel
        /// @param baseGreen the normalized base green channel
        /// @param baseBlue the normalized base blue channel
        /// @param outputRgb the mutable normalized gamma-encoded output RGB triplet
        private void convertBaseOnly(double baseRed, double baseGreen, double baseBlue, double[] outputRgb) {
            outputRgb[0] = AvifCicpColorTransforms.gammaToLinear(baseRed, baseColorInfo);
            outputRgb[1] = AvifCicpColorTransforms.gammaToLinear(baseGreen, baseColorInfo);
            outputRgb[2] = AvifCicpColorTransforms.gammaToLinear(baseBlue, baseColorInfo);
            baseOutputConversion.apply(outputRgb);
            outputRgb[0] = AvifCicpColorTransforms.linearToGamma(outputRgb[0], outputColorInfo);
            outputRgb[1] = AvifCicpColorTransforms.linearToGamma(outputRgb[1], outputColorInfo);
            outputRgb[2] = AvifCicpColorTransforms.linearToGamma(outputRgb[2], outputColorInfo);
        }

        /// Applies gain-map math to one normalized gamma-encoded RGB triplet.
        ///
        /// @param baseRed the normalized base red channel
        /// @param baseGreen the normalized base green channel
        /// @param baseBlue the normalized base blue channel
        /// @param gainMapRed the normalized gain-map red channel
        /// @param gainMapGreen the normalized gain-map green channel
        /// @param gainMapBlue the normalized gain-map blue channel
        /// @param outputRgb the mutable normalized gamma-encoded output RGB triplet
        private void applyGainMap(
                double baseRed,
                double baseGreen,
                double baseBlue,
                double gainMapRed,
                double gainMapGreen,
                double gainMapBlue,
                double[] outputRgb
        ) {
            outputRgb[0] = AvifCicpColorTransforms.gammaToLinear(baseRed, baseColorInfo);
            outputRgb[1] = AvifCicpColorTransforms.gammaToLinear(baseGreen, baseColorInfo);
            outputRgb[2] = AvifCicpColorTransforms.gammaToLinear(baseBlue, baseColorInfo);
            inputConversion.apply(outputRgb);
            outputRgb[0] = applyChannel(0, outputRgb[0], gainMapRed);
            outputRgb[1] = applyChannel(1, outputRgb[1], gainMapGreen);
            outputRgb[2] = applyChannel(2, outputRgb[2], gainMapBlue);
            outputConversion.apply(outputRgb);
            outputRgb[0] = AvifCicpColorTransforms.linearToGamma(outputRgb[0], outputColorInfo);
            outputRgb[1] = AvifCicpColorTransforms.linearToGamma(outputRgb[1], outputColorInfo);
            outputRgb[2] = AvifCicpColorTransforms.linearToGamma(outputRgb[2], outputColorInfo);
        }

        /// Applies gain-map math to one linear-light channel.
        ///
        /// @param channel the RGB channel index
        /// @param baseLinear the normalized linear-light base value
        /// @param gainMapSample the normalized gain-map sample
        /// @return the normalized linear-light tone-mapped value
        private double applyChannel(int channel, double baseLinear, double gainMapSample) {
            double gainMapEncoded = clampUnit(gainMapSample);
            double gainMapLog2 = lerp(
                    gainMapMin[channel],
                    gainMapMax[channel],
                    Math.pow(gainMapEncoded, gammaInverse[channel])
            );
            return (baseLinear + baseOffset[channel]) * Math.pow(2.0, gainMapLog2 * weight)
                    - alternateOffset[channel];
        }

        /// Computes the gain-map application weight for one display headroom.
        ///
        /// @param hdrHeadroom the requested display HDR headroom in log2 space
        /// @param baseHdrHeadroom the base image HDR headroom
        /// @param alternateHdrHeadroom the alternate image HDR headroom
        /// @return the gain-map application weight
        private static double computeWeight(
                double hdrHeadroom,
                AvifUnsignedFraction baseHdrHeadroom,
                AvifUnsignedFraction alternateHdrHeadroom
        ) {
            double base = baseHdrHeadroom.toDouble();
            double alternate = alternateHdrHeadroom.toDouble();
            if (base == alternate) {
                return 0.0;
            }
            double weight = clampUnit((hdrHeadroom - base) / (alternate - base));
            return alternate < base ? -weight : weight;
        }

        /// Linearly interpolates between two values.
        ///
        /// @param a the first value
        /// @param b the second value
        /// @param weight the interpolation weight
        /// @return the interpolated value
        private static double lerp(double a, double b, double weight) {
            return (1.0 - weight) * a + weight * b;
        }

        /// Clamps a normalized sample to the closed unit interval.
        ///
        /// @param value the sample value
        /// @return the clamped value
        private static double clampUnit(double value) {
            return AvifCicpColorTransforms.clampUnit(value);
        }

        /// Converts signed fractions to `double` values.
        ///
        /// @param fractions the source fractions
        /// @return converted values
        private static double [] signedFractionsToDoubles(
                AvifSignedFraction [] fractions
        ) {
            double[] result = new double[fractions.length];
            for (int i = 0; i < fractions.length; i++) {
                result[i] = fractions[i].toDouble();
            }
            return result;
        }

        /// Converts unsigned gamma fractions to inverse `double` values.
        ///
        /// @param fractions the source gamma fractions
        /// @return inverse gamma values
        private static double [] inverseUnsignedFractions(
                AvifUnsignedFraction [] fractions
        ) {
            double[] result = new double[fractions.length];
            for (int i = 0; i < fractions.length; i++) {
                result[i] = 1.0 / fractions[i].toDouble();
            }
            return result;
        }
    }

    /// Samples normalized RGB gain-map values at base-frame coordinates.
    
    private static final class GainMapSampler {
        /// The decoded gain-map planes.
        private final Av1DecodedPlanes planes;
        /// The base frame width in pixels.
        private final int baseWidth;
        /// The base frame height in pixels.
        private final int baseHeight;
        /// The maximum gain-map sample value.
        private final int maxSample;
        /// The gain-map YUV-to-RGB transform.
        private final YuvToRgbTransform yuvToRgbTransform;
        /// The cached RGB pixel for the last sampled gain-map coordinate.
        private int cachedRgb;
        /// The last sampled gain-map x coordinate, or -1 before the first sample.
        private int cachedX = -1;
        /// The last sampled gain-map y coordinate, or -1 before the first sample.
        private int cachedY = -1;

        /// Creates a gain-map sampler.
        ///
        /// @param planes the decoded gain-map planes
        /// @param baseWidth the base frame width in pixels
        /// @param baseHeight the base frame height in pixels
        /// @param gainMapColorInfo the gain-map image item CICP color information, or `null`
        private GainMapSampler(
                Av1DecodedPlanes planes,
                int baseWidth,
                int baseHeight,
                AvifColorInfo gainMapColorInfo
        ) {
            if (baseWidth <= 0 || baseHeight <= 0) {
                throw new IllegalArgumentException("base frame dimensions must be positive");
            }
            this.planes = Objects.requireNonNull(planes, "planes");
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
            this.maxSample = planes.bitDepth().maxSampleValue();
            this.yuvToRgbTransform = gainMapColorInfo != null
                    ? YuvToRgbTransform.fromColorInfo(gainMapColorInfo, planes.chromaFormat() == Av1ChromaFormat.MONOCHROME)
                    : YuvToRgbTransform.BT601_FULL_RANGE;
        }

        /// Returns a normalized gain-map RGB channel for one base pixel coordinate.
        ///
        /// @param baseX the base frame x coordinate
        /// @param baseY the base frame y coordinate
        /// @param channel the RGB channel index
        /// @return the normalized gain-map channel
        private double channel(int baseX, int baseY, int channel) {
            int x = mapCoordinate(baseX, baseWidth, planes.codedWidth());
            int y = mapCoordinate(baseY, baseHeight, planes.codedHeight());
            if (planes.chromaFormat() == Av1ChromaFormat.MONOCHROME) {
                return sampleToUnit(planes.lumaPlane().sample(x, y));
            }
            int rgb = rgbAt(x, y);
            return switch (channel) {
                case 0 -> ((rgb >>> 16) & 0xFF) / 255.0;
                case 1 -> ((rgb >>> 8) & 0xFF) / 255.0;
                case 2 -> (rgb & 0xFF) / 255.0;
                default -> throw new IllegalArgumentException("channel out of range: " + channel);
            };
        }

        /// Converts one gain-map YUV sample location to an 8-bit RGB pixel.
        ///
        /// @param x the gain-map luma x coordinate
        /// @param y the gain-map luma y coordinate
        /// @return an opaque ARGB pixel
        private int rgbAt(int x, int y) {
            if (x == cachedX && y == cachedY) {
                return cachedRgb;
            }
            Av1DecodedPlane chromaUPlane = planes.chromaUPlane();
            Av1DecodedPlane chromaVPlane = planes.chromaVPlane();
            if (chromaUPlane == null || chromaVPlane == null) {
                throw new IllegalArgumentException("Gain-map chroma planes are missing");
            }
            int chromaX = chromaX(x);
            int chromaY = chromaY(y);
            cachedRgb = yuvToRgbTransform.toOpaqueArgb(
                    planes.lumaPlane().sample(x, y),
                    chromaUPlane.sample(chromaX, chromaY),
                    chromaVPlane.sample(chromaX, chromaY),
                    planes.bitDepth().bits()
            );
            cachedX = x;
            cachedY = y;
            return cachedRgb;
        }

        /// Maps a base-frame coordinate into the gain-map coordinate space.
        ///
        /// @param value the base-frame coordinate
        /// @param sourceSize the base-frame dimension
        /// @param targetSize the gain-map dimension
        /// @return the mapped gain-map coordinate
        private static int mapCoordinate(int value, int sourceSize, int targetSize) {
            long mapped = (long) value * targetSize / sourceSize;
            if (mapped >= targetSize) {
                return targetSize - 1;
            }
            return (int) mapped;
        }

        /// Converts one decoded gain-map sample to the normalized unit interval.
        ///
        /// @param sample the decoded sample
        /// @return the normalized sample
        private double sampleToUnit(int sample) {
            return AvifCicpColorTransforms.clampUnit((double) sample / (double) maxSample);
        }

        /// Returns the chroma x coordinate for one luma x coordinate.
        ///
        /// @param x the luma x coordinate
        /// @return the chroma x coordinate
        private int chromaX(int x) {
            return switch (planes.chromaFormat()) {
                case MONOCHROME, YUV444 -> x;
                case YUV420, YUV422 -> x >> 1;
            };
        }

        /// Returns the chroma y coordinate for one luma y coordinate.
        ///
        /// @param y the luma y coordinate
        /// @return the chroma y coordinate
        private int chromaY(int y) {
            return switch (planes.chromaFormat()) {
                case MONOCHROME, YUV422, YUV444 -> y;
                case YUV420 -> y >> 1;
            };
        }
    }
}
