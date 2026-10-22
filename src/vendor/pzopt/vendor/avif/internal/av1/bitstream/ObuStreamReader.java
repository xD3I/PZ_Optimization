// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.bitstream;

import pzopt.vendor.avif.av1.Av1DecodeErrorCode;
import pzopt.vendor.avif.av1.Av1DecodeException;
import pzopt.vendor.avif.av1.Av1DecodeStage;
import pzopt.vendor.avif.internal.io.BufferedInput;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Sequential OBU reader for raw AV1 low-overhead or Annex B bitstreams.

public final class ObuStreamReader {
    /// The default maximum encoded payload retained for one OBU.
    private static final long DEFAULT_PAYLOAD_SIZE_LIMIT = 256L * 1024L * 1024L;
    /// The largest OBU payload representable by the decoder's byte-array storage.
    private static final int MAX_PAYLOAD_SIZE = Integer.MAX_VALUE - 8;

    /// The forward-only buffered byte source.
    private final BufferedInput input;
    /// Whether the source uses Annex B temporal-unit and frame-unit framing.
    private final boolean annexB;
    /// The effective maximum encoded payload retained for one OBU.
    private final int maximumPayloadSize;
    /// The next unread byte offset in the source.
    private long streamOffset;
    /// The next unread OBU index in the source.
    private int obuIndex;
    /// Whether end-of-stream has already been observed.
    private boolean endOfStream;
    /// The bytes following the current Annex B frame unit within its temporal unit.
    private long temporalUnitRemaining;
    /// The unread bytes within the current Annex B frame unit.
    private long frameUnitRemaining;
    /// Whether at least one Annex B temporal unit has been opened.
    private boolean annexBStarted;

    /// Creates a sequential OBU reader.
    ///
    /// @param input the forward-only buffered byte source
    public ObuStreamReader(BufferedInput input) {
        this(input, false, DEFAULT_PAYLOAD_SIZE_LIMIT);
    }

    /// Creates a sequential OBU reader with an encoded-payload resource limit.
    ///
    /// @param input the forward-only buffered byte source
    /// @param payloadSizeLimit the maximum encoded payload retained for one OBU, or `0` to apply
    ///                         only the implementation limit
    public ObuStreamReader(BufferedInput input, long payloadSizeLimit) {
        this(input, false, payloadSizeLimit);
    }

    /// Creates a sequential reader for an Annex B AV1 bitstream.
    ///
    /// @param input the forward-only buffered byte source
    /// @return a reader that consumes Annex B temporal, frame, and OBU length fields
    public static ObuStreamReader forAnnexB(BufferedInput input) {
        return new ObuStreamReader(input, true, DEFAULT_PAYLOAD_SIZE_LIMIT);
    }

    /// Creates a sequential Annex B reader with an encoded-payload resource limit.
    ///
    /// @param input the forward-only buffered byte source
    /// @param payloadSizeLimit the maximum encoded payload retained for one OBU, or `0` to apply
    ///                         only the implementation limit
    /// @return a reader that consumes Annex B temporal, frame, and OBU length fields
    public static ObuStreamReader forAnnexB(BufferedInput input, long payloadSizeLimit) {
        return new ObuStreamReader(input, true, payloadSizeLimit);
    }

    /// Creates a sequential OBU reader for one framing mode.
    ///
    /// @param input the forward-only buffered byte source
    /// @param annexB whether the source uses Annex B framing
    /// @param payloadSizeLimit the maximum encoded payload retained for one OBU, or `0` to apply
    ///                         only the implementation limit
    private ObuStreamReader(BufferedInput input, boolean annexB, long payloadSizeLimit) {
        if (payloadSizeLimit < 0) {
            throw new IllegalArgumentException("payloadSizeLimit < 0: " + payloadSizeLimit);
        }
        this.input = Objects.requireNonNull(input, "input");
        this.annexB = annexB;
        this.maximumPayloadSize = (int) Math.min(
                payloadSizeLimit == 0 ? MAX_PAYLOAD_SIZE : payloadSizeLimit,
                MAX_PAYLOAD_SIZE
        );
    }

    /// Reads the next OBU packet from the source.
    ///
    /// @return the next OBU packet, or `null` at end-of-stream
    /// @throws IOException if the source is truncated or the OBU is malformed
    public ObuPacket readObu() throws IOException {
        return annexB ? readAnnexBObu() : readLowOverheadObu();
    }

    /// Returns whether the reader is positioned between two Annex B temporal units.
    ///
    /// The result becomes `true` after the final OBU of a temporal unit is returned and remains
    /// true until the next call to [#readObu()] starts another temporal unit. Low-overhead readers
    /// always return `false` because that framing has no external temporal-unit boundary.
    ///
    /// @return whether an Annex B temporal unit has just been completed
    public boolean atTemporalUnitBoundary() {
        return annexB && annexBStarted && temporalUnitRemaining == 0L && frameUnitRemaining == 0L;
    }

    /// Reads the next OBU from a low-overhead source.
    ///
    /// @return the next OBU packet, or `null` at end-of-stream
    /// @throws IOException if the source is truncated or malformed
    private ObuPacket readLowOverheadObu() throws IOException {
        if (endOfStream) {
            return null;
        }

        long obuOffset = streamOffset;
        int currentObuIndex = obuIndex;
        long unitRemainingAtObuStart = input.currentUnitRemaining();

        final int firstByte;
        try {
            firstByte = input.readUnsignedByte();
        } catch (EOFException ignored) {
            endOfStream = true;
            return null;
        }
        streamOffset++;

        return readObuPacket(firstByte, obuOffset, currentObuIndex, unitRemainingAtObuStart, false);
    }

    /// Reads the next OBU from an Annex B source.
    ///
    /// @return the next OBU packet, or `null` at end-of-stream
    /// @throws IOException if an external length field or enclosed OBU is malformed
    private ObuPacket readAnnexBObu() throws IOException {
        if (endOfStream) {
            return null;
        }

        while (frameUnitRemaining == 0L) {
            while (temporalUnitRemaining == 0L) {
                Leb128.ReadResult temporalUnitSize = readAnnexBLength(
                        true,
                        -1L,
                        "temporal unit"
                );
                if (temporalUnitSize == null) {
                    endOfStream = true;
                    return null;
                }
                annexBStarted = true;
                temporalUnitRemaining = temporalUnitSize.value();
            }

            Leb128.ReadResult frameUnitSize = Objects.requireNonNull(readAnnexBLength(
                    false,
                    temporalUnitRemaining,
                    "frame unit"
            ));
            temporalUnitRemaining -= frameUnitSize.byteCount();
            if (frameUnitSize.value() > temporalUnitRemaining) {
                throw invalidAnnexBFraming(
                        "Annex B frame unit exceeds its temporal unit: " + frameUnitSize.value()
                                + " > " + temporalUnitRemaining,
                        streamOffset - frameUnitSize.byteCount(),
                        null
                );
            }
            temporalUnitRemaining -= frameUnitSize.value();
            frameUnitRemaining = frameUnitSize.value();
        }

        Leb128.ReadResult obuSize = Objects.requireNonNull(readAnnexBLength(
                false,
                frameUnitRemaining,
                "OBU"
        ));
        frameUnitRemaining -= obuSize.byteCount();
        if (obuSize.value() == 0L) {
            throw invalidAnnexBFraming("Annex B OBU length must be positive", streamOffset - obuSize.byteCount(), null);
        }
        if (obuSize.value() > frameUnitRemaining) {
            throw invalidAnnexBFraming(
                    "Annex B OBU exceeds its frame unit: " + obuSize.value() + " > " + frameUnitRemaining,
                    streamOffset - obuSize.byteCount(),
                    null
            );
        }
        frameUnitRemaining -= obuSize.value();

        long obuOffset = streamOffset;
        int currentObuIndex = obuIndex;
        final int firstByte;
        try {
            firstByte = input.readUnsignedByte();
        } catch (EOFException exception) {
            throw unexpectedAnnexBEof("Unexpected end of Annex B OBU header", obuOffset, exception);
        }
        streamOffset++;
        return readObuPacket(firstByte, obuOffset, currentObuIndex, obuSize.value(), true);
    }

    /// Parses one OBU after its first header byte has been consumed.
    ///
    /// @param firstByte the first OBU header byte
    /// @param obuOffset the byte offset of that header byte
    /// @param currentObuIndex the zero-based OBU index
    /// @param unitRemainingAtObuStart the enclosing unit length starting at the header, or `-1`
    /// @param requireExactUnitSize whether the OBU must consume the complete enclosing unit
    /// @return the parsed OBU packet
    /// @throws IOException if the header, size, or payload is malformed
    private ObuPacket readObuPacket(
            int firstByte,
            long obuOffset,
            int currentObuIndex,
            long unitRemainingAtObuStart,
            boolean requireExactUnitSize
    ) throws IOException {

        if ((firstByte & 0x80) != 0) {
            throw invalidHeader("OBU forbidden bit must be zero", obuOffset, currentObuIndex);
        }

        int typeId = (firstByte >>> 3) & 0x0F;
        boolean extensionFlag = ((firstByte >>> 2) & 1) != 0;
        boolean hasSizeField = ((firstByte >>> 1) & 1) != 0;
        boolean reservedBit = (firstByte & 1) != 0;

        if (reservedBit) {
            throw invalidHeader("OBU reserved bit must be zero", obuOffset, currentObuIndex);
        }

        ObuType type = ObuType.fromId(typeId);
        if (type == null) {
            throw invalidHeader("Unsupported OBU type: " + typeId, obuOffset, currentObuIndex);
        }

        int temporalId = 0;
        int spatialId = 0;
        if (extensionFlag) {
            int extensionByte = readUnsignedByte(Av1DecodeErrorCode.UNEXPECTED_EOF, "Unexpected end of OBU extension", obuOffset, currentObuIndex);
            streamOffset++;
            temporalId = (extensionByte >>> 5) & 0x07;
            spatialId = (extensionByte >>> 3) & 0x03;
            if ((extensionByte & 0x07) != 0) {
                throw invalidHeader("OBU extension reserved bits must be zero", obuOffset, currentObuIndex);
            }
        }

        long payloadSize = -1L;
        if (hasSizeField) {
            Leb128.ReadResult sizeResult;
            try {
                sizeResult = Leb128.readUnsigned(input, 8);
            } catch (EOFException ex) {
                throw new Av1DecodeException(
                        Av1DecodeErrorCode.UNEXPECTED_EOF,
                        Av1DecodeStage.OBU_READ,
                        "Unexpected end of OBU size field",
                        obuOffset,
                        currentObuIndex,
                        null,
                        ex
                );
            } catch (IOException ex) {
                throw new Av1DecodeException(
                        Av1DecodeErrorCode.INVALID_LEB128,
                        Av1DecodeStage.OBU_READ,
                        ex.getMessage(),
                        obuOffset,
                        currentObuIndex,
                        null,
                        ex
                );
            }
            streamOffset += sizeResult.byteCount();
            payloadSize = sizeResult.value();
        }

        long headerSize = streamOffset - obuOffset;
        if (unitRemainingAtObuStart >= 0L && headerSize > unitRemainingAtObuStart) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.UNEXPECTED_EOF,
                    Av1DecodeStage.OBU_READ,
                    "OBU header exceeds its externally bounded input unit",
                    obuOffset,
                    currentObuIndex,
                    null
            );
        }
        byte[] unboundedPayload = null;
        if (!hasSizeField) {
            if (unitRemainingAtObuStart < 0L) {
                unboundedPayload = readPayloadToEnd(obuOffset, currentObuIndex);
                payloadSize = unboundedPayload.length;
                endOfStream = true;
            } else {
                payloadSize = unitRemainingAtObuStart - headerSize;
            }
        } else if (unitRemainingAtObuStart >= 0L
                && payloadSize > unitRemainingAtObuStart - headerSize) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.UNEXPECTED_EOF,
                    Av1DecodeStage.OBU_READ,
                    "OBU payload exceeds its externally bounded input unit",
                    obuOffset,
                    currentObuIndex,
                    null
            );
        }
        if (requireExactUnitSize && payloadSize != unitRemainingAtObuStart - headerSize) {
            throw invalidAnnexBFraming(
                    "Annex B OBU length does not match its header and payload: expected "
                            + unitRemainingAtObuStart + " bytes but parsed " + (headerSize + payloadSize),
                    obuOffset,
                    null
            );
        }
        if (payloadSize > maximumPayloadSize) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.OBU_PAYLOAD_SIZE_LIMIT_EXCEEDED,
                    Av1DecodeStage.OBU_READ,
                    "OBU payload exceeds the configured or implementation size limit: "
                            + payloadSize + " > " + maximumPayloadSize,
                    obuOffset,
                    currentObuIndex,
                    null
            );
        }

        byte[] payload;
        if (unboundedPayload != null) {
            payload = unboundedPayload;
        } else {
            try {
                payload = input.readByteArray((int) payloadSize);
            } catch (EOFException ex) {
                throw new Av1DecodeException(
                        Av1DecodeErrorCode.UNEXPECTED_EOF,
                        Av1DecodeStage.OBU_READ,
                        "Unexpected end of OBU payload",
                        obuOffset,
                        currentObuIndex,
                        null,
                        ex
                );
            }
        }
        streamOffset += payloadSize;
        obuIndex++;

        return new ObuPacket(
                new ObuHeader(type, extensionFlag, hasSizeField, temporalId, spatialId),
                payload,
                obuOffset,
                currentObuIndex
        );
    }

    /// Reads one Annex B unsigned length field and accounts for its bytes in the stream offset.
    ///
    /// Padded encodings are accepted because the AV1 syntax permits any encoding of up to eight
    /// bytes whose decoded value fits the unsigned 32-bit range.
    ///
    /// @param allowEndOfStream whether EOF before the first byte denotes normal stream completion
    /// @param enclosingRemaining the unread enclosing-unit size, or `-1` when unbounded
    /// @param description the length-field subject used in diagnostics
    /// @return the decoded length, or `null` for permitted clean EOF
    /// @throws IOException if the length is truncated, too long, out of range, or crosses its unit
    private Leb128.ReadResult readAnnexBLength(
            boolean allowEndOfStream,
            long enclosingRemaining,
            String description
    ) throws IOException {
        long lengthOffset = streamOffset;
        long value = 0L;
        int shift = 0;
        for (int byteCount = 1; byteCount <= 8; byteCount++) {
            if (enclosingRemaining >= 0L && byteCount > enclosingRemaining) {
                throw invalidAnnexBFraming(
                        "Annex B " + description + " length field exceeds its enclosing unit",
                        lengthOffset,
                        null
                );
            }

            final int current;
            try {
                current = input.readUnsignedByte();
            } catch (EOFException exception) {
                if (allowEndOfStream && byteCount == 1) {
                    return null;
                }
                throw unexpectedAnnexBEof(
                        "Unexpected end of Annex B " + description + " length field",
                        lengthOffset,
                        exception
                );
            }
            streamOffset++;
            value |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                if (value > 0xFFFF_FFFFL) {
                    throw invalidAnnexBLeb128(
                            "Annex B " + description + " length exceeds the unsigned 32-bit range: " + value,
                            lengthOffset,
                            null
                    );
                }
                return new Leb128.ReadResult(value, byteCount);
            }
            shift += 7;
        }
        throw invalidAnnexBLeb128(
                "Annex B " + description + " length exceeds eight bytes",
                lengthOffset,
                null
        );
    }

    /// Reads a size-less final OBU payload until the backing input reaches EOF.
    ///
    /// @param obuOffset the OBU header offset
    /// @param currentObuIndex the OBU index
    /// @return the payload bytes consumed before EOF
    /// @throws IOException if the input fails before reaching EOF or the payload is too large
    private byte[] readPayloadToEnd(long obuOffset, int currentObuIndex) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        while (true) {
            final int value;
            try {
                value = input.readUnsignedByte();
            } catch (EOFException ignored) {
                return payload.toByteArray();
            }
            if (payload.size() == maximumPayloadSize) {
                throw new Av1DecodeException(
                        Av1DecodeErrorCode.OBU_PAYLOAD_SIZE_LIMIT_EXCEEDED,
                        Av1DecodeStage.OBU_READ,
                        "OBU payload exceeds the configured or implementation size limit: "
                                + (maximumPayloadSize + 1L) + " > " + maximumPayloadSize,
                        obuOffset,
                        currentObuIndex,
                        null
                );
            }
            payload.write(value);
        }
    }

    /// Reads the next unsigned byte or throws a contextual decode exception.
    ///
    /// @param errorCode the error code to use if EOF is encountered
    /// @param message the contextual error message
    /// @param obuOffset the OBU header offset
    /// @param currentObuIndex the OBU index
    /// @return the next unsigned byte
    /// @throws IOException if the source is truncated or unreadable
    private int readUnsignedByte(
            Av1DecodeErrorCode errorCode,
            String message,
            long obuOffset,
            int currentObuIndex
    ) throws IOException {
        try {
            return input.readUnsignedByte();
        } catch (EOFException ex) {
            throw new Av1DecodeException(
                    errorCode,
                    Av1DecodeStage.OBU_READ,
                    message,
                    obuOffset,
                    currentObuIndex,
                    null,
                    ex
            );
        }
    }

    /// Creates a contextual exception for malformed Annex B unit nesting or lengths.
    ///
    /// @param message the detailed error message
    /// @param offset the byte offset of the failing external length or enclosed OBU
    /// @param cause the underlying I/O failure, or `null`
    /// @return the contextual invalid-bitstream exception
    private Av1DecodeException invalidAnnexBFraming(String message, long offset, Throwable cause) {
        return new Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.OBU_READ,
                message,
                offset,
                obuIndex,
                null,
                cause
        );
    }

    /// Creates a contextual exception for an invalid Annex B LEB128 length field.
    ///
    /// @param message the detailed error message
    /// @param offset the byte offset of the failing length field
    /// @param cause the underlying I/O failure, or `null`
    /// @return the contextual invalid-LEB128 exception
    private Av1DecodeException invalidAnnexBLeb128(String message, long offset, Throwable cause) {
        return new Av1DecodeException(
                Av1DecodeErrorCode.INVALID_LEB128,
                Av1DecodeStage.OBU_READ,
                message,
                offset,
                obuIndex,
                null,
                cause
        );
    }

    /// Creates a contextual exception for truncated Annex B framing.
    ///
    /// @param message the detailed error message
    /// @param offset the byte offset where the truncated structure started
    /// @param cause the underlying end-of-input exception
    /// @return the contextual unexpected-EOF exception
    private Av1DecodeException unexpectedAnnexBEof(String message, long offset, EOFException cause) {
        return new Av1DecodeException(
                Av1DecodeErrorCode.UNEXPECTED_EOF,
                Av1DecodeStage.OBU_READ,
                message,
                offset,
                obuIndex,
                null,
                cause
        );
    }

    /// Creates a contextual invalid-header exception.
    ///
    /// @param message the detailed error message
    /// @param obuOffset the OBU header offset
    /// @param currentObuIndex the OBU index
    /// @return the contextual invalid-header exception
    private static Av1DecodeException invalidHeader(String message, long obuOffset, int currentObuIndex) {
        return new Av1DecodeException(
                Av1DecodeErrorCode.INVALID_OBU_HEADER,
                Av1DecodeStage.OBU_READ,
                message,
                obuOffset,
                currentObuIndex,
                null
        );
    }
}
