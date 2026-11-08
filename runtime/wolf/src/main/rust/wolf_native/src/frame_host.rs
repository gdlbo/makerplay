//! Frame-local interpreter host. Never locks the native loop while it is stepping.
use crate::input::InputState;
use crate::interpreter::Host;
use crate::wolfformat::event_command::EventCommand;

pub(crate) enum PictureOp {
    Erase(i32,i32),
    ResetEffects(i32,i32),
    Update((i32, String, i32, i32, i32)),
    Effect(EventCommand),
}

#[derive(Default)]
pub(crate) struct LoopHost {
    pub message: Option<String>,
    pub pictures: Vec<(i32, String, i32, i32, i32)>,
    pub pictures_changed: bool,
    pub error: Option<String>,
    pub teleports: usize,
    key: i32,
    cursor: Option<(i32, i32)>,
    pub map_transform: Option<[i32;4]>,
    pub scroll_locks: Vec<i32>,
    pub save_root: Option<std::path::PathBuf>,
    pub empty_auto_inputs: u64,
    pub sound_commands: Vec<EventCommand>,
    pub picture_ops: Vec<PictureOp>,
    pub shake_commands: Vec<(i32, i32)>,
    pub transition_config: Option<crate::screen_effect::TransitionConfig>,
    pub deployment_root: Option<std::path::PathBuf>,
    pub no_target_routes: u64,
    pub event_route_opacities: Vec<(i32,u8)>,
    pub hero_route_opacity: Option<u8>,
    pub hero_route_speed: Option<u16>,
    pub current_map_event: Option<i32>,
    pub bounds: Vec<(i32, i32, i32, i32, i32)>,
    pub key_consumed: bool,
}

impl LoopHost {
    fn unsupported(&mut self, operation: &str) {
        if self.error.is_none() { self.error = Some(format!("native host does not implement {operation}")); }
    }
    pub fn from_input(input: &InputState) -> Self {
        Self { key: input.poll_key(), cursor: input.cursor(), ..Self::default() }
    }
}

impl Host for LoopHost {
    fn on_message(&mut self, text: &str) { self.message = Some(text.into()); }
    fn on_picture(&mut self, command: &EventCommand) {
        let slot = command.params.get(1).copied().unwrap_or(0);
        let option = command.params.first().copied().unwrap_or(0);
        let process = option & 0xF;
        if process==2 || process==3 {
            let endpoint=if option&0x01000000!=0 {
                command.params.get(if process==2 {5}else{2}).copied().unwrap_or(slot)
            } else {slot};
            let (from,to)=(slot.min(endpoint),slot.max(endpoint));
            if process==2 {
                self.picture_ops.push(PictureOp::Erase(from,to));
                self.pictures.push((slot,String::new(),0,0,option));
            } else {self.picture_ops.push(PictureOp::ResetEffects(from,to));}
            return;
        }
        if process==1 {
            self.unsupported(&format!("picture move params={:?}",command.params));
            return;
        }
        let mut name = command.strings.first().filter(|s| !s.trim().is_empty()).cloned().unwrap_or_default();
        if (option >> 4) & 15 == 2 && !name.is_empty() {
            name=format!("@text:{}",name.as_bytes().iter().map(|byte|format!("{byte:02x}")).collect::<String>());
        }
        let x = command.params.get(7).or_else(|| command.params.get(2)).copied().unwrap_or(0);
        let y = command.params.get(8).or_else(|| command.params.get(3)).copied().unwrap_or(0);
        self.picture_ops.push(PictureOp::Update((slot, name.clone(), x, y, option)));
        self.pictures.push((slot, name, x, y, option));
    }
    fn on_teleport(&mut self, _: i32, _: i32, _: i32) { self.teleports += 1; }
    fn on_key_poll(&mut self) -> i32 { self.key }
    fn on_pointer_position(&mut self) -> Option<(i32, i32)> { self.cursor }
    fn on_unhandled(&mut self, command: &EventCommand) {
        self.unsupported(&format!("command {}", command.command_type));
    }
    fn on_sound(&mut self, command: &EventCommand) {
        self.sound_commands.push(command.clone());
    }
    fn on_screen_effect(&mut self, command: &EventCommand) {
        if command.command_type == 160 {
            match crate::screen_effect::TransitionConfig::decode(&command.params) {
                Ok(config) => self.transition_config = Some(config),
                Err(error) => if self.error.is_none() { self.error = Some(error); },
            }
        } else {
            self.unsupported(&format!("screen effect opcode={} params={:?}", command.command_type, command.params));
        }
    }
    fn on_save(&mut self, _: i32) -> bool { self.unsupported("save"); false }
    fn on_load(&mut self, _: i32) -> bool { self.unsupported("load"); false }
    fn on_save_load(&mut self) -> bool { self.unsupported("save/load selection"); false }
    fn on_file_exists(&mut self, name: &str) -> bool {
        let Some(root) = self.deployment_root.as_ref() else {
            self.unsupported("file existence query without deployment root"); return false;
        };
        match crate::deployment_files::file_exists(root, name) {
            Ok(exists) => exists,
            Err(error) => { if self.error.is_none() { self.error = Some(error); } false }
        }
    }
    fn on_write_save_number(&mut self, name: &str, field:i32, value:i32) -> bool {
        let result=self.save_root.as_ref().ok_or("native save directory is not configured".to_string())
            .and_then(|root|crate::named_numbers::write(root,name,field,value));
        match result {Ok(())=>true,Err(error)=>{self.unsupported(&error);false}}
    }
    fn on_read_save_number(&mut self, name: &str, field:i32) -> i32 {
        let result=self.save_root.as_ref().ok_or("native save directory is not configured".to_string())
            .and_then(|root|crate::named_numbers::read(root,name,field));
        match result {Ok(value)=>value,Err(error)=>{self.unsupported(&error);0}}
    }
    fn on_save_file(&mut self, name: &str) -> bool { self.unsupported(&format!("save file {name:?}")); false }
    fn on_auto_input(&mut self, command: &EventCommand) {
        let options=command.params.first().copied().unwrap_or(-1) as u32;
        if options==0x10000000 && command.params.len()==2 && command.params[1]<=100 {
            // sub_A1C0E0 clamps to virtual-key 0. The runtime has no physical
            // binding for VK 0, so this expires on the next input update.
            self.empty_auto_inputs+=1;
        } else {self.unsupported(&format!("automatic input params={:?}",command.params));}
    }
    fn on_ban_input(&mut self, _: bool) { self.unsupported("input restriction"); }
    fn on_move(&mut self, command: &EventCommand) {
        let target = command.params.first().copied()
            .ok_or_else(|| "move route has no target".to_string())
            .and_then(|raw| crate::move_route::resolve_target(raw, self.current_map_event));
        match target {
            Ok(crate::move_route::RouteTarget::NoTarget) => self.no_target_routes += 1,
            Ok(crate::move_route::RouteTarget::Hero) => {
                let Some(route) = &command.route else {
                    self.unsupported("move route without route data"); return;
                };
                if route.steps.iter().all(|step| matches!(step.step_type, 29 | 45) && !step.args_u4.is_empty()) {
                    for step in &route.steps {
                        match step.step_type {
                            29 => self.hero_route_speed = Some(step.args_u4[0] as u16),
                            45 => self.hero_route_opacity = Some(step.args_u4[0].clamp(0, 255) as u8),
                            _ => unreachable!(),
                        }
                    }
                } else { self.unsupported(&format!("hero move-route steps {:?}", route.steps)); }
            }
            Ok(crate::move_route::RouteTarget::MapEvent(id)) => {
                if let Some(route)=&command.route {
                    if route.steps.iter().all(|step|step.step_type==45 && !step.args_u4.is_empty()) {
                        for step in &route.steps {self.event_route_opacities.push((id,step.args_u4[0].clamp(0,255) as u8));}
                    } else {self.unsupported(&format!("map event {id} move-route steps {:?}",route.steps));}
                } else {self.unsupported("map event move route without route data");}
            }
            Ok(target) => self.unsupported(&format!("move route target={target:?} route={:?}", command.route)),
            Err(error) => if self.error.is_none() { self.error = Some(error); },
        }
    }
    fn on_database(&mut self, _: &EventCommand) { self.unsupported("database without loaded database"); }
    fn on_party(&mut self, _: &EventCommand) { self.unsupported("party"); }
    fn on_map_effect(&mut self, command: &EventCommand) {
        if command.command_type == 280 && command.params.len() >= 2 {
            self.shake_commands.push((command.params[0], command.params[1]));
        } else {
            self.unsupported(&format!("map effect opcode={} params={:?}", command.command_type, command.params));
        }
    }
    fn on_scroll(&mut self, command: &EventCommand) {
        match command.params.first().copied() {
            Some(op @ 2..=5)=>self.scroll_locks.push(op),
            _=>self.unsupported(&format!("scroll params={:?}",command.params)),
        }
    }
    fn on_effect(&mut self, command: &EventCommand) {
        if command.params.first() == Some(&2) && command.params.len() >= 7 {
            if command.params[6] < 100 {
                self.unsupported("map zoom below 100% requires expanded map viewport");
            } else {
                self.map_transform = Some([command.params[4], command.params[5], command.params[6], command.params[1]]);
            }
        } else if command.params.first().is_some_and(|options| options & 15 == 0) {
            self.picture_ops.push(PictureOp::Effect(command.clone()));
        } else {
            self.unsupported(&format!("effect opcode={} params={:?}", command.command_type, command.params));
        }
    }
    fn on_chip_change(&mut self, _: &EventCommand) { self.unsupported("chip change"); }
    fn on_return_to_title(&mut self) { self.unsupported("return to title"); }
    fn on_end_game(&mut self) { self.unsupported("end game"); }
    fn on_choices(&mut self, _: &[String]) {} // AppState publishes interpreter blocking state.
    fn on_picture_at(&mut self, x: i32, y: i32, first: i32, count: i32) -> i32 {
        if count <= 0 { return -1; }
        self.bounds.iter().filter(|(slot, left, top, right, bottom)| {
            *slot >= first && i64::from(*slot) < i64::from(first) + i64::from(count)
                && x >= *left && x < *right && y >= *top && y < *bottom
        }).map(|bound| bound.0 - first).max().unwrap_or(-1)
    }
    fn on_key_consumed(&mut self) { self.key_consumed = true; self.key = 0; }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn picture(slot: i32, name: &str, process: i32) -> EventCommand {
        EventCommand { command_type: 150, param_count: 3, params: vec![process, slot],
            branch_depth: 0, strings: vec![name.into()], route: None }
    }

    #[test]
    fn erase_range_removes_only_matching_slots_and_queue_clear_keeps_pictures() {
        let mut state=crate::app::AppState::new(320,240);
        state.interpreter.start(vec![picture(10,"a.png",0),picture(11,"b.png",0),picture(12,"c.png",0)]);
        let shown=state.step_native(&[]);
        assert_eq!(shown.pictures.len(),3);
        let mut erase=picture(11,"",0x01000002);
        erase.params=vec![0x01000002,11,0,0,0,12];
        state.interpreter.start(vec![erase]);
        let host=state.step_native(&[]);
        assert_eq!(host.pictures.iter().map(|p|p.0).collect::<Vec<_>>(),vec![10]);
        assert!(host.pictures_changed);
        let mut clear=picture(10,"",0x01000003);
        clear.params=vec![0x01000003,10,100];
        state.interpreter.start(vec![clear]);
        let host=state.step_native(&[]);
        assert!(!host.pictures_changed);
        assert!(host.error.is_none());
        state.interpreter.start(vec![picture(13,"d.png",0)]);
        let host=state.step_native(&[]);
        assert_eq!(host.pictures.iter().map(|p|p.0).collect::<Vec<_>>(),vec![10,13]);
    }

    #[test]
    fn virtual_key_zero_is_counted_but_nonempty_automatic_input_is_explicit() {
        let mut host=LoopHost::default();
        let mut command=picture(0,"",0);
        command.command_type=125;
        for value in [-1,0,100] {
            command.params=vec![0x10000000,value];
            host.on_auto_input(&command);
        }
        assert_eq!(host.empty_auto_inputs,3);
        assert!(host.error.is_none());
        command.params[1]=113;
        host.on_auto_input(&command);
        assert!(host.error.unwrap().contains("automatic input"));
    }

    #[test]
    fn text_picture_uses_generated_texture_encoding_and_preserves_position() {
        let mut host=LoopHost::default();
        let mut command=picture(100,"PUSH　ANY KEY",0x1020);
        command.params=vec![0x1020,100,0,0,0,0,0,640,500];
        host.on_picture(&command);
        let (_,name,x,y,option)=&host.pictures[0];
        assert!(name.starts_with("@text:"));
        let hex=name.strip_prefix("@text:").unwrap();
        let bytes=(0..hex.len()).step_by(2).map(|i|u8::from_str_radix(&hex[i..i+2],16).unwrap()).collect::<Vec<_>>();
        assert_eq!(String::from_utf8(bytes).unwrap(),"PUSH　ANY KEY");
        assert_eq!((*x,*y,*option),(640,500,0x1020));
    }

    #[test]
    fn no_current_event_route_is_counted_without_applying_hero_effects() {
        let command = EventCommand { command_type: 201, param_count: 2,
            params: vec![-1], branch_depth: 0, strings: vec![], route: None };
        let mut host = LoopHost::default();
        host.on_move(&command);
        assert_eq!(host.no_target_routes, 1);
        assert!(host.error.is_none());
        assert!(host.hero_route_speed.is_none());
        assert!(host.hero_route_opacity.is_none());
    }

    #[test]
    fn hero_speed_route_preserves_zero_without_clamping() {
        use crate::wolfformat::event_command::{MoveRoute, RouteStep};
        let mut host = LoopHost::default();
        let mut command = EventCommand { command_type: 201, param_count: 2,
            params: vec![-2], branch_depth: 0, strings: vec![],
            route: Some(MoveRoute { animation_frequency: 3, move_speed: 3, move_frequency: 3,
                move_route_mode: 0, behavior_options_raw: 3, route_options_raw: 0,
                steps: vec![RouteStep { step_type: 29, args_u4: vec![0], args_u1: vec![0] }] }) };
        host.on_move(&command);
        assert_eq!(host.hero_route_speed, Some(0));
        assert!(host.error.is_none());
        for (raw, opacity) in [(-1, 0), (128, 128), (300, 255)] {
            command.route.as_mut().unwrap().steps[0].step_type = 45;
            command.route.as_mut().unwrap().steps[0].args_u4[0] = raw;
            host.on_move(&command);
            assert_eq!(host.hero_route_opacity, Some(opacity));
        }
        command.params[0] = -1;
        let mut event_host = LoopHost::default();
        event_host.current_map_event = Some(7);
        event_host.on_move(&command);
        assert!(event_host.error.is_none());
        assert_eq!(event_host.event_route_opacities,vec![(7,255)]);
        assert!(event_host.hero_route_speed.is_none());
    }

    #[test]
    fn hit_test_uses_topmost_slot_and_exclusive_right_bottom_edges() {
        let mut host = LoopHost::default();
        host.bounds = vec![(12, 0, 0, 20, 20), (10, 0, 0, 10, 10), (99, 0, 0, 30, 30)];
        assert_eq!(host.on_picture_at(5, 5, 10, 3), 2);
        assert_eq!(host.on_picture_at(20, 5, 10, 3), -1);
        assert_eq!(host.on_picture_at(5, 20, 10, 3), -1);
        assert_eq!(host.on_picture_at(5, 5, 10, 0), -1);
        assert!(host.error.is_none());
    }

    #[test]
    fn native_message_requires_a_new_press_and_does_not_repeat_held_confirm() {
        let mut state = crate::app::AppState::new(320, 240);
        let message = |text: &str| EventCommand { command_type: 101, param_count: 1,
            params: vec![], branch_depth: 0, strings: vec![text.into()], route: None };
        state.interpreter.start(vec![message("first"), message("second")]);
        let down = crate::sdl_event::AppEvent::Key { scancode: 40, down: true, repeat: false };
        let up = crate::sdl_event::AppEvent::Key { scancode: 40, down: false, repeat: false };
        state.step_native(std::slice::from_ref(&down));
        assert!(matches!(state.interpreter.blocking(), Some(crate::interpreter::Blocking::Message(t)) if t == "first"));
        state.step_native(&[]);
        assert!(matches!(state.interpreter.blocking(), Some(crate::interpreter::Blocking::Message(t)) if t == "first"));
        state.step_native(&[up, down]);
        assert!(matches!(state.interpreter.blocking(), Some(crate::interpreter::Blocking::Message(t)) if t == "second"));
        state.step_native(&[]);
        assert!(matches!(state.interpreter.blocking(), Some(crate::interpreter::Blocking::Message(t)) if t == "second"));
    }

    #[test]
    fn empty_effect_range_is_counted_but_existing_picture_effect_is_not_ignored() {
        let effect = || EventCommand { command_type: 290, param_count: 8,
            params: vec![128, 1, 1599, 1599, 1, 0, 0], branch_depth: 0, strings: vec![], route: None };
        let mut app = crate::app::AppState::new(320, 240);
        app.interpreter.start(vec![effect(), picture(1599, "sheet.png", 0)]);
        app.step_native(&[]);
        assert_eq!(app.empty_picture_effects, 1);
        assert!(app.runtime_error.is_none());
        app.interpreter.start(vec![effect()]);
        app.step_native(&[]);
        assert!(app.runtime_error.as_ref().unwrap().contains("unimplemented picture effect"));
        assert_eq!(app.empty_picture_effects, 1);
    }

    #[test]
    fn erasing_picture_before_effect_makes_the_range_empty() {
        let mut app = crate::app::AppState::new(320, 240);
        app.interpreter.start(vec![picture(1, "sheet.png", 0)]);
        app.step_native(&[]);
        let effect = EventCommand { command_type: 290, param_count: 8,
            params: vec![128, 1, 1, 1, 1, 0, 0], branch_depth: 0, strings: vec![], route: None };
        app.interpreter.start(vec![picture(1, "", 2), effect]);
        app.step_native(&[]);
        assert_eq!(app.empty_picture_effects, 1);
        assert!(app.runtime_error.is_none());
    }

    #[test]
    fn picture_slots_survive_other_slot_updates_and_final_erase_is_published() {
        let mut state = crate::app::AppState::new(320, 240);
        state.interpreter.start(vec![picture(2, "second.png", 0)]);
        assert_eq!(state.step_native(&[]).pictures.len(), 1);
        state.interpreter.start(vec![picture(1, "first.png", 0)]);
        let host = state.step_native(&[]);
        assert_eq!(host.pictures.iter().map(|p| p.0).collect::<Vec<_>>(), vec![1, 2]);
        state.interpreter.start(vec![picture(1, "", 2)]);
        let host = state.step_native(&[]);
        assert_eq!(host.pictures.iter().map(|p| p.0).collect::<Vec<_>>(), vec![2]);
        state.interpreter.start(vec![picture(2, "", 2)]);
        let host = state.step_native(&[]);
        assert!(host.pictures_changed);
        assert!(host.pictures.is_empty());
        assert!(!state.step_native(&[]).pictures_changed);
    }

    #[test]
    fn native_sound_without_database_reports_failure_instead_of_succeeding_silently() {
        let mut state = crate::app::AppState::new(320, 240);
        let mut command = picture(0, "music.ogg", 0);
        command.command_type = 140;
        state.interpreter.start(vec![command]);
        state.step_native(&[]);
        assert!(state.quit_requested());
        assert_eq!(state.runtime_error.as_deref(), Some("sound requires loaded database"));
    }

    #[test]
    fn native_step_delivers_input_to_interpreter_without_relocking_loop() {
        use crate::wolfformat::event_command::EventCommand;
        let mut state = crate::app::AppState::new(320, 240);
        state.interpreter.start(vec![EventCommand {
            command_type: 123, param_count: 3, params: vec![0, 128],
            branch_depth: 0, strings: vec![], route: None,
        }]);
        let events = [
            crate::sdl_event::AppEvent::Key { scancode: 40, down: true, repeat: false },
            crate::sdl_event::AppEvent::Key { scancode: 40, down: false, repeat: false },
        ];
        let host = state.step_native(&events);
        assert_eq!(state.interpreter.variables.get(&0), Some(&10));
        assert!(host.key_consumed);
        assert_eq!(state.input.poll_key(), 0);
        assert!(state.interpreter.is_finished());
    }

    #[test]
    fn consumed_key_is_cleared_locally_before_next_poll() {
        let mut input = InputState::new(320, 240);
        input.key_down(10);
        input.key_up(10);
        let mut host = LoopHost::from_input(&input);
        assert_eq!(host.on_key_poll(), 10);
        host.on_key_consumed();
        assert_eq!(host.on_key_poll(), 0);
        assert!(host.key_consumed);
        assert_eq!(input.poll_key(), 10);
        input.consume_key();
        assert_eq!(input.poll_key(), 0);
    }
}
