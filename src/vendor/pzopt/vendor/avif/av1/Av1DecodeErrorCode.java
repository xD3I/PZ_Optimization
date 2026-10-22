// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.av1;


/// Stable error codes exposed by the public AV1 decoder API.

public enum Av1DecodeErrorCode {
    /// The input ended before the expected number of bytes or bits were available.
    UNEXPECTED_EOF,
    /// The OBU header contains invalid or unsupported flag combinations.
    INVALID_OBU_HEADER,
    /// A LEB128 value is malformed or exceeds the supported range.
    INVALID_LEB128,
    /// The AV1 bitstream violates structural constraints.
    INVALID_BITSTREAM,
    /// The input uses a valid AV1 feature that this decoder does not support.
    UNSUPPORTED_FEATURE,
    /// The configured or implementation frame size limit was exceeded.
    FRAME_SIZE_LIMIT_EXCEEDED,
    /// The configured or implementation OBU payload size limit was exceeded.
    OBU_PAYLOAD_SIZE_LIMIT_EXCEEDED,
    /// The decoder state machine reached an invalid state.
    STATE_VIOLATION
}
