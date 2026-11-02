#![cfg(feature = "ghostty")]
use rex_probe::ghostty::Replica;
use serde_json::Value;

#[test]
fn replays_real_rex_streams_without_a_server() {
    for (stream, metadata) in [
        (
            include_bytes!("../evidence/ghostty-local.bin").as_slice(),
            include_str!("../evidence/ghostty-local.json"),
        ),
        (
            include_bytes!("../evidence/ghostty-remote.bin").as_slice(),
            include_str!("../evidence/ghostty-remote.json"),
        ),
    ] {
        let expected: Value = serde_json::from_str(metadata).unwrap();
        assert_eq!(
            stream.len() as u64,
            expected["stream_bytes"].as_u64().unwrap()
        );
        for chunk_size in [1, 7, 131] {
            let mut replica = Replica::new(expected["nonce"].as_u64().unwrap());
            for chunk in stream.chunks(chunk_size) {
                replica.feed(chunk).unwrap();
            }
            assert_eq!(
                replica.snapshot_bytes as u64,
                expected["snapshot_bytes"].as_u64().unwrap()
            );
            let frame = replica.terminal().unwrap().frame().unwrap();
            assert_eq!((frame.columns, frame.rows), (93, 31));
            assert_eq!(frame.background, [17, 34, 51]);
            assert!(frame.text().contains("λ🦀"));
            assert!(frame.text().contains("REPLY:during-gap"));
            let green = frame.cells.iter().find(|c| c.text == "G").unwrap();
            assert_eq!(green.foreground, [12, 200, 90]);
            assert_ne!(green.attributes & 1, 0);
        }
    }
}
