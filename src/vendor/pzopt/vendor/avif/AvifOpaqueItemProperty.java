// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Immutable AVIF item property retained without interpreting its type-specific payload.

public final class AvifOpaqueItemProperty {
    /// The byte length of a UUID box user type.
    private static final int UUID_USER_TYPE_LENGTH = 16;

    /// The four-character property box type.
    private final String type;
    /// The UUID box user type, or `null` for non-UUID properties.
    private final ByteBuffer userType;
    /// The property box payload after any UUID user type.
    private final ByteBuffer payload;

    /// Creates an immutable opaque item property descriptor.
    ///
    /// @param type the four-character property box type
    /// @param userType the UUID box user type, or `null` for non-UUID properties
    /// @param payload the property box payload after any UUID user type
    public AvifOpaqueItemProperty(String type, byte [] userType, byte[] payload) {
        this.type = validateType(type);
        if ("uuid".equals(type)) {
            if (userType == null || userType.length != UUID_USER_TYPE_LENGTH) {
                throw new IllegalArgumentException("uuid item properties require a 16-byte userType");
            }
        } else if (userType != null) {
            throw new IllegalArgumentException("Only uuid item properties may carry userType");
        }

        this.userType = immutableBytes(userType);
        this.payload = Objects.requireNonNull(immutableBytes(Objects.requireNonNull(payload, "payload")));
    }

    /// Returns the four-character property box type.
    ///
    /// @return the four-character property box type
    public String type() {
        return type;
    }

    /// Returns the UUID box user type.
    ///
    /// Non-UUID properties return `null`.
    ///
    /// @return a read-only view of the UUID user type, or `null`
    public ByteBuffer userType() {
        return byteView(userType);
    }

    /// Returns the property box payload after any UUID user type.
    ///
    /// @return a read-only view of the property payload
    public ByteBuffer payload() {
        return payload.slice();
    }

    /// Validates one four-character box type string.
    ///
    /// @param type the type string
    /// @return the validated type string
    private static String validateType(String type) {
        Objects.requireNonNull(type, "type");
        if (type.length() != 4) {
            throw new IllegalArgumentException("type must contain exactly four characters: " + type);
        }
        for (int i = 0; i < type.length(); i++) {
            if (type.charAt(i) > 0xFF) {
                throw new IllegalArgumentException("type must be an ISO-8859-1 four-character code: " + type);
            }
        }
        return type;
    }

    /// Creates immutable byte-buffer storage for one optional payload.
    ///
    /// @param bytes the source bytes, or `null`
    /// @return immutable byte-buffer storage, or `null`
    private static ByteBuffer immutableBytes(byte [] bytes) {
        if (bytes == null) {
            return null;
        }
        return ByteBuffer.wrap(Arrays.copyOf(bytes, bytes.length)).asReadOnlyBuffer();
    }

    /// Returns a read-only view over immutable payload storage.
    ///
    /// @param bytes the immutable payload storage, or `null`
    /// @return a read-only view, or `null`
    private static ByteBuffer byteView(ByteBuffer bytes) {
        return bytes == null ? null : bytes.slice();
    }
}
