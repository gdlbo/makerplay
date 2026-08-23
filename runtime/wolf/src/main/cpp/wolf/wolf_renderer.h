#ifndef WOLF_RENDERER_H
#define WOLF_RENDERER_H

#include <cstdint>

namespace wolf {

// Unit quad as a triangle strip in clip space, shared by the draw call.
inline const float kQuadVertices[8] = {
    -1.0f, -1.0f,
     1.0f, -1.0f,
    -1.0f,  1.0f,
     1.0f,  1.0f,
};

/**
 * Draws the current session frame letterboxed into the surface. Must be called
 * with a current GL context (GL thread).
 */
void rendererDrawFrame(int surfaceWidth,
                       int surfaceHeight,
                       const uint8_t* rgba,
                       int frameWidth,
                       int frameHeight,
                       uint64_t frameVersion,
                       bool newFrame);

}  // namespace wolf

#endif  // WOLF_RENDERER_H
