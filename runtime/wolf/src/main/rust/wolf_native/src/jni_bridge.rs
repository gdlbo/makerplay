//! JNI exports for `WolfNativeJni`.

use jni::objects::{JByteArray, JFloatArray, JIntArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jdoubleArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::{renderer, sdl, session};

fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value)
        .map(|s| s.into())
        .unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_sdlVersion<'local>(
    mut env: JNIEnv<'local>,
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
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
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
    session::set_static_frame(handle as u64, &bytes, width, height);
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
            renderer::draw_frame(width, height, Some(&rgba), fw, fh, version, true);
        }
        None => {
            renderer::draw_frame(width, height, None, 0, 0, 0, false);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSetInputState<'local>(
    mut env: JNIEnv<'local>,
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
    mut env: JNIEnv<'local>,
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
    mut env: JNIEnv<'local>,
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
    mut env: JNIEnv<'local>,
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
