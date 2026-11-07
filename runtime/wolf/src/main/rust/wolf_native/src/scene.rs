//! Scene compositor: rasterizes a WOLF map into an RGBA frame.
//!
//! This is the Rust port of `WolfSceneLoader.composeFrame`. The sampling rules are
//! not re-derived here — they were established from `GamePro.exe`'s own decoder
//! (`sub_9D2440`) during earlier work and are pinned by tests, so this module
//! implements them rather than guessing:
//!
//! * **Autotile layout.** The four digits of `raw % 10000` are quadrant modes
//!   TL/TR/BL/BR. Each quadrant samples the corresponding quarter of the whole
//!   frame selected by its mode, i.e. row `mode * tileSize` — the engine's
//!   `case 3` produces `a4 % 2 + 2 * (a5 % 2)` as the quadrant index, and
//!   `case 4` reports `20 * frameCount` sheet cells.
//! * **Passability offset.** Every tileset stores `baseChipTiles + 32` entries, so
//!   the base-chip table is `passability.len() - 32`. Verified across all 53
//!   tilesets in this deployment with zero mismatches.
//! * **Star split.** Layer 1/2 chips marked `star` are drawn in the overhead pass,
//!   above entities, rather than in the ground pass.
//!
//! The compositor is deliberately pure: it takes already-decoded `Image`s and
//! produces an `Image`. No I/O, no GL, no globals. That is what makes the 305-map
//! and per-cell checks possible.

use crate::png::Image;
use crate::wolfformat::tileset::{TileSetData, Tileset};
use crate::wolfformat::map::MapFile;

/// Autotile slots appended after the base chip table.
pub const AUTOTILE_SLOTS: usize = 32;

/// Pixel columns in a base chip sheet.
const BASE_COLUMNS: u32 = 8;

/// Value at or above which a layer word is an autotile reference.
const AUTOTILE_THRESHOLD: i32 = 100_000;

/// A decoded tileset ready to draw.
pub struct SceneTileset<'a> {
    pub tileset: &'a Tileset,
    /// The base chip sheet, already decoded.
    pub base: Image,
    /// Decoded autotile sheets, indexed by `raw / 100000 - 1`.
    pub autotiles: Vec<Option<Image>>,
}

impl<'a> SceneTileset<'a> {
    /// Index of the first autotile passability entry.
    pub fn autotile_base(&self) -> usize {
        self.tileset
            .tile_passability
            .len()
            .saturating_sub(AUTOTILE_SLOTS)
    }

    /// Passability entry for a raw layer word, base chip or autotile.
    pub fn passability(&self, raw: i32) -> Option<&crate::wolfformat::tileset::Passability> {
        if raw <= 0 {
            return None;
        }
        if raw < AUTOTILE_THRESHOLD {
            self.tileset.tile_passability.get(raw as usize)
        } else {
            let index = raw / AUTOTILE_THRESHOLD - 1;
            self.tileset
                .tile_passability
                .get(self.autotile_base() + index as usize)
        }
    }

    /// True when a layer-1/2 chip belongs to the overhead pass.
    pub fn is_star(&self, raw: i32) -> bool {
        self.passability(raw).map(|p| p.star).unwrap_or(false)
    }
}

/// Where the camera sits and how big the frame is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Camera {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

impl Camera {
    /// Camera that centres the viewport on a tile, clamped to the map.
    pub fn follow(
        map: &MapFile,
        tile_x: i32,
        tile_y: i32,
        tile_size: i32,
        width: i32,
        height: i32,
    ) -> Self {
        let map_w = map.width * tile_size;
        let map_h = map.height * tile_size;
        let hero_x = tile_x * tile_size + tile_size / 2;
        let hero_y = tile_y * tile_size + tile_size / 2;
        Self {
            x: (hero_x - width / 2).clamp(0, (map_w - width).max(0)),
            y: (hero_y - height / 2).clamp(0, (map_h - height).max(0)),
            width,
            height,
        }
    }
}

/// Fills the frame with the composer's backdrop colour.
fn backdrop(width: u32, height: u32) -> Image {
    let mut image = Image::new(width, height);
    for chunk in image.rgba.chunks_exact_mut(4) {
        chunk[0] = 0x10;
        chunk[1] = 0x10;
        chunk[2] = 0x18;
        chunk[3] = 0xFF;
    }
    image
}

/// Draws one autotile cell: four quadrants, each sampling its own whole frame.
fn draw_autotile(
    dst: &mut Image,
    sheet: &Image,
    packed_modes: i32,
    dst_x: i32,
    dst_y: i32,
    tile_size: u32,
    frame_index: u32,
) {
    let modes = [
        packed_modes / 1_000,
        (packed_modes / 100) % 10,
        (packed_modes / 10) % 10,
        packed_modes % 10,
    ];
    let half = tile_size / 2;
    if half == 0 {
        return;
    }
    for (index, mode) in modes.iter().enumerate() {
        if *mode < 0 || *mode > 9 {
            continue;
        }
        let frame_row = (*mode as u32) * tile_size;
        if frame_row + tile_size > sheet.height {
            continue;
        }
        let is_right = index == 1 || index == 3;
        let is_bottom = index >= 2;
        let src_x = frame_index * tile_size + if is_right { half } else { 0 };
        let src_y = frame_row + if is_bottom { half } else { 0 };
        if src_x + half > sheet.width {
            continue;
        }
        let dx = dst_x + if is_right { half as i32 } else { 0 };
        let dy = dst_y + if is_bottom { half as i32 } else { 0 };
        sheet.blit(dst, src_x, src_y, half, half, dx, dy, true);
    }
}

/// Draws one layer cell, base chip or autotile.
fn draw_cell(
    dst: &mut Image,
    tiles: &SceneTileset,
    raw: i32,
    dst_x: i32,
    dst_y: i32,
    tile_size: u32,
    frame_index: u32,
) {
    if raw >= AUTOTILE_THRESHOLD {
        let index = (raw / AUTOTILE_THRESHOLD - 1).max(0) as usize;
        if let Some(Some(sheet)) = tiles.autotiles.get(index) {
            draw_autotile(dst, sheet, raw % 10_000, dst_x, dst_y, tile_size, frame_index);
        }
        return;
    }
    if raw <= 0 {
        return;
    }
    let chip = raw as u32;
    let src_x = (chip % BASE_COLUMNS) * tile_size;
    let src_y = (chip / BASE_COLUMNS) * tile_size;
    if src_x + tile_size > tiles.base.width || src_y + tile_size > tiles.base.height {
        return;
    }
    tiles
        .base
        .blit(dst, src_x, src_y, tile_size, tile_size, dst_x, dst_y, true);
}

/// Options for a composition.
pub struct ComposeOptions<'a> {
    pub map: &'a MapFile,
    pub tiles: &'a SceneTileset<'a>,
    pub tile_size: u32,
    pub camera: Camera,
    /// Animation frame selector; 0 freezes autotile animation.
    pub frame_index: u32,
}

/// Composes the tile layers into a frame.
///
/// Two passes, matching the original: ground (layer 0 plus non-star layer 1/2)
/// first, then overhead (star chips) so trees and roofs draw above entities. This
/// function does the ground pass and returns the pending overhead cells, which the
/// caller draws after entities.
pub fn compose_ground(options: &ComposeOptions) -> (Image, Vec<OverheadCell>) {
    let width = options.camera.width.max(1) as u32;
    let height = options.camera.height.max(1) as u32;
    let mut frame = backdrop(width, height);
    let mut overhead = Vec::new();

    let tile = options.tile_size.max(1);
    let map = options.map;
    let layers = map.layers.len().min(3);
    for layer_index in 0..layers {
        let layer = &map.layers[layer_index];
        for y in 0..map.height {
            for x in 0..map.width {
                let raw = layer[(y * map.width + x) as usize];
                if raw == 0 {
                    continue;
                }
                let dst_x = x * tile as i32 - options.camera.x;
                let dst_y = y * tile as i32 - options.camera.y;
                if dst_x + tile as i32 <= 0
                    || dst_y + tile as i32 <= 0
                    || dst_x >= width as i32
                    || dst_y >= height as i32
                {
                    continue;
                }
                // Layer 1 splits on `star`; layer 2 is the top layer and is drawn
                // entirely overhead. Matching WolfSceneLoader's pass 3 exactly:
                //   `if (!isTopLayer && pass?.star != true) continue`
                let is_top_layer = layer_index == 2;
                if layer_index > 0 && (is_top_layer || options.tiles.is_star(raw)) {
                    overhead.push(OverheadCell {
                        raw,
                        dst_x,
                        dst_y,
                    });
                    continue;
                }
                draw_cell(&mut frame, options.tiles, raw, dst_x, dst_y, tile, options.frame_index);
            }
        }
    }
    (frame, overhead)
}

/// Draws a chara chip (3x4 or 5x4 sheet) anchored at its feet.
///
/// Mirrors `WolfSceneLoader.drawCharaChip`: the sheet is 3 or 5 columns by 4 rows,
/// the cell is `width/cols` by `height/rows`, and the sprite is drawn so its bottom
/// edge sits at `anchor_y` scaled to one tile wide.
pub fn draw_chara_chip(
    frame: &mut Image,
    sheet: &Image,
    anchor_x: i32,
    anchor_y: i32,
    tile_size: u32,
    row: u32,
    col: u32,
) {
    draw_chara_chip_with(frame, sheet, anchor_x, anchor_y, tile_size, row, col, 255)
}

/// Same, with an explicit opacity (0..255), as page graphics carry one.
pub fn draw_chara_chip_with(
    frame: &mut Image,
    sheet: &Image,
    anchor_x: i32,
    anchor_y: i32,
    tile_size: u32,
    row: u32,
    col: u32,
    opacity: i32,
) {
    let cols = if sheet.width >= tile_size * 5 && sheet.width % 5 == 0 {
        5
    } else {
        3
    };
    let rows = 4u32;
    let cell_w = (sheet.width / cols).max(1);
    let cell_h = (sheet.height / rows).max(1);
    let src_x = col.min(cols - 1) * cell_w;
    let src_y = row.min(rows - 1) * cell_h;
    // One tile wide, height scaled by the cell aspect ratio.
    let dst_h = (cell_h * tile_size) / cell_w.max(1);
    let dst_x = anchor_x;
    let dst_y = anchor_y - dst_h as i32;
    if opacity >= 255 {
        sheet.blit(frame, src_x, src_y, cell_w, cell_h, dst_x, dst_y, true);
        return;
    }
    if opacity <= 0 {
        return;
    }
    // Scale alpha before compositing, so a translucent page graphic fades rather
    // than disappearing.
    let mut scaled = sheet.clone();
    for pixel in scaled.rgba.chunks_exact_mut(4) {
        pixel[3] = ((pixel[3] as i32 * opacity) / 255) as u8;
    }
    scaled.blit(frame, src_x, src_y, cell_w, cell_h, dst_x, dst_y, true);
}

/// A layer-1/2 chip deferred to the overhead pass.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OverheadCell {
    pub raw: i32,
    pub dst_x: i32,
    pub dst_y: i32,
}

/// Draws the deferred overhead chips, after entities.
pub fn compose_overhead(frame: &mut Image, tiles: &SceneTileset, cells: &[OverheadCell], tile_size: u32, frame_index: u32) {
    for cell in cells {
        draw_cell(frame, tiles, cell.raw, cell.dst_x, cell.dst_y, tile_size, frame_index);
    }
}

/// Composes ground plus overhead, with no entities. Convenience for tests and for
/// whole-map dumps.
pub fn compose(options: &ComposeOptions) -> Image {
    let (mut frame, overhead) = compose_ground(options);
    compose_overhead(
        &mut frame,
        options.tiles,
        &overhead,
        options.tile_size.max(1),
        options.frame_index,
    );
    frame
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wolfformat::map::{MapEvent, MapFile};
    use crate::wolfformat::tileset::Passability;

    fn solid_image(w: u32, h: u32, rgba: [u8; 4]) -> Image {
        let mut image = Image::new(w, h);
        for chunk in image.rgba.chunks_exact_mut(4) {
            chunk.copy_from_slice(&rgba);
        }
        image
    }

    fn passability(star: bool) -> Passability {
        Passability {
            counter_enabled: false,
            square: false,
            quarter_tile: false,
            star,
            top_left_passable: false,
            top_right_passable: false,
            bottom_left_passable: false,
            bottom_right_passable: false,
            upwards_not_passable: false,
            rightwards_not_passable: false,
            leftwards_not_passable: false,
            downwards_not_passable: false,
            down_arrow: false,
            triangle: false,
            raw: 0,
        }
    }

    /// Tileset with `base_chips` base entries then 32 autotile entries.
    fn tileset(base_chips: usize, star_for: Option<usize>) -> TileSetData {
        let mut passability_list = Vec::new();
        for _ in 0..base_chips {
            passability_list.push(passability(false));
        }
        for i in 0..AUTOTILE_SLOTS {
            passability_list.push(passability(star_for == Some(i)));
        }
        TileSetData {
            v3: true,
            revision: 0xD3,
            tilesets: vec![Tileset {
                title: "test".into(),
                base_tileset_file: "base.png".into(),
                auto_tile_files: vec![String::new(); AUTOTILE_SLOTS],
                tag_numbers: Vec::new(),
                tile_passability: passability_list,
            }],
        }
    }

    fn empty_map(width: i32, height: i32) -> MapFile {
        MapFile {
            v3: true,
            revision: 0x67,
            title: String::new(),
            tileset_id: 0,
            width,
            height,
            layers: vec![vec![0; (width * height) as usize]; 3],
            events: Vec::<MapEvent>::new(),
        }
    }

    fn scene<'a>(tileset: &'a TileSetData, base: Image, autos: Vec<Option<Image>>) -> SceneTileset<'a> {
        SceneTileset {
            tileset: &tileset.tilesets[0],
            base,
            autotiles: autos,
        }
    }

    #[test]
    fn the_frame_is_filled_with_the_backdrop() {
        let ts = tileset(8, None);
        let base = solid_image(320, 320, [0, 0, 0, 0]);
        let tiles = scene(&ts, base, Vec::new());
        let map = empty_map(2, 2);
        let frame = compose(&ComposeOptions {
            map: &map,
            tiles: &tiles,
            tile_size: 40,
            camera: Camera { x: 0, y: 0, width: 80, height: 80 },
            frame_index: 0,
        });
        assert_eq!((frame.width, frame.height), (80, 80));
        assert_eq!(frame.pixel(0, 0), [0x10, 0x10, 0x18, 0xFF]);
    }

    #[test]
    fn a_base_chip_is_sampled_at_eight_columns() {
        let ts = tileset(64, None);
        // 8 columns x 8 rows of 4px chips; chip 9 = col 1, row 1.
        let mut base = Image::new(32, 32);
        let (sx, sy) = (4u32, 4u32);
        for y in 0..4u32 {
            for x in 0..4u32 {
                let i = (((sy + y) * 32) + (sx + x)) as usize * 4;
                base.rgba[i] = 250;
                base.rgba[i + 3] = 255;
            }
        }
        let tiles = scene(&ts, base, Vec::new());
        let mut map = empty_map(1, 1);
        map.layers[0][0] = 9;
        let frame = compose(&ComposeOptions {
            map: &map,
            tiles: &tiles,
            tile_size: 4,
            camera: Camera { x: 0, y: 0, width: 4, height: 4 },
            frame_index: 0,
        });
        assert_eq!(frame.pixel(0, 0), [250, 0, 0, 255], "chip 9 must come from col 1 row 1");
    }

    #[test]
    fn the_autotile_base_offset_is_passability_minus_32() {
        let ts = tileset(296, Some(0));
        let tiles = scene(&ts, solid_image(8, 8, [0, 0, 0, 0]), Vec::new());
        assert_eq!(tiles.autotile_base(), 296);
        // Autotile 1 (raw 100000) maps to entry 296.
        assert!(tiles.passability(100_000).is_some());
        assert_eq!(tiles.passability(100_000).unwrap().star, true);
        // A base chip maps to its own index, not offset.
        assert_eq!(tiles.passability(5).unwrap().star, false);
    }

    #[test]
    fn star_chips_are_deferred_to_the_overhead_pass() {
        // One base sheet where chip 1 (col 1, row 0) is red and chip 2 is green, so
        // the two passes are distinguishable by colour alone.
        let mut base = Image::new(320, 320);
        let paint = |base: &mut Image, chip: u32, rgba: [u8; 4]| {
            let (cx, cy) = ((chip % 8) * 40, (chip / 8) * 40);
            for y in cy..cy + 40 {
                for x in cx..cx + 40 {
                    let i = ((y * 320) + x) as usize * 4;
                    base.rgba[i..i + 4].copy_from_slice(&rgba);
                }
            }
        };
        paint(&mut base, 1, [255, 0, 0, 255]);
        paint(&mut base, 2, [0, 255, 0, 255]);

        // Layer 0 uses chip 1 (ground); layer 1 uses chip 2, marked star.
        let mut ts = tileset(8, None);
        ts.tilesets[0].tile_passability[2] = passability(true);
        let tiles = scene(&ts, base, Vec::new());

        let mut map = empty_map(1, 1);
        map.layers[0][0] = 1;
        map.layers[1][0] = 2;

        let (ground, overhead) = compose_ground(&ComposeOptions {
            map: &map,
            tiles: &tiles,
            tile_size: 40,
            camera: Camera { x: 0, y: 0, width: 40, height: 40 },
            frame_index: 0,
        });
        assert_eq!(overhead.len(), 1, "the star chip must be deferred");
        assert_eq!(overhead[0].raw, 2);
        // Ground pass drew only layer 0.
        assert_eq!(ground.pixel(0, 0), [255, 0, 0, 255]);

        let mut frame = ground;
        compose_overhead(&mut frame, &tiles, &overhead, 40, 0);
        assert_eq!(frame.pixel(0, 0), [0, 255, 0, 255], "overhead draws on top");
    }

    #[test]
    fn autotile_quadrants_sample_distinct_regions() {
        // A 4x8 sheet: one frame column, two frame rows of 4px.
        // Frame 0 = pure red, frame 1 = pure blue. Modes "1000" means TL samples
        // frame 1 and the rest frame 0.
        let mut sheet = Image::new(4, 8);
        for y in 0..4u32 {
            for x in 0..4u32 {
                let i = ((y * 4) + x) as usize * 4;
                sheet.rgba[i] = 255;
                sheet.rgba[i + 3] = 255;
            }
        }
        for y in 4..8u32 {
            for x in 0..4u32 {
                let i = ((y * 4) + x) as usize * 4;
                sheet.rgba[i + 2] = 255;
                sheet.rgba[i + 3] = 255;
            }
        }
        let ts = tileset(8, None);
        let tiles = scene(&ts, solid_image(8, 8, [0, 0, 0, 0]), vec![Some(sheet)]);
        let mut map = empty_map(1, 1);
        // raw 100000 => autotile index 0, modes 0000 (all frame 0 = red)
        map.layers[0][0] = 100_000;
        let frame = compose(&ComposeOptions {
            map: &map,
            tiles: &tiles,
            tile_size: 4,
            camera: Camera { x: 0, y: 0, width: 4, height: 4 },
            frame_index: 0,
        });
        assert_eq!(frame.pixel(0, 0), [255, 0, 0, 255]);
        assert_eq!(frame.pixel(3, 3), [255, 0, 0, 255]);

        // modes 1000 => TL samples frame 1 (blue), the rest frame 0 (red).
        map.layers[0][0] = 101_000;
        let frame = compose(&ComposeOptions {
            map: &map,
            tiles: &tiles,
            tile_size: 4,
            camera: Camera { x: 0, y: 0, width: 4, height: 4 },
            frame_index: 0,
        });
        assert_eq!(frame.pixel(0, 0), [0, 0, 255, 255], "TL must come from frame 1");
        assert_eq!(frame.pixel(3, 0), [255, 0, 0, 255], "TR must come from frame 0");
    }

    #[test]
    fn a_chara_chip_writes_pixels() {
        // Direct check that the sprite blit is not a no-op: a 3x4 sheet with one
        // opaque cell must paint into the frame.
        let mut sheet = Image::new(192, 384);
        // Make row 0, col 1 opaque red (cell 64x96 at x=64).
        for y in 0..96u32 {
            for x in 64..128u32 {
                let i = ((y * 192) + x) as usize * 4;
                sheet.rgba[i] = 255;
                sheet.rgba[i + 3] = 255;
            }
        }
        let mut frame = Image::new(400, 400);
        draw_chara_chip(&mut frame, &sheet, 100, 200, 40, 0, 1);
        let painted = frame
            .rgba
            .chunks_exact(4)
            .filter(|p| p[0] > 0)
            .count();
        eprintln!("DBG chara painted={painted}");
        assert!(painted > 0, "the chara chip blit wrote nothing");
    }

    #[test]
    fn the_camera_clamps_to_the_map() {
        let map = empty_map(10, 10);
        let cam = Camera::follow(&map, 0, 0, 40, 400, 400);
        assert_eq!((cam.x, cam.y), (0, 0));
        let cam = Camera::follow(&map, 9, 9, 40, 400, 400);
        assert_eq!((cam.x, cam.y), (0, 0), "a map exactly the viewport size cannot scroll");
        let map2 = empty_map(40, 40);
        let cam = Camera::follow(&map2, 39, 39, 40, 400, 400);
        assert_eq!(cam.x, 40 * 40 - 400);
        assert_eq!(cam.y, 40 * 40 - 400);
    }

    #[test]
    fn cells_outside_the_camera_are_skipped_without_panicking() {
        let ts = tileset(64, None);
        let base = solid_image(320, 320, [255, 255, 255, 255]);
        let tiles = scene(&ts, base, Vec::new());
        let mut map = empty_map(4, 4);
        for cell in map.layers[0].iter_mut() {
            *cell = 1;
        }
        // Camera far to the right: most cells are off-screen.
        let frame = compose(&ComposeOptions {
            map: &map,
            tiles: &tiles,
            tile_size: 40,
            camera: Camera { x: 60, y: 60, width: 40, height: 40 },
            frame_index: 0,
        });
        assert_eq!((frame.width, frame.height), (40, 40));
        assert_eq!(frame.pixel(0, 0), [255, 255, 255, 255]);
    }
}
