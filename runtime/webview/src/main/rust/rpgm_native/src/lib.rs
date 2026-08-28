//! RPG Maker native bridge (`librpgm_native`).

mod async_pool;
mod codec;
mod jni_bridge;

pub use async_pool::{decode_asset_async, read_file_async};
pub use codec::{decode_asset, list_files_recursive, read_file_fully, DecodeError, IndexedPath, KEY_SIZE};
