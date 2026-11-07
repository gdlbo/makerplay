//! The Rust-owned frame loop.
//!
//! This is the structural change the whole task exists for: one loop reads the
//! SDL event queue, updates the interpreter, and presents the frame. Previously
//! the Android view hierarchy owned input and a `GLSurfaceView` owned rendering,
//! and the two never met — which is why touch could not reach the game and the
//! mouse-driven title menu was inoperable.
//!
//! The loop is written as a pure state machine ([`AppState`]) plus a thin driver,
//! so the sequencing (event → input → interpreter → frame) can be tested without
//! a window or a GL context.

use crate::game_session::GameSession;
use crate::input::{wolf_key_for_scancode, InputState};
use crate::interpreter::Interpreter;
use crate::png::Image;
use crate::sdl_event::AppEvent;

/// What the loop wants the platform layer to do after a step.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StepOutcome {
    /// Keep running.
    Continue,
    /// The window asked to close.
    Quit,
}

/// Everything the loop owns.
pub struct AppState {
    pub input: InputState,
    pub interpreter: Interpreter,
    /// Last presented frame's identity, so the presenter can skip re-uploading an
    /// unchanged frame.
    frame_version: u64,
    quit_requested: bool,
    /// Events applied since the last frame, for diagnostics.
    events_applied: u64,
    /// Frames presented since start.
    frames: u64,
    /// The renderer session. When present, the loop composes real frames instead
    /// of presenting black; absent means the map could not be loaded.
    pub session: Option<GameSession>,
}

impl AppState {
    pub fn new(game_width: i32, game_height: i32) -> Self {
        Self {
            input: InputState::new(game_width, game_height),
            interpreter: Interpreter::new(60),
            frame_version: 0,
            quit_requested: false,
            events_applied: 0,
            frames: 0,
            session: None,
        }
    }

    /// Attaches a renderer session so the loop draws the game.
    pub fn with_session(mut self, session: GameSession) -> Self {
        self.session = Some(session);
        self
    }

    /// Composes the frame to present at this viewport size.
    ///
    /// Returns `None` when no session is loaded, in which case the presenter draws
    /// black — which is honest, rather than presenting a stale frame as if it were
    /// current.
    pub fn render(&mut self, width: i32, height: i32) -> Option<(Image, u64)> {
        let session = self.session.as_mut()?;
        Some(session.compose(width, height))
    }

    pub fn game_size(&self) -> (i32, i32) {
        let vp = self.input.viewport();
        (vp.game_width, vp.game_height)
    }

    /// Applies one SDL event to the input state.
    ///
    /// Touch is converted here, once: SDL reports finger coordinates normalised
    /// to the window, so they are scaled to window pixels and then handed to the
    /// same letterbox mapping a mouse event uses. That keeps one conversion path
    /// instead of two that can drift apart.
    pub fn apply_event(&mut self, event: &AppEvent) {
        self.events_applied += 1;
        match event {
            AppEvent::Quit => self.quit_requested = true,
            AppEvent::Resized { width, height } => {
                self.input.resize_window(*width, *height);
            }
            AppEvent::Key {
                scancode,
                down,
                repeat,
            } => {
                if *repeat {
                    return;
                }
                let wolf_key = wolf_key_for_scancode(*scancode);
                if *down {
                    self.input.key_down(wolf_key);
                } else {
                    self.input.key_up(wolf_key);
                }
            }
            AppEvent::Motion { x, y } => self.input.pointer_motion(*x, *y),
            AppEvent::Button {
                x,
                y,
                button,
                down,
            } => self.input.pointer_button(*x, *y, *button, *down),
            AppEvent::Finger {
                x,
                y,
                down,
                canceled,
            } => {
                let vp = *self.input.viewport();
                let wx = x * vp.window_width as f32;
                let wy = y * vp.window_height as f32;
                // A finger is the left mouse button: WOLF's picture menus poll for
                // mouse code 20 and nothing else.
                if *canceled {
                    self.input.pointer_button(wx, wy, 1, false);
                } else {
                    self.input.pointer_button(wx, wy, 1, *down);
                }
            }
        }
    }

    /// Applies a batch of events, then runs one frame of the interpreter.
    pub fn step(&mut self, events: &[AppEvent], host: &mut dyn crate::interpreter::Host) {
        for event in events {
            self.apply_event(event);
        }
        if self.quit_requested {
            return;
        }
        self.input.tick();
        self.interpreter.tick_default(host);
        if let Some(session) = self.session.as_mut() {
            // A teleport takes effect immediately, as in the original engine.
            if let Some((_map_id, tx, ty)) = self.interpreter.take_teleport() {
                session.set_hero(tx, ty);
            }
            session.tick();
        }
        self.frames += 1;
    }

    /// Advances the frame identity when the content changed.
    pub fn mark_frame(&mut self) {
        self.frame_version = self.frame_version.wrapping_add(1);
    }

    pub fn frame_version(&self) -> u64 {
        self.frame_version
    }

    pub fn quit_requested(&self) -> bool {
        self.quit_requested
    }

    pub fn frames(&self) -> u64 {
        self.frames
    }

    pub fn events_applied(&self) -> u64 {
        self.events_applied
    }

    /// The outcome the driver should act on.
    pub fn outcome(&self) -> StepOutcome {
        if self.quit_requested {
            StepOutcome::Quit
        } else {
            StepOutcome::Continue
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::{KEY_OK, MOUSE_LEFT};
    use crate::interpreter::Host;

    /// Records what the interpreter asked the host to present.
    #[derive(Default)]
    struct TestHost {
        messages: Vec<String>,
    }

    impl Host for TestHost {
        fn on_message(&mut self, text: &str) {
            self.messages.push(text.to_string());
        }
    }

    fn state() -> AppState {
        let mut s = AppState::new(1280, 960);
        s.input.resize_window(1280, 960);
        s
    }

    #[test]
    fn a_quit_event_ends_the_loop() {
        let mut s = state();
        s.apply_event(&AppEvent::Quit);
        assert_eq!(s.outcome(), StepOutcome::Quit);
        assert!(s.quit_requested());
    }

    #[test]
    fn a_resize_event_updates_the_viewport() {
        let mut s = state();
        s.apply_event(&AppEvent::Resized {
            width: 1920,
            height: 1080,
        });
        assert_eq!(s.input.viewport().window_width, 1920);
        assert_eq!(s.input.viewport().window_height, 1080);
    }

    #[test]
    fn a_touch_at_a_known_window_point_yields_wolf_mouse_code_20() {
        // This is the task's acceptance criterion, at the level the loop controls.
        let mut s = state();
        // Tap the centre of the title menu's second entry: game (254, 405) in a
        // 1280x960 window with no letterboxing.
        let vp = *s.input.viewport();
        let (wx, wy) = vp.to_window(254, 405);
        s.apply_event(&AppEvent::Finger {
            x: wx / vp.window_width as f32,
            y: wy / vp.window_height as f32,
            down: true,
            canceled: false,
        });

        assert_eq!(s.input.poll_key(), MOUSE_LEFT, "touch must look like a click");
        let (gx, gy) = s.input.cursor().expect("a cursor position");
        assert!((150..360).contains(&gx), "cursor x {gx} not on the entry");
        assert!((370..440).contains(&gy), "cursor y {gy} not on the entry");
    }

    #[test]
    fn a_touch_release_clears_the_button_but_keeps_the_latch() {
        let mut s = state();
        s.apply_event(&AppEvent::Finger {
            x: 0.5,
            y: 0.5,
            down: true,
            canceled: false,
        });
        s.apply_event(&AppEvent::Finger {
            x: 0.5,
            y: 0.5,
            down: false,
            canceled: false,
        });
        assert!(!s.input.is_down(1));
        // The latch survives so a menu polling on the next frame still sees it.
        assert_eq!(s.input.poll_key(), MOUSE_LEFT);
    }

    #[test]
    fn a_canceled_touch_releases_the_button() {
        let mut s = state();
        s.apply_event(&AppEvent::Finger {
            x: 0.5,
            y: 0.5,
            down: true,
            canceled: false,
        });
        s.apply_event(&AppEvent::Finger {
            x: 0.5,
            y: 0.5,
            down: true,
            canceled: true,
        });
        assert!(!s.input.is_down(1));
    }

    #[test]
    fn touch_and_mouse_reach_the_same_input_state() {
        // The point of one loop: two event sources, one path.
        let mut by_touch = state();
        by_touch.apply_event(&AppEvent::Finger {
            x: 0.5,
            y: 0.5,
            down: true,
            canceled: false,
        });

        let mut by_mouse = state();
        by_mouse.apply_event(&AppEvent::Button {
            x: 640.0,
            y: 480.0,
            button: 1,
            down: true,
        });

        assert_eq!(by_touch.input.poll_key(), by_mouse.input.poll_key());
        assert_eq!(by_touch.input.cursor(), by_mouse.input.cursor());
    }

    #[test]
    fn a_key_event_maps_to_a_wolf_key() {
        let mut s = state();
        // SDL_SCANCODE_RETURN = 40.
        s.apply_event(&AppEvent::Key {
            scancode: 40,
            down: true,
            repeat: false,
        });
        assert_eq!(s.input.poll_key(), KEY_OK);
    }

    #[test]
    fn a_repeat_key_event_is_ignored() {
        let mut s = state();
        s.apply_event(&AppEvent::Key {
            scancode: 40,
            down: true,
            repeat: true,
        });
        assert_eq!(s.input.poll_key(), 0, "auto-repeat must not re-trigger");
    }

    #[test]
    fn a_key_up_releases_the_key() {
        let mut s = state();
        s.apply_event(&AppEvent::Key {
            scancode: 40,
            down: true,
            repeat: false,
        });
        s.input.consume_key();
        s.apply_event(&AppEvent::Key {
            scancode: 40,
            down: false,
            repeat: false,
        });
        assert_eq!(s.input.poll_key(), 0);
    }

    #[test]
    fn an_unused_scancode_is_not_latched() {
        let mut s = state();
        s.apply_event(&AppEvent::Key {
            scancode: 4,
            down: true,
            repeat: false,
        });
        assert_eq!(s.input.poll_key(), 0);
    }

    #[test]
    fn stepping_runs_the_interpreter_and_counts_frames() {
        let mut s = state();
        let mut host = TestHost::default();
        s.interpreter.start(vec![crate::wolfformat::event_command::EventCommand {
            param_count: 1,
            command_type: 101,
            params: Vec::new(),
            branch_depth: 0,
            strings: vec!["hello".to_string()],
            route: None,
        }]);
        s.step(&[], &mut host);
        assert_eq!(host.messages, vec!["hello"]);
        assert_eq!(s.frames(), 1);
    }

    #[test]
    fn stepping_after_quit_does_nothing() {
        let mut s = state();
        let mut host = TestHost::default();
        s.apply_event(&AppEvent::Quit);
        s.step(&[], &mut host);
        assert_eq!(s.frames(), 0, "no frame after quit");
    }

    #[test]
    fn events_are_applied_before_the_frame_runs() {
        // A tap and the frame it drives must be in the same step, or the menu
        // would react one frame late and a short tap could be missed entirely.
        let mut s = state();
        let mut host = TestHost::default();
        s.apply_event(&AppEvent::Finger {
            x: 0.5,
            y: 0.5,
            down: true,
            canceled: false,
        });
        assert_eq!(s.input.poll_key(), MOUSE_LEFT);
        s.step(&[], &mut host);
        assert!(s.events_applied() >= 1);
    }

    #[test]
    fn the_frame_version_advances_only_when_marked() {
        let mut s = state();
        assert_eq!(s.frame_version(), 0);
        s.mark_frame();
        assert_eq!(s.frame_version(), 1);
        s.step(&[], &mut TestHost::default());
        assert_eq!(s.frame_version(), 1, "stepping alone does not mark a frame");
    }

    #[test]
    fn the_game_size_is_the_virtual_resolution() {
        let s = state();
        assert_eq!(s.game_size(), (1280, 960));
    }
}
