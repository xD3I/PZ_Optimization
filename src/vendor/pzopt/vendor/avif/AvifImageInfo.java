// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Immutable metadata for one parsed AVIF image.

public final class AvifImageInfo {
    /// The display width in pixels.
    private final int width;
    /// The display height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final AvifBitDepth bitDepth;
    /// The AV1 chroma sampling layout.
    private final Av1ChromaFormat chromaFormat;
    /// Whether an alpha auxiliary image is present.
    private final boolean alphaPresent;
    /// Whether alpha presence was declared independently of auxiliary image metadata.
    private final boolean explicitAlphaPresent;
    /// Whether color samples are premultiplied by the alpha auxiliary image.
    private final boolean alphaPremultiplied;
    /// The typed sequence descriptor for animated inputs, or `null` for still images.
    private final AvifSequenceInfo sequenceInfo;
    /// The typed image-transform descriptor, or `null` when no image transform is present.
    private final AvifImageTransformInfo transformInfo;
    /// Auxiliary image type strings associated with the primary image.
    private final String [] auxiliaryImageTypes;
    /// Auxiliary image descriptors associated with the primary image.
    private final AvifAuxiliaryImageInfo [] auxiliaryImages;
    /// The gain-map descriptor associated with the primary image, or `null`.
    private final AvifGainMapInfo gainMapInfo;
    /// The parsed color information, or `null`.
    private final AvifColorInfo colorInfo;
    /// The embedded ICC profile payload, or `null`.
    private final ByteBuffer iccProfile;
    /// The embedded Exif metadata payload, or `null`.
    private final ByteBuffer exif;
    /// The embedded XMP metadata payload, or `null`.
    private final ByteBuffer xmp;
    /// Opaque item properties associated with the primary image item.
    private final AvifOpaqueItemProperty [] opaqueItemProperties;

    /// Creates image metadata without optional container metadata.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the AV1 chroma sampling layout
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat
    ) {
        this(
                width,
                height,
                bitDepth,
                chromaFormat,
                null,
                null,
                new String[0],
                new AvifAuxiliaryImageInfo[0],
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                new AvifOpaqueItemProperty[0]
        );
    }

    /// Creates image metadata from normalized immutable component storage.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the AV1 chroma sampling layout
    /// @param sequenceInfo the sequence descriptor, or `null`
    /// @param transformInfo the image-transform descriptor, or `null`
    /// @param auxiliaryImageTypes immutable auxiliary image type storage
    /// @param auxiliaryImages immutable auxiliary image descriptor storage
    /// @param explicitAlphaPresent whether alpha presence was declared independently of auxiliary metadata
    /// @param alphaPremultiplied whether color samples are premultiplied by alpha
    /// @param gainMapInfo the gain-map descriptor, or `null`
    /// @param colorInfo the color descriptor, or `null`
    /// @param iccProfile immutable ICC profile storage, or `null`
    /// @param exif immutable Exif storage, or `null`
    /// @param xmp immutable XMP storage, or `null`
    /// @param opaqueItemProperties immutable opaque-property storage
    @SuppressWarnings("checkstyle:ParameterNumber")
    private AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            AvifSequenceInfo sequenceInfo,
            AvifImageTransformInfo transformInfo,
            String [] auxiliaryImageTypes,
            AvifAuxiliaryImageInfo [] auxiliaryImages,
            boolean explicitAlphaPresent,
            boolean alphaPremultiplied,
            AvifGainMapInfo gainMapInfo,
            AvifColorInfo colorInfo,
            ByteBuffer iccProfile,
            ByteBuffer exif,
            ByteBuffer xmp,
            AvifOpaqueItemProperty [] opaqueItemProperties
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        this.width = width;
        this.height = height;
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        this.sequenceInfo = sequenceInfo;
        this.transformInfo = transformInfo;
        this.auxiliaryImageTypes = auxiliaryImageTypes;
        this.auxiliaryImages = auxiliaryImages;
        this.explicitAlphaPresent = explicitAlphaPresent;
        this.alphaPresent = explicitAlphaPresent
                || containsAuxiliaryImageType(auxiliaryImageTypes, AvifAuxiliaryImageInfo.ALPHA_TYPE)
                || containsAlphaAuxiliaryImage(auxiliaryImages);
        this.alphaPremultiplied = this.alphaPresent && alphaPremultiplied;
        this.gainMapInfo = gainMapInfo;
        this.colorInfo = colorInfo;
        this.iccProfile = iccProfile;
        this.exif = exif;
        this.xmp = xmp;
        this.opaqueItemProperties = opaqueItemProperties;
    }

    /// Returns metadata with the requested sequence descriptor.
    ///
    /// @param sequenceInfo the sequence descriptor, or `null` for a still image
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifImageInfo withSequenceInfo(AvifSequenceInfo sequenceInfo) {
        return this.sequenceInfo == sequenceInfo ? this : copy(sequenceInfo, transformInfo, auxiliaryImageTypes,
                auxiliaryImages, explicitAlphaPresent, alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif,
                xmp, opaqueItemProperties);
    }

    /// Returns metadata with the requested image-transform descriptor.
    ///
    /// @param transformInfo the image-transform descriptor, or `null` when absent
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifImageInfo withTransformInfo(AvifImageTransformInfo transformInfo) {
        return this.transformInfo == transformInfo ? this : copy(sequenceInfo, transformInfo, auxiliaryImageTypes,
                auxiliaryImages, explicitAlphaPresent, alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif,
                xmp, opaqueItemProperties);
    }

    /// Returns metadata with the requested auxiliary image information.
    ///
    /// When `auxiliaryImageTypes` is `null`, the type strings are derived from `auxiliaryImages`.
    /// Both arrays are copied, and `null` arrays are treated as empty.
    ///
    /// @param auxiliaryImageTypes the auxiliary image type strings, or `null` to derive them
    /// @param auxiliaryImages the auxiliary image descriptors, or `null` when absent
    /// @return a new metadata value
    public AvifImageInfo withAuxiliaryImages(
            String [] auxiliaryImageTypes,
            AvifAuxiliaryImageInfo [] auxiliaryImages
    ) {
        AvifAuxiliaryImageInfo [] checkedImages = immutableAuxiliaryImages(auxiliaryImages);
        String [] checkedTypes = auxiliaryImageTypes == null
                ? auxiliaryImageTypesFromDescriptors(checkedImages)
                : immutableAuxiliaryImageTypes(auxiliaryImageTypes);
        return copy(sequenceInfo, transformInfo, checkedTypes, checkedImages, explicitAlphaPresent,
                alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif, xmp, opaqueItemProperties);
    }

    /// Returns metadata with explicitly declared alpha semantics.
    ///
    /// Auxiliary alpha metadata continues to imply alpha presence even when `alphaPresent` is `false`.
    /// Premultiplication is disabled when the resulting metadata has no alpha channel.
    ///
    /// @param alphaPresent whether alpha is present independently of auxiliary metadata
    /// @param alphaPremultiplied whether source color samples are premultiplied by alpha
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifImageInfo withAlpha(boolean alphaPresent, boolean alphaPremultiplied) {
        boolean normalizedPremultiplied = (alphaPresent || containsAlphaMetadata()) && alphaPremultiplied;
        if (explicitAlphaPresent == alphaPresent && this.alphaPremultiplied == normalizedPremultiplied) {
            return this;
        }
        return copy(sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, alphaPresent,
                normalizedPremultiplied, gainMapInfo, colorInfo, iccProfile, exif, xmp, opaqueItemProperties);
    }

    /// Returns metadata with the requested gain-map descriptor.
    ///
    /// @param gainMapInfo the gain-map descriptor, or `null` when absent
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifImageInfo withGainMapInfo(AvifGainMapInfo gainMapInfo) {
        return this.gainMapInfo == gainMapInfo ? this : copy(sequenceInfo, transformInfo, auxiliaryImageTypes,
                auxiliaryImages, explicitAlphaPresent, alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif,
                xmp, opaqueItemProperties);
    }

    /// Returns metadata with the requested color descriptor.
    ///
    /// @param colorInfo the color descriptor, or `null` when absent
    /// @return this metadata when unchanged, otherwise a new metadata value
    public AvifImageInfo withColorInfo(AvifColorInfo colorInfo) {
        return this.colorInfo == colorInfo ? this : copy(sequenceInfo, transformInfo, auxiliaryImageTypes,
                auxiliaryImages, explicitAlphaPresent, alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif,
                xmp, opaqueItemProperties);
    }

    /// Returns metadata with an embedded ICC profile.
    ///
    /// @param iccProfile the profile payload, or `null` to remove it; the array is copied
    /// @return a new metadata value
    public AvifImageInfo withIccProfile(byte [] iccProfile) {
        return copy(sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent,
                alphaPremultiplied, gainMapInfo, colorInfo, immutableBytes(iccProfile), exif, xmp,
                opaqueItemProperties);
    }

    /// Returns metadata with embedded Exif data.
    ///
    /// @param exif the payload excluding the AVIF Exif header offset field, or `null` to remove it; the array is copied
    /// @return a new metadata value
    public AvifImageInfo withExif(byte [] exif) {
        return copy(sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent,
                alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, immutableBytes(exif), xmp,
                opaqueItemProperties);
    }

    /// Returns metadata with embedded XMP data.
    ///
    /// @param xmp the XMP payload, or `null` to remove it; the array is copied
    /// @return a new metadata value
    public AvifImageInfo withXmp(byte [] xmp) {
        return copy(sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent,
                alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif, immutableBytes(xmp),
                opaqueItemProperties);
    }

    /// Returns metadata with opaque primary-item properties.
    ///
    /// @param opaqueItemProperties the property descriptors, or `null` when absent; the array is copied
    /// @return a new metadata value
    public AvifImageInfo withOpaqueItemProperties(AvifOpaqueItemProperty [] opaqueItemProperties) {
        return copy(sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent,
                alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif, xmp,
                immutableOpaqueItemProperties(opaqueItemProperties));
    }

    /// Returns the display width in pixels.
    ///
    /// @return the display width in pixels
    public int width() {
        return width;
    }

    /// Returns the display height in pixels.
    ///
    /// @return the display height in pixels
    public int height() {
        return height;
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the AV1 chroma sampling layout.
    ///
    /// @return the AV1 chroma sampling layout
    public Av1ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /// Returns whether an alpha auxiliary image is present.
    ///
    /// @return whether an alpha auxiliary image is present
    public boolean alphaPresent() {
        return alphaPresent;
    }

    /// Returns whether color samples are premultiplied by the alpha auxiliary image.
    ///
    /// Decoded [AvifFrame] pixels are still exposed as non-premultiplied ARGB. This flag reports the
    /// source container semantics before conversion.
    ///
    /// @return whether the source color samples are premultiplied by alpha
    public boolean alphaPremultiplied() {
        return alphaPremultiplied;
    }

    /// Returns whether the input is an animated image sequence.
    ///
    /// @return whether the input is an animated image sequence
    public boolean animated() {
        return sequenceInfo != null;
    }

    /// Returns the number of frames advertised by the container.
    ///
    /// @return the number of frames advertised by the container
    public int frameCount() {
        return sequenceInfo == null ? 1 : sequenceInfo.frameCount();
    }

    /// Returns the typed animated-sequence descriptor.
    ///
    /// Still images return `null`.
    ///
    /// @return the sequence descriptor, or `null`
    public AvifSequenceInfo sequenceInfo() {
        return sequenceInfo;
    }

    /// Returns the typed AVIF image-transform descriptor.
    ///
    /// Inputs without `clap`, `irot`, or `imir` item properties return `null`.
    ///
    /// @return the image-transform descriptor, or `null`
    public AvifImageTransformInfo transformInfo() {
        return transformInfo;
    }

    /// Returns auxiliary image type strings associated with the primary image.
    ///
    /// The returned array includes alpha auxiliary image types when present.
    ///
    /// @return auxiliary image type strings associated with the primary image
    public String [] auxiliaryImageTypes() {
        return auxiliaryImageTypes.clone();
    }

    /// Returns auxiliary image descriptors associated with the primary image.
    ///
    /// The returned array includes alpha auxiliary images when present.
    ///
    /// @return auxiliary image descriptors associated with the primary image
    public AvifAuxiliaryImageInfo [] auxiliaryImages() {
        return auxiliaryImages.clone();
    }

    /// Returns the gain-map descriptor associated with the primary image.
    ///
    /// The returned descriptor is present only when the file advertises the `tmap` brand and the
    /// `tmap` item is the preferred alternative to the primary image item. The descriptor does not
    /// imply that gain-map pixels have been tone-mapped or applied to the base image.
    ///
    /// @return the gain-map descriptor, or `null`
    public AvifGainMapInfo gainMapInfo() {
        return gainMapInfo;
    }

    /// Returns the parsed color information.
    ///
    /// @return the parsed color information, or `null`
    public AvifColorInfo colorInfo() {
        return colorInfo;
    }

    /// Returns the embedded ICC profile payload.
    ///
    /// @return a read-only view of the embedded ICC profile payload, or `null`
    public ByteBuffer iccProfile() {
        return byteView(iccProfile);
    }

    /// Returns the embedded Exif metadata payload.
    ///
    /// The returned payload excludes the AVIF `exif_tiff_header_offset` field
    /// and matches the Exif byte sequence exposed by libavif.
    ///
    /// @return a read-only view of the embedded Exif metadata payload, or `null`
    public ByteBuffer exif() {
        return byteView(exif);
    }

    /// Returns the embedded XMP metadata payload.
    ///
    /// @return a read-only view of the embedded XMP metadata payload, or `null`
    public ByteBuffer xmp() {
        return byteView(xmp);
    }

    /// Returns opaque item properties associated with the primary image item.
    ///
    /// The reader stores properties whose box type is not interpreted as a typed AVIF feature. The
    /// returned array is empty when no such properties are associated with the primary image item.
    ///
    /// @return opaque item properties associated with the primary image item
    public AvifOpaqueItemProperty [] opaqueItemProperties() {
        return opaqueItemProperties.clone();
    }

    /// Creates a copy with normalized immutable component storage.
    ///
    /// @param sequenceInfo the sequence descriptor, or `null`
    /// @param transformInfo the image-transform descriptor, or `null`
    /// @param auxiliaryImageTypes immutable auxiliary image type storage
    /// @param auxiliaryImages immutable auxiliary image descriptor storage
    /// @param explicitAlphaPresent whether alpha presence was declared independently of auxiliary metadata
    /// @param alphaPremultiplied whether source color samples are premultiplied by alpha
    /// @param gainMapInfo the gain-map descriptor, or `null`
    /// @param colorInfo the color descriptor, or `null`
    /// @param iccProfile immutable ICC profile storage, or `null`
    /// @param exif immutable Exif storage, or `null`
    /// @param xmp immutable XMP storage, or `null`
    /// @param opaqueItemProperties immutable opaque-property storage
    /// @return the copied metadata
    @SuppressWarnings("checkstyle:ParameterNumber")
    private AvifImageInfo copy(
            AvifSequenceInfo sequenceInfo,
            AvifImageTransformInfo transformInfo,
            String [] auxiliaryImageTypes,
            AvifAuxiliaryImageInfo [] auxiliaryImages,
            boolean explicitAlphaPresent,
            boolean alphaPremultiplied,
            AvifGainMapInfo gainMapInfo,
            AvifColorInfo colorInfo,
            ByteBuffer iccProfile,
            ByteBuffer exif,
            ByteBuffer xmp,
            AvifOpaqueItemProperty [] opaqueItemProperties
    ) {
        return new AvifImageInfo(width, height, bitDepth, chromaFormat, sequenceInfo, transformInfo,
                auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent, alphaPremultiplied, gainMapInfo,
                colorInfo, iccProfile, exif, xmp, opaqueItemProperties);
    }

    /// Returns whether the current auxiliary metadata identifies an alpha image.
    ///
    /// @return whether auxiliary alpha metadata is present
    private boolean containsAlphaMetadata() {
        return containsAuxiliaryImageType(auxiliaryImageTypes, AvifAuxiliaryImageInfo.ALPHA_TYPE)
                || containsAlphaAuxiliaryImage(auxiliaryImages);
    }

    /// Creates immutable byte-buffer storage for one optional payload.
    ///
    /// @param bytes the source bytes, or `null`
    /// @return immutable byte-buffer storage, or `null`
    private static ByteBuffer immutableBytes(byte [] bytes) {
        if (bytes == null) {
            return null;
        }
        return ByteBuffer.wrap(Arrays.copyOf(bytes, bytes.length)).asReadOnlyBuffer();
    }

    /// Creates immutable storage for auxiliary image type strings.
    ///
    /// @param auxiliaryImageTypes the source auxiliary image type strings, or `null`
    /// @return immutable auxiliary image type storage
    private static String [] immutableAuxiliaryImageTypes(String [] auxiliaryImageTypes) {
        if (auxiliaryImageTypes == null || auxiliaryImageTypes.length == 0) {
            return new String[0];
        }
        String[] result = auxiliaryImageTypes.clone();
        for (String auxiliaryImageType : result) {
            Objects.requireNonNull(auxiliaryImageType, "auxiliaryImageTypes element");
        }
        return result;
    }

    /// Creates immutable storage for auxiliary image descriptors.
    ///
    /// @param auxiliaryImages the source auxiliary image descriptors, or `null`
    /// @return immutable auxiliary image descriptor storage
    private static AvifAuxiliaryImageInfo [] immutableAuxiliaryImages(
            AvifAuxiliaryImageInfo [] auxiliaryImages
    ) {
        if (auxiliaryImages == null || auxiliaryImages.length == 0) {
            return new AvifAuxiliaryImageInfo[0];
        }
        AvifAuxiliaryImageInfo[] result = auxiliaryImages.clone();
        for (AvifAuxiliaryImageInfo auxiliaryImage : result) {
            Objects.requireNonNull(auxiliaryImage, "auxiliaryImages element");
        }
        return result;
    }

    /// Creates immutable storage for opaque item property descriptors.
    ///
    /// @param opaqueItemProperties the source opaque item property descriptors, or `null`
    /// @return immutable item property descriptor storage
    private static AvifOpaqueItemProperty [] immutableOpaqueItemProperties(
            AvifOpaqueItemProperty [] opaqueItemProperties
    ) {
        if (opaqueItemProperties == null || opaqueItemProperties.length == 0) {
            return new AvifOpaqueItemProperty[0];
        }
        AvifOpaqueItemProperty[] result = opaqueItemProperties.clone();
        for (AvifOpaqueItemProperty opaqueItemProperty : result) {
            Objects.requireNonNull(opaqueItemProperty, "opaqueItemProperties element");
        }
        return result;
    }

    /// Creates auxiliary image type storage from auxiliary image descriptors.
    ///
    /// @param auxiliaryImages the auxiliary image descriptors
    /// @return auxiliary image type storage
    private static String [] auxiliaryImageTypesFromDescriptors(
            AvifAuxiliaryImageInfo [] auxiliaryImages
    ) {
        if (auxiliaryImages.length == 0) {
            return new String[0];
        }
        String[] result = new String[auxiliaryImages.length];
        int size = 0;
        for (AvifAuxiliaryImageInfo auxiliaryImage : auxiliaryImages) {
            String type = auxiliaryImage.auxiliaryType();
            boolean seen = false;
            for (int i = 0; i < size; i++) {
                if (result[i].equals(type)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                result[size++] = type;
            }
        }
        return Arrays.copyOf(result, size);
    }

    /// Returns whether an auxiliary image type list contains the requested type.
    ///
    /// @param auxiliaryImageTypes the auxiliary image type strings
    /// @param expectedType the type to find
    /// @return whether the requested type is present
    private static boolean containsAuxiliaryImageType(
            String [] auxiliaryImageTypes,
            String expectedType
    ) {
        for (String auxiliaryImageType : auxiliaryImageTypes) {
            if (expectedType.equals(auxiliaryImageType)) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether any auxiliary image descriptor represents alpha samples.
    ///
    /// @param auxiliaryImages the auxiliary image descriptors
    /// @return whether an alpha auxiliary image is present
    private static boolean containsAlphaAuxiliaryImage(
            AvifAuxiliaryImageInfo [] auxiliaryImages
    ) {
        for (AvifAuxiliaryImageInfo auxiliaryImage : auxiliaryImages) {
            if (auxiliaryImage.isAlpha()) {
                return true;
            }
        }
        return false;
    }

    /// Returns a read-only view over immutable payload storage.
    ///
    /// @param bytes the immutable payload storage, or `null`
    /// @return a read-only view, or `null`
    private static ByteBuffer byteView(ByteBuffer bytes) {
        return bytes == null ? null : bytes.slice();
    }
}
