// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "snapin_processor.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <vector>
#include <memory>
#if defined(__i386__) || defined(__x86_64__)
#include <xmmintrin.h>
#endif

namespace recorder {

/**
 * Tiny numbers left in a reverb or filter tail after the sound stops
 * ("denormals") are dozens of times slower to compute with, so a silent tail
 * can cost more than the music did. Flushing them to zero is inaudible. The
 * setting belongs to the calling thread, so it is set on every render.
 */
static inline void flushDenormalsToZero() {
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    asm volatile("msr fpcr, %0" :: "r"(fpcr | (uint64_t{1} << 24)));
#elif defined(__arm__)
    uint32_t fpscr;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    asm volatile("vmsr fpscr, %0" :: "r"(fpscr | (1u << 24)));
#elif defined(__i386__) || defined(__x86_64__)
    _mm_setcsr(_mm_getcsr() | 0x8040);  // FTZ + DAZ
#endif
}

constexpr int kBlock = 512;
constexpr int kRoles = 4;
constexpr int kMaster = kRoles;
constexpr int kBuses = kRoles + 1;

class FxChain {
public:
    void append(int type, const float* parameters, int count, float wet, int sampleRate, int oversampling = 1) {
        std::unique_ptr<SnapinProcessor> processor(createSnapin(static_cast<SnapinType>(type)));
        if (!processor) return;
        processor->setOversampling(oversampling);
        processor->prepareOS(sampleRate, kBlock);
        for (int i = 0; i < std::min(count, processor->getNumParameters()); ++i) {
            if (std::isfinite(parameters[i])) processor->setParameter(i, parameters[i]);
        }
        processor->setDryWet(wet);
        effects_.push_back(std::move(processor));
        sampleRate_ = sampleRate;
    }

    void clear() { effects_.clear(); }

    int size() const { return static_cast<int>(effects_.size()); }

    void setParameter(int effect, int parameter, float value) {
        if (effect < 0 || effect >= size() || parameter < 0 || !std::isfinite(value)) return;
        auto& processor = effects_[effect];
        if (parameter < processor->getNumParameters()) processor->setParameter(parameter, value);
    }

    void setWet(int effect, float wet) {
        if (effect < 0 || effect >= size() || !std::isfinite(wet)) return;
        effects_[effect]->setDryWet(std::clamp(wet, 0.f, 1.f));
    }

    void process(float* left, float* right, int frames) {
        for (auto& effect : effects_) {
            const float wet = effect->getDryWet();
            if (wet <= 0.f) continue;
            if (wet < 1.f) {
                std::copy_n(left, frames, dryLeft_.data());
                std::copy_n(right, frames, dryRight_.data());
            }
            effect->processOS(left, right, frames);
            bool broken = false;
            for (int i = 0; i < frames; ++i) {
                broken = broken || !std::isfinite(left[i]) || !std::isfinite(right[i]);
                const float l = std::isfinite(left[i]) ? left[i] : 0.f;
                const float r = std::isfinite(right[i]) ? right[i] : 0.f;
                left[i] = wet == 1.f ? l : l * wet + dryLeft_[i] * (1.f - wet);
                right[i] = wet == 1.f ? r : r * wet + dryRight_[i] * (1.f - wet);
            }
            // An effect that produced NaN or infinity keeps it in its state and
            // stays silent for good. Clear it so it recovers on the next block.
            if (broken) {
                effect->reset();
                effect->prepareOS(sampleRate_, kBlock);
            }
        }
    }

private:
    std::vector<std::unique_ptr<SnapinProcessor>> effects_;
    std::array<float, kBlock> dryLeft_{}, dryRight_{};
    int sampleRate_ = 48000;
};

class MixerGraph {
public:
    explicit MixerGraph(int sampleRate) : sampleRate(sampleRate) {}

    void process(const float* input, int16_t* output, int frames, const float* controls,
                 bool fxEnabled, float* peaks) {
        flushDenormalsToZero();
        std::fill_n(peaks, kBuses * 2, 0.f);
        for (int start = 0; start < frames; start += kBlock) {
            const int count = std::min(kBlock, frames - start);
            std::fill_n(masterLeft_.data(), count, 0.f);
            std::fill_n(masterRight_.data(), count, 0.f);
            for (int bus = 0; bus < kRoles; ++bus) {
                for (int i = 0; i < count; ++i) {
                    const int index = ((start + i) * kRoles + bus) * 2;
                    left_[i] = input[index];
                    right_[i] = input[index + 1];
                }
                if (fxEnabled) chains[bus].process(left_.data(), right_.data(), count);
                for (int i = 0; i < count; ++i) {
                    const float left = left_[i] * controls[bus * 2];
                    const float right = right_[i] * controls[bus * 2 + 1];
                    peaks[bus * 2] = std::max(peaks[bus * 2], std::abs(left));
                    peaks[bus * 2 + 1] = std::max(peaks[bus * 2 + 1], std::abs(right));
                    masterLeft_[i] += left;
                    masterRight_[i] += right;
                }
            }
            if (fxEnabled) chains[kMaster].process(masterLeft_.data(), masterRight_.data(), count);
            for (int i = 0; i < count; ++i) {
                const float left = masterLeft_[i] * controls[kMaster * 2];
                const float right = masterRight_[i] * controls[kMaster * 2 + 1];
                peaks[kMaster * 2] = std::max(peaks[kMaster * 2], std::abs(left));
                peaks[kMaster * 2 + 1] = std::max(peaks[kMaster * 2 + 1], std::abs(right));
                // Media3 requires the mixer output to match the graph's input format.
                // Only the first stereo pair carries the master. A final processor unpacks it.
                const int index = (start + i) * kRoles * 2;
                output[index] = pcm16(left);
                output[index + 1] = pcm16(right);
                std::fill_n(output + index + 2, kRoles * 2 - 2, int16_t{0});
            }
        }
    }

    const int sampleRate;
    std::array<FxChain, kBuses> chains;

private:
    static int16_t pcm16(float sample) {
        return static_cast<int16_t>(std::clamp(std::lround(std::clamp(sample, -1.f, 1.f) * 32768.f), -32768L, 32767L));
    }

    std::array<float, kBlock> left_{}, right_{}, masterLeft_{}, masterRight_{};
};

} // namespace recorder
