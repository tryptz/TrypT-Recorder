cmake_minimum_required(VERSION 3.22.1)
project(recorder_dsp LANGUAGES CXX)

# Only the snap-ins (tac_dsp): the mixer below is the recorder's own, so the
# core's DspEngine and its USB driver stay out of the build.
set(TAC_BUILD_USB OFF CACHE BOOL "" FORCE)
set(TAC_BUILD_ENGINE OFF CACHE BOOL "" FORCE)
add_subdirectory(tryptify-audio-core)
# The core builds with -ffast-math, which assumes no NaN or infinity and so
# deletes isfinite() checks - including the ones that keep a broken effect
# from feeding NaN onward. Keep fast maths, but not that assumption.
target_compile_options(tac_dsp PRIVATE -fno-finite-math-only)

if(ANDROID)
    add_library(recorder_dsp SHARED mixer_jni.cpp)
    target_compile_options(recorder_dsp PRIVATE -Wall -Wextra -fvisibility=hidden)
    target_link_libraries(recorder_dsp PRIVATE tac_dsp)
    # Export only the JNI entry points: without this the static DSP library and
    # libc++ leaked ~2,400 symbols into the .so (two thirds of its size), and
    # kept code alive that nothing calls.
    target_link_options(recorder_dsp PRIVATE "-Wl,--exclude-libs,ALL")
else()
    enable_testing()
    add_executable(recorder_mixer_test tests/recorder_mixer_test.cpp)
    target_include_directories(recorder_mixer_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(recorder_mixer_test PRIVATE -UNDEBUG)
    target_link_libraries(recorder_mixer_test PRIVATE tac_dsp)
    add_test(NAME recorder_mixer COMMAND recorder_mixer_test)
endif()
