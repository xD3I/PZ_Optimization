// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.model.TransformResidualUnit;
import pzopt.vendor.avif.internal.av1.model.TransformSize;
import pzopt.vendor.avif.internal.av1.model.TransformType;

import java.util.Arrays;
import java.util.Objects;

/// Dequantizes AV1 luma transform coefficients.
///
/// Applies the AV1 `8-bit`, `10-bit`, and `12-bit` QTX lookup tables, optional frame-level
/// quantization matrices, transform-size shifts, and coefficient saturation.

final class LumaDequantizer {
    /// Prevents instantiation of this utility class.
    private LumaDequantizer() {
    }

    /// Dequantizes one luma transform residual unit into transform-domain coefficients.
    ///
    /// DC uses the frame-level luma DC delta while AC uses the block-local `qindex` unchanged.
    /// All-zero units return a fresh all-zero array of the matching transform area.
    ///
    /// @param residualUnit the luma residual unit to dequantize
    /// @param context the block-local dequantization context
    /// @return one dequantized transform-domain coefficient block in natural raster order
    static int[] dequantize(TransformResidualUnit residualUnit, Context context) {
        TransformResidualUnit nonNullResidualUnit = Objects.requireNonNull(residualUnit, "residualUnit");
        int[] dequantizedCoefficients = new int[nonNullResidualUnit.coefficientCount()];
        dequantize(nonNullResidualUnit, context, dequantizedCoefficients);
        return dequantizedCoefficients;
    }

    /// Dequantizes one luma transform residual unit into caller-owned exact-length storage.
    ///
    /// The complete destination array is overwritten. DC uses the frame-level luma DC delta while
    /// AC uses the block-local `qindex` unchanged.
    ///
    /// @param residualUnit the luma residual unit to dequantize
    /// @param context the block-local dequantization context
    /// @param destination the exact-length destination in natural raster order
    static void dequantize(
            TransformResidualUnit residualUnit,
            Context context,
            int[] destination
    ) {
        TransformResidualUnit nonNullResidualUnit = Objects.requireNonNull(residualUnit, "residualUnit");
        Context nonNullContext = Objects.requireNonNull(context, "context");
        dequantize(
                nonNullResidualUnit,
                nonNullContext.qIndex(),
                nonNullContext.yDcDelta(),
                nonNullContext.bitDepth(),
                nonNullContext.useQuantizationMatrices(),
                nonNullContext.quantizationMatrix(),
                destination
        );
    }

    /// Dequantizes only the DC coefficient of one luma residual unit.
    ///
    /// @param residualUnit the luma residual unit to dequantize
    /// @param qIndex the block-local luma quantizer index in `[0, 255]`
    /// @param yDcDelta the frame-level luma DC delta quantizer
    /// @param bitDepth the decoded sample bit depth
    /// @param useQuantizationMatrices whether frame-level quantization matrices are enabled
    /// @param quantizationMatrixIndex the luma quantization matrix index in `[0, 15]`
    /// @return the signed dequantized DC coefficient, or zero for an all-zero unit
    static int dequantizeDcCoefficient(
            TransformResidualUnit residualUnit,
            int qIndex,
            int yDcDelta,
            int bitDepth,
            boolean useQuantizationMatrices,
            int quantizationMatrixIndex
    ) {
        TransformResidualUnit nonNullResidualUnit = Objects.requireNonNull(residualUnit, "residualUnit");
        validateParameters(qIndex, bitDepth, quantizationMatrixIndex);

        byte [] quantizationMatrix = quantizationMatrix(
                nonNullResidualUnit,
                useQuantizationMatrices,
                quantizationMatrixIndex
        );
        return scaledDcCoefficient(
                nonNullResidualUnit,
                qIndex,
                yDcDelta,
                QuantizerTables.dequantizationShift(nonNullResidualUnit.size()),
                bitDepth,
                quantizationMatrix
        );
    }

    /// Dequantizes one luma transform residual unit from scalar block parameters.
    ///
    /// This allocation-free entry point overwrites the complete exact-length destination array.
    ///
    /// @param residualUnit the luma residual unit to dequantize
    /// @param qIndex the block-local luma AC quantizer index in `[0, 255]`
    /// @param yDcDelta the frame-level luma DC delta quantizer
    /// @param bitDepth the decoded sample bit depth
    /// @param useQuantizationMatrices whether frame-level quantization matrices are enabled
    /// @param quantizationMatrixIndex the luma quantization matrix index in `[0, 15]`
    /// @param destination the exact-length destination in natural raster order
    static void dequantize(
            TransformResidualUnit residualUnit,
            int qIndex,
            int yDcDelta,
            int bitDepth,
            boolean useQuantizationMatrices,
            int quantizationMatrixIndex,
            int[] destination
    ) {
        TransformResidualUnit nonNullResidualUnit = Objects.requireNonNull(residualUnit, "residualUnit");
        int[] nonNullDestination = Objects.requireNonNull(destination, "destination");
        validateParameters(qIndex, bitDepth, quantizationMatrixIndex);

        int coefficientCount = nonNullResidualUnit.coefficientCount();
        if (nonNullDestination.length != coefficientCount) {
            throw new IllegalArgumentException("destination length does not match transform area");
        }
        if (nonNullResidualUnit.allZero()) {
            Arrays.fill(nonNullDestination, 0);
            return;
        }

        int dequantizationShift = QuantizerTables.dequantizationShift(nonNullResidualUnit.size());
        byte [] quantizationMatrix = quantizationMatrix(
                nonNullResidualUnit,
                useQuantizationMatrices,
                quantizationMatrixIndex
        );
        nonNullDestination[0] = scaledDcCoefficient(
                nonNullResidualUnit,
                qIndex,
                yDcDelta,
                dequantizationShift,
                bitDepth,
                quantizationMatrix
        );

        int acQuantizer = QuantizerTables.acQuantizer(qIndex, bitDepth);
        for (int coefficientIndex = 1; coefficientIndex < coefficientCount; coefficientIndex++) {
            nonNullDestination[coefficientIndex] = scaledCoefficient(
                    nonNullResidualUnit.coefficient(coefficientIndex),
                    acQuantizer,
                    dequantizationShift,
                    bitDepth,
                    matrixValue(quantizationMatrix, nonNullResidualUnit.size(), coefficientIndex)
            );
        }
    }

    /// Validates scalar luma dequantization parameters shared by both entry points.
    ///
    /// @param qIndex the block-local luma quantizer index
    /// @param bitDepth the decoded sample bit depth
    /// @param quantizationMatrixIndex the luma quantization matrix index
    private static void validateParameters(int qIndex, int bitDepth, int quantizationMatrixIndex) {
        if (qIndex < 0 || qIndex > 255) {
            throw new IllegalArgumentException("qIndex out of range: " + qIndex);
        }
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalStateException("Unsupported luma dequantization bit depth: " + bitDepth);
        }
        if (quantizationMatrixIndex < 0 || quantizationMatrixIndex > 15) {
            throw new IllegalArgumentException("quantizationMatrixIndex out of range: " + quantizationMatrixIndex);
        }
    }

    /// Scales one luma DC coefficient from already validated parameters.
    ///
    /// @param residualUnit the luma residual unit
    /// @param qIndex the block-local luma quantizer index
    /// @param yDcDelta the frame-level luma DC delta quantizer
    /// @param dequantizationShift the transform-size dequantization shift
    /// @param bitDepth the decoded sample bit depth
    /// @param quantizationMatrix the active quantization matrix, or `null`
    /// @return the signed dequantized DC coefficient
    private static int scaledDcCoefficient(
            TransformResidualUnit residualUnit,
            int qIndex,
            int yDcDelta,
            int dequantizationShift,
            int bitDepth,
            byte [] quantizationMatrix
    ) {
        return scaledCoefficient(
                residualUnit.coefficient(0),
                QuantizerTables.dcQuantizer(qIndex + yDcDelta, bitDepth),
                dequantizationShift,
                bitDepth,
                matrixValue(quantizationMatrix, residualUnit.size(), 0)
        );
    }

    /// Returns the active quantization matrix for one residual unit, or `null` when no matrix applies.
    ///
    /// @param residualUnit the residual unit to dequantize
    /// @param useQuantizationMatrices whether frame-level quantization matrices are enabled
    /// @param quantizationMatrixIndex the luma quantization matrix index
    /// @return the active quantization matrix, or `null` when no matrix applies
    private static byte [] quantizationMatrix(
            TransformResidualUnit residualUnit,
            boolean useQuantizationMatrices,
            int quantizationMatrixIndex
    ) {
        if (!useQuantizationMatrices || !usesQuantizationMatrix(residualUnit.transformType())) {
            return null;
        }
        return QuantizationMatrixTables.matrix(quantizationMatrixIndex, false, residualUnit.size());
    }

    /// Returns whether one transform type applies frame-level quantization matrices.
    ///
    /// @param transformType the transform type to test
    /// @return whether the transform type applies frame-level quantization matrices
    private static boolean usesQuantizationMatrix(TransformType transformType) {
        return switch (Objects.requireNonNull(transformType, "transformType")) {
            case DCT_DCT,
                 ADST_DCT,
                 DCT_ADST,
                 ADST_ADST,
                 FLIPADST_DCT,
                 DCT_FLIPADST,
                 FLIPADST_FLIPADST,
                 ADST_FLIPADST,
                 FLIPADST_ADST -> true;
            case IDTX,
                 V_DCT,
                 H_DCT,
                 V_ADST,
                 H_ADST,
                 V_FLIPADST,
                 H_FLIPADST,
                 WHT_WHT -> false;
        };
    }

    /// Returns the matrix scale at one natural coefficient index.
    ///
    /// A neutral scale is returned when no matrix applies or when a 64-wide/high transform index is
    /// outside the entropy-coded 32-coefficient span.
    ///
    /// @param quantizationMatrix the active quantization matrix, or `null`
    /// @param transformSize the active transform size
    /// @param coefficientIndex the natural raster coefficient index
    /// @return the quantization-matrix scale, or `32` for neutral scaling
    private static int matrixValue(
            byte [] quantizationMatrix,
            TransformSize transformSize,
            int coefficientIndex
    ) {
        if (quantizationMatrix == null) {
            return 32;
        }
        int transformWidth = transformSize.widthPixels();
        int matrixWidth = QuantizationMatrixTables.matrixWidth(transformSize);
        int matrixHeight = QuantizationMatrixTables.matrixHeight(transformSize);
        int x = coefficientIndex % transformWidth;
        int y = coefficientIndex / transformWidth;
        if (x >= matrixWidth || y >= matrixHeight) {
            return 32;
        }
        return quantizationMatrix[y * matrixWidth + x] & 0xFF;
    }

    /// Multiplies one quantized coefficient by one dequantizer with AV1 coefficient saturation.
    ///
    /// @param coefficient the quantized transform coefficient
    /// @param quantizer the active dequantizer
    /// @param dequantizationShift the transform-size dequantization shift
    /// @param bitDepth the decoded sample bit depth
    /// @param matrixValue the active quantization-matrix scale, or `32` for neutral scaling
    /// @return the scaled transform coefficient
    private static int scaledCoefficient(
            int coefficient,
            int quantizer,
            int dequantizationShift,
            int bitDepth,
            int matrixValue
    ) {
        long magnitude = coefficient < 0 ? -(long) coefficient : coefficient;
        boolean extendedToken = magnitude >= 15;
        if (extendedToken) {
            magnitude &= 0xFFFFF;
        }
        int effectiveQuantizer = (quantizer * matrixValue + 16) >> 5;
        long scaled = effectiveQuantizer * magnitude;
        if (extendedToken) {
            scaled &= 0xFFFFFFL;
        }
        scaled >>= dequantizationShift;
        scaled = Math.min(scaled, coefficientMaximum(bitDepth) + (coefficient < 0 ? 1 : 0));
        if (coefficient < 0) {
            scaled = -scaled;
        }
        return (int) scaled;
    }

    /// Returns the maximum positive transform coefficient for one AV1 bit depth.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @return the maximum positive transform coefficient
    private static int coefficientMaximum(int bitDepth) {
        return (128 << bitDepth) - 1;
    }

    /// Block-local luma dequantization parameters.
    ///
    /// The block-local `qindex` already includes any superblock-level delta-q updates. The luma DC
    /// delta remains frame-level state. Bit depth must be `8`, `10`, or `12`.
    ///
    /// @param qIndex the block-local luma AC quantizer index in `[0, 255]`
    /// @param yDcDelta the frame-level luma DC delta quantizer
    /// @param bitDepth the decoded sample bit depth
    /// @param useQuantizationMatrices whether frame-level quantization matrices are enabled
    /// @param quantizationMatrix the luma quantization matrix index in `[0, 15]`
    
    record Context(
            int qIndex,
            int yDcDelta,
            int bitDepth,
            boolean useQuantizationMatrices,
            int quantizationMatrix
    ) {
        /// Creates one luma dequantization context.
        ///
        /// @param qIndex the block-local luma AC quantizer index in `[0, 255]`
        /// @param yDcDelta the frame-level luma DC delta quantizer
        /// @param bitDepth the decoded sample bit depth
        Context(int qIndex, int yDcDelta, int bitDepth) {
            this(qIndex, yDcDelta, bitDepth, false, 0);
        }

        /// Creates one luma dequantization context.
        ///
        /// @param qIndex the block-local luma AC quantizer index in `[0, 255]`
        /// @param yDcDelta the frame-level luma DC delta quantizer
        /// @param bitDepth the decoded sample bit depth
        /// @param useQuantizationMatrices whether frame-level quantization matrices are enabled
        /// @param quantizationMatrix the luma quantization matrix index in `[0, 15]`
        Context {
            if (qIndex < 0 || qIndex > 255) {
                throw new IllegalArgumentException("qIndex out of range: " + qIndex);
            }
            if (bitDepth <= 0) {
                throw new IllegalArgumentException("bitDepth <= 0: " + bitDepth);
            }
            if (quantizationMatrix < 0 || quantizationMatrix > 15) {
                throw new IllegalArgumentException("quantizationMatrix out of range: " + quantizationMatrix);
            }
        }
    }
}
