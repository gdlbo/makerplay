//! Letterbox GLES2 frame presenter.

use std::ffi::CString;
use std::sync::{LazyLock, Mutex};

use crate::gles::*;

const QUAD: [f32; 8] = [-1.0, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, 1.0];

const VERTEX_SHADER: &str = r#"
attribute vec2 aPos;
varying vec2 vUV;
uniform vec4 uScale;
void main() {
    vUV = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    vec2 p = aPos * uScale.xy + uScale.zw;
    gl_Position = vec4(p, 0.0, 1.0);
}
"#;

const FRAGMENT_SHADER: &str = r#"
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
void main() {
    gl_FragColor = texture2D(uTex, vUV);
}
"#;

const ATTR_POS: &str = "aPos";
const UNIFORM_SCALE: &str = "uScale";
const UNIFORM_TEXTURE: &str = "uTex";

struct RendererState {
    initialized: bool,
    program: GLuint,
    texture: GLuint,
    u_scale: GLint,
    u_tex: GLint,
    uploaded_version: u64,
    /// Dimensions the texture storage was allocated with, or 0 when unallocated.
    texture_width: i32,
    texture_height: i32,
}

static STATE: LazyLock<Mutex<RendererState>> = LazyLock::new(|| {
    Mutex::new(RendererState {
        initialized: false,
        program: 0,
        texture: 0,
        u_scale: -1,
        u_tex: -1,
        uploaded_version: 0,
        texture_width: 0,
        texture_height: 0,
    })
});

unsafe fn compile(shader_type: GLenum, source: &str) -> GLuint {
    let shader = glCreateShader(shader_type);
    let c = CString::new(source).unwrap_or_default();
    let ptr = c.as_ptr();
    glShaderSource(shader, 1, &ptr, std::ptr::null());
    glCompileShader(shader);
    let mut ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &mut ok);
    if ok == 0 {
        glDeleteShader(shader);
        return 0;
    }
    shader
}

unsafe fn uniform_location(program: GLuint, name: &str) -> GLint {
    match CString::new(name) {
        Ok(name) => glGetUniformLocation(program, name.as_ptr()),
        Err(_) => -1,
    }
}

unsafe fn ensure_initialized(state: &mut RendererState) -> bool {
    if state.initialized {
        return true;
    }
    let vs = compile(GL_VERTEX_SHADER, VERTEX_SHADER);
    let fs = compile(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    if vs == 0 || fs == 0 {
        if vs != 0 {
            glDeleteShader(vs);
        }
        if fs != 0 {
            glDeleteShader(fs);
        }
        return false;
    }
    let program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    if let Ok(attr) = CString::new(ATTR_POS) {
        glBindAttribLocation(program, 0, attr.as_ptr());
    }
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    let mut ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &mut ok);
    if ok == 0 {
        glDeleteProgram(program);
        return false;
    }
    state.program = program;
    // Sampler and scale locations are stable for the life of the program, so resolve
    // them once here instead of issuing a GL lookup on every frame.
    state.u_scale = uniform_location(program, UNIFORM_SCALE);
    state.u_tex = uniform_location(program, UNIFORM_TEXTURE);

    glGenTextures(1, &mut state.texture);
    glBindTexture(GL_TEXTURE_2D, state.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    state.texture_width = 0;
    state.texture_height = 0;
    state.uploaded_version = 0;
    state.initialized = true;
    true
}

unsafe fn clear_black() {
    glClearColor(0.0, 0.0, 0.0, 1.0);
    glClear(GL_COLOR_BUFFER_BIT);
}

pub fn draw_frame(
    surface_width: i32,
    surface_height: i32,
    rgba: Option<&[u8]>,
    frame_width: i32,
    frame_height: i32,
    frame_version: u64,
    new_frame: bool,
) {
    let mut state = STATE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    unsafe {
        let ready = ensure_initialized(&mut state);
        let (Some(rgba), true) = (rgba, ready && frame_width > 0 && frame_height > 0) else {
            clear_black();
            return;
        };
        // Bounds check before handing the slice to GL: `glTexImage2D` reads
        // `width * height * 4` bytes unconditionally. Saturating so absurd dimensions
        // cannot wrap the requirement back down to a satisfiable value.
        let needed = (frame_width as u64)
            .saturating_mul(frame_height as u64)
            .saturating_mul(4);
        if (rgba.len() as u64) < needed {
            clear_black();
            return;
        }

        glBindTexture(GL_TEXTURE_2D, state.texture);
        let size_changed =
            state.texture_width != frame_width || state.texture_height != frame_height;
        if size_changed || new_frame || state.uploaded_version != frame_version {
            if size_changed {
                glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA as GLint,
                    frame_width,
                    frame_height,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    rgba.as_ptr().cast(),
                );
                state.texture_width = frame_width;
                state.texture_height = frame_height;
            } else {
                // Same dimensions: refresh the existing storage instead of asking the
                // driver to allocate a new texture every frame.
                glTexSubImage2D(
                    GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    frame_width,
                    frame_height,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    rgba.as_ptr().cast(),
                );
            }
            state.uploaded_version = frame_version;
        }

        let mut scale_x = 1.0f32;
        let mut scale_y = 1.0f32;
        if surface_width > 0 && surface_height > 0 {
            let sw = surface_width as f32;
            let sh = surface_height as f32;
            let fw = frame_width as f32;
            let fh = frame_height as f32;
            let fit = (sw / fw).min(sh / fh);
            scale_x = fw * fit / sw;
            scale_y = fh * fit / sh;
        }

        glViewport(0, 0, surface_width, surface_height);
        glClearColor(0.0, 0.0, 0.0, 1.0);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(state.program);
        glUniform4f(state.u_scale, scale_x, scale_y, 0.0, 0.0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, QUAD.as_ptr().cast());
        glEnableVertexAttribArray(0);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(state.u_tex, 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
}
