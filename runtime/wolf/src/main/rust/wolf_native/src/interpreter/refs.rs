//! Variable and string reference decoding.
//!
//! Port of the `decodeVariableRef` / `decodeStringRef` helpers in
//! `WolfInterpreter.kt`. WOLF packs a reference's namespace into the millions
//! digit; the decoded keys deliberately overlap across namespaces in the
//! original, and that overlap is behaviour, not a bug, so it is preserved.

/// Numeric CSelf keys: `-1_000_000 - n`.
pub fn self_var_key(n: i32) -> i32 {
    -1_000_000 - n
}

/// True when `key` is a CSelf slot (used to snapshot/restore per event).
pub fn is_self_var_key(key: i32) -> bool {
    (-1_000_099..=-1_000_000).contains(&key)
}

/// True when `key` is a CSelf string slot.
pub fn is_self_string_key(key: i32) -> bool {
    is_self_var_key(key) || (1_600_000..=1_699_999).contains(&key)
}

/// Maps a raw numeric reference to its decoded variable key.
///
/// * `2_000_000..2_999_999` — normal variables, `raw - 2_000_000`.
/// * `8_000_000..8_999_999` — system variables, below zero.
/// * `9_000_000..9_999_999` — 9M system variables, below zero.
/// * `1_600_000..1_699_999` — game-state/UI variables used by menu scripts.
/// * anything else — already a plain key.
pub fn decode_variable_ref(raw: i32) -> i32 {
    match raw {
        2_000_000..=2_999_999 => raw - 2_000_000,
        8_000_000..=8_999_999 => -(raw - 8_000_000) - 1,
        9_000_000..=9_999_999 => -(raw - 9_000_000) - 1_000_000,
        1_600_000..=1_699_999 => -1_000_000 - (raw - 1_600_000),
        _ => raw,
    }
}

/// Maps a raw string reference to its decoded string key.
pub fn decode_string_ref(raw: i32) -> i32 {
    match raw {
        3_000_000..=3_999_999 => raw - 3_000_000,
        // CSelf string slots share the 1.6M range with numeric CSelf.
        1_600_000..=1_699_999 => -1_000_000 - (raw - 1_600_000),
        _ => raw,
    }
}

/// True when a raw operand is a variable reference rather than a literal.
pub fn is_variable_ref(raw: i32) -> bool {
    raw >= 1_000_000
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normal_variables_offset_by_two_million() {
        assert_eq!(decode_variable_ref(2_000_010), 10);
        assert_eq!(decode_variable_ref(2_000_000), 0);
    }

    #[test]
    fn system_variables_are_negative() {
        assert_eq!(decode_variable_ref(8_000_000), -1);
        assert_eq!(decode_variable_ref(8_000_005), -6);
    }

    #[test]
    fn nine_million_variables_are_below_the_other_bank() {
        assert_eq!(decode_variable_ref(9_000_000), -1_000_000);
        assert_eq!(decode_variable_ref(9_000_007), -1_000_007);
    }

    #[test]
    fn self_variables_map_into_the_self_range() {
        assert_eq!(decode_variable_ref(1_600_000), -1_000_000);
        assert_eq!(decode_variable_ref(1_600_023), -1_000_023);
        assert!(is_self_var_key(decode_variable_ref(1_600_023)));
    }

    #[test]
    fn plain_numbers_pass_through() {
        assert_eq!(decode_variable_ref(7), 7);
        assert_eq!(decode_variable_ref(-5), -5);
    }

    #[test]
    fn string_refs_offset_by_three_million() {
        assert_eq!(decode_string_ref(3_000_005), 5);
        assert_eq!(decode_string_ref(1_600_002), -1_000_002);
        assert_eq!(decode_string_ref(42), 42);
    }

    #[test]
    fn self_range_covers_one_hundred_slots() {
        assert!(is_self_var_key(self_var_key(0)));
        assert!(is_self_var_key(self_var_key(99)));
        assert!(!is_self_var_key(self_var_key(100)));
    }
}
