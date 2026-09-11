use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::time::UNIX_EPOCH;

pub const HEADER_SIZE: usize = 16;
pub const KEY_SIZE: usize = 16;
pub const XOR_LENGTH: usize = 16;

pub const HEADER: [u8; HEADER_SIZE] = [
    0x52, 0x50, 0x47, 0x4d, 0x56, 0x00, 0x00, 0x00, 0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
];

/// Codec failure. The message is static, so building an error never allocates.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct DecodeError {
    pub message: &'static str,
}

fn decode_error(message: &'static str) -> DecodeError {
    DecodeError { message }
}

#[derive(Debug, Clone)]
pub struct IndexedPath {
    pub relative_path: String,
    pub size: i64,
    pub last_modified_millis: i64,
}

/// Decrypted asset bytes.
///
/// The 16-byte RPG Maker header is skipped by offset instead of being removed, so a
/// decode never copies or reallocates the payload: the caller passes ownership of the
/// stored buffer in and reads the plaintext through [`DecodedAsset::as_slice`].
#[derive(Debug)]
pub struct DecodedAsset {
    bytes: Vec<u8>,
    offset: usize,
}

impl DecodedAsset {
    /// Plaintext view, i.e. the stored buffer with the header skipped.
    pub fn as_slice(&self) -> &[u8] {
        &self.bytes[self.offset..]
    }

    pub fn len(&self) -> usize {
        self.bytes.len() - self.offset
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

pub fn decode_asset(
    key: &[u8; KEY_SIZE],
    mut stored: Vec<u8>,
) -> Result<DecodedAsset, DecodeError> {
    if stored.len() < HEADER_SIZE + XOR_LENGTH {
        return Err(decode_error("Encrypted asset is truncated"));
    }
    if stored[..HEADER_SIZE] != HEADER {
        return Err(decode_error("Encrypted asset header is invalid"));
    }
    for i in 0..XOR_LENGTH {
        stored[HEADER_SIZE + i] ^= key[i];
    }
    Ok(DecodedAsset {
        bytes: stored,
        offset: HEADER_SIZE,
    })
}

pub fn read_file_fully(path: &str) -> Result<Vec<u8>, DecodeError> {
    if path.is_empty() {
        return Err(decode_error("Path is empty"));
    }
    let mut file = fs::File::open(path).map_err(|_| decode_error("Unable to open file"))?;
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)
        .map_err(|_| decode_error("Unable to read file"))?;
    Ok(bytes)
}

/// Case-insensitive checks against the relative path, without allocating a lowercased copy.
fn is_volatile(rel: &str) -> bool {
    if rel.eq_ignore_ascii_case("save")
        || rel.eq_ignore_ascii_case("logs.txt")
        || rel.eq_ignore_ascii_case("debug.log")
    {
        return true;
    }
    if rel
        .get(..5)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("save/"))
    {
        return true;
    }
    let bytes = rel.as_bytes();
    if bytes.len() >= 8 && bytes[bytes.len() - 8..].eq_ignore_ascii_case(b".rpgsave") {
        return true;
    }
    bytes.len() >= 9 && bytes[bytes.len() - 9..].eq_ignore_ascii_case(b".rmmzsave")
}

fn java_compatible_mtime(meta: &fs::Metadata) -> Option<i64> {
    let modified = meta.modified().ok()?;
    let duration = modified.duration_since(UNIX_EPOCH).ok()?;
    Some(duration.as_millis() as i64)
}

pub fn list_files_recursive(root_path: &str) -> Result<Vec<IndexedPath>, DecodeError> {
    if root_path.is_empty() {
        return Err(decode_error("Path is empty"));
    }
    let root = PathBuf::from(root_path);
    if !root.is_dir() {
        return Err(decode_error("Game root is not a directory"));
    }

    let mut out = Vec::new();
    // `rel_dir` is carried as a `/`-joined string: the JNI surface expects invariant
    // separators (`GameFileIndex` uses `invariantSeparatorsPath`), and this avoids a
    // `PathBuf` join plus a `to_string_lossy().replace()` per entry.
    let mut stack = vec![(root, String::new(), 0usize)];
    while let Some((dir, rel_dir, depth)) = stack.pop() {
        if depth > 64 {
            continue;
        }
        let entries = match fs::read_dir(&dir) {
            Ok(e) => e,
            Err(_) => continue,
        };
        for entry in entries.flatten() {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            let mut rel = String::with_capacity(rel_dir.len() + name.len() + 1);
            if !rel_dir.is_empty() {
                rel.push_str(&rel_dir);
                rel.push('/');
            }
            rel.push_str(&name);
            if is_volatile(&rel) {
                continue;
            }
            // `DirEntry::metadata` is an lstat: it answers dir/file/symlink in one syscall.
            // Symlinks are neither `is_dir` nor `is_file` here, so tree cycles stay impossible.
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            if meta.is_dir() {
                stack.push((entry.path(), rel, depth + 1));
                continue;
            }
            if !meta.is_file() {
                continue;
            }
            let Some(mtime) = java_compatible_mtime(&meta) else {
                continue;
            };
            out.push(IndexedPath {
                relative_path: rel,
                size: meta.len() as i64,
                last_modified_millis: mtime,
            });
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_roundtrip_header() {
        let key = [0u8, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
        let png = [
            0x89u8, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48,
            0x44, 0x52,
        ];
        let mut body = [0u8; 32];
        body[..16].copy_from_slice(&png);
        for i in 0..16 {
            body[i] ^= key[i];
        }
        let mut stored = Vec::from(HEADER);
        stored.extend_from_slice(&body);
        let plain = decode_asset(&key, stored).unwrap();
        assert_eq!(&plain.as_slice()[..16], &png);
        assert_eq!(plain.len(), 32);
    }

    #[test]
    fn decode_rejects_foreign_header() {
        let key = [0u8; KEY_SIZE];
        let stored = vec![0u8; HEADER_SIZE + XOR_LENGTH];
        assert_eq!(
            decode_asset(&key, stored).unwrap_err().message,
            "Encrypted asset header is invalid"
        );
    }

    #[test]
    fn volatile_paths_are_skipped_without_lowercasing() {
        // Root-level markers, matched case-insensitively (the old check lowercased the path).
        assert!(is_volatile("save"));
        assert!(is_volatile("SAVE"));
        assert!(is_volatile("save/file.rmmzsave"));
        assert!(is_volatile("Save/Data"));
        assert!(is_volatile("LOGS.TXT"));
        assert!(is_volatile("Debug.Log"));
        assert!(is_volatile("a.rpgsave"));
        assert!(is_volatile("data/Game.rpgSAVE"));
        assert!(is_volatile("save/x.rmmzsave"));
        // Same semantics as before: only the root-level names are filtered.
        assert!(!is_volatile("data/Logs.txt"));
        assert!(!is_volatile("data/save"));
        assert!(!is_volatile("savedata"));
        assert!(!is_volatile("saver"));
        assert!(!is_volatile("savex/a.txt"));
        assert!(!is_volatile("data/Game.rpgsave.bak"));
    }
}
