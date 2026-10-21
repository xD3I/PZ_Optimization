// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.bmff;


import java.util.Objects;

/// Header for one BMFF box.
///
/// @param type the four-character box type
/// @param offset the absolute byte offset of the box header
/// @param payloadOffset the absolute byte offset of the box payload
/// @param payloadSize the byte length of the box payload
/// @param sizeZero whether the serialized box used a size field of zero

public record BoxHeader(String type, int offset, int payloadOffset, int payloadSize, boolean sizeZero) {
    /// Creates a BMFF box header.
    public BoxHeader {
        Objects.requireNonNull(type, "type");
    }

    /// Returns the absolute byte offset immediately after this box.
    ///
    /// @return the absolute byte offset immediately after this box
    public int endOffset() {
        return payloadOffset + payloadSize;
    }
}
