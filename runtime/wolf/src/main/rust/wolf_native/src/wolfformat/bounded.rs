//! Bounds-checked little-endian reader.
//!
//! Mirrors `core/wolfformat/BoundedReader.kt`. Every read is checked against the
//! remaining length so a truncated or hostile file produces an error instead of
//! a panic or a silently wrong value.

use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FormatError(pub String);

impl fmt::Display for FormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for FormatError {}

pub type Result<T> = std::result::Result<T, FormatError>;

fn err<T>(msg: impl Into<String>) -> Result<T> {
    Err(FormatError(msg.into()))
}

/// Sanity ceilings, matching the Kotlin `Limits`.
#[derive(Debug, Clone, Copy)]
pub struct Limits {
    pub max_file_bytes: usize,
    pub max_count: u32,
    pub max_string_bytes: u32,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_file_bytes: 512 * 1024 * 1024,
            max_count: 8_000_000,
            max_string_bytes: 64 * 1024 * 1024,
        }
    }
}

pub struct BoundedReader<'a> {
    data: &'a [u8],
    pos: usize,
    end: usize,
    limits: Limits,
}

impl<'a> BoundedReader<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Self {
            data,
            pos: 0,
            end: data.len(),
            limits: Limits::default(),
        }
    }

    pub fn at(data: &'a [u8], offset: usize) -> Result<Self> {
        if offset > data.len() {
            return err(format!(
                "offset {offset} is past the end of a {} byte file",
                data.len()
            ));
        }
        Ok(Self {
            data,
            pos: offset,
            end: data.len(),
            limits: Limits::default(),
        })
    }

    pub fn position(&self) -> usize {
        self.pos
    }

    pub fn remaining(&self) -> usize {
        self.end.saturating_sub(self.pos)
    }

    fn need(&self, n: usize, what: &str) -> Result<()> {
        if self.remaining() < n {
            return err(format!(
                "needed {n} byte(s) of {what} but only {} remain",
                self.remaining()
            ));
        }
        Ok(())
    }

    pub fn u8(&mut self) -> Result<u8> {
        self.need(1, "u1")?;
        let v = self.data[self.pos];
        self.pos += 1;
        Ok(v)
    }

    pub fn u16(&mut self) -> Result<u16> {
        self.need(2, "u2")?;
        let v = u16::from_le_bytes([self.data[self.pos], self.data[self.pos + 1]]);
        self.pos += 2;
        Ok(v)
    }

    pub fn u32(&mut self) -> Result<u32> {
        self.need(4, "u4")?;
        let v = u32::from_le_bytes([
            self.data[self.pos],
            self.data[self.pos + 1],
            self.data[self.pos + 2],
            self.data[self.pos + 3],
        ]);
        self.pos += 4;
        Ok(v)
    }

    pub fn i32(&mut self) -> Result<i32> {
        Ok(self.u32()? as i32)
    }

    /// A count that is also validated against [`Limits::max_count`].
    pub fn count(&mut self, what: &str) -> Result<usize> {
        let v = self.u32()?;
        if v > self.limits.max_count {
            return err(format!(
                "{what} count {v} exceeds limit {}",
                self.limits.max_count
            ));
        }
        Ok(v as usize)
    }

    pub fn bytes(&mut self, n: usize, what: &str) -> Result<&'a [u8]> {
        self.need(n, what)?;
        let out = &self.data[self.pos..self.pos + n];
        self.pos += n;
        Ok(out)
    }

    pub fn skip(&mut self, n: usize, what: &str) -> Result<()> {
        self.need(n, what)?;
        self.pos += n;
        Ok(())
    }

    /// Length-prefixed WOLF string: `u4` byte length then that many bytes,
    /// normally NUL-terminated. `v3` files are UTF-8, v2 files Shift-JIS.
    pub fn string(&mut self, v3: bool) -> Result<String> {
        let len = self.u32()?;
        if len > self.limits.max_string_bytes {
            return err(format!(
                "string length {len} exceeds limit {}",
                self.limits.max_string_bytes
            ));
        }
        let raw = self.bytes(len as usize, "string")?;
        let body = match raw.last() {
            Some(0) => &raw[..raw.len() - 1],
            _ => raw,
        };
        if v3 {
            String::from_utf8(body.to_vec())
                .map_err(|e| FormatError(format!("undecodable UTF-8 string: {e}")))
        } else {
            // Shift-JIS: decode lossily rather than failing, matching the
            // Kotlin side's tolerance for editor-era files.
            Ok(crate::wolfformat::shift_jis(body))
        }
    }
}

/// Minimal Shift-JIS decode for v2 files.
///
/// Only the ASCII range and the JIS X 0201 half-width katakana block are
/// mapped; anything else becomes U+FFFD. That is enough to keep v2 titles and
/// file names readable without pulling in a full encoding table, and v2
/// deployments are out of scope for the current objective.
pub(crate) fn shift_jis(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len());
    for &b in bytes {
        if b < 0x80 {
            out.push(b as char);
        } else if (0xA1..=0xDF).contains(&b) {
            // Half-width katakana: U+FF61 + (byte - 0xA1)
            out.push(char::from_u32(0xFF61 + (b as u32 - 0xA1)).unwrap_or('\u{FFFD}'));
        } else {
            out.push('\u{FFFD}');
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_little_endian_values() {
        let data = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06];
        let mut r = BoundedReader::new(&data);
        assert_eq!(r.u8().unwrap(), 0x01);
        assert_eq!(r.u16().unwrap(), 0x0302);
        assert_eq!(r.u16().unwrap(), 0x0504); // bytes at 3,4 are 04,05
        assert_eq!(r.remaining(), 1);
    }

    #[test]
    fn refuses_to_read_past_the_end() {
        let data = [1u8, 2, 3];
        let mut r = BoundedReader::new(&data);
        assert!(r.u32().is_err(), "reading 4 bytes from 3 must fail");
        assert_eq!(r.remaining(), 3, "a failed read must not consume");
    }

    #[test]
    fn reads_length_prefixed_utf8_strings() {
        // len 3, "ab\0"
        let data = [3, 0, 0, 0, b'a', b'b', 0];
        let mut r = BoundedReader::new(&data);
        assert_eq!(r.string(true).unwrap(), "ab");
        assert_eq!(r.remaining(), 0);
    }

    #[test]
    fn rejects_oversized_counts() {
        let data = [0xFF, 0xFF, 0xFF, 0xFF];
        let mut r = BoundedReader::new(&data);
        assert!(r.count("thing").is_err());
    }

    #[test]
    fn decodes_half_width_katakana() {
        assert_eq!(shift_jis(&[0xB1]), "\u{FF71}");
        assert_eq!(shift_jis(b"abc"), "abc");
    }
}
