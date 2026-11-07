//! LZ4 block decompression, used by WOLF v3.5 containers.
//!
//! Port of `core/wolfformat/WolfLz4.kt`. Only the raw block format is needed;
//! there is no frame header.

use super::bounded::{FormatError, Result};

/// Decompresses a raw LZ4 block that must expand to exactly `output_size` bytes.
pub fn decompress(input: &[u8], output_size: usize) -> Result<Vec<u8>> {
    let mut out = vec![0u8; output_size];
    let mut src = 0usize;
    let mut dst = 0usize;

    while src < input.len() {
        let token = input[src];
        src += 1;

        let mut literal_len = (token >> 4) as usize;
        if literal_len == 15 {
            loop {
                if src >= input.len() {
                    return Err(FormatError("truncated LZ4 literals".into()));
                }
                let b = input[src];
                src += 1;
                literal_len += b as usize;
                if b != 255 {
                    break;
                }
            }
        }

        if src + literal_len > input.len() || dst + literal_len > output_size {
            return Err(FormatError("invalid LZ4 literal block".into()));
        }
        out[dst..dst + literal_len].copy_from_slice(&input[src..src + literal_len]);
        src += literal_len;
        dst += literal_len;

        // The last sequence in a block has no match.
        if src == input.len() {
            break;
        }
        if src + 2 > input.len() {
            return Err(FormatError("truncated LZ4 match offset".into()));
        }
        let offset = (input[src] as usize) | ((input[src + 1] as usize) << 8);
        src += 2;
        if offset == 0 || offset > dst {
            return Err(FormatError("invalid LZ4 match offset".into()));
        }

        let mut match_len = (token & 0x0F) as usize + 4;
        if (token & 0x0F) == 15 {
            loop {
                if src >= input.len() {
                    return Err(FormatError("truncated LZ4 match".into()));
                }
                let b = input[src];
                src += 1;
                match_len += b as usize;
                if b != 255 {
                    break;
                }
            }
        }
        if dst + match_len > output_size {
            return Err(FormatError("invalid LZ4 match block".into()));
        }
        // Byte-by-byte: matches may overlap the region being written.
        for i in 0..match_len {
            out[dst + i] = out[dst - offset + i];
        }
        dst += match_len;
    }

    if dst != output_size {
        return Err(FormatError(format!(
            "LZ4 payload expanded to {dst} bytes, expected {output_size}"
        )));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a literal-only LZ4 block: token then raw bytes.
    fn literal_block(payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        if payload.len() < 15 {
            out.push((payload.len() as u8) << 4);
        } else {
            out.push(0xF0);
            let mut remaining = payload.len() - 15;
            while remaining >= 255 {
                out.push(255);
                remaining -= 255;
            }
            out.push(remaining as u8);
        }
        out.extend_from_slice(payload);
        out
    }

    #[test]
    fn decodes_a_literal_only_block() {
        let payload = b"hello world";
        let block = literal_block(payload);
        assert_eq!(decompress(&block, payload.len()).unwrap(), payload);
    }

    #[test]
    fn decodes_a_match_sequence() {
        // "ab" then a match of offset 2 length 4 => "ababab"
        // token: literal 2, match len 4-4=0 => 0x20
        let block = [0x20u8, b'a', b'b', 0x02, 0x00];
        assert_eq!(decompress(&block, 6).unwrap(), b"ababab");
    }

    #[test]
    fn rejects_a_wrong_output_size() {
        let payload = b"abc";
        let block = literal_block(payload);
        assert!(decompress(&block, 99).is_err());
    }

    #[test]
    fn rejects_an_offset_past_the_start() {
        // literal 1 then offset 5 with nothing written before it
        let block = [0x10u8, b'x', 0x05, 0x00];
        assert!(decompress(&block, 8).is_err());
    }

    #[test]
    fn rejects_truncated_input() {
        // declares 15+ literals but supplies none
        assert!(decompress(&[0xF0], 32).is_err());
    }
}
