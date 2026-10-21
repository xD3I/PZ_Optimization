// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.parse;

import pzopt.vendor.avif.av1.Av1DecodeErrorCode;
import pzopt.vendor.avif.av1.Av1DecodeException;
import pzopt.vendor.avif.av1.Av1DecodeStage;
import pzopt.vendor.avif.av1.Av1FrameType;
import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.bitstream.BitReader;
import pzopt.vendor.avif.internal.av1.bitstream.ObuPacket;
import pzopt.vendor.avif.internal.av1.bitstream.ObuType;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.SequenceHeader;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/// Parser for AV1 frame headers carried by standalone or combined frame OBUs.

public final class FrameHeaderParser {
    /// Prevents instantiation of this stateless parser.
    private FrameHeaderParser() {
    }

    /// The AV1 `primary_ref_none` sentinel value.
    private static final int PRIMARY_REF_NONE = 7;
    /// The AV1 maximum tile column count.
    private static final int MAX_TILE_COLUMNS = 64;
    /// The AV1 maximum tile row count.
    private static final int MAX_TILE_ROWS = 64;
    /// The AV1 segment count.
    private static final int MAX_SEGMENTS = 8;
    /// The AV1 total reference-frame slot count.
    private static final int TOTAL_REFERENCE_FRAMES = 8;
    /// The AV1 reference-frame count signaled per frame.
    private static final int REFERENCES_PER_FRAME = 7;
    /// The subexponential group-width parameter used by AV1 global motion.
    private static final int GLOBAL_MOTION_SUBEXP_K = 3;
    /// The fixed-point precision of AV1 global-motion matrix entries.
    private static final int GLOBAL_MOTION_MATRIX_PRECISION = 16;
    /// The signed alpha parameter magnitude bit count used by AV1 global motion.
    private static final int GLOBAL_MOTION_ALPHA_BITS = 12;
    /// The AV1 maximum CDEF strength count.
    private static final int MAX_CDEF_STRENGTHS = 8;
    /// The AV1 maximum luma film grain point count.
    private static final int MAX_FILM_GRAIN_Y_POINTS = 14;
    /// The AV1 maximum chroma film grain point count.
    private static final int MAX_FILM_GRAIN_UV_POINTS = 10;
    /// The default loop filter reference deltas.
    private static final int [] DEFAULT_REFERENCE_DELTAS = new int[]{1, 0, 0, 0, -1, 0, -1, -1};
    /// The default loop filter mode deltas.
    private static final int [] DEFAULT_MODE_DELTAS = new int[]{0, 0};

    /// Parses a standalone AV1 frame header OBU.
    ///
    /// @param obu the standalone frame header OBU
    /// @param sequenceHeader the active sequence header
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @return the parsed frame header
    /// @throws IOException if the OBU is truncated, unreadable, or invalid
    public static FrameHeader parse(
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            boolean strictStdCompliance
    ) throws IOException {
        return parse(obu, sequenceHeader, strictStdCompliance, new FrameHeader[TOTAL_REFERENCE_FRAMES]);
    }

    /// Parses a standalone AV1 frame header OBU with access to previously refreshed reference headers.
    ///
    /// @param obu the standalone frame header OBU
    /// @param sequenceHeader the active sequence header
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @return the parsed frame header
    /// @throws IOException if the OBU is truncated, unreadable, or invalid
    public static FrameHeader parse(
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            boolean strictStdCompliance,
            FrameHeader[] referenceFrameHeaders
    ) throws IOException {
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        validateReferenceFrameHeaders(referenceFrameHeaders);
        if (obu.header().type() != ObuType.FRAME_HEADER) {
            throw new IllegalArgumentException("OBU type is not a standalone frame header: " + obu.header().type());
        }

        BitReader reader = new BitReader(obu.payload());
        FrameHeader header = parseFramePayload(reader, obu, sequenceHeader, strictStdCompliance, referenceFrameHeaders);
        try {
            checkTrailingBits(reader, strictStdCompliance);
            return header;
        } catch (EOFException ex) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.UNEXPECTED_EOF,
                    Av1DecodeStage.FRAME_HEADER_PARSE,
                    "Unexpected end of frame header payload",
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        } catch (Av1DecodeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.INVALID_BITSTREAM,
                    Av1DecodeStage.FRAME_HEADER_PARSE,
                    ex.getMessage(),
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        }
    }

    /// Parses a frame header payload from a `FRAME_HEADER` or `FRAME` OBU.
    ///
    /// @param reader the payload bit reader positioned at the frame header
    /// @param obu the source OBU packet
    /// @param sequenceHeader the active sequence header
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @return the parsed frame header
    /// @throws IOException if the payload is truncated, unreadable, or invalid
    public static FrameHeader parseFramePayload(
            BitReader reader,
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            boolean strictStdCompliance
    ) throws IOException {
        return parseFramePayload(reader, obu, sequenceHeader, strictStdCompliance, new FrameHeader[TOTAL_REFERENCE_FRAMES]);
    }

    /// Parses a frame header payload from a `FRAME_HEADER` or `FRAME` OBU using refreshed reference headers.
    ///
    /// @param reader the payload bit reader positioned at the frame header
    /// @param obu the source OBU packet
    /// @param sequenceHeader the active sequence header
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @return the parsed frame header
    /// @throws IOException if the payload is truncated, unreadable, or invalid
    public static FrameHeader parseFramePayload(
            BitReader reader,
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            boolean strictStdCompliance,
            FrameHeader[] referenceFrameHeaders
    ) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        validateReferenceFrameHeaders(referenceFrameHeaders);
        ObuType type = obu.header().type();
        if (type != ObuType.FRAME_HEADER && type != ObuType.FRAME) {
            throw new IllegalArgumentException("OBU type does not carry a frame header payload: " + type);
        }

        try {
            return parse(reader, obu, sequenceHeader, strictStdCompliance, referenceFrameHeaders);
        } catch (EOFException ex) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.UNEXPECTED_EOF,
                    Av1DecodeStage.FRAME_HEADER_PARSE,
                    "Unexpected end of frame header payload",
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        } catch (Av1DecodeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.INVALID_BITSTREAM,
                    Av1DecodeStage.FRAME_HEADER_PARSE,
                    ex.getMessage(),
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        }
    }

    /// Parses the frame header payload.
    ///
    /// @param reader the payload bit reader
    /// @param obu the source OBU packet
    /// @param sequenceHeader the active sequence header
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @return the parsed frame header
    /// @throws IOException if the payload is truncated or invalid
    private static FrameHeader parse(
            BitReader reader,
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            boolean strictStdCompliance,
            FrameHeader[] referenceFrameHeaders
    ) throws IOException {
        int temporalId = obu.header().temporalId();
        int spatialId = obu.header().spatialId();

        boolean showExistingFrame = false;
        int existingFrameIndex = 0;
        long frameId = 0;
        long framePresentationDelay = 0;
        Av1FrameType frameType;
        boolean showFrame;
        boolean showableFrame;
        boolean errorResilientMode;

        if (!sequenceHeader.reducedStillPictureHeader()) {
            showExistingFrame = reader.readFlag();
        }
        if (showExistingFrame) {
            if (strictStdCompliance && obu.header().type() == ObuType.FRAME) {
                fail("Combined frame OBUs must not signal show_existing_frame");
            }
            existingFrameIndex = readInt(reader, 3);
            FrameHeader existingFrameHeader = referenceFrameHeaders[existingFrameIndex];
            if (sequenceHeader.timingInfo().decoderModelInfoPresent()
                    && !sequenceHeader.timingInfo().equalPictureInterval()) {
                framePresentationDelay = readLong(reader, sequenceHeader.timingInfo().framePresentationDelayLength());
            }
            if (sequenceHeader.frameIdNumbersPresent()) {
                frameId = readLong(reader, sequenceHeader.frameIdBits());
                if (existingFrameHeader != null && existingFrameHeader.frameId() != frameId) {
                    fail("show_existing_frame frame id does not match the referenced slot");
                }
            }

            Av1FrameType existingFrameType = existingFrameHeader != null
                    ? existingFrameHeader.frameType()
                    : Av1FrameType.INTER;
            int existingRefreshFrameFlags = existingFrameType == Av1FrameType.KEY ? 0xFF : 0;
            FrameHeader.FilmGrainParams existingFilmGrain = existingFrameHeader != null
                    ? existingFrameHeader.filmGrain()
                    : FrameHeader.FilmGrainParams.disabled();

            return new FrameHeader(
                    temporalId,
                    spatialId,
                    true,
                    existingFrameIndex,
                    frameId,
                    framePresentationDelay,
                    existingFrameType,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    PRIMARY_REF_NONE,
                    0,
                    existingRefreshFrameFlags,
                    new FrameHeader.FrameSize(0, 0, 0, 0, 0),
                    new FrameHeader.SuperResolutionInfo(false, 8),
                    false,
                    false,
                    new FrameHeader.TilingInfo(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, new int[0], new int[0], 0),
                    new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                    new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[MAX_SEGMENTS], new int[MAX_SEGMENTS]),
                    new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                    false,
                    new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, DEFAULT_REFERENCE_DELTAS, DEFAULT_MODE_DELTAS),
                    new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                    new FrameHeader.RestorationInfo(
                            new FrameHeader.RestorationType[]{
                                    FrameHeader.RestorationType.NONE,
                                    FrameHeader.RestorationType.NONE,
                                    FrameHeader.RestorationType.NONE
                            },
                            0,
                            0
                    ),
                    FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                    false,
                    existingFilmGrain
            );
        }

        if (sequenceHeader.reducedStillPictureHeader()) {
            frameType = Av1FrameType.KEY;
            showFrame = true;
        } else {
            frameType = decodeFrameType(readInt(reader, 2));
            showFrame = reader.readFlag();
        }

        if (showFrame) {
            if (sequenceHeader.timingInfo().decoderModelInfoPresent()
                    && !sequenceHeader.timingInfo().equalPictureInterval()) {
                framePresentationDelay = readLong(reader, sequenceHeader.timingInfo().framePresentationDelayLength());
            }
            showableFrame = frameType != Av1FrameType.KEY;
        } else {
            showableFrame = reader.readFlag();
        }

        errorResilientMode = (frameType == Av1FrameType.KEY && showFrame)
                || frameType == Av1FrameType.SWITCH
                || sequenceHeader.reducedStillPictureHeader()
                || reader.readFlag();

        boolean disableCdfUpdate = reader.readFlag();
        boolean allowScreenContentTools = readFrameScreenContentTools(reader, sequenceHeader);
        boolean forceIntegerMotionVectors = false;
        if (allowScreenContentTools) {
            forceIntegerMotionVectors = readFrameForceIntegerMotionVectors(reader, sequenceHeader);
        }

        if (frameType == Av1FrameType.KEY || frameType == Av1FrameType.INTRA) {
            forceIntegerMotionVectors = true;
        }

        if (sequenceHeader.frameIdNumbersPresent()) {
            frameId = readLong(reader, sequenceHeader.frameIdBits());
        }

        boolean frameSizeOverride = false;
        if (!sequenceHeader.reducedStillPictureHeader()) {
            frameSizeOverride = frameType == Av1FrameType.SWITCH || reader.readFlag();
        }

        int frameOffset = 0;
        if (sequenceHeader.features().orderHint()) {
            frameOffset = readInt(reader, sequenceHeader.features().orderHintBits());
        }

        int primaryRefFrame = (!errorResilientMode && isInterOrSwitch(frameType))
                ? readInt(reader, 3)
                : PRIMARY_REF_NONE;

        if (sequenceHeader.timingInfo().decoderModelInfoPresent()) {
            boolean bufferRemovalTimePresent = reader.readFlag();
            if (bufferRemovalTimePresent) {
                for (int i = 0; i < sequenceHeader.operatingPointCount(); i++) {
                    SequenceHeader.OperatingPoint operatingPoint = sequenceHeader.operatingPoint(i);
                    if (operatingPoint.decoderModelParamPresent()) {
                        int idc = operatingPoint.idc();
                        boolean inTemporalLayer = ((idc >> temporalId) & 1) != 0;
                        boolean inSpatialLayer = ((idc >> (spatialId + 8)) & 1) != 0;
                        if (idc == 0 || (inTemporalLayer && inSpatialLayer)) {
                            readLong(reader, sequenceHeader.timingInfo().bufferRemovalDelayLength());
                        }
                    }
                }
            }
        }
        int refreshFrameFlags;
        boolean frameReferenceShortSignaling = false;
        int[] referenceFrameIndices = new int[]{-1, -1, -1, -1, -1, -1, -1};
        FrameSizeResult frameSizeResult;
        boolean allowIntrabc = false;
        boolean allowHighPrecisionMotionVectors = false;
        FrameHeader.InterpolationFilter subpelFilterMode = FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR;
        boolean switchableMotionMode = false;
        boolean useReferenceFrameMotionVectors = false;

        if (!isInterOrSwitch(frameType)) {
            refreshFrameFlags = (frameType == Av1FrameType.KEY && showFrame) ? 0xFF : readInt(reader, 8);
            if (refreshFrameFlags != 0xFF && errorResilientMode && sequenceHeader.features().orderHint()) {
                skipReferenceOrderHints(reader, sequenceHeader);
            }
            if (strictStdCompliance && frameType == Av1FrameType.INTRA && refreshFrameFlags == 0xFF) {
                fail("Intra frame headers must not refresh all reference frames in strict mode");
            }

            frameSizeResult = readFrameSize(
                    reader,
                    sequenceHeader,
                    frameSizeOverride,
                    false,
                    referenceFrameHeaders,
                    referenceFrameIndices
            );
            if (allowScreenContentTools && !frameSizeResult.superResolution.enabled()) {
                allowIntrabc = reader.readFlag();
            }
        } else {
            refreshFrameFlags = frameType == Av1FrameType.SWITCH ? 0xFF : readInt(reader, 8);
            if (errorResilientMode && sequenceHeader.features().orderHint()) {
                skipReferenceOrderHints(reader, sequenceHeader);
            }

            if (sequenceHeader.features().orderHint()) {
                frameReferenceShortSignaling = reader.readFlag();
                if (frameReferenceShortSignaling) {
                    deriveReferenceFrameIndices(reader, sequenceHeader, frameOffset, referenceFrameHeaders, referenceFrameIndices);
                }
            }

            for (int i = 0; i < REFERENCES_PER_FRAME; i++) {
                if (!frameReferenceShortSignaling) {
                    referenceFrameIndices[i] = readInt(reader, 3);
                }
                if (sequenceHeader.frameIdNumbersPresent()) {
                    long deltaReferenceFrameId = readLong(reader, sequenceHeader.deltaFrameIdBits()) + 1;
                    long frameIdMask = (1L << sequenceHeader.frameIdBits()) - 1;
                    long referenceFrameId = (frameId + (1L << sequenceHeader.frameIdBits()) - deltaReferenceFrameId) & frameIdMask;
                    FrameHeader referenceFrameHeader = referenceFrameHeaders[referenceFrameIndices[i]];
                    if (referenceFrameHeader != null && referenceFrameHeader.frameId() != referenceFrameId) {
                        fail("Reference frame id does not match the decoded slot");
                    }
                }
            }

            frameSizeResult = readFrameSize(
                    reader,
                    sequenceHeader,
                    frameSizeOverride,
                    !errorResilientMode && frameSizeOverride,
                    referenceFrameHeaders,
                    referenceFrameIndices
            );
            if (!forceIntegerMotionVectors) {
                allowHighPrecisionMotionVectors = reader.readFlag();
            }
            subpelFilterMode = parseInterpolationFilter(reader);
            switchableMotionMode = reader.readFlag();
            if (!errorResilientMode && sequenceHeader.features().refFrameMotionVectors()
                    && sequenceHeader.features().orderHint()) {
                useReferenceFrameMotionVectors = reader.readFlag();
            }
        }

        boolean refreshContext = false;
        if (!sequenceHeader.reducedStillPictureHeader() && !disableCdfUpdate) {
            refreshContext = !reader.readFlag();
        }

        FrameHeader.TilingInfo tiling = parseTiling(reader, sequenceHeader, frameSizeResult.frameSize);
        FrameHeader.QuantizationInfo quantization = parseQuantization(reader, sequenceHeader);
        FrameHeader.SegmentationInfo segmentation = parseSegmentation(
                reader,
                primaryRefFrame,
                referenceFrameHeaders,
                referenceFrameIndices
        );
        FrameHeader.DeltaInfo delta = parseDelta(reader, quantization.baseQIndex(), allowIntrabc);
        DerivedLosslessState losslessState = deriveLosslessState(quantization, segmentation);
        FrameHeader.LoopFilterInfo loopFilter = parseLoopFilter(
                reader,
                sequenceHeader,
                primaryRefFrame,
                referenceFrameHeaders,
                referenceFrameIndices,
                allowIntrabc,
                losslessState.allLossless
        );
        FrameHeader.CdefInfo cdef = parseCdef(reader, sequenceHeader, allowIntrabc, losslessState.allLossless);
        FrameHeader.RestorationInfo restoration = parseRestoration(reader, sequenceHeader, frameSizeResult.superResolution, allowIntrabc, losslessState.allLossless);
        FrameHeader.TransformMode transformMode = losslessState.allLossless
                ? FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY
                : (reader.readFlag() ? FrameHeader.TransformMode.SWITCHABLE : FrameHeader.TransformMode.LARGEST);
        boolean switchableCompoundReferences = isInterOrSwitch(frameType) && reader.readFlag();
        SkipModeResult skipMode = deriveSkipMode(
                reader,
                sequenceHeader,
                frameType,
                frameOffset,
                switchableCompoundReferences,
                referenceFrameHeaders,
                referenceFrameIndices
        );
        boolean warpedMotion = false;
        if (!errorResilientMode && isInterOrSwitch(frameType) && sequenceHeader.features().warpedMotion()) {
            warpedMotion = reader.readFlag();
        }
        boolean reducedTransformSet = reader.readFlag();
        FrameHeader.GlobalMotionParams[] globalMotionParameters = parseGlobalMotion(
                reader,
                frameType,
                allowHighPrecisionMotionVectors,
                primaryRefFrame,
                referenceFrameHeaders,
                referenceFrameIndices
        );
        FrameHeader.FilmGrainParams filmGrain = parseFilmGrain(
                reader,
                sequenceHeader,
                frameType,
                showFrame,
                showableFrame,
                referenceFrameHeaders,
                referenceFrameIndices
        );

        FrameHeader parsedHeader = new FrameHeader(
                temporalId,
                spatialId,
                false,
                0,
                frameId,
                framePresentationDelay,
                frameType,
                showFrame,
                showableFrame,
                errorResilientMode,
                disableCdfUpdate,
                allowScreenContentTools,
                forceIntegerMotionVectors,
                frameSizeOverride,
                primaryRefFrame,
                frameOffset,
                refreshFrameFlags,
                frameReferenceShortSignaling,
                referenceFrameIndices,
                frameSizeResult.frameSize,
                frameSizeResult.superResolution,
                allowIntrabc,
                allowHighPrecisionMotionVectors,
                subpelFilterMode,
                switchableMotionMode,
                useReferenceFrameMotionVectors,
                refreshContext,
                tiling,
                quantization,
                losslessState.segmentation,
                delta,
                losslessState.allLossless,
                loopFilter,
                cdef,
                restoration,
                transformMode,
                switchableCompoundReferences,
                skipMode.allowed,
                skipMode.enabled,
                skipMode.referenceIndices,
                warpedMotion,
                reducedTransformSet,
                filmGrain
        );
        parsedHeader = parsedHeader.withGlobalMotionParameters(globalMotionParameters);
        if (strictStdCompliance) {
            FrameHeaderConformanceValidator.validate(sequenceHeader, parsedHeader, referenceFrameHeaders);
        }
        return parsedHeader;
    }

    /// Validates the supplied reference-frame header array shape.
    ///
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    private static void validateReferenceFrameHeaders(FrameHeader[] referenceFrameHeaders) {
        Objects.requireNonNull(referenceFrameHeaders, "referenceFrameHeaders");
        if (referenceFrameHeaders.length != TOTAL_REFERENCE_FRAMES) {
            throw new IllegalArgumentException("referenceFrameHeaders.length != 8: " + referenceFrameHeaders.length);
        }
    }

    /// Consumes the error-resilient reference order-hint array when it is present.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @throws IOException if the payload is truncated
    private static void skipReferenceOrderHints(BitReader reader, SequenceHeader sequenceHeader) throws IOException {
        for (int i = 0; i < TOTAL_REFERENCE_FRAMES; i++) {
            readInt(reader, sequenceHeader.features().orderHintBits());
        }
    }

    /// Derives the reference-frame list from short-signaling syntax.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param frameOffset the current frame order hint
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceFrameIndices the destination LAST..ALTREF slot array
    /// @throws IOException if any referenced header is unavailable or invalid
    private static void deriveReferenceFrameIndices(
            BitReader reader,
            SequenceHeader sequenceHeader,
            int frameOffset,
            FrameHeader[] referenceFrameHeaders,
            int[] referenceFrameIndices
    ) throws IOException {
        referenceFrameIndices[0] = readInt(reader, 3);
        referenceFrameIndices[3] = readInt(reader, 3);

        int[] referenceOffsets = new int[TOTAL_REFERENCE_FRAMES];
        boolean[] usedFrames = new boolean[TOTAL_REFERENCE_FRAMES];
        for (int i = 0; i < TOTAL_REFERENCE_FRAMES; i++) {
            FrameHeader referenceFrameHeader = referenceFrameHeaders[i];
            if (referenceFrameHeader == null) {
                fail("Short-signaled references require all reference-frame headers to be available");
            }
            referenceOffsets[i] = getPocDiff(
                    sequenceHeader.features().orderHintBits(),
                    referenceFrameHeader.frameOffset(),
                    frameOffset
            );
        }
        usedFrames[referenceFrameIndices[0]] = true;
        usedFrames[referenceFrameIndices[3]] = true;

        int referenceIndex = -1;
        for (int i = 0; i < TOTAL_REFERENCE_FRAMES; i++) {
            int offset = referenceOffsets[i];
            if (!usedFrames[i]
                    && offset >= 0
                    && (referenceIndex < 0 || offset >= referenceOffsets[referenceIndex])) {
                referenceIndex = i;
            }
        }
        if (referenceIndex >= 0) {
            referenceFrameIndices[6] = referenceIndex;
            usedFrames[referenceIndex] = true;
        }

        for (int i = 4; i < 6; i++) {
            referenceIndex = -1;
            for (int j = 0; j < TOTAL_REFERENCE_FRAMES; j++) {
                int offset = referenceOffsets[j];
                if (!usedFrames[j]
                        && offset >= 0
                        && (referenceIndex < 0 || offset < referenceOffsets[referenceIndex])) {
                    referenceIndex = j;
                }
            }
            if (referenceIndex >= 0) {
                referenceFrameIndices[i] = referenceIndex;
                usedFrames[referenceIndex] = true;
            }
        }

        for (int i = 0; i < REFERENCES_PER_FRAME - 2; i++) {
            int referenceFrame = switch (i) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 5;
                case 4 -> 6;
                default -> throw new AssertionError(i);
            };
            if (referenceFrameIndices[referenceFrame] >= 0) {
                continue;
            }

            referenceIndex = -1;
            for (int j = 0; j < TOTAL_REFERENCE_FRAMES; j++) {
                int offset = referenceOffsets[j];
                if (!usedFrames[j]
                        && offset < 0
                        && (referenceIndex < 0 || offset >= referenceOffsets[referenceIndex])) {
                    referenceIndex = j;
                }
            }
            if (referenceIndex >= 0) {
                referenceFrameIndices[referenceFrame] = referenceIndex;
                usedFrames[referenceIndex] = true;
            }
        }

        int earliestReference = 0;
        for (int i = 1; i < TOTAL_REFERENCE_FRAMES; i++) {
            if (referenceOffsets[i] < referenceOffsets[earliestReference]) {
                earliestReference = i;
            }
        }
        for (int i = 0; i < REFERENCES_PER_FRAME; i++) {
            if (referenceFrameIndices[i] < 0) {
                referenceFrameIndices[i] = earliestReference;
            }
        }
    }

    /// Parses the frame size and render size fields for intra and inter frames.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param frameSizeOverride whether frame size override signaling is enabled
    /// @param useReferenceFrameSize whether the frame size may be copied from a reference frame
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceFrameIndices the decoded LAST..ALTREF slot indices
    /// @return the parsed frame size and super-resolution state
    /// @throws IOException if the payload is truncated or invalid
    private static FrameSizeResult readFrameSize(
            BitReader reader,
            SequenceHeader sequenceHeader,
            boolean frameSizeOverride,
            boolean useReferenceFrameSize,
            FrameHeader[] referenceFrameHeaders,
            int[] referenceFrameIndices
    ) throws IOException {
        int upscaledWidth;
        int height;
        int renderWidth;
        int renderHeight;
        if (useReferenceFrameSize) {
            for (int i = 0; i < REFERENCES_PER_FRAME; i++) {
                if (reader.readFlag()) {
                    int referenceSlot = referenceFrameIndices[i];
                    if (referenceSlot < 0 || referenceSlot >= TOTAL_REFERENCE_FRAMES) {
                        fail("Reference-sized frames require a decoded reference-frame slot");
                    }
                    FrameHeader referenceFrameHeader = referenceFrameHeaders[referenceSlot];
                    if (referenceFrameHeader == null) {
                        fail("Reference-sized frames require the selected reference-frame header");
                    }
                    upscaledWidth = referenceFrameHeader.frameSize().upscaledWidth();
                    height = referenceFrameHeader.frameSize().height();
                    renderWidth = referenceFrameHeader.frameSize().renderWidth();
                    renderHeight = referenceFrameHeader.frameSize().renderHeight();

                    FrameHeader.SuperResolutionInfo superResolution = readSuperResolution(reader, sequenceHeader);
                    return new FrameSizeResult(
                            new FrameHeader.FrameSize(
                                    computeCodedWidth(upscaledWidth, superResolution.widthScaleDenominator()),
                                    upscaledWidth,
                                    height,
                                    renderWidth,
                                    renderHeight
                            ),
                            superResolution
                    );
                }
            }
        }

        if (frameSizeOverride) {
            upscaledWidth = readInt(reader, sequenceHeader.widthBits()) + 1;
            height = readInt(reader, sequenceHeader.heightBits()) + 1;
        } else {
            upscaledWidth = sequenceHeader.maxWidth();
            height = sequenceHeader.maxHeight();
        }

        FrameHeader.SuperResolutionInfo superResolution = readSuperResolution(reader, sequenceHeader);

        boolean haveRenderSize = reader.readFlag();
        if (haveRenderSize) {
            renderWidth = readInt(reader, 16) + 1;
            renderHeight = readInt(reader, 16) + 1;
        } else {
            renderWidth = upscaledWidth;
            renderHeight = height;
        }

        return new FrameSizeResult(
                new FrameHeader.FrameSize(
                        computeCodedWidth(upscaledWidth, superResolution.widthScaleDenominator()),
                        upscaledWidth,
                        height,
                        renderWidth,
                        renderHeight
                ),
                superResolution
        );
    }

    /// Parses frame-level super-resolution signaling.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @return the parsed super-resolution state
    /// @throws IOException if the payload is truncated
    private static FrameHeader.SuperResolutionInfo readSuperResolution(
            BitReader reader,
            SequenceHeader sequenceHeader
    ) throws IOException {
        boolean enabled = sequenceHeader.features().superResolution() && reader.readFlag();
        int widthScaleDenominator = enabled ? 9 + readInt(reader, 3) : 8;
        return new FrameHeader.SuperResolutionInfo(enabled, widthScaleDenominator);
    }

    /// Computes the coded width after optional super-resolution downscaling.
    ///
    /// @param upscaledWidth the frame width before super-resolution downscaling
    /// @param widthScaleDenominator the super-resolution width denominator
    /// @return the coded width after super-resolution downscaling
    private static int computeCodedWidth(int upscaledWidth, int widthScaleDenominator) {
        if (widthScaleDenominator == 8) {
            return upscaledWidth;
        }
        return Math.max((upscaledWidth * 8 + (widthScaleDenominator >> 1)) / widthScaleDenominator, Math.min(16, upscaledWidth));
    }

    /// Parses tile layout information.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param frameSize the parsed frame dimensions
    /// @return the parsed tile layout information
    /// @throws IOException if the payload is truncated or invalid
    private static FrameHeader.TilingInfo parseTiling(
            BitReader reader,
            SequenceHeader sequenceHeader,
            FrameHeader.FrameSize frameSize
    ) throws IOException {
        boolean uniform = reader.readFlag();
        int superblockSizeMinusOne = (64 << (sequenceHeader.features().use128x128Superblocks() ? 1 : 0)) - 1;
        int superblockLog2 = 6 + (sequenceHeader.features().use128x128Superblocks() ? 1 : 0);
        int superblockWidth = (frameSize.codedWidth() + superblockSizeMinusOne) >> superblockLog2;
        int superblockHeight = (frameSize.height() + superblockSizeMinusOne) >> superblockLog2;
        int maxTileWidthSuperblocks = 4096 >> superblockLog2;
        int maxTileAreaSuperblocks = (4096 * 2304) >> (2 * superblockLog2);
        int minLog2Cols = tileLog2(maxTileWidthSuperblocks, superblockWidth);
        int maxLog2Cols = tileLog2(1, Math.min(superblockWidth, MAX_TILE_COLUMNS));
        int maxLog2Rows = tileLog2(1, Math.min(superblockHeight, MAX_TILE_ROWS));
        int minLog2Tiles = Math.max(tileLog2(maxTileAreaSuperblocks, superblockWidth * superblockHeight), minLog2Cols);

        int log2Cols;
        int columns = 0;
        int[] columnStarts = new int[MAX_TILE_COLUMNS + 1];
        int minLog2Rows;
        int log2Rows;
        int rows = 0;
        int[] rowStarts = new int[MAX_TILE_ROWS + 1];

        if (uniform) {
            log2Cols = minLog2Cols;
            while (log2Cols < maxLog2Cols && reader.readFlag()) {
                log2Cols++;
            }
            int tileWidth = 1 + ((superblockWidth - 1) >> log2Cols);
            for (int superblockX = 0; superblockX < superblockWidth; superblockX += tileWidth) {
                columnStarts[columns++] = superblockX;
            }
            minLog2Rows = Math.max(minLog2Tiles - log2Cols, 0);
            log2Rows = minLog2Rows;
            while (log2Rows < maxLog2Rows && reader.readFlag()) {
                log2Rows++;
            }
            int tileHeight = 1 + ((superblockHeight - 1) >> log2Rows);
            for (int superblockY = 0; superblockY < superblockHeight; superblockY += tileHeight) {
                rowStarts[rows++] = superblockY;
            }
        } else {
            int widestTile = 0;
            int remainingMaxTileArea = superblockWidth * superblockHeight;

            int superblockX = 0;
            while (superblockX < superblockWidth && columns < MAX_TILE_COLUMNS) {
                int tileWidthSuperblocks = Math.min(superblockWidth - superblockX, maxTileWidthSuperblocks);
                int tileWidth = tileWidthSuperblocks > 1
                        ? 1 + readUniform(reader, tileWidthSuperblocks)
                        : 1;
                columnStarts[columns++] = superblockX;
                superblockX += tileWidth;
                widestTile = Math.max(widestTile, tileWidth);
            }

            log2Cols = tileLog2(1, columns);
            minLog2Rows = 0;
            if (minLog2Tiles > 0) {
                remainingMaxTileArea >>= minLog2Tiles + 1;
            }
            int maxTileHeightSuperblocks = Math.max(remainingMaxTileArea / widestTile, 1);

            int superblockY = 0;
            while (superblockY < superblockHeight && rows < MAX_TILE_ROWS) {
                int tileHeightSuperblocks = Math.min(superblockHeight - superblockY, maxTileHeightSuperblocks);
                int tileHeight = tileHeightSuperblocks > 1
                        ? 1 + readUniform(reader, tileHeightSuperblocks)
                        : 1;
                rowStarts[rows++] = superblockY;
                superblockY += tileHeight;
            }

            log2Rows = tileLog2(1, rows);
        }

        columnStarts[columns] = superblockWidth;
        rowStarts[rows] = superblockHeight;

        int updateTileIndex = 0;
        int sizeBytes = 0;
        if (log2Cols != 0 || log2Rows != 0) {
            updateTileIndex = readInt(reader, log2Cols + log2Rows);
            if (updateTileIndex >= columns * rows) {
                fail("Tile update index exceeds the number of tiles");
            }
            sizeBytes = readInt(reader, 2) + 1;
        }

        return new FrameHeader.TilingInfo(
                uniform,
                sizeBytes,
                minLog2Cols,
                maxLog2Cols,
                log2Cols,
                columns,
                minLog2Rows,
                maxLog2Rows,
                log2Rows,
                rows,
                Arrays.copyOf(columnStarts, columns + 1),
                Arrays.copyOf(rowStarts, rows + 1),
                updateTileIndex
        );
    }

    /// Parses quantization parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @return the parsed quantization parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.QuantizationInfo parseQuantization(BitReader reader, SequenceHeader sequenceHeader) throws IOException {
        int baseQIndex = readInt(reader, 8);
        int yDcDelta = reader.readFlag() ? reader.readSignedBits(7) : 0;

        int uDcDelta = 0;
        int uAcDelta = 0;
        int vDcDelta = 0;
        int vAcDelta = 0;
        if (sequenceHeader.colorConfig().chromaFormat() != Av1ChromaFormat.MONOCHROME) {
            boolean diffUvDelta = sequenceHeader.colorConfig().separateUvDeltaQ() && reader.readFlag();
            if (reader.readFlag()) {
                uDcDelta = reader.readSignedBits(7);
            }
            if (reader.readFlag()) {
                uAcDelta = reader.readSignedBits(7);
            }
            if (diffUvDelta) {
                if (reader.readFlag()) {
                    vDcDelta = reader.readSignedBits(7);
                }
                if (reader.readFlag()) {
                    vAcDelta = reader.readSignedBits(7);
                }
            } else {
                vDcDelta = uDcDelta;
                vAcDelta = uAcDelta;
            }
        }

        boolean useQuantizationMatrices = reader.readFlag();
        int qmY = 0;
        int qmU = 0;
        int qmV = 0;
        if (useQuantizationMatrices) {
            qmY = readInt(reader, 4);
            qmU = readInt(reader, 4);
            qmV = sequenceHeader.colorConfig().separateUvDeltaQ() ? readInt(reader, 4) : qmU;
        }

        return new FrameHeader.QuantizationInfo(
                baseQIndex,
                yDcDelta,
                uDcDelta,
                uAcDelta,
                vDcDelta,
                vAcDelta,
                useQuantizationMatrices,
                qmY,
                qmU,
                qmV
        );
    }

    /// Resolves the refreshed frame header addressed by `primary_ref_frame`.
    ///
    /// @param primaryRefFrame the primary reference-frame position, or `7` when none is used
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceFrameIndices the decoded LAST..ALTREF slot indices
    /// @return the refreshed frame header addressed by `primary_ref_frame`, or `null` when none is used
    /// @throws IOException if `primary_ref_frame` requires a missing reference header
    private static FrameHeader resolvePrimaryReferenceFrameHeader(
            int primaryRefFrame,
            FrameHeader[] referenceFrameHeaders,
            int[] referenceFrameIndices
    ) throws IOException {
        if (primaryRefFrame == PRIMARY_REF_NONE) {
            return null;
        }
        int referenceSlot = referenceFrameIndices[primaryRefFrame];
        if (referenceSlot < 0 || referenceSlot >= TOTAL_REFERENCE_FRAMES) {
            fail("primary_ref_frame does not resolve to a valid reference slot");
        }
        FrameHeader referenceFrameHeader = referenceFrameHeaders[referenceSlot];
        if (referenceFrameHeader == null) {
            fail("primary_ref_frame requires an available refreshed reference header");
        }
        return referenceFrameHeader;
    }

    /// Parses segmentation parameters, optionally inheriting segment data from the primary reference frame.
    ///
    /// @param reader the payload bit reader
    /// @param primaryRefFrame the primary reference frame index
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceFrameIndices the decoded LAST..ALTREF slot indices
    /// @return the parsed segmentation parameters
    /// @throws IOException if the payload is truncated or invalid
    private static FrameHeader.SegmentationInfo parseSegmentation(
            BitReader reader,
            int primaryRefFrame,
            FrameHeader[] referenceFrameHeaders,
            int[] referenceFrameIndices
    ) throws IOException {
        boolean enabled = reader.readFlag();
        FrameHeader.SegmentData[] segments = defaultSegments();
        boolean[] losslessBySegment = new boolean[MAX_SEGMENTS];
        int[] qIndexBySegment = new int[MAX_SEGMENTS];

        if (!enabled) {
            return new FrameHeader.SegmentationInfo(false, false, false, false, false, -1, segments, losslessBySegment, qIndexBySegment);
        }

        boolean updateMap;
        boolean temporalUpdate = false;
        boolean updateData;
        if (primaryRefFrame == PRIMARY_REF_NONE) {
            updateMap = true;
            updateData = true;
        } else {
            updateMap = reader.readFlag();
            if (updateMap) {
                temporalUpdate = reader.readFlag();
            }
            updateData = reader.readFlag();
        }

        boolean preskip;
        int lastActiveSegmentId;
        if (updateData) {
            preskip = false;
            lastActiveSegmentId = 0;
            for (int i = 0; i < MAX_SEGMENTS; i++) {
                boolean deltaQEnabled = reader.readFlag();
                int deltaQ = deltaQEnabled ? reader.readSignedBits(9) : 0;
                boolean deltaLfYVerticalEnabled = reader.readFlag();
                int deltaLfYVertical = deltaLfYVerticalEnabled ? reader.readSignedBits(7) : 0;
                boolean deltaLfYHorizontalEnabled = reader.readFlag();
                int deltaLfYHorizontal = deltaLfYHorizontalEnabled ? reader.readSignedBits(7) : 0;
                boolean deltaLfUEnabled = reader.readFlag();
                int deltaLfU = deltaLfUEnabled ? reader.readSignedBits(7) : 0;
                boolean deltaLfVEnabled = reader.readFlag();
                int deltaLfV = deltaLfVEnabled ? reader.readSignedBits(7) : 0;
                boolean referenceFrameEnabled = reader.readFlag();
                int referenceFrame = referenceFrameEnabled ? readInt(reader, 3) : -1;
                boolean skip = reader.readFlag();
                boolean globalMotion = reader.readFlag();

                if (deltaQEnabled
                        || deltaLfYVerticalEnabled
                        || deltaLfYHorizontalEnabled
                        || deltaLfUEnabled
                        || deltaLfVEnabled
                        || referenceFrameEnabled
                        || skip
                        || globalMotion) {
                    lastActiveSegmentId = i;
                }
                if (referenceFrameEnabled || skip || globalMotion) {
                    preskip = true;
                }

                segments[i] = new FrameHeader.SegmentData(
                        deltaQ,
                        deltaLfYVertical,
                        deltaLfYHorizontal,
                        deltaLfU,
                        deltaLfV,
                        referenceFrame,
                        skip,
                        globalMotion
                );
            }
        } else {
            FrameHeader referenceFrameHeader = resolvePrimaryReferenceFrameHeader(
                    primaryRefFrame,
                    referenceFrameHeaders,
                    referenceFrameIndices
            );
            if (referenceFrameHeader == null) {
                fail("Segmentation data cannot be inherited without a primary reference frame");
            }
            FrameHeader.SegmentationInfo referencedSegmentation = referenceFrameHeader.segmentation();
            preskip = referencedSegmentation.preskip();
            lastActiveSegmentId = referencedSegmentation.lastActiveSegmentId();
            segments = referencedSegmentation.segments();
        }

        return new FrameHeader.SegmentationInfo(
                enabled,
                updateMap,
                temporalUpdate,
                updateData,
                preskip,
                lastActiveSegmentId,
                segments,
                losslessBySegment,
                qIndexBySegment
        );
    }

    /// Parses delta-q and delta-lf signaling flags.
    ///
    /// @param reader the payload bit reader
    /// @param baseQIndex the base luma AC quantizer index
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @return the parsed delta-q and delta-lf signaling flags
    /// @throws IOException if the payload is truncated
    private static FrameHeader.DeltaInfo parseDelta(BitReader reader, int baseQIndex, boolean allowIntrabc) throws IOException {
        if (baseQIndex == 0) {
            return new FrameHeader.DeltaInfo(false, 0, false, 0, false);
        }

        boolean deltaQPresent = reader.readFlag();
        if (!deltaQPresent) {
            return new FrameHeader.DeltaInfo(false, 0, false, 0, false);
        }

        int deltaQResLog2 = readInt(reader, 2);
        boolean deltaLfPresent = false;
        int deltaLfResLog2 = 0;
        boolean deltaLfMulti = false;
        if (!allowIntrabc) {
            deltaLfPresent = reader.readFlag();
            if (deltaLfPresent) {
                deltaLfResLog2 = readInt(reader, 2);
                deltaLfMulti = reader.readFlag();
            }
        }
        return new FrameHeader.DeltaInfo(deltaQPresent, deltaQResLog2, deltaLfPresent, deltaLfResLog2, deltaLfMulti);
    }

    /// Derives per-segment lossless flags and qindex values.
    ///
    /// @param quantization the parsed quantization parameters
    /// @param segmentation the parsed segmentation parameters
    /// @return the derived lossless state
    private static DerivedLosslessState deriveLosslessState(
            FrameHeader.QuantizationInfo quantization,
            FrameHeader.SegmentationInfo segmentation
    ) {
        boolean deltaLossless = quantization.yDcDelta() == 0
                && quantization.uDcDelta() == 0
                && quantization.uAcDelta() == 0
                && quantization.vDcDelta() == 0
                && quantization.vAcDelta() == 0;

        boolean[] losslessBySegment = new boolean[MAX_SEGMENTS];
        int[] qIndexBySegment = new int[MAX_SEGMENTS];
        boolean allLossless = true;
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            int qIndex = segmentation.enabled()
                    ? clipUnsignedByte(quantization.baseQIndex() + segmentation.segment(i).deltaQ())
                    : quantization.baseQIndex();
            qIndexBySegment[i] = qIndex;
            boolean lossless = qIndex == 0 && deltaLossless;
            losslessBySegment[i] = lossless;
            allLossless &= lossless;
        }

        FrameHeader.SegmentationInfo updatedSegmentation = new FrameHeader.SegmentationInfo(
                segmentation.enabled(),
                segmentation.updateMap(),
                segmentation.temporalUpdate(),
                segmentation.updateData(),
                segmentation.preskip(),
                segmentation.lastActiveSegmentId(),
                segmentation.segments(),
                losslessBySegment,
                qIndexBySegment
        );
        return new DerivedLosslessState(updatedSegmentation, allLossless);
    }

    /// Parses loop filter parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param primaryRefFrame the primary reference-frame position, or `7` when none is used
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceFrameIndices the decoded LAST..ALTREF slot indices
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @param allLossless whether all segments are lossless
    /// @return the parsed loop filter parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.LoopFilterInfo parseLoopFilter(
            BitReader reader,
            SequenceHeader sequenceHeader,
            int primaryRefFrame,
            FrameHeader[] referenceFrameHeaders,
            int[] referenceFrameIndices,
            boolean allowIntrabc,
            boolean allLossless
    ) throws IOException {
        int[] levelY = new int[]{0, 0};
        int levelU = 0;
        int levelV = 0;
        int sharpness = 0;
        boolean modeRefDeltaEnabled = true;
        boolean modeRefDeltaUpdate = true;
        int[] referenceDeltas = Arrays.copyOf(DEFAULT_REFERENCE_DELTAS, DEFAULT_REFERENCE_DELTAS.length);
        int[] modeDeltas = Arrays.copyOf(DEFAULT_MODE_DELTAS, DEFAULT_MODE_DELTAS.length);

        if (!allLossless && !allowIntrabc) {
            levelY[0] = readInt(reader, 6);
            levelY[1] = readInt(reader, 6);
            if (sequenceHeader.colorConfig().chromaFormat() != Av1ChromaFormat.MONOCHROME
                    && (levelY[0] != 0 || levelY[1] != 0)) {
                levelU = readInt(reader, 6);
                levelV = readInt(reader, 6);
            }
            sharpness = readInt(reader, 3);

            if (primaryRefFrame != PRIMARY_REF_NONE) {
                FrameHeader referenceFrameHeader = resolvePrimaryReferenceFrameHeader(
                        primaryRefFrame,
                        referenceFrameHeaders,
                        referenceFrameIndices
                );
                if (referenceFrameHeader == null) {
                    fail("Loop-filter state cannot be inherited without a primary reference frame");
                }
                referenceDeltas = referenceFrameHeader.loopFilter().referenceDeltas();
                modeDeltas = referenceFrameHeader.loopFilter().modeDeltas();
            }

            modeRefDeltaEnabled = reader.readFlag();
            modeRefDeltaUpdate = false;
            if (modeRefDeltaEnabled) {
                modeRefDeltaUpdate = reader.readFlag();
                if (modeRefDeltaUpdate) {
                    for (int i = 0; i < referenceDeltas.length; i++) {
                        if (reader.readFlag()) {
                            referenceDeltas[i] = reader.readSignedBits(7);
                        }
                    }
                    for (int i = 0; i < modeDeltas.length; i++) {
                        if (reader.readFlag()) {
                            modeDeltas[i] = reader.readSignedBits(7);
                        }
                    }
                }
            }
        }

        return new FrameHeader.LoopFilterInfo(levelY, levelU, levelV, sharpness, modeRefDeltaEnabled, modeRefDeltaUpdate, referenceDeltas, modeDeltas);
    }

    /// Parses CDEF parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @param allLossless whether all segments are lossless
    /// @return the parsed CDEF parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.CdefInfo parseCdef(
            BitReader reader,
            SequenceHeader sequenceHeader,
            boolean allowIntrabc,
            boolean allLossless
    ) throws IOException {
        if (allLossless || !sequenceHeader.features().cdef() || allowIntrabc) {
            return new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]);
        }

        int damping = readInt(reader, 2) + 3;
        int bits = readInt(reader, 2);
        int count = 1 << bits;
        if (count > MAX_CDEF_STRENGTHS) {
            fail("CDEF strength count exceeds the supported maximum");
        }

        int[] yStrengths = new int[count];
        int[] uvStrengths = new int[count];
        for (int i = 0; i < count; i++) {
            yStrengths[i] = readInt(reader, 6);
            if (sequenceHeader.colorConfig().chromaFormat() != Av1ChromaFormat.MONOCHROME) {
                uvStrengths[i] = readInt(reader, 6);
            }
        }
        return new FrameHeader.CdefInfo(damping, bits, yStrengths, uvStrengths);
    }

    /// Parses loop restoration parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param superResolution the parsed super-resolution settings
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @param allLossless whether all segments are lossless
    /// @return the parsed loop restoration parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.RestorationInfo parseRestoration(
            BitReader reader,
            SequenceHeader sequenceHeader,
            FrameHeader.SuperResolutionInfo superResolution,
            boolean allowIntrabc,
            boolean allLossless
    ) throws IOException {
        FrameHeader.RestorationType[] types = new FrameHeader.RestorationType[]{
                FrameHeader.RestorationType.NONE,
                FrameHeader.RestorationType.NONE,
                FrameHeader.RestorationType.NONE
        };
        if ((allLossless && !superResolution.enabled()) || !sequenceHeader.features().restoration() || allowIntrabc) {
            return new FrameHeader.RestorationInfo(types, 0, 0);
        }

        types[0] = decodeRestorationType(readInt(reader, 2));
        if (sequenceHeader.colorConfig().chromaFormat() != Av1ChromaFormat.MONOCHROME) {
            types[1] = decodeRestorationType(readInt(reader, 2));
            types[2] = decodeRestorationType(readInt(reader, 2));
        }

        int unitSizeLog2Y = 8;
        int unitSizeLog2Uv = 8;
        if (types[0] != FrameHeader.RestorationType.NONE
                || types[1] != FrameHeader.RestorationType.NONE
                || types[2] != FrameHeader.RestorationType.NONE) {
            unitSizeLog2Y = 6 + (sequenceHeader.features().use128x128Superblocks() ? 1 : 0);
            if (reader.readFlag()) {
                unitSizeLog2Y++;
                if (!sequenceHeader.features().use128x128Superblocks()) {
                    unitSizeLog2Y += reader.readFlag() ? 1 : 0;
                }
            }
            unitSizeLog2Uv = unitSizeLog2Y;
            if ((types[1] != FrameHeader.RestorationType.NONE || types[2] != FrameHeader.RestorationType.NONE)
                    && sequenceHeader.colorConfig().chromaSubsamplingX()
                    && sequenceHeader.colorConfig().chromaSubsamplingY()) {
                unitSizeLog2Uv -= reader.readFlag() ? 1 : 0;
            }
        }

        return new FrameHeader.RestorationInfo(types, unitSizeLog2Y, unitSizeLog2Uv);
    }

    /// Parses the seven frame-level global-motion parameter sets.
    ///
    /// Inter and switch frames inherit each parameter reference from `primary_ref_frame`; other
    /// frame types use identity parameters without consuming global-motion syntax.
    ///
    /// @param reader the payload bit reader
    /// @param frameType the decoded frame type
    /// @param allowHighPrecisionMotionVectors whether high-precision motion vectors are allowed
    /// @param primaryRefFrame the primary reference position, or `7` when absent
    /// @param referenceFrameHeaders the refreshed reference headers indexed by slot
    /// @param referenceFrameIndices the current LAST-through-ALTREF slot selection
    /// @return the seven decoded global-motion parameter sets
    /// @throws IOException if the payload is truncated or inherited state is unavailable
    private static FrameHeader.GlobalMotionParams[] parseGlobalMotion(
            BitReader reader,
            Av1FrameType frameType,
            boolean allowHighPrecisionMotionVectors,
            int primaryRefFrame,
            FrameHeader[] referenceFrameHeaders,
            int[] referenceFrameIndices
    ) throws IOException {
        FrameHeader.GlobalMotionParams[] parameters = new FrameHeader.GlobalMotionParams[REFERENCES_PER_FRAME];
        if (!isInterOrSwitch(frameType)) {
            Arrays.fill(parameters, FrameHeader.GlobalMotionParams.identity());
            return parameters;
        }

        FrameHeader primaryReferenceHeader = resolvePrimaryReferenceFrameHeader(
                primaryRefFrame,
                referenceFrameHeaders,
                referenceFrameIndices
        );
        if (primaryRefFrame != PRIMARY_REF_NONE && primaryReferenceHeader == null) {
            fail("Global motion parameters cannot be inherited without a primary reference frame");
        }

        for (int referenceFrame = 0; referenceFrame < parameters.length; referenceFrame++) {
            FrameHeader.GlobalMotionParams referenceParameters = primaryReferenceHeader == null
                    ? FrameHeader.GlobalMotionParams.identity()
                    : primaryReferenceHeader.globalMotion(referenceFrame);
            parameters[referenceFrame] = parseGlobalMotionParameters(
                    reader,
                    referenceParameters,
                    allowHighPrecisionMotionVectors
            );
        }
        return parameters;
    }

    /// Parses one global-motion parameter set relative to inherited reference parameters.
    ///
    /// @param reader the payload bit reader
    /// @param referenceParameters the inherited parameters used for subexponential recentering
    /// @param allowHighPrecisionMotionVectors whether high-precision motion vectors are allowed
    /// @return the decoded global-motion parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.GlobalMotionParams parseGlobalMotionParameters(
            BitReader reader,
            FrameHeader.GlobalMotionParams referenceParameters,
            boolean allowHighPrecisionMotionVectors
    ) throws IOException {
        if (!reader.readFlag()) {
            return FrameHeader.GlobalMotionParams.identity();
        }

        FrameHeader.GlobalMotionType type;
        if (reader.readFlag()) {
            type = FrameHeader.GlobalMotionType.ROTATION_ZOOM;
        } else {
            type = reader.readFlag()
                    ? FrameHeader.GlobalMotionType.TRANSLATION
                    : FrameHeader.GlobalMotionType.AFFINE;
        }

        int[] matrix = FrameHeader.GlobalMotionParams.identity().matrix();
        int translationBits;
        int translationShift;
        if (type == FrameHeader.GlobalMotionType.ROTATION_ZOOM || type == FrameHeader.GlobalMotionType.AFFINE) {
            int identityScale = 1 << GLOBAL_MOTION_MATRIX_PRECISION;
            matrix[2] = identityScale + 2 * readSignedSubexpWithReference(
                    reader,
                    GLOBAL_MOTION_ALPHA_BITS,
                    (referenceParameters.matrix(2) - identityScale) >> 1
            );
            matrix[3] = 2 * readSignedSubexpWithReference(
                    reader,
                    GLOBAL_MOTION_ALPHA_BITS,
                    referenceParameters.matrix(3) >> 1
            );
            translationBits = 12;
            translationShift = 10;
        } else {
            int precisionReduction = allowHighPrecisionMotionVectors ? 0 : 1;
            translationBits = 9 - precisionReduction;
            translationShift = 13 + precisionReduction;
        }

        if (type == FrameHeader.GlobalMotionType.AFFINE) {
            int identityScale = 1 << GLOBAL_MOTION_MATRIX_PRECISION;
            matrix[4] = 2 * readSignedSubexpWithReference(
                    reader,
                    GLOBAL_MOTION_ALPHA_BITS,
                    referenceParameters.matrix(4) >> 1
            );
            matrix[5] = identityScale + 2 * readSignedSubexpWithReference(
                    reader,
                    GLOBAL_MOTION_ALPHA_BITS,
                    (referenceParameters.matrix(5) - identityScale) >> 1
            );
        } else if (type == FrameHeader.GlobalMotionType.ROTATION_ZOOM) {
            matrix[4] = -matrix[3];
            matrix[5] = matrix[2];
        }

        matrix[0] = readSignedSubexpWithReference(
                reader,
                translationBits,
                referenceParameters.matrix(0) >> translationShift
        ) << translationShift;
        matrix[1] = readSignedSubexpWithReference(
                reader,
                translationBits,
                referenceParameters.matrix(1) >> translationShift
        ) << translationShift;
        return new FrameHeader.GlobalMotionParams(type, matrix);
    }

    /// Reads a signed AV1 subexponential value recentered around a reference.
    ///
    /// @param reader the payload bit reader
    /// @param magnitudeBits the signed magnitude bit count
    /// @param reference the signed recentering reference
    /// @return the decoded signed value
    /// @throws IOException if the payload is truncated
    private static int readSignedSubexpWithReference(
            BitReader reader,
            int magnitudeBits,
            int reference
    ) throws IOException {
        int magnitude = 1 << magnitudeBits;
        int maximum = magnitude << 1;
        int unsignedReference = reference + magnitude;
        if (unsignedReference < 0 || unsignedReference > maximum) {
            fail("Global motion subexponential reference is out of range: " + reference);
        }
        return readUnsignedSubexpWithReference(reader, maximum, unsignedReference) - magnitude;
    }

    /// Reads an unsigned finite subexponential value recentered around a reference.
    ///
    /// @param reader the payload bit reader
    /// @param maximum the inclusive decoded maximum
    /// @param reference the unsigned recentering reference in `[0, maximum]`
    /// @return the decoded unsigned value
    /// @throws IOException if the payload is truncated
    private static int readUnsignedSubexpWithReference(
            BitReader reader,
            int maximum,
            int reference
    ) throws IOException {
        int value = 0;
        for (int group = 0; ; group++) {
            int bits = group == 0 ? GLOBAL_MOTION_SUBEXP_K : GLOBAL_MOTION_SUBEXP_K + group - 1;
            int groupSize = 1 << bits;
            if (maximum < value + 3 * groupSize) {
                value += readUniform(reader, maximum - value + 1);
                break;
            }
            if (!reader.readFlag()) {
                value += readInt(reader, bits);
                break;
            }
            value += groupSize;
        }

        return reference * 2 <= maximum
                ? inverseRecenter(reference, value)
                : maximum - inverseRecenter(maximum - reference, value);
    }

    /// Applies the AV1 inverse non-negative recentering transform.
    ///
    /// @param reference the non-negative recentering reference
    /// @param value the coded non-negative value
    /// @return the inverse-recentered value
    private static int inverseRecenter(int reference, int value) {
        if (value > (reference << 1)) {
            return value;
        }
        return (value & 1) == 0
                ? (value >> 1) + reference
                : reference - ((value + 1) >> 1);
    }

    /// Parses normalized film grain parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param frameType the current AV1 frame type
    /// @param showFrame whether the frame is shown immediately
    /// @param showableFrame whether the frame can be shown later
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceFrameIndices the decoded LAST..ALTREF slot indices
    /// @return the normalized film grain parameters
    /// @throws IOException if the payload is truncated or invalid
    private static FrameHeader.FilmGrainParams parseFilmGrain(
            BitReader reader,
            SequenceHeader sequenceHeader,
            Av1FrameType frameType,
            boolean showFrame,
            boolean showableFrame,
            FrameHeader[] referenceFrameHeaders,
            int[] referenceFrameIndices
    ) throws IOException {
        if (!sequenceHeader.features().filmGrainPresent() || (!showFrame && !showableFrame)) {
            return FrameHeader.FilmGrainParams.disabled();
        }

        if (!reader.readFlag()) {
            return FrameHeader.FilmGrainParams.disabled();
        }

        int grainSeed = readInt(reader, 16);
        boolean updated = frameType != Av1FrameType.INTER || reader.readFlag();
        if (!updated) {
            int referenceFrameIndex = readInt(reader, 3);
            if (!usesReferenceFrameIndex(referenceFrameIndices, referenceFrameIndex)) {
                fail("film_grain_params_ref_idx must match one of the frame reference slots");
            }

            FrameHeader referenceFrameHeader = referenceFrameHeaders[referenceFrameIndex];
            if (referenceFrameHeader == null) {
                fail("film_grain_params_ref_idx requires an available refreshed reference header");
            }

            FrameHeader.FilmGrainParams referencedFilmGrain = referenceFrameHeader.filmGrain();
            return new FrameHeader.FilmGrainParams(
                    true,
                    grainSeed,
                    false,
                    referenceFrameIndex,
                    referencedFilmGrain.yPoints(),
                    referencedFilmGrain.chromaScalingFromLuma(),
                    referencedFilmGrain.cbPoints(),
                    referencedFilmGrain.crPoints(),
                    referencedFilmGrain.scalingShift(),
                    referencedFilmGrain.arCoeffLag(),
                    referencedFilmGrain.arCoefficientsY(),
                    referencedFilmGrain.arCoefficientsCb(),
                    referencedFilmGrain.arCoefficientsCr(),
                    referencedFilmGrain.arCoeffShift(),
                    referencedFilmGrain.grainScaleShift(),
                    referencedFilmGrain.cbMult(),
                    referencedFilmGrain.cbLumaMult(),
                    referencedFilmGrain.cbOffset(),
                    referencedFilmGrain.crMult(),
                    referencedFilmGrain.crLumaMult(),
                    referencedFilmGrain.crOffset(),
                    referencedFilmGrain.overlapEnabled(),
                    referencedFilmGrain.clipToRestrictedRange()
            );
        }

        int numYPoints = readInt(reader, 4);
        if (numYPoints > MAX_FILM_GRAIN_Y_POINTS) {
            fail("Film grain luma point count exceeds the AV1 maximum of 14");
        }
        FrameHeader.FilmGrainPoint[] yPoints = parseFilmGrainPoints(reader, numYPoints, "luma");

        boolean monochrome = sequenceHeader.colorConfig().monochrome();
        boolean chromaScalingFromLuma = !monochrome && reader.readFlag();
        boolean subsamplingX = sequenceHeader.colorConfig().chromaSubsamplingX();
        boolean subsamplingY = sequenceHeader.colorConfig().chromaSubsamplingY();

        FrameHeader.FilmGrainPoint[] cbPoints;
        FrameHeader.FilmGrainPoint[] crPoints;
        if (monochrome || chromaScalingFromLuma || (subsamplingX && subsamplingY && numYPoints == 0)) {
            cbPoints = new FrameHeader.FilmGrainPoint[0];
            crPoints = new FrameHeader.FilmGrainPoint[0];
        } else {
            int numCbPoints = readInt(reader, 4);
            if (numCbPoints > MAX_FILM_GRAIN_UV_POINTS) {
                fail("Film grain Cb point count exceeds the AV1 maximum of 10");
            }
            cbPoints = parseFilmGrainPoints(reader, numCbPoints, "Cb");

            int numCrPoints = readInt(reader, 4);
            if (numCrPoints > MAX_FILM_GRAIN_UV_POINTS) {
                fail("Film grain Cr point count exceeds the AV1 maximum of 10");
            }
            crPoints = parseFilmGrainPoints(reader, numCrPoints, "Cr");

            if (subsamplingX && subsamplingY) {
                if (numCbPoints == 0 && numCrPoints != 0) {
                    fail("4:2:0 film grain must not signal Cr points without Cb points");
                }
                if (numCbPoints != 0 && numCrPoints == 0) {
                    fail("4:2:0 film grain must signal Cr points when Cb points are present");
                }
            }
        }

        int scalingShift = readInt(reader, 2) + 8;
        int arCoeffLag = readInt(reader, 2);
        int numPosLuma = 2 * arCoeffLag * (arCoeffLag + 1);
        int numPosChroma = yPoints.length > 0 ? numPosLuma + 1 : numPosLuma;

        int[] arCoefficientsY = yPoints.length > 0 ? parseFilmGrainCoefficients(reader, numPosLuma) : new int[0];
        int[] arCoefficientsCb = chromaScalingFromLuma || cbPoints.length > 0
                ? parseFilmGrainCoefficients(reader, numPosChroma)
                : new int[0];
        int[] arCoefficientsCr = chromaScalingFromLuma || crPoints.length > 0
                ? parseFilmGrainCoefficients(reader, numPosChroma)
                : new int[0];

        int arCoeffShift = readInt(reader, 2) + 6;
        int grainScaleShift = readInt(reader, 2);

        int cbMult = 0;
        int cbLumaMult = 0;
        int cbOffset = 0;
        if (cbPoints.length > 0) {
            cbMult = readInt(reader, 8) - 128;
            cbLumaMult = readInt(reader, 8) - 128;
            cbOffset = readInt(reader, 9) - 256;
        }

        int crMult = 0;
        int crLumaMult = 0;
        int crOffset = 0;
        if (crPoints.length > 0) {
            crMult = readInt(reader, 8) - 128;
            crLumaMult = readInt(reader, 8) - 128;
            crOffset = readInt(reader, 9) - 256;
        }

        boolean overlapEnabled = reader.readFlag();
        boolean clipToRestrictedRange = reader.readFlag();
        return new FrameHeader.FilmGrainParams(
                true,
                grainSeed,
                true,
                -1,
                yPoints,
                chromaScalingFromLuma,
                cbPoints,
                crPoints,
                scalingShift,
                arCoeffLag,
                arCoefficientsY,
                arCoefficientsCb,
                arCoefficientsCr,
                arCoeffShift,
                grainScaleShift,
                cbMult,
                cbLumaMult,
                cbOffset,
                crMult,
                crLumaMult,
                crOffset,
                overlapEnabled,
                clipToRestrictedRange
        );
    }

    /// Parses ordered film grain scaling points for one component.
    ///
    /// @param reader the payload bit reader
    /// @param pointCount the number of points to parse
    /// @param componentName the component name used in validation messages
    /// @return the parsed scaling points
    /// @throws IOException if the payload is truncated or invalid
    private static FrameHeader.FilmGrainPoint[] parseFilmGrainPoints(
            BitReader reader,
            int pointCount,
            String componentName
    ) throws IOException {
        FrameHeader.FilmGrainPoint[] points = new FrameHeader.FilmGrainPoint[pointCount];
        int previousValue = -1;
        for (int i = 0; i < pointCount; i++) {
            int value = readInt(reader, 8);
            int scaling = readInt(reader, 8);
            if (value <= previousValue) {
                fail(componentName + " film grain point values must increase strictly");
            }
            points[i] = new FrameHeader.FilmGrainPoint(value, scaling);
            previousValue = value;
        }
        return points;
    }

    /// Parses normalized film grain auto-regressive coefficients.
    ///
    /// @param reader the payload bit reader
    /// @param coefficientCount the number of coefficients to parse
    /// @return the normalized coefficients with the spec's `+128` offset removed
    /// @throws IOException if the payload is truncated
    private static int[] parseFilmGrainCoefficients(BitReader reader, int coefficientCount) throws IOException {
        int[] coefficients = new int[coefficientCount];
        for (int i = 0; i < coefficientCount; i++) {
            coefficients[i] = readInt(reader, 8) - 128;
        }
        return coefficients;
    }

    /// Returns whether the current frame references the supplied frame slot.
    ///
    /// @param referenceFrameIndices the decoded LAST..ALTREF slot indices
    /// @param referenceFrameIndex the frame slot to look for
    /// @return whether the current frame references the supplied frame slot
    private static boolean usesReferenceFrameIndex(int[] referenceFrameIndices, int referenceFrameIndex) {
        for (int decodedReferenceIndex : referenceFrameIndices) {
            if (decodedReferenceIndex == referenceFrameIndex) {
                return true;
            }
        }
        return false;
    }

    /// Reads the frame-level screen content tools flag.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @return whether screen content tools are enabled for the frame
    /// @throws IOException if the payload is truncated
    private static boolean readFrameScreenContentTools(BitReader reader, SequenceHeader sequenceHeader) throws IOException {
        return switch (sequenceHeader.features().screenContentTools()) {
            case OFF -> false;
            case ON -> true;
            case ADAPTIVE -> reader.readFlag();
        };
    }

    /// Reads the frame-level force-integer-motion-vectors flag.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @return whether integer motion vectors are forced for the frame
    /// @throws IOException if the payload is truncated
    private static boolean readFrameForceIntegerMotionVectors(BitReader reader, SequenceHeader sequenceHeader) throws IOException {
        return switch (sequenceHeader.features().forceIntegerMotionVectors()) {
            case OFF -> false;
            case ON -> true;
            case ADAPTIVE -> reader.readFlag();
        };
    }

    /// Parses the frame-level interpolation filter mode for inter prediction.
    ///
    /// @param reader the payload bit reader
    /// @return the parsed interpolation filter mode
    /// @throws IOException if the payload is truncated or invalid
    private static FrameHeader.InterpolationFilter parseInterpolationFilter(BitReader reader) throws IOException {
        if (reader.readFlag()) {
            return FrameHeader.InterpolationFilter.SWITCHABLE;
        }
        return switch (readInt(reader, 2)) {
            case 0 -> FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR;
            case 1 -> FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH;
            case 2 -> FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP;
            case 3 -> FrameHeader.InterpolationFilter.BILINEAR;
            default -> throw new IllegalStateException("Unexpected interpolation filter code");
        };
    }

    /// Derives skip-mode availability and reads the enabled flag when present.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param frameType the current frame type
    /// @param frameOffset the current frame order hint
    /// @param switchableCompoundReferences whether compound-reference mode is switchable
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceFrameIndices the decoded LAST..ALTREF slot indices
    /// @return the parsed skip-mode state
    /// @throws IOException if referenced headers are unavailable or the payload is truncated
    private static SkipModeResult deriveSkipMode(
            BitReader reader,
            SequenceHeader sequenceHeader,
            Av1FrameType frameType,
            int frameOffset,
            boolean switchableCompoundReferences,
            FrameHeader[] referenceFrameHeaders,
            int[] referenceFrameIndices
    ) throws IOException {
        if (!switchableCompoundReferences || !isInterOrSwitch(frameType) || !sequenceHeader.features().orderHint()) {
            return new SkipModeResult(false, false, new int[]{-1, -1});
        }

        int offBefore = -1;
        int offAfter = -1;
        int offBeforeIndex = -1;
        int offAfterIndex = -1;
        for (int i = 0; i < REFERENCES_PER_FRAME; i++) {
            int referenceSlot = referenceFrameIndices[i];
            if (referenceSlot < 0 || referenceSlot >= TOTAL_REFERENCE_FRAMES) {
                fail("Skip-mode derivation requires all reference-frame slots to be decoded");
            }
            FrameHeader referenceFrameHeader = referenceFrameHeaders[referenceSlot];
            if (referenceFrameHeader == null) {
                fail("Skip-mode derivation requires all referenced frame headers");
            }
            int referencePoc = referenceFrameHeader.frameOffset();
            int diff = getPocDiff(sequenceHeader.features().orderHintBits(), referencePoc, frameOffset);
            if (diff > 0) {
                if (offAfter < 0 || getPocDiff(sequenceHeader.features().orderHintBits(), offAfter, referencePoc) > 0) {
                    offAfter = referencePoc;
                    offAfterIndex = i;
                }
            } else if (diff < 0) {
                if (offBefore < 0 || getPocDiff(sequenceHeader.features().orderHintBits(), referencePoc, offBefore) > 0) {
                    offBefore = referencePoc;
                    offBeforeIndex = i;
                }
            }
        }

        int[] skipModeReferenceIndices = new int[]{-1, -1};
        boolean skipModeAllowed = false;
        if (offBefore >= 0 && offAfter >= 0) {
            skipModeReferenceIndices[0] = Math.min(offBeforeIndex, offAfterIndex);
            skipModeReferenceIndices[1] = Math.max(offBeforeIndex, offAfterIndex);
            skipModeAllowed = true;
        } else if (offBefore >= 0) {
            int offBefore2 = -1;
            int offBefore2Index = -1;
            for (int i = 0; i < REFERENCES_PER_FRAME; i++) {
                int referenceSlot = referenceFrameIndices[i];
                FrameHeader referenceFrameHeader = referenceFrameHeaders[referenceSlot];
                if (referenceFrameHeader == null) {
                    fail("Skip-mode derivation requires all referenced frame headers");
                }
                int referencePoc = referenceFrameHeader.frameOffset();
                if (getPocDiff(sequenceHeader.features().orderHintBits(), referencePoc, offBefore) < 0) {
                    if (offBefore2 < 0 || getPocDiff(sequenceHeader.features().orderHintBits(), referencePoc, offBefore2) > 0) {
                        offBefore2 = referencePoc;
                        offBefore2Index = i;
                    }
                }
            }
            if (offBefore2 >= 0) {
                skipModeReferenceIndices[0] = Math.min(offBeforeIndex, offBefore2Index);
                skipModeReferenceIndices[1] = Math.max(offBeforeIndex, offBefore2Index);
                skipModeAllowed = true;
            }
        }

        boolean skipModeEnabled = skipModeAllowed && reader.readFlag();
        return new SkipModeResult(skipModeAllowed, skipModeEnabled, skipModeReferenceIndices);
    }

    /// Returns the wrapped order-hint difference `poc0 - poc1`.
    ///
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param poc0 the minuend order hint
    /// @param poc1 the subtrahend order hint
    /// @return the wrapped order-hint difference `poc0 - poc1`
    private static int getPocDiff(int orderHintBits, int poc0, int poc1) {
        if (orderHintBits == 0) {
            return 0;
        }
        int mask = 1 << (orderHintBits - 1);
        int diff = poc0 - poc1;
        return (diff & (mask - 1)) - (diff & mask);
    }

    /// Maps an AV1 frame type code to the public enum.
    ///
    /// @param value the AV1 frame type code
    /// @return the mapped public enum
    /// @throws IOException if the frame type code is invalid
    private static Av1FrameType decodeFrameType(int value) throws IOException {
        return switch (value) {
            case 0 -> Av1FrameType.KEY;
            case 1 -> Av1FrameType.INTER;
            case 2 -> Av1FrameType.INTRA;
            case 3 -> Av1FrameType.SWITCH;
            default -> throw new IllegalStateException("Unexpected frame type code: " + value);
        };
    }

    /// Maps an AV1 loop restoration type code to the internal enum.
    ///
    /// @param value the AV1 loop restoration type code
    /// @return the mapped internal enum
    /// @throws IOException if the restoration type code is invalid
    private static FrameHeader.RestorationType decodeRestorationType(int value) throws IOException {
        return switch (value) {
            case 0 -> FrameHeader.RestorationType.NONE;
            case 1 -> FrameHeader.RestorationType.SWITCHABLE;
            case 2 -> FrameHeader.RestorationType.WIENER;
            case 3 -> FrameHeader.RestorationType.SELF_GUIDED;
            default -> throw new IllegalStateException("Unexpected restoration type code: " + value);
        };
    }

    /// Returns whether the frame type is inter or switch.
    ///
    /// @param frameType the AV1 frame type
    /// @return whether the frame type is inter or switch
    private static boolean isInterOrSwitch(Av1FrameType frameType) {
        return frameType == Av1FrameType.INTER || frameType == Av1FrameType.SWITCH;
    }

    /// Reads an AV1 uniform integer.
    ///
    /// @param reader the payload bit reader
    /// @param maxExclusive the exclusive upper bound
    /// @return the decoded uniform integer
    /// @throws IOException if the payload is truncated
    private static int readUniform(BitReader reader, int maxExclusive) throws IOException {
        if (maxExclusive <= 1) {
            return 0;
        }

        int bits = Integer.SIZE - Integer.numberOfLeadingZeros(maxExclusive);
        int m = (1 << bits) - maxExclusive;
        int value = readInt(reader, bits - 1);
        return value < m ? value : (value << 1) - m + readInt(reader, 1);
    }

    /// Computes the smallest `k` such that `size << k >= target`.
    ///
    /// @param size the tile unit size
    /// @param target the target size
    /// @return the smallest `k` such that `size << k >= target`
    private static int tileLog2(int size, int target) {
        int k = 0;
        while ((size << k) < target) {
            k++;
        }
        return k;
    }

    /// Reads and validates the trailing bits at the end of the payload.
    ///
    /// @param reader the payload bit reader
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @throws IOException if the payload is truncated or invalid
    private static void checkTrailingBits(BitReader reader, boolean strictStdCompliance) throws IOException {
        boolean trailingOneBit = reader.readFlag();
        if (!strictStdCompliance) {
            return;
        }
        if (!trailingOneBit) {
            fail("Trailing one bit was not present");
        }
        while (reader.bitsRemaining() > 0) {
            if (reader.readBit() != 0) {
                fail("Non-zero trailing padding bits are not allowed in strict mode");
            }
        }
    }

    /// Clips a value to the unsigned byte range.
    ///
    /// @param value the unclipped value
    /// @return the value clipped to the unsigned byte range
    private static int clipUnsignedByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /// Returns default per-segment data with `referenceFrame = -1`.
    ///
    /// @return default per-segment data
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[MAX_SEGMENTS];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }

    /// Reads a bounded unsigned integer and narrows it to `int`.
    ///
    /// @param reader the payload bit reader
    /// @param bitCount the number of bits to read
    /// @return the decoded integer value
    /// @throws IOException if the payload is truncated
    private static int readInt(BitReader reader, int bitCount) throws IOException {
        return Math.toIntExact(readLong(reader, bitCount));
    }

    /// Reads a bounded unsigned integer and returns it as `long`.
    ///
    /// @param reader the payload bit reader
    /// @param bitCount the number of bits to read
    /// @return the decoded integer value
    /// @throws IOException if the payload is truncated
    private static long readLong(BitReader reader, int bitCount) throws IOException {
        return reader.readBits(bitCount);
    }

    /// Throws an `IOException` with a validation message.
    ///
    /// @param message the validation message
    /// @throws IOException always
    private static void fail(String message) throws IOException {
        throw new IOException(message);
    }

    /// Parsed skip-mode availability and enabled state.
    ///
    /// @param allowed whether skip mode is permitted for the frame
    /// @param enabled whether skip mode is enabled for the frame
    /// @param referenceIndices the two skip-mode reference positions, or `-1` when unavailable
    
    private record SkipModeResult(
            boolean allowed,
            boolean enabled,
            int [] referenceIndices
    ) {
        /// Creates parsed skip-mode state with an independent reference-index array.
        private SkipModeResult {
            referenceIndices = Arrays.copyOf(referenceIndices, referenceIndices.length);
        }
    }

    /// Parsed frame size and super-resolution state.
    ///
    /// @param frameSize the parsed frame dimensions
    /// @param superResolution the parsed super-resolution settings
    
    private record FrameSizeResult(
            FrameHeader.FrameSize frameSize,
            FrameHeader.SuperResolutionInfo superResolution
    ) {
        /// Validates parsed frame size and super-resolution state.
        private FrameSizeResult {
            Objects.requireNonNull(frameSize, "frameSize");
            Objects.requireNonNull(superResolution, "superResolution");
        }
    }

    /// Derived per-segment lossless state.
    ///
    /// @param segmentation the segmentation state with derived qindex and lossless arrays
    /// @param allLossless whether all segments are lossless
    
    private record DerivedLosslessState(
            FrameHeader.SegmentationInfo segmentation,
            boolean allLossless
    ) {
        /// Validates derived per-segment lossless state.
        private DerivedLosslessState {
            Objects.requireNonNull(segmentation, "segmentation");
        }
    }
}
