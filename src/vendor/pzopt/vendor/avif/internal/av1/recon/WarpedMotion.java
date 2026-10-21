// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.MotionVector;

import java.util.Arrays;
import java.util.Objects;

/// Derives and applies AV1 affine warped-motion models.
///
/// The implementation uses the integer least-squares, shear-parameter normalization, and
/// separable 193-phase interpolation process defined by AV1. Coordinates supplied to [Sample]
/// are relative to the current block origin and use eighth-pel units.

final class WarpedMotion {
    /// The maximum number of projection samples accepted by AV1 local warped motion.
    static final int SAMPLE_CAPACITY = 8;

    /// The reciprocal lookup table used by AV1 affine-model division.
    private static final int [] DIVISOR_LOOKUP = {
            16384, 16320, 16257, 16194, 16132, 16070, 16009, 15948, 15888, 15828, 15768,
            15709, 15650, 15592, 15534, 15477, 15420, 15364, 15308, 15252, 15197, 15142,
            15087, 15033, 14980, 14926, 14873, 14821, 14769, 14717, 14665, 14614, 14564,
            14513, 14463, 14413, 14364, 14315, 14266, 14218, 14170, 14122, 14075, 14028,
            13981, 13935, 13888, 13843, 13797, 13752, 13707, 13662, 13618, 13574, 13530,
            13487, 13443, 13400, 13358, 13315, 13273, 13231, 13190, 13148, 13107, 13066,
            13026, 12985, 12945, 12906, 12866, 12827, 12788, 12749, 12710, 12672, 12633,
            12596, 12558, 12520, 12483, 12446, 12409, 12373, 12336, 12300, 12264, 12228,
            12193, 12157, 12122, 12087, 12053, 12018, 11984, 11950, 11916, 11882, 11848,
            11815, 11782, 11749, 11716, 11683, 11651, 11619, 11586, 11555, 11523, 11491,
            11460, 11429, 11398, 11367, 11336, 11305, 11275, 11245, 11215, 11185, 11155,
            11125, 11096, 11067, 11038, 11009, 10980, 10951, 10923, 10894, 10866, 10838,
            10810, 10782, 10755, 10727, 10700, 10673, 10645, 10618, 10592, 10565, 10538,
            10512, 10486, 10460, 10434, 10408, 10382, 10356, 10331, 10305, 10280, 10255,
            10230, 10205, 10180, 10156, 10131, 10107, 10082, 10058, 10034, 10010, 9986,
            9963, 9939, 9916, 9892, 9869, 9846, 9823, 9800, 9777, 9754, 9732,
            9709, 9687, 9664, 9642, 9620, 9598, 9576, 9554, 9533, 9511, 9489,
            9468, 9447, 9425, 9404, 9383, 9362, 9341, 9321, 9300, 9279, 9259,
            9239, 9218, 9198, 9178, 9158, 9138, 9118, 9098, 9079, 9059, 9039,
            9020, 9001, 8981, 8962, 8943, 8924, 8905, 8886, 8867, 8849, 8830,
            8812, 8793, 8775, 8756, 8738, 8720, 8702, 8684, 8666, 8648, 8630,
            8613, 8595, 8577, 8560, 8542, 8525, 8508, 8490, 8473, 8456, 8439,
            8422, 8405, 8389, 8372, 8355, 8339, 8322, 8306, 8289, 8273, 8257,
            8240, 8224, 8208, 8192
    };

    /// The normative AV1 warped-motion filters for phases in `[-1, 2]`.
    private static final int [] [] FILTERS = {
            // [-1, 0)
            {0, 0, 127, 1, 0, 0, 0, 0}, {0, -1, 127, 2, 0, 0, 0, 0},
            {1, -3, 127, 4, -1, 0, 0, 0}, {1, -4, 126, 6, -2, 1, 0, 0},
            {1, -5, 126, 8, -3, 1, 0, 0}, {1, -6, 125, 11, -4, 1, 0, 0},
            {1, -7, 124, 13, -4, 1, 0, 0}, {2, -8, 123, 15, -5, 1, 0, 0},
            {2, -9, 122, 18, -6, 1, 0, 0}, {2, -10, 121, 20, -6, 1, 0, 0},
            {2, -11, 120, 22, -7, 2, 0, 0}, {2, -12, 119, 25, -8, 2, 0, 0},
            {3, -13, 117, 27, -8, 2, 0, 0}, {3, -13, 116, 29, -9, 2, 0, 0},
            {3, -14, 114, 32, -10, 3, 0, 0}, {3, -15, 113, 35, -10, 2, 0, 0},
            {3, -15, 111, 37, -11, 3, 0, 0}, {3, -16, 109, 40, -11, 3, 0, 0},
            {3, -16, 108, 42, -12, 3, 0, 0}, {4, -17, 106, 45, -13, 3, 0, 0},
            {4, -17, 104, 47, -13, 3, 0, 0}, {4, -17, 102, 50, -14, 3, 0, 0},
            {4, -17, 100, 52, -14, 3, 0, 0}, {4, -18, 98, 55, -15, 4, 0, 0},
            {4, -18, 96, 58, -15, 3, 0, 0}, {4, -18, 94, 60, -16, 4, 0, 0},
            {4, -18, 91, 63, -16, 4, 0, 0}, {4, -18, 89, 65, -16, 4, 0, 0},
            {4, -18, 87, 68, -17, 4, 0, 0}, {4, -18, 85, 70, -17, 4, 0, 0},
            {4, -18, 82, 73, -17, 4, 0, 0}, {4, -18, 80, 75, -17, 4, 0, 0},
            {4, -18, 78, 78, -18, 4, 0, 0}, {4, -17, 75, 80, -18, 4, 0, 0},
            {4, -17, 73, 82, -18, 4, 0, 0}, {4, -17, 70, 85, -18, 4, 0, 0},
            {4, -17, 68, 87, -18, 4, 0, 0}, {4, -16, 65, 89, -18, 4, 0, 0},
            {4, -16, 63, 91, -18, 4, 0, 0}, {4, -16, 60, 94, -18, 4, 0, 0},
            {3, -15, 58, 96, -18, 4, 0, 0}, {4, -15, 55, 98, -18, 4, 0, 0},
            {3, -14, 52, 100, -17, 4, 0, 0}, {3, -14, 50, 102, -17, 4, 0, 0},
            {3, -13, 47, 104, -17, 4, 0, 0}, {3, -13, 45, 106, -17, 4, 0, 0},
            {3, -12, 42, 108, -16, 3, 0, 0}, {3, -11, 40, 109, -16, 3, 0, 0},
            {3, -11, 37, 111, -15, 3, 0, 0}, {2, -10, 35, 113, -15, 3, 0, 0},
            {3, -10, 32, 114, -14, 3, 0, 0}, {2, -9, 29, 116, -13, 3, 0, 0},
            {2, -8, 27, 117, -13, 3, 0, 0}, {2, -8, 25, 119, -12, 2, 0, 0},
            {2, -7, 22, 120, -11, 2, 0, 0}, {1, -6, 20, 121, -10, 2, 0, 0},
            {1, -6, 18, 122, -9, 2, 0, 0}, {1, -5, 15, 123, -8, 2, 0, 0},
            {1, -4, 13, 124, -7, 1, 0, 0}, {1, -4, 11, 125, -6, 1, 0, 0},
            {1, -3, 8, 126, -5, 1, 0, 0}, {1, -2, 6, 126, -4, 1, 0, 0},
            {0, -1, 4, 127, -3, 1, 0, 0}, {0, 0, 2, 127, -1, 0, 0, 0},
            // [0, 1)
            {0, 0, 0, 127, 1, 0, 0, 0}, {0, 0, -1, 127, 2, 0, 0, 0},
            {0, 1, -3, 127, 4, -2, 1, 0}, {0, 1, -5, 127, 6, -2, 1, 0},
            {0, 2, -6, 126, 8, -3, 1, 0}, {-1, 2, -7, 126, 11, -4, 2, -1},
            {-1, 3, -8, 125, 13, -5, 2, -1}, {-1, 3, -10, 124, 16, -6, 3, -1},
            {-1, 4, -11, 123, 18, -7, 3, -1}, {-1, 4, -12, 122, 20, -7, 3, -1},
            {-1, 4, -13, 121, 23, -8, 3, -1}, {-2, 5, -14, 120, 25, -9, 4, -1},
            {-1, 5, -15, 119, 27, -10, 4, -1}, {-1, 5, -16, 118, 30, -11, 4, -1},
            {-2, 6, -17, 116, 33, -12, 5, -1}, {-2, 6, -17, 114, 35, -12, 5, -1},
            {-2, 6, -18, 113, 38, -13, 5, -1}, {-2, 7, -19, 111, 41, -14, 6, -2},
            {-2, 7, -19, 110, 43, -15, 6, -2}, {-2, 7, -20, 108, 46, -15, 6, -2},
            {-2, 7, -20, 106, 49, -16, 6, -2}, {-2, 7, -21, 104, 51, -16, 7, -2},
            {-2, 7, -21, 102, 54, -17, 7, -2}, {-2, 8, -21, 100, 56, -18, 7, -2},
            {-2, 8, -22, 98, 59, -18, 7, -2}, {-2, 8, -22, 96, 62, -19, 7, -2},
            {-2, 8, -22, 94, 64, -19, 7, -2}, {-2, 8, -22, 91, 67, -20, 8, -2},
            {-2, 8, -22, 89, 69, -20, 8, -2}, {-2, 8, -22, 87, 72, -21, 8, -2},
            {-2, 8, -21, 84, 74, -21, 8, -2}, {-2, 8, -22, 82, 77, -21, 8, -2},
            {-2, 8, -21, 79, 79, -21, 8, -2}, {-2, 8, -21, 77, 82, -22, 8, -2},
            {-2, 8, -21, 74, 84, -21, 8, -2}, {-2, 8, -21, 72, 87, -22, 8, -2},
            {-2, 8, -20, 69, 89, -22, 8, -2}, {-2, 8, -20, 67, 91, -22, 8, -2},
            {-2, 7, -19, 64, 94, -22, 8, -2}, {-2, 7, -19, 62, 96, -22, 8, -2},
            {-2, 7, -18, 59, 98, -22, 8, -2}, {-2, 7, -18, 56, 100, -21, 8, -2},
            {-2, 7, -17, 54, 102, -21, 7, -2}, {-2, 7, -16, 51, 104, -21, 7, -2},
            {-2, 6, -16, 49, 106, -20, 7, -2}, {-2, 6, -15, 46, 108, -20, 7, -2},
            {-2, 6, -15, 43, 110, -19, 7, -2}, {-2, 6, -14, 41, 111, -19, 7, -2},
            {-1, 5, -13, 38, 113, -18, 6, -2}, {-1, 5, -12, 35, 114, -17, 6, -2},
            {-1, 5, -12, 33, 116, -17, 6, -2}, {-1, 4, -11, 30, 118, -16, 5, -1},
            {-1, 4, -10, 27, 119, -15, 5, -1}, {-1, 4, -9, 25, 120, -14, 5, -2},
            {-1, 3, -8, 23, 121, -13, 4, -1}, {-1, 3, -7, 20, 122, -12, 4, -1},
            {-1, 3, -7, 18, 123, -11, 4, -1}, {-1, 3, -6, 16, 124, -10, 3, -1},
            {-1, 2, -5, 13, 125, -8, 3, -1}, {-1, 2, -4, 11, 126, -7, 2, -1},
            {0, 1, -3, 8, 126, -6, 2, 0}, {0, 1, -2, 6, 127, -5, 1, 0},
            {0, 1, -2, 4, 127, -3, 1, 0}, {0, 0, 0, 2, 127, -1, 0, 0},
            // [1, 2)
            {0, 0, 0, 1, 127, 0, 0, 0}, {0, 0, 0, -1, 127, 2, 0, 0},
            {0, 0, 1, -3, 127, 4, -1, 0}, {0, 0, 1, -4, 126, 6, -2, 1},
            {0, 0, 1, -5, 126, 8, -3, 1}, {0, 0, 1, -6, 125, 11, -4, 1},
            {0, 0, 1, -7, 124, 13, -4, 1}, {0, 0, 2, -8, 123, 15, -5, 1},
            {0, 0, 2, -9, 122, 18, -6, 1}, {0, 0, 2, -10, 121, 20, -6, 1},
            {0, 0, 2, -11, 120, 22, -7, 2}, {0, 0, 2, -12, 119, 25, -8, 2},
            {0, 0, 3, -13, 117, 27, -8, 2}, {0, 0, 3, -13, 116, 29, -9, 2},
            {0, 0, 3, -14, 114, 32, -10, 3}, {0, 0, 3, -15, 113, 35, -10, 2},
            {0, 0, 3, -15, 111, 37, -11, 3}, {0, 0, 3, -16, 109, 40, -11, 3},
            {0, 0, 3, -16, 108, 42, -12, 3}, {0, 0, 4, -17, 106, 45, -13, 3},
            {0, 0, 4, -17, 104, 47, -13, 3}, {0, 0, 4, -17, 102, 50, -14, 3},
            {0, 0, 4, -17, 100, 52, -14, 3}, {0, 0, 4, -18, 98, 55, -15, 4},
            {0, 0, 4, -18, 96, 58, -15, 3}, {0, 0, 4, -18, 94, 60, -16, 4},
            {0, 0, 4, -18, 91, 63, -16, 4}, {0, 0, 4, -18, 89, 65, -16, 4},
            {0, 0, 4, -18, 87, 68, -17, 4}, {0, 0, 4, -18, 85, 70, -17, 4},
            {0, 0, 4, -18, 82, 73, -17, 4}, {0, 0, 4, -18, 80, 75, -17, 4},
            {0, 0, 4, -18, 78, 78, -18, 4}, {0, 0, 4, -17, 75, 80, -18, 4},
            {0, 0, 4, -17, 73, 82, -18, 4}, {0, 0, 4, -17, 70, 85, -18, 4},
            {0, 0, 4, -17, 68, 87, -18, 4}, {0, 0, 4, -16, 65, 89, -18, 4},
            {0, 0, 4, -16, 63, 91, -18, 4}, {0, 0, 4, -16, 60, 94, -18, 4},
            {0, 0, 3, -15, 58, 96, -18, 4}, {0, 0, 4, -15, 55, 98, -18, 4},
            {0, 0, 3, -14, 52, 100, -17, 4}, {0, 0, 3, -14, 50, 102, -17, 4},
            {0, 0, 3, -13, 47, 104, -17, 4}, {0, 0, 3, -13, 45, 106, -17, 4},
            {0, 0, 3, -12, 42, 108, -16, 3}, {0, 0, 3, -11, 40, 109, -16, 3},
            {0, 0, 3, -11, 37, 111, -15, 3}, {0, 0, 2, -10, 35, 113, -15, 3},
            {0, 0, 3, -10, 32, 114, -14, 3}, {0, 0, 2, -9, 29, 116, -13, 3},
            {0, 0, 2, -8, 27, 117, -13, 3}, {0, 0, 2, -8, 25, 119, -12, 2},
            {0, 0, 2, -7, 22, 120, -11, 2}, {0, 0, 1, -6, 20, 121, -10, 2},
            {0, 0, 1, -6, 18, 122, -9, 2}, {0, 0, 1, -5, 15, 123, -8, 2},
            {0, 0, 1, -4, 13, 124, -7, 1}, {0, 0, 1, -4, 11, 125, -6, 1},
            {0, 0, 1, -3, 8, 126, -5, 1}, {0, 0, 1, -2, 6, 126, -4, 1},
            {0, 0, 0, -1, 4, 127, -3, 1}, {0, 0, 0, 0, 2, 127, -1, 0},
            // Dummy row replicating phase 191.
            {0, 0, 0, 0, 2, 127, -1, 0}
    };

    /// Prevents instantiation.
    private WarpedMotion() {
    }

    /// One projectable neighbor motion sample.
    ///
    /// @param sourceX the neighbor center X before applying its motion vector
    /// @param sourceY the neighbor center Y before applying its motion vector
    /// @param destinationX the neighbor center X after applying its motion vector
    /// @param destinationY the neighbor center Y after applying its motion vector
    record Sample(int sourceX, int sourceY, int destinationX, int destinationY) {
    }

    /// One normalized local affine warped-motion model.
    
    static final class Model {
        /// Whether the derived model passed AV1's affine shear constraints.
        private final boolean affine;

        /// The six AV1 affine matrix entries.
        private final int [] matrix;

        /// The normalized horizontal X derivative.
        private final int alpha;

        /// The normalized horizontal Y derivative.
        private final int beta;

        /// The normalized vertical X derivative.
        private final int gamma;

        /// The normalized vertical Y derivative.
        private final int delta;

        /// Creates one normalized warped-motion model.
        ///
        /// @param affine whether the model passed AV1's affine shear constraints
        /// @param matrix the six affine matrix entries
        /// @param alpha the normalized horizontal X derivative
        /// @param beta the normalized horizontal Y derivative
        /// @param gamma the normalized vertical X derivative
        /// @param delta the normalized vertical Y derivative
        private Model(boolean affine, int[] matrix, int alpha, int beta, int gamma, int delta) {
            this.affine = affine;
            this.matrix = Arrays.copyOf(Objects.requireNonNull(matrix, "matrix"), matrix.length);
            this.alpha = alpha;
            this.beta = beta;
            this.gamma = gamma;
            this.delta = delta;
        }

        /// Returns whether this model can use affine warped prediction.
        ///
        /// @return whether this model can use affine warped prediction
        boolean affine() {
            return affine;
        }

        /// Returns one affine matrix entry.
        ///
        /// @param index the matrix index in `[0, 5]`
        /// @return the selected matrix entry
        int matrix(int index) {
            return matrix[Objects.checkIndex(index, matrix.length)];
        }

        /// Returns the normalized horizontal X derivative.
        ///
        /// @return the normalized horizontal X derivative
        int alpha() {
            return alpha;
        }

        /// Returns the normalized horizontal Y derivative.
        ///
        /// @return the normalized horizontal Y derivative
        int beta() {
            return beta;
        }

        /// Returns the normalized vertical X derivative.
        ///
        /// @return the normalized vertical X derivative
        int gamma() {
            return gamma;
        }

        /// Returns the normalized vertical Y derivative.
        ///
        /// @return the normalized vertical Y derivative
        int delta() {
            return delta;
        }
    }

    /// Derives one AV1 local affine model from projectable neighbor samples.
    ///
    /// @param samples the projectable motion samples
    /// @param sampleCount the number of populated samples
    /// @param blockWidth4 the current block width in 4x4 units
    /// @param blockHeight4 the current block height in 4x4 units
    /// @param motionVector the current block motion vector
    /// @param blockX4 the current block X origin in 4x4 units
    /// @param blockY4 the current block Y origin in 4x4 units
    /// @return the derived and normalized model
    static Model derive(
            Sample[] samples,
            int sampleCount,
            int blockWidth4,
            int blockHeight4,
            MotionVector motionVector,
            int blockX4,
            int blockY4
    ) {
        Sample[] selected = Arrays.copyOf(Objects.requireNonNull(samples, "samples"), sampleCount);
        MotionVector checkedMotionVector = Objects.requireNonNull(motionVector, "motionVector");
        if (sampleCount <= 0 || sampleCount > SAMPLE_CAPACITY) {
            throw new IllegalArgumentException("sampleCount out of range: " + sampleCount);
        }

        int[] differences = new int[sampleCount];
        int accepted = 0;
        int threshold = 4 * clamp(Math.max(blockWidth4, blockHeight4), 4, 28);
        for (int i = 0; i < sampleCount; i++) {
            Sample sample = Objects.requireNonNull(selected[i], "samples[i]");
            int difference = Math.abs(sample.destinationX() - sample.sourceX()
                    - checkedMotionVector.columnEighthPel())
                    + Math.abs(sample.destinationY() - sample.sourceY()
                    - checkedMotionVector.rowEighthPel());
            if (difference > threshold) {
                differences[i] = -1;
            } else {
                differences[i] = difference;
                accepted++;
            }
        }
        if (accepted == 0) {
            accepted = 1;
        } else {
            int front = 0;
            int back = sampleCount - 1;
            for (int replaced = 0; replaced < sampleCount - accepted; replaced++, front++, back--) {
                while (differences[front] != -1) {
                    front++;
                }
                while (differences[back] == -1) {
                    back--;
                }
                if (front > back) {
                    break;
                }
                differences[front] = differences[back];
                selected[front] = selected[back];
            }
        }

        int[] matrix = new int[6];
        if (!findAffine(
                selected,
                accepted,
                blockWidth4,
                blockHeight4,
                checkedMotionVector,
                blockX4,
                blockY4,
                matrix
        )) {
            return new Model(false, matrix, 0, 0, 0, 0);
        }
        return normalizeShear(matrix);
    }

    /// Creates a normalized affine model from decoded frame-level global-motion parameters.
    ///
    /// @param parameters the decoded rotation-zoom or affine global-motion parameters
    /// @return the normalized global warped-motion model
    static Model fromGlobalMotion(FrameHeader.GlobalMotionParams parameters) {
        FrameHeader.GlobalMotionParams nonNullParameters = Objects.requireNonNull(parameters, "parameters");
        if (nonNullParameters.type() != FrameHeader.GlobalMotionType.ROTATION_ZOOM
                && nonNullParameters.type() != FrameHeader.GlobalMotionType.AFFINE) {
            throw new IllegalArgumentException("Global motion is not affine: " + nonNullParameters.type());
        }
        int[] matrix = new int[6];
        for (int index = 0; index < matrix.length; index++) {
            matrix[index] = nonNullParameters.matrix(index);
        }
        return normalizeShear(matrix);
    }

    /// Applies one affine warped model to a plane region.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the destination block X origin in plane samples
    /// @param destinationY the destination block Y origin in plane samples
    /// @param visibleWidth the visible destination width in plane samples
    /// @param visibleHeight the visible destination height in plane samples
    /// @param codedWidth the coded block width in plane samples
    /// @param codedHeight the coded block height in plane samples
    /// @param blockX4 the luma block X origin in 4x4 units
    /// @param blockY4 the luma block Y origin in 4x4 units
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param model the normalized affine model
    static void predictPlane(
            MutablePlaneBuffer destinationPlane,
            PaddedPlane referencePlane,
            int destinationX,
            int destinationY,
            int visibleWidth,
            int visibleHeight,
            int codedWidth,
            int codedHeight,
            int blockX4,
            int blockY4,
            int subsamplingX,
            int subsamplingY,
            Model model
    ) {
        MutablePlaneBuffer checkedDestination = Objects.requireNonNull(destinationPlane, "destinationPlane");
        PaddedPlane checkedReference = Objects.requireNonNull(referencePlane, "referencePlane");
        Model checkedModel = Objects.requireNonNull(model, "model");
        int[] predictions = predictPlaneSamples(
                checkedReference,
                visibleWidth,
                visibleHeight,
                codedWidth,
                codedHeight,
                blockX4,
                blockY4,
                subsamplingX,
                subsamplingY,
                checkedDestination.bitDepth(),
                0,
                checkedModel
        );
        for (int y = 0; y < visibleHeight; y++) {
            for (int x = 0; x < visibleWidth; x++) {
                checkedDestination.setSample(
                        destinationX + x,
                        destinationY + y,
                        clamp(predictions[y * visibleWidth + x], 0, checkedDestination.maxSampleValue())
                );
            }
        }
    }

    /// Returns affine warped predictors retaining AV1's compound-inter fractional precision.
    ///
    /// The returned caller-owned array is row-major and contains `visibleWidth * visibleHeight`
    /// signed predictors. Each value retains four fractional bits for 8- and 10-bit input, or two
    /// fractional bits for 12-bit input. Values are neither clipped nor rounded to sample precision.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param visibleWidth the visible prediction width in plane samples
    /// @param visibleHeight the visible prediction height in plane samples
    /// @param codedWidth the coded block width in plane samples
    /// @param codedHeight the coded block height in plane samples
    /// @param blockX4 the luma block X origin in 4x4 units
    /// @param blockY4 the luma block Y origin in 4x4 units
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param bitDepth the decoded sample bit depth
    /// @param model the normalized affine model
    /// @return the row-major higher-precision compound predictors
    static int[] predictCompoundPlane(
            PaddedPlane referencePlane,
            int visibleWidth,
            int visibleHeight,
            int codedWidth,
            int codedHeight,
            int blockX4,
            int blockY4,
            int subsamplingX,
            int subsamplingY,
            int bitDepth,
            Model model
    ) {
        int retainedBits = bitDepth == 12 ? 2 : 4;
        return predictPlaneSamples(
                referencePlane,
                visibleWidth,
                visibleHeight,
                codedWidth,
                codedHeight,
                blockX4,
                blockY4,
                subsamplingX,
                subsamplingY,
                bitDepth,
                retainedBits,
                model
        );
    }

    /// Computes one affine warped plane while retaining the requested output precision.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param visibleWidth the visible prediction width in plane samples
    /// @param visibleHeight the visible prediction height in plane samples
    /// @param codedWidth the coded block width in plane samples
    /// @param codedHeight the coded block height in plane samples
    /// @param blockX4 the luma block X origin in 4x4 units
    /// @param blockY4 the luma block Y origin in 4x4 units
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param bitDepth the decoded sample bit depth
    /// @param retainedBits the number of fractional bits retained in each result
    /// @param model the normalized affine model
    /// @return the row-major signed predictors at the requested precision
    private static int[] predictPlaneSamples(
            PaddedPlane referencePlane,
            int visibleWidth,
            int visibleHeight,
            int codedWidth,
            int codedHeight,
            int blockX4,
            int blockY4,
            int subsamplingX,
            int subsamplingY,
            int bitDepth,
            int retainedBits,
            Model model
    ) {
        PaddedPlane checkedReference = Objects.requireNonNull(referencePlane, "referencePlane");
        Model checkedModel = Objects.requireNonNull(model, "model");
        if (!checkedModel.affine()) {
            throw new IllegalArgumentException("Warped prediction requires an affine model");
        }
        int intermediateBits = bitDepth == 12 ? 2 : 4;
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("Unsupported warped-prediction bit depth: " + bitDepth);
        }
        if (retainedBits < 0 || retainedBits > intermediateBits) {
            throw new IllegalArgumentException("retainedBits out of range: " + retainedBits);
        }
        int[] predictions = new int[visibleWidth * visibleHeight];
        for (int blockY = 0; blockY < codedHeight; blockY += 8) {
            int sourceY = blockY4 * 4 + ((blockY + 4) << subsamplingY);
            long matrix3Y = (long) checkedModel.matrix(3) * sourceY + checkedModel.matrix(0);
            long matrix5Y = (long) checkedModel.matrix(5) * sourceY + checkedModel.matrix(1);
            for (int blockX = 0; blockX < codedWidth; blockX += 8) {
                int sourceX = blockX4 * 4 + ((blockX + 4) << subsamplingX);
                long mappedX = ((long) checkedModel.matrix(2) * sourceX + matrix3Y) >> subsamplingX;
                long mappedY = ((long) checkedModel.matrix(4) * sourceX + matrix5Y) >> subsamplingY;
                int integerX = (int) (mappedX >> 16) - 4;
                int phaseX = (((int) mappedX & 0xFFFF)
                        - checkedModel.alpha() * 4
                        - checkedModel.beta() * 7) & ~0x3F;
                int integerY = (int) (mappedY >> 16) - 4;
                int phaseY = (((int) mappedY & 0xFFFF)
                        - checkedModel.gamma() * 4
                        - checkedModel.delta() * 4) & ~0x3F;
                predict8x8(
                        checkedReference,
                        predictions,
                        visibleWidth,
                        blockX,
                        blockY,
                        Math.max(0, Math.min(8, visibleWidth - blockX)),
                        Math.max(0, Math.min(8, visibleHeight - blockY)),
                        integerX,
                        integerY,
                        phaseX,
                        phaseY,
                        checkedModel,
                        intermediateBits,
                        retainedBits
                );
            }
        }
        return predictions;
    }

    /// Predicts one 8x8 affine warped region.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param predictions the row-major destination predictor array
    /// @param predictionStride the destination predictor row stride
    /// @param destinationX the destination X origin within the predictor array
    /// @param destinationY the destination Y origin within the predictor array
    /// @param visibleWidth the visible width to store
    /// @param visibleHeight the visible height to store
    /// @param sourceX the integer reference X origin
    /// @param sourceY the integer reference Y origin
    /// @param phaseX the starting horizontal warped phase
    /// @param phaseY the starting vertical warped phase
    /// @param model the normalized affine model
    /// @param intermediateBits the horizontal intermediate precision
    /// @param retainedBits the fractional precision retained after vertical filtering
    private static void predict8x8(
            PaddedPlane referencePlane,
            int[] predictions,
            int predictionStride,
            int destinationX,
            int destinationY,
            int visibleWidth,
            int visibleHeight,
            int sourceX,
            int sourceY,
            int phaseX,
            int phaseY,
            Model model,
            int intermediateBits,
            int retainedBits
    ) {
        int[][] intermediate = new int[15][8];
        int horizontalPhase = phaseX;
        for (int y = 0; y < 15; y++, horizontalPhase += model.beta()) {
            int sampleY = clamp(sourceY - 3 + y, 0, referencePlane.height() - 1);
            int pixelPhase = horizontalPhase;
            for (int x = 0; x < 8; x++, pixelPhase += model.alpha()) {
                int[] filter = filter(pixelPhase);
                long sum = 0;
                for (int tap = 0; tap < 8; tap++) {
                    int sampleX = clamp(sourceX + x + tap - 3, 0, referencePlane.width() - 1);
                    sum += (long) filter[tap] * referencePlane.sample(sampleX, sampleY);
                }
                intermediate[y][x] = roundShiftBiased(sum, 7 - intermediateBits);
            }
        }

        int verticalPhase = phaseY;
        for (int y = 0; y < 8; y++, verticalPhase += model.delta()) {
            int pixelPhase = verticalPhase;
            for (int x = 0; x < 8; x++, pixelPhase += model.gamma()) {
                int[] filter = filter(pixelPhase);
                long sum = 0;
                for (int tap = 0; tap < 8; tap++) {
                    sum += (long) filter[tap] * intermediate[y + tap][x];
                }
                if (x < visibleWidth && y < visibleHeight) {
                    predictions[(destinationY + y) * predictionStride + destinationX + x] =
                            roundShiftBiased(sum, 7 + intermediateBits - retainedBits);
                }
            }
        }
    }

    /// Returns the warped filter selected by one fixed-point phase.
    ///
    /// @param phase the phase in AV1 warped-filter precision
    /// @return the selected eight-tap filter
    private static int[] filter(int phase) {
        int index = 64 + ((phase + 512) >> 10);
        if (index < 0 || index >= FILTERS.length) {
            throw new IllegalStateException("Warped-motion filter phase out of range: " + phase);
        }
        return FILTERS[index];
    }

    /// Solves the AV1 integer affine least-squares system.
    ///
    /// @param samples the selected projection samples
    /// @param sampleCount the number of selected samples
    /// @param blockWidth4 the current block width in 4x4 units
    /// @param blockHeight4 the current block height in 4x4 units
    /// @param motionVector the current block motion vector
    /// @param blockX4 the current block X origin in 4x4 units
    /// @param blockY4 the current block Y origin in 4x4 units
    /// @param matrix the destination affine matrix
    /// @return whether the system has a nonzero determinant
    private static boolean findAffine(
            Sample[] samples,
            int sampleCount,
            int blockWidth4,
            int blockHeight4,
            MotionVector motionVector,
            int blockX4,
            int blockY4,
            int[] matrix
    ) {
        int a00 = 0;
        int a01 = 0;
        int a11 = 0;
        int bx0 = 0;
        int bx1 = 0;
        int by0 = 0;
        int by1 = 0;
        int relativeCenterY = (2 * blockHeight4 - 1) * 8;
        int relativeCenterX = (2 * blockWidth4 - 1) * 8;
        int destinationCenterY = relativeCenterY + motionVector.rowEighthPel();
        int destinationCenterX = relativeCenterX + motionVector.columnEighthPel();
        int absoluteCenterY = blockY4 * 4 + 2 * blockHeight4 - 1;
        int absoluteCenterX = blockX4 * 4 + 2 * blockWidth4 - 1;

        for (int i = 0; i < sampleCount; i++) {
            Sample sample = Objects.requireNonNull(samples[i], "samples[i]");
            int destinationX = sample.destinationX() - destinationCenterX;
            int destinationY = sample.destinationY() - destinationCenterY;
            int sourceX = sample.sourceX() - relativeCenterX;
            int sourceY = sample.sourceY() - relativeCenterY;
            if (Math.abs(sourceX - destinationX) < 256 && Math.abs(sourceY - destinationY) < 256) {
                a00 += ((sourceX * sourceX) >> 2) + sourceX * 2 + 8;
                a01 += ((sourceX * sourceY) >> 2) + sourceX + sourceY + 4;
                a11 += ((sourceY * sourceY) >> 2) + sourceY * 2 + 8;
                bx0 += ((sourceX * destinationX) >> 2) + sourceX + destinationX + 8;
                bx1 += ((sourceY * destinationX) >> 2) + sourceY + destinationX + 4;
                by0 += ((sourceX * destinationY) >> 2) + sourceX + destinationY + 4;
                by1 += ((sourceY * destinationY) >> 2) + sourceY + destinationY + 8;
            }
        }

        long determinant = (long) a00 * a11 - (long) a01 * a01;
        if (determinant == 0) {
            return false;
        }
        long absoluteDeterminant = Math.abs(determinant);
        int shift = 63 - Long.numberOfLeadingZeros(absoluteDeterminant);
        long exponentRemainder = absoluteDeterminant - (1L << shift);
        long lookupIndex = shift > 8
                ? (exponentRemainder + (1L << (shift - 9))) >> (shift - 8)
                : exponentRemainder << (8 - shift);
        int inverseDeterminant = DIVISOR_LOOKUP[(int) lookupIndex];
        if (determinant < 0) {
            inverseDeterminant = -inverseDeterminant;
        }
        shift += 14;
        shift -= 16;
        if (shift < 0) {
            inverseDeterminant <<= -shift;
            shift = 0;
        }

        matrix[2] = multiplyAndRoundDiagonal((long) a11 * bx0 - (long) a01 * bx1, inverseDeterminant, shift);
        matrix[3] = multiplyAndRoundNonDiagonal((long) a00 * bx1 - (long) a01 * bx0, inverseDeterminant, shift);
        matrix[4] = multiplyAndRoundNonDiagonal((long) a11 * by0 - (long) a01 * by1, inverseDeterminant, shift);
        matrix[5] = multiplyAndRoundDiagonal((long) a00 * by1 - (long) a01 * by0, inverseDeterminant, shift);
        matrix[0] = clamp(
                motionVector.columnEighthPel() * 0x2000
                        - (absoluteCenterX * (matrix[2] - 0x10000) + absoluteCenterY * matrix[3]),
                -0x800000,
                0x7FFFFF
        );
        matrix[1] = clamp(
                motionVector.rowEighthPel() * 0x2000
                        - (absoluteCenterX * matrix[4] + absoluteCenterY * (matrix[5] - 0x10000)),
                -0x800000,
                0x7FFFFF
        );
        return true;
    }

    /// Normalizes affine shear parameters and checks AV1's validity bounds.
    ///
    /// @param matrix the six affine matrix entries
    /// @return the normalized model
    private static Model normalizeShear(int[] matrix) {
        if (matrix[2] <= 0) {
            return new Model(false, matrix, 0, 0, 0, 0);
        }
        int alpha = clipWarpParameter(matrix[2] - 0x10000);
        int beta = clipWarpParameter(matrix[3]);

        int divisor = Math.abs(matrix[2]);
        int shift = 31 - Integer.numberOfLeadingZeros(divisor);
        int remainder = divisor - (1 << shift);
        int lookupIndex = shift > 8
                ? (remainder + (1 << (shift - 9))) >> (shift - 8)
                : remainder << (8 - shift);
        int inverse = DIVISOR_LOOKUP[lookupIndex];
        if (matrix[2] < 0) {
            inverse = -inverse;
        }
        shift += 14;
        long verticalCross = (long) matrix[4] * 0x10000 * inverse;
        int rounding = (1 << shift) >> 1;
        int gamma = clipWarpParameter(applySign((Math.abs(verticalCross) + rounding) >> shift, verticalCross));
        long diagonalCross = (long) matrix[3] * matrix[4] * inverse;
        int delta = clipWarpParameter(matrix[5]
                - applySign((Math.abs(diagonalCross) + rounding) >> shift, diagonalCross)
                - 0x10000);
        boolean affine = 4 * Math.abs(alpha) + 7 * Math.abs(beta) < 0x10000
                && 4 * Math.abs(gamma) + 4 * Math.abs(delta) < 0x10000;
        return new Model(affine, matrix, alpha, beta, gamma, delta);
    }

    /// Multiplies by a reciprocal and clips one diagonal affine entry.
    ///
    /// @param value the determinant numerator
    /// @param inverseDeterminant the normalized reciprocal
    /// @param shift the reciprocal normalization shift
    /// @return the clipped diagonal entry
    private static int multiplyAndRoundDiagonal(long value, int inverseDeterminant, int shift) {
        long product = value * inverseDeterminant;
        int rounded = applySign((Math.abs(product) + ((1L << shift) >> 1)) >> shift, product);
        return clamp(rounded, 0xE001, 0x11FFF);
    }

    /// Multiplies by a reciprocal and clips one non-diagonal affine entry.
    ///
    /// @param value the determinant numerator
    /// @param inverseDeterminant the normalized reciprocal
    /// @param shift the reciprocal normalization shift
    /// @return the clipped non-diagonal entry
    private static int multiplyAndRoundNonDiagonal(long value, int inverseDeterminant, int shift) {
        long product = value * inverseDeterminant;
        int rounded = applySign((Math.abs(product) + ((1L << shift) >> 1)) >> shift, product);
        return clamp(rounded, -0x1FFF, 0x1FFF);
    }

    /// Clips and quantizes one normalized shear parameter.
    ///
    /// @param value the unnormalized parameter
    /// @return the signed multiple of 64 in AV1's 16-bit parameter range
    private static int clipWarpParameter(int value) {
        int clipped = clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
        int magnitude = (Math.abs(clipped) + 32) >> 6;
        return (clipped < 0 ? -magnitude : magnitude) << 6;
    }

    /// Applies the sign of one value to a nonnegative magnitude.
    ///
    /// @param magnitude the nonnegative magnitude
    /// @param signedValue the value that supplies the sign
    /// @return the signed integer magnitude
    private static int applySign(long magnitude, long signedValue) {
        int result = (int) magnitude;
        return signedValue < 0 ? -result : result;
    }

    /// Rounds one signed value by adding the normative positive bias before shifting.
    ///
    /// @param value the signed value
    /// @param shift the nonnegative right shift
    /// @return the rounded value
    private static int roundShiftBiased(long value, int shift) {
        return (int) ((value + ((1L << shift) >> 1)) >> shift);
    }

    /// Clips one integer to an inclusive range.
    ///
    /// @param value the value to clip
    /// @param minimum the inclusive minimum
    /// @param maximum the inclusive maximum
    /// @return the clipped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
