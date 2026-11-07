//! Differential corpus runner.
//!
//! Runs a shared command corpus through the Rust interpreter and emits a
//! canonical trace, so the Kotlin implementation can be run over the *same*
//! corpus and compared line for line. Two implementations that disagree about
//! an opcode cannot both pass.
//!
//! # Corpus format
//!
//! One command per line, `TYPE|params|strings`:
//!
//! ```text
//! 101||hello
//! 121|10,42,0|
//! 111|1,3,10,2|
//! ```
//!
//! Blank lines and lines starting with `#` are ignored.
//!
//! # Trace format
//!
//! Deterministic, one event per line, in execution order:
//!
//! ```text
//! MSG:hello
//! CHOICE:はい/いいえ
//! VAR:10=50
//! STR:5=モノクロ
//! PICTURE:150:slot=22
//! SOUND
//! TELEPORT:7,12,34
//! UNHANDLED:9999
//! DONE
//! ```
//!
//! Waits are deliberately excluded from the differential corpus: the Kotlin
//! implementation uses a wall-clock deadline, so a slow device could change how
//! many ticks a wait survives and the comparison would be flaky rather than
//! wrong. Wait behaviour is covered by unit tests in both implementations.

use std::collections::HashMap;

use crate::wolfformat::event_command::EventCommand;

use super::{Blocking, Host, Interpreter, MAX_COMMANDS_PER_TICK};

/// A parsed corpus command plus the meta-commands the runner understands.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CorpusStep {
    Command(EventCommand),
    /// `@choose N` — select choice index N when the interpreter blocks on one.
    Choose(i32),
    /// `@advance` — dismiss the current message.
    Advance,
    /// `@vars` — dump the variable bank in key order.
    DumpVariables,
    /// `@strings` — dump the string bank in key order.
    DumpStrings,
    /// `@common NAME` — begin a common-event body named NAME.
    CommonEventStart(String),
    /// `@common-id N` — begin a common-event body registered under numeric id N.
    CommonEventIdStart(i32),
    /// `@end` — end the current common-event body.
    CommonEventEnd,
    /// `@ticks N` — run N extra ticks.
    Ticks(usize),
}

/// Parses a corpus.
pub fn parse_corpus(text: &str) -> Result<Vec<CorpusStep>, String> {
    let mut steps = Vec::new();
    for (line_no, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let step = parse_line(line).map_err(|e| format!("line {}: {e}", line_no + 1))?;
        steps.push(step);
    }
    Ok(steps)
}

fn parse_line(line: &str) -> Result<CorpusStep, String> {
    if let Some(rest) = line.strip_prefix('@') {
        let (name, arg) = match rest.split_once(char::is_whitespace) {
            Some((n, a)) => (n, a.trim()),
            None => (rest, ""),
        };
        return match name {
            "choose" => Ok(CorpusStep::Choose(
                arg.parse::<i32>().map_err(|e| format!("@choose: {e}"))?,
            )),
            "advance" => Ok(CorpusStep::Advance),
            "vars" => Ok(CorpusStep::DumpVariables),
            "strings" => Ok(CorpusStep::DumpStrings),
            "common" => Ok(CorpusStep::CommonEventStart(arg.to_string())),
            "common-id" => Ok(CorpusStep::CommonEventIdStart(
                arg.parse::<i32>().map_err(|e| format!("@common-id: {e}"))?,
            )),
            "end" => Ok(CorpusStep::CommonEventEnd),
            "ticks" => Ok(CorpusStep::Ticks(
                arg.parse::<usize>().map_err(|e| format!("@ticks: {e}"))?,
            )),
            other => Err(format!("unknown meta-command @{other}")),
        };
    }

    let mut parts = line.splitn(3, '|');
    let type_text = parts.next().ok_or("missing command type")?;
    let params_text = parts.next().unwrap_or("");
    let strings_text = parts.next().unwrap_or("");

    let command_type: i32 = type_text
        .trim()
        .parse()
        .map_err(|e| format!("command type {type_text:?}: {e}"))?;

    let params: Vec<i32> = if params_text.trim().is_empty() {
        Vec::new()
    } else {
        params_text
            .split(',')
            .map(|p| {
                let t = p.trim();
                // Packed operator words read better in hex (0x100, 0x3000).
                let parsed = match t.strip_prefix("0x").or_else(|| t.strip_prefix("0X")) {
                    Some(hex) => i64::from_str_radix(hex, 16).map(|v| v as i32),
                    None => t.parse::<i32>(),
                };
                parsed.map_err(|e| format!("parameter {t:?}: {e}"))
            })
            .collect::<Result<Vec<_>, _>>()?
    };

    let strings: Vec<String> = if strings_text.is_empty() {
        Vec::new()
    } else {
        strings_text.split(',').map(|s| s.to_string()).collect()
    };

    Ok(CorpusStep::Command(EventCommand {
        param_count: if command_type == 0 {
            0
        } else {
            params.len() as u8 + 1
        },
        command_type,
        params,
        branch_depth: 0,
        strings,
        route: None,
    }))
}

/// Records everything observable, in a form both implementations can produce.
#[derive(Default)]
struct TraceHost {
    lines: Vec<String>,
    picture_slots: Vec<i32>,
}

impl Host for TraceHost {
    fn on_message(&mut self, text: &str) {
        self.lines.push(format!("MSG:{text}"));
    }
    fn on_choices(&mut self, options: &[String]) {
        self.lines.push(format!("CHOICE:{}", options.join("/")));
    }
    fn on_sound(&mut self, command: &EventCommand) {
        self.lines.push(format!("SOUND:{}", command.command_type));
    }
    fn on_picture(&mut self, command: &EventCommand) {
        // The slot is the interesting part: it proves parameter resolution.
        let slot = command.params.get(1).copied().unwrap_or(-1);
        self.picture_slots.push(slot);
        self.lines
            .push(format!("PICTURE:{}:slot={slot}", command.command_type));
    }
    fn on_screen_effect(&mut self, command: &EventCommand) {
        self.lines
            .push(format!("EFFECT:{}", command.command_type));
    }
    fn on_teleport(&mut self, map_id: i32, tile_x: i32, tile_y: i32) {
        self.lines
            .push(format!("TELEPORT:{map_id},{tile_x},{tile_y}"));
    }
    fn on_save(&mut self, slot: i32) -> bool {
        self.lines.push(format!("SAVE:{slot}"));
        true
    }
    fn on_load(&mut self, slot: i32) -> bool {
        self.lines.push(format!("LOAD:{slot}"));
        true
    }
    fn on_save_load(&mut self) -> bool {
        self.lines.push("SAVELOAD".to_string());
        true
    }
    fn on_move(&mut self, command: &EventCommand) {
        self.lines.push(format!("MOVE:{}", command.command_type));
    }
    fn on_database(&mut self, command: &EventCommand) {
        self.lines.push(format!("DATABASE:{}", command.command_type));
    }
    fn on_party(&mut self, command: &EventCommand) {
        self.lines.push(format!("PARTY:{}", command.command_type));
    }
    fn on_chip_change(&mut self, command: &EventCommand) {
        self.lines.push(format!("CHIP:{}", command.command_type));
    }
    fn on_ban_input(&mut self, banned: bool) {
        self.lines.push(format!("BANINPUT:{banned}"));
    }
    fn on_return_to_title(&mut self) {
        self.lines.push("TITLERETURN".to_string());
    }
    fn on_end_game(&mut self) {
        self.lines.push("ENDGAME".to_string());
    }
    fn on_unhandled(&mut self, command: &EventCommand) {
        self.lines
            .push(format!("UNHANDLED:{}", command.command_type));
    }
}

/// Runs a corpus and returns its canonical trace.
pub fn run_corpus(text: &str) -> Result<String, String> {
    run_corpus_with_database(text, None)
}

/// Runs a corpus with an optional database loaded from a deployment's `Data`
/// directory. With a database present, `250/251/252/255/256/257` execute inside
/// the interpreter; without one they reach the host hook, which the trace shows
/// as `DATABASE:<opcode>`.
pub fn run_corpus_with_database(
    text: &str,
    data_dir: Option<&std::path::Path>,
) -> Result<String, String> {
    let steps = parse_corpus(text)?;

    // Split the corpus into the main program and named common-event bodies.
    let mut main_program: Vec<EventCommand> = Vec::new();
    let mut common_by_name: HashMap<String, Vec<EventCommand>> = HashMap::new();
    let mut common_by_id: HashMap<i32, Vec<EventCommand>> = HashMap::new();
    // Which body is currently being filled, as (name, optional numeric id).
    let mut current_common: Option<(Option<String>, Option<i32>)> = None;
    let mut actions: Vec<CorpusStep> = Vec::new();

    let steps_for_segments = steps.clone();
    for step in steps {
        match step {
            CorpusStep::CommonEventStart(name) => {
                common_by_name.entry(name.clone()).or_default();
                current_common = Some((Some(name), None));
            }
            CorpusStep::CommonEventIdStart(id) => {
                common_by_id.entry(id).or_default();
                current_common = Some((None, Some(id)));
            }
            CorpusStep::CommonEventEnd => current_common = None,
            CorpusStep::Command(command) => match &current_common {
                Some((Some(name), _)) => common_by_name
                    .entry(name.clone())
                    .or_default()
                    .push(command),
                Some((None, Some(id))) => common_by_id.entry(*id).or_default().push(command),
                _ => main_program.push(command),
            },
            other => actions.push(other),
        }
    }

    let mut host = TraceHost::default();
    let mut interpreter = Interpreter::new(60);
    interpreter.set_common_events_by_name(common_by_name);
    interpreter.set_common_events(common_by_id);
    if let Some(dir) = data_dir {
        interpreter.set_database(crate::interpreter::database::Database::load(dir));
    }

    // The corpus is an ordered script, so it is executed in segments: the
    // commands before an action run first, then the action observes the result.
    // Running the whole program up front would let the first `@vars` consume
    // every later command, which is what happened before this was segmented.
    //
    // `start` resets frames and blocking but keeps the variable and string banks,
    // so segments share machine state exactly as one continuous run would.
    let mut segment: Vec<EventCommand> = Vec::new();
    // Commands inside a `@common` block belong to that common event, not to the
    // main program: pushing them here as well would run every common-event body
    // inline at the point it is defined, which is not what the corpus means.
    let mut in_common = false;
    for step in &steps_for_segments {
        match step {
            CorpusStep::CommonEventStart(_) | CorpusStep::CommonEventIdStart(_) => {
                in_common = true
            }
            CorpusStep::CommonEventEnd => in_common = false,
            CorpusStep::Command(command) => {
                if !in_common {
                    segment.push(command.clone());
                }
            }
            action => {
                if !segment.is_empty() {
                    interpreter.start(std::mem::take(&mut segment));
                    run_to_stop(&mut interpreter, &mut host);
                }
                apply_action(action, &mut interpreter, &mut host);
            }
        }
    }
    if !segment.is_empty() {
        interpreter.start(segment);
        run_to_stop(&mut interpreter, &mut host);
    }

    host.lines.push("DONE".to_string());
    Ok(host.lines.join("\n"))
}

/// Applies one corpus action to a running interpreter.
fn apply_action(action: &CorpusStep, interpreter: &mut Interpreter, host: &mut TraceHost) {
    match action {
        CorpusStep::Choose(index) => {
            if matches!(interpreter.blocking(), Some(Blocking::Choices(_))) {
                interpreter.choose(*index);
                // Choosing pushes the case body as a new frame; run it now so a
                // corpus does not need a redundant segment after every @choose.
                run_to_stop(interpreter, host);
            }
        }
        CorpusStep::Advance => interpreter.advance(),
        CorpusStep::Ticks(n) => {
            for _ in 0..*n {
                interpreter.tick_default(host);
                if matches!(interpreter.blocking(), Some(Blocking::Message(_))) {
                    interpreter.advance();
                }
            }
        }
        CorpusStep::DumpVariables => {
            let mut keys: Vec<i32> = interpreter.variables.keys().copied().collect();
            keys.sort_unstable();
            for key in keys {
                let value = interpreter.variables.get(&key).copied().unwrap_or(0);
                host.lines.push(format!("VAR:{key}={value}"));
            }
        }
        CorpusStep::DumpStrings => {
            let mut keys: Vec<i32> = interpreter.strings.keys().copied().collect();
            keys.sort_unstable();
            for key in keys {
                let value = interpreter.strings.get(&key).cloned().unwrap_or_default();
                host.lines.push(format!("STR:{key}={value}"));
            }
        }
        CorpusStep::Command(_)
        | CorpusStep::CommonEventStart(_)
        | CorpusStep::CommonEventIdStart(_)
        | CorpusStep::CommonEventEnd => {}
    }
}

/// Runs until the interpreter blocks or finishes, dismissing messages as they
/// appear so a corpus does not need an `@advance` per line.
fn run_to_stop(interpreter: &mut Interpreter, host: &mut TraceHost) {
    let mut guard = 0usize;
    loop {
        guard += 1;
        // A runaway corpus would otherwise spin here for minutes; the guard is
        // small because a legitimate corpus never needs thousands of ticks.
        if guard > 200 || interpreter.is_finished() {
            return;
        }
        interpreter.tick(host, MAX_COMMANDS_PER_TICK);
        if matches!(interpreter.blocking(), Some(Blocking::Message(_))) {
            interpreter.advance();
            continue;
        }
        if interpreter.blocking().is_some() {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_command_line() {
        let steps = parse_corpus("121|10,42,0|").unwrap();
        assert_eq!(steps.len(), 1);
        match &steps[0] {
            CorpusStep::Command(c) => {
                assert_eq!(c.command_type, 121);
                assert_eq!(c.params, vec![10, 42, 0]);
                assert!(c.strings.is_empty());
            }
            other => panic!("unexpected step {other:?}"),
        }
    }

    #[test]
    fn parses_strings_and_meta_commands() {
        let steps = parse_corpus("# a comment\n101||hello\n@advance\n").unwrap();
        assert_eq!(steps.len(), 2);
        match &steps[0] {
            CorpusStep::Command(c) => assert_eq!(c.strings, vec!["hello"]),
            other => panic!("unexpected {other:?}"),
        }
        assert_eq!(steps[1], CorpusStep::Advance);
    }

    #[test]
    fn rejects_a_bad_command_type() {
        assert!(parse_corpus("abc||").is_err());
    }

    #[test]
    fn rejects_an_unknown_meta_command() {
        assert!(parse_corpus("@nope").is_err());
    }

    #[test]
    fn runs_messages_to_completion() {
        let trace = run_corpus("101||hello\n101||world\n").unwrap();
        assert_eq!(trace, "MSG:hello\nMSG:world\nDONE");
    }

    #[test]
    fn reports_unhandled_opcodes() {
        let trace = run_corpus("9999|1|\n").unwrap();
        assert_eq!(trace, "UNHANDLED:9999\nDONE");
    }

    #[test]
    fn dumps_variables_in_key_order() {
        let trace = run_corpus("121|10,42,0|\n121|2,7,0|\n@vars\n").unwrap();
        assert_eq!(trace, "VAR:2=7\nVAR:10=42\nDONE");
    }

    #[test]
    fn routes_a_choice_selection() {
        let corpus = "\
102||A,B
401|0|
101||picked-a
499|
401|1|
101||picked-b
499|
@choose 1
";
        let trace = run_corpus(corpus).unwrap();
        assert!(trace.contains("CHOICE:A/B"), "trace: {trace}");
        assert!(trace.contains("MSG:picked-b"), "trace: {trace}");
        assert!(!trace.contains("picked-a"), "trace: {trace}");
    }

    /// The shared corpus and the trace the Kotlin implementation produces for
    /// it. Both files are the same ones the Kotlin differential test reads, so a
    /// disagreement about any opcode in the corpus fails on one side or the
    /// other. Comparing against a recorded answer (rather than invoking the JVM
    /// from here) keeps the Rust suite dependency-free.
    fn shared_corpus() -> String {
        std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../../src/test/resources/wolf/interpreter-corpus.txt"
        ))
        .expect("shared corpus is checked in")
    }

    fn shared_golden() -> String {
        std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../../src/test/resources/wolf/interpreter-corpus.golden.txt"
        ))
        .expect("shared golden trace is checked in")
    }

    #[test]
    fn the_shared_corpus_matches_the_golden_trace() {
        let actual = run_corpus(&shared_corpus()).expect("corpus parses");
        let expected = shared_golden();
        let expected = expected.trim_end();
        let actual = actual.trim_end();

        let expected_lines: Vec<&str> = expected.lines().collect();
        let actual_lines: Vec<&str> = actual.lines().collect();
        let limit = expected_lines.len().min(actual_lines.len());
        for i in 0..limit {
            assert_eq!(
                expected_lines[i],
                actual_lines[i],
                "trace diverges at line {}",
                i + 1
            );
        }
        assert_eq!(
            expected_lines.len(),
            actual_lines.len(),
            "trace length differs"
        );
    }

    /// Database and XY-array cases, run with the real deployment loaded.
    ///
    /// Kept out of the shared corpus because the shared golden trace must be
    /// reproducible on a machine without the game data; this one skips itself
    /// when the deployment is absent.
    fn database_corpus() -> String {
        std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../../src/test/resources/wolf/interpreter-database-corpus.txt"
        ))
        .expect("database corpus is checked in")
    }

    fn database_golden() -> String {
        std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../../src/test/resources/wolf/interpreter-database-corpus.golden.txt"
        ))
        .expect("database golden trace is checked in")
    }



    #[test]
    fn a_runaway_corpus_terminates() {
        // An unbounded loop with no blocking command must hit the guard rather
        // than hang the test runner.
        let trace = run_corpus("170||\n121|1,1,0,0x100|\n498|\n").unwrap();
        assert!(trace.ends_with("DONE"));
    }
}
