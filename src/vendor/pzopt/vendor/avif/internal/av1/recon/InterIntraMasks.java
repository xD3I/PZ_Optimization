// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.InterIntraPredictionMode;

import java.util.Arrays;
import java.util.Objects;

/// Generates AV1 inter-intra blend and wedge masks for the reconstruction path.

final class InterIntraMasks {
    /// The AV1 wedge master template width and height.
    private static final int WEDGE_MASTER_SIZE = 64;

    /// The number of AV1 wedge mask indices.
    private static final int WEDGE_INDEX_COUNT = 16;

    /// Horizontal wedge direction.
    private static final int WEDGE_HORIZONTAL = 0;

    /// Vertical wedge direction.
    private static final int WEDGE_VERTICAL = 1;

    /// 27-degree oblique wedge direction.
    private static final int WEDGE_OBLIQUE27 = 2;

    /// 63-degree oblique wedge direction.
    private static final int WEDGE_OBLIQUE63 = 3;

    /// 117-degree oblique wedge direction.
    private static final int WEDGE_OBLIQUE117 = 4;

    /// 153-degree oblique wedge direction.
    private static final int WEDGE_OBLIQUE153 = 5;

    /// Odd-row oblique wedge master border.
    private static final int [] WEDGE_MASTER_BORDER_ODD = {1, 2, 6, 18, 37, 53, 60, 63};

    /// Even-row oblique wedge master border.
    private static final int [] WEDGE_MASTER_BORDER_EVEN = {1, 4, 11, 27, 46, 58, 62, 63};

    /// Vertical wedge master border.
    private static final int [] WEDGE_MASTER_BORDER_VERTICAL = {0, 2, 7, 21, 43, 57, 62, 64};

    /// Inter-intra one-dimensional non-DC blend weights.
    private static final int [] INTER_INTRA_WEIGHTS_1D = {
            60, 52, 45, 39, 34, 30, 26, 22, 19, 17, 15, 13, 11, 10, 8, 7,
            6, 6, 5, 4, 4, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1
    };

    /// Wedge codebook used when the block is taller than it is wide.
    private static final int [] [] WEDGE_CODEBOOK_HGTW = {
            {WEDGE_OBLIQUE27, 4, 4}, {WEDGE_OBLIQUE63, 4, 4},
            {WEDGE_OBLIQUE117, 4, 4}, {WEDGE_OBLIQUE153, 4, 4},
            {WEDGE_HORIZONTAL, 4, 2}, {WEDGE_HORIZONTAL, 4, 4},
            {WEDGE_HORIZONTAL, 4, 6}, {WEDGE_VERTICAL, 4, 4},
            {WEDGE_OBLIQUE27, 4, 2}, {WEDGE_OBLIQUE27, 4, 6},
            {WEDGE_OBLIQUE153, 4, 2}, {WEDGE_OBLIQUE153, 4, 6},
            {WEDGE_OBLIQUE63, 2, 4}, {WEDGE_OBLIQUE63, 6, 4},
            {WEDGE_OBLIQUE117, 2, 4}, {WEDGE_OBLIQUE117, 6, 4}
    };

    /// Wedge codebook used when the block is wider than it is tall.
    private static final int [] [] WEDGE_CODEBOOK_HLTW = {
            {WEDGE_OBLIQUE27, 4, 4}, {WEDGE_OBLIQUE63, 4, 4},
            {WEDGE_OBLIQUE117, 4, 4}, {WEDGE_OBLIQUE153, 4, 4},
            {WEDGE_VERTICAL, 2, 4}, {WEDGE_VERTICAL, 4, 4},
            {WEDGE_VERTICAL, 6, 4}, {WEDGE_HORIZONTAL, 4, 4},
            {WEDGE_OBLIQUE27, 4, 2}, {WEDGE_OBLIQUE27, 4, 6},
            {WEDGE_OBLIQUE153, 4, 2}, {WEDGE_OBLIQUE153, 4, 6},
            {WEDGE_OBLIQUE63, 2, 4}, {WEDGE_OBLIQUE63, 6, 4},
            {WEDGE_OBLIQUE117, 2, 4}, {WEDGE_OBLIQUE117, 6, 4}
    };

    /// Wedge codebook used when the block is square.
    private static final int [] [] WEDGE_CODEBOOK_HEQW = {
            {WEDGE_OBLIQUE27, 4, 4}, {WEDGE_OBLIQUE63, 4, 4},
            {WEDGE_OBLIQUE117, 4, 4}, {WEDGE_OBLIQUE153, 4, 4},
            {WEDGE_HORIZONTAL, 4, 2}, {WEDGE_HORIZONTAL, 4, 6},
            {WEDGE_VERTICAL, 2, 4}, {WEDGE_VERTICAL, 6, 4},
            {WEDGE_OBLIQUE27, 4, 2}, {WEDGE_OBLIQUE27, 4, 6},
            {WEDGE_OBLIQUE153, 4, 2}, {WEDGE_OBLIQUE153, 4, 6},
            {WEDGE_OBLIQUE63, 2, 4}, {WEDGE_OBLIQUE63, 6, 4},
            {WEDGE_OBLIQUE117, 2, 4}, {WEDGE_OBLIQUE117, 6, 4}
    };

    /// The generated luma-domain wedge master masks indexed by direction.
    private static final int [] [] WEDGE_MASTER = createWedgeMaster();

    /// Prevents instantiation of this utility class.
    private InterIntraMasks() {
    }

    /// Returns whether the supplied block size can signal inter-intra prediction.
    ///
    /// @param size the block size to test
    /// @return whether the supplied block size can signal inter-intra prediction
    static boolean supportsInterIntra(BlockSize size) {
        return switch (Objects.requireNonNull(size, "size")) {
            case SIZE_32X32,
                    SIZE_32X16,
                    SIZE_16X32,
                    SIZE_16X16,
                    SIZE_16X8,
                    SIZE_8X16,
                    SIZE_8X8 -> true;
            default -> false;
        };
    }

    /// Returns whether the supplied block size can signal compound wedge prediction.
    ///
    /// @param size the block size to test
    /// @return whether the supplied block size can signal compound wedge prediction
    static boolean supportsCompoundWedge(BlockSize size) {
        return switch (Objects.requireNonNull(size, "size")) {
            case SIZE_32X32,
                    SIZE_32X16,
                    SIZE_32X8,
                    SIZE_16X32,
                    SIZE_16X16,
                    SIZE_16X8,
                    SIZE_8X32,
                    SIZE_8X16,
                    SIZE_8X8 -> true;
            default -> false;
        };
    }

    /// Returns one inter-intra blend mask value in `[0, 64]`.
    ///
    /// @param mode the decoded inter-intra prediction mode
    /// @param wedge whether the mask is a wedge mask instead of a smooth blend mask
    /// @param wedgeIndex the decoded wedge index, or `-1` for non-wedge blending
    /// @param size the luma block size that owns the prediction
    /// @param x the plane-local sample X coordinate inside the block
    /// @param y the plane-local sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift for the current plane
    /// @param subsamplingY the vertical chroma subsampling shift for the current plane
    /// @return one inter-intra blend mask value in `[0, 64]`
    static int maskValue(
            InterIntraPredictionMode mode,
            boolean wedge,
            int wedgeIndex,
            BlockSize size,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY
    ) {
        InterIntraPredictionMode nonNullMode = Objects.requireNonNull(mode, "mode");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        if (!supportsInterIntra(nonNullSize)) {
            throw new IllegalArgumentException("Inter-intra mask is not defined for block size: " + nonNullSize);
        }
        if (subsamplingX < 0 || subsamplingX > 1 || subsamplingY < 0 || subsamplingY > 1) {
            throw new IllegalArgumentException("Subsampling shifts must be in [0, 1]");
        }
        if (!wedge) {
            return blendMaskValue(nonNullMode, nonNullSize, x, y, subsamplingX, subsamplingY);
        }
        if (wedgeIndex < 0 || wedgeIndex >= WEDGE_INDEX_COUNT) {
            throw new IllegalArgumentException("wedgeIndex out of range: " + wedgeIndex);
        }
        return subsampledWedgeMaskValue(nonNullSize, wedgeIndex, x, y, subsamplingX, subsamplingY);
    }

    /// Returns one compound wedge mask value as the effective secondary-source blend weight.
    ///
    /// @param size the luma block size that owns the prediction
    /// @param wedgeIndex the decoded wedge index
    /// @param maskSign whether the compound mask uses inverted source order
    /// @param x the plane-local sample X coordinate inside the block
    /// @param y the plane-local sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift for the current plane
    /// @param subsamplingY the vertical chroma subsampling shift for the current plane
    /// @return one compound wedge mask value as the effective secondary-source blend weight
    static int compoundWedgeMaskValue(
            BlockSize size,
            int wedgeIndex,
            boolean maskSign,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY
    ) {
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        if (!supportsCompoundWedge(nonNullSize)) {
            throw new IllegalArgumentException("Compound wedge mask is not defined for block size: " + nonNullSize);
        }
        if (wedgeIndex < 0 || wedgeIndex >= WEDGE_INDEX_COUNT) {
            throw new IllegalArgumentException("wedgeIndex out of range: " + wedgeIndex);
        }
        if (subsamplingX < 0 || subsamplingX > 1 || subsamplingY < 0 || subsamplingY > 1) {
            throw new IllegalArgumentException("Subsampling shifts must be in [0, 1]");
        }

        if (subsamplingX == 0 && subsamplingY == 0) {
            int mask = lumaWedgeMaskValue(nonNullSize, wedgeIndex, x, y);
            return maskSign ? mask : 64 - mask;
        }
        int mask = subsampledWedgeMaskValue(
                nonNullSize,
                wedgeIndex,
                x,
                y,
                subsamplingX,
                subsamplingY,
                maskSign ? 1 : 0
        );
        return maskSign ? mask : 64 - mask;
    }

    /// Returns one smooth-blend inter-intra mask value.
    ///
    /// @param mode the decoded inter-intra prediction mode
    /// @param size the luma block size that owns the prediction
    /// @param x the plane-local sample X coordinate inside the block
    /// @param y the plane-local sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift for the current plane
    /// @param subsamplingY the vertical chroma subsampling shift for the current plane
    /// @return one smooth-blend inter-intra mask value
    private static int blendMaskValue(
            InterIntraPredictionMode mode,
            BlockSize size,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY
    ) {
        if (mode == InterIntraPredictionMode.DC) {
            return 32;
        }
        int planeWidth = size.widthPixels() >> subsamplingX;
        int planeHeight = size.heightPixels() >> subsamplingY;
        int step = 32 / Math.max(planeWidth, planeHeight);
        return switch (mode) {
            case VERTICAL -> INTER_INTRA_WEIGHTS_1D[Math.min(y * step, INTER_INTRA_WEIGHTS_1D.length - 1)];
            case HORIZONTAL -> INTER_INTRA_WEIGHTS_1D[Math.min(x * step, INTER_INTRA_WEIGHTS_1D.length - 1)];
            case SMOOTH -> INTER_INTRA_WEIGHTS_1D[Math.min(Math.min(x, y) * step, INTER_INTRA_WEIGHTS_1D.length - 1)];
            case DC -> 32;
        };
    }

    /// Returns one wedge mask value after chroma subsampling when needed.
    ///
    /// @param size the luma block size that owns the prediction
    /// @param wedgeIndex the decoded wedge index
    /// @param x the plane-local sample X coordinate inside the block
    /// @param y the plane-local sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift for the current plane
    /// @param subsamplingY the vertical chroma subsampling shift for the current plane
    /// @return one wedge mask value after chroma subsampling when needed
    private static int subsampledWedgeMaskValue(
            BlockSize size,
            int wedgeIndex,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY
    ) {
        return subsampledWedgeMaskValue(size, wedgeIndex, x, y, subsamplingX, subsamplingY, 0);
    }

    /// Returns one wedge mask value after chroma subsampling with AV1's rounding sign applied.
    ///
    /// @param size the luma block size that owns the prediction
    /// @param wedgeIndex the decoded wedge index
    /// @param x the plane-local sample X coordinate inside the block
    /// @param y the plane-local sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift for the current plane
    /// @param subsamplingY the vertical chroma subsampling shift for the current plane
    /// @param roundingSign the AV1 chroma wedge rounding sign in `[0, 1]`
    /// @return one wedge mask value after chroma subsampling when needed
    private static int subsampledWedgeMaskValue(
            BlockSize size,
            int wedgeIndex,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY,
            int roundingSign
    ) {
        if (subsamplingX == 0 && subsamplingY == 0) {
            return lumaWedgeMaskValue(size, wedgeIndex, x, y);
        }

        int lumaX = x << subsamplingX;
        int lumaY = y << subsamplingY;
        int horizontalSpan = 1 << subsamplingX;
        int verticalSpan = 1 << subsamplingY;
        int sum = 0;
        for (int yy = 0; yy < verticalSpan; yy++) {
            for (int xx = 0; xx < horizontalSpan; xx++) {
                sum += lumaWedgeMaskValue(size, wedgeIndex, lumaX + xx, lumaY + yy);
            }
            if (subsamplingX != 0) {
                sum++;
            }
        }
        return (sum - roundingSign) >> (subsamplingX + subsamplingY);
    }

    /// Returns one luma-domain wedge mask value.
    ///
    /// @param size the luma block size that owns the prediction
    /// @param wedgeIndex the decoded wedge index
    /// @param x the luma sample X coordinate inside the block
    /// @param y the luma sample Y coordinate inside the block
    /// @return one luma-domain wedge mask value
    private static int lumaWedgeMaskValue(BlockSize size, int wedgeIndex, int x, int y) {
        int width = size.widthPixels();
        int height = size.heightPixels();
        int[][] codebook = wedgeCodebook(width, height);
        int[] code = codebook[wedgeIndex];
        int sourceX = 32 - ((width * code[1]) >> 3) + x;
        int sourceY = 32 - ((height * code[2]) >> 3) + y;
        int value = WEDGE_MASTER[code[0]][sourceY * WEDGE_MASTER_SIZE + sourceX];
        return ((wedgeSigns(size) >>> wedgeIndex) & 1) != 0 ? 64 - value : value;
    }

    /// Returns the wedge codebook for one luma block geometry.
    ///
    /// @param width the luma block width in samples
    /// @param height the luma block height in samples
    /// @return the wedge codebook for one luma block geometry
    private static int[][] wedgeCodebook(int width, int height) {
        if (height > width) {
            return WEDGE_CODEBOOK_HGTW;
        }
        if (height < width) {
            return WEDGE_CODEBOOK_HLTW;
        }
        return WEDGE_CODEBOOK_HEQW;
    }

    /// Returns the per-index sign bits used by AV1's canonical wedge masks.
    ///
    /// @param size the luma block size that owns the prediction
    /// @return the per-index sign bits used by AV1's canonical wedge masks
    private static int wedgeSigns(BlockSize size) {
        return switch (size) {
            case SIZE_32X32, SIZE_16X16, SIZE_8X8 -> 0x7bfb;
            case SIZE_32X16, SIZE_16X32, SIZE_16X8, SIZE_8X16 -> 0x7beb;
            case SIZE_32X8 -> 0x6beb;
            case SIZE_8X32 -> 0x7aeb;
            default -> throw new IllegalArgumentException("Wedge mask is not defined for block size: " + size);
        };
    }

    /// Creates the six AV1 wedge master masks.
    ///
    /// @return the six AV1 wedge master masks
    private static int[][] createWedgeMaster() {
        int[][] master = new int[6][WEDGE_MASTER_SIZE * WEDGE_MASTER_SIZE];
        for (int y = 0; y < WEDGE_MASTER_SIZE; y++) {
            insertBorder(master[WEDGE_VERTICAL], y * WEDGE_MASTER_SIZE, WEDGE_MASTER_BORDER_VERTICAL, 32);
        }
        for (int y = 0, ctr = 48; y < WEDGE_MASTER_SIZE; y += 2, ctr--) {
            insertBorder(master[WEDGE_OBLIQUE63], y * WEDGE_MASTER_SIZE, WEDGE_MASTER_BORDER_EVEN, ctr);
            insertBorder(master[WEDGE_OBLIQUE63], (y + 1) * WEDGE_MASTER_SIZE, WEDGE_MASTER_BORDER_ODD, ctr - 1);
        }
        transpose(master[WEDGE_OBLIQUE27], master[WEDGE_OBLIQUE63]);
        transpose(master[WEDGE_HORIZONTAL], master[WEDGE_VERTICAL]);
        hflip(master[WEDGE_OBLIQUE117], master[WEDGE_OBLIQUE63]);
        hflip(master[WEDGE_OBLIQUE153], master[WEDGE_OBLIQUE27]);
        return master;
    }

    /// Inserts one wedge border into a master-mask row.
    ///
    /// @param destination the destination master-mask plane
    /// @param destinationOffset the row offset inside the destination plane
    /// @param source the eight-sample border ramp
    /// @param center the center coordinate of the inserted border
    private static void insertBorder(int[] destination, int destinationOffset, int[] source, int center) {
        if (center > 4) {
            Arrays.fill(destination, destinationOffset, destinationOffset + center - 4, 0);
        }
        int copyOffset = Math.max(center, 4) - 4;
        int sourceOffset = Math.max(4 - center, 0);
        int length = Math.min(64 - center, 8);
        System.arraycopy(source, sourceOffset, destination, destinationOffset + copyOffset, length);
        if (center < 60) {
            Arrays.fill(destination, destinationOffset + center + 4, destinationOffset + 64, 64);
        }
    }

    /// Transposes one 64x64 wedge master mask.
    ///
    /// @param destination the destination master-mask plane
    /// @param source the source master-mask plane
    private static void transpose(int[] destination, int[] source) {
        for (int y = 0; y < WEDGE_MASTER_SIZE; y++) {
            for (int x = 0; x < WEDGE_MASTER_SIZE; x++) {
                destination[x * WEDGE_MASTER_SIZE + y] = source[y * WEDGE_MASTER_SIZE + x];
            }
        }
    }

    /// Horizontally flips one 64x64 wedge master mask.
    ///
    /// @param destination the destination master-mask plane
    /// @param source the source master-mask plane
    private static void hflip(int[] destination, int[] source) {
        for (int y = 0; y < WEDGE_MASTER_SIZE; y++) {
            int rowOffset = y * WEDGE_MASTER_SIZE;
            for (int x = 0; x < WEDGE_MASTER_SIZE; x++) {
                destination[rowOffset + WEDGE_MASTER_SIZE - 1 - x] = source[rowOffset + x];
            }
        }
    }
}
