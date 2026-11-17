// SPDX-License-Identifier: GPL-3.0-or-later
#include "mixer_chain.h"
#include <jni.h>

using recorder::MixerGraph;

namespace {
// Every index that comes from Kotlin is checked: a bad one would otherwise
// read or write outside the mixer's memory and take the whole app down.
MixerGraph* graphOf(jlong handle) { return reinterpret_cast<MixerGraph*>(handle); }
bool validBus(jint bus) { return bus >= 0 && bus < recorder::kBuses; }
}  // namespace
#define JNI_METHOD(name) Java_tf_monochrome_recorder_editor_export_NativeRecorderMixer_##name

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv*, jobject, jint sampleRate) {
    if (sampleRate <= 0) return 0;
    return reinterpret_cast<jlong>(new MixerGraph(sampleRate));
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(destroy)(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<MixerGraph*>(handle);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(clear)(JNIEnv*, jobject, jlong handle, jint bus) {
    if (handle == 0 || !validBus(bus)) return;
    graphOf(handle)->chains[bus].clear();
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(append)(
    JNIEnv* env, jobject, jlong handle, jint bus, jint type, jfloatArray parameters, jfloat wet, jint oversampling) {
    if (handle == 0 || !validBus(bus) || parameters == nullptr) return;
    auto* graph = graphOf(handle);
    const int count = env->GetArrayLength(parameters);
    std::vector<float> values(count);
    if (count > 0) env->GetFloatArrayRegion(parameters, 0, count, values.data());
    graph->chains[bus].append(type, values.data(), count, wet, graph->sampleRate, oversampling);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(setParameter)(
    JNIEnv*, jobject, jlong handle, jint bus, jint effect, jint parameter, jfloat value) {
    if (handle == 0 || !validBus(bus)) return;
    graphOf(handle)->chains[bus].setParameter(effect, parameter, value);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(setWet)(
    JNIEnv*, jobject, jlong handle, jint bus, jint effect, jfloat wet) {
    if (handle == 0 || !validBus(bus)) return;
    graphOf(handle)->chains[bus].setWet(effect, wet);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(render)(
    JNIEnv* env, jobject, jlong handle, jobject input, jint offset, jobject output,
    jint frames, jfloatArray controls, jboolean fxEnabled, jfloatArray peaks) {
    if (handle == 0 || frames <= 0 || offset < 0 || controls == nullptr || peaks == nullptr) return;
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
    auto* dest = static_cast<int16_t*>(env->GetDirectBufferAddress(output));
    if (data == nullptr || dest == nullptr) return;
    // Never read or write past either buffer, whatever the caller says.
    constexpr jlong kInFrame = recorder::kRoles * 2 * sizeof(float);
    constexpr jlong kOutFrame = recorder::kRoles * 2 * sizeof(int16_t);
    const jlong inCapacity = env->GetDirectBufferCapacity(input);
    const jlong outCapacity = env->GetDirectBufferCapacity(output);
    if (inCapacity < offset + frames * kInFrame || outCapacity < frames * kOutFrame) return;
    if (env->GetArrayLength(controls) < recorder::kBuses * 2 || env->GetArrayLength(peaks) < recorder::kBuses * 2) return;
    float gains[recorder::kBuses * 2];
    float levels[recorder::kBuses * 2];
    env->GetFloatArrayRegion(controls, 0, recorder::kBuses * 2, gains);
    graphOf(handle)->process(
        reinterpret_cast<const float*>(data + offset), dest, frames, gains, fxEnabled, levels);
    env->SetFloatArrayRegion(peaks, 0, recorder::kBuses * 2, levels);
}

// ── Chains built off the audio thread ─────────────────────────────────────
// RecorderAudioMixer builds a changed effect chain on a worker thread
// (prepareChain), swaps it in on the audio thread (installChain: a pointer
// swap, nothing allocated or freed there) and frees the swapped-out effects
// back on the worker (destroyChain).

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(prepareChain)(
    JNIEnv* env, jobject, jint sampleRate, jintArray types, jfloatArray parameters, jintArray counts,
    jfloatArray wets, jintArray oversampling) {
    if (sampleRate <= 0 || types == nullptr || parameters == nullptr || counts == nullptr ||
        wets == nullptr || oversampling == nullptr) {
        return 0;
    }
    const jsize effects = env->GetArrayLength(types);
    if (env->GetArrayLength(counts) != effects || env->GetArrayLength(wets) != effects ||
        env->GetArrayLength(oversampling) != effects) {
        return 0;
    }
    std::vector<jint> kind(effects), count(effects), factor(effects);
    std::vector<jfloat> wet(effects);
    if (effects > 0) {
        env->GetIntArrayRegion(types, 0, effects, kind.data());
        env->GetIntArrayRegion(counts, 0, effects, count.data());
        env->GetFloatArrayRegion(wets, 0, effects, wet.data());
        env->GetIntArrayRegion(oversampling, 0, effects, factor.data());
    }
    const jsize total = env->GetArrayLength(parameters);
    jlong needed = 0;
    for (jint c : count) {
        if (c < 0) return 0;
        needed += c;
    }
    if (needed != total) return 0;
    std::vector<jfloat> values(total);
    if (total > 0) env->GetFloatArrayRegion(parameters, 0, total, values.data());
    auto chain = std::make_unique<recorder::FxChain>();
    jsize at = 0;
    for (jsize i = 0; i < effects; ++i) {
        chain->append(kind[i], values.data() + at, count[i], std::clamp(wet[i], 0.f, 1.f), sampleRate, factor[i]);
        at += count[i];
    }
    return reinterpret_cast<jlong>(chain.release());
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(installChain)(JNIEnv*, jobject, jlong handle, jint bus, jlong chain) {
    if (handle == 0 || chain == 0 || !validBus(bus)) return;
    graphOf(handle)->chains[bus].swapEffects(*reinterpret_cast<recorder::FxChain*>(chain));
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(destroyChain)(JNIEnv*, jobject, jlong chain) {
    delete reinterpret_cast<recorder::FxChain*>(chain);
}
