//! Letterbox GLES2 frame presenter.

use std::ffi::CString;
use std::sync::Mutex;

use once_cell::sync::Lazy;

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

struct RendererState {
    initialized: bool,
    program: GLuint,
    texture: GLuint,
    u_scale: GLint,
    uploaded_version: u64,
}

static STATE: Lazy<Mutex<RendererState>> = Lazy::new(|| {
    Mutex::new(RendererState {
        initialized: false,
        program: 0,
        texture: 0,
        u_scale: -1,
        uploaded_version: 0,
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
    let attr = CString::new("aPos").unwrap();
    glBindAttribLocation(program, 0, attr.as_ptr());
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
    glGenTextures(1, &mut state.texture);
    glBindTexture(GL_TEXTURE_2D, state.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    let u_scale = CString::new("uScale").unwrap();
    state.u_scale = glGetUniformLocation(program, u_scale.as_ptr());
    state.initialized = true;
    true
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
    let mut state = STATE.lock().expect("renderer poisoned");
    unsafe {
        if !ensure_initialized(&mut state)
            || rgba.is_none()
            || frame_width <= 0
            || frame_height <= 0
        {
            glClearColor(0.0, 0.0, 0.0, 1.0);
            glClear(GL_COLOR_BUFFER_BIT);
            return;
        }
        let rgba = rgba.unwrap();
        glBindTexture(GL_TEXTURE_2D, state.texture);
        if state.uploaded_version != frame_version || new_frame {
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
        let u_tex = CString::new("uTex").unwrap();
        glUniform1i(glGetUniformLocation(state.program, u_tex.as_ptr()), 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
}
