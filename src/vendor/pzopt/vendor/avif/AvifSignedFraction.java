// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


/// A signed rational value carried by AVIF gain-map metadata.
///
/// @param numerator the signed 32-bit numerator
/// @param denominator the unsigned 32-bit denominator

public record AvifSignedFraction(int numerator, long denominator) {
    /// The maximum unsigned 32-bit value representable in a Java `long`.
    private static final long UINT32_MAX = 0xFFFF_FFFFL;

    /// Creates a signed rational value.
    ///
    /// @param numerator the signed 32-bit numerator
    /// @param denominator the unsigned 32-bit denominator
    public AvifSignedFraction {
        if (denominator <= 0 || denominator > UINT32_MAX) {
            throw new IllegalArgumentException("denominator must be in 1..2^32-1: " + denominator);
        }
    }

    /// Returns the signed numerator.
    ///
    /// @return the signed numerator
    @Override
    public int numerator() {
        return numerator;
    }

    /// Returns the unsigned denominator.
    ///
    /// @return the unsigned denominator
    @Override
    public long denominator() {
        return denominator;
    }

    /// Returns this rational value as a finite `double`.
    ///
    /// @return this rational value as a finite `double`
    public double toDouble() {
        return (double) numerator / (double) denominator;
    }
}
