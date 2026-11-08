//! Map zoom centre and percentage interpolation, sub_A04410 / sub_A04870.
#[derive(Debug, Clone)]
pub struct MapTransform { from: [i32; 3], target: [i32; 3], current: [i32; 3], remaining: i32, total: i32 }
impl Default for MapTransform {
    fn default() -> Self { Self { from: [0,0,100], target: [0,0,100], current: [0,0,100], remaining:0,total:0 } }
}
impl MapTransform {
    pub fn configure(&mut self, x:i32,y:i32,zoom:i32,frames:i32) {
        self.from = if self.current[2] == 100 { [x,y,100] } else { self.current };
        self.target=[x,y,zoom.max(1)]; self.total=frames; self.remaining=frames;
        if frames<=0 {self.current=self.target;}
    }
    pub fn tick(&mut self) {
        if self.remaining>0 {self.remaining-=1;}
        if self.remaining<=0 {self.current=self.target; return;}
        for i in 0..3 {self.current[i]=(i64::from(self.target[i])-i64::from(self.remaining)*(i64::from(self.target[i])-i64::from(self.from[i]))/i64::from(self.total.max(1))) as i32;}
    }
    pub fn values(&self)->[i32;3] {self.current}
}
#[cfg(test)] mod tests {
 use super::*;
 #[test] fn zoom_interpolates_and_retarget_preserves_current_value() {
  let mut t=MapTransform::default(); t.configure(400,145,200,10);
  t.tick(); assert_eq!(t.values(),[400,145,110]);
  for _ in 0..9 {t.tick();} assert_eq!(t.values(),[400,145,200]);
  t.configure(200,100,100,10); t.tick(); assert_eq!(t.values(),[380,140,190]);
 }
 #[test] fn immediate_and_neutral_zoom() {
  let mut t=MapTransform::default(); t.configure(400,145,100,10);
  for _ in 0..10 {t.tick();assert_eq!(t.values()[2],100);}
  t.configure(0,0,0,0); assert_eq!(t.values(),[0,0,1]);
 }
}
