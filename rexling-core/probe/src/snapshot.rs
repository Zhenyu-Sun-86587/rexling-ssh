//! Locate a complete version-1 Ghostty snapshot without interpreting its payload.
//! libghostty validates every record and its CRC when restoring it.
use anyhow::{Result, ensure};

const LIMIT: usize = 128 * 1024 * 1024;
#[derive(Default)]
pub struct SnapshotBuffer {
    bytes: Vec<u8>,
    offset: usize,
    complete: bool,
}
impl SnapshotBuffer {
    pub fn push(&mut self, chunk: &[u8]) -> Result<Option<(Vec<u8>, Vec<u8>)>> {
        ensure!(!self.complete, "snapshot already complete");
        ensure!(
            self.bytes
                .len()
                .checked_add(chunk.len())
                .is_some_and(|n| n <= LIMIT),
            "snapshot exceeds 128 MiB"
        );
        self.bytes.extend_from_slice(chunk);
        if self.offset == 0 {
            if self.bytes.len() < 10 {
                return Ok(None);
            }
            ensure!(
                &self.bytes[..8] == b"GHOSTSNP",
                "invalid Ghostty snapshot signature"
            );
            ensure!(
                u16::from_le_bytes(self.bytes[8..10].try_into()?) == 1,
                "unsupported Ghostty snapshot version"
            );
            self.offset = 10;
        }
        while self.bytes.len() - self.offset >= 10 {
            let header = &self.bytes[self.offset..self.offset + 10];
            let tag = u16::from_le_bytes(header[..2].try_into()?);
            let len = u32::from_le_bytes(header[2..6].try_into()?) as usize;
            let end = self
                .offset
                .checked_add(10)
                .and_then(|n| n.checked_add(len))
                .filter(|n| *n <= LIMIT)
                .ok_or_else(|| anyhow::anyhow!("snapshot record exceeds limit"))?;
            if end > self.bytes.len() {
                return Ok(None);
            }
            self.offset = end;
            if tag == 6 {
                ensure!(len == 0, "Ghostty FINISH must be empty");
                self.complete = true;
                let tail = self.bytes.split_off(end);
                return Ok(Some((std::mem::take(&mut self.bytes), tail)));
            }
        }
        Ok(None)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn framing_survives_every_split_and_preserves_trailing_vt() {
        let mut bytes = b"GHOSTSNP\x01\0".to_vec();
        bytes.extend([1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 9, 8, 7]);
        bytes.extend([6, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        let snapshot = bytes.clone();
        bytes.extend(b"\x1b_rex1;42;{}\x1b\\live");
        for split in 0..snapshot.len() {
            let mut parser = SnapshotBuffer::default();
            assert!(parser.push(&bytes[..split]).unwrap().is_none());
            let (got, tail) = parser.push(&bytes[split..]).unwrap().unwrap();
            assert_eq!(got, snapshot);
            assert_eq!(tail, b"\x1b_rex1;42;{}\x1b\\live");
        }
    }
    #[test]
    fn rejects_unknown_versions_and_oversized_records() {
        assert!(SnapshotBuffer::default().push(b"GHOSTSNP\x02\0").is_err());
        assert!(
            SnapshotBuffer::default()
                .push(b"GHOSTSNP\x01\0\x01\0\xff\xff\xff\xff\0\0\0\0")
                .is_err()
        );
    }
}
