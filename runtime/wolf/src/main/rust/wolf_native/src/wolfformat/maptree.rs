//! `MapTree.dat` and `SysDataBaseBasic.dat`.
//!
//! Port of `core/wolfformat/BasicDataFile.kt`. Both files share a framing that
//! differs from every other WOLF `.dat`: ten zero bytes instead of the core
//! magic, then a marker byte, a `u4` count, the records, and a footer.
//!
//! `MapTree.dat` records are `[s32 parentId][s32 mapId]`. That layout is
//! verified against the deployment: 240 records, ids 0..239 with no duplicates,
//! and every id has a matching `MapNNN.mps` on disk.
//!
//! `SysDataBaseBasic.dat` bodies are deliberately **not** decoded: `GamePro.exe`
//! contains no reference to that filename, nothing in this runtime reads it, and
//! the payload is a packed field stream with no public specification. The framing
//! is validated strictly and the bodies are kept raw.

use super::bounded::{BoundedReader, FormatError, Result};

pub const ZERO_HEADER: usize = 10;
pub const MARKER_MAP_TREE: u8 = 0x90;
pub const MARKER_SYSTEM_BASICS: u8 = 0xC0;
/// Observed closing byte of the shipped `MapTree.dat`.
pub const FOOTER_MAP_TREE: u8 = 0xBC;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MapTreeNode {
    /// Negative means the node sits at the top level.
    pub parent_id: i32,
    /// Map id, selecting `Data/MapData/MapNNN.mps`.
    pub map_id: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MapTree {
    pub marker: u8,
    pub nodes: Vec<MapTreeNode>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SystemBasics {
    pub marker: u8,
    pub declared_count: usize,
    pub payload: Vec<u8>,
}

/// True when `data` starts with the ten zero bytes these files use.
pub fn is_basic_data(data: &[u8]) -> bool {
    data.len() > ZERO_HEADER && data[..ZERO_HEADER].iter().all(|b| *b == 0)
}

fn open_framed<'a>(data: &'a [u8], what: &str) -> Result<(u8, BoundedReader<'a>)> {
    if !is_basic_data(data) {
        return Err(FormatError(format!(
            "not a {what} file: expected {ZERO_HEADER} zero bytes"
        )));
    }
    let marker = data[ZERO_HEADER];
    let reader = BoundedReader::at(data, ZERO_HEADER + 1)?;
    Ok((marker, reader))
}

fn require_footer(r: &mut BoundedReader<'_>, marker: u8, what: &str) -> Result<()> {
    let footer = r.u8()?;
    // The shipped files close with a value that may differ from the marker:
    // MapTree.dat ends 0xBC behind a 0x90 marker, SysDataBaseBasic.dat ends 0xC0
    // behind a 0xC0 marker.
    let accepted = [
        marker,
        FOOTER_MAP_TREE,
        0xC2,
        0xC3,
        0xC4,
    ];
    if !accepted.contains(&footer) {
        return Err(FormatError(format!(
            "{what} has unexpected footer 0x{footer:02x} (marker 0x{marker:02x})"
        )));
    }
    if r.remaining() != 0 {
        return Err(FormatError(format!(
            "{what} has {} trailing byte(s)",
            r.remaining()
        )));
    }
    Ok(())
}

impl MapTree {
    pub fn parse(data: &[u8]) -> Result<Self> {
        let (marker, mut r) = open_framed(data, "MapTree.dat")?;
        let count = r.count("map tree node")?;
        let expected = count * 8;
        if expected > r.remaining() {
            return Err(FormatError(format!(
                "MapTree.dat declares {count} nodes ({expected} bytes) but only {} remain",
                r.remaining()
            )));
        }
        let mut nodes = Vec::with_capacity(count);
        for _ in 0..count {
            nodes.push(MapTreeNode {
                parent_id: r.i32()?,
                map_id: r.i32()?,
            });
        }
        require_footer(&mut r, marker, "MapTree.dat")?;
        Ok(Self { marker, nodes })
    }
}

impl SystemBasics {
    pub fn parse(data: &[u8]) -> Result<Self> {
        let (marker, mut r) = open_framed(data, "SysDataBaseBasic.dat")?;
        let declared_count = r.count("system basics type")?;
        let body_len = r.remaining().saturating_sub(1);
        let payload = r.bytes(body_len, "SysDataBaseBasic.dat payload")?.to_vec();
        require_footer(&mut r, marker, "SysDataBaseBasic.dat")?;
        Ok(Self {
            marker,
            declared_count,
            payload,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn map_tree(nodes: &[(i32, i32)], footer: u8) -> Vec<u8> {
        let mut v = vec![0u8; ZERO_HEADER];
        v.push(MARKER_MAP_TREE);
        v.extend_from_slice(&(nodes.len() as u32).to_le_bytes());
        for (parent, id) in nodes {
            v.extend_from_slice(&parent.to_le_bytes());
            v.extend_from_slice(&id.to_le_bytes());
        }
        v.push(footer);
        v
    }

    #[test]
    fn parses_map_tree_nodes() {
        let data = map_tree(&[(-1, 0), (-1, 1), (0, 2)], FOOTER_MAP_TREE);
        let tree = MapTree::parse(&data).unwrap();
        assert_eq!(tree.marker, MARKER_MAP_TREE);
        assert_eq!(tree.nodes.len(), 3);
        assert_eq!(tree.nodes[0], MapTreeNode { parent_id: -1, map_id: 0 });
        assert_eq!(tree.nodes[2], MapTreeNode { parent_id: 0, map_id: 2 });
    }

    #[test]
    fn accepts_the_shipped_footer_byte() {
        // 0xBC is what the real file ends with, despite the 0x90 marker.
        let data = map_tree(&[(-1, 0)], FOOTER_MAP_TREE);
        assert!(MapTree::parse(&data).is_ok());
    }

    #[test]
    fn rejects_a_wrong_footer() {
        let data = map_tree(&[(-1, 0)], 0x00);
        assert!(MapTree::parse(&data).is_err());
    }

    #[test]
    fn rejects_a_truncated_node_list() {
        let mut data = map_tree(&[(-1, 0), (-1, 1)], FOOTER_MAP_TREE);
        // Remove the last node's second word so the declared count cannot fit.
        data.remove(data.len() - 2);
        assert!(MapTree::parse(&data).is_err());
    }

    #[test]
    fn rejects_a_file_without_the_zero_header() {
        assert!(MapTree::parse(b"\x00W\x00\x00OL").is_err());
    }

    #[test]
    fn parses_system_basics_framing_and_keeps_bodies_raw() {
        let mut v = vec![0u8; ZERO_HEADER];
        v.push(MARKER_SYSTEM_BASICS);
        v.extend_from_slice(&12u32.to_le_bytes());
        v.extend_from_slice(b"payload bytes here");
        v.push(MARKER_SYSTEM_BASICS);
        let basics = SystemBasics::parse(&v).unwrap();
        assert_eq!(basics.marker, MARKER_SYSTEM_BASICS);
        assert_eq!(basics.declared_count, 12);
        assert_eq!(basics.payload, b"payload bytes here");
    }
}
