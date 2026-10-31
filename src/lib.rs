//! Direct Rex client with optional native Ghostty terminal state; no Rex subprocesses.
pub mod control;
pub mod data;
#[cfg(feature = "ghostty")]
pub mod ghostty;
pub mod snapshot;
pub mod transport;
