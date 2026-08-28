//! Wolf session registry.

use std::collections::HashMap;
use std::sync::Mutex;

use once_cell::sync::Lazy;

const ACTION_COUNT: usize = 17;

struct FrameBlob {
    rgba: Vec<u8>,
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
    actions_pressed: Vec<bool>,
    analog_axes: Vec<f32>,
    static_frame: Option<FrameBlob>,
}

struct Registry {
    next_handle: u64,
    sessions: HashMap<u64, Session>,
}

static REGISTRY: Lazy<Mutex<Registry>> = Lazy::new(|| {
    Mutex::new(Registry {
        next_handle: 1,
        sessions: HashMap::new(),
    })
});

fn with_registry<T>(f: impl FnOnce(&mut Registry) -> T) -> T {
    let mut guard = REGISTRY.lock().expect("wolf registry poisoned");
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
                actions_pressed: vec![false; ACTION_COUNT],
                analog_axes: vec![0.0; ACTION_COUNT],
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

pub fn set_static_frame(handle: u64, rgba: &[u8], width: i32, height: i32) {
    if width <= 0 || height <= 0 {
        return;
    }
    let need = (width as usize).saturating_mul(height as usize).saturating_mul(4);
    if rgba.len() < need {
        return;
    }
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
            rgba: rgba[..need].to_vec(),
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
        for (i, value) in actions.iter().enumerate().take(session.actions_pressed.len()) {
            session.actions_pressed[i] = *value != 0;
        }
        for (i, value) in axes.iter().enumerate().take(session.analog_axes.len()) {
            session.analog_axes[i] = *value;
        }
    });
}

/// `None` = unknown handle. `(false, _)` = paused/exiting. `(true, frame)` = draw.
pub fn take_render_frame(
    handle: u64,
) -> Option<(bool /*draw*/, Option<(Vec<u8>, i32, i32, u64)>)> {
    with_registry(|reg| {
        let session = reg.sessions.get_mut(&handle)?;
        let paused_or_exit = session.paused || session.exit_requested;
        if !paused_or_exit {
            session.frames_rendered = session.frames_rendered.saturating_add(1);
        }
        if paused_or_exit {
            return Some((false, None));
        }
        let frame = session.static_frame.as_ref().map(|f| {
            (f.rgba.clone(), f.width, f.height, f.version)
        });
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
    with_registry(|reg| reg.sessions.contains_key(&handle) && false)
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
                actions_pressed: vec![false; ACTION_COUNT],
                analog_axes: vec![0.0; ACTION_COUNT],
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
