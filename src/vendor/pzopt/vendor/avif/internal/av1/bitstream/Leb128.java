// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.bitstream;

import pzopt.vendor.avif.internal.io.BufferedInput;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Utility methods for reading unsigned LEB128 values from AV1 bitstreams.

public final class Leb128 {
    /// The maximum supported unsigned value.
    private static final long MAX_UNSIGNED_VALUE = 0xFFFF_FFFFL;

    /// Prevents instantiation of this utility class.
    private Leb128() {
    }

    /// Reads an unsigned LEB128 value from a buffered byte source.
    ///
    /// @param input the buffered byte source
    /// @param maxBytes the maximum number of bytes to consume
    /// @return the decoded LEB128 value and byte count
    /// @throws IOException if the source is truncated or unreadable
    public static ReadResult readUnsigned(BufferedInput input, int maxBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes <= 0: " + maxBytes);
        }

        long value = 0;
        int shift = 0;

        for (int byteCount = 1; byteCount <= maxBytes; byteCount++) {
            int current = input.readUnsignedByte();
            value |= (long) (current & 0x7F) << shift;

            if ((current & 0x80) == 0) {
                if (value > MAX_UNSIGNED_VALUE) {
                    throw new IOException("LEB128 value exceeds 32-bit unsigned range: " + value);
                }
                return new ReadResult(value, byteCount);
            }

            shift += 7;
        }

        throw new EOFException("LEB128 value exceeds the supported byte length");
    }

    /// Decoded unsigned LEB128 value and encoded byte count.
    ///
    /// @param value the decoded unsigned value
    /// @param byteCount the number of bytes consumed from the source
    
    public record ReadResult(long value, int byteCount) {
        /// Creates a decoded unsigned LEB128 result.
        public ReadResult {
            if (value < 0) {
                throw new IllegalArgumentException("value < 0: " + value);
            }
            if (byteCount <= 0) {
                throw new IllegalArgumentException("byteCount <= 0: " + byteCount);
            }
        }
    }
}
