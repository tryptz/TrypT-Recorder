#pragma once
#include "snapin_processor.h"
#include "delay_line.h"
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <vector>

// Brickwall lookahead limiter.
//
// The audio is delayed by the lookahead, L samples. Each input sample asks
// for a gain r = threshold / peak (1 when under the threshold). The gain
// applied as that sample leaves the delay must be at most its r, and should
// get there smoothly rather than in one step. So:
//
//   1. m = the smallest r of the last L + 1 samples (a sliding minimum, O(1)
//      per sample with a monotone queue). It drops the moment a peak comes in
//      and holds for L + 1 samples, until that peak has left the delay.
//   2. g = m smoothed by two moving averages in a row whose lengths add up to
//      L + 2, so together they average m over exactly the last L + 1 samples.
//      Every one of those m is at most the r of the sample now leaving the
//      delay (each window holds it), so their average is too: the limit
//      holds exactly, and the gain comes down along an S-shaped curve spread
//      over the whole lookahead instead of jumping.
//   3. Release: the gain follows g down at once (g is already smooth) and
//      back up exponentially, which only ever keeps it lower.
//
// Positions in the queue are a free-running unsigned sample counter; only
// differences are taken, so its wrap after 2^32 samples (a day at 48 kHz) is
// harmless.
class LimiterProcessor : public SnapinProcessor {
public:
    enum Params { INPUT_GAIN = 0, THRESHOLD, RELEASE, LOOKAHEAD, OUTPUT_GAIN, NUM_PARAMS };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;

        // Max lookahead = 10 ms
        maxLookaheadSamples_ = static_cast<int>(0.010 * sampleRate) + 1;
        delayL_.prepare(maxLookaheadSamples_);
        delayR_.prepare(maxLookaheadSamples_);

        // A window of at most maxLookahead + 1 samples, plus the one pushed
        // before the oldest is dropped.
        queueValue_.assign(static_cast<size_t>(maxLookaheadSamples_) + 3, 1.0f);
        queueStamp_.assign(queueValue_.size(), 0u);
        // Each average is at most maxLookahead / 2 + 2 long.
        avg1_.assign(static_cast<size_t>(maxLookaheadSamples_) + 2, 1.0f);
        avg2_.assign(static_cast<size_t>(maxLookaheadSamples_) + 2, 1.0f);

        releaseCoeff_ = calcReleaseCoeff(releaseMs_);
        requestedLookahead_ = lookaheadFor(lookaheadMs_);
        reset();
    }

    void process(float* left, float* right, int numFrames) override {
        if (requestedLookahead_ != lookaheadSamples_) applyLookahead(requestedLookahead_);

        const float inputLin = dbToLin(inputGainDb_);
        const float outputLin = dbToLin(outputGainDb_);
        const float threshLin = dbToLin(thresholdDb_);
        const uint32_t window = static_cast<uint32_t>(lookaheadSamples_) + 1u;

        for (int i = 0; i < numFrames; i++) {
            const float inL = left[i] * inputLin;
            const float inR = right[i] * inputLin;

            delayL_.write(inL);
            delayR_.write(inR);
            const float delL = delayL_.read(lookaheadSamples_);
            const float delR = delayR_.read(lookaheadSamples_);

            // The gain this sample needs (linked stereo).
            const float peak = std::max(std::fabs(inL), std::fabs(inR));
            const float needed = peak > threshLin ? threshLin / peak : 1.0f;

            const float held = slidingMin(needed, window);
            const float smooth = average(avg2_, pos2_, sum2_, len2_,
                                         average(avg1_, pos1_, sum1_, len1_, held));

            if (smooth < gain_) gain_ = smooth;
            else gain_ += releaseCoeff_ * (smooth - gain_);
            ++clock_;

            left[i]  = delL * gain_ * outputLin;
            right[i] = delR * gain_ * outputLin;

            // Hard clip at 0dBFS as safety net — never exceed digital full-scale
            left[i]  = std::max(-1.0f, std::min(1.0f, left[i]));
            right[i] = std::max(-1.0f, std::min(1.0f, right[i]));
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case INPUT_GAIN:
                inputGainDb_ = clamp(value, -24.0f, 24.0f);
                break;
            case THRESHOLD:
                thresholdDb_ = clamp(value, -24.0f, 0.0f);
                break;
            case RELEASE:
                releaseMs_ = clamp(value, 1.0f, 1000.0f);
                releaseCoeff_ = calcReleaseCoeff(releaseMs_);
                break;
            case LOOKAHEAD:
                // Takes effect at the start of the next block.
                lookaheadMs_ = clamp(value, 1.0f, 10.0f);
                requestedLookahead_ = lookaheadFor(lookaheadMs_);
                break;
            case OUTPUT_GAIN:
                outputGainDb_ = clamp(value, -24.0f, 24.0f);
                break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case INPUT_GAIN:  return inputGainDb_;
            case THRESHOLD:   return thresholdDb_;
            case RELEASE:     return releaseMs_;
            case LOOKAHEAD:   return lookaheadMs_;
            case OUTPUT_GAIN: return outputGainDb_;
            default: return 0.0f;
        }
    }

    void reset() override {
        delayL_.reset(); delayR_.reset();
        queueHead_ = queueTail_ = 0;
        clock_ = 0;
        gain_ = 1.0f;
        applyLookahead(requestedLookahead_);
    }

    // The audio leaves the delay line L samples after it went in.
    int getLatencySamples() const override { return requestedLookahead_; }
    int getMaxLatencySamples() const override { return lookaheadFor(10.0f); }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Limiter"; }
    SnapinType getType() const override { return SnapinType::LIMITER; }

    // For the host test: start the sample counter at [value], to run it
    // through its wrap without processing 2^32 samples first.
    void setSampleClockForTest(uint32_t value) { clock_ = value; }

private:
    int lookaheadFor(float ms) const {
        const int samples = static_cast<int>(ms * 0.001f * static_cast<float>(sampleRate_));
        return std::max(1, std::min(samples, std::max(1, maxLookaheadSamples_)));
    }

    // A new lookahead re-splits the smoothing between the two averages. They
    // restart holding the gain being applied now, so nothing jumps.
    void applyLookahead(int samples) {
        lookaheadSamples_ = samples;
        len1_ = samples / 2 + 1;
        len2_ = samples + 2 - len1_;
        std::fill(avg1_.begin(), avg1_.end(), gain_);
        std::fill(avg2_.begin(), avg2_.end(), gain_);
        sum1_ = static_cast<double>(gain_) * len1_;
        sum2_ = static_cast<double>(gain_) * len2_;
        pos1_ = pos2_ = 0;
    }

    // Smallest value pushed in the last [window] samples. The queue keeps, in
    // order of arrival, only values smaller than everything after them.
    float slidingMin(float value, uint32_t window) {
        const int cap = static_cast<int>(queueValue_.size());
        while (queueHead_ != queueTail_) {
            const int back = queueTail_ == 0 ? cap - 1 : queueTail_ - 1;
            if (queueValue_[back] < value) break;
            queueTail_ = back;
        }
        queueValue_[queueTail_] = value;
        queueStamp_[queueTail_] = clock_;
        queueTail_ = queueTail_ + 1 == cap ? 0 : queueTail_ + 1;
        // Unsigned difference: correct across the counter's wrap.
        while (static_cast<uint32_t>(clock_ - queueStamp_[queueHead_]) >= window) {
            queueHead_ = queueHead_ + 1 == cap ? 0 : queueHead_ + 1;
        }
        return queueValue_[queueHead_];
    }

    static float average(std::vector<float>& ring, int& pos, double& sum, int length, float value) {
        sum += static_cast<double>(value) - ring[pos];
        ring[pos] = value;
        pos = pos + 1 == length ? 0 : pos + 1;
        return static_cast<float>(sum / length);
    }

    float calcReleaseCoeff(float ms) const {
        if (sampleRate_ <= 0.0) return 0.01f;
        return 1.0f - std::exp(-1.0f / (ms * 0.001f * static_cast<float>(sampleRate_)));
    }

    static float clamp(float v, float lo, float hi) {
        return std::max(lo, std::min(hi, v));
    }

    static float dbToLin(float db) {
        return std::pow(10.0f, db * 0.05f);
    }

    // Parameters
    float inputGainDb_ = 0.0f;
    float thresholdDb_ = 0.0f;
    float releaseMs_ = 100.0f;
    float lookaheadMs_ = 5.0f;
    float outputGainDb_ = 0.0f;

    // State
    float releaseCoeff_ = 0.01f;
    float gain_ = 1.0f;

    // Delay lines for audio path
    DelayLine delayL_, delayR_;
    int lookaheadSamples_ = 1;
    int requestedLookahead_ = 1;
    int maxLookaheadSamples_ = 0;

    // Sliding minimum: a ring of (value, sample stamp)
    std::vector<float> queueValue_;
    std::vector<uint32_t> queueStamp_;
    int queueHead_ = 0;
    int queueTail_ = 0;
    uint32_t clock_ = 0;

    // The two moving averages
    std::vector<float> avg1_, avg2_;
    double sum1_ = 0.0, sum2_ = 0.0;
    int len1_ = 1, len2_ = 1, pos1_ = 0, pos2_ = 0;
};
