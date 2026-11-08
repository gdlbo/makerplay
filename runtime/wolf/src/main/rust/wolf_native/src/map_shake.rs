//! Map shake from sub_A041E0, sub_A04290 and sub_A04870.
#[derive(Default, Debug, Clone, Copy)]
struct Axis { power: i32, speed: i32, remaining: i32, direction: i32, offset: i32 }
#[derive(Default, Debug)]
pub struct MapShake { axes: [Axis; 2] }
impl MapShake {
    pub fn configure(&mut self, flags: i32, duration: i32) -> Result<(), String> {
        let mode = (flags >> 8) & 15;
        match mode {
            0 | 1 => {
                // Dispatcher mode 0 selects the second axis; mode 1 the first.
                let axis = &mut self.axes[if mode == 0 { 1 } else { 0 }];
                axis.power = 1 << (flags & 15);
                axis.speed = 1 << ((flags >> 4) & 15);
                axis.remaining = duration.wrapping_add(1);
                axis.direction = 1;
            }
            2 => { for axis in &mut self.axes { axis.remaining = 0; } }
            _ => return Err(format!("unsupported map shake mode {mode}")),
        }
        Ok(())
    }
    pub fn tick(&mut self, fps: i32) {
        for axis in &mut self.axes {
            if axis.remaining <= 0 && axis.offset == 0 { continue; }
            let delta = axis.direction.wrapping_mul(axis.power).wrapping_mul(axis.speed)
                .wrapping_shl(6) / fps.max(1) / 10;
            if axis.remaining > 1 || i64::from(axis.offset) * i64::from(axis.offset.wrapping_add(delta)) > 0 {
                axis.offset = axis.offset.wrapping_add(delta);
            } else { axis.offset = 0; }
            if axis.offset > axis.power * 128 { axis.direction = -1; }
            if axis.offset < -128 * axis.power { axis.direction = 1; }
            if (1..100000).contains(&axis.remaining) { axis.remaining -= 1; }
        }
    }
    pub fn pixels(&self, tile_size: i32) -> (i32, i32) {
        let pixel = |axis: &Axis| (i64::from(axis.offset) * i64::from(tile_size) / 2048) as i32;
        (pixel(&self.axes[0]), pixel(&self.axes[1]))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shake_advances_only_selected_axis_then_settles() {
        let mut shake = MapShake::default();
        shake.configure(0x32, 5).unwrap();
        shake.tick(60);
        assert_eq!(shake.axes[1].offset, 3); // (4*8*64)/60/10
        assert_eq!(shake.axes[0].offset, 0);
        for _ in 0..1000 { shake.tick(60); }
        assert_eq!(shake.pixels(40), (0, 0));
        assert_eq!(shake.axes[1].offset, 0);
    }
    #[test]
    fn stop_settles_both_axes_without_overwriting_their_offsets() {
        let mut shake = MapShake::default();
        shake.configure(0x144, 100000).unwrap();
        for _ in 0..20 { shake.tick(60); }
        assert_ne!(shake.pixels(40).0, 0);
        let offset = shake.axes[0].offset;
        shake.configure(0x200, 0).unwrap();
        assert_eq!(shake.axes[0].offset, offset);
        for _ in 0..1000 { shake.tick(60); }
        assert_eq!(shake.pixels(40), (0, 0));
    }
}
