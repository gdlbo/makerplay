//! Minimal GLES2 FFI used by the Wolf letterbox renderer.

#![allow(non_camel_case_types, non_snake_case, dead_code)]

use std::os::raw::{c_char, c_float, c_void};

pub type GLenum = u32;
pub type GLbitfield = u32;
pub type GLuint = u32;
pub type GLint = i32;
pub type GLsizei = i32;
pub type GLboolean = u8;

pub const GL_COLOR_BUFFER_BIT: GLbitfield = 0x00004000;
pub const GL_TEXTURE_2D: GLenum = 0x0DE1;
pub const GL_TEXTURE0: GLenum = 0x84C0;
pub const GL_RGBA: GLenum = 0x1908;
pub const GL_UNSIGNED_BYTE: GLenum = 0x1401;
pub const GL_FLOAT: GLenum = 0x1406;
pub const GL_FALSE: GLboolean = 0;
pub const GL_TRIANGLE_STRIP: GLenum = 0x0005;
pub const GL_VERTEX_SHADER: GLenum = 0x8B31;
pub const GL_FRAGMENT_SHADER: GLenum = 0x8B30;
pub const GL_COMPILE_STATUS: GLenum = 0x8B81;
pub const GL_LINK_STATUS: GLenum = 0x8B82;
pub const GL_TEXTURE_MIN_FILTER: GLenum = 0x2801;
pub const GL_TEXTURE_MAG_FILTER: GLenum = 0x2800;
pub const GL_TEXTURE_WRAP_S: GLenum = 0x2802;
pub const GL_TEXTURE_WRAP_T: GLenum = 0x2803;
pub const GL_LINEAR: GLint = 0x2601;
pub const GL_NEAREST: GLint = 0x2600;
pub const GL_CLAMP_TO_EDGE: GLint = 0x812F;

#[link(name = "GLESv2")]
extern "C" {
    pub fn glCreateShader(type_: GLenum) -> GLuint;
    pub fn glShaderSource(shader: GLuint, count: GLsizei, string: *const *const c_char, length: *const GLint);
    pub fn glCompileShader(shader: GLuint);
    pub fn glGetShaderiv(shader: GLuint, pname: GLenum, params: *mut GLint);
    pub fn glDeleteShader(shader: GLuint);
    pub fn glCreateProgram() -> GLuint;
    pub fn glAttachShader(program: GLuint, shader: GLuint);
    pub fn glBindAttribLocation(program: GLuint, index: GLuint, name: *const c_char);
    pub fn glLinkProgram(program: GLuint);
    pub fn glGetProgramiv(program: GLuint, pname: GLenum, params: *mut GLint);
    pub fn glDeleteProgram(program: GLuint);
    pub fn glGenTextures(n: GLsizei, textures: *mut GLuint);
    pub fn glBindTexture(target: GLenum, texture: GLuint);
    pub fn glTexParameteri(target: GLenum, pname: GLenum, param: GLint);
    pub fn glGetUniformLocation(program: GLuint, name: *const c_char) -> GLint;
    pub fn glClearColor(red: c_float, green: c_float, blue: c_float, alpha: c_float);
    pub fn glClear(mask: GLbitfield);
    pub fn glTexImage2D(
        target: GLenum,
        level: GLint,
        internalformat: GLint,
        width: GLsizei,
        height: GLsizei,
        border: GLint,
        format: GLenum,
        type_: GLenum,
        pixels: *const c_void,
    );
    pub fn glViewport(x: GLint, y: GLint, width: GLsizei, height: GLsizei);
    pub fn glUseProgram(program: GLuint);
    pub fn glUniform4f(location: GLint, v0: c_float, v1: c_float, v2: c_float, v3: c_float);
    pub fn glUniform1i(location: GLint, v0: GLint);
    pub fn glVertexAttribPointer(
        index: GLuint,
        size: GLint,
        type_: GLenum,
        normalized: GLboolean,
        stride: GLsizei,
        pointer: *const c_void,
    );
    pub fn glEnableVertexAttribArray(index: GLuint);
    pub fn glActiveTexture(texture: GLenum);
    pub fn glDrawArrays(mode: GLenum, first: GLint, count: GLsizei);
}
