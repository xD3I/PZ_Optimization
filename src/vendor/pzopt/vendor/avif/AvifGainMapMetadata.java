// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.util.Arrays;
import java.util.Objects;

/// Parsed ISO 21496-1 gain-map metadata from an AVIF `tmap` item.
///
/// The per-channel arrays always contain three RGB entries. Single-channel metadata is expanded
/// by repeating channel 0 into channels 1 and 2, matching the AVIF gain-map metadata syntax.
///
/// @param multichannel whether the source metadata signaled three independent channels
/// @param useBaseColorSpace whether tone mapping should be performed in the base image color space
/// @param baseHdrHeadroom the log2-encoded HDR headroom of the base image
/// @param alternateHdrHeadroom the log2-encoded HDR headroom of the alternate image
/// @param gainMapMin per-channel minimum gain-map values in log2 space
/// @param gainMapMax per-channel maximum gain-map values in log2 space
/// @param gainMapGamma per-channel gain-map encoding gamma values
/// @param baseOffset per-channel base image offsets
/// @param alternateOffset per-channel alternate image offsets

public record AvifGainMapMetadata(
        boolean multichannel,
        boolean useBaseColorSpace,
        AvifUnsignedFraction baseHdrHeadroom,
        AvifUnsignedFraction alternateHdrHeadroom,
        AvifSignedFraction [] gainMapMin,
        AvifSignedFraction [] gainMapMax,
        AvifUnsignedFraction [] gainMapGamma,
        AvifSignedFraction [] baseOffset,
        AvifSignedFraction [] alternateOffset
) {
    /// The number of RGB channel entries exposed by gain-map metadata arrays.
    private static final int CHANNEL_COUNT = 3;

    /// Creates parsed gain-map metadata.
    ///
    /// @param multichannel whether the source metadata signaled three independent channels
    /// @param useBaseColorSpace whether tone mapping should be performed in the base image color space
    /// @param baseHdrHeadroom the log2-encoded HDR headroom of the base image
    /// @param alternateHdrHeadroom the log2-encoded HDR headroom of the alternate image
    /// @param gainMapMin per-channel minimum gain-map values in log2 space
    /// @param gainMapMax per-channel maximum gain-map values in log2 space
    /// @param gainMapGamma per-channel gain-map encoding gamma values
    /// @param baseOffset per-channel base image offsets
    /// @param alternateOffset per-channel alternate image offsets
    public AvifGainMapMetadata {
        baseHdrHeadroom = Objects.requireNonNull(baseHdrHeadroom, "baseHdrHeadroom");
        alternateHdrHeadroom = Objects.requireNonNull(alternateHdrHeadroom, "alternateHdrHeadroom");
        gainMapMin = copySignedFractions(gainMapMin, "gainMapMin");
        gainMapMax = copySignedFractions(gainMapMax, "gainMapMax");
        gainMapGamma = copyUnsignedFractions(gainMapGamma, "gainMapGamma");
        baseOffset = copySignedFractions(baseOffset, "baseOffset");
        alternateOffset = copySignedFractions(alternateOffset, "alternateOffset");

        for (int i = 0; i < CHANNEL_COUNT; i++) {
            if (compareSignedFractions(gainMapMax[i], gainMapMin[i]) < 0) {
                throw new IllegalArgumentException("gainMapMax is less than gainMapMin for channel " + i);
            }
            if (gainMapGamma[i].numerator() == 0) {
                throw new IllegalArgumentException("gainMapGamma numerator is zero for channel " + i);
            }
        }
    }

    /// Returns whether the source metadata signaled three independent channels.
    ///
    /// @return whether the source metadata signaled three independent channels
    @Override
    public boolean multichannel() {
        return multichannel;
    }

    /// Returns whether tone mapping should be performed in the base image color space.
    ///
    /// @return whether tone mapping should be performed in the base image color space
    @Override
    public boolean useBaseColorSpace() {
        return useBaseColorSpace;
    }

    /// Returns the log2-encoded HDR headroom of the base image.
    ///
    /// @return the log2-encoded HDR headroom of the base image
    @Override
    public AvifUnsignedFraction baseHdrHeadroom() {
        return baseHdrHeadroom;
    }

    /// Returns the log2-encoded HDR headroom of the alternate image.
    ///
    /// @return the log2-encoded HDR headroom of the alternate image
    @Override
    public AvifUnsignedFraction alternateHdrHeadroom() {
        return alternateHdrHeadroom;
    }

    /// Returns per-channel minimum gain-map values in log2 space.
    ///
    /// @return per-channel minimum gain-map values in log2 space
    @Override
    public AvifSignedFraction [] gainMapMin() {
        return gainMapMin.clone();
    }

    /// Returns per-channel maximum gain-map values in log2 space.
    ///
    /// @return per-channel maximum gain-map values in log2 space
    @Override
    public AvifSignedFraction [] gainMapMax() {
        return gainMapMax.clone();
    }

    /// Returns per-channel gain-map encoding gamma values.
    ///
    /// @return per-channel gain-map encoding gamma values
    @Override
    public AvifUnsignedFraction [] gainMapGamma() {
        return gainMapGamma.clone();
    }

    /// Returns per-channel base image offsets.
    ///
    /// @return per-channel base image offsets
    @Override
    public AvifSignedFraction [] baseOffset() {
        return baseOffset.clone();
    }

    /// Returns per-channel alternate image offsets.
    ///
    /// @return per-channel alternate image offsets
    @Override
    public AvifSignedFraction [] alternateOffset() {
        return alternateOffset.clone();
    }

    /// Compares this metadata with another object using per-channel array contents.
    ///
    /// @param object the object to compare with this metadata
    /// @return whether the object represents the same gain-map metadata
    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof AvifGainMapMetadata other)) {
            return false;
        }
        return multichannel == other.multichannel
                && useBaseColorSpace == other.useBaseColorSpace
                && baseHdrHeadroom.equals(other.baseHdrHeadroom)
                && alternateHdrHeadroom.equals(other.alternateHdrHeadroom)
                && Arrays.equals(gainMapMin, other.gainMapMin)
                && Arrays.equals(gainMapMax, other.gainMapMax)
                && Arrays.equals(gainMapGamma, other.gainMapGamma)
                && Arrays.equals(baseOffset, other.baseOffset)
                && Arrays.equals(alternateOffset, other.alternateOffset);
    }

    /// Returns a hash code using per-channel array contents.
    ///
    /// @return a hash code for this metadata
    @Override
    public int hashCode() {
        int result = Boolean.hashCode(multichannel);
        result = 31 * result + Boolean.hashCode(useBaseColorSpace);
        result = 31 * result + baseHdrHeadroom.hashCode();
        result = 31 * result + alternateHdrHeadroom.hashCode();
        result = 31 * result + Arrays.hashCode(gainMapMin);
        result = 31 * result + Arrays.hashCode(gainMapMax);
        result = 31 * result + Arrays.hashCode(gainMapGamma);
        result = 31 * result + Arrays.hashCode(baseOffset);
        result = 31 * result + Arrays.hashCode(alternateOffset);
        return result;
    }

    /// Returns a diagnostic string including per-channel array contents.
    ///
    /// @return a diagnostic string for this metadata
    @Override
    public String toString() {
        return "AvifGainMapMetadata["
                + "multichannel=" + multichannel
                + ", useBaseColorSpace=" + useBaseColorSpace
                + ", baseHdrHeadroom=" + baseHdrHeadroom
                + ", alternateHdrHeadroom=" + alternateHdrHeadroom
                + ", gainMapMin=" + Arrays.toString(gainMapMin)
                + ", gainMapMax=" + Arrays.toString(gainMapMax)
                + ", gainMapGamma=" + Arrays.toString(gainMapGamma)
                + ", baseOffset=" + Arrays.toString(baseOffset)
                + ", alternateOffset=" + Arrays.toString(alternateOffset)
                + ']';
    }

    /// Copies and validates one signed per-channel fraction array.
    ///
    /// @param values the source values
    /// @param name the diagnostic array name
    /// @return a defensive copy
    private static AvifSignedFraction [] copySignedFractions(
            AvifSignedFraction[] values,
            String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.length != CHANNEL_COUNT) {
            throw new IllegalArgumentException(name + " length must be 3: " + values.length);
        }
        AvifSignedFraction[] copy = values.clone();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            copy[i] = Objects.requireNonNull(copy[i], name + "[" + i + "]");
        }
        return copy;
    }

    /// Copies and validates one unsigned per-channel fraction array.
    ///
    /// @param values the source values
    /// @param name the diagnostic array name
    /// @return a defensive copy
    private static AvifUnsignedFraction [] copyUnsignedFractions(
            AvifUnsignedFraction[] values,
            String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.length != CHANNEL_COUNT) {
            throw new IllegalArgumentException(name + " length must be 3: " + values.length);
        }
        AvifUnsignedFraction[] copy = values.clone();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            copy[i] = Objects.requireNonNull(copy[i], name + "[" + i + "]");
        }
        return copy;
    }

    /// Compares two signed fractions without converting them to floating point.
    ///
    /// @param left the left fraction
    /// @param right the right fraction
    /// @return a negative value, zero, or a positive value if `left` is less than, equal to, or greater than `right`
    private static int compareSignedFractions(AvifSignedFraction left, AvifSignedFraction right) {
        long leftScaled = (long) left.numerator() * right.denominator();
        long rightScaled = (long) right.numerator() * left.denominator();
        return Long.compare(leftScaled, rightScaled);
    }
}
