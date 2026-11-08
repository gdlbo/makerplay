//! A JNI-reachable entry point for the format layer.
//!
//! The parsers are only useful if they survive into `libwolf_native.so`. With
//! `lto = true` and `strip = true`, anything the linker cannot reach from an
//! exported symbol is discarded — so the crate needs at least one real caller of
//! these parsers, not just unit tests.
//!
//! `probe_deployment` parses a whole game directory and reports what it found as
//! a one-line summary. It is the smoke test that proves the format layer is
//! linked, and it doubles as the first thing the Rust engine will need: the
//! counts and titles the interpreter keys off.

use std::path::Path;

use super::{common_event, database, gamedat, map, maptree, tileset};

/// Result of parsing one deployment.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DeploymentSummary {
    pub title: String,
    pub tile_size: i32,
    pub fps: i32,
    pub screen_width: i32,
    pub screen_height: i32,
    pub database_types: usize,
    pub character_database_types: usize,
    pub system_database_types: usize,
    pub tilesets: usize,
    pub map_tree_nodes: usize,
    pub common_events: usize,
    pub common_event_commands: usize,
    pub maps_parsed: usize,
    pub maps_failed: usize,
    pub map_events: usize,
    /// First failure encountered, if any.
    pub error: Option<String>,
}

impl DeploymentSummary {
    /// True when every file parsed and the counts match this deployment.
    pub fn is_healthy(&self) -> bool {
        self.error.is_none()
            && self.maps_failed == 0
            && self.maps_parsed > 0
            && self.database_types > 0
            && self.common_events > 0
    }
}

/// Parses `data_dir` (the deployment's `Data` folder).
///
/// Never panics: a missing or malformed file is recorded in `error` so the
/// caller can report it rather than take the process down.
pub fn probe_deployment(data_dir: &Path) -> DeploymentSummary {
    let mut summary = DeploymentSummary::default();
    let basic = data_dir.join("BasicData");

    match gamedat::GameDat::parse(&read_or_record(&basic, "Game.dat", &mut summary)) {
        Ok(g) => {
            summary.title = g.title;
            summary.tile_size = g.tile_size;
            summary.fps = g.fps;
            summary.screen_width = g.screen_width;
            summary.screen_height = g.screen_height;
        }
        Err(e) => record(&mut summary, "Game.dat", e),
    }

    for name in ["DataBase.dat", "CDataBase.dat", "SysDatabase.dat"] {
        match database::DataBaseDat::parse(&read_or_record(&basic, name, &mut summary)) {
            Ok(db) => match name {
                "DataBase.dat" => summary.database_types = db.types.len(),
                "CDataBase.dat" => summary.character_database_types = db.types.len(),
                _ => summary.system_database_types = db.types.len(),
            },
            Err(e) => record(&mut summary, name, e),
        }
    }

    match tileset::TileSetData::parse(&read_or_record(&basic, "TileSetData.dat", &mut summary)) {
        Ok(ts) => summary.tilesets = ts.tilesets.len(),
        Err(e) => record(&mut summary, "TileSetData.dat", e),
    }

    match maptree::MapTree::parse(&read_or_record(&basic, "MapTree.dat", &mut summary)) {
        Ok(t) => summary.map_tree_nodes = t.nodes.len(),
        Err(e) => record(&mut summary, "MapTree.dat", e),
    }

    match common_event::CommonEventDat::parse(&read_or_record(
        &basic,
        "CommonEvent.dat",
        &mut summary,
    )) {
        Ok(ce) => {
            summary.common_events = ce.events.len();
            summary.common_event_commands =
                ce.events.iter().map(|e| e.commands.len()).sum();
        }
        Err(e) => record(&mut summary, "CommonEvent.dat", e),
    }

    if let Ok(entries) = std::fs::read_dir(data_dir.join("MapData")) {
        let mut paths: Vec<_> = entries
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.extension()
                    .and_then(|e| e.to_str())
                    .map(|e| e.eq_ignore_ascii_case("mps"))
                    == Some(true)
            })
            .collect();
        paths.sort();
        for path in paths {
            match std::fs::read(&path) {
                Ok(bytes) => match map::MapFile::parse(&bytes) {
                    Ok(m) => {
                        summary.maps_parsed += 1;
                        summary.map_events += m.events.len();
                    }
                    Err(e) => {
                        summary.maps_failed += 1;
                        if summary.error.is_none() {
                            summary.error = Some(format!("{}: {e}", path.display()));
                        }
                    }
                },
                Err(e) => {
                    summary.maps_failed += 1;
                    if summary.error.is_none() {
                        summary.error = Some(format!("{}: {e}", path.display()));
                    }
                }
            }
        }
    }

    summary
}

fn read_or_record(basic: &Path, name: &str, summary: &mut DeploymentSummary) -> Vec<u8> {
    let path = basic.join(name);
    match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(e) => {
            if summary.error.is_none() {
                summary.error = Some(format!("{}: {e}", path.display()));
            }
            Vec::new()
        }
    }
}

fn record(summary: &mut DeploymentSummary, name: &str, error: impl std::fmt::Display) {
    if summary.error.is_none() {
        summary.error = Some(format!("{name}: {error}"));
    }
}

impl std::fmt::Display for DeploymentSummary {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "title={:?} tile={} fps={} screen={}x{} db={}/{}/{} tilesets={} \
             maptree={} ce={} ({} cmds) maps={}/{} events={}",
            self.title,
            self.tile_size,
            self.fps,
            self.screen_width,
            self.screen_height,
            self.database_types,
            self.character_database_types,
            self.system_database_types,
            self.tilesets,
            self.map_tree_nodes,
            self.common_events,
            self.common_event_commands,
            self.maps_parsed,
            self.maps_parsed + self.maps_failed,
            self.map_events,
        )?;
        if let Some(e) = &self.error {
            write!(f, " error={e}")?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_a_missing_directory_as_an_error_without_panicking() {
        let summary = probe_deployment(Path::new("Z:/definitely/not/a/real/path"));
        assert!(!summary.is_healthy());
        assert!(summary.error.is_some());
        assert_eq!(summary.maps_parsed, 0);
    }

    #[test]
    fn summary_display_mentions_the_failure() {
        let summary = DeploymentSummary {
            error: Some("boom".into()),
            ..Default::default()
        };
        assert!(summary.to_string().contains("error=boom"));
        assert!(!summary.is_healthy());
    }

    #[test]
    fn a_healthy_summary_needs_parsed_maps_and_databases() {
        let healthy = DeploymentSummary {
            maps_parsed: 305,
            database_types: 31,
            common_events: 594,
            ..Default::default()
        };
        assert!(healthy.is_healthy());

        let no_maps = DeploymentSummary {
            maps_parsed: 0,
            database_types: 31,
            common_events: 594,
            ..Default::default()
        };
        assert!(!no_maps.is_healthy());
    }


}
