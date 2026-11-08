//! JNI exports for `WolfNativeJni`.

use jni::objects::{JByteArray, JFloatArray, JIntArray, JObject, JString};
use jni::sys::{
    jboolean, jbyteArray, jdoubleArray, jfloat, jint, jintArray, jlong, jstring, JNI_FALSE,
    JNI_TRUE,
};
use jni::JNIEnv;

use crate::{renderer, sdl, session};

fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value)
        .map(|s| s.into())
        .unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_sdlVersion<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    env.new_string(sdl::version_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSmokeTest(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    if sdl::smoke_with_sdl(session::smoke_test_registry()) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoadGame<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    game_id: JString<'local>,
    game_root: JString<'local>,
) -> jlong {
    let id = jstring_to_string(&mut env, &game_id);
    let root = jstring_to_string(&mut env, &game_root);
    let handle = session::load_game(&id, &root);
    if handle == 0 {
        let _ = env.throw_new(
            "io/github/gdlbo/makerplay/runtime/api/WolfNativeLoadException",
            "load failed",
        );
        return 0;
    }
    handle as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeDestroySession(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    session::destroy_session(handle as u64);
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSetPaused(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
    paused: jboolean,
) {
    session::set_paused(handle as u64, paused == JNI_TRUE);
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeRequestExit(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    session::request_exit(handle as u64);
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSetStaticFrame<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    rgba: JByteArray<'local>,
    width: jint,
    height: jint,
) {
    if width <= 0 || height <= 0 {
        return;
    }
    // Hand the JNI buffer straight to the session: no intermediate copy is needed.
    let Ok(bytes) = env.convert_byte_array(&rgba) else {
        return;
    };
    session::set_static_frame(handle as u64, bytes, width, height);
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeRenderFrame(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
    width: jint,
    height: jint,
) {
    let Some((should_draw, frame)) = session::take_render_frame(handle as u64) else {
        return;
    };
    if !should_draw {
        return;
    }
    match frame {
        Some((rgba, fw, fh, version)) => {
            renderer::draw_frame(width, height, Some(rgba.as_slice()), fw, fh, version, true);
        }
        None => {
            renderer::draw_frame(width, height, None, 0, 0, 0, false);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSetInputState<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    actions: JIntArray<'local>,
    axes: JFloatArray<'local>,
) {
    let mut action_vals: Vec<i32> = Vec::new();
    let mut axis_vals: Vec<f32> = Vec::new();
    if let Ok(len) = env.get_array_length(&actions) {
        action_vals.resize(len as usize, 0);
        if len > 0 {
            let _ = env.get_int_array_region(&actions, 0, &mut action_vals);
        }
    }
    if let Ok(len) = env.get_array_length(&axes) {
        axis_vals.resize(len as usize, 0.0);
        if len > 0 {
            let _ = env.get_float_array_region(&axes, 0, &mut axis_vals);
        }
    }
    session::set_input_state(handle as u64, &action_vals, &axis_vals);
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSerializeSave<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    _slot: JString<'local>,
) -> jbyteArray {
    match session::serialize_save(handle as u64) {
        Ok(payload) => env
            .byte_array_from_slice(&payload)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeRestoreSave<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    _slot: JString<'local>,
    payload: JByteArray<'local>,
) -> jboolean {
    let bytes = env.convert_byte_array(&payload).unwrap_or_default();
    if session::restore_save(handle as u64, &bytes) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeDiagnosticsSnapshot<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> jdoubleArray {
    let (frames, avg, maps, events, audio) = session::diagnostics_snapshot(handle as u64);
    let values = [
        frames as f64,
        avg,
        maps as f64,
        events as f64,
        audio as f64,
    ];
    match env.new_double_array(5) {
        Ok(arr) => {
            let _ = env.set_double_array_region(&arr, 0, &values);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLastError<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> jstring {
    match session::last_error(handle as u64) {
        Some(msg) => env
            .new_string(msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// Parses a deployment's `Data` folder with the Rust format layer and returns a
/// one-line summary, or an empty string when the directory cannot be read.
///
/// This is the reachability anchor for `wolfformat`: without a caller like this
/// the whole module is discarded by LTO, so the format layer would compile and
/// pass its tests while being absent from `libwolf_native.so`.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeProbeFormats<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    data_dir: JString<'local>,
) -> jstring {
    let dir = jstring_to_string(&mut env, &data_dir);
    if dir.is_empty() {
        return std::ptr::null_mut();
    }
    let summary = crate::wolfformat::probe::probe_deployment(std::path::Path::new(&dir));
    env.new_string(summary.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Runs an interpreter corpus and returns its trace.
///
/// This is the reachability anchor for the `interpreter` module. The crate builds
/// with `lto = true` and `strip = true`, so an interpreter that no exported symbol
/// calls is discarded: it would compile, pass its unit tests, and be absent from
/// `libwolf_native.so`. Routing the shared differential corpus through here makes
/// the shipped interpreter observable on device.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeRunInterpreterCorpus<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    corpus: JString<'local>,
) -> jstring {
    let text = jstring_to_string(&mut env, &corpus);
    let trace = match crate::interpreter::corpus::run_corpus(&text) {
        Ok(trace) => trace,
        Err(e) => format!("ERROR: {e}"),
    };
    env.new_string(trace)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Runs an interpreter corpus with the deployment's databases loaded, and
/// returns its trace.
///
/// Needed because `250/251/252/255/256/257` only execute inside the interpreter
/// when a database is present; without one they reach the host hook. This makes
/// the shipped database and XY-array implementation observable on device.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeRunInterpreterCorpusWithDatabase<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    corpus: JString<'local>,
    data_dir: JString<'local>,
) -> jstring {
    let text = jstring_to_string(&mut env, &corpus);
    let dir = jstring_to_string(&mut env, &data_dir);
    let trace = if dir.is_empty() {
        crate::interpreter::corpus::run_corpus(&text)
    } else {
        crate::interpreter::corpus::run_corpus_with_database(
            &text,
            Some(std::path::Path::new(&dir)),
        )
    };
    let trace = match trace {
        Ok(t) => t,
        Err(e) => format!("ERROR: {e}"),
    };
    env.new_string(trace)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// --- Rust-owned loop -------------------------------------------------------

/// How many externally composed frames have been presented, for diagnostics.
static EXTERNAL_PRESENTS: std::sync::Mutex<u64> = std::sync::Mutex::new(0);

use crate::app::{AppState, StepOutcome};
use crate::input::InputState;
use crate::sdl::SdlWindow;

/// A running Rust-owned loop.
struct RunningLoop {
    state: AppState,
    window: Option<SdlWindow>,
    /// Events decoded by the last `poll_events` call.
    events: Vec<crate::sdl_event::AppEvent>,
    quit: bool,
    /// A frame composed outside the loop, presented instead of the loop's own
    /// session.
    ///
    /// The shipped app drives gameplay in Kotlin (that engine is what has the
    /// save/load, message and battle behaviour verified by tests) and hands the
    /// composed frame here. Without this the loop would draw its own empty
    /// session and the app would show black, which is why the view could not
    /// simply be pointed at the loop.
    external_frame: Option<(Vec<u8>, i32, i32)>,
    /// Incremented when `external_frame` changes, so the presenter can skip
    /// re-uploading an unchanged image.
    external_version: u64,
}

impl Drop for RunningLoop {
    fn drop(&mut self) {
        // SDL_Quit destroys audio streams; release Rust owners first.
        self.state.disable_audio();
        self.window = None;
    }
}

/// Host implementation that forwards presentation to the session registry so the
/// existing Kotlin diagnostics keep working.
/// Host bridge for the Rust loop.
///
/// It does not merely count: messages become the in-frame message box and pictures
/// become composited sprites, so a script running inside the loop actually draws.
/// The mutation is staged and applied after the step, because the interpreter holds
/// `&mut self` while it calls the host and the session lives in the same struct.

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopInitializeGameplay<'local>(
    mut env: JNIEnv<'local>, _this: JObject<'local>,
) -> jstring {
    let storage=(|| -> Result<std::path::PathBuf,String> {
        let application=env.call_static_method("android/app/ActivityThread","currentApplication","()Landroid/app/Application;",&[])
            .and_then(|v|v.l()).map_err(|e|e.to_string())?;
        let directory=env.call_method(&application,"getFilesDir","()Ljava/io/File;",&[])
            .and_then(|v|v.l()).map_err(|e|e.to_string())?;
        let path=env.call_method(&directory,"getAbsolutePath","()Ljava/lang/String;",&[])
            .and_then(|v|v.l()).map_err(|e|e.to_string())?;
        let path=JString::from(path);
        let text:String=env.get_string(&path).map_err(|e|e.to_string())?.into();
        Ok(std::path::PathBuf::from(text).join("wolf-native-fields"))
    })();
    let result = with_loop(|slot| {
        let running = slot.as_mut().ok_or("no native loop is running")?;
        let session=running.state.session.as_ref().ok_or("no deployment session")?;
        let root=session.deployment_root().join("Data");
        // Stable deployment namespace; never write inside the deployment itself.
        let mut identity=0xcbf29ce484222325u64;
        for byte in session.deployment_root().to_string_lossy().as_bytes() {identity=(identity^u64::from(*byte)).wrapping_mul(0x100000001b3);}
        let save_root=storage?.join(format!("{identity:016x}"));
        running.state.initialize_gameplay(&root)?;
        running.state.save_root=Some(save_root);
        Ok::<(),String>(())
    });
    env.new_string(result.err().unwrap_or_default()).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopGameplayDiagnostics<'local>(
    env: JNIEnv<'local>, _this: JObject<'local>,
) -> jstring {
    let text=with_loop(|slot|slot.as_ref().map(|running|running.state.gameplay_diagnostics()).unwrap_or_else(||"no native loop".into()));
    env.new_string(text).map(|value|value.into_raw()).unwrap_or(std::ptr::null_mut())
}

static LOOP_STATE: std::sync::Mutex<Option<RunningLoop>> = std::sync::Mutex::new(None);

/// One standalone scene session for the app-side compositor, keyed by game root.
static SCENE_STATE: std::sync::Mutex<Option<(String, crate::game_session::GameSession)>> =
    std::sync::Mutex::new(None);

fn with_loop<T>(f: impl FnOnce(&mut Option<RunningLoop>) -> T) -> T {
    let mut guard = LOOP_STATE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    f(&mut guard)
}

/// Starts the Rust-owned SDL loop.
///
/// Returns an empty string on success, or the failure reason. The loop is driven
/// by `nativeLoopStep` from the platform thread, which keeps SDL's requirement
/// that the window is created and used on one thread.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopStart<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    title: JString<'local>,
    game_width: jint,
    game_height: jint,
    video_driver: JString<'local>,
    game_root: JString<'local>,
    map_name: JString<'local>,
) -> jstring {
    let title = jstring_to_string(&mut env, &title);
    let root = jstring_to_string(&mut env, &game_root);
    let map = jstring_to_string(&mut env, &map_name);
    let (gw, gh) = (game_width.max(1), game_height.max(1));
    let driver = jstring_to_string(&mut env, &video_driver);

    // Must happen before SDL_Init inside SdlWindow::create.
    if !driver.is_empty() && !crate::sdl::set_video_driver(&driver) {
        return env
            .new_string(format!("could not select video driver {driver:?}"))
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }

    // SDL's Android video backend needs its Java side wired up before any window
    // exists; skipping this aborts the process inside SDL_CreateWindow.
    if let Err(error) = crate::sdl::prepare_android_backend(&mut env) {
        return env
            .new_string(error)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }

    let result = with_loop(|slot| {
        if slot.is_some() {
            return Err("a loop is already running".to_string());
        }
        // SDL's "dummy" driver is a real driver that creates a window record with
        // no surface, used when a caller only needs input and the interpreter
        // (device tests, headless). Any other driver gets a GL context.
        let window = if driver == "dummy" {
            SdlWindow::create_without_gl(&title, gw, gh)?
        } else {
            SdlWindow::create(&title, gw, gh)?
        };
        let mut state = AppState::new(gw, gh);
        state.enable_audio();
        // Load the renderer session so the loop draws the game rather than black.
        // A failure is reported through `last_error` and the loop still runs, which
        // keeps input and the interpreter testable on a machine without the game.
        if !root.is_empty() && !map.is_empty() {
            match crate::game_session::GameSession::open(std::path::Path::new(&root), &map) {
                Ok(session) => state = state.with_session(session),
                Err(error) => eprintln!("wolf: session load failed: {error}"),
            }
        }
        let (w, h) = window.size();
        state.input.resize_window(w, h);
        window.flush_events();
        *slot = Some(RunningLoop {
            state,
            window: Some(window),
            events: Vec::new(),
            quit: false,
            external_frame: None,
            external_version: 0,
        });
        Ok(())
    });

    match result {
        Ok(()) => env
            .new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => env
            .new_string(error)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

/// Runs one loop iteration: poll events, apply them, tick the interpreter, present.
///
/// Returns 0 to continue, 1 when the loop has stopped. Keeping the iteration
/// explicit (rather than blocking inside a `while` loop) lets the platform thread
/// stay responsive to lifecycle events.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopStep(
    mut _env: JNIEnv,
    _this: JObject,
) -> jint {
    with_loop(|slot| {
        let Some(running) = slot.as_mut() else {
            return 1;
        };
        if running.quit {
            return 1;
        }
        if let Some(window) = running.window.as_ref() {
            window.poll_events(&mut running.events);
        }
        let events = std::mem::take(&mut running.events);
        let mut host = running.state.step_native(&events);
        running.events = events;
        if let Some(error) = &running.state.runtime_error {
            running.quit = true;
            let _ = _env.throw_new("java/lang/IllegalStateException", error);
            return 1;
        }

        // Apply what the script asked to present. Doing it after `step` is what lets
        // the host borrow the loop's input state while the interpreter runs.
        if let Some(session) = running.state.session.as_mut() {
            if let Some(message) = host.message.take() {
                session.set_message(Some(message));
            }
            if host.pictures_changed {
                // Erases and shows both arrive; pass them through in order so the
                // session's own slot semantics apply.
                let mut existing: Vec<(i32, String, i32, i32, i32)> = Vec::new();
                for (slot, name, x, y, option) in host.pictures.drain(..) {
                    if name.is_empty() {
                        existing.retain(|(s, _, _, _, _)| *s != slot);
                    } else {
                        existing.retain(|(s, _, _, _, _)| *s != slot);
                        existing.push((slot, name, x, y, option));
                    }
                }
                if let Err(error)=session.set_pictures(&existing) {
                    running.quit=true;
                    let _ = _env.throw_new("java/lang/IllegalStateException", error);
                    return 1;
                }
            }
        }

        if running.state.outcome() == StepOutcome::Quit {
            running.quit = true;
            return 1;
        }
        if let Some(window) = running.window.as_ref() {
            let (pw, ph) = window.pixel_size();
            if let Some((rgba, fw, fh)) = running.external_frame.as_ref() {
                let mut diag = EXTERNAL_PRESENTS.lock().unwrap_or_else(|p| p.into_inner());
                *diag += 1;
                if *diag <= 3 || *diag % 120 == 0 {
                    eprintln!(
                        "wolf: present #{} window={}x{} frame={}x{} bytes={}",
                        *diag, pw, ph, fw, fh, rgba.len()
                    );
                }
                // A frame composed outside the loop takes precedence: the app's
                // gameplay lives in Kotlin, and the loop's job here is to present
                // and to own input.
                crate::renderer::draw_frame(
                    pw,
                    ph,
                    Some(rgba.as_slice()),
                    *fw,
                    *fh,
                    running.external_version,
                    true,
                );
                window.swap();
                return 0;
            }
            // Compose the game frame and present it. Without a session this draws
            // black, which is the honest state rather than a stale image.
            let rendered = running.state.render(pw, ph);
            let (rgba, fw, fh, version) = match &rendered {
                Some((image, version)) => (
                    Some(image.rgba.as_slice()),
                    image.width as i32,
                    image.height as i32,
                    *version,
                ),
                None => (None, 0, 0, 0),
            };
            crate::renderer::draw_frame(pw, ph, rgba, fw, fh, version, rgba.is_some());
            window.swap();
        }
        0
    })
}

/// Presents a frame the embedder composed, instead of the loop's own session.
///
/// The app keeps its gameplay engine in Kotlin; this is how its frames reach the
/// surface the loop owns. Passing an empty array clears the override and returns
/// the loop to drawing its own session.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopSetExternalFrame<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    rgba: JByteArray<'local>,
    width: jint,
    height: jint,
) {
    if width <= 0 || height <= 0 {
        return;
    }
    let Ok(bytes) = env.convert_byte_array(&rgba) else {
        return;
    };
    with_loop(|slot| {
        if let Some(running) = slot.as_mut() {
            running.external_frame = Some((bytes, width, height));
            running.external_version = running.external_version.wrapping_add(1);
        }
    });
}

/// Injects a pointer position in **window** pixels.
///
/// This exists so device tests can drive the loop deterministically: `adb input
/// tap` never reaches a `GLSurfaceView` in its own window layer, which is the bug
/// this task removes. Routing the injection through the same input path the real
/// SDL events use means the test exercises the shipping code, not a shortcut.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopInjectPointer(
    _env: JNIEnv,
    _this: JObject,
    x: jfloat,
    y: jfloat,
    down: jboolean,
) {
    with_loop(|slot| {
        if let Some(running) = slot.as_mut() {
            running
                .state
                .input
                .pointer_button(x, y, 1, down == JNI_TRUE);
        }
    });
}

/// Injects a pointer position in **game** pixels, bypassing the letterbox maths.
///
/// Used to place a tap on an exact recorded menu rectangle.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopInjectGamePointer(
    _env: JNIEnv,
    _this: JObject,
    x: jint,
    y: jint,
    down: jboolean,
) {
    with_loop(|slot| {
        if let Some(running) = slot.as_mut() {
            running.state.input.pointer_motion_game(x, y);
            running.state.input.set_button(1, down == JNI_TRUE);
        }
    });
}

/// The WOLF key code the loop currently reports, 0 when nothing is pressed.
///
/// This is the acceptance signal for the task: a synthetic touch at a known game
/// coordinate must make this return 20.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopPollKey(
    _env: JNIEnv,
    _this: JObject,
) -> jint {
    with_loop(|slot| {
        slot.as_mut()
            .map(|running| {
                let key = running.state.input.poll_key();
                running.state.input.consume_key();
                key
            })
            .unwrap_or(0)
    })
}

/// The cursor position in game pixels, as `[x, y]`, or an empty array.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopCursor<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jintArray {
    let cursor = with_loop(|slot| {
        slot.as_ref()
            .and_then(|running| running.state.input.cursor())
    });
    let values: [jint; 2] = match cursor {
        Some((x, y)) => [x, y],
        None => return std::ptr::null_mut(),
    };
    match env.new_int_array(2) {
        Ok(array) => {
            let _ = env.set_int_array_region(&array, 0, &values);
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Diagnostics for the loop: `[frames, events_applied, quit, running]`.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopStats<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jdoubleArray {
    // Index 4 is how many externally composed frames have been presented. It is
    // observable from Kotlin because Rust's stderr does not reach logcat, so a
    // diagnostic that only prints is invisible on device.
    let presents = EXTERNAL_PRESENTS
        .lock()
        .map(|value| *value)
        .unwrap_or(0);
    let values = with_loop(|slot| match slot.as_ref() {
        Some(running) => [
            running.state.frames() as f64,
            running.state.events_applied() as f64,
            if running.quit { 1.0 } else { 0.0 },
            1.0,
            presents as f64,
        ],
        None => [0.0, 0.0, 0.0, 0.0, presents as f64],
    });
    match env.new_double_array(5) {
        Ok(array) => {
            let _ = env.set_double_array_region(&array, 0, &values);
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Stops the loop and releases the window and GL context.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopStop(
    _env: JNIEnv,
    _this: JObject,
) {
    with_loop(|slot| {
        if let Some(mut running) = slot.take() {
            running.quit = true;
            running.state.disable_audio();
            running.window = None; // Drop closes the GL context and window.
        }
    });
}

/// Runs a scripted input sequence through the loop and reports the observed keys.

/// Runs a scripted input sequence through the loop and reports the observed keys.
///
/// Each step is `down:x:y` / `up:x:y` in window pixels, or `gdown:x:y` /
/// `gup:x:y` in game pixels. Returns one line per step:
/// `index:key:cursorX,cursorY`. This lets the device test assert the exact
/// conversion the shipping path performs, with no test-only branch in the loop.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopRunScript<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    script: JString<'local>,
) -> jstring {
    let script = jstring_to_string(&mut env, &script);
    let mut out = String::new();
    for (index, line) in script.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.split(':').collect();
        if parts.len() != 3 {
            out.push_str(&format!("{index}:BADSTEP:0,0\n"));
            continue;
        }
        let op = parts[0];
        let x: f32 = parts[1].trim().parse().unwrap_or(0.0);
        let y: f32 = parts[2].trim().parse().unwrap_or(0.0);
        let down = op.ends_with("down");

        with_loop(|slot| {
            let Some(running) = slot.as_mut() else {
                return;
            };
            let input: &mut InputState = &mut running.state.input;
            match op {
                "down" | "up" => input.pointer_button(x, y, 1, down),
                "gdown" | "gup" => {
                    input.pointer_motion_game(x as i32, y as i32);
                    input.set_button(1, down);
                }
                _ => {}
            }
        });

        let (key, cursor) = with_loop(|slot| match slot.as_ref() {
            Some(running) => (
                running.state.input.poll_key(),
                running.state.input.cursor(),
            ),
            None => (0, None),
        });
        let (cx, cy) = cursor.unwrap_or((-1, -1));
        out.push_str(&format!("{index}:{key}:{cx},{cy}\n"));
    }
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Composes the loop's current frame and returns it as raw RGBA.
///
/// Exists so device tests can prove the Rust renderer produces real pixels, not
/// just that the loop runs. Returns null when no session is loaded.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopFrame<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    width: jint,
    height: jint,
) -> jbyteArray {
    let payload = with_loop(|slot| {
        let running = slot.as_mut()?;
        let (image, _version) = running.state.render(width.max(1), height.max(1))?;
        Some((image.rgba, image.width as i32, image.height as i32))
    });
    let Some((rgba, w, h)) = payload else {
        return std::ptr::null_mut();
    };
    // Prefix the buffer with its dimensions so the caller does not have to guess.
    let mut out = Vec::with_capacity(8 + rgba.len());
    out.extend_from_slice(&w.to_le_bytes());
    out.extend_from_slice(&h.to_le_bytes());
    out.extend_from_slice(&rgba);
    match env.byte_array_from_slice(&out) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Loads a map into the running loop's session.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopLoadMap<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    map_name: JString<'local>,
) -> jstring {
    let name = jstring_to_string(&mut env, &map_name);
    let error = with_loop(|slot| match slot.as_mut() {
        None => Some("no loop is running".to_string()),
        Some(running) => match running.state.session.as_mut() {
            None => Some("no render session loaded".to_string()),
            Some(session) => session.load_map(&name).err(),
        },
    });
    let text = error.unwrap_or_default();
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Hides or shows the hero sprite, so a tile comparison can exclude it.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopSetHeroVisible(
    _env: JNIEnv,
    _this: JObject,
    visible: jboolean,
) {
    with_loop(|slot| {
        if let Some(running) = slot.as_mut() {
            if let Some(session) = running.state.session.as_mut() {
                session.set_hero_visible(visible == JNI_TRUE);
            }
        }
    });
}

/// Places the hero at a tile, so the camera follows.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopSetHero(
    _env: JNIEnv,
    _this: JObject,
    tile_x: jint,
    tile_y: jint,
) {
    with_loop(|slot| {
        if let Some(running) = slot.as_mut() {
            if let Some(session) = running.state.session.as_mut() {
                session.set_hero(tile_x, tile_y);
            }
        }
    });
}

/// Replaces the loop's picture list.
///
/// `spec` is one picture per line: `slot|fileName|x|y|optionWord`. The option word
/// carries the anchor (bits 12..15), blend (bits 8..11) and zoom (bits 20..23) the
/// engine packs into it, so the device test exercises the real decode path.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopSetPictures<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    spec: JString<'local>,
) -> jstring {
    let spec = jstring_to_string(&mut env, &spec);
    let mut parsed: Vec<(i32, String, i32, i32, i32)> = Vec::new();
    for line in spec.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.split('|').collect();
        if parts.len() != 5 {
            return env
                .new_string(format!("bad picture spec: {line}"))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
        let slot = parts[0].trim().parse().unwrap_or(0);
        let x = parts[2].trim().parse().unwrap_or(0);
        let y = parts[3].trim().parse().unwrap_or(0);
        let option = parts[4].trim().parse().unwrap_or(0);
        parsed.push((slot, parts[1].trim().to_string(), x, y, option));
    }
    let error = with_loop(|slot| match slot.as_mut() {
        None => Some("no loop is running".to_string()),
        Some(running) => match running.state.session.as_mut() {
            None => Some("no render session loaded".to_string()),
            Some(session) => {
                session.set_pictures(&parsed).err()
            }
        },
    });
    env.new_string(error.unwrap_or_default())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Number of pictures the loop's session currently holds.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopPictureCount(
    _env: JNIEnv,
    _this: JObject,
) -> jint {
    with_loop(|slot| {
        slot.as_ref()
            .and_then(|running| running.state.session.as_ref())
            .map(|session| session.picture_count() as jint)
            .unwrap_or(0)
    })
}

/// Sets or clears the in-frame message text. Empty string clears it.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopSetMessage<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    text: JString<'local>,
) -> jstring {
    let text = jstring_to_string(&mut env, &text);
    let error = with_loop(|slot| match slot.as_mut() {
        None => Some("no loop is running".to_string()),
        Some(running) => match running.state.session.as_mut() {
            None => Some("no render session loaded".to_string()),
            Some(session) => {
                if text.is_empty() {
                    session.set_message(None);
                } else {
                    session.set_message(Some(text.clone()));
                }
                None
            }
        },
    });
    env.new_string(error.unwrap_or_default())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// True when the session loaded a font (messages can be drawn).
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopHasFont(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    if with_loop(|slot| {
        slot.as_ref()
            .and_then(|running| running.state.session.as_ref())
            .map(|session| session.has_font())
            .unwrap_or(false)
    }) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Sets the loop's map event sprites.
///
/// `spec` is one sprite per line: `graphicFile|tileX|tileY|row|col|opacity`.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopSetEvents<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    spec: JString<'local>,
) -> jstring {
    let spec = jstring_to_string(&mut env, &spec);
    let mut parsed: Vec<(String, i32, i32, u32, u32, i32)> = Vec::new();
    for line in spec.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.split('|').collect();
        if parts.len() != 6 {
            return env
                .new_string(format!("bad event spec: {line}"))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
        parsed.push((
            parts[0].trim().to_string(),
            parts[1].trim().parse().unwrap_or(0),
            parts[2].trim().parse().unwrap_or(0),
            parts[3].trim().parse().unwrap_or(0),
            parts[4].trim().parse().unwrap_or(0),
            parts[5].trim().parse().unwrap_or(255),
        ));
    }
    let error = with_loop(|slot| match slot.as_mut() {
        None => Some("no loop is running".to_string()),
        Some(running) => match running.state.session.as_mut() {
            None => Some("no render session loaded".to_string()),
            Some(session) => {
                session.set_events(&parsed);
                let message = session.last_error().to_string();
                if message.is_empty() { None } else { Some(message) }
            }
        },
    });
    env.new_string(error.unwrap_or_default())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Number of event sprites the loop's session currently holds.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopEventCount(
    _env: JNIEnv,
    _this: JObject,
) -> jint {
    with_loop(|slot| {
        slot.as_ref()
            .and_then(|running| running.state.session.as_ref())
            .map(|session| session.event_count() as jint)
            .unwrap_or(0)
    })
}

/// Runs a command list through the loop's interpreter and returns the composed
/// frame as `[w, h, rgba...]`.
///
/// Proves the loop *presents* what a script asks for, instead of merely counting
/// host callbacks: the text and pictures a script produces must appear in the frame.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopRunScriptCommands<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    script: JString<'local>,
    width: jint,
    height: jint,
) -> jbyteArray {
    let script = jstring_to_string(&mut env, &script);

    // Parse `type|param,param,..|string,string` lines into commands.
    use crate::wolfformat::event_command::EventCommand;
    let mut commands: Vec<EventCommand> = Vec::new();
    for line in script.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts: Vec<&str> = line.split('|').collect();
        let command_type: i32 = parts.first().and_then(|s| s.trim().parse().ok()).unwrap_or(0);
        let params: Vec<i32> = parts
            .get(1)
            .map(|s| {
                s.split(',')
                    .filter(|p| !p.trim().is_empty())
                    .filter_map(|p| p.trim().parse().ok())
                    .collect()
            })
            .unwrap_or_default();
        let strings: Vec<String> = parts
            .get(2)
            .map(|s| {
                if s.is_empty() {
                    Vec::new()
                } else {
                    s.split(',').map(|x| x.to_string()).collect()
                }
            })
            .unwrap_or_default();
        commands.push(EventCommand {
            param_count: if command_type == 0 { 0 } else { params.len() as u8 + 1 },
            command_type,
            params,
            branch_depth: 0,
            strings,
            route: None,
        });
    }

    let payload = with_loop(|slot| {
        let running = slot.as_mut()?;
        running.state.interpreter.start(commands.clone());
        // One step is enough for these scripts, but run a few so waits settle.
        for _ in 0..4 {
            let mut host = running.state.step_native(&[]);
            if let Some(error) = &running.state.runtime_error {
                let _ = env.throw_new("java/lang/IllegalStateException", error);
                return None;
            }
            if let Some(session) = running.state.session.as_mut() {
                if let Some(message) = host.message.take() {
                    session.set_message(Some(message));
                }
                if host.pictures_changed {
                    let mut existing: Vec<(i32, String, i32, i32, i32)> = Vec::new();
                    for (slot, name, x, y, option) in host.pictures.drain(..) {
                        existing.retain(|(s, _, _, _, _)| *s != slot);
                        if !name.is_empty() {
                            existing.push((slot, name, x, y, option));
                        }
                    }
                    if let Err(error)=session.set_pictures(&existing) {
                        let _ = env.throw_new("java/lang/IllegalStateException", error);
                        return None;
                    }
                }
            }
        }
        // The frame after the script has drawn.
        let (image, _version) = running.state.render(width.max(1), height.max(1))?;
        Some((image.rgba, image.width as i32, image.height as i32))
    });

    let Some((rgba, w, h)) = payload else {
        return std::ptr::null_mut();
    };
    let mut out = Vec::with_capacity(8 + rgba.len());
    out.extend_from_slice(&w.to_le_bytes());
    out.extend_from_slice(&h.to_le_bytes());
    out.extend_from_slice(&rgba);
    match env.byte_array_from_slice(&out) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Sets the loop's choice window. `spec` is `selected|opt1|opt2|...`; empty clears.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopSetChoices<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    spec: JString<'local>,
) -> jstring {
    let spec = jstring_to_string(&mut env, &spec);
    let mut parts = spec.split('|');
    let selected: i32 = parts.next().and_then(|s| s.trim().parse().ok()).unwrap_or(0);
    let choices: Vec<String> = parts.map(|s| s.to_string()).filter(|s| !s.is_empty()).collect();
    let error = with_loop(|slot| match slot.as_mut() {
        None => Some("no loop is running".to_string()),
        Some(running) => match running.state.session.as_mut() {
            None => Some("no render session loaded".to_string()),
            Some(session) => {
                session.set_choices(choices.clone(), selected);
                None
            }
        },
    });
    env.new_string(error.unwrap_or_default())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Number of choice options currently shown.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopChoiceCount(
    _env: JNIEnv,
    _this: JObject,
) -> jint {
    with_loop(|slot| {
        slot.as_ref()
            .and_then(|running| running.state.session.as_ref())
            .map(|session| session.choices().len() as jint)
            .unwrap_or(0)
    })
}

/// Picture bounds as a flat `[slot, left, top, right, bottom, ...]` array.
///
/// This is what the engine's "picture under the cursor" query needs, which is how
/// WOLF's mouse-driven menus decide what was clicked.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopPictureBounds<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jintArray {
    let flat = with_loop(|slot| {
        let mut out: Vec<jint> = Vec::new();
        if let Some(session) = slot
            .as_ref()
            .and_then(|running| running.state.session.as_ref())
        {
            for (slot_id, l, t, r, b) in session.picture_bounds() {
                out.push(slot_id as jint);
                out.push(l as jint);
                out.push(t as jint);
                out.push(r as jint);
                out.push(b as jint);
            }
        }
        out
    });
    if flat.is_empty() {
        return env.new_int_array(0).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut());
    }
    match env.new_int_array(flat.len() as i32) {
        Ok(array) => {
            let _ = env.set_int_array_region(&array, 0, &flat);
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Sets an extra camera offset (engine shake/scroll), in pixels.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopSetCameraExtra(
    _env: JNIEnv,
    _this: JObject,
    dx: jint,
    dy: jint,
) {
    with_loop(|slot| {
        if let Some(running) = slot.as_mut() {
            if let Some(session) = running.state.session.as_mut() {
                session.set_camera_extra(dx, dy);
            }
        }
    });
}

/// A standalone Rust scene compositor, for the app side.
///
/// Owns one `GameSession`, so the app can push a scene and get a composed frame
/// without hosting the SDL loop. This is what lets `WolfRuntimeBackend` use the
/// **Rust renderer** while the Kotlin engine keeps owning map loading, triggers,
/// the interpreter and the database.
///
/// Returns `[w, h, rgba...]`, or null when the session could not be opened.
#[no_mangle]
/// Where the last composed scene drew each picture, as a flat
/// `[slot, left, top, right, bottom, ...]` array.
///
/// The pointer hit test for picture-driven menus lives in Kotlin, so it needs the
/// rectangles the **Rust** compositor actually used. Without this the app fell
/// back to the Kotlin compositor's rectangles, which do not exist when Rust
/// composed, so a tap could never select a picture menu row.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeScenePictureBounds<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jintArray {
    let flat = {
        let guard = SCENE_STATE
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match guard.as_ref() {
            Some((_, session)) => {
                let mut out: Vec<jint> = Vec::new();
                for (slot_id, l, t, r, b) in session.picture_bounds() {
                    out.push(slot_id as jint);
                    out.push(l as jint);
                    out.push(t as jint);
                    out.push(r as jint);
                    out.push(b as jint);
                }
                out
            }
            None => Vec::new(),
        }
    };
    match env.new_int_array(flat.len() as i32) {
        Ok(array) => {
            let _ = env.set_int_array_region(&array, 0, &flat);
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeComposeScene<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    game_root: JString<'local>,
    map_name: JString<'local>,
    spec: JString<'local>,
    width: jint,
    height: jint,
) -> jbyteArray {
    let root = jstring_to_string(&mut env, &game_root);
    let map = jstring_to_string(&mut env, &map_name);
    let spec = jstring_to_string(&mut env, &spec);
    if root.is_empty() || map.is_empty() {
        return std::ptr::null_mut();
    }

    let mut guard = SCENE_STATE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());

    // Rebuild only when the game root changes; a new map on the same root is
    // applied in place, which keeps the decoded image cache warm.
    let same_root = guard
        .as_ref()
        .map(|(cached_root, _)| cached_root == &root)
        .unwrap_or(false);
    if !same_root {
        match crate::game_session::GameSession::open(std::path::Path::new(&root), &map) {
            Ok(session) => *guard = Some((root.clone(), session)),
            Err(_) => return std::ptr::null_mut(),
        }
    }

    let Some((_, session)) = guard.as_mut() else {
        return std::ptr::null_mut();
    };
    if let Err(error) = session.apply_scene(&spec) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
        return std::ptr::null_mut();
    }
    let (image, _version) = session.compose(width.max(1), height.max(1));

    let mut out = Vec::with_capacity(8 + image.rgba.len());
    out.extend_from_slice(&(image.width as i32).to_le_bytes());
    out.extend_from_slice(&(image.height as i32).to_le_bytes());
    out.extend_from_slice(&image.rgba);
    match env.byte_array_from_slice(&out) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Clears the standalone scene session, releasing its decoded images.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeResetScene(
    _env: JNIEnv,
    _this: JObject,
) {
    let mut guard = SCENE_STATE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard = None;
}

/// Starts the Rust loop on the app's **own** `Surface`.
///
/// `surface` is a `android.view.Surface` (a `jobject`). Its `ANativeWindow` is
/// acquired with `ANativeWindow_fromSurface`, handed to SDL through the vendored
/// patch, and released by SDL when the window is destroyed.
///
/// This is the difference from `nativeLoopStart`: the app keeps the surface, so
/// SDL's own input path stays valid, and the loop can own input as well as render.
#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoopStartOnSurface<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    surface: JObject<'local>,
    title: JString<'local>,
    game_width: jint,
    game_height: jint,
    game_root: JString<'local>,
    map_name: JString<'local>,
) -> jstring {
    let title = jstring_to_string(&mut env, &title);
    let root = jstring_to_string(&mut env, &game_root);
    let map = jstring_to_string(&mut env, &map_name);
    let (gw, gh) = (game_width.max(1), game_height.max(1));

    // SDL's Android backend must be initialised before any window exists.
    if let Err(error) = crate::sdl::prepare_android_backend(&mut env) {
        return env
            .new_string(error)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }

    // ANativeWindow_fromSurface increments the window's reference count; SDL takes
    // ownership and releases it on destroy, the same contract as its JNI path.
    let native_window = match crate::sdl::native_window_from_surface(&mut env, &surface) {
        Ok(ptr) if !ptr.is_null() => ptr,
        Ok(_) => {
            return env
                .new_string("ANativeWindow_fromSurface returned null")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
        Err(error) => {
            return env
                .new_string(error)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    };

    let result = with_loop(|slot| {
        if slot.is_some() {
            return Err("a loop is already running".to_string());
        }
        let window =
            SdlWindow::create_with_native_window(&title, gw, gh, native_window, true)?;
        let mut state = AppState::new(gw, gh);
        state.enable_audio();
        if !root.is_empty() && !map.is_empty() {
            match crate::game_session::GameSession::open(std::path::Path::new(&root), &map) {
                Ok(session) => state = state.with_session(session),
                Err(error) => eprintln!("wolf: session load failed: {error}"),
            }
        }
        let (w, h) = window.size();
        state.input.resize_window(w, h);
        window.flush_events();
        *slot = Some(RunningLoop {
            state,
            window: Some(window),
            events: Vec::new(),
            quit: false,
            external_frame: None,
            external_version: 0,
        });
        Ok(())
    });

    match result {
        Ok(()) => env
            .new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => env
            .new_string(error)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}
