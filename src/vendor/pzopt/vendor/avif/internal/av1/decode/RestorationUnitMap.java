// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.model.FrameAssembly;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;

import java.util.Arrays;
import java.util.Objects;

/// Frame-level storage for decoded loop-restoration units.
///
/// The map is indexed by plane, unit row, and unit column. Inactive frame-level planes may have
/// zero-sized maps, while active planes require an explicitly decoded unit at every used location.

public final class RestorationUnitMap {
    /// The unit column count for each plane.
    private final int [] columnsByPlane;

    /// The unit row count for each plane.
    private final int [] rowsByPlane;

    /// The flat unit storage for each plane.
    private final RestorationUnit[][] unitsByPlane;

    /// Creates one restoration-unit map.
    ///
    /// @param columnsByPlane the unit column count for each plane
    /// @param rowsByPlane the unit row count for each plane
    private RestorationUnitMap(int[] columnsByPlane, int[] rowsByPlane) {
        this.columnsByPlane = Arrays.copyOf(Objects.requireNonNull(columnsByPlane, "columnsByPlane"), columnsByPlane.length);
        this.rowsByPlane = Arrays.copyOf(Objects.requireNonNull(rowsByPlane, "rowsByPlane"), rowsByPlane.length);
        if (this.columnsByPlane.length != 3 || this.rowsByPlane.length != 3) {
            throw new IllegalArgumentException("Restoration unit dimensions must cover exactly three planes");
        }
        this.unitsByPlane = new RestorationUnit[3][];
        for (int plane = 0; plane < 3; plane++) {
            if (this.columnsByPlane[plane] < 0 || this.rowsByPlane[plane] < 0) {
                throw new IllegalArgumentException("Restoration unit dimensions must not be negative");
            }
            this.unitsByPlane[plane] = new RestorationUnit[this.columnsByPlane[plane] * this.rowsByPlane[plane]];
        }
    }

    /// Creates an empty map matching one frame assembly.
    ///
    /// @param assembly the frame assembly that owns the restoration syntax
    /// @return an empty map matching one frame assembly
    public static RestorationUnitMap createEmpty(FrameAssembly assembly) {
        FrameAssembly checkedAssembly = Objects.requireNonNull(assembly, "assembly");
        FrameHeader frameHeader = checkedAssembly.frameHeader();
        Av1ChromaFormat chromaFormat = checkedAssembly.sequenceHeader().colorConfig().chromaFormat();
        int[] columns = new int[3];
        int[] rows = new int[3];
        for (int plane = 0; plane < 3; plane++) {
            if (plane > 0 && chromaFormat == Av1ChromaFormat.MONOCHROME) {
                continue;
            }
            if (frameHeader.restoration().types()[plane] == FrameHeader.RestorationType.NONE) {
                continue;
            }
            int unitSize = 1 << (plane == 0
                    ? frameHeader.restoration().unitSizeLog2Y()
                    : frameHeader.restoration().unitSizeLog2Uv());
            int width = plane == 0
                    ? frameHeader.frameSize().upscaledWidth()
                    : chromaWidth(chromaFormat, frameHeader.frameSize().upscaledWidth());
            int height = plane == 0
                    ? frameHeader.frameSize().height()
                    : chromaHeight(chromaFormat, frameHeader.frameSize().height());
            columns[plane] = countUnits(unitSize, width);
            rows[plane] = countUnits(unitSize, height);
        }
        return new RestorationUnitMap(columns, rows);
    }

    /// Returns a deep copy of this map.
    ///
    /// @return a deep copy of this map
    public RestorationUnitMap copy() {
        RestorationUnitMap copy = new RestorationUnitMap(columnsByPlane, rowsByPlane);
        for (int plane = 0; plane < unitsByPlane.length; plane++) {
            System.arraycopy(unitsByPlane[plane], 0, copy.unitsByPlane[plane], 0, unitsByPlane[plane].length);
        }
        return copy;
    }

    /// Merges decoded units from another map into this map.
    ///
    /// @param source the source map to merge
    public void mergeFrom(RestorationUnitMap source) {
        RestorationUnitMap checkedSource = Objects.requireNonNull(source, "source");
        for (int plane = 0; plane < 3; plane++) {
            if (checkedSource.columnsByPlane[plane] != columnsByPlane[plane]
                    || checkedSource.rowsByPlane[plane] != rowsByPlane[plane]) {
                throw new IllegalArgumentException("Restoration unit map dimensions do not match");
            }
            for (int index = 0; index < unitsByPlane[plane].length; index++) {
                RestorationUnit unit = checkedSource.unitsByPlane[plane][index];
                if (unit != null) {
                    unitsByPlane[plane][index] = unit;
                }
            }
        }
    }

    /// Returns the unit column count for one plane.
    ///
    /// @param plane the plane index
    /// @return the unit column count for one plane
    public int columns(int plane) {
        return columnsByPlane[Objects.checkIndex(plane, columnsByPlane.length)];
    }

    /// Returns the unit row count for one plane.
    ///
    /// @param plane the plane index
    /// @return the unit row count for one plane
    public int rows(int plane) {
        return rowsByPlane[Objects.checkIndex(plane, rowsByPlane.length)];
    }

    /// Returns one decoded restoration unit.
    ///
    /// @param plane the plane index
    /// @param row the restoration-unit row
    /// @param column the restoration-unit column
    /// @return one decoded restoration unit, or `null` when not decoded
    public RestorationUnit unit(int plane, int row, int column) {
        return unitsByPlane[Objects.checkIndex(plane, unitsByPlane.length)][index(plane, row, column)];
    }

    /// Stores one decoded restoration unit.
    ///
    /// @param plane the plane index
    /// @param row the restoration-unit row
    /// @param column the restoration-unit column
    /// @param unit the decoded restoration unit
    public void setUnit(int plane, int row, int column, RestorationUnit unit) {
        unitsByPlane[Objects.checkIndex(plane, unitsByPlane.length)][index(plane, row, column)] =
                Objects.requireNonNull(unit, "unit");
    }

    /// Returns whether a unit location has already been decoded.
    ///
    /// @param plane the plane index
    /// @param row the restoration-unit row
    /// @param column the restoration-unit column
    /// @return whether a unit location has already been decoded
    public boolean hasUnit(int plane, int row, int column) {
        return unit(plane, row, column) != null;
    }

    /// Returns the flat index for one unit coordinate.
    ///
    /// @param plane the plane index
    /// @param row the restoration-unit row
    /// @param column the restoration-unit column
    /// @return the flat index for one unit coordinate
    private int index(int plane, int row, int column) {
        int columns = columnsByPlane[plane];
        int rows = rowsByPlane[plane];
        if (column < 0 || column >= columns) {
            throw new IndexOutOfBoundsException("Restoration unit column out of range: " + column);
        }
        if (row < 0 || row >= rows) {
            throw new IndexOutOfBoundsException("Restoration unit row out of range: " + row);
        }
        return row * columns + column;
    }

    /// Counts restoration units for one plane dimension.
    ///
    /// @param unitSize the restoration unit size in samples
    /// @param frameSize the plane dimension in samples
    /// @return the number of units needed for the dimension
    private static int countUnits(int unitSize, int frameSize) {
        return Math.max((frameSize + (unitSize >> 1)) / unitSize, 1);
    }

    /// Returns one chroma plane width for a luma width.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @param lumaWidth the luma width in samples
    /// @return one chroma plane width for a luma width
    private static int chromaWidth(Av1ChromaFormat chromaFormat, int lumaWidth) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420, YUV422 -> (lumaWidth + 1) >> 1;
            case YUV444 -> lumaWidth;
        };
    }

    /// Returns one chroma plane height for a luma height.
    ///
    /// @param chromaFormat the decoded chroma format
    /// @param lumaHeight the luma height in samples
    /// @return one chroma plane height for a luma height
    private static int chromaHeight(Av1ChromaFormat chromaFormat, int lumaHeight) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420 -> (lumaHeight + 1) >> 1;
            case YUV422, YUV444 -> lumaHeight;
        };
    }
}
