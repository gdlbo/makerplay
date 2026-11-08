//! Resource-backed sound requests. File/fade/control forms remain explicit errors.
use crate::interpreter::database::{Database, Kind};
use crate::wolfformat::event_command::EventCommand;
#[derive(Debug, Clone, PartialEq)]
pub struct SoundRequest {
    pub channel: i32,
    pub resource_id: i32,
    pub data_path: String,
    pub volume_percent: i32,
    pub frequency_percent: i32,
    pub fade_frames: i32,
    pub looping: bool,
}

pub fn resolve_resource(command: &EventCommand, database: &Database) -> Result<SoundRequest, String> {
    if command.command_type != 140 { return Err("not a sound command".into()); }
    if command.params.iter().skip(2).any(|value| *value != 0) {
        return Err("extended sound controls are not implemented".into());
    }
    let options = *command.params.first().ok_or("sound command has no options")?;
    let channel = (options >> 4) & 15;
    let resource_id = (options >> 8) & 0xFFFF;
    let stop = resource_id == 0xFFFF;
    if (!stop && options & 0xFF00_0000u32 as i32 != 0) || options & 15 != 0 {
        return Err(format!("unsupported sound source/control options {options:#x}"));
    }
    if channel > 2 { return Err(format!("unsupported sound channel {channel}")); }
    let fade_frames = command.params.get(1).copied().unwrap_or(0).max(0);
    if !stop && fade_frames != 0 {
        return Err(format!("sound fade timing is not implemented: params={:?}", command.params));
    }
    if stop { return Ok(SoundRequest {channel,resource_id,data_path:String::new(),volume_percent:100,frequency_percent:100,fade_frames,looping:false}); }
    let type_index = (channel + 1) as usize;
    let bank = database.bank(Kind::System).ok_or("sound system database is absent")?;
    let sound_type = bank.types.get(type_index).ok_or("sound resource list is absent")?;
    if sound_type.entries.get(resource_id as usize).is_none() {
        return Err(format!("sound resource {resource_id} is absent from channel {channel}"));
    }
    if !sound_type.is_string_field(0) || !sound_type.is_number_field(1) || !sound_type.is_number_field(2) {
        return Err("invalid sound resource field layout".into());
    }
    let name = sound_type.read_string(resource_id as usize, 0).replace('\\', "/");
    if name.is_empty() { return Err(format!("sound resource {resource_id} has no file")); }
    let percent = |field| {
        let raw = sound_type.read_number(resource_id as usize, field);
        if raw == 0 { 100 } else { raw }
    };
    Ok(SoundRequest { channel, resource_id,
        data_path: if name.starts_with("Data/") { name } else { format!("Data/{name}") },
        volume_percent: percent(1), frequency_percent: percent(2), fade_frames, looping: channel < 2 })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::interpreter::database::{Bank, TypeState, EntryState};
    #[test]
    fn sound_list_resolves_path_defaults_and_channel() {
        let blank = || TypeState::new(String::new(), vec![], vec![], vec![], vec![]);
        let sound = TypeState::new("SE".into(), vec![], vec![], vec![2000, 1000, 1001],
            vec![EntryState { numbers: vec![0, 0], strings: vec!["SystemFile/test.mp3".into()] }]);
        let db = Database::new(None, Some(Bank::new(vec![blank(), blank(), blank(), sound])), None);
        let mut command = EventCommand { command_type: 140, param_count: 3,
            params: vec![0x20, 0], branch_depth: 0, strings: vec![], route: None };
        let sound = resolve_resource(&command, &db).unwrap();
        assert_eq!(sound.data_path, "Data/SystemFile/test.mp3");
        assert_eq!((sound.volume_percent, sound.frequency_percent), (100, 100));
        assert!(!sound.looping);
        command.params[0] = 0x120;
        assert!(resolve_resource(&command, &db).is_err());
        command.params[0] = -224; // 0xFFFFFF20, stop all SE voices.
        assert_eq!(resolve_resource(&command, &db).unwrap().resource_id, 65535);
        command.params[0] = -256;
        command.params[1] = 30;
        assert_eq!(resolve_resource(&command, &db).unwrap().fade_frames, 30);
        command.params[1] = 0;
        command.params[0] = 0x0200_0020;
        assert!(resolve_resource(&command, &db).is_err());
    }
}
