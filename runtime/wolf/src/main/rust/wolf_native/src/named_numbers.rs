//! MakerPlay native named numeric fields. Separate from original WOLF save files
//! and from full gameplay snapshots. Each field replaces one bounded record.
use std::path::{Path,PathBuf};
fn field_path(root:&Path,name:&str,field:i32)->Result<PathBuf,String> {
 let name=name.replace('\\',"/");
 if name.len()>100 || !name.starts_with("Save/") || name.split('/').any(|s|s.is_empty()||s=="."||s==".."||s.contains(':')) {
  return Err("invalid named save path".into());
 }
 if field<0 || (1_100_000..1_200_000).contains(&field)||(1_600_000..1_700_000).contains(&field) {
  return Err(format!("unsupported named numeric field {field}"));
 }
 let encoded=name.bytes().map(|b|format!("{b:02x}")).collect::<String>();
 Ok(root.join(encoded).join(format!("{field}.num")))
}
pub fn read(root:&Path,name:&str,field:i32)->Result<i32,String> {
 let path=field_path(root,name,field)?;
 let bytes=(|| -> Result<Vec<u8>,std::io::Error> {
  use std::io::Read;
  let mut bytes=Vec::new();
  std::fs::File::open(&path)?.take(9).read_to_end(&mut bytes)?;
  Ok(bytes)
 })();
 match bytes {
  Ok(bytes) if bytes.len()==8 && &bytes[..4]==b"WNF1" => Ok(i32::from_le_bytes(bytes[4..8].try_into().unwrap())),
  Ok(_)=>Err(format!("invalid numeric save record {}",path.display())),
  Err(e) if e.kind()==std::io::ErrorKind::NotFound=>Ok(0),
  Err(e)=>Err(format!("{}: {e}",path.display()))
 }
}
pub fn write(root:&Path,name:&str,field:i32,value:i32)->Result<(),String> {
 let path=field_path(root,name,field)?;
 let parent=path.parent().ok_or("missing save parent")?;
 std::fs::create_dir_all(parent).map_err(|e|e.to_string())?;
 let temporary=path.with_extension("pending");
 let mut bytes=b"WNF1".to_vec();bytes.extend_from_slice(&value.to_le_bytes());
 use std::io::Write;
 let mut file=std::fs::File::create(&temporary).map_err(|e|e.to_string())?;
 file.write_all(&bytes).and_then(|_|file.sync_all()).map_err(|e|e.to_string())?;
 drop(file);
 std::fs::rename(&temporary,&path).map_err(|e|e.to_string())
}
#[cfg(test)] mod tests {
 use super::*;
 #[test] fn records_survive_reopening_replace_independently_and_reject_corruption() {
  let root=std::env::temp_dir().join(format!("wolf-numeric-save-{}",std::process::id()));
  let _=std::fs::remove_dir_all(&root);
  assert_eq!(read(&root,"Save/System.sav",2_000_000).unwrap(),0);
  write(&root,"Save/System.sav",2_000_000,42).unwrap();
  write(&root,"Save/System.sav",2_000_001,-7).unwrap();
  write(&root,"Save/System.sav",2_000_000,99).unwrap();
  assert_eq!(read(&root,"Save/System.sav",2_000_000).unwrap(),99);
  assert_eq!(read(&root,"Save/System.sav",2_000_001).unwrap(),-7);
  std::fs::write(field_path(&root,"Save/System.sav",2_000_000).unwrap(),b"bad").unwrap();
  assert!(read(&root,"Save/System.sav",2_000_000).is_err());
  std::fs::remove_dir_all(root).unwrap();
 }
 #[test] fn paths_and_unsupported_fields_fail_before_writing() {
  for name in ["Save/../x","/Save/x","Save//x","Save/C:/x","x"] {assert!(field_path(Path::new("unused"),name,0).is_err());}
  assert!(field_path(Path::new("unused"),"Save/x",1_600_000).is_err());
 }
}
