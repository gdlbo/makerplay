//! `Game.dat` — project settings.
//!
//! Port of `core/wolfformat/GameDat.kt`. The file opens with the core magic,
//! then `00 'F' 'M'`, and *then* the version byte — unlike the database family,
//! which puts the version immediately after the magic.

use super::bounded::{BoundedReader, FormatError, Result};
use super::container::WolfContainer;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GameDat {
    pub v3: bool,
    pub title: String,
    pub serial: String,
    pub encryption_key: String,
    pub starting_hero_graphic: String,
    pub screen_width: i32,
    pub screen_height: i32,
    pub tile_size: i32,
    pub fps: i32,
    pub hero_move_speed: i32,
}

impl GameDat {
    pub fn parse(data: &[u8]) -> Result<Self> {
        if !WolfContainer::is_wolf_dat(data) {
            return Err(FormatError("not a WOLF data file".into()));
        }
        let mut r = BoundedReader::at(data, 6)?;
        let tag = r.bytes(4, "Game.dat tag")?;
        if tag[0] != 0x00 || tag[1] != b'F' || tag[2] != b'M' {
            return Err(FormatError("not a Game.dat file".into()));
        }
        let v3 = match tag[3] {
            WolfContainer::VERSION_V2 => false,
            WolfContainer::VERSION_V3 => true,
            other => {
                return Err(FormatError(format!(
                    "unknown WOLF version header 0x{other:02x}"
                )))
            }
        };

        let u8_len = r.u32()? as usize;
        if !(20..=64).contains(&u8_len) {
            return Err(FormatError(format!(
                "unexpected Game.dat u8 settings length {u8_len}"
            )));
        }
        let u8_settings = r.bytes(u8_len, "Game.dat u8 settings")?;
        let tile_size = u8_settings[0] as i32;
        if !matches!(tile_size, 16 | 32 | 40 | 48) {
            return Err(FormatError(format!("unknown tile size {tile_size}")));
        }
        let fps = u8_settings.get(4).copied().unwrap_or(60) as i32;
        let hero_move_speed = u8_settings.get(16).copied().unwrap_or(4).clamp(4, 9) as i32;

        // Editor revisions differ by a few bytes before the string table, so scan
        // a small window for an offset whose length field validates.
        let (strings_offset, string_count) = locate_string_table(data, r.position(), v3)?;
        let mut sr = BoundedReader::at(data, strings_offset + 4)?;

        let mut title = String::new();
        let mut serial = String::new();
        let mut encryption_key = String::new();
        let mut starting_hero_graphic = String::new();
        for index in 0..string_count {
            let value = sr.string(v3)?;
            match index {
                0 => title = value,
                1 => serial = value,
                2 => encryption_key = value,
                7 => starting_hero_graphic = value,
                _ => {}
            }
        }

        // The u16 settings block is `[filesize marker][unknown][count][count u2]`.
        // Screen width/height sit at indices 16 and 17; index 18 is the editor
        // version word.
        let mut width = 0;
        let mut height = 0;
        sr.u32()?; // static randoms section size marker
        sr.u32()?; // unknown
        let u16_count = sr.count("Game.dat u16 settings")?;
        if u16_count < 19 {
            return Err(FormatError(format!(
                "Game.dat u16 settings too short: {u16_count}"
            )));
        }
        for index in 0..u16_count {
            let value = sr.u16()? as i32;
            match index {
                16 => width = value,
                17 => height = value,
                _ => {}
            }
        }
        if width <= 0 || height <= 0 || width > 16384 || height > 16384 {
            return Err(FormatError(format!(
                "unreasonable Game.dat resolution {width}x{height}"
            )));
        }

        Ok(Self {
            v3,
            title,
            serial,
            encryption_key,
            starting_hero_graphic,
            screen_width: width,
            screen_height: height,
            tile_size,
            fps,
            hero_move_speed,
        })
    }
}

/// Finds the string table by scanning for an offset whose `u4` count and first
/// string length both look sane.
fn locate_string_table(data: &[u8], from: usize, v3: bool) -> Result<(usize, usize)> {
    for offset in from..from.saturating_add(64).min(data.len().saturating_sub(8)) {
        let mut r = match BoundedReader::at(data, offset) {
            Ok(r) => r,
            Err(_) => continue,
        };
        let count = match r.u32() {
            Ok(c) => c,
            Err(_) => continue,
        };
        let expected = if v3 { 9..=32 } else { 5..=20 };
        if !expected.contains(&count) {
            continue;
        }
        // Every entry must decode, and a plausible filesize marker must follow
        // the table. Requiring the marker is what distinguishes the real table
        // from an offset that merely happens to start with a small number.
        let mut valid = true;
        for _ in 0..count {
            if r.string(v3).is_err() {
                valid = false;
                break;
            }
        }
        if !valid {
            continue;
        }
        // `r.position()` is already absolute, so the marker starts there.
        match BoundedReader::at(data, r.position()).and_then(|mut m| m.u32()) {
            Ok(marker) if (marker as usize) <= data.len() => {
                return Ok((offset, count as usize))
            }
            _ => continue,
        }
    }
    Err(FormatError(
        "could not locate the Game.dat string table".into(),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a minimal but structurally valid Game.dat.
    fn build(tile: u8, fps: u8, speed: u8, strings: &[&str], w: u16, h: u16) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&WolfContainer::CORE_MAGIC);
        v.extend_from_slice(&[0x00, b'F', b'M', WolfContainer::VERSION_V3]);
        let mut u8s = vec![0u8; 32];
        u8s[0] = tile;
        u8s[4] = fps;
        u8s[16] = speed;
        v.extend_from_slice(&(u8s.len() as u32).to_le_bytes());
        v.extend_from_slice(&u8s);
        v.extend_from_slice(&(strings.len() as u32).to_le_bytes());
        for s in strings {
            let mut bytes = s.as_bytes().to_vec();
            bytes.push(0);
            v.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
            v.extend_from_slice(&bytes);
        }
        // [filesize marker][unknown][count][count u2 values], with the screen
        // dimensions at indices 16 and 17.
        v.extend_from_slice(&((v.len() as u32) + 8 + 4 + 19 * 2).to_le_bytes());
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&19u32.to_le_bytes());
        for index in 0..19 {
            let value: u16 = match index {
                16 => w,
                17 => h,
                _ => 0,
            };
            v.extend_from_slice(&value.to_le_bytes());
        }
        v
    }

    fn names() -> Vec<&'static str> {
        // Nine slots, matching what the locator accepts for v3 files.
        let mut v = vec![""; 9];
        v[0] = "Synthetic WOLF Game";
        v[1] = "RJ01724124";
        v[2] = "KEY";
        v[7] = "CharaChipA/a_02.png";
        v
    }

    #[test]
    fn parses_project_settings() {
        let data = build(40, 60, 4, &names(), 1280, 960);
        let g = GameDat::parse(&data).unwrap();
        assert!(g.v3);
        assert_eq!(g.title, "Synthetic WOLF Game");
        assert_eq!(g.serial, "RJ01724124");
        assert_eq!(g.encryption_key, "KEY");
        assert_eq!(g.starting_hero_graphic, "CharaChipA/a_02.png");
        assert_eq!(g.tile_size, 40);
        assert_eq!(g.fps, 60);
        assert_eq!(g.screen_width, 1280);
        assert_eq!(g.screen_height, 960);
    }

    #[test]
    fn rejects_a_non_wolf_file() {
        assert!(GameDat::parse(b"not a wolf file at all").is_err());
    }

    #[test]
    fn rejects_an_unknown_tile_size() {
        let data = build(37, 60, 4, &names(), 640, 480);
        assert!(GameDat::parse(&data).is_err());
    }

    #[test]
    fn clamps_the_hero_speed_to_the_documented_range() {
        let data = build(40, 60, 99, &names(), 640, 480);
        let g = GameDat::parse(&data).unwrap();
        assert_eq!(g.hero_move_speed, 9, "speeds above 9 clamp to 9");
    }
}
