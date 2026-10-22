// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.bitstream;


/// AV1 OBU types defined by the bitstream specification.

public enum ObuType {
    /// Sequence header OBU.
    SEQUENCE_HEADER(1),
    /// Temporal delimiter OBU.
    TEMPORAL_DELIMITER(2),
    /// Frame header OBU.
    FRAME_HEADER(3),
    /// Tile group OBU.
    TILE_GROUP(4),
    /// Metadata OBU.
    METADATA(5),
    /// Combined frame OBU.
    FRAME(6),
    /// Redundant frame header OBU.
    REDUNDANT_FRAME_HEADER(7),
    /// Large Scale Tile list OBU.
    TILE_LIST(8),
    /// Padding OBU.
    PADDING(15);

    /// The numeric OBU type identifier.
    private final int id;

    /// Creates an OBU type with a numeric identifier.
    ///
    /// @param id the numeric OBU type identifier
    ObuType(int id) {
        this.id = id;
    }

    /// Returns the numeric OBU type identifier.
    ///
    /// @return the numeric OBU type identifier
    public int id() {
        return id;
    }

    /// Maps a numeric OBU type identifier to an enum constant.
    ///
    /// @param id the numeric OBU type identifier
    /// @return the matching OBU type, or `null`
    public static ObuType fromId(int id) {
        for (ObuType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
