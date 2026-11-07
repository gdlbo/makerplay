//! Shared container constants for WOLF `.dat` files.

use super::bounded::{BoundedReader, FormatError, Result};

pub struct WolfContainer;

impl WolfContainer {
    /// Every WOLF `.dat` opens with `00 'W' 00 00 'O' 'L'`.
    pub const CORE_MAGIC: [u8; 6] = [0x00, b'W', 0x00, 0x00, b'O', b'L'];

    /// Editor v2 (Shift-JIS strings).
    pub const VERSION_V2: u8 = 0x00;
    /// Editor v3 (UTF-8 strings).
    pub const VERSION_V3: u8 = 0x55;

    /// Database-family footer bytes.
    pub const FOOTER_V2: u8 = 0xC2;
    pub const FOOTER_V30: u8 = 0xC3;
    pub const FOOTER_V33: u8 = 0xC4;
    /// TileSetData closes with its own constant.
    pub const FOOTER_TILESET: u8 = 0xCF;

    /// True when `data` starts with the WOLF core magic.
    pub fn is_wolf_dat(data: &[u8]) -> bool {
        data.len() >= Self::CORE_MAGIC.len() && data[..6] == Self::CORE_MAGIC
    }

    /// Reads the core magic and version byte. Returns true for v3 (UTF-8).
    ///
    /// `Game.dat` puts its version byte *after* a 3-byte tag, so it does not use
    /// this helper.
    pub fn read_core(reader: &mut BoundedReader<'_>) -> Result<bool> {
        let magic = reader.bytes(6, "WOLF magic")?;
        if magic != Self::CORE_MAGIC {
            return Err(FormatError("not a WOLF data file".into()));
        }
        let version = reader.u8()?;
        match version {
            Self::VERSION_V2 => Ok(false),
            Self::VERSION_V3 => Ok(true),
            other => Err(FormatError(format!(
                "unknown WOLF version header 0x{other:02x}"
            ))),
        }
    }

    /// Validates `core magic + version + 'FM\0' tag` and consumes the per-file
    /// revision byte, returning whether the file is v3.
    pub fn read_standard_dat_header(reader: &mut BoundedReader<'_>) -> Result<bool> {
        let v3 = Self::read_core(reader)?;
        let tag = reader.bytes(3, "file tag")?;
        if tag != [b'F', b'M', 0x00] {
            return Err(FormatError("unexpected WOLF file tag; expected 'FM'".into()));
        }
        reader.u8()?; // revision byte
        Ok(v3)
    }

    /// Consumes the closing footer and rejects anything outside `accepted`.
    pub fn read_footer(reader: &mut BoundedReader<'_>, accepted: &[u8]) -> Result<u8> {
        let footer = reader.u8()?;
        if !accepted.contains(&footer) {
            return Err(FormatError(format!(
                "invalid WOLF footer byte 0x{footer:02x}"
            )));
        }
        Ok(footer)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(v3: bool, revision: u8) -> Vec<u8> {
        let mut v = WolfContainer::CORE_MAGIC.to_vec();
        v.push(if v3 { WolfContainer::VERSION_V3 } else { WolfContainer::VERSION_V2 });
        v.extend_from_slice(&[b'F', b'M', 0x00]);
        v.push(revision);
        v
    }

    #[test]
    fn recognises_core_magic() {
        assert!(WolfContainer::is_wolf_dat(&WolfContainer::CORE_MAGIC));
        assert!(!WolfContainer::is_wolf_dat(b"PK\x03\x04"));
        assert!(!WolfContainer::is_wolf_dat(b"\x00W"));
    }

    #[test]
    fn reads_v3_and_v2_headers() {
        for (v3, revision) in [(true, 0xC4u8), (false, 0xC2)] {
            let data = header(v3, revision);
            let mut r = BoundedReader::new(&data);
            assert_eq!(WolfContainer::read_standard_dat_header(&mut r).unwrap(), v3);
            assert_eq!(r.remaining(), 0);
        }
    }

    #[test]
    fn rejects_a_wrong_tag() {
        let mut data = header(true, 0xC4);
        data[7] = b'X';
        let mut r = BoundedReader::new(&data);
        assert!(WolfContainer::read_standard_dat_header(&mut r).is_err());
    }

    #[test]
    fn validates_footers() {
        let accepted = [
            WolfContainer::FOOTER_V2,
            WolfContainer::FOOTER_V30,
            WolfContainer::FOOTER_V33,
        ];
        let ok = [WolfContainer::FOOTER_V33];
        let mut r = BoundedReader::new(&ok);
        assert!(WolfContainer::read_footer(&mut r, &accepted).is_ok());

        let bad = [0x00u8];
        let mut r = BoundedReader::new(&bad);
        assert!(WolfContainer::read_footer(&mut r, &accepted).is_err());
    }
}
