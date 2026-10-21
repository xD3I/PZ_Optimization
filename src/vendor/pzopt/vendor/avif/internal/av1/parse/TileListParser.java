// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.parse;

import pzopt.vendor.avif.av1.Av1DecodeErrorCode;
import pzopt.vendor.avif.av1.Av1DecodeException;
import pzopt.vendor.avif.av1.Av1DecodeStage;
import pzopt.vendor.avif.internal.av1.bitstream.ObuPacket;
import pzopt.vendor.avif.internal.av1.bitstream.ObuType;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.SequenceHeader;
import pzopt.vendor.avif.internal.av1.model.TileBitstream;
import pzopt.vendor.avif.internal.av1.model.TileList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Parses AV1 Large Scale Tile list OBUs against a common camera frame header.

public final class TileListParser {
    /// The maximum tile-list entry count permitted by the AV1 specification.
    private static final int MAX_TILE_LIST_ENTRIES = 512;

    /// Prevents instantiation of this stateless parser.
    private TileListParser() {
    }

    /// Parses one tile-list OBU.
    ///
    /// @param obu the source tile-list OBU
    /// @param sequenceHeader the sequence header associated with the common camera frame
    /// @param cameraFrameHeader the active common camera frame header
    /// @param strictStdCompliance whether Large Scale Tile conformance constraints must be enforced
    /// @return the parsed tile list
    /// @throws Av1DecodeException if the payload or an entry violates tile-list syntax
    public static TileList parse(
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            FrameHeader cameraFrameHeader,
            boolean strictStdCompliance
    ) throws Av1DecodeException {
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        Objects.requireNonNull(cameraFrameHeader, "cameraFrameHeader");
        if (obu.header().type() != ObuType.TILE_LIST) {
            throw new IllegalArgumentException("OBU type is not TILE_LIST: " + obu.header().type());
        }
        validateCameraFrame(obu, sequenceHeader, cameraFrameHeader, strictStdCompliance);

        byte[] payload = obu.payload();
        if (payload.length < 4) {
            throw invalidBitstream(obu, "Tile-list header is truncated");
        }
        int outputTileColumns = (payload[0] & 0xFF) + 1;
        int outputTileRows = (payload[1] & 0xFF) + 1;
        int tileCount = readUnsigned16(payload, 2) + 1;
        if (tileCount > MAX_TILE_LIST_ENTRIES) {
            throw invalidBitstream(obu, "Tile-list entry count exceeds 512");
        }
        if (tileCount > (long) outputTileColumns * outputTileRows) {
            throw invalidBitstream(obu, "Tile-list entries exceed the output tile grid");
        }

        FrameHeader.TilingInfo tiling = cameraFrameHeader.tiling();
        List<TileList.Entry> entries = new ArrayList<>(tileCount);
        int cursor = 4;
        for (int tile = 0; tile < tileCount; tile++) {
            if (cursor + 5 > payload.length) {
                throw invalidBitstream(obu, "Tile-list entry header is truncated");
            }
            int anchorFrameIndex = payload[cursor] & 0xFF;
            int tileRow = payload[cursor + 1] & 0xFF;
            int tileColumn = payload[cursor + 2] & 0xFF;
            int tileDataLength = readUnsigned16(payload, cursor + 3) + 1;
            cursor += 5;

            if (anchorFrameIndex >= 128) {
                throw invalidBitstream(obu, "Tile-list anchor frame index exceeds 127");
            }
            if (tileRow >= tiling.rows()) {
                throw invalidBitstream(obu, "Tile-list source row exceeds the camera frame tile grid");
            }
            if (tileColumn >= tiling.columns()) {
                throw invalidBitstream(obu, "Tile-list source column exceeds the camera frame tile grid");
            }
            if (tileDataLength > payload.length - cursor) {
                throw invalidBitstream(obu, "Tile-list coded tile data is truncated");
            }

            int tileIndex = tileRow * tiling.columns() + tileColumn;
            entries.add(new TileList.Entry(
                    anchorFrameIndex,
                    tileRow,
                    tileColumn,
                    new TileBitstream(tileIndex, payload, cursor, tileDataLength)
            ));
            cursor += tileDataLength;
        }
        if (cursor != payload.length) {
            throw invalidBitstream(obu, "Tile-list OBU has trailing payload bytes");
        }
        return new TileList(outputTileColumns, outputTileRows, entries);
    }

    /// Validates the common camera frame when strict Large Scale Tile conformance is enabled.
    ///
    /// @param obu the OBU used for error context
    /// @param sequenceHeader the sequence header associated with the camera frame
    /// @param cameraFrameHeader the common camera frame header
    /// @param strictStdCompliance whether conformance constraints must be enforced
    /// @throws Av1DecodeException if strict validation finds a nonconformant value
    public static void validateCameraFrame(
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            FrameHeader cameraFrameHeader,
            boolean strictStdCompliance
    ) throws Av1DecodeException {
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        Objects.requireNonNull(cameraFrameHeader, "cameraFrameHeader");
        if (!strictStdCompliance) {
            return;
        }
        try {
            FrameHeaderConformanceValidator.validateLargeScaleTileFrame(sequenceHeader, cameraFrameHeader);
        } catch (IOException exception) {
            throw invalidBitstream(obu, exception.getMessage());
        }
    }

    /// Reads one big-endian unsigned 16-bit literal.
    ///
    /// @param data the source payload
    /// @param offset the first source byte
    /// @return the unsigned literal
    private static int readUnsigned16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    /// Creates a contextual invalid-bitstream exception for tile-list errors.
    ///
    /// @param obu the source OBU
    /// @param message the detailed validation message
    /// @return the contextual exception
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
