# Builds the whisper.cpp engine from the pinned submodule plus the fork's
# two JNI libraries. Everything the app loads is compiled here from source;
# nothing prebuilt is checked in.
cmake_minimum_required(VERSION 3.22)
project(whisper_android LANGUAGES C CXX)

# Fail loudly and early for a missing submodule: without this the first
# error is an unhelpful add_subdirectory failure deep in the configure log.
if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/CMakeLists.txt)
    message(FATAL_ERROR
        "The whisper.cpp submodule is missing. Run: git submodule update --init")
endif()

# Static-link the engine into the JNI shim: one self-contained .so, no
# separate ggml/whisper libraries to package or version.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Cross-compiling for a device population, not the build host: no
# -march=native. Without GGML_NATIVE, ggml adds no -march at all (plain
# armv8-a, losing dotprod and fp16 vector arithmetic), so the feature floor
# must be stated explicitly. Deliberately WITHOUT +i8mm: that is an
# Armv8.6 feature the Pixel 6 Pro class of hardware does not have, and the
# features are compile-time selected with no runtime dispatch, so a floor
# above the device population makes every transcription fail on capable
# phones. libwhispercpu.so's runtime probe checks exactly this floor.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING
    "CPU feature floor; must stay in sync with the probe in whisper_cpu.c" FORCE)

# No OpenMP runtime to ship, no RPC surface, no dynamically loaded
# backends: the engine runs CPU-only inside the app process, and every
# switch that would add a network daemon, dlopen path, or extra .so stays
# off. WHISPER_CURL would embed a downloader; model downloads are the
# Kotlin installer's job, behind its own digest verification.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)

# AGP compiles the Debug variant's native code with CMake's Debug config,
# whose default flags carry no optimization level at all. Unoptimized ggml
# is slower by an order of magnitude, which makes debug-build dictation
# unusable and hides real performance regressions. Per-config option, so
# Release keeps its own flags.
add_compile_options($<$<CONFIG:Debug>:-O3>)

add_subdirectory(whisper.cpp)

# CPU capability probe. A separate baseline-arch library so the check can
# run on CPUs that libwhisperjni.so itself must never execute on.
add_library(whispercpu SHARED whisper_cpu.c)
target_link_options(whispercpu PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.map")

# The engine shim: whisper.cpp statically linked behind a minimal JNI
# surface. The version script keeps every whisper/ggml symbol local so
# nothing else in the process can interpose them; 16 KB max-page-size keeps
# the libraries loadable on 16 KB page devices.
find_library(log-lib log)
add_library(whisperjni SHARED whisper_jni.cpp)
target_link_libraries(whisperjni PRIVATE whisper ${log-lib})
target_link_options(whisperjni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.map")
