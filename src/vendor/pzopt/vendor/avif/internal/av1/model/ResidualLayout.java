// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;


import java.util.Arrays;
import java.util.Objects;

/// Decoded block-level luma and chroma transform residual units in bitstream order.

public final class ResidualLayout {
    /// Shared empty residual-unit sequence for layouts without chroma units.
    private static final TransformResidualUnit [] EMPTY_UNITS = new TransformResidualUnit[0];

    /// The local tile-relative luma-grid origin of the owning block.
    private final BlockPosition position;

    /// The coded block size that owns this residual layout.
    private final BlockSize blockSize;

    /// The luma transform residual units in bitstream order.
    private final TransformResidualUnit [] lumaUnits;

    /// The chroma U transform residual units in bitstream order.
    private final TransformResidualUnit [] chromaUUnits;

    /// The chroma V transform residual units in bitstream order.
    private final TransformResidualUnit [] chromaVUnits;

    /// Creates one block-level residual layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this residual layout
    /// @param lumaUnits the luma transform residual units in bitstream order
    public ResidualLayout(BlockPosition position, BlockSize blockSize, TransformResidualUnit[] lumaUnits) {
        this(position, blockSize, lumaUnits, EMPTY_UNITS, EMPTY_UNITS);
    }

    /// Creates one block-level residual layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this residual layout
    /// @param lumaUnits the luma transform residual units in bitstream order
    /// @param chromaUUnits the chroma U transform residual units in bitstream order
    /// @param chromaVUnits the chroma V transform residual units in bitstream order
    public ResidualLayout(
            BlockPosition position,
            BlockSize blockSize,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits
    ) {
        this(position, blockSize, lumaUnits, chromaUUnits, chromaVUnits, true);
    }

    /// Creates one residual layout by taking exclusive ownership of all unit arrays.
    ///
    /// The caller must not access or modify any unit array after this method returns.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this residual layout
    /// @param lumaUnits the exclusively owned luma transform residual units in bitstream order
    /// @param chromaUUnits the exclusively owned chroma U transform residual units in bitstream order
    /// @param chromaVUnits the exclusively owned chroma V transform residual units in bitstream order
    /// @return one residual layout backed by the supplied unit arrays
    public static ResidualLayout fromOwnedUnits(
            BlockPosition position,
            BlockSize blockSize,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits
    ) {
        return new ResidualLayout(
                position,
                blockSize,
                lumaUnits,
                chromaUUnits,
                chromaVUnits,
                false
        );
    }

    /// Creates one residual layout with copied or exclusively transferred unit arrays.
    ///
    /// @param position the local tile-relative luma-grid origin
    /// @param blockSize the coded block size
    /// @param lumaUnits the luma residual units
    /// @param chromaUUnits the chroma U residual units
    /// @param chromaVUnits the chroma V residual units
    /// @param copyUnits whether to copy all unit arrays
    private ResidualLayout(
            BlockPosition position,
            BlockSize blockSize,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits,
            boolean copyUnits
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.blockSize = Objects.requireNonNull(blockSize, "blockSize");
        TransformResidualUnit[] checkedLumaUnits = Objects.requireNonNull(lumaUnits, "lumaUnits");
        this.lumaUnits = copyUnits ? Arrays.copyOf(checkedLumaUnits, checkedLumaUnits.length) : checkedLumaUnits;
        if (this.lumaUnits.length == 0) {
            throw new IllegalArgumentException("lumaUnits must not be empty");
        }
        TransformResidualUnit[] checkedChromaUUnits = Objects.requireNonNull(chromaUUnits, "chromaUUnits");
        TransformResidualUnit[] checkedChromaVUnits = Objects.requireNonNull(chromaVUnits, "chromaVUnits");
        this.chromaUUnits = checkedChromaUUnits.length == 0
                ? EMPTY_UNITS
                : copyUnits ? Arrays.copyOf(checkedChromaUUnits, checkedChromaUUnits.length) : checkedChromaUUnits;
        this.chromaVUnits = checkedChromaVUnits.length == 0
                ? EMPTY_UNITS
                : copyUnits ? Arrays.copyOf(checkedChromaVUnits, checkedChromaVUnits.length) : checkedChromaVUnits;
        if (this.chromaUUnits.length != this.chromaVUnits.length) {
            throw new IllegalArgumentException("chromaUUnits and chromaVUnits must have the same length");
        }
    }

    /// Returns the local tile-relative luma-grid origin of the owning block.
    ///
    /// @return the local tile-relative luma-grid origin of the owning block
    public BlockPosition position() {
        return position;
    }

    /// Returns a copy of this residual layout offset by the supplied 4x4-unit delta.
    ///
    /// @param deltaX4 the X-axis offset in 4x4 units
    /// @param deltaY4 the Y-axis offset in 4x4 units
    /// @return a copy of this residual layout offset by the supplied 4x4-unit delta
    public ResidualLayout withOffset(int deltaX4, int deltaY4) {
        if (deltaX4 == 0 && deltaY4 == 0) {
            return this;
        }
        return new ResidualLayout(
                position.offset(deltaX4, deltaY4),
                blockSize,
                offsetUnits(lumaUnits, deltaX4, deltaY4),
                offsetUnits(chromaUUnits, deltaX4, deltaY4),
                offsetUnits(chromaVUnits, deltaX4, deltaY4),
                false
        );
    }

    /// Returns the coded block size that owns this residual layout.
    ///
    /// @return the coded block size that owns this residual layout
    public BlockSize blockSize() {
        return blockSize;
    }

    /// Returns the luma transform residual units in bitstream order.
    ///
    /// @return the luma transform residual units in bitstream order
    public TransformResidualUnit[] lumaUnits() {
        return Arrays.copyOf(lumaUnits, lumaUnits.length);
    }

    /// Returns the number of luma residual units.
    ///
    /// @return the number of luma residual units
    public int lumaUnitCount() {
        return lumaUnits.length;
    }

    /// Returns one luma residual unit without copying the complete unit array.
    ///
    /// @param index the zero-based unit index in bitstream order
    /// @return the selected luma residual unit
    public TransformResidualUnit lumaUnit(int index) {
        return lumaUnits[index];
    }

    /// Returns the chroma U transform residual units in bitstream order.
    ///
    /// @return the chroma U transform residual units in bitstream order
    public TransformResidualUnit[] chromaUUnits() {
        return Arrays.copyOf(chromaUUnits, chromaUUnits.length);
    }

    /// Returns one chroma U residual unit without copying the complete unit array.
    ///
    /// @param index the zero-based unit index in bitstream order
    /// @return the selected chroma U residual unit
    public TransformResidualUnit chromaUUnit(int index) {
        return chromaUUnits[index];
    }

    /// Returns the chroma V transform residual units in bitstream order.
    ///
    /// @return the chroma V transform residual units in bitstream order
    public TransformResidualUnit[] chromaVUnits() {
        return Arrays.copyOf(chromaVUnits, chromaVUnits.length);
    }

    /// Returns the number of residual units in each chroma plane.
    ///
    /// @return the number of chroma U and chroma V residual units
    public int chromaUnitCount() {
        return chromaUUnits.length;
    }

    /// Returns one chroma V residual unit without copying the complete unit array.
    ///
    /// @param index the zero-based unit index in bitstream order
    /// @return the selected chroma V residual unit
    public TransformResidualUnit chromaVUnit(int index) {
        return chromaVUnits[index];
    }

    /// Returns whether this residual layout carries any modeled chroma residual units.
    ///
    /// @return whether this residual layout carries any modeled chroma residual units
    public boolean hasChromaUnits() {
        return chromaUUnits.length != 0;
    }

    /// Returns residual units offset by the supplied 4x4-unit delta.
    ///
    /// @param units the source residual units
    /// @param deltaX4 the X-axis offset in 4x4 units
    /// @param deltaY4 the Y-axis offset in 4x4 units
    /// @return residual units offset by the supplied 4x4-unit delta
    private static TransformResidualUnit[] offsetUnits(
            TransformResidualUnit[] units,
            int deltaX4,
            int deltaY4
    ) {
        if (units.length == 0) {
            return EMPTY_UNITS;
        }
        TransformResidualUnit[] offsetUnits = new TransformResidualUnit[units.length];
        for (int i = 0; i < units.length; i++) {
            TransformResidualUnit unit = units[i];
            offsetUnits[i] = unit.withPosition(unit.position().offset(deltaX4, deltaY4));
        }
        return offsetUnits;
    }
}
