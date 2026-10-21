// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.model.TransformKernel;
import pzopt.vendor.avif.internal.av1.model.TransformSize;
import pzopt.vendor.avif.internal.av1.model.TransformType;

import java.util.Arrays;
import java.util.Objects;

/// Inverse-transform helper with instance-owned storage for the residual reconstruction path.
///
/// Staged integer kernels are used for all modeled transform axes. This class exposes one method
/// that reconstructs a residual sample block and one method that adds that block into an already
/// predicted plane.
/// One instance must not execute transform operations concurrently.

final class InverseTransformer {
    /// The AV1 inverse-transform cosine precision.
    private static final int INV_COS_BIT = 12;

    /// The AV1 inverse-transform cosine table at precision `12`.
    private static final int [] COSPI = new int[]{
            4096, 4095, 4091, 4085, 4076, 4065, 4052, 4036,
            4017, 3996, 3973, 3948, 3920, 3889, 3857, 3822,
            3784, 3745, 3703, 3659, 3612, 3564, 3513, 3461,
            3406, 3349, 3290, 3229, 3166, 3102, 3035, 2967,
            2896, 2824, 2751, 2675, 2598, 2520, 2440, 2359,
            2276, 2191, 2106, 2019, 1931, 1842, 1751, 1660,
            1567, 1474, 1380, 1285, 1189, 1092, 995, 897,
            799, 700, 601, 501, 401, 301, 201, 101,
    };

    /// The unrestricted clip range used by helper tests that do not supply bit-depth context.
    private static final ClipRange FULL_INT_CLIP_RANGE = new ClipRange(Integer.MIN_VALUE, Integer.MAX_VALUE, false);

    /// The row-pass clip range for 8-bit samples.
    private static final ClipRange ROW_CLIP_RANGE_8 = new ClipRange(Short.MIN_VALUE, Short.MAX_VALUE, false);

    /// The row-pass clip range for 10-bit samples.
    private static final ClipRange ROW_CLIP_RANGE_10 = new ClipRange(-(1 << 17), (1 << 17) - 1, false);

    /// The row-pass clip range for 12-bit samples.
    private static final ClipRange ROW_CLIP_RANGE_12 = new ClipRange(-(1 << 19), (1 << 19) - 1, false);

    /// The column-pass clip range for 8-bit samples.
    private static final ClipRange COLUMN_CLIP_RANGE_8 = ROW_CLIP_RANGE_8;

    /// The column-pass clip range for 10-bit samples.
    private static final ClipRange COLUMN_CLIP_RANGE_10 = ROW_CLIP_RANGE_8;

    /// The column-pass clip range for 12-bit samples.
    private static final ClipRange COLUMN_CLIP_RANGE_12 = ROW_CLIP_RANGE_10;

    /// Reusable buffers owned by this transformer.
    private final Workspace reconstructionWorkspace = new Workspace();

    /// The stage-local clip range active during the current synchronous transform operation.
    private ClipRange activeClipRange = FULL_INT_CLIP_RANGE;

    /// Creates an inverse transformer with isolated reusable storage and clip state.
    InverseTransformer() {
    }

    /// Returns the reusable coefficient buffer for one transform size.
    ///
    /// The buffer is invalidated by the next residual reconstruction of the same size on this
    /// transformer and must not escape the immediate decode operation.
    ///
    /// @param transformSize the transform size that determines the exact buffer length
    /// @return this transformer's reusable coefficient buffer
    int[] coefficientBuffer(TransformSize transformSize) {
        return reconstructionWorkspace.coefficientBuffer(transformSize);
    }

    /// Reconstructs one residual sample block from dequantized `DCT_DCT` coefficients.
    ///
    /// Input and output both use natural raster order. The returned samples are signed residuals
    /// that are ready to add to a predictor plane of the same dimensions.
    ///
    /// @param dequantizedCoefficients the dequantized `DCT_DCT` coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @return one signed residual sample block in natural raster order
    int[] reconstructResidualBlock(int[] dequantizedCoefficients, TransformSize transformSize) {
        return reconstructResidualBlock(dequantizedCoefficients, transformSize, TransformType.DCT_DCT, 8);
    }

    /// Reconstructs one residual sample block from dequantized transform coefficients.
    ///
    /// Input and output both use natural raster order. The returned samples are signed residuals
    /// that are ready to add to a predictor plane of the same dimensions.
    ///
    /// @param dequantizedCoefficients the dequantized transform coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param transformType the transform type to reconstruct
    /// @return one signed residual sample block in natural raster order
    int[] reconstructResidualBlock(
            int[] dequantizedCoefficients,
            TransformSize transformSize,
            TransformType transformType
    ) {
        return reconstructResidualBlock(dequantizedCoefficients, transformSize, transformType, 8);
    }

    /// Reconstructs one residual sample block from dequantized transform coefficients while using
    /// the active frame bit depth to mirror dav1d's stage-local clip ranges.
    ///
    /// @param dequantizedCoefficients the dequantized transform coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param transformType the transform type to reconstruct
    /// @param bitDepth the decoded sample bit depth
    /// @return one signed residual sample block in natural raster order
    int[] reconstructResidualBlock(
            int[] dequantizedCoefficients,
            TransformSize transformSize,
            TransformType transformType,
            int bitDepth
    ) {
        return reconstructResidualBlock(
                dequantizedCoefficients,
                transformSize,
                transformType,
                bitDepth,
                false,
                null
        );
    }

    /// Reconstructs one residual block while optionally rejecting nonconformant transform ranges.
    ///
    /// @param dequantizedCoefficients the dequantized transform coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param transformType the transform type to reconstruct
    /// @param bitDepth the decoded sample bit depth
    /// @param strictStdCompliance whether transform conformance ranges must be enforced
    /// @return one signed residual sample block in natural raster order
    int[] reconstructResidualBlock(
            int[] dequantizedCoefficients,
            TransformSize transformSize,
            TransformType transformType,
            int bitDepth,
            boolean strictStdCompliance
    ) {
        return reconstructResidualBlock(
                dequantizedCoefficients,
                transformSize,
                transformType,
                bitDepth,
                strictStdCompliance,
                null
        );
    }

    /// Reconstructs one residual block using either isolated result storage or a reusable workspace.
    ///
    /// @param dequantizedCoefficients the dequantized coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param transformType the transform type to reconstruct
    /// @param bitDepth the decoded sample bit depth
    /// @param strictStdCompliance whether transform conformance ranges must be enforced
    /// @param workspace the reusable workspace, or `null` to return independently owned storage
    /// @return one signed residual sample block in natural raster order
    private int[] reconstructResidualBlock(
            int[] dequantizedCoefficients,
            TransformSize transformSize,
            TransformType transformType,
            int bitDepth,
            boolean strictStdCompliance,
            Workspace workspace
    ) {
        int[] nonNullCoefficients = Objects.requireNonNull(dequantizedCoefficients, "dequantizedCoefficients");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        int transformArea = checkedTransformArea(nonNullTransformSize);
        if (nonNullCoefficients.length != transformArea) {
            throw new IllegalArgumentException("dequantizedCoefficients length does not match transform area");
        }

        if (nonNullTransformType == TransformType.WHT_WHT) {
            return reconstructWalshHadamard(
                    nonNullCoefficients,
                    nonNullTransformSize,
                    bitDepth,
                    strictStdCompliance,
                    workspace
            );
        }

        if (nonNullTransformType != TransformType.DCT_DCT) {
            return reconstructGenericTransform(
                    nonNullCoefficients,
                    nonNullTransformSize,
                    nonNullTransformType,
                    bitDepth,
                    strictStdCompliance,
                    workspace
            );
        }

        return reconstructDctDct(
                nonNullCoefficients,
                nonNullTransformSize,
                bitDepth,
                strictStdCompliance,
                workspace
        );
    }

    /// Reconstructs one residual sample block from dequantized `DCT_DCT` coefficients.
    ///
    /// @param coefficients the dequantized `DCT_DCT` coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param bitDepth the decoded sample bit depth
    /// @param strictStdCompliance whether transform conformance ranges must be enforced
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @return one signed residual sample block in natural raster order
    private int[] reconstructDctDct(
            int[] coefficients,
            TransformSize transformSize,
            int bitDepth,
            boolean strictStdCompliance,
            Workspace workspace
    ) {
        ClipRange rowClipRange = conformanceClipRange(rowClipRange(bitDepth), strictStdCompliance);
        ClipRange columnClipRange = conformanceClipRange(columnClipRange(bitDepth), strictStdCompliance);
        return switch (transformSize) {
            case TX_4X4 -> reconstructFourByFour(coefficients, rowClipRange, columnClipRange, workspace);
            case TX_8X8 -> reconstructEightByEight(coefficients, rowClipRange, columnClipRange, workspace);
            case TX_16X16 -> reconstructSixteenBySixteen(coefficients, rowClipRange, columnClipRange, workspace);
            case RTX_4X8 -> reconstructRectangularDctDct(coefficients, transformSize, 0, rowClipRange, columnClipRange, workspace);
            case RTX_8X4 -> reconstructRectangularDctDct(coefficients, transformSize, 0, rowClipRange, columnClipRange, workspace);
            case RTX_4X16 -> reconstructRectangularDctDct(coefficients, transformSize, 1, rowClipRange, columnClipRange, workspace);
            case RTX_16X4 -> reconstructRectangularDctDct(coefficients, transformSize, 1, rowClipRange, columnClipRange, workspace);
            case RTX_8X16 -> reconstructRectangularDctDct(coefficients, transformSize, 1, rowClipRange, columnClipRange, workspace);
            case RTX_16X8 -> reconstructRectangularDctDct(coefficients, transformSize, 1, rowClipRange, columnClipRange, workspace);
            case TX_32X32 -> reconstructRectangularDctDct(coefficients, transformSize, 2, rowClipRange, columnClipRange, workspace);
            case TX_64X64 -> reconstructRectangularDctDct(coefficients, transformSize, 2, rowClipRange, columnClipRange, workspace);
            case RTX_16X32 -> reconstructRectangularDctDct(coefficients, transformSize, 1, rowClipRange, columnClipRange, workspace);
            case RTX_32X16 -> reconstructRectangularDctDct(coefficients, transformSize, 1, rowClipRange, columnClipRange, workspace);
            case RTX_32X64 -> reconstructRectangularDctDct(coefficients, transformSize, 1, rowClipRange, columnClipRange, workspace);
            case RTX_64X32 -> reconstructRectangularDctDct(coefficients, transformSize, 1, rowClipRange, columnClipRange, workspace);
            case RTX_8X32 -> reconstructRectangularDctDct(coefficients, transformSize, 2, rowClipRange, columnClipRange, workspace);
            case RTX_32X8 -> reconstructRectangularDctDct(coefficients, transformSize, 2, rowClipRange, columnClipRange, workspace);
            case RTX_16X64 -> reconstructRectangularDctDct(coefficients, transformSize, 2, rowClipRange, columnClipRange, workspace);
            case RTX_64X16 -> reconstructRectangularDctDct(coefficients, transformSize, 2, rowClipRange, columnClipRange, workspace);
        };
    }

    /// Adds one already reconstructed residual block into the supplied predictor plane.
    ///
    /// Sample addition delegates clipping to `MutablePlaneBuffer`, so callers may pass signed
    /// residual values directly.
    ///
    /// @param plane the destination predictor plane
    /// @param x the zero-based destination x coordinate
    /// @param y the zero-based destination y coordinate
    /// @param transformSize the residual block size
    /// @param residualSamples the signed residual sample block in natural raster order
    void addResidualBlock(
            MutablePlaneBuffer plane,
            int x,
            int y,
            TransformSize transformSize,
            int[] residualSamples
    ) {
        addResidualBlock(
                plane,
                x,
                y,
                transformSize,
                transformSize.widthPixels(),
                transformSize.heightPixels(),
                residualSamples
        );
    }

    /// Adds a caller-selected rectangular portion of one reconstructed residual block into the
    /// supplied predictor plane.
    ///
    /// Sample addition delegates clipping to `MutablePlaneBuffer`, so callers may pass signed
    /// residual values directly.
    ///
    /// @param plane the destination predictor plane
    /// @param x the zero-based destination x coordinate
    /// @param y the zero-based destination y coordinate
    /// @param transformSize the coded residual block size
    /// @param writtenWidthPixels the residual width to write in pixels
    /// @param writtenHeightPixels the residual height to write in pixels
    /// @param residualSamples the signed residual sample block in natural raster order
    void addResidualBlock(
            MutablePlaneBuffer plane,
            int x,
            int y,
            TransformSize transformSize,
            int writtenWidthPixels,
            int writtenHeightPixels,
            int[] residualSamples
    ) {
        MutablePlaneBuffer nonNullPlane = Objects.requireNonNull(plane, "plane");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int[] nonNullResidualSamples = Objects.requireNonNull(residualSamples, "residualSamples");
        int transformArea = checkedTransformArea(nonNullTransformSize);
        if (nonNullResidualSamples.length != transformArea) {
            throw new IllegalArgumentException("residualSamples length does not match transform area");
        }
        if (writtenWidthPixels <= 0 || writtenWidthPixels > nonNullTransformSize.widthPixels()) {
            throw new IllegalArgumentException("writtenWidthPixels out of range: " + writtenWidthPixels);
        }
        if (writtenHeightPixels <= 0 || writtenHeightPixels > nonNullTransformSize.heightPixels()) {
            throw new IllegalArgumentException("writtenHeightPixels out of range: " + writtenHeightPixels);
        }

        int transformWidth = nonNullTransformSize.widthPixels();
        for (int row = 0; row < writtenHeightPixels; row++) {
            for (int column = 0; column < writtenWidthPixels; column++) {
                int sampleIndex = row * transformWidth + column;
                int predicted = nonNullPlane.sample(x + column, y + row);
                nonNullPlane.setSample(x + column, y + row, predicted + nonNullResidualSamples[sampleIndex]);
            }
        }
    }

    /// Reconstructs and immediately applies one residual block using reusable instance storage.
    ///
    /// The coefficient array must be the buffer returned by [Workspace#coefficientBuffer(TransformSize)]
    /// or another exact-length array that is not modified until this method returns.
    ///
    /// @param plane the destination predictor plane
    /// @param x the zero-based destination x coordinate
    /// @param y the zero-based destination y coordinate
    /// @param transformSize the coded residual block size
    /// @param transformType the transform type to reconstruct
    /// @param bitDepth the decoded sample bit depth
    /// @param writtenWidthPixels the residual width to write in pixels
    /// @param writtenHeightPixels the residual height to write in pixels
    /// @param strictStdCompliance whether transform conformance ranges must be enforced
    /// @param dequantizedCoefficients the dequantized transform coefficients
    void reconstructAndAddResidualBlock(
            MutablePlaneBuffer plane,
            int x,
            int y,
            TransformSize transformSize,
            TransformType transformType,
            int bitDepth,
            int writtenWidthPixels,
            int writtenHeightPixels,
            boolean strictStdCompliance,
            int[] dequantizedCoefficients
    ) {
        int[] residualSamples = reconstructResidualBlock(
                dequantizedCoefficients,
                transformSize,
                transformType,
                bitDepth,
                strictStdCompliance,
                reconstructionWorkspace
        );
        addResidualBlock(
                plane,
                x,
                y,
                transformSize,
                writtenWidthPixels,
                writtenHeightPixels,
                residualSamples
        );
    }

    /// Reconstructs and immediately applies one DC-only `DCT_DCT` residual block.
    ///
    /// @param plane the destination predictor plane
    /// @param x the zero-based destination x coordinate
    /// @param y the zero-based destination y coordinate
    /// @param transformSize the coded residual block size
    /// @param bitDepth the decoded sample bit depth
    /// @param writtenWidthPixels the residual width to write in pixels
    /// @param writtenHeightPixels the residual height to write in pixels
    /// @param strictStdCompliance whether transform conformance ranges must be enforced
    /// @param dequantizedDcCoefficient the dequantized DC coefficient
    void reconstructAndAddDcOnlyDctBlock(
            MutablePlaneBuffer plane,
            int x,
            int y,
            TransformSize transformSize,
            int bitDepth,
            int writtenWidthPixels,
            int writtenHeightPixels,
            boolean strictStdCompliance,
            int dequantizedDcCoefficient
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (writtenWidthPixels <= 0 || writtenWidthPixels > nonNullTransformSize.widthPixels()) {
            throw new IllegalArgumentException("writtenWidthPixels out of range: " + writtenWidthPixels);
        }
        if (writtenHeightPixels <= 0 || writtenHeightPixels > nonNullTransformSize.heightPixels()) {
            throw new IllegalArgumentException("writtenHeightPixels out of range: " + writtenHeightPixels);
        }
        int residualSample = reconstructDcOnlyDctDct(
                dequantizedDcCoefficient,
                nonNullTransformSize,
                bitDepth,
                strictStdCompliance
        );
        Objects.requireNonNull(plane, "plane").addConstantBlock(
                x, y, writtenWidthPixels, writtenHeightPixels, residualSample
        );
    }

    /// Reconstructs the constant residual sample produced by one DC-only `DCT_DCT` block.
    ///
    /// Only one row and one column transform are required because a DCT DC basis has the same
    /// value at every output position.
    ///
    /// @param dcCoefficient the dequantized DC coefficient
    /// @param transformSize the transform size to reconstruct
    /// @param bitDepth the decoded sample bit depth
    /// @param strictStdCompliance whether transform conformance ranges must be enforced
    /// @return the constant signed residual sample
    private int reconstructDcOnlyDctDct(
            int dcCoefficient,
            TransformSize transformSize,
            int bitDepth,
            boolean strictStdCompliance
    ) {
        int width = transformSize.widthPixels();
        int height = transformSize.heightPixels();
        int scratchLength = Math.max(width, height);
        int[] input = reconstructionWorkspace.scratchInput(scratchLength);
        int[] output = reconstructionWorkspace.scratchOutput(scratchLength);
        Arrays.fill(input, 0, width, 0);
        input[0] = width * 2 == height || height * 2 == width
                ? positiveRoundShift((long) dcCoefficient * 181, 8)
                : dcCoefficient;

        ClipRange rowClipRange = conformanceClipRange(rowClipRange(bitDepth), strictStdCompliance);
        ClipRange columnClipRange = conformanceClipRange(columnClipRange(bitDepth), strictStdCompliance);
        withActiveClipRange(rowClipRange, () -> inverseDctDctAxis(transformSize, input, output, width));
        int columnInput = clipToRange(
                positiveRoundShift(output[0], intermediateTransformShift(transformSize)),
                columnClipRange
        );

        Arrays.fill(input, 0, height, 0);
        input[0] = columnInput;
        withActiveClipRange(columnClipRange, () -> inverseDctDctAxis(transformSize, input, output, height));
        return positiveRoundShift(output[0], 4);
    }

    /// Applies the DCT kernel used by one axis of the supplied two-dimensional transform size.
    ///
    /// @param transformSize the two-dimensional transform size
    /// @param input the transform input vector
    /// @param output the transform output vector
    /// @param length the active vector length
    private void inverseDctDctAxis(TransformSize transformSize, int[] input, int[] output, int length) {
        switch (transformSize) {
            case TX_4X4 -> inverseDct4(input, output);
            case TX_8X8 -> inverseDct8(input, output);
            case TX_16X16 -> inverseDct16(input, output);
            default -> inverseDct(input, output, length);
        }
    }

    /// Reconstructs one `TX_4X4` `DCT_DCT` residual block.
    ///
    /// @param coefficients the dequantized `TX_4X4` coefficients in natural raster order
    /// @param rowClipRange the row-pass clip range
    /// @param columnClipRange the column-pass clip range
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @return one signed `TX_4X4` residual sample block
    private int[] reconstructFourByFour(
            int[] coefficients,
            ClipRange rowClipRange,
            ClipRange columnClipRange,
            Workspace workspace
    ) {
        int[] buffer = intermediateBuffer(workspace, TransformSize.TX_4X4);
        int[] output = outputBuffer(workspace, TransformSize.TX_4X4);
        int[] scratchIn = scratchInput(workspace, 4);
        int[] scratchOut = scratchOutput(workspace, 4);
        for (int row = 0; row < 4; row++) {
            int rowOffset = row << 2;
            scratchIn[0] = coefficients[rowOffset];
            scratchIn[1] = coefficients[rowOffset + 1];
            scratchIn[2] = coefficients[rowOffset + 2];
            scratchIn[3] = coefficients[rowOffset + 3];
            if ((scratchIn[0] | scratchIn[1] | scratchIn[2] | scratchIn[3]) == 0) {
                Arrays.fill(buffer, rowOffset, rowOffset + 4, 0);
                continue;
            }
            withActiveClipRange(rowClipRange, () -> inverseDct4(scratchIn, scratchOut));
            buffer[rowOffset] = clipToRange(scratchOut[0], columnClipRange);
            buffer[rowOffset + 1] = clipToRange(scratchOut[1], columnClipRange);
            buffer[rowOffset + 2] = clipToRange(scratchOut[2], columnClipRange);
            buffer[rowOffset + 3] = clipToRange(scratchOut[3], columnClipRange);
        }

        for (int column = 0; column < 4; column++) {
            scratchIn[0] = buffer[column];
            scratchIn[1] = buffer[4 + column];
            scratchIn[2] = buffer[8 + column];
            scratchIn[3] = buffer[12 + column];
            withActiveClipRange(columnClipRange, () -> inverseDct4(scratchIn, scratchOut));
            output[column] = positiveRoundShift(scratchOut[0], 4);
            output[4 + column] = positiveRoundShift(scratchOut[1], 4);
            output[8 + column] = positiveRoundShift(scratchOut[2], 4);
            output[12 + column] = positiveRoundShift(scratchOut[3], 4);
        }
        return output;
    }

    /// Reconstructs one `TX_8X8` `DCT_DCT` residual block.
    ///
    /// @param coefficients the dequantized `TX_8X8` coefficients in natural raster order
    /// @param rowClipRange the row-pass clip range
    /// @param columnClipRange the column-pass clip range
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @return one signed `TX_8X8` residual sample block
    private int[] reconstructEightByEight(
            int[] coefficients,
            ClipRange rowClipRange,
            ClipRange columnClipRange,
            Workspace workspace
    ) {
        int[] buffer = intermediateBuffer(workspace, TransformSize.TX_8X8);
        int[] output = outputBuffer(workspace, TransformSize.TX_8X8);
        int[] scratchIn = scratchInput(workspace, 8);
        int[] scratchOut = scratchOutput(workspace, 8);
        for (int row = 0; row < 8; row++) {
            int rowOffset = row << 3;
            int rowNonZero = 0;
            for (int column = 0; column < 8; column++) {
                int coefficient = coefficients[rowOffset + column];
                scratchIn[column] = coefficient;
                rowNonZero |= coefficient;
            }
            if (rowNonZero == 0) {
                Arrays.fill(buffer, rowOffset, rowOffset + 8, 0);
                continue;
            }
            withActiveClipRange(rowClipRange, () -> inverseDct8(scratchIn, scratchOut));
            for (int column = 0; column < 8; column++) {
                buffer[rowOffset + column] = clipToRange(positiveRoundShift(scratchOut[column], 1), columnClipRange);
            }
        }

        for (int column = 0; column < 8; column++) {
            for (int row = 0; row < 8; row++) {
                scratchIn[row] = buffer[(row << 3) + column];
            }
            withActiveClipRange(columnClipRange, () -> inverseDct8(scratchIn, scratchOut));
            for (int row = 0; row < 8; row++) {
                output[(row << 3) + column] = positiveRoundShift(scratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one `TX_16X16` `DCT_DCT` residual block.
    ///
    /// This follows the same staged integer transform and scaling schedule used by `dav1d` for the
    /// non-rectangular `16x16` inverse transform: one row pass, one intermediate shift by `2`,
    /// one column pass, and one final shift by `4`.
    ///
    /// @param coefficients the dequantized `TX_16X16` coefficients in natural raster order
    /// @param rowClipRange the row-pass clip range
    /// @param columnClipRange the column-pass clip range
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @return one signed `TX_16X16` residual sample block
    private int[] reconstructSixteenBySixteen(
            int[] coefficients,
            ClipRange rowClipRange,
            ClipRange columnClipRange,
            Workspace workspace
    ) {
        int[] buffer = intermediateBuffer(workspace, TransformSize.TX_16X16);
        int[] output = outputBuffer(workspace, TransformSize.TX_16X16);
        int[] scratchIn = scratchInput(workspace, 16);
        int[] scratchOut = scratchOutput(workspace, 16);
        for (int row = 0; row < 16; row++) {
            int rowOffset = row << 4;
            int rowNonZero = 0;
            for (int column = 0; column < 16; column++) {
                int coefficient = coefficients[rowOffset + column];
                scratchIn[column] = coefficient;
                rowNonZero |= coefficient;
            }
            if (rowNonZero == 0) {
                Arrays.fill(buffer, rowOffset, rowOffset + 16, 0);
                continue;
            }
            withActiveClipRange(rowClipRange, () -> inverseDct16(scratchIn, scratchOut));
            for (int column = 0; column < 16; column++) {
                buffer[rowOffset + column] = clipToRange(positiveRoundShift(scratchOut[column], 2), columnClipRange);
            }
        }

        for (int column = 0; column < 16; column++) {
            for (int row = 0; row < 16; row++) {
                scratchIn[row] = buffer[(row << 4) + column];
            }
            withActiveClipRange(columnClipRange, () -> inverseDct16(scratchIn, scratchOut));
            for (int row = 0; row < 16; row++) {
                output[(row << 4) + column] = positiveRoundShift(scratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one supported rectangular `DCT_DCT` residual block.
    ///
    /// This follows the same high-level schedule used by `dav1d` for rectangular transforms:
    /// apply one pre-scale for `2:1` shapes, run one row transform, apply the size-specific
    /// intermediate shift, run one column transform, then finish with the shared `>> 4`
    /// post-transform scaling.
    ///
    /// @param coefficients the dequantized coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param intermediateShift the AV1 size-specific intermediate shift applied after the first pass
    /// @param rowClipRange the row-pass clip range
    /// @param columnClipRange the column-pass clip range
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @return one signed rectangular residual sample block
    private int[] reconstructRectangularDctDct(
            int[] coefficients,
            TransformSize transformSize,
            int intermediateShift,
            ClipRange rowClipRange,
            ClipRange columnClipRange,
            Workspace workspace
    ) {
        int width = transformSize.widthPixels();
        int height = transformSize.heightPixels();
        int[] buffer = intermediateBuffer(workspace, transformSize);
        int[] output = outputBuffer(workspace, transformSize);
        int scratchLength = Math.max(width, height);
        int[] rowScratchIn = scratchInput(workspace, scratchLength);
        int[] rowScratchOut = scratchOutput(workspace, scratchLength);
        int[] columnScratchIn = rowScratchIn;
        int[] columnScratchOut = rowScratchOut;
        boolean requiresRectangularPrescale = width * 2 == height || height * 2 == width;

        for (int row = 0; row < height; row++) {
            int rowOffset = row * width;
            int rowNonZero = 0;
            for (int column = 0; column < width; column++) {
                int coefficient = coefficients[rowOffset + column];
                int transformInput = requiresRectangularPrescale
                        ? positiveRoundShift((long) coefficient * 181, 8)
                        : coefficient;
                rowScratchIn[column] = transformInput;
                rowNonZero |= transformInput;
            }
            if (rowNonZero == 0) {
                Arrays.fill(buffer, rowOffset, rowOffset + width, 0);
                continue;
            }
            withActiveClipRange(rowClipRange, () -> inverseDct(rowScratchIn, rowScratchOut, width));
            for (int column = 0; column < width; column++) {
                buffer[rowOffset + column] = clipToRange(
                        positiveRoundShift(rowScratchOut[column], intermediateShift),
                        columnClipRange
                );
            }
        }

        for (int column = 0; column < width; column++) {
            for (int row = 0; row < height; row++) {
                columnScratchIn[row] = buffer[row * width + column];
            }
            withActiveClipRange(columnClipRange, () -> inverseDct(columnScratchIn, columnScratchOut, height));
            for (int row = 0; row < height; row++) {
                output[row * width + column] = positiveRoundShift(columnScratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one non-`DCT_DCT` residual block through staged one-dimensional kernels.
    ///
    /// @param coefficients the dequantized coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param transformType the explicit transform type to reconstruct
    /// @param bitDepth the decoded sample bit depth
    /// @param strictStdCompliance whether transform conformance ranges must be enforced
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @return one signed residual sample block in natural raster order
    private int[] reconstructGenericTransform(
            int[] coefficients,
            TransformSize transformSize,
            TransformType transformType,
            int bitDepth,
            boolean strictStdCompliance,
            Workspace workspace
    ) {
        int width = transformSize.widthPixels();
        int height = transformSize.heightPixels();
        int intermediateShift = intermediateTransformShift(transformSize);
        boolean requiresRectangularPrescale = width * 2 == height || height * 2 == width;
        ClipRange rowClipRange = conformanceClipRange(rowClipRange(bitDepth), strictStdCompliance);
        ClipRange columnClipRange = conformanceClipRange(columnClipRange(bitDepth), strictStdCompliance);
        int[] buffer = intermediateBuffer(workspace, transformSize);
        int[] output = outputBuffer(workspace, transformSize);
        int scratchLength = Math.max(width, height);
        int[] rowScratchIn = scratchInput(workspace, scratchLength);
        int[] rowScratchOut = scratchOutput(workspace, scratchLength);
        int[] columnScratchIn = rowScratchIn;
        int[] columnScratchOut = rowScratchOut;

        for (int row = 0; row < height; row++) {
            int rowOffset = row * width;
            int rowNonZero = 0;
            for (int column = 0; column < width; column++) {
                int coefficient = coefficients[rowOffset + column];
                int transformInput = requiresRectangularPrescale
                        ? positiveRoundShift((long) coefficient * 181, 8)
                        : coefficient;
                rowScratchIn[column] = transformInput;
                rowNonZero |= transformInput;
            }
            if (rowNonZero == 0) {
                Arrays.fill(buffer, rowOffset, rowOffset + width, 0);
                continue;
            }
            withActiveClipRange(
                    rowClipRange,
                    () -> inverseKernel(transformType.horizontalKernel(), rowScratchIn, rowScratchOut, width)
            );
            for (int column = 0; column < width; column++) {
                buffer[rowOffset + column] = clipToRange(
                        positiveRoundShift(rowScratchOut[column], intermediateShift),
                        columnClipRange
                );
            }
        }

        for (int column = 0; column < width; column++) {
            for (int row = 0; row < height; row++) {
                columnScratchIn[row] = buffer[row * width + column];
            }
            withActiveClipRange(
                    columnClipRange,
                    () -> inverseKernel(transformType.verticalKernel(), columnScratchIn, columnScratchOut, height)
            );
            for (int row = 0; row < height; row++) {
                output[row * width + column] = positiveRoundShift(columnScratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one AV1 lossless `WHT_WHT` residual block.
    ///
    /// AV1 lossless blocks always use a coded `TX_4X4` transform. The initial `>> 2` matches the
    /// scalar `dav1d` path; the input is already converted into this decoder's natural row-major
    /// coefficient order before reaching the inverse transform.
    ///
    /// @param coefficients the dequantized lossless coefficients in natural raster order
    /// @param transformSize the active transform size
    /// @param bitDepth the decoded sample bit depth
    /// @param strictStdCompliance whether lossless residual ranges must be enforced
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @return one signed `TX_4X4` residual sample block
    private int[] reconstructWalshHadamard(
            int[] coefficients,
            TransformSize transformSize,
            int bitDepth,
            boolean strictStdCompliance,
            Workspace workspace
    ) {
        if (transformSize != TransformSize.TX_4X4) {
            throw new IllegalStateException("WHT_WHT is only valid for TX_4X4 lossless blocks");
        }

        int[] tmp = intermediateBuffer(workspace, TransformSize.TX_4X4);
        int[] output = outputBuffer(workspace, TransformSize.TX_4X4);
        int[] scratch = scratchInput(workspace, 4);
        for (int y = 0; y < 4; y++) {
            int rowOffset = y << 2;
            for (int x = 0; x < 4; x++) {
                scratch[x] = coefficients[rowOffset + x] >> 2;
            }
            inverseWalshHadamard4(scratch);
            for (int x = 0; x < 4; x++) {
                tmp[(y << 2) + x] = scratch[x];
            }
        }

        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                scratch[y] = tmp[(y << 2) + x];
            }
            inverseWalshHadamard4(scratch);
            for (int y = 0; y < 4; y++) {
                output[(y << 2) + x] = scratch[y];
            }
        }
        if (strictStdCompliance) {
            validateLosslessResidualRange(output, bitDepth);
        }
        return output;
    }

    /// Validates that lossless residual samples fit a signed `1 + BitDepth`-bit value.
    ///
    /// @param residualSamples the reconstructed lossless residual samples
    /// @param bitDepth the decoded sample bit depth
    private void validateLosslessResidualRange(int[] residualSamples, int bitDepth) {
        int minimum = -(1 << bitDepth);
        int maximum = (1 << bitDepth) - 1;
        for (int residualSample : residualSamples) {
            if (residualSample < minimum || residualSample > maximum) {
                throw new InvalidFrameReconstructionException(
                        "Lossless residual does not fit a signed " + (bitDepth + 1) + "-bit value"
                );
            }
        }
    }

    /// Returns the intermediate transform buffer for one isolated or reusable reconstruction.
    ///
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @param transformSize the transform size that determines the buffer length
    /// @return the intermediate transform buffer
    private int[] intermediateBuffer(Workspace workspace, TransformSize transformSize) {
        return workspace == null
                ? new int[checkedTransformArea(transformSize)]
                : workspace.intermediateBuffer(transformSize);
    }

    /// Returns the output buffer for one isolated or reusable reconstruction.
    ///
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @param transformSize the transform size that determines the buffer length
    /// @return the residual output buffer
    private int[] outputBuffer(Workspace workspace, TransformSize transformSize) {
        return workspace == null
                ? new int[checkedTransformArea(transformSize)]
                : workspace.outputBuffer(transformSize);
    }

    /// Returns one input scratch vector with at least the requested length.
    ///
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @param length the required vector length
    /// @return the input scratch vector
    private int[] scratchInput(Workspace workspace, int length) {
        return workspace == null ? new int[length] : workspace.scratchInput(length);
    }

    /// Returns one output scratch vector with at least the requested length.
    ///
    /// @param workspace the reusable workspace, or `null` for isolated storage
    /// @param length the required vector length
    /// @return the output scratch vector
    private int[] scratchOutput(Workspace workspace, int length) {
        return workspace == null ? new int[length] : workspace.scratchOutput(length);
    }

    /// Applies one in-place AV1 inverse Walsh-Hadamard transform to four samples.
    ///
    /// @param values the four-sample vector to transform in place
    private void inverseWalshHadamard4(int[] values) {
        int in0 = values[0];
        int in1 = values[1];
        int in2 = values[2];
        int in3 = values[3];

        int t0 = in0 + in1;
        int t2 = in2 - in3;
        int t4 = (t0 - t2) >> 1;
        int t3 = t4 - in3;
        int t1 = t4 - in1;

        values[0] = t0 - t3;
        values[1] = t3;
        values[2] = t1;
        values[3] = t2 + t1;
    }

    /// Reconstructs one supported one-dimensional inverse DCT vector.
    ///
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private void inverseDct(int[] input, int[] output, int length) {
        switch (length) {
            case 4 -> inverseDct4Dav1d(input, output);
            case 8 -> inverseDct8Dav1d(input, output);
            case 16 -> inverseDct16Dav1d(input, output);
            case 32 -> inverseDct32Dav1d(input, output);
            case 64 -> inverseDct64Aom(input, output);
            default -> throw new IllegalStateException("Unsupported inverse DCT length: " + length);
        }
    }

    /// Reconstructs one supported one-dimensional inverse transform vector.
    ///
    /// @param kernel the one-dimensional kernel to apply
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private void inverseKernel(TransformKernel kernel, int[] input, int[] output, int length) {
        switch (kernel) {
            case DCT -> inverseDct(input, output, length);
            case ADST -> inverseAdst(input, output, length);
            case FLIPADST -> inverseFlipAdst(input, output, length);
            case IDENTITY -> inverseIdentity(input, output, length);
            case WHT -> throw new IllegalStateException("WHT uses the dedicated lossless transform path");
        }
    }

    /// Returns the size-specific intermediate inverse-transform shift used between the two 1-D
    /// transform passes.
    ///
    /// @param transformSize the transform size
    /// @return the intermediate shift in bits
    private int intermediateTransformShift(TransformSize transformSize) {
        return switch (transformSize) {
            case TX_4X4, RTX_4X8, RTX_8X4 -> 0;
            case RTX_4X16, TX_8X8, RTX_8X16, RTX_16X4, RTX_16X8, RTX_16X32, RTX_32X16,
                 RTX_32X64, RTX_64X32 -> 1;
            case RTX_8X32, TX_16X16, TX_32X32, TX_64X64, RTX_32X8, RTX_16X64, RTX_64X16 -> 2;
        };
    }

    /// Reconstructs one one-dimensional `DCT_4` vector.
    ///
    /// This follows the libaom staged integer `av1_idct4` schedule and preserves the same
    /// clamp locations between half-butterfly stages.
    ///
    /// @param input the dequantized `DCT_4` input vector
    /// @param output the reconstructed output vector
    private void inverseDct4(int[] input, int[] output) {
        int input0 = input[0];
        int input1 = input[2];
        int input2 = input[1];
        int input3 = input[3];
        int step0 = halfBtf(COSPI[32], input0, COSPI[32], input1);
        int step1 = halfBtf(COSPI[32], input0, -COSPI[32], input1);
        int step2 = halfBtf(COSPI[48], input2, -COSPI[16], input3);
        int step3 = halfBtf(COSPI[16], input2, COSPI[48], input3);

        output[0] = clip((long) step0 + step3);
        output[1] = clip((long) step1 + step2);
        output[2] = clip((long) step1 - step2);
        output[3] = clip((long) step0 - step3);
    }

    /// Reconstructs one one-dimensional `DCT_8` vector.
    ///
    /// This follows the libaom staged integer `av1_idct8` schedule and preserves the same
    /// clamp locations between half-butterfly stages.
    ///
    /// @param input the dequantized `DCT_8` input vector
    /// @param output the reconstructed output vector
    private void inverseDct8(int[] input, int[] output) {
        int[] step = reconstructionWorkspace.kernelStep();

        output[0] = input[0];
        output[1] = input[4];
        output[2] = input[2];
        output[3] = input[6];
        output[4] = input[1];
        output[5] = input[5];
        output[6] = input[3];
        output[7] = input[7];

        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = halfBtf(COSPI[56], output[4], -COSPI[8], output[7]);
        step[5] = halfBtf(COSPI[24], output[5], -COSPI[40], output[6]);
        step[6] = halfBtf(COSPI[40], output[5], COSPI[24], output[6]);
        step[7] = halfBtf(COSPI[8], output[4], COSPI[56], output[7]);

        output[0] = halfBtf(COSPI[32], step[0], COSPI[32], step[1]);
        output[1] = halfBtf(COSPI[32], step[0], -COSPI[32], step[1]);
        output[2] = halfBtf(COSPI[48], step[2], -COSPI[16], step[3]);
        output[3] = halfBtf(COSPI[16], step[2], COSPI[48], step[3]);
        output[4] = clip((long) step[4] + step[5]);
        output[5] = clip((long) step[4] - step[5]);
        output[6] = clip(-(long) step[6] + step[7]);
        output[7] = clip((long) step[6] + step[7]);

        step[0] = clip((long) output[0] + output[3]);
        step[1] = clip((long) output[1] + output[2]);
        step[2] = clip((long) output[1] - output[2]);
        step[3] = clip((long) output[0] - output[3]);
        step[4] = output[4];
        step[5] = halfBtf(-COSPI[32], output[5], COSPI[32], output[6]);
        step[6] = halfBtf(COSPI[32], output[5], COSPI[32], output[6]);
        step[7] = output[7];

        output[0] = clip((long) step[0] + step[7]);
        output[1] = clip((long) step[1] + step[6]);
        output[2] = clip((long) step[2] + step[5]);
        output[3] = clip((long) step[3] + step[4]);
        output[4] = clip((long) step[3] - step[4]);
        output[5] = clip((long) step[2] - step[5]);
        output[6] = clip((long) step[1] - step[6]);
        output[7] = clip((long) step[0] - step[7]);
    }

    /// Reconstructs one one-dimensional `DCT_16` vector.
    ///
    /// This follows the libaom staged integer `av1_idct16` schedule and preserves the same
    /// clamp locations between half-butterfly stages.
    ///
    /// @param input the dequantized `DCT_16` input vector
    /// @param output the reconstructed output vector
    private void inverseDct16(int[] input, int[] output) {
        int[] step = reconstructionWorkspace.kernelStep();

        output[0] = input[0];
        output[1] = input[8];
        output[2] = input[4];
        output[3] = input[12];
        output[4] = input[2];
        output[5] = input[10];
        output[6] = input[6];
        output[7] = input[14];
        output[8] = input[1];
        output[9] = input[9];
        output[10] = input[5];
        output[11] = input[13];
        output[12] = input[3];
        output[13] = input[11];
        output[14] = input[7];
        output[15] = input[15];

        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = output[4];
        step[5] = output[5];
        step[6] = output[6];
        step[7] = output[7];
        step[8] = halfBtf(COSPI[60], output[8], -COSPI[4], output[15]);
        step[9] = halfBtf(COSPI[28], output[9], -COSPI[36], output[14]);
        step[10] = halfBtf(COSPI[44], output[10], -COSPI[20], output[13]);
        step[11] = halfBtf(COSPI[12], output[11], -COSPI[52], output[12]);
        step[12] = halfBtf(COSPI[52], output[11], COSPI[12], output[12]);
        step[13] = halfBtf(COSPI[20], output[10], COSPI[44], output[13]);
        step[14] = halfBtf(COSPI[36], output[9], COSPI[28], output[14]);
        step[15] = halfBtf(COSPI[4], output[8], COSPI[60], output[15]);

        output[0] = step[0];
        output[1] = step[1];
        output[2] = step[2];
        output[3] = step[3];
        output[4] = halfBtf(COSPI[56], step[4], -COSPI[8], step[7]);
        output[5] = halfBtf(COSPI[24], step[5], -COSPI[40], step[6]);
        output[6] = halfBtf(COSPI[40], step[5], COSPI[24], step[6]);
        output[7] = halfBtf(COSPI[8], step[4], COSPI[56], step[7]);
        output[8] = clip((long) step[8] + step[9]);
        output[9] = clip((long) step[8] - step[9]);
        output[10] = clip(-(long) step[10] + step[11]);
        output[11] = clip((long) step[10] + step[11]);
        output[12] = clip((long) step[12] + step[13]);
        output[13] = clip((long) step[12] - step[13]);
        output[14] = clip(-(long) step[14] + step[15]);
        output[15] = clip((long) step[14] + step[15]);

        step[0] = halfBtf(COSPI[32], output[0], COSPI[32], output[1]);
        step[1] = halfBtf(COSPI[32], output[0], -COSPI[32], output[1]);
        step[2] = halfBtf(COSPI[48], output[2], -COSPI[16], output[3]);
        step[3] = halfBtf(COSPI[16], output[2], COSPI[48], output[3]);
        step[4] = clip((long) output[4] + output[5]);
        step[5] = clip((long) output[4] - output[5]);
        step[6] = clip(-(long) output[6] + output[7]);
        step[7] = clip((long) output[6] + output[7]);
        step[8] = output[8];
        step[9] = halfBtf(-COSPI[16], output[9], COSPI[48], output[14]);
        step[10] = halfBtf(-COSPI[48], output[10], -COSPI[16], output[13]);
        step[11] = output[11];
        step[12] = output[12];
        step[13] = halfBtf(-COSPI[16], output[10], COSPI[48], output[13]);
        step[14] = halfBtf(COSPI[48], output[9], COSPI[16], output[14]);
        step[15] = output[15];

        output[0] = clip((long) step[0] + step[3]);
        output[1] = clip((long) step[1] + step[2]);
        output[2] = clip((long) step[1] - step[2]);
        output[3] = clip((long) step[0] - step[3]);
        output[4] = step[4];
        output[5] = halfBtf(-COSPI[32], step[5], COSPI[32], step[6]);
        output[6] = halfBtf(COSPI[32], step[5], COSPI[32], step[6]);
        output[7] = step[7];
        output[8] = clip((long) step[8] + step[11]);
        output[9] = clip((long) step[9] + step[10]);
        output[10] = clip((long) step[9] - step[10]);
        output[11] = clip((long) step[8] - step[11]);
        output[12] = clip(-(long) step[12] + step[15]);
        output[13] = clip(-(long) step[13] + step[14]);
        output[14] = clip((long) step[13] + step[14]);
        output[15] = clip((long) step[12] + step[15]);

        step[0] = clip((long) output[0] + output[7]);
        step[1] = clip((long) output[1] + output[6]);
        step[2] = clip((long) output[2] + output[5]);
        step[3] = clip((long) output[3] + output[4]);
        step[4] = clip((long) output[3] - output[4]);
        step[5] = clip((long) output[2] - output[5]);
        step[6] = clip((long) output[1] - output[6]);
        step[7] = clip((long) output[0] - output[7]);
        step[8] = output[8];
        step[9] = output[9];
        step[10] = halfBtf(-COSPI[32], output[10], COSPI[32], output[13]);
        step[11] = halfBtf(-COSPI[32], output[11], COSPI[32], output[12]);
        step[12] = halfBtf(COSPI[32], output[11], COSPI[32], output[12]);
        step[13] = halfBtf(COSPI[32], output[10], COSPI[32], output[13]);
        step[14] = output[14];
        step[15] = output[15];

        output[0] = clip((long) step[0] + step[15]);
        output[1] = clip((long) step[1] + step[14]);
        output[2] = clip((long) step[2] + step[13]);
        output[3] = clip((long) step[3] + step[12]);
        output[4] = clip((long) step[4] + step[11]);
        output[5] = clip((long) step[5] + step[10]);
        output[6] = clip((long) step[6] + step[9]);
        output[7] = clip((long) step[7] + step[8]);
        output[8] = clip((long) step[7] - step[8]);
        output[9] = clip((long) step[6] - step[9]);
        output[10] = clip((long) step[5] - step[10]);
        output[11] = clip((long) step[4] - step[11]);
        output[12] = clip((long) step[3] - step[12]);
        output[13] = clip((long) step[2] - step[13]);
        output[14] = clip((long) step[1] - step[14]);
        output[15] = clip((long) step[0] - step[15]);
    }

    /// Reconstructs one one-dimensional `DCT_32` vector using the scalar `dav1d` schedule.
    ///
    /// This keeps the same even/odd split and rounding points as `inv_dct32_1d_internal_c` for the
    /// non-`tx64` case. Matching that path avoids coefficient-dependent drift in large `DCT_DCT`
    /// residuals.
    ///
    /// @param input the dequantized `DCT_32` input vector
    /// @param output the reconstructed output vector
    private void inverseDct32Dav1d(int[] input, int[] output) {
        Workspace workspace = reconstructionWorkspace;
        int[] evenInput = workspace.evenInput16();
        int[] evenOutput = workspace.evenOutput16();
        for (int i = 0; i < 16; i++) {
            evenInput[i] = input[i << 1];
        }
        inverseDct16Dav1d(evenInput, evenOutput);

        int in1 = input[1];
        int in3 = input[3];
        int in5 = input[5];
        int in7 = input[7];
        int in9 = input[9];
        int in11 = input[11];
        int in13 = input[13];
        int in15 = input[15];
        int in17 = input[17];
        int in19 = input[19];
        int in21 = input[21];
        int in23 = input[23];
        int in25 = input[25];
        int in27 = input[27];
        int in29 = input[29];
        int in31 = input[31];

        int t16a = positiveRoundShift((long) in1 * 201 - (long) in31 * (4091 - 4096), 12) - in31;
        int t17a = positiveRoundShift((long) in17 * (3035 - 4096) - (long) in15 * 2751, 12) + in17;
        int t18a = positiveRoundShift((long) in9 * 1751 - (long) in23 * (3703 - 4096), 12) - in23;
        int t19a = positiveRoundShift((long) in25 * (3857 - 4096) - (long) in7 * 1380, 12) + in25;
        int t20a = positiveRoundShift((long) in5 * 995 - (long) in27 * (3973 - 4096), 12) - in27;
        int t21a = positiveRoundShift((long) in21 * (3513 - 4096) - (long) in11 * 2106, 12) + in21;
        int t22a = positiveRoundShift((long) in13 * 1220 - (long) in19 * 1645, 11);
        int t23a = positiveRoundShift((long) in29 * (4052 - 4096) - (long) in3 * 601, 12) + in29;
        int t24a = positiveRoundShift((long) in29 * 601 + (long) in3 * (4052 - 4096), 12) + in3;
        int t25a = positiveRoundShift((long) in13 * 1645 + (long) in19 * 1220, 11);
        int t26a = positiveRoundShift((long) in21 * 2106 + (long) in11 * (3513 - 4096), 12) + in11;
        int t27a = positiveRoundShift((long) in5 * (3973 - 4096) + (long) in27 * 995, 12) + in5;
        int t28a = positiveRoundShift((long) in25 * 1380 + (long) in7 * (3857 - 4096), 12) + in7;
        int t29a = positiveRoundShift((long) in9 * (3703 - 4096) + (long) in23 * 1751, 12) + in9;
        int t30a = positiveRoundShift((long) in17 * 2751 + (long) in15 * (3035 - 4096), 12) + in15;
        int t31a = positiveRoundShift((long) in1 * (4091 - 4096) + (long) in31 * 201, 12) + in1;

        int t16 = clip((long) t16a + t17a);
        int t17 = clip((long) t16a - t17a);
        int t18 = clip((long) t19a - t18a);
        int t19 = clip((long) t19a + t18a);
        int t20 = clip((long) t20a + t21a);
        int t21 = clip((long) t20a - t21a);
        int t22 = clip((long) t23a - t22a);
        int t23 = clip((long) t23a + t22a);
        int t24 = clip((long) t24a + t25a);
        int t25 = clip((long) t24a - t25a);
        int t26 = clip((long) t27a - t26a);
        int t27 = clip((long) t27a + t26a);
        int t28 = clip((long) t28a + t29a);
        int t29 = clip((long) t28a - t29a);
        int t30 = clip((long) t31a - t30a);
        int t31 = clip((long) t31a + t30a);

        t17a = positiveRoundShift((long) t30 * 799 - (long) t17 * (4017 - 4096), 12) - t17;
        t30a = positiveRoundShift((long) t30 * (4017 - 4096) + (long) t17 * 799, 12) + t30;
        t18a = positiveRoundShift(-((long) t29 * (4017 - 4096) + (long) t18 * 799), 12) - t29;
        t29a = positiveRoundShift((long) t29 * 799 - (long) t18 * (4017 - 4096), 12) - t18;
        t21a = positiveRoundShift((long) t26 * 1703 - (long) t21 * 1138, 11);
        t26a = positiveRoundShift((long) t26 * 1138 + (long) t21 * 1703, 11);
        t22a = positiveRoundShift(-((long) t25 * 1138 + (long) t22 * 1703), 11);
        t25a = positiveRoundShift((long) t25 * 1703 - (long) t22 * 1138, 11);

        t16a = clip((long) t16 + t19);
        t17 = clip((long) t17a + t18a);
        t18 = clip((long) t17a - t18a);
        t19a = clip((long) t16 - t19);
        t20a = clip((long) t23 - t20);
        t21 = clip((long) t22a - t21a);
        t22 = clip((long) t22a + t21a);
        t23a = clip((long) t23 + t20);
        t24a = clip((long) t24 + t27);
        t25 = clip((long) t25a + t26a);
        t26 = clip((long) t25a - t26a);
        t27a = clip((long) t24 - t27);
        t28a = clip((long) t31 - t28);
        t29 = clip((long) t30a - t29a);
        t30 = clip((long) t30a + t29a);
        t31a = clip((long) t31 + t28);

        t18a = positiveRoundShift((long) t29 * 1567 - (long) t18 * (3784 - 4096), 12) - t18;
        t29a = positiveRoundShift((long) t29 * (3784 - 4096) + (long) t18 * 1567, 12) + t29;
        t19 = positiveRoundShift((long) t28a * 1567 - (long) t19a * (3784 - 4096), 12) - t19a;
        t28 = positiveRoundShift((long) t28a * (3784 - 4096) + (long) t19a * 1567, 12) + t28a;
        t20 = positiveRoundShift(-((long) t27a * (3784 - 4096) + (long) t20a * 1567), 12) - t27a;
        t27 = positiveRoundShift((long) t27a * 1567 - (long) t20a * (3784 - 4096), 12) - t20a;
        t21a = positiveRoundShift(-((long) t26 * (3784 - 4096) + (long) t21 * 1567), 12) - t26;
        t26a = positiveRoundShift((long) t26 * 1567 - (long) t21 * (3784 - 4096), 12) - t21;

        t16 = clip((long) t16a + t23a);
        t17a = clip((long) t17 + t22);
        t18 = clip((long) t18a + t21a);
        t19a = clip((long) t19 + t20);
        t20a = clip((long) t19 - t20);
        t21 = clip((long) t18a - t21a);
        t22a = clip((long) t17 - t22);
        t23 = clip((long) t16a - t23a);
        t24 = clip((long) t31a - t24a);
        t25a = clip((long) t30 - t25);
        t26 = clip((long) t29a - t26a);
        t27a = clip((long) t28 - t27);
        t28a = clip((long) t28 + t27);
        t29 = clip((long) t29a + t26a);
        t30a = clip((long) t30 + t25);
        t31 = clip((long) t31a + t24a);

        t20 = positiveRoundShift((long) (t27a - t20a) * 181, 8);
        t27 = positiveRoundShift((long) (t27a + t20a) * 181, 8);
        t21a = positiveRoundShift((long) (t26 - t21) * 181, 8);
        t26a = positiveRoundShift((long) (t26 + t21) * 181, 8);
        t22 = positiveRoundShift((long) (t25a - t22a) * 181, 8);
        t25 = positiveRoundShift((long) (t25a + t22a) * 181, 8);
        t23a = positiveRoundShift((long) (t24 - t23) * 181, 8);
        t24a = positiveRoundShift((long) (t24 + t23) * 181, 8);

        output[0] = clip((long) evenOutput[0] + t31);
        output[1] = clip((long) evenOutput[1] + t30a);
        output[2] = clip((long) evenOutput[2] + t29);
        output[3] = clip((long) evenOutput[3] + t28a);
        output[4] = clip((long) evenOutput[4] + t27);
        output[5] = clip((long) evenOutput[5] + t26a);
        output[6] = clip((long) evenOutput[6] + t25);
        output[7] = clip((long) evenOutput[7] + t24a);
        output[8] = clip((long) evenOutput[8] + t23a);
        output[9] = clip((long) evenOutput[9] + t22);
        output[10] = clip((long) evenOutput[10] + t21a);
        output[11] = clip((long) evenOutput[11] + t20);
        output[12] = clip((long) evenOutput[12] + t19a);
        output[13] = clip((long) evenOutput[13] + t18);
        output[14] = clip((long) evenOutput[14] + t17a);
        output[15] = clip((long) evenOutput[15] + t16);
        output[16] = clip((long) evenOutput[15] - t16);
        output[17] = clip((long) evenOutput[14] - t17a);
        output[18] = clip((long) evenOutput[13] - t18);
        output[19] = clip((long) evenOutput[12] - t19a);
        output[20] = clip((long) evenOutput[11] - t20);
        output[21] = clip((long) evenOutput[10] - t21a);
        output[22] = clip((long) evenOutput[9] - t22);
        output[23] = clip((long) evenOutput[8] - t23a);
        output[24] = clip((long) evenOutput[7] - t24a);
        output[25] = clip((long) evenOutput[6] - t25);
        output[26] = clip((long) evenOutput[5] - t26a);
        output[27] = clip((long) evenOutput[4] - t27);
        output[28] = clip((long) evenOutput[3] - t28a);
        output[29] = clip((long) evenOutput[2] - t29);
        output[30] = clip((long) evenOutput[1] - t30a);
        output[31] = clip((long) evenOutput[0] - t31);
    }

    /// Reconstructs one non-`tx64` one-dimensional `DCT_4` vector using the scalar `dav1d`
    /// schedule.
    ///
    /// @param input the dequantized `DCT_4` input vector
    /// @param output the reconstructed output vector
    private void inverseDct4Dav1d(int[] input, int[] output) {
        int in0 = input[0];
        int in1 = input[1];
        int in2 = input[2];
        int in3 = input[3];

        int t0 = positiveRoundShift((long) (in0 + in2) * 181, 8);
        int t1 = positiveRoundShift((long) (in0 - in2) * 181, 8);
        int t2 = positiveRoundShift((long) in1 * 1567 - (long) in3 * (3784 - 4096), 12) - in3;
        int t3 = positiveRoundShift((long) in1 * (3784 - 4096) + (long) in3 * 1567, 12) + in1;

        output[0] = clip((long) t0 + t3);
        output[1] = clip((long) t1 + t2);
        output[2] = clip((long) t1 - t2);
        output[3] = clip((long) t0 - t3);
    }

    /// Reconstructs one non-`tx64` one-dimensional `DCT_8` vector using the scalar `dav1d`
    /// schedule.
    ///
    /// @param input the dequantized `DCT_8` input vector
    /// @param output the reconstructed output vector
    private void inverseDct8Dav1d(int[] input, int[] output) {
        Workspace workspace = reconstructionWorkspace;
        int[] evenInput = workspace.evenInput4();
        int[] evenOutput = workspace.evenOutput4();
        for (int i = 0; i < 4; i++) {
            evenInput[i] = input[i << 1];
        }
        inverseDct4Dav1d(evenInput, evenOutput);

        int in1 = input[1];
        int in3 = input[3];
        int in5 = input[5];
        int in7 = input[7];

        int t4a = positiveRoundShift((long) in1 * 799 - (long) in7 * (4017 - 4096), 12) - in7;
        int t5a = positiveRoundShift((long) in5 * 1703 - (long) in3 * 1138, 11);
        int t6a = positiveRoundShift((long) in5 * 1138 + (long) in3 * 1703, 11);
        int t7a = positiveRoundShift((long) in1 * (4017 - 4096) + (long) in7 * 799, 12) + in1;

        int t4 = clip((long) t4a + t5a);
        t5a = clip((long) t4a - t5a);
        int t7 = clip((long) t7a + t6a);
        t6a = clip((long) t7a - t6a);

        int t5 = positiveRoundShift((long) (t6a - t5a) * 181, 8);
        int t6 = positiveRoundShift((long) (t6a + t5a) * 181, 8);

        output[0] = clip((long) evenOutput[0] + t7);
        output[1] = clip((long) evenOutput[1] + t6);
        output[2] = clip((long) evenOutput[2] + t5);
        output[3] = clip((long) evenOutput[3] + t4);
        output[4] = clip((long) evenOutput[3] - t4);
        output[5] = clip((long) evenOutput[2] - t5);
        output[6] = clip((long) evenOutput[1] - t6);
        output[7] = clip((long) evenOutput[0] - t7);
    }

    /// Reconstructs one non-`tx64` one-dimensional `DCT_16` vector using the scalar `dav1d`
    /// schedule.
    ///
    /// @param input the dequantized `DCT_16` input vector
    /// @param output the reconstructed output vector
    private void inverseDct16Dav1d(int[] input, int[] output) {
        Workspace workspace = reconstructionWorkspace;
        int[] evenInput = workspace.evenInput8();
        int[] evenOutput = workspace.evenOutput8();
        for (int i = 0; i < 8; i++) {
            evenInput[i] = input[i << 1];
        }
        inverseDct8Dav1d(evenInput, evenOutput);

        int in1 = input[1];
        int in3 = input[3];
        int in5 = input[5];
        int in7 = input[7];
        int in9 = input[9];
        int in11 = input[11];
        int in13 = input[13];
        int in15 = input[15];

        int t8a = positiveRoundShift((long) in1 * 401 - (long) in15 * (4076 - 4096), 12) - in15;
        int t9a = positiveRoundShift((long) in9 * 1583 - (long) in7 * 1299, 11);
        int t10a = positiveRoundShift((long) in5 * 1931 - (long) in11 * (3612 - 4096), 12) - in11;
        int t11a = positiveRoundShift((long) in13 * (3920 - 4096) - (long) in3 * 1189, 12) + in13;
        int t12a = positiveRoundShift((long) in13 * 1189 + (long) in3 * (3920 - 4096), 12) + in3;
        int t13a = positiveRoundShift((long) in5 * (3612 - 4096) + (long) in11 * 1931, 12) + in5;
        int t14a = positiveRoundShift((long) in9 * 1299 + (long) in7 * 1583, 11);
        int t15a = positiveRoundShift((long) in1 * (4076 - 4096) + (long) in15 * 401, 12) + in1;

        int t8 = clip((long) t8a + t9a);
        int t9 = clip((long) t8a - t9a);
        int t10 = clip((long) t11a - t10a);
        int t11 = clip((long) t11a + t10a);
        int t12 = clip((long) t12a + t13a);
        int t13 = clip((long) t12a - t13a);
        int t14 = clip((long) t15a - t14a);
        int t15 = clip((long) t15a + t14a);

        t9a = positiveRoundShift((long) t14 * 1567 - (long) t9 * (3784 - 4096), 12) - t9;
        t14a = positiveRoundShift((long) t14 * (3784 - 4096) + (long) t9 * 1567, 12) + t14;
        t10a = positiveRoundShift(-((long) t13 * (3784 - 4096) + (long) t10 * 1567), 12) - t13;
        t13a = positiveRoundShift((long) t13 * 1567 - (long) t10 * (3784 - 4096), 12) - t10;

        t8a = clip((long) t8 + t11);
        t9 = clip((long) t9a + t10a);
        t10 = clip((long) t9a - t10a);
        int t11b = clip((long) t8 - t11);
        int t12b = clip((long) t15 - t12);
        t13 = clip((long) t14a - t13a);
        t14 = clip((long) t14a + t13a);
        int t15b = clip((long) t15 + t12);

        t10a = positiveRoundShift((long) (t13 - t10) * 181, 8);
        t13a = positiveRoundShift((long) (t13 + t10) * 181, 8);
        t11 = positiveRoundShift((long) (t12b - t11b) * 181, 8);
        t12 = positiveRoundShift((long) (t12b + t11b) * 181, 8);

        output[0] = clip((long) evenOutput[0] + t15b);
        output[1] = clip((long) evenOutput[1] + t14);
        output[2] = clip((long) evenOutput[2] + t13a);
        output[3] = clip((long) evenOutput[3] + t12);
        output[4] = clip((long) evenOutput[4] + t11);
        output[5] = clip((long) evenOutput[5] + t10a);
        output[6] = clip((long) evenOutput[6] + t9);
        output[7] = clip((long) evenOutput[7] + t8a);
        output[8] = clip((long) evenOutput[7] - t8a);
        output[9] = clip((long) evenOutput[6] - t9);
        output[10] = clip((long) evenOutput[5] - t10a);
        output[11] = clip((long) evenOutput[4] - t11);
        output[12] = clip((long) evenOutput[3] - t12);
        output[13] = clip((long) evenOutput[2] - t13a);
        output[14] = clip((long) evenOutput[1] - t14);
        output[15] = clip((long) evenOutput[0] - t15b);
    }

    /// Reconstructs one one-dimensional `DCT_64` vector with the staged AOM reference schedule.
    ///
    /// This follows `av1_idct64()` stage-for-stage, including its fixed-point rounding and clipping
    /// schedule.
    ///
    /// @param input the dequantized `DCT_64` input vector
    /// @param output the reconstructed output vector
    private void inverseDct64Aom(int[] input, int[] output) {
        int[] step = reconstructionWorkspace.kernelStep();
        int[] bf0;
        int[] bf1;

        bf1 = output;
        bf1[0] = input[0];
        bf1[1] = input[32];
        bf1[2] = input[16];
        bf1[3] = input[48];
        bf1[4] = input[8];
        bf1[5] = input[40];
        bf1[6] = input[24];
        bf1[7] = input[56];
        bf1[8] = input[4];
        bf1[9] = input[36];
        bf1[10] = input[20];
        bf1[11] = input[52];
        bf1[12] = input[12];
        bf1[13] = input[44];
        bf1[14] = input[28];
        bf1[15] = input[60];
        bf1[16] = input[2];
        bf1[17] = input[34];
        bf1[18] = input[18];
        bf1[19] = input[50];
        bf1[20] = input[10];
        bf1[21] = input[42];
        bf1[22] = input[26];
        bf1[23] = input[58];
        bf1[24] = input[6];
        bf1[25] = input[38];
        bf1[26] = input[22];
        bf1[27] = input[54];
        bf1[28] = input[14];
        bf1[29] = input[46];
        bf1[30] = input[30];
        bf1[31] = input[62];
        bf1[32] = input[1];
        bf1[33] = input[33];
        bf1[34] = input[17];
        bf1[35] = input[49];
        bf1[36] = input[9];
        bf1[37] = input[41];
        bf1[38] = input[25];
        bf1[39] = input[57];
        bf1[40] = input[5];
        bf1[41] = input[37];
        bf1[42] = input[21];
        bf1[43] = input[53];
        bf1[44] = input[13];
        bf1[45] = input[45];
        bf1[46] = input[29];
        bf1[47] = input[61];
        bf1[48] = input[3];
        bf1[49] = input[35];
        bf1[50] = input[19];
        bf1[51] = input[51];
        bf1[52] = input[11];
        bf1[53] = input[43];
        bf1[54] = input[27];
        bf1[55] = input[59];
        bf1[56] = input[7];
        bf1[57] = input[39];
        bf1[58] = input[23];
        bf1[59] = input[55];
        bf1[60] = input[15];
        bf1[61] = input[47];
        bf1[62] = input[31];
        bf1[63] = input[63];

        bf0 = output;
        bf1 = step;
        System.arraycopy(bf0, 0, bf1, 0, 32);
        bf1[32] = halfBtf(COSPI[63], bf0[32], -COSPI[1], bf0[63]);
        bf1[33] = halfBtf(COSPI[31], bf0[33], -COSPI[33], bf0[62]);
        bf1[34] = halfBtf(COSPI[47], bf0[34], -COSPI[17], bf0[61]);
        bf1[35] = halfBtf(COSPI[15], bf0[35], -COSPI[49], bf0[60]);
        bf1[36] = halfBtf(COSPI[55], bf0[36], -COSPI[9], bf0[59]);
        bf1[37] = halfBtf(COSPI[23], bf0[37], -COSPI[41], bf0[58]);
        bf1[38] = halfBtf(COSPI[39], bf0[38], -COSPI[25], bf0[57]);
        bf1[39] = halfBtf(COSPI[7], bf0[39], -COSPI[57], bf0[56]);
        bf1[40] = halfBtf(COSPI[59], bf0[40], -COSPI[5], bf0[55]);
        bf1[41] = halfBtf(COSPI[27], bf0[41], -COSPI[37], bf0[54]);
        bf1[42] = halfBtf(COSPI[43], bf0[42], -COSPI[21], bf0[53]);
        bf1[43] = halfBtf(COSPI[11], bf0[43], -COSPI[53], bf0[52]);
        bf1[44] = halfBtf(COSPI[51], bf0[44], -COSPI[13], bf0[51]);
        bf1[45] = halfBtf(COSPI[19], bf0[45], -COSPI[45], bf0[50]);
        bf1[46] = halfBtf(COSPI[35], bf0[46], -COSPI[29], bf0[49]);
        bf1[47] = halfBtf(COSPI[3], bf0[47], -COSPI[61], bf0[48]);
        bf1[48] = halfBtf(COSPI[61], bf0[47], COSPI[3], bf0[48]);
        bf1[49] = halfBtf(COSPI[29], bf0[46], COSPI[35], bf0[49]);
        bf1[50] = halfBtf(COSPI[45], bf0[45], COSPI[19], bf0[50]);
        bf1[51] = halfBtf(COSPI[13], bf0[44], COSPI[51], bf0[51]);
        bf1[52] = halfBtf(COSPI[53], bf0[43], COSPI[11], bf0[52]);
        bf1[53] = halfBtf(COSPI[21], bf0[42], COSPI[43], bf0[53]);
        bf1[54] = halfBtf(COSPI[37], bf0[41], COSPI[27], bf0[54]);
        bf1[55] = halfBtf(COSPI[5], bf0[40], COSPI[59], bf0[55]);
        bf1[56] = halfBtf(COSPI[57], bf0[39], COSPI[7], bf0[56]);
        bf1[57] = halfBtf(COSPI[25], bf0[38], COSPI[39], bf0[57]);
        bf1[58] = halfBtf(COSPI[41], bf0[37], COSPI[23], bf0[58]);
        bf1[59] = halfBtf(COSPI[9], bf0[36], COSPI[55], bf0[59]);
        bf1[60] = halfBtf(COSPI[49], bf0[35], COSPI[15], bf0[60]);
        bf1[61] = halfBtf(COSPI[17], bf0[34], COSPI[47], bf0[61]);
        bf1[62] = halfBtf(COSPI[33], bf0[33], COSPI[31], bf0[62]);
        bf1[63] = halfBtf(COSPI[1], bf0[32], COSPI[63], bf0[63]);

        bf0 = step;
        bf1 = output;
        System.arraycopy(bf0, 0, bf1, 0, 16);
        bf1[16] = halfBtf(COSPI[62], bf0[16], -COSPI[2], bf0[31]);
        bf1[17] = halfBtf(COSPI[30], bf0[17], -COSPI[34], bf0[30]);
        bf1[18] = halfBtf(COSPI[46], bf0[18], -COSPI[18], bf0[29]);
        bf1[19] = halfBtf(COSPI[14], bf0[19], -COSPI[50], bf0[28]);
        bf1[20] = halfBtf(COSPI[54], bf0[20], -COSPI[10], bf0[27]);
        bf1[21] = halfBtf(COSPI[22], bf0[21], -COSPI[42], bf0[26]);
        bf1[22] = halfBtf(COSPI[38], bf0[22], -COSPI[26], bf0[25]);
        bf1[23] = halfBtf(COSPI[6], bf0[23], -COSPI[58], bf0[24]);
        bf1[24] = halfBtf(COSPI[58], bf0[23], COSPI[6], bf0[24]);
        bf1[25] = halfBtf(COSPI[26], bf0[22], COSPI[38], bf0[25]);
        bf1[26] = halfBtf(COSPI[42], bf0[21], COSPI[22], bf0[26]);
        bf1[27] = halfBtf(COSPI[10], bf0[20], COSPI[54], bf0[27]);
        bf1[28] = halfBtf(COSPI[50], bf0[19], COSPI[14], bf0[28]);
        bf1[29] = halfBtf(COSPI[18], bf0[18], COSPI[46], bf0[29]);
        bf1[30] = halfBtf(COSPI[34], bf0[17], COSPI[30], bf0[30]);
        bf1[31] = halfBtf(COSPI[2], bf0[16], COSPI[62], bf0[31]);
        bf1[32] = clip((long) bf0[32] + bf0[33]);
        bf1[33] = clip((long) bf0[32] - bf0[33]);
        bf1[34] = clip(-(long) bf0[34] + bf0[35]);
        bf1[35] = clip((long) bf0[34] + bf0[35]);
        bf1[36] = clip((long) bf0[36] + bf0[37]);
        bf1[37] = clip((long) bf0[36] - bf0[37]);
        bf1[38] = clip(-(long) bf0[38] + bf0[39]);
        bf1[39] = clip((long) bf0[38] + bf0[39]);
        bf1[40] = clip((long) bf0[40] + bf0[41]);
        bf1[41] = clip((long) bf0[40] - bf0[41]);
        bf1[42] = clip(-(long) bf0[42] + bf0[43]);
        bf1[43] = clip((long) bf0[42] + bf0[43]);
        bf1[44] = clip((long) bf0[44] + bf0[45]);
        bf1[45] = clip((long) bf0[44] - bf0[45]);
        bf1[46] = clip(-(long) bf0[46] + bf0[47]);
        bf1[47] = clip((long) bf0[46] + bf0[47]);
        bf1[48] = clip((long) bf0[48] + bf0[49]);
        bf1[49] = clip((long) bf0[48] - bf0[49]);
        bf1[50] = clip(-(long) bf0[50] + bf0[51]);
        bf1[51] = clip((long) bf0[50] + bf0[51]);
        bf1[52] = clip((long) bf0[52] + bf0[53]);
        bf1[53] = clip((long) bf0[52] - bf0[53]);
        bf1[54] = clip(-(long) bf0[54] + bf0[55]);
        bf1[55] = clip((long) bf0[54] + bf0[55]);
        bf1[56] = clip((long) bf0[56] + bf0[57]);
        bf1[57] = clip((long) bf0[56] - bf0[57]);
        bf1[58] = clip(-(long) bf0[58] + bf0[59]);
        bf1[59] = clip((long) bf0[58] + bf0[59]);
        bf1[60] = clip((long) bf0[60] + bf0[61]);
        bf1[61] = clip((long) bf0[60] - bf0[61]);
        bf1[62] = clip(-(long) bf0[62] + bf0[63]);
        bf1[63] = clip((long) bf0[62] + bf0[63]);

        bf0 = output;
        bf1 = step;
        System.arraycopy(bf0, 0, bf1, 0, 8);
        bf1[8] = halfBtf(COSPI[60], bf0[8], -COSPI[4], bf0[15]);
        bf1[9] = halfBtf(COSPI[28], bf0[9], -COSPI[36], bf0[14]);
        bf1[10] = halfBtf(COSPI[44], bf0[10], -COSPI[20], bf0[13]);
        bf1[11] = halfBtf(COSPI[12], bf0[11], -COSPI[52], bf0[12]);
        bf1[12] = halfBtf(COSPI[52], bf0[11], COSPI[12], bf0[12]);
        bf1[13] = halfBtf(COSPI[20], bf0[10], COSPI[44], bf0[13]);
        bf1[14] = halfBtf(COSPI[36], bf0[9], COSPI[28], bf0[14]);
        bf1[15] = halfBtf(COSPI[4], bf0[8], COSPI[60], bf0[15]);
        bf1[16] = clip((long) bf0[16] + bf0[17]);
        bf1[17] = clip((long) bf0[16] - bf0[17]);
        bf1[18] = clip(-(long) bf0[18] + bf0[19]);
        bf1[19] = clip((long) bf0[18] + bf0[19]);
        bf1[20] = clip((long) bf0[20] + bf0[21]);
        bf1[21] = clip((long) bf0[20] - bf0[21]);
        bf1[22] = clip(-(long) bf0[22] + bf0[23]);
        bf1[23] = clip((long) bf0[22] + bf0[23]);
        bf1[24] = clip((long) bf0[24] + bf0[25]);
        bf1[25] = clip((long) bf0[24] - bf0[25]);
        bf1[26] = clip(-(long) bf0[26] + bf0[27]);
        bf1[27] = clip((long) bf0[26] + bf0[27]);
        bf1[28] = clip((long) bf0[28] + bf0[29]);
        bf1[29] = clip((long) bf0[28] - bf0[29]);
        bf1[30] = clip(-(long) bf0[30] + bf0[31]);
        bf1[31] = clip((long) bf0[30] + bf0[31]);
        bf1[32] = bf0[32];
        bf1[33] = halfBtf(-COSPI[4], bf0[33], COSPI[60], bf0[62]);
        bf1[34] = halfBtf(-COSPI[60], bf0[34], -COSPI[4], bf0[61]);
        bf1[35] = bf0[35];
        bf1[36] = bf0[36];
        bf1[37] = halfBtf(-COSPI[36], bf0[37], COSPI[28], bf0[58]);
        bf1[38] = halfBtf(-COSPI[28], bf0[38], -COSPI[36], bf0[57]);
        bf1[39] = bf0[39];
        bf1[40] = bf0[40];
        bf1[41] = halfBtf(-COSPI[20], bf0[41], COSPI[44], bf0[54]);
        bf1[42] = halfBtf(-COSPI[44], bf0[42], -COSPI[20], bf0[53]);
        bf1[43] = bf0[43];
        bf1[44] = bf0[44];
        bf1[45] = halfBtf(-COSPI[52], bf0[45], COSPI[12], bf0[50]);
        bf1[46] = halfBtf(-COSPI[12], bf0[46], -COSPI[52], bf0[49]);
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = halfBtf(-COSPI[52], bf0[46], COSPI[12], bf0[49]);
        bf1[50] = halfBtf(COSPI[12], bf0[45], COSPI[52], bf0[50]);
        bf1[51] = bf0[51];
        bf1[52] = bf0[52];
        bf1[53] = halfBtf(-COSPI[20], bf0[42], COSPI[44], bf0[53]);
        bf1[54] = halfBtf(COSPI[44], bf0[41], COSPI[20], bf0[54]);
        bf1[55] = bf0[55];
        bf1[56] = bf0[56];
        bf1[57] = halfBtf(-COSPI[36], bf0[38], COSPI[28], bf0[57]);
        bf1[58] = halfBtf(COSPI[28], bf0[37], COSPI[36], bf0[58]);
        bf1[59] = bf0[59];
        bf1[60] = bf0[60];
        bf1[61] = halfBtf(-COSPI[4], bf0[34], COSPI[60], bf0[61]);
        bf1[62] = halfBtf(COSPI[60], bf0[33], COSPI[4], bf0[62]);
        bf1[63] = bf0[63];

        bf0 = step;
        bf1 = output;
        System.arraycopy(bf0, 0, bf1, 0, 4);
        bf1[4] = halfBtf(COSPI[56], bf0[4], -COSPI[8], bf0[7]);
        bf1[5] = halfBtf(COSPI[24], bf0[5], -COSPI[40], bf0[6]);
        bf1[6] = halfBtf(COSPI[40], bf0[5], COSPI[24], bf0[6]);
        bf1[7] = halfBtf(COSPI[8], bf0[4], COSPI[56], bf0[7]);
        bf1[8] = clip((long) bf0[8] + bf0[9]);
        bf1[9] = clip((long) bf0[8] - bf0[9]);
        bf1[10] = clip(-(long) bf0[10] + bf0[11]);
        bf1[11] = clip((long) bf0[10] + bf0[11]);
        bf1[12] = clip((long) bf0[12] + bf0[13]);
        bf1[13] = clip((long) bf0[12] - bf0[13]);
        bf1[14] = clip(-(long) bf0[14] + bf0[15]);
        bf1[15] = clip((long) bf0[14] + bf0[15]);
        bf1[16] = bf0[16];
        bf1[17] = halfBtf(-COSPI[8], bf0[17], COSPI[56], bf0[30]);
        bf1[18] = halfBtf(-COSPI[56], bf0[18], -COSPI[8], bf0[29]);
        bf1[19] = bf0[19];
        bf1[20] = bf0[20];
        bf1[21] = halfBtf(-COSPI[40], bf0[21], COSPI[24], bf0[26]);
        bf1[22] = halfBtf(-COSPI[24], bf0[22], -COSPI[40], bf0[25]);
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = halfBtf(-COSPI[40], bf0[22], COSPI[24], bf0[25]);
        bf1[26] = halfBtf(COSPI[24], bf0[21], COSPI[40], bf0[26]);
        bf1[27] = bf0[27];
        bf1[28] = bf0[28];
        bf1[29] = halfBtf(-COSPI[8], bf0[18], COSPI[56], bf0[29]);
        bf1[30] = halfBtf(COSPI[56], bf0[17], COSPI[8], bf0[30]);
        bf1[31] = bf0[31];
        bf1[32] = clip((long) bf0[32] + bf0[35]);
        bf1[33] = clip((long) bf0[33] + bf0[34]);
        bf1[34] = clip((long) bf0[33] - bf0[34]);
        bf1[35] = clip((long) bf0[32] - bf0[35]);
        bf1[36] = clip(-(long) bf0[36] + bf0[39]);
        bf1[37] = clip(-(long) bf0[37] + bf0[38]);
        bf1[38] = clip((long) bf0[37] + bf0[38]);
        bf1[39] = clip((long) bf0[36] + bf0[39]);
        bf1[40] = clip((long) bf0[40] + bf0[43]);
        bf1[41] = clip((long) bf0[41] + bf0[42]);
        bf1[42] = clip((long) bf0[41] - bf0[42]);
        bf1[43] = clip((long) bf0[40] - bf0[43]);
        bf1[44] = clip(-(long) bf0[44] + bf0[47]);
        bf1[45] = clip(-(long) bf0[45] + bf0[46]);
        bf1[46] = clip((long) bf0[45] + bf0[46]);
        bf1[47] = clip((long) bf0[44] + bf0[47]);
        bf1[48] = clip((long) bf0[48] + bf0[51]);
        bf1[49] = clip((long) bf0[49] + bf0[50]);
        bf1[50] = clip((long) bf0[49] - bf0[50]);
        bf1[51] = clip((long) bf0[48] - bf0[51]);
        bf1[52] = clip(-(long) bf0[52] + bf0[55]);
        bf1[53] = clip(-(long) bf0[53] + bf0[54]);
        bf1[54] = clip((long) bf0[53] + bf0[54]);
        bf1[55] = clip((long) bf0[52] + bf0[55]);
        bf1[56] = clip((long) bf0[56] + bf0[59]);
        bf1[57] = clip((long) bf0[57] + bf0[58]);
        bf1[58] = clip((long) bf0[57] - bf0[58]);
        bf1[59] = clip((long) bf0[56] - bf0[59]);
        bf1[60] = clip(-(long) bf0[60] + bf0[63]);
        bf1[61] = clip(-(long) bf0[61] + bf0[62]);
        bf1[62] = clip((long) bf0[61] + bf0[62]);
        bf1[63] = clip((long) bf0[60] + bf0[63]);

        bf0 = output;
        bf1 = step;
        bf1[0] = halfBtf(COSPI[32], bf0[0], COSPI[32], bf0[1]);
        bf1[1] = halfBtf(COSPI[32], bf0[0], -COSPI[32], bf0[1]);
        bf1[2] = halfBtf(COSPI[48], bf0[2], -COSPI[16], bf0[3]);
        bf1[3] = halfBtf(COSPI[16], bf0[2], COSPI[48], bf0[3]);
        bf1[4] = clip((long) bf0[4] + bf0[5]);
        bf1[5] = clip((long) bf0[4] - bf0[5]);
        bf1[6] = clip(-(long) bf0[6] + bf0[7]);
        bf1[7] = clip((long) bf0[6] + bf0[7]);
        bf1[8] = bf0[8];
        bf1[9] = halfBtf(-COSPI[16], bf0[9], COSPI[48], bf0[14]);
        bf1[10] = halfBtf(-COSPI[48], bf0[10], -COSPI[16], bf0[13]);
        bf1[11] = bf0[11];
        bf1[12] = bf0[12];
        bf1[13] = halfBtf(-COSPI[16], bf0[10], COSPI[48], bf0[13]);
        bf1[14] = halfBtf(COSPI[48], bf0[9], COSPI[16], bf0[14]);
        bf1[15] = bf0[15];
        bf1[16] = clip((long) bf0[16] + bf0[19]);
        bf1[17] = clip((long) bf0[17] + bf0[18]);
        bf1[18] = clip((long) bf0[17] - bf0[18]);
        bf1[19] = clip((long) bf0[16] - bf0[19]);
        bf1[20] = clip(-(long) bf0[20] + bf0[23]);
        bf1[21] = clip(-(long) bf0[21] + bf0[22]);
        bf1[22] = clip((long) bf0[21] + bf0[22]);
        bf1[23] = clip((long) bf0[20] + bf0[23]);
        bf1[24] = clip((long) bf0[24] + bf0[27]);
        bf1[25] = clip((long) bf0[25] + bf0[26]);
        bf1[26] = clip((long) bf0[25] - bf0[26]);
        bf1[27] = clip((long) bf0[24] - bf0[27]);
        bf1[28] = clip(-(long) bf0[28] + bf0[31]);
        bf1[29] = clip(-(long) bf0[29] + bf0[30]);
        bf1[30] = clip((long) bf0[29] + bf0[30]);
        bf1[31] = clip((long) bf0[28] + bf0[31]);
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = halfBtf(-COSPI[8], bf0[34], COSPI[56], bf0[61]);
        bf1[35] = halfBtf(-COSPI[8], bf0[35], COSPI[56], bf0[60]);
        bf1[36] = halfBtf(-COSPI[56], bf0[36], -COSPI[8], bf0[59]);
        bf1[37] = halfBtf(-COSPI[56], bf0[37], -COSPI[8], bf0[58]);
        bf1[38] = bf0[38];
        bf1[39] = bf0[39];
        bf1[40] = bf0[40];
        bf1[41] = bf0[41];
        bf1[42] = halfBtf(-COSPI[40], bf0[42], COSPI[24], bf0[53]);
        bf1[43] = halfBtf(-COSPI[40], bf0[43], COSPI[24], bf0[52]);
        bf1[44] = halfBtf(-COSPI[24], bf0[44], -COSPI[40], bf0[51]);
        bf1[45] = halfBtf(-COSPI[24], bf0[45], -COSPI[40], bf0[50]);
        bf1[46] = bf0[46];
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = bf0[49];
        bf1[50] = halfBtf(-COSPI[40], bf0[45], COSPI[24], bf0[50]);
        bf1[51] = halfBtf(-COSPI[40], bf0[44], COSPI[24], bf0[51]);
        bf1[52] = halfBtf(COSPI[24], bf0[43], COSPI[40], bf0[52]);
        bf1[53] = halfBtf(COSPI[24], bf0[42], COSPI[40], bf0[53]);
        bf1[54] = bf0[54];
        bf1[55] = bf0[55];
        bf1[56] = bf0[56];
        bf1[57] = bf0[57];
        bf1[58] = halfBtf(-COSPI[8], bf0[37], COSPI[56], bf0[58]);
        bf1[59] = halfBtf(-COSPI[8], bf0[36], COSPI[56], bf0[59]);
        bf1[60] = halfBtf(COSPI[56], bf0[35], COSPI[8], bf0[60]);
        bf1[61] = halfBtf(COSPI[56], bf0[34], COSPI[8], bf0[61]);
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];

        bf0 = step;
        bf1 = output;
        bf1[0] = clip((long) bf0[0] + bf0[3]);
        bf1[1] = clip((long) bf0[1] + bf0[2]);
        bf1[2] = clip((long) bf0[1] - bf0[2]);
        bf1[3] = clip((long) bf0[0] - bf0[3]);
        bf1[4] = bf0[4];
        bf1[5] = halfBtf(-COSPI[32], bf0[5], COSPI[32], bf0[6]);
        bf1[6] = halfBtf(COSPI[32], bf0[5], COSPI[32], bf0[6]);
        bf1[7] = bf0[7];
        bf1[8] = clip((long) bf0[8] + bf0[11]);
        bf1[9] = clip((long) bf0[9] + bf0[10]);
        bf1[10] = clip((long) bf0[9] - bf0[10]);
        bf1[11] = clip((long) bf0[8] - bf0[11]);
        bf1[12] = clip(-(long) bf0[12] + bf0[15]);
        bf1[13] = clip(-(long) bf0[13] + bf0[14]);
        bf1[14] = clip((long) bf0[13] + bf0[14]);
        bf1[15] = clip((long) bf0[12] + bf0[15]);
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = halfBtf(-COSPI[16], bf0[18], COSPI[48], bf0[29]);
        bf1[19] = halfBtf(-COSPI[16], bf0[19], COSPI[48], bf0[28]);
        bf1[20] = halfBtf(-COSPI[48], bf0[20], -COSPI[16], bf0[27]);
        bf1[21] = halfBtf(-COSPI[48], bf0[21], -COSPI[16], bf0[26]);
        bf1[22] = bf0[22];
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = bf0[25];
        bf1[26] = halfBtf(-COSPI[16], bf0[21], COSPI[48], bf0[26]);
        bf1[27] = halfBtf(-COSPI[16], bf0[20], COSPI[48], bf0[27]);
        bf1[28] = halfBtf(COSPI[48], bf0[19], COSPI[16], bf0[28]);
        bf1[29] = halfBtf(COSPI[48], bf0[18], COSPI[16], bf0[29]);
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
        bf1[32] = clip((long) bf0[32] + bf0[39]);
        bf1[33] = clip((long) bf0[33] + bf0[38]);
        bf1[34] = clip((long) bf0[34] + bf0[37]);
        bf1[35] = clip((long) bf0[35] + bf0[36]);
        bf1[36] = clip((long) bf0[35] - bf0[36]);
        bf1[37] = clip((long) bf0[34] - bf0[37]);
        bf1[38] = clip((long) bf0[33] - bf0[38]);
        bf1[39] = clip((long) bf0[32] - bf0[39]);
        bf1[40] = clip(-(long) bf0[40] + bf0[47]);
        bf1[41] = clip(-(long) bf0[41] + bf0[46]);
        bf1[42] = clip(-(long) bf0[42] + bf0[45]);
        bf1[43] = clip(-(long) bf0[43] + bf0[44]);
        bf1[44] = clip((long) bf0[43] + bf0[44]);
        bf1[45] = clip((long) bf0[42] + bf0[45]);
        bf1[46] = clip((long) bf0[41] + bf0[46]);
        bf1[47] = clip((long) bf0[40] + bf0[47]);
        bf1[48] = clip((long) bf0[48] + bf0[55]);
        bf1[49] = clip((long) bf0[49] + bf0[54]);
        bf1[50] = clip((long) bf0[50] + bf0[53]);
        bf1[51] = clip((long) bf0[51] + bf0[52]);
        bf1[52] = clip((long) bf0[51] - bf0[52]);
        bf1[53] = clip((long) bf0[50] - bf0[53]);
        bf1[54] = clip((long) bf0[49] - bf0[54]);
        bf1[55] = clip((long) bf0[48] - bf0[55]);
        bf1[56] = clip(-(long) bf0[56] + bf0[63]);
        bf1[57] = clip(-(long) bf0[57] + bf0[62]);
        bf1[58] = clip(-(long) bf0[58] + bf0[61]);
        bf1[59] = clip(-(long) bf0[59] + bf0[60]);
        bf1[60] = clip((long) bf0[59] + bf0[60]);
        bf1[61] = clip((long) bf0[58] + bf0[61]);
        bf1[62] = clip((long) bf0[57] + bf0[62]);
        bf1[63] = clip((long) bf0[56] + bf0[63]);

        bf0 = output;
        bf1 = step;
        bf1[0] = clip((long) bf0[0] + bf0[7]);
        bf1[1] = clip((long) bf0[1] + bf0[6]);
        bf1[2] = clip((long) bf0[2] + bf0[5]);
        bf1[3] = clip((long) bf0[3] + bf0[4]);
        bf1[4] = clip((long) bf0[3] - bf0[4]);
        bf1[5] = clip((long) bf0[2] - bf0[5]);
        bf1[6] = clip((long) bf0[1] - bf0[6]);
        bf1[7] = clip((long) bf0[0] - bf0[7]);
        bf1[8] = bf0[8];
        bf1[9] = bf0[9];
        bf1[10] = halfBtf(-COSPI[32], bf0[10], COSPI[32], bf0[13]);
        bf1[11] = halfBtf(-COSPI[32], bf0[11], COSPI[32], bf0[12]);
        bf1[12] = halfBtf(COSPI[32], bf0[11], COSPI[32], bf0[12]);
        bf1[13] = halfBtf(COSPI[32], bf0[10], COSPI[32], bf0[13]);
        bf1[14] = bf0[14];
        bf1[15] = bf0[15];
        bf1[16] = clip((long) bf0[16] + bf0[23]);
        bf1[17] = clip((long) bf0[17] + bf0[22]);
        bf1[18] = clip((long) bf0[18] + bf0[21]);
        bf1[19] = clip((long) bf0[19] + bf0[20]);
        bf1[20] = clip((long) bf0[19] - bf0[20]);
        bf1[21] = clip((long) bf0[18] - bf0[21]);
        bf1[22] = clip((long) bf0[17] - bf0[22]);
        bf1[23] = clip((long) bf0[16] - bf0[23]);
        bf1[24] = clip(-(long) bf0[24] + bf0[31]);
        bf1[25] = clip(-(long) bf0[25] + bf0[30]);
        bf1[26] = clip(-(long) bf0[26] + bf0[29]);
        bf1[27] = clip(-(long) bf0[27] + bf0[28]);
        bf1[28] = clip((long) bf0[27] + bf0[28]);
        bf1[29] = clip((long) bf0[26] + bf0[29]);
        bf1[30] = clip((long) bf0[25] + bf0[30]);
        bf1[31] = clip((long) bf0[24] + bf0[31]);
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = bf0[34];
        bf1[35] = bf0[35];
        bf1[36] = halfBtf(-COSPI[16], bf0[36], COSPI[48], bf0[59]);
        bf1[37] = halfBtf(-COSPI[16], bf0[37], COSPI[48], bf0[58]);
        bf1[38] = halfBtf(-COSPI[16], bf0[38], COSPI[48], bf0[57]);
        bf1[39] = halfBtf(-COSPI[16], bf0[39], COSPI[48], bf0[56]);
        bf1[40] = halfBtf(-COSPI[48], bf0[40], -COSPI[16], bf0[55]);
        bf1[41] = halfBtf(-COSPI[48], bf0[41], -COSPI[16], bf0[54]);
        bf1[42] = halfBtf(-COSPI[48], bf0[42], -COSPI[16], bf0[53]);
        bf1[43] = halfBtf(-COSPI[48], bf0[43], -COSPI[16], bf0[52]);
        bf1[44] = bf0[44];
        bf1[45] = bf0[45];
        bf1[46] = bf0[46];
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = bf0[49];
        bf1[50] = bf0[50];
        bf1[51] = bf0[51];
        bf1[52] = halfBtf(-COSPI[16], bf0[43], COSPI[48], bf0[52]);
        bf1[53] = halfBtf(-COSPI[16], bf0[42], COSPI[48], bf0[53]);
        bf1[54] = halfBtf(-COSPI[16], bf0[41], COSPI[48], bf0[54]);
        bf1[55] = halfBtf(-COSPI[16], bf0[40], COSPI[48], bf0[55]);
        bf1[56] = halfBtf(COSPI[48], bf0[39], COSPI[16], bf0[56]);
        bf1[57] = halfBtf(COSPI[48], bf0[38], COSPI[16], bf0[57]);
        bf1[58] = halfBtf(COSPI[48], bf0[37], COSPI[16], bf0[58]);
        bf1[59] = halfBtf(COSPI[48], bf0[36], COSPI[16], bf0[59]);
        bf1[60] = bf0[60];
        bf1[61] = bf0[61];
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];

        bf0 = step;
        bf1 = output;
        bf1[0] = clip((long) bf0[0] + bf0[15]);
        bf1[1] = clip((long) bf0[1] + bf0[14]);
        bf1[2] = clip((long) bf0[2] + bf0[13]);
        bf1[3] = clip((long) bf0[3] + bf0[12]);
        bf1[4] = clip((long) bf0[4] + bf0[11]);
        bf1[5] = clip((long) bf0[5] + bf0[10]);
        bf1[6] = clip((long) bf0[6] + bf0[9]);
        bf1[7] = clip((long) bf0[7] + bf0[8]);
        bf1[8] = clip((long) bf0[7] - bf0[8]);
        bf1[9] = clip((long) bf0[6] - bf0[9]);
        bf1[10] = clip((long) bf0[5] - bf0[10]);
        bf1[11] = clip((long) bf0[4] - bf0[11]);
        bf1[12] = clip((long) bf0[3] - bf0[12]);
        bf1[13] = clip((long) bf0[2] - bf0[13]);
        bf1[14] = clip((long) bf0[1] - bf0[14]);
        bf1[15] = clip((long) bf0[0] - bf0[15]);
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = bf0[18];
        bf1[19] = bf0[19];
        bf1[20] = halfBtf(-COSPI[32], bf0[20], COSPI[32], bf0[27]);
        bf1[21] = halfBtf(-COSPI[32], bf0[21], COSPI[32], bf0[26]);
        bf1[22] = halfBtf(-COSPI[32], bf0[22], COSPI[32], bf0[25]);
        bf1[23] = halfBtf(-COSPI[32], bf0[23], COSPI[32], bf0[24]);
        bf1[24] = halfBtf(COSPI[32], bf0[23], COSPI[32], bf0[24]);
        bf1[25] = halfBtf(COSPI[32], bf0[22], COSPI[32], bf0[25]);
        bf1[26] = halfBtf(COSPI[32], bf0[21], COSPI[32], bf0[26]);
        bf1[27] = halfBtf(COSPI[32], bf0[20], COSPI[32], bf0[27]);
        bf1[28] = bf0[28];
        bf1[29] = bf0[29];
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
        bf1[32] = clip((long) bf0[32] + bf0[47]);
        bf1[33] = clip((long) bf0[33] + bf0[46]);
        bf1[34] = clip((long) bf0[34] + bf0[45]);
        bf1[35] = clip((long) bf0[35] + bf0[44]);
        bf1[36] = clip((long) bf0[36] + bf0[43]);
        bf1[37] = clip((long) bf0[37] + bf0[42]);
        bf1[38] = clip((long) bf0[38] + bf0[41]);
        bf1[39] = clip((long) bf0[39] + bf0[40]);
        bf1[40] = clip((long) bf0[39] - bf0[40]);
        bf1[41] = clip((long) bf0[38] - bf0[41]);
        bf1[42] = clip((long) bf0[37] - bf0[42]);
        bf1[43] = clip((long) bf0[36] - bf0[43]);
        bf1[44] = clip((long) bf0[35] - bf0[44]);
        bf1[45] = clip((long) bf0[34] - bf0[45]);
        bf1[46] = clip((long) bf0[33] - bf0[46]);
        bf1[47] = clip((long) bf0[32] - bf0[47]);
        bf1[48] = clip(-(long) bf0[48] + bf0[63]);
        bf1[49] = clip(-(long) bf0[49] + bf0[62]);
        bf1[50] = clip(-(long) bf0[50] + bf0[61]);
        bf1[51] = clip(-(long) bf0[51] + bf0[60]);
        bf1[52] = clip(-(long) bf0[52] + bf0[59]);
        bf1[53] = clip(-(long) bf0[53] + bf0[58]);
        bf1[54] = clip(-(long) bf0[54] + bf0[57]);
        bf1[55] = clip(-(long) bf0[55] + bf0[56]);
        bf1[56] = clip((long) bf0[55] + bf0[56]);
        bf1[57] = clip((long) bf0[54] + bf0[57]);
        bf1[58] = clip((long) bf0[53] + bf0[58]);
        bf1[59] = clip((long) bf0[52] + bf0[59]);
        bf1[60] = clip((long) bf0[51] + bf0[60]);
        bf1[61] = clip((long) bf0[50] + bf0[61]);
        bf1[62] = clip((long) bf0[49] + bf0[62]);
        bf1[63] = clip((long) bf0[48] + bf0[63]);

        bf0 = output;
        bf1 = step;
        bf1[0] = clip((long) bf0[0] + bf0[31]);
        bf1[1] = clip((long) bf0[1] + bf0[30]);
        bf1[2] = clip((long) bf0[2] + bf0[29]);
        bf1[3] = clip((long) bf0[3] + bf0[28]);
        bf1[4] = clip((long) bf0[4] + bf0[27]);
        bf1[5] = clip((long) bf0[5] + bf0[26]);
        bf1[6] = clip((long) bf0[6] + bf0[25]);
        bf1[7] = clip((long) bf0[7] + bf0[24]);
        bf1[8] = clip((long) bf0[8] + bf0[23]);
        bf1[9] = clip((long) bf0[9] + bf0[22]);
        bf1[10] = clip((long) bf0[10] + bf0[21]);
        bf1[11] = clip((long) bf0[11] + bf0[20]);
        bf1[12] = clip((long) bf0[12] + bf0[19]);
        bf1[13] = clip((long) bf0[13] + bf0[18]);
        bf1[14] = clip((long) bf0[14] + bf0[17]);
        bf1[15] = clip((long) bf0[15] + bf0[16]);
        bf1[16] = clip((long) bf0[15] - bf0[16]);
        bf1[17] = clip((long) bf0[14] - bf0[17]);
        bf1[18] = clip((long) bf0[13] - bf0[18]);
        bf1[19] = clip((long) bf0[12] - bf0[19]);
        bf1[20] = clip((long) bf0[11] - bf0[20]);
        bf1[21] = clip((long) bf0[10] - bf0[21]);
        bf1[22] = clip((long) bf0[9] - bf0[22]);
        bf1[23] = clip((long) bf0[8] - bf0[23]);
        bf1[24] = clip((long) bf0[7] - bf0[24]);
        bf1[25] = clip((long) bf0[6] - bf0[25]);
        bf1[26] = clip((long) bf0[5] - bf0[26]);
        bf1[27] = clip((long) bf0[4] - bf0[27]);
        bf1[28] = clip((long) bf0[3] - bf0[28]);
        bf1[29] = clip((long) bf0[2] - bf0[29]);
        bf1[30] = clip((long) bf0[1] - bf0[30]);
        bf1[31] = clip((long) bf0[0] - bf0[31]);
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = bf0[34];
        bf1[35] = bf0[35];
        bf1[36] = bf0[36];
        bf1[37] = bf0[37];
        bf1[38] = bf0[38];
        bf1[39] = bf0[39];
        bf1[40] = halfBtf(-COSPI[32], bf0[40], COSPI[32], bf0[55]);
        bf1[41] = halfBtf(-COSPI[32], bf0[41], COSPI[32], bf0[54]);
        bf1[42] = halfBtf(-COSPI[32], bf0[42], COSPI[32], bf0[53]);
        bf1[43] = halfBtf(-COSPI[32], bf0[43], COSPI[32], bf0[52]);
        bf1[44] = halfBtf(-COSPI[32], bf0[44], COSPI[32], bf0[51]);
        bf1[45] = halfBtf(-COSPI[32], bf0[45], COSPI[32], bf0[50]);
        bf1[46] = halfBtf(-COSPI[32], bf0[46], COSPI[32], bf0[49]);
        bf1[47] = halfBtf(-COSPI[32], bf0[47], COSPI[32], bf0[48]);
        bf1[48] = halfBtf(COSPI[32], bf0[47], COSPI[32], bf0[48]);
        bf1[49] = halfBtf(COSPI[32], bf0[46], COSPI[32], bf0[49]);
        bf1[50] = halfBtf(COSPI[32], bf0[45], COSPI[32], bf0[50]);
        bf1[51] = halfBtf(COSPI[32], bf0[44], COSPI[32], bf0[51]);
        bf1[52] = halfBtf(COSPI[32], bf0[43], COSPI[32], bf0[52]);
        bf1[53] = halfBtf(COSPI[32], bf0[42], COSPI[32], bf0[53]);
        bf1[54] = halfBtf(COSPI[32], bf0[41], COSPI[32], bf0[54]);
        bf1[55] = halfBtf(COSPI[32], bf0[40], COSPI[32], bf0[55]);
        bf1[56] = bf0[56];
        bf1[57] = bf0[57];
        bf1[58] = bf0[58];
        bf1[59] = bf0[59];
        bf1[60] = bf0[60];
        bf1[61] = bf0[61];
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];

        bf0 = step;
        bf1 = output;
        bf1[0] = clip((long) bf0[0] + bf0[63]);
        bf1[1] = clip((long) bf0[1] + bf0[62]);
        bf1[2] = clip((long) bf0[2] + bf0[61]);
        bf1[3] = clip((long) bf0[3] + bf0[60]);
        bf1[4] = clip((long) bf0[4] + bf0[59]);
        bf1[5] = clip((long) bf0[5] + bf0[58]);
        bf1[6] = clip((long) bf0[6] + bf0[57]);
        bf1[7] = clip((long) bf0[7] + bf0[56]);
        bf1[8] = clip((long) bf0[8] + bf0[55]);
        bf1[9] = clip((long) bf0[9] + bf0[54]);
        bf1[10] = clip((long) bf0[10] + bf0[53]);
        bf1[11] = clip((long) bf0[11] + bf0[52]);
        bf1[12] = clip((long) bf0[12] + bf0[51]);
        bf1[13] = clip((long) bf0[13] + bf0[50]);
        bf1[14] = clip((long) bf0[14] + bf0[49]);
        bf1[15] = clip((long) bf0[15] + bf0[48]);
        bf1[16] = clip((long) bf0[16] + bf0[47]);
        bf1[17] = clip((long) bf0[17] + bf0[46]);
        bf1[18] = clip((long) bf0[18] + bf0[45]);
        bf1[19] = clip((long) bf0[19] + bf0[44]);
        bf1[20] = clip((long) bf0[20] + bf0[43]);
        bf1[21] = clip((long) bf0[21] + bf0[42]);
        bf1[22] = clip((long) bf0[22] + bf0[41]);
        bf1[23] = clip((long) bf0[23] + bf0[40]);
        bf1[24] = clip((long) bf0[24] + bf0[39]);
        bf1[25] = clip((long) bf0[25] + bf0[38]);
        bf1[26] = clip((long) bf0[26] + bf0[37]);
        bf1[27] = clip((long) bf0[27] + bf0[36]);
        bf1[28] = clip((long) bf0[28] + bf0[35]);
        bf1[29] = clip((long) bf0[29] + bf0[34]);
        bf1[30] = clip((long) bf0[30] + bf0[33]);
        bf1[31] = clip((long) bf0[31] + bf0[32]);
        bf1[32] = clip((long) bf0[31] - bf0[32]);
        bf1[33] = clip((long) bf0[30] - bf0[33]);
        bf1[34] = clip((long) bf0[29] - bf0[34]);
        bf1[35] = clip((long) bf0[28] - bf0[35]);
        bf1[36] = clip((long) bf0[27] - bf0[36]);
        bf1[37] = clip((long) bf0[26] - bf0[37]);
        bf1[38] = clip((long) bf0[25] - bf0[38]);
        bf1[39] = clip((long) bf0[24] - bf0[39]);
        bf1[40] = clip((long) bf0[23] - bf0[40]);
        bf1[41] = clip((long) bf0[22] - bf0[41]);
        bf1[42] = clip((long) bf0[21] - bf0[42]);
        bf1[43] = clip((long) bf0[20] - bf0[43]);
        bf1[44] = clip((long) bf0[19] - bf0[44]);
        bf1[45] = clip((long) bf0[18] - bf0[45]);
        bf1[46] = clip((long) bf0[17] - bf0[46]);
        bf1[47] = clip((long) bf0[16] - bf0[47]);
        bf1[48] = clip((long) bf0[15] - bf0[48]);
        bf1[49] = clip((long) bf0[14] - bf0[49]);
        bf1[50] = clip((long) bf0[13] - bf0[50]);
        bf1[51] = clip((long) bf0[12] - bf0[51]);
        bf1[52] = clip((long) bf0[11] - bf0[52]);
        bf1[53] = clip((long) bf0[10] - bf0[53]);
        bf1[54] = clip((long) bf0[9] - bf0[54]);
        bf1[55] = clip((long) bf0[8] - bf0[55]);
        bf1[56] = clip((long) bf0[7] - bf0[56]);
        bf1[57] = clip((long) bf0[6] - bf0[57]);
        bf1[58] = clip((long) bf0[5] - bf0[58]);
        bf1[59] = clip((long) bf0[4] - bf0[59]);
        bf1[60] = clip((long) bf0[3] - bf0[60]);
        bf1[61] = clip((long) bf0[2] - bf0[61]);
        bf1[62] = clip((long) bf0[1] - bf0[62]);
        bf1[63] = clip((long) bf0[0] - bf0[63]);
    }

    /// Reconstructs one supported one-dimensional inverse ADST vector.
    ///
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private void inverseAdst(int[] input, int[] output, int length) {
        switch (length) {
            case 4 -> inverseAdst4(input, output);
            case 8 -> inverseAdst8(input, output);
            case 16 -> inverseAdst16(input, output);
            default -> throw new IllegalStateException("Unsupported inverse ADST length: " + length);
        }
    }

    /// Reconstructs one supported one-dimensional inverse FLIPADST vector.
    ///
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private void inverseFlipAdst(int[] input, int[] output, int length) {
        int[] scratch = reconstructionWorkspace.flipAdstScratch();
        inverseAdst(input, scratch, length);
        for (int i = 0; i < length; i++) {
            output[i] = scratch[length - 1 - i];
        }
    }

    /// Reconstructs one one-dimensional `ADST_4` vector.
    ///
    /// The arithmetic matches the AOM `av1_iadst4(..., cos_bit = 12)` path.
    ///
    /// @param input the dequantized `ADST_4` input vector
    /// @param output the reconstructed output vector
    private void inverseAdst4(int[] input, int[] output) {
        int x0 = input[0];
        int x1 = input[1];
        int x2 = input[2];
        int x3 = input[3];

        if ((x0 | x1 | x2 | x3) == 0) {
            output[0] = 0;
            output[1] = 0;
            output[2] = 0;
            output[3] = 0;
            return;
        }

        long s0 = 1321L * x0;
        long s1 = 2482L * x0;
        long s2 = 3344L * x1;
        long s3 = 3803L * x2;
        long s4 = 1321L * x2;
        long s5 = 2482L * x3;
        long s6 = 3803L * x3;
        int s7 = saturatedInt((long) (x0 - x2) + x3);

        s0 += s3;
        s1 -= s4;
        s3 = s2;
        s2 = 3344L * s7;

        s0 += s5;
        s1 -= s6;

        long y0 = s0 + s3;
        long y1 = s1 + s3;
        long y2 = s2;
        long y3 = s0 + s1 - s3;

        output[0] = positiveRoundShift(y0, 12);
        output[1] = positiveRoundShift(y1, 12);
        output[2] = positiveRoundShift(y2, 12);
        output[3] = positiveRoundShift(y3, 12);
    }

    /// Reconstructs one one-dimensional `ADST_8` vector.
    ///
    /// The arithmetic matches the AOM `av1_iadst8(..., cos_bit = 12)` path.
    ///
    /// @param input the dequantized `ADST_8` input vector
    /// @param output the reconstructed output vector
    private void inverseAdst8(int[] input, int[] output) {
        int b0 = input[7];
        int b1 = input[0];
        int b2 = input[5];
        int b3 = input[2];
        int b4 = input[3];
        int b5 = input[4];
        int b6 = input[1];
        int b7 = input[6];

        int s0 = halfBtf(COSPI[4], b0, COSPI[60], b1);
        int s1 = halfBtf(COSPI[60], b0, -COSPI[4], b1);
        int s2 = halfBtf(COSPI[20], b2, COSPI[44], b3);
        int s3 = halfBtf(COSPI[44], b2, -COSPI[20], b3);
        int s4 = halfBtf(COSPI[36], b4, COSPI[28], b5);
        int s5 = halfBtf(COSPI[28], b4, -COSPI[36], b5);
        int s6 = halfBtf(COSPI[52], b6, COSPI[12], b7);
        int s7 = halfBtf(COSPI[12], b6, -COSPI[52], b7);

        int t0 = clip((long) s0 + s4);
        int t1 = clip((long) s1 + s5);
        int t2 = clip((long) s2 + s6);
        int t3 = clip((long) s3 + s7);
        int t4 = clip((long) s0 - s4);
        int t5 = clip((long) s1 - s5);
        int t6 = clip((long) s2 - s6);
        int t7 = clip((long) s3 - s7);

        int u4 = halfBtf(COSPI[16], t4, COSPI[48], t5);
        int u5 = halfBtf(COSPI[48], t4, -COSPI[16], t5);
        int u6 = halfBtf(-COSPI[48], t6, COSPI[16], t7);
        int u7 = halfBtf(COSPI[16], t6, COSPI[48], t7);

        int v0 = clip((long) t0 + t2);
        int v1 = clip((long) t1 + t3);
        int v2 = clip((long) t0 - t2);
        int v3 = clip((long) t1 - t3);
        int v4 = clip((long) u4 + u6);
        int v5 = clip((long) u5 + u7);
        int v6 = clip((long) u4 - u6);
        int v7 = clip((long) u5 - u7);

        int w2 = halfBtf(COSPI[32], v2, COSPI[32], v3);
        int w3 = halfBtf(COSPI[32], v2, -COSPI[32], v3);
        int w6 = halfBtf(COSPI[32], v6, COSPI[32], v7);
        int w7 = halfBtf(COSPI[32], v6, -COSPI[32], v7);

        output[0] = v0;
        output[1] = clip(-(long) v4);
        output[2] = w6;
        output[3] = clip(-(long) w2);
        output[4] = w3;
        output[5] = clip(-(long) w7);
        output[6] = v5;
        output[7] = clip(-(long) v1);
    }

    /// Reconstructs one one-dimensional `ADST_16` vector.
    ///
    /// @param input the dequantized `ADST_16` input vector
    /// @param output the reconstructed output vector
    private void inverseAdst16(int[] input, int[] output) {
        int in0 = input[0];
        int in1 = input[1];
        int in2 = input[2];
        int in3 = input[3];
        int in4 = input[4];
        int in5 = input[5];
        int in6 = input[6];
        int in7 = input[7];
        int in8 = input[8];
        int in9 = input[9];
        int in10 = input[10];
        int in11 = input[11];
        int in12 = input[12];
        int in13 = input[13];
        int in14 = input[14];
        int in15 = input[15];

        int t0 = positiveRoundShift((long) in15 * (4091 - 4096) + (long) in0 * 201, 12) + in15;
        int t1 = positiveRoundShift((long) in15 * 201 - (long) in0 * (4091 - 4096), 12) - in0;
        int t2 = positiveRoundShift((long) in13 * (3973 - 4096) + (long) in2 * 995, 12) + in13;
        int t3 = positiveRoundShift((long) in13 * 995 - (long) in2 * (3973 - 4096), 12) - in2;
        int t4 = positiveRoundShift((long) in11 * (3703 - 4096) + (long) in4 * 1751, 12) + in11;
        int t5 = positiveRoundShift((long) in11 * 1751 - (long) in4 * (3703 - 4096), 12) - in4;
        int t6 = positiveRoundShift((long) in9 * 1645 + (long) in6 * 1220, 11);
        int t7 = positiveRoundShift((long) in9 * 1220 - (long) in6 * 1645, 11);
        int t8 = positiveRoundShift((long) in7 * 2751 + (long) in8 * (3035 - 4096), 12) + in8;
        int t9 = positiveRoundShift((long) in7 * (3035 - 4096) - (long) in8 * 2751, 12) + in7;
        int t10 = positiveRoundShift((long) in5 * 2106 + (long) in10 * (3513 - 4096), 12) + in10;
        int t11 = positiveRoundShift((long) in5 * (3513 - 4096) - (long) in10 * 2106, 12) + in5;
        int t12 = positiveRoundShift((long) in3 * 1380 + (long) in12 * (3857 - 4096), 12) + in12;
        int t13 = positiveRoundShift((long) in3 * (3857 - 4096) - (long) in12 * 1380, 12) + in3;
        int t14 = positiveRoundShift((long) in1 * 601 + (long) in14 * (4052 - 4096), 12) + in14;
        int t15 = positiveRoundShift((long) in1 * (4052 - 4096) - (long) in14 * 601, 12) + in1;

        int t0a = clip((long) t0 + t8);
        int t1a = clip((long) t1 + t9);
        int t2a = clip((long) t2 + t10);
        int t3a = clip((long) t3 + t11);
        int t4a = clip((long) t4 + t12);
        int t5a = clip((long) t5 + t13);
        int t6a = clip((long) t6 + t14);
        int t7a = clip((long) t7 + t15);
        int t8a = clip((long) t0 - t8);
        int t9a = clip((long) t1 - t9);
        int t10a = clip((long) t2 - t10);
        int t11a = clip((long) t3 - t11);
        int t12a = clip((long) t4 - t12);
        int t13a = clip((long) t5 - t13);
        int t14a = clip((long) t6 - t14);
        int t15a = clip((long) t7 - t15);

        t8 = positiveRoundShift((long) t8a * (4017 - 4096) + (long) t9a * 799, 12) + t8a;
        t9 = positiveRoundShift((long) t8a * 799 - (long) t9a * (4017 - 4096), 12) - t9a;
        t10 = positiveRoundShift((long) t10a * 2276 + (long) t11a * (3406 - 4096), 12) + t11a;
        t11 = positiveRoundShift((long) t10a * (3406 - 4096) - (long) t11a * 2276, 12) + t10a;
        t12 = positiveRoundShift((long) t13a * (4017 - 4096) - (long) t12a * 799, 12) + t13a;
        t13 = positiveRoundShift((long) t13a * 799 + (long) t12a * (4017 - 4096), 12) + t12a;
        t14 = positiveRoundShift((long) t15a * 2276 - (long) t14a * (3406 - 4096), 12) - t14a;
        t15 = positiveRoundShift((long) t15a * (3406 - 4096) + (long) t14a * 2276, 12) + t15a;

        t0 = clip((long) t0a + t4a);
        t1 = clip((long) t1a + t5a);
        t2 = clip((long) t2a + t6a);
        t3 = clip((long) t3a + t7a);
        t4 = clip((long) t0a - t4a);
        t5 = clip((long) t1a - t5a);
        t6 = clip((long) t2a - t6a);
        t7 = clip((long) t3a - t7a);
        t8a = clip((long) t8 + t12);
        t9a = clip((long) t9 + t13);
        t10a = clip((long) t10 + t14);
        t11a = clip((long) t11 + t15);
        t12a = clip((long) t8 - t12);
        t13a = clip((long) t9 - t13);
        t14a = clip((long) t10 - t14);
        t15a = clip((long) t11 - t15);

        t4a = positiveRoundShift((long) t4 * (3784 - 4096) + (long) t5 * 1567, 12) + t4;
        t5a = positiveRoundShift((long) t4 * 1567 - (long) t5 * (3784 - 4096), 12) - t5;
        t6a = positiveRoundShift((long) t7 * (3784 - 4096) - (long) t6 * 1567, 12) + t7;
        t7a = positiveRoundShift((long) t7 * 1567 + (long) t6 * (3784 - 4096), 12) + t6;
        t12 = positiveRoundShift((long) t12a * (3784 - 4096) + (long) t13a * 1567, 12) + t12a;
        t13 = positiveRoundShift((long) t12a * 1567 - (long) t13a * (3784 - 4096), 12) - t13a;
        t14 = positiveRoundShift((long) t15a * (3784 - 4096) - (long) t14a * 1567, 12) + t15a;
        t15 = positiveRoundShift((long) t15a * 1567 + (long) t14a * (3784 - 4096), 12) + t14a;

        output[0] = clip((long) t0 + t2);
        output[15] = clip(-(long) clip((long) t1 + t3));
        t2a = clip((long) t0 - t2);
        t3a = clip((long) t1 - t3);
        output[3] = clip(-(long) clip((long) t4a + t6a));
        output[12] = clip((long) t5a + t7a);
        t6 = clip((long) t4a - t6a);
        t7 = clip((long) t5a - t7a);
        output[1] = clip(-(long) clip((long) t8a + t10a));
        output[14] = clip((long) t9a + t11a);
        t10 = clip((long) t8a - t10a);
        t11 = clip((long) t9a - t11a);
        output[2] = clip((long) t12 + t14);
        output[13] = clip(-(long) clip((long) t13 + t15));
        t14a = clip((long) t12 - t14);
        t15a = clip((long) t13 - t15);

        output[7] = clip(-(long) positiveRoundShift((long) (t2a + t3a) * 181, 8));
        output[8] = positiveRoundShift((long) (t2a - t3a) * 181, 8);
        output[4] = positiveRoundShift((long) (t6 + t7) * 181, 8);
        output[11] = clip(-(long) positiveRoundShift((long) (t6 - t7) * 181, 8));
        output[6] = positiveRoundShift((long) (t10 + t11) * 181, 8);
        output[9] = clip(-(long) positiveRoundShift((long) (t10 - t11) * 181, 8));
        output[5] = clip(-(long) positiveRoundShift((long) (t14a + t15a) * 181, 8));
        output[10] = positiveRoundShift((long) (t14a - t15a) * 181, 8);
    }

    /// Reconstructs one supported one-dimensional inverse identity vector.
    ///
    /// Identity kernels scale their inputs without a stage-local clamp. The surrounding
    /// two-dimensional transform applies the required input and inter-pass clipping.
    ///
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private void inverseIdentity(int[] input, int[] output, int length) {
        switch (length) {
            case 4 -> {
                for (int i = 0; i < 4; i++) {
                    int value = input[i];
                    output[i] = saturatedInt((long) value + positiveRoundShift((long) value * 1697, 12));
                }
            }
            case 8 -> {
                for (int i = 0; i < 8; i++) {
                    output[i] = saturatedInt((long) input[i] * 2);
                }
            }
            case 16 -> {
                for (int i = 0; i < 16; i++) {
                    int value = input[i];
                    output[i] = saturatedInt((long) value * 2 + positiveRoundShift((long) value * 1697, 11));
                }
            }
            case 32 -> {
                for (int i = 0; i < 32; i++) {
                    output[i] = saturatedInt((long) input[i] * 4);
                }
            }
            default -> throw new IllegalStateException("Unsupported inverse identity length: " + length);
        }
    }

    /// Applies one AV1 half-butterfly operation with inverse-transform rounding.
    ///
    /// @param weight0 the first cosine weight
    /// @param value0 the first source value
    /// @param weight1 the second cosine weight
    /// @param value1 the second source value
    /// @return the rounded half-butterfly result
    private int halfBtf(int weight0, int value0, int weight1, int value1) {
        return positiveRoundShift((long) weight0 * value0 + (long) weight1 * value1, INV_COS_BIT);
    }

    /// Applies the positive-bias arithmetic shifts used by `dav1d` inverse-transform kernels.
    ///
    /// @param value the signed value to shift
    /// @param bitCount the positive number of bits to shift away, or `0`
    /// @return the rounded signed result
    private int positiveRoundShift(long value, int bitCount) {
        if (bitCount < 0) {
            throw new IllegalArgumentException("bitCount < 0: " + bitCount);
        }
        if (bitCount == 0) {
            return saturatedInt(value);
        }
        return saturatedInt((value + (1L << (bitCount - 1))) >> bitCount);
    }

    /// Saturates one `long` into the signed `int` range.
    ///
    /// @param value the value to saturate
    /// @return the saturated `int`
    private int saturatedInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    /// Saturates one intermediate transform value into the signed `int` range.
    ///
    /// @param value the intermediate transform value
    /// @return the saturated `int`
    private int clip(long value) {
        // Every supported stage range contains the signed 16-bit domain. Most transform
        // intermediates stay inside it, so avoid consulting the active stage state there.
        // A narrowing round trip checks both signed bounds with one comparison.
        if ((short) value == value) {
            return (int) value;
        }
        return clipToRange(value, activeClipRange);
    }

    /// Clamps one intermediate value into one explicit inverse-transform clip range.
    ///
    /// @param value the value to validate and clamp
    /// @param clipRange the active clip range
    /// @return the clamped value
    private int clipToRange(long value, ClipRange clipRange) {
        if (clipRange.rejectOutOfRange()
                && (value < clipRange.minimum() || value > clipRange.maximum())) {
            throw new InvalidFrameReconstructionException(
                    "Inverse-transform intermediate value exceeds its conformance range"
            );
        }
        return Math.max(
                clipRange.minimum(),
                Math.min(clipRange.maximum(), saturatedInt(value))
        );
    }

    /// Returns a clip range with strict overflow rejection enabled when requested.
    ///
    /// @param clipRange the base normative clip range
    /// @param strictStdCompliance whether out-of-range values must be rejected
    /// @return the base range or an equivalent strict conformance range
    private ClipRange conformanceClipRange(ClipRange clipRange, boolean strictStdCompliance) {
        return strictStdCompliance
                ? new ClipRange(clipRange.minimum(), clipRange.maximum(), true)
                : clipRange;
    }

    /// Runs one exact inverse-transform stage while overriding the active clip range.
    ///
    /// @param clipRange the temporary clip range
    /// @param action the stage body to execute
    private void withActiveClipRange(ClipRange clipRange, Runnable action) {
        ClipRange previous = activeClipRange;
        activeClipRange = clipRange;
        try {
            action.run();
        } finally {
            activeClipRange = previous;
        }
    }

    /// Returns the dav1d row-pass clip range for one supported decoded bit depth.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @return the stage-local row-pass clip range
    private ClipRange rowClipRange(int bitDepth) {
        return switch (bitDepth) {
            case 8 -> ROW_CLIP_RANGE_8;
            case 10 -> ROW_CLIP_RANGE_10;
            case 12 -> ROW_CLIP_RANGE_12;
            default -> throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        };
    }

    /// Returns the dav1d column-domain clip range for one supported decoded bit depth.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @return the stage-local column-domain clip range
    private ClipRange columnClipRange(int bitDepth) {
        return switch (bitDepth) {
            case 8 -> COLUMN_CLIP_RANGE_8;
            case 10 -> COLUMN_CLIP_RANGE_10;
            case 12 -> COLUMN_CLIP_RANGE_12;
            default -> throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        };
    }

    /// Returns the transform area in samples.
    ///
    /// @param transformSize the transform size to validate
    /// @return the transform area in samples
    private static int checkedTransformArea(TransformSize transformSize) {
        return transformSize.widthPixels() * transformSize.heightPixels();
    }

    /// Reusable transformer-owned storage for dequantization and inverse transforms.
    ///
    /// Arrays are allocated lazily per transform size and remain owned by the current decode
    /// transformer. Callers must consume their contents before beginning another residual operation.
    
    private static final class Workspace {
        /// Exact-length dequantized-coefficient buffers indexed by transform-size ordinal.
        private final int [][] coefficientBuffers = new int[TransformSize.values().length][];

        /// Exact-length intermediate transform buffers indexed by transform-size ordinal.
        private final int [][] intermediateBuffers = new int[TransformSize.values().length][];

        /// Exact-length residual output buffers indexed by transform-size ordinal.
        private final int [][] outputBuffers = new int[TransformSize.values().length][];

        /// Shared input vector for one-dimensional transform passes.
        private final int[] scratchInput = new int[64];

        /// Shared output vector for one-dimensional transform passes.
        private final int[] scratchOutput = new int[64];

        /// Shared staging vector for non-recursive AOM inverse-DCT kernels.
        private final int[] kernelStep = new int[64];

        /// Four-sample even-input vector used by the recursive dav1d inverse DCT.
        private final int[] evenInput4 = new int[4];

        /// Four-sample even-output vector used by the recursive dav1d inverse DCT.
        private final int[] evenOutput4 = new int[4];

        /// Eight-sample even-input vector used by the recursive dav1d inverse DCT.
        private final int[] evenInput8 = new int[8];

        /// Eight-sample even-output vector used by the recursive dav1d inverse DCT.
        private final int[] evenOutput8 = new int[8];

        /// Sixteen-sample even-input vector used by the recursive dav1d inverse DCT.
        private final int[] evenInput16 = new int[16];

        /// Sixteen-sample even-output vector used by the recursive dav1d inverse DCT.
        private final int[] evenOutput16 = new int[16];

        /// Shared output vector used before reversing one FLIPADST result.
        private final int[] flipAdstScratch = new int[16];

        /// Creates an initially empty reusable workspace.
        private Workspace() {
        }

        /// Returns an exact-length coefficient buffer for one transform size.
        ///
        /// @param transformSize the transform size that determines the buffer length
        /// @return the reusable coefficient buffer
        int[] coefficientBuffer(TransformSize transformSize) {
            return buffer(coefficientBuffers, transformSize);
        }

        /// Returns an exact-length intermediate buffer for one transform size.
        ///
        /// @param transformSize the transform size that determines the buffer length
        /// @return the reusable intermediate buffer
        private int[] intermediateBuffer(TransformSize transformSize) {
            return buffer(intermediateBuffers, transformSize);
        }

        /// Returns an exact-length output buffer for one transform size.
        ///
        /// @param transformSize the transform size that determines the buffer length
        /// @return the reusable output buffer
        private int[] outputBuffer(TransformSize transformSize) {
            return buffer(outputBuffers, transformSize);
        }

        /// Returns the shared input scratch vector after validating the requested length.
        ///
        /// @param length the required vector length
        /// @return the shared input scratch vector
        private int[] scratchInput(int length) {
            requireScratchLength(length);
            return scratchInput;
        }

        /// Returns the shared output scratch vector after validating the requested length.
        ///
        /// @param length the required vector length
        /// @return the shared output scratch vector
        private int[] scratchOutput(int length) {
            requireScratchLength(length);
            return scratchOutput;
        }

        /// Returns the shared staging vector for one non-recursive AOM inverse-DCT kernel.
        ///
        /// @return the shared 64-sample staging vector
        private int[] kernelStep() {
            return kernelStep;
        }

        /// Returns the four-sample recursive even-input vector.
        ///
        /// @return the shared four-sample input vector
        private int[] evenInput4() {
            return evenInput4;
        }

        /// Returns the four-sample recursive even-output vector.
        ///
        /// @return the shared four-sample output vector
        private int[] evenOutput4() {
            return evenOutput4;
        }

        /// Returns the eight-sample recursive even-input vector.
        ///
        /// @return the shared eight-sample input vector
        private int[] evenInput8() {
            return evenInput8;
        }

        /// Returns the eight-sample recursive even-output vector.
        ///
        /// @return the shared eight-sample output vector
        private int[] evenOutput8() {
            return evenOutput8;
        }

        /// Returns the sixteen-sample recursive even-input vector.
        ///
        /// @return the shared sixteen-sample input vector
        private int[] evenInput16() {
            return evenInput16;
        }

        /// Returns the sixteen-sample recursive even-output vector.
        ///
        /// @return the shared sixteen-sample output vector
        private int[] evenOutput16() {
            return evenOutput16;
        }

        /// Returns the shared FLIPADST reversal vector.
        ///
        /// @return the shared sixteen-sample reversal vector
        private int[] flipAdstScratch() {
            return flipAdstScratch;
        }

        /// Returns one lazily allocated exact-length transform buffer.
        ///
        /// @param buffers the per-transform-size buffer table
        /// @param transformSize the transform size that selects the buffer
        /// @return the selected reusable buffer
        private static int[] buffer(int [][] buffers, TransformSize transformSize) {
            TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
            int index = nonNullTransformSize.ordinal();
            int [] buffer = buffers[index];
            if (buffer == null) {
                buffer = new int[checkedTransformArea(nonNullTransformSize)];
                buffers[index] = buffer;
            }
            return buffer;
        }

        /// Validates one requested scratch-vector length.
        ///
        /// @param length the requested vector length
        private static void requireScratchLength(int length) {
            if (length < 0 || length > 64) {
                throw new IllegalArgumentException("Unsupported scratch length: " + length);
            }
        }
    }

    /// One clip range used by the exact inverse-transform kernels.
    ///
    /// @param minimum the inclusive minimum value
    /// @param maximum the inclusive maximum value
    /// @param rejectOutOfRange whether values outside the range must be rejected instead of clamped
    private record ClipRange(int minimum, int maximum, boolean rejectOutOfRange) {
    }

}
