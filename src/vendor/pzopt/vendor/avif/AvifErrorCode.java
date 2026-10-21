// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


/// Stable AVIF container-level error categories.

public enum AvifErrorCode {
    /// The input does not contain an AVIF-compatible `ftyp` box.
    INVALID_FTYP,
    /// The BMFF box structure is malformed.
    BMFF_PARSE_FAILED,
    /// The input ended before a required byte range was available.
    TRUNCATED_DATA,
    /// The AVIF file does not contain a usable primary image item.
    MISSING_IMAGE_ITEM,
    /// The input uses a valid feature that this reader does not support.
    UNSUPPORTED_FEATURE,
    /// A decoded AV1 image does not match the dimensions declared by its `ispe` property.
    ISPE_SIZE_MISMATCH,
    /// A grid image has inconsistent cells or invalid canvas geometry.
    INVALID_IMAGE_GRID,
    /// The decoded image exceeds the configured or implementation maximum frame size.
    FRAME_SIZE_LIMIT_EXCEEDED,
    /// The embedded AV1 payload could not be decoded.
    AV1_DECODE_FAILED,
    /// The reader was used after being closed.
    CLOSED,
    /// The input exceeds the configured maximum input size.
    INPUT_TOO_LARGE,
    /// A forward-only input requires data that has already passed outside the retained window.
    SEEKABLE_SOURCE_REQUIRED
}
