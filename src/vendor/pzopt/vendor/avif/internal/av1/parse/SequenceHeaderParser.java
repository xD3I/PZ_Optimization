// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.parse;

import pzopt.vendor.avif.av1.Av1DecodeErrorCode;
import pzopt.vendor.avif.av1.Av1DecodeException;
import pzopt.vendor.avif.av1.Av1DecodeStage;
import pzopt.vendor.avif.av1.Av1ColorConfig;
import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.internal.av1.bitstream.BitReader;
import pzopt.vendor.avif.internal.av1.bitstream.ObuPacket;
import pzopt.vendor.avif.internal.av1.bitstream.ObuType;
import pzopt.vendor.avif.internal.av1.model.SequenceHeader;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Parser for AV1 sequence header OBUs.

public final class SequenceHeaderParser {
    /// Prevents instantiation of this stateless parser.
    private SequenceHeaderParser() {
    }

    /// The AV1 `BT.709` color primaries code.
    private static final int COLOR_PRI_BT709 = 1;
    /// The AV1 `unknown` color primaries code.
    private static final int COLOR_PRI_UNKNOWN = 2;
    /// The AV1 `unknown` transfer characteristics code.
    private static final int TRANSFER_UNKNOWN = 2;
    /// The AV1 `sRGB` transfer characteristics code.
    private static final int TRANSFER_SRGB = 13;
    /// The AV1 `identity` matrix coefficients code.
    private static final int MATRIX_IDENTITY = 0;
    /// The AV1 `unknown` matrix coefficients code.
    private static final int MATRIX_UNKNOWN = 2;
    /// The AV1 `unknown` chroma sample position code.
    private static final int CHROMA_UNKNOWN = 0;

    /// Parses an AV1 sequence header OBU payload.
    ///
    /// @param obu the sequence header OBU packet
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @return the parsed sequence header
    /// @throws IOException if the OBU is truncated, unreadable, or invalid
    public static SequenceHeader parse(ObuPacket obu, boolean strictStdCompliance) throws IOException {
        Objects.requireNonNull(obu, "obu");
        if (obu.header().type() != ObuType.SEQUENCE_HEADER) {
            throw new IllegalArgumentException("OBU type is not a sequence header: " + obu.header().type());
        }

        BitReader reader = new BitReader(obu.payload());

        try {
            SequenceHeader header = parse(reader, strictStdCompliance);
            checkTrailingBits(reader, strictStdCompliance);
            return header;
        } catch (EOFException ex) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.UNEXPECTED_EOF,
                    Av1DecodeStage.SEQUENCE_HEADER_PARSE,
                    "Unexpected end of sequence header OBU",
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
                    Av1DecodeStage.SEQUENCE_HEADER_PARSE,
                    ex.getMessage(),
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        }
    }

    /// Parses the sequence header payload.
    ///
    /// @param reader the payload bit reader
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @return the parsed sequence header
    /// @throws IOException if the payload is truncated or invalid
    private static SequenceHeader parse(BitReader reader, boolean strictStdCompliance) throws IOException {
        int profile = readInt(reader, 3);
        if (profile > 2) {
            fail("Sequence header profile out of range: " + profile);
        }

        boolean stillPicture = reader.readFlag();
        boolean reducedStillPictureHeader = reader.readFlag();
        if (reducedStillPictureHeader && !stillPicture) {
            fail("Reduced still-picture header requires still_picture to be set");
        }

        SequenceHeader.TimingInfo timingInfo;
        SequenceHeader.OperatingPoint[] operatingPoints;

        if (reducedStillPictureHeader) {
            operatingPoints = new SequenceHeader.OperatingPoint[]{
                    new SequenceHeader.OperatingPoint(
                            readInt(reader, 3),
                            readInt(reader, 2),
                            10,
                            0,
                            false,
                            false,
                            false,
                            null
                    )
            };
            timingInfo = new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false);
        } else {
            boolean timingInfoPresent = reader.readFlag();
            long numUnitsInTick = 0;
            long timeScale = 0;
            boolean equalPictureInterval = false;
            long numTicksPerPicture = 0;
            boolean decoderModelInfoPresent = false;
            int encoderDecoderBufferDelayLength = 0;
            long numUnitsInDecodingTick = 0;
            int bufferRemovalDelayLength = 0;
            int framePresentationDelayLength = 0;

            if (timingInfoPresent) {
                numUnitsInTick = readLong(reader, 32);
                timeScale = readLong(reader, 32);
                if (strictStdCompliance && (numUnitsInTick == 0 || timeScale == 0)) {
                    fail("Timing info contains zero-valued required fields");
                }

                equalPictureInterval = reader.readFlag();
                if (equalPictureInterval) {
                    numTicksPerPicture = reader.readUvlc() + 1;
                }

                decoderModelInfoPresent = reader.readFlag();
                if (decoderModelInfoPresent) {
                    encoderDecoderBufferDelayLength = readInt(reader, 5) + 1;
                    numUnitsInDecodingTick = readLong(reader, 32);
                    if (strictStdCompliance && numUnitsInDecodingTick == 0) {
                        fail("Decoder model info contains a zero num_units_in_decoding_tick");
                    }
                    bufferRemovalDelayLength = readInt(reader, 5) + 1;
                    framePresentationDelayLength = readInt(reader, 5) + 1;
                }
            }

            boolean displayModelInfoPresent = reader.readFlag();
            timingInfo = new SequenceHeader.TimingInfo(
                    timingInfoPresent,
                    numUnitsInTick,
                    timeScale,
                    equalPictureInterval,
                    numTicksPerPicture,
                    decoderModelInfoPresent,
                    encoderDecoderBufferDelayLength,
                    numUnitsInDecodingTick,
                    bufferRemovalDelayLength,
                    framePresentationDelayLength,
                    displayModelInfoPresent
            );

            int operatingPointCount = readInt(reader, 5) + 1;
            operatingPoints = new SequenceHeader.OperatingPoint[operatingPointCount];
            for (int i = 0; i < operatingPointCount; i++) {
                int idc = readInt(reader, 12);
                if (idc != 0 && (((idc & 0xFF) == 0) || ((idc & 0xF00) == 0))) {
                    fail("Operating point IDC must reference both temporal and spatial layers when non-zero");
                }

                int majorLevel = 2 + readInt(reader, 3);
                int minorLevel = readInt(reader, 2);
                boolean tier = false;
                if (majorLevel > 3) {
                    tier = reader.readFlag();
                }

                boolean decoderModelParamPresent = false;
                SequenceHeader.OperatingParameterInfo parameterInfo = null;
                if (timingInfo.decoderModelInfoPresent()) {
                    decoderModelParamPresent = reader.readFlag();
                    if (decoderModelParamPresent) {
                        parameterInfo = new SequenceHeader.OperatingParameterInfo(
                                readLong(reader, timingInfo.encoderDecoderBufferDelayLength()),
                                readLong(reader, timingInfo.encoderDecoderBufferDelayLength()),
                                reader.readFlag()
                        );
                    }
                }

                boolean displayModelParamPresent = false;
                if (timingInfo.displayModelInfoPresent()) {
                    displayModelParamPresent = reader.readFlag();
                }
                int initialDisplayDelay = displayModelParamPresent ? readInt(reader, 4) + 1 : 10;

                operatingPoints[i] = new SequenceHeader.OperatingPoint(
                        majorLevel,
                        minorLevel,
                        initialDisplayDelay,
                        idc,
                        tier,
                        decoderModelParamPresent,
                        displayModelParamPresent,
                        parameterInfo
                );
            }
        }

        int widthBits = readInt(reader, 4) + 1;
        int heightBits = readInt(reader, 4) + 1;
        int maxWidth = readInt(reader, widthBits) + 1;
        int maxHeight = readInt(reader, heightBits) + 1;

        boolean frameIdNumbersPresent = false;
        int deltaFrameIdBits = 0;
        int frameIdBits = 0;
        if (!reducedStillPictureHeader) {
            frameIdNumbersPresent = reader.readFlag();
            if (frameIdNumbersPresent) {
                deltaFrameIdBits = readInt(reader, 4) + 2;
                frameIdBits = readInt(reader, 3) + deltaFrameIdBits + 1;
            }
        }

        boolean use128x128Superblocks = reader.readFlag();
        boolean filterIntra = reader.readFlag();
        boolean intraEdgeFilter = reader.readFlag();
        boolean interIntra = false;
        boolean maskedCompound = false;
        boolean warpedMotion = false;
        boolean dualFilter = false;
        boolean orderHint = false;
        boolean jointCompound = false;
        boolean refFrameMotionVectors = false;
        SequenceHeader.AdaptiveBoolean screenContentTools;
        SequenceHeader.AdaptiveBoolean forceIntegerMotionVectors;
        int orderHintBits = 0;

        if (reducedStillPictureHeader) {
            screenContentTools = SequenceHeader.AdaptiveBoolean.ADAPTIVE;
            forceIntegerMotionVectors = SequenceHeader.AdaptiveBoolean.ADAPTIVE;
        } else {
            interIntra = reader.readFlag();
            maskedCompound = reader.readFlag();
            warpedMotion = reader.readFlag();
            dualFilter = reader.readFlag();
            orderHint = reader.readFlag();
            if (orderHint) {
                jointCompound = reader.readFlag();
                refFrameMotionVectors = reader.readFlag();
            }
            screenContentTools = readAdaptiveBoolean(reader);
            if (screenContentTools != SequenceHeader.AdaptiveBoolean.OFF) {
                forceIntegerMotionVectors = readAdaptiveBoolean(reader);
            } else {
                forceIntegerMotionVectors = SequenceHeader.AdaptiveBoolean.ADAPTIVE;
            }
            if (orderHint) {
                orderHintBits = readInt(reader, 3) + 1;
            }
        }

        boolean superResolution = reader.readFlag();
        boolean cdef = reader.readFlag();
        boolean restoration = reader.readFlag();

        int bitDepth = reader.readFlag() ? 10 : 8;
        if (profile == 2 && bitDepth == 10 && reader.readFlag()) {
            bitDepth = 12;
        }

        boolean monochrome = false;
        if (profile != 1) {
            monochrome = reader.readFlag();
        }

        boolean colorDescriptionPresent = reader.readFlag();
        int colorPrimaries = COLOR_PRI_UNKNOWN;
        int transferCharacteristics = TRANSFER_UNKNOWN;
        int matrixCoefficients = MATRIX_UNKNOWN;
        if (colorDescriptionPresent) {
            colorPrimaries = readInt(reader, 8);
            transferCharacteristics = readInt(reader, 8);
            matrixCoefficients = readInt(reader, 8);
        }

        boolean colorRange;
        Av1ChromaFormat chromaFormat;
        int chromaSamplePosition;
        boolean chromaSubsamplingX = false;
        boolean chromaSubsamplingY = false;
        boolean separateUvDeltaQ = false;

        if (monochrome) {
            colorRange = reader.readFlag();
            chromaFormat = Av1ChromaFormat.MONOCHROME;
            chromaSubsamplingX = true;
            chromaSubsamplingY = true;
            chromaSamplePosition = CHROMA_UNKNOWN;
        } else if (colorPrimaries == COLOR_PRI_BT709
                && transferCharacteristics == TRANSFER_SRGB
                && matrixCoefficients == MATRIX_IDENTITY) {
            chromaFormat = Av1ChromaFormat.YUV444;
            colorRange = true;
            if (profile != 1 && !(profile == 2 && bitDepth == 12)) {
                fail("Identity matrix RGB signaling is only valid for profile 1 or profile 2 12-bit streams");
            }
            chromaSamplePosition = CHROMA_UNKNOWN;
        } else {
            colorRange = reader.readFlag();
            switch (profile) {
                case 0 -> {
                    chromaFormat = Av1ChromaFormat.YUV420;
                    chromaSubsamplingX = true;
                    chromaSubsamplingY = true;
                }
                case 1 -> chromaFormat = Av1ChromaFormat.YUV444;
                case 2 -> {
                    if (bitDepth == 12) {
                        chromaSubsamplingX = reader.readFlag();
                        if (chromaSubsamplingX) {
                            chromaSubsamplingY = reader.readFlag();
                        }
                    } else {
                        chromaSubsamplingX = true;
                    }
                    chromaFormat = chromaSubsamplingX
                            ? (chromaSubsamplingY ? Av1ChromaFormat.YUV420 : Av1ChromaFormat.YUV422)
                            : Av1ChromaFormat.YUV444;
                }
                default -> throw new IllegalStateException("Unexpected profile: " + profile);
            }
            chromaSamplePosition = chromaSubsamplingX && chromaSubsamplingY ? readInt(reader, 2) : CHROMA_UNKNOWN;
        }

        if (strictStdCompliance && matrixCoefficients == MATRIX_IDENTITY && chromaFormat != Av1ChromaFormat.YUV444) {
            fail("Identity matrix coefficients require YUV444 chroma in strict mode");
        }

        if (!monochrome) {
            separateUvDeltaQ = reader.readFlag();
        }

        boolean filmGrainPresent = reader.readFlag();

        return new SequenceHeader(
                profile,
                maxWidth,
                maxHeight,
                timingInfo,
                operatingPoints,
                stillPicture,
                reducedStillPictureHeader,
                widthBits,
                heightBits,
                frameIdNumbersPresent,
                deltaFrameIdBits,
                frameIdBits,
                new SequenceHeader.FeatureConfig(
                        use128x128Superblocks,
                        filterIntra,
                        intraEdgeFilter,
                        interIntra,
                        maskedCompound,
                        warpedMotion,
                        dualFilter,
                        orderHint,
                        jointCompound,
                        refFrameMotionVectors,
                        screenContentTools,
                        forceIntegerMotionVectors,
                        orderHintBits,
                        superResolution,
                        cdef,
                        restoration,
                        filmGrainPresent
                ),
                new Av1ColorConfig(
                        bitDepth,
                        monochrome,
                        colorDescriptionPresent,
                        colorPrimaries,
                        transferCharacteristics,
                        matrixCoefficients,
                        colorRange,
                        chromaFormat,
                        chromaSamplePosition,
                        chromaSubsamplingX,
                        chromaSubsamplingY,
                        separateUvDeltaQ
                )
        );
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

    /// Reads an AV1 adaptive boolean syntax element.
    ///
    /// @param reader the payload bit reader
    /// @return the decoded adaptive boolean
    /// @throws IOException if the payload is truncated
    private static SequenceHeader.AdaptiveBoolean readAdaptiveBoolean(BitReader reader) throws IOException {
        if (reader.readFlag()) {
            return SequenceHeader.AdaptiveBoolean.ADAPTIVE;
        }
        return reader.readFlag() ? SequenceHeader.AdaptiveBoolean.ON : SequenceHeader.AdaptiveBoolean.OFF;
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
}
