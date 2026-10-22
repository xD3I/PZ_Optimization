// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.av1;


/// AV1 frame categories exposed by decoded frame metadata.

public enum Av1FrameType {
    /// A key intra frame.
    KEY,
    /// An inter frame.
    INTER,
    /// A non-key intra frame.
    INTRA,
    /// A switch frame.
    SWITCH
}
