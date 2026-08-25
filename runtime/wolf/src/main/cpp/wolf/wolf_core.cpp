// WOLF RPG clean-room interpreter core (skeleton).
//
// Milestone 2 of docs/wolf-rpg-runtime.md: the native module builds and owns
// a session registry plus diagnostics counters. Parsers (milestone 3), the
// static boot frame (milestone 4), game loop, event interpreter, saves, and
// audio attach to this core incrementally. SDL3 provides surface, input, and
// audio backends; native code owns the game loop and never touches JNI types.

#include "wolf_core.h"

#include "wolf_renderer.h"

#include <memory>
#include <android/log.h>

#ifdef WOLF_HAVE_SDL
#include <SDL3/SDL.h>

// SDL3 declares this only inside SDL_main.h's main() redirection, which does
// not apply to library consumers; the symbol is still exported.
extern "C" void SDL_SetMainReady(void);
#endif

#include <mutex>
#include <unordered_map>
#include <vector>

namespace wolf {

struct Session {
    std::string gameId;
    std::string gameRoot;
    bool paused = false;
    bool exitRequested = false;
    uint64_t framesRendered = 0;
    double totalFrameMillis = 0.0;
    int mapsParsed = 0;
    uint64_t eventsExecuted = 0;
    int audioStreamsActive = 0;
    std::string lastError;

    // Logical input state for the current frame; indices follow GameAction.
    std::vector<bool> actionsPressed;
    std::vector<float> analogAxes;

    // Immutable static-boot frame blob; swapped atomically per update so the
    // render path can draw without holding the registry lock.
    struct FrameBlob {
        std::vector<uint8_t> rgba;
        int width = 0;
        int height = 0;
        uint64_t version = 0;
    };
    std::shared_ptr<const FrameBlob> staticFrame;
};

namespace {

std::mutex g_mutex;
uint64_t g_nextHandle = 1;
std::unordered_map<uint64_t, Session> g_sessions;

Session* find(uint64_t handle) {
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : &it->second;
}

Diagnostics toDiagnostics(const Session& session) {
    Diagnostics diagnostics;
    diagnostics.framesRendered = session.framesRendered;
    diagnostics.averageFrameMillis =
        session.framesRendered == 0
            ? 0.0
            : session.totalFrameMillis / static_cast<double>(session.framesRendered);
    diagnostics.mapsParsed = session.mapsParsed;
    diagnostics.eventsExecuted = session.eventsExecuted;
    diagnostics.audioStreamsActive = session.audioStreamsActive;
    return diagnostics;
}

}  // namespace

const char* sdlRuntimeVersion() {
#ifdef WOLF_HAVE_SDL
    static int version = SDL_GetVersion();
    static std::string text = std::to_string(SDL_VERSIONNUM_MAJOR(version)) + "." +
                              std::to_string(SDL_VERSIONNUM_MINOR(version)) + "." +
                              std::to_string(SDL_VERSIONNUM_MICRO(version));
    return text.c_str();
#else
    return "host";
#endif
}

LoadResult loadGame(const std::string& gameId, const std::string& gameRoot) {
    std::lock_guard<std::mutex> lock(g_mutex);
    LoadResult result;
    // SDL platform init is deferred until a subsystem (audio/input) needs it:
    // SDL_Init performs Java callbacks that require the SDL activity bootstrap,
    // which standalone sessions do not have.
    // Milestone 3 replaces this with bounded parsers for Game.dat and friends.
    Session session;
    session.gameId = gameId;
    session.gameRoot = gameRoot;
    session.actionsPressed.assign(static_cast<size_t>(ActionCount::VALUE), false);
    session.analogAxes.assign(static_cast<size_t>(ActionCount::VALUE), 0.0f);
    const uint64_t handle = g_nextHandle++;
    g_sessions.emplace(handle, std::move(session));
    result.handle = handle;
    return result;
}

void destroySession(uint64_t handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_sessions.erase(handle);
}

void setPaused(uint64_t handle, bool paused) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (Session* session = find(handle)) {
        session->paused = paused;
    }
}

void requestExit(uint64_t handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (Session* session = find(handle)) {
        session->exitRequested = true;
    }
}

void setStaticFrame(uint64_t handle, const uint8_t* rgba, int32_t size, int32_t width, int32_t height) {
    std::lock_guard<std::mutex> lock(g_mutex);
    Session* session = find(handle);
    if (session == nullptr || rgba == nullptr || width <= 0 || height <= 0 ||
        size < width * height * 4) {
        return;
    }
    auto blob = std::make_shared<Session::FrameBlob>();
    blob->rgba.assign(rgba, rgba + (static_cast<size_t>(width) * height * 4));
    blob->width = width;
    blob->height = height;
    blob->version = (session->staticFrame ? session->staticFrame->version : 0) + 1;
    session->staticFrame = std::move(blob);
}

void renderFrame(uint64_t handle, int width, int height) {
    std::shared_ptr<const Session::FrameBlob> frame;
    bool pausedOrExiting = false;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        Session* session = find(handle);
        if (session == nullptr) return;
        pausedOrExiting = session->paused || session->exitRequested;
        frame = session->staticFrame;
        if (!pausedOrExiting) session->framesRendered += 1;
    }
    // Milestones 5+ drive scrolling/sprites from the interpreter; the static
    // boot milestone presents the composited map frame as-is.
    if (pausedOrExiting) return;
    if (frame) {
        rendererDrawFrame(width, height,
                          frame->rgba.data(), frame->width, frame->height,
                          frame->version, /*newFrame=*/true);
    } else {
        rendererDrawFrame(width, height, nullptr, 0, 0, 0, false);
    }
}

void setInputState(uint64_t handle,
                   const int32_t* actions,
                   int32_t actionCount,
                   const float* axes,
                   int32_t axisCount) {
    std::lock_guard<std::mutex> lock(g_mutex);
    Session* session = find(handle);
    if (session == nullptr) {
        return;
    }
    for (int32_t i = 0; i < actionCount && i < static_cast<int32_t>(session->actionsPressed.size()); ++i) {
        session->actionsPressed[static_cast<size_t>(i)] = actions[i] != 0;
    }
    for (int32_t i = 0; i < axisCount && i < static_cast<int32_t>(session->analogAxes.size()); ++i) {
        session->analogAxes[static_cast<size_t>(i)] = axes[i];
    }
}

SaveResult serializeSave(uint64_t handle, const std::string& slot) {
    (void)slot;
    std::lock_guard<std::mutex> lock(g_mutex);
    SaveResult result;
    Session* session = find(handle);
    if (session == nullptr) {
        result.error = "unknown session";
        return result;
    }
    // Milestone 7 implements the atomic WOLF save format.
    result.error = "save format not implemented yet";
    return result;
}

bool restoreSave(uint64_t handle, const std::string& slot, const uint8_t* payload, int32_t size) {
    (void)slot;
    (void)payload;
    (void)size;
    std::lock_guard<std::mutex> lock(g_mutex);
    return find(handle) != nullptr && false;  // Milestone 7.
}

Diagnostics diagnosticsSnapshot(uint64_t handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const Session* session = find(handle);
    if (session == nullptr) {
        return Diagnostics{};
    }
    return toDiagnostics(*session);
}

const char* lastError(uint64_t handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const Session* session = find(handle);
    if (session == nullptr || session->lastError.empty()) {
        return nullptr;
    }
    return session->lastError.c_str();
}

bool smokeTest() {
    // Registry-only smoke check; SDL platform init is deferred (see loadGame).
    std::lock_guard<std::mutex> lock(g_mutex);
#ifdef WOLF_HAVE_SDL
    SDL_SetMainReady();
#endif
    LoadResult result;
    Session session;
    session.gameId = "smoke";
    const uint64_t handle = g_nextHandle++;
    g_sessions.emplace(handle, std::move(session));
    const bool ok = find(handle) != nullptr;
    g_sessions.erase(handle);
#ifdef WOLF_HAVE_SDL
    return ok && SDL_GetVersion() != 0;
#else
    return ok;
#endif
}

}  // namespace wolf
