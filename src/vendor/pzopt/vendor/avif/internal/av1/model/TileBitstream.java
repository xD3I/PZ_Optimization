// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;

import pzopt.vendor.avif.internal.av1.bitstream.BitReader;
import pzopt.vendor.avif.internal.av1.entropy.MsacDecoder;

import java.util.Arrays;
import java.util.Objects;

/// Read-only view over one tile payload inside a tile group.

public final class TileBitstream {
    /// The zero-based tile index within the frame.
    private final int tileIndex;
    /// The backing payload bytes that contain the tile payload.
    private final byte [] data;
    /// The first tile byte inside `data`.
    private final int dataOffset;
    /// The byte length of the tile payload.
    private final int dataLength;

    /// Creates a read-only tile payload view.
    ///
    /// @param tileIndex the zero-based tile index within the frame
    /// @param data the backing payload bytes that contain the tile payload
    /// @param dataOffset the first tile byte inside `data`
    /// @param dataLength the byte length of the tile payload
    public TileBitstream(int tileIndex, byte[] data, int dataOffset, int dataLength) {
        if (tileIndex < 0) {
            throw new IllegalArgumentException("tileIndex < 0: " + tileIndex);
        }
        this.data = Objects.requireNonNull(data, "data");
        if (dataOffset < 0 || dataOffset > data.length) {
            throw new IllegalArgumentException("dataOffset out of range: " + dataOffset);
        }
        if (dataLength < 0 || dataOffset + dataLength > data.length) {
            throw new IllegalArgumentException("dataLength out of range: " + dataLength);
        }
        this.tileIndex = tileIndex;
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
    }

    /// Returns the zero-based tile index within the frame.
    ///
    /// @return the zero-based tile index within the frame
    public int tileIndex() {
        return tileIndex;
    }

    /// Returns the first tile byte inside the backing payload.
    ///
    /// @return the first tile byte inside the backing payload
    public int dataOffset() {
        return dataOffset;
    }

    /// Returns the byte length of the tile payload.
    ///
    /// @return the byte length of the tile payload
    public int dataLength() {
        return dataLength;
    }

    /// Opens a fresh bit reader over the tile payload.
    ///
    /// @return a fresh bit reader over the tile payload
    public BitReader openBitReader() {
        return new BitReader(data, dataOffset, dataLength);
    }

    /// Opens a fresh AV1 multi-symbol arithmetic decoder over the tile payload.
    ///
    /// @param disableCdfUpdate whether CDF updates are disabled for the active frame
    /// @return a fresh arithmetic decoder over the tile payload
    public MsacDecoder openMsacDecoder(boolean disableCdfUpdate) {
        return new MsacDecoder(data, dataOffset, dataLength, disableCdfUpdate);
    }

    /// Returns a copy of the tile payload bytes.
    ///
    /// @return a copy of the tile payload bytes
    public byte[] copyBytes() {
        return Arrays.copyOfRange(data, dataOffset, dataOffset + dataLength);
    }

    /// Returns a content hash code for the tile payload bytes.
    ///
    /// @return the payload content hash code
    public int payloadHashCode() {
        int hashCode = 1;
        int dataEnd = dataOffset + dataLength;
        for (int index = dataOffset; index < dataEnd; index++) {
            hashCode = 31 * hashCode + data[index];
        }
        return hashCode;
    }

    /// Returns whether another tile bitstream contains the same payload bytes.
    ///
    /// Tile indices are not compared.
    ///
    /// @param other the tile bitstream to compare
    /// @return whether both payload byte ranges have identical contents
    public boolean hasSamePayload(TileBitstream other) {
        TileBitstream checkedOther = Objects.requireNonNull(other, "other");
        return dataLength == checkedOther.dataLength
                && Arrays.equals(
                        data,
                        dataOffset,
                        dataOffset + dataLength,
                        checkedOther.data,
                        checkedOther.dataOffset,
                        checkedOther.dataOffset + checkedOther.dataLength
                );
    }
}
