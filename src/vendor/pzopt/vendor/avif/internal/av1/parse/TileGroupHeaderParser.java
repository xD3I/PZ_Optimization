// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.parse;

import pzopt.vendor.avif.av1.Av1DecodeErrorCode;
import pzopt.vendor.avif.av1.Av1DecodeException;
import pzopt.vendor.avif.av1.Av1DecodeStage;
import pzopt.vendor.avif.internal.av1.bitstream.BitReader;
import pzopt.vendor.avif.internal.av1.bitstream.ObuPacket;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.TileGroupHeader;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Parser for AV1 tile-group headers embedded inside `TILE_GROUP` and `FRAME` OBUs.

public final class TileGroupHeaderParser {
    /// Prevents instantiation of this stateless parser.
    private TileGroupHeaderParser() {
    }

    /// Parses a tile-group header from the supplied bit reader.
    ///
    /// @param reader the payload bit reader positioned at the tile-group header
    /// @param obu the source OBU packet
    /// @param frameHeader the active frame header
    /// @return the parsed tile-group header
    /// @throws IOException if the payload is truncated or invalid
    public static TileGroupHeader parse(BitReader reader, ObuPacket obu, FrameHeader frameHeader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(frameHeader, "frameHeader");

        int totalTiles = frameHeader.tiling().columns() * frameHeader.tiling().rows();
        if (totalTiles <= 0) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.INVALID_BITSTREAM,
                    Av1DecodeStage.FRAME_ASSEMBLY,
                    "Frame header does not describe any tiles",
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null
            );
        }

        try {
            boolean explicitTilePositions = totalTiles > 1 && reader.readFlag();
            int startTileIndex;
            int endTileIndex;
            if (explicitTilePositions) {
                int indexBitCount = frameHeader.tiling().log2Cols() + frameHeader.tiling().log2Rows();
                startTileIndex = Math.toIntExact(reader.readBits(indexBitCount));
                endTileIndex = Math.toIntExact(reader.readBits(indexBitCount));
            } else {
                startTileIndex = 0;
                endTileIndex = totalTiles - 1;
            }

            if (startTileIndex > endTileIndex) {
                throw invalidBitstream(
                        obu,
                        "Tile-group start index exceeds end index: " + startTileIndex + " > " + endTileIndex
                );
            }
            if (endTileIndex >= totalTiles) {
                throw invalidBitstream(
                        obu,
                        "Tile-group end index exceeds the frame tile count: " + endTileIndex + " >= " + totalTiles
                );
            }

            return new TileGroupHeader(explicitTilePositions, startTileIndex, endTileIndex, totalTiles);
        } catch (EOFException ex) {
            throw new Av1DecodeException(
                    Av1DecodeErrorCode.UNEXPECTED_EOF,
                    Av1DecodeStage.FRAME_ASSEMBLY,
                    "Unexpected end of tile-group header",
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        }
    }

    /// Creates a contextual invalid-bitstream exception for tile-group assembly errors.
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
