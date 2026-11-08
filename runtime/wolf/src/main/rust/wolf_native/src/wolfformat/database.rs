//! `DataBase.dat` / `CDataBase.dat` / `SysDatabase.dat`.
//!
//! Port of `core/wolfformat/DataBaseDat.kt`, including the two corrections found
//! while getting this deployment to parse:
//!
//! 1. The optional type string is read **after** the field count, not before it:
//!    `[TYPE_HEADER][method][fieldCount][optional string][positions...]`.
//!    Reading it early desynchronises the stream.
//! 2. Every position below `STRING_START` is a number slot, including unused ones
//!    below `INT_START`; they still occupy a number position in each entry.

use super::bounded::{BoundedReader, FormatError, Result};
use super::container::WolfContainer;
use super::lz4;

/// Little-endian `FE FF FF FF`, read unsigned.
const TYPE_HEADER: u32 = 0xFFFF_FFFE;
/// When the method word equals this, a type string follows the field count.
pub const STRING_INDICATOR: u32 = 0x0001_D4C0;
pub const INT_START: u32 = 1_000;
pub const STRING_START: u32 = 2_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DbEntry {
    pub numbers: Vec<i32>,
    pub strings: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DbType {
    pub data_id_method: u32,
    pub data_id_string: Option<String>,
    /// Per-field encoding: `1000+` number slot, `2000+` string slot.
    pub property_positions: Vec<u32>,
    pub entries: Vec<DbEntry>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataBaseDat {
    pub v3: bool,
    pub types: Vec<DbType>,
}

impl DataBaseDat {
    pub fn parse(data: &[u8]) -> Result<Self> {
        // v3.5 databases (revision 0xC4 at offset 10) LZ4-pack the body from
        // offset 11 onward, keeping the standard 11-byte header prefix.
        let working: Vec<u8> = if data.len() > 10 && data[10] == WolfContainer::FOOTER_V33 {
            let mut head = BoundedReader::at(data, 11)?;
            let decompressed_size = head.u32()? as usize;
            let compressed_size = head.u32()? as usize;
            if compressed_size > head.remaining() {
                return Err(FormatError(
                    "invalid v3.5 database compression header".into(),
                ));
            }
            let compressed = head.bytes(compressed_size, "v3.5 database payload")?;
            let body = lz4::decompress(compressed, decompressed_size)?;
            let mut out = data[..11].to_vec();
            out.extend_from_slice(&body);
            out
        } else {
            data.to_vec()
        };

        let mut r = BoundedReader::new(&working);
        let v3 = WolfContainer::read_standard_dat_header(&mut r)?;
        let count = r.count("database type")?;
        let mut types = Vec::with_capacity(count);
        for _ in 0..count {
            types.push(read_type(&mut r, v3)?);
        }
        WolfContainer::read_footer(
            &mut r,
            &[
                WolfContainer::FOOTER_V2,
                WolfContainer::FOOTER_V30,
                WolfContainer::FOOTER_V33,
            ],
        )?;
        Ok(Self { v3, types })
    }
}

fn read_type(r: &mut BoundedReader<'_>, v3: bool) -> Result<DbType> {
    if r.u32()? != TYPE_HEADER {
        return Err(FormatError("database type missing sub-header".into()));
    }
    let data_id_method = r.u32()?;
    // Order matters: field count first, then the optional type string.
    let property_count = r.count("property position")?;
    let data_id_string = if data_id_method == STRING_INDICATOR {
        Some(r.string(v3)?)
    } else {
        None
    };

    let mut positions = Vec::with_capacity(property_count);
    for _ in 0..property_count {
        positions.push(r.u32()?);
    }
    let entry_count = r.count("database entry")?;

    // Slot classification: anything below STRING_START is a number slot,
    // including unused ones below INT_START.
    let number_count = positions.iter().filter(|p| **p < STRING_START).count();
    let string_count = positions.iter().filter(|p| **p >= STRING_START).count();

    let mut entries = Vec::with_capacity(entry_count);
    for _ in 0..entry_count {
        let mut numbers = Vec::with_capacity(number_count);
        for _ in 0..number_count {
            numbers.push(r.i32()?);
        }
        let mut strings = Vec::with_capacity(string_count);
        for _ in 0..string_count {
            strings.push(r.string(v3)?);
        }
        entries.push(DbEntry { numbers, strings });
    }

    Ok(DbType {
        data_id_method,
        data_id_string,
        property_positions: positions,
        entries,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db(v3: bool, types: &[(&str, Vec<u32>, usize)], revision: u8) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&WolfContainer::CORE_MAGIC);
        v.push(if v3 { WolfContainer::VERSION_V3 } else { WolfContainer::VERSION_V2 });
        v.extend_from_slice(&[b'F', b'M', 0x00]);
        v.push(revision);
        v.extend_from_slice(&(types.len() as u32).to_le_bytes());
        for (name, positions, entries) in types {
            v.extend_from_slice(&TYPE_HEADER.to_le_bytes());
            let method = if name.is_empty() { 0u32 } else { STRING_INDICATOR };
            v.extend_from_slice(&method.to_le_bytes());
            v.extend_from_slice(&(positions.len() as u32).to_le_bytes());
            if !name.is_empty() {
                let mut b = name.as_bytes().to_vec();
                b.push(0);
                v.extend_from_slice(&(b.len() as u32).to_le_bytes());
                v.extend_from_slice(&b);
            }
            for p in positions {
                v.extend_from_slice(&p.to_le_bytes());
            }
            v.extend_from_slice(&(*entries as u32).to_le_bytes());
            let numbers = positions.iter().filter(|p| **p < STRING_START).count();
            let strings = positions.iter().filter(|p| **p >= STRING_START).count();
            for e in 0..*entries {
                for n in 0..numbers {
                    v.extend_from_slice(&((e * 10 + n) as i32).to_le_bytes());
                }
                for s in 0..strings {
                    let text = format!("s{e}_{s}");
                    let mut b = text.as_bytes().to_vec();
                    b.push(0);
                    v.extend_from_slice(&(b.len() as u32).to_le_bytes());
                    v.extend_from_slice(&b);
                }
            }
        }
        v.push(WolfContainer::FOOTER_V33);
        v
    }

    #[test]
    fn parses_a_single_type() {
        // one number slot and one string slot
        let data = db(true, &[("unit", vec![INT_START, STRING_START], 3)], 0xC3);
        let parsed = DataBaseDat::parse(&data).unwrap();
        assert_eq!(parsed.types.len(), 1);
        let t = &parsed.types[0];
        assert_eq!(t.data_id_string.as_deref(), Some("unit"));
        assert_eq!(t.property_positions, vec![INT_START, STRING_START]);
        assert_eq!(t.entries.len(), 3);
        assert_eq!(t.entries[1].numbers, vec![10]);
        assert_eq!(t.entries[1].strings, vec!["s1_0".to_string()]);
    }

    #[test]
    fn unused_low_positions_still_count_as_number_slots() {
        // position 0 is an unused slot; it must still occupy a number position.
        let data = db(true, &[("t", vec![0, INT_START], 1)], 0xC3);
        let parsed = DataBaseDat::parse(&data).unwrap();
        let t = &parsed.types[0];
        assert_eq!(
            t.entries[0].numbers.len(),
            2,
            "both positions are below STRING_START, so both are number slots"
        );
    }

    #[test]
    fn type_string_is_read_after_the_field_count() {
        // If the string were read before the count, the count would be read from
        // the string bytes and parsing would fail or produce nonsense.
        let data = db(true, &[("BTL_order", vec![INT_START], 2)], 0xC3);
        let parsed = DataBaseDat::parse(&data).unwrap();
        assert_eq!(parsed.types[0].data_id_string.as_deref(), Some("BTL_order"));
        assert_eq!(parsed.types[0].property_positions, vec![INT_START]);
    }

    #[test]
    fn rejects_a_missing_sub_header() {
        let mut data = db(true, &[("t", vec![INT_START], 1)], 0xC3);
        // Corrupt the type header (it starts right after the 11-byte header + count).
        data[15] ^= 0xFF;
        assert!(DataBaseDat::parse(&data).is_err());
    }

    #[test]
    fn rejects_a_bad_footer() {
        let mut data = db(true, &[("t", vec![INT_START], 1)], 0xC3);
        let last = data.len() - 1;
        data[last] = 0x00;
        assert!(DataBaseDat::parse(&data).is_err());
    }
}
