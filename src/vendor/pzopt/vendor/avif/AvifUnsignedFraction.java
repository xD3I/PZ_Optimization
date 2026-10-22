// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


/// An unsigned rational value carried by AVIF gain-map metadata.
///
/// @param numerator the unsigned 32-bit numerator
/// @param denominator the unsigned 32-bit denominator

public record AvifUnsignedFraction(long numerator, long denominator) {
    /// The maximum unsigned 32-bit value representable in a Java `long`.
    private static final long UINT32_MAX = 0xFFFF_FFFFL;

    /// Creates an unsigned rational value.
    ///
    /// @param numerator the unsigned 32-bit numerator
    /// @param denominator the unsigned 32-bit denominator
    public AvifUnsignedFraction {
        if (numerator < 0 || numerator > UINT32_MAX) {
            throw new IllegalArgumentException("numerator must be in 0..2^32-1: " + numerator);
        }
        if (denominator <= 0 || denominator > UINT32_MAX) {
            throw new IllegalArgumentException("denominator must be in 1..2^32-1: " + denominator);
        }
    }

    /// Returns the unsigned numerator.
    ///
    /// @return the unsigned numerator
    @Override
    public long numerator() {
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
