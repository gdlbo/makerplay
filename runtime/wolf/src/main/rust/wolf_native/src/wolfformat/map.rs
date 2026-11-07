//! `.mps` map files.
//!
//! Port of `core/wolfformat/MapFile.kt`. A map is ten zero bytes, `"WOLFM"`, a
//! NUL, a version byte, then (from revision 0x65) an LZ4-packed body.
//!
//! Two things here were only settled by reading the Kotlin parser and the real
//! files, not by guessing:
//!
//! * The layer block comes **before** the events, and it is one contiguous run of
//!   `width * height * layerCount` words. A leading `0xFFFFFFFF` marks an empty
//!   map whose layer words are all absent.
//! * `layerCount` is read from the file only at revision 0x67 and above; older
//!   maps always have three planes.
//!
//! The title length field sits at a revision-dependent offset, so candidate
//! offsets are scanned and the first one that parses through the footer wins.

use super::bounded::{BoundedReader, FormatError, Result};
use super::container::WolfContainer;
use super::event_command::{EventCommand, MoveRoute};
use super::lz4;

const EVENT_HEADER: u8 = 0x6F;
const EVENT_HEADER2: u32 = 0x3039;
const EVENT_FOOTER: u8 = 0x70;
const PAGE_HEADER: u8 = 0x79;
const PAGE_FOOTER: u8 = 0x7A;
const EMPTY_LAYERS: u32 = 0xFFFF_FFFF;
/// Revision at which the body becomes LZ4-packed.
const REVISION_PACKED: u8 = 0x65;
/// Revision at which the layer count is stored and commands gain metadata.
const REVISION_LAYER_COUNT: u8 = 0x67;
/// Earliest and latest observed offsets of the title length field.
const TITLE_LEN_MIN: usize = 21;
const TITLE_LEN_MAX: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MapPage {
    pub graphic_chip_id: i32,
    pub graphic_file: String,
    pub graphic_row: u8,
    pub graphic_col: u8,
    pub graphic_opacity: u8,
    /// 0 confirm key, 1 autorun, 2 parallel, 3 player touch, 4 event touch.
    pub trigger_condition: i32,
    pub commands: Vec<EventCommand>,
    pub route: MoveRoute,
    pub shadow_graphic_id: u8,
    pub range_extension_x: u8,
    pub range_extension_y: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MapEvent {
    pub event_id: i32,
    pub title: String,
    pub x: i32,
    pub y: i32,
    pub pages: Vec<MapPage>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MapFile {
    pub v3: bool,
    pub revision: u8,
    pub title: String,
    pub tileset_id: i32,
    pub width: i32,
    pub height: i32,
    /// `layer_count` planes of `width*height` raw words. Values `>= 100000`
    /// encode autotiles (`index = value / 100000 - 1`).
    pub layers: Vec<Vec<i32>>,
    pub events: Vec<MapEvent>,
}

impl MapFile {
    pub fn parse(data: &[u8]) -> Result<Self> {
        validate_magic(data)?;
        let revision = if data.len() > 20 { data[20] } else { 0 };

        let (working, body_offset): (Vec<u8>, Option<usize>) = if revision >= REVISION_PACKED {
            let mut head = BoundedReader::at(data, 25)?;
            let decompressed_size = head.u32()? as usize;
            let compressed_size = head.u32()? as usize;
            if compressed_size > head.remaining() {
                return Err(FormatError("invalid v3.5 map compression header".into()));
            }
            let compressed = head.bytes(compressed_size, "v3.5 map payload")?;
            let body = lz4::decompress(compressed, decompressed_size)?;
            let mut out = data[..25].to_vec();
            out.extend_from_slice(&body);
            (out, Some(25))
        } else {
            (data.to_vec(), None)
        };

        let candidates: Vec<usize> = match body_offset {
            Some(o) => vec![o],
            None => (TITLE_LEN_MIN..=TITLE_LEN_MAX).collect(),
        };
        let mut last_error = None;
        for title_len_offset in candidates {
            if title_len_offset + 4 > working.len() {
                break;
            }
            match parse_body(&working, title_len_offset, revision) {
                Ok(map) => return Ok(map),
                Err(e) => last_error = Some(e),
            }
        }
        Err(last_error.unwrap_or_else(|| FormatError("not a WOLF map file".into())))
    }

    /// Autotile slot index for a raw layer value, or `None` for a base chip.
    pub fn autotile_index(raw: i32) -> Option<usize> {
        if raw >= 100_000 {
            Some((raw / 100_000 - 1) as usize)
        } else {
            None
        }
    }
}

fn validate_magic(data: &[u8]) -> Result<()> {
    if data.len() < 16 {
        return Err(FormatError("map file is too short".into()));
    }
    if data[..10].iter().any(|b| *b != 0) {
        return Err(FormatError("map file lacks the zero prefix".into()));
    }
    if &data[10..15] != b"WOLFM" {
        return Err(FormatError("not a WOLF map file".into()));
    }
    if data[15] != 0 {
        return Err(FormatError("unexpected byte after the map magic".into()));
    }
    Ok(())
}

fn parse_body(data: &[u8], title_len_offset: usize, revision: u8) -> Result<MapFile> {
    let mut head = BoundedReader::new(data);
    head.bytes(16, "mps magic")?;
    let v3 = match head.u8()? {
        WolfContainer::VERSION_V2 => false,
        WolfContainer::VERSION_V3 => true,
        other => {
            return Err(FormatError(format!("unknown mps version 0x{other:02x}")))
        }
    };

    let mut r = BoundedReader::at(data, title_len_offset)?;
    let title = r.string(v3)?;
    let tileset_id = r.i32()?;
    let width = r.count("map width")? as i32;
    let height = r.count("map height")? as i32;
    if width <= 0 || height <= 0 || width as i64 * height as i64 > 8_000_000 {
        return Err(FormatError(format!("implausible map size {width}x{height}")));
    }
    let event_count = r.count("map event")?;

    // From revision 0x67 the file stores an unknown word and the real layer
    // count; earlier revisions are always three planes.
    let layer_count = if revision >= REVISION_LAYER_COUNT {
        r.i32()?;
        (r.i32()?).clamp(1, 8) as usize
    } else {
        3
    };

    let total_pixels = width as usize * height as usize;
    let first_pixel = r.u32()?;
    let layers: Vec<Vec<i32>> = if first_pixel == EMPTY_LAYERS {
        // An empty map stores only the marker word.
        vec![vec![0i32; total_pixels]; layer_count]
    } else {
        let total = total_pixels * layer_count;
        let mut all = Vec::with_capacity(total);
        all.push(first_pixel as i32);
        for _ in 1..total {
            all.push(r.i32()?);
        }
        (0..layer_count)
            .map(|plane| all[plane * total_pixels..(plane + 1) * total_pixels].to_vec())
            .collect()
    };

    let mut events = Vec::with_capacity(event_count);
    for _ in 0..event_count {
        events.push(read_event(&mut r, v3, revision)?);
    }
    r.u8()?; // footer byte varies by editor revision

    Ok(MapFile {
        v3,
        revision,
        title,
        tileset_id,
        width,
        height,
        layers,
        events,
    })
}

fn read_event(r: &mut BoundedReader<'_>, v3: bool, revision: u8) -> Result<MapEvent> {
    if r.u8()? != EVENT_HEADER {
        return Err(FormatError("map event missing header".into()));
    }
    if r.u32()? != EVENT_HEADER2 {
        return Err(FormatError("map event missing second header".into()));
    }
    let event_id = r.i32()?;
    let title = r.string(v3)?;
    let x = r.i32()?;
    let y = r.i32()?;
    let page_count = r.count("event page")?;
    if r.u32()? != 0 {
        return Err(FormatError("event separator must be zero".into()));
    }
    let mut pages = Vec::with_capacity(page_count);
    for _ in 0..page_count {
        pages.push(read_page(r, v3, revision)?);
    }
    let footer = r.u8()?;
    if footer != EVENT_FOOTER {
        return Err(FormatError(format!(
            "map event has unexpected footer 0x{footer:02x}"
        )));
    }
    Ok(MapEvent {
        event_id,
        title,
        x,
        y,
        pages,
    })
}

fn read_page(r: &mut BoundedReader<'_>, v3: bool, revision: u8) -> Result<MapPage> {
    if r.u8()? != PAGE_HEADER {
        return Err(FormatError("map page missing header".into()));
    }
    let graphic_chip_id = r.i32()?;
    let graphic_file = r.string(v3)?;
    let graphic_row = r.u8()?;
    let graphic_col = r.u8()?;
    let graphic_opacity = r.u8()?;
    r.u8()?; // render mode
    let trigger_condition = r.u8()? as i32;
    if !(0..=4).contains(&trigger_condition) {
        return Err(FormatError(format!(
            "invalid page trigger {trigger_condition}"
        )));
    }
    r.skip(4, "trigger switches")?;
    r.skip(16, "trigger variables")?;
    r.skip(16, "trigger values")?;
    let route = MoveRoute::parse(r)?;
    let commands = EventCommand::parse_list(r, v3, revision >= REVISION_LAYER_COUNT)?;
    // Feature flags; values above 3 add a page-transfer byte.
    let features = r.i32()?;
    let shadow_graphic_id = r.u8()?;
    let range_extension_x = r.u8()?;
    let range_extension_y = r.u8()?;
    if features > 3 {
        r.u8()?;
    }
    if r.u8()? != PAGE_FOOTER {
        return Err(FormatError("map page missing footer".into()));
    }
    Ok(MapPage {
        graphic_chip_id,
        graphic_file,
        graphic_row,
        graphic_col,
        graphic_opacity,
        trigger_condition,
        commands,
        route,
        shadow_graphic_id,
        range_extension_x,
        range_extension_y,
    })
}

/// True when the file looks like a `.mps` map by its magic.
pub fn is_map(data: &[u8]) -> bool {
    validate_magic(data).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds an unpacked (revision below 0x65) map so the title length can sit
    /// at a scanned offset.
    fn tiny_map(width: i32, height: i32, events: usize) -> Vec<u8> {
        let mut v = vec![0u8; 10];
        v.extend_from_slice(b"WOLFM");
        v.push(0);
        v.push(WolfContainer::VERSION_V3);
        // Pad so the title length field lands at TITLE_LEN_MIN.
        while v.len() < TITLE_LEN_MIN {
            v.push(0);
        }

        let mut body = Vec::new();
        let title = "m";
        let mut b = title.as_bytes().to_vec();
        b.push(0);
        body.extend_from_slice(&(b.len() as u32).to_le_bytes());
        body.extend_from_slice(&b);
        body.extend_from_slice(&1i32.to_le_bytes()); // tileset
        body.extend_from_slice(&(width as u32).to_le_bytes());
        body.extend_from_slice(&(height as u32).to_le_bytes());
        body.extend_from_slice(&(events as u32).to_le_bytes());
        // Layers come before events.
        for plane in 0..3 {
            for _ in 0..(width * height) {
                body.extend_from_slice(&(plane as i32).to_le_bytes());
            }
        }
        for _ in 0..events {
            body.push(EVENT_HEADER);
            body.extend_from_slice(&EVENT_HEADER2.to_le_bytes());
            body.extend_from_slice(&0i32.to_le_bytes()); // id
            body.extend_from_slice(&1u32.to_le_bytes());
            body.push(0); // empty title
            body.extend_from_slice(&0i32.to_le_bytes()); // x
            body.extend_from_slice(&0i32.to_le_bytes()); // y
            body.extend_from_slice(&0u32.to_le_bytes()); // 0 pages
            body.extend_from_slice(&0u32.to_le_bytes()); // separator
            body.push(EVENT_FOOTER);
        }
        body.push(0x66); // map footer
        v.extend_from_slice(&body);
        v
    }

    #[test]
    fn parses_a_minimal_map() {
        let data = tiny_map(4, 3, 0);
        let map = MapFile::parse(&data).unwrap();
        assert_eq!(map.title, "m");
        assert_eq!(map.tileset_id, 1);
        assert_eq!(map.width, 4);
        assert_eq!(map.height, 3);
        assert_eq!(map.layers.len(), 3, "pre-0x67 maps always have 3 planes");
        assert_eq!(map.layers[0].len(), 12);
        assert_eq!(map.layers[2][0], 2, "each plane carries its own value");
        assert!(map.events.is_empty());
    }

    #[test]
    fn parses_events_without_pages() {
        let data = tiny_map(2, 2, 2);
        let map = MapFile::parse(&data).unwrap();
        assert_eq!(map.events.len(), 2);
        assert!(map.events[0].pages.is_empty());
    }

    #[test]
    fn treats_the_empty_map_marker_as_blank_layers() {
        let mut v = vec![0u8; 10];
        v.extend_from_slice(b"WOLFM");
        v.push(0);
        v.push(WolfContainer::VERSION_V3);
        while v.len() < TITLE_LEN_MIN {
            v.push(0);
        }
        v.extend_from_slice(&1u32.to_le_bytes());
        v.push(0); // empty title
        v.extend_from_slice(&0i32.to_le_bytes()); // tileset
        v.extend_from_slice(&2u32.to_le_bytes()); // width
        v.extend_from_slice(&2u32.to_le_bytes()); // height
        v.extend_from_slice(&0u32.to_le_bytes()); // no events
        v.extend_from_slice(&EMPTY_LAYERS.to_le_bytes());
        v.push(0x66);

        let map = MapFile::parse(&v).unwrap();
        assert_eq!(map.layers.len(), 3);
        assert!(map.layers.iter().all(|l| l.iter().all(|v| *v == 0)));
    }

    #[test]
    fn rejects_a_file_without_the_map_magic() {
        assert!(MapFile::parse(b"\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00XXXXX").is_err());
    }

    #[test]
    fn rejects_a_zero_sized_map() {
        assert!(MapFile::parse(&tiny_map(0, 0, 0)).is_err());
    }

    #[test]
    fn identifies_autotile_values() {
        assert_eq!(MapFile::autotile_index(601), None);
        assert_eq!(MapFile::autotile_index(101414), Some(0));
        assert_eq!(MapFile::autotile_index(204141), Some(1));
    }
}
