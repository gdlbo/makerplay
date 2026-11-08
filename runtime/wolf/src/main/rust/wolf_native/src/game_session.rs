//! The Rust game session: owns the map, the compositor inputs, and the frame.
//!
//! This is what makes the Rust loop *render* rather than present a black frame. It
//! holds the decoded tileset for the current map and composes a frame per tick,
//! which the presenter uploads as a texture.
//!
//! Loading is deliberately lazy and cached: a base chip sheet in this deployment
//! reaches 320x8360 (3.5 MB PNG), so decoding every tileset up front would cost
//! hundreds of megabytes and seconds of startup.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use crate::picture::{self, Anchor, FreeTransform, PictureDraw, Tween};
use crate::png::{self, Image};
use crate::text::{self, Font, TextStyle};
use crate::scene::{self, Camera, ComposeOptions, SceneTileset};
use crate::wolfformat::gamedat::GameDat;
use crate::wolfformat::map::MapFile;
use crate::wolfformat::tileset::TileSetData;

/// One map event sprite.
#[derive(Debug, Clone, Copy)]
pub struct EventSprite {
    pub tile_x: i32,
    pub tile_y: i32,
    pub row: u32,
    pub col: u32,
    pub opacity: i32,
}

/// Draws the message box: a translucent panel with a light border.
fn draw_message_box(frame: &mut Image, x: i32, y: i32, w: i32, h: i32) {
    let fill = [16u8, 16, 32, 210];
    let border = [220u8, 220, 235, 235];
    for row in 0..h {
        for col in 0..w {
            let px = x + col;
            let py = y + row;
            if px < 0 || py < 0 || px >= frame.width as i32 || py >= frame.height as i32 {
                continue;
            }
            let edge = row < 2 || col < 2 || row >= h - 2 || col >= w - 2;
            let colour = if edge { border } else { fill };
            let i = ((py as usize) * (frame.width as usize) + (px as usize)) * 4;
            let dst = [
                frame.rgba[i],
                frame.rgba[i + 1],
                frame.rgba[i + 2],
                frame.rgba[i + 3],
            ];
            let out = picture::blend(dst, colour, picture::BLEND_NORMAL);
            frame.rgba[i..i + 4].copy_from_slice(&out);
        }
    }
}

/// Normalises a game-relative asset path to one relative to the `Data` folder.
///
/// The image cache is rooted at `Data`, so a path that already begins with `Data/`
/// must have that segment removed. Adding it instead produced
/// `root/Data/Data/...`, which silently failed for every hero sheet and picture.
/// Select one row-major cell from a divided picture sheet.
fn picture_cell(sheet: &Image, columns: i32, rows: i32, pattern: i32) -> Image {
    let columns = columns.max(1).min(sheet.width.max(1) as i32) as u32;
    let rows = rows.max(1).min(sheet.height.max(1) as i32) as u32;
    let width = sheet.width / columns;
    let height = sheet.height / rows;
    let pattern = (pattern.max(0) as u32).min(columns.saturating_mul(rows) - 1);
    let left = (pattern % columns) * width;
    let top = (pattern / columns) * height;
    let mut cell = Image::new(width, height);
    for y in 0..height {
        let src = (((top + y) * sheet.width + left) * 4) as usize;
        let dst = (y * width * 4) as usize;
        let bytes = (width * 4) as usize;
        cell.rgba[dst..dst + bytes].copy_from_slice(&sheet.rgba[src..src + bytes]);
    }
    cell
}

#[cfg(test)]
mod picture_cell_tests {
    use super::*;

    #[test]
    fn map_id_resolution_is_exact_and_deterministic() {
        let names = ["SampleMapA_1.mps", "Map0001.mps", "Map001.mps", "MAP00002.MPS"]
            .map(str::to_string);
        assert_eq!(map_name_for_id(&names, 1).as_deref(), Some("Map001"));
        assert_eq!(map_name_for_id(&names, 2).as_deref(), Some("MAP00002"));
        assert!(map_name_for_id(&names, 3).is_none());
        assert!(map_name_for_id(&names, -1).is_none());
        assert!(map_name_for_id(&["SampleMapA_1.mps".into()], 1).is_none());
    }

    #[test]
    fn generated_fill_preserves_size_and_argb_channels() {
        let image = generated_picture("@fill:3:2:-2146360781", None).unwrap();
        assert_eq!((image.width, image.height), (3, 2));
        assert_eq!(image.pixel(2, 1), [0x11, 0x22, 0x33, 0x80]);
        assert!(generated_picture("@fill:0:2:0", None).is_err());
        assert!(generated_picture("@fill:50000:2:0", None).is_err());
    }

    #[test]
    fn text_pictures_reject_invalid_encoding_and_missing_fonts() {
        for value in ["@text:f", "@text:zz", "@text:ff", "@text:61"] {
            assert!(generated_picture(value, None).is_err());
        }
    }

    #[test]
    fn selects_only_the_requested_row_major_cell() {
        let mut sheet = Image::new(4, 4);
        for y in 0..4u32 {
            for x in 0..4u32 {
                let i = ((y * 4 + x) * 4) as usize;
                sheet.rgba[i..i + 4].copy_from_slice(&[x as u8, y as u8, 0, 255]);
            }
        }
        let cell = picture_cell(&sheet, 2, 2, 3);
        assert_eq!((cell.width, cell.height), (2, 2));
        assert_eq!(cell.pixel(0, 0), [2, 2, 0, 255]);
        assert_eq!(cell.pixel(1, 1), [3, 3, 0, 255]);
        assert_eq!(picture_cell(&sheet, 2, 2, 99).rgba, cell.rgba);
        assert_eq!(picture_cell(&sheet, 0, 0, -1).rgba, sheet.rgba);
    }
}

fn map_name_for_id(names: &[String], id: i32) -> Option<String> {
    if id < 0 { return None; }
    for candidate in [format!("Map{id:03}.mps"), format!("Map{id:04}.mps"), format!("Map{id}.mps")] {
        if names.contains(&candidate) { return Some(candidate[..candidate.len() - 4].into()); }
    }
    let mut names = names.iter().collect::<Vec<_>>();
    names.sort();
    names.into_iter().find_map(|name| {
        let lower = name.to_ascii_lowercase();
        let number = lower.strip_prefix("map")?.strip_suffix(".mps")?;
        if number.is_empty() || !number.bytes().all(|b| b.is_ascii_digit()) { return None; }
        (number.parse::<i32>().ok()? == id).then(|| name[..name.len() - 4].into())
    })
}

fn generated_picture(spec: &str, font: Option<&mut Font>) -> Result<Image, String> {
    if let Some(value) = spec.strip_prefix("@fill:") {
        let values: Vec<i32> = value.split(':').map(str::parse).collect::<Result<_, _>>()
            .map_err(|_| "invalid fill picture".to_string())?;
        if values.len() != 3 || !(1..=4096).contains(&values[0]) || !(1..=4096).contains(&values[1]) {
            return Err("invalid fill picture dimensions".into());
        }
        let colour = values[2] as u32;
        let pixel = [(colour >> 16) as u8, (colour >> 8) as u8, colour as u8, (colour >> 24) as u8];
        let mut image = Image::new(values[0] as u32, values[1] as u32);
        for chunk in image.rgba.chunks_exact_mut(4) { chunk.copy_from_slice(&pixel); }
        return Ok(image);
    }
    let hex = spec.strip_prefix("@text:").ok_or("unknown generated picture")?;
    if hex.len() % 2 != 0 || hex.len() > 131072 || !hex.is_ascii() {
        return Err("invalid text picture encoding".into());
    }
    let bytes = (0..hex.len()).step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16))
        .collect::<Result<Vec<_>, _>>().map_err(|_| "invalid text picture hex")?;
    let text = String::from_utf8(bytes).map_err(|_| "invalid text picture UTF-8")?;
    let font = font.ok_or("no font available for text picture")?;
    let lines: Vec<_> = text.split('\n').collect();
    let width = lines.iter().map(|line| font.measure(line).ceil() as u32).max().unwrap_or(1).clamp(1, 4096);
    let height = (font.line_height() * lines.len() as f32).ceil().clamp(1.0, 4096.0) as u32;
    let mut image = Image::new(width, height);
    text::draw_text(&mut image, font, &text, 0, 0, &TextStyle::default());
    Ok(image)
}

fn strip_data_prefix(path: &str) -> &str {
    // Trim both separators so a Windows-style or rooted path normalises too.
    let trimmed = path.trim().trim_start_matches(['/', '\\']);
    trimmed.strip_prefix("Data/").unwrap_or(trimmed)
}

/// A decoded image cache, keyed by Data-relative path.
#[derive(Default)]
pub struct ImageCache {
    images: HashMap<String, Option<Image>>,
    /// Total bytes held, so the cache can be bounded.
    bytes: usize,
}

/// Ceiling for the image cache. A single 320x8360 sheet is ~10.7 MB decoded, so
/// this holds roughly a dozen sheets, which covers a tileset plus its autotiles.
const MAX_CACHE_BYTES: usize = 160 * 1024 * 1024;

impl ImageCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn get(&mut self, root: &Path, relative: &str) -> Option<&Image> {
        if !self.images.contains_key(relative) {
            let decoded = std::fs::read(root.join(relative))
                .ok()
                // Sniff the format: the deployment ships 16 JPEGs including the
                // title background, so a PNG-only loader loses it.
                .and_then(|bytes| png::decode_any(&bytes).ok());
            if let Some(image) = &decoded {
                self.bytes += image.rgba.len();
            }
            // Bounded: on overflow drop everything. Re-decoding a sheet is far
            // cheaper than an OOM kill, and a map change invalidates it anyway.
            if self.bytes > MAX_CACHE_BYTES {
                self.images.clear();
                self.bytes = 0;
            }
            self.images.insert(relative.to_string(), decoded);
        }
        self.images.get(relative).and_then(|o| o.as_ref())
    }

    pub fn clear(&mut self) {
        self.images.clear();
        self.bytes = 0;
    }

    pub fn cached_bytes(&self) -> usize {
        self.bytes
    }
}

/// A loaded game, ready to compose frames.
pub struct GameSession {
    root: PathBuf,
    project: GameDat,
    tilesets: TileSetData,
    map: MapFile,
    map_name: String,
    cache: ImageCache,
    /// Decoded tileset for the current map, resolved once per map change.
    base: Image,
    autotiles: Vec<Option<Image>>,
    tileset_index: usize,
    /// Hero tile, used for the follow camera.
    hero: (i32, i32),
    /// Whether the hero sprite is composited.
    hero_opacity: u8,
    hero_visible: bool,
    frame_index: u32,
    /// Decoded starting-hero sheet, if the project names one.
    hero_sheet: Option<Image>,
    /// Pictures to draw above the tiles, in slot order.
    colour_pulses: std::collections::HashMap<i32,crate::picture_effect::ColourPulse>,
    pictures: Vec<PictureDraw>,
    /// Decoded picture sheets, parallel to `pictures`.
    picture_sheets: Vec<Option<Image>>,
    /// Map event sprites to draw, y-sorted with the hero.
    events: Vec<EventSprite>,
    /// Decoded event sheets, parallel to `events`.
    event_sheets: Vec<Option<Image>>,
    /// Loaded message font, if the deployment ships one.
    font: Option<Font>,
    /// Message text to draw in-frame, as the original does.
    message: String,
    /// Choice options to draw below the message.
    choices: Vec<String>,
    /// Which choice row is highlighted.
    selected_choice: i32,
    /// Extra camera offset from the engine (shake/scroll), in pixels.
    map_transform: [i32;3],
    shake_extra: (i32, i32),
    camera_lock: crate::camera_lock::CameraLock,
    camera_extra: (i32, i32),
    /// Last composed frame and the version that produced it.
    frame: Option<Image>,
    frame_version: u64,
    last_error: String,
}

impl GameSession {
    /// Opens a deployment. `root` is the game root (the folder containing `Data`).
    pub fn open(root: &Path, map_name: &str) -> Result<Self, String> {
        let data = root.join("Data");
        let project = GameDat::parse(&std::fs::read(data.join("BasicData/Game.dat")).map_err(|e| format!("Game.dat: {e}"))?)
            .map_err(|e| format!("Game.dat: {e}"))?;
        let tilesets = TileSetData::parse(
            &std::fs::read(data.join("BasicData/TileSetData.dat")).map_err(|e| format!("TileSetData: {e}"))?,
        )
        .map_err(|e| format!("TileSetData: {e}"))?;
        let map_bytes = std::fs::read(data.join(format!("MapData/{map_name}.mps")))
            .map_err(|e| format!("{map_name}.mps: {e}"))?;
        let map = MapFile::parse(&map_bytes).map_err(|e| format!("{map_name}.mps: {e}"))?;

        let mut session = Self {
            root: root.to_path_buf(),
            project,
            tilesets,
            map,
            map_name: map_name.to_string(),
            cache: ImageCache::new(),
            hero_sheet: None,
            colour_pulses: std::collections::HashMap::new(),
            pictures: Vec::new(),
            picture_sheets: Vec::new(),
            events: Vec::new(),
            event_sheets: Vec::new(),
            font: None,
            message: String::new(),
            choices: Vec::new(),
            selected_choice: 0,
            map_transform: [0,0,100],
            shake_extra: (0, 0),
            camera_lock: crate::camera_lock::CameraLock::default(),
            camera_extra: (0, 0),
            base: Image::new(1, 1),
            autotiles: Vec::new(),
            tileset_index: usize::MAX,
            hero: (0, 0),
            hero_opacity: 255,
            hero_visible: true,
            frame_index: 0,
            frame: None,
            frame_version: 0,
            last_error: String::new(),
        };
        session.reload_tileset()?;
        // Prefer fonts supplied by the deployment. A missing optional font must
        // not turn dialogue into a blank box on Android.
        let mut font_paths: Vec<_> = std::fs::read_dir(&data)
            .into_iter().flatten().filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| path.extension().and_then(|s| s.to_str())
                .map(|s| s.eq_ignore_ascii_case("ttf") || s.eq_ignore_ascii_case("ttc") || s.eq_ignore_ascii_case("otf"))
                .unwrap_or(false))
            .collect();
        font_paths.sort();
        font_paths.extend([
            PathBuf::from("/system/fonts/NotoSansCJK-Regular.ttc"),
            PathBuf::from("/system/fonts/Roboto-Regular.ttf"),
        ]);
        for path in font_paths {
            if let Ok(font) = Font::load(&path, 28.0) {
                session.font = Some(font);
                break;
            }
        }
        if session.font.is_none() {
            session.last_error = "no usable deployment or system font for dialogue".to_string();
        }
        // The starting hero graphic is `Data/`-relative (e.g. CharaChipA/a_02.png),
        // unlike the tile chips which are `MapChip/`-relative.
        let hero_name = session.project.starting_hero_graphic.trim().to_string();
        if !hero_name.is_empty() {
            // The cache root is `Data`, so strip a leading `Data/` rather than
            // adding one.
            let rel = strip_data_prefix(&hero_name);
            session.hero_sheet = session.cache.get(&data, rel).cloned();
            if session.hero_sheet.is_none() {
                session.last_error = format!("hero sheet {rel} could not be decoded");
            }
        }
        Ok(session)
    }

    /// Resolves the current map's tileset images.
    fn reload_tileset(&mut self) -> Result<(), String> {
        let index = self.map.tileset_id.max(0) as usize;
        let tileset = self
            .tilesets
            .tilesets
            .get(index)
            .ok_or_else(|| format!("map references tileset {index} which does not exist"))?
            .clone();
        let data = self.root.join("Data");
        let base_rel = tileset.base_tileset_file.trim_start_matches('/').to_string();
        let base = self
            .cache
            .get(&data, &base_rel)
            .cloned()
            .ok_or_else(|| format!("cannot decode base tileset {base_rel}"))?;
        let mut autotiles = Vec::with_capacity(tileset.auto_tile_files.len());
        for name in &tileset.auto_tile_files {
            if name.trim().is_empty() {
                autotiles.push(None);
                continue;
            }
            let rel = name.trim_start_matches('/').to_string();
            autotiles.push(self.cache.get(&data, &rel).cloned());
        }
        self.base = base;
        self.autotiles = autotiles;
        self.tileset_index = index;
        Ok(())
    }

    /// Loads the destination before moving the hero. IDs refer to Map<number>.mps,
    /// not arbitrary filenames containing the same digits.
    pub fn teleport(&mut self, map_id: i32, x: i32, y: i32) -> Result<(), String> {
        let directory = self.root.join("Data/MapData");
        let names = std::fs::read_dir(&directory)
            .map_err(|e| format!("map directory: {e}"))?
            .filter_map(|entry| entry.ok())
            .filter_map(|entry| entry.file_name().into_string().ok())
            .collect::<Vec<_>>();
        let destination = map_name_for_id(&names, map_id)
            .ok_or_else(|| format!("teleport destination map {map_id} not found"))?;
        self.load_map(&destination)?;
        self.set_hero(x, y);
        Ok(())
    }

    /// Switches maps, reloading the tileset only when it changes.
    pub fn load_map(&mut self, map_name: &str) -> Result<(), String> {
        let data = self.root.join("Data");
        let map_dir = data.join("MapData");
        let file_name = format!("{map_name}.mps");
        let direct = map_dir.join(&file_name);
        let path = if direct.is_file() {
            direct
        } else {
            std::fs::read_dir(&map_dir).map_err(|e| format!("map directory: {e}"))?
                .filter_map(|entry| entry.ok())
                .find(|entry| entry.file_name().to_string_lossy().eq_ignore_ascii_case(&file_name))
                .map(|entry| entry.path()).ok_or_else(|| format!("{file_name}: not found"))?
        };
        let bytes = std::fs::read(path).map_err(|e| format!("{file_name}: {e}"))?;
        let map = MapFile::parse(&bytes).map_err(|e| format!("{map_name}.mps: {e}"))?;
        let new_index = map.tileset_id.max(0) as usize;
        self.map = map;
        self.map_name = map_name.to_string();
        self.events.clear();
        self.event_sheets.clear();
        self.frame = None;
        self.frame_version = self.frame_version.wrapping_add(1);
        if new_index != self.tileset_index {
            self.reload_tileset()?;
        }
        Ok(())
    }

    /// Replaces the picture list, decoding any sheets not already cached.
    ///
    /// `specs` are `(slot, file name, x, y, option word)` where the option word
    /// carries the anchor, blend and zoom the engine packs into it.
    pub fn start_colour_pulse(&mut self, slot:i32, raw:[i32;3], duration:i32, saw:bool) {
        self.colour_pulses.insert(slot,crate::picture_effect::ColourPulse::new(raw,duration,saw));
        self.invalidate();
    }
    pub fn clear_picture_queue(&mut self,from:i32,to:i32) {
        for picture in &mut self.pictures {
            if (from..=to).contains(&picture.slot) {picture.tween=None;}
        }
        self.invalidate();
    }
    pub fn clear_picture_effects(&mut self, slot:i32) { self.colour_pulses.remove(&slot); }
    pub fn tick_picture_effects(&mut self) {
        if !self.colour_pulses.is_empty() {
            for pulse in self.colour_pulses.values_mut() {pulse.tick();}
            self.invalidate();
        }
    }

    pub fn set_pictures(&mut self, specs: &[(i32, String, i32, i32, i32)]) -> Result<(), String> {
        let data = self.root.join("Data");
        let mut pictures = Vec::with_capacity(specs.len());
        let mut sheets = Vec::with_capacity(specs.len());
        for (slot, name, x, y, option) in specs {
            if name.trim().is_empty() {
                continue;
            }
            if name.starts_with("@text:") || name.starts_with("@fill:") {
                if !self.cache.images.contains_key(name) {
                    let generated = generated_picture(name, self.font.as_mut());
                    match generated {
                        Ok(image) => {
                            if self.cache.bytes + image.rgba.len() > MAX_CACHE_BYTES {
                                self.cache.clear();
                            }
                            self.cache.bytes += image.rgba.len();
                            self.cache.images.insert(name.clone(), Some(image));
                        }
                        Err(error) => {
                            self.last_error = error.clone();
                            return Err(error);
                        }
                    }
                }
            }
            let rel = strip_data_prefix(name).to_string();
            let Some(sheet) = self.cache.get(&data, &rel).cloned() else {
                let error=format!("picture sheet {rel} could not be decoded");
                self.last_error=error.clone();
                return Err(error);
            };
            let mut draw = PictureDraw::new(*slot, *x, *y);
            // Bit layout from GamePro.exe sub_A1CD40.
            draw.anchor = Anchor::from_code((option >> 12) & 0xF);
            draw.blend = (option >> 8) & 0x0F;
            if draw.blend == picture::BLEND_SAME {
                draw.blend = picture::BLEND_NORMAL;
            }
            pictures.push(draw);
            sheets.push(Some(sheet));
        }
        self.pictures = pictures;
        self.picture_sheets = sheets;
        self.invalidate();
        Ok(())
    }

    /// Sets the map event sprites to draw.
    ///
    /// `specs` are `(graphicFile, tileX, tileY, row, col, opacity)`. The file name is
    /// resolved the same way the Kotlin loader does: as-is when it already resolves
    /// under `Data/`, otherwise under `Data/CharaChip/`.
    pub fn set_events(&mut self, specs: &[(String, i32, i32, u32, u32, i32)]) -> Result<(),String> {
        let data = self.root.join("Data");
        let mut events = Vec::with_capacity(specs.len());
        let mut sheets = Vec::with_capacity(specs.len());
        for (name, tx, ty, row, col, opacity) in specs {
            if name.trim().is_empty() {
                continue;
            }
            let rel = strip_data_prefix(name);
            let direct = rel.to_string();
            let fallback = format!("CharaChip/{rel}");
            let sheet = match self.cache.get(&data, &direct).cloned() {
                Some(image) => Some(image),
                None => self.cache.get(&data, &fallback).cloned(),
            };
            let Some(sheet) = sheet else {
                let error=format!("event sheet {direct} could not be decoded");
                self.last_error=error.clone();
                return Err(error);
            };
            events.push(EventSprite {
                tile_x: *tx,
                tile_y: *ty,
                row: *row,
                col: *col,
                opacity: (*opacity).clamp(0, 255),
            });
            sheets.push(Some(sheet));
        }
        self.events = events;
        self.event_sheets = sheets;
        self.invalidate();
        Ok(())
    }

    pub fn event_count(&self) -> usize {
        self.events.len()
    }

    /// Sets a free-transform quad on a picture slot (自由変形).
    pub fn set_free_transform(&mut self, slot: i32, quad: FreeTransform) {
        if let Some(draw) = self.pictures.iter_mut().find(|p| p.slot == slot) {
            draw.free_transform = Some(quad);
            self.invalidate();
        }
    }

    /// Starts a tween on a picture slot (処理時間 tween and 発動ディレイ).
    pub fn set_tween(&mut self, slot: i32, tween: Tween) {
        if let Some(draw) = self.pictures.iter_mut().find(|p| p.slot == slot) {
            draw.tween = Some(tween);
            self.invalidate();
        }
    }

    /// True while any picture has an active tween.
    pub fn has_active_tween(&self) -> bool {
        self.pictures
            .iter()
            .any(|p| p.tween.map(|t| t.is_active()).unwrap_or(false))
    }

    /// Sets the message to draw in-frame, or `None` to clear it.
    pub fn set_message(&mut self, message: Option<String>) {
        let value = message.unwrap_or_default();
        if self.message != value {
            self.message = value;
            // A new message means no choice window is open any more.
            self.choices.clear();
            self.selected_choice = 0;
            self.invalidate();
        }
    }

    /// Sets the choice window: options plus the highlighted row.
    pub fn set_choices(&mut self, choices: Vec<String>, selected: i32) {
        if self.choices != choices || self.selected_choice != selected {
            self.choices = choices;
            self.selected_choice = selected;
            self.invalidate();
        }
    }

    pub fn choices(&self) -> &[String] {
        &self.choices
    }

    /// Pushes a complete scene from an external engine in one call.
    ///
    /// The Kotlin engine still owns map loading, triggers, the interpreter and the
    /// database; what it hands over here is purely what to draw. `spec` lines are:
    ///
    /// ```text
    /// map=<MapNNN>          switch map
    /// hero=<x>,<y>[/<visible>]
    /// cam=<dx>,<dy>         extra camera offset
    /// msg=<text>            message text (empty clears)
    /// choice=<sel>|<opt>...  choice window
    /// pic=<slot>|<file>|<x>|<y>|<option>[|<columns>|<rows>|<pattern>]
    /// ev=<file>|<x>|<y>|<row>|<col>|<opacity>
    /// ```
    ///
    /// Unknown lines are ignored rather than rejecting the whole update, so a newer
    /// caller cannot break an older library.
    pub fn apply_scene(&mut self, spec: &str) -> Result<(), String> {
        let mut picture_styles: Vec<(i32, Vec<i32>)> = Vec::new();
        let mut pictures: Vec<(i32, String, i32, i32, i32)> = Vec::new();
        let mut events: Vec<(String, i32, i32, u32, u32, i32)> = Vec::new();
        let mut message: Option<String> = None;
        let mut choices_spec: Option<String> = None;
        let mut new_map: Option<String> = None;
        let mut hero: Option<(i32, i32, bool)> = None;
        let mut cam: Option<(i32, i32)> = None;

        for line in spec.lines() {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            let Some((kind, value)) = line.split_once('=') else {
                continue;
            };
            match kind {
                "map" => new_map = Some(value.trim().to_string()),
                "hero" => {
                    let (coords, visible) = match value.split_once('/') {
                        Some((c, v)) => (c, v.trim() != "0"),
                        None => (value, true),
                    };
                    let mut parts = coords.split(',');
                    if let (Some(x), Some(y)) = (parts.next(), parts.next()) {
                        if let (Ok(x), Ok(y)) = (x.trim().parse(), y.trim().parse()) {
                            hero = Some((x, y, visible));
                        }
                    }
                }
                "cam" => {
                    let mut parts = value.split(',');
                    if let (Some(dx), Some(dy)) = (parts.next(), parts.next()) {
                        if let (Ok(dx), Ok(dy)) = (dx.trim().parse(), dy.trim().parse()) {
                            cam = Some((dx, dy));
                        }
                    }
                }
                "msg" => message = Some(value.to_string()),
                "choice" => choices_spec = Some(value.to_string()),
                "pic" => {
                    let parts: Vec<&str> = value.split('|').collect();
                    if parts.len() >= 5 {
                        picture_styles.push((parts[0].trim().parse().unwrap_or(0),
                            parts[5..].iter().map(|v| v.trim().parse().unwrap_or(0)).collect()));
                        pictures.push((
                            parts[0].trim().parse().unwrap_or(0),
                            parts[1].to_string(),
                            parts[2].trim().parse().unwrap_or(0),
                            parts[3].trim().parse().unwrap_or(0),
                            parts[4].trim().parse().unwrap_or(0),
                        ));
                    }
                }
                "ev" => {
                    let parts: Vec<&str> = value.split('|').collect();
                    if parts.len() >= 6 {
                        events.push((
                            parts[0].to_string(),
                            parts[1].trim().parse().unwrap_or(0),
                            parts[2].trim().parse().unwrap_or(0),
                            parts[3].trim().parse().unwrap_or(0),
                            parts[4].trim().parse().unwrap_or(0),
                            parts[5].trim().parse().unwrap_or(255),
                        ));
                    }
                }
                _ => {}
            }
        }

        if let Some(name) = new_map {
            if name != self.map_name {
                self.load_map(&name)?;
            }
        }
        if let Some((x, y, visible)) = hero {
            self.set_hero(x, y);
            self.set_hero_visible(visible);
        }
        if let Some((dx, dy)) = cam {
            self.set_camera_extra(dx, dy);
        }
        // Scene updates are complete snapshots. An omitted message means the
        // dialogue closed, just as an omitted choice list means choices closed.
        self.set_message(message.filter(|text| !text.is_empty()));
        // Choices are always pushed so a closed window clears; the picture and event
        // lists are always pushed for the same reason.
        if let Some(spec) = choices_spec {
            let mut parts = spec.split('|');
            let selected: i32 = parts.next().and_then(|s| s.trim().parse().ok()).unwrap_or(0);
            let options: Vec<String> = parts.map(|s| s.to_string()).filter(|s| !s.is_empty()).collect();
            self.set_choices(options, selected);
        } else if !self.choices.is_empty() {
            self.set_choices(Vec::new(), 0);
        }
        self.set_pictures(&pictures)?;
        for (slot, values) in picture_styles {
            if let Some(index) = self.pictures.iter().position(|p| p.slot == slot) {
                if values.len() >= 3 {
                    if let Some(sheet) = self.picture_sheets[index].as_mut() {
                        *sheet = picture_cell(sheet, values[0], values[1], values[2]);
                    }
                }
            }
        }
        self.set_events(&events)?;
        Ok(())
    }

    /// Sets an extra camera offset (engine shake / scroll), in pixels.
    pub fn set_map_transform(&mut self, transform: [i32;3]) {
        if self.map_transform != transform { self.map_transform=transform; self.invalidate(); }
    }

    pub fn set_shake(&mut self, shake: &crate::map_shake::MapShake) {
        let offset = shake.pixels(self.project.tile_size);
        if self.shake_extra != offset { self.shake_extra = offset; self.invalidate(); }
    }
    pub fn fps(&self) -> i32 { self.project.fps }

    pub fn set_camera_lock(&mut self,operation:i32)->Result<(),String> {
        self.camera_lock.command(operation)?;
        self.invalidate();
        Ok(())
    }

    pub fn set_camera_extra(&mut self, dx: i32, dy: i32) {
        if self.camera_extra != (dx, dy) {
            self.camera_extra = (dx, dy);
            self.invalidate();
        }
    }

    /// Screen bounds of each visible picture, as `(slot, left, top, right, bottom)`.
    ///
    /// This is what the engine's "picture under the cursor" query needs, which is
    /// how WOLF's mouse-driven menus decide what was clicked.
    pub fn picture_bounds(&self) -> Vec<(i32, i32, i32, i32, i32)> {
        let mut out = Vec::with_capacity(self.pictures.len());
        for (draw, sheet) in self.pictures.iter().zip(self.picture_sheets.iter()) {
            let Some(sheet) = sheet else { continue };
            let (zx, zy) = draw.effective_zoom();
            let w = (sheet.width as f32 * zx).round().max(1.0) as i32;
            let h = (sheet.height as f32 * zy).round().max(1.0) as i32;
            let (left, top) = draw.anchor.top_left(draw.x, draw.y, w, h);
            out.push((draw.slot, left, top, left + w, top + h));
        }
        out
    }

    pub fn has_font(&self) -> bool {
        self.font.is_some()
    }

    pub fn picture_count(&self) -> usize {
        self.pictures.len()
    }

    /// Shows or hides the hero sprite. The camera still follows the hero tile.
    pub fn set_hero_visible(&mut self, visible: bool) {
        if self.hero_visible != visible {
            self.hero_visible = visible;
            self.invalidate();
        }
    }

    pub fn set_hero_opacity(&mut self, opacity: u8) {
        if self.hero_opacity != opacity {
            self.hero_opacity = opacity;
            self.invalidate();
        }
    }

    pub fn set_hero(&mut self, tile_x: i32, tile_y: i32) {
        if self.hero != (tile_x, tile_y) {
            self.hero = (tile_x, tile_y);
            self.frame_version = self.frame_version.wrapping_add(1);
        }
    }

    /// Advances the autotile animation clock.
    pub fn tick(&mut self) {
        self.frame_index = self.frame_index.wrapping_add(1);
        // Advance per-picture tweens, including 発動ディレイ. A picture that is
        // mid-tween must invalidate the cached frame so the fade is visible.
        let mut changed = false;
        for draw in self.pictures.iter_mut() {
            let Some(tween) = draw.tween.as_mut() else {
                continue;
            };
            if !tween.is_active() {
                continue;
            }
            let (opacity, zoom) = tween.advance(draw.opacity, draw.zoom_x);
            draw.opacity = opacity.clamp(0, 255);
            draw.zoom_x = zoom;
            draw.zoom_y = zoom;
            if !tween.is_active() {
                draw.tween = None;
            }
            changed = true;
        }
        if changed {
            self.invalidate();
        }
    }

    pub fn deployment_root(&self) -> &Path { &self.root }

    pub fn map_events(&self) -> &[crate::wolfformat::map::MapEvent] {
        &self.map.events
    }

    pub fn map_name(&self) -> &str {
        &self.map_name
    }

    pub fn last_error(&self) -> &str {
        &self.last_error
    }

    pub fn cached_bytes(&self) -> usize {
        self.cache.cached_bytes()
    }

    /// Composes the frame for a viewport, reusing the previous one when nothing
    /// changed. Returns the frame and its version.
    pub fn compose(&mut self, width: i32, height: i32) -> (Image, u64) {
        let reuse = self
            .frame
            .as_ref()
            .map(|f| f.width as i32 == width && f.height as i32 == height)
            .unwrap_or(false);
        if reuse {
            return (
                self.frame.as_ref().expect("checked above").clone(),
                self.frame_version,
            );
        }
        let tile = self.project.tile_size.max(1) as u32;
        let mut camera =
            Camera::follow(&self.map, self.hero.0, self.hero.1, tile as i32, width, height);
        let (locked_x,locked_y)=self.camera_lock.apply(camera.x,camera.y);
        camera.x=locked_x;camera.y=locked_y;
        // Engine shake/scroll shifts the viewport without moving the hero.
        camera.x += self.camera_extra.0 + self.shake_extra.0;
        camera.y += self.camera_extra.1 + self.shake_extra.1;
        let tiles = SceneTileset {
            tileset: &self.tilesets.tilesets[self.tileset_index],
            base: self.base.clone(),
            autotiles: self.autotiles.clone(),
        };
        let options = ComposeOptions {
            map: &self.map,
            tiles: &tiles,
            tile_size: tile,
            camera,
            frame_index: 0,
        };
        let (mut image, overhead) = scene::compose_ground(&options);
        // Entities draw between the ground and the overhead pass, so a tree canopy
        // covers a sprite standing behind it. Y-sorted: a sprite lower on the map
        // draws in front, which is what makes the original's depth read correctly.
        struct Entity<'a> {
            y_sort: i32,
            sheet: &'a Image,
            tile_x: i32,
            tile_y: i32,
            row: u32,
            col: u32,
            opacity: i32,
        }
        let mut entities: Vec<Entity> = Vec::new();
        if let Some(sheet) = self.hero_sheet.as_ref().filter(|_| self.hero_visible) {
            entities.push(Entity {
                y_sort: self.hero.1 * tile as i32 + tile as i32,
                sheet,
                tile_x: self.hero.0,
                tile_y: self.hero.1,
                row: 0,
                col: 1,
                opacity: i32::from(self.hero_opacity),
            });
        }
        for (event, sheet) in self.events.iter().zip(self.event_sheets.iter()) {
            if let Some(sheet) = sheet {
                entities.push(Entity {
                    y_sort: event.tile_y * tile as i32 + tile as i32,
                    sheet,
                    tile_x: event.tile_x,
                    tile_y: event.tile_y,
                    row: event.row,
                    col: event.col,
                    opacity: event.opacity,
                });
            }
        }
        entities.sort_by_key(|e| e.y_sort);
        for entity in entities {
            let px = entity.tile_x * tile as i32 - camera.x;
            let py = entity.tile_y * tile as i32 - camera.y;
            scene::draw_chara_chip_with(
                &mut image,
                entity.sheet,
                px,
                py + tile as i32,
                tile,
                entity.row,
                entity.col,
                entity.opacity,
            );
        }
        scene::compose_overhead(&mut image, &tiles, &overhead, tile, 0);
        let [cx,cy,zoom] = self.map_transform;
        if zoom != 100 {
            let original = image.clone();
            for y in 0..image.height { for x in 0..image.width {
                let sx=i64::from(cx)+(i64::from(x)-i64::from(cx))*100/i64::from(zoom.max(1));
                let sy=i64::from(cy)+(i64::from(y)-i64::from(cy))*100/i64::from(zoom.max(1));
                let pixel=if sx>=0 && sy>=0 && sx<i64::from(original.width) && sy<i64::from(original.height) {
                    original.pixel(sx as u32,sy as u32)
                } else { [0,0,0,255] };
                let index=((y*image.width+x)*4) as usize;
                image.rgba[index..index+4].copy_from_slice(&pixel);
            }}
        }
        // Pictures draw above everything, in slot order.
        for (draw, sheet) in self.pictures.iter().zip(self.picture_sheets.iter()) {
            if let Some(sheet) = sheet {
                if let Some(pulse)=self.colour_pulses.get(&draw.slot) {
                    let mut effected=draw.clone();
                    effected.effect_colour_offset=pulse.offsets();
                    picture::draw_picture(&mut image,sheet,&effected);
                } else { picture::draw_picture(&mut image, sheet, draw); }
            }
        }
        // The message box draws last, in-frame, as the original does.
        let frame_w = image.width as i32;
        let frame_h = image.height as i32;
        if !self.message.is_empty() || !self.choices.is_empty() {
            // A simple box at the bottom of the viewport, with the same margin
            // the original uses for its 1280x960 layout.
            let margin = 24i32;
            let box_h = (frame_h / 4).max(120);
            let box_y = frame_h - box_h - margin;
            draw_message_box(&mut image, margin, box_y, frame_w - margin * 2, box_h);
            if let Some(font) = self.font.as_mut() {
                let mut text_y = box_y + 14;
                if !self.message.is_empty() {
                    let style = TextStyle {
                        colour: [255, 255, 255, 255],
                        wrap_width: Some((frame_w - margin * 2 - 32) as f32),
                        ..Default::default()
                    };
                    text::draw_text(&mut image, font, &self.message, margin + 16, text_y, &style);
                    // The message occupies the upper part of the box; choices list
                    // below it, which is how the original lays out a choice window.
                    text_y += (font.line_height() * 2.0) as i32;
                }
                if !self.choices.is_empty() {
                    let selected = self.selected_choice.clamp(0, self.choices.len() as i32 - 1);
                    for (index, choice) in self.choices.iter().enumerate() {
                        let is_selected = index as i32 == selected;
                        let style = TextStyle {
                            colour: if is_selected {
                                [255, 240, 160, 255]
                            } else {
                                [220, 220, 230, 255]
                            },
                            wrap_width: Some((frame_w - margin * 2 - 64) as f32),
                            ..Default::default()
                        };
                        // A caret marks the active row, as the original does.
                        if is_selected {
                            text::draw_text(&mut image, font, "▶", margin + 20, text_y, &style);
                        }
                        text::draw_text(&mut image, font, choice, margin + 56, text_y, &style);
                        text_y += font.line_height() as i32 + 6;
                    }
                }
            }
        }
        self.frame = Some(image);
        self.frame_version = self.frame_version.wrapping_add(1);
        (
            self.frame.as_ref().expect("just set").clone(),
            self.frame_version,
        )
    }

    /// Renders the whole map at its true size. Used by diagnostics and tests.
    pub fn compose_whole_map(&mut self) -> (Image, u64) {
        let tile = self.project.tile_size.max(1) as i32;
        let width = self.map.width * tile;
        let height = self.map.height * tile;
        self.frame = None;
        self.compose(width, height)
    }

    pub fn frame_version(&self) -> u64 {
        self.frame_version
    }

    /// Forces the next `compose` to rebuild even at the same size.
    pub fn invalidate(&mut self) {
        self.frame = None;
        self.frame_version = self.frame_version.wrapping_add(1);
    }
}
