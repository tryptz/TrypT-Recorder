#pragma once
#include "snapin_processor.h"
#include "envelope_follower.h"
#include "lookahead_buffer.h"
#include <cmath>
#include <algorithm>

// Lookahead limiter / ducker with attack as lookahead pre-delay.
// Envelope follower modes: RMS, Peak, ISP (inter-sample peak).
class CompactorProcessor : public SnapinProcessor {
public:
    // RESERVED_SIDECHAIN was a sidechain switch with no sidechain input
    // behind it. The slot stays so saved settings keep their indices; it is
    // ignored.
    enum Params {
        ATTACK = 0, HOLD, RELEASE, THRESHOLD, MODE,
        RANGE_PCT, STEREO_LINK, RESERVED_SIDECHAIN, NUM_PARAMS
    };

    static constexpr float MAX_ATTACK_MS = 50.0f;

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;

        envL_.prepare(sampleRate);
        envR_.prepare(sampleRate);
        envL_.setAttack(0.01f);  // Near-instant for peak detection
        envR_.setAttack(0.01f);
        envL_.setRelease(releaseMs_);
        envR_.setRelease(releaseMs_);
        envL_.setMode(DetectionMode::Peak);
        envR_.setMode(DetectionMode::Peak);

        // Room for the longest attack now, so turning the knob never
        // allocates on the audio thread.
        lookaheadL_.prepare(samplesFor(MAX_ATTACK_MS));
        lookaheadR_.prepare(samplesFor(MAX_ATTACK_MS));
        updateLookahead();

        gainL_ = 1.0f;
        gainR_ = 1.0f;
        holdCounterL_ = 0;
        holdCounterR_ = 0;
    }

    void process(float* left, float* right, int numFrames) override {
        float threshLin = std::pow(10.0f, thresholdDb_ / 20.0f);
        int holdSamples = static_cast<int>(holdMs_ * 0.001f * static_cast<float>(sampleRate_));
        float releaseCoeff = 1.0f - std::exp(-1.0f / (releaseMs_ * 0.001f * static_cast<float>(sampleRate_)));
        float rangeFactor = rangePct_ / 100.0f;

        for (int i = 0; i < numFrames; i++) {
            // Lookahead delay
            float delL = lookaheadL_.process(left[i]);
            float delR = lookaheadR_.process(right[i]);

            // Envelope detection on undelayed signal
            float envValL = envL_.process(left[i]);
            float envValR = envR_.process(right[i]);

            // ISP mode: parabolic interpolation between samples
            if (mode_ == 2) {
                envValL = ispPeak(left[i], prevL_);
                envValR = ispPeak(right[i], prevR_);
                prevL_ = left[i];
                prevR_ = right[i];
            }

            // Stereo linking
            float envLinked;
            if (stereoLink_ >= 0.99f) {
                // Dual mono
                // Process each channel independently
                float targetL = computeGain(envValL, threshLin, rangeFactor);
                float targetR = computeGain(envValR, threshLin, rangeFactor);
                gainL_ = smoothGain(gainL_, targetL, holdCounterL_, holdSamples, releaseCoeff);
                gainR_ = smoothGain(gainR_, targetR, holdCounterR_, holdSamples, releaseCoeff);
                left[i] = delL * gainL_;
                right[i] = delR * gainR_;
                continue;
            }

            // Linked: use max of L/R
            envLinked = std::max(envValL, envValR) * (1.0f - stereoLink_)
                      + (envValL + envValR) * 0.5f * stereoLink_;
            float target = computeGain(envLinked, threshLin, rangeFactor);
            gainL_ = smoothGain(gainL_, target, holdCounterL_, holdSamples, releaseCoeff);

            left[i] = delL * gainL_;
            right[i] = delR * gainL_;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case ATTACK:
                attackMs_ = std::max(0.0f, std::min(50.0f, value));
                updateLookahead();
                break;
            case HOLD:    holdMs_ = std::max(0.0f, std::min(500.0f, value)); break;
            case RELEASE:
                releaseMs_ = std::max(1.0f, std::min(1000.0f, value));
                envL_.setRelease(releaseMs_);
                envR_.setRelease(releaseMs_);
                break;
            case THRESHOLD: thresholdDb_ = std::max(-60.0f, std::min(0.0f, value)); break;
            case MODE:
                mode_ = static_cast<int>(std::max(0.0f, std::min(2.0f, value)));
                if (mode_ == 0) { envL_.setMode(DetectionMode::RMS); envR_.setMode(DetectionMode::RMS); }
                else { envL_.setMode(DetectionMode::Peak); envR_.setMode(DetectionMode::Peak); }
                break;
            case RANGE_PCT:    rangePct_ = std::max(0.0f, std::min(200.0f, value)); break;
            case STEREO_LINK:  stereoLink_ = std::max(0.0f, std::min(1.0f, value / 100.0f)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case ATTACK:       return attackMs_;
            case HOLD:         return holdMs_;
            case RELEASE:      return releaseMs_;
            case THRESHOLD:    return thresholdDb_;
            case MODE:         return static_cast<float>(mode_);
            case RANGE_PCT:    return rangePct_;
            case STEREO_LINK:  return stereoLink_ * 100.0f;
            default: return 0.0f;
        }
    }

    // The attack is a delay on the audio: the gain drops before the peak.
    int getLatencySamples() const override { return lookaheadL_.getDelay(); }
    int getMaxLatencySamples() const override { return samplesFor(MAX_ATTACK_MS); }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Compactor"; }
    SnapinType getType() const override { return SnapinType::COMPACTOR; }

private:
    int samplesFor(float ms) const {
        return std::max(1, static_cast<int>(ms * 0.001f * static_cast<float>(sampleRate_)));
    }

    void updateLookahead() {
        lookaheadL_.setDelay(samplesFor(attackMs_));
        lookaheadR_.setDelay(samplesFor(attackMs_));
    }

    float computeGain(float envVal, float threshLin, float rangeFactor) const {
        if (envVal <= threshLin || threshLin <= 0.0f) return 1.0f;
        float reduction = threshLin / envVal;
        return 1.0f - (1.0f - reduction) * rangeFactor;
    }

    float smoothGain(float current, float target, int& holdCounter, int holdSamples, float releaseCoeff) {
        if (target < current) {
            // Instant attack (within lookahead window)
            holdCounter = holdSamples;
            return target;
        }
        if (holdCounter > 0) {
            holdCounter--;
            return current;
        }
        return current + releaseCoeff * (target - current);
    }

    // Inter-sample peak via parabolic interpolation
    float ispPeak(float current, float prev) const {
        float peak = std::max(std::fabs(current), std::fabs(prev));
        // Parabolic overshoot estimation
        float mid = (current + prev) * 0.5f;
        float overshoot = std::fabs(mid) + std::fabs(current - prev) * 0.25f;
        return std::max(peak, overshoot);
    }

    float attackMs_ = 5.0f;
    float holdMs_ = 10.0f;
    float releaseMs_ = 100.0f;
    float thresholdDb_ = -12.0f;
    int mode_ = 1;  // 0=RMS, 1=Peak, 2=ISP
    float rangePct_ = 100.0f;
    float stereoLink_ = 0.0f;  // 0=linked, 1=dual-mono

    EnvelopeFollower envL_, envR_;
    LookaheadBuffer lookaheadL_, lookaheadR_;
    float gainL_ = 1.0f, gainR_ = 1.0f;
    int holdCounterL_ = 0, holdCounterR_ = 0;
    float prevL_ = 0.0f, prevR_ = 0.0f;
};
