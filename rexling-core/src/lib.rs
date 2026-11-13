//! Direct Rex transport and native Ghostty replica used by Rexling.
pub mod control;
pub mod data;
pub mod ghostty;
pub mod snapshot;
pub mod transport;
pub mod ssh;

pub mod upload;

pub mod diagnostics;
mod socket_driver;
pub mod graphics;
