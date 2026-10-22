// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.bitstream;


import java.util.Objects;

/// Parsed AV1 OBU header metadata.
///
/// @param type the parsed OBU type
/// @param extensionFlag whether the OBU carries an extension header
/// @param hasSizeField whether the OBU carries a size field
/// @param temporalId the temporal layer identifier
/// @param spatialId the spatial layer identifier

public record ObuHeader(
        ObuType type,
        boolean extensionFlag,
        boolean hasSizeField,
        int temporalId,
        int spatialId
) {
    /// Creates a parsed OBU header.
    public ObuHeader {
        if (temporalId < 0 || temporalId > 7) {
            throw new IllegalArgumentException("temporalId out of range: " + temporalId);
        }
        if (spatialId < 0 || spatialId > 3) {
            throw new IllegalArgumentException("spatialId out of range: " + spatialId);
        }
        if (!extensionFlag && (temporalId != 0 || spatialId != 0)) {
            throw new IllegalArgumentException("temporalId and spatialId must be zero when extensionFlag is false");
        }
        Objects.requireNonNull(type, "type");
    }
}
