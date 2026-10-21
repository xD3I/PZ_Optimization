// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


import java.util.Arrays;
import java.util.Objects;

/// One decoded block-level luma and chroma transform layout produced before coefficient syntax is read.

public final class TransformLayout {
    /// Shared empty transform-unit sequence for layouts without chroma units.
    private static final TransformUnit [] EMPTY_UNITS = new TransformUnit[0];

    /// The local tile-relative luma-grid origin of the owning block.
    private final BlockPosition position;

    /// The coded block size that owns this transform layout.
    private final BlockSize blockSize;

    /// The visible block width in 4x4 units after clipping against tile bounds.
    private final int visibleWidth4;

    /// The visible block height in 4x4 units after clipping against tile bounds.
    private final int visibleHeight4;

    /// The exact visible block width in pixels after clipping against tile bounds.
    private final int visibleWidthPixels;

    /// The exact visible block height in pixels after clipping against tile bounds.
    private final int visibleHeightPixels;

    /// The largest luma transform size allowed by the current block and frame layout.
    private final TransformSize maxLumaTransformSize;

    /// The largest chroma transform size allowed by the current block and frame layout, or `null`.
    private final TransformSize chromaTransformSize;

    /// Whether this layout came from a variable luma transform tree.
    private final boolean variableLumaTransformTree;

    /// The luma transform units in bitstream order.
    private final TransformUnit [] lumaUnits;

    /// The shared chroma transform units for the U and V planes in bitstream order.
    private final TransformUnit [] chromaUnits;

    /// Creates one decoded block-level transform layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this transform layout
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @param visibleWidthPixels the exact coded-grid block width in pixels after clipping against tile bounds
    /// @param visibleHeightPixels the exact coded-grid block height in pixels after clipping against tile bounds
    /// @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
    /// @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
    /// @param variableLumaTransformTree whether this layout came from a variable luma transform tree
    /// @param lumaUnits the luma transform units in bitstream order
    /// @param chromaUnits the shared chroma transform units for the U and V planes in bitstream order
    public TransformLayout(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize maxLumaTransformSize,
            TransformSize chromaTransformSize,
            boolean variableLumaTransformTree,
            TransformUnit[] lumaUnits,
            TransformUnit[] chromaUnits
    ) {
        this(
                position,
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                maxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                chromaUnits,
                true
        );
    }

    /// Creates one transform layout by taking exclusive ownership of both unit arrays.
    ///
    /// The caller must not access or modify either unit array after this method returns.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this transform layout
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @param visibleWidthPixels the exact coded-grid block width in pixels after clipping against tile bounds
    /// @param visibleHeightPixels the exact coded-grid block height in pixels after clipping against tile bounds
    /// @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
    /// @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
    /// @param variableLumaTransformTree whether this layout came from a variable luma transform tree
    /// @param lumaUnits the exclusively owned luma transform units in bitstream order
    /// @param chromaUnits the exclusively owned shared chroma transform units in bitstream order
    /// @return one transform layout backed by the supplied unit arrays
    public static TransformLayout fromOwnedUnits(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize maxLumaTransformSize,
            TransformSize chromaTransformSize,
            boolean variableLumaTransformTree,
            TransformUnit[] lumaUnits,
            TransformUnit[] chromaUnits
    ) {
        return new TransformLayout(
                position,
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                maxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                chromaUnits,
                false
        );
    }

    /// Creates one decoded block-level transform layout with copied or transferred unit arrays.
    ///
    /// @param position the local tile-relative luma-grid origin
    /// @param blockSize the coded block size
    /// @param visibleWidth4 the visible width in 4x4 units
    /// @param visibleHeight4 the visible height in 4x4 units
    /// @param visibleWidthPixels the exact visible width in pixels
    /// @param visibleHeightPixels the exact visible height in pixels
    /// @param maxLumaTransformSize the largest luma transform size
    /// @param chromaTransformSize the chroma transform size, or `null`
    /// @param variableLumaTransformTree whether luma uses a variable transform tree
    /// @param lumaUnits the luma transform units
    /// @param chromaUnits the chroma transform units
    /// @param copyUnits whether to copy both unit arrays
    private TransformLayout(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize maxLumaTransformSize,
            TransformSize chromaTransformSize,
            boolean variableLumaTransformTree,
            TransformUnit[] lumaUnits,
            TransformUnit[] chromaUnits,
            boolean copyUnits
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.blockSize = Objects.requireNonNull(blockSize, "blockSize");
        if (visibleWidth4 <= 0 || visibleWidth4 > blockSize.width4()) {
            throw new IllegalArgumentException("visibleWidth4 out of range: " + visibleWidth4);
        }
        if (visibleHeight4 <= 0 || visibleHeight4 > blockSize.height4()) {
            throw new IllegalArgumentException("visibleHeight4 out of range: " + visibleHeight4);
        }
        this.visibleWidth4 = visibleWidth4;
        this.visibleHeight4 = visibleHeight4;
        if (visibleWidthPixels <= 0 || visibleWidthPixels > blockSize.widthPixels()) {
            throw new IllegalArgumentException("visibleWidthPixels out of range: " + visibleWidthPixels);
        }
        if (visibleHeightPixels <= 0 || visibleHeightPixels > blockSize.heightPixels()) {
            throw new IllegalArgumentException("visibleHeightPixels out of range: " + visibleHeightPixels);
        }
        if (visibleWidthPixels > (visibleWidth4 << 2)) {
            throw new IllegalArgumentException("visibleWidthPixels exceeds visibleWidth4 coverage");
        }
        if (visibleHeightPixels > (visibleHeight4 << 2)) {
            throw new IllegalArgumentException("visibleHeightPixels exceeds visibleHeight4 coverage");
        }
        this.visibleWidthPixels = visibleWidthPixels;
        this.visibleHeightPixels = visibleHeightPixels;
        this.maxLumaTransformSize = Objects.requireNonNull(maxLumaTransformSize, "maxLumaTransformSize");
        this.chromaTransformSize = chromaTransformSize;
        this.variableLumaTransformTree = variableLumaTransformTree;
        TransformUnit[] checkedLumaUnits = Objects.requireNonNull(lumaUnits, "lumaUnits");
        this.lumaUnits = copyUnits ? Arrays.copyOf(checkedLumaUnits, checkedLumaUnits.length) : checkedLumaUnits;
        if (this.lumaUnits.length == 0) {
            throw new IllegalArgumentException("lumaUnits must not be empty");
        }
        TransformUnit[] checkedChromaUnits = Objects.requireNonNull(chromaUnits, "chromaUnits");
        this.chromaUnits = checkedChromaUnits.length == 0
                ? EMPTY_UNITS
                : copyUnits ? Arrays.copyOf(checkedChromaUnits, checkedChromaUnits.length) : checkedChromaUnits;
        if (chromaTransformSize == null && this.chromaUnits.length != 0) {
            throw new IllegalArgumentException("chromaUnits must be empty when chromaTransformSize is null");
        }
        if (chromaTransformSize != null && this.chromaUnits.length == 0) {
            throw new IllegalArgumentException("chromaUnits must not be empty when chromaTransformSize is present");
        }
        for (TransformUnit chromaUnit : this.chromaUnits) {
            if (chromaUnit.size() != chromaTransformSize) {
                throw new IllegalArgumentException("chroma unit size does not match chromaTransformSize");
            }
        }
    }

    /// Creates one decoded block-level transform layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this transform layout
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @param visibleWidthPixels the exact coded-grid block width in pixels after clipping against tile bounds
    /// @param visibleHeightPixels the exact coded-grid block height in pixels after clipping against tile bounds
    /// @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
    /// @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
    /// @param variableLumaTransformTree whether this layout came from a variable luma transform tree
    /// @param lumaUnits the luma transform units in bitstream order
    public TransformLayout(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize maxLumaTransformSize,
            TransformSize chromaTransformSize,
            boolean variableLumaTransformTree,
            TransformUnit[] lumaUnits
    ) {
        this(
                position,
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                maxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                defaultChromaUnits(position, chromaTransformSize)
        );
    }

    /// Creates one decoded block-level transform layout whose exact visible pixel dimensions match
    /// its visible 4x4-grid coverage and whose chroma layout contains one unit when present.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this transform layout
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
    /// @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
    /// @param variableLumaTransformTree whether this layout came from a variable luma transform tree
    /// @param lumaUnits the luma transform units in bitstream order
    public TransformLayout(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            TransformSize maxLumaTransformSize,
            TransformSize chromaTransformSize,
            boolean variableLumaTransformTree,
            TransformUnit[] lumaUnits
    ) {
        this(
                position,
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidth4 << 2,
                visibleHeight4 << 2,
                maxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                defaultChromaUnits(position, chromaTransformSize)
        );
    }

    /// Returns the local tile-relative luma-grid origin of the owning block.
    ///
    /// @return the local tile-relative luma-grid origin of the owning block
    public BlockPosition position() {
        return position;
    }

    /// Returns a copy of this transform layout offset by the supplied 4x4-unit delta.
    ///
    /// @param deltaX4 the X-axis offset in 4x4 units
    /// @param deltaY4 the Y-axis offset in 4x4 units
    /// @return a copy of this transform layout offset by the supplied 4x4-unit delta
    public TransformLayout withOffset(int deltaX4, int deltaY4) {
        if (deltaX4 == 0 && deltaY4 == 0) {
            return this;
        }
        return new TransformLayout(
                position.offset(deltaX4, deltaY4),
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                maxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                offsetUnits(lumaUnits, deltaX4, deltaY4),
                offsetUnits(chromaUnits, deltaX4, deltaY4),
                false
        );
    }

    /// Returns the coded block size that owns this transform layout.
    ///
    /// @return the coded block size that owns this transform layout
    public BlockSize blockSize() {
        return blockSize;
    }

    /// Returns the visible block width in 4x4 units after clipping against tile bounds.
    ///
    /// @return the visible block width in 4x4 units after clipping against tile bounds
    public int visibleWidth4() {
        return visibleWidth4;
    }

    /// Returns the visible block height in 4x4 units after clipping against tile bounds.
    ///
    /// @return the visible block height in 4x4 units after clipping against tile bounds
    public int visibleHeight4() {
        return visibleHeight4;
    }

    /// Returns the exact visible block width in pixels after clipping against tile bounds.
    ///
    /// @return the exact visible block width in pixels after clipping against tile bounds
    public int visibleWidthPixels() {
        return visibleWidthPixels;
    }

    /// Returns the exact visible block height in pixels after clipping against tile bounds.
    ///
    /// @return the exact visible block height in pixels after clipping against tile bounds
    public int visibleHeightPixels() {
        return visibleHeightPixels;
    }

    /// Returns the largest luma transform size allowed by the current block and frame layout.
    ///
    /// @return the largest luma transform size allowed by the current block and frame layout
    public TransformSize maxLumaTransformSize() {
        return maxLumaTransformSize;
    }

    /// Returns the largest chroma transform size allowed by the current block and frame layout, or `null`.
    ///
    /// @return the largest chroma transform size allowed by the current block and frame layout, or `null`
    public TransformSize chromaTransformSize() {
        return chromaTransformSize;
    }

    /// Returns whether this layout came from a variable luma transform tree.
    ///
    /// @return whether this layout came from a variable luma transform tree
    public boolean variableLumaTransformTree() {
        return variableLumaTransformTree;
    }

    /// Returns the luma transform units in bitstream order.
    ///
    /// @return the luma transform units in bitstream order
    public TransformUnit[] lumaUnits() {
        return Arrays.copyOf(lumaUnits, lumaUnits.length);
    }

    /// Returns the number of luma transform units.
    ///
    /// @return the number of luma transform units
    public int lumaUnitCount() {
        return lumaUnits.length;
    }

    /// Returns one luma transform unit without copying the complete unit array.
    ///
    /// @param index the zero-based unit index in bitstream order
    /// @return the selected luma transform unit
    public TransformUnit lumaUnit(int index) {
        return lumaUnits[index];
    }

    /// Returns the shared chroma transform units for the U and V planes in bitstream order.
    ///
    /// @return the shared chroma transform units for the U and V planes in bitstream order
    public TransformUnit[] chromaUnits() {
        return Arrays.copyOf(chromaUnits, chromaUnits.length);
    }

    /// Returns the number of shared chroma transform units.
    ///
    /// @return the number of chroma transform units
    public int chromaUnitCount() {
        return chromaUnits.length;
    }

    /// Returns one shared chroma transform unit without copying the complete unit array.
    ///
    /// @param index the zero-based unit index in bitstream order
    /// @return the selected chroma transform unit
    public TransformUnit chromaUnit(int index) {
        return chromaUnits[index];
    }

    /// Returns the uniform luma transform size, or `null` when the layout mixes sizes.
    ///
    /// @return the uniform luma transform size, or `null` when the layout mixes sizes
    public TransformSize uniformLumaTransformSize() {
        TransformSize uniformSize = lumaUnits[0].size();
        for (int i = 1; i < lumaUnits.length; i++) {
            if (lumaUnits[i].size() != uniformSize) {
                return null;
            }
        }
        return uniformSize;
    }

    /// Returns a single-unit chroma layout when a chroma transform size is present.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param chromaTransformSize the chroma transform size, or `null`
    /// @return the single-unit chroma layout, or an empty array for monochrome input
    private static TransformUnit[] defaultChromaUnits(
            BlockPosition position,
            TransformSize chromaTransformSize
    ) {
        if (chromaTransformSize == null) {
            return EMPTY_UNITS;
        }
        return new TransformUnit[]{new TransformUnit(position, chromaTransformSize)};
    }

    /// Returns transform units offset by the supplied 4x4-unit delta.
    ///
    /// @param units the source transform units
    /// @param deltaX4 the X-axis offset in 4x4 units
    /// @param deltaY4 the Y-axis offset in 4x4 units
    /// @return transform units offset by the supplied 4x4-unit delta
    private static TransformUnit[] offsetUnits(TransformUnit[] units, int deltaX4, int deltaY4) {
        if (units.length == 0) {
            return EMPTY_UNITS;
        }
        TransformUnit[] offsetUnits = new TransformUnit[units.length];
        for (int i = 0; i < units.length; i++) {
            TransformUnit unit = units[i];
            offsetUnits[i] = unit.withPosition(unit.position().offset(deltaX4, deltaY4));
        }
        return offsetUnits;
    }
}
