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

# ----------------------------------------------------------------
# Our JNI bridge library
# ----------------------------------------------------------------
add_library(describe_jni SHARED describe_jni.cpp)

target_link_libraries(describe_jni
    PRIVATE
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
