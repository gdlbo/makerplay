//! `CommonEvent.dat`.
//!
//! Port of `core/wolfformat/CommonEventDat.kt`.
//!
//! Two framings exist. v3.5 files mark themselves with revision `0xCC` or `0x93`
//! at offset 10 and then LZ4-pack everything after the 11-byte header; older
//! files store the events inline. The v3.5 event record also has a different
//! shape (an `0x8E` header byte, a 7-byte condition block, and editor name/memo
//! strings before the metadata). Both are handled below.
//!
//! Note the tag is `'F' 'C' 0x00`, not the `'F' 'M'` used by the database
//! family, so this file cannot use `WolfContainer::read_standard_dat_header`.

use super::bounded::{BoundedReader, FormatError, Result};
use super::container::WolfContainer;
use super::event_command::EventCommand;
use super::lz4;

const EVENT_HEADER: u8 = 0x8E;
const SEPARATOR_ARGS: u8 = 0x8F;
const SEPARATOR_COLOR: u8 = 0x90;
const SEPARATOR_CSELF: u8 = 0x91;
const SEPARATOR_RETURN: u8 = 0x92;
/// Revision bytes that mark a v3.5 (LZ4-packed) file.
const REVISION_V35_A: u8 = 0xCC;
const REVISION_V35_B: u8 = 0x93;
/// cself variable name slots stored per event.
const CSELF_SLOTS: usize = 100;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommonEvent {
    pub id: i32,
    /// 0 call-only, 1 auto start, 2 parallel, 3 parallel always.
    pub run_condition: i32,
    pub title: String,
    pub commands: Vec<EventCommand>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommonEventDat {
    pub v3: bool,
    pub events: Vec<CommonEvent>,
}

impl CommonEventDat {
    pub fn parse(data: &[u8]) -> Result<Self> {
        let mut header = BoundedReader::new(data);
        let v3 = WolfContainer::read_core(&mut header)?;
        let tag = header.bytes(3, "common event tag")?;
        if tag != [b'F', b'C', 0x00] {
            return Err(FormatError("not a CommonEvent.dat file".into()));
        }
        let revision = header.u8()?;
        let v35 = revision == REVISION_V35_A || revision == REVISION_V35_B;

        if v35 {
            let decompressed_size = header.u32()? as usize;
            let compressed_size = header.u32()? as usize;
            if compressed_size > header.remaining() {
                return Err(FormatError(
                    "invalid v3.5 common event compression header".into(),
                ));
            }
            let compressed = header.bytes(compressed_size, "v3.5 common event payload")?;
            let body = lz4::decompress(compressed, decompressed_size)?;
            let mut payload = BoundedReader::new(&body);
            let count = payload.count("common event")?;
            let mut events = Vec::with_capacity(count);
            for _ in 0..count {
                events.push(read_event_v35(&mut payload, v3)?);
            }
            let footer = payload.u8()?;
            if footer < 0x89 {
                return Err(FormatError(format!(
                    "invalid v3.5 common event footer 0x{footer:02x}"
                )));
            }
            Ok(Self { v3, events })
        } else {
            let count = header.count("common event")?;
            let mut events = Vec::with_capacity(count);
            for _ in 0..count {
                events.push(read_event(&mut header, v3)?);
            }
            WolfContainer::read_footer(&mut header, &[0x8F, 0x90, 0x91, 0x92])?;
            Ok(Self { v3, events })
        }
    }
}

/// v3.5 record: header byte, id, packed condition, then commands and metadata.
fn read_event_v35(r: &mut BoundedReader<'_>, v3: bool) -> Result<CommonEvent> {
    if r.u8()? != EVENT_HEADER {
        return Err(FormatError("common event missing header byte".into()));
    }
    let id = r.i32()?;
    let condition_raw = r.i32()?;
    r.skip(7, "common event condition block")?;
    let title = r.string(v3)?;
    let commands = EventCommand::parse_list(r, v3, true)?;
    r.string(v3)?; // editor name
    r.string(v3)?; // memo
    require_separator(r, SEPARATOR_ARGS, "arguments", id)?;
    read_event_metadata(r, v3, id)?;
    Ok(CommonEvent {
        id,
        run_condition: condition_raw & 0xF,
        title,
        commands,
    })
}

/// Pre-3.5 record: one byte of 4-bit fields, condition values, then the script.
fn read_event(r: &mut BoundedReader<'_>, v3: bool) -> Result<CommonEvent> {
    let id = r.i32()?;
    // Operator in the high nibble, run condition in the low nibble.
    let run_condition = (r.u8()? & 0xF) as i32;
    if !(0..=3).contains(&run_condition) {
        return Err(FormatError(format!(
            "common event {id} has invalid run condition {run_condition}"
        )));
    }
    r.u32()?; // condition variable
    r.u32()?; // condition value
    let number_args = r.u8()?;
    let string_args = r.u8()?;
    if number_args > 64 || string_args > 64 {
        return Err(FormatError(format!(
            "common event {id} has too many arguments"
        )));
    }
    let title = r.string(v3)?;
    let commands = EventCommand::parse_list(r, v3, false)?;

    r.skip(5, "common event unknown block")?;
    r.string(v3)?; // memo
    require_separator(r, SEPARATOR_ARGS, "arguments", id)?;
    read_event_metadata(r, v3, id)?;

    Ok(CommonEvent {
        id,
        run_condition,
        title,
        commands,
    })
}

/// The editor-only metadata tail shared by both framings.
fn read_event_metadata(r: &mut BoundedReader<'_>, v3: bool, id: i32) -> Result<()> {
    read_string_array(r, v3)?; // argument names
    let mode_pages = r.count("special specification mode page")?;
    r.skip(mode_pages, "special specification mode")?;
    let option_string_pages = r.count("option string page")?;
    for _ in 0..option_string_pages {
        read_string_array(r, v3)?;
    }
    let option_value_pages = r.count("option value page")?;
    for _ in 0..option_value_pages {
        read_i32_array(r)?;
    }
    read_i32_array(r)?; // argument number default values

    require_separator(r, SEPARATOR_COLOR, "color", id)?;
    r.u32()?; // colour
    for _ in 0..CSELF_SLOTS {
        r.string(v3)?;
    }
    require_separator(r, SEPARATOR_CSELF, "cself", id)?;
    r.string(v3)?; // cself name
    let return_separator = r.u8()?;
    if return_separator == SEPARATOR_RETURN {
        r.string(v3)?; // return name
        r.u32()?; // return value id
        require_separator(r, SEPARATOR_RETURN, "return end", id)?;
    } else if return_separator != SEPARATOR_CSELF {
        return Err(FormatError(format!(
            "common event {id} has invalid return separator 0x{return_separator:02x}"
        )));
    }
    Ok(())
}

fn require_separator(
    r: &mut BoundedReader<'_>,
    expected: u8,
    what: &str,
    id: i32,
) -> Result<()> {
    let actual = r.u8()?;
    if actual != expected {
        return Err(FormatError(format!(
            "common event {id} missing '{what}' separator \
             (expected 0x{expected:02x}, got 0x{actual:02x})"
        )));
    }
    Ok(())
}

fn read_string_array(r: &mut BoundedReader<'_>, v3: bool) -> Result<Vec<String>> {
    let count = r.count("string array")?;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        out.push(r.string(v3)?);
    }
    Ok(out)
}

fn read_i32_array(r: &mut BoundedReader<'_>) -> Result<Vec<i32>> {
    let count = r.count("i32 array")?;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        out.push(r.i32()?);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a pre-3.5 file with the given events.
    fn build_legacy(events: &[(i32, &str)]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&WolfContainer::CORE_MAGIC);
        v.push(WolfContainer::VERSION_V3);
        v.extend_from_slice(&[b'F', b'C', 0x00]);
        v.push(0xCE); // a revision that is not v3.5
        v.extend_from_slice(&(events.len() as u32).to_le_bytes());
        for (id, title) in events {
            v.extend_from_slice(&id.to_le_bytes());
            v.push(1); // run condition 1, operator 0
            v.extend_from_slice(&0u32.to_le_bytes());
            v.extend_from_slice(&0u32.to_le_bytes());
            v.push(0); // no number args
            v.push(0); // no string args
            let mut b = title.as_bytes().to_vec();
            b.push(0);
            v.extend_from_slice(&(b.len() as u32).to_le_bytes());
            v.extend_from_slice(&b);
            v.extend_from_slice(&0u32.to_le_bytes()); // no commands
            v.extend_from_slice(&[1, 0, 0, 0, 0]); // unknown block
            v.extend_from_slice(&1u32.to_le_bytes()); // memo
            v.push(0);
            v.push(SEPARATOR_ARGS);
            metadata(&mut v);
        }
        v.push(0x8F);
        v
    }

    fn metadata(v: &mut Vec<u8>) {
        v.extend_from_slice(&0u32.to_le_bytes()); // string array
        v.extend_from_slice(&0u32.to_le_bytes()); // mode pages
        v.extend_from_slice(&0u32.to_le_bytes()); // option strings
        v.extend_from_slice(&0u32.to_le_bytes()); // option values
        v.extend_from_slice(&0u32.to_le_bytes()); // i32 array
        v.push(SEPARATOR_COLOR);
        v.extend_from_slice(&0u32.to_le_bytes()); // colour
        for _ in 0..CSELF_SLOTS {
            v.extend_from_slice(&1u32.to_le_bytes());
            v.push(0);
        }
        v.push(SEPARATOR_CSELF);
        v.extend_from_slice(&1u32.to_le_bytes()); // cself name
        v.push(0);
        v.push(0x91); // return separator: cself marker means "no return value"
    }

    #[test]
    fn parses_legacy_events_and_titles() {
        let data = build_legacy(&[(1, "main01"), (45, "■バトル")]);
        let parsed = CommonEventDat::parse(&data).unwrap();
        assert!(parsed.v3);
        assert_eq!(parsed.events.len(), 2);
        assert_eq!(parsed.events[0].id, 1);
        assert_eq!(parsed.events[0].title, "main01");
        assert_eq!(parsed.events[0].run_condition, 1);
        assert_eq!(parsed.events[1].title, "■バトル");
        assert!(parsed.events[0].commands.is_empty());
    }

    #[test]
    fn recognises_both_v35_revision_bytes() {
        for revision in [REVISION_V35_A, REVISION_V35_B] {
            let mut v = Vec::new();
            v.extend_from_slice(&WolfContainer::CORE_MAGIC);
            v.push(WolfContainer::VERSION_V3);
            v.extend_from_slice(&[b'F', b'C', 0x00]);
            v.push(revision);
            // A payload that decompresses to "zero events + footer".
            let body = [0u8, 0, 0, 0, 0x8F];
            let compressed = literal_block(&body);
            v.extend_from_slice(&(body.len() as u32).to_le_bytes());
            v.extend_from_slice(&(compressed.len() as u32).to_le_bytes());
            v.extend_from_slice(&compressed);

            let parsed = CommonEventDat::parse(&v)
                .unwrap_or_else(|e| panic!("revision 0x{revision:02x} rejected: {e}"));
            assert!(parsed.events.is_empty());
        }
    }

    /// A literal-only LZ4 block, enough for these fixtures.
    fn literal_block(payload: &[u8]) -> Vec<u8> {
        let mut out = vec![(payload.len() as u8) << 4];
        out.extend_from_slice(payload);
        out
    }

    #[test]
    fn rejects_a_wrong_tag() {
        let mut data = build_legacy(&[(1, "x")]);
        data[7] = b'M';
        assert!(CommonEventDat::parse(&data).is_err());
    }

    #[test]
    fn rejects_a_bad_footer() {
        let mut data = build_legacy(&[(1, "x")]);
        let last = data.len() - 1;
        data[last] = 0x00;
        assert!(CommonEventDat::parse(&data).is_err());
    }

    #[test]
    fn rejects_an_invalid_run_condition() {
        let mut data = build_legacy(&[(1, "x")]);
        // The run-condition byte sits right after the 4-byte id of the first
        // event, which follows the 11-byte header plus the count.
        data[11 + 4 + 4] = 9;
        assert!(CommonEventDat::parse(&data).is_err());
    }
}
