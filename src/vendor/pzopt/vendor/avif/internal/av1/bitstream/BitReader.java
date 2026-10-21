// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.bitstream;


import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// MSB-first bit reader for a single AV1 OBU payload.

public final class BitReader {
    /// The payload bytes being read.
    private final byte [] data;
    /// The first readable byte offset inside `data`.
    private final int startByteOffset;
    /// The readable byte length inside `data`.
    private final int byteLength;
    /// The next unread bit offset from the start of the payload.
    private int bitOffset;

    /// Creates a bit reader for the supplied payload bytes.
    ///
    /// @param data the payload bytes
    public BitReader(byte[] data) {
        this(data, 0, Objects.requireNonNull(data, "data").length);
    }

    /// Creates a bit reader for a byte slice inside the supplied payload array.
    ///
    /// @param data the payload bytes
    /// @param startByteOffset the first readable byte offset inside `data`
    /// @param byteLength the readable byte length inside `data`
    public BitReader(byte[] data, int startByteOffset, int byteLength) {
        this.data = Objects.requireNonNull(data, "data");
        if (startByteOffset < 0 || startByteOffset > data.length) {
            throw new IllegalArgumentException("startByteOffset out of range: " + startByteOffset);
        }
        if (byteLength < 0 || startByteOffset + byteLength > data.length) {
            throw new IllegalArgumentException("byteLength out of range: " + byteLength);
        }
        this.startByteOffset = startByteOffset;
        this.byteLength = byteLength;
    }

    /// Returns the number of unread bits.
    ///
    /// @return the number of unread bits
    public int bitsRemaining() {
        return byteLength * Byte.SIZE - bitOffset;
    }

    /// Returns the next unread bit offset from the start of the payload.
    ///
    /// @return the next unread bit offset
    public int bitOffset() {
        return bitOffset;
    }

    /// Returns whether the current position is aligned to the next byte boundary.
    ///
    /// @return whether the current position is byte aligned
    public boolean isByteAligned() {
        return (bitOffset & 7) == 0;
    }

    /// Returns the next unread byte offset when the reader is byte aligned.
    ///
    /// @return the next unread byte offset
    public int byteOffset() {
        if (!isByteAligned()) {
            throw new IllegalStateException("BitReader is not byte aligned");
        }
        return bitOffset >>> 3;
    }

    /// Advances the reader to the next byte boundary.
    public void byteAlign() {
        int remainder = bitOffset & 7;
        if (remainder != 0) {
            bitOffset += Byte.SIZE - remainder;
        }
    }

    /// Reads a single bit.
    ///
    /// @return the next bit as `0` or `1`
    /// @throws IOException if the payload is truncated
    public int readBit() throws IOException {
        ensureBitsRemaining(1);
        int byteIndex = startByteOffset + (bitOffset >>> 3);
        int bitIndex = 7 - (bitOffset & 7);
        bitOffset++;
        return (data[byteIndex] >>> bitIndex) & 1;
    }

    /// Reads a boolean flag.
    ///
    /// @return the next bit as a boolean
    /// @throws IOException if the payload is truncated
    public boolean readFlag() throws IOException {
        return readBit() != 0;
    }

    /// Reads up to thirty-two bits as an unsigned literal.
    ///
    /// @param bitCount the number of bits to read, in the range `0..32`
    /// @return the decoded unsigned literal
    /// @throws IOException if the payload is truncated
    public long readBits(int bitCount) throws IOException {
        if (bitCount < 0 || bitCount > Integer.SIZE) {
            throw new IllegalArgumentException("bitCount out of range: " + bitCount);
        }
        if (bitCount == 0) {
            return 0;
        }

        ensureBitsRemaining(bitCount);

        long value = 0;
        for (int i = 0; i < bitCount; i++) {
            value = (value << 1) | readBit();
        }
        return value;
    }

    /// Reads up to thirty-two bits as a signed literal with sign extension.
    ///
    /// @param bitCount the number of bits to read, in the range `1..32`
    /// @return the decoded signed literal
    /// @throws IOException if the payload is truncated
    public int readSignedBits(int bitCount) throws IOException {
        if (bitCount <= 0 || bitCount > Integer.SIZE) {
            throw new IllegalArgumentException("bitCount out of range: " + bitCount);
        }

        long value = readBits(bitCount);
        long shift = Long.SIZE - bitCount;
        return (int) ((value << shift) >> shift);
    }

    /// Reads an unsigned exponential-Golomb style value used by AV1 headers.
    ///
    /// @return the decoded unsigned value
    /// @throws IOException if the payload is truncated or invalid
    public long readUvlc() throws IOException {
        int leadingZeros = 0;

        while (true) {
            int bit = readBit();
            if (bit == 1) {
                break;
            }
            leadingZeros++;
            if (leadingZeros == 32) {
                throw new IOException("UVLC value exceeds the supported range");
            }
        }

        if (leadingZeros == 0) {
            return 0;
        }

        long suffix = readBits(leadingZeros);
        return ((1L << leadingZeros) - 1L) + suffix;
    }

    /// Ensures that the payload exposes at least the requested number of unread bits.
    ///
    /// @param bitCount the number of required unread bits
    /// @throws EOFException if the payload is truncated
    private void ensureBitsRemaining(int bitCount) throws EOFException {
        if (bitsRemaining() < bitCount) {
            throw new EOFException("Unexpected end of OBU payload");
        }
    }
}
