#pragma once
#include <cstdint>

// xorshift32 (Marsaglia): a few instructions, no lock, its own state in each
// effect. For dither and jitter on the audio thread, where rand() will not do
// — bionic's takes a lock — and statistical quality beyond white noise is not
// needed.
class FastRandom {
public:
    explicit FastRandom(uint32_t seed = 0x9E3779B9u) : state_(seed != 0 ? seed : 1u) {}

    uint32_t next() {
        state_ ^= state_ << 13;
        state_ ^= state_ >> 17;
        state_ ^= state_ << 5;
        return state_;
    }

    // Uniform in [0, 1), from the top 24 bits.
    float uniform() { return static_cast<float>(next() >> 8) * (1.0f / 16777216.0f); }

    // Uniform in [-1, 1).
    float bipolar() { return uniform() * 2.0f - 1.0f; }

private:
    uint32_t state_;
};
