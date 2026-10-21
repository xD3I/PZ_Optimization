// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;

import pzopt.vendor.avif.Av1ChromaFormat;

/// The AV1 block sizes currently needed by the early partition tree reader.

public enum BlockSize {
    /// A 128x128 block.
    SIZE_128X128(32, 32, 3, 15),
    /// A 128x64 block.
    SIZE_128X64(32, 16, 3, 14),
    /// A 64x128 block.
    SIZE_64X128(16, 32, 3, 13),
    /// A 64x64 block.
    SIZE_64X64(16, 16, 3, 12),
    /// A 64x32 block.
    SIZE_64X32(16, 8, 3, 11),
    /// A 64x16 block.
    SIZE_64X16(16, 4, 2, 21),
    /// A 32x64 block.
    SIZE_32X64(8, 16, 3, 10),
    /// A 32x32 block.
    SIZE_32X32(8, 8, 3, 9),
    /// A 32x16 block.
    SIZE_32X16(8, 4, 2, 8),
    /// A 32x8 block.
    SIZE_32X8(8, 2, 1, 19),
    /// A 16x64 block.
    SIZE_16X64(4, 16, 2, 20),
    /// A 16x32 block.
    SIZE_16X32(4, 8, 2, 7),
    /// A 16x16 block.
    SIZE_16X16(4, 4, 2, 6),
    /// A 16x8 block.
    SIZE_16X8(4, 2, 1, 5),
    /// A 16x4 block.
    SIZE_16X4(4, 1, 0, 17),
    /// A 8x32 block.
    SIZE_8X32(2, 8, 1, 18),
    /// A 8x16 block.
    SIZE_8X16(2, 4, 1, 4),
    /// A 8x8 block.
    SIZE_8X8(2, 2, 1, 3),
    /// A 8x4 block.
    SIZE_8X4(2, 1, 0, 2),
    /// A 4x16 block.
    SIZE_4X16(1, 4, 0, 16),
    /// A 4x8 block.
    SIZE_4X8(1, 2, 0, 1),
    /// A 4x4 block.
    SIZE_4X4(1, 1, 0, 0);

    /// The maximum luma transform sizes in block-size enum order.
    private static final TransformSize [] MAX_LUMA_TRANSFORM_SIZES = {
            TransformSize.TX_64X64,
            TransformSize.TX_64X64,
            TransformSize.TX_64X64,
            TransformSize.TX_64X64,
            TransformSize.RTX_64X32,
            TransformSize.RTX_64X16,
            TransformSize.RTX_32X64,
            TransformSize.TX_32X32,
            TransformSize.RTX_32X16,
            TransformSize.RTX_32X8,
            TransformSize.RTX_16X64,
            TransformSize.RTX_16X32,
            TransformSize.TX_16X16,
            TransformSize.RTX_16X8,
            TransformSize.RTX_16X4,
            TransformSize.RTX_8X32,
            TransformSize.RTX_8X16,
            TransformSize.TX_8X8,
            TransformSize.RTX_8X4,
            TransformSize.RTX_4X16,
            TransformSize.RTX_4X8,
            TransformSize.TX_4X4
    };

    /// The maximum 4:2:0 chroma transform sizes in block-size enum order.
    private static final TransformSize [] MAX_CHROMA_420_TRANSFORM_SIZES = {
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.RTX_32X16,
            TransformSize.RTX_32X8,
            TransformSize.RTX_16X32,
            TransformSize.TX_16X16,
            TransformSize.RTX_16X8,
            TransformSize.RTX_16X4,
            TransformSize.RTX_8X32,
            TransformSize.RTX_8X16,
            TransformSize.TX_8X8,
            TransformSize.RTX_8X4,
            TransformSize.RTX_8X4,
            TransformSize.RTX_4X16,
            TransformSize.RTX_4X8,
            TransformSize.TX_4X4,
            TransformSize.TX_4X4,
            TransformSize.RTX_4X8,
            TransformSize.TX_4X4,
            TransformSize.TX_4X4
    };

    /// The maximum 4:2:2 chroma transform sizes in block-size enum order when `dav1d` exposes one directly.
    private static final TransformSize [] MAX_CHROMA_422_TRANSFORM_SIZES = {
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            null,
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.RTX_32X16,
            null,
            TransformSize.RTX_16X32,
            TransformSize.TX_16X16,
            TransformSize.RTX_16X8,
            null,
            null,
            TransformSize.RTX_8X16,
            TransformSize.TX_8X8,
            TransformSize.RTX_8X4,
            null,
            null,
            TransformSize.RTX_4X8,
            TransformSize.TX_4X4,
            null,
            null,
            TransformSize.TX_4X4
    };

    /// The maximum 4:4:4 chroma transform sizes in block-size enum order.
    private static final TransformSize [] MAX_CHROMA_444_TRANSFORM_SIZES = {
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.RTX_32X16,
            TransformSize.TX_32X32,
            TransformSize.TX_32X32,
            TransformSize.RTX_32X16,
            TransformSize.RTX_32X8,
            TransformSize.RTX_16X32,
            TransformSize.RTX_16X32,
            TransformSize.TX_16X16,
            TransformSize.RTX_16X8,
            TransformSize.RTX_16X4,
            TransformSize.RTX_8X32,
            TransformSize.RTX_8X16,
            TransformSize.TX_8X8,
            TransformSize.RTX_8X4,
            TransformSize.RTX_4X16,
            TransformSize.RTX_4X8,
            TransformSize.TX_4X4
    };

    /// The block width in 4x4 units.
    private final int width4;

    /// The block height in 4x4 units.
    private final int height4;

    /// The Y-mode size context used by `TileSyntaxReader`.
    private final int yModeSizeContext;

    /// The size-dependent entropy-table index matching `dav1d`'s `N_BS_SIZES` order.
    private final int cdfIndex;

    /// Creates one AV1 block size entry.
    ///
    /// @param width4 the block width in 4x4 units
    /// @param height4 the block height in 4x4 units
    /// @param yModeSizeContext the Y-mode size context used by `TileSyntaxReader`
    /// @param cdfIndex the size-dependent entropy-table index matching `dav1d`'s `N_BS_SIZES` order
    BlockSize(int width4, int height4, int yModeSizeContext, int cdfIndex) {
        this.width4 = width4;
        this.height4 = height4;
        this.yModeSizeContext = yModeSizeContext;
        this.cdfIndex = cdfIndex;
    }

    /// Returns the block width in 4x4 units.
    ///
    /// @return the block width in 4x4 units
    public int width4() {
        return width4;
    }

    /// Returns the block height in 4x4 units.
    ///
    /// @return the block height in 4x4 units
    public int height4() {
        return height4;
    }

    /// Returns the block width in pixels.
    ///
    /// @return the block width in pixels
    public int widthPixels() {
        return width4 << 2;
    }

    /// Returns the block height in pixels.
    ///
    /// @return the block height in pixels
    public int heightPixels() {
        return height4 << 2;
    }

    /// Returns the base-2 logarithm of the block width in 4x4 units.
    ///
    /// @return the base-2 logarithm of the block width in 4x4 units
    public int log2Width4() {
        return Integer.numberOfTrailingZeros(width4);
    }

    /// Returns the base-2 logarithm of the block height in 4x4 units.
    ///
    /// @return the base-2 logarithm of the block height in 4x4 units
    public int log2Height4() {
        return Integer.numberOfTrailingZeros(height4);
    }

    /// Returns the Y-mode size context used by `TileSyntaxReader`.
    ///
    /// @return the Y-mode size context used by `TileSyntaxReader`
    public int yModeSizeContext() {
        return yModeSizeContext;
    }

    /// Returns the size-dependent entropy-table index matching `dav1d`'s `N_BS_SIZES` order.
    ///
    /// @return the size-dependent entropy-table index matching `dav1d`'s `N_BS_SIZES` order
    public int cdfIndex() {
        return cdfIndex;
    }

    /// Returns the palette size context used by AV1 screen-content palette syntax.
    ///
    /// This context is defined as `log2(width4) + log2(height4) - 2` for palette-eligible
    /// blocks whose 4x4 dimensions are powers of two.
    ///
    /// @return the palette size context used by AV1 screen-content palette syntax
    public int paletteSizeContext() {
        return Integer.numberOfTrailingZeros(width4) + Integer.numberOfTrailingZeros(height4) - 2;
    }

    /// Returns whether the block is square.
    ///
    /// @return whether the block is square
    public boolean isSquare() {
        return width4 == height4;
    }

    /// Returns the largest luma transform size allowed for this block size.
    ///
    /// @return the largest luma transform size allowed for this block size
    public TransformSize maxLumaTransformSize() {
        return MAX_LUMA_TRANSFORM_SIZES[ordinal()];
    }

    /// Returns the largest chroma transform size allowed for this block size and chroma format.
    ///
    /// The mapping follows the `dav1d` lookup tables. For 4:2:2 entries without a direct table
    /// value, it derives the exact transform size from the horizontally subsampled block
    /// dimensions.
    ///
    /// @param chromaFormat the active sequence chroma format
    /// @return the largest chroma transform size allowed for this block size and chroma format, or `null`
    public TransformSize maxChromaTransformSize(Av1ChromaFormat chromaFormat) {
        Av1ChromaFormat nonNullChromaFormat = java.util.Objects.requireNonNull(chromaFormat, "chromaFormat");
        return switch (nonNullChromaFormat) {
            case MONOCHROME -> null;
            case YUV420 -> MAX_CHROMA_420_TRANSFORM_SIZES[ordinal()];
            case YUV422 -> {
                TransformSize mapped = MAX_CHROMA_422_TRANSFORM_SIZES[ordinal()];
                if (mapped != null) {
                    yield mapped;
                }
                yield TransformSize.forDimensions(Math.max(1, width4 >> 1), height4);
            }
            case YUV444 -> MAX_CHROMA_444_TRANSFORM_SIZES[ordinal()];
        };
    }
}
