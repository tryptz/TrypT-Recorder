// SPDX-License-Identifier: GPL-3.0-or-later
#include "mixer_chain.h"
#include <jni.h>

using recorder::MixerGraph;
#define JNI_METHOD(name) Java_tf_monochrome_recorder_editor_export_NativeRecorderMixer_##name

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv*, jobject, jint sampleRate) {
    return reinterpret_cast<jlong>(new MixerGraph(sampleRate));
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(destroy)(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<MixerGraph*>(handle);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(clear)(JNIEnv*, jobject, jlong handle, jint bus) {
    reinterpret_cast<MixerGraph*>(handle)->chains[bus].clear();
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(append)(
    JNIEnv* env, jobject, jlong handle, jint bus, jint type, jfloatArray parameters, jfloat wet, jint oversampling) {
    auto* graph = reinterpret_cast<MixerGraph*>(handle);
    const int count = env->GetArrayLength(parameters);
    std::vector<float> values(count);
    if (count > 0) env->GetFloatArrayRegion(parameters, 0, count, values.data());
    graph->chains[bus].append(type, values.data(), count, wet, graph->sampleRate, oversampling);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(setParameter)(
    JNIEnv*, jobject, jlong handle, jint bus, jint effect, jint parameter, jfloat value) {
    reinterpret_cast<MixerGraph*>(handle)->chains[bus].setParameter(effect, parameter, value);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(setWet)(
    JNIEnv*, jobject, jlong handle, jint bus, jint effect, jfloat wet) {
    reinterpret_cast<MixerGraph*>(handle)->chains[bus].setWet(effect, wet);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(render)(
    JNIEnv* env, jobject, jlong handle, jobject input, jint offset, jobject output,
    jint frames, jfloatArray controls, jboolean fxEnabled, jfloatArray peaks) {
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
    auto* dest = static_cast<int16_t*>(env->GetDirectBufferAddress(output));
    float gains[recorder::kBuses * 2];
    float levels[recorder::kBuses * 2];
    env->GetFloatArrayRegion(controls, 0, recorder::kBuses * 2, gains);
    reinterpret_cast<MixerGraph*>(handle)->process(
        reinterpret_cast<const float*>(data + offset), dest, frames, gains, fxEnabled, levels);
    env->SetFloatArrayRegion(peaks, 0, recorder::kBuses * 2, levels);
}
