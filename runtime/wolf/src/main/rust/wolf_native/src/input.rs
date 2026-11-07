//! Input state owned by the Rust layer.
//!
//! This is the structural fix for the bug that has blocked the title menu: touch
//! never reached the game because the Android view hierarchy and the GL surface
//! were separate layers, each seeing only part of the input. Here one path reads
//! the SDL event queue and writes this state, and the interpreter reads only this
//! state.
//!
//! Coordinates are stored in **game (virtual) pixels**, not window pixels. The
//! window is letterboxed, so the conversion happens once, at the edge, in
//! [`Viewport::to_game`]. Doing it anywhere else means every consumer has to
//! repeat the same arithmetic and one of them eventually gets it wrong.

/// WOLF mouse-button codes, from `sub_A0B700`: bits 0x10 / 0x20 / 0x40 of the
/// input word become codes 20 / 21 / 22.
pub const MOUSE_LEFT: i32 = 20;
pub const MOUSE_RIGHT: i32 = 21;
pub const MOUSE_MIDDLE: i32 = 22;

/// WOLF keyboard codes as the interpreter consumes them.
pub const KEY_UP: i32 = 8;
pub const KEY_DOWN: i32 = 2;
pub const KEY_LEFT: i32 = 4;
pub const KEY_RIGHT: i32 = 6;
pub const KEY_SHIFT: i32 = 7;
pub const KEY_OK: i32 = 10;
pub const KEY_CANCEL: i32 = 11;
pub const KEY_MENU: i32 = 17;

/// Letterbox mapping between window pixels and game pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Viewport {
    pub game_width: i32,
    pub game_height: i32,
    pub window_width: i32,
    pub window_height: i32,
}

impl Viewport {
    pub fn new(game_width: i32, game_height: i32) -> Self {
        Self {
            game_width: game_width.max(1),
            game_height: game_height.max(1),
            window_width: 0,
            window_height: 0,
        }
    }

    /// Updates the window size. Returns true when it actually changed.
    pub fn resize(&mut self, width: i32, height: i32) -> bool {
        let width = width.max(0);
        let height = height.max(0);
        if self.window_width == width && self.window_height == height {
            return false;
        }
        self.window_width = width;
        self.window_height = height;
        true
    }

    /// Uniform scale factor that fits the game inside the window.
    pub fn scale(&self) -> f32 {
        if self.window_width <= 0 || self.window_height <= 0 {
            return 1.0;
        }
        let sx = self.window_width as f32 / self.game_width as f32;
        let sy = self.window_height as f32 / self.game_height as f32;
        sx.min(sy)
    }

    /// Letterbox bars, in window pixels.
    pub fn offset(&self) -> (f32, f32) {
        let scale = self.scale();
        let used_w = self.game_width as f32 * scale;
        let used_h = self.game_height as f32 * scale;
        (
            (self.window_width as f32 - used_w) / 2.0,
            (self.window_height as f32 - used_h) / 2.0,
        )
    }

    /// Converts a window-pixel point to game pixels.
    ///
    /// Points inside the letterbox bars map outside the game rectangle; that is
    /// deliberate, because WOLF's own hit tests reject them and clamping would
    /// silently turn a tap on the bar into a tap on the nearest menu entry.
    pub fn to_game(&self, x: f32, y: f32) -> (i32, i32) {
        let scale = self.scale();
        if scale <= 0.0 {
            return (0, 0);
        }
        let (off_x, off_y) = self.offset();
        (
            ((x - off_x) / scale) as i32,
            ((y - off_y) / scale) as i32,
        )
    }

    /// Converts a game-pixel point to window pixels. Used to place synthetic
    /// input in tests and to report the cursor to the interpreter.
    pub fn to_window(&self, x: i32, y: i32) -> (f32, f32) {
        let scale = self.scale();
        let (off_x, off_y) = self.offset();
        (off_x + x as f32 * scale, off_y + y as f32 * scale)
    }

    /// True when a game-space point lies inside the game rectangle.
    pub fn contains_game(&self, x: i32, y: i32) -> bool {
        x >= 0 && y >= 0 && x < self.game_width && y < self.game_height
    }
}

/// Input state as the interpreter sees it.
#[derive(Debug, Clone)]
pub struct InputState {
    viewport: Viewport,
    /// Cursor in game pixels; `None` until the first motion or press.
    cursor: Option<(i32, i32)>,
    left_down: bool,
    right_down: bool,
    middle_down: bool,
    /// Keys currently held, as WOLF codes.
    held_keys: Vec<i32>,
    /// One-shot key latch: survives the press being released so a script that
    /// polls only on some frames still sees it. Cleared by `consume_key`.
    latched_key: i32,
    /// Frames the latch remains valid, mirroring the Kotlin TTL.
    latch_ttl: i32,
    /// Total presses, for tests and diagnostics.
    press_edges: u64,
}

/// How long a latched key survives without being consumed, in frames.
const LATCH_FRAMES: i32 = 120;

impl InputState {
    pub fn new(game_width: i32, game_height: i32) -> Self {
        Self {
            viewport: Viewport::new(game_width, game_height),
            cursor: None,
            left_down: false,
            right_down: false,
            middle_down: false,
            held_keys: Vec::new(),
            latched_key: 0,
            latch_ttl: 0,
            press_edges: 0,
        }
    }

    pub fn viewport(&self) -> &Viewport {
        &self.viewport
    }

    pub fn viewport_mut(&mut self) -> &mut Viewport {
        &mut self.viewport
    }

    pub fn resize_window(&mut self, width: i32, height: i32) -> bool {
        self.viewport.resize(width, height)
    }

    /// Records a pointer position in **window** pixels.
    pub fn pointer_motion(&mut self, window_x: f32, window_y: f32) {
        let (gx, gy) = self.viewport.to_game(window_x, window_y);
        self.cursor = Some((gx, gy));
    }

    /// Records a pointer position already in game pixels.
    pub fn pointer_motion_game(&mut self, game_x: i32, game_y: i32) {
        self.cursor = Some((game_x, game_y));
    }

    /// Records a button transition in window pixels. `button` is 1/2/3 as SDL
    /// reports it.
    pub fn pointer_button(&mut self, window_x: f32, window_y: f32, button: u8, down: bool) {
        self.pointer_motion(window_x, window_y);
        self.set_button(button, down);
    }

    /// Records a button transition with the cursor already known.
    pub fn set_button(&mut self, button: u8, down: bool) {
        match button {
            1 => self.left_down = down,
            2 => self.middle_down = down,
            3 => self.right_down = down,
            _ => return,
        }
        if down {
            self.press_edges += 1;
            // A click also latches the mouse code, so a script that polls for a
            // press on a later frame still sees it. This is what makes the title
            // menu respond to a tap shorter than one frame.
            if let Some(code) = mouse_code_for(self.left_down, self.right_down, self.middle_down) {
                self.latch(code);
            }
        }
    }

    pub fn cursor(&self) -> Option<(i32, i32)> {
        self.cursor
    }

    pub fn is_down(&self, button: u8) -> bool {
        match button {
            1 => self.left_down,
            3 => self.right_down,
            2 => self.middle_down,
            _ => false,
        }
    }

    /// Presses a key by WOLF code.
    pub fn key_down(&mut self, wolf_key: i32) {
        if wolf_key == 0 {
            return;
        }
        if !self.held_keys.contains(&wolf_key) {
            self.held_keys.push(wolf_key);
        }
        self.latch(wolf_key);
        self.press_edges += 1;
    }

    pub fn key_up(&mut self, wolf_key: i32) {
        self.held_keys.retain(|k| *k != wolf_key);
    }

    fn latch(&mut self, wolf_key: i32) {
        if wolf_key != 0 {
            self.latched_key = wolf_key;
            self.latch_ttl = LATCH_FRAMES;
        }
    }

    /// The code the interpreter should observe for a key poll.
    ///
    /// Mouse buttons take precedence while held: WOLF's picture menus compare the
    /// polled code against 20/21/22 (`sub_A0B700`), and a keyboard code can never
    /// satisfy them. The latch is left intact so a keyboard press is still
    /// delivered once the pointer is released.
    pub fn poll_key(&self) -> i32 {
        if let Some(code) = mouse_code_for(self.left_down, self.right_down, self.middle_down) {
            return code;
        }
        if self.latched_key != 0 {
            return self.latched_key;
        }
        self.held_keys.first().copied().unwrap_or(0)
    }

    /// Clears the one-shot latch after a waiting InputKey accepted it.
    pub fn consume_key(&mut self) {
        self.latched_key = 0;
        self.latch_ttl = 0;
    }

    /// Advances the latch TTL by one frame.
    pub fn tick(&mut self) {
        if self.latch_ttl > 0 {
            self.latch_ttl -= 1;
            if self.latch_ttl == 0 {
                self.latched_key = 0;
            }
        }
    }

    pub fn press_edges(&self) -> u64 {
        self.press_edges
    }

    /// Drops every held button and key, for a focus loss.
    pub fn clear(&mut self) {
        self.left_down = false;
        self.right_down = false;
        self.middle_down = false;
        self.held_keys.clear();
        self.latched_key = 0;
        self.latch_ttl = 0;
    }
}

/// The mouse code for the currently held buttons, or `None` when none is held.
///
/// Left wins over right over middle, matching the Kotlin precedence.
pub fn mouse_code_for(left: bool, right: bool, middle: bool) -> Option<i32> {
    if left {
        Some(MOUSE_LEFT)
    } else if right {
        Some(MOUSE_RIGHT)
    } else if middle {
        Some(MOUSE_MIDDLE)
    } else {
        None
    }
}

/// Maps an SDL scancode to a WOLF key code.
///
/// Returns 0 for keys the game does not use, which callers must ignore rather
/// than latch.
pub fn wolf_key_for_scancode(scancode: i32) -> i32 {
    // SDL_SCANCODE_* values, from SDL_scancode.h.
    const SDL_SCANCODE_UP: i32 = 82;
    const SDL_SCANCODE_DOWN: i32 = 81;
    const SDL_SCANCODE_LEFT: i32 = 80;
    const SDL_SCANCODE_RIGHT: i32 = 79;
    const SDL_SCANCODE_RETURN: i32 = 40;
    const SDL_SCANCODE_KP_ENTER: i32 = 88;
    const SDL_SCANCODE_SPACE: i32 = 44;
    const SDL_SCANCODE_Z: i32 = 29;
    const SDL_SCANCODE_ESCAPE: i32 = 41;
    const SDL_SCANCODE_X: i32 = 27;
    const SDL_SCANCODE_LSHIFT: i32 = 225;
    const SDL_SCANCODE_RSHIFT: i32 = 229;
    const SDL_SCANCODE_TAB: i32 = 43;

    match scancode {
        SDL_SCANCODE_UP => KEY_UP,
        SDL_SCANCODE_DOWN => KEY_DOWN,
        SDL_SCANCODE_LEFT => KEY_LEFT,
        SDL_SCANCODE_RIGHT => KEY_RIGHT,
        SDL_SCANCODE_RETURN | SDL_SCANCODE_KP_ENTER | SDL_SCANCODE_SPACE | SDL_SCANCODE_Z => {
            KEY_OK
        }
        SDL_SCANCODE_ESCAPE | SDL_SCANCODE_X => KEY_CANCEL,
        SDL_SCANCODE_LSHIFT | SDL_SCANCODE_RSHIFT => KEY_SHIFT,
        SDL_SCANCODE_TAB => KEY_MENU,
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input() -> InputState {
        let mut i = InputState::new(1280, 960);
        // A 16:9 window against a 4:3 game leaves horizontal bars.
        i.resize_window(1920, 1080);
        i
    }

    #[test]
    fn a_1280x960_game_in_a_1920x1080_window_scales_by_1_125() {
        let i = input();
        // min(1920/1280, 1080/960) = min(1.5, 1.125) = 1.125
        assert!((i.viewport().scale() - 1.125).abs() < 1e-6);
    }

    #[test]
    fn letterbox_bars_are_centred_horizontally() {
        let i = input();
        let (off_x, off_y) = i.viewport().offset();
        // 1280 * 1.125 = 1440 used, so (1920 - 1440) / 2 = 240.
        assert!((off_x - 240.0).abs() < 1e-3, "offset x was {off_x}");
        assert!(off_y.abs() < 1e-3, "offset y was {off_y}");
    }

    #[test]
    fn the_game_origin_maps_to_the_top_left_of_the_content_box() {
        let i = input();
        let (wx, wy) = i.viewport().to_window(0, 0);
        assert!((wx - 240.0).abs() < 1e-3);
        assert!(wy.abs() < 1e-3);
    }

    #[test]
    fn a_window_point_inside_the_content_maps_back_to_the_same_game_point() {
        let i = input();
        for (gx, gy) in [(0, 0), (157, 379), (640, 480), (1279, 959)] {
            let (wx, wy) = i.viewport().to_window(gx, gy);
            let (rx, ry) = i.viewport().to_game(wx, wy);
            assert_eq!((rx, ry), (gx, gy), "round trip for {gx},{gy}");
        }
    }

    #[test]
    fn a_tap_on_the_letterbox_bar_maps_outside_the_game() {
        let i = input();
        // x = 100 is inside the left bar (bar spans 0..240).
        let (gx, _) = i.viewport().to_game(100.0, 540.0);
        assert!(gx < 0, "expected a negative game x, got {gx}");
        assert!(!i.viewport().contains_game(gx, 0));
    }

    #[test]
    fn the_title_menu_second_entry_maps_from_its_device_rectangle() {
        // The recorded title-menu hit rects: slot 22 is "continue" at
        // left=157, top=379, 195x52 in game pixels.
        let i = input();
        let (wx, wy) = i.viewport().to_window(157 + 195 / 2, 379 + 52 / 2);
        let (gx, gy) = i.viewport().to_game(wx, wy);
        assert!((157..157 + 195).contains(&gx), "x {gx} not in the slot");
        assert!((379..379 + 52).contains(&gy), "y {gy} not in the slot");
    }

    #[test]
    fn a_left_press_reports_wolf_mouse_code_20() {
        let mut i = input();
        i.pointer_button(700.0, 400.0, 1, true);
        assert_eq!(i.poll_key(), MOUSE_LEFT);
        assert_eq!(i.poll_key(), 20);
    }

    #[test]
    fn right_and_middle_presses_report_21_and_22() {
        let mut i = input();
        i.set_button(3, true);
        assert_eq!(i.poll_key(), MOUSE_RIGHT);
        i.set_button(3, false);
        i.set_button(2, true);
        assert_eq!(i.poll_key(), MOUSE_MIDDLE);
    }

    #[test]
    fn mouse_buttons_take_precedence_over_a_held_key() {
        let mut i = input();
        i.key_down(KEY_OK);
        i.set_button(1, true);
        assert_eq!(i.poll_key(), MOUSE_LEFT);
        i.set_button(1, false);
        // The click's latch outranks the held key until it is consumed or
        // expires; then the key is delivered. This ordering matters because a
        // picture menu polls for the click on a later frame than it arrived.
        assert_eq!(i.poll_key(), MOUSE_LEFT);
        i.consume_key();
        assert_eq!(i.poll_key(), KEY_OK, "the key is still delivered");
    }

    #[test]
    fn a_released_click_still_latches_once() {
        let mut i = input();
        i.set_button(1, true);
        i.set_button(1, false);
        assert_eq!(i.poll_key(), MOUSE_LEFT, "the latch survives the release");
        i.consume_key();
        assert_eq!(i.poll_key(), 0);
    }

    #[test]
    fn the_latch_expires_after_the_documented_frame_count() {
        let mut i = input();
        i.set_button(1, true);
        i.set_button(1, false);
        for _ in 0..LATCH_FRAMES {
            i.tick();
        }
        assert_eq!(i.poll_key(), 0, "the latch must expire");
    }

    #[test]
    fn a_cursor_is_absent_until_the_first_pointer_event() {
        let i = input();
        assert_eq!(i.cursor(), None, "no cursor before any event");
    }

    #[test]
    fn the_cursor_is_reported_in_game_pixels() {
        let mut i = input();
        i.pointer_motion(240.0 + 640.0 * 1.125, 480.0 * 1.125);
        assert_eq!(i.cursor(), Some((640, 480)));
    }

    #[test]
    fn scancodes_map_to_the_wolf_key_table() {
        assert_eq!(wolf_key_for_scancode(82), KEY_UP);
        assert_eq!(wolf_key_for_scancode(81), KEY_DOWN);
        assert_eq!(wolf_key_for_scancode(80), KEY_LEFT);
        assert_eq!(wolf_key_for_scancode(79), KEY_RIGHT);
        assert_eq!(wolf_key_for_scancode(40), KEY_OK);
        assert_eq!(wolf_key_for_scancode(41), KEY_CANCEL);
        assert_eq!(wolf_key_for_scancode(225), KEY_SHIFT);
        assert_eq!(wolf_key_for_scancode(43), KEY_MENU);
    }

    #[test]
    fn unused_scancodes_map_to_zero() {
        assert_eq!(wolf_key_for_scancode(4), 0);
        assert_eq!(wolf_key_for_scancode(999), 0);
    }

    #[test]
    fn a_zero_key_is_never_latched() {
        let mut i = input();
        i.key_down(0);
        assert_eq!(i.poll_key(), 0);
        assert_eq!(i.press_edges(), 0);
    }

    #[test]
    fn clearing_drops_buttons_keys_and_the_latch() {
        let mut i = input();
        i.pointer_button(700.0, 400.0, 1, true);
        i.key_down(KEY_OK);
        i.clear();
        assert_eq!(i.poll_key(), 0);
        // The cursor position is kept: a focus loss must not move the pointer,
        // or a picture menu would highlight a different entry on return.
        assert!(i.cursor().is_some(), "the cursor position is kept");
    }
}
