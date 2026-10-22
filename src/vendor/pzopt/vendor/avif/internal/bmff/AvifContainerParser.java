// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.bmff;

import pzopt.vendor.avif.AvifAuxiliaryImageInfo;
import pzopt.vendor.avif.AvifColorInfo;
import pzopt.vendor.avif.AvifBitDepth;
import pzopt.vendor.avif.AvifDecodeException;
import pzopt.vendor.avif.AvifErrorCode;
import pzopt.vendor.avif.AvifGainMapInfo;
import pzopt.vendor.avif.AvifGainMapMetadata;
import pzopt.vendor.avif.AvifImageInfo;
import pzopt.vendor.avif.AvifImageTransformInfo;
import pzopt.vendor.avif.AvifOpaqueItemProperty;
import pzopt.vendor.avif.AvifSequenceInfo;
import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.AvifSignedFraction;
import pzopt.vendor.avif.AvifUnsignedFraction;
import pzopt.vendor.avif.internal.io.AvifDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Parses AVIF and AVIS image sources from BMFF containers.

public final class AvifContainerParser {
    /// The MIME content type used by AVIF XMP metadata items.
    private static final String XMP_CONTENT_TYPE = "application/rdf+xml";
    /// The maximum gain-map metadata version supported by this parser.
    private static final int SUPPORTED_GAIN_MAP_METADATA_VERSION = 0;
    /// Internal marker for an indefinite track duration.
    private static final long INDEFINITE_TRACK_DURATION = -1L;
    /// The maximum number of sequence samples materialized from one track's sample tables.
    private static final int MAX_SEQUENCE_SAMPLE_COUNT = 1_000_000;
    /// The default maximum cumulative estimated size of retained non-image metadata.
    private static final long DEFAULT_METADATA_SIZE_LIMIT = 64L * 1024L * 1024L;
    /// The implementation limit for estimated retained non-image metadata.
    private static final long MAX_RETAINED_METADATA_SIZE = 256L * 1024L * 1024L;
    /// The estimated retained size of one parsed item and its collection headers.
    private static final long RETAINED_ITEM_SIZE = 256L;
    /// The estimated retained size of one parsed item property.
    private static final long RETAINED_PROPERTY_SIZE = 64L;
    /// The estimated retained size of one item extent.
    private static final long RETAINED_EXTENT_SIZE = 32L;
    /// The estimated retained size of one reference stored in a collection.
    private static final long RETAINED_REFERENCE_SIZE = 24L;
    /// The estimated retained base size of one entity group.
    private static final long RETAINED_ENTITY_GROUP_SIZE = 48L;
    /// The estimated retained size of one AVIS track parser state.
    private static final long RETAINED_MOOV_STATE_SIZE = 256L;
    /// The estimated retained size of one boxed sample-table integer.
    private static final long RETAINED_SAMPLE_INTEGER_SIZE = 24L;
    /// The estimated retained size of one sample-to-chunk entry.
    private static final long RETAINED_SAMPLE_TO_CHUNK_SIZE = 32L;
    /// The estimated retained size of one resolved sequence payload and its array entries.
    private static final long RETAINED_SEQUENCE_PAYLOAD_SIZE = 48L;

    /// The retained container data source.
    private final AvifDataSource source;
    /// The configured cumulative estimated limit for retained non-image metadata, or zero for no limit.
    private final long metadataSizeLimit;
    /// The cumulative estimated byte size reserved for retained non-image metadata.
    private long retainedMetadataSize;
    /// The parsed metadata state.
    private final MetaState meta = new MetaState();
    /// Whether an AVIF-compatible `ftyp` box was parsed.
    private boolean compatibleFileTypeSeen;
    /// Whether an `avis` brand was parsed.
    private boolean avisBrandSeen;
    /// Whether a `tmap` compatible brand was parsed.
    private boolean tmapBrandSeen;

    /// Creates an AVIF container parser.
    ///
    /// @param source the retained container data source
    /// @param metadataSizeLimit the cumulative metadata size limit, or zero for no configured limit
    private AvifContainerParser(AvifDataSource source, long metadataSizeLimit) {
        if (metadataSizeLimit < 0) {
            throw new IllegalArgumentException("metadataSizeLimit < 0: " + metadataSizeLimit);
        }
        this.source = Objects.requireNonNull(source, "source");
        this.metadataSizeLimit = metadataSizeLimit;
    }

    /// Parses AVIF container data.
    ///
    /// The returned container retains the supplied array for lazy AV1 payload reads. The caller
    /// must not modify the array while using the returned container.
    ///
    /// @param source the source bytes
    /// @return parsed AVIF container data
    /// @throws AvifDecodeException if the container is malformed or unsupported
    public static AvifContainer parse(byte[] source) throws AvifDecodeException {
        return parse(
                AvifDataSource.ofBytes(Objects.requireNonNull(source, "source")),
                DEFAULT_METADATA_SIZE_LIMIT
        );
    }

    /// Parses AVIF container data from a retained positional source.
    ///
    /// The returned container may retain payload ranges backed by `source`; the source must remain
    /// open while those payloads are decoded.
    ///
    /// @param source the retained positional source
    /// @return parsed AVIF container data
    /// @throws AvifDecodeException if the container is malformed or unsupported
    public static AvifContainer parse(AvifDataSource source) throws AvifDecodeException {
        return parse(source, DEFAULT_METADATA_SIZE_LIMIT);
    }

    /// Parses AVIF container data with a cumulative retained-metadata estimate limit.
    ///
    /// @param source the retained positional source
    /// @param metadataSizeLimit the cumulative metadata size limit in bytes, or zero for no
    ///                          configured limit
    /// @return parsed AVIF container data
    /// @throws AvifDecodeException if the container is malformed, unsupported, or exceeds the
    ///                             configured metadata limit
    public static AvifContainer parse(AvifDataSource source, long metadataSizeLimit) throws AvifDecodeException {
        return new AvifContainerParser(source, metadataSizeLimit).parse();
    }

    /// Parses AVIF container data.
    ///
    /// @return parsed AVIF container data
    /// @throws AvifDecodeException if the container is malformed or unsupported
    private AvifContainer parse() throws AvifDecodeException {
        BoxInput input = new BoxInput(source);
        Set<String> uniqueTopLevelBoxes = new HashSet<>();
        while (input.hasRemaining()) {
            BoxHeader header = input.readBoxHeader();
            if (header.sizeZero() && !allowsTopLevelSizeZero(header.type())) {
                throw parseFailed("Top-level BMFF box cannot have size 0: " + header.type(), header.offset());
            }
            BoxInput payload = input.slice(header.payloadOffset(), header.payloadSize());
            switch (header.type()) {
                case "ftyp" -> {
                    unique(uniqueTopLevelBoxes, header.type(), header.offset());
                    parseFileType(payload);
                }
                case "meta" -> {
                    unique(uniqueTopLevelBoxes, header.type(), header.offset());
                    parseMeta(header, payload);
                }
                case "moov" -> {
                    unique(uniqueTopLevelBoxes, header.type(), header.offset());
                    avisBrandSeen = true;
                    parseMoov(payload);
                }
                default -> {
                }
            }
            input.skipBoxPayload(header);
            if (forwardMetadataComplete(header.type())) {
                break;
            }
        }

        if (!compatibleFileTypeSeen) {
            throw new AvifDecodeException(AvifErrorCode.INVALID_FTYP, "Missing AVIF-compatible ftyp box", 0L);
        }
        if (avisBrandSeen) {
            return parseSequenceImage();
        }
        if (meta.primaryItemId == 0) {
            throw new AvifDecodeException(AvifErrorCode.MISSING_IMAGE_ITEM, "Primary item is not specified", null);
        }

        Item primaryItem = meta.item(meta.primaryItemId);
        if (primaryItem == null || primaryItem.hasUnsupportedEssentialProperty) {
            throw new AvifDecodeException(AvifErrorCode.MISSING_IMAGE_ITEM, "Primary item is not usable", null);
        }
        if (!"av01".equals(primaryItem.type) && !"grid".equals(primaryItem.type)) {
            throw unsupported("Unsupported primary item type: " + primaryItem.type, null);
        }

        if ("grid".equals(primaryItem.type)) {
            return parseGridContainer(primaryItem);
        }

        validateOperatingPointStructure(primaryItem, "Primary image");

        ImageSpatialExtents ispe = primaryItem.firstProperty(ImageSpatialExtents.class);
        if (ispe == null) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Primary AV1 item is missing ispe", null);
        }
        Av1Config av1Config = primaryItem.firstProperty(Av1Config.class);
        if (av1Config == null) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Primary AV1 item is missing av1C", null);
        }

        AvifImageSource alphaSource = parseAuxiliarySource(
                primaryItem,
                AvifAuxiliaryImageInfo.ALPHA_TYPE,
                "Alpha",
                ispe.width,
                ispe.height
        );
        validateItemPremultipliedAlpha(primaryItem, alphaSource, "Primary image");
        boolean alphaPremultiplied = itemAlphaPremultiplied(primaryItem);
        AvifImageSource depthSource = parseAuxiliarySource(
                primaryItem,
                AvifAuxiliaryImageInfo.DEPTH_TYPE,
                "Depth",
                ispe.width,
                ispe.height
        );
        SampleTransform sampleTransform = parseSampleTransform(
                primaryItem,
                ispe.width,
                ispe.height,
                alphaSource != null
        );

        AvifImageSource primarySource = AvifImageSource.item(
                itemPayload(primaryItem),
                operatingPoint(primaryItem),
                selectedSpatialLayer(primaryItem),
                ispe.width,
                ispe.height
        );
        MetadataPayloads metadata = collectMetadataPayloads(primaryItem);
        AvifImageTransformInfo transformInfo = extractTransformInfo(
                primaryItem,
                ispe.width,
                ispe.height
        );
        DisplaySize displaySize = transformedDisplaySize(ispe.width, ispe.height, transformInfo);
        GainMapPayloads gainMapPayloads = gainMapPayloads(primaryItem.id);
        AvifImageInfo info = new AvifImageInfo(
                displaySize.width(),
                displaySize.height(),
                sampleTransform != null
                        ? sampleTransform.bitDepth()
                        : AvifBitDepth.fromBits(av1Config.bitDepth()),
                av1Config.chromaFormat()
        ).withTransformInfo(transformInfo)
                .withAuxiliaryImages(null, auxiliaryImages(primaryItem.id, ispe.width, ispe.height))
                .withAlpha(alphaSource != null, alphaPremultiplied)
                .withGainMapInfo(gainMapPayloads.info)
                .withColorInfo(primaryItem.firstProperty(AvifColorInfo.class))
                .withIccProfile(metadata.iccProfile)
                .withExif(metadata.exif)
                .withXmp(metadata.xmp)
                .withOpaqueItemProperties(opaqueItemProperties(primaryItem));

        return new AvifContainer(
                info,
                primarySource,
                alphaSource,
                depthSource,
                gainMapPayloads.source,
                sampleTransform
        );
    }

    /// Returns whether a forward-only source has supplied all required top-level metadata.
    ///
    /// Seekable sources continue through the complete container so malformed trailing boxes remain
    /// observable. Forward-only sources stop before media payloads or unrelated trailing data and
    /// leave those bytes available for lazy frame decoding.
    ///
    /// @param parsedBoxType the type of the top-level box most recently parsed
    /// @return whether top-level parsing can finish without reading another box
    private boolean forwardMetadataComplete(String parsedBoxType) {
        if (source.isSeekable() || !compatibleFileTypeSeen) {
            return false;
        }
        if (avisBrandSeen) {
            return "moov".equals(parsedBoxType);
        }
        return "meta".equals(parsedBoxType) && meta.primaryItemId != 0;
    }

    /// Parses a grid derived image container.
    ///
    /// @param gridItem the primary grid item
    /// @return the parsed AVIF container
    /// @throws AvifDecodeException if the grid is malformed or unsupported
    private AvifContainer parseGridContainer(Item gridItem) throws AvifDecodeException {
        GridPayloads colorGrid = parseGridPayloads(gridItem);
        AvifImageSource alphaSource = parseGridAuxiliarySource(
                gridItem,
                colorGrid,
                AvifAuxiliaryImageInfo.ALPHA_TYPE,
                "Alpha"
        );
        validateItemPremultipliedAlpha(gridItem, alphaSource, "Primary grid");
        boolean alphaPremultiplied = itemAlphaPremultiplied(gridItem);
        AvifImageSource depthSource = parseGridAuxiliarySource(
                gridItem,
                colorGrid,
                AvifAuxiliaryImageInfo.DEPTH_TYPE,
                "Depth"
        );
        SampleTransform sampleTransform = parseSampleTransform(
                gridItem,
                colorGrid.outputWidth,
                colorGrid.outputHeight,
                alphaSource != null
        );
        MetadataPayloads metadata = collectMetadataPayloads(gridItem);
        AvifImageTransformInfo transformInfo = extractTransformInfo(
                gridItem,
                colorGrid.outputWidth,
                colorGrid.outputHeight
        );
        DisplaySize displaySize = transformedDisplaySize(
                colorGrid.outputWidth,
                colorGrid.outputHeight,
                transformInfo
        );
        GainMapPayloads gainMapPayloads = gainMapPayloads(gridItem.id);

        AvifImageInfo info = new AvifImageInfo(
                displaySize.width(),
                displaySize.height(),
                sampleTransform != null
                        ? sampleTransform.bitDepth()
                        : AvifBitDepth.fromBits(colorGrid.representativeAv1C.bitDepth()),
                colorGrid.representativeAv1C.chromaFormat()
        ).withTransformInfo(transformInfo)
                .withAuxiliaryImages(null, auxiliaryImages(gridItem.id, colorGrid.outputWidth, colorGrid.outputHeight))
                .withAlpha(alphaSource != null, alphaPremultiplied)
                .withGainMapInfo(gainMapPayloads.info)
                .withColorInfo(gridItem.firstProperty(AvifColorInfo.class))
                .withIccProfile(metadata.iccProfile)
                .withExif(metadata.exif)
                .withXmp(metadata.xmp)
                .withOpaqueItemProperties(opaqueItemProperties(gridItem));

        return new AvifContainer(
                info,
                colorGrid.source,
                alphaSource,
                depthSource,
                gainMapPayloads.source,
                sampleTransform
        );
    }

    /// Parses a preferred `sato` Sample Transform alternative for one primary still image.
    ///
    /// @param primaryItem the primary color item or grid
    /// @param expectedWidth the reconstructed image width
    /// @param expectedHeight the reconstructed image height
    /// @param primaryAlphaPresent whether the primary image has alpha
    /// @return the parsed Sample Transform, or `null` when no preferred alternative is present
    /// @throws AvifDecodeException if the selected Sample Transform is malformed or unsupported
    private SampleTransform parseSampleTransform(
            Item primaryItem,
            int expectedWidth,
            int expectedHeight,
            boolean primaryAlphaPresent
    ) throws AvifDecodeException {
        Item transformItem = findSampleTransformItem(primaryItem.id);
        if (transformItem == null) {
            return null;
        }
        BoxInput expressionInput = new BoxInput(mergeItemExtents(transformItem));
        int header = expressionInput.readU8();
        int version = header >>> 6;
        int arithmeticBitDepthCode = header & 0x03;
        if (version != 0) {
            return null;
        }
        int intermediateBitDepth = 1 << (arithmeticBitDepthCode + 3);
        validateTransformProperties(primaryItem, transformItem, "Sample Transform item");

        ImageSpatialExtents transformIspe = transformItem.firstProperty(ImageSpatialExtents.class);
        if (transformIspe == null) {
            throw parseFailed("Sample Transform item is missing ispe: " + transformItem.id, 0);
        }
        if (transformIspe.width != expectedWidth || transformIspe.height != expectedHeight) {
            throw parseFailed("Sample Transform ispe differs from the primary image", 0);
        }
        PixelInformation pixelInformation = transformItem.firstProperty(PixelInformation.class);
        if (pixelInformation == null) {
            throw parseFailed("Sample Transform item is missing pixi: " + transformItem.id, 0);
        }
        // Omitted color properties inherit the primary fallback's interpretation. Any explicitly
        // associated color information must still agree across the transform and its inputs.
        Item colorReferenceItem = hasColorInformation(transformItem) ? transformItem : primaryItem;
        AvifColorInfo outputColorInfo = colorReferenceItem.firstProperty(AvifColorInfo.class);
        int outputBitCount = pixelInformation.bitsPerChannel[0];
        for (int bitCount : pixelInformation.bitsPerChannel) {
            if (bitCount != outputBitCount) {
                throw parseFailed("Sample Transform pixi channel depths differ", 0);
            }
        }
        AvifBitDepth outputBitDepth;
        try {
            outputBitDepth = AvifBitDepth.fromBits(outputBitCount);
        } catch (IllegalArgumentException exception) {
            throw unsupported("Unsupported Sample Transform output bit depth: " + outputBitCount, null);
        }

        List<Integer> inputIds = transformItem.dimgCellIds;
        if (inputIds.isEmpty() || inputIds.size() > 32) {
            throw parseFailed("Sample Transform input count must be in [1, 32]", 0);
        }
        SampleTransform.Input[] inputs = new SampleTransform.Input[inputIds.size()];
        Av1Config representativeConfig = null;
        int primaryInputIndex = -1;
        boolean primaryPremultiplied = itemAlphaPremultiplied(primaryItem);

        for (int inputIndex = 0; inputIndex < inputIds.size(); inputIndex++) {
            int inputId = inputIds.get(inputIndex);
            Item inputItem = meta.item(inputId);
            if (inputItem == null) {
                throw parseFailed("Sample Transform input item not found: " + inputId, 0);
            }
            if (inputItem.hasUnsupportedEssentialProperty) {
                throw new AvifDecodeException(
                        AvifErrorCode.MISSING_IMAGE_ITEM,
                        "Sample Transform input item is not usable: " + inputId,
                        null
                );
            }
            if (inputId == primaryItem.id) {
                if (primaryInputIndex >= 0) {
                    throw parseFailed("Sample Transform references the primary image item more than once", 0);
                }
                primaryInputIndex = inputIndex;
            }
            ImageSpatialExtents inputIspe = inputItem.firstProperty(ImageSpatialExtents.class);
            if (inputIspe == null) {
                throw parseFailed("Sample Transform input item is missing ispe: " + inputId, 0);
            }
            if (inputIspe.width != expectedWidth || inputIspe.height != expectedHeight) {
                throw parseFailed("Sample Transform input dimensions differ: " + inputId, 0);
            }
            PixelInformation inputPixelInformation = inputItem.firstProperty(PixelInformation.class);
            if (inputPixelInformation == null) {
                throw parseFailed("Sample Transform input item is missing pixi: " + inputId, 0);
            }
            if (inputPixelInformation.bitsPerChannel.length != pixelInformation.bitsPerChannel.length) {
                throw parseFailed("Sample Transform input channel count differs: " + inputId, 0);
            }
            if (hasColorInformation(inputItem) && !sameColorInformation(colorReferenceItem, inputItem)) {
                throw parseFailed("Sample Transform input color information differs: " + inputId, 0);
            }

            AvifImageSource colorSource;
            AvifImageSource alphaSource;
            Av1Config inputConfig;
            if ("av01".equals(inputItem.type)) {
                validateOperatingPointStructure(inputItem, "Sample Transform input image");
                inputConfig = inputItem.firstProperty(Av1Config.class);
                if (inputConfig == null) {
                    throw parseFailed("Sample Transform AV1 input is missing av1C: " + inputId, 0);
                }
                colorSource = AvifImageSource.item(
                        itemPayload(inputItem),
                        operatingPoint(inputItem),
                        selectedSpatialLayer(inputItem),
                        expectedWidth,
                        expectedHeight
                );
                alphaSource = parseAuxiliarySource(
                        inputItem,
                        AvifAuxiliaryImageInfo.ALPHA_TYPE,
                        "Sample Transform alpha",
                        expectedWidth,
                        expectedHeight
                );
            } else if ("grid".equals(inputItem.type)) {
                GridPayloads inputGrid = parseGridPayloads(inputItem);
                if (inputGrid.outputWidth != expectedWidth || inputGrid.outputHeight != expectedHeight) {
                    throw parseFailed("Sample Transform input grid dimensions differ: " + inputId, 0);
                }
                inputConfig = inputGrid.representativeAv1C;
                colorSource = inputGrid.source;
                alphaSource = parseGridAuxiliarySource(
                        inputItem,
                        inputGrid,
                        AvifAuxiliaryImageInfo.ALPHA_TYPE,
                        "Sample Transform alpha"
                );
            } else {
                throw unsupported("Unsupported Sample Transform input item type: " + inputItem.type, null);
            }

            for (int bitCount : inputPixelInformation.bitsPerChannel) {
                if (bitCount != inputConfig.bitDepth()) {
                    throw parseFailed("Sample Transform input pixi differs from av1C: " + inputId, 0);
                }
            }

            if (representativeConfig == null) {
                representativeConfig = inputConfig;
            } else if (representativeConfig.monochrome != inputConfig.monochrome
                    || representativeConfig.chromaSubsamplingX != inputConfig.chromaSubsamplingX
                    || representativeConfig.chromaSubsamplingY != inputConfig.chromaSubsamplingY
                    || representativeConfig.chromaSamplePosition != inputConfig.chromaSamplePosition) {
                throw parseFailed("Sample Transform input chroma layouts differ", 0);
            }

            if ((alphaSource != null) != primaryAlphaPresent) {
                throw unsupported("Sample Transform inputs must either all have alpha or all omit alpha", null);
            }
            if (alphaSource != null) {
                validateItemPremultipliedAlpha(inputItem, alphaSource, "Sample Transform input");
                if (itemAlphaPremultiplied(inputItem) != primaryPremultiplied) {
                    throw unsupported("Sample Transform input alpha premultiplication differs", null);
                }
            }
            inputs[inputIndex] = new SampleTransform.Input(
                    colorSource,
                    AvifBitDepth.fromBits(inputConfig.bitDepth()),
                    alphaSource
            );
        }

        if (primaryInputIndex < 0) {
            throw parseFailed("Sample Transform does not reference the primary image item", 0);
        }
        assert representativeConfig != null;
        int expectedChannelCount = representativeConfig.monochrome ? 1 : 3;
        if (pixelInformation.bitsPerChannel.length != expectedChannelCount) {
            throw parseFailed("Sample Transform pixi channel count differs from its inputs", 0);
        }

        int tokenCount = expressionInput.readU8();
        if (tokenCount == 0) {
            throw parseFailed("Sample Transform expression has no tokens", expressionInput.offset() - 1L);
        }
        int[] tokenCodes = new int[tokenCount];
        long[] constantValues = new long[tokenCount];
        boolean hasReservedToken = false;
        for (int tokenIndex = 0; tokenIndex < tokenCount; tokenIndex++) {
            int tokenCode = expressionInput.readU8();
            tokenCodes[tokenIndex] = tokenCode;
            if (tokenCode == 0) {
                constantValues[tokenIndex] = switch (intermediateBitDepth) {
                    case 8 -> expressionInput.readI8();
                    case 16 -> expressionInput.readI16();
                    case 32 -> expressionInput.readI32();
                    case 64 -> expressionInput.readI64();
                    default -> throw new AssertionError("Unexpected intermediate bit depth: " + intermediateBitDepth);
                };
            } else if (isReservedSampleTransformToken(tokenCode)) {
                hasReservedToken = true;
            }
        }
        if (expressionInput.hasRemaining()) {
            throw parseFailed("Sample Transform expression has trailing bytes", expressionInput.offset());
        }
        if (hasReservedToken) {
            return null;
        }
        try {
            return new SampleTransform(
                    outputBitDepth,
                    outputColorInfo == null || outputColorInfo.fullRange(),
                    intermediateBitDepth,
                    tokenCodes,
                    constantValues,
                    inputs,
                    primaryInputIndex
            );
        } catch (IllegalArgumentException exception) {
            throw parseFailed("Invalid Sample Transform expression: " + exception.getMessage(), 0);
        }
    }

    /// Returns whether a Sample Transform token code is reserved by AVIF.
    ///
    /// @param tokenCode the unsigned 8-bit token code
    /// @return whether the token is reserved
    private static boolean isReservedSampleTransformToken(int tokenCode) {
        return (tokenCode >= 33 && tokenCode <= 63)
                || (tokenCode >= 68 && tokenCode <= 127)
                || tokenCode >= 138;
    }

    /// Returns whether two items carry the same color-information properties.
    ///
    /// Parsed `nclx` and ICC properties are compared by value. Unknown `colr` properties are
    /// compared by their preserved payload so that their semantics are not guessed.
    ///
    /// @param first the first image item
    /// @param second the second image item
    /// @return whether both items have equal color information in association order
    private static boolean sameColorInformation(Item first, Item second) {
        ArrayList<Property> firstProperties = new ArrayList<>();
        ArrayList<Property> secondProperties = new ArrayList<>();
        for (Property property : first.properties) {
            if (isColorInformationProperty(property)) {
                firstProperties.add(property);
            }
        }
        for (Property property : second.properties) {
            if (isColorInformationProperty(property)) {
                secondProperties.add(property);
            }
        }
        if (firstProperties.size() != secondProperties.size()) {
            return false;
        }
        for (int i = 0; i < firstProperties.size(); i++) {
            Property firstProperty = firstProperties.get(i);
            Property secondProperty = secondProperties.get(i);
            if (firstProperty instanceof ColorProperty firstColor
                    && secondProperty instanceof ColorProperty secondColor) {
                AvifColorInfo firstInfo = firstColor.colorInfo;
                AvifColorInfo secondInfo = secondColor.colorInfo;
                if (firstInfo.colorPrimaries() != secondInfo.colorPrimaries()
                        || firstInfo.transferCharacteristics() != secondInfo.transferCharacteristics()
                        || firstInfo.matrixCoefficients() != secondInfo.matrixCoefficients()
                        || firstInfo.fullRange() != secondInfo.fullRange()) {
                    return false;
                }
            } else if (firstProperty instanceof IccColorProfile firstIcc
                    && secondProperty instanceof IccColorProfile secondIcc) {
                if (!Arrays.equals(firstIcc.profile, secondIcc.profile)) {
                    return false;
                }
            } else if (firstProperty instanceof OpaqueProperty firstOpaque
                    && secondProperty instanceof OpaqueProperty secondOpaque) {
                if (!Arrays.equals(firstOpaque.payload, secondOpaque.payload)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    /// Returns whether one parsed property carries color information.
    ///
    /// @param property the parsed item property
    /// @return whether the property originated from a `colr` property
    private static boolean isColorInformationProperty(Property property) {
        return property instanceof ColorProperty
                || property instanceof IccColorProfile
                || (property instanceof OpaqueProperty opaqueProperty && "colr".equals(opaqueProperty.type));
    }

    /// Returns whether one item has any associated color-information property.
    ///
    /// @param item the image item
    /// @return whether the item has a `colr` property
    private static boolean hasColorInformation(Item item) {
        for (Property property : item.properties) {
            if (isColorInformationProperty(property)) {
                return true;
            }
        }
        return false;
    }

    /// Finds the first usable `sato` item preferred to the primary image in an `altr` group.
    ///
    /// @param primaryItemId the primary image item id
    /// @return the preferred Sample Transform item, or `null`
    private Item findSampleTransformItem(int primaryItemId) {
        for (EntityGroup group : meta.entityGroups) {
            if (!"altr".equals(group.type)) {
                continue;
            }
            int primaryIndex = -1;
            for (int i = 0; i < group.entityIds.length; i++) {
                if (group.entityIds[i] == primaryItemId) {
                    primaryIndex = i;
                    break;
                }
            }
            if (primaryIndex < 0) {
                continue;
            }
            for (int i = 0; i < primaryIndex; i++) {
                Item candidate = meta.item(group.entityIds[i]);
                if (candidate != null
                        && candidate.id != primaryItemId
                        && "sato".equals(candidate.type)
                        && !candidate.extents.isEmpty()
                        && !candidate.hasUnsupportedEssentialProperty) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /// Parses the payloads and geometry for one `grid` derived image item.
    ///
    /// @param gridItem the grid item
    /// @return parsed grid payloads and geometry
    /// @throws AvifDecodeException if the grid is malformed or unsupported
    private GridPayloads parseGridPayloads(Item gridItem) throws AvifDecodeException {
        validateOperatingPointStructure(gridItem, "Grid image");
        byte[] gridPayload = mergeItemExtents(gridItem);
        BoxInput input = new BoxInput(gridPayload);
        int version = input.readU8();
        int flags = input.readU8();
        if (version != 0) {
            throw unsupported("Unsupported grid version: " + version, null);
        }
        if ((flags & ~1) != 0) {
            throw parseFailed("grid contains reserved flags: " + flags, 1);
        }
        int rowsMinusOne = input.readU8();
        int columnsMinusOne = input.readU8();
        int rows = rowsMinusOne + 1;
        int columns = columnsMinusOne + 1;
        if (rows <= 0 || columns <= 0) {
            throw parseFailed("grid dimensions must be positive", 2);
        }

        long outputWidthValue;
        long outputHeightValue;
        if ((flags & 1) == 0) {
            outputWidthValue = input.readU16();
            outputHeightValue = input.readU16();
        } else {
            outputWidthValue = input.readU32();
            outputHeightValue = input.readU32();
        }
        if (outputWidthValue == 0 || outputHeightValue == 0) {
            throw parseFailed("grid output dimensions must be positive", input.offset());
        }
        if (outputWidthValue > Integer.MAX_VALUE || outputHeightValue > Integer.MAX_VALUE) {
            throw unsupported(
                    "Grid output dimensions exceed the supported Java array range: "
                            + outputWidthValue + "x" + outputHeightValue,
                    null
            );
        }
        if (input.hasRemaining()) {
            throw parseFailed("grid payload has trailing bytes", input.offset());
        }
        int outputWidth = (int) outputWidthValue;
        int outputHeight = (int) outputHeightValue;

        int expectedCellCount = rows * columns;
        List<Integer> cellIds = gridItem.dimgCellIds;
        if (cellIds.size() != expectedCellCount) {
            throw parseFailed(
                    "grid dimg cell count mismatch: expected " + expectedCellCount + " but got " + cellIds.size(),
                    0
            );
        }

        Av1Config representativeAv1C = null;
        List<AvifPayload> cellPayloads = new ArrayList<>(expectedCellCount);
        int[] cellOperatingPoints = new int[expectedCellCount];
        int[] cellSelectedSpatialLayers = new int[expectedCellCount];
        int[] cellWidths = new int[expectedCellCount];
        int[] cellHeights = new int[expectedCellCount];

        for (int i = 0; i < expectedCellCount; i++) {
            int cellId = cellIds.get(i);
            Item cellItem = meta.item(cellId);
            if (cellItem == null) {
                throw parseFailed("grid dimg cell item not found: " + cellId, 0);
            }
            if (!"av01".equals(cellItem.type)) {
                throw unsupported("Unsupported grid cell item type: " + cellItem.type, null);
            }
            if (cellItem.hasUnsupportedEssentialProperty) {
                throw new AvifDecodeException(
                        AvifErrorCode.MISSING_IMAGE_ITEM,
                        "Grid cell item is not usable: " + cellId,
                        null
                );
            }
            validateOperatingPointStructure(cellItem, "Grid cell image");
            ImageSpatialExtents cellIspe = cellItem.firstProperty(ImageSpatialExtents.class);
            if (cellIspe == null) {
                throw new AvifDecodeException(
                        AvifErrorCode.BMFF_PARSE_FAILED,
                        "Grid cell item is missing ispe: " + cellId,
                        null
                );
            }
            Av1Config cellAv1C = cellItem.firstProperty(Av1Config.class);
            if (cellAv1C == null) {
                throw new AvifDecodeException(
                        AvifErrorCode.BMFF_PARSE_FAILED,
                        "Grid cell item is missing av1C: " + cellId,
                        null
                );
            }
            if (representativeAv1C == null) {
                representativeAv1C = cellAv1C;
            }
            cellPayloads.add(itemPayload(cellItem));
            cellOperatingPoints[i] = operatingPoint(cellItem);
            cellSelectedSpatialLayers[i] = selectedSpatialLayer(cellItem);
            cellWidths[i] = cellIspe.width;
            cellHeights[i] = cellIspe.height;
        }

        assert representativeAv1C != null;

        AvifPayload[] payloads = cellPayloads.toArray(AvifPayload[]::new);
        return new GridPayloads(
                rows,
                columns,
                outputWidth,
                outputHeight,
                representativeAv1C,
                AvifImageSource.grid(
                        payloads,
                        cellOperatingPoints,
                        cellSelectedSpatialLayers,
                        cellWidths,
                        cellHeights,
                        rows,
                        columns,
                        outputWidth,
                        outputHeight
                )
        );
    }

    /// Parses AV1 auxiliary payloads for a non-grid color item.
    ///
    /// @param imageItem the color image item
    /// @param auxiliaryType the auxiliary image type string
    /// @param label the diagnostic auxiliary label
    /// @param expectedWidth the expected auxiliary width
    /// @param expectedHeight the expected auxiliary height
    /// @return the auxiliary image source, or `null` when no matching auxiliary image is present
    /// @throws AvifDecodeException if auxiliary data is malformed or unsupported
    private AvifImageSource parseAuxiliarySource(
            Item imageItem,
            String auxiliaryType,
            String label,
            int expectedWidth,
            int expectedHeight
    ) throws AvifDecodeException {
        Item auxiliaryItem = findAuxiliaryItem(imageItem.id, auxiliaryType);
        if (auxiliaryItem == null) {
            return null;
        }
        if (auxiliaryItem.hasUnsupportedEssentialProperty) {
            throw new AvifDecodeException(
                    AvifErrorCode.MISSING_IMAGE_ITEM,
                    label + " auxiliary item is not usable: " + auxiliaryItem.id,
                    null
            );
        }
        if (!"av01".equals(auxiliaryItem.type)) {
            throw unsupported("Unsupported " + label + " auxiliary item type: " + auxiliaryItem.type, null);
        }
        validateOperatingPointStructure(auxiliaryItem, label + " auxiliary image");
        int outputWidth;
        int outputHeight;
        if (isAlphaAuxiliaryType(auxiliaryType)) {
            validateAlphaAuxiliaryItemDimensions(auxiliaryItem, label, expectedWidth, expectedHeight);
            validateTransformProperties(imageItem, auxiliaryItem, label);
            outputWidth = expectedWidth;
            outputHeight = expectedHeight;
        } else {
            ImageSpatialExtents auxiliaryIspe = requireAuxiliaryItemProperties(auxiliaryItem, label);
            outputWidth = auxiliaryIspe.width;
            outputHeight = auxiliaryIspe.height;
        }
        return AvifImageSource.item(
                itemPayload(auxiliaryItem),
                operatingPoint(auxiliaryItem),
                selectedSpatialLayer(auxiliaryItem),
                outputWidth,
                outputHeight
        );
    }

    /// Parses auxiliary payloads for a grid color item.
    ///
    /// @param gridItem the color grid item
    /// @param colorGrid the parsed color grid
    /// @param auxiliaryType the auxiliary image type string
    /// @param label the diagnostic auxiliary label
    /// @return the auxiliary image source, or `null` when no matching auxiliary image is present
    /// @throws AvifDecodeException if auxiliary data is malformed or unsupported
    private AvifImageSource parseGridAuxiliarySource(
            Item gridItem,
            GridPayloads colorGrid,
            String auxiliaryType,
            String label
    ) throws AvifDecodeException {
        Item auxiliaryItem = findAuxiliaryItem(gridItem.id, auxiliaryType);
        if (auxiliaryItem != null) {
            if (auxiliaryItem.hasUnsupportedEssentialProperty) {
                throw new AvifDecodeException(
                        AvifErrorCode.MISSING_IMAGE_ITEM,
                        label + " auxiliary item is not usable: " + auxiliaryItem.id,
                        null
                );
            }
            if (isAlphaAuxiliaryType(auxiliaryType)) {
                validateTransformProperties(gridItem, auxiliaryItem, label);
            }
            if ("grid".equals(auxiliaryItem.type)) {
                GridPayloads auxiliaryGrid = parseGridPayloads(auxiliaryItem);
                if (isAlphaAuxiliaryType(auxiliaryType)) {
                    validateAlphaGridDimensions(colorGrid, auxiliaryGrid, label);
                }
                return auxiliaryGrid.source;
            }
            if (!"av01".equals(auxiliaryItem.type)) {
                throw unsupported("Unsupported " + label + " auxiliary item type: " + auxiliaryItem.type, null);
            }
            validateOperatingPointStructure(auxiliaryItem, label + " auxiliary image");
            int outputWidth;
            int outputHeight;
            if (isAlphaAuxiliaryType(auxiliaryType)) {
                validateAlphaAuxiliaryItemDimensions(auxiliaryItem, label, colorGrid.outputWidth, colorGrid.outputHeight);
                outputWidth = colorGrid.outputWidth;
                outputHeight = colorGrid.outputHeight;
            } else {
                ImageSpatialExtents auxiliaryIspe = requireAuxiliaryItemProperties(auxiliaryItem, label);
                outputWidth = auxiliaryIspe.width;
                outputHeight = auxiliaryIspe.height;
            }
            return AvifImageSource.item(
                    itemPayload(auxiliaryItem),
                    operatingPoint(auxiliaryItem),
                    selectedSpatialLayer(auxiliaryItem),
                    outputWidth,
                    outputHeight
            );
        }

        return parsePerCellGridAuxiliarySource(gridItem, colorGrid, auxiliaryType, label);
    }

    /// Parses the legacy grid-auxiliary shape where each color cell has its own auxiliary item.
    ///
    /// @param gridItem the color grid item
    /// @param colorGrid the parsed color grid
    /// @param auxiliaryType the auxiliary image type string
    /// @param label the diagnostic auxiliary label
    /// @return the auxiliary image source, or `null` when no complete per-cell auxiliary set is present
    /// @throws AvifDecodeException if auxiliary data is malformed or unsupported
    private AvifImageSource parsePerCellGridAuxiliarySource(
            Item gridItem,
            GridPayloads colorGrid,
            String auxiliaryType,
            String label
    ) throws AvifDecodeException {
        List<Integer> cellIds = gridItem.dimgCellIds;
        AvifPayload[] auxiliaryCellPayloads = new AvifPayload[cellIds.size()];
        int[] auxiliaryCellOperatingPoints = new int[cellIds.size()];
        int[] auxiliaryCellSelectedSpatialLayers = new int[cellIds.size()];
        int[] auxiliaryCellWidths = new int[cellIds.size()];
        int[] auxiliaryCellHeights = new int[cellIds.size()];
        for (int i = 0; i < cellIds.size(); i++) {
            int cellId = cellIds.get(i);
            Item colorCellItem = requireItem(cellId, -1L);
            Item auxiliaryCellItem = findAuxiliaryItem(cellId, auxiliaryType);
            if (auxiliaryCellItem == null) {
                return null;
            }
            if (!"av01".equals(auxiliaryCellItem.type)) {
                throw unsupported(
                        "Unsupported per-cell " + label + " auxiliary item type: " + auxiliaryCellItem.type,
                        null
                );
            }
            if (auxiliaryCellItem.dimgForId != 0) {
                throw unsupported(
                        "Per-cell " + label + " auxiliary item is also a derived-image cell: " + auxiliaryCellItem.id,
                        null
                );
            }
            if (auxiliaryCellItem.hasUnsupportedEssentialProperty) {
                throw new AvifDecodeException(
                        AvifErrorCode.MISSING_IMAGE_ITEM,
                        "Per-cell " + label + " auxiliary item is not usable: " + auxiliaryCellItem.id,
                        null
                );
            }
            validateOperatingPointStructure(auxiliaryCellItem, "Per-cell " + label + " auxiliary image");
            ImageSpatialExtents colorIspe = colorCellItem.firstProperty(ImageSpatialExtents.class);
            assert colorIspe != null;
            if (isAlphaAuxiliaryType(auxiliaryType)) {
                validateAlphaAuxiliaryItemDimensions(auxiliaryCellItem, label, colorIspe.width, colorIspe.height);
                validateTransformProperties(colorCellItem, auxiliaryCellItem, "Per-cell " + label);
                auxiliaryCellWidths[i] = colorIspe.width;
                auxiliaryCellHeights[i] = colorIspe.height;
            } else {
                ImageSpatialExtents auxiliaryIspe = requireAuxiliaryItemProperties(auxiliaryCellItem, label);
                if (auxiliaryIspe.width != colorIspe.width || auxiliaryIspe.height != colorIspe.height) {
                    throw parseFailed(
                            "Per-cell " + label + " auxiliary dimensions must match the associated color cell"
                    );
                }
                auxiliaryCellWidths[i] = auxiliaryIspe.width;
                auxiliaryCellHeights[i] = auxiliaryIspe.height;
            }
            auxiliaryCellPayloads[i] = itemPayload(auxiliaryCellItem);
            auxiliaryCellOperatingPoints[i] = operatingPoint(auxiliaryCellItem);
            auxiliaryCellSelectedSpatialLayers[i] = selectedSpatialLayer(auxiliaryCellItem);
        }
        return AvifImageSource.grid(
                auxiliaryCellPayloads,
                auxiliaryCellOperatingPoints,
                auxiliaryCellSelectedSpatialLayers,
                auxiliaryCellWidths,
                auxiliaryCellHeights,
                colorGrid.rows,
                colorGrid.columns,
                colorGrid.outputWidth,
                colorGrid.outputHeight
        );
    }

    /// Validates that an alpha grid matches the color grid canvas dimensions.
    ///
    /// @param colorGrid the color grid
    /// @param auxiliaryGrid the auxiliary grid
    /// @param label the diagnostic auxiliary label
    /// @throws AvifDecodeException if dimensions differ
    private static void validateAlphaGridDimensions(
            GridPayloads colorGrid,
            GridPayloads auxiliaryGrid,
            String label
    ) throws AvifDecodeException {
        if (auxiliaryGrid.outputWidth != colorGrid.outputWidth || auxiliaryGrid.outputHeight != colorGrid.outputHeight) {
            throw parseFailed(label + " grid dimensions must match the master grid");
        }
    }

    /// Requires the spatial extents and AV1 configuration of one non-alpha auxiliary item.
    ///
    /// @param auxiliaryItem the auxiliary item
    /// @param label the diagnostic auxiliary label
    /// @return the auxiliary image spatial extents
    /// @throws AvifDecodeException if a required property is absent
    private ImageSpatialExtents requireAuxiliaryItemProperties(
            Item auxiliaryItem,
            String label
    ) throws AvifDecodeException {
        ImageSpatialExtents auxiliaryIspe = auxiliaryItem.firstProperty(ImageSpatialExtents.class);
        if (auxiliaryIspe == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " auxiliary item is missing ispe: " + auxiliaryItem.id,
                    null
            );
        }
        if (auxiliaryItem.firstProperty(Av1Config.class) == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " auxiliary item is missing av1C: " + auxiliaryItem.id,
                    null
            );
        }
        return auxiliaryIspe;
    }

    /// Validates one alpha auxiliary item against the expected output dimensions.
    ///
    /// Legacy alpha items are allowed to omit `ispe`; in that case AVIF readers treat the alpha
    /// canvas as matching the associated master image.
    ///
    /// @param auxiliaryItem the alpha auxiliary item
    /// @param label the diagnostic auxiliary label
    /// @param expectedWidth the expected alpha width
    /// @param expectedHeight the expected alpha height
    /// @throws AvifDecodeException if the item is malformed or has unsupported dimensions
    private void validateAlphaAuxiliaryItemDimensions(
            Item auxiliaryItem,
            String label,
            int expectedWidth,
            int expectedHeight
    ) throws AvifDecodeException {
        ImageSpatialExtents auxiliaryIspe = auxiliaryItem.firstProperty(ImageSpatialExtents.class);
        if (auxiliaryIspe != null
                && (auxiliaryIspe.width != expectedWidth || auxiliaryIspe.height != expectedHeight)) {
            throw parseFailed(label + " auxiliary image dimensions must match the master image");
        }
        if (auxiliaryItem.firstProperty(Av1Config.class) == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " auxiliary item is missing av1C: " + auxiliaryItem.id,
                    null
            );
        }
    }

    /// Returns whether an auxiliary type is the AVIF alpha auxiliary type.
    ///
    /// @param auxiliaryType the auxiliary image type string
    /// @return whether the type describes an alpha auxiliary image
    private static boolean isAlphaAuxiliaryType(String auxiliaryType) {
        return AvifAuxiliaryImageInfo.ALPHA_TYPE.equals(auxiliaryType);
    }

    /// Validates explicitly associated transform properties against a master image item.
    ///
    /// An item may omit all transformative properties and use the master image's presentation. If
    /// it explicitly associates any transformative property, `clap`, `irot`, and `imir` must all
    /// match the master image exactly.
    ///
    /// @param imageItem the master image item
    /// @param comparedItem the item whose transform properties are checked
    /// @param label the diagnostic item label
    /// @throws AvifDecodeException if explicit transform properties differ from the master image
    private static void validateTransformProperties(
            Item imageItem,
            Item comparedItem,
            String label
    ) throws AvifDecodeException {
        if (!hasTransformProperties(comparedItem)) {
            return;
        }
        if (!sameCleanApertureProperty(imageItem, comparedItem)
                || !sameImageRotationProperty(imageItem, comparedItem)
                || !sameImageMirrorProperty(imageItem, comparedItem)) {
            throw unsupported(label + " transform properties differ from the master image", null);
        }
    }

    /// Returns whether one item has any AVIF transformative property.
    ///
    /// @param item the item to inspect
    /// @return whether the item has a `clap`, `irot`, or `imir` property
    private static boolean hasTransformProperties(Item item) {
        return item.firstProperty(CleanAperture.class) != null
                || item.firstProperty(ImageRotation.class) != null
                || item.firstProperty(ImageMirror.class) != null;
    }

    /// Returns whether two items have equal `clap` properties.
    ///
    /// @param firstItem the first item
    /// @param secondItem the second item
    /// @return whether the `clap` properties are both absent or equal
    private static boolean sameCleanApertureProperty(Item firstItem, Item secondItem) {
        CleanAperture first = firstItem.firstProperty(CleanAperture.class);
        CleanAperture second = secondItem.firstProperty(CleanAperture.class);
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.cleanApertureWidthN == second.cleanApertureWidthN
                && first.cleanApertureWidthD == second.cleanApertureWidthD
                && first.cleanApertureHeightN == second.cleanApertureHeightN
                && first.cleanApertureHeightD == second.cleanApertureHeightD
                && first.horizOffN == second.horizOffN
                && first.horizOffD == second.horizOffD
                && first.vertOffN == second.vertOffN
                && first.vertOffD == second.vertOffD;
    }

    /// Returns whether two items have equal `irot` properties.
    ///
    /// @param firstItem the first item
    /// @param secondItem the second item
    /// @return whether the `irot` properties are both absent or equal
    private static boolean sameImageRotationProperty(Item firstItem, Item secondItem) {
        ImageRotation first = firstItem.firstProperty(ImageRotation.class);
        ImageRotation second = secondItem.firstProperty(ImageRotation.class);
        if (first == second) {
            return true;
        }
        return first != null && second != null && first.rotation == second.rotation;
    }

    /// Returns whether two items have equal `imir` properties.
    ///
    /// @param firstItem the first item
    /// @param secondItem the second item
    /// @return whether the `imir` properties are both absent or equal
    private static boolean sameImageMirrorProperty(Item firstItem, Item secondItem) {
        ImageMirror first = firstItem.firstProperty(ImageMirror.class);
        ImageMirror second = secondItem.firstProperty(ImageMirror.class);
        if (first == second) {
            return true;
        }
        return first != null && second != null && first.axis == second.axis;
    }

    /// Collects metadata payloads associated with one rendered image item.
    ///
    /// @param imageItem the color or grid image item
    /// @return the associated metadata payloads
    /// @throws AvifDecodeException if metadata item extents are malformed
    private MetadataPayloads collectMetadataPayloads(Item imageItem) throws AvifDecodeException {
        byte[] iccProfile = null;
        IccColorProfile iccProperty = imageItem.firstProperty(IccColorProfile.class);
        if (iccProperty != null) {
            iccProfile = iccProperty.profile();
        }

        byte[] exif = null;
        byte[] xmp = null;
        for (Item item : meta.items.values()) {
            if (item.descForId != imageItem.id) {
                continue;
            }
            if (exif == null && "Exif".equals(item.type)) {
                exif = exifPayload(item);
            } else if (xmp == null
                    && "mime".equals(item.type)
                    && (XMP_CONTENT_TYPE.equals(item.contentType) || "XMP".equals(item.name))) {
                xmp = mergeItemExtents(item);
            }
        }
        return new MetadataPayloads(iccProfile, exif, xmp);
    }

    /// Collects opaque item properties associated with one rendered image item.
    ///
    /// @param imageItem the color or grid image item
    /// @return the opaque item properties in association order
    private static AvifOpaqueItemProperty [] opaqueItemProperties(Item imageItem) {
        ArrayList<AvifOpaqueItemProperty> result = new ArrayList<>();
        for (Property property : imageItem.properties) {
            if (property instanceof OpaqueProperty opaqueProperty) {
                result.add(opaqueProperty.toOpaqueItemProperty());
            }
        }
        return result.toArray(AvifOpaqueItemProperty[]::new);
    }

    /// Reads and normalizes an Exif metadata item.
    ///
    /// @param item the Exif item
    /// @return the Exif payload without the AVIF Exif header offset field
    /// @throws AvifDecodeException if the Exif item is malformed
    private byte[] exifPayload(Item item) throws AvifDecodeException {
        byte[] payload = mergeItemExtents(item);
        if (payload.length < 4) {
            throw parseFailed("Exif item is missing exif_tiff_header_offset: " + item.id, 0);
        }
        return Arrays.copyOfRange(payload, 4, payload.length);
    }

    /// Extracts typed image-transform metadata from the properties of one item.
    ///
    /// @param item the item whose properties are searched
    /// @param imageWidth the image width before clap
    /// @param imageHeight the image height before clap
    /// @return the normalized image-transform metadata, or `null` when no transform is present
    private static AvifImageTransformInfo extractTransformInfo(
            Item item,
            int imageWidth,
            int imageHeight
    ) {
        int clapCropX = -1;
        int clapCropY = -1;
        int clapCropWidth = -1;
        int clapCropHeight = -1;
        int rotationCode = -1;
        int mirrorAxis = -1;

        CleanAperture clap = item.firstProperty(CleanAperture.class);
        if (clap != null) {
            clapCropWidth = (int) (((long) clap.cleanApertureWidthN
                    + clap.cleanApertureWidthD - 1L) / clap.cleanApertureWidthD);
            clapCropHeight = (int) (((long) clap.cleanApertureHeightN
                    + clap.cleanApertureHeightD - 1L) / clap.cleanApertureHeightD);
            clapCropX = roundedCleanApertureOrigin(
                    imageWidth,
                    clap.cleanApertureWidthN, clap.cleanApertureWidthD,
                    clap.horizOffN, clap.horizOffD
            );
            clapCropY = roundedCleanApertureOrigin(
                    imageHeight,
                    clap.cleanApertureHeightN, clap.cleanApertureHeightD,
                    clap.vertOffN, clap.vertOffD
            );
            if (clapCropX >= 0 && clapCropY >= 0
                    && clapCropWidth > 0 && clapCropHeight > 0
                    && clapCropX < imageWidth && clapCropY < imageHeight) {
                clapCropWidth = Math.min(clapCropWidth, imageWidth - clapCropX);
                clapCropHeight = Math.min(clapCropHeight, imageHeight - clapCropY);
            }
            if (clapCropX < 0 || clapCropY < 0
                    || clapCropWidth <= 0 || clapCropHeight <= 0
                    || clapCropX >= imageWidth || clapCropY >= imageHeight) {
                clapCropX = -1;
                clapCropY = -1;
                clapCropWidth = -1;
                clapCropHeight = -1;
            }
        }

        ImageRotation irot = item.firstProperty(ImageRotation.class);
        if (irot != null) {
            rotationCode = irot.rotation;
        }

        ImageMirror imir = item.firstProperty(ImageMirror.class);
        if (imir != null) {
            mirrorAxis = imir.axis;
        }

        if (clapCropX == -1 && rotationCode == -1 && mirrorAxis == -1) {
            return null;
        }
        return new AvifImageTransformInfo(
                clapCropX,
                clapCropY,
                clapCropWidth,
                clapCropHeight,
                rotationCode,
                mirrorAxis
        );
    }

    /// Returns the display dimensions after applying clean-aperture cropping and rotation.
    ///
    /// @param imageWidth the image width before item transforms
    /// @param imageHeight the image height before item transforms
    /// @param transformInfo the normalized transform metadata, or `null` when no transform is present
    /// @return the transformed display dimensions
    private static DisplaySize transformedDisplaySize(
            int imageWidth,
            int imageHeight,
            AvifImageTransformInfo transformInfo
    ) {
        int width = transformInfo != null && transformInfo.hasCleanApertureCrop()
                ? transformInfo.cleanApertureCropWidth()
                : imageWidth;
        int height = transformInfo != null && transformInfo.hasCleanApertureCrop()
                ? transformInfo.cleanApertureCropHeight()
                : imageHeight;
        int rotationCode = transformInfo == null ? -1 : transformInfo.rotationCode();
        return rotationCode == 1 || rotationCode == 3
                ? new DisplaySize(height, width)
                : new DisplaySize(width, height);
    }

    /// Converts one clean-aperture center offset to an integer crop origin.
    ///
    /// The `clap` offset locates the clean-aperture center relative to the uncropped image center;
    /// it is not the top-left crop coordinate. Non-integral coordinates are rounded to the nearest
    /// pixel, with half-pixel ties rounded toward the positive direction.
    ///
    /// @param imageDimension the uncropped image dimension
    /// @param apertureNumerator the clean-aperture dimension numerator
    /// @param apertureDenominator the clean-aperture dimension denominator
    /// @param offsetNumerator the signed center-offset numerator
    /// @param offsetDenominator the center-offset denominator
    /// @return the normalized crop origin, or -1 if it is negative or exceeds the integer range
    private static int roundedCleanApertureOrigin(
            int imageDimension,
            int apertureNumerator,
            int apertureDenominator,
            int offsetNumerator,
            int offsetDenominator
    ) {
        BigInteger apertureDenominatorValue = BigInteger.valueOf(apertureDenominator);
        BigInteger offsetDenominatorValue = BigInteger.valueOf(offsetDenominator);
        BigInteger commonDenominator = apertureDenominatorValue
                .multiply(offsetDenominatorValue)
                .shiftLeft(1);
        BigInteger numerator = BigInteger.valueOf(imageDimension)
                .multiply(apertureDenominatorValue)
                .multiply(offsetDenominatorValue)
                .subtract(BigInteger.valueOf(apertureNumerator).multiply(offsetDenominatorValue))
                .add(BigInteger.valueOf(offsetNumerator).multiply(apertureDenominatorValue).shiftLeft(1));
        if (numerator.signum() < 0) {
            return -1;
        }

        BigInteger rounded = numerator.add(commonDenominator.shiftRight(1)).divide(commonDenominator);
        return rounded.bitLength() <= 31 ? rounded.intValue() : -1;
    }

    /// Validates progressive dependencies referenced by one renderable image item.
    ///
    /// @param item the image item to validate
    /// @param label the diagnostic label used in failure messages
    /// @throws AvifDecodeException if a progressive dependency item is missing
    private void validateOperatingPointStructure(Item item, String label) throws AvifDecodeException {
        for (int depId : item.progDeps) {
            Item depItem = meta.item(depId);
            if (depItem == null) {
                throw parseFailed(label + " progressive dependency item not found: " + depId, 0);
            }
        }
    }

    /// Returns the AV1 operating point selected for one image item.
    ///
    /// @param item the image item
    /// @return the `a1op` value, or zero when the property is absent
    /// @throws AvifDecodeException if the item has more than one `a1op` property
    private static int operatingPoint(Item item) throws AvifDecodeException {
        OperatingPoint operatingPoint = uniqueProperty(item, OperatingPoint.class, "a1op");
        return operatingPoint != null ? operatingPoint.operatingPoint : 0;
    }

    /// Returns the AV1 spatial layer selected for one image item.
    ///
    /// The absent selector and the special `layer_id` value `65535` both select the highest output
    /// spatial layer from the chosen operating point.
    ///
    /// @param item the image item
    /// @return the selected spatial-layer identifier, or [AvifImageSource#HIGHEST_SPATIAL_LAYER]
    /// @throws AvifDecodeException if the item has more than one `lsel` property
    private static int selectedSpatialLayer(Item item) throws AvifDecodeException {
        LayerSelector layerSelector = uniqueProperty(item, LayerSelector.class, "lsel");
        return layerSelector == null || layerSelector.layerId == 0xFFFF
                ? AvifImageSource.HIGHEST_SPATIAL_LAYER
                : layerSelector.layerId;
    }

    /// Returns the unique item property of one type.
    ///
    /// @param item the image item
    /// @param propertyClass the requested property class
    /// @param propertyType the property box type used in diagnostics
    /// @param <T> the parsed property type
    /// @return the property, or `null` when absent
    /// @throws AvifDecodeException if more than one matching property is associated with the item
    private static <T> T uniqueProperty(
            Item item,
            Class<T> propertyClass,
            String propertyType
    ) throws AvifDecodeException {
        T result = null;
        for (Property property : item.properties) {
            if (!propertyClass.isInstance(property)) {
                continue;
            }
            if (result != null) {
                throw parseFailed("Item " + item.id + " has more than one " + propertyType + " property");
            }
            result = propertyClass.cast(property);
        }
        return result;
    }

    /// Parses an `ftyp` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseFileType(BoxInput input) throws AvifDecodeException {
        String majorBrand = input.readFourCc();
        input.skip(4);

        boolean hasAvif = "avif".equals(majorBrand);
        boolean hasAvis = "avis".equals(majorBrand);
        boolean hasTmap = "tmap".equals(majorBrand);
        while (input.remaining() >= 4) {
            String brand = input.readFourCc();
            hasAvif |= "avif".equals(brand);
            hasAvis |= "avis".equals(brand);
            hasTmap |= "tmap".equals(brand);
        }
        if (input.remaining() != 0) {
            throw parseFailed("ftyp compatible brands length is not divisible by four", input.offset());
        }
        compatibleFileTypeSeen = hasAvif || hasAvis;
        avisBrandSeen = hasAvis;
        tmapBrandSeen = hasTmap;
    }

    /// Parses a root `meta` box.
    ///
    /// @param header the enclosing box header
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseMeta(BoxHeader header, BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        boolean firstChild = true;
        Set<String> uniqueBoxes = new HashSet<>();
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            if (firstChild && !"hdlr".equals(child.type())) {
                throw parseFailed("meta box must start with hdlr", child.offset());
            }
            firstChild = false;

            switch (child.type()) {
                case "hdlr" -> {
                    unique(uniqueBoxes, "hdlr", child.offset());
                    parseHandler(payload);
                }
                case "pitm" -> {
                    unique(uniqueBoxes, "pitm", child.offset());
                    parsePrimaryItem(payload);
                }
                case "iloc" -> {
                    unique(uniqueBoxes, "iloc", child.offset());
                    parseItemLocation(payload);
                }
                case "iinf" -> {
                    unique(uniqueBoxes, "iinf", child.offset());
                    parseItemInfo(payload);
                }
                case "iprp" -> {
                    unique(uniqueBoxes, "iprp", child.offset());
                    parseItemProperties(child, payload);
                }
                case "iref" -> {
                    unique(uniqueBoxes, "iref", child.offset());
                    parseItemReference(payload);
                }
                case "grpl" -> {
                    unique(uniqueBoxes, "grpl", child.offset());
                    parseGroupsList(payload);
                }
                case "idat" -> {
                    unique(uniqueBoxes, "idat", child.offset());
                    meta.idatOffset = payload.offset();
                    meta.idatLength = payload.remaining();
                    payload.skip(payload.remaining());
                }
                default -> {
                }
            }
            input.skipBoxPayload(child);
        }
        if (firstChild) {
            throw parseFailed("meta box has no child boxes", header.offset());
        }
    }

    /// Parses a `hdlr` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private static void parseHandler(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        long preDefined = input.readU32();
        if (preDefined != 0) {
            throw parseFailed("hdlr pre_defined must be zero", input.offset() - 4);
        }
        String handlerType = input.readFourCc();
        if (!"pict".equals(handlerType)) {
            throw parseFailed("meta handler_type must be pict", input.offset() - 4);
        }
    }

    /// Parses a `pitm` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parsePrimaryItem(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        meta.primaryItemId = fullBox.version == 0 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
    }

    /// Parses an `iloc` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemLocation(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version > 2) {
            throw unsupported("Unsupported iloc version: " + fullBox.version, input.offset());
        }
        int packedSizes = input.readU8();
        int offsetSize = packedSizes >>> 4;
        int lengthSize = packedSizes & 0x0F;
        int packedBaseAndIndex = input.readU8();
        int baseOffsetSize = packedBaseAndIndex >>> 4;
        int indexSize = fullBox.version == 0 ? 0 : (packedBaseAndIndex & 0x0F);
        validateIlocFieldSize(offsetSize, input.offset());
        validateIlocFieldSize(lengthSize, input.offset());
        validateIlocFieldSize(baseOffsetSize, input.offset());
        validateIlocFieldSize(indexSize, input.offset());

        int itemCount = fullBox.version < 2 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int i = 0; i < itemCount; i++) {
            int itemId = fullBox.version < 2 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
            Item item = requireItem(itemId, input.offset());
            int constructionMethod = 0;
            if (fullBox.version > 0) {
                int packedConstruction = input.readU16();
                if ((packedConstruction & 0xFFF0) != 0) {
                    throw parseFailed("iloc reserved bits must be zero", input.offset() - 2);
                }
                constructionMethod = packedConstruction & 0x000F;
                if (constructionMethod != 0 && constructionMethod != 1) {
                    throw unsupported("Unsupported iloc construction method: " + constructionMethod, input.offset() - 2);
                }
            }
            input.skip(2);
            long baseOffset = readUx(input, baseOffsetSize);
            int extentCount = input.readU16();
            reserveRetainedElements(
                    extentCount,
                    RETAINED_EXTENT_SIZE,
                    input.offset() - 2L,
                    "iloc extents"
            );
            item.extents.clear();
            item.idatStored = constructionMethod == 1;
            for (int extentIndex = 0; extentIndex < extentCount; extentIndex++) {
                if (indexSize > 0) {
                    readUx(input, indexSize);
                }
                long extentOffset = readUx(input, offsetSize);
                long extentLength = readUx(input, lengthSize);
                long absoluteOffset = checkedAdd(baseOffset, extentOffset, input.offset());
                item.extents.add(new Extent(absoluteOffset, checkedU64ToInt(extentLength, input.offset())));
            }
        }
    }

    /// Parses an `iinf` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemInfo(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        int entryCount = fullBox.version == 0 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int i = 0; i < entryCount; i++) {
            BoxHeader infe = input.readBoxHeader();
            if (!"infe".equals(infe.type())) {
                throw parseFailed("iinf entry is not infe", infe.offset());
            }
            parseItemInfoEntry(input.slice(infe.payloadOffset(), infe.payloadSize()), infe.offset());
            input.skipBoxPayload(infe);
        }
    }

    /// Parses an `infe` item info entry.
    ///
    /// @param input the box payload input
    /// @param boxOffset the enclosing box offset
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemInfoEntry(BoxInput input, int boxOffset) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version < 2 || fullBox.version > 3) {
            throw unsupported("Unsupported infe version: " + fullBox.version, boxOffset);
        }
        int itemId = fullBox.version == 2 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
        input.skip(2);
        Item item = requireItem(itemId, input.offset());
        item.type = input.readFourCc();
        reserveMetadataBytes(input.remaining(), input.offset(), "item information strings");
        item.name = readNullTerminatedString(input);
        if ("mime".equals(item.type)) {
            item.contentType = readNullTerminatedString(input);
            if (input.hasRemaining()) {
                readNullTerminatedString(input);
            }
        }
    }

    /// Parses an `iprp` box.
    ///
    /// @param header the enclosing box header
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemProperties(BoxHeader header, BoxInput input) throws AvifDecodeException {
        boolean propertyContainerSeen = false;
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "ipco" -> {
                    if (propertyContainerSeen) {
                        throw parseFailed("Box[iprp] contains a duplicate unique box of type 'ipco'", child.offset());
                    }
                    propertyContainerSeen = true;
                    parseItemPropertyContainer(child, payload);
                }
                case "ipma" -> parseItemPropertyAssociation(payload);
                default -> {
                }
            }
            input.skipBoxPayload(child);
        }
        if (meta.properties.isEmpty()) {
            throw parseFailed("iprp contains no parsed properties", header.offset());
        }
    }

    /// Parses an `ipco` box.
    ///
    /// @param header the enclosing box header
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemPropertyContainer(BoxHeader header, BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader propertyHeader = input.readBoxHeader();
            BoxInput payload = input.slice(propertyHeader.payloadOffset(), propertyHeader.payloadSize());
            reserveMetadataBytes(RETAINED_PROPERTY_SIZE, propertyHeader.offset(), "item property structure");
            meta.properties.add(parseProperty(propertyHeader, payload));
            input.skipBoxPayload(propertyHeader);
        }
        if (meta.properties.isEmpty()) {
            throw parseFailed("ipco contains no properties", header.offset());
        }
    }

    /// Parses one item property.
    ///
    /// @param header the property box header
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private Property parseProperty(BoxHeader header, BoxInput input) throws AvifDecodeException {
        return switch (header.type()) {
            case "ispe" -> parseIspe(input);
            case "av1C" -> parseAv1C(input);
            case "colr" -> parseColr(input);
            case "auxC" -> parseAuxC(input);
            case "pixi" -> parsePixi(input);
            case "pasp" -> parsePasp(input);
            case "clap" -> parseClap(input);
            case "irot" -> parseIrot(input);
            case "imir" -> parseImir(input);
            case "a1op" -> parseA1op(input);
            case "lsel" -> parseLsel(input);
            default -> parseOpaqueProperty(header, input);
        };
    }

    /// Parses an opaque or currently unsupported item property.
    ///
    /// @param header the property box header
    /// @param input the property payload input
    /// @return the parsed opaque property
    /// @throws AvifDecodeException if a UUID property is missing its user type
    private OpaqueProperty parseOpaqueProperty(BoxHeader header, BoxInput input) throws AvifDecodeException {
        reserveMetadataBytes(input.remaining(), input.offset(), header.type() + " item property");
        byte [] userType = null;
        if ("uuid".equals(header.type())) {
            if (input.remaining() < 16) {
                throw parseFailed("uuid item property is missing its 16-byte user type", input.offset());
            }
            userType = input.readBytes(16);
        }
        return new OpaqueProperty(header.type(), userType, input.readBytes(input.remaining()));
    }

    /// Parses an `ispe` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static ImageSpatialExtents parseIspe(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        int width = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int height = checkedU32ToInt(input.readU32(), input.offset() - 4);
        if (width <= 0 || height <= 0) {
            throw parseFailed("ispe dimensions must be positive", input.offset());
        }
        return new ImageSpatialExtents(width, height);
    }

    /// Parses an `av1C` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static Av1Config parseAv1C(BoxInput input) throws AvifDecodeException {
        int first = input.readU8();
        if ((first >>> 7) != 1 || (first & 0x7F) != 1) {
            throw parseFailed("av1C marker/version is invalid", input.offset() - 1);
        }
        int second = input.readU8();
        int third = input.readU8();
        input.readU8();
        int seqProfile = second >>> 5;
        int seqLevelIdx0 = second & 0x1F;
        boolean highBitDepth = ((third >>> 6) & 1) != 0;
        boolean twelveBit = ((third >>> 5) & 1) != 0;
        boolean monochrome = ((third >>> 4) & 1) != 0;
        boolean chromaSubsamplingX = ((third >>> 3) & 1) != 0;
        boolean chromaSubsamplingY = ((third >>> 2) & 1) != 0;
        int chromaSamplePosition = third & 0x03;
        return new Av1Config(
                seqProfile,
                seqLevelIdx0,
                highBitDepth,
                twelveBit,
                monochrome,
                chromaSubsamplingX,
                chromaSubsamplingY,
                chromaSamplePosition
        );
    }

    /// Parses a `colr` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private Property parseColr(BoxInput input) throws AvifDecodeException {
        String colourType = input.readFourCc();
        if ("prof".equals(colourType) || "rICC".equals(colourType)) {
            reserveMetadataBytes(input.remaining(), input.offset(), "ICC color profile");
            byte[] profile = input.readBytes(input.remaining());
            if (profile.length == 0) {
                throw parseFailed("ICC color profile payload is empty", input.offset());
            }
            return new IccColorProfile(profile);
        }
        if (!"nclx".equals(colourType)) {
            reserveMetadataBytes((long) input.remaining() + 4L, input.offset() - 4, "opaque colr property");
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.writeBytes(colourType.getBytes(StandardCharsets.ISO_8859_1));
            payload.writeBytes(input.readBytes(input.remaining()));
            return new OpaqueProperty("colr", null, payload.toByteArray());
        }
        int colorPrimaries = input.readU16();
        int transferCharacteristics = input.readU16();
        int matrixCoefficients = input.readU16();
        int packedRange = input.readU8();
        if ((packedRange & 0x7F) != 0) {
            throw parseFailed("nclx reserved bits must be zero", input.offset() - 1);
        }
        return new ColorProperty(new AvifColorInfo(
                colorPrimaries,
                transferCharacteristics,
                matrixCoefficients,
                (packedRange & 0x80) != 0
        ));
    }

    /// Parses an `auxC` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private AuxiliaryType parseAuxC(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        reserveMetadataBytes(input.remaining(), input.offset(), "auxiliary type property");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (input.hasRemaining()) {
            int value = input.readU8();
            if (value == 0) {
                break;
            }
            output.write(value);
        }
        return new AuxiliaryType(output.toString(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /// Parses a `pixi` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static PixelInformation parsePixi(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        int numChannels = input.readU8();
        if (numChannels < 1) {
            throw parseFailed("pixi must have at least one channel", input.offset() - 1);
        }
        if (numChannels > input.remaining()) {
            throw parseFailed("pixi numChannels exceeds available payload", input.offset() - 1);
        }
        int[] bitsPerChannel = new int[numChannels];
        for (int i = 0; i < numChannels; i++) {
            bitsPerChannel[i] = input.readU8();
        }
        return new PixelInformation(bitsPerChannel);
    }

    /// Parses a `pasp` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static PixelAspectRatio parsePasp(BoxInput input) throws AvifDecodeException {
        int hSpacing = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int vSpacing = checkedU32ToInt(input.readU32(), input.offset() - 4);
        if (hSpacing <= 0 || vSpacing <= 0) {
            throw parseFailed("pasp spacing values must be positive", input.offset());
        }
        return new PixelAspectRatio(hSpacing, vSpacing);
    }

    /// Parses a `clap` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static CleanAperture parseClap(BoxInput input) throws AvifDecodeException {
        int cleanApertureWidthN = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int cleanApertureWidthD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int cleanApertureHeightN = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int cleanApertureHeightD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int horizOffN = (int) input.readU32();
        int horizOffD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int vertOffN = (int) input.readU32();
        int vertOffD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        if (cleanApertureWidthD <= 0 || cleanApertureHeightD <= 0 || horizOffD <= 0 || vertOffD <= 0) {
            throw parseFailed("clap denominator values must be positive", input.offset());
        }
        return new CleanAperture(
                cleanApertureWidthN, cleanApertureWidthD,
                cleanApertureHeightN, cleanApertureHeightD,
                horizOffN, horizOffD,
                vertOffN, vertOffD
        );
    }

    /// Parses an `irot` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static ImageRotation parseIrot(BoxInput input) throws AvifDecodeException {
        int packed = input.readU8();
        if ((packed & 0xFC) != 0) {
            throw parseFailed("irot reserved bits must be zero", input.offset() - 1);
        }
        int rotation = packed & 0x03;
        return new ImageRotation(rotation);
    }

    /// Parses an `imir` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static ImageMirror parseImir(BoxInput input) throws AvifDecodeException {
        int packed = input.readU8();
        if ((packed & 0xFE) != 0) {
            throw parseFailed("imir reserved bits must be zero", input.offset() - 1);
        }
        int axis = packed & 0x01;
        if (axis != 0 && axis != 1) {
            throw parseFailed("imir axis must be 0 or 1", input.offset() - 1);
        }
        return new ImageMirror(axis);
    }

    /// Parses an `a1op` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static OperatingPoint parseA1op(BoxInput input) throws AvifDecodeException {
        if (input.remaining() != 1) {
            throw parseFailed("a1op payload must contain exactly one byte", input.offset());
        }
        int operatingPoint = input.readU8();
        if (operatingPoint > 31) {
            throw parseFailed("a1op operating point exceeds maximum", input.offset() - 1);
        }
        return new OperatingPoint(operatingPoint);
    }

    /// Parses an `lsel` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static LayerSelector parseLsel(BoxInput input) throws AvifDecodeException {
        if (input.remaining() != 2) {
            throw parseFailed("lsel payload must contain exactly two bytes", input.offset());
        }
        int layerId = input.readU16();
        if (layerId > 3 && layerId != 0xFFFF) {
            throw parseFailed("lsel layer_id must be in [0, 3] or 65535", input.offset() - 2);
        }
        return new LayerSelector(layerId);
    }

    /// Creates an AVIF container from parsed sequence data.
    private AvifContainer parseSequenceImage() throws AvifDecodeException {
        MoovState s = meta.moovState;
        if (s.seqHeaderObu == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "AVIS container has no usable AV1 color track",
                    null
            );
        }
        if (s.width <= 0 || s.height <= 0 || s.bitDepth <= 0 || s.chromaFormat == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "AVIS color track is missing required image configuration",
                    null
            );
        }
        if (s.mediaTimescale <= 0) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "AVIS color track has no positive media timescale",
                    null
            );
        }
        validateSequencePremultipliedAlphaReferences();
        SequencePayloads colorPayloads = sequencePayloads(s, "Color sequence");
        AvifPayload [] alphaPayloads = sequenceAuxiliaryPayloads(
                meta.moovAlphaState,
                colorPayloads.sampleCount,
                "Alpha sequence"
        );
        AvifPayload [] depthPayloads = sequenceAuxiliaryPayloads(
                meta.moovDepthState,
                colorPayloads.sampleCount,
                "Depth sequence"
        );
        long dur = sequenceDuration(s, colorPayloads);
        int repetitionCount = sequenceRepetitionCount(s);
        AvifImageInfo info = new AvifImageInfo(
                s.width,
                s.height,
                AvifBitDepth.fromBits(s.bitDepth),
                s.chromaFormat
        ).withSequenceInfo(new AvifSequenceInfo(
                        colorPayloads.sampleCount,
                        s.mediaTimescale,
                        dur,
                        repetitionCount,
                        colorPayloads.frameDeltas
                ))
                .withAuxiliaryImages(meta.moovAuxiliaryTypes.toArray(String[]::new), null)
                .withAlpha(alphaPayloads != null, sequenceAlphaPremultiplied())
                .withColorInfo(s.colr)
                .withIccProfile(s.iccProfile);
        return new AvifContainer(info,
                colorPayloads.payloads,
                alphaPayloads,
                depthPayloads);
    }

    /// Returns whether one top-level BMFF box may use size 0 to extend to EOF.
    ///
    /// @param type the box type
    /// @return whether this parser accepts size 0 for the box type
    private static boolean allowsTopLevelSizeZero(String type) {
        return "mdat".equals(type) || "meta".equals(type) || "moov".equals(type);
    }

    /// Resolves and validates the total sequence duration.
    ///
    /// @param track the parsed color track state
    /// @param payloads the extracted color sample payloads
    /// @return the reconciled duration in media timescale units
    /// @throws AvifDecodeException if the advertised duration conflicts with per-frame durations
    private static long sequenceDuration(MoovState track, SequencePayloads payloads) throws AvifDecodeException {
        long frameDurationSum = frameDurationSum(payloads.frameDeltas);
        if (track.editListRepeating && track.editListSegmentDuration != frameDurationSum) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Color sequence edit-list duration does not match frame durations: "
                            + track.editListSegmentDuration + " != " + frameDurationSum,
                    null
            );
        }
        if (track.mediaDuration == 0) {
            return frameDurationSum;
        }
        if (track.mediaDuration != frameDurationSum) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Color sequence media duration does not match frame durations: "
                            + track.mediaDuration + " != " + frameDurationSum,
                    null
            );
        }
        return track.mediaDuration;
    }

    /// Returns whether the selected AVIS color track is premultiplied by the selected alpha track.
    ///
    /// @return whether the selected AVIS sequence uses premultiplied alpha
    private boolean sequenceAlphaPremultiplied() {
        MoovState alphaTrack = meta.moovAlphaState;
        return alphaTrack != null
                && alphaTrack.trackId != 0
                && meta.moovState.premultipliedByTrackIds.contains(alphaTrack.trackId);
    }

    /// Resolves the animated-sequence repetition count from a parsed edit list.
    ///
    /// @param track the parsed color track state
    /// @return the public repetition-count value
    /// @throws AvifDecodeException if a repeating edit list has an invalid track duration
    private static int sequenceRepetitionCount(MoovState track) throws AvifDecodeException {
        if (!track.editListSeen) {
            return AvifSequenceInfo.REPETITION_COUNT_UNKNOWN;
        }
        if (!track.editListRepeating) {
            return 0;
        }
        if (track.trackDuration == INDEFINITE_TRACK_DURATION) {
            return AvifSequenceInfo.REPETITION_COUNT_INFINITE;
        }
        if (track.trackDuration == 0) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Invalid repeating edit-list track duration", null);
        }
        long segmentDuration = track.editListSegmentDuration;
        long repetitionCount = (track.trackDuration / segmentDuration)
                + (track.trackDuration % segmentDuration != 0 ? 1 : 0)
                - 1;
        return repetitionCount > Integer.MAX_VALUE
                ? AvifSequenceInfo.REPETITION_COUNT_INFINITE
                : (int) repetitionCount;
    }

    /// Sums per-frame duration deltas.
    ///
    /// @param frameDeltas the frame duration deltas
    /// @return the total duration
    private static long frameDurationSum(int [] frameDeltas) {
        long sum = 0;
        for (int frameDelta : frameDeltas) {
            sum += frameDelta;
        }
        return sum;
    }

    /// Extracts sample payloads and frame deltas from one AVIS track.
    ///
    /// @param track the parsed track state
    /// @param label the diagnostic label
    /// @return extracted sample payloads
    /// @throws AvifDecodeException if sample data is missing or truncated
    private SequencePayloads sequencePayloads(MoovState track, String label) throws AvifDecodeException {
        if (track.sampleSizes.isEmpty()) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, label + " has no samples", null);
        }
        if (track.chunkOffsets.isEmpty()) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, label + " has no chunk offsets", null);
        }
        int sampleCount = track.sampleSizes.size();
        reserveRetainedElements(
                sampleCount,
                RETAINED_SEQUENCE_PAYLOAD_SIZE,
                -1L,
                label + " resolved sample payloads"
        );
        if (track.sampleDeltas.size() != sampleCount) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " timing table does not cover all samples",
                    null
            );
        }
        if (track.sampleToChunkEntries.isEmpty()) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " has no sample-to-chunk table",
                    null
            );
        }
        AvifPayload[] payloads = new AvifPayload[sampleCount];
        int[] deltas = new int[sampleCount];

        int sampleIndex = 0;
        int entryIndex = 0;
        for (int chunkIndex = 1; chunkIndex <= track.chunkOffsets.size() && sampleIndex < sampleCount; chunkIndex++) {
            while (entryIndex + 1 < track.sampleToChunkEntries.size()
                    && chunkIndex >= track.sampleToChunkEntries.get(entryIndex + 1).firstChunk) {
                entryIndex++;
            }
            SampleToChunkEntry entry = track.sampleToChunkEntries.get(entryIndex);
            int exclusiveSampleEnd = (int) Math.min(
                    sampleCount,
                    (long) sampleIndex + entry.samplesPerChunk
            );
            sampleIndex = collectSequentialChunkSamples(
                    track,
                    label,
                    payloads,
                    deltas,
                    sampleIndex,
                    chunkIndex - 1,
                    exclusiveSampleEnd
            );
        }
        if (sampleIndex != sampleCount) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " sample-to-chunk table does not cover all samples",
                    null
            );
        }
        return new SequencePayloads(payloads, deltas, sampleCount);
    }

    /// Collects sequential sample descriptors from one chunk.
    ///
    /// @param track the parsed track state
    /// @param label the diagnostic label
    /// @param payloads the destination sample payloads
    /// @param deltas the destination frame deltas
    /// @param firstSampleIndex the first sample index to copy
    /// @param chunkIndex the zero-based chunk index
    /// @param exclusiveSampleEnd the exclusive sample index end
    /// @return the next uncopied sample index
    /// @throws AvifDecodeException if the chunk sample data is outside the source
    private int collectSequentialChunkSamples(
            MoovState track,
            String label,
            AvifPayload[] payloads,
            int[] deltas,
            int firstSampleIndex,
            int chunkIndex,
            int exclusiveSampleEnd
    ) throws AvifDecodeException {
        long offset = track.chunkOffsets.get(chunkIndex);
        int sampleIndex = firstSampleIndex;
        while (sampleIndex < exclusiveSampleEnd) {
            int size = track.sampleSizes.get(sampleIndex);
            payloads[sampleIndex] = sequenceSample(label, sampleIndex, offset, size);
            deltas[sampleIndex] = track.sampleDeltas.get(sampleIndex);
            offset += size;
            sampleIndex++;
        }
        return sampleIndex;
    }

    /// Describes one AVIS sample payload without copying its contents.
    ///
    /// @param label the diagnostic label
    /// @param sampleIndex the zero-based sample index
    /// @param offset the absolute payload offset
    /// @param size the sample size in bytes
    /// @return the sample payload descriptor
    /// @throws AvifDecodeException if the sample is outside the source
    private AvifPayload sequenceSample(String label, int sampleIndex, long offset, int size)
            throws AvifDecodeException {
        if (offset < 0 || size < 0 || offset > source.limit() || size > source.limit() - offset) {
            throw new AvifDecodeException(
                    source.isSeekable() ? AvifErrorCode.TRUNCATED_DATA : AvifErrorCode.INPUT_TOO_LARGE,
                    source.isSeekable()
                            ? label + " sample outside source: " + sampleIndex
                            : label + " sample exceeds the configured input-size limit: " + sampleIndex,
                    offset
            );
        }
        return AvifPayload.ofRanges(source, new long[]{offset}, new int[]{size});
    }

    /// Extracts auxiliary sample payloads and validates the sample count.
    ///
    /// @param track the parsed auxiliary track state, or `null`
    /// @param colorSampleCount the color track sample count
    /// @param label the diagnostic label
    /// @return extracted auxiliary payloads, or `null`
    /// @throws AvifDecodeException if the auxiliary sample table is malformed
    private AvifPayload [] sequenceAuxiliaryPayloads(
            MoovState track,
            int colorSampleCount,
            String label
    ) throws AvifDecodeException {
        if (track == null) {
            return null;
        }
        SequencePayloads payloads = sequencePayloads(track, label);
        if (payloads.sampleCount != colorSampleCount) {
            throw unsupported(
                    label + " sample count does not match color sequence: " + payloads.sampleCount
                            + " != " + colorSampleCount,
                    null
            );
        }
        return payloads.payloads;
    }

    /// Parses a `moov` box for AVIS image sequences.
    ///
    /// Navigates to supported color and auxiliary-image tracks and extracts frame metadata.
    ///
    /// @param input the moov box payload
    /// @throws AvifDecodeException if the box is malformed
    private void parseMoov(BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            if ("trak".equals(child.type())) {
                reserveMetadataBytes(RETAINED_MOOV_STATE_SIZE, child.offset(), "AVIS track state");
                MoovState parsedTrack = new MoovState();
                BoxInput trakPayload = input.slice(child.payloadOffset(), child.payloadSize());
                parseMoovTrack(trakPayload, parsedTrack);
                finalizeMoovTrack(parsedTrack);
                inferLegacyMoovAuxiliaryType(parsedTrack);
                if (isMoovImageTrack(parsedTrack)) {
                    if (parsedTrack.auxiliaryType != null) {
                        meta.moovAuxiliaryCandidates.add(parsedTrack);
                    } else if (parsedTrack.seqHeaderObu != null
                            && (meta.moovState.seqHeaderObu == null
                            || moovColorTrackPreference(parsedTrack) > moovColorTrackPreference(meta.moovState))) {
                        meta.moovState = parsedTrack;
                    }
                }
            }
            input.skipBoxPayload(child);
        }
        resolveMoovAuxiliaryTracks();
    }

    /// Finalizes AV1 configuration that depends on the complete parsed track geometry.
    ///
    /// @param track the parsed track to finalize
    private static void finalizeMoovTrack(MoovState track) throws AvifDecodeException {
        if (isPotentialMoovImageTrack(track)
                && track.containsAv1SampleDescription
                && track.sampleDescriptionCount != 1) {
            throw parseFailed("AVIS stsd must contain exactly one sample description");
        }
        if (isPotentialMoovImageTrack(track)
                && track.av1Config != null
                && track.hasNonPrimarySampleDescriptionReference) {
            throw parseFailed("AVIS stsc sample_description_index must be 1");
        }
        if (isPotentialMoovImageTrack(track)
                && track.av1Config != null
                && track.maximumSyncSampleNumber > track.sampleSizes.size()) {
            throw parseFailed("AVIS stss sample number is outside the sample table");
        }
        Av1Config av1Config = track.av1Config;
        if (av1Config != null && track.width > 0 && track.height > 0) {
            track.seqHeaderObu = av1Config.seqHeaderObu(track.width, track.height);
        }
    }

    /// Infers the alpha type for legacy monochrome AVIS auxiliary tracks without `auxi`.
    ///
    /// Early AVIF sequence encoders identified alpha tracks only through a `tref/auxl`
    /// relationship. A monochrome track with that relationship is treated as alpha so that its
    /// samples remain associated with the selected color sequence.
    ///
    /// @param track the parsed track state
    private static void inferLegacyMoovAuxiliaryType(MoovState track) {
        if (track.auxiliaryType == null
                && !track.auxiliaryForTrackIds.isEmpty()
                && track.chromaFormat == Av1ChromaFormat.MONOCHROME) {
            track.auxiliaryType = AvifAuxiliaryImageInfo.ALPHA_TYPE;
        }
    }

    /// Returns whether a parsed `trak` can be used as an AVIS image-sequence track.
    ///
    /// @param track the parsed track state
    /// @return whether the track handler describes a color or auxiliary image track
    private static boolean isMoovImageTrack(MoovState track) {
        String handlerType = track.mediaHandlerType;
        return handlerType == null
                || "pict".equals(handlerType)
                || "vide".equals(handlerType)
                || ("auxv".equals(handlerType) && track.auxiliaryType != null);
    }

    /// Returns whether a handler may describe an AVIS image track before its sample entry is parsed.
    ///
    /// @param track the partially parsed track state
    /// @return whether the handler permits an AV1 image sample entry
    private static boolean isPotentialMoovImageTrack(MoovState track) {
        String handlerType = track.mediaHandlerType;
        return handlerType == null
                || "pict".equals(handlerType)
                || "vide".equals(handlerType)
                || "auxv".equals(handlerType);
    }

    /// Returns the deterministic selection priority for one AVIS color-track candidate.
    ///
    /// A conforming `pict` handler takes precedence over legacy `vide` or omitted handler
    /// signaling. Tracks at the same priority retain file order.
    ///
    /// @param track the parsed color-track candidate
    /// @return the candidate priority
    private static int moovColorTrackPreference(MoovState track) {
        return "pict".equals(track.mediaHandlerType) ? 2 : 1;
    }

    /// Resolves AVIS auxiliary tracks against the selected color track.
    private void resolveMoovAuxiliaryTracks() throws AvifDecodeException {
        meta.moovAuxiliaryTypes.clear();
        meta.moovAlphaState = null;
        meta.moovDepthState = null;
        for (MoovState candidate : meta.moovAuxiliaryCandidates) {
            if (candidate.seqHeaderObu == null || !auxiliaryTrackMatchesColor(candidate, meta.moovState)) {
                continue;
            }
            if (AvifAuxiliaryImageInfo.ALPHA_TYPE.equals(candidate.auxiliaryType)) {
                validateMoovAlphaTrackGeometry(candidate, meta.moovState);
            }
            if (!meta.moovAuxiliaryTypes.contains(candidate.auxiliaryType)) {
                meta.moovAuxiliaryTypes.add(candidate.auxiliaryType);
            }
            if (AvifAuxiliaryImageInfo.ALPHA_TYPE.equals(candidate.auxiliaryType) && meta.moovAlphaState == null) {
                meta.moovAlphaState = candidate;
            }
            if (AvifAuxiliaryImageInfo.DEPTH_TYPE.equals(candidate.auxiliaryType) && meta.moovDepthState == null) {
                meta.moovDepthState = candidate;
            }
        }
    }

    /// Returns whether an auxiliary AVIS track is associated with the selected color track.
    ///
    /// @param auxiliaryTrack the candidate auxiliary track
    /// @param colorTrack the selected color track
    /// @return whether the auxiliary track applies to the selected color track
    private static boolean auxiliaryTrackMatchesColor(MoovState auxiliaryTrack, MoovState colorTrack) {
        return auxiliaryTrack.auxiliaryForTrackIds.isEmpty()
                || (colorTrack.trackId != 0 && auxiliaryTrack.auxiliaryForTrackIds.contains(colorTrack.trackId));
    }

    /// Validates that one matched AVIS alpha track has the color track's geometry.
    ///
    /// @param auxiliaryTrack the matched alpha track
    /// @param colorTrack the selected color track
    /// @throws AvifDecodeException if the alpha track dimensions differ
    private static void validateMoovAlphaTrackGeometry(
            MoovState auxiliaryTrack,
            MoovState colorTrack
    ) throws AvifDecodeException {
        if (auxiliaryTrack.width > 0
                && colorTrack.width > 0
                && auxiliaryTrack.width != colorTrack.width) {
            throw parseFailed(
                    moovAuxiliaryLabel(auxiliaryTrack) + " sequence track width does not match color sequence: "
                            + auxiliaryTrack.width + " != " + colorTrack.width
            );
        }
        if (auxiliaryTrack.height > 0
                && colorTrack.height > 0
                && auxiliaryTrack.height != colorTrack.height) {
            throw parseFailed(
                    moovAuxiliaryLabel(auxiliaryTrack) + " sequence track height does not match color sequence: "
                            + auxiliaryTrack.height + " != " + colorTrack.height
            );
        }
    }

    /// Validates `prem` references from the selected AVIS color track.
    ///
    /// @throws AvifDecodeException if the color track's premultiplied-alpha references are malformed
    private void validateSequencePremultipliedAlphaReferences() throws AvifDecodeException {
        List<Integer> premultipliedByTrackIds = meta.moovState.premultipliedByTrackIds;
        if (premultipliedByTrackIds.isEmpty()) {
            return;
        }
        MoovState alphaTrack = meta.moovAlphaState;
        if (alphaTrack == null || !premultipliedByTrackIds.contains(alphaTrack.trackId)) {
            throw parseFailed("AVIS prem track references do not target the selected alpha auxiliary track");
        }
    }

    /// Returns a diagnostic label for one AVIS auxiliary track.
    ///
    /// @param auxiliaryTrack the auxiliary track
    /// @return the diagnostic label
    private static String moovAuxiliaryLabel(MoovState auxiliaryTrack) {
        if (AvifAuxiliaryImageInfo.ALPHA_TYPE.equals(auxiliaryTrack.auxiliaryType)) {
            return "Alpha";
        }
        if (AvifAuxiliaryImageInfo.DEPTH_TYPE.equals(auxiliaryTrack.auxiliaryType)) {
            return "Depth";
        }
        return "Auxiliary";
    }

    /// Parses a `trak` box and extracts video track metadata.
    private void parseMoovTrack(BoxInput input, MoovState track) throws AvifDecodeException {
        boolean trackHeaderSeen = false;
        boolean mediaSeen = false;
        boolean edtsSeen = false;
        boolean trefSeen = false;
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "tkhd" -> {
                    if (trackHeaderSeen) {
                        throw parseFailed("Box[trak] contains a duplicate unique box of type 'tkhd'", child.offset());
                    }
                    trackHeaderSeen = true;
                    parseMoovTkhd(payload, track);
                }
                case "tref" -> {
                    if (trefSeen) {
                        throw parseFailed("Box[trak] contains a duplicate unique box of type 'tref'", child.offset());
                    }
                    trefSeen = true;
                    parseMoovTref(payload, track);
                }
                case "edts" -> {
                    if (edtsSeen) {
                        throw parseFailed("Box[trak] contains a duplicate unique box of type 'edts'", child.offset());
                    }
                    edtsSeen = true;
                    parseMoovEdts(payload, track);
                }
                case "mdia" -> {
                    if (mediaSeen) {
                        throw parseFailed("Box[trak] contains a duplicate unique box of type 'mdia'", child.offset());
                    }
                    mediaSeen = true;
                    parseMoovMdia(payload, track);
                }
                default -> {}
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses a `tkhd` box for track dimensions.
    private static void parseMoovTkhd(BoxInput input, MoovState track) throws AvifDecodeException {
        FullBox fb = readFullBox(input);
        if (fb.version == 1) {
            input.skip(16);
        } else {
            input.skip(8);
        }
        track.trackId = checkedU32ToInt(input.readU32(), input.offset() - 4);
        input.skip(4);
        track.trackDuration = fb.version == 1 ? readTrackDuration64(input) : readTrackDuration32(input);
        input.skip(52);
        long w = input.readU32();
        long h = input.readU32();
        int width = checkedU32ToInt(w >>> 16, input.offset());
        int height = checkedU32ToInt(h >>> 16, input.offset());
        if (width > 0 && track.width == 0) {
            track.width = width;
        }
        if (height > 0 && track.height == 0) {
            track.height = height;
        }
    }

    /// Parses a `tref` box for AVIS track references.
    ///
    /// @param input the tref box payload
    /// @throws AvifDecodeException if the box is malformed
    private void parseMoovTref(BoxInput input, MoovState track) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "auxl" -> parseMoovTrackReference(payload, track.auxiliaryForTrackIds, "auxl");
                case "prem" -> parseMoovTrackReference(payload, track.premultipliedByTrackIds, "prem");
                default -> {
                }
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses one track-reference type box.
    ///
    /// @param input the reference box payload
    /// @param target the destination track-id list
    /// @param type the reference box type
    /// @throws AvifDecodeException if the reference payload is malformed
    private void parseMoovTrackReference(
            BoxInput input,
            List<Integer> target,
            String type
    ) throws AvifDecodeException {
        if (input.remaining() == 0 || (input.remaining() & 3) != 0) {
            throw parseFailed("Box[tref]/" + type + " must contain one or more 32-bit track IDs", input.offset());
        }
        reserveRetainedElements(
                input.remaining() / Integer.BYTES,
                RETAINED_SAMPLE_INTEGER_SIZE,
                input.offset(),
                "AVIS " + type + " track references"
        );
        while (input.hasRemaining()) {
            int trackId = checkedU32ToInt(input.readU32(), input.offset() - 4);
            if (trackId <= 0) {
                throw parseFailed("Box[tref]/" + type + " track_ID must be positive", input.offset() - 4);
            }
            target.add(trackId);
        }
    }

    /// Parses an `edts` box for AVIS edit-list metadata.
    private static void parseMoovEdts(BoxInput input, MoovState track) throws AvifDecodeException {
        boolean elstSeen = false;
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            if ("elst".equals(child.type())) {
                if (elstSeen) {
                    throw parseFailed("More than one [elst] box was found", child.offset());
                }
                elstSeen = true;
                BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
                parseMoovElst(payload, track);
            }
            input.skipBoxPayload(child);
        }
        if (!elstSeen) {
            throw parseFailed("Box[edts] contains no [elst] box", input.offset());
        }
    }

    /// Parses an `elst` box for AVIS repetition metadata.
    private static void parseMoovElst(BoxInput input, MoovState track) throws AvifDecodeException {
        FullBox fb = readFullBox(input);
        track.editListSeen = true;
        if ((fb.flags & 1) == 0) {
            track.editListRepeating = false;
            return;
        }
        if (fb.version != 0 && fb.version != 1) {
            throw parseFailed("Box[elst] has an unsupported version: " + fb.version, input.offset() - 4);
        }
        int entryCount = checkedU32ToInt(input.readU32(), input.offset() - 4);
        if (entryCount != 1) {
            throw parseFailed("Box[elst] contains an entry_count != 1: " + entryCount, input.offset() - 4);
        }
        long segmentDuration = fb.version == 1 ? input.readU64() : input.readU32();
        if (segmentDuration == 0) {
            throw parseFailed("Box[elst] has a zero segment_duration", input.offset() - (fb.version == 1 ? 8 : 4));
        }
        input.skip(fb.version == 1 ? 8 : 4);
        input.skip(4);
        track.editListRepeating = true;
        track.editListSegmentDuration = segmentDuration;
    }

    /// Parses a `mdia` box to reach the media information and sample table.
    private void parseMoovMdia(BoxInput input, MoovState track) throws AvifDecodeException {
        // Resolve the handler before interpreting sample-table restrictions. BMFF does not make
        // child-box order significant, and non-image tracks may use otherwise unsupported tables.
        BoxInput handlerScan = input.slice(input.offset(), input.remaining());
        boolean hdlrSeen = false;
        while (handlerScan.hasRemaining()) {
            BoxHeader child = handlerScan.readBoxHeader();
            if ("hdlr".equals(child.type())) {
                if (hdlrSeen) {
                    throw parseFailed("Box[mdia] contains a duplicate unique box of type 'hdlr'", child.offset());
                }
                hdlrSeen = true;
                parseMoovHandler(handlerScan.slice(child.payloadOffset(), child.payloadSize()), track);
            }
            handlerScan.skipBoxPayload(child);
        }
        boolean mediaHeaderSeen = false;
        boolean mediaInfoSeen = false;
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "mdhd" -> {
                    if (mediaHeaderSeen) {
                        throw parseFailed("Box[mdia] contains a duplicate unique box of type 'mdhd'", child.offset());
                    }
                    mediaHeaderSeen = true;
                    parseMoovMdhd(payload, track);
                }
                case "hdlr" -> {}
                case "minf" -> {
                    if (mediaInfoSeen) {
                        throw parseFailed("Box[mdia] contains a duplicate unique box of type 'minf'", child.offset());
                    }
                    mediaInfoSeen = true;
                    parseMoovMinf(payload, track);
                }
                default -> {}
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses an AVIS media handler box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the handler box is malformed
    private static void parseMoovHandler(BoxInput input, MoovState track) throws AvifDecodeException {
        readFullBox(input);
        long preDefined = input.readU32();
        if (preDefined != 0) {
            throw parseFailed("mdia hdlr pre_defined must be zero", input.offset() - 4);
        }
        track.mediaHandlerType = input.readFourCc();
    }

    /// Parses an `mdhd` box for media timescale and duration.
    private static void parseMoovMdhd(BoxInput input, MoovState track) throws AvifDecodeException {
        FullBox fb = readFullBox(input);
        if (fb.version == 1) {
            input.skip(16);
        } else {
            input.skip(8);
        }
        int timescale = checkedU32ToInt(input.readU32(), input.offset() - 4);
        long duration = fb.version == 1 ? input.readU64() : input.readU32();
        track.mediaTimescale = timescale;
        track.mediaDuration = duration;
    }

    /// Parses a `minf` box to reach the sample table.
    private void parseMoovMinf(BoxInput input, MoovState track) throws AvifDecodeException {
        boolean sampleTableSeen = false;
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            if ("stbl".equals(child.type())) {
                if (sampleTableSeen) {
                    throw parseFailed("Box[minf] contains a duplicate unique box of type 'stbl'", child.offset());
                }
                sampleTableSeen = true;
                BoxInput stblPayload = input.slice(child.payloadOffset(), child.payloadSize());
                parseMoovStbl(stblPayload, track);
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses a `stbl` (sample table) box and extracts all available sample metadata.
    private void parseMoovStbl(BoxInput input, MoovState track) throws AvifDecodeException {
        Set<String> seenTables = new HashSet<>();
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "stsd" -> {
                    unique(seenTables, child.type(), child.offset());
                    parseMoovStsd(payload, track);
                }
                case "stts" -> {
                    unique(seenTables, child.type(), child.offset());
                    parseMoovStts(payload, track);
                }
                case "stco", "co64" -> {
                    if (seenTables.contains("stco") || seenTables.contains("co64")) {
                        throw parseFailed("AVIS sample table contains multiple chunk-offset tables", child.offset());
                    }
                    seenTables.add(child.type());
                    if ("stco".equals(child.type())) {
                        parseMoovStco(payload, track);
                    } else {
                        parseMoovCo64(payload, track);
                    }
                }
                case "stsc" -> {
                    unique(seenTables, child.type(), child.offset());
                    parseMoovStsc(payload, track);
                }
                case "stsz" -> {
                    unique(seenTables, child.type(), child.offset());
                    parseMoovStsz(payload, track);
                }
                case "stss" -> {
                    unique(seenTables, child.type(), child.offset());
                    parseMoovStss(payload, track);
                }
                default -> {}
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses `stsd` and extracts the av01 sample entry with av1C config.
    private void parseMoovStsd(BoxInput input, MoovState track) throws AvifDecodeException {
        readFullBox(input);
        int entryCount = checkedU32ToInt(input.readU32(), input.offset() - 4);
        enforceSequenceTableEntryCount(entryCount, "stsd", input.offset() - 4);
        track.sampleDescriptionCount = entryCount;
        if (entryCount != 1 && isPotentialMoovImageTrack(track)) {
            throw parseFailed("AVIS stsd must contain exactly one sample description", input.offset() - 4);
        }
        for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
            BoxHeader entry = input.readBoxHeader();
            if ("av01".equals(entry.type())) {
                track.containsAv1SampleDescription = true;
                if (entryCount == 1) {
                    BoxInput av01i = input.slice(entry.payloadOffset(), entry.payloadSize());
                    parseMoovAv01Entry(av01i, track);
                }
            }
            input.skipBoxPayload(entry);
        }
        if (input.hasRemaining()) {
            throw parseFailed("AVIS stsd contains trailing data after its sample descriptions", input.offset());
        }
    }

    /// Extracts av1C and colr from an av01 sample entry.
    private void parseMoovAv01Entry(BoxInput input, MoovState track) throws AvifDecodeException {
        input.skip(24);
        int w = input.readU16();
        int h = input.readU16();
        if (w > 0 && track.width == 0) track.width = w;
        if (h > 0 && track.height == 0) track.height = h;
        input.skip(50);
        boolean av1ConfigSeen = false;
        boolean auxiliaryTypeSeen = false;
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "av1C" -> {
                    if (av1ConfigSeen) {
                        throw parseFailed("AVIS av01 entry contains multiple av1C boxes", child.offset());
                    }
                    av1ConfigSeen = true;
                    Av1Config c = parseAv1C(payload);
                    track.av1Config = c;
                    track.bitDepth = c.bitDepth();
                    track.chromaFormat = c.chromaFormat();
                }
                case "colr" -> {
                    Property p = parseColr(payload);
                    if (p instanceof ColorProperty cp) track.colr = cp.colorInfo;
                    if (p instanceof IccColorProfile icc) track.iccProfile = icc.profile();
                }
                case "auxi" -> {
                    if (auxiliaryTypeSeen) {
                        throw parseFailed("AVIS av01 entry contains multiple auxi boxes", child.offset());
                    }
                    auxiliaryTypeSeen = true;
                    track.auxiliaryType = parseMoovAuxiliaryType(payload);
                }
                default -> {}
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses an `auxi` sample-entry box for an AVIS auxiliary track.
    ///
    /// @param input the box payload input
    /// @return the auxiliary image type string
    /// @throws AvifDecodeException if the box is malformed or unsupported
    private String parseMoovAuxiliaryType(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version != 0 || fullBox.flags != 0) {
            throw unsupported("Unsupported auxi version/flags", input.offset());
        }
        reserveMetadataBytes(input.remaining(), input.offset(), "AVIS auxiliary type");
        return readNullTerminatedString(input);
    }

    /// Parses `stts` for sample timing deltas.
    private void parseMoovStts(BoxInput input, MoovState track) throws AvifDecodeException {
        input.skip(4);
        int n = checkedU32ToInt(input.readU32(), input.offset() - 4);
        enforceSequenceTableEntryCount(n, "stts", input.offset() - 4);
        for (int i = 0; i < n; i++) {
            int sc = checkedU32ToInt(input.readU32(), input.offset() - 4);
            int sd = checkedU32ToInt(input.readU32(), input.offset() - 4);
            if (sc <= 0) {
                throw parseFailed("stts sample_count must be positive", input.offset() - 8);
            }
            if (sd <= 0) {
                throw parseFailed("stts sample_delta must be positive", input.offset() - 4);
            }
            enforceSequenceSampleCount(
                    track.sampleDeltas.size(),
                    sc,
                    "stts",
                    input.offset() - 8
            );
            reserveRetainedElements(
                    sc,
                    RETAINED_SAMPLE_INTEGER_SIZE,
                    input.offset() - 8L,
                    "stts expanded sample deltas"
            );
            for (int j = 0; j < sc; j++) track.sampleDeltas.add(sd);
        }
    }

    /// Parses `stco` for chunk offsets.
    private void parseMoovStco(BoxInput input, MoovState track) throws AvifDecodeException {
        input.skip(4);
        int n = checkedU32ToInt(input.readU32(), input.offset() - 4);
        enforceSequenceTableEntryCount(n, "stco", input.offset() - 4);
        reserveRetainedElements(n, RETAINED_SAMPLE_INTEGER_SIZE, input.offset() - 4L, "stco chunk offsets");
        for (int i = 0; i < n; i++)
            track.chunkOffsets.add(checkedU32ToInt(input.readU32(), input.offset() - 4));
    }

    /// Parses `co64` for 64-bit chunk offsets.
    private void parseMoovCo64(BoxInput input, MoovState track) throws AvifDecodeException {
        input.skip(4);
        int n = checkedU32ToInt(input.readU32(), input.offset() - 4);
        enforceSequenceTableEntryCount(n, "co64", input.offset() - 4);
        reserveRetainedElements(n, RETAINED_SAMPLE_INTEGER_SIZE, input.offset() - 4L, "co64 chunk offsets");
        for (int i = 0; i < n; i++)
            track.chunkOffsets.add(checkedU64ToInt(input.readU64(), input.offset() - 8));
    }

    /// Parses `stsc` for sample-to-chunk layout.
    private void parseMoovStsc(BoxInput input, MoovState track) throws AvifDecodeException {
        input.skip(4);
        int n = checkedU32ToInt(input.readU32(), input.offset() - 4);
        enforceSequenceTableEntryCount(n, "stsc", input.offset() - 4);
        reserveRetainedElements(n, RETAINED_SAMPLE_TO_CHUNK_SIZE, input.offset() - 4L, "stsc entries");
        for (int i = 0; i < n; i++) {
            int firstChunk = checkedU32ToInt(input.readU32(), input.offset() - 4);
            int samplesPerChunk = checkedU32ToInt(input.readU32(), input.offset() - 4);
            int sampleDescriptionIndex = checkedU32ToInt(input.readU32(), input.offset() - 4);
            if (firstChunk <= 0) {
                throw parseFailed("stsc first_chunk must be positive", input.offset() - 12);
            }
            if (i == 0 && firstChunk != 1) {
                throw parseFailed("stsc first entry must start at chunk 1", input.offset() - 12);
            }
            if (samplesPerChunk <= 0) {
                throw parseFailed("stsc samples_per_chunk must be positive", input.offset() - 8);
            }
            track.hasNonPrimarySampleDescriptionReference |= sampleDescriptionIndex != 1;
            if (!track.sampleToChunkEntries.isEmpty()) {
                SampleToChunkEntry previous = track.sampleToChunkEntries.get(
                        track.sampleToChunkEntries.size() - 1
                );
                if (firstChunk <= previous.firstChunk) {
                    throw parseFailed("stsc first_chunk entries must be strictly increasing", input.offset() - 12);
                }
            }
            track.sampleToChunkEntries.add(new SampleToChunkEntry(firstChunk, samplesPerChunk));
        }
    }

    /// Parses `stsz` for sample sizes.
    private void parseMoovStsz(BoxInput input, MoovState track) throws AvifDecodeException {
        input.skip(4);
        int ss = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int sc = checkedU32ToInt(input.readU32(), input.offset() - 4);
        enforceSequenceSampleCount(
                track.sampleSizes.size(),
                sc,
                "stsz",
                input.offset() - 4
        );
        reserveRetainedElements(sc, RETAINED_SAMPLE_INTEGER_SIZE, input.offset() - 4L, "stsz sample sizes");
        if (ss == 0) {
            for (int i = 0; i < sc; i++)
                track.sampleSizes.add(checkedU32ToInt(input.readU32(), input.offset() - 4));
        } else {
            for (int i = 0; i < sc; i++) track.sampleSizes.add(ss);
        }
    }

    /// Rejects a compressed sample-table run before expanding it beyond the parser resource limit.
    ///
    /// @param existingCount the number of samples already materialized for the current track
    /// @param additionalCount the number of samples declared by the current table entry
    /// @param boxType the sample-table box type used in diagnostics
    /// @param offset the byte offset of the declared count
    /// @throws AvifDecodeException if the expanded sample count exceeds the supported limit
    private static void enforceSequenceSampleCount(
            int existingCount,
            int additionalCount,
            String boxType,
            int offset
    ) throws AvifDecodeException {
        long expandedCount = (long) existingCount + additionalCount;
        if (expandedCount > MAX_SEQUENCE_SAMPLE_COUNT) {
            throw unsupported(
                    boxType + " sample count exceeds the supported limit: " + expandedCount,
                    offset
            );
        }
    }

    /// Rejects an AVIS table before retaining an excessive number of entries.
    ///
    /// @param entryCount the declared table entry count
    /// @param boxType the table box type used in diagnostics
    /// @param offset the source offset of the entry count
    /// @throws AvifDecodeException if the table exceeds the parser resource limit
    private static void enforceSequenceTableEntryCount(int entryCount, String boxType, int offset)
            throws AvifDecodeException {
        if (entryCount > MAX_SEQUENCE_SAMPLE_COUNT) {
            throw unsupported(
                    boxType + " entry count exceeds the supported limit: " + entryCount,
                    offset
            );
        }
    }

    /// Parses `stss` for sync sample indices.
    private static void parseMoovStss(BoxInput input, MoovState track) throws AvifDecodeException {
        input.skip(4);
        int n = checkedU32ToInt(input.readU32(), input.offset() - 4);
        enforceSequenceTableEntryCount(n, "stss", input.offset() - 4);
        for (int i = 0; i < n; i++) {
            int sampleNumber = checkedU32ToInt(input.readU32(), input.offset() - 4);
            if (sampleNumber <= 0) {
                throw parseFailed("stss sample number is outside the sample table", input.offset() - 4);
            }
            track.maximumSyncSampleNumber = Math.max(track.maximumSyncSampleNumber, sampleNumber);
        }
    }

    /// Parses an `ipma` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemPropertyAssociation(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version > 1 || (fullBox.flags & ~1) != 0) {
            throw unsupported("Unsupported ipma version/flags", input.offset());
        }
        boolean widePropertyIndex = (fullBox.flags & 1) != 0;
        int entryCount = checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
            int itemId = fullBox.version == 0 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
            Item item = requireItem(itemId, input.offset());
            int associationCount = input.readU8();
            for (int associationIndex = 0; associationIndex < associationCount; associationIndex++) {
                int rawAssociation = widePropertyIndex ? input.readU16() : input.readU8();
                boolean essential = (rawAssociation & (widePropertyIndex ? 0x8000 : 0x80)) != 0;
                int propertyIndex = rawAssociation & (widePropertyIndex ? 0x7FFF : 0x7F);
                if (propertyIndex == 0) {
                    if (essential) {
                        throw parseFailed("ipma property index zero cannot be essential", input.offset());
                    }
                    continue;
                }
                int zeroBasedIndex = propertyIndex - 1;
                if (zeroBasedIndex >= meta.properties.size()) {
                    throw parseFailed("ipma references a missing property", input.offset());
                }
                Property property = meta.properties.get(zeroBasedIndex);
                if (property instanceof OpaqueProperty && essential) {
                    item.hasUnsupportedEssentialProperty = true;
                }
                if (!essential && (property instanceof OperatingPoint || property instanceof LayerSelector)) {
                    throw parseFailed(
                            "Item " + itemId + " associates "
                                    + (property instanceof OperatingPoint ? "a1op" : "lsel")
                                    + " as non-essential",
                            input.offset()
                    );
                }
                String transformativePropertyType = transformativePropertyType(property);
                if (!essential && transformativePropertyType != null) {
                    continue;
                }
                reserveMetadataBytes(RETAINED_REFERENCE_SIZE, input.offset(), "item property association");
                item.properties.add(property);
            }
        }
    }

    /// Returns the box type for a parsed item property that changes the rendered image geometry.
    ///
    /// @param property the parsed item property
    /// @return the transformative property type, or `null`
    private static String transformativePropertyType(Property property) {
        if (property instanceof CleanAperture) {
            return "clap";
        }
        if (property instanceof ImageRotation) {
            return "irot";
        }
        if (property instanceof ImageMirror) {
            return "imir";
        }
        return null;
    }

    /// Parses an `iref` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemReference(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version > 1) {
            throw unsupported("Unsupported iref version: " + fullBox.version, input.offset());
        }
        while (input.hasRemaining()) {
            BoxHeader reference = input.readBoxHeader();
            BoxInput payload = input.slice(reference.payloadOffset(), reference.payloadSize());
            int fromId = fullBox.version == 0 ? payload.readU16() : checkedU32ToInt(payload.readU32(), payload.offset() - 4);
            int referenceCount = payload.readU16();
            Item fromItem = requireItem(fromId, payload.offset());
            for (int i = 0; i < referenceCount; i++) {
                int toId = fullBox.version == 0 ? payload.readU16() : checkedU32ToInt(payload.readU32(), payload.offset() - 4);
                Item toItem = requireItem(toId, payload.offset());
                if ("auxl".equals(reference.type())) {
                    fromItem.auxForId = toItem.id;
                }
                if ("prem".equals(reference.type())) {
                    fromItem.premultipliedById = toItem.id;
                }
                if ("dimg".equals(reference.type())) {
                    reserveMetadataBytes(RETAINED_REFERENCE_SIZE, payload.offset(), "dimg item reference");
                    toItem.dimgForId = fromItem.id;
                    fromItem.dimgCellIds.add(toItem.id);
                }
                if ("prog".equals(reference.type())) {
                    reserveMetadataBytes(RETAINED_REFERENCE_SIZE, payload.offset(), "prog item reference");
                    fromItem.progDeps.add(toItem.id);
                }
                if ("cdsc".equals(reference.type())) {
                    fromItem.descForId = toItem.id;
                }
            }
            input.skipBoxPayload(reference);
        }
    }

    /// Parses a `grpl` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseGroupsList(BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader group = input.readBoxHeader();
            BoxInput payload = input.slice(group.payloadOffset(), group.payloadSize());
            readFullBox(payload);
            int groupId = checkedU32ToInt(payload.readU32(), payload.offset() - 4);
            int entityCount = checkedU32ToInt(payload.readU32(), payload.offset() - 4);
            reserveMetadataBytes(RETAINED_ENTITY_GROUP_SIZE, group.offset(), "entity group structure");
            reserveRetainedElements(entityCount, Integer.BYTES, payload.offset() - 4L, "entity group identifiers");
            int[] entityIds = new int[entityCount];
            for (int i = 0; i < entityCount; i++) {
                entityIds[i] = checkedU32ToInt(payload.readU32(), payload.offset() - 4);
            }
            meta.entityGroups.add(new EntityGroup(group.type(), groupId, entityIds));
            input.skipBoxPayload(group);
        }
    }

    /// Finds an auxiliary item for the supplied master image item id.
    ///
    /// @param itemId the master image item id
    /// @param auxiliaryType the requested auxiliary image type string
    /// @return the auxiliary item, or `null`
    private Item findAuxiliaryItem(int itemId, String auxiliaryType) {
        Item masterItem = meta.item(itemId);
        for (Item item : meta.items.values()) {
            if (item.auxForId == itemId || (masterItem != null && masterItem.auxForId == item.id)) {
                AuxiliaryType aux = item.firstProperty(AuxiliaryType.class);
                if (aux != null && auxiliaryType.equals(aux.type)) {
                    return item;
                }
            }
        }
        return null;
    }

    /// Returns whether one item declares premultiplied alpha semantics.
    ///
    /// @param imageItem the color image item
    /// @return whether the color item is premultiplied by that auxiliary item
    private boolean itemAlphaPremultiplied(Item imageItem) {
        Item auxiliaryItem = findAuxiliaryItem(imageItem.id, AvifAuxiliaryImageInfo.ALPHA_TYPE);
        return auxiliaryItem != null && imageItem.premultipliedById == auxiliaryItem.id;
    }

    /// Validates `prem` references from a still-image or grid item.
    ///
    /// @param imageItem the color image or grid item
    /// @param alphaSource the resolved alpha image source, or `null`
    /// @param label the diagnostic image label
    /// @throws AvifDecodeException if the item's premultiplied-alpha reference is malformed
    private void validateItemPremultipliedAlpha(
            Item imageItem,
            AvifImageSource alphaSource,
            String label
    ) throws AvifDecodeException {
        if (imageItem.premultipliedById == 0) {
            return;
        }
        Item referencedItem = meta.item(imageItem.premultipliedById);
        AuxiliaryType auxiliaryType = referencedItem != null
                ? referencedItem.firstProperty(AuxiliaryType.class)
                : null;
        if (alphaSource == null
                || auxiliaryType == null
                || !AvifAuxiliaryImageInfo.ALPHA_TYPE.equals(auxiliaryType.type)) {
            throw parseFailed(
                    label + " prem reference does not target a usable alpha auxiliary image: "
                            + imageItem.premultipliedById
            );
        }
    }

    /// Returns auxiliary image descriptors associated with one master image item.
    ///
    /// @param itemId the master image item id
    /// @param fallbackWidth the master image width used by legacy alpha items without `ispe`
    /// @param fallbackHeight the master image height used by legacy alpha items without `ispe`
    /// @return auxiliary image descriptors
    private AvifAuxiliaryImageInfo [] auxiliaryImages(int itemId, int fallbackWidth, int fallbackHeight) {
        Item masterItem = meta.item(itemId);
        ArrayList<AvifAuxiliaryImageInfo> images = new ArrayList<>();
        for (Item item : meta.items.values()) {
            if (item.auxForId == itemId || (masterItem != null && masterItem.auxForId == item.id)) {
                AuxiliaryType aux = item.firstProperty(AuxiliaryType.class);
                if (aux != null) {
                    images.add(auxiliaryImageInfo(item, aux, fallbackWidth, fallbackHeight));
                }
            }
        }
        return images.toArray(AvifAuxiliaryImageInfo[]::new);
    }

    /// Creates a public auxiliary image descriptor from one parsed item.
    ///
    /// @param item the auxiliary item
    /// @param auxiliaryType the parsed auxiliary image type
    /// @param fallbackWidth the master image width used by legacy alpha items without `ispe`
    /// @param fallbackHeight the master image height used by legacy alpha items without `ispe`
    /// @return an auxiliary image descriptor
    private static AvifAuxiliaryImageInfo auxiliaryImageInfo(
            Item item,
            AuxiliaryType auxiliaryType,
            int fallbackWidth,
            int fallbackHeight
    ) {
        ImageSpatialExtents ispe = item.firstProperty(ImageSpatialExtents.class);
        Av1Config av1Config = item.firstProperty(Av1Config.class);
        boolean alphaWithoutIspe = ispe == null && AvifAuxiliaryImageInfo.ALPHA_TYPE.equals(auxiliaryType.type);
        int width = ispe != null ? ispe.width : (alphaWithoutIspe ? fallbackWidth : -1);
        int height = ispe != null ? ispe.height : (alphaWithoutIspe ? fallbackHeight : -1);
        AvifBitDepth bitDepth = av1Config != null ? AvifBitDepth.fromBits(av1Config.bitDepth()) : null;
        Av1ChromaFormat chromaFormat = av1Config != null ? av1Config.chromaFormat() : null;
        return new AvifAuxiliaryImageInfo(item.id, auxiliaryType.type, item.type, width, height, bitDepth, chromaFormat);
    }

    /// Returns the gain-map descriptor and decodable payloads associated with one base image item.
    ///
    /// @param baseItemId the base image item id
    /// @return the gain-map descriptor and payloads, or empty data
    /// @throws AvifDecodeException if the `tmap` metadata payload is malformed
    private GainMapPayloads gainMapPayloads(int baseItemId) throws AvifDecodeException {
        if (!tmapBrandSeen) {
            return GainMapPayloads.empty();
        }
        for (Item item : meta.items.values()) {
            if (!"tmap".equals(item.type) || item.hasUnsupportedEssentialProperty) {
                continue;
            }
            if (item.dimgCellIds.size() != 2) {
                continue;
            }
            int referencedBaseItemId = item.dimgCellIds.get(0);
            int gainMapItemId = item.dimgCellIds.get(1);
            if (referencedBaseItemId != baseItemId || gainMapItemId == baseItemId) {
                continue;
            }
            if (!isPreferredAlternativeTo(item.id, baseItemId)) {
                continue;
            }
            Item gainMapItem = meta.item(gainMapItemId);
            if (gainMapItem == null || gainMapItem.hasUnsupportedEssentialProperty) {
                return GainMapPayloads.empty();
            }
            validateOperatingPointStructure(gainMapItem, "Gain-map image");

            ToneMapMetadata toneMapMetadata = toneMapMetadata(item);
            if (toneMapMetadata == null) {
                return GainMapPayloads.empty();
            }
            ItemDimensions toneMappedDimensions = itemDimensions(item);

            if ("grid".equals(gainMapItem.type)) {
                GridPayloads grid = parseGridPayloads(gainMapItem);
                AvifGainMapInfo info = gainMapInfo(
                        item,
                        baseItemId,
                        gainMapItem,
                        toneMappedDimensions,
                        new ItemDimensions(grid.outputWidth, grid.outputHeight),
                        AvifBitDepth.fromBits(grid.representativeAv1C.bitDepth()),
                        grid.representativeAv1C.chromaFormat(),
                        toneMapMetadata
                );
                return GainMapPayloads.grid(info, grid);
            }

            ItemDimensions gainMapDimensions = itemDimensions(gainMapItem);
            Av1Config gainMapAv1Config = "av01".equals(gainMapItem.type)
                    ? itemAv1Config(gainMapItem)
                    : null;
            AvifBitDepth gainMapBitDepth = gainMapAv1Config != null
                    ? AvifBitDepth.fromBits(gainMapAv1Config.bitDepth())
                    : null;
            Av1ChromaFormat gainMapChromaFormat = gainMapAv1Config != null
                    ? gainMapAv1Config.chromaFormat()
                    : null;
            AvifGainMapInfo info = gainMapInfo(
                    item,
                    baseItemId,
                    gainMapItem,
                    toneMappedDimensions,
                    gainMapDimensions,
                    gainMapBitDepth,
                    gainMapChromaFormat,
                    toneMapMetadata
            );
            if ("av01".equals(gainMapItem.type)) {
                return GainMapPayloads.item(
                        info,
                        AvifImageSource.item(
                                itemPayload(gainMapItem),
                                operatingPoint(gainMapItem),
                                selectedSpatialLayer(gainMapItem),
                                gainMapDimensions.width,
                                gainMapDimensions.height
                        )
                );
            }
            return GainMapPayloads.descriptorOnly(info);
        }
        return GainMapPayloads.empty();
    }

    /// Creates a gain-map descriptor from parsed association metadata.
    ///
    /// @param toneMappedItem the `tmap` derived image item
    /// @param baseItemId the base image item id
    /// @param gainMapItem the gain-map image item
    /// @param toneMappedDimensions the tone-mapped item dimensions
    /// @param gainMapDimensions the gain-map item dimensions
    /// @param gainMapBitDepth the gain-map AV1 bit depth, or `null`
    /// @param gainMapChromaFormat the gain-map AV1 chroma format, or `null`
    /// @param metadata the parsed tone-map metadata
    /// @return a gain-map descriptor
    private static AvifGainMapInfo gainMapInfo(
            Item toneMappedItem,
            int baseItemId,
            Item gainMapItem,
            ItemDimensions toneMappedDimensions,
            ItemDimensions gainMapDimensions,
            AvifBitDepth gainMapBitDepth,
            Av1ChromaFormat gainMapChromaFormat,
            ToneMapMetadata metadata
    ) {
        return new AvifGainMapInfo(
                toneMappedItem.id,
                baseItemId,
                gainMapItem.id,
                toneMappedItem.type,
                gainMapItem.type
        ).withToneMappedSize(toneMappedDimensions.width, toneMappedDimensions.height)
                .withGainMapImage(
                        gainMapDimensions.width,
                        gainMapDimensions.height,
                        gainMapBitDepth,
                        gainMapChromaFormat
                )
                .withToneMappedColorInfo(toneMappedItem.firstProperty(AvifColorInfo.class))
                .withToneMappedIccProfile(iccProfile(toneMappedItem))
                .withGainMapColorInfo(gainMapItem.firstProperty(AvifColorInfo.class))
                .withMetadata(
                        metadata.version,
                        metadata.minimumVersion,
                        metadata.writerVersion,
                        metadata.metadata
                );
    }

    /// Returns the ICC profile property payload for one item.
    ///
    /// @param item the item whose properties are searched
    /// @return the ICC profile payload, or `null` when absent
    private static byte [] iccProfile(Item item) {
        IccColorProfile iccProfile = item.firstProperty(IccColorProfile.class);
        return iccProfile != null ? iccProfile.profile() : null;
    }

    /// Parses the tone-map metadata payload from one `tmap` item.
    ///
    /// @param item the `tmap` item
    /// @return parsed tone-map metadata, or `null` when the metadata version is unsupported or malformed
    /// @throws AvifDecodeException if the item payload header is malformed
    private ToneMapMetadata toneMapMetadata(Item item) throws AvifDecodeException {
        byte[] payload = mergeItemExtents(item);
        if (payload.length < 1) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "tmap item payload is too short: " + item.id,
                    null
            );
        }
        BoxInput input = new BoxInput(payload);
        int version = input.readU8();
        if (version != 0) {
            return null;
        }
        if (input.remaining() < 4) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "tmap item payload is too short: " + item.id,
                    null
            );
        }
        int minimumVersion = input.readU16();
        if (minimumVersion > SUPPORTED_GAIN_MAP_METADATA_VERSION) {
            return null;
        }
        int writerVersion = input.readU16();
        if (writerVersion < minimumVersion) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "tmap writer_version is less than minimum_version: " + writerVersion + " < " + minimumVersion,
                    null
            );
        }

        AvifGainMapMetadata metadata;
        try {
            metadata = parseGainMapMetadata(input);
        } catch (AvifDecodeException exception) {
            return null;
        }
        if (writerVersion <= SUPPORTED_GAIN_MAP_METADATA_VERSION && input.hasRemaining()) {
            return null;
        }
        return new ToneMapMetadata(version, minimumVersion, writerVersion, metadata);
    }

    /// Parses ISO 21496-1 gain-map metadata from a `tmap` payload body.
    ///
    /// @param input the input positioned after the version fields
    /// @return parsed gain-map metadata
    /// @throws AvifDecodeException if the metadata is malformed
    private static AvifGainMapMetadata parseGainMapMetadata(BoxInput input) throws AvifDecodeException {
        int packedFlags = input.readU8();
        boolean multichannel = (packedFlags & 0x80) != 0;
        boolean useBaseColorSpace = (packedFlags & 0x40) != 0;
        int channelCount = multichannel ? 3 : 1;

        AvifUnsignedFraction baseHdrHeadroom = readUnsignedFraction(input);
        AvifUnsignedFraction alternateHdrHeadroom = readUnsignedFraction(input);
        AvifSignedFraction[] gainMapMin = new AvifSignedFraction[3];
        AvifSignedFraction[] gainMapMax = new AvifSignedFraction[3];
        AvifUnsignedFraction[] gainMapGamma = new AvifUnsignedFraction[3];
        AvifSignedFraction[] baseOffset = new AvifSignedFraction[3];
        AvifSignedFraction[] alternateOffset = new AvifSignedFraction[3];

        for (int c = 0; c < channelCount; c++) {
            gainMapMin[c] = readSignedFraction(input);
            gainMapMax[c] = readSignedFraction(input);
            gainMapGamma[c] = readUnsignedFraction(input);
            baseOffset[c] = readSignedFraction(input);
            alternateOffset[c] = readSignedFraction(input);
        }
        for (int c = channelCount; c < 3; c++) {
            gainMapMin[c] = gainMapMin[0];
            gainMapMax[c] = gainMapMax[0];
            gainMapGamma[c] = gainMapGamma[0];
            baseOffset[c] = baseOffset[0];
            alternateOffset[c] = alternateOffset[0];
        }

        try {
            return new AvifGainMapMetadata(
                    multichannel,
                    useBaseColorSpace,
                    baseHdrHeadroom,
                    alternateHdrHeadroom,
                    gainMapMin,
                    gainMapMax,
                    gainMapGamma,
                    baseOffset,
                    alternateOffset
            );
        } catch (IllegalArgumentException exception) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Invalid gain-map metadata: " + exception.getMessage(),
                    null,
                    exception
            );
        }
    }

    /// Reads one unsigned gain-map metadata fraction.
    ///
    /// @param input the source input
    /// @return one unsigned fraction
    /// @throws AvifDecodeException if the input is truncated or invalid
    private static AvifUnsignedFraction readUnsignedFraction(BoxInput input) throws AvifDecodeException {
        long numerator = input.readU32();
        long denominator = input.readU32();
        try {
            return new AvifUnsignedFraction(numerator, denominator);
        } catch (IllegalArgumentException exception) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Invalid unsigned gain-map fraction: " + exception.getMessage(),
                    null,
                    exception
            );
        }
    }

    /// Reads one signed gain-map metadata fraction.
    ///
    /// @param input the source input
    /// @return one signed fraction
    /// @throws AvifDecodeException if the input is truncated or invalid
    private static AvifSignedFraction readSignedFraction(BoxInput input) throws AvifDecodeException {
        int numerator = (int) input.readU32();
        long denominator = input.readU32();
        try {
            return new AvifSignedFraction(numerator, denominator);
        } catch (IllegalArgumentException exception) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Invalid signed gain-map fraction: " + exception.getMessage(),
                    null,
                    exception
            );
        }
    }

    /// Returns image dimensions from an item's `ispe` property.
    ///
    /// @param item the image item
    /// @return known or unknown item dimensions
    private static ItemDimensions itemDimensions(Item item) {
        ImageSpatialExtents ispe = item.firstProperty(ImageSpatialExtents.class);
        return ispe != null ? new ItemDimensions(ispe.width, ispe.height) : ItemDimensions.UNKNOWN;
    }

    /// Returns the AV1 configuration for an image item or a representative grid cell.
    ///
    /// @param item the item to inspect
    /// @return the AV1 configuration, or `null`
    private Av1Config itemAv1Config(Item item) {
        Av1Config av1Config = item.firstProperty(Av1Config.class);
        if (av1Config != null) {
            return av1Config;
        }
        if (!"grid".equals(item.type)) {
            return null;
        }
        for (int cellId : item.dimgCellIds) {
            Item cell = meta.item(cellId);
            if (cell != null) {
                av1Config = cell.firstProperty(Av1Config.class);
                if (av1Config != null) {
                    return av1Config;
                }
            }
        }
        return null;
    }

    /// Returns whether one entity id is a preferred alternative to another.
    ///
    /// @param preferredId the entity id that must appear first
    /// @param alternativeId the entity id that must appear after `preferredId`
    /// @return whether `preferredId` is a preferred alternative to `alternativeId`
    private boolean isPreferredAlternativeTo(int preferredId, int alternativeId) {
        for (EntityGroup group : meta.entityGroups) {
            if (!"altr".equals(group.type)) {
                continue;
            }
            boolean preferredSeen = false;
            for (int entityId : group.entityIds) {
                if (entityId == preferredId) {
                    preferredSeen = true;
                } else if (entityId == alternativeId) {
                    return preferredSeen;
                }
            }
        }
        return false;
    }

    /// Describes item extents as one logical payload without copying their contents.
    ///
    /// @param item the item whose extents should be described
    /// @return the logical item payload
    /// @throws AvifDecodeException if the item data is malformed, truncated, or too large
    private AvifPayload itemPayload(Item item) throws AvifDecodeException {
        if (item.extents.isEmpty()) {
            throw new AvifDecodeException(AvifErrorCode.TRUNCATED_DATA, "Item has no extents: " + item.id, null);
        }
        long storageOffset = 0L;
        long storageLength = source.limit();
        if (item.idatStored) {
            if (meta.idatOffset < 0) {
                throw new AvifDecodeException(
                        AvifErrorCode.TRUNCATED_DATA,
                        "Item is stored in missing idat: " + item.id,
                        null
                );
            }
            storageOffset = meta.idatOffset;
            storageLength = meta.idatLength;
        }

        long[] offsets = new long[item.extents.size()];
        int[] lengths = new int[item.extents.size()];
        long totalLength = 0L;
        for (int i = 0; i < item.extents.size(); i++) {
            Extent extent = item.extents.get(i);
            if (extent.offset < 0
                    || extent.offset > storageLength
                    || extent.length > storageLength - extent.offset) {
                boolean exceedsProgressiveLimit = !source.isSeekable() && !item.idatStored;
                throw new AvifDecodeException(
                        exceedsProgressiveLimit ? AvifErrorCode.INPUT_TOO_LARGE : AvifErrorCode.TRUNCATED_DATA,
                        exceedsProgressiveLimit
                                ? "Item extent exceeds the configured input-size limit: " + item.id
                                : "Item extent is outside available data: " + item.id,
                        extent.offset
                );
            }
            offsets[i] = storageOffset + extent.offset;
            lengths[i] = extent.length;
            totalLength += extent.length;
            if (totalLength > Integer.MAX_VALUE - 8L) {
                throw unsupported("Item payload exceeds supported size: " + item.id, null);
            }
        }
        return AvifPayload.ofRanges(source, offsets, lengths);
    }

    /// Merges item extents into one contiguous payload.
    ///
    /// @param item the item whose extents should be merged
    /// @return one contiguous item payload
    /// @throws AvifDecodeException if the item data is malformed or truncated
    private byte[] mergeItemExtents(Item item) throws AvifDecodeException {
        AvifPayload payload = itemPayload(item);
        reserveMetadataBytes(payload.length(), -1L, "item " + item.id + " payload");
        try {
            return payload.readBytes();
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Cannot read item payload " + item.id + ": " + exception.getMessage(),
                    null,
                    exception
            );
        }
    }

    /// Returns an existing item or creates one within the retained-metadata budget.
    ///
    /// @param itemId the item identifier
    /// @param offset the associated source offset, or `-1` when unavailable
    /// @return the existing or newly created item
    /// @throws AvifDecodeException if creating the item would exceed the metadata limit
    private Item requireItem(int itemId, long offset) throws AvifDecodeException {
        Item existing = meta.item(itemId);
        if (existing != null) {
            return existing;
        }
        reserveMetadataBytes(RETAINED_ITEM_SIZE, offset, "item structure");
        Item created = new Item(itemId);
        meta.items.put(itemId, created);
        return created;
    }

    /// Reserves a repeated retained structure within the metadata budget.
    ///
    /// @param elementCount the number of retained elements
    /// @param estimatedElementSize the estimated retained size of each element
    /// @param offset the associated source offset, or `-1` when unavailable
    /// @param description the structure description used in diagnostics
    /// @throws AvifDecodeException if retaining the elements would exceed the metadata limit
    private void reserveRetainedElements(
            long elementCount,
            long estimatedElementSize,
            long offset,
            String description
    ) throws AvifDecodeException {
        if (elementCount < 0 || estimatedElementSize < 0) {
            throw new IllegalArgumentException("negative retained-metadata estimate");
        }
        long byteCount;
        try {
            byteCount = Math.multiplyExact(elementCount, estimatedElementSize);
        } catch (ArithmeticException exception) {
            throw new AvifDecodeException(
                    AvifErrorCode.INPUT_TOO_LARGE,
                    "AVIF retained metadata size overflows the supported range: " + description,
                    offset >= 0 ? offset : null,
                    exception
            );
        }
        reserveMetadataBytes(byteCount, offset, description);
    }

    /// Reserves bytes from the cumulative retained non-image metadata budget.
    ///
    /// @param byteCount the actual or estimated number of bytes about to be retained
    /// @param offset the associated source offset, or `-1` when the metadata spans extents
    /// @param description the metadata description used in diagnostics
    /// @throws AvifDecodeException if the configured or implementation metadata limit would be exceeded
    private void reserveMetadataBytes(long byteCount, long offset, String description)
            throws AvifDecodeException {
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount < 0: " + byteCount);
        }
        long effectiveLimit = metadataSizeLimit == 0
                ? MAX_RETAINED_METADATA_SIZE
                : Math.min(metadataSizeLimit, MAX_RETAINED_METADATA_SIZE);
        if (byteCount > effectiveLimit - retainedMetadataSize) {
            boolean configuredLimitApplies = metadataSizeLimit != 0 && metadataSizeLimit <= MAX_RETAINED_METADATA_SIZE;
            throw new AvifDecodeException(
                    AvifErrorCode.INPUT_TOO_LARGE,
                    "AVIF retained metadata exceeds "
                            + (configuredLimitApplies ? "configured" : "implementation")
                            + " size limit: " + description + " (" + effectiveLimit + " bytes)",
                    offset >= 0 ? offset : null
            );
        }
        retainedMetadataSize += byteCount;
    }

    /// Reads one full-box header.
    ///
    /// @param input the box payload input
    /// @return one full-box header
    /// @throws AvifDecodeException if the box is truncated
    private static FullBox readFullBox(BoxInput input) throws AvifDecodeException {
        int version = input.readU8();
        int flags = input.readU24();
        return new FullBox(version, flags);
    }

    /// Ensures one unique child box is not repeated.
    ///
    /// @param seen the seen box types
    /// @param type the current box type
    /// @param offset the current box offset
    /// @throws AvifDecodeException if the box repeats
    private static void unique(Set<String> seen, String type, int offset) throws AvifDecodeException {
        if (!seen.add(type)) {
            throw parseFailed("duplicate unique box: " + type, offset);
        }
    }

    /// Reads a BMFF variable-width unsigned integer.
    ///
    /// @param input the input to read from
    /// @param byteCount the encoded byte count
    /// @return the parsed value
    /// @throws AvifDecodeException if the size is unsupported or the input is truncated
    private static long readUx(BoxInput input, int byteCount) throws AvifDecodeException {
        return switch (byteCount) {
            case 0 -> 0L;
            case 4 -> input.readU32();
            case 8 -> input.readU64();
            default -> throw parseFailed("unsupported variable integer byte count: " + byteCount, input.offset());
        };
    }

    /// Reads a 32-bit track duration while preserving the indefinite-duration marker.
    ///
    /// @param input the input to read from
    /// @return the parsed duration, or `INDEFINITE_TRACK_DURATION`
    /// @throws AvifDecodeException if the input is truncated
    private static long readTrackDuration32(BoxInput input) throws AvifDecodeException {
        long value = input.readU32();
        return value == 0xFFFF_FFFFL ? INDEFINITE_TRACK_DURATION : value;
    }

    /// Reads a 64-bit track duration while preserving the indefinite-duration marker.
    ///
    /// @param input the input to read from
    /// @return the parsed duration, or `INDEFINITE_TRACK_DURATION`
    /// @throws AvifDecodeException if the input is truncated or the value exceeds the supported range
    private static long readTrackDuration64(BoxInput input) throws AvifDecodeException {
        long high = input.readU32();
        long low = input.readU32();
        if (high == 0xFFFF_FFFFL && low == 0xFFFF_FFFFL) {
            return INDEFINITE_TRACK_DURATION;
        }
        if ((high & 0x8000_0000L) != 0) {
            throw parseFailed("64-bit track duration exceeds supported range", input.offset() - 8);
        }
        return (high << 32) | low;
    }

    /// Validates an `iloc` variable integer field size.
    ///
    /// @param byteCount the byte count to validate
    /// @param offset the associated byte offset
    /// @throws AvifDecodeException if the byte count is invalid
    private static void validateIlocFieldSize(int byteCount, int offset) throws AvifDecodeException {
        if (byteCount != 0 && byteCount != 4 && byteCount != 8) {
            throw parseFailed("invalid iloc field size: " + byteCount, offset);
        }
    }

    /// Adds two unsigned offsets with overflow checking.
    ///
    /// @param left the left value
    /// @param right the right value
    /// @param offset the associated byte offset
    /// @return the checked sum
    /// @throws AvifDecodeException if the sum overflows
    private static long checkedAdd(long left, long right, int offset) throws AvifDecodeException {
        if (right > Long.MAX_VALUE - left) {
            throw parseFailed("integer overflow while merging iloc offsets", offset);
        }
        return left + right;
    }

    /// Converts a parsed unsigned 32-bit value to `int`.
    ///
    /// @param value the parsed unsigned value
    /// @param offset the associated byte offset
    /// @return the converted value
    /// @throws AvifDecodeException if the value exceeds `Integer.MAX_VALUE`
    private static int checkedU32ToInt(long value, int offset) throws AvifDecodeException {
        if (value > Integer.MAX_VALUE) {
            throw parseFailed("32-bit value exceeds supported range: " + value, offset);
        }
        return (int) value;
    }

    /// Converts a parsed unsigned 64-bit value to `int`.
    ///
    /// @param value the parsed unsigned value
    /// @param offset the associated byte offset
    /// @return the converted value
    /// @throws AvifDecodeException if the value exceeds `Integer.MAX_VALUE`
    private static int checkedU64ToInt(long value, int offset) throws AvifDecodeException {
        if (value > Integer.MAX_VALUE) {
            throw parseFailed("64-bit value exceeds supported range: " + value, offset);
        }
        return (int) value;
    }

    /// Creates a BMFF parse failure.
    ///
    /// @param message the failure message
    /// @return a BMFF parse failure without a specific byte offset
    private static AvifDecodeException parseFailed(String message) {
        return new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, message, null);
    }

    /// Creates a BMFF parse failure at a specific input offset.
    ///
    /// @param message the failure message
    /// @param offset the associated byte offset
    /// @return a BMFF parse failure
    private static AvifDecodeException parseFailed(String message, long offset) {
        return new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, message, offset);
    }

    /// Creates an unsupported-feature failure.
    ///
    /// @param message the failure message
    /// @param offset the associated byte offset, or `null`
    /// @return an unsupported-feature failure
    private static AvifDecodeException unsupported(String message, Integer offset) {
        return new AvifDecodeException(
                AvifErrorCode.UNSUPPORTED_FEATURE,
                message,
                offset == null ? null : (long) offset
        );
    }

    /// Parsed item dimensions, or an unknown marker.
    ///
    /// @param width the item width in pixels, or -1 when unknown
    /// @param height the item height in pixels, or -1 when unknown
    
    private record ItemDimensions(int width, int height) {
        /// Unknown item dimensions.
        private static final ItemDimensions UNKNOWN = new ItemDimensions(-1, -1);
    }

    /// Extracted AVIS sample payloads for one track.
    ///
    /// @param payloads the AV1 OBU payloads for each sample in order
    /// @param frameDeltas the frame duration deltas in media timescale units
    /// @param sampleCount the number of samples
    
    private record SequencePayloads(
            AvifPayload [] payloads,
            int [] frameDeltas,
            int sampleCount
    ) {
        /// Creates extracted AVIS sample payloads for one track.
        private SequencePayloads {
            Objects.requireNonNull(payloads, "payloads");
            Objects.requireNonNull(frameDeltas, "frameDeltas");
        }
    }

    /// One `stsc` sample-to-chunk entry.
    ///
    /// @param firstChunk the one-based first chunk using this layout
    /// @param samplesPerChunk the number of samples stored in each covered chunk
    
    private record SampleToChunkEntry(int firstChunk, int samplesPerChunk) {
    }

    /// Parsed `tmap` metadata.
    ///
    /// @param version the metadata version field
    /// @param minimumVersion the minimum supported metadata version field
    /// @param writerVersion the writer metadata version field
    /// @param metadata the parsed gain-map metadata
    
    private record ToneMapMetadata(
            int version,
            int minimumVersion,
            int writerVersion,
            AvifGainMapMetadata metadata
    ) {
        /// Creates parsed `tmap` metadata.
        private ToneMapMetadata {
            Objects.requireNonNull(metadata, "metadata");
        }
    }

    /// Parsed BMFF entity group.
    ///
    /// @param type the grouping type
    /// @param groupId the group id
    /// @param entityIds the entity ids in group order
    
    private record EntityGroup(String type, int groupId, int [] entityIds) {
        /// Creates a parsed BMFF entity group.
        private EntityGroup {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(entityIds, "entityIds");
        }
    }

    /// Mutable parser state for one root `meta` box.
    
    private static final class MetaState {
        /// The primary item id from `pitm`.
        private int primaryItemId;
        /// Parsed items keyed by item id.
        private final Map<Integer, Item> items = new HashMap<>();
        /// Parsed item properties from `ipco`.
        private final List<Property> properties = new ArrayList<>();
        /// Parsed entity groups from `grpl`.
        private final List<EntityGroup> entityGroups = new ArrayList<>();
        /// Auxiliary image type strings parsed from AVIS auxiliary tracks.
        private final List<String> moovAuxiliaryTypes = new ArrayList<>();
        /// Parsed AVIS auxiliary track candidates.
        private final List<MoovState> moovAuxiliaryCandidates = new ArrayList<>();
        /// The parsed AVIS alpha auxiliary track state, or `null`.
        private MoovState moovAlphaState;
        /// The parsed AVIS depth auxiliary track state, or `null`.
        private MoovState moovDepthState;
        /// The absolute offset of the optional `idat` payload, or `-1` when absent.
        private long idatOffset = -1L;
        /// The optional `idat` payload length.
        private int idatLength;
        /// The parsed AVIS moov state.
        private MoovState moovState = new MoovState();

        /// Returns one item by id.
        ///
        /// @param itemId the item id
        /// @return the item, or `null`
        private Item item(int itemId) {
            return items.get(itemId);
        }
    }

    /// Mutable parser state for AVIS moov parsing.
    
    private static final class MoovState {
        /// The parsed track ID from `tkhd`, or zero when absent.
        private int trackId;
        /// The parsed image sequence width.
        private int width;
        /// The parsed image sequence height.
        private int height;
        /// The parsed image sequence bit depth.
        private int bitDepth;
        /// The parsed image sequence chroma format, or `null`.
        private Av1ChromaFormat chromaFormat;
        /// The parsed image sequence color information, or `null`.
        private AvifColorInfo colr;
        /// The parsed image sequence ICC profile payload, or `null`.
        private byte [] iccProfile;
        /// The parsed media handler type, or `null` when the track has no `hdlr` box.
        private String mediaHandlerType;
        /// The parsed image sequence media timescale.
        private int mediaTimescale;
        /// The parsed image sequence media duration.
        private long mediaDuration;
        /// The parsed track duration from `tkhd`.
        private long trackDuration;
        /// Whether an edit list was parsed for this track.
        private boolean editListSeen;
        /// Whether the edit list signals repetition semantics.
        private boolean editListRepeating;
        /// The edit-list segment duration for repeating tracks.
        private long editListSegmentDuration;
        /// The parsed AV1 sequence header OBU, or `null` before an AV1 track is found.
        private byte [] seqHeaderObu;
        /// The parsed AV1 sample-entry configuration, or `null` when absent.
        private Av1Config av1Config;
        /// The number of sample descriptions declared by `stsd`.
        private int sampleDescriptionCount;
        /// Whether `stsd` contains at least one AV1 sample description.
        private boolean containsAv1SampleDescription;
        /// Whether `stsc` references a sample description other than the first.
        private boolean hasNonPrimarySampleDescriptionReference;
        /// The greatest one-based sync sample number declared by `stss`.
        private int maximumSyncSampleNumber;
        /// The AVIS auxiliary track type, or `null` for the selected color track.
        private String auxiliaryType;
        /// The `tref/auxl` color track IDs referenced by this auxiliary track.
        private final List<Integer> auxiliaryForTrackIds = new ArrayList<>();
        /// The `tref/prem` alpha track IDs referenced by this color track.
        private final List<Integer> premultipliedByTrackIds = new ArrayList<>();
        /// The parsed sample timing deltas.
        private final List<Integer> sampleDeltas = new ArrayList<>();
        /// The parsed chunk offsets.
        private final List<Integer> chunkOffsets = new ArrayList<>();
        /// The parsed sample-to-chunk entries.
        private final List<SampleToChunkEntry> sampleToChunkEntries = new ArrayList<>();
        /// The parsed sample sizes.
        private final List<Integer> sampleSizes = new ArrayList<>();
    }

    /// Parsed grid payloads and geometry.
    ///
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the reconstructed output width
    /// @param outputHeight the reconstructed output height
    /// @param representativeAv1C the representative AV1 configuration from the first grid cell
    /// @param source the normalized grid image source
    
    private record GridPayloads(
            int rows,
            int columns,
            int outputWidth,
            int outputHeight,
            Av1Config representativeAv1C,
            AvifImageSource source
    ) {
        /// Creates parsed grid payloads and geometry.
        private GridPayloads {
            Objects.requireNonNull(representativeAv1C, "representativeAv1C");
            Objects.requireNonNull(source, "source");
        }
    }

    /// Parsed gain-map descriptor and decodable image payloads.
    ///
    /// @param info the public gain-map descriptor, or `null`
    /// @param source the normalized gain-map image source, or `null` when unavailable
    
    private record GainMapPayloads(AvifGainMapInfo info, AvifImageSource source) {
        /// Creates empty gain-map payloads.
        ///
        /// @return empty gain-map payloads
        private static GainMapPayloads empty() {
            return new GainMapPayloads(null, null);
        }

        /// Creates descriptor-only gain-map payloads.
        ///
        /// @param info the public gain-map descriptor
        /// @return descriptor-only gain-map payloads
        private static GainMapPayloads descriptorOnly(AvifGainMapInfo info) {
            return new GainMapPayloads(Objects.requireNonNull(info, "info"), null);
        }

        /// Creates gain-map payloads from one standalone item.
        ///
        /// @param info the public gain-map descriptor
        /// @param source the standalone gain-map image source
        /// @return gain-map payloads for the item
        private static GainMapPayloads item(AvifGainMapInfo info, AvifImageSource source) {
            return new GainMapPayloads(Objects.requireNonNull(info, "info"), Objects.requireNonNull(source, "source"));
        }

        /// Creates gain-map payloads from grid data.
        ///
        /// @param info the public gain-map descriptor
        /// @param grid the parsed gain-map grid
        /// @return gain-map payloads for the grid
        private static GainMapPayloads grid(AvifGainMapInfo info, GridPayloads grid) {
            return new GainMapPayloads(
                    Objects.requireNonNull(info, "info"),
                    Objects.requireNonNull(grid, "grid").source
            );
        }
    }

    /// Parsed image metadata payloads.
    ///
    /// @param iccProfile the ICC profile payload, or `null`
    /// @param exif the Exif metadata payload, or `null`
    /// @param xmp the XMP metadata payload, or `null`
    
    private record MetadataPayloads(
            byte [] iccProfile,
            byte [] exif,
            byte [] xmp
    ) {
    }

    /// Mutable parser state for one item.
    
    private static final class Item {
        /// The item id.
        private final int id;
        /// The item type.
        private String type = "";
        /// The item name from `infe`.
        private String name = "";
        /// The MIME content type for `mime` items, or an empty string.
        private String contentType = "";
        /// Whether the item extents are stored in `idat`.
        private boolean idatStored;
        /// The item extents.
        private final List<Extent> extents = new ArrayList<>();
        /// The associated item properties.
        private final List<Property> properties = new ArrayList<>();
        /// Whether the item has an unsupported essential property.
        private boolean hasUnsupportedEssentialProperty;
        /// The master image item id for auxiliary items.
        private int auxForId;
        /// The auxiliary item id that premultiplies this color item, or 0.
        private int premultipliedById;
        /// The image item id described by this metadata item, or 0.
        private int descForId;
        /// The grid item id for dimg cell items, or 0.
        private int dimgForId;
        /// The dimg cell item ids for a grid item, in row-major order.
        private final List<Integer> dimgCellIds = new ArrayList<>();
        /// The progressive dependency item ids from `prog` references.
        private final List<Integer> progDeps = new ArrayList<>();

        /// Creates item parser state.
        ///
        /// @param id the item id
        private Item(int id) {
            this.id = id;
        }

        /// Finds the first associated property assignable to one type.
        ///
        /// @param type the property class
        /// @param <T> the property type
        /// @return the first matching property, or `null`
        private <T> T firstProperty(Class<T> type) {
            for (Property property : properties) {
                if (type.isInstance(property)) {
                    return type.cast(property);
                }
                if (type == AvifColorInfo.class && property instanceof ColorProperty colorProperty) {
                    return type.cast(colorProperty.colorInfo);
                }
            }
            return null;
        }
    }

    /// One item extent.
    ///
    /// @param offset the extent byte offset
    /// @param length the extent byte length
    
    private record Extent(long offset, int length) {
    }

    /// Display dimensions after applying AVIF item transforms.
    ///
    /// @param width the transformed display width
    /// @param height the transformed display height
    
    private record DisplaySize(int width, int height) {
    }

    /// One full-box header.
    ///
    /// @param version the full-box version
    /// @param flags the full-box flags
    
    private record FullBox(int version, int flags) {
    }

    /// Marker interface for parsed item properties.
    
    private sealed interface Property permits ImageSpatialExtents, Av1Config, ColorProperty, IccColorProfile, AuxiliaryType, OpaqueProperty, PixelInformation, PixelAspectRatio, CleanAperture, ImageRotation, ImageMirror, OperatingPoint, LayerSelector {
    }

    /// Parsed `ispe` item property.
    ///
    /// @param width the image width
    /// @param height the image height
    
    private record ImageSpatialExtents(int width, int height) implements Property {
    }

    /// Parsed `av1C` item property.
    ///
    /// @param seqProfile the AV1 sequence profile
    /// @param seqLevelIdx0 the AV1 sequence level index
    /// @param highBitDepth whether high bit depth is signaled
    /// @param twelveBit whether twelve-bit depth is signaled
    /// @param monochrome whether monochrome output is signaled
    /// @param chromaSubsamplingX whether horizontal chroma subsampling is signaled
    /// @param chromaSubsamplingY whether vertical chroma subsampling is signaled
    /// @param chromaSamplePosition the chroma sample position
    
    private record Av1Config(
            int seqProfile,
            int seqLevelIdx0,
            boolean highBitDepth,
            boolean twelveBit,
            boolean monochrome,
            boolean chromaSubsamplingX,
            boolean chromaSubsamplingY,
            int chromaSamplePosition
    ) implements Property {

        /// Returns the decoded bit depth.
        ///
        /// @return the decoded bit depth
        private int bitDepth() {
            return highBitDepth ? (twelveBit ? 12 : 10) : 8;
        }

        /// Returns the AV1 chroma sampling layout.
        ///
        /// @return the AV1 chroma sampling layout
        private Av1ChromaFormat chromaFormat() {
            if (monochrome) {
                return Av1ChromaFormat.MONOCHROME;
            }
            if (chromaSubsamplingX && chromaSubsamplingY) {
                return Av1ChromaFormat.YUV420;
            }
            if (chromaSubsamplingX) {
                return Av1ChromaFormat.YUV422;
            }
            return Av1ChromaFormat.YUV444;
        }

        /// Constructs a reduced-still-picture AV1 SEQUENCE_HEADER OBU from this configuration.
        ///
        /// @param width the image width
        /// @param height the image height
        /// @return the SEQUENCE_HEADER OBU bytes ready for decoding
        private byte [] seqHeaderObu(int width, int height) {
            byte[] payload = reducedStillSeqHdrPayload(width, height);
            ByteArrayOutputStream obu = new ByteArrayOutputStream();
            obu.write((1 << 3) | (1 << 1));
            writeLeb128(obu, payload.length);
            obu.writeBytes(payload);
            return obu.toByteArray();
        }

        /// Builds the payload portion of a reduced still-picture SEQUENCE_HEADER.
        private byte[] reducedStillSeqHdrPayload(int frameWidth, int frameHeight) {
            int mfw = frameWidth - 1;
            int mfh = frameHeight - 1;
            int fb = 32 - Integer.numberOfLeadingZeros(mfw);
            if (fb < 1) fb = 1;
            int fh = 32 - Integer.numberOfLeadingZeros(mfh);
            if (fh < 1) fh = 1;
            SeqBitWriter w = new SeqBitWriter();
            w.bits(0, 3);
            w.flag(true);
            w.flag(true);
            w.bits(0, 3);
            w.bits(0, 5);
            w.bits(fb, 4);
            w.bits(fh, 4);
            w.bits(mfw, fb + 1);
            w.bits(mfh, fh + 1);
            w.flag(false);
            w.flag(true);
            w.flag(true);
            w.flag(false);
            w.flag(true);
            w.flag(true);
            w.flag(false);
            if (monochrome) {
                w.flag(true);
                w.flag(false);
                w.flag(true);
                w.flag(true);
            } else {
                w.flag(false);
                w.flag(false);
                w.flag(false);
                w.bits(1, 2);
                w.flag(true);
                w.flag(true);
            }
            w.flag(false);
            w.trail();
            return w.toBytes();
        }
    }

    /// Writes a LEB128 unsigned value to the stream.
    private static void writeLeb128(ByteArrayOutputStream out, int value) {
        int v = value;
        do {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) b |= 0x80;
            out.write(b);
        } while (v != 0);
    }

    /// Reads one null-terminated UTF-8 string.
    ///
    /// @param input the source input
    /// @return the decoded string without the terminator
    /// @throws AvifDecodeException if the string is not terminated within this input
    private static String readNullTerminatedString(BoxInput input) throws AvifDecodeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (input.hasRemaining()) {
            int value = input.readU8();
            if (value == 0) {
                return output.toString(StandardCharsets.UTF_8);
            }
            output.write(value);
        }
        throw parseFailed("unterminated BMFF string", input.offset());
    }

    /// MSB-first bit writer for sequence-header construction.
    
    private static final class SeqBitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int cur;
        private int cnt;

        private void flag(boolean v) { bit(v ? 1 : 0); }
        private void bits(long v, int w) {
            for (int b = w - 1; b >= 0; b--) bit((int) ((v >>> b) & 1L));
        }
        private void trail() { bit(1); while (cnt != 0) bit(0); }

        private void bit(int b) {
            cur = (cur << 1) | (b & 1);
            if (++cnt == 8) { out.write(cur); cur = 0; cnt = 0; }
        }
        private byte[] toBytes() {
            if (cnt > 0) throw new IllegalStateException("not byte aligned");
            return out.toByteArray();
        }
    }

    /// Parsed `colr` item property carrying `nclx` color information.
    ///
    /// @param colorInfo the parsed color information
    
    private record ColorProperty(AvifColorInfo colorInfo) implements Property {
        /// Creates a color property.
        private ColorProperty {
            Objects.requireNonNull(colorInfo, "colorInfo");
        }
    }

    /// Parsed `colr` item property carrying an ICC profile.
    ///
    /// @param profile the ICC profile payload
    
    private record IccColorProfile(byte [] profile) implements Property {
        /// Creates an ICC color profile property.
        private IccColorProfile {
            Objects.requireNonNull(profile, "profile");
        }
    }

    /// Parsed `auxC` item property.
    ///
    /// @param type the auxiliary image type string
    
    private record AuxiliaryType(String type) implements Property {
        /// Creates an auxiliary type property.
        private AuxiliaryType {
            Objects.requireNonNull(type, "type");
        }
    }

    /// Opaque or currently unsupported item property.
    ///
    /// @param type the property type
    /// @param userType the UUID property user type, or `null`
    /// @param payload the property payload after any UUID user type
    
    private record OpaqueProperty(
            String type,
            byte [] userType,
            byte [] payload
    ) implements Property {
        /// Creates an opaque property.
        private OpaqueProperty {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(payload, "payload");
        }

        /// Creates a public opaque item property descriptor.
        ///
        /// @return the public opaque item property descriptor
        private AvifOpaqueItemProperty toOpaqueItemProperty() {
            return new AvifOpaqueItemProperty(type, userType, payload);
        }
    }

    /// Parsed `pixi` item property.
    ///
    /// @param bitsPerChannel the bits-per-channel array
    
    private record PixelInformation(int [] bitsPerChannel) implements Property {
        /// Creates a pixel information property with an independent channel-depth array.
        private PixelInformation {
            bitsPerChannel = Objects.requireNonNull(bitsPerChannel, "bitsPerChannel").clone();
        }
    }

    /// Parsed `pasp` item property.
    ///
    /// @param hSpacing the horizontal relative spacing
    /// @param vSpacing the vertical relative spacing
    
    private record PixelAspectRatio(int hSpacing, int vSpacing) implements Property {
    }

    /// Parsed `clap` item property.
    ///
    /// @param cleanApertureWidthN the clean aperture width numerator
    /// @param cleanApertureWidthD the clean aperture width denominator
    /// @param cleanApertureHeightN the clean aperture height numerator
    /// @param cleanApertureHeightD the clean aperture height denominator
    /// @param horizOffN the horizontal offset numerator
    /// @param horizOffD the horizontal offset denominator
    /// @param vertOffN the vertical offset numerator
    /// @param vertOffD the vertical offset denominator
    
    private record CleanAperture(
            int cleanApertureWidthN,
            int cleanApertureWidthD,
            int cleanApertureHeightN,
            int cleanApertureHeightD,
            int horizOffN,
            int horizOffD,
            int vertOffN,
            int vertOffD
    ) implements Property {
    }

    /// Parsed `irot` item property.
    ///
    /// @param rotation the rotation angle code
    
    private record ImageRotation(int rotation) implements Property {
    }

    /// Parsed `imir` item property.
    ///
    /// @param axis the mirror axis
    
    private record ImageMirror(int axis) implements Property {
    }

    /// Parsed `a1op` item property.
    ///
    /// @param operatingPoint the operating point index
    
    private record OperatingPoint(int operatingPoint) implements Property {
    }

    /// Parsed `lsel` item property.
    ///
    /// @param layerId the selected AV1 spatial-layer identifier, or `65535` for progressive selection
    
    private record LayerSelector(int layerId) implements Property {
    }
}
