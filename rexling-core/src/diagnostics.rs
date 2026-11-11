//! Opt-in metadata diagnostics. Never pass input, keys, credentials or frame bodies.
use std::{
    collections::VecDeque,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};
static ENABLED: AtomicBool = AtomicBool::new(false);
static FILE: OnceLock<Mutex<Option<std::path::PathBuf>>> = OnceLock::new();
static LOG: OnceLock<Mutex<VecDeque<String>>> = OnceLock::new();
pub fn set_file(path: Option<std::path::PathBuf>) {
    if let Ok(mut file) = FILE.get_or_init(Default::default).lock() {
        *file = path;
    }
}
pub fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}
pub fn set_enabled(value: bool) {
    ENABLED.store(value, Ordering::Relaxed);
    if value {
        record("develop", "enabled");
    }
}
pub fn record(category: &str, message: impl AsRef<str>) {
    if !enabled() {
        return;
    }
    if let Ok(mut entries) = LOG.get_or_init(Default::default).lock() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis();
        let line = format!(
            "{stamp} native {category} {}",
            message.as_ref().chars().take(1000).collect::<String>()
        );
        if let Ok(file) = FILE.get_or_init(Default::default).lock() {
            if let Some(path) = file.as_ref() {
                use std::io::Write;
                if path.metadata().is_ok_and(|m| m.len() > 2 * 1024 * 1024) {
                    let _ = std::fs::rename(path, path.with_extension("previous.log"));
                }
                if let Ok(mut output) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(path)
                {
                    let _ = writeln!(output, "{line}");
                }
            }
        }
        entries.push_back(line);
        while entries.len() > 4000 {
            entries.pop_front();
        }
    }
}
pub fn snapshot() -> String {
    LOG.get_or_init(Default::default)
        .lock()
        .map(|log| log.iter().cloned().collect::<Vec<_>>().join("\n"))
        .unwrap_or_default()
}
