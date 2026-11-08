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
#[derive(Default)]
struct EventSelfBank {
    numbers: std::collections::HashMap<i32, i32>,
    strings: std::collections::HashMap<i32, String>,
}

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
    /// Last fatal runtime failure. The driver must surface it before shutting down.
    pub runtime_error: Option<String>,
    /// Original-engine route requests whose current-event target does not exist.
    #[cfg(not(test))]
    audio_mixer: Option<crate::audio_output::AudioMixer>,
    pub map_transform: crate::map_transform::MapTransform,
    pub map_shake: crate::map_shake::MapShake,
    pub transition_config: Option<crate::screen_effect::TransitionConfig>,
    event_opacities: std::collections::HashMap<i32,u8>,
    native_event_specs: Vec<(String,i32,i32,u32,u32,i32)>,
    pub save_root: Option<std::path::PathBuf>,
    pub empty_auto_inputs: u64,
    pub empty_picture_effects: u64,
    pub no_target_routes: u64,
    pub hero_route_speed: u16,
    common_runners: crate::common_runners::CommonRunners,
    gameplay_ready: bool,
    event_self_banks: std::collections::HashMap<(String, i32), EventSelfBank>,
    active_map_event: Option<(String, i32)>,
    map_context: String,
    pub event_scheduler: crate::event_scheduler::EventScheduler,
    selected_choice: i32,
    ui_press_edges: u64,
    picture_specs: Vec<(i32, String, i32, i32, i32)>,
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
            runtime_error: None,
            #[cfg(not(test))]
            audio_mixer: None,
            map_transform: crate::map_transform::MapTransform::default(),
            map_shake: crate::map_shake::MapShake::default(),
            transition_config: None,
            event_opacities: std::collections::HashMap::new(),
            native_event_specs: Vec::new(),
            save_root: None,
            empty_auto_inputs: 0,
            empty_picture_effects: 0,
            no_target_routes: 0,
            hero_route_speed: 4,
            common_runners: crate::common_runners::CommonRunners::default(),
            gameplay_ready: false,
            event_self_banks: std::collections::HashMap::new(),
            active_map_event: None,
            map_context: String::new(),
            event_scheduler: crate::event_scheduler::EventScheduler::default(),
            selected_choice: 0,
            ui_press_edges: 0,
            picture_specs: Vec::new(),
            session: None,
        }
    }

    #[cfg(not(test))]
    pub fn disable_audio(&mut self) {
        self.audio_mixer = None;
    }

    #[cfg(not(test))]
    pub fn enable_audio(&mut self) {
        self.audio_mixer = Some(crate::audio_output::AudioMixer::default());
    }

    fn play_sound(&mut self, command: &crate::wolfformat::event_command::EventCommand) -> Result<(), String> {
        let database = self.interpreter.database().ok_or("sound requires loaded database")?;
        let request = crate::sound_command::resolve_resource(command, database)?;
        #[cfg(not(test))]
        {
            let root = self.session.as_ref().ok_or("sound requires deployment session")?.deployment_root();
            let mixer = self.audio_mixer.as_mut().ok_or("native audio output is not enabled")?;
            mixer.play(root, &request)
        }
        #[cfg(test)]
        Err(format!("native audio output unavailable in test host: {request:?}"))
    }

    /// Attaches a renderer session so the loop draws the game.
    pub fn with_session(mut self, session: GameSession) -> Self {
        self.set_map_context(session.map_name());
        self.session = Some(session);
        self
    }

    /// Starts at most one foreground autorun, preserving a running interpreter.
    /// `read` must use the event's own variable context.
    pub fn start_next_autorun(&mut self, events: &[crate::wolfformat::map::MapEvent],
        read: impl FnMut(i32, i32) -> i32) -> Result<Option<(i32, usize)>, String>
    {
        if !self.interpreter.is_finished() { return Ok(None); }
        self.store_event_self_bank();
        let Some((event, page, selected)) = self.event_scheduler.next_autorun(events, read)? else {
            return Ok(None);
        };
        self.install_event_self_bank(event);
        self.interpreter.start(selected.commands.clone());
        self.event_scheduler.mark_started(event, page);
        Ok(Some((event, page)))
    }

    fn store_event_self_bank(&mut self) {
        use crate::interpreter::refs::{is_self_var_key, is_self_string_key};
        if let Some(context) = self.active_map_event.take() {
            self.event_self_banks.insert(context, EventSelfBank {
                numbers: self.interpreter.variables.iter().filter(|(key, _)| is_self_var_key(**key))
                    .map(|(key, value)| (*key, *value)).collect(),
                strings: self.interpreter.strings.iter().filter(|(key, _)| is_self_string_key(**key))
                    .map(|(key, value)| (*key, value.clone())).collect(),
            });
        }
    }

    fn install_event_self_bank(&mut self, event: i32) {
        use crate::interpreter::refs::{is_self_var_key, is_self_string_key};
        self.interpreter.variables.retain(|key, _| !is_self_var_key(*key));
        self.interpreter.strings.retain(|key, _| !is_self_string_key(*key));
        let context = (self.map_context.clone(), event);
        if let Some(bank) = self.event_self_banks.get(&context) {
            self.interpreter.variables.extend(bank.numbers.iter().map(|(key, value)| (*key, *value)));
            self.interpreter.strings.extend(bank.strings.iter().map(|(key, value)| (*key, value.clone())));
        }
        self.active_map_event = Some(context);
    }

    /// A transfer starts fresh autorun activations but retains each map's self banks.
    pub fn set_map_context(&mut self, map: &str) {
        if self.map_context != map {
            if self.interpreter.is_finished() { self.store_event_self_bank(); }
            self.map_context = map.into();
            self.event_opacities.clear();
            self.native_event_specs.clear();
            self.event_scheduler.reset();
        }
    }

    /// Initializes native gameplay dependencies. Rendering-only sessions do not
    /// implicitly start events or require database assets.
    pub fn initialize_gameplay(&mut self, data_dir: &std::path::Path) -> Result<(), String> {
        self.interpreter.load_deployment(data_dir)?;
        self.common_runners = crate::common_runners::CommonRunners::new(&self.interpreter);
        self.gameplay_ready = true;
        Ok(())
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
            if let Some((map_id, tx, ty)) = self.interpreter.take_teleport() {
                if let Err(error) = session.teleport(map_id, tx, ty) {
                    self.runtime_error = Some(error);
                    self.quit_requested = true;
                    return;
                }
            }
            session.tick();
        }
        self.frames += 1;
    }

    pub fn step_native(&mut self, events: &[AppEvent]) -> crate::frame_host::LoopHost {
        if let Some(session)=self.session.as_mut() {session.tick_picture_effects();}
        if self.quit_requested { return crate::frame_host::LoopHost::default(); }
        for event in events { self.apply_event(event); }
        if self.gameplay_ready && self.interpreter.is_finished() {
            self.store_event_self_bank();
            if let Some(session) = &self.session {
                let map_events = session.map_events();
                let variables = self.interpreter.variables.clone();
                let banks = &self.event_self_banks;
                let map = &self.map_context;
                let selection = self.event_scheduler.next_autorun(map_events, |event, raw| {
                    use crate::interpreter::refs::{decode_variable_ref, is_self_var_key};
                    let key = decode_variable_ref(raw);
                    if is_self_var_key(key) {
                        banks.get(&(map.clone(), event)).and_then(|bank| bank.numbers.get(&key)).copied().unwrap_or(0)
                    } else { variables.get(&key).copied().unwrap_or(0) }
                }).map(|selected| selected.map(|(id, index, page)| (id, index, page.commands.clone())));
                match selection {
                    Ok(Some((id, index, commands))) => {
                        self.install_event_self_bank(id);
                        self.interpreter.start(commands);
                        self.event_scheduler.mark_started(id, index);
                    }
                    Ok(None) => {}
                    Err(error) => { self.runtime_error = Some(error); self.quit_requested = true; }
                }
            }
        }
        let active = !self.interpreter.is_finished();
        let new_press = self.input.press_edges() != self.ui_press_edges;
        self.ui_press_edges = self.input.press_edges();
        if new_press {
            let key = self.input.poll_key();
            match self.interpreter.blocking() {
                Some(crate::interpreter::Blocking::Message(_)) if key == 10 || key == 20 => {
                    self.interpreter.advance();
                    self.input.consume_key();
                }
                Some(crate::interpreter::Blocking::Choices(options)) => {
                    match key {
                        8 | 4 => self.selected_choice = (self.selected_choice - 1).max(0),
                        2 | 6 if !options.is_empty() => self.selected_choice = (self.selected_choice + 1) % options.len() as i32,
                        10 => self.interpreter.choose(self.selected_choice),
                        _ => {}
                    }
                    self.input.consume_key();
                }
                _ => {}
            }
        }
        let mut host = crate::frame_host::LoopHost::from_input(&self.input);
        host.save_root=self.save_root.clone();
        host.current_map_event = self.active_map_event.as_ref().map(|context| context.1);
        if let Some(session) = &self.session {
            host.bounds = session.picture_bounds();
            host.deployment_root = Some(session.deployment_root().to_path_buf());
        }
        self.step(&[], &mut host);
        host.current_map_event = None;
        if self.gameplay_ready && !self.quit_requested && host.error.is_none() {
            if let Err(error) = self.common_runners.tick(&mut self.interpreter, &mut host, 256) {
                self.runtime_error = Some(error);
                self.quit_requested = true;
            }
        }
        for command in &host.sound_commands {
            if let Err(error) = self.play_sound(command) {
                if host.error.is_none() { host.error = Some(error); }
                break;
            }
        }
        #[cfg(not(test))]
        if let Some(mixer) = self.audio_mixer.as_mut() {
            if let Err(error) = mixer.tick() { if host.error.is_none() { host.error = Some(error); } }
        }
        if let Some([x,y,zoom,frames]) = host.map_transform {
            self.map_transform.configure(x,y,zoom,frames);
        }
        self.map_transform.tick();
        for (flags, duration) in &host.shake_commands {
            if let Err(error) = self.map_shake.configure(*flags, *duration) {
                if host.error.is_none() { host.error = Some(error); }
            }
        }
        let fps = self.session.as_ref().map_or(60, |session| session.fps());
        self.map_shake.tick(fps);
        if let Some(session) = self.session.as_mut() {
            session.set_map_transform(self.map_transform.values());
            session.set_shake(&self.map_shake);
        }
        if let Some(config) = host.transition_config { self.transition_config = Some(config); }
        self.no_target_routes = self.no_target_routes.saturating_add(host.no_target_routes);
        if let Some(opacity) = host.hero_route_opacity {
            if let Some(session) = self.session.as_mut() { session.set_hero_opacity(opacity); }
        }
        if let Some(speed) = host.hero_route_speed { self.hero_route_speed = speed; }
        if self.interpreter.is_finished() { self.store_event_self_bank(); }
        if let Some(map) = self.session.as_ref().map(|session| session.map_name().to_string()) {
            self.set_map_context(&map);
        }
        if active {
            if let Some(session) = self.session.as_mut() {
                match self.interpreter.blocking() {
                    Some(crate::interpreter::Blocking::Message(text)) => session.set_message(Some(text.clone())),
                    Some(crate::interpreter::Blocking::Choices(options)) => session.set_choices(options.clone(), self.selected_choice),
                    _ => {
                        session.set_message(None);
                        session.set_choices(Vec::new(), 0);
                    }
                }
            }
        }
        if !matches!(self.interpreter.blocking(), Some(crate::interpreter::Blocking::Choices(_))) {
            self.selected_choice = 0;
        }
        if host.key_consumed { self.input.consume_key(); }
        host.pictures_changed = !host.pictures.is_empty();
        for operation in host.picture_ops.drain(..) {
            match operation {
                crate::frame_host::PictureOp::Erase(from,to) => {
                    if let Some(session)=self.session.as_mut() {
                        for picture in &self.picture_specs {
                            if (from..=to).contains(&picture.0) {session.clear_picture_effects(picture.0);}
                        }
                    }
                    self.picture_specs.retain(|picture| !(from..=to).contains(&picture.0));
                    host.pictures_changed=true;
                }
                crate::frame_host::PictureOp::ResetEffects(from,to) => {
                    if let Some(session)=self.session.as_mut() {session.clear_picture_queue(from,to);}
                }
                crate::frame_host::PictureOp::Update(update) => {
                    if let Some(session)=self.session.as_mut() {session.clear_picture_effects(update.0);}
                    self.picture_specs.retain(|picture| picture.0 != update.0);
                    if !update.1.is_empty() { self.picture_specs.push(update); }
                }
                crate::frame_host::PictureOp::Effect(command) => {
                    if let (Some(a), Some(b)) = (command.params.get(2), command.params.get(3)) {
                        let from = (*a).min(*b);
                        let to = (*a).max(*b);
                        if self.picture_specs.iter().any(|picture| (from..=to).contains(&picture.0)) {
                            let kind=command.params.first().copied().unwrap_or(-1);
                            if matches!(kind,80|96) && command.params.len()==7 {
                                if let Some(session)=self.session.as_mut() {
                                    for picture in &self.picture_specs {
                                        if (from..=to).contains(&picture.0) {
                                            session.start_colour_pulse(picture.0,[command.params[4],command.params[5],command.params[6]],command.params[1],kind==96);
                                        }
                                    }
                                } else if host.error.is_none() {host.error=Some("picture effect requires render session".into());}
                            } else if host.error.is_none() { host.error = Some(format!("unimplemented picture effect params={:?}", command.params)); }
                        } else { self.empty_picture_effects += 1; }
                    } else if host.error.is_none() { host.error = Some("picture effect is missing slot range".into()); }
                }
            }
        }
        if host.pictures_changed {
            self.picture_specs.sort_by_key(|picture| picture.0);
            host.pictures = self.picture_specs.clone();
        }
        if self.gameplay_ready {
            for (id,opacity) in &host.event_route_opacities {
                if self.session.as_ref().is_some_and(|session|session.map_events().iter().any(|event|event.event_id==*id)) {
                    self.event_opacities.insert(*id,*opacity);
                } else if host.error.is_none() {host.error=Some(format!("map event route target {id} not found"));}
            }
            if let Err(error)=self.refresh_native_events() {if host.error.is_none() {host.error=Some(error);}}
        }
        for operation in &host.scroll_locks {
            let result=self.session.as_mut().ok_or("camera lock requires render session".to_string())
                .and_then(|session|session.set_camera_lock(*operation));
            if let Err(error)=result {if host.error.is_none() {host.error=Some(error);}}
        }
        self.empty_auto_inputs+=host.empty_auto_inputs;
        if let Some(error) = &host.error {
            self.runtime_error = Some(error.clone());
            self.quit_requested = true;
        }
        host
    }

    fn refresh_native_events(&mut self)->Result<(),String> {
        let Some(session)=self.session.as_mut() else {return Err("native events require render session".into());};
        let mut specs=Vec::new();
        for event in session.map_events() {
            let page=event.active_page(&mut |raw| {
                use crate::interpreter::refs::{decode_variable_ref,is_self_var_key};
                let key=decode_variable_ref(raw);
                if is_self_var_key(key) {
                    if self.active_map_event.as_ref().is_some_and(|context|context.0==self.map_context && context.1==event.event_id) {self.interpreter.variables.get(&key).copied().unwrap_or(0)}
                    else {self.event_self_banks.get(&(self.map_context.clone(),event.event_id)).and_then(|bank|bank.numbers.get(&key)).copied().unwrap_or(0)}
                } else {self.interpreter.variables.get(&key).copied().unwrap_or(0)}
            })?;
            if let Some((_,page))=page {
                if !page.graphic_file.is_empty() {
                    specs.push((page.graphic_file.clone(),event.x,event.y,u32::from(page.graphic_row),u32::from(page.graphic_col),
                        i32::from(self.event_opacities.get(&event.event_id).copied().unwrap_or(page.graphic_opacity))));
                }
            }
        }
        if self.native_event_specs!=specs {
            session.set_events(&specs)?;
            self.native_event_specs=specs;
        }
        Ok(())
    }

    pub fn gameplay_diagnostics(&self) -> String {
        format!("frames={} map={:?} pc={} blocked={:?} finished={} pictures={:?}\n{}\nrecent foreground commands:\n{}",
            self.frames,self.session.as_ref().map(|s|s.map_name()),self.interpreter.current_pc(),
            self.interpreter.blocking(),self.interpreter.is_finished(),self.picture_specs,
            self.common_runners.diagnostics(),self.interpreter.recent_commands())
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
    fn event_self_banks_are_isolated_by_event_and_map_while_globals_stay_shared() {
        use crate::interpreter::refs::self_var_key;
        let key = self_var_key(3);
        let mut state = AppState::new(320, 240);
        state.set_map_context("Map001");
        state.install_event_self_bank(10);
        state.interpreter.variables.insert(key, 42);
        state.interpreter.strings.insert(key, "first event".into());
        state.interpreter.variables.insert(7, 99);
        state.store_event_self_bank();
        state.install_event_self_bank(11);
        assert!(!state.interpreter.variables.contains_key(&key));
        assert!(!state.interpreter.strings.contains_key(&key));
        state.interpreter.variables.insert(key, 84);
        state.store_event_self_bank();
        state.install_event_self_bank(10);
        assert_eq!(state.interpreter.variables.get(&key), Some(&42));
        assert_eq!(state.interpreter.strings.get(&key).map(String::as_str), Some("first event"));
        assert_eq!(state.interpreter.variables.get(&7), Some(&99));
        state.set_map_context("Map002");
        state.install_event_self_bank(10);
        assert!(!state.interpreter.variables.contains_key(&key));
        state.set_map_context("Map001");
        state.install_event_self_bank(11);
        assert_eq!(state.interpreter.variables.get(&key), Some(&84));
    }

    #[test]
    fn transfer_preserves_running_events_original_self_context() {
        use crate::interpreter::refs::self_var_key;
        use crate::wolfformat::event_command::EventCommand;
        let key = self_var_key(0);
        let mut state = AppState::new(320, 240);
        state.set_map_context("Map001");
        state.install_event_self_bank(10);
        state.interpreter.start(vec![EventCommand { command_type: 101, param_count: 1,
            params: vec![], branch_depth: 0, strings: vec!["running".into()], route: None }]);
        state.step_native(&[]);
        state.set_map_context("Map002");
        state.interpreter.variables.insert(key, 123);
        state.interpreter.advance();
        state.step_native(&[]);
        assert!(state.active_map_event.is_none());
        state.install_event_self_bank(10);
        assert!(!state.interpreter.variables.contains_key(&key));
        state.set_map_context("Map001");
        state.install_event_self_bank(10);
        assert_eq!(state.interpreter.variables.get(&key), Some(&123));
    }

    #[test]
    fn failed_gameplay_initialization_preserves_existing_interpreter_state() {
        let root = std::env::temp_dir().join(format!("wolf-bad-deployment-{}", std::process::id()));
        let basic = root.join("BasicData");
        std::fs::create_dir_all(&basic).unwrap();
        std::fs::write(basic.join("CDataBase.dat"), b"corrupt database").unwrap();
        let mut state = AppState::new(320, 240);
        state.interpreter.set_database(crate::interpreter::database::Database::default());
        state.interpreter.variables.insert(123, 456);
        let error = state.initialize_gameplay(&root).unwrap_err();
        assert!(error.contains("CDataBase.dat"), "{error}");
        assert!(state.interpreter.database().is_some());
        assert_eq!(state.interpreter.variables.get(&123), Some(&456));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn native_choices_move_once_per_press_and_confirm() {
        use crate::wolfformat::event_command::EventCommand;
        let mut state = AppState::new(320, 240);
        state.interpreter.start(vec![EventCommand {
            command_type: 102, param_count: 1, params: vec![], branch_depth: 0,
            strings: vec!["first".into(), "second".into()], route: None,
        }]);
        state.step_native(&[]);
        let down = AppEvent::Key { scancode: 81, down: true, repeat: false };
        state.step_native(&[down]);
        assert_eq!(state.selected_choice, 1);
        state.step_native(&[]);
        assert_eq!(state.selected_choice, 1);
        state.step_native(&[
            AppEvent::Key { scancode: 81, down: false, repeat: false },
            AppEvent::Key { scancode: 40, down: true, repeat: false },
        ]);
        assert!(state.interpreter.is_finished());
        assert!(state.runtime_error.is_none());
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
