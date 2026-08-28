use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jbyteArray, jobject, jobjectArray, jstring};
use jni::JNIEnv;

use crate::async_pool::{decode_asset_async, read_file_async};
use crate::codec::{decode_asset, list_files_recursive, read_file_fully, KEY_SIZE};

fn throw_illegal_argument(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", message);
}

fn parse_hex_key(hex: &str) -> Result<[u8; KEY_SIZE], String> {
    if hex.len() != KEY_SIZE * 2 {
        return Err(
            "RPG Maker encryption key must contain exactly 32 hexadecimal characters".into(),
        );
    }
    let mut key = [0u8; KEY_SIZE];
    for i in 0..KEY_SIZE {
        key[i] = u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).map_err(|_| {
            "RPG Maker encryption key must contain exactly 32 hexadecimal characters".to_string()
        })?;
    }
    Ok(key)
}

fn to_jbyte_array<'a>(env: &mut JNIEnv<'a>, bytes: &[u8]) -> Result<JByteArray<'a>, jni::errors::Error> {
    let array = env.new_byte_array(bytes.len() as i32)?;
    // jni expects i8 for jbyte
    let as_i8: Vec<i8> = bytes.iter().map(|b| *b as i8).collect();
    env.set_byte_array_region(&array, 0, &as_i8)?;
    Ok(array)
}

fn from_jbyte_array(env: &mut JNIEnv, array: &JByteArray) -> Result<Vec<u8>, jni::errors::Error> {
    let len = env.get_array_length(array)? as usize;
    let mut buf = vec![0i8; len];
    env.get_byte_array_region(array, 0, &mut buf)?;
    Ok(buf.into_iter().map(|b| b as u8).collect())
}

fn invoke_callback_success(env: &mut JNIEnv, callback: &JObject, bytes: &[u8]) {
    if let Ok(array) = to_jbyte_array(env, bytes) {
        let _ = env.call_method(
            callback,
            "onSuccess",
            "([B)V",
            &[JValue::Object(&array)],
        );
    }
}

fn invoke_callback_error(env: &mut JNIEnv, callback: &JObject, message: &str) {
    if let Ok(jmsg) = env.new_string(message) {
        let _ = env.call_method(
            callback,
            "onError",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&jmsg)],
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_webview_nativebridge_RpgmNative_nativeDecodeAsset<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    hex_key: JString<'local>,
    stored_bytes: JByteArray<'local>,
) -> jbyteArray {
    let hex: String = match env.get_string(&hex_key) {
        Ok(s) => s.into(),
        Err(_) => {
            throw_illegal_argument(&mut env, "Encrypted asset key is null");
            return std::ptr::null_mut();
        }
    };
    let key = match parse_hex_key(&hex) {
        Ok(k) => k,
        Err(msg) => {
            throw_illegal_argument(&mut env, &msg);
            return std::ptr::null_mut();
        }
    };
    let stored = match from_jbyte_array(&mut env, &stored_bytes) {
        Ok(v) => v,
        Err(_) => {
            throw_illegal_argument(&mut env, "Encrypted asset is null");
            return std::ptr::null_mut();
        }
    };
    match decode_asset(&key, &stored) {
        Ok(plain) => match to_jbyte_array(&mut env, &plain) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(err) => {
            throw_illegal_argument(&mut env, &err.message);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_webview_nativebridge_RpgmNative_nativeReadFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jbyteArray {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => {
            throw_illegal_argument(&mut env, "Path is empty");
            return std::ptr::null_mut();
        }
    };
    match read_file_fully(&path) {
        Ok(bytes) => match to_jbyte_array(&mut env, &bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(err) => {
            throw_illegal_argument(&mut env, &err.message);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_webview_nativebridge_RpgmNative_nativeListFiles<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    root_path: JString<'local>,
) -> jobjectArray {
    let root: String = match env.get_string(&root_path) {
        Ok(s) => s.into(),
        Err(_) => {
            throw_illegal_argument(&mut env, "Path is empty");
            return std::ptr::null_mut();
        }
    };
    let entries = match list_files_recursive(&root) {
        Ok(v) => v,
        Err(err) => {
            throw_illegal_argument(&mut env, &err.message);
            return std::ptr::null_mut();
        }
    };
    let string_class = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let array = match env.new_object_array(entries.len() as i32, &string_class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, entry) in entries.iter().enumerate() {
        let encoded = format!(
            "{}\u{0001}{}\u{0001}{}",
            entry.relative_path, entry.size, entry.last_modified_millis
        );
        let Ok(jstr) = env.new_string(&encoded) else {
            return std::ptr::null_mut();
        };
        if env
            .set_object_array_element(&array, i as i32, &jstr)
            .is_err()
        {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_webview_nativebridge_RpgmNative_nativeReadFileAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    callback: JObject<'local>,
) {
    if callback.is_null() {
        throw_illegal_argument(&mut env, "callback is null");
        return;
    }
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => {
            throw_illegal_argument(&mut env, "Path is empty");
            return;
        }
    };
    let Ok(jvm) = env.get_java_vm() else {
        throw_illegal_argument(&mut env, "Unable to get JavaVM");
        return;
    };
    let Ok(global) = env.new_global_ref(&callback) else {
        throw_illegal_argument(&mut env, "Unable to retain callback");
        return;
    };
    read_file_async(path, move |result| {
        let Ok(mut env) = jvm.attach_current_thread() else {
            return;
        };
        match result {
            Ok(bytes) => invoke_callback_success(&mut env, &global, &bytes),
            Err(err) => invoke_callback_error(&mut env, &global, &err.message),
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_gdlbo_makerplay_runtime_webview_nativebridge_RpgmNative_nativeDecodeAssetAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    hex_key: JString<'local>,
    stored_bytes: JByteArray<'local>,
    callback: JObject<'local>,
) {
    if callback.is_null() {
        throw_illegal_argument(&mut env, "callback is null");
        return;
    }
    let hex: String = match env.get_string(&hex_key) {
        Ok(s) => s.into(),
        Err(_) => {
            throw_illegal_argument(&mut env, "Encrypted asset key is null");
            return;
        }
    };
    let key = match parse_hex_key(&hex) {
        Ok(k) => k,
        Err(msg) => {
            throw_illegal_argument(&mut env, &msg);
            return;
        }
    };
    let stored = match from_jbyte_array(&mut env, &stored_bytes) {
        Ok(v) => v,
        Err(_) => {
            throw_illegal_argument(&mut env, "Encrypted asset is null");
            return;
        }
    };
    let Ok(jvm) = env.get_java_vm() else {
        throw_illegal_argument(&mut env, "Unable to get JavaVM");
        return;
    };
    let Ok(global) = env.new_global_ref(&callback) else {
        throw_illegal_argument(&mut env, "Unable to retain callback");
        return;
    };
    decode_asset_async(key, stored, move |result| {
        let Ok(mut env) = jvm.attach_current_thread() else {
            return;
        };
        match result {
            Ok(bytes) => invoke_callback_success(&mut env, &global, &bytes),
            Err(err) => invoke_callback_error(&mut env, &global, &err.message),
        }
    });
}

// Silence unused import warnings for jstring/jobject aliases used via into_raw.
#[allow(dead_code)]
fn _type_pins(_: jstring, _: jobject) {}
