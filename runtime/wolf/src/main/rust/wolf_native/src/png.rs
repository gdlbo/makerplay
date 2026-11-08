//! Minimal PNG decoder for the Rust scene compositor.
//!
//! The deployment ships only PNG (map chips, autotile sheets, chara chips,
//! pictures), so this covers the PNG subset those files actually use: 8-bit
//! greyscale / RGB / palette / greyscale+alpha / RGBA, with all five filter types
//! and Adam7 interlacing rejected rather than mis-decoded.
//!
//! Deliberately strict. A silently wrong decode would surface as wrong colours on
//! screen, which is far harder to diagnose than a hard failure, so anything
//! outside the supported subset returns an error naming what was found.

use std::io::Read;

use flate2::read::ZlibDecoder;

/// A decoded image as non-premultiplied RGBA8.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub width: u32,
    pub height: u32,
    /// `width * height * 4` bytes.
    pub rgba: Vec<u8>,
}

impl Image {
    pub fn new(width: u32, height: u32) -> Self {
        Self {
            width,
            height,
            rgba: vec![0u8; (width as usize) * (height as usize) * 4],
        }
    }

    pub fn pixel(&self, x: u32, y: u32) -> [u8; 4] {
        if x >= self.width || y >= self.height {
            return [0, 0, 0, 0];
        }
        let i = ((y as usize) * (self.width as usize) + (x as usize)) * 4;
        [
            self.rgba[i],
            self.rgba[i + 1],
            self.rgba[i + 2],
            self.rgba[i + 3],
        ]
    }

    /// Copies a sub-rectangle into `dst` at `(dx, dy)`, clipping at the edges.
    ///
    /// Alpha-blends only when `blend` is true; otherwise the source replaces the
    /// destination, which is what the tile pass wants (a chip is opaque or it is
    /// transparent and must not contribute).
    pub fn blit(
        &self,
        dst: &mut Image,
        src_x: u32,
        src_y: u32,
        w: u32,
        h: u32,
        dx: i32,
        dy: i32,
        blend: bool,
    ) {
        for row in 0..h {
            let ty = dy + row as i32;
            if ty < 0 || ty >= dst.height as i32 {
                continue;
            }
            let sy = src_y + row;
            if sy >= self.height {
                break;
            }
            for col in 0..w {
                let tx = dx + col as i32;
                if tx < 0 || tx >= dst.width as i32 {
                    continue;
                }
                let sx = src_x + col;
                if sx >= self.width {
                    break;
                }
                let src = self.pixel(sx, sy);
                let di = ((ty as usize) * (dst.width as usize) + (tx as usize)) * 4;
                if blend && src[3] != 255 {
                    let a = src[3] as u32;
                    if a == 0 {
                        continue;
                    }
                    let ia = 255 - a;
                    for c in 0..3 {
                        let s = src[c] as u32;
                        let d = dst.rgba[di + c] as u32;
                        dst.rgba[di + c] = ((s * a + d * ia) / 255) as u8;
                    }
                    let da = dst.rgba[di + 3] as u32;
                    dst.rgba[di + 3] = (a + da * ia / 255).min(255) as u8;
                } else if src[3] == 0 {
                    continue;
                } else {
                    dst.rgba[di..di + 4].copy_from_slice(&src);
                }
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PngError(pub String);

impl std::fmt::Display for PngError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for PngError {}

type Result<T> = std::result::Result<T, PngError>;

fn err<T>(msg: impl Into<String>) -> Result<T> {
    Err(PngError(msg.into()))
}

const SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];

/// Decodes an image by sniffing its format.
///
/// The deployment ships 634 PNGs and 16 JPEGs, and the JPEGs include the title
/// background (`SystemFile/title.jpg`), so a PNG-only decoder loses the most
/// visible picture in the game.
pub fn decode_any(data: &[u8]) -> Result<Image> {
    // JPEG starts with FF D8 FF.
    if data.len() > 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF {
        return decode_jpeg(data);
    }
    decode(data)
}

/// Decodes a JPEG to RGBA8.
pub fn decode_jpeg(data: &[u8]) -> Result<Image> {
    use jpeg_decoder::{Decoder, PixelFormat};
    let mut decoder = Decoder::new(std::io::Cursor::new(data));
    let pixels = decoder
        .decode()
        .map_err(|e| PngError(format!("JPEG decode failed: {e}")))?;
    let info = decoder
        .info()
        .ok_or_else(|| PngError("JPEG has no frame info".into()))?;
    let (width, height) = (info.width as u32, info.height as u32);
    if width == 0 || height == 0 || width > 16384 || height > 16384 {
        return err(format!("implausible JPEG size {width}x{height}"));
    }
    let mut image = Image::new(width, height);
    match info.pixel_format {
        PixelFormat::RGB24 => {
            if pixels.len() < (width * height * 3) as usize {
                return err("JPEG RGB data is short".to_string());
            }
            for i in 0..(width * height) as usize {
                image.rgba[i * 4] = pixels[i * 3];
                image.rgba[i * 4 + 1] = pixels[i * 3 + 1];
                image.rgba[i * 4 + 2] = pixels[i * 3 + 2];
                image.rgba[i * 4 + 3] = 255;
            }
        }
        PixelFormat::L8 => {
            if pixels.len() < (width * height) as usize {
                return err("JPEG greyscale data is short".to_string());
            }
            for i in 0..(width * height) as usize {
                let v = pixels[i];
                image.rgba[i * 4] = v;
                image.rgba[i * 4 + 1] = v;
                image.rgba[i * 4 + 2] = v;
                image.rgba[i * 4 + 3] = 255;
            }
        }
        PixelFormat::CMYK32 => {
            if pixels.len() < (width * height * 4) as usize {
                return err("JPEG CMYK data is short".to_string());
            }
            for i in 0..(width * height) as usize {
                let (c, m, y, k) = (
                    pixels[i * 4] as u32,
                    pixels[i * 4 + 1] as u32,
                    pixels[i * 4 + 2] as u32,
                    pixels[i * 4 + 3] as u32,
                );
                image.rgba[i * 4] = ((c * k) / 255) as u8;
                image.rgba[i * 4 + 1] = ((m * k) / 255) as u8;
                image.rgba[i * 4 + 2] = ((y * k) / 255) as u8;
                image.rgba[i * 4 + 3] = 255;
            }
        }
        PixelFormat::L16 => {
            return err("16-bit JPEG is not supported".to_string());
        }
    }
    Ok(image)
}

/// Decodes a PNG file to RGBA8.
pub fn decode(data: &[u8]) -> Result<Image> {
    if data.len() < 8 || data[..8] != SIGNATURE {
        return err("not a PNG file (bad signature)");
    }
    let mut pos = 8usize;
    let mut header: Option<(u32, u32, u8, u8, u8)> = None; // w, h, depth, colour, interlace
    let mut palette: Vec<[u8; 3]> = Vec::new();
    let mut trns: Option<Vec<u8>> = None;
    let mut idat: Vec<u8> = Vec::new();
    let mut seen_end = false;

    while pos + 8 <= data.len() {
        let len = u32::from_be_bytes([data[pos], data[pos + 1], data[pos + 2], data[pos + 3]])
            as usize;
        let kind = &data[pos + 4..pos + 8];
        pos += 8;
        if pos + len + 4 > data.len() {
            return err("PNG chunk overruns the file");
        }
        let body = &data[pos..pos + len];
        pos += len + 4; // skip CRC; we do not verify it

        match kind {
            b"IHDR" => {
                if body.len() < 13 {
                    return err("short IHDR");
                }
                let w = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
                let h = u32::from_be_bytes([body[4], body[5], body[6], body[7]]);
                let depth = body[8];
                let colour = body[9];
                let interlace = body[12];
                // Measured: this deployment ships 320x8360 chip sheets
                // (39_higashi.png, 62_ghost_house.png), so the ceiling must clear
                // that or nine maps cannot be composed at all.
                const MAX_EDGE: u32 = 16384;
                if w == 0 || h == 0 || w > MAX_EDGE || h > MAX_EDGE {
                    return err(format!("implausible PNG size {w}x{h}"));
                }
                if interlace != 0 {
                    return err("interlaced PNG is not supported");
                }
                header = Some((w, h, depth, colour, interlace));
            }
            b"PLTE" => {
                palette = body.chunks_exact(3).map(|c| [c[0], c[1], c[2]]).collect();
            }
            b"tRNS" => trns = Some(body.to_vec()),
            b"IDAT" => idat.extend_from_slice(body),
            b"IEND" => {
                seen_end = true;
                break;
            }
            _ => {}
        }
    }

    let Some((width, height, depth, colour, _)) = header else {
        return err("PNG has no IHDR");
    };
    if !seen_end && idat.is_empty() {
        return err("PNG has no image data");
    }
    if depth != 8 {
        return err(format!("unsupported PNG bit depth {depth} (only 8)"));
    }
    let channels: usize = match colour {
        0 => 1, // greyscale
        2 => 3, // RGB
        3 => 1, // palette index
        4 => 2, // greyscale + alpha
        6 => 4, // RGBA
        other => return err(format!("unsupported PNG colour type {other}")),
    };

    let mut raw = Vec::new();
    ZlibDecoder::new(&idat[..])
        .read_to_end(&mut raw)
        .map_err(|e| PngError(format!("PNG inflate failed: {e}")))?;

    let stride = width as usize * channels;
    let expected = (stride + 1) * height as usize;
    if raw.len() < expected {
        return err(format!(
            "PNG data too short: {} bytes for {expected}",
            raw.len()
        ));
    }

    // Undo the per-scanline filters.
    let mut lines: Vec<Vec<u8>> = Vec::with_capacity(height as usize);
    let mut prev = vec![0u8; stride];
    for y in 0..height as usize {
        let base = y * (stride + 1);
        let filter = raw[base];
        let src = &raw[base + 1..base + 1 + stride];
        let mut line = src.to_vec();
        match filter {
            0 => {}
            1 => {
                for i in channels..stride {
                    line[i] = line[i].wrapping_add(line[i - channels]);
                }
            }
            2 => {
                for i in 0..stride {
                    line[i] = line[i].wrapping_add(prev[i]);
                }
            }
            3 => {
                for i in 0..stride {
                    let left = if i >= channels { line[i - channels] as u32 } else { 0 };
                    let up = prev[i] as u32;
                    line[i] = line[i].wrapping_add(((left + up) / 2) as u8);
                }
            }
            4 => {
                for i in 0..stride {
                    let a = if i >= channels { line[i - channels] as i32 } else { 0 };
                    let b = prev[i] as i32;
                    let c = if i >= channels { prev[i - channels] as i32 } else { 0 };
                    let p = a + b - c;
                    let pa = (p - a).abs();
                    let pb = (p - b).abs();
                    let pc = (p - c).abs();
                    let pred = if pa <= pb && pa <= pc {
                        a
                    } else if pb <= pc {
                        b
                    } else {
                        c
                    };
                    line[i] = line[i].wrapping_add(pred as u8);
                }
            }
            other => return err(format!("unknown PNG filter type {other}")),
        }
        prev = line.clone();
        lines.push(line);
    }

    let mut image = Image::new(width, height);
    for y in 0..height as usize {
        let line = &lines[y];
        for x in 0..width as usize {
            let di = (y * width as usize + x) * 4;
            let (r, g, b, a) = match colour {
                0 => {
                    let v = line[x];
                    (v, v, v, 255)
                }
                2 => (line[x * 3], line[x * 3 + 1], line[x * 3 + 2], 255),
                3 => {
                    let idx = line[x] as usize;
                    let rgb = palette.get(idx).copied().unwrap_or([0, 0, 0]);
                    let alpha = trns
                        .as_ref()
                        .and_then(|t| t.get(idx).copied())
                        .unwrap_or(255);
                    (rgb[0], rgb[1], rgb[2], alpha)
                }
                4 => {
                    let v = line[x * 2];
                    (v, v, v, line[x * 2 + 1])
                }
                _ => (
                    line[x * 4],
                    line[x * 4 + 1],
                    line[x * 4 + 2],
                    line[x * 4 + 3],
                ),
            };
            image.rgba[di] = r;
            image.rgba[di + 1] = g;
            image.rgba[di + 2] = b;
            image.rgba[di + 3] = a;
        }
    }
    Ok(image)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a PNG with the given IHDR parameters and raw (unfiltered) scanlines.
    fn build_png(
        width: u32,
        height: u32,
        colour: u8,
        rows: &[Vec<u8>],
        palette: Option<&[[u8; 3]]>,
    ) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&SIGNATURE);

        let mut ihdr = Vec::new();
        ihdr.extend_from_slice(&width.to_be_bytes());
        ihdr.extend_from_slice(&height.to_be_bytes());
        ihdr.push(8); // depth
        ihdr.push(colour);
        ihdr.push(0); // compression
        ihdr.push(0); // filter
        ihdr.push(0); // interlace
        push_chunk(&mut out, b"IHDR", &ihdr);

        if let Some(p) = palette {
            let mut body = Vec::new();
            for c in p {
                body.extend_from_slice(c);
            }
            push_chunk(&mut out, b"PLTE", &body);
        }

        // Filter byte 0 then the row.
        let mut raw = Vec::new();
        for row in rows {
            raw.push(0);
            raw.extend_from_slice(row);
        }
        let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::fast());
        std::io::Write::write_all(&mut enc, &raw).unwrap();
        let compressed = enc.finish().unwrap();
        push_chunk(&mut out, b"IDAT", &compressed);
        push_chunk(&mut out, b"IEND", &[]);
        out
    }

    fn push_chunk(out: &mut Vec<u8>, kind: &[u8; 4], body: &[u8]) {
        out.extend_from_slice(&(body.len() as u32).to_be_bytes());
        out.extend_from_slice(kind);
        out.extend_from_slice(body);
        out.extend_from_slice(&[0, 0, 0, 0]); // CRC placeholder
    }



    #[test]
    fn decode_any_still_handles_png() {
        let rows = vec![vec![10, 20, 30, 255]];
        let png = build_png(1, 1, 6, &rows, None);
        let image = decode_any(&png).expect("png still decodes");
        assert_eq!(image.pixel(0, 0), [10, 20, 30, 255]);
    }



    #[test]
    fn decodes_an_rgba_image() {
        let rows = vec![
            vec![255, 0, 0, 255, 0, 255, 0, 128],
            vec![0, 0, 255, 255, 10, 20, 30, 40],
        ];
        let png = build_png(2, 2, 6, &rows, None);
        let img = decode(&png).unwrap();
        assert_eq!((img.width, img.height), (2, 2));
        assert_eq!(img.pixel(0, 0), [255, 0, 0, 255]);
        assert_eq!(img.pixel(1, 0), [0, 255, 0, 128]);
        assert_eq!(img.pixel(0, 1), [0, 0, 255, 255]);
        assert_eq!(img.pixel(1, 1), [10, 20, 30, 40]);
    }

    #[test]
    fn decodes_an_rgb_image_as_opaque() {
        let rows = vec![vec![1, 2, 3, 4, 5, 6]];
        let png = build_png(2, 1, 2, &rows, None);
        let img = decode(&png).unwrap();
        assert_eq!(img.pixel(0, 0), [1, 2, 3, 255]);
        assert_eq!(img.pixel(1, 0), [4, 5, 6, 255]);
    }

    #[test]
    fn decodes_a_palette_image() {
        let palette = [[10u8, 20, 30], [40, 50, 60]];
        let rows = vec![vec![1, 0]];
        let png = build_png(2, 1, 3, &rows, Some(&palette));
        let img = decode(&png).unwrap();
        assert_eq!(img.pixel(0, 0), [40, 50, 60, 255]);
        assert_eq!(img.pixel(1, 0), [10, 20, 30, 255]);
    }

    #[test]
    fn decodes_a_greyscale_alpha_image() {
        let rows = vec![vec![100, 255, 50, 0]];
        let png = build_png(2, 1, 4, &rows, None);
        let img = decode(&png).unwrap();
        assert_eq!(img.pixel(0, 0), [100, 100, 100, 255]);
        assert_eq!(img.pixel(1, 0), [50, 50, 50, 0]);
    }

    #[test]
    fn rejects_a_non_png() {
        assert!(decode(b"not a png at all").is_err());
    }

    #[test]
    fn rejects_an_interlaced_png() {
        let mut png = build_png(1, 1, 2, &[vec![1, 2, 3]], None);
        // IHDR body starts at 8 (sig) + 8 (len+kind) = 16; interlace is body[12].
        png[16 + 12] = 1;
        let error = decode(&png).unwrap_err().0;
        assert!(error.contains("interlaced"), "got: {error}");
    }

    #[test]
    fn rejects_an_unsupported_bit_depth() {
        let mut png = build_png(1, 1, 2, &[vec![1, 2, 3]], None);
        png[16 + 8] = 16; // depth
        let error = decode(&png).unwrap_err().0;
        assert!(error.contains("bit depth"), "got: {error}");
    }

    #[test]
    fn a_blit_copies_within_bounds_and_clips_outside() {
        let mut src = Image::new(2, 2);
        for i in 0..4 {
            src.rgba[i * 4] = 200;
            src.rgba[i * 4 + 3] = 255;
        }
        let mut dst = Image::new(4, 4);
        // Fully inside.
        src.blit(&mut dst, 0, 0, 2, 2, 1, 1, false);
        assert_eq!(dst.pixel(1, 1), [200, 0, 0, 255]);
        assert_eq!(dst.pixel(2, 2), [200, 0, 0, 255]);
        // Partly outside: must clip, not panic or wrap.
        src.blit(&mut dst, 0, 0, 2, 2, 3, 3, false);
        assert_eq!(dst.pixel(3, 3), [200, 0, 0, 255]);
        // Entirely outside: no-op.
        src.blit(&mut dst, 0, 0, 2, 2, -10, -10, false);
    }

    #[test]
    fn a_blended_blit_mixes_alpha() {
        let mut src = Image::new(1, 1);
        src.rgba = vec![255, 255, 255, 128];
        let mut dst = Image::new(1, 1);
        dst.rgba = vec![0, 0, 0, 255];
        src.blit(&mut dst, 0, 0, 1, 1, 0, 0, true);
        // 255*128/255 + 0*(127)/255 = 128
        assert_eq!(dst.pixel(0, 0)[0], 128);
        assert_eq!(dst.pixel(0, 0)[3], 255);
    }

    #[test]
    fn a_transparent_source_pixel_does_not_erase_the_destination() {
        let mut src = Image::new(1, 1);
        src.rgba = vec![255, 0, 0, 0];
        let mut dst = Image::new(1, 1);
        dst.rgba = vec![1, 2, 3, 255];
        src.blit(&mut dst, 0, 0, 1, 1, 0, 0, true);
        assert_eq!(dst.pixel(0, 0), [1, 2, 3, 255]);
    }
}
