// SPDX-License-Identifier: GPL-3.0-or-later
// Host tests for fixes made here to the vendored snap-ins and to the
// recorder's own effect chain (mixer_chain.h). Each one failed, or could not
// be written, before its fix.
#include "mixer_chain.h"
#include "bitcrush.h"
#include "chorus.h"
#include "compactor.h"
#include "compressor.h"
#include "flanger.h"
#include "gate.h"
#include "limiter.h"
#include "reverser.h"
#include "stereo.h"
#include "trance_gate.h"
#include "transient_shaper.h"
#include <cmath>
#include <cstdio>
#include <functional>
#include <memory>
#include <vector>

using namespace recorder;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kRate = 48000;
constexpr double kPi = 3.14159265358979323846;

// Runs [in] through [process] in blocks of at most kBlock, in place.
void runBlocks(std::vector<float>& l, std::vector<float>& r,
               const std::function<void(float*, float*, int)>& process) {
    for (size_t start = 0; start < l.size(); start += kBlock) {
        const int n = static_cast<int>(std::min<size_t>(kBlock, l.size() - start));
        process(l.data() + start, r.data() + start, n);
    }
}

std::vector<float> noise(size_t n, uint32_t seed, float level) {
    std::vector<float> out(n);
    for (auto& x : out) {
        seed = seed * 1664525u + 1013904223u;
        x = level * (static_cast<float>(seed >> 8) / 8388608.0f - 1.0f);
    }
    return out;
}

// ── Limiter ─────────────────────────────────────────────────────────────

void limiterAttackIsSpreadOverTheLookahead() {
    LimiterProcessor limiter;
    limiter.prepare(kRate, kBlock);
    limiter.setParameter(LimiterProcessor::THRESHOLD, -6.0f);
    limiter.setParameter(LimiterProcessor::LOOKAHEAD, 5.0f);
    const int lookahead = limiter.getLatencySamples();
    const float threshold = std::pow(10.0f, -6.0f / 20.0f);

    // A quiet passage, then a step to full scale: the worst case for an
    // attack, which must have the gain down by the time the step leaves the
    // delay without a jump on the way.
    const int n = 6000, step = 2000;
    std::vector<float> l(n), r(n);
    for (int i = 0; i < n; ++i) l[i] = r[i] = i < step ? 0.1f : 1.0f;
    const std::vector<float> in = l;
    runBlocks(l, r, [&](float* a, float* b, int k) { limiter.process(a, b, k); });

    float worstOver = 0.f, worstJump = 0.f, previousGain = 1.f;
    for (int i = lookahead; i < n; ++i) {
        const float source = in[i - lookahead];
        worstOver = std::max(worstOver, std::fabs(l[i]) - threshold);
        const float gain = l[i] / source;
        if (i > lookahead) worstJump = std::max(worstJump, std::fabs(gain - previousGain));
        previousGain = gain;
    }
    std::printf("info  limiter: lookahead %d, overshoot %.2e, largest gain step %.5f\n",
                lookahead, worstOver, worstJump);
    check(lookahead == 240, "limiter reports its 5 ms lookahead as latency");
    check(worstOver <= 1e-5f, "limiter holds the threshold through a step");
    // The gain falls by half over 240 samples; the S-curve's steepest step is
    // about twice the average one. A one-sample attack would step by 0.5.
    check(worstJump < 0.01f, "limiter gain never jumps: the attack is spread over the lookahead");
    check(std::fabs(l[step - 1] - 0.1f) < 1e-6f,
          "limiter leaves the quiet passage alone until the attack begins");
}

void limiterSurvivesTheSampleCounterWrapping() {
    // The window used to be found from a signed int sample counter: after
    // 2^31 samples (12 h at 48 kHz) it went negative and the modulo gave a
    // negative index. Two limiters, one with its counter just short of the
    // wrap, must come out identical.
    LimiterProcessor fresh, wrapping;
    for (auto* limiter : {&fresh, &wrapping}) {
        limiter->prepare(kRate, kBlock);
        limiter->setParameter(LimiterProcessor::THRESHOLD, -12.0f);
        limiter->setParameter(LimiterProcessor::RELEASE, 20.0f);
    }
    wrapping.setSampleClockForTest(0xFFFFFFFFu - 3000u);
    std::vector<float> l1 = noise(8000, 7, 0.9f), r1 = noise(8000, 11, 0.9f);
    std::vector<float> l2 = l1, r2 = r1;
    runBlocks(l1, r1, [&](float* a, float* b, int k) { fresh.process(a, b, k); });
    runBlocks(l2, r2, [&](float* a, float* b, int k) { wrapping.process(a, b, k); });
    bool same = true;
    for (size_t i = 0; i < l1.size(); ++i) same = same && l1[i] == l2[i] && r1[i] == r2[i];
    check(same, "limiter output is unchanged across the sample counter's wrap");
}

// ── Wet/dry latency ─────────────────────────────────────────────────────

// Peak level of a sine at [freq] through [chain], once settled.
float sineThrough(FxChain& chain, double freq) {
    const int n = 9600;
    std::vector<float> l(n), r(n);
    for (int i = 0; i < n; ++i) l[i] = r[i] = static_cast<float>(0.5 * std::sin(2 * kPi * freq * i / kRate));
    runBlocks(l, r, [&](float* a, float* b, int k) { chain.process(a, b, k); });
    float peak = 0.f;
    for (int i = n / 2; i < n; ++i) peak = std::max(peak, std::fabs(l[i]));
    return peak / 0.5f;
}

void halfWetLimiterIsTheInputDelayed() {
    FxChain chain;
    const float params[] = {0.f, 0.f, 100.f, 5.f, 0.f};  // nothing over 0 dBFS
    chain.append(static_cast<int>(SnapinType::LIMITER), params, 5, 0.5f, kRate);
    check(chain.dryDelay(0) == 240, "chain delays a lookahead limiter's dry signal by its lookahead");
    std::vector<float> l = noise(4000, 3, 0.5f), r = noise(4000, 5, 0.5f);
    const std::vector<float> inL = l;
    runBlocks(l, r, [&](float* a, float* b, int k) { chain.process(a, b, k); });
    float worst = 0.f;
    for (int i = 240; i < 4000; ++i) worst = std::max(worst, std::fabs(l[i] - inL[i - 240]));
    // Before, the undelayed dry was added to the delayed wet: two copies of
    // the signal 5 ms apart, a comb filter with notches every 200 Hz.
    check(worst < 1e-6f, "50 % wet limiter under its threshold is the input, delayed, with no comb");
}

void oversampledEffectsLineUpWithTheirDry() {
    for (int factor : {2, 4}) {
        FxChain chain;
        const float unity = 0.f;
        chain.append(static_cast<int>(SnapinType::GAIN), &unity, 1, 0.5f, kRate, factor);
        const int delay = chain.dryDelay(0);
        float worst = 2.f;
        for (double f : {200.0, 1000.0, 3000.0, 6000.0, 10000.0}) {
            worst = std::min(worst, sineThrough(chain, f));
        }
        std::printf("info  %dx oversampling: dry delayed %d samples, lowest level 200 Hz-10 kHz %.3f\n",
                    factor, delay, worst);
        check(delay > 0, factor == 2 ? "2x oversampling reports its filters' delay"
                                     : "4x oversampling reports its filters' delay");
        // Unaligned, a 4-sample offset cancels completely at 6 kHz.
        check(worst > 0.95f, factor == 2 ? "50 % wet unity gain at 2x stays flat to 10 kHz"
                                         : "50 % wet unity gain at 4x stays flat to 10 kHz");
    }
}

void latenciesAreReported() {
    CompressorProcessor compressor;
    compressor.prepare(kRate, kBlock);
    compressor.setParameter(CompressorProcessor::LOOKAHEAD, 5.0f);
    check(compressor.getLatencySamples() == 240, "compressor reports its lookahead");
    check(compressor.getMaxLatencySamples() >= 480, "compressor's maximum covers 10 ms");

    GateProcessor gate;
    gate.prepare(kRate, kBlock);
    check(gate.getLatencySamples() == 0, "gate without lookahead has no latency");
    gate.setParameter(GateProcessor::LOOKAHEAD, 1.0f);
    check(gate.getLatencySamples() == 240, "gate with lookahead reports 5 ms");

    CompactorProcessor compactor;
    compactor.prepare(kRate, kBlock);
    compactor.setParameter(CompactorProcessor::ATTACK, 5.0f);
    check(compactor.getLatencySamples() == 240, "compactor reports its attack as latency");
    compactor.setParameter(CompactorProcessor::ATTACK, 50.0f);
    check(compactor.getLatencySamples() == 2400 && compactor.getMaxLatencySamples() == 2400,
          "compactor's longest attack fits the room made for it");
}

// ── Knobs that did nothing ──────────────────────────────────────────────

// Output of a fresh P over noise, with parameter [index] set to [value].
template <typename P>
std::vector<float> render(int index, float value) {
    P processor;
    processor.prepare(kRate, kBlock);
    processor.setParameter(index, value);
    std::vector<float> l = noise(4096, 9, 0.7f), r = noise(4096, 13, 0.7f);
    runBlocks(l, r, [&](float* a, float* b, int k) { processor.process(a, b, k); });
    l.insert(l.end(), r.begin(), r.end());
    return l;
}

void reservedSlotsKeepTheirPlaceAndDoNothing() {
    check(GateProcessor::NUM_PARAMS == 9 && GateProcessor::RESERVED_SIDECHAIN == 8, "gate keeps 9 slots");
    check(CompactorProcessor::NUM_PARAMS == 8 && CompactorProcessor::RESERVED_SIDECHAIN == 7, "compactor keeps 8 slots");
    check(TransientShaperProcessor::NUM_PARAMS == 6 && TransientShaperProcessor::RESERVED_SIDECHAIN == 5,
          "transient shaper keeps 6 slots");
    check(BitcrushProcessor::NUM_PARAMS == 6 && BitcrushProcessor::RESERVED_DAC == 3 && BitcrushProcessor::DITHER == 4,
          "bitcrush keeps Dither and Mix where they were");
    check(ReverserProcessor::NUM_PARAMS == 4 && ReverserProcessor::RESERVED_SYNC == 1 && ReverserProcessor::CROSSFADE_PCT == 2,
          "reverser keeps X-Fade and Mix where they were");
    check(render<GateProcessor>(8, 0.f) == render<GateProcessor>(8, 1.f), "gate's reserved slot changes nothing");
    check(render<CompactorProcessor>(7, 0.f) == render<CompactorProcessor>(7, 1.f), "compactor's reserved slot changes nothing");
    check(render<TransientShaperProcessor>(5, 0.f) == render<TransientShaperProcessor>(5, 1.f),
          "transient shaper's reserved slot changes nothing");
    check(render<ReverserProcessor>(1, 0.f) == render<ReverserProcessor>(1, 1.f), "reverser's reserved slot changes nothing");
}

void bitcrushDitherNeedsNoGlobalRandom() {
    auto crush = [](float dither) {
        BitcrushProcessor crusher;
        crusher.prepare(kRate, kBlock);
        crusher.setParameter(BitcrushProcessor::BITS, 4.0f);
        crusher.setParameter(BitcrushProcessor::DITHER, dither);
        std::vector<float> l = noise(2048, 21, 0.3f), r = l;
        runBlocks(l, r, [&](float* a, float* b, int k) { crusher.process(a, b, k); });
        return l;
    };
    check(crush(100.f) != crush(0.f), "bitcrush dither changes the output");
    check(crush(100.f) == crush(100.f), "bitcrush dither comes from the effect's own generator");
    check(render<BitcrushProcessor>(3, 0.f) == render<BitcrushProcessor>(3, 100.f), "bitcrush's reserved slot changes nothing");
}

// ── Trance Gate ─────────────────────────────────────────────────────────

void tranceGatePatternsFillThirtyTwoSteps() {
    TranceGateProcessor gate;
    gate.prepare(kRate, kBlock);
    bool repeated = true, secondBarPlays = true;
    for (int p = 0; p < 7; ++p) {
        bool any = false;
        for (int s = 16; s < 32; ++s) {
            repeated = repeated && gate.isStepOn(p, s) == gate.isStepOn(p, s - 16);
            any = any || gate.isStepOn(p, s);
        }
        secondBarPlays = secondBarPlays && any;
    }
    check(repeated && secondBarPlays, "trance gate patterns 0-6 play their bar again over steps 16-31");
    check(TranceGateProcessor::NUM_PARAMS == 9 && TranceGateProcessor::BPM == 8, "trance gate BPM is the last slot");
}

float tranceLevelAt(float bpm, double seconds) {
    TranceGateProcessor gate;
    gate.prepare(kRate, kBlock);
    gate.setParameter(TranceGateProcessor::PATTERN, 0.f);     // a step on every beat
    gate.setParameter(TranceGateProcessor::RESOLUTION, 0.f);  // one step per beat
    gate.setParameter(TranceGateProcessor::BPM, bpm);
    const int n = static_cast<int>(seconds * kRate);
    std::vector<float> l(n, 1.f), r(n, 1.f);
    runBlocks(l, r, [&](float* a, float* b, int k) { gate.process(a, b, k); });
    return l.back();
}

void tranceGateFollowsItsTempo() {
    // Pattern 0 is on at step 0 and off for steps 1-3. 0.75 s in, 60 BPM is
    // still in step 0 and 120 BPM is in step 1.
    check(tranceLevelAt(60.f, 0.75) > 0.9f, "trance gate at 60 BPM is open 0.75 s in");
    check(tranceLevelAt(120.f, 0.75) < 0.05f, "trance gate at 120 BPM is closed 0.75 s in");
    TranceGateProcessor gate;
    gate.setParameter(TranceGateProcessor::BPM, 1000.f);
    check(gate.getParameter(TranceGateProcessor::BPM) == TranceGateProcessor::MAX_BPM, "trance gate BPM is clamped");
}

// ── Stereo, Chorus, Flanger ─────────────────────────────────────────────

void stereoIsUnityAtItsDefaults() {
    StereoProcessor stereo;
    stereo.prepare(kRate, kBlock);
    std::vector<float> l = noise(1024, 17, 0.5f), r = noise(1024, 19, 0.5f);
    const std::vector<float> inL = l, inR = r;
    runBlocks(l, r, [&](float* a, float* b, int k) { stereo.process(a, b, k); });
    float worst = 0.f;
    for (size_t i = 0; i < l.size(); ++i) {
        worst = std::max({worst, std::fabs(l[i] - inL[i]), std::fabs(r[i] - inR[i])});
    }
    check(worst < 1e-6f, "stereo at centre leaves the signal as it was (was -3 dB)");
    stereo.setParameter(StereoProcessor::PAN, 1.f);
    for (int i = 0; i < 4 * kRate / 100; ++i) {  // let the pan smoother settle
        float a = 0.5f, b = 0.5f;
        stereo.process(&a, &b, 1);
    }
    float a = 0.5f, b = 0.5f;
    stereo.process(&a, &b, 1);
    check(std::fabs(a) < 1e-3f && std::fabs(b - 0.5f) < 1e-3f, "stereo panned right keeps the right side at unity");
}

void chorusBufferReachesItsLongestRead() {
    for (double rate : {44100.0, 48000.0, 96000.0, 192000.0}) {
        ChorusProcessor chorus;
        chorus.prepare(rate, kBlock);
        // 40 ms base, the last of 8 voices 5 % further, full depth another
        // 100 %, and the cubic read's two samples past the read point.
        const int needed = static_cast<int>(std::ceil(0.040 * rate * 2.05)) + 2;
        if (chorus.capacity() < needed) {
            std::printf("info  chorus at %.0f Hz holds %d, needs %d\n", rate, chorus.capacity(), needed);
            check(false, "chorus buffer holds its longest read");
            return;
        }
    }
    check(true, "chorus buffer holds its longest read at 44.1-192 kHz");
}

void flangerStereoIsAPercentage() {
    FlangerProcessor flanger;
    flanger.prepare(kRate, kBlock);
    flanger.setParameter(FlangerProcessor::STEREO, 300.f);
    check(flanger.getParameter(FlangerProcessor::STEREO) == 100.f, "flanger Stereo is clamped to 100 %");
    auto spread = [](float stereo) {
        FlangerProcessor f;
        f.prepare(kRate, kBlock);
        f.setParameter(FlangerProcessor::STEREO, stereo);
        f.setParameter(FlangerProcessor::MIX, 100.f);
        std::vector<float> l = noise(9600, 23, 0.5f), r = l;
        runBlocks(l, r, [&](float* a, float* b, int k) { f.process(a, b, k); });
        float diff = 0.f;
        for (size_t i = 0; i < l.size(); ++i) diff = std::max(diff, std::fabs(l[i] - r[i]));
        return diff;
    };
    check(spread(0.f) == 0.f, "flanger at 0 % stereo is mono");
    check(spread(100.f) > 0.05f, "flanger at 100 % stereo differs between sides");
}

}  // namespace

int main() {
    flushDenormalsToZero();
    limiterAttackIsSpreadOverTheLookahead();
    limiterSurvivesTheSampleCounterWrapping();
    halfWetLimiterIsTheInputDelayed();
    oversampledEffectsLineUpWithTheirDry();
    latenciesAreReported();
    reservedSlotsKeepTheirPlaceAndDoNothing();
    bitcrushDitherNeedsNoGlobalRandom();
    tranceGatePatternsFillThirtyTwoSteps();
    tranceGateFollowsItsTempo();
    stereoIsUnityAtItsDefaults();
    chorusBufferReachesItsLongestRead();
    flangerStereoIsAPercentage();
    std::printf("%s\n", failures ? "FAILED" : "PASSED");
    return failures ? 1 : 0;
}
