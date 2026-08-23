// WOLF RPG clean-room interpreter core (skeleton). JNI-free API so the core
// can be unit-tested or ported without the Android NDK.

#ifndef WOLF_CORE_H
#define WOLF_CORE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace wolf {

// Logical actions; indices mirror GameAction in core:input.
enum class ActionCount : std::size_t { VALUE = 17 };

struct LoadResult {
    uint64_t handle = 0;
    std::string error;
};

struct SaveResult {
    std::vector<uint8_t> payload;
    std::string error;
};

struct Diagnostics {
    uint64_t framesRendered = 0;
    double averageFrameMillis = 0.0;
    int mapsParsed = 0;
    uint64_t eventsExecuted = 0;
    int audioStreamsActive = 0;
};

const char* sdlRuntimeVersion();
LoadResult loadGame(const std::string& gameId, const std::string& gameRoot);
void destroySession(uint64_t handle);
void setPaused(uint64_t handle, bool paused);
void requestExit(uint64_t handle);
void setStaticFrame(uint64_t handle, const uint8_t* rgba, int32_t size, int32_t width, int32_t height);
void renderFrame(uint64_t handle, int width, int height);
void setInputState(uint64_t handle,
                   const int32_t* actions,
                   int32_t actionCount,
                   const float* axes,
                   int32_t axisCount);
SaveResult serializeSave(uint64_t handle, const std::string& slot);
bool restoreSave(uint64_t handle, const std::string& slot, const uint8_t* payload, int32_t size);
Diagnostics diagnosticsSnapshot(uint64_t handle);
const char* lastError(uint64_t handle);
bool smokeTest();

}  // namespace wolf

#endif  // WOLF_CORE_H
