cmake_minimum_required(VERSION 3.22.1)
project(describe_jni)

# ----------------------------------------------------------------
# llama.cpp configuration — minimum-viable subset for the Day-5
# spike. The goal is to LINK against llama.cpp + ggml so a JNI
# bridge can call basic functions; full multimodal (mtmd) flags
# will get layered on in Day 6.
# ----------------------------------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)            # static — we wrap in libdescribe_jni.so
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

# ggml backend selection — Android arm64 only needs CPU. Disable
# the desktop-only backends so the build doesn't try to find OpenCL /
# Vulkan / CUDA SDKs that aren't part of the NDK.
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)  # don't optimize for build host CPU

# Pull in llama.cpp as a subdirectory build. The path is relative to
# this CMakeLists.txt location: app/src/main/cpp/.
# ../../../../../third_party/llama.cpp/ resolves to repo root.
add_subdirectory(
    ${CMAKE_SOURCE_DIR}/../../../../../third_party/llama.cpp
    ${CMAKE_BINARY_DIR}/llama-cpp-build
    EXCLUDE_FROM_ALL
)

# mtmd (multimodal) lives in tools/mtmd. We do NOT add_subdirectory it
# because tools/mtmd/CMakeLists.txt unconditionally defines six CLI
# executables (llama-llava-cli, llama-gemma3-cli, llama-minicpmv-cli,
# llama-qwen2vl-cli, llama-mtmd-cli, llama-mtmd-debug) that depend on
# llama-common's arg.h header — and llama-common is something we don't
# (and can't, per mtmd's own design) link against. EXCLUDE_FROM_ALL
# on the subdirectory doesn't help: AGP/ninja builds them anyway.
#
# Instead: define a private mtmd library here from the same sources
# the upstream tools/mtmd/CMakeLists.txt lists. Tracks closely with
# upstream — if they add a model file, this list needs updating.
set(MTMD_DIR    ${CMAKE_SOURCE_DIR}/../../../../../third_party/llama.cpp/tools/mtmd)
set(LLAMA_DIR   ${CMAKE_SOURCE_DIR}/../../../../../third_party/llama.cpp)

file(GLOB MTMD_MODEL_SOURCES ${MTMD_DIR}/models/*.cpp)

add_library(mtmd STATIC
    ${MTMD_DIR}/mtmd.cpp
    ${MTMD_DIR}/mtmd-audio.cpp
    ${MTMD_DIR}/mtmd-image.cpp
    ${MTMD_DIR}/mtmd-helper.cpp
    ${MTMD_DIR}/clip.cpp
    ${MTMD_MODEL_SOURCES}
)
target_link_libraries(mtmd PUBLIC ggml llama)
target_include_directories(mtmd PUBLIC  ${MTMD_DIR})
target_include_directories(mtmd PRIVATE ${LLAMA_DIR})
target_include_directories(mtmd PRIVATE ${LLAMA_DIR}/vendor)
target_compile_features(mtmd PRIVATE cxx_std_17)

# miniaudio.h defines a function without a prior prototype that
# triggers a Clang warning-as-error on Android. Suppress it (the
# upstream tools/mtmd/CMakeLists does the same).
target_compile_options(mtmd PRIVATE -Wno-missing-prototypes)

# ----------------------------------------------------------------
# Our JNI bridge library
# ----------------------------------------------------------------
add_library(describe_jni SHARED describe_jni.cpp)

target_link_libraries(describe_jni
    PRIVATE
        # mtmd transitively links llama + ggml + threads, so listing
        # mtmd alone would be enough — but we keep the others explicit
        # for clarity + to insulate against upstream refactors. Note:
        # mtmd-helper is NOT a separate library; its sources are
        # compiled into the mtmd library above.
        mtmd
        llama
        ggml
        ggml-base
        ggml-cpu
        android      # __android_log_print
        log
)

target_compile_features(describe_jni PRIVATE cxx_std_17)

# 16 KB page-size alignment — required by Google Play for new uploads
# (Pixel 9-family + future Android devices use 16 KB pages, not the
# legacy 4 KB). Without this, the loader logs a "LOAD segment not
# aligned" warning and may refuse to map the lib on future OS versions.
# See: https://developer.android.com/guide/practices/page-sizes
target_link_options(describe_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
