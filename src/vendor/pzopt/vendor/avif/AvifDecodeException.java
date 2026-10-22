// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.io.IOException;
import java.util.Objects;

/// Checked exception thrown when AVIF container parsing or image decoding fails.

public final class AvifDecodeException extends IOException {
    /// The stable AVIF error code.
    private final AvifErrorCode code;

    /// The byte offset associated with the failure, or `null` when not known.
    private final Long byteOffset;

    /// Creates an exception without an underlying cause.
    ///
    /// @param code the stable AVIF error code
    /// @param message the human-readable failure message
    /// @param byteOffset the byte offset associated with the failure, or `null`
    public AvifDecodeException(AvifErrorCode code, String message, Long byteOffset) {
        this(code, message, byteOffset, null);
    }

    /// Creates an exception with an underlying cause.
    ///
    /// @param code the stable AVIF error code
    /// @param message the human-readable failure message
    /// @param byteOffset the byte offset associated with the failure, or `null`
    /// @param cause the underlying failure, or `null`
    public AvifDecodeException(
            AvifErrorCode code,
            String message,
            Long byteOffset,
            Throwable cause
    ) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.byteOffset = byteOffset;
    }

    /// Returns the stable AVIF error code.
    ///
    /// @return the stable AVIF error code
    public AvifErrorCode code() {
        return code;
    }

    /// Returns the byte offset associated with the failure.
    ///
    /// @return the byte offset associated with the failure, or `null`
    public Long byteOffset() {
        return byteOffset;
    }
}
