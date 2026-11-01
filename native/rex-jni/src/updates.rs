//! Local JNI format, not Rex wire protocol. One decoder per native handle.
use crate::engine::Shared;
use rex_core::ghostty::{Cell, Frame};
use std::sync::{Arc, Condvar, Mutex};

#[derive(Default)]
pub struct Updates {
    state: Mutex<(u64, bool)>,
    ready: Condvar,
}
impl Updates {
    pub fn publish(&self, revision: u64) {
        if let Ok(mut state) = self.state.lock() {
            state.0 = revision;
            self.ready.notify_all();
        }
    }
    pub fn close(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.1 = true;
            self.ready.notify_all();
        }
    }
    pub fn wait(&self, after: u64, ms: u64) -> i64 {
        let Ok(state) = self.state.lock() else {
            return -1;
        };
        let Ok((state, _)) =
            self.ready
                .wait_timeout_while(state, std::time::Duration::from_millis(ms), |s| {
                    !s.1 && s.0 <= after
                })
        else {
            return -1;
        };
        if state.1 { -1 } else { state.0 as i64 }
    }
}
#[derive(Default)]
pub struct WireBase {
    revision: u64,
    metadata: String,
    key: String,
    frame: Option<Arc<Frame>>,
}
fn u16le(out: &mut Vec<u8>, v: u16) {
    out.extend(v.to_le_bytes());
}
fn u32le(out: &mut Vec<u8>, v: u32) {
    out.extend(v.to_le_bytes());
}
fn u64le(out: &mut Vec<u8>, v: u64) {
    out.extend(v.to_le_bytes());
}
fn text(out: &mut Vec<u8>, v: &str) {
    u32le(out, v.len() as u32);
    out.extend(v.as_bytes());
}
fn rgb(v: [u8; 3]) -> u32 {
    0xff000000 | ((v[0] as u32) << 16) | ((v[1] as u32) << 8) | v[2] as u32
}
fn rows(frame: &Frame) -> Vec<&[Cell]> {
    let mut result = Vec::with_capacity(frame.rows as usize);
    let mut start = 0;
    for y in 0..frame.rows {
        let end = start + frame.cells[start..].iter().take_while(|c| c.y == y).count();
        result.push(&frame.cells[start..end]);
        start = end;
    }
    result
}
pub fn encode(state: &mut Shared, after: u64) -> Vec<u8> {
    if after >= state.revision {
        return Vec::new();
    }
    let base = &state.wire;
    let reset = after != base.revision || base.key != state.key;
    let mut out = b"RXT2".to_vec();
    u64le(&mut out, state.revision);
    let previews = std::mem::take(&mut state.pending_previews);
    if reset || base.metadata != state.json || !previews.is_empty() {
        let mut metadata: serde_json::Value =
            serde_json::from_str(&state.json).expect("internal metadata JSON");
        if !previews.is_empty() {
            metadata["previews"] = serde_json::to_value(previews).expect("preview frames");
        }
        text(&mut out, &metadata.to_string());
    } else {
        text(&mut out, "");
    }
    match &state.frame {
        None => out.push(1),
        Some(frame)
            if !reset
                && base
                    .frame
                    .as_ref()
                    .is_some_and(|old| Arc::ptr_eq(old, frame)) =>
        {
            out.push(0)
        }
        Some(frame) => {
            let previous = base
                .frame
                .as_ref()
                .filter(|old| !reset && old.columns == frame.columns && old.rows == frame.rows);
            out.push(if previous.is_some() { 2 } else { 3 });
            u16le(&mut out, frame.columns);
            u16le(&mut out, frame.rows);
            let (x, y) = frame
                .cursor
                .map_or((-1, -1), |(x, y)| (i32::from(x), i32::from(y)));
            out.extend(x.to_le_bytes());
            out.extend(y.to_le_bytes());
            u32le(&mut out, rgb(frame.foreground));
            u32le(&mut out, rgb(frame.background));
            for v in [
                frame.scroll_total,
                frame.scroll_offset,
                frame.scroll_visible,
            ] {
                u64le(&mut out, v);
            }
            out.push(frame.input_flags);
            let next = rows(frame);
            let old = previous.map(|old| rows(old));
            let changed: Vec<_> = next
                .iter()
                .enumerate()
                .filter(|(y, row)| old.as_ref().is_none_or(|old| old[*y] != **row))
                .collect();
            u16le(&mut out, changed.len() as u16);
            for (y, row) in changed {
                u16le(&mut out, y as u16);
                u16le(&mut out, row.len() as u16);
                for cell in *row {
                    u16le(&mut out, cell.x);
                    out.push(cell.width);
                    u32le(&mut out, cell.attributes);
                    u32le(&mut out, rgb(cell.foreground));
                    u32le(&mut out, rgb(cell.background));
                    text(&mut out, &cell.text);
                }
            }
        }
    }
    state.wire = WireBase {
        revision: state.revision,
        metadata: state.json.clone(),
        key: state.key.clone(),
        frame: state.frame.clone(),
    };
    out
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn notification_wakes_for_updates_and_shutdown() {
        let updates = Arc::new(Updates::default());
        let writer = updates.clone();
        let thread = std::thread::spawn(move || {
            writer.publish(4);
        });
        assert_eq!(updates.wait(0, 1000), 4);
        thread.join().unwrap();
        assert_eq!(updates.wait(4, 1), 4);
        updates.close();
        assert_eq!(updates.wait(4, 1), -1);
    }
}

#[cfg(test)]
mod wire_tests {
    use super::*;
    use std::collections::BTreeMap;
    fn frame() -> Frame {
        Frame {
            columns: 100,
            rows: 50,
            cursor: Some((0, 0)),
            foreground: [255; 3],
            background: [0; 3],
            cells: (0..50)
                .flat_map(|y| {
                    (0..100).map(move |x| Cell {
                        x,
                        y,
                        text: "x".into(),
                        foreground: [255; 3],
                        background: [0; 3],
                        attributes: 0,
                        width: 1,
                    })
                })
                .collect(),
            scroll_total: 50,
            scroll_offset: 0,
            scroll_visible: 50,
            input_flags: 0,
        }
    }
    #[test]
    fn packets_diff_against_delivered_frame_and_reset_on_unknown_base() {
        let mut state = Shared {
            revision: 1,
            json: "{}".into(),
            pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(frame())),
            key: "a".into(),
            wire: WireBase::default(),
        };
        let full = encode(&mut state, 0);
        assert_eq!(full[18], 3);
        let mut next = frame();
        next.cells[200].text = "λ🦀".into();
        state.frame = Some(Arc::new(next));
        state.revision = 3;
        let delta = encode(&mut state, 1);
        assert_eq!(delta[16], 2);
        assert!(delta.len() < full.len() / 20);
        state.revision = 4;
        let reset = encode(&mut state, 1);
        assert_eq!(reset[18], 3);
        state.key = "other host pane".into();
        state.revision = 5;
        assert_eq!(encode(&mut state, 4)[18], 3);
        assert!(encode(&mut state, 5).is_empty());
        println!(
            "wire payload bytes: full={} one-row delta={}",
            full.len(),
            delta.len()
        );
    }
    #[test]
    fn a_closed_notifier_wakes_a_blocked_reader() {
        let updates = Arc::new(Updates::default());
        let reader = updates.clone();
        let thread = std::thread::spawn(move || reader.wait(0, 1000));
        updates.close();
        assert_eq!(thread.join().unwrap(), -1);
    }
}
