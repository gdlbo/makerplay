//! Event command decoding, shared by map events and common events.
//!
//! Port of `core/wolfformat/EventCommand.kt`. A command is:
//! `u1 param_count`, then when `param_count > 0`:
//! `u4 command_type`, `param_count - 1` raw `i32` parameters, `u1 branch_depth`,
//! `u1 string_count` + strings, `u1 has_route` + optional route, and for v3.5
//! files `u1 metadata_length` + that many bytes.
//!
//! Parameters stay raw here; the interpreter decodes them per opcode.

use super::bounded::{BoundedReader, FormatError, Result};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EventCommand {
    /// 0 means a blank padding line.
    pub param_count: u8,
    pub command_type: i32,
    /// Raw parameter words, excluding the command type itself.
    pub params: Vec<i32>,
    pub branch_depth: u8,
    pub strings: Vec<String>,
    pub route: Option<MoveRoute>,
}

impl EventCommand {
    pub fn is_blank(&self) -> bool {
        self.param_count == 0
    }

    /// Reads one command. `v35` selects the v3.5 trailing metadata block, which
    /// is present only in maps at revision 0x67 and above.
    pub fn parse(reader: &mut BoundedReader<'_>, v3: bool, v35: bool) -> Result<Self> {
        let param_count = reader.u8()?;
        if param_count == 0 {
            return Ok(Self {
                param_count: 0,
                command_type: 0,
                params: Vec::new(),
                branch_depth: 0,
                strings: Vec::new(),
                route: None,
            });
        }
        let command_type = reader.i32()?;
        let remaining = param_count as usize - 1;
        let mut params = Vec::with_capacity(remaining);
        for _ in 0..remaining {
            params.push(reader.i32()?);
        }
        let branch_depth = reader.u8()?;
        let string_count = reader.u8()? as usize;
        let mut strings = Vec::with_capacity(string_count);
        for _ in 0..string_count {
            strings.push(reader.string(v3)?);
        }
        let has_route = reader.u8()?;
        let route = if has_route != 0 {
            Some(MoveRoute::parse(reader)?)
        } else {
            None
        };
        if v35 {
            let extra = reader.u8()? as usize;
            reader.skip(extra, "v3.5 event command metadata")?;
        }
        Ok(Self {
            param_count,
            command_type,
            params,
            branch_depth,
            strings,
            route,
        })
    }

    pub fn parse_list(reader: &mut BoundedReader<'_>, v3: bool, v35: bool) -> Result<Vec<Self>> {
        let count = reader.count("event command")?;
        let mut out = Vec::with_capacity(count);
        for _ in 0..count {
            out.push(Self::parse(reader, v3, v35)?);
        }
        Ok(out)
    }
}

/// Custom move route attached to a command. Steps stay raw.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MoveRoute {
    pub animation_frequency: u8,
    pub move_speed: u8,
    pub move_frequency: u8,
    pub move_route_mode: u8,
    pub behavior_options_raw: u8,
    pub route_options_raw: u8,
    pub steps: Vec<RouteStep>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RouteStep {
    pub step_type: u8,
    pub args_u4: Vec<i32>,
    pub args_u1: Vec<u8>,
}

impl MoveRoute {
    pub fn parse(reader: &mut BoundedReader<'_>) -> Result<Self> {
        let animation_frequency = reader.u8()?;
        let move_speed = reader.u8()?;
        let move_frequency = reader.u8()?;
        let move_route_mode = reader.u8()?;
        let behavior_options_raw = reader.u8()?;
        let route_options_raw = reader.u8()?;
        let step_count = reader.count("route step")?;
        let mut steps = Vec::with_capacity(step_count);
        for _ in 0..step_count {
            let step_type = reader.u8()?;
            let u4_len = reader.u8()? as usize;
            if u4_len > 8 {
                return Err(FormatError(format!(
                    "route step with {u4_len} u4 args"
                )));
            }
            let mut args_u4 = Vec::with_capacity(u4_len);
            for _ in 0..u4_len {
                args_u4.push(reader.i32()?);
            }
            let u1_len = reader.u8()? as usize;
            if u1_len > 8 {
                return Err(FormatError(format!(
                    "route step with {u1_len} u1 args"
                )));
            }
            let mut args_u1 = Vec::with_capacity(u1_len);
            for _ in 0..u1_len {
                args_u1.push(reader.u8()?);
            }
            steps.push(RouteStep {
                step_type,
                args_u4,
                args_u1,
            });
        }
        Ok(Self {
            animation_frequency,
            move_speed,
            move_frequency,
            move_route_mode,
            behavior_options_raw,
            route_options_raw,
            steps,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds one command: blank, or with params/strings.
    fn build(type_: i32, params: &[i32], strings: &[&str], depth: u8) -> Vec<u8> {
        if type_ == 0 && params.is_empty() && strings.is_empty() {
            return vec![0u8];
        }
        let mut v = Vec::new();
        v.push((params.len() + 1) as u8);
        v.extend_from_slice(&type_.to_le_bytes());
        for p in params {
            v.extend_from_slice(&p.to_le_bytes());
        }
        v.push(depth);
        v.push(strings.len() as u8);
        for s in strings {
            let mut b = s.as_bytes().to_vec();
            b.push(0);
            v.extend_from_slice(&(b.len() as u32).to_le_bytes());
            v.extend_from_slice(&b);
        }
        v.push(0); // no route
        v
    }

    #[test]
    fn parses_a_blank_line() {
        let data = [0u8];
        let mut r = BoundedReader::new(&data);
        let c = EventCommand::parse(&mut r, true, false).unwrap();
        assert!(c.is_blank());
        assert_eq!(c.command_type, 0);
        assert_eq!(r.remaining(), 0);
    }

    #[test]
    fn parses_a_message_command() {
        let data = build(101, &[], &["hello"], 0);
        let mut r = BoundedReader::new(&data);
        let c = EventCommand::parse(&mut r, true, false).unwrap();
        assert_eq!(c.command_type, 101);
        assert_eq!(c.param_count, 1);
        assert!(c.params.is_empty());
        assert_eq!(c.strings, vec!["hello".to_string()]);
        assert_eq!(r.remaining(), 0);
    }

    #[test]
    fn parses_parameters_and_branch_depth() {
        let data = build(111, &[1, 2_000_003, 10, 2], &[], 3);
        let mut r = BoundedReader::new(&data);
        let c = EventCommand::parse(&mut r, true, false).unwrap();
        assert_eq!(c.command_type, 111);
        assert_eq!(c.params, vec![1, 2_000_003, 10, 2]);
        assert_eq!(c.branch_depth, 3);
        assert!(c.strings.is_empty());
    }

    #[test]
    fn parses_a_list() {
        let mut data = Vec::new();
        data.extend_from_slice(&2u32.to_le_bytes());
        data.extend_from_slice(&build(101, &[], &["a"], 0));
        data.extend_from_slice(&build(0, &[], &[], 0));
        let mut r = BoundedReader::new(&data);
        let list = EventCommand::parse_list(&mut r, true, false).unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].command_type, 101);
        assert!(list[1].is_blank());
    }

    #[test]
    fn consumes_the_v35_metadata_block() {
        // v3.5 commands carry a trailing length-prefixed metadata blob.
        let mut data = build(101, &[], &["a"], 0);
        data.push(3); // metadata length
        data.extend_from_slice(&[0xAA, 0xBB, 0xCC]);
        let mut r = BoundedReader::new(&data);
        let c = EventCommand::parse(&mut r, true, true).unwrap();
        assert_eq!(c.command_type, 101);
        assert_eq!(r.remaining(), 0, "metadata bytes must be consumed");
    }

    #[test]
    fn rejects_a_truncated_command() {
        // claims 3 params but supplies none
        let data = [4u8, 101, 0, 0, 0];
        let mut r = BoundedReader::new(&data);
        assert!(EventCommand::parse(&mut r, true, false).is_err());
    }
}
