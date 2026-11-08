//! `.project` sidecars for database `.dat` files.
//!
//! Port of `core/wolfformat/DataBaseProject.kt`. The `.dat` file holds the
//! runtime values; the `.project` file holds the human-readable names the
//! name-addressed database commands (`\cdb[name:entry:field]`, opcode 250 with
//! `NAME_*` flags) resolve against. Without it those commands can only use
//! numeric indices.

use super::bounded::{BoundedReader, Result};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DbTypeMeta {
    pub name: String,
    pub field_names: Vec<String>,
    pub entry_names: Vec<String>,
    pub description: String,
    /// Per-field editor type codes (0 = number-like, 1 = string-like, …).
    pub field_types: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataBaseProject {
    pub types: Vec<DbTypeMeta>,
}

impl DataBaseProject {
    pub fn parse(data: &[u8], v3: bool) -> Result<Self> {
        let mut r = BoundedReader::new(data);
        let count = r.count("database project type")?;
        let mut types = Vec::with_capacity(count);
        for _ in 0..count {
            types.push(read_type(&mut r, v3)?);
        }
        if r.remaining() != 0 {
            return Err(super::bounded::FormatError(format!(
                "database project has {} trailing byte(s)",
                r.remaining()
            )));
        }
        Ok(Self { types })
    }
}

fn read_type(r: &mut BoundedReader<'_>, v3: bool) -> Result<DbTypeMeta> {
    let name = r.string(v3)?;
    let field_count = r.count("database project field")?;
    let mut field_names = Vec::with_capacity(field_count);
    for _ in 0..field_count {
        field_names.push(r.string(v3)?);
    }
    let entry_count = r.count("database project entry")?;
    let mut entry_names = Vec::with_capacity(entry_count);
    for _ in 0..entry_count {
        entry_names.push(r.string(v3)?);
    }
    let description = r.string(v3)?;

    let field_type_list_size = r.u32()? as usize;
    if field_type_list_size < field_count {
        return Err(super::bounded::FormatError(format!(
            "invalid field-type list size {field_type_list_size} for {field_count} fields"
        )));
    }
    let mut field_types = Vec::with_capacity(field_count);
    for _ in 0..field_count {
        field_types.push(r.u8()?);
    }
    if field_type_list_size > field_count {
        r.skip(field_type_list_size - field_count, "field-type padding")?;
    }

    // Editor-only records, read for their lengths so the stream stays aligned.
    let unknown_count = r.count("database project field note")?;
    for _ in 0..unknown_count {
        r.string(v3)?;
    }
    let string_args_count = r.count("database project field string-args")?;
    for _ in 0..string_args_count {
        let n = r.count("field string-arg")?;
        for _ in 0..n {
            r.string(v3)?;
        }
    }
    let int_args_count = r.count("database project field int-args")?;
    for _ in 0..int_args_count {
        let n = r.count("field int-arg")?;
        for _ in 0..n {
            r.i32()?;
        }
    }
    let default_count = r.count("database project field default")?;
    for _ in 0..default_count {
        r.i32()?;
    }

    Ok(DbTypeMeta {
        name,
        field_names,
        entry_names,
        description,
        field_types,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn project(types: &[(&str, &[&str], &[&str])]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&(types.len() as u32).to_le_bytes());
        for (name, fields, entries) in types {
            let mut b = name.as_bytes().to_vec();
            b.push(0);
            v.extend_from_slice(&(b.len() as u32).to_le_bytes());
            v.extend_from_slice(&b);

            v.extend_from_slice(&(fields.len() as u32).to_le_bytes());
            for f in *fields {
                let mut b = f.as_bytes().to_vec();
                b.push(0);
                v.extend_from_slice(&(b.len() as u32).to_le_bytes());
                v.extend_from_slice(&b);
            }

            v.extend_from_slice(&(entries.len() as u32).to_le_bytes());
            for e in *entries {
                let mut b = e.as_bytes().to_vec();
                b.push(0);
                v.extend_from_slice(&(b.len() as u32).to_le_bytes());
                v.extend_from_slice(&b);
            }

            v.extend_from_slice(&1u32.to_le_bytes()); // description
            v.push(0);

            v.extend_from_slice(&(fields.len() as u32).to_le_bytes());
            for _ in *fields {
                v.push(0); // field type code
            }
            v.extend_from_slice(&0u32.to_le_bytes()); // notes
            v.extend_from_slice(&0u32.to_le_bytes()); // string args
            v.extend_from_slice(&0u32.to_le_bytes()); // int args
            v.extend_from_slice(&0u32.to_le_bytes()); // defaults
        }
        v
    }

    #[test]
    fn parses_type_field_and_entry_names() {
        let data = project(&[("unit", &["hp", "name"], &["Seris", "Wolf"])]);
        let parsed = DataBaseProject::parse(&data, true).unwrap();
        assert_eq!(parsed.types.len(), 1);
        let t = &parsed.types[0];
        assert_eq!(t.name, "unit");
        assert_eq!(t.field_names, vec!["hp", "name"]);
        assert_eq!(t.entry_names, vec!["Seris", "Wolf"]);
        assert_eq!(t.field_types.len(), 2);
    }

    #[test]
    fn rejects_trailing_bytes() {
        let mut data = project(&[("t", &["f"], &["e"])]);
        data.push(0xFF);
        assert!(DataBaseProject::parse(&data, true).is_err());
    }

    #[test]
    fn rejects_a_field_type_list_shorter_than_the_field_count() {
        let mut data = project(&[("t", &["a", "b"], &["e"])]);
        // Corrupt the field-type list size, which sits after the description.
        let n = data.len();
        // The size word is the 4 bytes immediately before the two type codes,
        // the three zero counts and the defaults count.
        let size_pos = n - (2 + 4 + 4 + 4 + 4) - 4;
        data[size_pos..size_pos + 4].copy_from_slice(&1u32.to_le_bytes());
        assert!(DataBaseProject::parse(&data, true).is_err());
    }
}
