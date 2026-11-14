#pragma once
#include "snapin_processor.h"
#include "crossfade_buffer.h"
#include <cmath>
#include <algorithm>
#include <cstdlib>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Granular pitch shifter with overlap-add crossfading.
class PitchShifterProcessor : public SnapinProcessor {
public:
    enum Params {
        PITCH = 0, JITTER, GRAIN_SIZE, MIX, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        // The longest delay a grain reads from: a 200 ms grain at +24 semitones
        // (ratio 4) reaches back 3 grains. The jitter adds a tenth of one.
        int maxSamples = static_cast<int>(0.2 * 3.1 * sampleRate) + 16;
        bufL_.prepare(maxSamples);
        bufR_.prepare(maxSamples);
        grainPhase_ = 0.0f;
        readOffsetL_ = 0.0f;
        readOffsetR_ = 0.0f;
    }

    void process(float* left, float* right, int numFrames) override {
        float ratio = std::pow(2.0f, pitch_ / 12.0f);
        float rateOffset = ratio - 1.0f;  // How much faster/slower to read
        int grainSamples = static_cast<int>(grainMs_ * 0.001f * static_cast<float>(sampleRate_));
        grainSamples = std::max(64, grainSamples);
        float grainInc = 1.0f / static_cast<float>(grainSamples);
        float m = mix_ / 100.0f;

        for (int i = 0; i < numFrames; i++) {
            bufL_.write(left[i]);
            bufR_.write(right[i]);

            // Two overlapping grains for crossfade
            float phase1 = grainPhase_;
            float phase2 = grainPhase_ + 0.5f;
            if (phase2 >= 1.0f) phase2 -= 1.0f;

            // Hann windows
            float win1 = CrossfadeBuffer::hannWindow(phase1);
            float win2 = CrossfadeBuffer::hannWindow(phase2);

            // Each grain reads from a delay that changes by |ratio - 1| per
            // sample: falling from its longest to 0 to shift up, rising from 0
            // to its longest to shift down. The read point then moves at
            // exactly `ratio`, and the Hann windows hide each grain's jump.
            float span = static_cast<float>(grainSamples) * std::fabs(rateOffset);
            float delay1 = (rateOffset > 0.0f ? 1.0f - phase1 : phase1) * span;
            float delay2 = (rateOffset > 0.0f ? 1.0f - phase2 : phase2) * span;

            if (jitter_ > 0.0f) {
                float j = (static_cast<float>(rand()) / RAND_MAX) * jitter_ / 100.0f * grainSamples * 0.1f;
                delay1 += j;
                delay2 += j;
            }

            float wetL = readAt(bufL_, delay1) * win1 + readAt(bufL_, delay2) * win2;
            float wetR = readAt(bufR_, delay1) * win1 + readAt(bufR_, delay2) * win2;

            left[i]  = left[i] * (1.0f - m) + wetL * m;
            right[i] = right[i] * (1.0f - m) + wetR * m;

            grainPhase_ += grainInc;
            if (grainPhase_ >= 1.0f) grainPhase_ -= 1.0f;
        }
    }

    /** Linear interpolation between the two samples around a fractional delay. */
    static float readAt(const CrossfadeBuffer& buffer, float delay) {
        delay = std::max(0.0f, std::min(delay, static_cast<float>(buffer.getMaxSegment() - 2)));
        int whole = static_cast<int>(delay);
        float frac = delay - static_cast<float>(whole);
        return buffer.readReverse(whole) * (1.0f - frac) + buffer.readReverse(whole + 1) * frac;
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case PITCH:      pitch_ = std::max(-24.0f, std::min(24.0f, value)); break;
            case JITTER:     jitter_ = std::max(0.0f, std::min(100.0f, value)); break;
            case GRAIN_SIZE: grainMs_ = std::max(10.0f, std::min(200.0f, value)); break;
            case MIX:        mix_ = std::max(0.0f, std::min(100.0f, value)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case PITCH:      return pitch_;
            case JITTER:     return jitter_;
            case GRAIN_SIZE: return grainMs_;
            case MIX:        return mix_;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Pitch Shifter"; }
    SnapinType getType() const override { return SnapinType::PITCH_SHIFTER; }

private:
    float pitch_ = 0.0f;
    float jitter_ = 0.0f;
    float grainMs_ = 50.0f;
    float mix_ = 100.0f;

    CrossfadeBuffer bufL_, bufR_;
    float grainPhase_ = 0.0f;
    float readOffsetL_ = 0.0f, readOffsetR_ = 0.0f;
};
