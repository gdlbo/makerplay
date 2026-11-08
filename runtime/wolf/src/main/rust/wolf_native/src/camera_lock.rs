//! Opcode281 lock bits from sub_A2FC10. Movement timing is separate.
#[derive(Default,Debug)]
pub struct CameraLock { mask:u8, last:Option<(i32,i32)> }
impl CameraLock {
 pub fn command(&mut self,op:i32)->Result<(),String> {
  self.mask=match op {2=>3,3=>0,4=>self.mask|1,5=>self.mask|2,_=>return Err(format!("unsupported camera lock operation {op}"))};
  Ok(())
 }
 pub fn apply(&mut self,x:i32,y:i32)->(i32,i32) {
  let (previous_x,previous_y)=self.last.unwrap_or((x,y));
  let result=(if self.mask&1!=0 {previous_x}else{x},if self.mask&2!=0 {previous_y}else{y});
  self.last=Some(result);result
 }
}
#[cfg(test)] mod tests {
 use super::*;
 #[test] fn axes_lock_independently_and_unlock_preserves_following() {
  let mut lock=CameraLock::default();assert_eq!(lock.apply(10,20),(10,20));
  lock.command(4).unwrap();assert_eq!(lock.apply(30,40),(10,40));
  lock.command(5).unwrap();assert_eq!(lock.apply(50,60),(10,40));
  lock.command(3).unwrap();assert_eq!(lock.apply(50,60),(50,60));
  lock.command(2).unwrap();assert_eq!(lock.apply(100,200),(50,60));
 }
 #[test] fn locking_before_first_camera_sample_uses_first_origin() {
  let mut lock=CameraLock::default();lock.command(2).unwrap();
  assert_eq!(lock.apply(50,60),(50,60));assert_eq!(lock.apply(70,80),(50,60));
  assert!(lock.command(0).is_err());assert_eq!(lock.apply(70,80),(50,60));
 }
}
