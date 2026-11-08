//! Independent parallel common-event stacks sharing the foreground global banks.
use std::collections::{HashMap, HashSet};
use crate::interpreter::{Host, Interpreter};
use crate::wolfformat::common_event::CommonEvent;

#[derive(Default)]
pub struct CommonRunners {
    definitions: Vec<CommonEvent>,
    runners: HashMap<i32, Interpreter>,
    active: HashSet<i32>,
}

impl CommonRunners {
    pub fn new(main: &Interpreter) -> Self {
        Self { definitions: main.common_event_definitions().iter()
            .filter(|event| event.run_condition == 2 || event.run_condition == 3).cloned().collect(),
            ..Self::default() }
    }

    pub fn diagnostics(&self) -> String {
        let mut rows:Vec<_>=self.runners.iter().map(|(id,runner)|
            (*id,format!("common={id} active={} pc={} blocked={:?} finished={}",self.active.contains(id),runner.current_pc(),runner.blocking(),runner.is_finished()))).collect();
        rows.sort_by_key(|row|row.0);
        rows.into_iter().map(|row|row.1).collect::<Vec<_>>().join("\n")
    }

    pub fn tick(&mut self, main: &mut Interpreter, host: &mut dyn Host, budget_per_event: usize) -> Result<(), String> {
        // Validate all predicates before running any event this frame.
        let mut enabled = Vec::with_capacity(self.definitions.len());
        for event in &self.definitions {
            let active = event.automatically_active(&mut |raw| {
                if (15_000_000..16_000_000).contains(&raw) {
                    let id = (raw - 15_000_000) / 100;
                    let slot = (raw - 15_000_000) % 100;
                    self.runners.get(&id).and_then(|runner| runner.variables.get(&crate::interpreter::refs::self_var_key(slot)))
                        .copied().unwrap_or(0)
                } else {
                    main.variables.get(&crate::interpreter::refs::decode_variable_ref(raw)).copied().unwrap_or(0)
                }
            })?;
            enabled.push(active);
        }
        for (event, enabled) in self.definitions.iter().zip(enabled) {
            if !enabled { self.active.remove(&event.id); continue; }
            let runner = self.runners.entry(event.id).or_insert_with(|| main.common_runner());
            if !self.active.contains(&event.id) || runner.is_finished() {
                runner.start(event.commands.clone());
            }
            self.active.insert(event.id);
            main.tick_common_runner(runner, host, budget_per_event);
            if let Some((map, x, y)) = runner.take_teleport() {
                return Err(format!("parallel common event {} requested unwired teleport {map} at {x},{y}", event.id));
            }
            if matches!(runner.blocking(), Some(crate::interpreter::Blocking::Message(_) | crate::interpreter::Blocking::Choices(_))) {
                return Err(format!("parallel common event {} requires unwired foreground UI ownership", event.id));
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wolfformat::event_command::EventCommand;
    struct TestHost;
    impl Host for TestHost {}
    fn definition(id: i32, mode: i32) -> CommonEvent {
        CommonEvent { id, run_condition: mode, condition_raw: 0x20 | mode,
            condition_variable: Some(2_000_010), condition_value: Some(1),
            condition_block: vec![], title: format!("event{id}"), commands: vec![EventCommand {
                command_type: 121, param_count: 5, params: vec![7, 1, 0, 0x100],
                branch_depth: 0, strings: vec![], route: None,
            }] }
    }
    #[test]
    fn parallel_ui_request_returns_error_after_restoring_foreground_banks() {
        let mut main = Interpreter::new(60);
        main.variables.insert(99, 123);
        main.set_database(crate::interpreter::database::Database::default());
        let mut event = definition(1, 3);
        event.commands = vec![EventCommand { command_type: 101, param_count: 1,
            params: vec![], branch_depth: 0, strings: vec!["parallel message".into()], route: None }];
        let mut runners = CommonRunners { definitions: vec![event], ..Default::default() };
        let error = runners.tick(&mut main, &mut TestHost, 100).unwrap_err();
        assert!(error.contains("common event 1"));
        assert!(error.contains("UI ownership"));
        assert_eq!(main.variables.get(&99), Some(&123));
        assert!(main.database().is_some());
        assert!(runners.runners.get(&1).unwrap().database().is_none());
    }

    #[test]
    fn always_runner_restarts_and_conditional_runner_uses_live_globals() {
        let mut main = Interpreter::new(60);
        let mut runners = CommonRunners { definitions: vec![definition(1, 3), definition(2, 2)], ..Default::default() };
        runners.tick(&mut main, &mut TestHost, 100).unwrap();
        assert_eq!(main.variables.get(&7), Some(&1));
        main.variables.insert(10, 1);
        runners.tick(&mut main, &mut TestHost, 100).unwrap();
        assert_eq!(main.variables.get(&7), Some(&3));
        main.variables.insert(10, 0);
        runners.tick(&mut main, &mut TestHost, 100).unwrap();
        assert_eq!(main.variables.get(&7), Some(&4));
        assert!(!runners.active.contains(&2));
    }
    #[test]
    fn undecoded_predicate_stops_before_any_runner_executes() {
        let mut main = Interpreter::new(60);
        let mut conditional = definition(2, 2);
        conditional.condition_variable = None;
        let mut runners = CommonRunners { definitions: vec![definition(1, 3), conditional], ..Default::default() };
        assert!(runners.tick(&mut main, &mut TestHost, 100).is_err());
        assert!(main.variables.is_empty());
        assert!(runners.runners.is_empty());
    }
}
