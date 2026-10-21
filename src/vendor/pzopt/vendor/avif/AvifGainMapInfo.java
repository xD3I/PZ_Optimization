// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.nio.ByteBuffer;
import java.util.Objects;

/// Immutable metadata for one AVIF tone-mapped gain map association.
///
/// The descriptor exposes container-level `tmap` and gain-map image item metadata. Gain-map
/// pixels can be decoded separately through `AvifImageReader`; this descriptor does not imply that
/// those pixels have been tone-mapped or applied to the base image.

public final class AvifGainMapInfo {
    /// The `tmap` derived image item id.
    private final int toneMappedImageItemId;
    /// The base image item id referenced by the `tmap` item.
    private final int baseImageItemId;
    /// The gain-map image item id referenced by the `tmap` item.
    private final int gainMapImageItemId;
    /// The BMFF item type for the tone-mapped item.
    private final String toneMappedItemType;
    /// The BMFF item type for the gain-map image item.
    private final String gainMapItemType;
    /// The tone-mapped image width in pixels, or -1 when unknown.
    private final int toneMappedWidth;
    /// The tone-mapped image height in pixels, or -1 when unknown.
    private final int toneMappedHeight;
    /// The gain-map image width in pixels, or -1 when unknown.
    private final int gainMapWidth;
    /// The gain-map image height in pixels, or -1 when unknown.
    private final int gainMapHeight;
    /// The gain-map AV1 bit depth, or `null` when unknown.
    private final AvifBitDepth gainMapBitDepth;
    /// The gain-map AV1 chroma sampling layout, or `null` when unknown.
    private final Av1ChromaFormat gainMapChromaFormat;
    /// The tone-mapped item CICP color information, or `null` when absent.
    private final AvifColorInfo toneMappedColorInfo;
    /// The tone-mapped item ICC profile payload, or `null` when absent.
    private final ByteBuffer toneMappedIccProfile;
    /// The gain-map image item CICP color information, or `null` when absent.
    private final AvifColorInfo gainMapColorInfo;
    /// The gain-map metadata version field from the `tmap` payload.
    private final int metadataVersion;
    /// The minimum metadata version required by the `tmap` payload.
    private final int metadataMinimumVersion;
    /// The writer metadata version from the `tmap` payload.
    private final int metadataWriterVersion;
    /// The parsed gain-map metadata, or `null` when the metadata version is unsupported.
    private final AvifGainMapMetadata metadata;

    /// Creates gain-map association metadata without optional image and payload descriptors.
    ///
    /// @param toneMappedImageItemId the `tmap` derived image item id
    /// @param baseImageItemId the base image item id referenced by the `tmap` item
    /// @param gainMapImageItemId the gain-map image item id referenced by the `tmap` item
    /// @param toneMappedItemType the BMFF item type for the tone-mapped item
    /// @param gainMapItemType the BMFF item type for the gain-map image item
    public AvifGainMapInfo(
            int toneMappedImageItemId,
            int baseImageItemId,
            int gainMapImageItemId,
            String toneMappedItemType,
            String gainMapItemType
    ) {
        this(
                toneMappedImageItemId,
                baseImageItemId,
                gainMapImageItemId,
                toneMappedItemType,
                gainMapItemType,
                -1,
                -1,
                -1,
                -1,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                0,
                null
        );
    }

    /// Creates normalized gain-map association metadata.
    ///
    /// @param toneMappedImageItemId the `tmap` derived image item id
    /// @param baseImageItemId the base image item id referenced by the `tmap` item
    /// @param gainMapImageItemId the gain-map image item id referenced by the `tmap` item
    /// @param toneMappedItemType the BMFF item type for the tone-mapped item
    /// @param gainMapItemType the BMFF item type for the gain-map image item
    /// @param toneMappedWidth the tone-mapped image width in pixels, or -1 when unknown
    /// @param toneMappedHeight the tone-mapped image height in pixels, or -1 when unknown
    /// @param gainMapWidth the gain-map image width in pixels, or -1 when unknown
    /// @param gainMapHeight the gain-map image height in pixels, or -1 when unknown
    /// @param gainMapBitDepth the gain-map AV1 bit depth, or `null` when unknown
    /// @param gainMapChromaFormat the gain-map AV1 chroma sampling layout, or `null` when unknown
    /// @param toneMappedColorInfo the tone-mapped color information, or `null`
    /// @param toneMappedIccProfile immutable tone-mapped ICC profile storage, or `null`
    /// @param gainMapColorInfo the gain-map color information, or `null`
    /// @param metadataVersion the metadata version field
    /// @param metadataMinimumVersion the minimum metadata version field
    /// @param metadataWriterVersion the writer metadata version field
    /// @param metadata parsed metadata, or `null`
    @SuppressWarnings("checkstyle:ParameterNumber")
    private AvifGainMapInfo(
            int toneMappedImageItemId,
            int baseImageItemId,
            int gainMapImageItemId,
            String toneMappedItemType,
            String gainMapItemType,
            int toneMappedWidth,
            int toneMappedHeight,
            int gainMapWidth,
            int gainMapHeight,
            AvifBitDepth gainMapBitDepth,
            Av1ChromaFormat gainMapChromaFormat,
            AvifColorInfo toneMappedColorInfo,
            ByteBuffer toneMappedIccProfile,
            AvifColorInfo gainMapColorInfo,
            int metadataVersion,
            int metadataMinimumVersion,
            int metadataWriterVersion,
            AvifGainMapMetadata metadata
    ) {
        if (toneMappedImageItemId <= 0) {
            throw new IllegalArgumentException("toneMappedImageItemId <= 0: " + toneMappedImageItemId);
        }
        if (baseImageItemId <= 0) {
            throw new IllegalArgumentException("baseImageItemId <= 0: " + baseImageItemId);
        }
        if (gainMapImageItemId <= 0) {
            throw new IllegalArgumentException("gainMapImageItemId <= 0: " + gainMapImageItemId);
        }
        if (!isKnownSize(toneMappedWidth, toneMappedHeight) && !isUnknownSize(toneMappedWidth, toneMappedHeight)) {
            throw new IllegalArgumentException("toneMappedWidth and toneMappedHeight must both be positive or both be -1");
        }
        if (!isKnownSize(gainMapWidth, gainMapHeight) && !isUnknownSize(gainMapWidth, gainMapHeight)) {
            throw new IllegalArgumentException("gainMapWidth and gainMapHeight must both be positive or both be -1");
        }
        if ((gainMapBitDepth == null) != (gainMapChromaFormat == null)) {
            throw new IllegalArgumentException("gainMapBitDepth and gainMapChromaFormat must both be present or both be null");
        }
        if (metadataVersion < 0 || metadataMinimumVersion < 0 || metadataWriterVersion < 0) {
            throw new IllegalArgumentException("metadata version fields must be non-negative");
        }
        this.toneMappedImageItemId = toneMappedImageItemId;
        this.baseImageItemId = baseImageItemId;
        this.gainMapImageItemId = gainMapImageItemId;
        this.toneMappedItemType = Objects.requireNonNull(toneMappedItemType, "toneMappedItemType");
        this.gainMapItemType = Objects.requireNonNull(gainMapItemType, "gainMapItemType");
        this.toneMappedWidth = toneMappedWidth;
        this.toneMappedHeight = toneMappedHeight;
        this.gainMapWidth = gainMapWidth;
        this.gainMapHeight = gainMapHeight;
        this.gainMapBitDepth = gainMapBitDepth;
        this.gainMapChromaFormat = gainMapChromaFormat;
        this.toneMappedColorInfo = toneMappedColorInfo;
        this.toneMappedIccProfile = toneMappedIccProfile;
        this.gainMapColorInfo = gainMapColorInfo;
        this.metadataVersion = metadataVersion;
        this.metadataMinimumVersion = metadataMinimumVersion;
        this.metadataWriterVersion = metadataWriterVersion;
        this.metadata = metadata;
    }

    /// Returns metadata with the requested tone-mapped image dimensions.
    ///
    /// Both dimensions must be positive, or both must be `-1` to represent an unknown size.
    ///
    /// @param width the image width in pixels, or `-1`
    /// @param height the image height in pixels, or `-1`
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifGainMapInfo withToneMappedSize(int width, int height) {
        return width == toneMappedWidth && height == toneMappedHeight ? this : copy(
                width, height, gainMapWidth, gainMapHeight, gainMapBitDepth, gainMapChromaFormat,
                toneMappedColorInfo, toneMappedIccProfile, gainMapColorInfo, metadataVersion,
                metadataMinimumVersion, metadataWriterVersion, metadata
        );
    }

    /// Returns metadata with the requested gain-map image description.
    ///
    /// Both dimensions must be positive, or both must be `-1`. Bit depth and chroma format must
    /// either both be present or both be `null`.
    ///
    /// @param width the image width in pixels, or `-1`
    /// @param height the image height in pixels, or `-1`
    /// @param bitDepth the gain-map bit depth, or `null` when unknown
    /// @param chromaFormat the gain-map chroma format, or `null` when unknown
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifGainMapInfo withGainMapImage(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat
    ) {
        if (width == gainMapWidth && height == gainMapHeight
                && bitDepth == gainMapBitDepth && chromaFormat == gainMapChromaFormat) {
            return this;
        }
        return copy(toneMappedWidth, toneMappedHeight, width, height, bitDepth, chromaFormat,
                toneMappedColorInfo, toneMappedIccProfile, gainMapColorInfo, metadataVersion,
                metadataMinimumVersion, metadataWriterVersion, metadata);
    }

    /// Returns metadata with the requested tone-mapped color information.
    ///
    /// @param colorInfo the color information, or `null` when absent
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifGainMapInfo withToneMappedColorInfo(AvifColorInfo colorInfo) {
        return colorInfo == toneMappedColorInfo ? this : copy(toneMappedWidth, toneMappedHeight,
                gainMapWidth, gainMapHeight, gainMapBitDepth, gainMapChromaFormat, colorInfo,
                toneMappedIccProfile, gainMapColorInfo, metadataVersion, metadataMinimumVersion,
                metadataWriterVersion, metadata);
    }

    /// Returns metadata with the requested tone-mapped ICC profile.
    ///
    /// @param iccProfile the ICC profile payload, or `null` to remove it; the array is copied
    /// @return a new metadata value
    public AvifGainMapInfo withToneMappedIccProfile(byte [] iccProfile) {
        return copy(toneMappedWidth, toneMappedHeight, gainMapWidth, gainMapHeight, gainMapBitDepth,
                gainMapChromaFormat, toneMappedColorInfo, immutableBytes(iccProfile), gainMapColorInfo,
                metadataVersion, metadataMinimumVersion, metadataWriterVersion, metadata);
    }

    /// Returns metadata with the requested gain-map color information.
    ///
    /// @param colorInfo the gain-map color information, or `null` when absent
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifGainMapInfo withGainMapColorInfo(AvifColorInfo colorInfo) {
        return colorInfo == gainMapColorInfo ? this : copy(toneMappedWidth, toneMappedHeight,
                gainMapWidth, gainMapHeight, gainMapBitDepth, gainMapChromaFormat,
                toneMappedColorInfo, toneMappedIccProfile, colorInfo, metadataVersion,
                metadataMinimumVersion, metadataWriterVersion, metadata);
    }

    /// Returns metadata with the requested version fields and parsed payload.
    ///
    /// @param version the metadata version field
    /// @param minimumVersion the minimum required metadata version field
    /// @param writerVersion the writer metadata version field
    /// @param metadata the parsed metadata, or `null` when the version is unsupported
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifGainMapInfo withMetadata(
            int version,
            int minimumVersion,
            int writerVersion,
            AvifGainMapMetadata metadata
    ) {
        if (version == metadataVersion && minimumVersion == metadataMinimumVersion
                && writerVersion == metadataWriterVersion && metadata == this.metadata) {
            return this;
        }
        return copy(toneMappedWidth, toneMappedHeight, gainMapWidth, gainMapHeight, gainMapBitDepth,
                gainMapChromaFormat, toneMappedColorInfo, toneMappedIccProfile, gainMapColorInfo,
                version, minimumVersion, writerVersion, metadata);
    }

    /// Returns the `tmap` derived image item id.
    ///
    /// @return the `tmap` derived image item id
    public int toneMappedImageItemId() {
        return toneMappedImageItemId;
    }

    /// Returns the base image item id referenced by the `tmap` item.
    ///
    /// @return the base image item id
    public int baseImageItemId() {
        return baseImageItemId;
    }

    /// Returns the gain-map image item id referenced by the `tmap` item.
    ///
    /// @return the gain-map image item id
    public int gainMapImageItemId() {
        return gainMapImageItemId;
    }

    /// Returns the BMFF item type for the tone-mapped item.
    ///
    /// @return the BMFF item type for the tone-mapped item
    public String toneMappedItemType() {
        return toneMappedItemType;
    }

    /// Returns the BMFF item type for the gain-map image item.
    ///
    /// @return the BMFF item type for the gain-map image item
    public String gainMapItemType() {
        return gainMapItemType;
    }

    /// Returns the tone-mapped image width in pixels.
    ///
    /// @return the tone-mapped image width in pixels, or -1 when unknown
    public int toneMappedWidth() {
        return toneMappedWidth;
    }

    /// Returns the tone-mapped image height in pixels.
    ///
    /// @return the tone-mapped image height in pixels, or -1 when unknown
    public int toneMappedHeight() {
        return toneMappedHeight;
    }

    /// Returns the gain-map image width in pixels.
    ///
    /// @return the gain-map image width in pixels, or -1 when unknown
    public int gainMapWidth() {
        return gainMapWidth;
    }

    /// Returns the gain-map image height in pixels.
    ///
    /// @return the gain-map image height in pixels, or -1 when unknown
    public int gainMapHeight() {
        return gainMapHeight;
    }

    /// Returns the gain-map AV1 bit depth.
    ///
    /// @return the gain-map AV1 bit depth, or `null` when unknown
    public AvifBitDepth gainMapBitDepth() {
        return gainMapBitDepth;
    }

    /// Returns the gain-map AV1 chroma sampling layout.
    ///
    /// @return the gain-map AV1 chroma sampling layout, or `null` when unknown
    public Av1ChromaFormat gainMapChromaFormat() {
        return gainMapChromaFormat;
    }

    /// Returns the tone-mapped item CICP color information.
    ///
    /// This describes the alternate image represented by the `tmap` derived item. It is distinct
    /// from the base image color information exposed by `AvifImageInfo.colorInfo()`.
    ///
    /// @return the tone-mapped item CICP color information, or `null` when absent
    public AvifColorInfo toneMappedColorInfo() {
        return toneMappedColorInfo;
    }

    /// Returns the tone-mapped item ICC profile payload.
    ///
    /// ICC profiles are exposed as metadata only; the pure-Java decoder does not apply ICC color
    /// transforms at runtime.
    ///
    /// @return the tone-mapped item ICC profile payload, or `null` when absent
    public ByteBuffer toneMappedIccProfile() {
        return byteView(toneMappedIccProfile);
    }

    /// Returns the gain-map image item CICP color information.
    ///
    /// The gain-map image uses this CICP information when its YUV planes are converted into RGB
    /// gain-map samples.
    ///
    /// @return the gain-map image item CICP color information, or `null` when absent
    public AvifColorInfo gainMapColorInfo() {
        return gainMapColorInfo;
    }

    /// Returns the gain-map metadata version field from the `tmap` payload.
    ///
    /// @return the gain-map metadata version field
    public int metadataVersion() {
        return metadataVersion;
    }

    /// Returns the minimum metadata version required by the `tmap` payload.
    ///
    /// @return the minimum metadata version required by the `tmap` payload
    public int metadataMinimumVersion() {
        return metadataMinimumVersion;
    }

    /// Returns the writer metadata version from the `tmap` payload.
    ///
    /// @return the writer metadata version from the `tmap` payload
    public int metadataWriterVersion() {
        return metadataWriterVersion;
    }

    /// Returns whether this implementation can parse the advertised gain-map metadata version.
    ///
    /// @return whether the gain-map metadata version is supported
    public boolean metadataSupported() {
        return metadata != null;
    }

    /// Returns the parsed gain-map metadata.
    ///
    /// @return the parsed gain-map metadata, or `null` when unsupported
    public AvifGainMapMetadata metadata() {
        return metadata;
    }

    /// Creates a copy with the requested optional descriptors.
    ///
    /// @param toneMappedWidth the tone-mapped width
    /// @param toneMappedHeight the tone-mapped height
    /// @param gainMapWidth the gain-map width
    /// @param gainMapHeight the gain-map height
    /// @param gainMapBitDepth the gain-map bit depth, or `null`
    /// @param gainMapChromaFormat the gain-map chroma format, or `null`
    /// @param toneMappedColorInfo the tone-mapped color information, or `null`
    /// @param toneMappedIccProfile immutable tone-mapped ICC profile storage, or `null`
    /// @param gainMapColorInfo the gain-map color information, or `null`
    /// @param metadataVersion the metadata version
    /// @param metadataMinimumVersion the minimum metadata version
    /// @param metadataWriterVersion the writer metadata version
    /// @param metadata the parsed metadata, or `null`
    /// @return the copied metadata
    @SuppressWarnings("checkstyle:ParameterNumber")
    private AvifGainMapInfo copy(
            int toneMappedWidth,
            int toneMappedHeight,
            int gainMapWidth,
            int gainMapHeight,
            AvifBitDepth gainMapBitDepth,
            Av1ChromaFormat gainMapChromaFormat,
            AvifColorInfo toneMappedColorInfo,
            ByteBuffer toneMappedIccProfile,
            AvifColorInfo gainMapColorInfo,
            int metadataVersion,
            int metadataMinimumVersion,
            int metadataWriterVersion,
            AvifGainMapMetadata metadata
    ) {
        return new AvifGainMapInfo(toneMappedImageItemId, baseImageItemId, gainMapImageItemId,
                toneMappedItemType, gainMapItemType, toneMappedWidth, toneMappedHeight, gainMapWidth,
                gainMapHeight, gainMapBitDepth, gainMapChromaFormat, toneMappedColorInfo,
                toneMappedIccProfile, gainMapColorInfo, metadataVersion, metadataMinimumVersion,
                metadataWriterVersion, metadata);
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

    /// Creates immutable byte storage.
    ///
    /// @param bytes the source bytes, or `null`
    /// @return immutable byte storage, or `null`
    private static ByteBuffer immutableBytes(byte [] bytes) {
        if (bytes == null) {
            return null;
        }
        return ByteBuffer.wrap(bytes.clone()).asReadOnlyBuffer();
    }

    /// Creates a read-only byte-buffer view.
    ///
    /// @param bytes the immutable byte storage, or `null`
    /// @return a read-only view, or `null`
    private static ByteBuffer byteView(ByteBuffer bytes) {
        if (bytes == null) {
            return null;
        }
        return bytes.asReadOnlyBuffer();
    }
}
