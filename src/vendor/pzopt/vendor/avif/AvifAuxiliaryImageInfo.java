// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.util.Objects;

/// Immutable metadata for one auxiliary image associated with an AVIF primary image.

public final class AvifAuxiliaryImageInfo {
    /// The AVIF auxiliary image type string used for alpha images.
    public static final String ALPHA_TYPE = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha";
    /// The AVIF auxiliary image type string used for depth images.
    public static final String DEPTH_TYPE = "urn:mpeg:mpegB:cicp:systems:auxiliary:depth";

    /// The BMFF item id for the auxiliary image.
    private final int itemId;
    /// The `auxC` auxiliary image type string.
    private final String auxiliaryType;
    /// The BMFF item type for the auxiliary item.
    private final String itemType;
    /// The auxiliary image width in pixels, or -1 when unknown.
    private final int width;
    /// The auxiliary image height in pixels, or -1 when unknown.
    private final int height;
    /// The decoded AV1 bit depth, or `null` when the auxiliary item is not an AV1 image.
    private final AvifBitDepth bitDepth;
    /// The AV1 chroma sampling layout, or `null` when the auxiliary item is not an AV1 image.
    private final Av1ChromaFormat chromaFormat;

    /// Creates auxiliary image metadata.
    ///
    /// @param itemId the BMFF item id for the auxiliary image
    /// @param auxiliaryType the `auxC` auxiliary image type string
    /// @param itemType the BMFF item type for the auxiliary item
    /// @param width the auxiliary image width in pixels, or -1 when unknown
    /// @param height the auxiliary image height in pixels, or -1 when unknown
    /// @param bitDepth the decoded AV1 bit depth, or `null` when the auxiliary item is not an AV1 image
    /// @param chromaFormat the AV1 chroma sampling layout, or `null` when the auxiliary item is not an AV1 image
    public AvifAuxiliaryImageInfo(
            int itemId,
            String auxiliaryType,
            String itemType,
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat
    ) {
        if (itemId <= 0) {
            throw new IllegalArgumentException("itemId <= 0: " + itemId);
        }
        if (!isKnownSize(width, height) && !isUnknownSize(width, height)) {
            throw new IllegalArgumentException("width and height must both be positive or both be -1");
        }
        if ((bitDepth == null) != (chromaFormat == null)) {
            throw new IllegalArgumentException("bitDepth and chromaFormat must both be present or both be null");
        }

        this.itemId = itemId;
        this.auxiliaryType = Objects.requireNonNull(auxiliaryType, "auxiliaryType");
        this.itemType = Objects.requireNonNull(itemType, "itemType");
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.chromaFormat = chromaFormat;
    }

    /// Returns the BMFF item id for the auxiliary image.
    ///
    /// @return the BMFF item id
    public int itemId() {
        return itemId;
    }

    /// Returns the `auxC` auxiliary image type string.
    ///
    /// @return the auxiliary image type string
    public String auxiliaryType() {
        return auxiliaryType;
    }

    /// Returns the BMFF item type for the auxiliary item.
    ///
    /// @return the BMFF item type
    public String itemType() {
        return itemType;
    }

    /// Returns the auxiliary image width in pixels.
    ///
    /// A value of -1 means the width is unknown.
    ///
    /// @return the auxiliary image width in pixels, or -1 when unknown
    public int width() {
        return width;
    }

    /// Returns the auxiliary image height in pixels.
    ///
    /// A value of -1 means the height is unknown.
    ///
    /// @return the auxiliary image height in pixels, or -1 when unknown
    public int height() {
        return height;
    }

    /// Returns the decoded AV1 bit depth.
    ///
    /// A `null` value means the auxiliary item is not an AV1 image or does not expose `av1C`.
    ///
    /// @return the decoded AV1 bit depth, or `null`
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the AV1 chroma sampling layout.
    ///
    /// A `null` value means the auxiliary item is not an AV1 image or does not expose `av1C`.
    ///
    /// @return the AV1 chroma sampling layout, or `null`
    public Av1ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /// Returns whether this auxiliary image is an alpha image.
    ///
    /// @return whether this auxiliary image is an alpha image
    public boolean isAlpha() {
        return ALPHA_TYPE.equals(auxiliaryType);
    }

    /// Returns whether this auxiliary image is a depth image.
    ///
    /// @return whether this auxiliary image is a depth image
    public boolean isDepth() {
        return DEPTH_TYPE.equals(auxiliaryType);
    }

    /// Returns whether the supplied dimensions represent a known image size.
    ///
    /// @param width the width in pixels
    /// @param height the height in pixels
    /// @return whether both dimensions are positive
    private static boolean isKnownSize(int width, int height) {
        return width > 0 && height > 0;
    }

    /// Returns whether the supplied dimensions represent an unknown image size.
    ///
    /// @param width the width in pixels
    /// @param height the height in pixels
    /// @return whether both dimensions are -1
    private static boolean isUnknownSize(int width, int height) {
        return width == -1 && height == -1;
    }
}
