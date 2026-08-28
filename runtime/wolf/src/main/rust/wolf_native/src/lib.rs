//! Wolf native runtime (`libwolf_native`).

mod session;

#[cfg(not(test))]
mod gles;
#[cfg(not(test))]
mod renderer;
#[cfg(not(test))]
mod sdl;
#[cfg(not(test))]
mod jni_bridge;

pub use session::smoke_test_registry;

#[no_mangle]
pub extern "C" fn wolf_rust_abi_version() -> i32 {
    3
}
