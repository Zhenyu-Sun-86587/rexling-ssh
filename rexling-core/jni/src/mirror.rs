//! Live mirrors of a tab's other panes, for the multi-pane layout.
//!
//! The selected pane keeps the actor's own stream, input, theme and size logic untouched. This module
//! only adds streams for the remaining tiled panes Android asks to see beside it, owns them so each
//! can be sized to its share of the screen, and turns their output into frames on the existing
//! per-pane frame channel. With no panes requested it holds nothing, and the engine is exactly the
//! single-pane engine.
use anyhow::{Context, Result};
use futures_util::future::select_all;
use rex_core::{
    control::{Control, Trace},
    data::Data,
    ghostty::{Frame, Replica},
    transport::Endpoint,
};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};

/// More panes than this are left to the single-pane carousel.
pub const MAX_PANES: usize = 3;
/// Mirrors redraw at most this often, below the selected pane's frame rate.
const FRAME_INTERVAL: Duration = Duration::from_millis(33);

/// One requested pane: its block and the grid that fits its share of the screen.
#[derive(Clone, Debug, PartialEq)]
pub struct Wanted {
    pub block: String,
    pub columns: u16,
    pub rows: u16,
}

struct Pane {
    block: String,
    data: Data,
    replica: Replica,
    size: Option<(u16, u16)>,
    dirty: bool,
}

#[derive(Default)]
pub struct Mirror {
    session: String,
    wanted: Vec<Wanted>,
    panes: Vec<Pane>,
    drawn_at: Option<tokio::time::Instant>,
}

/// Parses Android's `mirror` command: `{session, panes: [{block, columns, rows}]}`.
pub fn parse(value: &Value) -> (String, Vec<Wanted>) {
    let session = value["session"].as_str().unwrap_or("").to_owned();
    let mut wanted: Vec<Wanted> = Vec::new();
    for pane in value["panes"].as_array().into_iter().flatten() {
        let Some(block) = pane["block"].as_str().filter(|b| !b.is_empty()) else { continue };
        if wanted.iter().any(|w| w.block == block) {
            continue;
        }
        let size = |key: &str| pane[key].as_u64().unwrap_or(0).clamp(1, 512) as u16;
        wanted.push(Wanted { block: block.to_owned(), columns: size("columns"), rows: size("rows").min(256) });
        if wanted.len() == MAX_PANES {
            break;
        }
    }
    (session, wanted)
}

impl Mirror {
    pub fn is_active(&self) -> bool {
        !self.panes.is_empty()
    }
    /// Hands a mirrored pane's stream and state to the actor when it becomes the selected pane.
    /// Rex allows one data connection per pane per client, so the stream moves rather than reopens.
    pub fn take(&mut self, block: &str) -> Option<(Data, Replica)> {
        let index = self.panes.iter().position(|p| p.block == block)?;
        let pane = self.panes.remove(index);
        Some((pane.data, pane.replica))
    }
    /// Takes over the stream of the pane the actor is leaving. The next [Mirror::sync] closes it if
    /// Android does not ask for it; its size is set again there.
    pub fn adopt(&mut self, block: String, data: Data, replica: Replica) {
        if self.panes.iter().any(|p| p.block == block) {
            return;
        }
        self.panes.push(Pane { block, data, replica, size: None, dirty: true });
    }
    /// The next chunk from any mirrored pane. Pending forever with none, so it can sit in a select.
    pub async fn read(&mut self) -> (usize, Result<Vec<u8>>) {
        if self.panes.is_empty() {
            return std::future::pending().await;
        }
        let reads = self.panes.iter_mut().map(|p| Box::pin(p.data.read()));
        let (result, index, _) = select_all(reads).await;
        (index, result)
    }
    /// Feeds a chunk; a pane whose stream or state fails is dropped rather than shown stale.
    pub fn feed(&mut self, index: usize, chunk: Result<Vec<u8>>) {
        let Some(pane) = self.panes.get_mut(index) else { return };
        let ok = chunk.and_then(|bytes| pane.replica.feed(&bytes)).is_ok();
        // Mirrors never replay clipboard writes or status events; the control stream reports status.
        pane.replica.events.clear();
        pane.replica.clipboard_write = None;
        if ok {
            pane.dirty = true;
        } else {
            self.panes.remove(index);
        }
    }
    /// Whether a frame is waiting, and the earliest moment it may be drawn.
    pub fn due(&self) -> Option<tokio::time::Instant> {
        self.panes.iter().any(|p| p.dirty).then(|| {
            self.drawn_at.map_or_else(tokio::time::Instant::now, |at| at + FRAME_INTERVAL)
        })
    }
    /// Frames for panes that changed since the last call, once the frame interval allows it.
    pub fn frames(&mut self) -> Vec<(String, Arc<Frame>)> {
        let now = tokio::time::Instant::now();
        if self.due().is_none_or(|at| at > now) {
            return Vec::new();
        }
        self.drawn_at = Some(now);
        let mut frames = Vec::new();
        for pane in self.panes.iter_mut().filter(|p| p.dirty && p.replica.ready()) {
            pane.dirty = false;
            if let Ok(terminal) = pane.replica.terminal()
                && terminal.render_ready().unwrap_or(false)
                && let Ok(frame) = terminal.frame()
            {
                frames.push((pane.block.clone(), Arc::new(frame)));
            }
        }
        frames
    }
    /// Brings the open streams, ownership and sizes in line with what Android asked for. The
    /// selected pane is never mirrored: it already has the actor's own stream.
    pub async fn sync(
        &mut self,
        endpoint: &Endpoint,
        control: &mut Control,
        session: &str,
        selected: &str,
        wanted: Vec<Wanted>,
    ) -> Result<()> {
        // Panes from another session are dropped below: block IDs are unique across sessions.
        let wanted: Vec<Wanted> = if session.is_empty() {
            Vec::new()
        } else {
            wanted.into_iter().filter(|w| w.block != selected).collect()
        };
        // Give back panes no longer shown, unless the actor now selects them.
        let dropped: Vec<String> = self
            .panes
            .iter()
            .filter(|p| !wanted.iter().any(|w| w.block == p.block))
            .map(|p| p.block.clone())
            .collect();
        for block in &dropped {
            if let Some(index) = self.panes.iter().position(|p| &p.block == block) {
                let mut pane = self.panes.remove(index);
                let _ = pane.data.close().await;
            }
        }
        let release: Vec<&String> = dropped.iter().filter(|b| b.as_str() != selected).collect();
        if !release.is_empty() && !self.session.is_empty() {
            let _ = control
                .call("session.disown_block", json!({"session_id": self.session, "block_ids": release}))
                .await;
        }
        self.session = if wanted.is_empty() { String::new() } else { session.to_owned() };
        self.wanted = wanted.clone();
        if wanted.is_empty() {
            return Ok(());
        }
        let ids: Vec<&str> = wanted.iter().map(|w| w.block.as_str()).collect();
        control
            .call("session.own_block", json!({"session_id": session, "block_ids": ids, "mode": "take"}))
            .await?;
        for want in &wanted {
            if !self.panes.iter().any(|p| p.block == want.block) {
                let data = open(endpoint, control, session, &want.block)
                    .await
                    .with_context(|| format!("could not mirror {}", want.block))?;
                let replica = Replica::new(data.nonce);
                self.panes.push(Pane { block: want.block.clone(), data, replica, size: None, dirty: false });
            }
            let pane = self.panes.iter_mut().find(|p| p.block == want.block).context("mirror pane missing")?;
            if pane.size != Some((want.columns, want.rows)) {
                control
                    .terminal(session, &want.block, "resize", json!({"columns": want.columns, "rows": want.rows}))
                    .await?;
                pane.size = Some((want.columns, want.rows));
            }
        }
        // Keep Android's order, so frames and layout agree on which pane is which.
        self.panes.sort_by_key(|p| wanted.iter().position(|w| w.block == p.block));
        Ok(())
    }
    /// What Android last asked for in [session], to restore after a reconnect.
    pub fn wanted(&self) -> (String, Vec<Wanted>) {
        (self.session.clone(), self.wanted.clone())
    }
    /// Drops every stream without RPCs: for a lost connection, whose ownership the server already ended.
    pub fn clear(&mut self) {
        self.panes.clear();
        self.drawn_at = None;
    }
}

/// Opens a pane's stream, retrying once if a just-closed stream for it is still being torn down
/// (REJECT 0x02: one data connection per pane per client).
async fn open(endpoint: &Endpoint, control: &mut Control, session: &str, block: &str) -> Result<Data> {
    match Data::open(endpoint, control, session, block, Trace::default()).await {
        Err(error) if error.to_string().contains("reason 0x02") => {
            tokio::time::sleep(Duration::from_millis(300)).await;
            Data::open(endpoint, control, session, block, Trace::default()).await
        }
        result => result,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_keeps_order_drops_duplicates_and_caps_panes() {
        let (session, wanted) = parse(&json!({"session": "session:a", "panes": [
            {"block": "b1", "columns": 80, "rows": 24},
            {"block": "b1", "columns": 10, "rows": 10},
            {"block": "", "columns": 10, "rows": 10},
            {"block": "b2", "columns": 9999, "rows": 9999},
            {"block": "b3", "columns": 0, "rows": 0},
            {"block": "b4", "columns": 40, "rows": 12},
        ]}));
        assert_eq!(session, "session:a");
        assert_eq!(
            wanted,
            vec![
                Wanted { block: "b1".into(), columns: 80, rows: 24 },
                Wanted { block: "b2".into(), columns: 512, rows: 256 },
                Wanted { block: "b3".into(), columns: 1, rows: 1 },
            ]
        );
    }

    #[test]
    fn empty_mirror_has_nothing_due_and_no_frames() {
        let mut mirror = Mirror::default();
        assert!(mirror.due().is_none());
        assert!(mirror.frames().is_empty());
        assert_eq!(mirror.wanted(), (String::new(), Vec::new()));
    }
}
