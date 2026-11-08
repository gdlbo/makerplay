//! Database banks, the `250/251/252` command family, and the `255/256/257`
//! XY-array family.
//!
//! Port of `runtime/wolf/src/main/kotlin/.../WolfDatabase.kt`. The opcode
//! semantics come from IDA decompilation of `GamePro.exe` (see
//! `docs/wolf-ida-vm-reference.md`): case 250 of `sub_9E20C0` handles the
//! database assignment, and `sub_A2ACA0` / `sub_A2BD90` / `sub_A2C500` handle the
//! XY-array trio, with `sub_AED340` doing the actual cell write.
//!
//! Two details that are easy to get wrong and are therefore explicit here:
//!
//! * `params[3]` carries four packed fields — operator (bits 4–7), right-hand-side
//!   kind (bits 0–3), mode (bits 8–11) and name-resolution flags (bits 16–19).
//! * XY-array division and modulo substitute **1** for a zero divisor rather than
//!   producing zero. `sub_AED340` does `v12 = a4; if (a4 == 0) v12 = 1;`, which is
//!   a different rule from the database path.

use std::collections::HashMap;

use crate::wolfformat::dbproject::DataBaseProject;
use crate::wolfformat::event_command::EventCommand;
use crate::wolfformat::{DataBaseDat, INT_START, STRING_START};

use super::refs::is_variable_ref;

/// Which database a command addresses.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Kind {
    Changeable,
    System,
    User,
}

impl Kind {
    fn index(self) -> usize {
        match self {
            Kind::Changeable => 0,
            Kind::System => 1,
            Kind::User => 2,
        }
    }
}

/// One database row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryState {
    pub numbers: Vec<i32>,
    pub strings: Vec<String>,
}

/// One database type: its field layout, entry names, and rows.
#[derive(Debug, Clone)]
pub struct TypeState {
    pub name: String,
    pub field_names: Vec<String>,
    pub entry_names: Vec<String>,
    /// Per-field encoding: `1000+` number slot, `2000+` string slot.
    pub positions: Vec<u32>,
    pub entries: Vec<EntryState>,
    fields_by_name: HashMap<String, usize>,
    entries_by_name: HashMap<String, usize>,
}

impl TypeState {
    pub fn new(
        name: String,
        field_names: Vec<String>,
        entry_names: Vec<String>,
        positions: Vec<u32>,
        entries: Vec<EntryState>,
    ) -> Self {
        let fields_by_name = field_names
            .iter()
            .enumerate()
            .map(|(i, n)| (n.clone(), i))
            .collect();
        let entries_by_name = entry_names
            .iter()
            .enumerate()
            .map(|(i, n)| (n.clone(), i))
            .collect();
        Self {
            name,
            field_names,
            entry_names,
            positions,
            entries,
            fields_by_name,
            entries_by_name,
        }
    }

    pub fn field_index(&self, name: &str) -> Option<usize> {
        self.fields_by_name.get(name).copied()
    }

    pub fn entry_index(&self, name: &str) -> Option<usize> {
        self.entries_by_name.get(name).copied()
    }

    pub fn is_string_field(&self, field: usize) -> bool {
        self.positions
            .get(field)
            .map(|p| *p >= STRING_START)
            .unwrap_or(false)
    }

    pub fn is_number_field(&self, field: usize) -> bool {
        self.positions
            .get(field)
            .map(|p| (INT_START..STRING_START).contains(p))
            .unwrap_or(false)
    }

    /// Slot within the number or string pool, or `None` for an unused position.
    pub fn pool_index(&self, field: usize) -> Option<usize> {
        let pos = *self.positions.get(field)?;
        if pos >= STRING_START {
            Some((pos - STRING_START) as usize)
        } else if pos >= INT_START {
            Some((pos - INT_START) as usize)
        } else {
            None
        }
    }

    pub fn read_number(&self, entry: usize, field: usize) -> i32 {
        let Some(e) = self.entries.get(entry) else {
            return 0;
        };
        let Some(slot) = self.pool_index(field) else {
            return 0;
        };
        e.numbers.get(slot).copied().unwrap_or(0)
    }

    pub fn read_string(&self, entry: usize, field: usize) -> String {
        let Some(e) = self.entries.get(entry) else {
            return String::new();
        };
        let Some(slot) = self.pool_index(field) else {
            return String::new();
        };
        e.strings.get(slot).cloned().unwrap_or_default()
    }

    pub fn write_number(&mut self, entry: usize, field: usize, value: i32, operator: i32) {
        self.ensure_entry(entry);
        let Some(slot) = self.pool_index(field) else {
            return;
        };
        let Some(row) = self.entries.get_mut(entry) else {
            return;
        };
        let Some(cell) = row.numbers.get_mut(slot) else {
            return;
        };
        *cell = apply_op(*cell, value, operator);
    }

    pub fn write_string(&mut self, entry: usize, field: usize, value: String) {
        self.ensure_entry(entry);
        let Some(slot) = self.pool_index(field) else {
            return;
        };
        let Some(row) = self.entries.get_mut(entry) else {
            return;
        };
        if let Some(cell) = row.strings.get_mut(slot) {
            *cell = value;
        }
    }

    pub fn clear_values(&mut self) {
        for entry in &mut self.entries {
            for n in &mut entry.numbers {
                *n = 0;
            }
            for s in &mut entry.strings {
                s.clear();
            }
        }
    }

    fn number_pool_size(&self) -> usize {
        self.positions
            .iter()
            .filter(|p| (INT_START..STRING_START).contains(*p))
            .count()
    }

    fn string_pool_size(&self) -> usize {
        self.positions.iter().filter(|p| **p >= STRING_START).count()
    }

    /// Grows the row list so `entry` is addressable, matching the editor runtime.
    fn ensure_entry(&mut self, entry: usize) {
        while self.entries.len() <= entry {
            let numbers = vec![0i32; self.number_pool_size()];
            let strings = vec![String::new(); self.string_pool_size()];
            self.entries.push(EntryState { numbers, strings });
            self.entry_names.push(String::new());
        }
    }
}

/// One database bank (changeable, system or user).
#[derive(Debug, Clone, Default)]
pub struct Bank {
    pub types: Vec<TypeState>,
    types_by_name: HashMap<String, usize>,
}

impl Bank {
    pub fn new(types: Vec<TypeState>) -> Self {
        let types_by_name = types
            .iter()
            .enumerate()
            .map(|(i, t)| (t.name.clone(), i))
            .collect();
        Self {
            types,
            types_by_name,
        }
    }

    pub fn type_index(&self, name: &str) -> Option<usize> {
        self.types_by_name.get(name).copied()
    }

    pub fn type_at(&self, index: usize) -> Option<&TypeState> {
        self.types.get(index)
    }

    pub fn type_at_mut(&mut self, index: usize) -> Option<&mut TypeState> {
        self.types.get_mut(index)
    }
}

/// A 2-D integer array addressed by `(array index, x, y)`.
///
/// `GamePro.exe` grows these lazily (`sub_AED340` extends rows and columns on
/// first write); missing cells read as zero.
#[derive(Debug, Clone, Default)]
pub struct XyArray {
    rows: HashMap<i32, HashMap<i32, i32>>,
}

impl XyArray {
    pub fn read(&self, x: i32, y: i32) -> i32 {
        self.rows
            .get(&x)
            .and_then(|row| row.get(&y))
            .copied()
            .unwrap_or(0)
    }

    pub fn write(&mut self, x: i32, y: i32, value: i32, operator: i32) {
        let row = self.rows.entry(x).or_default();
        let current = row.get(&y).copied().unwrap_or(0);
        // Operators arrive on two scales: the database path hands over the
        // low-byte nibble (0x10/0x20/...) while the engine constants in
        // sub_AED340 are 0x100..0x500. Normalise to the latter.
        let op = if (1..=0x0F).contains(&operator) {
            operator << 8
        } else if operator <= 0xF0 {
            operator << 4
        } else {
            operator
        };
        let result = match op {
            0x100 => current.wrapping_add(value),
            0x200 => current.wrapping_sub(value),
            0x300 => current.wrapping_mul(value),
            // sub_AED340 substitutes 1 for a zero divisor instead of faulting.
            0x400 => current / if value != 0 { value } else { 1 },
            0x500 => current % if value != 0 { value } else { 1 },
            _ => value,
        };
        row.insert(y, result);
    }

    pub fn clear(&mut self) {
        self.rows.clear();
    }
}

/// Packed fields of `params[3]` for the database family.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DbFlags {
    pub operator: i32,
    pub rhs_kind: i32,
    pub mode: i32,
    pub name_flags: i32,
}

pub const MODE_WRITE: i32 = 0x00;
pub const MODE_READ: i32 = 0x10;
pub const RHS_NUMBER: i32 = 0x00;
pub const RHS_INDIRECT: i32 = 0x01;
pub const RHS_STRING: i32 = 0x02;
pub const NAME_TYPE: i32 = 0x01;
pub const NAME_ENTRY: i32 = 0x02;
pub const NAME_FIELD: i32 = 0x04;
/// `0xFFFFFFFD` — the entry/field operand names a type, entry or field.
pub const SENTINEL_NAME: i32 = -3;
/// `0xFFFFFFFE` — clear the type's values.
pub const SENTINEL_CLEAR: i32 = -2;

impl DbFlags {
    pub fn decode(packed: i32) -> Self {
        let op_byte = packed & 0xFF;
        let mode_byte = (packed >> 8) & 0xFF;
        let name_flags = (packed >> 16) & 0x0F;
        // 0x20 in the mode nibble is an alias for write in this engine build.
        let mode = if mode_byte & 0xF0 == 0x20 {
            MODE_WRITE
        } else {
            mode_byte & 0xF0
        };
        Self {
            operator: op_byte & 0xF0,
            rhs_kind: op_byte & 0x0F,
            mode,
            name_flags,
        }
    }

    pub fn db_kind(self, mode_byte: i32) -> Option<Kind> {
        match mode_byte & 0x0F {
            0 => Some(Kind::Changeable),
            1 => Some(Kind::System),
            2 | 3 => Some(Kind::User),
            // GamePro.exe's opcode-250 handler maps banks 0/1/2 and jumps out
            // for anything else; XY arrays are separate opcodes, not a bank.
            _ => None,
        }
    }
}

/// The database model: three banks plus the XY-array store.
#[derive(Debug, Clone, Default)]
pub struct Database {
    changeable: Option<Bank>,
    system: Option<Bank>,
    user: Option<Bank>,
    xy_arrays: HashMap<i32, XyArray>,
}

/// Access to the interpreter's variable banks, so database commands can read and
/// write through variable references without the model depending on the
/// interpreter type.
pub trait VariableAccess {
    fn read_number(&self, raw: i32) -> i32;
    fn write_number(&mut self, raw: i32, value: i32);
    fn read_string(&self, raw: i32) -> String;
    fn write_string(&mut self, raw: i32, value: String);
}

impl Database {
    pub fn new(
        changeable: Option<Bank>,
        system: Option<Bank>,
        user: Option<Bank>,
    ) -> Self {
        Self {
            changeable,
            system,
            user,
            xy_arrays: HashMap::new(),
        }
    }

    pub fn bank(&self, kind: Kind) -> Option<&Bank> {
        match kind {
            Kind::Changeable => self.changeable.as_ref(),
            Kind::System => self.system.as_ref(),
            Kind::User => self.user.as_ref(),
        }
    }

    fn bank_mut(&mut self, kind: Kind) -> Option<&mut Bank> {
        match kind {
            Kind::Changeable => self.changeable.as_mut(),
            Kind::System => self.system.as_mut(),
            Kind::User => self.user.as_mut(),
        }
    }

    /// Number of types in a bank, for diagnostics.
    pub fn type_count(&self, kind: Kind) -> usize {
        self.bank(kind).map(|b| b.types.len()).unwrap_or(0)
    }

    /// Loads all three banks from a deployment's `BasicData` directory.
    ///
    /// A missing `.dat` leaves that bank absent rather than failing: the game
    /// still runs, and a database command against the absent bank is a no-op
    /// exactly as it is in the original when the file is unreadable.
    pub fn load(data_dir: &std::path::Path) -> Self {
        let basic = data_dir.join("BasicData");
        let changeable = load_bank(&basic, "CDataBase");
        let system = load_bank(&basic, "SysDatabase");
        let user = load_bank(&basic, "DataBase");
        Self::new(changeable, system, user)
    }

    /// Strict deployment load for gameplay startup: missing/corrupt banks are errors.
    pub fn load_checked(data_dir: &std::path::Path) -> Result<Self, String> {
        let basic = data_dir.join("BasicData");
        Ok(Self::new(
            Some(load_bank_checked(&basic, "CDataBase")?),
            Some(load_bank_checked(&basic, "SysDatabase")?),
            Some(load_bank_checked(&basic, "DataBase")?),
        ))
    }

    /// Executes a database or XY-array command.
    pub fn execute(&mut self, command: &EventCommand, vars: &mut dyn VariableAccess) {
        match command.command_type {
            250 | 252 => self.execute_assign(command, vars),
            251 => self.import_csv(command),
            255 | 256 | 257 => self.execute_xy_array(command, vars),
            _ => {}
        }
    }

    // --- 250 / 252: database assignment ------------------------------------

    fn execute_assign(&mut self, command: &EventCommand, vars: &mut dyn VariableAccess) {
        let p = &command.params;
        let s = &command.strings;
        if p.len() < 4 {
            return;
        }
        let flags = DbFlags::decode(p[3]);
        let mode_byte = (p[3] >> 8) & 0xFF;
        let Some(db_kind) = flags.db_kind(mode_byte) else {
            return;
        };
        let type_name = s.get(1).cloned().unwrap_or_default();

        // Resolve the type, falling back to a name lookup across banks.
        let resolved = self
            .bank(db_kind)
            .and_then(|bank| resolve_type_index(bank, p[0], &type_name, flags.name_flags))
            .map(|index| (db_kind, index))
            .or_else(|| self.find_named_type(&type_name));
        let Some((target_kind, type_index)) = resolved else {
            return;
        };

        let entry_raw = p.get(1).copied().unwrap_or(0);
        let field_raw = p.get(2).copied().unwrap_or(0);
        if is_meta(entry_raw, field_raw) {
            self.handle_meta(target_kind, type_index, command, &flags, vars);
            return;
        }

        let (entry_index, field_index) = {
            let Some(bank) = self.bank(target_kind) else {
                return;
            };
            let Some(t) = bank.type_at(type_index) else {
                return;
            };
            let entry_name = s.get(2).cloned().unwrap_or_default();
            let field_name = s.get(3).cloned().unwrap_or_default();
            let Some(e) =
                resolve_entry(t, entry_raw, &entry_name, flags.name_flags, vars)
            else {
                return;
            };
            let Some(f) =
                resolve_field(t, field_raw, &field_name, flags.name_flags, vars)
            else {
                return;
            };
            (e, f)
        };

        let Some(bank) = self.bank_mut(target_kind) else {
            return;
        };
        let Some(t) = bank.type_at_mut(type_index) else {
            return;
        };

        if flags.mode == MODE_READ {
            let Some(dest) = p.get(4).copied() else {
                return;
            };
            if t.is_string_field(field_index) {
                let value = t.read_string(entry_index, field_index);
                vars.write_string(dest, value);
            } else {
                let value = t.read_number(entry_index, field_index);
                vars.write_number(dest, value);
            }
            return;
        }

        if flags.mode == MODE_WRITE {
            if t.is_string_field(field_index) || flags.rhs_kind == RHS_STRING {
                let value = if flags.rhs_kind == RHS_STRING {
                    s.first().cloned().unwrap_or_default()
                } else {
                    let raw = p.get(4).copied().unwrap_or(0);
                    vars.read_string(raw)
                };
                // String fields only support overwrite in this subset.
                t.write_string(entry_index, field_index, value);
            } else {
                let raw = p.get(4).copied().unwrap_or(0);
                let value = resolve_rhs_number(raw, flags.rhs_kind, vars);
                t.write_number(entry_index, field_index, value, flags.operator);
            }
        }
    }

    /// Sentinel-addressed reads: names, counts and clearing.
    fn handle_meta(
        &mut self,
        kind: Kind,
        type_index: usize,
        command: &EventCommand,
        flags: &DbFlags,
        vars: &mut dyn VariableAccess,
    ) {
        let p = &command.params;
        let s = &command.strings;
        let entry_raw = p.get(1).copied().unwrap_or(0);
        let field_raw = p.get(2).copied().unwrap_or(0);
        let value_raw = p.get(4).copied().unwrap_or(0);
        let entry_name_hint = s.get(2).cloned().unwrap_or_default();
        let field_name_hint = s.get(3).cloned().unwrap_or_default();

        if flags.mode == MODE_WRITE
            && (entry_raw == SENTINEL_CLEAR || field_raw == SENTINEL_CLEAR)
        {
            if let Some(t) = self.bank_mut(kind).and_then(|b| b.type_at_mut(type_index)) {
                t.clear_values();
            }
            return;
        }
        if flags.mode != MODE_READ {
            return;
        }

        // Reads that need the type by value (names, counts).
        let (type_name, entry_names, field_names, entry_count, field_count) = {
            let Some(t) = self.bank(kind).and_then(|b| b.type_at(type_index)) else {
                return;
            };
            (
                t.name.clone(),
                t.entry_names.clone(),
                t.field_names.clone(),
                t.entries.len(),
                t.field_names.len(),
            )
        };

        if entry_raw == SENTINEL_NAME && field_raw == SENTINEL_NAME {
            // Type name.
            vars.write_string(value_raw, type_name);
        } else if field_raw == SENTINEL_NAME && entry_raw != SENTINEL_CLEAR {
            // Entry name.
            let entry = resolve_literal_or_ref(entry_raw, vars);
            let name = entry_names
                .get(entry.max(0) as usize)
                .cloned()
                .unwrap_or_default();
            vars.write_string(value_raw, name);
        } else if entry_raw == SENTINEL_NAME {
            if !field_name_hint.is_empty() {
                let index = self
                    .bank(kind)
                    .and_then(|b| b.type_at(type_index))
                    .and_then(|t| t.field_index(&field_name_hint))
                    .map(|i| i as i32)
                    .unwrap_or(-1);
                vars.write_number(value_raw, index);
            } else {
                let field = resolve_literal_or_ref(field_raw, vars);
                let name = field_names
                    .get(field.max(0) as usize)
                    .cloned()
                    .unwrap_or_default();
                vars.write_string(value_raw, name);
            }
        } else if field_raw == SENTINEL_NAME && entry_raw == 0 {
            let index = self
                .bank(kind)
                .and_then(|b| b.type_at(type_index))
                .and_then(|t| t.entry_index(&entry_name_hint))
                .map(|i| i as i32)
                .unwrap_or(-1);
            vars.write_number(value_raw, index);
        }

        // Length-style reads: -1 in one operand asks for a count.
        if field_raw == -1 {
            vars.write_number(value_raw, entry_count as i32);
        } else if entry_raw == -1 {
            vars.write_number(value_raw, field_count as i32);
        }
    }

    fn find_named_type(&self, name: &str) -> Option<(Kind, usize)> {
        if name.is_empty() {
            return None;
        }
        // The runtime types the battle scripts address by name (BTL_member, unit,
        // item...) live in CDataBase.dat, which is the *changeable* bank. Search
        // that first so a name that exists in several banks resolves the way the
        // scripts expect.
        for kind in [Kind::Changeable, Kind::User, Kind::System] {
            if let Some(index) = self.bank(kind).and_then(|b| b.type_index(name)) {
                return Some((kind, index));
            }
        }
        None
    }

    // --- 251: CSV import ---------------------------------------------------

    /// Opcode 251. `strings[0]` is a CSV path, `strings[1]` the user-database
    /// type. Rows replace the type's entries.
    fn import_csv(&mut self, command: &EventCommand) {
        let Some(path) = command.strings.first().filter(|s| !s.trim().is_empty()) else {
            return;
        };
        let type_name = command.strings.get(1).cloned().unwrap_or_default();
        let kind = if self.user.is_some() {
            Kind::User
        } else if self.changeable.is_some() {
            Kind::Changeable
        } else {
            return;
        };
        let Some(rows) = read_csv(path) else {
            return;
        };
        let Some(bank) = self.bank_mut(kind) else {
            return;
        };
        // An empty type name means the first type, matching the editor default.
        let index = if type_name.is_empty() {
            0
        } else {
            match bank.type_index(&type_name) {
                Some(i) => i,
                None => return,
            }
        };
        let Some(t) = bank.type_at_mut(index) else {
            return;
        };
        replace_from_rows(t, &rows);
    }

    // --- 255 / 256 / 257: XY arrays ----------------------------------------

    /// XY array access.
    ///
    /// `GamePro.exe` models these as 2-D integer arrays keyed by an array
    /// selector plus x/y (`sub_AED340`). Param roles follow the same slot order
    /// the database path uses: `p[0]` array selector, `p[1]` x/column, `p[2]`
    /// y/row, `p[4]` value or destination. Direction comes from bits 12–15 of the
    /// flags word: 0 reads a cell into `p[4]`, 1..3 are the write forms.
    fn execute_xy_array(&mut self, command: &EventCommand, vars: &mut dyn VariableAccess) {
        let p = &command.params;
        if p.len() < 4 {
            return;
        }
        let flags = p[3];
        let action = (flags >> 12) & 0x0F;
        let operator = (flags >> 4) & 0x0F;
        let array_index = p[0];
        let x = resolve_coordinate(p.get(1).copied().unwrap_or(0), vars);
        let y = resolve_coordinate(p.get(2).copied().unwrap_or(0), vars);
        let array = self.xy_arrays.entry(array_index).or_default();

        if action == 0 {
            let Some(dest) = p.get(4).copied() else {
                return;
            };
            vars.write_number(dest, array.read(x, y));
            return;
        }
        let raw = p.get(4).copied().unwrap_or(0);
        let rhs_kind = flags & 0x0F;
        let value = resolve_rhs_number(raw, rhs_kind, vars);
        array.write(x, y, value, operator);
    }

    /// Reads a cell without going through a command, for tests and diagnostics.
    pub fn xy_read(&self, array: i32, x: i32, y: i32) -> i32 {
        self.xy_arrays
            .get(&array)
            .map(|a| a.read(x, y))
            .unwrap_or(0)
    }
}

// --- helpers ---------------------------------------------------------------

fn load_bank(basic: &std::path::Path, stem: &str) -> Option<Bank> {
    load_bank_impl(basic, stem, false).ok()
}

fn load_bank_checked(basic: &std::path::Path, stem: &str) -> Result<Bank, String> {
    load_bank_impl(basic, stem, true)
}

fn load_bank_impl(basic: &std::path::Path, stem: &str, strict: bool) -> Result<Bank, String> {
    let dat_path = basic.join(format!("{stem}.dat"));
    let dat_bytes = std::fs::read(&dat_path).map_err(|e| format!("{}: {e}", dat_path.display()))?;
    let dat = DataBaseDat::parse(&dat_bytes).map_err(|e| format!("{}: {e}", dat_path.display()))?;
    let project_path = basic.join(format!("{stem}.project"));
    let project = match std::fs::read(&project_path) {
        Ok(bytes) => match DataBaseProject::parse(&bytes, dat.v3) {
            Ok(project) => Some(project),
            Err(e) if strict => return Err(format!("{}: {e}", project_path.display())),
            Err(_) => None,
        },
        Err(e) if strict => return Err(format!("{}: {e}", project_path.display())),
        Err(_) => None,
    };

    let mut types = Vec::with_capacity(dat.types.len());
    for (index, dat_type) in dat.types.iter().enumerate() {
        let meta = project.as_ref().and_then(|p| p.types.get(index));
        let name = meta
            .map(|m| m.name.clone())
            .unwrap_or_else(|| format!("type-{index}"));
        let field_names = meta
            .map(|m| m.field_names.clone())
            .unwrap_or_else(|| {
                (0..dat_type.property_positions.len())
                    .map(|i| format!("field-{i}"))
                    .collect()
            });
        let entry_names = meta
            .map(|m| m.entry_names.clone())
            .unwrap_or_else(|| {
                (0..dat_type.entries.len())
                    .map(|i| format!("entry-{i}"))
                    .collect()
            });
        let entries = dat_type
            .entries
            .iter()
            .map(|e| EntryState {
                numbers: e.numbers.clone(),
                strings: e.strings.clone(),
            })
            .collect();
        types.push(TypeState::new(
            name,
            field_names,
            entry_names,
            dat_type.property_positions.clone(),
            entries,
        ));
    }
    Ok(Bank::new(types))
}

fn resolve_type_index(
    bank: &Bank,
    raw: i32,
    name: &str,
    name_flags: i32,
) -> Option<usize> {
    if name_flags & NAME_TYPE != 0 && !name.is_empty() {
        return bank.type_index(name).or(if raw >= 0 { Some(raw as usize) } else { None });
    }
    if raw >= 0 {
        Some(raw as usize)
    } else {
        None
    }
}

fn resolve_entry(
    t: &TypeState,
    raw: i32,
    name: &str,
    name_flags: i32,
    vars: &dyn VariableAccess,
) -> Option<usize> {
    if name_flags & NAME_ENTRY != 0 && !name.is_empty() {
        return t
            .entry_index(name)
            .or_else(|| as_index(resolve_literal_or_ref(raw, vars)));
    }
    as_index(resolve_literal_or_ref(raw, vars))
}

fn resolve_field(
    t: &TypeState,
    raw: i32,
    name: &str,
    name_flags: i32,
    vars: &dyn VariableAccess,
) -> Option<usize> {
    if name_flags & NAME_FIELD != 0 && !name.is_empty() {
        return t
            .field_index(name)
            .or_else(|| as_index(resolve_literal_or_ref(raw, vars)));
    }
    as_index(resolve_literal_or_ref(raw, vars))
}

fn as_index(value: i32) -> Option<usize> {
    if value >= 0 {
        Some(value as usize)
    } else {
        None
    }
}

fn resolve_literal_or_ref(raw: i32, vars: &dyn VariableAccess) -> i32 {
    if is_variable_ref(raw) {
        vars.read_number(raw)
    } else {
        raw
    }
}

fn resolve_coordinate(raw: i32, vars: &dyn VariableAccess) -> i32 {
    if is_variable_ref(raw) {
        vars.read_number(raw)
    } else {
        raw
    }
}

fn resolve_rhs_number(raw: i32, rhs_kind: i32, vars: &dyn VariableAccess) -> i32 {
    match rhs_kind {
        RHS_INDIRECT => vars.read_number(vars.read_number(raw)),
        _ => resolve_literal_or_ref(raw, vars),
    }
}

fn is_meta(entry_raw: i32, field_raw: i32) -> bool {
    entry_raw == SENTINEL_NAME
        || entry_raw == SENTINEL_CLEAR
        || field_raw == SENTINEL_NAME
        || field_raw == SENTINEL_CLEAR
}

/// Database write operator table (the engine's 0x10..0x70 scale).
fn apply_op(current: i32, value: i32, operator: i32) -> i32 {
    match operator {
        0x00 => value,
        0x10 => current.wrapping_add(value),
        0x20 => current.wrapping_sub(value),
        0x30 => current.wrapping_mul(value),
        0x40 => {
            if value != 0 {
                current.wrapping_div(value)
            } else {
                0
            }
        }
        0x50 => {
            if value != 0 {
                current.wrapping_rem(value)
            } else {
                0
            }
        }
        0x60 => current.max(value),
        0x70 => current.min(value),
        _ => value,
    }
}

/// Replaces a type's rows from CSV text. The first cell of each row is the
/// entry name; the remaining cells fill fields in order.
fn replace_from_rows(t: &mut TypeState, rows: &[Vec<String>]) {
    let number_slots = t.number_pool_size();
    let string_slots = t.string_pool_size();
    let field_names = t.field_names.clone();
    let positions = t.positions.clone();
    let mut entries = Vec::with_capacity(rows.len());
    let mut entry_names = Vec::with_capacity(rows.len());

    for row in rows {
        let name = row.first().cloned().unwrap_or_default();
        let values: &[String] = if row.len() > 1 { &row[1..] } else { row };
        let mut numbers = vec![0i32; number_slots];
        let mut strings = vec![String::new(); string_slots];
        let mut number_slot = 0usize;
        let mut string_slot = 0usize;
        for field in 0..field_names.len() {
            let Some(cell) = values.get(field) else {
                continue;
            };
            let pos = positions.get(field).copied().unwrap_or(0);
            if pos >= STRING_START {
                if string_slot < strings.len() {
                    strings[string_slot] = cell.clone();
                    string_slot += 1;
                }
            } else if (INT_START..STRING_START).contains(&pos) && number_slot < numbers.len() {
                numbers[number_slot] = cell.trim().parse::<i32>().unwrap_or(0);
                number_slot += 1;
            }
        }
        entries.push(EntryState { numbers, strings });
        entry_names.push(name);
    }

    t.entries = entries;
    t.entry_names = entry_names.clone();
    t.entries_by_name = entry_names
        .iter()
        .enumerate()
        .map(|(i, n)| (n.clone(), i))
        .collect();
}

/// Reads and splits a CSV file. Returns `None` when the file is unreadable, which
/// the original treats as "nothing imported" rather than an error.
fn read_csv(path: &str) -> Option<Vec<Vec<String>>> {
    let text = std::fs::read_to_string(path).ok()?;
    let rows: Vec<Vec<String>> = text
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(split_csv)
        .collect();
    if rows.is_empty() {
        None
    } else {
        Some(rows)
    }
}

/// Splits one CSV line, honouring double-quoted cells with `""` escapes.
fn split_csv(line: &str) -> Vec<String> {
    let mut cells = Vec::new();
    let mut current = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' => {
                if in_quotes && chars.peek() == Some(&'"') {
                    current.push('"');
                    chars.next();
                } else {
                    in_quotes = !in_quotes;
                }
            }
            ',' if !in_quotes => {
                cells.push(current.trim().to_string());
                current.clear();
            }
            other => current.push(other),
        }
    }
    cells.push(current.trim().to_string());
    cells
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::interpreter::refs::{decode_string_ref, decode_variable_ref};
    use std::collections::HashMap;

    /// A stand-in for the interpreter's variable banks.
    #[derive(Default)]
    struct Vars {
        numbers: HashMap<i32, i32>,
        strings: HashMap<i32, String>,
    }

    impl VariableAccess for Vars {
        fn read_number(&self, raw: i32) -> i32 {
            self.numbers
                .get(&decode_variable_ref(raw))
                .copied()
                .unwrap_or(0)
        }
        fn write_number(&mut self, raw: i32, value: i32) {
            self.numbers.insert(decode_variable_ref(raw), value);
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

    /// One number field ("hp") and one string field ("name"), two entries.
    fn sample_type() -> TypeState {
        TypeState::new(
            "unit".to_string(),
            vec!["hp".to_string(), "name".to_string()],
            vec!["Seris".to_string(), "Wolf".to_string()],
            vec![INT_START, STRING_START],
            vec![
                EntryState {
                    numbers: vec![100],
                    strings: vec!["Seris".to_string()],
                },
                EntryState {
                    numbers: vec![80],
                    strings: vec!["Wolf".to_string()],
                },
            ],
        )
    }

    fn db_with_sample() -> Database {
        Database::new(None, None, Some(Bank::new(vec![sample_type()])))
    }

    fn command(type_: i32, params: Vec<i32>, strings: Vec<&str>) -> EventCommand {
        EventCommand {
            param_count: params.len() as u8 + 1,
            command_type: type_,
            params,
            branch_depth: 0,
            strings: strings.into_iter().map(str::to_string).collect(),
            route: None,
        }
    }

    /// Builds the packed flags word the way the deployment does.
    ///
    /// Bits 8..15 form the mode byte: its high nibble is read (`0x10`) or write
    /// (`0x00`), and its low nibble selects the bank (0 changeable, 1 system,
    /// 2/3 user). Real values from CE 144 and the BTL family: `0x10000` (write,
    /// bank 0, name=type), `0x51200` (read, bank 2, name=type+field), `0x113300`
    /// (XY write, action 3). Operator is bits 4..7, RHS kind bits 0..3, name flags
    /// bits 16..19.
    fn flags(operator: i32, rhs: i32, mode: i32, names: i32) -> i32 {
        flags_in_bank(operator, rhs, mode, names, 2)
    }

    /// Same, with an explicit bank nibble.
    fn flags_in_bank(operator: i32, rhs: i32, mode: i32, names: i32, bank: i32) -> i32 {
        (names << 16) | ((mode | bank) << 8) | operator | rhs
    }

    #[test]
    fn flags_debug_shape() {
        // Diagnostic: pin the exact packed word the fixtures rely on.
        let f = flags(0, 0, MODE_READ, 0);
        eprintln!("packed=0x{f:05x} decoded={:?}", DbFlags::decode(f));
        assert_eq!(DbFlags::decode(f).mode, MODE_READ);
        assert_eq!(DbFlags::decode(f).db_kind(2), Some(Kind::User));
    }

    #[test]
    fn reads_a_number_field_into_a_variable() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        // 250: type 0, entry 1, field 0 (hp), read into v5.
        db.execute(
            &command(250, vec![0, 1, 0, flags(0, 0, MODE_READ, 0), 5], vec![]),
            &mut vars,
        );
        assert_eq!(vars.read_number(5), 80);
    }

    #[test]
    fn reads_a_string_field_into_a_string_variable() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        // Field 1 is the string field; destination 3_000_007.
        db.execute(
            &command(250, vec![0, 0, 1, flags(0, 0, MODE_READ, 0), 3_000_007], vec![]),
            &mut vars,
        );
        assert_eq!(vars.read_string(3_000_007), "Seris");
    }

    #[test]
    fn writes_a_number_field_with_each_operator() {
        let cases = [
            (0x00, 42, 42),   // assign
            (0x10, 5, 105),   // add
            (0x20, 5, 95),    // subtract
            (0x30, 2, 200),   // multiply
            (0x40, 4, 25),    // divide
            (0x50, 30, 10),   // modulo
            (0x60, 500, 500), // max
            (0x70, 1, 1),     // min
        ];
        for (operator, value, expected) in cases {
            let mut db = db_with_sample();
            let mut vars = Vars::default();
            db.execute(
                &command(
                    250,
                    vec![0, 0, 0, flags(operator, 0, MODE_WRITE, 0), value],
                    vec![],
                ),
                &mut vars,
            );
            let bank = db.bank(Kind::User).unwrap();
            assert_eq!(
                bank.type_at(0).unwrap().read_number(0, 0),
                expected,
                "operator 0x{operator:02x}"
            );
        }
    }

    #[test]
    fn writes_a_string_field() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        db.execute(
            &command(
                250,
                vec![0, 0, 1, flags(0, RHS_STRING, MODE_WRITE, 0), 0],
                vec!["Renamed"],
            ),
            &mut vars,
        );
        assert_eq!(db.bank(Kind::User).unwrap().type_at(0).unwrap().read_string(0, 1), "Renamed");
    }

    #[test]
    fn a_read_destination_can_be_a_variable_reference() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        // A variable-reference destination is decoded by the variable layer:
        // 2_000_009 names v9, so the value lands in v9.
        db.execute(
            &command(250, vec![0, 1, 0, flags(0, 0, MODE_READ, 0), 2_000_009], vec![]),
            &mut vars,
        );
        assert_eq!(vars.read_number(9), 80);
    }

    #[test]
    fn name_based_access_resolves_type_entry_and_field() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        // Names: strings[1] type, strings[2] entry, strings[3] field.
        let names = NAME_TYPE | NAME_ENTRY | NAME_FIELD;
        db.execute(
            &command(
                250,
                vec![0, 0, 0, flags(0, 0, MODE_READ, names), 5],
                vec!["", "unit", "Wolf", "hp"],
            ),
            &mut vars,
        );
        assert_eq!(vars.read_number(5), 80);
    }

    #[test]
    fn the_name_sentinel_reads_the_type_name() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        db.execute(
            &command(
                250,
                vec![0, SENTINEL_NAME, SENTINEL_NAME, flags(0, 0, MODE_READ, 0), 3_000_001],
                vec![],
            ),
            &mut vars,
        );
        assert_eq!(vars.read_string(3_000_001), "unit");
    }

    #[test]
    fn the_name_sentinel_reads_an_entry_name() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        db.execute(
            &command(
                250,
                vec![0, 1, SENTINEL_NAME, flags(0, 0, MODE_READ, 0), 3_000_001],
                vec![],
            ),
            &mut vars,
        );
        assert_eq!(vars.read_string(3_000_001), "Wolf");
    }

    #[test]
    fn the_clear_sentinel_zeroes_the_type() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        db.execute(
            &command(250, vec![0, SENTINEL_CLEAR, 0, flags(0, 0, MODE_WRITE, 0), 0], vec![]),
            &mut vars,
        );
        let t = db.bank(Kind::User).unwrap().type_at(0).unwrap();
        assert_eq!(t.read_number(0, 0), 0);
        assert_eq!(t.read_string(0, 1), "");
    }

    #[test]
    fn a_minus_one_field_operand_reads_the_entry_count() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        // Reaching the count read requires the meta path, which is entered via a
        // sentinel operand; the entry operand then names the field to fetch.
        db.execute(
            &command(
                250,
                vec![0, SENTINEL_NAME, -1, flags(0, 0, MODE_READ, 0), 5],
                vec![],
            ),
            &mut vars,
        );
        assert_eq!(vars.read_number(5), 2, "two entries");
    }

    #[test]
    fn a_minus_one_entry_operand_reads_the_field_count() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        db.execute(
            &command(
                250,
                vec![0, -1, SENTINEL_NAME, flags(0, 0, MODE_READ, 0), 5],
                vec![],
            ),
            &mut vars,
        );
        assert_eq!(vars.read_number(5), 2, "two fields");
    }

    #[test]
    fn an_unknown_bank_is_ignored_rather_than_failing() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        // Bank nibble 7 is not a database; the engine jumps out.
        db.execute(
            &command(
                250,
                vec![0, 0, 0, flags_in_bank(0, 0, MODE_READ, 0, 7), 5],
                vec![],
            ),
            &mut vars,
        );
        assert_eq!(vars.read_number(5), 0);
    }

    #[test]
    fn a_missing_type_does_not_write_anything() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        db.execute(
            &command(250, vec![99, 0, 0, flags(0, 0, MODE_READ, 0), 5], vec![]),
            &mut vars,
        );
        assert_eq!(vars.read_number(5), 0);
    }

    #[test]
    fn type_252_uses_the_same_assignment_path() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        db.execute(
            &command(252, vec![0, 1, 0, flags(0, 0, MODE_READ, 0), 5], vec![]),
            &mut vars,
        );
        assert_eq!(vars.read_number(5), 80);
    }

    // --- XY arrays ---------------------------------------------------------

    #[test]
    fn xy_arrays_start_at_zero() {
        let db = Database::default();
        assert_eq!(db.xy_read(0, 3, 4), 0);
    }

    #[test]
    fn xy_array_writes_then_reads_a_cell() {
        let mut db = Database::default();
        let mut vars = Vars::default();
        // action 1 (write), operator 0 (assign), rhs literal 42 at (x=3, y=4).
        let flags = (1 << 12) | (0 << 4) | 0;
        db.execute(&command(255, vec![0, 3, 4, flags, 42], vec![]), &mut vars);
        assert_eq!(db.xy_read(0, 3, 4), 42);

        // action 0 (read) into v6.
        db.execute(&command(255, vec![0, 3, 4, 0, 6], vec![]), &mut vars);
        assert_eq!(vars.read_number(6), 42);
    }

    #[test]
    fn xy_array_operators_follow_the_engine_scale() {
        // operator nibble 1..3 is shifted to the 0x100 scale by sub_AED340.
        // Seed with 42 via an assign (operator nibble 0), then apply each
        // operator to it: +5=47, -5=37, *2=84, /4=10.
        let cases = [(1, 5, 47), (2, 5, 37), (3, 2, 84), (4, 4, 10)];
        for (operator_nibble, value, expected) in cases {
            let mut db = Database::default();
            let mut vars = Vars::default();
            let assign = 1 << 12;
            let apply = (1 << 12) | (operator_nibble << 4);
            db.execute(&command(255, vec![0, 0, 0, assign, 42], vec![]), &mut vars);
            db.execute(&command(255, vec![0, 0, 0, apply, value], vec![]), &mut vars);
            assert_eq!(
                db.xy_read(0, 0, 0),
                expected,
                "operator nibble {operator_nibble}"
            );
        }
    }

    #[test]
    fn xy_array_division_by_zero_uses_one_as_the_divisor() {
        // sub_AED340: v12 = a4; if (a4 == 0) v12 = 1; -- not zero.
        let mut db = Database::default();
        let mut vars = Vars::default();
        let assign = 1 << 12;
        let divide = (1 << 12) | (4 << 4);
        db.execute(&command(255, vec![0, 0, 0, assign, 42], vec![]), &mut vars);
        db.execute(&command(255, vec![0, 0, 0, divide, 0], vec![]), &mut vars);
        assert_eq!(db.xy_read(0, 0, 0), 42, "42 / 1 stays 42");
    }

    #[test]
    fn xy_coordinates_can_come_from_variables() {
        let mut db = Database::default();
        let mut vars = Vars::default();
        vars.write_number(10, 3);
        vars.write_number(11, 4);
        let write = 1 << 12;
        db.execute(
            &command(255, vec![0, 2_000_010, 2_000_011, write, 9], vec![]),
            &mut vars,
        );
        assert_eq!(db.xy_read(0, 3, 4), 9);
    }

    #[test]
    fn xy_arrays_are_addressed_separately() {
        let mut db = Database::default();
        let mut vars = Vars::default();
        let write = 1 << 12;
        db.execute(&command(255, vec![0, 0, 0, write, 1], vec![]), &mut vars);
        db.execute(&command(257, vec![1, 0, 0, write, 2], vec![]), &mut vars);
        assert_eq!(db.xy_read(0, 0, 0), 1);
        assert_eq!(db.xy_read(1, 0, 0), 2);
    }

    // --- CSV import --------------------------------------------------------

    #[test]
    fn csv_splitting_honours_quoted_cells() {
        assert_eq!(
            split_csv(r#"a,"b,c",d"#),
            vec!["a".to_string(), "b,c".to_string(), "d".to_string()]
        );
        assert_eq!(
            split_csv(r#""say ""hi""",x"#),
            vec![r#"say "hi""#.to_string(), "x".to_string()]
        );
    }

    #[test]
    fn csv_rows_replace_the_type_entries() {
        let mut t = sample_type();
        let rows = vec![
            vec!["NewHero".to_string(), "123".to_string(), "NewName".to_string()],
        ];
        replace_from_rows(&mut t, &rows);
        assert_eq!(t.entries.len(), 1);
        assert_eq!(t.entry_names, vec!["NewHero"]);
        assert_eq!(t.read_number(0, 0), 123);
        assert_eq!(t.read_string(0, 1), "NewName");
        assert_eq!(t.entry_index("NewHero"), Some(0));
    }

    #[test]
    fn importing_a_missing_csv_is_a_no_op() {
        let mut db = db_with_sample();
        let mut vars = Vars::default();
        db.execute(
            &command(251, vec![], vec!["/definitely/not/a/file.csv", "unit"]),
            &mut vars,
        );
        assert_eq!(
            db.bank(Kind::User).unwrap().type_at(0).unwrap().read_number(0, 0),
            100,
            "the original data must survive a failed import"
        );
    }

    #[test]
    fn flags_decode_the_packed_fields() {
        let packed = (NAME_FIELD << 16) | (MODE_READ << 8) | 0x30 | RHS_INDIRECT;
        let f = DbFlags::decode(packed);
        assert_eq!(f.operator, 0x30);
        assert_eq!(f.rhs_kind, RHS_INDIRECT);
        assert_eq!(f.mode, MODE_READ);
        assert_eq!(f.name_flags, NAME_FIELD);
    }

    #[test]
    fn the_mode_alias_0x20_means_write() {
        let f = DbFlags::decode(0x20);
        assert_eq!(f.mode, MODE_WRITE);
    }
}
