// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


/// Immutable AVIF image transform metadata from `clap`, `irot`, and `imir` item properties.

public final class AvifImageTransformInfo {
    /// The clean-aperture crop x coordinate, or -1 when absent.
    private final int cleanApertureCropX;
    /// The clean-aperture crop y coordinate, or -1 when absent.
    private final int cleanApertureCropY;
    /// The clean-aperture crop width, or -1 when absent.
    private final int cleanApertureCropWidth;
    /// The clean-aperture crop height, or -1 when absent.
    private final int cleanApertureCropHeight;
    /// The AVIF `irot` rotation code, or -1 when absent.
    private final int rotationCode;
    /// The AVIF `imir` mirror axis, or -1 when absent.
    private final int mirrorAxis;

    /// Creates immutable image transform metadata.
    ///
    /// @param cleanApertureCropX the clean-aperture crop x coordinate, or -1 when absent
    /// @param cleanApertureCropY the clean-aperture crop y coordinate, or -1 when absent
    /// @param cleanApertureCropWidth the clean-aperture crop width, or -1 when absent
    /// @param cleanApertureCropHeight the clean-aperture crop height, or -1 when absent
    /// @param rotationCode the AVIF `irot` rotation code, or -1 when absent
    /// @param mirrorAxis the AVIF `imir` mirror axis, or -1 when absent
    public AvifImageTransformInfo(
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight,
            int rotationCode,
            int mirrorAxis
    ) {
        if (!isAbsentCleanAperture(cleanApertureCropX, cleanApertureCropY, cleanApertureCropWidth, cleanApertureCropHeight)
                && (cleanApertureCropX < 0 || cleanApertureCropY < 0
                || cleanApertureCropWidth <= 0 || cleanApertureCropHeight <= 0)) {
            throw new IllegalArgumentException("Invalid clean-aperture crop parameters");
        }
        if (rotationCode < -1 || rotationCode > 3) {
            throw new IllegalArgumentException("rotationCode must be -1 or between 0 and 3: " + rotationCode);
        }
        if (mirrorAxis < -1 || mirrorAxis > 1) {
            throw new IllegalArgumentException("mirrorAxis must be -1, 0, or 1: " + mirrorAxis);
        }
        if (isAbsentCleanAperture(cleanApertureCropX, cleanApertureCropY, cleanApertureCropWidth, cleanApertureCropHeight)
                && rotationCode == -1 && mirrorAxis == -1) {
            throw new IllegalArgumentException("At least one image transform must be present");
        }

        this.cleanApertureCropX = cleanApertureCropX;
        this.cleanApertureCropY = cleanApertureCropY;
        this.cleanApertureCropWidth = cleanApertureCropWidth;
        this.cleanApertureCropHeight = cleanApertureCropHeight;
        this.rotationCode = rotationCode;
        this.mirrorAxis = mirrorAxis;
    }

    /// Returns whether a clean-aperture crop is present.
    ///
    /// @return whether a clean-aperture crop is present
    public boolean hasCleanApertureCrop() {
        return cleanApertureCropX >= 0;
    }

    /// Returns the clean-aperture crop x coordinate.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop x coordinate, or -1 when absent
    public int cleanApertureCropX() {
        return cleanApertureCropX;
    }

    /// Returns the clean-aperture crop y coordinate.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop y coordinate, or -1 when absent
    public int cleanApertureCropY() {
        return cleanApertureCropY;
    }

    /// Returns the clean-aperture crop width.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop width, or -1 when absent
    public int cleanApertureCropWidth() {
        return cleanApertureCropWidth;
    }

    /// Returns the clean-aperture crop height.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop height, or -1 when absent
    public int cleanApertureCropHeight() {
        return cleanApertureCropHeight;
    }

    /// Returns whether an `irot` rotation transform is present.
    ///
    /// @return whether an `irot` rotation transform is present
    public boolean hasRotation() {
        return rotationCode >= 0;
    }

    /// Returns the AVIF `irot` rotation code.
    ///
    /// Values 0 through 3 represent 0, 90, 180, and 270 degrees counter-clockwise.
    /// A value of -1 means the property is absent.
    ///
    /// @return the rotation code, or -1 when absent
    public int rotationCode() {
        return rotationCode;
    }

    /// Returns the clockwise-equivalent rotation in degrees.
    ///
    /// A value of -1 means the `irot` property is absent.
    ///
    /// @return the clockwise-equivalent rotation in degrees, or -1 when absent
    public int rotationDegreesClockwise() {
        return rotationCode < 0 ? -1 : ((4 - rotationCode) & 3) * 90;
    }

    /// Returns the counter-clockwise rotation in degrees encoded by `irot`.
    ///
    /// A value of -1 means the `irot` property is absent.
    ///
    /// @return the counter-clockwise rotation in degrees, or -1 when absent
    public int rotationDegreesCounterClockwise() {
        return rotationCode < 0 ? -1 : rotationCode * 90;
    }

    /// Returns whether an `imir` mirror transform is present.
    ///
    /// @return whether an `imir` mirror transform is present
    public boolean hasMirror() {
        return mirrorAxis >= 0;
    }

    /// Returns the AVIF `imir` mirror axis.
    ///
    /// A value of 0 mirrors over the horizontal axis, 1 mirrors over the vertical axis,
    /// and -1 means the property is absent.
    ///
    /// @return the mirror axis, or -1 when absent
    public int mirrorAxis() {
        return mirrorAxis;
    }

    /// Returns whether clean-aperture parameters represent an absent property.
    ///
    /// @param cleanApertureCropX the clean-aperture crop x coordinate
    /// @param cleanApertureCropY the clean-aperture crop y coordinate
    /// @param cleanApertureCropWidth the clean-aperture crop width
    /// @param cleanApertureCropHeight the clean-aperture crop height
    /// @return whether the clean-aperture property is absent
    private static boolean isAbsentCleanAperture(
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight
    ) {
        return cleanApertureCropX == -1
                && cleanApertureCropY == -1
                && cleanApertureCropWidth == -1
                && cleanApertureCropHeight == -1;
    }
}
