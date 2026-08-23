cmake_minimum_required(VERSION 3.22.1)
project(wolf_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# --- Vendored SDL3 (zlib licensed) -------------------------------------------------
# Provides the Android surface, GLES rendering, input, and audio backends.
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SDL_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(third-party/SDL EXCLUDE_FROM_ALL)

# --- WOLF RPG clean-room core -------------------------------------------------------
add_library(wolf_native SHARED
        wolf/wolf_core.cpp
        wolf/wolf_renderer.cpp
        wolf_jni.cpp)

target_include_directories(wolf_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/wolf)

target_link_libraries(wolf_native PRIVATE SDL3::SDL3 android log GLESv2 EGL)

target_compile_definitions(wolf_native PRIVATE WOLF_HAVE_SDL=1)
target_compile_options(wolf_native PRIVATE -Wall -Wextra -fvisibility=hidden)

# Host-runnable smoke binary (not for Android); verifies the core without JNI.
if(NOT ANDROID)
    add_executable(wolf_host_smoke
            wolf/wolf_core.cpp
            wolf/wolf_host_smoke.cpp)
    target_include_directories(wolf_host_smoke PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/wolf)
    target_compile_options(wolf_host_smoke PRIVATE -Wall -Wextra)
endif()
