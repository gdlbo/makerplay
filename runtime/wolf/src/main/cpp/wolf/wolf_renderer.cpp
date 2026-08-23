// Minimal GLES2 renderer for the WOLF runtime: uploads the current session
// frame as a texture and draws it letterboxed into the surface. Replaced by
// the full map renderer pipeline as later milestones layer in scrolling,
// sprites, and effects.

#include "wolf_renderer.h"

#include <GLES2/gl2.h>

#include <android/log.h>
#include <cstring>
#include <mutex>

namespace wolf {

namespace {

const char* kVertexShader =
    "attribute vec2 aPos;\n"
    "varying vec2 vUV;\n"
    "uniform vec4 uScale;\n"  // xy = quad scale, zw = center offset
    "void main() {\n"
    "    vUV = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);\n"
    "    vec2 p = aPos * uScale.xy + uScale.zw;\n"
    "    gl_Position = vec4(p, 0.0, 1.0);\n"
    "}\n";

const char* kFragmentShader =
    "precision mediump float;\n"
    "varying vec2 vUV;\n"
    "uniform sampler2D uTex;\n"
    "void main() {\n"
    "    gl_FragColor = texture2D(uTex, vUV);\n"
    "}\n";

struct RendererState {
    bool initialized = false;
    GLuint program = 0;
    GLuint texture = 0;
    GLint uScale = -1;
    uint64_t uploadedVersion = 0;
};

std::mutex g_mutex;          // guards renderer creation across sessions
RendererState g_state;       // single GL context (GLSurfaceView client version)

GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok == 0) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool ensureInitialized() {
    if (g_state.initialized) return true;
    const GLuint vs = compile(GL_VERTEX_SHADER, kVertexShader);
    const GLuint fs = compile(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vs == 0 || fs == 0) {
        if (vs != 0) glDeleteShader(vs);
        if (fs != 0) glDeleteShader(fs);
        return false;
    }
    const GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok == 0) {
        glDeleteProgram(program);
        return false;
    }
    g_state.program = program;

    glGenTextures(1, &g_state.texture);
    glBindTexture(GL_TEXTURE_2D, g_state.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    g_state.uScale = glGetUniformLocation(program, "uScale");
    g_state.initialized = true;
    return true;
}

}  // namespace

void rendererDrawFrame(int surfaceWidth,
                       int surfaceHeight,
                       const uint8_t* rgba,
                       int frameWidth,
                       int frameHeight,
                       uint64_t frameVersion,
                       bool newFrame) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!ensureInitialized() || rgba == nullptr || frameWidth <= 0 || frameHeight <= 0) {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        return;
    }

    glBindTexture(GL_TEXTURE_2D, g_state.texture);
    if (g_state.uploadedVersion != frameVersion || newFrame) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, frameWidth, frameHeight, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        g_state.uploadedVersion = frameVersion;
    }

    // Letterbox fit: the unit quad (+/-1) covers surfaceWidth*scaleX pixels,
    // so choose scaleX/Y so the frame occupies fw*fit x fh*fit pixels where
    // fit = min(surfaceWidth/fw, surfaceHeight/fh).
    float scaleX = 1.0f;
    float scaleY = 1.0f;
    if (surfaceWidth > 0 && surfaceHeight > 0) {
        const float sw = static_cast<float>(surfaceWidth);
        const float sh = static_cast<float>(surfaceHeight);
        const float fw = static_cast<float>(frameWidth);
        const float fh = static_cast<float>(frameHeight);
        const float fit = (sw / fw) < (sh / fh) ? (sw / fw) : (sh / fh);
        scaleX = fw * fit / sw;
        scaleY = fh * fit / sh;
    }

    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(g_state.program);
    glUniform4f(g_state.uScale, scaleX, scaleY, 0.0f, 0.0f);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, kQuadVertices);
    glEnableVertexAttribArray(0);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(glGetUniformLocation(g_state.program, "uTex"), 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

}  // namespace wolf
