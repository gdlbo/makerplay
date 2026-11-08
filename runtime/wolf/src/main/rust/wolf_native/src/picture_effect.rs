//! Picture effect 5/6 colour pulse, sub_9A45F0, sub_997E20, sub_9988E0.
#[derive(Clone,Debug)]
pub struct ColourPulse { packed: [i32;3], duration:i32, phase:i64, saw:bool }
impl ColourPulse {
 pub fn new(raw:[i32;3],duration:i32,saw:bool)->Self {
  Self {packed:raw.map(|v|(v/2+100).clamp(0,200)),duration:duration.max(0),phase:0,saw}
 }
 pub fn tick(&mut self) {
  if self.duration>0 {
   let period=i64::from(self.duration)*if self.saw {1}else{2};
   self.phase=(self.phase+1)%period;
  }
 }
 pub fn offsets(&self)->[i32;3] {
  if self.duration<=0 {return [0;3];}
  let strength=if self.saw {i64::from(self.duration)-self.phase}else{(self.phase-i64::from(self.duration)).abs()};
  self.packed.map(|v| ((v-100) as f64*2.55*2.0*strength as f64/f64::from(self.duration)) as i32)
 }
}
#[cfg(test)] mod tests {
 use super::*;
 #[test] fn pulse_repeats_through_neutral_without_becoming_a_tween() {
  let mut pulse=ColourPulse::new([-50,-50,-50],60,false);
  assert_eq!(pulse.offsets(),[-127;3]);
  for _ in 0..60 {pulse.tick();} assert_eq!(pulse.offsets(),[0;3]);
  for _ in 0..60 {pulse.tick();} assert_eq!(pulse.offsets(),[-127;3]);
 }
 #[test] fn saw_restarts_and_zero_duration_is_neutral() {
  let mut p=ColourPulse::new([400,-400,1],2,true);
  assert_eq!(p.offsets(),[509,-509,0]);
  p.tick();assert_eq!(p.offsets(),[254,-254,0]);
  p.tick();assert_eq!(p.offsets(),[509,-509,0]);
  assert_eq!(ColourPulse::new([10;3],0,false).offsets(),[0;3]);
 }
}
