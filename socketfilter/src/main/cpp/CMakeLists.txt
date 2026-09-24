cmake_minimum_required(VERSION 3.22)
project(gravel_socketfilter LANGUAGES C)

add_library(gravelsocketfilter SHARED socket_filter.c)
target_compile_options(gravelsocketfilter PRIVATE -Wall -Wextra -Werror)
target_link_libraries(gravelsocketfilter PRIVATE android)
# 16 KB page alignment, and only the JNI entry points exported (socket_filter.map).
target_link_options(gravelsocketfilter PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/socket_filter.map")
