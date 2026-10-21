// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.bmff;

import pzopt.vendor.avif.AvifDecodeException;
import pzopt.vendor.avif.AvifErrorCode;
import pzopt.vendor.avif.internal.io.AvifDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Bounded big-endian byte reader for BMFF boxes.

public final class BoxInput {
    /// The retained positional source.
    private final AvifDataSource source;
    /// The inclusive lower bound for this view.
    private final int start;
    /// The exclusive upper bound for this view.
    private final int end;
    /// The current absolute read offset.
    private int offset;

    /// Creates a bounded input over a byte array.
    ///
    /// The input retains the array without copying it. The caller must not modify the array while
    /// this input or one of its slices is in use.
    ///
    /// @param source the complete source bytes
    public BoxInput(byte[] source) {
        this(AvifDataSource.ofBytes(Objects.requireNonNull(source, "source")));
    }

    /// Creates a bounded input over a retained positional source.
    ///
    /// @param source the retained positional source
    public BoxInput(AvifDataSource source) {
        this(source, 0, checkedSourceSize(source));
    }

    /// Creates a bounded input over one source slice.
    ///
    /// @param source the retained positional source
    /// @param start the inclusive lower bound
    /// @param end the exclusive upper bound
    private BoxInput(AvifDataSource source, int start, int end) {
        this.source = Objects.requireNonNull(source, "source");
        if (start < 0 || end < start || end > source.limit()) {
            throw new IllegalArgumentException("invalid input bounds: " + start + ".." + end);
        }
        this.start = start;
        this.end = end;
        this.offset = start;
    }

    /// Returns the current absolute read offset.
    ///
    /// @return the current absolute read offset
    public int offset() {
        return offset;
    }

    /// Returns the exclusive upper bound for this view.
    ///
    /// @return the exclusive upper bound for this view
    public int end() {
        return end;
    }

    /// Returns the remaining byte count.
    ///
    /// @return the remaining byte count
    public int remaining() {
        return end - offset;
    }

    /// Returns whether unread bytes remain.
    ///
    /// @return whether unread bytes remain
    public boolean hasRemaining() {
        return offset < end;
    }

    /// Reads one unsigned 8-bit integer.
    ///
    /// @return one unsigned 8-bit integer
    /// @throws AvifDecodeException if the input is truncated
    public int readU8() throws AvifDecodeException {
        ensureAvailable(1);
        return Byte.toUnsignedInt(readSourceByte(offset++));
    }

    /// Reads one signed 8-bit integer.
    ///
    /// @return one signed 8-bit integer
    /// @throws AvifDecodeException if the input is truncated
    public int readI8() throws AvifDecodeException {
        return (byte) readU8();
    }

    /// Reads one unsigned 16-bit big-endian integer.
    ///
    /// @return one unsigned 16-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public int readU16() throws AvifDecodeException {
        ensureAvailable(2);
        int value = (Byte.toUnsignedInt(readSourceByte(offset)) << 8)
                | Byte.toUnsignedInt(readSourceByte(offset + 1));
        offset += 2;
        return value;
    }

    /// Reads one signed 16-bit big-endian integer.
    ///
    /// @return one signed 16-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public int readI16() throws AvifDecodeException {
        return (short) readU16();
    }

    /// Reads one unsigned 24-bit big-endian integer.
    ///
    /// @return one unsigned 24-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public int readU24() throws AvifDecodeException {
        ensureAvailable(3);
        int value = (Byte.toUnsignedInt(readSourceByte(offset)) << 16)
                | (Byte.toUnsignedInt(readSourceByte(offset + 1)) << 8)
                | Byte.toUnsignedInt(readSourceByte(offset + 2));
        offset += 3;
        return value;
    }

    /// Reads one unsigned 32-bit big-endian integer.
    ///
    /// @return one unsigned 32-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public long readU32() throws AvifDecodeException {
        ensureAvailable(4);
        long value = ((long) Byte.toUnsignedInt(readSourceByte(offset)) << 24)
                | ((long) Byte.toUnsignedInt(readSourceByte(offset + 1)) << 16)
                | ((long) Byte.toUnsignedInt(readSourceByte(offset + 2)) << 8)
                | Byte.toUnsignedInt(readSourceByte(offset + 3));
        offset += 4;
        return value;
    }

    /// Reads one signed 32-bit big-endian integer.
    ///
    /// @return one signed 32-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public int readI32() throws AvifDecodeException {
        return (int) readU32();
    }

    /// Reads one unsigned 64-bit big-endian integer that fits in `long`.
    ///
    /// @return one unsigned 64-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated or the value exceeds `Long.MAX_VALUE`
    public long readU64() throws AvifDecodeException {
        ensureAvailable(8);
        long high = readU32();
        long low = readU32();
        if ((high & 0x8000_0000L) != 0) {
            throw parseFailed("64-bit box value exceeds supported range", offset - 8);
        }
        return (high << 32) | low;
    }

    /// Reads one signed 64-bit big-endian integer.
    ///
    /// @return one signed 64-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public long readI64() throws AvifDecodeException {
        long high = readU32();
        long low = readU32();
        return (high << 32) | low;
    }

    /// Reads one fixed-length four-character code.
    ///
    /// @return one fixed-length four-character code
    /// @throws AvifDecodeException if the input is truncated
    public String readFourCc() throws AvifDecodeException {
        return new String(readBytes(4), StandardCharsets.ISO_8859_1);
    }

    /// Reads one byte array.
    ///
    /// @param length the byte count to read
    /// @return one byte array
    /// @throws AvifDecodeException if the input is truncated
    public byte[] readBytes(int length) throws AvifDecodeException {
        if (length < 0) {
            throw new IllegalArgumentException("length < 0: " + length);
        }
        ensureAvailable(length);
        byte[] result;
        try {
            result = source.readBytes(offset, length);
        } catch (IOException exception) {
            throw readFailed(exception, offset);
        }
        offset += length;
        return result;
    }

    /// Skips bytes.
    ///
    /// @param length the byte count to skip
    /// @throws AvifDecodeException if the input is truncated
    public void skip(int length) throws AvifDecodeException {
        if (length < 0) {
            throw new IllegalArgumentException("length < 0: " + length);
        }
        ensureAvailable(length);
        offset += length;
    }

    /// Creates a child input over one absolute byte range.
    ///
    /// @param absoluteStart the absolute child start offset
    /// @param size the child byte size
    /// @return a child input over one absolute byte range
    /// @throws AvifDecodeException if the range is outside this view
    public BoxInput slice(int absoluteStart, int size) throws AvifDecodeException {
        if (size < 0 || absoluteStart < start || absoluteStart > end || size > end - absoluteStart) {
            throw truncated("child box exceeds parent bounds", absoluteStart);
        }
        return new BoxInput(source, absoluteStart, absoluteStart + size);
    }

    /// Returns the supported integer size of a complete source.
    ///
    /// @param source the source to inspect
    /// @return the source-position limit as an integer
    /// @throws IllegalArgumentException if the source exceeds the parser's integer offset range
    private static int checkedSourceSize(AvifDataSource source) {
        long size = Objects.requireNonNull(source, "source").limit();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("source exceeds supported size: " + size);
        }
        return (int) size;
    }

    /// Reads one source byte and translates I/O failures to a container parse failure.
    ///
    /// @param absoluteOffset the absolute source offset
    /// @return the requested byte
    /// @throws AvifDecodeException if the source cannot be read
    private byte readSourceByte(int absoluteOffset) throws AvifDecodeException {
        try {
            return source.readByte(absoluteOffset);
        } catch (IOException exception) {
            throw readFailed(exception, absoluteOffset);
        }
    }

    /// Creates a container parse failure for an underlying positional-read failure.
    ///
    /// @param cause the source read failure
    /// @param absoluteOffset the attempted absolute source offset
    /// @return the translated decode exception
    private static AvifDecodeException readFailed(IOException cause, int absoluteOffset) {
        if (cause instanceof AvifDecodeException decodeException) {
            return decodeException;
        }
        return new AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "Cannot read AVIF container data: " + cause.getMessage(),
                (long) absoluteOffset,
                cause
        );
    }

    /// Reads the next BMFF box header.
    ///
    /// @return the next BMFF box header
    /// @throws AvifDecodeException if the header is malformed or truncated
    public BoxHeader readBoxHeader() throws AvifDecodeException {
        int boxOffset = offset;
        ensureAvailable(8);
        long smallSize = readU32();
        String type = readFourCc();

        long boxSize = smallSize;
        boolean sizeZero = false;
        if (smallSize == 1) {
            boxSize = readU64();
        } else if (smallSize == 0) {
            sizeZero = true;
            boxSize = end - boxOffset;
        }

        int headerSize = offset - boxOffset;
        if (boxSize < headerSize || boxSize > Integer.MAX_VALUE) {
            throw parseFailed("invalid BMFF box size for " + type + ": " + boxSize, boxOffset);
        }
        int payloadSize = (int) boxSize - headerSize;
        if (payloadSize > end - offset) {
            throw truncated("BMFF box payload exceeds parent bounds: " + type, boxOffset);
        }
        return new BoxHeader(type, boxOffset, offset, payloadSize, sizeZero);
    }

    /// Moves to the end of one parsed box.
    ///
    /// @param header the parsed box header
    /// @throws AvifDecodeException if the box end is outside this view
    public void skipBoxPayload(BoxHeader header) throws AvifDecodeException {
        int endOffset = header.endOffset();
        if (endOffset < offset || endOffset > end) {
            throw truncated("BMFF box payload exceeds parent bounds: " + header.type(), header.offset());
        }
        offset = endOffset;
    }

    /// Fails if the requested byte count is not available.
    ///
    /// @param count the requested byte count
    /// @throws AvifDecodeException if the input is truncated
    private void ensureAvailable(int count) throws AvifDecodeException {
        if (count < 0 || count > end - offset) {
            throw truncated("Unexpected end of BMFF input", offset);
        }
    }

    /// Creates a BMFF parse failure.
    ///
    /// @param message the failure message
    /// @param byteOffset the associated byte offset
    /// @return a BMFF parse failure
    private static AvifDecodeException parseFailed(String message, long byteOffset) {
        return new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, message, byteOffset);
    }

    /// Creates a truncation failure.
    ///
    /// @param message the failure message
    /// @param byteOffset the associated byte offset
    /// @return a truncation failure
    private static AvifDecodeException truncated(String message, long byteOffset) {
        return new AvifDecodeException(AvifErrorCode.TRUNCATED_DATA, message, byteOffset);
    }
}
