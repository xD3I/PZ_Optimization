// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.internal.av1.model.FrameHeader;

import java.util.Objects;

/// Decoded AV1 loop-restoration unit syntax for one image plane.
///
/// A unit can disable restoration locally, or carry either Wiener coefficients or self-guided
/// restoration projection coefficients. Frame-level `SWITCHABLE` restoration is resolved to one
/// of these concrete per-unit types before instances of this class are stored.

public final class RestorationUnit {
    /// A local unit with no loop restoration.
    private static final RestorationUnit NONE = new RestorationUnit(
            FrameHeader.RestorationType.NONE,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
    );

    /// The concrete restoration type for this unit.
    private final FrameHeader.RestorationType type;

    /// The first horizontal Wiener coefficient.
    private final int horizontalWienerCoefficient0;

    /// The second horizontal Wiener coefficient.
    private final int horizontalWienerCoefficient1;

    /// The third horizontal Wiener coefficient.
    private final int horizontalWienerCoefficient2;

    /// The first vertical Wiener coefficient.
    private final int verticalWienerCoefficient0;

    /// The second vertical Wiener coefficient.
    private final int verticalWienerCoefficient1;

    /// The third vertical Wiener coefficient.
    private final int verticalWienerCoefficient2;

    /// The self-guided restoration parameter set index.
    private final int selfGuidedSet;

    /// The first self-guided projection coefficient.
    private final int selfGuidedProjectionCoefficient0;

    /// The second self-guided projection coefficient.
    private final int selfGuidedProjectionCoefficient1;

    /// Creates one decoded restoration unit.
    ///
    /// @param type the concrete restoration type for this unit
    /// @param horizontalWienerCoefficient0 the first horizontal Wiener coefficient
    /// @param horizontalWienerCoefficient1 the second horizontal Wiener coefficient
    /// @param horizontalWienerCoefficient2 the third horizontal Wiener coefficient
    /// @param verticalWienerCoefficient0 the first vertical Wiener coefficient
    /// @param verticalWienerCoefficient1 the second vertical Wiener coefficient
    /// @param verticalWienerCoefficient2 the third vertical Wiener coefficient
    /// @param selfGuidedSet the self-guided restoration parameter set index
    /// @param selfGuidedProjectionCoefficient0 the first self-guided projection coefficient
    /// @param selfGuidedProjectionCoefficient1 the second self-guided projection coefficient
    private RestorationUnit(
            FrameHeader.RestorationType type,
            int horizontalWienerCoefficient0,
            int horizontalWienerCoefficient1,
            int horizontalWienerCoefficient2,
            int verticalWienerCoefficient0,
            int verticalWienerCoefficient1,
            int verticalWienerCoefficient2,
            int selfGuidedSet,
            int selfGuidedProjectionCoefficient0,
            int selfGuidedProjectionCoefficient1
    ) {
        this.type = Objects.requireNonNull(type, "type");
        if (type == FrameHeader.RestorationType.SWITCHABLE) {
            throw new IllegalArgumentException("Restoration units must carry a concrete restoration type");
        }
        this.horizontalWienerCoefficient0 = horizontalWienerCoefficient0;
        this.horizontalWienerCoefficient1 = horizontalWienerCoefficient1;
        this.horizontalWienerCoefficient2 = horizontalWienerCoefficient2;
        this.verticalWienerCoefficient0 = verticalWienerCoefficient0;
        this.verticalWienerCoefficient1 = verticalWienerCoefficient1;
        this.verticalWienerCoefficient2 = verticalWienerCoefficient2;
        if (selfGuidedSet < 0 || selfGuidedSet >= 16) {
            throw new IllegalArgumentException("selfGuidedSet out of range: " + selfGuidedSet);
        }
        this.selfGuidedSet = selfGuidedSet;
        this.selfGuidedProjectionCoefficient0 = selfGuidedProjectionCoefficient0;
        this.selfGuidedProjectionCoefficient1 = selfGuidedProjectionCoefficient1;
    }

    /// Returns a disabled restoration unit.
    ///
    /// @return a disabled restoration unit
    public static RestorationUnit none() {
        return NONE;
    }

    /// Creates one Wiener restoration unit.
    ///
    /// @param coefficients the horizontal and vertical Wiener coefficients, in that order
    /// @return one Wiener restoration unit
    public static RestorationUnit wiener(int[][] coefficients) {
        int[][] checkedCoefficients = Objects.requireNonNull(coefficients, "coefficients");
        if (checkedCoefficients.length != 2) {
            throw new IllegalArgumentException("Wiener coefficient table must have two passes");
        }
        int[] horizontal = Objects.requireNonNull(checkedCoefficients[0], "coefficients[0]");
        int[] vertical = Objects.requireNonNull(checkedCoefficients[1], "coefficients[1]");
        if (horizontal.length != 3 || vertical.length != 3) {
            throw new IllegalArgumentException("Each Wiener coefficient pass must contain three coefficients");
        }
        return wiener(
                horizontal[0], horizontal[1], horizontal[2],
                vertical[0], vertical[1], vertical[2]
        );
    }

    /// Creates one Wiener restoration unit from scalar coefficients.
    ///
    /// @param horizontal0 the first horizontal coefficient
    /// @param horizontal1 the second horizontal coefficient
    /// @param horizontal2 the third horizontal coefficient
    /// @param vertical0 the first vertical coefficient
    /// @param vertical1 the second vertical coefficient
    /// @param vertical2 the third vertical coefficient
    /// @return one Wiener restoration unit
    static RestorationUnit wiener(
            int horizontal0,
            int horizontal1,
            int horizontal2,
            int vertical0,
            int vertical1,
            int vertical2
    ) {
        return new RestorationUnit(
                FrameHeader.RestorationType.WIENER,
                horizontal0,
                horizontal1,
                horizontal2,
                vertical0,
                vertical1,
                vertical2,
                0,
                0,
                0
        );
    }

    /// Creates one self-guided restoration unit.
    ///
    /// @param set the self-guided restoration parameter set index
    /// @param projectionCoefficients the two projection coefficients
    /// @return one self-guided restoration unit
    public static RestorationUnit selfGuided(int set, int[] projectionCoefficients) {
        int[] checkedCoefficients = Objects.requireNonNull(projectionCoefficients, "projectionCoefficients");
        if (checkedCoefficients.length != 2) {
            throw new IllegalArgumentException("projectionCoefficients length must be 2");
        }
        return selfGuided(set, checkedCoefficients[0], checkedCoefficients[1]);
    }

    /// Creates one self-guided restoration unit from scalar projection coefficients.
    ///
    /// @param set the self-guided restoration parameter set index
    /// @param coefficient0 the first projection coefficient
    /// @param coefficient1 the second projection coefficient
    /// @return one self-guided restoration unit
    static RestorationUnit selfGuided(int set, int coefficient0, int coefficient1) {
        return new RestorationUnit(
                FrameHeader.RestorationType.SELF_GUIDED,
                0,
                0,
                0,
                0,
                0,
                0,
                set,
                coefficient0,
                coefficient1
        );
    }

    /// Returns the concrete restoration type for this unit.
    ///
    /// @return the concrete restoration type for this unit
    public FrameHeader.RestorationType type() {
        return type;
    }

    /// Returns one Wiener coefficient without allocating a coefficient table.
    ///
    /// @param pass the pass index, where zero is horizontal and one is vertical
    /// @param coefficient the coefficient index in `[0, 3)`
    /// @return the selected Wiener coefficient
    public int wienerCoefficient(int pass, int coefficient) {
        Objects.checkIndex(coefficient, 3);
        return switch (Objects.checkIndex(pass, 2)) {
            case 0 -> switch (coefficient) {
                case 0 -> horizontalWienerCoefficient0;
                case 1 -> horizontalWienerCoefficient1;
                case 2 -> horizontalWienerCoefficient2;
                default -> throw new AssertionError();
            };
            case 1 -> switch (coefficient) {
                case 0 -> verticalWienerCoefficient0;
                case 1 -> verticalWienerCoefficient1;
                case 2 -> verticalWienerCoefficient2;
                default -> throw new AssertionError();
            };
            default -> throw new AssertionError();
        };
    }

    /// Returns the self-guided restoration parameter set index.
    ///
    /// @return the self-guided restoration parameter set index
    public int selfGuidedSet() {
        return selfGuidedSet;
    }

    /// Returns one self-guided projection coefficient without allocating an array.
    ///
    /// @param index the coefficient index in `[0, 2)`
    /// @return the selected projection coefficient
    public int selfGuidedProjectionCoefficient(int index) {
        return switch (Objects.checkIndex(index, 2)) {
            case 0 -> selfGuidedProjectionCoefficient0;
            case 1 -> selfGuidedProjectionCoefficient1;
            default -> throw new AssertionError();
        };
    }
}
