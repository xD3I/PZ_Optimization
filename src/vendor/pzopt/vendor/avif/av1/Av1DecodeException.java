// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.av1;


import java.io.IOException;
import java.util.Objects;

/// Checked exception thrown when AV1 input cannot be decoded.

public final class Av1DecodeException extends IOException {
    /// The stable error code describing the failure category.
    private final Av1DecodeErrorCode code;
    /// The high-level decode stage where the failure occurred.
    private final Av1DecodeStage stage;
    /// The byte offset of the relevant OBU or payload location when known.
    private final Long streamOffset;
    /// The zero-based OBU index when known.
    private final Integer obuIndex;
    /// The zero-based frame index when known.
    private final Integer frameIndex;

    /// Creates an exception without an underlying cause.
    ///
    /// @param code the stable error code
    /// @param stage the decode stage
    /// @param message the human-readable error message
    /// @param streamOffset the byte offset when known
    /// @param obuIndex the OBU index when known
    /// @param frameIndex the frame index when known
    public Av1DecodeException(
            Av1DecodeErrorCode code,
            Av1DecodeStage stage,
            String message,
            Long streamOffset,
            Integer obuIndex,
            Integer frameIndex
    ) {
        this(code, stage, message, streamOffset, obuIndex, frameIndex, null);
    }

    /// Creates an exception with an underlying cause.
    ///
    /// @param code the stable error code
    /// @param stage the decode stage
    /// @param message the human-readable error message
    /// @param streamOffset the byte offset when known
    /// @param obuIndex the OBU index when known
    /// @param frameIndex the frame index when known
    /// @param cause the underlying cause, if any
    public Av1DecodeException(
            Av1DecodeErrorCode code,
            Av1DecodeStage stage,
            String message,
            Long streamOffset,
            Integer obuIndex,
            Integer frameIndex,
            Throwable cause
    ) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.stage = Objects.requireNonNull(stage, "stage");
        this.streamOffset = streamOffset;
        this.obuIndex = obuIndex;
        this.frameIndex = frameIndex;
    }

    /// Returns the stable error code.
    ///
    /// @return the stable error code
    public Av1DecodeErrorCode code() {
        return code;
    }

    /// Returns the high-level decode stage where the failure occurred.
    ///
    /// @return the failing decode stage
    public Av1DecodeStage stage() {
        return stage;
    }

    /// Returns the relevant byte offset when known.
    ///
    /// @return the relevant byte offset, or `null`
    public Long streamOffset() {
        return streamOffset;
    }

    /// Returns the relevant OBU index when known.
    ///
    /// @return the relevant OBU index, or `null`
    public Integer obuIndex() {
        return obuIndex;
    }

    /// Returns the relevant frame index when known.
    ///
    /// @return the relevant frame index, or `null`
    public Integer frameIndex() {
        return frameIndex;
    }
}
