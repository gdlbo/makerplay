//! Wolf native runtime (`libwolf_native`).

mod session;
pub mod app;
pub mod input;
pub mod interpreter;
pub mod game_session;
pub mod picture;
pub mod png;
pub mod scene;
pub mod wolfformat;

#[cfg(not(test))]
mod gles;
#[cfg(not(test))]
mod renderer;
#[cfg(not(test))]
pub mod sdl;
pub mod sdl_event;
pub mod text;
#[cfg(not(test))]
mod jni_bridge;

pub use session::smoke_test_registry;

#[no_mangle]
pub extern "C" fn wolf_rust_abi_version() -> i32 {
    3
}
