//! WOLF RPG data formats.
//!
//! A Rust port of the format layer whose behaviour is already pinned by the
//! Kotlin implementation and its tests (`core/wolfformat`), cross-checked against
//! the published descriptions in `djytw/wolf-rpg-formats` (MIT) and
//! `Sinflower/WolfTL` (MIT). The Rust side will own the frame buffer and the
//! event loop, so it needs to read these files without a JNI round trip per
//! field.
//!
//! Parsing is deliberately strict: every length is bounds-checked and trailing
//! bytes are accounted for, because a silent misparse would surface as wrong
//! graphics rather than as an error.

pub mod bounded;
pub mod probe;
pub mod common_event;
pub mod container;
pub mod database;
pub mod dbproject;
pub mod event_command;
pub mod gamedat;
pub mod lz4;
pub mod map;
pub mod maptree;
pub mod tileset;

pub use bounded::{BoundedReader, FormatError, Limits};
pub use container::WolfContainer;
pub use database::{DataBaseDat, INT_START, STRING_START};

pub(crate) use bounded::shift_jis;
