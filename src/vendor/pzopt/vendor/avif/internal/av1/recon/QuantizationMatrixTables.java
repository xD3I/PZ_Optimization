// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.model.TransformSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/// AV1 quantization matrix tables derived from dav1d's `qm.c` base tables.
///
/// The embedded payload stores dav1d's `qm_tbl_32x16` and triangular `qm_tbl_32x32_t` base tables.
/// Smaller rectangular tables are generated during class initialization with the same subsampling,
/// transpose, and untriangle steps as dav1d, but exposed in natural raster coefficient order because
/// this decoder stores transform coefficients without dav1d's internal transposition.

final class QuantizationMatrixTables {
    /// The number of AV1 matrix indices that resolve to concrete tables.
    private static final int CONCRETE_MATRIX_INDEX_COUNT = 15;

    /// The AV1 matrix index that disables quantization matrix scaling.
    private static final int DISABLED_MATRIX_INDEX = 15;

    /// The number of plane matrix classes: luma and chroma.
    private static final int PLANE_CLASS_COUNT = 2;

    /// The luma quantization matrix class index.
    private static final int LUMA_PLANE_CLASS = 0;

    /// The chroma quantization matrix class index.
    private static final int CHROMA_PLANE_CLASS = 1;

    /// The byte count of all 32x16 base matrix tables.
    private static final int BASE_32X16_BYTE_COUNT = CONCRETE_MATRIX_INDEX_COUNT * PLANE_CLASS_COUNT * 512;

    /// The byte count of all triangular 32x32 base matrix tables.
    private static final int BASE_32X32_TRIANGULAR_BYTE_COUNT = CONCRETE_MATRIX_INDEX_COUNT * PLANE_CLASS_COUNT * 528;

    /// The compressed dav1d base quantization matrix payload.
    private static final String BASE_TABLES_GZIP_BASE64 = """
            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
            """;

    /// The generated natural-order quantization matrices.
    private static final byte [] [] [] [] MATRICES =
            createMatrices();

    /// Prevents instantiation of this utility class.
    private QuantizationMatrixTables() {
    }

    /// Returns one generated quantization matrix in natural coefficient order, or `null` when disabled.
    ///
    /// @param matrixIndex the frame-signaled matrix index in `[0, 15]`
    /// @param chroma whether the matrix is for a chroma plane
    /// @param transformSize the active transform size
    /// @return the natural-order matrix, or `null` when the index disables matrix scaling
    static byte [] matrix(int matrixIndex, boolean chroma, TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (matrixIndex < 0 || matrixIndex > DISABLED_MATRIX_INDEX) {
            throw new IllegalArgumentException("Quantization matrix index out of range: " + matrixIndex);
        }
        if (matrixIndex == DISABLED_MATRIX_INDEX) {
            return null;
        }
        return MATRICES[matrixIndex][chroma ? CHROMA_PLANE_CLASS : LUMA_PLANE_CLASS][nonNullTransformSize.ordinal()];
    }

    /// Returns the matrix width used for one transform size.
    ///
    /// @param transformSize the active transform size
    /// @return the natural-order matrix width in coefficients
    static int matrixWidth(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return Math.min(nonNullTransformSize.widthPixels(), 32);
    }

    /// Returns the matrix height used for one transform size.
    ///
    /// @param transformSize the active transform size
    /// @return the natural-order matrix height in coefficients
    static int matrixHeight(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return Math.min(nonNullTransformSize.heightPixels(), 32);
    }

    /// Creates the complete natural-order matrix table from the embedded dav1d base payload.
    ///
    /// @return the generated natural-order matrix table
    private static byte[][][][] createMatrices() {
        byte[] baseTables = decodeBaseTables();
        byte[][][][] matrices = new byte[CONCRETE_MATRIX_INDEX_COUNT][PLANE_CLASS_COUNT][TransformSize.values().length][];
        for (int matrixIndex = 0; matrixIndex < CONCRETE_MATRIX_INDEX_COUNT; matrixIndex++) {
            for (int planeClass = 0; planeClass < PLANE_CLASS_COUNT; planeClass++) {
                int baseIndex = matrixIndex * PLANE_CLASS_COUNT + planeClass;
                byte[] base32x16 = copyRange(baseTables, baseIndex * 512, 512);
                byte[] base32x32Triangular = copyRange(
                        baseTables,
                        BASE_32X16_BYTE_COUNT + baseIndex * 528,
                        528
                );
                populateMatrices(matrices[matrixIndex][planeClass], base32x16, untriangle(base32x32Triangular));
            }
        }
        return matrices;
    }

    /// Populates all transform-size entries for one matrix index and plane class.
    ///
    /// @param destination the destination transform-size table
    /// @param matrix32x16 the natural 32x16 base matrix
    /// @param matrix32x32 the natural 32x32 base matrix
    private static void populateMatrices(byte[][] destination, byte[] matrix32x16, byte[] matrix32x32) {
        byte[] matrix4x4 = subsample(matrix32x32, 32 * 3 + 3, 32, 32, 8, 8);
        byte[] matrix8x4 = subsample(matrix32x16, 32 + 1, 32, 16, 4, 4);
        byte[] matrix8x8 = subsample(matrix32x32, 32 + 1, 32, 32, 4, 4);
        byte[] matrix16x4 = subsample(matrix32x16, 32, 32, 16, 2, 4);
        byte[] matrix16x8 = subsample(matrix32x16, 0, 32, 16, 2, 2);
        byte[] matrix16x16 = subsample(matrix32x32, 0, 32, 32, 2, 2);
        byte[] matrix32x8 = subsample(matrix32x16, 0, 32, 16, 1, 2);
        byte[] matrix4x8 = transpose(matrix8x4, 8, 4);
        byte[] matrix4x16 = transpose(matrix16x4, 16, 4);
        byte[] matrix8x16 = transpose(matrix16x8, 16, 8);
        byte[] matrix8x32 = transpose(matrix32x8, 32, 8);
        byte[] matrix16x32 = transpose(matrix32x16, 32, 16);

        destination[TransformSize.TX_4X4.ordinal()] = matrix4x4;
        destination[TransformSize.TX_8X8.ordinal()] = matrix8x8;
        destination[TransformSize.TX_16X16.ordinal()] = matrix16x16;
        destination[TransformSize.TX_32X32.ordinal()] = matrix32x32;
        destination[TransformSize.TX_64X64.ordinal()] = matrix32x32;
        destination[TransformSize.RTX_4X8.ordinal()] = matrix4x8;
        destination[TransformSize.RTX_8X4.ordinal()] = matrix8x4;
        destination[TransformSize.RTX_8X16.ordinal()] = matrix8x16;
        destination[TransformSize.RTX_16X8.ordinal()] = matrix16x8;
        destination[TransformSize.RTX_16X32.ordinal()] = matrix16x32;
        destination[TransformSize.RTX_32X16.ordinal()] = matrix32x16;
        destination[TransformSize.RTX_32X64.ordinal()] = matrix32x32;
        destination[TransformSize.RTX_64X32.ordinal()] = matrix32x32;
        destination[TransformSize.RTX_4X16.ordinal()] = matrix4x16;
        destination[TransformSize.RTX_16X4.ordinal()] = matrix16x4;
        destination[TransformSize.RTX_8X32.ordinal()] = matrix8x32;
        destination[TransformSize.RTX_32X8.ordinal()] = matrix32x8;
        destination[TransformSize.RTX_16X64.ordinal()] = matrix16x32;
        destination[TransformSize.RTX_64X16.ordinal()] = matrix32x16;
    }

    /// Decodes the compressed dav1d base table payload.
    ///
    /// @return the uncompressed base table bytes
    private static byte[] decodeBaseTables() {
        byte[] compressed = Base64.getMimeDecoder().decode(BASE_TABLES_GZIP_BASE64);
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     BASE_32X16_BYTE_COUNT + BASE_32X32_TRIANGULAR_BYTE_COUNT
             )) {
            input.transferTo(output);
            byte[] baseTables = output.toByteArray();
            int expectedLength = BASE_32X16_BYTE_COUNT + BASE_32X32_TRIANGULAR_BYTE_COUNT;
            if (baseTables.length != expectedLength) {
                throw new IllegalStateException(
                        "Decoded quantization matrix payload has length " + baseTables.length
                                + ", expected " + expectedLength
                );
            }
            return baseTables;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /// Expands one triangular 32x32 base table into a natural-order 32x32 matrix.
    ///
    /// @param triangular the triangular source table
    /// @return one natural-order 32x32 matrix
    private static byte[] untriangle(byte[] triangular) {
        byte[] matrix = new byte[32 * 32];
        int sourceRowOffset = 0;
        for (int y = 0; y < 32; y++) {
            System.arraycopy(triangular, sourceRowOffset, matrix, y * 32, y + 1);
            int sourcePointer = sourceRowOffset + y;
            for (int x = y + 1; x < 32; x++) {
                sourcePointer += x;
                matrix[y * 32 + x] = triangular[sourcePointer];
            }
            sourceRowOffset += y + 1;
        }
        return matrix;
    }

    /// Subsamples one 32-column base matrix into a smaller natural-order matrix.
    ///
    /// @param source the source matrix
    /// @param sourceOffset the first source coefficient offset
    /// @param sourceStride the source matrix stride
    /// @param sourceHeight the sampled source height
    /// @param horizontalStep the horizontal sampling step
    /// @param verticalStep the vertical sampling step
    /// @return the subsampled matrix
    private static byte[] subsample(
            byte[] source,
            int sourceOffset,
            int sourceStride,
            int sourceHeight,
            int horizontalStep,
            int verticalStep
    ) {
        byte[] destination = new byte[(32 / horizontalStep) * (sourceHeight / verticalStep)];
        int destinationOffset = 0;
        for (int y = 0; y < sourceHeight; y += verticalStep) {
            for (int x = 0; x < 32; x += horizontalStep) {
                destination[destinationOffset++] = source[sourceOffset + y * sourceStride + x];
            }
        }
        return destination;
    }

    /// Transposes one natural-order matrix.
    ///
    /// @param source the source matrix
    /// @param width the source matrix width
    /// @param height the source matrix height
    /// @return the transposed natural-order matrix
    private static byte[] transpose(byte[] source, int width, int height) {
        byte[] destination = new byte[source.length];
        for (int y = 0; y < height; y++) {
            int sourceRowOffset = y * width;
            for (int x = 0; x < width; x++) {
                destination[x * height + y] = source[sourceRowOffset + x];
            }
        }
        return destination;
    }

    /// Copies one byte range from the uncompressed base payload.
    ///
    /// @param source the source byte array
    /// @param offset the first byte offset
    /// @param length the number of bytes to copy
    /// @return a copied range
    private static byte[] copyRange(byte[] source, int offset, int length) {
        byte[] destination = new byte[length];
        System.arraycopy(source, offset, destination, 0, length);
        return destination;
    }
}
