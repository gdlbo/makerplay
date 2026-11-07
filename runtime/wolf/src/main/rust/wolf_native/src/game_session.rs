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
    hero_visible: bool,
    frame_index: u32,
    /// Decoded starting-hero sheet, if the project names one.
    hero_sheet: Option<Image>,
    /// Pictures to draw above the tiles, in slot order.
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
            pictures: Vec::new(),
            picture_sheets: Vec::new(),
            events: Vec::new(),
            event_sheets: Vec::new(),
            font: None,
            message: String::new(),
            choices: Vec::new(),
            selected_choice: 0,
            camera_extra: (0, 0),
            base: Image::new(1, 1),
            autotiles: Vec::new(),
            tileset_index: usize::MAX,
            hero: (0, 0),
            hero_visible: true,
            frame_index: 0,
            frame: None,
            frame_version: 0,
            last_error: String::new(),
        };
        session.reload_tileset()?;
        // The deployment ships its own font; messages are drawn in-frame, so
        // without it the renderer could not show dialogue at all.
        let font_rel = "rounded-mplus-1p-regular.ttf";
        match Font::load(&data.join(font_rel), 28.0) {
            Ok(font) => session.font = Some(font),
            Err(e) => session.last_error = format!("font {font_rel}: {e}"),
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

    /// Switches maps, reloading the tileset only when it changes.
    pub fn load_map(&mut self, map_name: &str) -> Result<(), String> {
        let data = self.root.join("Data");
        let bytes = std::fs::read(data.join(format!("MapData/{map_name}.mps")))
            .map_err(|e| format!("{map_name}.mps: {e}"))?;
        let map = MapFile::parse(&bytes).map_err(|e| format!("{map_name}.mps: {e}"))?;
        let new_index = map.tileset_id.max(0) as usize;
        self.map = map;
        self.map_name = map_name.to_string();
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
    pub fn set_pictures(&mut self, specs: &[(i32, String, i32, i32, i32)]) {
        let data = self.root.join("Data");
        let mut pictures = Vec::with_capacity(specs.len());
        let mut sheets = Vec::with_capacity(specs.len());
        for (slot, name, x, y, option) in specs {
            if name.trim().is_empty() {
                continue;
            }
            let rel = strip_data_prefix(name).to_string();
            let Some(sheet) = self.cache.get(&data, &rel).cloned() else {
                // Record the first failure so a caller can see why a picture is
                // missing instead of getting a silent zero count.
                if self.last_error.is_empty() {
                    self.last_error = format!("picture sheet {rel} could not be decoded");
                }
                continue;
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
    }

    /// Sets the map event sprites to draw.
    ///
    /// `specs` are `(graphicFile, tileX, tileY, row, col, opacity)`. The file name is
    /// resolved the same way the Kotlin loader does: as-is when it already resolves
    /// under `Data/`, otherwise under `Data/CharaChip/`.
    pub fn set_events(&mut self, specs: &[(String, i32, i32, u32, u32, i32)]) {
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
                if self.last_error.is_empty() {
                    self.last_error = format!("event sheet {direct} could not be decoded");
                }
                continue;
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
        if let Some(text) = message {
            self.set_message(if text.is_empty() { None } else { Some(text) });
        }
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
        self.set_pictures(&pictures);
        for (slot, values) in picture_styles {
            if let Some(index) = self.pictures.iter().position(|p| p.slot == slot) {
                if values.len() >= 3 {
                    if let Some(sheet) = self.picture_sheets[index].as_mut() {
                        *sheet = picture_cell(sheet, values[0], values[1], values[2]);
                    }
                }
            }
        }
        self.set_events(&events);
        Ok(())
    }

    /// Sets an extra camera offset (engine shake / scroll), in pixels.
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
        // Engine shake/scroll shifts the viewport without moving the hero.
        camera.x += self.camera_extra.0;
        camera.y += self.camera_extra.1;
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
                opacity: 255,
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
        // Pictures draw above everything, in slot order.
        for (draw, sheet) in self.pictures.iter().zip(self.picture_sheets.iter()) {
            if let Some(sheet) = sheet {
                picture::draw_picture(&mut image, sheet, draw);
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
