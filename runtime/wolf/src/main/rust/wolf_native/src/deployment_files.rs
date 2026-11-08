//! Read-only deployment file queries used by script probes.
use std::path::{Component, Path};

pub fn file_exists(root: &Path, name: &str) -> Result<bool, String> {
    let normalized = name.replace('\\', "/");
    if normalized.is_empty() { return Ok(false); }
    if normalized.contains(':') || normalized.contains('\0') {
        return Err(format!("unsupported deployment file query {name:?}"));
    }
    let relative = Path::new(&normalized);
    if relative.components().any(|part| !matches!(part, Component::Normal(_) | Component::CurDir)) {
        return Err(format!("file query is not deployment-relative: {name:?}"));
    }
    let mut path = root.to_path_buf();
    for part in relative.components() {
        let Component::Normal(part) = part else { continue; };
        let direct = path.join(part);
        if direct.exists() { path = direct; continue; }
        let entries = match std::fs::read_dir(&path) {
            Ok(entries) => entries,
            Err(error) if matches!(error.kind(), std::io::ErrorKind::NotFound | std::io::ErrorKind::NotADirectory) => return Ok(false),
            Err(error) => return Err(format!("file query {}: {error}", path.display())),
        };
        let mut candidates = Vec::new();
        for entry in entries {
            let entry = entry.map_err(|error| format!("file query: {error}"))?;
            if entry.file_name().to_string_lossy().eq_ignore_ascii_case(&part.to_string_lossy()) {
                candidates.push(entry.path());
            }
        }
        candidates.sort();
        let Some(found) = candidates.into_iter().next() else { return Ok(false); };
        path = found;
    }
    match std::fs::metadata(&path) {
        Ok(metadata) => Ok(metadata.is_file()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(format!("file query {}: {error}", path.display())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn probes_existing_missing_and_case_variant_files_without_creating_them() {
        let root = std::env::temp_dir().join(format!("wolf-file-query-{}", std::process::id()));
        std::fs::create_dir_all(root.join("Data")).unwrap();
        std::fs::write(root.join("Data/Test.txt"), b"fixture").unwrap();
        assert!(file_exists(&root, "data\\test.TXT").unwrap());
        assert!(!file_exists(&root, "Data/missing.txt").unwrap());
        assert!(!file_exists(&root, "Data").unwrap());
        assert!(!file_exists(&root, "Data/Test.txt/child").unwrap());
        assert!(!root.join("Data/missing.txt").exists());
        for name in ["../outside", "/absolute", "C:/absolute"] { assert!(file_exists(&root, name).is_err()); }
        std::fs::remove_dir_all(root).unwrap();
    }
}
