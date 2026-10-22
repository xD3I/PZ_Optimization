// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.av1;


/// High-level AV1 decoder stages used for error reporting.

public enum Av1DecodeStage {
    /// The decoder is interacting with the underlying byte source.
    INPUT,
    /// The decoder is reading raw OBU packets from the byte stream.
    OBU_READ,
    /// The decoder is parsing a sequence header OBU.
    SEQUENCE_HEADER_PARSE,
    /// The decoder is parsing a frame header OBU.
    FRAME_HEADER_PARSE,
    /// The decoder is assembling multiple OBUs into a frame unit.
    FRAME_ASSEMBLY,
    /// The decoder is reconstructing frame pixels.
    FRAME_DECODE,
    /// The decoder is converting decoded planes into ARGB output.
    OUTPUT_CONVERSION
}
