//! Picture compositing: anchors, zoom, rotation, blends, colour correction,
//! 9-slice and free transform.
//!
//! Port of the Kotlin picture layer (`PictureAnchor`, `PictureTransform`,
//! `PictureBlend`, `PictureGeometry`) and its drawing path in `WolfSceneLoader`.
//! The behaviours come from the manual's documented spec, and the bit layout comes
//! from `GamePro.exe` (`sub_A1CD40`: `(w >> 12) & 0xF` for the anchor, `& 0xF0000`
//! for the zoom mode, `& 0x4000000` for free transform).
//!
//! This is what the title menu needs: that screen is drawn entirely with pictures,
//! so a renderer without picture support cannot show it at all.

use crate::png::Image;

/// The seven anchors the manual documents.
///
/// Values 5 and 6 are the two names the manual lists that the Rust prior-art crate
/// omits (centre-top and centre-bottom). The nibble histogram over this deployment
/// shows exactly 0..6 in use (`0:574 1:297 2:13 3:51 4:7 5:15 6:3`), and 5/6 are
/// assigned by the manual's own listing order after removing the five confirmed
/// entries — recorded as inferred in `docs/wolf-task3-evidence.md`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Anchor {
    TopLeft,
    Center,
    BottomLeft,
    TopRight,
    BottomRight,
    CenterTop,
    CenterBottom,
}

impl Anchor {
    pub fn from_code(code: i32) -> Self {
        match code {
            1 => Anchor::Center,
            2 => Anchor::BottomLeft,
            3 => Anchor::TopRight,
            4 => Anchor::BottomRight,
            5 => Anchor::CenterTop,
            6 => Anchor::CenterBottom,
            _ => Anchor::TopLeft,
        }
    }

    /// Fraction of the picture that sits left of the anchor point, 0..1.
    pub fn x_factor(self) -> f64 {
        match self {
            Anchor::TopLeft | Anchor::BottomLeft => 0.0,
            Anchor::Center | Anchor::CenterTop | Anchor::CenterBottom => 0.5,
            Anchor::TopRight | Anchor::BottomRight => 1.0,
        }
    }

    /// Fraction of the picture that sits above the anchor point, 0..1.
    pub fn y_factor(self) -> f64 {
        match self {
            Anchor::TopLeft | Anchor::TopRight | Anchor::CenterTop => 0.0,
            Anchor::Center | Anchor::BottomLeft | Anchor::BottomRight | Anchor::CenterBottom => 0.5,
            _ => 0.0,
        }
    }

    /// Top-left corner so the anchor lands on `(x, y)` for a `w`x`h` picture.
    pub fn top_left(self, x: i32, y: i32, w: i32, h: i32) -> (i32, i32) {
        (
            x - (w as f64 * self.x_factor()) as i32,
            y - (h as f64 * self.y_factor()) as i32,
        )
    }
}

/// Documented blend modes.
pub const BLEND_NORMAL: i32 = 0;
pub const BLEND_ADD: i32 = 1;
pub const BLEND_SUBTRACT: i32 = 2;
pub const BLEND_MULTIPLY: i32 = 3;
/// "Keep the previous mode".
pub const BLEND_SAME: i32 = 0x0F;

/// Range of the per-channel colour correction parameters, as the deployment passes
/// them (percent, 100 neutral).
pub const COLOUR_CORRECTION_MIN: i32 = -200;
pub const COLOUR_CORRECTION_MAX: i32 = 200;
/// Neutral value in the engine's percent scale.
pub const COLOUR_NEUTRAL: i32 = 100;
/// Largest delta the engine's own endpoints produce: `(200-100)*2.55` is
/// 254.999... in IEEE754 and the engine truncates with `(int)`, giving 254.
pub const COLOUR_DELTA_MAX: i32 = 254;

/// One engine colour channel: `(value - 100) * 2.55`, truncated toward zero.
///
/// Decompiled from `sub_990570`:
/// ```c
/// v14[144] = (int)((double)(a12 / 0x10000       - 100) * 2.55);
/// v14[145] = (int)((double)(v135 % 0x10000 / 256 - 100) * 2.55);
/// v14[146] = (int)((double)(v135 % 256          - 100) * 2.55);
/// ```
/// The result is a signed delta added to the source channel, not a replacement.
pub fn engine_colour_channel(value: i32) -> i32 {
    // Truncation toward zero, matching C's `(int)` cast. `as i32` on an f64 does
    // exactly that, so the endpoints land on 254/-254 rather than 255/-255.
    let delta = (((value - COLOUR_NEUTRAL) as f64) * 2.55) as i32;
    delta.clamp(-COLOUR_DELTA_MAX, COLOUR_DELTA_MAX)
}

fn clamp8(v: i32) -> u8 {
    v.clamp(0, 255) as u8
}

/// Applies the picture's colour correction.
///
/// Each argument is a **percent value with 100 neutral**, as the deployment passes
/// it in `params[15..17]` (where 100 dominates). The engine converts with
/// [`engine_colour_channel`] and *adds* the result to the channel — confirmed by
/// `v14[147] = v14[144] + v157 * (v14[147] - v14[144]) / v59`, i.e. the tint is a
/// delta on the base channel, not a replacement.
pub fn apply_colour_correction(rgba: [u8; 4], red: i32, green: i32, blue: i32) -> [u8; 4] {
    [
        clamp8(rgba[0] as i32 + engine_colour_channel(red)),
        clamp8(rgba[1] as i32 + engine_colour_channel(green)),
        clamp8(rgba[2] as i32 + engine_colour_channel(blue)),
        rgba[3],
    ]
}

/// Blends `src` over `dst` in straight (non-premultiplied) colour.
pub fn blend(dst: [u8; 4], src: [u8; 4], mode: i32) -> [u8; 4] {
    let sa = src[3] as u32;
    if sa == 0 {
        return dst;
    }
    let da = dst[3] as u32;
    let (sr, sg, sb) = (src[0] as i32, src[1] as i32, src[2] as i32);
    let (dr, dg, db) = (dst[0] as i32, dst[1] as i32, dst[2] as i32);
    let (br, bg, bb) = match mode {
        BLEND_ADD => (clamp8(dr + sr), clamp8(dg + sg), clamp8(db + sb)),
        BLEND_SUBTRACT => (clamp8(dr - sr), clamp8(dg - sg), clamp8(db - sb)),
        BLEND_MULTIPLY => (
            ((dr * sr) / 255) as u8,
            ((dg * sg) / 255) as u8,
            ((db * sb) / 255) as u8,
        ),
        _ => (src[0], src[1], src[2]),
    };
    if sa == 255 {
        return [br, bg, bb, 255];
    }
    let inv = 255 - sa;
    let r = (br as u32 * sa + dr as u32 * inv) / 255;
    let g = (bg as u32 * sa + dg as u32 * inv) / 255;
    let b = (bb as u32 * sa + db as u32 * inv) / 255;
    let a = (sa + da * inv / 255).min(255);
    [clamp8(r as i32), clamp8(g as i32), clamp8(b as i32), a as u8]
}

/// One 9-slice destination rectangle paired with its source rectangle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Slice {
    pub src_left: i32,
    pub src_top: i32,
    pub src_right: i32,
    pub src_bottom: i32,
    pub dst_left: i32,
    pub dst_top: i32,
    pub dst_right: i32,
    pub dst_bottom: i32,
}

/// お手軽ウィンドウ: corners keep the source corner size, edges and centre stretch.
pub fn nine_slice(
    src_w: i32,
    src_h: i32,
    dst_x: i32,
    dst_y: i32,
    dst_w: i32,
    dst_h: i32,
    corner_w: i32,
    corner_h: i32,
) -> Vec<Slice> {
    if src_w <= 0 || src_h <= 0 {
        return Vec::new();
    }
    // Corners may not exceed half the source or the slices would overlap.
    let cw = corner_w.clamp(0, src_w / 2);
    let ch = corner_h.clamp(0, src_h / 2);
    let dcw = cw.min(dst_w / 2);
    let dch = ch.min(dst_h / 2);

    let src_xs = [0, cw, src_w - cw, src_w];
    let src_ys = [0, ch, src_h - ch, src_h];
    let dst_xs = [dst_x, dst_x + dcw, dst_x + dst_w - dcw, dst_x + dst_w];
    let dst_ys = [dst_y, dst_y + dch, dst_y + dst_h - dch, dst_y + dst_h];

    let mut out = Vec::with_capacity(9);
    for row in 0..3 {
        for col in 0..3 {
            out.push(Slice {
                src_left: src_xs[col],
                src_top: src_ys[row],
                src_right: src_xs[col + 1],
                src_bottom: src_ys[row + 1],
                dst_left: dst_xs[col],
                dst_top: dst_ys[row],
                dst_right: dst_xs[col + 1],
                dst_bottom: dst_ys[row + 1],
            });
        }
    }
    out
}

/// 自由変形: four independently positioned destination corners.
///
/// The manual exposes all four corners, which is a general quadrilateral map rather
/// than an affine one. Rasterising is a bilinear map from the source rectangle onto
/// the quad, matching what the original's free transform does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FreeTransform {
    pub top_left: (i32, i32),
    pub top_right: (i32, i32),
    pub bottom_right: (i32, i32),
    pub bottom_left: (i32, i32),
}

impl FreeTransform {
    /// Axis-aligned case: the quad is a plain rectangle.
    pub fn from_rect(x: i32, y: i32, w: i32, h: i32) -> Self {
        Self {
            top_left: (x, y),
            top_right: (x + w, y),
            bottom_right: (x + w, y + h),
            bottom_left: (x, y + h),
        }
    }

    pub fn corners(&self) -> [(i32, i32); 4] {
        [
            self.top_left,
            self.top_right,
            self.bottom_right,
            self.bottom_left,
        ]
    }

    /// True when `(x, y)` is inside the quad (same-side winding test).
    pub fn contains(&self, x: i32, y: i32) -> bool {
        let corners = self.corners();
        let mut positive = false;
        let mut negative = false;
        for i in 0..4 {
            let a = corners[i];
            let b = corners[(i + 1) % 4];
            let cross =
                (b.0 - a.0) as i64 * (y - a.1) as i64 - (b.1 - a.1) as i64 * (x - a.0) as i64;
            if cross > 0 {
                positive = true;
            } else if cross < 0 {
                negative = true;
            }
            if positive && negative {
                return false;
            }
        }
        true
    }

    pub fn bounds(&self) -> (i32, i32, i32, i32) {
        let c = self.corners();
        (
            c.iter().map(|p| p.0).min().unwrap_or(0),
            c.iter().map(|p| p.1).min().unwrap_or(0),
            c.iter().map(|p| p.0).max().unwrap_or(0),
            c.iter().map(|p| p.1).max().unwrap_or(0),
        )
    }
}

/// A running tween on one picture.
///
/// Mirrors `WolfPictureState`'s per-picture timing: 発動ディレイ holds the start
/// values for `delay_frames` before anything moves, then opacity and zoom
/// interpolate over `frames_total`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tween {
    pub opacity_from: i32,
    pub opacity_to: i32,
    pub zoom_from: i32,
    pub zoom_to: i32,
    pub pattern_step: i32,
    pub frames_left: i32,
    pub frames_total: i32,
    /// 発動ディレイ: frames to hold the start values before the tween begins.
    pub delay_frames: i32,
}

impl Tween {
    pub fn is_active(&self) -> bool {
        self.delay_frames > 0 || (self.frames_left > 0 && self.frames_total > 0)
    }

    /// Advances one frame, returning the new opacity and zoom.
    ///
    /// A delay counts down before anything moves, so the picture holds its starting
    /// values — that is precisely what 発動ディレイ means.
    pub fn advance(&mut self, current_opacity: i32, current_zoom: i32) -> (i32, i32) {
        if self.delay_frames > 0 {
            self.delay_frames -= 1;
            return (current_opacity, current_zoom);
        }
        if self.frames_left <= 0 || self.frames_total <= 0 {
            return (self.opacity_to, self.zoom_to);
        }
        let left = self.frames_left - 1;
        self.frames_left = left;
        let done = self.frames_total - left;
        if left == 0 {
            (self.opacity_to, self.zoom_to)
        } else {
            let opacity = self.opacity_from
                + (self.opacity_to - self.opacity_from) * done / self.frames_total;
            let zoom =
                self.zoom_from + (self.zoom_to - self.zoom_from) * done / self.frames_total;
            (opacity, zoom)
        }
    }
}

/// A picture to draw.
#[derive(Debug, Clone)]
pub struct PictureDraw {
    /// Slot id; lower draws first, so higher slots sit on top.
    pub slot: i32,
    pub x: i32,
    pub y: i32,
    pub anchor: Anchor,
    /// Percent zoom, 100 = native.
    pub zoom_x: i32,
    pub zoom_y: i32,
    /// Rotation in tenths of a degree (450 = 45°); negatives and >3600 wrap.
    pub angle_tenths: i32,
    pub opacity: i32,
    pub blend: i32,
    /// Per-channel colour correction in the engine's percent scale (100 neutral).
    pub colour: (i32, i32, i32),
    /// 4 means お手軽ウィンドウ (9-slice).
    pub display_type: i32,
    /// `None` means "draw at native size"; `Some` is the 9-slice target box.
    pub target_size: Option<(i32, i32)>,
    /// 自由変形 corners; when set the picture is mapped onto this quad.
    pub free_transform: Option<FreeTransform>,
    /// Running tween, if the picture is fading or zooming.
    pub tween: Option<Tween>,
}

impl PictureDraw {
    pub fn new(slot: i32, x: i32, y: i32) -> Self {
        Self {
            slot,
            x,
            y,
            anchor: Anchor::TopLeft,
            zoom_x: 100,
            zoom_y: 100,
            angle_tenths: 0,
            opacity: 255,
            blend: BLEND_NORMAL,
            // Neutral in the engine's percent scale is 100, not 0: the channel
            // conversion is (v - 100) * 2.55, so 0 would subtract 254.
            colour: (COLOUR_NEUTRAL, COLOUR_NEUTRAL, COLOUR_NEUTRAL),
            display_type: 0,
            target_size: None,
            free_transform: None,
            tween: None,
        }
    }

    pub fn is_rotated(&self) -> bool {
        self.angle_tenths % 3600 != 0
    }

    /// Effective zoom. Per-axis zoom is ignored while rotated, matching the
    /// documented rule (rotation and separate X/Y zoom cannot both apply).
    pub fn effective_zoom(&self) -> (f32, f32) {
        if self.is_rotated() {
            let z = self.zoom_x as f32 / 100.0;
            (z, z)
        } else {
            (self.zoom_x as f32 / 100.0, self.zoom_y as f32 / 100.0)
        }
    }
}

/// Composites one picture into `frame`.
pub fn draw_picture(frame: &mut Image, sheet: &Image, picture: &PictureDraw) {
    let (zx, zy) = picture.effective_zoom();
    let base_w = (sheet.width as f32 * zx).round().max(1.0) as i32;
    let base_h = (sheet.height as f32 * zy).round().max(1.0) as i32;

    if picture.display_type == 4 {
        // 9-slice: corners keep their size, edges and centre stretch.
        let (dw, dh) = picture.target_size.unwrap_or((base_w, base_h));
        let (left, top) = picture.anchor.top_left(picture.x, picture.y, dw, dh);
        let cw = (sheet.width as i32) / 3;
        let ch = (sheet.height as i32) / 3;
        for slice in nine_slice(sheet.width as i32, sheet.height as i32, left, top, dw, dh, cw, ch) {
            blit_scaled(frame, sheet, &slice, picture);
        }
        return;
    }

    if let Some(quad) = picture.free_transform {
        draw_free_transform(frame, sheet, &quad, picture);
        return;
    }

    let (left, top) = picture.anchor.top_left(picture.x, picture.y, base_w, base_h);
    if !picture.is_rotated() {
        // Fast path: axis-aligned scale, nearest neighbour.
        for dy in 0..base_h {
            let sy = (dy as f32 / zy) as u32;
            if sy >= sheet.height {
                continue;
            }
            for dx in 0..base_w {
                let sx = (dx as f32 / zx) as u32;
                if sx >= sheet.width {
                    continue;
                }
                let src = sheet.pixel(sx, sy);
                let mut colour = apply_colour_correction(src, picture.colour.0, picture.colour.1, picture.colour.2);
                colour[3] = ((colour[3] as i32 * picture.opacity.clamp(0, 255)) / 255) as u8;
                put(frame, left + dx, top + dy, colour, picture.blend);
            }
        }
        return;
    }

    // Rotated: inverse-map each destination pixel about the anchor point.
    let angle = (picture.angle_tenths as f32) * std::f32::consts::PI / 1800.0;
    let (cos, sin) = (angle.cos(), angle.sin());
    let (ax, ay) = (picture.x as f32, picture.y as f32);
    let half_w = base_w as f32 / 2.0;
    let half_h = base_h as f32 / 2.0;
    let reach = ((half_w * half_w + half_h * half_h).sqrt()).ceil() as i32 + 1;
    for dy in -reach..=reach {
        for dx in -reach..=reach {
            let px = ax + dx as f32;
            let py = ay + dy as f32;
            // Undo the rotation about the anchor.
            let rx = px - ax;
            let ry = py - ay;
            let ux = rx * cos + ry * sin + half_w;
            let uy = -rx * sin + ry * cos + half_h;
            if ux < 0.0 || uy < 0.0 || ux >= base_w as f32 || uy >= base_h as f32 {
                continue;
            }
            let sx = (ux / zx) as u32;
            let sy = (uy / zy) as u32;
            if sx >= sheet.width || sy >= sheet.height {
                continue;
            }
            let src = sheet.pixel(sx, sy);
            let mut colour = apply_colour_correction(src, picture.colour.0, picture.colour.1, picture.colour.2);
            colour[3] = ((colour[3] as i32 * picture.opacity.clamp(0, 255)) / 255) as u8;
            put(frame, px as i32, py as i32, colour, picture.blend);
        }
    }
}

/// Maps the whole source rectangle onto a quadrilateral by bilinear interpolation.
fn draw_free_transform(
    frame: &mut Image,
    sheet: &Image,
    quad: &FreeTransform,
    picture: &PictureDraw,
) {
    let (min_x, min_y, max_x, max_y) = quad.bounds();
    let (tl, tr, br, bl) = (
        quad.top_left,
        quad.top_right,
        quad.bottom_right,
        quad.bottom_left,
    );
    let sw = sheet.width as f32;
    let sh = sheet.height as f32;
    if sw <= 0.0 || sh <= 0.0 {
        return;
    }
    for y in min_y..=max_y {
        for x in min_x..=max_x {
            if !quad.contains(x, y) {
                continue;
            }
            let (px, py) = (x as f32, y as f32);
            let Some((u, v)) = bilinear_inverse(tl, tr, br, bl, px, py) else {
                continue;
            };
            if !(0.0..=1.0).contains(&u) || !(0.0..=1.0).contains(&v) {
                continue;
            }
            let sx = (u * (sw - 1.0)).round().clamp(0.0, sw - 1.0) as u32;
            let sy = (v * (sh - 1.0)).round().clamp(0.0, sh - 1.0) as u32;
            let src = sheet.pixel(sx, sy);
            let mut colour =
                apply_colour_correction(src, picture.colour.0, picture.colour.1, picture.colour.2);
            colour[3] = ((colour[3] as i32 * picture.opacity.clamp(0, 255)) / 255) as u8;
            put(frame, x, y, colour, picture.blend);
        }
    }
}

/// Finds the `(u, v)` in the unit square that bilinearly maps to `(px, py)`.
///
/// Newton's method on the bilinear map; a failure is reported as `None` rather than
/// guessing a sample position.
fn bilinear_inverse(
    tl: (i32, i32),
    tr: (i32, i32),
    br: (i32, i32),
    bl: (i32, i32),
    px: f32,
    py: f32,
) -> Option<(f32, f32)> {
    let (ax, ay) = (tl.0 as f32, tl.1 as f32);
    let (bx, by) = (tr.0 as f32, tr.1 as f32);
    let (cx, cy) = (br.0 as f32, br.1 as f32);
    let (dx, dy) = (bl.0 as f32, bl.1 as f32);
    let p = |u: f32, v: f32| -> (f32, f32) {
        (
            (1.0 - u) * (1.0 - v) * ax + u * (1.0 - v) * bx + u * v * cx + (1.0 - u) * v * dx,
            (1.0 - u) * (1.0 - v) * ay + u * (1.0 - v) * by + u * v * cy + (1.0 - u) * v * dy,
        )
    };
    let mut u = 0.5f32;
    let mut v = 0.5f32;
    for _ in 0..8 {
        let (fx, fy) = p(u, v);
        let (rx, ry) = (fx - px, fy - py);
        if rx.abs() < 0.5 && ry.abs() < 0.5 {
            return Some((u, v));
        }
        const E: f32 = 1e-3;
        let (fux, fuy) = p(u + E, v);
        let (fvx, fvy) = p(u, v + E);
        let j00 = (fux - fx) / E;
        let j10 = (fuy - fy) / E;
        let j01 = (fvx - fx) / E;
        let j11 = (fvy - fy) / E;
        let det = j00 * j11 - j01 * j10;
        if det.abs() < 1e-6 {
            return None;
        }
        let du = (rx * j11 - ry * j01) / det;
        let dv = (j00 * ry - j10 * rx) / det;
        u -= du;
        v -= dv;
        if !(-0.5..=1.5).contains(&u) || !(-0.5..=1.5).contains(&v) {
            return None;
        }
    }
    let (fx, fy) = p(u, v);
    if (fx - px).abs() < 1.0 && (fy - py).abs() < 1.0 {
        Some((u, v))
    } else {
        None
    }
}

fn blit_scaled(frame: &mut Image, sheet: &Image, slice: &Slice, picture: &PictureDraw) {
    let dw = slice.dst_right - slice.dst_left;
    let dh = slice.dst_bottom - slice.dst_top;
    let sw = slice.src_right - slice.src_left;
    let sh = slice.src_bottom - slice.src_top;
    if dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0 {
        return;
    }
    for dy in 0..dh {
        let sy = slice.src_top + (dy * sh) / dh;
        if sy < 0 || sy >= sheet.height as i32 {
            continue;
        }
        for dx in 0..dw {
            let sx = slice.src_left + (dx * sw) / dw;
            if sx < 0 || sx >= sheet.width as i32 {
                continue;
            }
            let src = sheet.pixel(sx as u32, sy as u32);
            let mut colour = apply_colour_correction(src, picture.colour.0, picture.colour.1, picture.colour.2);
            colour[3] = ((colour[3] as i32 * picture.opacity.clamp(0, 255)) / 255) as u8;
            put(frame, slice.dst_left + dx, slice.dst_top + dy, colour, picture.blend);
        }
    }
}

fn put(frame: &mut Image, x: i32, y: i32, colour: [u8; 4], mode: i32) {
    if x < 0 || y < 0 || x >= frame.width as i32 || y >= frame.height as i32 {
        return;
    }
    let i = ((y as usize) * (frame.width as usize) + (x as usize)) * 4;
    let dst = [
        frame.rgba[i],
        frame.rgba[i + 1],
        frame.rgba[i + 2],
        frame.rgba[i + 3],
    ];
    let out = blend(dst, colour, mode);
    frame.rgba[i..i + 4].copy_from_slice(&out);
}

/// Draws a list of pictures in slot order (higher slot on top).
pub fn draw_pictures(frame: &mut Image, sheets: &[(PictureDraw, Image)]) {
    let mut ordered: Vec<&(PictureDraw, Image)> = sheets.iter().collect();
    ordered.sort_by_key(|(picture, _)| picture.slot);
    for (picture, sheet) in ordered {
        draw_picture(frame, sheet, picture);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(w: u32, h: u32, rgba: [u8; 4]) -> Image {
        let mut image = Image::new(w, h);
        for chunk in image.rgba.chunks_exact_mut(4) {
            chunk.copy_from_slice(&rgba);
        }
        image
    }

    // --- anchors ---------------------------------------------------------

    #[test]
    fn all_seven_anchor_codes_map_to_distinct_anchors() {
        let codes: Vec<Anchor> = (0..=6).map(Anchor::from_code).collect();
        assert_eq!(
            codes,
            vec![
                Anchor::TopLeft,
                Anchor::Center,
                Anchor::BottomLeft,
                Anchor::TopRight,
                Anchor::BottomRight,
                Anchor::CenterTop,
                Anchor::CenterBottom,
            ]
        );
    }

    #[test]
    fn each_anchor_places_a_100x40_picture_at_the_documented_corner() {
        // (x, y) is the anchor point; the picture's top-left is derived from it.
        let cases = [
            (Anchor::TopLeft, (100, 200)),
            (Anchor::Center, (50, 180)),
            (Anchor::BottomLeft, (100, 180)),
            (Anchor::TopRight, (0, 200)),
            (Anchor::BottomRight, (0, 180)),
            (Anchor::CenterTop, (50, 200)),
            (Anchor::CenterBottom, (50, 180)),
        ];
        for (anchor, expected) in cases {
            assert_eq!(
                anchor.top_left(100, 200, 100, 40),
                expected,
                "anchor {anchor:?}"
            );
        }
    }

    #[test]
    fn an_unknown_anchor_code_falls_back_to_top_left() {
        assert_eq!(Anchor::from_code(99), Anchor::TopLeft);
        assert_eq!(Anchor::from_code(-1), Anchor::TopLeft);
    }

    // --- zoom ------------------------------------------------------------

    #[test]
    fn per_axis_zoom_applies_when_unrotated() {
        let mut p = PictureDraw::new(1, 0, 0);
        p.zoom_x = 200;
        p.zoom_y = 50;
        assert_eq!(p.effective_zoom(), (2.0, 0.5));
    }

    #[test]
    fn per_axis_zoom_is_ignored_when_rotated() {
        // Documented rule: rotation and separate X/Y zoom cannot both apply.
        let mut p = PictureDraw::new(1, 0, 0);
        p.zoom_x = 200;
        p.zoom_y = 50;
        p.angle_tenths = 450;
        assert_eq!(p.effective_zoom(), (2.0, 2.0));
    }

    #[test]
    fn a_rotation_that_wraps_to_zero_is_not_treated_as_rotated() {
        let mut p = PictureDraw::new(1, 0, 0);
        p.angle_tenths = 3600;
        assert!(!p.is_rotated());
        p.angle_tenths = -3600;
        assert!(!p.is_rotated());
        p.angle_tenths = 450;
        assert!(p.is_rotated());
    }

    // --- blends ----------------------------------------------------------

    #[test]
    fn normal_blend_replaces_the_destination() {
        let out = blend([10, 20, 30, 255], [200, 100, 50, 255], BLEND_NORMAL);
        assert_eq!(out, [200, 100, 50, 255]);
    }

    #[test]
    fn add_blend_sums_and_clamps() {
        assert_eq!(blend([10, 20, 30, 255], [5, 6, 7, 255], BLEND_ADD), [15, 26, 37, 255]);
        assert_eq!(
            blend([250, 250, 250, 255], [50, 50, 50, 255], BLEND_ADD),
            [255, 255, 255, 255]
        );
    }

    #[test]
    fn subtract_blend_differences_and_clamps_at_zero() {
        assert_eq!(blend([100, 100, 100, 255], [40, 60, 80, 255], BLEND_SUBTRACT), [60, 40, 20, 255]);
        assert_eq!(
            blend([10, 10, 10, 255], [50, 50, 50, 255], BLEND_SUBTRACT),
            [0, 0, 0, 255]
        );
    }

    #[test]
    fn multiply_blend_scales_by_255() {
        assert_eq!(blend([255, 128, 0, 255], [128, 128, 255, 255], BLEND_MULTIPLY), [128, 64, 0, 255]);
    }

    #[test]
    fn a_transparent_source_leaves_the_destination_untouched() {
        assert_eq!(blend([1, 2, 3, 255], [9, 9, 9, 0], BLEND_ADD), [1, 2, 3, 255]);
    }

    #[test]
    fn a_half_transparent_add_only_half_adds() {
        // (200*128 + 0*127)/255 = 100
        let out = blend([0, 0, 0, 255], [200, 200, 200, 128], BLEND_ADD);
        assert_eq!(out[0], 100);
    }

    // --- colour correction ----------------------------------------------

    #[test]
    fn the_engine_channel_conversion_matches_the_decompiled_arithmetic() {
        // (v - 100) * 2.55, truncated toward zero.
        assert_eq!(engine_colour_channel(100), 0);
        assert_eq!(engine_colour_channel(120), 51);
        assert_eq!(engine_colour_channel(80), -51);
        assert_eq!(engine_colour_channel(150), 127);
        // Endpoints are 254/-254, not 255/-255: 2.55 is not representable and the
        // engine truncates with `(int)`.
        assert_eq!(engine_colour_channel(0), -254);
        assert_eq!(engine_colour_channel(200), 254);
        // Out of range cannot exceed what a legal endpoint produces.
        assert_eq!(engine_colour_channel(1000), 254);
        assert_eq!(engine_colour_channel(-1000), -254);
    }

    #[test]
    fn colour_correction_uses_the_engines_percent_scale() {
        // 100 is neutral: a no-op.
        assert_eq!(
            apply_colour_correction([100, 100, 100, 255], 100, 100, 100),
            [100, 100, 100, 255]
        );
        // 120 -> +51 on every channel.
        assert_eq!(
            apply_colour_correction([100, 100, 100, 255], 120, 120, 120),
            [151, 151, 151, 255]
        );
        // 80 -> -51.
        assert_eq!(
            apply_colour_correction([100, 100, 100, 255], 80, 80, 80),
            [49, 49, 49, 255]
        );
    }

    #[test]
    fn colour_correction_is_per_channel_and_clamps_at_the_ends() {
        // Only red corrected upward: it clamps, the others are untouched.
        assert_eq!(
            apply_colour_correction([250, 5, 128, 255], 200, 100, 100),
            [255, 5, 128, 255]
        );
        // Only green corrected downward: it clamps at 0.
        assert_eq!(
            apply_colour_correction([250, 5, 128, 255], 100, 0, 100),
            [250, 0, 128, 255]
        );
    }

    // --- 9-slice ---------------------------------------------------------

    #[test]
    fn nine_slice_produces_nine_rectangles() {
        let slices = nine_slice(90, 60, 10, 20, 200, 120, 30, 20);
        assert_eq!(slices.len(), 9);
    }

    #[test]
    fn nine_slice_corners_keep_their_source_size() {
        let slices = nine_slice(90, 60, 0, 0, 300, 200, 30, 20);
        // Top-left corner: source 30x20, destination also 30x20.
        let tl = slices[0];
        assert_eq!(tl.src_right - tl.src_left, 30);
        assert_eq!(tl.src_bottom - tl.src_top, 20);
        assert_eq!(tl.dst_right - tl.dst_left, 30);
        assert_eq!(tl.dst_bottom - tl.dst_top, 20);
        // Centre stretches: source 30x20, destination fills the gap.
        let centre = slices[4];
        assert_eq!(centre.dst_right - centre.dst_left, 300 - 60);
        assert_eq!(centre.dst_bottom - centre.dst_top, 200 - 40);
    }

    #[test]
    fn nine_slice_clamps_corners_that_would_overlap() {
        // corner 100 on a 90-wide source clamps to 45 (half).
        let slices = nine_slice(90, 60, 0, 0, 300, 200, 100, 100);
        let tl = slices[0];
        assert_eq!(tl.src_right - tl.src_left, 45);
        assert_eq!(tl.src_bottom - tl.src_top, 30);
    }

    // --- drawing ---------------------------------------------------------

    #[test]
    fn a_picture_draws_at_its_anchor() {
        let mut frame = solid(40, 40, [0, 0, 0, 255]);
        let sheet = solid(10, 10, [255, 0, 0, 255]);
        let mut p = PictureDraw::new(1, 20, 20);
        p.anchor = Anchor::TopLeft;
        draw_picture(&mut frame, &sheet, &p);
        assert_eq!(frame.pixel(20, 20), [255, 0, 0, 255]);
        assert_eq!(frame.pixel(29, 29), [255, 0, 0, 255]);
        assert_eq!(frame.pixel(30, 30), [0, 0, 0, 255], "outside the picture");
        assert_eq!(frame.pixel(19, 19), [0, 0, 0, 255]);
    }

    #[test]
    fn a_centred_picture_straddles_the_anchor() {
        let mut frame = solid(40, 40, [0, 0, 0, 255]);
        let sheet = solid(10, 10, [0, 255, 0, 255]);
        let mut p = PictureDraw::new(1, 20, 20);
        p.anchor = Anchor::Center;
        draw_picture(&mut frame, &sheet, &p);
        // 10x10 centred on (20,20) covers 15..24.
        assert_eq!(frame.pixel(15, 15), [0, 255, 0, 255]);
        assert_eq!(frame.pixel(24, 24), [0, 255, 0, 255]);
        assert_eq!(frame.pixel(14, 20), [0, 0, 0, 255]);
    }

    #[test]
    fn zoom_scales_the_drawn_extent() {
        let mut frame = solid(40, 40, [0, 0, 0, 255]);
        let sheet = solid(4, 4, [0, 0, 255, 255]);
        let mut p = PictureDraw::new(1, 0, 0);
        p.zoom_x = 200;
        p.zoom_y = 200;
        draw_picture(&mut frame, &sheet, &p);
        // 4x4 at 200% covers 0..7.
        assert_eq!(frame.pixel(7, 7), [0, 0, 255, 255]);
        assert_eq!(frame.pixel(8, 8), [0, 0, 0, 255]);
    }

    #[test]
    fn opacity_scales_alpha() {
        let mut frame = solid(20, 20, [0, 0, 0, 255]);
        let sheet = solid(10, 10, [255, 255, 255, 255]);
        let mut p = PictureDraw::new(1, 0, 0);
        p.opacity = 128;
        p.blend = BLEND_NORMAL;
        draw_picture(&mut frame, &sheet, &p);
        // 255 * 128/255 = 128 alpha, blended over black gives ~128.
        let px = frame.pixel(0, 0);
        assert!(px[0] >= 126 && px[0] <= 130, "got {px:?}");
    }

    #[test]
    fn a_rotated_picture_covers_the_expected_diagonal_extent() {
        let mut frame = solid(60, 60, [0, 0, 0, 255]);
        // 20x4 bar rotated 90 degrees should become tall and thin.
        let sheet = solid(20, 4, [255, 0, 0, 255]);
        let mut p = PictureDraw::new(1, 30, 30);
        p.anchor = Anchor::Center;
        p.angle_tenths = 900; // 90 degrees
        draw_picture(&mut frame, &sheet, &p);
        // Report the actual extent instead of guessing a pixel.
        let mut min_y = i32::MAX;
        let mut max_y = i32::MIN;
        let mut min_x = i32::MAX;
        let mut max_x = i32::MIN;
        for y in 0..60u32 {
            for x in 0..60u32 {
                if frame.pixel(x, y) == [255, 0, 0, 255] {
                    min_y = min_y.min(y as i32);
                    max_y = max_y.max(y as i32);
                    min_x = min_x.min(x as i32);
                    max_x = max_x.max(x as i32);
                }
            }
        }
        eprintln!("ROT extent x={min_x}..{max_x} y={min_y}..{max_y}");
        // A 20x4 bar rotated 90 degrees about its centre becomes 4 wide and 20 tall.
        assert!(
            max_y - min_y >= 16,
            "rotated bar must be tall: y={min_y}..{max_y}"
        );
        assert!(
            max_x - min_x <= 8,
            "rotated bar must be narrow: x={min_x}..{max_x}"
        );
    }

    #[test]
    fn pictures_draw_in_slot_order_with_higher_on_top() {
        let mut frame = solid(20, 20, [0, 0, 0, 255]);
        let red = solid(20, 20, [255, 0, 0, 255]);
        let blue = solid(20, 20, [0, 0, 255, 255]);
        let mut low = PictureDraw::new(1, 0, 0);
        low.slot = 1;
        let mut high = PictureDraw::new(2, 0, 0);
        high.slot = 9;
        // Deliberately pass them out of order.
        draw_pictures(&mut frame, &[(high, blue), (low, red)]);
        assert_eq!(frame.pixel(5, 5), [0, 0, 255, 255], "the higher slot wins");
    }

    // --- free transform --------------------------------------------------

    #[test]
    fn a_rect_free_transform_matches_a_plain_rectangle() {
        let quad = FreeTransform::from_rect(10, 20, 30, 40);
        assert_eq!(quad.bounds(), (10, 20, 40, 60));
        assert!(quad.contains(20, 30));
        assert!(!quad.contains(9, 30));
        assert!(!quad.contains(41, 30));
    }

    #[test]
    fn a_skewed_quad_excludes_points_outside_it() {
        // Trapezoid: top edge narrower than the bottom.
        let quad = FreeTransform {
            top_left: (30, 0),
            top_right: (70, 0),
            bottom_right: (100, 100),
            bottom_left: (0, 100),
        };
        assert!(quad.contains(50, 50), "centre must be inside");
        assert!(!quad.contains(5, 0), "outside the narrow top edge");
        assert!(quad.contains(5, 95), "inside near the wide bottom edge");
        assert!(!quad.contains(50, 101), "below the quad");
    }

    #[test]
    fn a_free_transform_draws_inside_its_quad_only() {
        let mut frame = solid(200, 200, [0, 0, 0, 255]);
        let sheet = solid(20, 20, [0, 255, 0, 255]);
        let mut p = PictureDraw::new(1, 0, 0);
        p.free_transform = Some(FreeTransform {
            top_left: (50, 50),
            top_right: (120, 50),
            bottom_right: (150, 150),
            bottom_left: (20, 150),
        });
        draw_picture(&mut frame, &sheet, &p);
        assert_eq!(frame.pixel(85, 100), [0, 255, 0, 255], "centre painted");
        assert_eq!(frame.pixel(5, 5), [0, 0, 0, 255], "outside untouched");
    }

    #[test]
    fn a_free_transformed_picture_honours_opacity() {
        let mut frame = solid(100, 100, [0, 0, 0, 255]);
        let sheet = solid(10, 10, [255, 255, 255, 255]);
        let mut p = PictureDraw::new(1, 0, 0);
        p.opacity = 128;
        p.free_transform = Some(FreeTransform::from_rect(10, 10, 60, 60));
        draw_picture(&mut frame, &sheet, &p);
        let px = frame.pixel(40, 40);
        assert!(px[0] >= 120 && px[0] <= 136, "got {px:?}");
    }

    // --- tween timing ----------------------------------------------------

    #[test]
    fn a_tween_interpolates_opacity_and_zoom_over_its_frames() {
        let mut t = Tween {
            opacity_from: 0,
            opacity_to: 200,
            zoom_from: 100,
            zoom_to: 200,
            pattern_step: 0,
            frames_left: 4,
            frames_total: 4,
            delay_frames: 0,
        };
        // Frame 1 of 4: done = 1.
        let (o1, z1) = t.advance(0, 100);
        assert_eq!(o1, 50, "100 * 1 / 4 of the way");
        assert_eq!(z1, 125, "100 + 100 * 1 / 4");
        let (o2, _) = t.advance(o1, z1);
        assert_eq!(o2, 100);
        let (o3, _) = t.advance(o2, z1);
        assert_eq!(o3, 150);
        // Final frame snaps to the target exactly.
        let (o4, z4) = t.advance(o3, z1);
        assert_eq!((o4, z4), (200, 200));
        assert_eq!(t.frames_left, 0);
        assert!(!t.is_active());
    }

    #[test]
    fn the_delay_holds_the_start_values_before_the_tween_begins() {
        // 発動ディレイ: the picture must not move for delay_frames frames.
        let mut t = Tween {
            opacity_from: 0,
            opacity_to: 100,
            zoom_from: 100,
            zoom_to: 100,
            pattern_step: 0,
            frames_left: 2,
            frames_total: 2,
            delay_frames: 3,
        };
        for frame in 0..3 {
            let (o, z) = t.advance(7, 7);
            assert_eq!((o, z), (7, 7), "frame {frame} must hold the start values");
        }
        assert_eq!(t.frames_left, 2, "the tween itself has not started yet");
        let (o, _) = t.advance(7, 7);
        assert_eq!(o, 50, "the tween starts after the delay");
    }

    #[test]
    fn an_inactive_tween_reports_itself_done() {
        let t = Tween {
            opacity_from: 0,
            opacity_to: 0,
            zoom_from: 100,
            zoom_to: 100,
            pattern_step: 0,
            frames_left: 0,
            frames_total: 0,
            delay_frames: 0,
        };
        assert!(!t.is_active());
    }

    #[test]
    fn a_zero_frame_tween_snaps_to_its_target() {
        let mut t = Tween {
            opacity_from: 10,
            opacity_to: 250,
            zoom_from: 100,
            zoom_to: 100,
            pattern_step: 0,
            frames_left: 0,
            frames_total: 0,
            delay_frames: 0,
        };
        assert_eq!(t.advance(10, 100), (250, 100));
    }

    #[test]
    fn a_picture_outside_the_frame_is_clipped_without_panicking() {
        let mut frame = solid(10, 10, [0, 0, 0, 255]);
        let sheet = solid(20, 20, [255, 255, 0, 255]);
        let p = PictureDraw::new(1, -50, -50);
        draw_picture(&mut frame, &sheet, &p);
        let q = PictureDraw::new(2, 100, 100);
        draw_picture(&mut frame, &sheet, &q);
        // Nothing changed.
        assert_eq!(frame.pixel(0, 0), [0, 0, 0, 255]);
    }
}
