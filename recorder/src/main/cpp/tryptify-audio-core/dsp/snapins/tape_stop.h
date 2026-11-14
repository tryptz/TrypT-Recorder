#pragma once
#include "snapin_processor.h"
#include "delay_line.h"
#include <cmath>
#include <algorithm>

// Simulates tape slowdown/speedup via variable-rate playback.
class TapeStopProcessor : public SnapinProcessor {
public:
    enum Params {
        PLAY = 0, STOP_TIME, START_TIME, CURVE, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        // 5 seconds buffer for slowdown accumulation
        int maxSamples = static_cast<int>(5.0 * sampleRate) + 16;
        bufL_.prepare(maxSamples);
        bufR_.prepare(maxSamples);
        currentRate_ = 1.0f;
        lag_ = 0.0;
        catchup_ = 0;
        maxLag_ = static_cast<double>(maxSamples - 8);
        catchupSamples_ = std::max(1, static_cast<int>(0.01 * sampleRate));
        playing_ = true;
    }

    void process(float* left, float* right, int numFrames) override {
        float targetRate = playing_ ? 1.0f : 0.0f;
        float transTimeMs = playing_ ? startTimeMs_ : stopTimeMs_;
        float transTimeSamples = transTimeMs * 0.001f * static_cast<float>(sampleRate_);
        float rateStep = 1.0f / std::max(1.0f, transTimeSamples);

        // Curve: 0% = linear, 100% = exponential
        float curveAmt = curve_ / 100.0f;

        for (int i = 0; i < numFrames; i++) {
            // Write input
            bufL_.write(left[i]);
            bufR_.write(right[i]);

            // Ramp rate towards target
            if (currentRate_ < targetRate) {
                currentRate_ += rateStep;
                if (currentRate_ > targetRate) currentRate_ = targetRate;
            } else if (currentRate_ > targetRate) {
                currentRate_ -= rateStep;
                if (currentRate_ < targetRate) currentRate_ = targetRate;
            }

            // Apply curve shaping to rate
            float shapedRate = currentRate_;
            if (curveAmt > 0.0f) {
                // Exponential curve
                float linear = currentRate_;
                float exponential = (currentRate_ > 0.001f)
                    ? std::pow(currentRate_, 2.0f) : 0.0f;
                shapedRate = linear * (1.0f - curveAmt) + exponential * curveAmt;
            }

            // The read head runs at shapedRate while the write head runs at 1,
            // so it falls behind by (1 - rate) each sample: that lag is how far
            // back the tape is playing from. Slowing down plays ever older
            // audio ever slower, which is the pitch drop.
            if (shapedRate <= 0.001f) {
                // Stopped: silence. The next start begins from the live input.
                lag_ = 0.0;
                catchup_ = 0;
                left[i] = 0.0f;
                right[i] = 0.0f;
                continue;
            }
            lag_ = std::min(lag_ + 1.0 - static_cast<double>(shapedRate), maxLag_);
            float tapeL = bufL_.readLinear(static_cast<float>(lag_));
            float tapeR = bufR_.readLinear(static_cast<float>(lag_));

            // Back at full speed but still behind: crossfade to the live input
            // over 10 ms instead of jumping, then play live.
            if (shapedRate >= 1.0f && lag_ > 0.0) {
                if (catchup_ == 0) catchup_ = catchupSamples_;
                float live = 1.0f - static_cast<float>(--catchup_) / static_cast<float>(catchupSamples_);
                tapeL += (left[i] - tapeL) * live;
                tapeR += (right[i] - tapeR) * live;
                if (catchup_ == 0) lag_ = 0.0;
            }
            left[i] = tapeL;
            right[i] = tapeR;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case PLAY:
                playing_ = value > 0.5f;
                break;
            case STOP_TIME:  stopTimeMs_ = std::max(50.0f, std::min(5000.0f, value)); break;
            case START_TIME: startTimeMs_ = std::max(50.0f, std::min(5000.0f, value)); break;
            case CURVE:      curve_ = std::max(0.0f, std::min(100.0f, value)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case PLAY:       return playing_ ? 1.0f : 0.0f;
            case STOP_TIME:  return stopTimeMs_;
            case START_TIME: return startTimeMs_;
            case CURVE:      return curve_;
            default: return 0.0f;
        }
    }

    void reset() override {
        bufL_.reset(); bufR_.reset();
        currentRate_ = 1.0f;
        lag_ = 0.0;
        catchup_ = 0;
        playing_ = true;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Tape Stop"; }
    SnapinType getType() const override { return SnapinType::TAPE_STOP; }

private:
    bool playing_ = true;
    float stopTimeMs_ = 500.0f;
    float startTimeMs_ = 500.0f;
    float curve_ = 50.0f;

    DelayLine bufL_, bufR_;
    float currentRate_ = 1.0f;
    double lag_ = 0.0;
    double maxLag_ = 0.0;
    int catchup_ = 0;
    int catchupSamples_ = 480;
};
