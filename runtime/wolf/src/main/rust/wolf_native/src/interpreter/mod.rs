//! WOLF event interpreter.
//!
//! Port of `runtime/wolf/src/main/kotlin/.../WolfInterpreter.kt`. Executes event
//! command lists against a small machine state (numeric and string banks plus
//! presentation hooks). Blocking states — messages, choices, waits — suspend
//! execution until the host resolves them, mirroring the original engine.
//!
//! Opcode semantics come from IDA decompilation of `GamePro.exe`; the mapping of
//! dispatcher targets to opcodes is recorded in `docs/wolf-ida-vm-reference.md`.
//!
//! Unimplemented opcodes are reported through [`Host::on_unhandled`] and skipped,
//! so a real game degrades visibly instead of stalling.

use std::collections::HashMap;

use crate::wolfformat::event_command::EventCommand;

pub mod corpus;
pub mod database;
pub mod refs;
pub mod text;

#[cfg(test)]
mod tests;

use refs::{
    decode_string_ref, decode_variable_ref, is_self_string_key, is_self_var_key, is_variable_ref,
};
use text::{interpolate, Strings, Variables};

/// Ceiling for the foreground event; large New Game init loops need headroom.
pub const MAX_COMMANDS_PER_TICK: usize = 50_000;
/// Parallel pages must not stall the frame; they resume next tick.
pub const MAX_PARALLEL_COMMANDS_PER_TICK: usize = 4_000;
/// Deepest common-event call chain.
const MAX_FRAMES: usize = 64;

/// Commands that open a skippable body closed by 499.
const BRANCH_OPENERS: [i32; 9] = [111, 112, 170, 176, 179, 401, 402, 420, 421];
/// Case headers of a condition/choice branch.
const CASE_HEADERS: [i32; 4] = [401, 402, 420, 421];
/// Loop openers.
const LOOP_OPENERS: [i32; 3] = [170, 176, 179];

fn contains(list: &[i32], value: i32) -> bool {
    list.contains(&value)
}

/// A blocking state the host must resolve.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Blocking {
    Message(String),
    Choices(Vec<String>),
    /// Remaining frames and an optional wall-clock deadline in milliseconds.
    Wait { remaining_ticks: i32, deadline_ms: u64 },
    KeyWait(EventCommand),
    MoveWait(EventCommand),
}

/// Presentation and environment hooks. Every method has a default so a test host
/// only implements what it observes.
pub trait Host {
    fn on_message(&mut self, _text: &str) {}
    fn on_choices(&mut self, _options: &[String]) {}
    /// Expand escape tags before presentation.
    fn expand_text(&mut self, text: &str) -> String {
        text.to_string()
    }
    fn on_sound(&mut self, _command: &EventCommand) {}
    fn on_picture(&mut self, _command: &EventCommand) {}
    fn on_screen_effect(&mut self, _command: &EventCommand) {}
    fn on_teleport(&mut self, _map_id: i32, _tile_x: i32, _tile_y: i32) {}
    fn on_save(&mut self, _slot: i32) -> bool {
        true
    }
    fn on_load(&mut self, _slot: i32) -> bool {
        false
    }
    /// 220 SaveLoad: opens the host save/load selection; the script continues.
    fn on_save_load(&mut self) -> bool {
        true
    }
    fn on_file_exists(&mut self, _name: &str) -> bool {
        false
    }
    fn on_save_file(&mut self, _name: &str) -> bool {
        false
    }
    /// Currently-pressed WOLF key id, 0 = none.
    ///
    /// WOLF ids: 1 up, 2 down, 3 left, 4 right, 5 decide, 6 cancel, 7 shift.
    fn on_key_poll(&mut self) -> i32 {
        0
    }
    /// Clears a one-shot key latch after a waiting InputKey accepts it.
    fn on_key_consumed(&mut self) {}
    fn on_auto_input(&mut self, _command: &EventCommand) {}
    fn on_ban_input(&mut self, _banned: bool) {}
    fn on_move(&mut self, _command: &EventCommand) {}
    fn on_move_finished(&mut self) -> bool {
        true
    }
    fn on_database(&mut self, _command: &EventCommand) {}
    fn on_party(&mut self, _command: &EventCommand) {}
    fn on_map_effect(&mut self, _command: &EventCommand) {}
    fn on_scroll(&mut self, _command: &EventCommand) {}
    fn on_effect(&mut self, _command: &EventCommand) {}
    fn on_chip_change(&mut self, _command: &EventCommand) {}
    fn on_return_to_title(&mut self) {}
    fn on_end_game(&mut self) {}
    /// Cursor position in virtual pixel space, or `None` when no pointer exists.
    fn on_pointer_position(&mut self) -> Option<(i32, i32)> {
        None
    }
    /// Index (relative to `first_slot`) of the picture under the cursor, or -1.
    fn on_picture_at(&mut self, _x: i32, _y: i32, _first_slot: i32, _count: i32) -> i32 {
        -1
    }
    /// Called for opcodes this interpreter does not implement yet.
    fn on_unhandled(&mut self, _command: &EventCommand) {}
}

/// One command list being executed.
#[derive(Debug, Clone)]
struct Frame {
    commands: Vec<EventCommand>,
    pc: usize,
    /// Map-event pages and extracted case bodies: BreakEvent ends the whole run.
    /// Called common events break back to their caller instead.
    root: bool,
    saved_self: Option<HashMap<i32, i32>>,
    saved_self_strings: Option<HashMap<i32, String>>,
}

/// Active loop: where to jump back to and how many iterations remain.
#[derive(Debug, Clone)]
struct LoopMarker {
    /// Index into `frames` rather than a pointer, so frames stay comparable.
    frame_index: usize,
    start_pc: usize,
    /// `None` means unbounded (170) or condition-driven (176).
    remaining: Option<i32>,
    /// Loop2 condition params, re-evaluated at the 498 marker.
    condition_params: Option<Vec<i32>>,
}

/// The event interpreter.
pub struct Interpreter {
    pub variables: Variables,
    pub strings: Strings,
    /// Live census of executed opcodes: opcode -> count.
    pub opcode_census: HashMap<i32, i32>,
    common_events: HashMap<i32, Vec<EventCommand>>,
    common_events_by_name: HashMap<String, Vec<EventCommand>>,
    frames: Vec<Frame>,
    loops: Vec<LoopMarker>,
    blocking: Option<Blocking>,
    finished: bool,
    fps: i32,
    command_budget: usize,
    /// Result of the most recent 102 choice, for inline 401/402/420/421 cases.
    last_choice_index: i32,
    last_choice_cancel: bool,
    last_branch_was_choice: bool,
    last_condition_satisfied: bool,
    /// Transfer requested by the running event, applied by the engine later.
    pending_teleport: Option<(i32, i32, i32)>,
    /// Database banks and XY arrays. Absent until the engine loads them, in
    /// which case database commands are reported to the host instead.
    database: Option<database::Database>,
}

impl Interpreter {
    pub fn new(fps: i32) -> Self {
        Self {
            variables: HashMap::new(),
            strings: HashMap::new(),
            opcode_census: HashMap::new(),
            common_events: HashMap::new(),
            common_events_by_name: HashMap::new(),
            frames: Vec::new(),
            loops: Vec::new(),
            blocking: None,
            finished: true,
            fps: fps.max(1),
            command_budget: MAX_COMMANDS_PER_TICK,
            last_choice_index: -1,
            last_choice_cancel: false,
            last_branch_was_choice: false,
            last_condition_satisfied: false,
            pending_teleport: None,
            database: None,
        }
    }

    /// Installs the database model so `250/251/252/255/256/257` execute here
    /// rather than being handed to the host.
    pub fn set_database(&mut self, database: database::Database) {
        self.database = Some(database);
    }

    pub fn database(&self) -> Option<&database::Database> {
        self.database.as_ref()
    }

    pub fn set_common_events(&mut self, by_id: HashMap<i32, Vec<EventCommand>>) {
        self.common_events = by_id;
    }

    pub fn set_common_events_by_name(&mut self, by_name: HashMap<String, Vec<EventCommand>>) {
        self.common_events_by_name = by_name;
    }

    /// Starts executing an event's command list, resetting any prior run.
    pub fn start(&mut self, commands: Vec<EventCommand>) {
        self.frames.clear();
        self.loops.clear();
        self.blocking = None;
        self.finished = commands.is_empty();
        if !commands.is_empty() {
            self.frames.push(Frame {
                commands,
                pc: 0,
                root: true,
                saved_self: None,
                saved_self_strings: None,
            });
        }
    }

    /// Advances one logical frame: runs commands until a blocking state is
    /// reached, decrements active waits, and finishes when all frames complete.
    pub fn tick(&mut self, host: &mut dyn Host, max_commands: usize) {
        self.command_budget = max_commands.max(1);
        if self.finished {
            return;
        }
        if let Some(current) = self.blocking.clone() {
            match current {
                Blocking::Wait {
                    remaining_ticks,
                    deadline_ms,
                } => {
                    // Prefer wall-clock so a slow render loop cannot stretch a
                    // 30-frame fade into minutes; the tick countdown keeps unit
                    // tests deterministic without sleeping.
                    let now_ms = now_ms();
                    let expired_by_clock = deadline_ms != 0 && now_ms >= deadline_ms;
                    if expired_by_clock {
                        self.blocking = None;
                    } else {
                        let remaining = remaining_ticks - 1;
                        if remaining <= 0 {
                            self.blocking = None;
                        } else {
                            self.blocking = Some(Blocking::Wait {
                                remaining_ticks: remaining,
                                deadline_ms,
                            });
                            return;
                        }
                    }
                }
                Blocking::KeyWait(command) => {
                    let key = host.on_key_poll();
                    if key != 0 {
                        if let Some(target) = command.params.first() {
                            let decoded = decode_variable_ref(*target);
                            self.variables.insert(decoded, key);
                        }
                        host.on_key_consumed();
                        self.blocking = None;
                    } else {
                        return;
                    }
                }
                Blocking::MoveWait(_) => {
                    if host.on_move_finished() {
                        self.blocking = None;
                    } else {
                        return;
                    }
                }
                Blocking::Message(_) | Blocking::Choices(_) => return,
            }
        }
        if self.blocking.is_some() {
            return;
        }
        self.run_until_blocked(host);
    }

    /// Convenience wrapper using the default per-tick budget.
    pub fn tick_default(&mut self, host: &mut dyn Host) {
        self.tick(host, MAX_COMMANDS_PER_TICK);
    }

    /// Dismisses the current message window.
    pub fn advance(&mut self) {
        if matches!(self.blocking, Some(Blocking::Message(_))) {
            self.blocking = None;
        }
    }

    /// Selects a choice option (0-based host index); out-of-range cancels.
    pub fn choose(&mut self, index: i32) {
        let choices = match &self.blocking {
            Some(Blocking::Choices(options)) => options.clone(),
            _ => return,
        };
        self.blocking = None;
        self.last_choice_cancel = index < 0 || index as usize >= choices.len();
        self.last_branch_was_choice = true;

        let bodies = self.extract_choice_bodies();
        // Case headers are not always 1..N (title menus use 2/3/4), so map the
        // host's ordinal onto the sorted case ids actually present.
        let mut ordered_ids: Vec<i32> = bodies.cases.keys().copied().collect();
        ordered_ids.sort_unstable();
        let wolf_index = ordered_ids
            .get(index.max(0) as usize)
            .copied()
            .unwrap_or(index + 1);
        self.last_choice_index = wolf_index;

        let body = bodies
            .cases
            .get(&wolf_index)
            .cloned()
            .or_else(|| bodies.cancel.clone())
            .unwrap_or_default();
        if !body.is_empty() {
            self.frames.push(Frame {
                commands: body,
                pc: 0,
                root: false,
                saved_self: None,
                saved_self_strings: None,
            });
        } else if bodies.resumed_from_pc >= 0 {
            if let Some(frame) = self.frames.last_mut() {
                frame.pc = bodies.resumed_from_pc as usize;
            }
        }
    }

    /// True while a message/choice/wait is unresolved.
    pub fn is_blocked(&self) -> bool {
        self.blocking.is_some() || (!self.finished && self.frames.is_empty())
    }

    pub fn current_pc(&self) -> i32 {
        self.frames.last().map(|f| f.pc as i32).unwrap_or(-1)
    }

    pub fn blocking(&self) -> Option<&Blocking> {
        self.blocking.as_ref()
    }

    pub fn is_finished(&self) -> bool {
        self.finished
    }

    fn run_until_blocked(&mut self, host: &mut dyn Host) {
        // Safety valve: a malformed event whose loop never blocks would hang the
        // render loop; cap work per tick and yield to the next tick instead of
        // aborting the event.
        let mut executed = 0usize;
        while !self.finished && self.blocking.is_none() {
            if self.frames.is_empty() {
                self.finished = true;
                return;
            }
            let frame_index = self.frames.len() - 1;
            let next = {
                let frame = &self.frames[frame_index];
                if frame.pc >= frame.commands.len() {
                    None
                } else {
                    Some(frame.commands[frame.pc].clone())
                }
            };
            let Some(command) = next else {
                self.pop_frame();
                continue;
            };
            self.frames[frame_index].pc += 1;
            self.execute(host, &command);
            executed += 1;
            if executed > self.command_budget {
                return;
            }
        }
    }

    fn execute(&mut self, host: &mut dyn Host, command: &EventCommand) {
        *self.opcode_census.entry(command.command_type).or_insert(0) += 1;
        match command.command_type {
            0 | 99 | 103 | 104 => {} // blank line, checkpoint, comment
            105 | 106 | 107 => {}    // force-stop-message, debug message, clear
            101 => self.show_message(host, command),
            102 => self.show_choices(host, command),
            111 => self.evaluate_number_condition(command),
            112 => self.evaluate_string_condition(command),
            121 => self.set_variable(command),
            122 => self.set_string(host, command),
            123 => self.input_key(host, command),
            124 => self.set_variable_ex(host, command),
            125 => host.on_auto_input(command),
            126 => host.on_ban_input(command.params.first().copied().unwrap_or(0) != 0),
            130 => self.teleport(host, command),
            140 => host.on_sound(command),
            150 | 152 => self.emit_picture(host, command),
            151 | 160 | 161 | 162 => host.on_screen_effect(command),
            170 => self.begin_loop(command, None),
            171 => self.break_loop(),
            172 | 173 => self.break_event(command.command_type),
            174 => host.on_return_to_title(),
            175 => host.on_end_game(),
            176 => self.begin_loop2(command),
            177 | 178 => {} // StopNonPic / ResumeNonPic
            179 => {
                let count = self.loop_count(command);
                self.begin_loop(command, Some(count))
            }
            180 => self.begin_wait(command),
            201 => host.on_move(command),
            202 => self.blocking = Some(Blocking::MoveWait(command.clone())),
            212 => {} // label marker, resolved by 213 scans
            213 => self.jump_to_label(command),
            210 | 211 => self.call_common_event_by_id(command),
            220 => {
                host.on_save_load();
            }
            221 => self.load_or_probe(host, command),
            222 => self.save_or_marker(host, command),
            230 | 231 => {} // move-during-event flag: engine-level default
            240 | 241 | 242 => host.on_chip_change(command),
            250 | 251 | 252 | 255 | 256 | 257 => {
                // With a database loaded these execute here; without one they go
                // to the host, which is how the Kotlin engine routes them.
                if let Some(db) = self.database.as_mut() {
                    let mut access = InterpreterVars {
                        variables: &mut self.variables,
                        strings: &mut self.strings,
                    };
                    db.execute(command, &mut access);
                } else {
                    host.on_database(command);
                }
            }
            270 => host.on_party(command),
            280 => {
                let resolved = self.resolve_command_refs(command);
                host.on_map_effect(&resolved)
            }
            281 => {
                let resolved = self.resolve_command_refs(command);
                host.on_scroll(&resolved)
            }
            290 => {
                let resolved = self.resolve_command_refs(command);
                host.on_effect(&resolved)
            }
            300 => self.call_common_event_by_name(command),
            401 | 402 => {
                let enter = if self.last_branch_was_choice {
                    command.params.first().copied().unwrap_or(-1) == self.last_choice_index
                        && !self.last_choice_cancel
                } else {
                    self.last_condition_satisfied
                };
                if !enter {
                    self.skip_one_case();
                }
            }
            420 | 421 => {
                let enter = if self.last_branch_was_choice {
                    self.last_choice_cancel
                } else {
                    !self.last_condition_satisfied
                };
                if !enter {
                    self.skip_one_case();
                }
            }
            498 => self.loop_end(),
            499 => {}   // branch end: natural flow
            1000 => {}  // ProFeature
            _ => host.on_unhandled(command),
        }
    }

    // --- messages, choices, input -------------------------------------------

    fn show_message(&mut self, host: &mut dyn Host, command: &EventCommand) {
        let joined = command.strings.join("\n");
        let expanded = host.expand_text(&joined);
        let text = if expanded.trim().is_empty() {
            "…".to_string()
        } else {
            expanded
        };
        self.blocking = Some(Blocking::Message(text.clone()));
        host.on_message(&text);
    }

    fn show_choices(&mut self, host: &mut dyn Host, command: &EventCommand) {
        let raw: Vec<String> = if command.strings.is_empty() {
            vec!["…".to_string()]
        } else {
            command.strings.clone()
        };
        let options: Vec<String> = raw.iter().map(|s| host.expand_text(s)).collect();
        self.blocking = Some(Blocking::Choices(options.clone()));
        host.on_choices(&options);
    }

    fn input_key(&mut self, host: &mut dyn Host, command: &EventCommand) {
        // InputKey: params[0] = destination variable; the options word carries
        // wait_for_input in bit 7. Poll-only forms must not block when no key is
        // down — title anti-repeat common events spin on non-waiting 123.
        let key = host.on_key_poll();
        if let Some(target) = command.params.first() {
            let decoded = decode_variable_ref(*target);
            self.variables.insert(decoded, key);
        }
        let waits = input_key_waits(command);
        if waits {
            if key == 0 {
                self.blocking = Some(Blocking::KeyWait(command.clone()));
            } else {
                host.on_key_consumed();
            }
        }
    }

    fn emit_picture(&mut self, host: &mut dyn Host, command: &EventCommand) {
        let mut resolved = self.resolve_command_refs(command);
        resolved.command_type = 150;
        let expanded: Vec<String> = command
            .strings
            .iter()
            .map(|s| interpolate(s, &self.variables, &self.strings))
            .collect();
        resolved.strings = expanded.clone();

        // File-from-string forms keep a CSelf/string id in the raw params; copy
        // that resolved filename into the command before the host applies it.
        if expanded.iter().all(|s| s.trim().is_empty()) {
            let str_ref = command.params.iter().skip(1).find(|p| {
                (1_600_000..=1_699_999).contains(*p) || (3_000_000..=3_000_999).contains(*p)
            });
            let path = str_ref
                .map(|r| self.strings.get(&decode_string_ref(*r)).cloned().unwrap_or_default())
                .unwrap_or_default();
            if !path.trim().is_empty() {
                resolved.strings = vec![path];
            }
        }
        host.on_picture(&resolved);
    }

    // --- variables ----------------------------------------------------------

    /// Resolves a 121 operand: values in variable-reference ranges are refs.
    fn operand_value(&self, raw: i32) -> i32 {
        if is_variable_ref(raw) {
            self.var_value(raw)
        } else {
            raw
        }
    }

    fn var_value(&self, raw: i32) -> i32 {
        self.variables
            .get(&decode_variable_ref(raw))
            .copied()
            .unwrap_or(0)
    }

    fn set_variable(&mut self, command: &EventCommand) {
        // WolfTL SetVariable: arg[3] packs the modify-op for arg[0] in the low
        // byte-pair (0x000 =, 0x100 +=, 0x200 -=, 0x300 *=, 0x400 /=, 0x500 %=,
        // 0x600 floor, 0x700 ceil, 0x800 abs) and the operator between arg[1]
        // and arg[2] in the high nibble (0x0000 +, 0x1000 -, 0x2000 *, 0x3000 %,
        // 0x5000 &, 0x7000 |, 0x8000 ^, 0x9000 <<).
        let p = &command.params;
        let Some(target_raw) = p.first() else { return };
        let target = decode_variable_ref(*target_raw);
        let a = self.operand_value(p.get(1).copied().unwrap_or(0));
        let b = self.operand_value(p.get(2).copied().unwrap_or(0));
        let packed = p.get(3).copied().unwrap_or(0);

        let value = match packed & 0xF000 {
            0x0000 => a.wrapping_add(b),
            0x1000 => a.wrapping_sub(b),
            0x2000 => a.wrapping_mul(b),
            0x3000 => {
                if b != 0 {
                    a.wrapping_rem(b)
                } else {
                    0
                }
            }
            0x5000 => a & b,
            0x7000 => a | b,
            0x8000 => a ^ b,
            0x9000 => {
                if (0..32).contains(&b) {
                    a.wrapping_shl(b as u32)
                } else {
                    a
                }
            }
            _ => a.wrapping_add(b),
        };
        let current = self.variables.get(&target).copied().unwrap_or(0);
        let result = match packed & 0x0F00 {
            0x0000 => value,
            0x0100 => current.wrapping_add(value),
            0x0200 => current.wrapping_sub(value),
            0x0300 => current.wrapping_mul(value),
            0x0400 => {
                if value != 0 {
                    current.wrapping_div(value)
                } else {
                    0
                }
            }
            0x0500 => {
                if value != 0 {
                    current.wrapping_rem(value)
                } else {
                    0
                }
            }
            0x0600 => value, // floor of an integer is itself
            0x0700 => value, // ceil of an integer is itself
            0x0800 => value.wrapping_abs(),
            _ => value,
        };
        self.variables.insert(target, result);
    }

    fn set_variable_ex(&mut self, host: &mut dyn Host, command: &EventCommand) {
        // SetVariableEx shapes seen in real deployments (the 0x3000/0x4000 word
        // picks the sub-query).
        let p = &command.params;
        if p.len() >= 3 && p[1] == 0x3000 {
            let key_code = self.operand_value(p[2]);
            let dest = decode_variable_ref(p[0]);
            let down = if host.on_key_poll() == key_code { 1 } else { 0 };
            self.variables.insert(dest, down);
            return;
        }
        if p.len() >= 3 && p[1] == 0x4000 {
            // WOLF's picture menus hit-test pictures against the mouse cursor;
            // a host with no cursor can never satisfy them.
            let dest = decode_variable_ref(p[0]);
            let cursor = host.on_pointer_position();
            let first_slot = self.operand_value(p[2]);
            let count = if p.len() >= 4 {
                self.operand_value(p[3])
            } else {
                1
            };
            let value = match cursor {
                None => -1,
                Some((x, y)) => host.on_picture_at(x, y, first_slot, count),
            };
            self.variables.insert(dest, value);
            return;
        }
        if p.len() >= 2 {
            let dest = decode_variable_ref(p[0]);
            let value = self.operand_value(p[1]);
            self.variables.insert(dest, value);
        }
    }

    fn set_string(&mut self, host: &mut dyn Host, command: &EventCommand) {
        let Some(target_raw) = command.params.first() else {
            return;
        };
        let target = decode_string_ref(*target_raw);
        let raw = command.strings.first().cloned().unwrap_or_default();
        let expanded = host.expand_text(&raw);
        if let Some(rest) = expanded.strip_prefix("<<GET_FILE_EXIST>>") {
            let file = rest.trim();
            let value = if host.on_file_exists(file) { "1" } else { "0" };
            self.strings.insert(target, value.to_string());
            return;
        }
        if expanded.starts_with("<<DEL_FILE>>") {
            self.strings.insert(target, "0".to_string());
            return;
        }
        self.strings.insert(target, expanded);
    }

    // --- conditions ---------------------------------------------------------

    fn evaluate_number_condition(&mut self, command: &EventCommand) {
        // Real-data layout: params are packed from the leading mode value ([0])
        // plus condition triplets {variableRef, value, operator}; the loop is
        // bounded by the real param count. The first satisfied condition enters
        // its case; otherwise the whole branch is skipped.
        let p = &command.params;
        // Low nibble is the condition count; upper bits are mode flags.
        let want_count = (p.first().copied().unwrap_or(0) & 0x0F).max(0);
        let mut satisfied = false;
        let mut matched_cases = 0i32;
        let mut i = 1usize;
        while matched_cases < want_count && i + 2 < p.len() {
            let key = decode_variable_ref(p[i]);
            let expected = self.operand_value(p[i + 1]);
            let operator_code = p[i + 2];
            let actual = self.variables.get(&key).copied().unwrap_or(0);
            if compare(actual, operator_code, expected) {
                satisfied = true;
                break;
            }
            i += 3;
            matched_cases += 1;
        }
        self.last_condition_satisfied = satisfied;
        self.last_branch_was_choice = false;

        if !satisfied {
            let next_type = self
                .frames
                .last()
                .and_then(|f| f.commands.get(f.pc))
                .map(|c| c.command_type);
            if !next_type.map(|t| contains(&CASE_HEADERS, t)).unwrap_or(false) {
                self.skip_current_branch();
            }
            return;
        }

        // Run ONLY the matched case body as a sub-frame and continue the parent
        // after the branch: sibling case bodies are demarcated by the 401-family
        // headers, so linear flow would fall through into the next case.
        if p.len() <= 4 {
            return;
        }
        let Some(frame) = self.frames.last() else { return };
        let header_pc = frame.pc;
        let mut case_index = 0i32;
        let mut cursor = header_pc;
        let commands = frame.commands.clone();
        while cursor < commands.len() {
            let head = &commands[cursor];
            if !contains(&CASE_HEADERS, head.command_type) {
                break;
            }
            let end_pc = case_body_end(&commands, cursor);
            if case_index == matched_cases {
                let body: Vec<EventCommand> = commands[cursor..end_pc.min(commands.len())].to_vec();
                let resume = branch_end_after(&commands, end_pc);
                if let Some(frame) = self.frames.last_mut() {
                    frame.pc = resume;
                }
                // Case bodies are nested frames; root=true would make 172
                // BreakEvent wipe the whole interpreter.
                if !body.is_empty() {
                    self.frames.push(Frame {
                        commands: body,
                        pc: 0,
                        root: false,
                        saved_self: None,
                        saved_self_strings: None,
                    });
                }
                return;
            }
            case_index += 1;
            cursor = end_pc;
        }
    }

    fn evaluate_string_condition(&mut self, command: &EventCommand) {
        // Documented subset: compares the string bank against a literal; falls
        // through to the else branch otherwise.
        let key = command.params.first().copied().unwrap_or(0);
        let expected = command.strings.first().cloned().unwrap_or_default();
        let actual = self.strings.get(&key).cloned().unwrap_or_default();
        self.last_condition_satisfied = actual == expected;
        self.last_branch_was_choice = false;
        if !self.last_condition_satisfied {
            self.skip_current_branch();
        }
    }

    // --- flow ---------------------------------------------------------------

    fn loop_count(&mut self, command: &EventCommand) -> i32 {
        // LoopTimes operators may reference a variable (>=1M); resolve it.
        // 0 runs the body zero times. Cap runaway counts from unread database
        // slots so New Game init cannot stall.
        let raw = command.params.first().copied().unwrap_or(0);
        self.operand_value(raw).clamp(0, 64)
    }

    fn begin_loop(&mut self, _command: &EventCommand, remaining: Option<i32>) {
        let Some(frame_index) = self.frames.len().checked_sub(1) else {
            return;
        };
        if let Some(r) = remaining {
            if r <= 0 {
                // Zero iterations: skip the body up to the matching loop end.
                let mut pending = 1;
                while self.frames[frame_index].pc < self.frames[frame_index].commands.len() {
                    let pc = self.frames[frame_index].pc;
                    let command_type = self.frames[frame_index].commands[pc].command_type;
                    self.frames[frame_index].pc += 1;
                    if contains(&LOOP_OPENERS, command_type) {
                        pending += 1;
                    } else if command_type == 498 {
                        pending -= 1;
                        if pending == 0 {
                            break;
                        }
                    }
                }
                return;
            }
        }
        let start_pc = self.frames[frame_index].pc;
        self.loops.push(LoopMarker {
            frame_index,
            start_pc,
            remaining,
            condition_params: None,
        });
    }

    fn begin_loop2(&mut self, command: &EventCommand) {
        let Some(frame_index) = self.frames.len().checked_sub(1) else {
            return;
        };
        let start_pc = self.frames[frame_index].pc;
        self.loops.push(LoopMarker {
            frame_index,
            start_pc,
            remaining: None,
            condition_params: Some(command.params.clone()),
        });
    }

    fn loop_end(&mut self) {
        let Some(frame_index) = self.frames.len().checked_sub(1) else {
            return;
        };
        // Discard markers from already-popped frames.
        self.loops.retain(|m| m.frame_index <= frame_index);
        let Some(marker) = self.loops.last().cloned() else {
            return;
        };
        if let Some(params) = marker.condition_params {
            // Loop2: continue while the first condition pair still holds.
            // Param order is {variable, value, operator}: the operator is the
            // *third* word. Reading them the other way turns `v2 < 3` into
            // `v2 <= 4`, which runs a loop two extra times.
            let continue_loop = if params.len() >= 3 {
                let key = decode_variable_ref(params[0]);
                let actual = self.variables.get(&key).copied().unwrap_or(0);
                let expected = self.operand_value(params[1]);
                compare(actual, params[2], expected)
            } else {
                false
            };
            if continue_loop {
                self.frames[frame_index].pc = marker.start_pc;
            } else {
                self.loops.pop();
            }
        } else if let Some(remaining) = marker.remaining {
            let remaining = remaining - 1;
            if remaining > 0 {
                if let Some(last) = self.loops.last_mut() {
                    last.remaining = Some(remaining);
                }
                self.frames[frame_index].pc = marker.start_pc;
            } else {
                self.loops.pop();
            }
        } else {
            self.frames[frame_index].pc = marker.start_pc;
        }
    }

    fn break_loop(&mut self) {
        // Choice/common-event subframes often issue BreakLoop for a loop that
        // lives on a parent frame. Target that frame rather than wiping parent
        // markers with a subframe-local filter.
        let Some(marker) = self.loops.last().cloned() else {
            return;
        };
        let target = marker.frame_index;
        while self.frames.len() > target + 1 {
            self.pop_frame();
        }
        if self.frames.len() != target + 1 {
            return;
        }
        self.loops.pop();
        // Skip forward past the matching 498, accounting for nested loops so an
        // inner 498 cannot end an outer loop early.
        let mut pending = 1;
        let mut pc = self.frames[target].pc;
        while pc < self.frames[target].commands.len() {
            let command_type = self.frames[target].commands[pc].command_type;
            pc += 1;
            if contains(&LOOP_OPENERS, command_type) {
                pending += 1;
            } else if command_type == 498 {
                pending -= 1;
                if pending == 0 {
                    break;
                }
            }
        }
        self.frames[target].pc = pc;
    }

    fn break_event(&mut self, command_type: i32) {
        let Some(frame) = self.frames.last() else { return };
        if frame.root || command_type == 173 {
            self.frames.clear();
            self.loops.clear();
            self.finished = true;
        } else {
            self.pop_frame();
            if self.frames.is_empty() {
                self.finished = true;
            }
        }
    }

    fn jump_to_label(&mut self, command: &EventCommand) {
        let target_param = command.params.first().copied();
        let target_string = command
            .strings
            .first()
            .filter(|s| !s.trim().is_empty())
            .cloned();
        if target_param.is_none() && target_string.is_none() {
            return;
        }
        let matches = |candidate: &EventCommand| -> bool {
            if candidate.command_type != 212 {
                return false;
            }
            match (&target_string, target_param) {
                (Some(want), _) => candidate.strings.first() == Some(want),
                (None, Some(want)) => candidate.params.first() == Some(&want),
                _ => false,
            }
        };
        // Search from the innermost frame outward: case bodies are temporary
        // sub-frames, while labels live on the enclosing event.
        for frame_index in (0..self.frames.len()).rev() {
            let commands = self.frames[frame_index].commands.clone();
            let pc = self.frames[frame_index].pc;
            for i in pc..commands.len() {
                if matches(&commands[i]) {
                    while self.frames.len() > frame_index + 1 {
                        self.pop_frame();
                    }
                    self.frames[frame_index].pc = i + 1;
                    return;
                }
            }
            for i in 0..pc.min(commands.len()) {
                if matches(&commands[i]) {
                    while self.frames.len() > frame_index + 1 {
                        self.pop_frame();
                    }
                    self.frames[frame_index].pc = i + 1;
                    return;
                }
            }
        }
    }

    fn begin_wait(&mut self, command: &EventCommand) {
        // Duration may be a CSelf/variable ref (fade common events use 1600000).
        let raw = command.params.first().copied().unwrap_or(0);
        let frames = self.operand_value(raw).clamp(0, 600);
        if frames > 0 {
            let ms = (frames as u64) * 1000 / self.fps.max(1) as u64;
            let deadline = now_ms() + ms;
            self.blocking = Some(Blocking::Wait {
                remaining_ticks: frames,
                deadline_ms: deadline,
            });
        }
    }

    fn teleport(&mut self, host: &mut dyn Host, command: &EventCommand) {
        // WOLF location-move command: {target, x, y, mapId, flags}. The target is
        // -1 for the calling event and -2 for the protagonist. Map/position
        // operands may be variable references.
        let p = &command.params;
        let (map_index, x_index, y_index) = if p.len() >= 4 {
            (3usize, 1usize, 2usize)
        } else {
            (0usize, 1usize, 2usize)
        };
        let Some(map_raw) = p.get(map_index).copied() else {
            return;
        };
        let Some(x_raw) = p.get(x_index).copied() else {
            return;
        };
        let Some(y_raw) = p.get(y_index).copied() else {
            return;
        };
        let map_id = self.operand_value(map_raw);
        let tile_x = self.operand_value(x_raw);
        let tile_y = self.operand_value(y_raw);
        if map_id >= 0 {
            // Notify the host immediately, as the original does, and also record
            // it so the engine can apply the transfer once the event ends.
            host.on_teleport(map_id, tile_x, tile_y);
            self.pending_teleport = Some((map_id, tile_x, tile_y));
        }
    }

    /// Transfer requested by the running event, consumed by the engine.
    pub fn take_teleport(&mut self) -> Option<(i32, i32, i32)> {
        self.pending_teleport.take()
    }

    // --- common events ------------------------------------------------------

    fn call_common_event_by_id(&mut self, command: &EventCommand) {
        let Some(raw_id) = command.params.first().copied() else {
            return;
        };
        // v3.5 namespaced indexed CE references: 600100 -> id 100 and
        // 500005 -> id 5 (init/party CE calls).
        let id = match raw_id {
            600_000..=699_999 => raw_id - 600_000,
            500_000..=599_999 => raw_id - 500_000,
            _ => raw_id,
        };
        let Some(body) = self.common_events.get(&id).cloned() else {
            return;
        };
        let args = self.numeric_call_args(&command.params, 1);
        self.push_common_event_frame(body, &args);
    }

    fn call_common_event_by_name(&mut self, command: &EventCommand) {
        let Some(name) = command.strings.first().cloned() else {
            return;
        };
        let Some(body) = self.common_events_by_name.get(&name).cloned() else {
            return;
        };
        // 300: [flags, argCount, arg0..] — wolfrpg-map-parser number_arguments.
        let args = self.numeric_call_args(&command.params, 1);
        // Optional return variable sits immediately after the numeric args.
        // Title scripts test it after New Game; clear so a stale menu value
        // cannot force an immediate return-to-title.
        let return_ref = command.params.get(2 + args.len()).copied();
        if let Some(r) = return_ref {
            if is_variable_ref(r) {
                self.variables.insert(decode_variable_ref(r), 0);
            }
        }
        self.push_common_event_frame(body, &args);
    }

    fn numeric_call_args(&mut self, params: &[i32], arg_count_index: usize) -> Vec<i32> {
        // wolfrpg-map-parser ArgumentCount: low nibble = number args, high =
        // string args. Some dumps pack extra option bits into the same word.
        let Some(raw) = params.get(arg_count_index).copied() else {
            return Vec::new();
        };
        let count = (raw & 0x0F).max(0) as usize;
        if count == 0 {
            return Vec::new();
        }
        let start = arg_count_index + 1;
        if start >= params.len() {
            return Vec::new();
        }
        let n = count.min(params.len() - start);
        (0..n).map(|i| self.operand_value(params[start + i])).collect()
    }

    /// Common-event CSelf (1.6M) vars are per-event in the editor runtime:
    /// snapshot the caller bank, clear for the callee, apply call args into
    /// CSelf0..N, and restore the caller bank on pop.
    fn push_common_event_frame(&mut self, body: Vec<EventCommand>, args: &[i32]) {
        if self.frames.len() >= MAX_FRAMES {
            return;
        }
        let saved = self.snapshot_self_vars();
        let saved_strings = self.snapshot_self_strings();
        self.clear_self_vars();
        self.clear_self_strings();
        for (i, value) in args.iter().enumerate() {
            let key = decode_variable_ref(1_600_000 + i as i32);
            self.variables.insert(key, *value);
        }
        self.frames.push(Frame {
            commands: body,
            pc: 0,
            root: false,
            saved_self: Some(saved),
            saved_self_strings: Some(saved_strings),
        });
    }

    fn pop_frame(&mut self) {
        let Some(frame) = self.frames.pop() else {
            return;
        };
        let popped_index = self.frames.len();
        self.loops.retain(|m| m.frame_index < popped_index);
        if let Some(saved) = frame.saved_self {
            self.clear_self_vars();
            self.variables.extend(saved);
        }
        if let Some(saved) = frame.saved_self_strings {
            self.clear_self_strings();
            self.strings.extend(saved);
        }
    }

    fn snapshot_self_vars(&self) -> HashMap<i32, i32> {
        self.variables
            .iter()
            .filter(|(k, _)| is_self_var_key(**k))
            .map(|(k, v)| (*k, *v))
            .collect()
    }

    fn clear_self_vars(&mut self) {
        self.variables.retain(|k, _| !is_self_var_key(*k));
    }

    fn snapshot_self_strings(&self) -> HashMap<i32, String> {
        self.strings
            .iter()
            .filter(|(k, _)| is_self_string_key(**k))
            .map(|(k, v)| (*k, v.clone()))
            .collect()
    }

    fn clear_self_strings(&mut self) {
        self.strings.retain(|k, _| !is_self_string_key(*k));
    }

    // --- save / load --------------------------------------------------------

    fn load_or_probe(&mut self, host: &mut dyn Host, command: &EventCommand) {
        // Title/New Game scripts issue field/file 221 probes; only a full-slot
        // load may replace the live map.
        if is_full_slot_load(command) {
            host.on_load(self.save_load_slot(command));
            return;
        }
        // Field probe: params[0] = result var, params[1] = filename string.
        let dest = command.params.first().copied();
        let name_ref = command.params.get(1).copied();
        if let (Some(dest), Some(name_ref)) = (dest, name_ref) {
            let name = self
                .strings
                .get(&decode_string_ref(name_ref))
                .or_else(|| self.strings.get(&decode_variable_ref(name_ref)))
                .cloned()
                .unwrap_or_default();
            let exists = !name.trim().is_empty() && host.on_file_exists(&name);
            self.variables
                .insert(decode_variable_ref(dest), if exists { 1 } else { 0 });
        }
    }

    fn save_or_marker(&mut self, host: &mut dyn Host, command: &EventCommand) {
        if is_full_slot_load(command) {
            host.on_save(self.save_load_slot(command));
            return;
        }
        if let Some(name_ref) = command.params.get(1).copied() {
            let name = self
                .strings
                .get(&decode_string_ref(name_ref))
                .or_else(|| self.strings.get(&decode_variable_ref(name_ref)))
                .cloned()
                .unwrap_or_default();
            if !name.trim().is_empty() {
                host.on_save_file(&name);
            }
        }
    }

    fn save_slot(&self, command: &EventCommand) -> i32 {
        let raw = command.params.first().copied().unwrap_or(0);
        if is_variable_ref(raw) {
            self.var_value(raw)
        } else {
            raw
        }
    }

    /// Resolves a SaveGame/LoadGame slot. Named files (`Save/AUTO{n}.sav`,
    /// `Save/Manual{n}.sav`) store the trailing number in a string var at
    /// params[1]; `System.sav` maps to slot 0. Numeric slots keep their value.
    fn save_load_slot(&self, command: &EventCommand) -> i32 {
        if let Some(filename_ref) = command.params.get(1).copied() {
            if is_variable_ref(filename_ref) {
                let name = self
                    .strings
                    .get(&decode_string_ref(filename_ref))
                    .or_else(|| self.strings.get(&decode_variable_ref(filename_ref)))
                    .cloned()
                    .unwrap_or_default();
                if !name.trim().is_empty() {
                    if let Some(digits) = trailing_save_number(&name) {
                        return digits.clamp(0, 99);
                    }
                    if name.contains("System") {
                        return 0;
                    }
                }
            }
        }
        self.save_slot(command)
    }

    // --- branch walking -----------------------------------------------------

    /// Skips the remainder of the current conditional body.
    fn skip_current_branch(&mut self) {
        let Some(frame_index) = self.frames.len().checked_sub(1) else {
            return;
        };
        // v3.5 structure: a condition (111/112) is followed by its case headers.
        // Each case body extends until the next SIBLING case header, its own
        // balancing 499, or the list end. A false condition therefore advances to
        // the first non-case command. Bodies without case headers use a balanced
        // 499 walk.
        let next_type = self.frames[frame_index]
            .commands
            .get(self.frames[frame_index].pc)
            .map(|c| c.command_type);
        let has_cases = next_type.map(|t| contains(&CASE_HEADERS, t)).unwrap_or(false);
        if !has_cases {
            let mut pending = 1;
            while self.frames[frame_index].pc < self.frames[frame_index].commands.len() {
                let pc = self.frames[frame_index].pc;
                let command_type = self.frames[frame_index].commands[pc].command_type;
                self.frames[frame_index].pc += 1;
                if contains(&BRANCH_OPENERS, command_type) {
                    pending += 1;
                } else if command_type == 499 {
                    pending -= 1;
                    if pending == 0 {
                        break;
                    }
                }
            }
            return;
        }
        loop {
            let head_type = self.frames[frame_index]
                .commands
                .get(self.frames[frame_index].pc)
                .map(|c| c.command_type);
            match head_type {
                Some(t) if contains(&CASE_HEADERS, t) => self.skip_one_case(),
                _ => return,
            }
        }
    }

    /// `pc` is at a case header; consumes the header and its body.
    fn skip_one_case(&mut self) {
        let Some(frame_index) = self.frames.len().checked_sub(1) else {
            return;
        };
        self.frames[frame_index].pc += 1; // consume the case header
        loop {
            let Some(command_type) = self.frames[frame_index]
                .commands
                .get(self.frames[frame_index].pc)
                .map(|c| c.command_type)
            else {
                return;
            };
            match command_type {
                499 => {
                    self.frames[frame_index].pc += 1;
                    return;
                }
                t if contains(&CASE_HEADERS, t) => return, // sibling header ends it
                111 | 112 => {
                    self.skip_condition_cluster();
                    // A nested cluster whose last consumed command is the case's
                    // own shared terminator ends the enclosing case too when no
                    // further case header follows.
                    let prev_type = self.frames[frame_index]
                        .pc
                        .checked_sub(1)
                        .and_then(|i| self.frames[frame_index].commands.get(i))
                        .map(|c| c.command_type);
                    let next_type = self.frames[frame_index]
                        .commands
                        .get(self.frames[frame_index].pc)
                        .map(|c| c.command_type);
                    if prev_type == Some(499)
                        && next_type
                            .map(|t| !contains(&CASE_HEADERS, t))
                            .unwrap_or(false)
                    {
                        return;
                    }
                }
                t if contains(&LOOP_OPENERS, t) => self.skip_loop_body(),
                _ => self.frames[frame_index].pc += 1,
            }
        }
    }

    /// `pc` is at a 111/112; consumes it plus its whole case cluster.
    fn skip_condition_cluster(&mut self) {
        let Some(frame_index) = self.frames.len().checked_sub(1) else {
            return;
        };
        self.frames[frame_index].pc += 1; // consume the condition
        loop {
            let next_type = self.frames[frame_index]
                .commands
                .get(self.frames[frame_index].pc)
                .map(|c| c.command_type);
            match next_type {
                Some(t) if contains(&CASE_HEADERS, t) => self.skip_one_case(),
                _ => return,
            }
        }
    }

    /// `pc` is at a loop opener; consumes the body to its balancing 498.
    fn skip_loop_body(&mut self) {
        let Some(frame_index) = self.frames.len().checked_sub(1) else {
            return;
        };
        self.frames[frame_index].pc += 1; // consume the loop opener
        let mut depth = 1;
        while depth > 0 && self.frames[frame_index].pc < self.frames[frame_index].commands.len() {
            let command_type = self.frames[frame_index].commands[self.frames[frame_index].pc]
                .command_type;
            match command_type {
                t if contains(&LOOP_OPENERS, t) => {
                    self.frames[frame_index].pc += 1;
                    depth += 1;
                }
                498 => {
                    self.frames[frame_index].pc += 1;
                    depth -= 1;
                }
                111 | 112 => self.skip_condition_cluster(),
                t if contains(&CASE_HEADERS, t) => self.skip_one_case(),
                _ => self.frames[frame_index].pc += 1,
            }
        }
    }

    // --- choice bodies ------------------------------------------------------

    fn extract_choice_bodies(&mut self) -> ChoiceBodies {
        let mut result = ChoiceBodies::default();
        let Some(frame_index) = self.frames.len().checked_sub(1) else {
            return result;
        };
        let commands = self.frames[frame_index].commands.clone();
        let mut pc = self.frames[frame_index].pc;

        let mut active_index: Option<i32> = None;
        let mut is_cancel = false;
        let mut segment: Vec<EventCommand> = Vec::new();
        let mut nesting = 0usize;

        while pc < commands.len() {
            let command = &commands[pc];
            if nesting == 0 {
                let starts_case = contains(&CASE_HEADERS, command.command_type);
                if !starts_case {
                    break;
                }
                active_index = if command.command_type == 401 {
                    command.params.first().copied()
                } else {
                    None
                };
                is_cancel = command.command_type != 401;
                segment = Vec::new();
                nesting = 1;
                pc += 1;
                continue;
            }
            // Sibling case headers end this body; they are not nested openers.
            if nesting == 1 && contains(&CASE_HEADERS, command.command_type) {
                if is_cancel {
                    result.cancel = Some(segment.clone());
                } else if let Some(index) = active_index {
                    result.cases.insert(index, segment.clone());
                }
                segment.clear();
                active_index = None;
                is_cancel = false;
                nesting = 0;
                continue; // re-process this header in the nesting == 0 path
            }
            // Inside a case body.
            pc += 1;
            if command.command_type == 499 {
                nesting -= 1;
                if nesting == 0 {
                    if is_cancel {
                        result.cancel = Some(segment.clone());
                    } else if let Some(index) = active_index {
                        result.cases.insert(index, segment.clone());
                    }
                    segment.clear();
                    active_index = None;
                    is_cancel = false;
                    continue;
                }
            } else if contains(&BRANCH_OPENERS, command.command_type) {
                nesting += 1;
            }
            segment.push(command.clone());
        }
        result.resumed_from_pc = pc as i32;
        self.frames[frame_index].pc = pc;
        result
    }

    /// Picture/sound parameters carry variable references (e.g. a picture slot
    /// stored in a system variable); resolve them before presentation.
    fn resolve_command_refs(&self, command: &EventCommand) -> EventCommand {
        let params: Vec<i32> = command
            .params
            .iter()
            .enumerate()
            .map(|(i, raw)| {
                // Always resolve variable refs for slots/geometry. File-from-
                // string picture paths are recovered from the original params.
                if is_variable_ref(*raw) && i > 0 {
                    self.var_value(*raw)
                } else {
                    *raw
                }
            })
            .collect();
        EventCommand {
            param_count: command.param_count,
            command_type: command.command_type,
            params,
            branch_depth: command.branch_depth,
            strings: command.strings.clone(),
            route: command.route.clone(),
        }
    }
}

impl Interpreter {
    /// Transfer requested by the running event.
    ///
    /// Stored separately from `execute` so the engine can apply it after the
    /// event ends, matching the Kotlin host's deferred transfer.
    pub fn pending_teleport(&self) -> Option<(i32, i32, i32)> {
        self.pending_teleport
    }
}

#[derive(Debug, Default)]
struct ChoiceBodies {
    cases: HashMap<i32, Vec<EventCommand>>,
    cancel: Option<Vec<EventCommand>>,
    /// Position after the full choices region; -1 while unknown.
    resumed_from_pc: i32,
}

/// Lets the database model read and write the interpreter's variable banks.
struct InterpreterVars<'a> {
    variables: &'a mut Variables,
    strings: &'a mut Strings,
}

impl<'a> database::VariableAccess for InterpreterVars<'a> {
    fn read_number(&self, raw: i32) -> i32 {
        self.variables
            .get(&decode_variable_ref(raw))
            .copied()
            .unwrap_or(0)
    }
    fn write_number(&mut self, raw: i32, value: i32) {
        self.variables.insert(decode_variable_ref(raw), value);
    }
    fn read_string(&self, raw: i32) -> String {
        self.strings
            .get(&decode_string_ref(raw))
            .cloned()
            .unwrap_or_default()
    }
    fn write_string(&mut self, raw: i32, value: String) {
        self.strings.insert(decode_string_ref(raw), value);
    }
}

/// WOLF comparison operator table.
pub fn compare(actual: i32, operator_code: i32, expected: i32) -> bool {
    match operator_code {
        0 => actual > expected,
        1 => actual >= expected,
        2 => actual == expected,
        3 => actual <= expected,
        4 => actual < expected,
        5 => actual != expected,
        6 => (actual & expected) != 0,
        _ => false,
    }
}

/// True when InputKey options request blocking until a key is pressed.
fn input_key_waits(command: &EventCommand) -> bool {
    let Some(options) = command.params.get(1).copied() else {
        return true;
    };
    let bits = if (0..=255).contains(&options) {
        options
    } else {
        (options >> 8) & 0xFF
    };
    (bits & 0x80) != 0
}

/// Extracts the trailing `N` from `NAME{N}.sav`.
fn trailing_save_number(name: &str) -> Option<i32> {
    let stem = name.strip_suffix(".sav")?;
    let digits: String = stem
        .chars()
        .rev()
        .take_while(|c| c.is_ascii_digit())
        .collect::<String>()
        .chars()
        .rev()
        .collect();
    if digits.is_empty() {
        None
    } else {
        digits.parse::<i32>().ok()
    }
}

/// True when a 221/222 command loads or saves a whole slot rather than one field.
fn is_full_slot_load(command: &EventCommand) -> bool {
    let p = &command.params;
    let Some(dest) = p.first().copied() else {
        return true;
    };
    let Some(second) = p.get(1).copied() else {
        return true;
    };
    // Name-based / field loads used by title scripts: dest+filename string var,
    // or a system-var filename in the 1.6M range.
    if (2_000_000..=2_999_999).contains(&dest) && (3_000_000..=3_999_999).contains(&second) {
        return false;
    }
    if (2_000_000..=2_999_999).contains(&dest) && p.len() >= 3 && p.get(2) == Some(&dest) {
        return false;
    }
    if (1_600_000..=1_699_999).contains(&second) || (1_600_000..=1_699_999).contains(&dest) {
        return false;
    }
    if p.len() >= 3 && second >= 1_000_000 {
        return false;
    }
    true
}

/// Pure walk: pc of the first command after the case body at `header_pc`.
fn case_body_end(commands: &[EventCommand], header_pc: usize) -> usize {
    let mut pc = header_pc + 1;
    while pc < commands.len() {
        let command_type = commands[pc].command_type;
        if command_type == 499 {
            return pc + 1;
        }
        if contains(&CASE_HEADERS, command_type) {
            return pc;
        }
        if command_type == 111 || command_type == 112 {
            pc = condition_cluster_end(commands, pc);
        } else if contains(&LOOP_OPENERS, command_type) {
            pc = loop_body_end(commands, pc);
        } else {
            pc += 1;
        }
    }
    pc
}

/// Pure walk: pc after a 111/112 and its whole case cluster.
fn condition_cluster_end(commands: &[EventCommand], cond_pc: usize) -> usize {
    let mut pc = cond_pc + 1;
    while pc < commands.len() {
        if !contains(&CASE_HEADERS, commands[pc].command_type) {
            return pc;
        }
        pc = case_body_end(commands, pc);
    }
    pc
}

/// Pure walk: pc after an open loop body (to its balancing 498).
fn loop_body_end(commands: &[EventCommand], loop_pc: usize) -> usize {
    let mut pc = loop_pc + 1;
    let mut depth = 1;
    while pc < commands.len() && depth > 0 {
        let command_type = commands[pc].command_type;
        if contains(&LOOP_OPENERS, command_type) {
            depth += 1;
            pc += 1;
        } else if command_type == 498 {
            depth -= 1;
            pc += 1;
        } else if command_type == 111 || command_type == 112 {
            pc = condition_cluster_end(commands, pc);
        } else if contains(&CASE_HEADERS, command_type) {
            pc = case_body_end(commands, pc);
        } else {
            pc += 1;
        }
    }
    pc
}

/// Pure walk: skip remaining sibling cases after `after_body_pc`.
fn branch_end_after(commands: &[EventCommand], after_body_pc: usize) -> usize {
    let mut pc = after_body_pc;
    while pc < commands.len() {
        if !contains(&CASE_HEADERS, commands[pc].command_type) {
            return pc;
        }
        pc = case_body_end(commands, pc);
    }
    pc
}

/// Milliseconds since the process started, matching the Kotlin clock source.
fn now_ms() -> u64 {
    use std::sync::OnceLock;
    use std::time::Instant;
    static START: OnceLock<Instant> = OnceLock::new();
    let start = START.get_or_init(Instant::now);
    start.elapsed().as_millis() as u64
}
