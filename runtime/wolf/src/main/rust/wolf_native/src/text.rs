//! Text rendering for messages, choices and picture text.
//!
//! The original draws messages **in-frame** (there is no separate window layer),
//! so a renderer without text cannot show dialogue at all — which is why this is a
//! prerequisite for switching the app to the Rust loop.
//!
//! Two pieces:
//!
//! * [`Font`] wraps `ttf-parser` for glyph outlines and metrics. The deployment
//!   ships `rounded-mplus-1p-regular.ttf`.
//! * [`TextStyle`] / [`draw_text`] rasterize and composite, with the line breaking,
//!   wrapping and per-line advance the message box needs.
//!
//! Glyph coverage is cached as bitmaps: rasterizing an outline per frame for every
//! character would be far too slow, and a message box redraws many times per second.

use std::collections::HashMap;

use ttf_parser::Face;

use crate::png::Image;

/// A loaded font.
pub struct Font {
    /// Raw font bytes, kept alive because `Face` borrows them.
    data: Vec<u8>,
    /// Pixel size the outlines are rasterized at.
    size: f32,
    /// Advance and ascent/descent, in pixels.
    ascent: f32,
    descent: f32,
    line_gap: f32,
    /// Cached glyph coverage bitmaps, keyed by character.
    glyphs: HashMap<char, Glyph>,
}

/// A rasterized glyph: an 8-bit coverage mask plus its placement.
#[derive(Debug, Clone)]
pub struct Glyph {
    pub width: u32,
    pub height: u32,
    /// Distance from the pen position to the glyph's left edge.
    pub bearing_x: i32,
    /// Distance from the baseline up to the glyph's top edge.
    pub bearing_y: i32,
    /// Horizontal advance in pixels.
    pub advance: f32,
    /// `width * height` coverage values, 0..255.
    pub mask: Vec<u8>,
}

impl Glyph {
    fn empty(advance: f32) -> Self {
        Self {
            width: 0,
            height: 0,
            bearing_x: 0,
            bearing_y: 0,
            advance,
            mask: Vec::new(),
        }
    }
}

impl Font {
    /// Loads a font file at a pixel size.
    pub fn load(path: &std::path::Path, size: f32) -> Result<Self, String> {
        let data = std::fs::read(path).map_err(|e| format!("font {}: {e}", path.display()))?;
        Self::from_bytes(data, size)
    }

    pub fn from_bytes(data: Vec<u8>, size: f32) -> Result<Self, String> {
        let face = Face::parse(&data, 0).map_err(|e| format!("font parse: {e}"))?;
        let units = face.units_per_em() as f32;
        let scale = size / units;
        Ok(Self {
            ascent: face.ascender() as f32 * scale,
            descent: -face.descender() as f32 * scale,
            line_gap: face.line_gap() as f32 * scale,
            size,
            data,
            glyphs: HashMap::new(),
        })
    }

    pub fn size(&self) -> f32 {
        self.size
    }

    pub fn ascent(&self) -> f32 {
        self.ascent
    }

    pub fn descent(&self) -> f32 {
        self.descent
    }

    /// Line height including the font's own gap.
    pub fn line_height(&self) -> f32 {
        (self.ascent + self.descent + self.line_gap).max(1.0)
    }

    /// Glyph for a character, rasterizing and caching on first use.
    pub fn glyph(&mut self, c: char) -> &Glyph {
        if !self.glyphs.contains_key(&c) {
            let glyph = self.rasterize(c);
            self.glyphs.insert(c, glyph);
        }
        self.glyphs.get(&c).expect("just inserted")
    }

    /// Horizontal advance for a character, without rasterizing it.
    pub fn advance(&mut self, c: char) -> f32 {
        self.glyph(c).advance
    }

    /// Width of a string in pixels.
    pub fn measure(&mut self, text: &str) -> f32 {
        text.chars().map(|c| self.advance(c)).sum()
    }

    fn rasterize(&self, c: char) -> Glyph {
        let Ok(face) = Face::parse(&self.data, 0) else {
            return Glyph::empty(self.size * 0.5);
        };
        let scale = self.size / face.units_per_em() as f32;
        let Some(glyph_id) = face.glyph_index(c) else {
            // Unknown character: keep the advance so layout does not collapse.
            return Glyph::empty(self.size * 0.5);
        };
        let advance = face.glyph_hor_advance(glyph_id).unwrap_or(0) as f32 * scale;

        // Collect the outline's bounding box first, so the mask can be sized.
        let mut bbox: Option<(f32, f32, f32, f32)> = None;
        let mut outline_ok = true;
        if let Some(bbox_raw) = face.glyph_bounding_box(glyph_id) {
            let x0 = bbox_raw.x_min as f32 * scale;
            let y0 = bbox_raw.y_min as f32 * scale;
            let x1 = bbox_raw.x_max as f32 * scale;
            let y1 = bbox_raw.y_max as f32 * scale;
            bbox = Some((x0, y0, x1, y1));
        } else {
            outline_ok = false;
        }
        let Some((x0, y0, x1, y1)) = bbox else {
            return Glyph::empty(advance);
        };
        if !outline_ok {
            return Glyph::empty(advance);
        }
        let width = (x1 - x0).ceil().max(0.0) as u32;
        let height = (y1 - y0).ceil().max(0.0) as u32;
        if width == 0 || height == 0 || width > 512 || height > 512 {
            return Glyph::empty(advance);
        }

        let mut mask = vec![0u8; (width * height) as usize];
        // 4x4 supersampling: cheap, and UI text at 20-30px reads well.
        const SS: u32 = 4;
        let inv = 1.0 / (SS as f32);
        for row in 0..height {
            for col in 0..width {
                let mut hits = 0u32;
                for sy in 0..SS {
                    for sx in 0..SS {
                        let px = x0 + (col as f32 + (sx as f32 + 0.5) * inv);
                        let py = y1 - (row as f32 + (sy as f32 + 0.5) * inv);
                        if point_in_glyph(&face, glyph_id, scale, px, py) {
                            hits += 1;
                        }
                    }
                }
                let total = SS * SS;
                mask[(row * width + col) as usize] = ((hits * 255) / total) as u8;
            }
        }

        Glyph {
            width,
            height,
            bearing_x: x0.floor() as i32,
            bearing_y: y1.ceil() as i32,
            advance,
            mask,
        }
    }
}

/// True when `(px, py)` lies inside the glyph outline.
///
/// Flattens the outline to line segments and counts crossings along the ray to +X,
/// which is the standard even-odd fill rule.
fn point_in_glyph(face: &Face, glyph_id: ttf_parser::GlyphId, scale: f32, px: f32, py: f32) -> bool {
    let mut crossings = 0u32;
    let mut last: Option<(f32, f32)> = None;
    let mut start: Option<(f32, f32)> = None;
    let mut ok = true;
    let result = face.outline_glyph(
        glyph_id,
        &mut Builder {
            scale,
            px,
            py,
            last: &mut last,
            start: &mut start,
            crossings: &mut crossings,
            ok: &mut ok,
        },
    );
    if result.is_none() || !ok {
        return false;
    }
    // Close the contour back to the start if it was left open.
    if let (Some(a), Some(b)) = (last, start) {
        count_crossing(a, b, px, py, &mut crossings);
    }
    crossings % 2 == 1
}

fn count_crossing(a: (f32, f32), b: (f32, f32), px: f32, py: f32, crossings: &mut u32) {
    let (x0, y0) = a;
    let (x1, y1) = b;
    // Half-open interval on y avoids double-counting shared vertices.
    if (y0 <= py) != (y1 <= py) {
        let t = (py - y0) / (y1 - y0);
        let x = x0 + t * (x1 - x0);
        if x > px {
            *crossings += 1;
        }
    }
}

struct Builder<'a> {
    scale: f32,
    px: f32,
    py: f32,
    last: &'a mut Option<(f32, f32)>,
    start: &'a mut Option<(f32, f32)>,
    crossings: &'a mut u32,
    ok: &'a mut bool,
}

impl ttf_parser::OutlineBuilder for Builder<'_> {
    fn move_to(&mut self, x: f32, y: f32) {
        let p = (x * self.scale, y * self.scale);
        *self.last = Some(p);
        *self.start = Some(p);
    }

    fn line_to(&mut self, x: f32, y: f32) {
        let p = (x * self.scale, y * self.scale);
        if let Some(a) = *self.last {
            count_crossing(a, p, self.px, self.py, self.crossings);
        }
        *self.last = Some(p);
    }

    fn quad_to(&mut self, x1: f32, y1: f32, x: f32, y: f32) {
        let Some(a) = *self.last else {
            self.line_to(x, y);
            return;
        };
        // Flatten into 8 segments; plenty for a glyph at UI sizes.
        let c = (x1 * self.scale, y1 * self.scale);
        let b = (x * self.scale, y * self.scale);
        let mut prev = a;
        for i in 1..=8 {
            let t = i as f32 / 8.0;
            let mt = 1.0 - t;
            let p = (
                mt * mt * a.0 + 2.0 * mt * t * c.0 + t * t * b.0,
                mt * mt * a.1 + 2.0 * mt * t * c.1 + t * t * b.1,
            );
            count_crossing(prev, p, self.px, self.py, self.crossings);
            prev = p;
        }
        *self.last = Some(b);
    }

    fn curve_to(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, x: f32, y: f32) {
        let Some(a) = *self.last else {
            self.line_to(x, y);
            return;
        };
        let c1 = (x1 * self.scale, y1 * self.scale);
        let c2 = (x2 * self.scale, y2 * self.scale);
        let b = (x * self.scale, y * self.scale);
        let mut prev = a;
        for i in 1..=12 {
            let t = i as f32 / 12.0;
            let mt = 1.0 - t;
            let p = (
                mt * mt * mt * a.0 + 3.0 * mt * mt * t * c1.0 + 3.0 * mt * t * t * c2.0 + t * t * t * b.0,
                mt * mt * mt * a.1 + 3.0 * mt * mt * t * c1.1 + 3.0 * mt * t * t * c2.1 + t * t * t * b.1,
            );
            count_crossing(prev, p, self.px, self.py, self.crossings);
            prev = p;
        }
        *self.last = Some(b);
    }

    fn close(&mut self) {
        if let (Some(a), Some(b)) = (*self.last, *self.start) {
            count_crossing(a, b, self.px, self.py, self.crossings);
        }
        *self.last = *self.start;
    }
}

/// How to draw text.
#[derive(Debug, Clone)]
pub struct TextStyle {
    pub colour: [u8; 4],
    pub line_height: f32,
    /// Maximum line width before wrapping; `None` disables wrapping.
    pub wrap_width: Option<f32>,
    /// Extra spacing between lines, in pixels.
    pub line_spacing: f32,
}

impl Default for TextStyle {
    fn default() -> Self {
        Self {
            colour: [255, 255, 255, 255],
            line_height: 0.0,
            wrap_width: None,
            line_spacing: 0.0,
        }
    }
}

/// Breaks text into lines that fit `width`, honouring explicit newlines.
///
/// WOLF message boxes are fixed-width and wrap on any character (Japanese has no
/// spaces), so this wraps per character and prefers breaking at spaces when one is
/// available in the last third of the line.
pub fn wrap(font: &mut Font, text: &str, width: Option<f32>) -> Vec<String> {
    let Some(width) = width else {
        return text.split('\n').map(|s| s.to_string()).collect();
    };
    if width <= 0.0 {
        return text.split('\n').map(|s| s.to_string()).collect();
    }
    let mut out = Vec::new();
    for paragraph in text.split('\n') {
        let mut line = String::new();
        let mut line_w = 0.0f32;
        let mut last_space: Option<usize> = None;
        for c in paragraph.chars() {
            let adv = font.advance(c);
            if line_w + adv > width && !line.is_empty() {
                // Prefer a space break if there is one reasonably far along.
                if let Some(pos) = last_space {
                    if pos * 2 >= line.len() {
                        let (head, tail) = line.split_at(pos);
                        out.push(head.to_string());
                        line = tail.trim_start().to_string();
                        line_w = font.measure(&line);
                        last_space = line.find(' ');
                        line.push(c);
                        line_w += adv;
                        continue;
                    }
                }
                out.push(std::mem::take(&mut line));
                line_w = 0.0;
                last_space = None;
            }
            if c == ' ' {
                last_space = Some(line.len());
            }
            line.push(c);
            line_w += adv;
        }
        out.push(line);
    }
    out
}

/// Draws text with its top-left at `(x, y)`.
pub fn draw_text(
    frame: &mut Image,
    font: &mut Font,
    text: &str,
    x: i32,
    y: i32,
    style: &TextStyle,
) {
    let lines = wrap(font, text, style.wrap_width);
    let line_h = if style.line_height > 0.0 {
        style.line_height
    } else {
        font.line_height()
    } + style.line_spacing;
    // The glyph cache is keyed by char, so rasterize each distinct char once.
    let mut pen_y = y as f32 + font.ascent();
    for line in lines {
        let mut pen_x = x as f32;
        for c in line.chars() {
            let glyph = font.glyph(c).clone();
            if glyph.width > 0 {
                let gx = pen_x.round() as i32 + glyph.bearing_x;
                let gy = (pen_y.round() as i32) - glyph.bearing_y;
                blit_mask(frame, &glyph, gx, gy, style.colour);
            }
            pen_x += glyph.advance;
        }
        pen_y += line_h;
    }
}

/// Draws text centred horizontally within `[x, x+width)`.
pub fn draw_text_centered(
    frame: &mut Image,
    font: &mut Font,
    text: &str,
    x: i32,
    width: i32,
    y: i32,
    style: &TextStyle,
) {
    let w = font.measure(text);
    let left = x + ((width as f32 - w) / 2.0).max(0.0) as i32;
    draw_text(frame, font, text, left, y, style);
}

fn blit_mask(frame: &mut Image, glyph: &Glyph, x: i32, y: i32, colour: [u8; 4]) {
    for row in 0..glyph.height {
        let ty = y + row as i32;
        if ty < 0 || ty >= frame.height as i32 {
            continue;
        }
        for col in 0..glyph.width {
            let tx = x + col as i32;
            if tx < 0 || tx >= frame.width as i32 {
                continue;
            }
            let coverage = glyph.mask[(row * glyph.width + col) as usize];
            if coverage == 0 {
                continue;
            }
            let alpha = (coverage as u32 * colour[3] as u32 / 255) as u8;
            let i = ((ty as usize) * (frame.width as usize) + (tx as usize)) * 4;
            let dst = [
                frame.rgba[i],
                frame.rgba[i + 1],
                frame.rgba[i + 2],
                frame.rgba[i + 3],
            ];
            let src = [colour[0], colour[1], colour[2], alpha];
            let out = crate::picture::blend(dst, src, crate::picture::BLEND_NORMAL);
            frame.rgba[i..i + 4].copy_from_slice(&out);
        }
    }
}
