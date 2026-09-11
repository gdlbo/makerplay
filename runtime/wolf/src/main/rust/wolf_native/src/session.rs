//! Wolf session registry.

// The JNI module that consumes this API is excluded from test builds, so the registry
// surface looks unused when the test harness compiles the crate.
#![cfg_attr(test, allow(dead_code))]

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};

const ACTION_COUNT: usize = 17;

struct FrameBlob {
    /// Shared with the renderer, so handing a frame over is a refcount bump rather than
    /// a multi-megabyte copy of the RGBA buffer.
    rgba: Arc<Vec<u8>>,
    width: i32,
    height: i32,
    version: u64,
}

struct Session {
    #[allow(dead_code)]
    game_id: String,
    #[allow(dead_code)]
    game_root: String,
    paused: bool,
    exit_requested: bool,
    frames_rendered: u64,
    total_frame_millis: f64,
    maps_parsed: i32,
    events_executed: u64,
    audio_streams_active: i32,
    last_error: String,
    actions_pressed: [bool; ACTION_COUNT],
    analog_axes: [f32; ACTION_COUNT],
    static_frame: Option<FrameBlob>,
}

struct Registry {
    next_handle: u64,
    sessions: HashMap<u64, Session>,
}

static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(|| {
    Mutex::new(Registry {
        next_handle: 1,
        sessions: HashMap::new(),
    })
});

fn with_registry<T>(f: impl FnOnce(&mut Registry) -> T) -> T {
    let mut guard = REGISTRY
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    f(&mut guard)
}

pub fn load_game(game_id: &str, game_root: &str) -> u64 {
    with_registry(|reg| {
        let handle = reg.next_handle;
        reg.next_handle = reg.next_handle.wrapping_add(1).max(1);
        reg.sessions.insert(
            handle,
            Session {
                game_id: game_id.to_string(),
                game_root: game_root.to_string(),
                paused: false,
                exit_requested: false,
                frames_rendered: 0,
                total_frame_millis: 0.0,
                maps_parsed: 0,
                events_executed: 0,
                audio_streams_active: 0,
                last_error: String::new(),
                actions_pressed: [false; ACTION_COUNT],
                analog_axes: [0.0; ACTION_COUNT],
                static_frame: None,
            },
        );
        handle
    })
}

pub fn destroy_session(handle: u64) {
    with_registry(|reg| {
        reg.sessions.remove(&handle);
    });
}

pub fn set_paused(handle: u64, paused: bool) {
    with_registry(|reg| {
        if let Some(session) = reg.sessions.get_mut(&handle) {
            session.paused = paused;
        }
    });
}

pub fn request_exit(handle: u64) {
    with_registry(|reg| {
        if let Some(session) = reg.sessions.get_mut(&handle) {
            session.exit_requested = true;
        }
    });
}

pub fn set_static_frame(handle: u64, mut rgba: Vec<u8>, width: i32, height: i32) {
    if width <= 0 || height <= 0 {
        return;
    }
    let Some(need) = (width as usize)
        .checked_mul(height as usize)
        .and_then(|pixels| pixels.checked_mul(4))
    else {
        return;
    };
    if rgba.len() < need {
        return;
    }
    // The JNI buffer is already exactly this size in the common case, and truncating
    // keeps the allocation, so wrapping it in an Arc copies nothing.
    rgba.truncate(need);
    with_registry(|reg| {
        let Some(session) = reg.sessions.get_mut(&handle) else {
            return;
        };
        let version = session
            .static_frame
            .as_ref()
            .map(|f| f.version + 1)
            .unwrap_or(1);
        session.static_frame = Some(FrameBlob {
            rgba: Arc::new(rgba),
            width,
            height,
            version,
        });
    });
}

pub fn set_input_state(handle: u64, actions: &[i32], axes: &[f32]) {
    with_registry(|reg| {
        let Some(session) = reg.sessions.get_mut(&handle) else {
            return;
        };
        let actions_len = actions.len().min(session.actions_pressed.len());
        for (slot, value) in session.actions_pressed[..actions_len]
            .iter_mut()
            .zip(&actions[..actions_len])
        {
            *slot = *value != 0;
        }
        let axes_len = axes.len().min(session.analog_axes.len());
        session.analog_axes[..axes_len].copy_from_slice(&axes[..axes_len]);
    });
}

/// A frame handed to the renderer: shared RGBA bytes, width, height, version.
pub type RenderFrame = Option<(Arc<Vec<u8>>, i32, i32, u64)>;

/// `None` = unknown handle. `(false, _)` = paused/exiting. `(true, frame)` = draw.
pub fn take_render_frame(handle: u64) -> Option<(bool /*draw*/, RenderFrame)> {
    with_registry(|reg| {
        let session = reg.sessions.get_mut(&handle)?;
        let paused_or_exit = session.paused || session.exit_requested;
        if !paused_or_exit {
            session.frames_rendered = session.frames_rendered.saturating_add(1);
        }
        if paused_or_exit {
            return Some((false, None));
        }
        let frame = session
            .static_frame
            .as_ref()
            .map(|f| (Arc::clone(&f.rgba), f.width, f.height, f.version));
        Some((true, frame))
    })
}

pub fn serialize_save(handle: u64) -> Result<Vec<u8>, String> {
    with_registry(|reg| {
        if reg.sessions.contains_key(&handle) {
            Err("save format not implemented yet".into())
        } else {
            Err("unknown session".into())
        }
    })
}

pub fn restore_save(handle: u64, _payload: &[u8]) -> bool {
    // Restore is not implemented yet; the session lookup is kept so the handle is still
    // validated against the registry, and the result is always `false`.
    with_registry(|reg| {
        let _known = reg.sessions.contains_key(&handle);
        false
    })
}

pub fn diagnostics_snapshot(handle: u64) -> (u64, f64, i32, u64, i32) {
    with_registry(|reg| {
        let Some(session) = reg.sessions.get(&handle) else {
            return (0, 0.0, 0, 0, 0);
        };
        let avg = if session.frames_rendered == 0 {
            0.0
        } else {
            session.total_frame_millis / session.frames_rendered as f64
        };
        (
            session.frames_rendered,
            avg,
            session.maps_parsed,
            session.events_executed,
            session.audio_streams_active,
        )
    })
}

pub fn last_error(handle: u64) -> Option<String> {
    with_registry(|reg| {
        reg.sessions
            .get(&handle)
            .and_then(|s| {
                if s.last_error.is_empty() {
                    None
                } else {
                    Some(s.last_error.clone())
                }
            })
    })
}

/// Session create/destroy round-trip used by native smoke.
pub fn smoke_test_registry() -> bool {
    with_registry(|reg| {
        let handle = reg.next_handle;
        reg.next_handle = reg.next_handle.wrapping_add(1).max(1);
        reg.sessions.insert(
            handle,
            Session {
                game_id: "smoke".into(),
                game_root: String::new(),
                paused: false,
                exit_requested: false,
                frames_rendered: 0,
                total_frame_millis: 0.0,
                maps_parsed: 0,
                events_executed: 0,
                audio_streams_active: 0,
                last_error: String::new(),
                actions_pressed: [false; ACTION_COUNT],
                analog_axes: [0.0; ACTION_COUNT],
                static_frame: None,
            },
        );
        let ok = reg.sessions.contains_key(&handle);
        reg.sessions.remove(&handle);
        ok
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn load_destroy_roundtrip() {
        let h = load_game("g", "/tmp/g");
        assert!(h != 0);
        set_paused(h, true);
        request_exit(h);
        destroy_session(h);
        assert!(last_error(h).is_none());
    }

    #[test]
    fn smoke_registry_ok() {
        assert!(smoke_test_registry());
    }
}
