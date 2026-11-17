#pragma once
#include <vector>
#include <cstring>
#include <algorithm>

// Pre-delay ring buffer for lookahead limiters/gates.
class LookaheadBuffer {
public:
    // Allocates room for [delaySamples] and delays by that much.
    void prepare(int delaySamples) {
        delay_ = std::max(0, delaySamples);
        buffer_.assign(static_cast<size_t>(delay_), 0.0f);
        writePos_ = 0;
    }

    // A shorter or equal delay within what prepare() made room for, without
    // allocating: safe on the audio thread.
    void setDelay(int delaySamples) {
        delay_ = std::max(0, std::min(delaySamples, static_cast<int>(buffer_.size())));
        if (writePos_ >= delay_) writePos_ = 0;
    }

    void reset() {
        std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        writePos_ = 0;
    }

    float process(float in) {
        if (delay_ == 0) return in;
        float out = buffer_[writePos_];
        buffer_[writePos_] = in;
        writePos_ = (writePos_ + 1) % delay_;
        return out;
    }

    int getDelay() const { return delay_; }

private:
    std::vector<float> buffer_;
    int writePos_ = 0;
    int delay_ = 0;
};
