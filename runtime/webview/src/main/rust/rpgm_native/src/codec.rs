use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

pub const HEADER_SIZE: usize = 16;
pub const KEY_SIZE: usize = 16;
pub const XOR_LENGTH: usize = 16;

pub const HEADER: [u8; HEADER_SIZE] = [
    0x52, 0x50, 0x47, 0x4d, 0x56, 0x00, 0x00, 0x00, 0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
];

#[derive(Debug, Default, Clone)]
pub struct DecodeError {
    pub message: String,
}

#[derive(Debug, Clone)]
pub struct IndexedPath {
    pub relative_path: String,
    pub size: i64,
    pub last_modified_millis: i64,
}

pub fn decode_asset(key: &[u8; KEY_SIZE], stored: &[u8]) -> Result<Vec<u8>, DecodeError> {
    if stored.len() < HEADER_SIZE + XOR_LENGTH {
        return Err(DecodeError {
            message: "Encrypted asset is truncated".into(),
        });
    }
    if stored[..HEADER_SIZE] != HEADER {
        return Err(DecodeError {
            message: "Encrypted asset header is invalid".into(),
        });
    }
    let mut plain = stored[HEADER_SIZE..].to_vec();
    let xor_end = XOR_LENGTH.min(plain.len());
    for i in 0..xor_end {
        plain[i] ^= key[i];
    }
    Ok(plain)
}

pub fn read_file_fully(path: &str) -> Result<Vec<u8>, DecodeError> {
    if path.is_empty() {
        return Err(DecodeError {
            message: "Path is empty".into(),
        });
    }
    let mut file = fs::File::open(path).map_err(|_| DecodeError {
        message: "Unable to open file".into(),
    })?;
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes).map_err(|_| DecodeError {
        message: "Unable to read file".into(),
    })?;
    Ok(bytes)
}

fn is_volatile(rel: &str) -> bool {
    let lower = rel.to_ascii_lowercase();
    lower == "save"
        || lower.starts_with("save/")
        || lower == "logs.txt"
        || lower == "debug.log"
        || lower.ends_with(".rpgsave")
        || lower.ends_with(".rmmzsave")
}

fn java_compatible_mtime(meta: &fs::Metadata) -> Option<i64> {
    let modified = meta.modified().ok()?;
    let duration = modified.duration_since(UNIX_EPOCH).ok()?;
    Some(duration.as_millis() as i64)
}

pub fn list_files_recursive(root_path: &str) -> Result<Vec<IndexedPath>, DecodeError> {
    if root_path.is_empty() {
        return Err(DecodeError {
            message: "Path is empty".into(),
        });
    }
    let root = PathBuf::from(root_path);
    if !root.is_dir() {
        return Err(DecodeError {
            message: "Game root is not a directory".into(),
        });
    }

    let mut out = Vec::new();
    let mut stack = vec![(root.clone(), PathBuf::new(), 0usize)];
    while let Some((dir, rel_dir, depth)) = stack.pop() {
        if depth > 64 {
            continue;
        }
        let entries = match fs::read_dir(&dir) {
            Ok(e) => e,
            Err(_) => continue,
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name();
            let name = Path::new(&name);
            let rel = if rel_dir.as_os_str().is_empty() {
                PathBuf::from(name)
            } else {
                rel_dir.join(name)
            };
            let rel_str = rel.to_string_lossy().replace('\\', "/");
            if is_volatile(&rel_str) {
                continue;
            }
            let ft = match entry.file_type() {
                Ok(ft) => ft,
                Err(_) => continue,
            };
            if ft.is_symlink() {
                continue;
            }
            if ft.is_dir() {
                stack.push((path, rel, depth + 1));
                continue;
            }
            if !ft.is_file() {
                continue;
            }
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            let Some(mtime) = java_compatible_mtime(&meta) else {
                continue;
            };
            out.push(IndexedPath {
                relative_path: rel_str,
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
        let plain = decode_asset(&key, &stored).unwrap();
        assert_eq!(&plain[..16], &png);
    }
}
