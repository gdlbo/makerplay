//! Foreground map autorun selection. Conditions are evaluated in each event's
//! variable context; callers provide that lookup rather than sharing self banks.
use std::collections::HashMap;
use crate::wolfformat::map::{MapEvent, MapPage};

#[derive(Default)]
pub struct EventScheduler {
    started: HashMap<i32, usize>,
}

impl EventScheduler {
    pub fn reset(&mut self) { self.started.clear(); }

    /// Re-evaluates every event before selecting one, so an error never partially
    /// updates activation state. File order determines foreground priority.
    pub fn next_autorun<'a>(&mut self, events: &'a [MapEvent], mut read: impl FnMut(i32, i32) -> i32)
        -> Result<Option<(i32, usize, &'a MapPage)>, String>
    {
        let mut active = Vec::with_capacity(events.len());
        for event in events {
            let page = event.active_page(&mut |variable| read(event.event_id, variable))
                .map_err(|error| format!("event {}: {error}", event.event_id))?;
            active.push((event.event_id, page));
        }
        self.started.retain(|id, page| active.iter().any(|(current_id, current)| {
            current_id == id && current.as_ref().is_some_and(|(index, _)| index == page)
        }));
        Ok(active.into_iter().find_map(|(id, page)| {
            let (index, page) = page?;
            (page.trigger_condition == 1 && self.started.get(&id) != Some(&index))
                .then_some((id, index, page))
        }))
    }

    /// Call only after the interpreter accepted the selected command list.
    pub fn mark_started(&mut self, event: i32, page: usize) { self.started.insert(event, page); }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wolfformat::event_command::MoveRoute;
    fn event(id: i32) -> MapEvent {
        MapEvent { event_id: id, title: String::new(), x: 0, y: 0, pages: vec![MapPage {
            graphic_chip_id: -1, graphic_file: String::new(), graphic_row: 0, graphic_col: 0,
            graphic_opacity: 255, trigger_condition: 1, trigger_switches: [0x21, 0, 0, 0],
            trigger_variables: [2_000_007, 0, 0, 0], trigger_values: [1, 0, 0, 0], commands: vec![],
            route: MoveRoute { animation_frequency: 0, move_speed: 0, move_frequency: 0,
                move_route_mode: 0, behavior_options_raw: 0, route_options_raw: 0, steps: vec![] },
            shadow_graphic_id: 0, range_extension_x: 0, range_extension_y: 0,
        }] }
    }
    #[test]
    fn app_starts_only_one_event_and_preserves_it_while_blocked() {
        use crate::wolfformat::event_command::EventCommand;
        let mut events = [event(10), event(11)];
        events[0].pages[0].commands.push(EventCommand {
            command_type: 101, param_count: 1, params: vec![], branch_depth: 0,
            strings: vec!["foreground".into()], route: None,
        });
        let mut app = crate::app::AppState::new(320, 240);
        assert_eq!(app.start_next_autorun(&events, |_, _| 1).unwrap(), Some((10, 0)));
        app.step_native(&[]);
        assert_eq!(app.start_next_autorun(&events, |_, _| 1).unwrap(), None);
        assert!(matches!(app.interpreter.blocking(), Some(crate::interpreter::Blocking::Message(_))));
        app.interpreter.advance();
        app.step_native(&[]);
        assert_eq!(app.start_next_autorun(&events, |_, _| 1).unwrap(), Some((11, 0)));
    }

    #[test]
    fn invalid_condition_does_not_partially_rearm_started_events() {
        let mut events = [event(10), event(11)];
        let mut scheduler = EventScheduler::default();
        scheduler.mark_started(10, 0);
        events[1].pages[0].trigger_switches[0] = 0xF1;
        let error = scheduler.next_autorun(&events, |_, _| 0).unwrap_err();
        assert!(error.contains("event 11"));
        assert_eq!(scheduler.started.get(&10), Some(&0));
        events[1].pages[0].trigger_switches[0] = 0x21;
        assert_eq!(scheduler.next_autorun(&events, |_, _| 1).unwrap().unwrap().0, 11);
    }

    #[test]
    fn non_autorun_pages_never_enter_foreground_autorun_queue() {
        let mut events = [event(10), event(11)];
        events[0].pages[0].trigger_condition = 2;
        events[1].pages[0].trigger_condition = 0;
        assert!(EventScheduler::default().next_autorun(&events, |_, _| 1).unwrap().is_none());
    }

    #[test]
    fn foreground_priority_does_not_discard_later_autoruns() {
        let events = [event(10), event(11)];
        let mut scheduler = EventScheduler::default();
        assert_eq!(scheduler.next_autorun(&events, |_, _| 1).unwrap().unwrap().0, 10);
        scheduler.mark_started(10, 0);
        assert_eq!(scheduler.next_autorun(&events, |_, _| 1).unwrap().unwrap().0, 11);
        scheduler.mark_started(11, 0);
        assert!(scheduler.next_autorun(&events, |_, _| 1).unwrap().is_none());
        scheduler.reset();
        assert_eq!(scheduler.next_autorun(&events, |_, _| 1).unwrap().unwrap().0, 10);
    }
    #[test]
    fn deactivation_rearms_autorun_and_lookup_keeps_event_context() {
        let events = [event(10), event(11)];
        let mut scheduler = EventScheduler::default();
        let found = scheduler.next_autorun(&events, |id, var| { assert_eq!(var, 7); i32::from(id == 11) }).unwrap().unwrap();
        assert_eq!(found.0, 11);
        scheduler.mark_started(11, 0);
        assert!(scheduler.next_autorun(&events, |_, _| 0).unwrap().is_none());
        assert_eq!(scheduler.next_autorun(&events, |_, _| 1).unwrap().unwrap().0, 10);
        scheduler.mark_started(10, 0);
        assert_eq!(scheduler.next_autorun(&events, |_, _| 1).unwrap().unwrap().0, 11);
    }
}
