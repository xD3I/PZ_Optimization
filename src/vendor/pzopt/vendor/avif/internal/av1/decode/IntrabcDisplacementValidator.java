// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.av1.Av1ColorConfig;
import pzopt.vendor.avif.internal.av1.model.BlockPosition;
import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.MotionVector;

import java.util.Objects;

/// Validates AV1 same-frame displacement vectors against tile and decode-order constraints.

final class IntrabcDisplacementValidator {
    /// The AV1 hardware pipeline delay expressed in 64x64 superblocks.
    private static final int DELAY_SB64 = 4;

    /// Prevents instantiation of this utility class.
    private IntrabcDisplacementValidator() {
    }

    /// Requires one decoded displacement vector to be legal for its tile-local block.
    ///
    /// @param tileContext        the active tile decode context
    /// @param position           the tile-relative block origin in 4x4 units
    /// @param size               the decoded block size
    /// @param displacementVector the decoded same-frame displacement vector
    /// @param chromaReference    whether this block owns chroma prediction samples
    /// @throws InvalidDisplacementVectorException if the displacement vector is invalid
    static void requireValid(
            TileDecodeContext tileContext,
            BlockPosition position,
            BlockSize size,
            MotionVector displacementVector,
            boolean chromaReference
    ) {
        TileDecodeContext nonNullTileContext = Objects.requireNonNull(tileContext, "tileContext");
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        MotionVector nonNullDisplacementVector = Objects.requireNonNull(displacementVector, "displacementVector");
        Av1ColorConfig colorConfig = nonNullTileContext.sequenceHeader().colorConfig();
        int tileStartX4 = nonNullTileContext.startX() >> 2;
        int tileStartY4 = nonNullTileContext.startY() >> 2;
        if (!isValid(
                nonNullDisplacementVector,
                tileStartX4 + nonNullPosition.x4(),
                tileStartY4 + nonNullPosition.y4(),
                nonNullSize.widthPixels(),
                nonNullSize.heightPixels(),
                tileStartX4,
                tileStartY4,
                tileStartX4 + nonNullTileContext.codedWidth4(),
                tileStartY4 + nonNullTileContext.codedHeight4(),
                nonNullTileContext.superblockSize(),
                chromaReference && colorConfig.chromaFormat() != Av1ChromaFormat.MONOCHROME,
                colorConfig.chromaSubsamplingX(),
                colorConfig.chromaSubsamplingY()
        )) {
            throw new InvalidDisplacementVectorException(
                    "Invalid intrabc displacement vector at "
                            + nonNullPosition.x4() + "," + nonNullPosition.y4()
                            + ": row=" + nonNullDisplacementVector.rowEighthPel()
                            + ", column=" + nonNullDisplacementVector.columnEighthPel()
            );
        }
    }

    /// Returns whether one frame-relative displacement vector satisfies the normative AV1 rules.
    ///
    /// Tile boundaries are expressed in 4x4 coding units and their end coordinates are exclusive.
    ///
    /// @param displacementVector the displacement vector in luma eighth-pel units
    /// @param blockX4            the frame-relative block X origin in 4x4 units
    /// @param blockY4            the frame-relative block Y origin in 4x4 units
    /// @param blockWidth         the luma block width in pixels
    /// @param blockHeight        the luma block height in pixels
    /// @param tileStartX4        the inclusive frame-relative tile X origin in 4x4 units
    /// @param tileStartY4        the inclusive frame-relative tile Y origin in 4x4 units
    /// @param tileEndX4          the exclusive frame-relative tile X end in 4x4 units
    /// @param tileEndY4          the exclusive frame-relative tile Y end in 4x4 units
    /// @param superblockSize     the active superblock size in pixels, either 64 or 128
    /// @param chromaReference    whether the block owns chroma prediction samples
    /// @param subsamplingX       whether chroma is horizontally subsampled
    /// @param subsamplingY       whether chroma is vertically subsampled
    /// @return whether the displacement vector is legal
    static boolean isValid(
            MotionVector displacementVector,
            int blockX4,
            int blockY4,
            int blockWidth,
            int blockHeight,
            int tileStartX4,
            int tileStartY4,
            int tileEndX4,
            int tileEndY4,
            int superblockSize,
            boolean chromaReference,
            boolean subsamplingX,
            boolean subsamplingY
    ) {
        MotionVector nonNullDisplacementVector = Objects.requireNonNull(displacementVector, "displacementVector");
        if (superblockSize != 64 && superblockSize != 128) {
            throw new IllegalArgumentException("superblockSize must be 64 or 128: " + superblockSize);
        }
        if (blockWidth <= 0 || blockHeight <= 0) {
            throw new IllegalArgumentException("block dimensions must be positive");
        }
        if (tileStartX4 < 0 || tileStartY4 < 0 || tileEndX4 <= tileStartX4 || tileEndY4 <= tileStartY4) {
            throw new IllegalArgumentException("invalid tile bounds");
        }

        int displacementRow = nonNullDisplacementVector.rowEighthPel();
        int displacementColumn = nonNullDisplacementVector.columnEighthPel();
        if ((displacementRow & 7) != 0 || (displacementColumn & 7) != 0) {
            return false;
        }

        long sourceTopEdge = (long) blockY4 * 4 * 8 + displacementRow;
        long sourceLeftEdge = (long) blockX4 * 4 * 8 + displacementColumn;
        long sourceBottomEdge = ((long) blockY4 * 4 + blockHeight) * 8 + displacementRow;
        long sourceRightEdge = ((long) blockX4 * 4 + blockWidth) * 8 + displacementColumn;
        long tileTopEdge = (long) tileStartY4 * 4 * 8;
        long tileLeftEdge = (long) tileStartX4 * 4 * 8;
        long tileBottomEdge = (long) tileEndY4 * 4 * 8;
        long tileRightEdge = (long) tileEndX4 * 4 * 8;
        if (sourceTopEdge < tileTopEdge
                || sourceLeftEdge < tileLeftEdge
                || sourceBottomEdge > tileBottomEdge
                || sourceRightEdge > tileRightEdge) {
            return false;
        }

        if (chromaReference) {
            if (blockWidth < 8 && subsamplingX && sourceLeftEdge < tileLeftEdge + 4L * 8) {
                return false;
            }
            if (blockHeight < 8 && subsamplingY && sourceTopEdge < tileTopEdge + 4L * 8) {
                return false;
            }
        }

        int mibSizeLog2 = superblockSize == 128 ? 5 : 4;
        long activeSuperblockRow = blockY4 >> mibSizeLog2;
        long activeSb64Column = ((long) blockX4 * 4) >> 6;
        long sourceSuperblockRow = ((sourceBottomEdge >> 3) - 1) / superblockSize;
        long sourceSb64Column = ((sourceRightEdge >> 3) - 1) >> 6;
        long totalSb64PerRow = ((tileEndX4 - tileStartX4 - 1L) >> 4) + 1;
        long activeSb64 = activeSuperblockRow * totalSb64PerRow + activeSb64Column;
        long sourceSb64 = sourceSuperblockRow * totalSb64PerRow + sourceSb64Column;
        if (sourceSb64 >= activeSb64 - DELAY_SB64) {
            return false;
        }

        int gradient = 1 + DELAY_SB64 + (superblockSize > 64 ? 1 : 0);
        long wavefrontOffset = (long) gradient * (activeSuperblockRow - sourceSuperblockRow);
        return sourceSuperblockRow <= activeSuperblockRow
                && sourceSb64Column < activeSb64Column - DELAY_SB64 + wavefrontOffset;
    }

    /// Signals that a decoded same-frame displacement vector violates the AV1 bitstream rules.
    
    static final class InvalidDisplacementVectorException extends IllegalStateException {
        /// Creates an invalid-displacement exception with a diagnostic message.
        ///
        /// @param message the diagnostic message
        InvalidDisplacementVectorException(String message) {
            super(message);
        }
    }
}
