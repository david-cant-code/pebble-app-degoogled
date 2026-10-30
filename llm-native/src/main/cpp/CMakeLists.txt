# Builds the llama.cpp text engine from the pinned submodule plus Gravel's
# two JNI libraries, with the flags of :whisper-native; each engine links
# its own static copy of ggml behind its own version script, so the two
# pins move independently.
cmake_minimum_required(VERSION 3.22)
project(llm_android LANGUAGES C CXX)

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt)
    message(FATAL_ERROR
        "The llama.cpp submodule is missing. Run: git submodule update --init")
endif()

# Static-link the engine into the JNI shim: one self-contained .so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Cross-compiling for a device population: the CPU feature floor is stated
# explicitly, the same as the speech engine's, and must match the probe in
# llm_cpu.c.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING
    "CPU feature floor; must stay in sync with the probe in llm_cpu.c" FORCE)

# CPU-only inside the app: no OpenMP, RPC, dynamically loaded backends,
# tools, server, examples, tests, common library, web UI or HTTPS; with
# these off nothing in the engine's CMake downloads at configure time.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)

# CMake's Debug config carries no optimization level; unoptimized ggml is slower by an order of magnitude.
add_compile_options($<$<CONFIG:Debug>:-O3>)

# Relative paths in the output; see VerifyNativeLibPaths in androidApp/build.gradle.kts.
add_compile_options(
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=src"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build"
    "-ffile-prefix-map=${CMAKE_ANDROID_NDK}=ndk")

add_subdirectory(llama.cpp)

# CPU capability probe, a baseline-arch library (llm_cpu.c).
add_library(llmcpu SHARED llm_cpu.c)
target_link_options(llmcpu PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/llm_jni.map")

# The engine shim: llama.cpp statically linked behind a minimal JNI surface,
# with the version script exporting only that surface; 16 KB max-page-size
# keeps the libraries loadable on 16 KB page devices.
find_library(log-lib log)
add_library(llmjni SHARED llm_jni.cpp)
target_link_libraries(llmjni PRIVATE llama ${log-lib})
target_link_options(llmjni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/llm_jni.map")
