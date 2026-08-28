//! SDL3 FFI (vendored C library).

use std::sync::OnceLock;

#[link(name = "SDL3")]
extern "C" {
    fn SDL_SetMainReady();
    fn SDL_GetVersion() -> i32;
}

fn version_parts(v: i32) -> (i32, i32, i32) {
    let major = v / 1000000;
    let minor = (v / 1000) % 1000;
    let micro = v % 1000;
    (major, minor, micro)
}

pub fn set_main_ready() {
    unsafe { SDL_SetMainReady() }
}

pub fn get_version_raw() -> i32 {
    unsafe { SDL_GetVersion() }
}

pub fn version_string() -> &'static str {
    static TEXT: OnceLock<String> = OnceLock::new();
    TEXT.get_or_init(|| {
        let (maj, min, mic) = version_parts(get_version_raw());
        format!("{maj}.{min}.{mic}")
    })
    .as_str()
}

pub fn smoke_with_sdl(registry_ok: bool) -> bool {
    set_main_ready();
    registry_ok && get_version_raw() != 0
}
