use anyhow::{Context, Result, ensure};
use clap::{Parser, Subcommand};
use rex_probe::{
    control::{Control, Trace},
    data::Data,
    ghostty::Replica,
    transport::Endpoint,
};
use serde_json::{Value, json};
use std::{
    io::{IsTerminal, Write},
    path::PathBuf,
    time::Duration,
};

#[derive(Parser)]
#[command(about = "Native Ghostty terminal-replica proof for Rex")]
struct Cli {
    #[arg(short = 'S', long, env = "REX_SERVER")]
    server: Option<String>,
    #[command(subcommand)]
    command: Command,
}
#[derive(Subcommand)]
enum Command {
    /// Decode a captured terminal stream. Nonce comes from watch's data summary.
    Decode {
        file: PathBuf,
        #[arg(long)]
        nonce: u64,
    },
    /// Render the remote terminal, forward UTF-8 keyboard input; Ctrl-] detaches.
    View {
        session: String,
        block: String,
        #[arg(long)]
        take: bool,
    },
    /// Test Ghostty against a disposable terminal, then destroy only that session.
    SelfTest {
        /// Save only the disposable terminal's raw stream for offline replay.
        #[arg(long)]
        capture: Option<PathBuf>,
    },
}
#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    if let Command::Decode { file, nonce } = cli.command {
        let mut replica = Replica::new(nonce);
        for chunk in std::fs::read(file)?.chunks(137) {
            replica.feed(chunk)?;
        }
        print!("{}", replica.terminal()?.text()?);
        return Ok(());
    }
    let server = cli.server.context("pass --server or set REX_SERVER")?;
    let endpoint = Endpoint::new(&server, Duration::from_secs(10))?;
    let mut control = Control::connect(endpoint.clone(), None, Trace::default()).await?;
    let result = match cli.command {
        Command::View {
            session,
            block,
            take,
        } => view(&endpoint, &mut control, &session, &block, take).await,
        Command::SelfTest { capture } => self_test(&endpoint, &mut control, capture).await,
        Command::Decode { .. } => unreachable!(),
    };
    let close = control.close().await;
    result?;
    close
}

struct Screen;
impl Drop for Screen {
    fn drop(&mut self) {
        let _ = std::io::stdout().write_all(b"\x1b[0m\x1b[?25h\x1b[?7h\x1b[?1049l");
        let _ = crossterm::terminal::disable_raw_mode();
    }
}
async fn view(endpoint: &Endpoint, c: &mut Control, s: &str, b: &str, take: bool) -> Result<()> {
    ensure!(
        std::io::stdin().is_terminal() && std::io::stdout().is_terminal(),
        "view needs a terminal; use decode for captured data"
    );
    c.attach(s).await?;
    if take {
        c.call(
            "session.own_block",
            json!({"session_id":s,"block_ids":[b],"mode":"take"}),
        )
        .await?;
    }
    let mut data = Data::open(endpoint, c, s, b, Trace::default()).await?;
    let mut replica = Replica::new(data.nonce);
    eprintln!(
        "Native Ghostty view; Ctrl-] detaches. {}",
        if take {
            "Local size is sent while this client owns the block."
        } else {
            "Viewing existing size; use --take to follow this terminal's size."
        }
    );
    crossterm::terminal::enable_raw_mode()?;
    let _screen = Screen;
    std::io::stdout().write_all(b"\x1b[?1049h\x1b[?7l\x1b[2J")?;
    let (tx, mut input) = tokio::sync::mpsc::channel(8);
    std::thread::spawn(move || {
        use std::io::Read;
        let mut stdin = std::io::stdin().lock();
        loop {
            let mut bytes = vec![0; 4096];
            let result = stdin.read(&mut bytes).map(|n| {
                bytes.truncate(n);
                bytes
            });
            let end = match &result {
                Ok(b) => b.is_empty(),
                Err(_) => true,
            };
            if tx.blocking_send(result).is_err() || end {
                break;
            }
        }
    });
    let mut pending = Vec::new();
    let mut dirty = false;
    let mut previous_size = (0, 0);
    let mut tick = tokio::time::interval(Duration::from_millis(33));
    let mut ping = tokio::time::interval(Duration::from_secs(120));
    ping.tick().await;
    loop {
        c.drain_events();
        tokio::select! {
            _=tokio::signal::ctrl_c()=>break,
            _=ping.tick()=>{c.ping().await?;data.ping().await?;},
            _=tick.tick()=>{
                let size=crossterm::terminal::size()?;
                if size!=previous_size {
                    previous_size=size;dirty=true;
                    if take {c.terminal(s,b,"resize",json!({"columns":size.0,"rows":size.1})).await?;}
                    std::io::stdout().write_all(b"\x1b[2J")?;
                }
                if dirty&&replica.ready() {
                    let frame=replica.terminal()?.frame()?;
                    let mut out=std::io::stdout();out.write_all(&frame.ansi(size.0,size.1))?;out.flush()?;dirty=false;
                }
            },
            frame=c.next_message()=>{frame?;},
            chunk=data.read()=>{replica.feed(&chunk?)?;replica.events.clear();dirty=true;},
            bytes=input.recv()=>{
                let bytes=bytes.context("input reader ended")??;
                if bytes.is_empty(){break;}
                let stop=bytes.iter().position(|b|*b==0x1d);
                pending.extend_from_slice(&bytes[..stop.unwrap_or(bytes.len())]);
                let valid=match std::str::from_utf8(&pending){Ok(s)=>s.len(),Err(e)if e.error_len().is_none()=>e.valid_up_to(),Err(e)=>return Err(e.into())};
                if valid>0 {let text=std::str::from_utf8(&pending[..valid])?;c.terminal(s,b,"write",json!({"data":text})).await?;pending.drain(..valid);}
                if stop.is_some(){break;}
            },
        }
    }
    data.close().await
}

async fn read_until(
    data: &mut Data,
    replica: &mut Replica,
    needle: &str,
    capture: &mut Option<std::fs::File>,
) -> Result<()> {
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if replica.ready() && replica.terminal()?.text()?.contains(needle) {
                return Ok::<(), anyhow::Error>(());
            }
            let chunk = data.read().await?;
            if let Some(file) = capture {
                file.write_all(&chunk)?;
            }
            // Deliberately defeat message boundaries to exercise snapshot/VT continuation.
            for part in chunk.chunks(7) {
                replica.feed(part)?;
            }
        }
    })
    .await
    .with_context(|| format!("waiting for Ghostty screen to contain {needle:?}"))?
}

async fn self_test(endpoint: &Endpoint, c: &mut Control, path: Option<PathBuf>) -> Result<()> {
    let mut capture = path
        .map(|path| {
            let mut options = std::fs::OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            options.open(path)
        })
        .transpose()?;
    let script = "stty -echo; printf '\\033[1;38;2;12;200;90mGREEN\\033[0m λ🦀\\nGHOSTTY_READY\\n'; while IFS= read -r line; do case \"$line\" in alt) printf '\\033[?1049h\\033[HALTERNATE_READY' ;; main) printf '\\033[?1049l' ;; size) stty size ;; *) printf 'REPLY:%s\\n' \"$line\" ;; esac; done";
    let created=c.call("session.create",json!({"label":format!("rex-ghostty-test-{}",std::process::id()),"initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script],"size":{"columns":80,"rows":24}}}}}]})).await?;
    let s = created["session_id"].as_str().context("missing session")?;
    let b = created["initial_windows"][0]["block_ids"][0]
        .as_str()
        .context("missing block")?;
    eprintln!("Testing Ghostty in disposable {s}");
    let result = tokio::select! {r=test_session(endpoint,c,s,b,&mut capture)=>r,_=tokio::signal::ctrl_c()=>Err(anyhow::anyhow!("test interrupted"))};
    let cleanup = async {
        let mut cleanup = Control::connect(endpoint.clone(), None, Trace::default()).await?;
        cleanup.attach(s).await?;
        cleanup
            .call("session.destroy", json!({"session_id":s}))
            .await?;
        cleanup.close().await
    }
    .await;
    if let Err(e) = &cleanup {
        eprintln!("Cleanup failed for {s}: {e:#}");
    }
    let report = result?;
    cleanup?;
    println!("{}", serde_json::to_string_pretty(&report)?);
    Ok(())
}
async fn test_session(
    endpoint: &Endpoint,
    c: &mut Control,
    s: &str,
    b: &str,
    capture: &mut Option<std::fs::File>,
) -> Result<Value> {
    c.attach(s).await?;
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let screen = c.terminal(s, b, "format", json!({"trim":true})).await?;
            if screen["content"]
                .as_str()
                .is_some_and(|v| v.contains("GHOSTTY_READY"))
            {
                return Ok::<(), anyhow::Error>(());
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    })
    .await??;
    let mut data = Data::open(endpoint, c, s, b, Trace::default()).await?;
    let mut replica = Replica::new(data.nonce);
    read_until(&mut data, &mut replica, "GHOSTTY_READY", capture).await?;
    let frame = replica.terminal()?.frame()?;
    let green = frame
        .cells
        .iter()
        .find(|cell| cell.text == "G")
        .context("GREEN cell missing")?;
    ensure!(
        green.foreground == [12, 200, 90] && green.attributes & 1 != 0,
        "styled snapshot did not restore"
    );
    ensure!(
        frame.text().contains("λ🦀"),
        "Unicode snapshot did not restore"
    );
    c.terminal(s, b, "write", json!({"data":"live-λ🦀\n"}))
        .await?;
    read_until(&mut data, &mut replica, "REPLY:live-λ🦀", capture).await?;
    c.call(
        "session.own_block",
        json!({"session_id":s,"block_ids":[b],"mode":"take"}),
    )
    .await?;
    c.terminal(s, b, "resize", json!({"columns":93,"rows":31}))
        .await?;
    c.terminal(s, b, "write", json!({"data":"size\n"})).await?;
    read_until(&mut data, &mut replica, "31 93", capture).await?;
    let frame = replica.terminal()?.frame()?;
    ensure!(
        (frame.columns, frame.rows) == (93, 31),
        "resize APC not applied"
    );
    c.terminal(
        s,
        b,
        "set_theme",
        json!({"foreground":"#eeeeee","background":"#112233","palette":["2=#abcdef"]}),
    )
    .await?;
    c.terminal(s, b, "write", json!({"data":"theme-applied\n"}))
        .await?;
    read_until(&mut data, &mut replica, "REPLY:theme-applied", capture).await?;
    ensure!(
        replica.terminal()?.frame()?.background == [17, 34, 51],
        "theme APC not applied"
    );
    c.terminal(s, b, "write", json!({"data":"alt\n"})).await?;
    read_until(&mut data, &mut replica, "ALTERNATE_READY", capture).await?;
    ensure!(
        !replica
            .terminal()?
            .frame()?
            .text()
            .contains("GHOSTTY_READY"),
        "alternate screen mixed with primary"
    );
    c.terminal(s, b, "write", json!({"data":"main\nafter-main\n"}))
        .await?;
    read_until(&mut data, &mut replica, "REPLY:after-main", capture).await?;
    ensure!(
        replica.terminal()?.text()?.contains("GHOSTTY_READY"),
        "primary screen not restored"
    );
    let token = data.disconnect();
    c.terminal(s, b, "write", json!({"data":"during-gap\n"}))
        .await?;
    let mut data = token.connect(endpoint).await?;
    read_until(&mut data, &mut replica, "REPLY:during-gap", capture).await?;
    let expected = c.terminal(s, b, "format", json!({"trim":true})).await?;
    let actual = replica.terminal()?.text()?;
    ensure!(
        actual.trim_end()
            == expected["content"]
                .as_str()
                .context("missing server snapshot")?
                .trim_end(),
        "Ghostty replica differs from server screen/scrollback"
    );
    let frame = replica.terminal()?.frame()?;
    let report = json!({"passed":true,"session_id":s,"block_id":b,"ghostty_revision":"f54340ad77814fe319d136e5e915d6a48de8dd23","api_revision":c.welcome()["api_revision"],"snapshot_bytes":replica.snapshot_bytes,"stream_bytes":data.received,"nonce":data.nonce,"columns":frame.columns,"rows":frame.rows,"cursor":frame.cursor,"checks":["real Rex snapshot imported into native Ghostty","truecolor and bold cells preserved","Unicode snapshot and split live UTF-8","Rex resize APC applied before following VT","Rex theme APC applied","alternate screen and primary restoration","data resume preserves Ghostty state","replica screen and scrollback matches server format"],"cleanup":"destroyed before report"});
    data.close().await?;
    Ok(report)
}
