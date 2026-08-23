// Host-runnable smoke binary for the WOLF core registry logic. Built only when
// CMake is not configuring for Android (see CMakeLists.txt); the Android JNI
// smoke path is exercised by WolfNativeSmokeTest on device.

#include "wolf_core.h"

#include <iostream>

int main() {
    if (!wolf::smokeTest()) {
        std::cerr << "wolf core smoke test failed" << std::endl;
        return 1;
    }
    const wolf::LoadResult result = wolf::loadGame("host-smoke", ".");
    if (result.handle == 0) {
        std::cerr << "loadGame failed: " << result.error << std::endl;
        return 1;
    }
    wolf::renderFrame(result.handle, 320, 240);
    const wolf::Diagnostics diagnostics = wolf::diagnosticsSnapshot(result.handle);
    if (diagnostics.framesRendered != 1) {
        std::cerr << "unexpected framesRendered: " << diagnostics.framesRendered << std::endl;
        return 1;
    }
    wolf::destroySession(result.handle);
    std::cout << "wolf host smoke ok (sdl=" << wolf::sdlRuntimeVersion() << ")" << std::endl;
    return 0;
}
