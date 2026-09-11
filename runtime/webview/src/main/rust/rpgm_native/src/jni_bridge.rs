use std::fmt::Write as _;

use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jbyteArray, jobjectArray};
use jni::JNIEnv;

use crate::async_pool::{decode_asset_async, read_file_async};
use crate::codec::{decode_asset, list_files_recursive, read_file_fully, KEY_SIZE};

const KEY_ERROR: &str = "RPG Maker encryption key must contain exactly 32 hexadecimal characters";

fn throw_illegal_argument(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", message);
}

fn hex_nibble(byte: u8) -> Result<u8, &'static str> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        _ => Err(KEY_ERROR),
    }
}

/// Decodes the RPG Maker key from its hex form.
///
/// Operates on bytes rather than `&str` slices: indexing a `str` by byte offset would
/// panic on a multi-byte character, and a panic on this path aborts the process.
fn parse_hex_key(hex: &str) -> Result<[u8; KEY_SIZE], &'static str> {
    let bytes = hex.as_bytes();
    if bytes.len() != KEY_SIZE * 2 {
        return Err(KEY_ERROR);
    }
    let mut key = [0u8; KEY_SIZE];
    for (slot, pair) in key.iter_mut().zip(bytes.chunks_exact(2)) {
        *slot = (hex_nibble(pair[0])? << 4) | hex_nibble(pair[1])?;
    }
    Ok(key)
}

fn invoke_callback_success(env: &mut JNIEnv, callback: &JObject, bytes: &[u8]) {
    if let Ok(array) = env.byte_array_from_slice(bytes) {
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
            throw_illegal_argument(&mut env, msg);
            return std::ptr::null_mut();
        }
    };
    // Single copy in, single copy out: the stored buffer is decoded in place and only
    // the plaintext (header-offset) view is written into the new Java array.
    let stored = match env.convert_byte_array(&stored_bytes) {
        Ok(v) => v,
        Err(_) => {
            throw_illegal_argument(&mut env, "Encrypted asset is null");
            return std::ptr::null_mut();
        }
    };
    match decode_asset(&key, stored) {
        Ok(plain) => match env.byte_array_from_slice(plain.as_slice()) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(err) => {
            throw_illegal_argument(&mut env, err.message);
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
        Ok(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(err) => {
            throw_illegal_argument(&mut env, err.message);
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
            throw_illegal_argument(&mut env, err.message);
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
    // One reusable row buffer: `format!` would allocate a fresh String per entry.
    let mut row = String::with_capacity(96);
    for (i, entry) in entries.iter().enumerate() {
        row.clear();
        row.push_str(&entry.relative_path);
        let _ = write!(row, "\u{0001}{}\u{0001}{}", entry.size, entry.last_modified_millis);
        let Ok(jstr) = env.new_string(&row) else {
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
        // Pool workers are long-lived, so attach them as daemons: the thread stays
        // attached across jobs instead of attaching/detaching on every callback.
        let Ok(mut env) = jvm.attach_current_thread_as_daemon() else {
            return;
        };
        match result {
            Ok(bytes) => invoke_callback_success(&mut env, &global, &bytes),
            Err(err) => invoke_callback_error(&mut env, &global, err.message),
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
            throw_illegal_argument(&mut env, msg);
            return;
        }
    };
    let stored = match env.convert_byte_array(&stored_bytes) {
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
        let Ok(mut env) = jvm.attach_current_thread_as_daemon() else {
            return;
        };
        match result {
            Ok(plain) => invoke_callback_success(&mut env, &global, plain.as_slice()),
            Err(err) => invoke_callback_error(&mut env, &global, err.message),
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_key_parses_case_insensitively() {
        assert_eq!(
            parse_hex_key("00112233445566778899aabbccddeeff").unwrap(),
            [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]
        );
        assert_eq!(parse_hex_key("FFEEDDCCBBAA99887766554433221100").unwrap()[0], 0xff);
    }

    #[test]
    fn hex_key_rejects_bad_input_without_panicking() {
        for bad in [
            "",
            "00",
            "00112233445566778899aabbccddee",
            "00112233445566778899aabbccddeeg",
            "+0112233445566778899aabbccddeeff",
        ] {
            assert_eq!(parse_hex_key(bad).unwrap_err(), KEY_ERROR);
        }
        // 32 bytes of multi-byte characters: byte-slicing this panicked on a char boundary
        // (and `panic = "abort"` would turn that into a process abort instead of an error).
        let multibyte = format!("{}ab", "\u{20ac}".repeat(10));
        assert_eq!(multibyte.len(), KEY_SIZE * 2);
        assert_eq!(parse_hex_key(&multibyte).unwrap_err(), KEY_ERROR);
        let two_byte = "é".repeat(KEY_SIZE);
        assert_eq!(two_byte.len(), KEY_SIZE * 2);
        assert_eq!(parse_hex_key(&two_byte).unwrap_err(), KEY_ERROR);
    }
}
