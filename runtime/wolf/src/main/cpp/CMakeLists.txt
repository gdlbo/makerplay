cmake_minimum_required(VERSION 3.22.1)
project(wolf_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# --- Vendored SDL3 (zlib licensed) — remains C ------------------------------------
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SDL_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(third-party/SDL EXCLUDE_FROM_ALL)

# --- Rust Wolf core (session + GLES renderer + JNI) -------------------------------
if(NOT ANDROID)
    message(STATUS "Skipping Rust wolf_native on non-Android host build")
    return()
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(WOLF_RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(WOLF_RUST_TARGET "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(WOLF_RUST_TARGET "x86_64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86")
    set(WOLF_RUST_TARGET "i686-linux-android")
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI for wolf Rust core: ${ANDROID_ABI}")
endif()

set(WOLF_RUST_CRATE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../rust/wolf_native")
set(WOLF_RUST_LIB "${WOLF_RUST_CRATE_DIR}/target/${WOLF_RUST_TARGET}/release/libwolf_native.a")

if(NOT DEFINED ENV{ANDROID_NDK_HOME} AND DEFINED ANDROID_NDK)
    set(ENV{ANDROID_NDK_HOME} "${ANDROID_NDK}")
endif()

add_custom_command(
    OUTPUT "${WOLF_RUST_LIB}"
    COMMAND ${CMAKE_COMMAND} -E env
        ANDROID_NDK_HOME=$ENV{ANDROID_NDK_HOME}
        cargo ndk -t ${ANDROID_ABI} build --release
    WORKING_DIRECTORY "${WOLF_RUST_CRATE_DIR}"
    COMMENT "Building Rust wolf_native staticlib for ${ANDROID_ABI}"
    VERBATIM
)
add_custom_target(wolf_rust_core DEPENDS "${WOLF_RUST_LIB}")

add_library(wolf_rust_static STATIC IMPORTED GLOBAL)
set_target_properties(wolf_rust_static PROPERTIES IMPORTED_LOCATION "${WOLF_RUST_LIB}")
add_dependencies(wolf_rust_static wolf_rust_core)

# Shared .so consumed by System.loadLibrary("wolf_native")
add_library(wolf_native SHARED wolf_link_stub.cpp)
target_link_libraries(wolf_native PRIVATE
    -Wl,--whole-archive wolf_rust_static -Wl,--no-whole-archive
    SDL3::SDL3
    android
    log
    GLESv2
    EGL
    c++_shared
)
target_compile_options(wolf_native PRIVATE -fvisibility=hidden)
