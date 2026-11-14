// SPDX-License-Identifier: GPL-3.0-or-later
#include "mixer_chain.h"
#include <vector>
#include <cassert>
using namespace recorder;
int main() {
    MixerGraph graph(48000);
    constexpr int frames = 777;
    std::vector<float> input(frames * 8, 0.f);
    std::vector<int16_t> output(frames * 8, 0);
    float gains[10] = {1.f,1.f,1.f,1.f,1.f,1.f,1.f,1.f,1.f,1.f};
    float peaks[10]{};
    for (int frame = 0; frame < frames; ++frame) {
        input[frame * 8] = .25f;
        input[frame * 8 + 1] = .125f;
        input[frame * 8 + 4] = .5f;
        input[frame * 8 + 5] = .25f;
    }
    graph.process(input.data(), output.data(), frames, gains, true, peaks);
    for (int frame = 0; frame < frames; ++frame) {
        assert(output[frame * 8] == 24576);
        assert(output[frame * 8 + 1] == 12288);
        for (int channel = 2; channel < 8; ++channel) assert(output[frame * 8 + channel] == 0);
    }
    assert(peaks[0] == .25f && peaks[4] == .5f && peaks[8] == .75f);
    const float attenuation = -6.0206f;
    graph.chains[0].append(static_cast<int>(SnapinType::GAIN), &attenuation, 1, 1.f, 48000);
    for (int i = 0; i < 10; ++i) graph.process(input.data(), output.data(), frames, gains, true, peaks);
    // The insert on Dialogue leaves Music's signal intact.
    assert(std::abs(output[0] - 20480) <= 1 && std::abs(output[1] - 10240) <= 1);
    graph.chains[0].clear();
    gains[4] = 0.f;
    gains[5] = .5f;
    graph.process(input.data(), output.data(), frames, gains, true, peaks);
    assert(output[0] == 8192 && output[1] == 8192);
    graph.chains[kMaster].append(static_cast<int>(SnapinType::GAIN), &attenuation, 1, 1.f, 48000);
    for (int i = 0; i < 10; ++i) graph.process(input.data(), output.data(), frames, gains, true, peaks);
    assert(std::abs(output[0] - 4096) <= 1 && std::abs(output[1] - 4096) <= 1);
    graph.process(input.data(), output.data(), frames, gains, false, peaks);
    assert(output[0] == 8192 && output[1] == 8192);
}
