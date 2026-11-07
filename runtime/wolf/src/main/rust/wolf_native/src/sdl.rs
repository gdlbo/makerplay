//! SDL3 FFI (vendored C library).

use std::ffi::{c_char, c_int, c_void, CString};
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

// --- Window and event loop -------------------------------------------------

use crate::sdl_event::{decode_event, AppEvent, SDL_EVENT_SIZE};

/// `SDL_INIT_VIDEO`.
const SDL_INIT_VIDEO: u32 = 0x0000_0020;
const SDL_WINDOW_FULLSCREEN: u64 = 0x0000_0000_0000_0001;
const SDL_WINDOW_OPENGL: u64 = 0x0000_0000_0000_0002;
const SDL_WINDOW_HIGH_PIXEL_DENSITY: u64 = 0x0000_0000_0000_2000;
/// The property holding the `ANativeWindow` on Android.
const PROP_ANDROID_WINDOW: &str = "SDL.window.android.window";

#[link(name = "SDL3")]
extern "C" {
    fn SDL_Init(flags: u32) -> bool;
    fn SDL_Quit();
    fn SDL_GetError() -> *const c_char;
    fn SDL_CreateWindow(title: *const c_char, w: c_int, h: c_int, flags: u64) -> *mut c_void;
    fn SDL_CreateWindowWithProperties(props: u32) -> *mut c_void;
    fn SDL_CreateProperties() -> u32;
    fn SDL_SetNumberProperty(props: u32, name: *const c_char, value: i64) -> bool;
    fn SDL_SetBooleanProperty(props: u32, name: *const c_char, value: bool) -> bool;
    fn SDL_SetStringProperty(props: u32, name: *const c_char, value: *const c_char) -> bool;
    fn SDL_SetPointerProperty(props: u32, name: *const c_char, value: *mut c_void) -> bool;
    fn SDL_DestroyProperties(props: u32);
    fn SDL_DestroyWindow(window: *mut c_void);
    fn SDL_GetWindowSize(window: *mut c_void, w: *mut c_int, h: *mut c_int);
    fn SDL_GetWindowSizeInPixels(window: *mut c_void, w: *mut c_int, h: *mut c_int);
    fn SDL_GetWindowProperties(window: *mut c_void) -> u32;
    fn SDL_GetPointerProperty(
        props: u32,
        name: *const c_char,
        default_value: *mut c_void,
    ) -> *mut c_void;
    fn SDL_PollEvent(event: *mut c_void) -> bool;
    fn SDL_FlushEvents(min_type: u32, max_type: u32);
    fn SDL_GetTicks() -> u64;
    fn SDL_Delay(ms: u32);
    fn SDL_GL_CreateContext(window: *mut c_void) -> *mut c_void;
    fn SDL_GL_DestroyContext(context: *mut c_void) -> bool;
    fn SDL_GL_SwapWindow(window: *mut c_void) -> bool;
    fn SDL_GL_SetSwapInterval(interval: c_int) -> bool;
    fn SDL_SetHint(name: *const c_char, value: *const c_char) -> bool;
}

/// Selects the SDL video driver for the next `SDL_Init`.
///
/// Must be called before the window is created. Used by device tests to run the
/// loop on SDL's offscreen driver: an instrumentation test has no Activity, so
/// the Android driver has no `ANativeWindow` to attach to and window creation
/// fails with "Could not fetch native window". The offscreen driver is a real SDL
/// driver, so the loop code path exercised is the shipping one — only the surface
/// differs.
pub fn set_video_driver(driver: &str) -> bool {
    let Ok(name) = CString::new("SDL_VIDEO_DRIVER") else {
        return false;
    };
    let Ok(value) = CString::new(driver) else {
        return false;
    };
    unsafe { SDL_SetHint(name.as_ptr(), value.as_ptr()) }
}

pub fn last_error() -> String {
    unsafe {
        let ptr = SDL_GetError();
        if ptr.is_null() {
            return String::new();
        }
        std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned()
    }
}

pub fn ticks_ms() -> u64 {
    unsafe { SDL_GetTicks() }
}

pub fn delay_ms(ms: u32) {
    unsafe { SDL_Delay(ms) }
}

/// Prepares SDL's Android backend.
///
/// SDL's video driver calls back into `org.libsdl.app.SDLActivity` to obtain the
/// surface, and that Java side is wired up by `SDL.setupJNI()` plus
/// `SDL.initialize()`. Without them `SDL_CreateWindow` aborts with
/// "CallStaticObjectMethod received NULL jclass" — which is exactly what happened
/// the first time the loop ran on device.
///
/// The calls are made through the `jni` crate, which already resolves the
/// `JNIEnv` function table correctly; hand-rolling the vtable is easy to get
/// subtly wrong and there is no reason to.
pub fn prepare_android_backend(env: &mut jni::JNIEnv) -> Result<(), String> {
    for method in ["setupJNI", "initialize"] {
        let result = env.call_static_method("org/libsdl/app/SDL", method, "()V", &[]);
        match result {
            Ok(_) => {}
            Err(error) => {
                // A pending Java exception must be cleared or every later JNI
                // call aborts the process.
                let _ = env.exception_clear();
                return Err(format!("SDL.{method}() failed: {error}"));
            }
        }
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            return Err(format!("SDL.{method}() threw"));
        }
    }
    Ok(())
}

/// Acquires the `ANativeWindow` behind a `android.view.Surface`.
///
/// Calls `ANativeWindow_fromSurface`, which increments the window's reference
/// count; the caller (SDL) releases it with `ANativeWindow_release`. Returns the
/// raw pointer, or an error when the surface is not a valid Android surface.
///
/// Done here rather than in Kotlin because the pointer never needs to exist on the
/// Java side, and passing it through a `jlong` would invite a dangling reference.
pub fn native_window_from_surface(
    env: &mut jni::JNIEnv,
    surface: &jni::objects::JObject,
) -> Result<*mut std::ffi::c_void, String> {
    // ANativeWindow_fromSurface lives in libandroid, which CMake already links.
    extern "C" {
        fn ANativeWindow_fromSurface(
            env: *mut std::ffi::c_void,
            surface: *mut std::ffi::c_void,
        ) -> *mut std::ffi::c_void;
    }
    let raw_env = env.get_native_interface() as *mut std::ffi::c_void;
    let raw_surface = surface.as_raw() as *mut std::ffi::c_void;
    if raw_env.is_null() || raw_surface.is_null() {
        return Err("surface or JNIEnv was null".to_string());
    }
    let window = unsafe { ANativeWindow_fromSurface(raw_env, raw_surface) };
    if window.is_null() {
        return Err("ANativeWindow_fromSurface returned null".to_string());
    }
    Ok(window)
}

/// Owns the SDL window and GL context.
pub struct SdlWindow {
    window: *mut c_void,
    context: *mut c_void,
    initialized: bool,
}

impl SdlWindow {
    /// Creates the window and GL context.
    pub fn create(title: &str, width: i32, height: i32) -> Result<Self, String> {
        Self::create_inner(title, width, height, true)
    }

    /// Creates the window **without** a GL context.
    ///
    /// Input handling does not need GL, and an offscreen surface on this emulator
    /// has no working EGL device enumeration, so requiring a context would make the
    /// input path untestable on device. Splitting the two keeps the input path
    /// testable without pretending a GL context exists.
    pub fn create_without_gl(title: &str, width: i32, height: i32) -> Result<Self, String> {
        Self::create_inner(title, width, height, false)
    }

    /// Creates a window that adopts an **existing** `ANativeWindow`.
    ///
    /// This is what lets the app's own `SurfaceView` keep the surface, and
    /// therefore its input dispatch path, while the Rust SDL loop renders into it.
    /// Requires the vendored SDL patch in
    /// `src/video/android/SDL_androidwindow.c` that reads the pointer property at
    /// creation time.
    ///
    /// `native_window` must be an acquired `ANativeWindow*` (as returned by
    /// `ANativeWindow_fromSurface`, which increments the reference count); SDL
    /// releases it on destroy, matching its own JNI path.
    pub fn create_with_native_window(
        title: &str,
        width: i32,
        height: i32,
        native_window: *mut c_void,
        with_gl: bool,
    ) -> Result<Self, String> {
        set_main_ready();
        unsafe {
            if !SDL_Init(SDL_INIT_VIDEO) {
                return Err(format!("SDL_Init failed: {}", last_error()));
            }
            let props = SDL_CreateProperties();
            if props == 0 {
                SDL_Quit();
                return Err(format!("SDL_CreateProperties failed: {}", last_error()));
            }
            let mut flags = SDL_WINDOW_FULLSCREEN | SDL_WINDOW_HIGH_PIXEL_DENSITY;
            if with_gl {
                flags |= SDL_WINDOW_OPENGL;
            }
            if let Ok(name) = CString::new("SDL.window.create.title") {
                if let Ok(value) = CString::new(title) {
                    SDL_SetStringProperty(props, name.as_ptr(), value.as_ptr());
                }
            }
            if let Ok(name) = CString::new("SDL.window.create.width") {
                SDL_SetNumberProperty(props, name.as_ptr(), width as i64);
            }
            if let Ok(name) = CString::new("SDL.window.create.height") {
                SDL_SetNumberProperty(props, name.as_ptr(), height as i64);
            }
            if let Ok(name) = CString::new("SDL.window.create.fullscreen") {
                SDL_SetBooleanProperty(props, name.as_ptr(), true);
            }
            if let Ok(name) = CString::new("SDL.window.create.high_pixel_density") {
                SDL_SetBooleanProperty(props, name.as_ptr(), true);
            }
            if with_gl {
                if let Ok(name) = CString::new("SDL.window.create.opengl") {
                    SDL_SetBooleanProperty(props, name.as_ptr(), true);
                }
            }
            // The property the vendored patch reads.
            if let Ok(name) = CString::new("SDL.window.android.window") {
                SDL_SetPointerProperty(props, name.as_ptr(), native_window);
            }
            let _ = flags;
            let window = SDL_CreateWindowWithProperties(props);
            SDL_DestroyProperties(props);
            if window.is_null() {
                let error = last_error();
                SDL_Quit();
                return Err(format!("SDL_CreateWindowWithProperties failed: {error}"));
            }
            let mut context: *mut c_void = std::ptr::null_mut();
            if with_gl {
                context = SDL_GL_CreateContext(window);
                if context.is_null() {
                    let error = last_error();
                    SDL_DestroyWindow(window);
                    SDL_Quit();
                    return Err(format!("SDL_GL_CreateContext failed: {error}"));
                }
                SDL_GL_SetSwapInterval(1);
            }
            Ok(Self {
                window,
                context,
                initialized: true,
            })
        }
    }

    fn create_inner(
        title: &str,
        width: i32,
        height: i32,
        with_gl: bool,
    ) -> Result<Self, String> {
        set_main_ready();
        unsafe {
            if !SDL_Init(SDL_INIT_VIDEO) {
                return Err(format!("SDL_Init failed: {}", last_error()));
            }
            let c_title = CString::new(title).unwrap_or_default();
            let mut flags = SDL_WINDOW_FULLSCREEN | SDL_WINDOW_HIGH_PIXEL_DENSITY;
            if with_gl {
                flags |= SDL_WINDOW_OPENGL;
            }
            let window = SDL_CreateWindow(c_title.as_ptr(), width, height, flags);
            if window.is_null() {
                let error = last_error();
                SDL_Quit();
                return Err(format!("SDL_CreateWindow failed: {error}"));
            }
            let mut context: *mut c_void = std::ptr::null_mut();
            if with_gl {
                context = SDL_GL_CreateContext(window);
                if context.is_null() {
                    let error = last_error();
                    SDL_DestroyWindow(window);
                    SDL_Quit();
                    return Err(format!("SDL_GL_CreateContext failed: {error}"));
                }
                SDL_GL_SetSwapInterval(1);
            }
            Ok(Self {
                window,
                context,
                initialized: true,
            })
        }
    }

    /// The Android `ANativeWindow` backing this window, if any.
    pub fn android_native_window(&self) -> *mut c_void {
        unsafe {
            let props = SDL_GetWindowProperties(self.window);
            if props == 0 {
                return std::ptr::null_mut();
            }
            let Ok(name) = CString::new(PROP_ANDROID_WINDOW) else {
                return std::ptr::null_mut();
            };
            SDL_GetPointerProperty(props, name.as_ptr(), std::ptr::null_mut())
        }
    }

    pub fn size(&self) -> (i32, i32) {
        let mut w = 0;
        let mut h = 0;
        unsafe { SDL_GetWindowSize(self.window, &mut w, &mut h) };
        (w, h)
    }

    /// Size in physical pixels, which is what the GL viewport needs.
    pub fn pixel_size(&self) -> (i32, i32) {
        let mut w = 0;
        let mut h = 0;
        unsafe { SDL_GetWindowSizeInPixels(self.window, &mut w, &mut h) };
        (w, h)
    }

    pub fn swap(&self) {
        unsafe {
            SDL_GL_SwapWindow(self.window);
        }
    }

    /// Drains the event queue into `out`. Never blocks.
    pub fn poll_events(&self, out: &mut Vec<AppEvent>) {
        out.clear();
        let mut raw = [0u8; SDL_EVENT_SIZE];
        unsafe {
            while SDL_PollEvent(raw.as_mut_ptr().cast()) {
                if let Some(event) = decode_event(&raw) {
                    out.push(event);
                }
            }
        }
    }

    /// Drops queued events of every type, so stale touches from a previous
    /// session cannot drive the new one.
    pub fn flush_events(&self) {
        unsafe { SDL_FlushEvents(0, 0xFFFF) };
    }
}

// The window and GL context are created and used on one thread. The registry
// holds the loop behind a mutex, so the compiler needs this assertion; it is safe
// because `SdlWindow` is never used from two threads at once.
unsafe impl Send for SdlWindow {}

impl Drop for SdlWindow {
    fn drop(&mut self) {
        unsafe {
            if !self.context.is_null() {
                SDL_GL_DestroyContext(self.context);
            }
            if !self.window.is_null() {
                SDL_DestroyWindow(self.window);
            }
            if self.initialized {
                SDL_Quit();
            }
        }
    }
}
