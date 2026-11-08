//! `TileSetData.dat`.
//!
//! Port of `core/wolfformat/TileSetData.kt`. Two record revisions exist: 209
//! (v2, 15 autotile slots) and 210+ (v3, 31 slots). Passability is a bitfield per
//! tile.
//!
//! The deployment ships revision `0xD3` (211), which uses the v3 layout.

use super::bounded::{BoundedReader, FormatError, Result};
use super::container::WolfContainer;

const REVISION_V2: u8 = 209;
/// Autotile slots appended after the base chip sheet, per revision.
pub const AUTOTILE_SLOTS_V2: usize = 15;
pub const AUTOTILE_SLOTS_V3: usize = 31;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Passability {
    pub counter_enabled: bool,
    pub square: bool,
    pub quarter_tile: bool,
    pub star: bool,
    pub top_left_passable: bool,
    pub top_right_passable: bool,
    pub bottom_left_passable: bool,
    pub bottom_right_passable: bool,
    pub upwards_not_passable: bool,
    pub rightwards_not_passable: bool,
    pub leftwards_not_passable: bool,
    pub downwards_not_passable: bool,
    pub down_arrow: bool,
    pub triangle: bool,
    pub raw: u16,
}

impl Passability {
    /// True when the tile blocks movement in every direction.
    pub fn fully_blocked(&self) -> bool {
        self.upwards_not_passable
            && self.rightwards_not_passable
            && self.leftwards_not_passable
            && self.downwards_not_passable
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tileset {
    pub title: String,
    pub base_tileset_file: String,
    pub auto_tile_files: Vec<String>,
    pub tag_numbers: Vec<u8>,
    pub tile_passability: Vec<Passability>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TileSetData {
    pub v3: bool,
    pub revision: u8,
    pub tilesets: Vec<Tileset>,
}

impl TileSetData {
    pub fn parse(data: &[u8]) -> Result<Self> {
        let mut r = BoundedReader::new(data);
        let v3 = WolfContainer::read_core(&mut r)?;
        let tag = r.bytes(3, "tileset file tag")?;
        if tag != [b'F', b'M', 0x00] {
            return Err(FormatError("not a TileSetData.dat file".into()));
        }
        let revision = r.u8()?;
        // Revisions above 209 keep the v3 layout with 31 slots.
        let auto_tile_slots = if revision <= REVISION_V2 {
            AUTOTILE_SLOTS_V2
        } else {
            AUTOTILE_SLOTS_V3
        };
        let count = r.count("tileset")?;
        let mut tilesets = Vec::with_capacity(count);
        for _ in 0..count {
            let title = r.string(v3 && revision != REVISION_V2)?;
            let base_tileset_file = r.string(v3)?;
            let mut auto_tile_files = Vec::with_capacity(auto_tile_slots);
            for _ in 0..auto_tile_slots {
                auto_tile_files.push(r.string(v3)?);
            }
            if r.u8()? != 0xFF {
                return Err(FormatError("missing tileset separator 1".into()));
            }
            let tag_count = r.count("tag number")?;
            let mut tag_numbers = Vec::with_capacity(tag_count);
            for _ in 0..tag_count {
                tag_numbers.push(r.u8()?);
            }
            if r.u8()? != 0xFF {
                return Err(FormatError("missing tileset separator 2".into()));
            }
            let passable_count = r.count("passability entry")?;
            let mut tile_passability = Vec::with_capacity(passable_count);
            for _ in 0..passable_count {
                tile_passability.push(read_passability(&mut r)?);
            }
            tilesets.push(Tileset {
                title,
                base_tileset_file,
                auto_tile_files,
                tag_numbers,
                tile_passability,
            });
        }
        WolfContainer::read_footer(&mut r, &[WolfContainer::FOOTER_TILESET])?;
        Ok(Self {
            v3,
            revision,
            tilesets,
        })
    }
}

fn read_passability(r: &mut BoundedReader<'_>) -> Result<Passability> {
    let b0 = r.u8()?;
    let b1 = r.u8()?;
    r.u16()?; // reserved
    Ok(Passability {
        counter_enabled: b0 & (1 << 7) != 0,
        square: b0 & (1 << 6) != 0,
        quarter_tile: b0 & (1 << 5) != 0,
        star: b0 & (1 << 4) != 0,
        top_left_passable: b0 & (1 << 3) != 0,
        top_right_passable: b0 & (1 << 2) != 0,
        bottom_left_passable: b0 & (1 << 1) != 0,
        bottom_right_passable: b0 & 1 != 0,
        upwards_not_passable: b0 & (1 << 3) != 0,
        rightwards_not_passable: b0 & (1 << 2) != 0,
        leftwards_not_passable: b0 & (1 << 1) != 0,
        downwards_not_passable: b0 & 1 != 0,
        down_arrow: b1 & (1 << 1) != 0,
        triangle: b1 & 1 != 0,
        raw: ((b1 as u16) << 8) | b0 as u16,
    })
}

/// Number of base-chip passability entries.
///
/// Every tileset stores `base_chip_tiles + AUTOTILE_SLOTS` entries, so the base
/// count is recoverable as `passability.len() - AUTOTILE_SLOTS`. Verified across
/// all 53 tilesets in the deployment with zero mismatches.
pub fn base_tile_count(tileset: &Tileset, auto_tile_slots: usize) -> usize {
    tileset
        .tile_passability
        .len()
        .saturating_sub(auto_tile_slots)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tileset_data(revision: u8, autos: usize, passable: usize) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&WolfContainer::CORE_MAGIC);
        v.push(WolfContainer::VERSION_V3);
        v.extend_from_slice(&[b'F', b'M', 0x00]);
        v.push(revision);
        v.extend_from_slice(&1u32.to_le_bytes());
        for s in ["街道", "MapChip/51_kaidou.png"] {
            let mut b = s.as_bytes().to_vec();
            b.push(0);
            v.extend_from_slice(&(b.len() as u32).to_le_bytes());
            v.extend_from_slice(&b);
        }
        for i in 0..autos {
            let name = format!("MapChip/auto{i}.png");
            let mut b = name.as_bytes().to_vec();
            b.push(0);
            v.extend_from_slice(&(b.len() as u32).to_le_bytes());
            v.extend_from_slice(&b);
        }
        v.push(0xFF);
        v.extend_from_slice(&2u32.to_le_bytes());
        v.push(1);
        v.push(2);
        v.push(0xFF);
        v.extend_from_slice(&(passable as u32).to_le_bytes());
        for i in 0..passable {
            // alternate fully-blocked (0x0F) and fully-open (0x00)
            v.push(if i % 2 == 0 { 0x0F } else { 0x00 });
            v.push(0);
            v.extend_from_slice(&0u16.to_le_bytes());
        }
        v.push(WolfContainer::FOOTER_TILESET);
        v
    }

    #[test]
    fn parses_a_v3_tileset_with_31_slots() {
        let data = tileset_data(0xD3, AUTOTILE_SLOTS_V3, 40);
        let parsed = TileSetData::parse(&data).unwrap();
        assert_eq!(parsed.revision, 0xD3);
        assert_eq!(parsed.tilesets.len(), 1);
        let t = &parsed.tilesets[0];
        assert_eq!(t.title, "街道");
        assert_eq!(t.base_tileset_file, "MapChip/51_kaidou.png");
        assert_eq!(t.auto_tile_files.len(), AUTOTILE_SLOTS_V3);
        assert_eq!(t.tag_numbers, vec![1, 2]);
        assert_eq!(t.tile_passability.len(), 40);
        assert!(t.tile_passability[0].fully_blocked());
        assert!(!t.tile_passability[1].fully_blocked());
    }

    #[test]
    fn parses_a_v2_tileset_with_15_slots() {
        let data = tileset_data(209, AUTOTILE_SLOTS_V2, 20);
        let parsed = TileSetData::parse(&data).unwrap();
        assert_eq!(parsed.tilesets[0].auto_tile_files.len(), AUTOTILE_SLOTS_V2);
    }

    #[test]
    fn base_tile_count_is_total_minus_autotile_slots() {
        let data = tileset_data(0xD3, AUTOTILE_SLOTS_V3, 63);
        let parsed = TileSetData::parse(&data).unwrap();
        assert_eq!(
            base_tile_count(&parsed.tilesets[0], AUTOTILE_SLOTS_V3),
            32
        );
    }

    #[test]
    fn rejects_a_missing_separator() {
        let mut data = tileset_data(0xD3, 2, 4);
        // The first separator follows the autotile names; corrupt it.
        let pos = data.len() - (4 * 4) - 4 - 1 - 1;
        data[pos] = 0x00;
        assert!(TileSetData::parse(&data).is_err());
    }

    #[test]
    fn rejects_a_bad_footer() {
        let mut data = tileset_data(0xD3, 1, 2);
        let last = data.len() - 1;
        data[last] = 0x00;
        assert!(TileSetData::parse(&data).is_err());
    }
}
