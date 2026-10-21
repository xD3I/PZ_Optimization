// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.parse;

import pzopt.vendor.avif.av1.Av1DecodeErrorCode;
import pzopt.vendor.avif.av1.Av1DecodeException;
import pzopt.vendor.avif.av1.Av1DecodeStage;
import pzopt.vendor.avif.internal.av1.bitstream.ObuPacket;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.TileBitstream;
import pzopt.vendor.avif.internal.av1.model.TileGroupHeader;

import java.util.Objects;

/// Parser that converts AV1 tile-data layout into per-tile bitstream views.

public final class TileBitstreamParser {
    /// Prevents instantiation of this stateless parser.
    private TileBitstreamParser() {
    }

    /// Parses per-tile bitstream views from a tile-group payload.
    ///
    /// @param obu the source OBU packet
    /// @param frameHeader the active frame header
    /// @param tileGroupHeader the parsed tile-group header
    /// @param tileDataOffset the byte offset of the tile-data section inside the OBU payload
    /// @return the parsed per-tile bitstream views
    /// @throws Av1DecodeException if the tile-data layout is malformed
    public static TileBitstream[] parse(
            ObuPacket obu,
            FrameHeader frameHeader,
            TileGroupHeader tileGroupHeader,
            int tileDataOffset
    ) throws Av1DecodeException {
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(frameHeader, "frameHeader");
        Objects.requireNonNull(tileGroupHeader, "tileGroupHeader");

        byte[] payload = obu.payload();
        if (tileDataOffset < 0 || tileDataOffset > payload.length) {
            throw invalidBitstream(obu, "Tile-data offset exceeds the OBU payload length");
        }

        int tileCount = tileGroupHeader.tileCount();
        int sizeBytes = frameHeader.tiling().sizeBytes();
        if (tileCount > 1 && sizeBytes <= 0) {
            throw invalidBitstream(obu, "Multi-tile groups require size bytes in the active frame header");
        }

        TileBitstream[] bitstreams = new TileBitstream[tileCount];
        int cursor = tileDataOffset;
        for (int i = 0; i < tileCount; i++) {
            int tileDataLength;
            if (i == tileCount - 1) {
                tileDataLength = payload.length - cursor;
            } else {
                if (cursor + sizeBytes > payload.length) {
                    throw invalidBitstream(obu, "Tile size table exceeds the OBU payload length");
                }

                int tileDataLengthMinusOne = 0;
                for (int byteIndex = 0; byteIndex < sizeBytes; byteIndex++) {
                    tileDataLengthMinusOne |= (payload[cursor + byteIndex] & 0xFF) << (byteIndex * Byte.SIZE);
                }
                cursor += sizeBytes;
                tileDataLength = tileDataLengthMinusOne + 1;
                if (cursor + tileDataLength > payload.length) {
                    throw invalidBitstream(obu, "Tile payload exceeds the OBU payload length");
                }
            }

            bitstreams[i] = new TileBitstream(
                    tileGroupHeader.startTileIndex() + i,
                    payload,
                    cursor,
                    tileDataLength
            );
            cursor += tileDataLength;
        }
        return bitstreams;
    }

    /// Creates a contextual invalid-bitstream exception for tile-data layout errors.
    ///
    /// @param obu the source OBU packet
    /// @param message the detailed validation message
    /// @return the contextual invalid-bitstream exception
    private static Av1DecodeException invalidBitstream(ObuPacket obu, String message) {
        return new Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.FRAME_ASSEMBLY,
                message,
                obu.streamOffset(),
                obu.obuIndex(),
                null
        );
    }
}
