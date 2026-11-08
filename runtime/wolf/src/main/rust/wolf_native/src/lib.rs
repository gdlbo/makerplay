//! Wolf native runtime (`libwolf_native`).

mod session;
pub mod app;
pub mod event_scheduler;
pub mod common_runners;
pub mod move_route;
pub mod screen_effect;
pub mod map_shake;
pub mod map_transform;
pub mod audio;
pub mod sound_command;
#[cfg(not(test))]
pub mod audio_output;
mod deployment_files;
pub mod input;
pub mod interpreter;
pub mod game_session;
pub mod picture;
pub mod camera_lock;
pub mod named_numbers;
pub mod picture_effect;
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
mod frame_host;

pub use session::smoke_test_registry;

#[no_mangle]
pub extern "C" fn wolf_rust_abi_version() -> i32 {
    3
}
