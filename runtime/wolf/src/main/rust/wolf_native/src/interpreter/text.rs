//! Message / picture text escape expansion.
//!
//! Port of `WolfText.kt`. Expands `\v[n]`, `\cself[n]`, `\s[n]` and the database
//! forms `\cdb[t:e:f]`, `\udb[t:e:f]`, `\sdb[t:e:f]`. Unknown tags are left
//! intact rather than dropped, so a missing tag is visible instead of silently
//! blanking part of a message.
//!
//! Database references are **not** expanded here: the Rust side has no database
//! model yet (that is task-10), so `\cdb`/`\udb`/`\sdb` pass through unchanged.
//! Substituting a wrong value would be worse than leaving the tag visible.

use std::collections::HashMap;

pub type Variables = HashMap<i32, i32>;
pub type Strings = HashMap<i32, String>;

/// Expands the variable/string subset of WOLF's escape tags.
pub fn interpolate(raw: &str, variables: &Variables, strings: &Strings) -> String {
    if !raw.contains('\\') {
        return raw.to_string();
    }
    let mut out = String::with_capacity(raw.len());
    let bytes: Vec<char> = raw.chars().collect();
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] != '\\' {
            out.push(bytes[i]);
            i += 1;
            continue;
        }
        match try_expand(&bytes, i, variables, strings) {
            Some((text, next)) => {
                out.push_str(&text);
                i = next;
            }
            None => {
                out.push('\\');
                i += 1;
            }
        }
    }
    out
}

/// Tries to expand one tag starting at `start` (which must be a backslash).
/// Returns the replacement text and the index just past the tag.
fn try_expand(
    chars: &[char],
    start: usize,
    variables: &Variables,
    strings: &Strings,
) -> Option<(String, usize)> {
    // Read the tag name: letters only.
    let mut j = start + 1;
    while j < chars.len() && chars[j].is_ascii_alphabetic() {
        j += 1;
    }
    if j == start + 1 {
        return None;
    }
    let name: String = chars[start + 1..j].iter().collect::<String>().to_ascii_lowercase();
    if j >= chars.len() || chars[j] != '[' {
        return None;
    }
    // Find the closing bracket.
    let mut k = j + 1;
    while k < chars.len() && chars[k] != ']' {
        k += 1;
    }
    if k >= chars.len() {
        return None;
    }
    let arg: String = chars[j + 1..k].iter().collect();
    let next = k + 1;

    let text = match name.as_str() {
        "v" => {
            let id = arg.trim().parse::<i32>().ok()?;
            let value = variables
                .get(&id)
                .or_else(|| variables.get(&(id + 2_000_000)))
                .copied()
                .unwrap_or(0);
            value.to_string()
        }
        // CSelf is dual-typed: the string bank wins (title picture paths), then
        // the numeric bank. Both live at the decoded `-1_000_000 - n` key.
        "cself" => {
            let id = arg.trim().parse::<i32>().ok()?;
            let decoded = -1_000_000 - id;
            if let Some(s) = strings.get(&decoded) {
                s.clone()
            } else if let Some(s) = strings.get(&(1_600_000 + id)) {
                s.clone()
            } else if let Some(s) = strings.get(&id) {
                s.clone()
            } else {
                let value = variables
                    .get(&decoded)
                    .or_else(|| variables.get(&id))
                    .copied()
                    .unwrap_or(0);
                value.to_string()
            }
        }
        "s" => {
            let id = arg.trim().parse::<i32>().ok()?;
            strings.get(&id).cloned().unwrap_or_default()
        }
        // No database model yet; leave the tag intact.
        "cdb" | "udb" | "sdb" => return None,
        _ => return None,
    };
    Some((text, next))
}

/// Removes WOLF display directives that carry no meaning outside the original
/// renderer (font/size/colour/italic/wait markers).
pub fn strip_presentation_markup(raw: &str) -> String {
    let chars: Vec<char> = raw.chars().collect();
    let mut out = String::with_capacity(raw.len());
    let mut i = 0usize;
    while i < chars.len() {
        if chars[i] == '\\' && i + 1 < chars.len() {
            let mut j = i + 1;
            while j < chars.len() && chars[j].is_ascii_alphabetic() {
                j += 1;
            }
            if j < chars.len() && chars[j] == '[' {
                let mut k = j + 1;
                while k < chars.len() && chars[k] != ']' {
                    k += 1;
                }
                if k < chars.len() {
                    // `\space[...]` becomes a single space; everything else goes.
                    let name: String =
                        chars[i + 1..j].iter().collect::<String>().to_ascii_lowercase();
                    if name == "space" {
                        out.push(' ');
                    }
                    i = k + 1;
                    continue;
                }
            }
            // `\E` and similar bare directives.
            if chars[i + 1].is_ascii_alphabetic() {
                i += 2;
                continue;
            }
        }
        if chars[i] == '<' {
            if let Some(close) = (i..chars.len()).find(|&k| chars[k] == '>') {
                i = close + 1;
                continue;
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    // Collapse runs of spaces, matching the Kotlin behaviour.
    let mut collapsed = String::with_capacity(out.len());
    let mut last_space = false;
    for c in out.chars() {
        if c == ' ' || c == '\t' {
            if !last_space {
                collapsed.push(' ');
            }
            last_space = true;
        } else {
            collapsed.push(c);
            last_space = false;
        }
    }
    collapsed.trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vars(pairs: &[(i32, i32)]) -> Variables {
        pairs.iter().copied().collect()
    }

    fn strs(pairs: &[(i32, &str)]) -> Strings {
        pairs.iter().map(|(k, v)| (*k, v.to_string())).collect()
    }

    #[test]
    fn leaves_plain_text_alone() {
        assert_eq!(interpolate("hello", &vars(&[]), &strs(&[])), "hello");
    }

    #[test]
    fn expands_numeric_variables() {
        let v = vars(&[(10, 42)]);
        assert_eq!(interpolate(r"hp=\v[10]", &v, &strs(&[])), "hp=42");
    }

    #[test]
    fn missing_variables_read_as_zero() {
        assert_eq!(interpolate(r"\v[99]", &vars(&[]), &strs(&[])), "0");
    }

    #[test]
    fn expands_string_variables() {
        let s = strs(&[(5, "モノクロ")]);
        assert_eq!(interpolate(r"\s[5]", &vars(&[]), &s), "モノクロ");
    }

    #[test]
    fn cself_prefers_the_string_bank() {
        // The decoded CSelf key is -1_000_000 - n.
        let s = strs(&[(-1_000_003, "Picture/menu.png")]);
        assert_eq!(
            interpolate(r"\cself[3]", &vars(&[]), &s),
            "Picture/menu.png"
        );
    }

    #[test]
    fn cself_falls_back_to_numbers() {
        let v = vars(&[(-1_000_003, 7)]);
        assert_eq!(interpolate(r"\cself[3]", &v, &strs(&[])), "7");
    }

    #[test]
    fn database_tags_pass_through_unexpanded() {
        // No database model yet; a wrong value would be worse than a visible tag.
        let raw = r"\cdb[0:1:2]";
        assert_eq!(interpolate(raw, &vars(&[]), &strs(&[])), raw);
    }

    #[test]
    fn unknown_tags_are_preserved() {
        assert_eq!(interpolate(r"\zzz[1]", &vars(&[]), &strs(&[])), r"\zzz[1]");
    }

    #[test]
    fn tag_names_are_case_insensitive() {
        assert_eq!(interpolate(r"\V[1]", &vars(&[(1, 5)]), &strs(&[])), "5");
    }

    #[test]
    fn strips_presentation_markup() {
        assert_eq!(strip_presentation_markup(r"a\f[3]b"), "ab");
        assert_eq!(strip_presentation_markup(r"a\c[5]b"), "ab");
        assert_eq!(strip_presentation_markup(r"a\space[2]b"), "a b");
        assert_eq!(strip_presentation_markup(r"a\Eb"), "ab");
    }
}
