//! Pure-Rust decoding of `SDL_Event` buffers.
//!
//! Split out from the FFI in `sdl.rs` so it can be unit tested on any host. The
//! struct layouts below are the part of SDL integration that is easy to get
//! subtly wrong, and a wrong offset silently produces a valid-looking event with
//! garbage coordinates.

/// Size of the `SDL_Event` union buffer. SDL's header pins the union at 56 bytes
/// for ABI compatibility; 128 is comfortably above every member.
pub const SDL_EVENT_SIZE: usize = 128;

// Event type constants, from SDL_events.h.
pub const SDL_EVENT_QUIT: u32 = 0x100;
pub const SDL_EVENT_WINDOW_RESIZED: u32 = 0x101;
pub const SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED: u32 = 0x102;
pub const SDL_EVENT_KEY_DOWN: u32 = 0x300;
pub const SDL_EVENT_KEY_UP: u32 = 0x301;
pub const SDL_EVENT_MOUSE_MOTION: u32 = 0x400;
pub const SDL_EVENT_MOUSE_BUTTON_DOWN: u32 = 0x401;
pub const SDL_EVENT_MOUSE_BUTTON_UP: u32 = 0x402;
pub const SDL_EVENT_FINGER_DOWN: u32 = 0x700;
pub const SDL_EVENT_FINGER_UP: u32 = 0x701;
pub const SDL_EVENT_FINGER_MOTION: u32 = 0x702;
pub const SDL_EVENT_FINGER_CANCELED: u32 = 0x703;

/// `SDL_MouseButtonEvent`, field for field.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct SdlMouseButtonEvent {
    pub event_type: u32,
    pub reserved: u32,
    pub timestamp: u64,
    pub window_id: u32,
    pub which: u32,
    pub button: u8,
    pub down: bool,
    pub clicks: u8,
    pub padding: u8,
    pub x: f32,
    pub y: f32,
}

/// `SDL_MouseMotionEvent`, field for field.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct SdlMouseMotionEvent {
    pub event_type: u32,
    pub reserved: u32,
    pub timestamp: u64,
    pub window_id: u32,
    pub which: u32,
    pub state: u32,
    pub x: f32,
    pub y: f32,
    pub xrel: f32,
    pub yrel: f32,
}

/// `SDL_KeyboardEvent`, field for field. `scancode` is what the key table uses.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct SdlKeyboardEvent {
    pub event_type: u32,
    pub reserved: u32,
    pub timestamp: u64,
    pub window_id: u32,
    pub which: u32,
    pub scancode: i32,
    pub keycode: i32,
    pub mod_state: u16,
    pub raw: u16,
    pub down: bool,
    pub repeat: bool,
    pub padding: [u8; 2],
}

/// `SDL_TouchFingerEvent`.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct SdlTouchFingerEvent {
    pub event_type: u32,
    pub reserved: u32,
    pub timestamp: u64,
    pub touch_id: u64,
    pub finger_id: u64,
    pub x: f32,
    pub y: f32,
    pub dx: f32,
    pub dy: f32,
    pub pressure: f32,
    pub window_id: u32,
}

/// A decoded SDL event, in the terms the input layer understands.
#[derive(Debug, Clone, PartialEq)]
pub enum AppEvent {
    Quit,
    Resized { width: i32, height: i32 },
    Key { scancode: i32, down: bool, repeat: bool },
    /// Window-pixel coordinates, as SDL reports them.
    Motion { x: f32, y: f32 },
    Button { x: f32, y: f32, button: u8, down: bool },
    /// Normalised 0..1 coordinates, from a touch.
    Finger { x: f32, y: f32, down: bool, canceled: bool },
}

/// Decodes one raw `SDL_Event`.
pub fn decode_event(raw: &[u8]) -> Option<AppEvent> {
    if raw.len() < 4 {
        return None;
    }
    let event_type = u32::from_ne_bytes([raw[0], raw[1], raw[2], raw[3]]);
    match event_type {
        SDL_EVENT_QUIT => Some(AppEvent::Quit),
        SDL_EVENT_WINDOW_RESIZED | SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED => {
            // data1/data2 sit at offsets 16 and 20 in SDL_WindowEvent.
            if raw.len() < 24 {
                return None;
            }
            let width = i32::from_ne_bytes([raw[16], raw[17], raw[18], raw[19]]);
            let height = i32::from_ne_bytes([raw[20], raw[21], raw[22], raw[23]]);
            Some(AppEvent::Resized { width, height })
        }
        SDL_EVENT_KEY_DOWN | SDL_EVENT_KEY_UP => {
            let event = read_struct::<SdlKeyboardEvent>(raw)?;
            Some(AppEvent::Key {
                scancode: event.scancode,
                down: event.down,
                repeat: event.repeat,
            })
        }
        SDL_EVENT_MOUSE_MOTION => {
            let event = read_struct::<SdlMouseMotionEvent>(raw)?;
            Some(AppEvent::Motion {
                x: event.x,
                y: event.y,
            })
        }
        SDL_EVENT_MOUSE_BUTTON_DOWN | SDL_EVENT_MOUSE_BUTTON_UP => {
            let event = read_struct::<SdlMouseButtonEvent>(raw)?;
            Some(AppEvent::Button {
                x: event.x,
                y: event.y,
                button: event.button,
                down: event.down,
            })
        }
        SDL_EVENT_FINGER_DOWN | SDL_EVENT_FINGER_UP | SDL_EVENT_FINGER_MOTION
        | SDL_EVENT_FINGER_CANCELED => {
            let event = read_struct::<SdlTouchFingerEvent>(raw)?;
            Some(AppEvent::Finger {
                x: event.x,
                y: event.y,
                down: event_type == SDL_EVENT_FINGER_DOWN
                    || event_type == SDL_EVENT_FINGER_MOTION,
                canceled: event_type == SDL_EVENT_FINGER_CANCELED,
            })
        }
        _ => None,
    }
}

/// Reinterprets `raw` as `T`, refusing when it is too short.
fn read_struct<T: Copy>(raw: &[u8]) -> Option<T> {
    if raw.len() < std::mem::size_of::<T>() {
        return None;
    }
    // SAFETY: T is a plain `repr(C)` struct of scalars, and the length was just
    // checked. `read_unaligned` avoids assuming the byte buffer is aligned.
    Some(unsafe { std::ptr::read_unaligned(raw.as_ptr().cast::<T>()) })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a raw event buffer with the given type and body bytes.
    fn raw_event(event_type: u32, body: &[u8]) -> Vec<u8> {
        let mut v = vec![0u8; SDL_EVENT_SIZE];
        v[0..4].copy_from_slice(&event_type.to_ne_bytes());
        v[4..4 + body.len()].copy_from_slice(body);
        v
    }

    #[test]
    fn decodes_a_quit_event() {
        let raw = raw_event(SDL_EVENT_QUIT, &[]);
        assert_eq!(decode_event(&raw), Some(AppEvent::Quit));
    }

    #[test]
    fn decodes_a_resize_event() {
        let mut raw = raw_event(SDL_EVENT_WINDOW_RESIZED, &[]);
        raw[16..20].copy_from_slice(&1080i32.to_ne_bytes());
        raw[20..24].copy_from_slice(&1920i32.to_ne_bytes());
        assert_eq!(
            decode_event(&raw),
            Some(AppEvent::Resized {
                width: 1080,
                height: 1920
            })
        );
    }

    #[test]
    fn decodes_a_key_event() {
        let mut raw = raw_event(SDL_EVENT_KEY_DOWN, &[]);
        // scancode at offset 24, down flag at offset 36 in SdlKeyboardEvent.
        raw[24..28].copy_from_slice(&82i32.to_ne_bytes());
        raw[36] = 1;
        match decode_event(&raw) {
            Some(AppEvent::Key {
                scancode,
                down,
                repeat,
            }) => {
                assert_eq!(scancode, 82);
                assert!(down);
                assert!(!repeat);
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn decodes_a_mouse_motion_event() {
        let mut raw = raw_event(SDL_EVENT_MOUSE_MOTION, &[]);
        // x and y sit at offsets 28 and 32 in SdlMouseMotionEvent.
        raw[28..32].copy_from_slice(&640.0f32.to_ne_bytes());
        raw[32..36].copy_from_slice(&480.0f32.to_ne_bytes());
        match decode_event(&raw) {
            Some(AppEvent::Motion { x, y }) => {
                assert!((x - 640.0).abs() < 1e-3);
                assert!((y - 480.0).abs() < 1e-3);
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn decodes_a_mouse_button_event() {
        let mut raw = raw_event(SDL_EVENT_MOUSE_BUTTON_DOWN, &[]);
        // button at offset 24, down at 25, x at 28, y at 32.
        raw[24] = 1;
        raw[25] = 1;
        raw[28..32].copy_from_slice(&700.0f32.to_ne_bytes());
        raw[32..36].copy_from_slice(&400.0f32.to_ne_bytes());
        match decode_event(&raw) {
            Some(AppEvent::Button {
                x,
                y,
                button,
                down,
            }) => {
                assert!((x - 700.0).abs() < 1e-3);
                assert!((y - 400.0).abs() < 1e-3);
                assert_eq!(button, 1);
                assert!(down);
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn decodes_a_finger_event() {
        let mut raw = raw_event(SDL_EVENT_FINGER_DOWN, &[]);
        // x and y sit at offsets 32 and 36 in SdlTouchFingerEvent.
        raw[32..36].copy_from_slice(&0.5f32.to_ne_bytes());
        raw[36..40].copy_from_slice(&0.25f32.to_ne_bytes());
        match decode_event(&raw) {
            Some(AppEvent::Finger {
                x,
                y,
                down,
                canceled,
            }) => {
                assert!((x - 0.5).abs() < 1e-3);
                assert!((y - 0.25).abs() < 1e-3);
                assert!(down);
                assert!(!canceled);
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn a_finger_up_is_not_down() {
        let raw = raw_event(SDL_EVENT_FINGER_UP, &[]);
        match decode_event(&raw) {
            Some(AppEvent::Finger { down, .. }) => assert!(!down),
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn ignores_unknown_event_types() {
        let raw = raw_event(0xDEAD, &[]);
        assert_eq!(decode_event(&raw), None);
    }

    #[test]
    fn refuses_a_truncated_buffer() {
        assert_eq!(decode_event(&[0u8, 0]), None);
    }

    #[test]
    fn the_event_buffer_is_large_enough_for_the_union() {
        assert!(SDL_EVENT_SIZE >= 56, "SDL pins the union at 56 bytes");
        assert!(SDL_EVENT_SIZE >= std::mem::size_of::<SdlTouchFingerEvent>());
        assert!(SDL_EVENT_SIZE >= std::mem::size_of::<SdlKeyboardEvent>());
        assert!(SDL_EVENT_SIZE >= std::mem::size_of::<SdlMouseButtonEvent>());
    }

    #[test]
    fn the_struct_offsets_match_the_field_order() {
        // These are the offsets the decoder relies on; if a field is added in the
        // wrong place the decoder would read the wrong bytes.
        assert_eq!(std::mem::offset_of!(SdlKeyboardEvent, scancode), 24);
        assert_eq!(std::mem::offset_of!(SdlKeyboardEvent, mod_state), 32);
        assert_eq!(std::mem::offset_of!(SdlKeyboardEvent, raw), 34);
        assert_eq!(std::mem::offset_of!(SdlKeyboardEvent, down), 36);
        assert_eq!(std::mem::offset_of!(SdlMouseButtonEvent, button), 24);
        assert_eq!(std::mem::offset_of!(SdlMouseButtonEvent, down), 25);
        assert_eq!(std::mem::offset_of!(SdlMouseButtonEvent, clicks), 26);
        assert_eq!(std::mem::offset_of!(SdlMouseButtonEvent, x), 28);
        assert_eq!(std::mem::offset_of!(SdlMouseMotionEvent, x), 28);
        assert_eq!(std::mem::offset_of!(SdlTouchFingerEvent, x), 32);
        assert_eq!(std::mem::offset_of!(SdlTouchFingerEvent, y), 36);
    }
}
