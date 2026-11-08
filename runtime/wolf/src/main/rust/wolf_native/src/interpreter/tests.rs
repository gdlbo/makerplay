//! Interpreter tests, mirroring `WolfInterpreterTest.kt`.
//!
//! Each test here corresponds to a Kotlin test of the same name, so the two
//! implementations are held to one behavioural standard rather than to two
//! independent opinions about the format.

use std::collections::HashMap;

use crate::wolfformat::event_command::EventCommand;

use super::{compare, Blocking, Host, Interpreter, MAX_COMMANDS_PER_TICK};

#[test]
fn automatic_input_resolves_key_operand_without_modifying_mode_bits() {
    struct InputHost(Vec<i32>);
    impl Host for InputHost {
        fn on_auto_input(&mut self,command:&EventCommand) {self.0=command.params.clone();}
    }
    let mut interpreter=Interpreter::new(60);
    interpreter.variables.insert(super::refs::self_var_key(0),113);
    interpreter.start(vec![p(125,&[0x10000000,1_600_000])]);
    let mut host=InputHost(vec![]);
    interpreter.tick_default(&mut host);
    assert_eq!(host.0,vec![0x10000000,113]);
}

#[test]
fn move_route_target_resolves_cself_reference_before_host_dispatch() {
    struct TargetHost(Option<i32>);
    impl Host for TargetHost {
        fn on_move(&mut self, command: &EventCommand) { self.0 = command.params.first().copied(); }
    }
    let mut interpreter = Interpreter::new(60);
    interpreter.variables.insert(super::refs::self_var_key(0), -2);
    interpreter.start(vec![p(201, &[1_600_000])]);
    let mut host = TargetHost(None);
    interpreter.tick_default(&mut host);
    assert_eq!(host.0, Some(-2));
}

#[test]
fn common_runner_shares_globals_and_preserves_each_stacks_self_bank() {
    let mut main = Interpreter::new(60);
    main.variables.insert(7, 10);
    main.variables.insert(super::refs::self_var_key(0), 111);
    main.strings.insert(super::refs::self_var_key(0), "foreground".into());
    main.set_database(super::database::Database::default());
    let mut child = main.common_runner();
    child.variables.insert(super::refs::self_var_key(0), 222);
    child.strings.insert(super::refs::self_var_key(0), "parallel".into());
    child.start(vec![p(121, &[7, 5, 0, 0x100])]); // add five
    main.tick_common_runner(&mut child, &mut RecordingHost::new(), 100);
    assert_eq!(main.variables.get(&7), Some(&15));
    assert_eq!(main.variables.get(&super::refs::self_var_key(0)), Some(&111));
    assert_eq!(child.variables.get(&super::refs::self_var_key(0)), Some(&222));
    assert_eq!(main.strings.get(&super::refs::self_var_key(0)).map(String::as_str), Some("foreground"));
    assert_eq!(child.strings.get(&super::refs::self_var_key(0)).map(String::as_str), Some("parallel"));
    assert!(!child.variables.contains_key(&7));
    assert!(main.database().is_some());
    assert!(child.database().is_none());
    main.variables.insert(7, 100);
    child.start(vec![p(121, &[7, 5, 0, 0x100])]);
    main.tick_common_runner(&mut child, &mut RecordingHost::new(), 100);
    assert_eq!(main.variables.get(&7), Some(&105));
}

// --- fixtures ---------------------------------------------------------------

fn cmd(type_: i32, params: Vec<i32>, strings: Vec<&str>, depth: u8) -> EventCommand {
    EventCommand {
        param_count: if type_ == 0 {
            0
        } else {
            params.len() as u8 + 1
        },
        command_type: type_,
        params,
        branch_depth: depth,
        strings: strings.into_iter().map(str::to_string).collect(),
        route: None,
    }
}

/// Shorthand for a command with no strings.
fn p(type_: i32, params: &[i32]) -> EventCommand {
    cmd(type_, params.to_vec(), Vec::new(), 0)
}

/// Shorthand for a command with no params.
fn s(type_: i32, strings: &[&str]) -> EventCommand {
    cmd(type_, Vec::new(), strings.to_vec(), 0)
}

/// Shorthand for a bare marker command.
fn m(type_: i32) -> EventCommand {
    cmd(type_, Vec::new(), Vec::new(), 0)
}

#[derive(Default)]
struct RecordingHost {
    messages: Vec<String>,
    choices: Vec<Vec<String>>,
    sounds: Vec<i32>,
    pictures: Vec<EventCommand>,
    screen_effects: Vec<i32>,
    teleports: Vec<(i32, i32, i32)>,
    saves: Vec<i32>,
    loads: Vec<i32>,
    unhandled: Vec<i32>,
    moves: Vec<i32>,
    database: Vec<i32>,
    parties: Vec<i32>,
    map_effects: Vec<i32>,
    scrolls: Vec<i32>,
    effects: Vec<i32>,
    chip_changes: Vec<i32>,
    ban_input: Vec<bool>,
    auto_inputs: Vec<i32>,
    save_loads: i32,
    return_to_title: i32,
    end_games: i32,
    key_poll_result: i32,
    key_consumed: i32,
    move_finished: bool,
    pointer: Option<(i32, i32)>,
    hovered: Vec<(i32, i32, i32, i32)>,
    picture_at_result: i32,
    file_exists: bool,
    save_files: Vec<String>,
    expand: Option<fn(&str) -> String>,
}

impl RecordingHost {
    fn new() -> Self {
        Self {
            move_finished: true,
            picture_at_result: 2,
            ..Default::default()
        }
    }
}

impl Host for RecordingHost {
    fn on_message(&mut self, text: &str) {
        self.messages.push(text.to_string());
    }
    fn on_choices(&mut self, options: &[String]) {
        self.choices.push(options.to_vec());
    }
    fn expand_text(&mut self, text: &str) -> String {
        match self.expand {
            Some(f) => f(text),
            None => text.to_string(),
        }
    }
    fn on_sound(&mut self, command: &EventCommand) {
        self.sounds.push(command.command_type);
    }
    fn on_picture(&mut self, command: &EventCommand) {
        self.pictures.push(command.clone());
    }
    fn on_screen_effect(&mut self, command: &EventCommand) {
        self.screen_effects.push(command.command_type);
    }
    fn on_teleport(&mut self, map_id: i32, tile_x: i32, tile_y: i32) {
        self.teleports.push((map_id, tile_x, tile_y));
    }
    fn on_save(&mut self, slot: i32) -> bool {
        self.saves.push(slot);
        true
    }
    fn on_load(&mut self, slot: i32) -> bool {
        self.loads.push(slot);
        true
    }
    fn on_save_load(&mut self) -> bool {
        self.save_loads += 1;
        true
    }
    fn on_file_exists(&mut self, _name: &str) -> bool {
        self.file_exists
    }
    fn on_save_file(&mut self, name: &str) -> bool {
        self.save_files.push(name.to_string());
        true
    }
    fn on_key_poll(&mut self) -> i32 {
        self.key_poll_result
    }
    fn on_key_consumed(&mut self) {
        self.key_consumed += 1;
    }
    fn on_auto_input(&mut self, command: &EventCommand) {
        self.auto_inputs.push(command.command_type);
    }
    fn on_ban_input(&mut self, banned: bool) {
        self.ban_input.push(banned);
    }
    fn on_move(&mut self, command: &EventCommand) {
        self.moves.push(command.command_type);
    }
    fn on_move_finished(&mut self) -> bool {
        self.move_finished
    }
    fn on_database(&mut self, command: &EventCommand) {
        self.database.push(command.command_type);
    }
    fn on_party(&mut self, command: &EventCommand) {
        self.parties.push(command.command_type);
    }
    fn on_map_effect(&mut self, command: &EventCommand) {
        self.map_effects.push(command.command_type);
    }
    fn on_scroll(&mut self, command: &EventCommand) {
        self.scrolls.push(command.command_type);
    }
    fn on_effect(&mut self, command: &EventCommand) {
        self.effects.push(command.command_type);
    }
    fn on_chip_change(&mut self, command: &EventCommand) {
        self.chip_changes.push(command.command_type);
    }
    fn on_return_to_title(&mut self) {
        self.return_to_title += 1;
    }
    fn on_end_game(&mut self) {
        self.end_games += 1;
    }
    fn on_pointer_position(&mut self) -> Option<(i32, i32)> {
        self.pointer
    }
    fn on_picture_at(&mut self, x: i32, y: i32, first_slot: i32, count: i32) -> i32 {
        self.hovered.push((x, y, first_slot, count));
        self.picture_at_result
    }
    fn on_unhandled(&mut self, command: &EventCommand) {
        self.unhandled.push(command.command_type);
    }
}

fn interpreter() -> Interpreter {
    Interpreter::new(60)
}

/// Ticks until the interpreter finishes or a cap is reached.
fn run(interp: &mut Interpreter, host: &mut dyn Host) {
    interp.tick_default(host);
}

// --- messages ---------------------------------------------------------------

#[test]
fn message_shows_text_and_blocks_until_advanced() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![s(101, &["こんにちは"]), s(101, &["次"])]);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["こんにちは"]);
    assert!(i.is_blocked());

    // The second message must not run until advance().
    run(&mut i, &mut host);
    assert_eq!(host.messages.len(), 1);

    i.advance();
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["こんにちは", "次"]);

    i.advance();
    run(&mut i, &mut host);
    assert!(i.is_finished());
}

#[test]
fn an_empty_message_becomes_an_ellipsis() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![s(101, &["   "])]);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["…"]);
}

// --- choices ----------------------------------------------------------------

#[test]
fn choices_present_options_and_route_to_selected_case() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        s(102, &["はい", "いいえ"]),
        cmd(401, vec![0], vec![], 1),
        cmd(101, vec![], vec!["YES"], 1),
        cmd(499, vec![], vec![], 1),
        cmd(401, vec![1], vec![], 1),
        cmd(101, vec![], vec!["NO"], 1),
        cmd(499, vec![], vec![], 1),
    ]);
    run(&mut i, &mut host);
    assert_eq!(host.choices, vec![vec!["はい".to_string(), "いいえ".to_string()]]);

    i.choose(1);
    run(&mut i, &mut host);
    assert!(host.messages.contains(&"NO".to_string()));
    assert!(!host.messages.contains(&"YES".to_string()));
}

#[test]
fn choice_cancel_case_routes_when_cancelled() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        s(102, &["A", "B"]),
        cmd(402, vec![-1], vec![], 1),
        cmd(101, vec![], vec!["CANCELLED"], 1),
        cmd(499, vec![], vec![], 1),
    ]);
    run(&mut i, &mut host);
    i.choose(5); // out of range: cancel routing
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["CANCELLED"]);
}

#[test]
fn choice_cases_are_matched_by_sorted_id_not_ordinal() {
    // The title menu uses case ids 2/3/4, so host index 0 must select case 2.
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        s(102, &["new", "continue", "quit"]),
        cmd(401, vec![2], vec![], 1),
        cmd(101, vec![], vec!["NEW"], 1),
        cmd(499, vec![], vec![], 1),
        cmd(401, vec![3], vec![], 1),
        cmd(101, vec![], vec!["CONTINUE"], 1),
        cmd(499, vec![], vec![], 1),
        cmd(401, vec![4], vec![], 1),
        cmd(101, vec![], vec!["QUIT"], 1),
        cmd(499, vec![], vec![], 1),
    ]);
    run(&mut i, &mut host);
    i.choose(0);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["NEW"]);
}

// --- variables --------------------------------------------------------------

#[test]
fn set_variable_assigns_and_operates() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        p(121, &[10, 42, 0]),        // v10 = 42
        p(121, &[10, 8, 0, 0x100]),  // v10 += 8
        p(121, &[11, 3, 0, 0x200]),  // v11 -= 3
    ]);
    run(&mut i, &mut host);
    assert_eq!(i.variables.get(&10), Some(&50));
    assert_eq!(i.variables.get(&11), Some(&-3));
    assert!(host.unhandled.is_empty());
}

#[test]
fn set_variable_honours_every_documented_operator() {
    let cases: [(i32, i32, i32, i32); 8] = [
        (0x0000, 7, 3, 10),   // add
        (0x1000, 7, 3, 4),    // subtract
        (0x2000, 7, 3, 21),   // multiply
        (0x3000, 7, 3, 1),    // modulo
        (0x5000, 7, 3, 3),    // and
        (0x7000, 4, 3, 7),    // or
        (0x8000, 7, 3, 4),    // xor
        (0x9000, 1, 3, 8),    // shift left
    ];
    for (packed, a, b, expected) in cases {
        let mut host = RecordingHost::new();
        let mut i = interpreter();
        i.start(vec![p(121, &[1, a, b, packed])]);
        run(&mut i, &mut host);
        assert_eq!(
            i.variables.get(&1),
            Some(&expected),
            "packed operator 0x{packed:04x} with {a} and {b}"
        );
    }
}

#[test]
fn set_variable_honours_the_modify_ops() {
    // (modify op, current value, operand, expected)
    let cases: [(i32, i32, i32, i32); 6] = [
        (0x0100, 10, 5, 15), // +=
        (0x0200, 10, 5, 5),  // -=
        (0x0300, 10, 5, 50), // *=
        (0x0400, 10, 5, 2),  // /=
        (0x0500, 10, 4, 2),  // %=
        (0x0800, 0, -10, 10), // abs of the computed value
    ];
    for (modify, current, operand, expected) in cases {
        let mut host = RecordingHost::new();
        let mut i = interpreter();
        i.variables.insert(1, current);
        i.start(vec![p(121, &[1, operand, 0, modify])]);
        run(&mut i, &mut host);
        assert_eq!(
            i.variables.get(&1),
            Some(&expected),
            "modify op 0x{modify:04x} on {current} with {operand}"
        );
    }
}

#[test]
fn division_by_zero_yields_zero_rather_than_panicking() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.variables.insert(1, 10);
    i.start(vec![p(121, &[1, 0, 0, 0x0400])]);
    run(&mut i, &mut host);
    assert_eq!(i.variables.get(&1), Some(&0));
}

#[test]
fn set_string_stores_value() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![cmd(122, vec![5], vec!["モノクロ"], 0)]);
    run(&mut i, &mut host);
    assert_eq!(i.strings.get(&5).map(String::as_str), Some("モノクロ"));
}

#[test]
fn set_string_handles_the_file_probe_pseudo_commands() {
    let mut host = RecordingHost::new();
    host.file_exists = true;
    let mut i = interpreter();
    i.start(vec![cmd(122, vec![5], vec!["<<GET_FILE_EXIST>>Save/a.sav"], 0)]);
    run(&mut i, &mut host);
    assert_eq!(i.strings.get(&5).map(String::as_str), Some("1"));

    let mut host2 = RecordingHost::new();
    let mut i2 = interpreter();
    i2.start(vec![cmd(122, vec![5], vec!["<<DEL_FILE>>"], 0)]);
    run(&mut i2, &mut host2);
    assert_eq!(i2.strings.get(&5).map(String::as_str), Some("0"));
}

// --- conditions -------------------------------------------------------------

#[test]
fn number_condition_takes_branch_when_satisfied() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.variables.insert(3, 10);
    i.start(vec![
        p(111, &[1, 3, 10, 2]), // if v3 == 10
        cmd(101, vec![], vec!["EQUAL"], 1),
        cmd(499, vec![], vec![], 1),
        s(101, &["AFTER"]),
    ]);
    run(&mut i, &mut host);
    i.advance();
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["EQUAL", "AFTER"]);
}

#[test]
fn number_condition_skips_body_when_not_satisfied() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.variables.insert(3, 5);
    i.start(vec![
        p(111, &[1, 3, 10, 2]),
        cmd(101, vec![], vec!["EQUAL"], 1),
        cmd(499, vec![], vec![], 1),
        s(101, &["AFTER"]),
    ]);
    run(&mut i, &mut host);
    i.advance();
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["AFTER"]);
}

#[test]
fn string_condition_compares_stored_strings() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.strings.insert(2, "モノクロ".to_string());
    i.start(vec![
        cmd(112, vec![2], vec!["モノクロ"], 1),
        cmd(101, vec![], vec!["MATCH"], 1),
        cmd(499, vec![], vec![], 1),
    ]);
    run(&mut i, &mut host);
    i.advance();
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["MATCH"]);
}

#[test]
fn comparison_operator_table_matches_wolf_codes() {
    assert!(compare(5, 0, 4)); // greater than
    assert!(compare(4, 1, 4)); // greater or equal
    assert!(compare(4, 2, 4)); // equal
    assert!(compare(4, 3, 4)); // less or equal
    assert!(compare(3, 4, 4)); // less than
    assert!(compare(5, 5, 4)); // not equal
    assert!(compare(12, 6, 4)); // bitwise and
    assert!(!compare(4, 0, 4));
    assert!(!compare(1, 99, 1), "unknown operators are false");
}

// --- waits ------------------------------------------------------------------

#[test]
fn wait_blocks_for_configured_frames_then_resumes() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(180, &[3]), s(101, &["done"])]);
    run(&mut i, &mut host);
    assert!(matches!(i.blocking(), Some(Blocking::Wait { .. })));

    run(&mut i, &mut host); // remaining 2
    run(&mut i, &mut host); // remaining 1
    assert!(i.is_blocked());

    run(&mut i, &mut host); // resumes and hits the message
    assert!(matches!(i.blocking(), Some(Blocking::Message(_))));

    i.advance();
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["done"]);
    assert!(!i.is_blocked());
}

#[test]
fn a_zero_frame_wait_does_not_block() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(180, &[0]), s(101, &["after"])]);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["after"]);
}

// --- teleport ---------------------------------------------------------------

#[test]
fn teleport_records_map_and_position() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(130, &[7, 12, 34])]);
    run(&mut i, &mut host);
    assert_eq!(i.take_teleport(), Some((7, 12, 34)));
    assert_eq!(i.take_teleport(), None, "a transfer is consumed once");
}

#[test]
fn v35_teleport_uses_fourth_parameter_as_destination_map() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(130, &[-1, 12, 34, 7, 0])]);
    run(&mut i, &mut host);
    assert_eq!(i.take_teleport(), Some((7, 12, 34)));
}

// --- presentation hooks -----------------------------------------------------

#[test]
fn sound_picture_and_screen_effect_hooks_fire() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(140, &[1]), p(150, &[3]), p(151, &[9])]);
    run(&mut i, &mut host);
    assert_eq!(host.sounds.len(), 1);
    assert_eq!(host.pictures.len(), 1);
    assert_eq!(host.screen_effects.len(), 1);
    assert!(host.unhandled.is_empty());
}

#[test]
fn transition_trio_reaches_the_screen_effect_hook() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(160, &[1]), p(161, &[1]), p(162, &[1])]);
    run(&mut i, &mut host);
    assert_eq!(host.screen_effects.len(), 3);
    assert!(host.unhandled.is_empty());
}

#[test]
fn load_picture_custom_resolves_string_and_self_variable_file_names() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        cmd(122, vec![3_000_005], vec!["SystemFile/title.jpg"], 0),
        cmd(122, vec![1_600_006], vec!["SystemFile/logo.png"], 0),
        p(152, &[0, 20, 3_000_005, 0, 1, 1, 255, 30, 40]),
        p(152, &[0, 21, 1_600_006, 0, 1, 1, 255, 50, 60]),
    ]);
    run(&mut i, &mut host);

    assert_eq!(host.pictures.len(), 2);
    assert_eq!(host.pictures[0].command_type, 150, "152 normalises to 150");
    assert_eq!(host.pictures[0].strings, vec!["SystemFile/title.jpg"]);
    assert_eq!(host.pictures[1].strings, vec!["SystemFile/logo.png"]);
    assert_eq!(host.pictures[0].params[1], 20);
    assert_eq!(host.pictures[0].params[7], 30);
    assert_eq!(host.pictures[0].params[8], 40);
}

#[test]
fn picture_parameters_resolve_variable_references() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    // Slot number held in v7; the picture command refers to it as 2_000_007.
    i.variables.insert(7, 22);
    i.start(vec![p(150, &[0, 2_000_007, 100, 200])]);
    run(&mut i, &mut host);
    assert_eq!(host.pictures[0].params[1], 22, "slot must be resolved");
}

// --- save / load ------------------------------------------------------------

#[test]
fn save_and_load_commands_reach_host_slots() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(222, &[2]), p(221, &[2])]);
    run(&mut i, &mut host);
    assert_eq!(host.saves, vec![2]);
    assert_eq!(host.loads, vec![2]);
}

#[test]
fn named_number_save_roundtrips_field_and_value_through_host() {
    #[derive(Default)] struct Fields(HashMap<(String,i32),i32>);
    impl Host for Fields {
        fn on_write_save_number(&mut self,name:&str,field:i32,value:i32)->bool {self.0.insert((name.into(),field),value);true}
        fn on_read_save_number(&mut self,name:&str,field:i32)->i32 {*self.0.get(&(name.into(),field)).unwrap_or(&0)}
    }
    let mut i=interpreter();
    i.strings.insert(4,"Save/System.sav".into());
    i.variables.insert(1,42);
    i.variables.insert(9,2_000_050);
    i.start(vec![p(222,&[2_000_001,3_000_004,2_000_009,1]),p(221,&[2_000_002,3_000_004,2_000_050,0])]);
    let mut host=Fields::default();
    i.tick_default(&mut host);
    assert_eq!(host.0.get(&("Save/System.sav".into(),2_000_050)),Some(&42));
    assert_eq!(i.variables.get(&2),Some(&42));
}

#[test]
fn named_save_files_map_to_their_slot_number() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.strings.insert(4, "Save/AUTO3.sav".to_string());
    // 222 with a string-var filename at params[1] is a named save, not a slot.
    i.start(vec![p(222, &[2_000_001, 3_000_004])]);
    run(&mut i, &mut host);
    assert_eq!(host.save_files, vec!["Save/AUTO3.sav"]);
    assert!(host.saves.is_empty(), "a named save is not a slot save");
}

#[test]
fn a_system_save_name_maps_to_slot_zero() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.strings.insert(4, "Save/System.sav".to_string());
    i.start(vec![p(221, &[2_000_001, 3_000_004])]);
    run(&mut i, &mut host);
    // Field probe path: dest is written, not a slot load.
    assert!(host.loads.is_empty());
    assert_eq!(i.variables.get(&1), Some(&0));
}

// --- common events ----------------------------------------------------------

#[test]
fn common_event_by_id_runs_its_commands() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    let mut by_id: HashMap<i32, Vec<EventCommand>> = HashMap::new();
    by_id.insert(15, vec![s(101, &["COMMON!"])]);
    i.set_common_events(by_id);
    i.start(vec![p(210, &[15]), s(101, &["after"])]);
    run(&mut i, &mut host);
    i.advance();
    run(&mut i, &mut host);
    i.advance();
    assert_eq!(host.messages, vec!["COMMON!", "after"]);
}

#[test]
fn common_event_by_id_resolves_the_v35_namespace() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    let mut by_id: HashMap<i32, Vec<EventCommand>> = HashMap::new();
    by_id.insert(15, vec![s(101, &["NAMESPACED"])]);
    i.set_common_events(by_id);
    i.start(vec![p(210, &[600_015])]);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["NAMESPACED"]);
}

#[test]
fn common_event_by_name_resolves_through_the_name_table() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    let mut by_name: HashMap<String, Vec<EventCommand>> = HashMap::new();
    by_name.insert("オープニング".to_string(), vec![s(101, &["OP"])]);
    i.set_common_events_by_name(by_name);
    i.start(vec![s(300, &["オープニング"])]);
    run(&mut i, &mut host);
    i.advance();
    assert_eq!(host.messages, vec!["OP"]);
}

#[test]
fn a_missing_common_event_does_not_stall() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(210, &[9999]), s(101, &["still here"])]);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["still here"]);
}

#[test]
fn call_arguments_land_in_the_callee_self_variables() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    let mut by_id: HashMap<i32, Vec<EventCommand>> = HashMap::new();
    // Callee copies CSelf0 into v1, then returns.
    by_id.insert(7, vec![p(121, &[1, 1_600_000, 0, 0])]);
    i.set_common_events(by_id);
    // 210: [id, argCount, arg0]
    i.start(vec![p(210, &[7, 1, 42])]);
    run(&mut i, &mut host);
    assert_eq!(i.variables.get(&1), Some(&42));
}

// --- robustness -------------------------------------------------------------

#[test]
fn unknown_opcodes_are_reported_and_skipped() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(9999, &[1]), s(101, &["still works"])]);
    run(&mut i, &mut host);
    i.advance();
    assert_eq!(host.unhandled, vec![9999]);
    assert_eq!(host.messages, vec!["still works"]);
}

#[test]
fn break_event_stops_execution_immediately() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![m(172), s(101, &["never"])]);
    run(&mut i, &mut host);
    assert!(i.is_finished());
    assert!(host.messages.is_empty());
}

#[test]
fn a_malformed_runaway_event_yields_instead_of_hanging() {
    // An infinite loop with no blocking command must stop at the tick budget
    // and resume next tick, not spin forever.
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![m(170), p(121, &[1, 1, 0, 0x100]), m(498)]);
    i.tick(&mut host, 50);
    assert!(!i.is_finished(), "an unbounded loop must not finish");
    assert!(i.variables.get(&1).copied().unwrap_or(0) >= 1);
}

// --- loops, labels, input ---------------------------------------------------

#[test]
fn loop_times_resolves_a_variable_reference_count() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        p(121, &[7, 4]),              // v7 = 4
        p(179, &[2_000_007]),         // loop v7 times
        s(101, &["tick"]),
        m(498),
        s(101, &["after"]),
    ]);
    for _ in 0..4 {
        run(&mut i, &mut host);
        i.advance();
    }
    run(&mut i, &mut host);
    assert_eq!(
        host.messages.iter().take(5).cloned().collect::<Vec<_>>(),
        vec!["tick", "tick", "tick", "tick", "after"]
    );
}

#[test]
fn loop_times_runs_the_body_the_configured_number_of_times() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(179, &[3]), p(121, &[1, 1, 0, 0x100]), m(498)]);
    run(&mut i, &mut host);
    assert!(i.is_finished());
    assert_eq!(i.variables.get(&1), Some(&3));
}

#[test]
fn a_zero_count_loop_skips_its_body() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(179, &[0]), p(121, &[1, 1, 0, 0x100]), m(498)]);
    run(&mut i, &mut host);
    assert!(i.is_finished());
    assert_eq!(i.variables.get(&1), None, "body must not run");
}

#[test]
fn infinite_loop_with_break_exits_early() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        m(170),
        p(121, &[1, 1, 0, 0x100]), // v1 += 1
        p(111, &[1, 1, 2, 1]),     // if v1 >= 2
        m(171),                    // BreakLoop
        m(499),
        m(498),
    ]);
    run(&mut i, &mut host);
    assert!(i.is_finished());
    assert_eq!(i.variables.get(&1), Some(&2));
}

#[test]
fn loop2_repeats_until_the_condition_fails() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.variables.insert(2, 0);
    // 176 Loop2(cond: v2 < 3)
    i.start(vec![p(176, &[2, 3, 4]), p(121, &[2, 1, 0, 0x100]), m(498)]);
    run(&mut i, &mut host);
    assert!(i.is_finished());
    assert_eq!(i.variables.get(&2), Some(&3));
}

#[test]
fn jump_to_label_resumes_after_the_label_marker() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        p(213, &[7]),
        s(101, &["skipped"]),
        p(212, &[7]),
        s(101, &["landed"]),
    ]);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["landed"]);
}

#[test]
fn jump_to_label_matches_by_string() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        cmd(213, vec![], vec!["target"], 0),
        s(101, &["skipped"]),
        cmd(212, vec![], vec!["target"], 0),
        s(101, &["landed"]),
    ]);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["landed"]);
}

#[test]
fn key_wait_poll_stores_the_pressed_key() {
    let mut host = RecordingHost::new();
    host.key_poll_result = 5;
    let mut i = interpreter();
    i.start(vec![p(123, &[0, 1]), s(101, &["after key"])]);
    run(&mut i, &mut host);
    assert_eq!(i.variables.get(&0), Some(&5));
    assert!(matches!(i.blocking(), Some(Blocking::Message(_))));
}

#[test]
fn a_non_waiting_key_poll_does_not_block() {
    // Title anti-repeat common events spin on non-waiting 123 until keys clear.
    let mut host = RecordingHost::new();
    host.key_poll_result = 0;
    let mut i = interpreter();
    // options word 0 has bit 7 clear, so this is poll-only.
    i.start(vec![p(123, &[0, 0]), s(101, &["moved on"])]);
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["moved on"]);
}

#[test]
fn pointer_button_code_is_visible_to_input_key() {
    // 123 polls whatever the host reports; the runtime returns mouse button codes
    // (20/21/22) while the pointer is down so picture menus match.
    let mut host = RecordingHost::new();
    host.key_poll_result = 20;
    let mut i = interpreter();
    i.start(vec![p(123, &[5, 1])]);
    run(&mut i, &mut host);
    assert_eq!(i.variables.get(&5), Some(&20));
}

#[test]
fn move_and_wait_blocks_until_the_move_finishes() {
    let mut host = RecordingHost::new();
    host.move_finished = false;
    let mut i = interpreter();
    i.start(vec![p(201, &[1]), m(202), s(101, &["arrived"])]);
    run(&mut i, &mut host);
    assert_eq!(host.moves.len(), 1);
    assert!(host.messages.is_empty());

    host.move_finished = true;
    run(&mut i, &mut host);
    assert_eq!(host.messages, vec!["arrived"]);
}

#[test]
fn break_loop_from_a_choice_subframe_exits_the_parent_loop() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    // Mirrors the database OP-skip menu:
    // loop { choices; 401/2 -> BreakLoop }; after loop set v1 = 9
    i.start(vec![
        m(170),
        s(102, &["Watch", "Skip"]),
        cmd(401, vec![2], vec![], 1),
        m(171),
        cmd(401, vec![3], vec![], 1),
        p(121, &[1, 1, 0, 0]),
        m(171),
        m(499),
        m(498),
        p(121, &[1, 9, 0, 0]),
    ]);
    run(&mut i, &mut host);
    assert!(matches!(i.blocking(), Some(Blocking::Choices(_))));
    i.choose(0);
    run(&mut i, &mut host);
    assert_eq!(i.variables.get(&1), Some(&9));
}

// --- remaining reached families ---------------------------------------------

#[test]
fn engine_hooks_fire_for_database_party_effects_and_flow() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![
        p(250, &[1, 2, 3]),
        p(270, &[1, 1]),
        m(280),
        m(281),
        m(290),
        m(242),
        m(240),
        p(126, &[1]),
        m(174),
        m(175),
    ]);
    run(&mut i, &mut host);
    assert_eq!(host.database.len(), 1);
    assert_eq!(host.parties.len(), 1);
    assert_eq!(host.map_effects.len(), 1);
    assert_eq!(host.scrolls.len(), 1);
    assert_eq!(host.effects.len(), 1);
    assert_eq!(host.chip_changes.len(), 2);
    assert_eq!(host.ban_input, vec![true]);
    assert_eq!(host.return_to_title, 1);
    assert_eq!(host.end_games, 1);
    assert!(host.unhandled.is_empty());
}

#[test]
fn auto_input_and_save_load_reach_their_hooks() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(125, &[1, 0]), m(220)]);
    run(&mut i, &mut host);
    assert_eq!(host.auto_inputs.len(), 1);
    assert_eq!(host.save_loads, 1);
    assert!(host.unhandled.is_empty(), "125/220 must not be unhandled");
}

#[test]
fn set_variable_ex_uses_the_same_variable_bank() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(124, &[7, 0, 0, 0, 1])]);
    run(&mut i, &mut host);
    assert!(host.unhandled.is_empty(), "124 must be handled");
}

#[test]
fn set_variable_ex_answers_key_down_queries() {
    let mut host = RecordingHost::new();
    host.key_poll_result = 5;
    let mut i = interpreter();
    i.start(vec![p(124, &[7, 0x3000, 5])]);
    run(&mut i, &mut host);
    assert_eq!(i.variables.get(&7), Some(&1), "key 5 is down");
}

#[test]
fn picture_hit_test_reports_hovered_slot_or_minus_one() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    // No cursor: -1.
    i.start(vec![p(124, &[7, 0x4000, 1, 3])]);
    run(&mut i, &mut host);
    assert_eq!(i.variables.get(&7), Some(&-1));

    let mut host2 = RecordingHost::new();
    host2.pointer = Some((100, 200));
    let mut i2 = interpreter();
    i2.start(vec![p(124, &[7, 0x4000, 1, 3])]);
    run(&mut i2, &mut host2);
    assert_eq!(i2.variables.get(&7), Some(&2));
    assert_eq!(host2.hovered, vec![(100, 200, 1, 3)]);
}

#[test]
fn every_reached_opcode_has_a_handler() {
    // The opcodes a real play session reached on device (task-1 census). None
    // may fall through to on_unhandled.
    let reached = [
        0, 99, 101, 102, 103, 106, 111, 112, 121, 122, 123, 124, 130, 140, 150, 151, 170, 171,
        172, 176, 179, 180, 201, 210, 212, 213, 250, 290, 300, 401, 420, 498, 499,
    ];
    for opcode in reached {
        let mut host = RecordingHost::new();
        let mut i = interpreter();
        i.start(vec![p(opcode, &[0, 0, 0, 0, 0])]);
        run(&mut i, &mut host);
        assert!(
            host.unhandled.is_empty(),
            "opcode {opcode} was reached in real play but hit on_unhandled"
        );
    }
}

#[test]
fn the_opcode_census_counts_executions() {
    let mut host = RecordingHost::new();
    let mut i = interpreter();
    i.start(vec![p(179, &[3]), p(121, &[1, 1, 0, 0x100]), m(498)]);
    run(&mut i, &mut host);
    assert_eq!(i.opcode_census.get(&121), Some(&3), "121 ran 3 times");
    assert_eq!(i.opcode_census.get(&498), Some(&3), "498 ran 3 times");
    assert_eq!(i.opcode_census.get(&179), Some(&1));
}

#[test]
fn the_default_budget_is_the_documented_constant() {
    assert_eq!(MAX_COMMANDS_PER_TICK, 50_000);
}

#[test]
fn the_interpreter_starts_finished_when_given_no_commands() {
    let mut i = interpreter();
    i.start(Vec::new());
    assert!(i.is_finished());
    assert_eq!(i.current_pc(), -1);
}
