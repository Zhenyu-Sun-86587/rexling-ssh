use anyhow::{Context, Result, bail, ensure};
use clap::{Parser, Subcommand};
use rex_probe::{
    control::{Control, Trace, redact},
    data::Data,
    transport::Endpoint,
};
use serde_json::{Value, json};
use std::{
    io::{IsTerminal, Write},
    path::PathBuf,
    time::Duration,
};
use tokio::io::AsyncReadExt;

#[derive(Parser)]
#[command(
    version,
    about = "Independent direct Rex protocol probe; no Rex CLI runtime dependency"
)]
struct Cli {
    #[arg(short = 'S', long, env = "REX_SERVER", global = true)]
    server: Option<String>,
    #[arg(long, default_value_t = 10, global = true)]
    timeout: u64,
    /// New private JSONL file; secrets redacted, but terminal input is recorded.
    #[arg(long, global = true)]
    trace: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}
#[derive(Subcommand)]
enum Command {
    /// Inspect welcome, versions, identity, exact schemas, and block creators.
    Discover,
    /// Verify the direct connection and authenticated identity.
    Connect,
    Sessions,
    /// Inspect windows, layers, layouts, and blocks.
    Inspect {
        session: String,
    },
    /// Format screen and scrollback; this is not incremental output.
    Snapshot {
        session: String,
        block: String,
        #[arg(long, default_value = "text", value_parser = ["text", "vt", "html"])]
        format: String,
        #[arg(long)]
        output: Option<PathBuf>,
    },
    /// Watch data stream metadata; optionally capture exact binary bytes.
    Watch {
        session: String,
        block: String,
        #[arg(long, default_value_t = 10)]
        seconds: u64,
        #[arg(long)]
        output: Option<PathBuf>,
        /// Simulate one data-connection loss after the first received chunk.
        #[arg(long)]
        resume: bool,
    },
    /// Forward UTF-8 stdin as terminal input; capture the Ghostty stream to a file.
    Attach {
        session: String,
        block: String,
        /// File, or '-' for piped raw stdout. Generic terminals cannot render the snapshot.
        #[arg(long)]
        output: PathBuf,
    },
    /// Send exact text, or read UTF-8 text from stdin with --stdin. No newline added.
    Write {
        session: String,
        block: String,
        #[arg(required_unless_present = "stdin", conflicts_with = "stdin")]
        text: Option<String>,
        #[arg(long)]
        stdin: bool,
    },
    Resize {
        session: String,
        block: String,
        #[arg(value_parser = clap::value_parser!(u16).range(1..))]
        columns: u16,
        #[arg(value_parser = clap::value_parser!(u16).range(1..))]
        rows: u16,
        /// Explicitly claim ownership; otherwise a different owner may defer the resize.
        #[arg(long)]
        take: bool,
    },
    /// Call a discovered method. Session-scoped calls need --session to attach first.
    Call {
        method: String,
        #[arg(default_value = "{}")]
        args: String,
        #[arg(long)]
        session: Option<String>,
    },
    /// Create an isolated session, test input/resize/stream/resume, then destroy only it.
    SelfTest,
}

fn print_json(mut value: Value) -> Result<()> {
    redact(&mut value);
    println!("{}", serde_json::to_string_pretty(&value)?);
    Ok(())
}
fn new_output(path: &PathBuf) -> Result<std::fs::File> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options
        .open(path)
        .with_context(|| format!("create new output {}", path.display()))
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let server = cli.server.context("pass --server URL or set REX_SERVER")?;
    let endpoint = Endpoint::new(&server, Duration::from_secs(cli.timeout))?;
    let trace = cli
        .trace
        .as_deref()
        .map(Trace::create)
        .transpose()?
        .unwrap_or_default();
    let mut control = Control::connect(endpoint.clone(), None, trace.clone()).await?;
    let result = run(cli.command, &endpoint, &mut control, trace).await;
    let closed = control.close().await;
    result?;
    closed
}

async fn run(command: Command, endpoint: &Endpoint, c: &mut Control, trace: Trace) -> Result<()> {
    match command {
        Command::Discover => {
            let version = c.call("server.version", json!({})).await?;
            let status = c.call("server.status", json!({})).await?;
            let identity = c.call("server.whoami", json!({})).await?;
            let api = c.call("help.all", json!({})).await?;
            let creators = c.call("block_creator.list", json!({})).await?;
            print_json(
                json!({"welcome":c.welcome(),"version":version,"status":status,"identity":identity,"api":api,"creators":creators}),
            )?;
        }
        Command::Connect => {
            let identity = c.call("server.whoami", json!({})).await?;
            let version = c.call("server.version", json!({})).await?;
            print_json(json!({"welcome":c.welcome(),"identity":identity,"version":version}))?;
        }
        Command::Sessions => print_json(c.call("session.list", json!({})).await?)?,
        Command::Inspect { session } => {
            let attached = c.attach(&session).await?;
            let blocks = c
                .call("session.list_blocks", json!({"session_id":session}))
                .await?;
            print_json(json!({"attached":attached,"blocks":blocks}))?;
        }
        Command::Snapshot {
            session,
            block,
            format,
            output,
        } => {
            // Fail on an existing destination before making any session call.
            let file = output.as_ref().map(new_output).transpose()?;
            c.attach(&session).await?;
            let text = snapshot(c, &session, &block, &format).await?;
            if let Some(mut file) = file {
                file.write_all(text.as_bytes())?;
            } else {
                std::io::stdout().write_all(text.as_bytes())?;
            }
        }
        Command::Write {
            session,
            block,
            text,
            stdin,
        } => {
            let text = if stdin {
                let mut s = String::new();
                tokio::io::stdin().read_to_string(&mut s).await?;
                s
            } else {
                text.unwrap()
            };
            c.attach(&session).await?;
            print_json(
                c.terminal(&session, &block, "write", json!({"data":text}))
                    .await?,
            )?;
        }
        Command::Resize {
            session,
            block,
            columns,
            rows,
            take,
        } => {
            c.attach(&session).await?;
            if take {
                c.call(
                    "session.own_block",
                    json!({"session_id":session,"block_ids":[block],"mode":"take"}),
                )
                .await?;
            }
            c.terminal(
                &session,
                &block,
                "resize",
                json!({"columns":columns,"rows":rows}),
            )
            .await?;
            print_json(c.terminal(&session, &block, "size", json!({})).await?)?;
        }
        Command::Call {
            method,
            args,
            session,
        } => {
            let mut args: Value = serde_json::from_str(&args).context("arguments must be JSON")?;
            ensure!(args.is_object(), "arguments must be an object");
            if let Some(session) = session {
                c.attach(&session).await?;
                if args.get("session_id").is_none() {
                    args["session_id"] = json!(session);
                }
            }
            print_json(c.call(&method, args).await?)?;
        }
        Command::Watch {
            session,
            block,
            seconds,
            output,
            resume,
        } => {
            let mut file = output.as_ref().map(new_output).transpose()?;
            c.attach(&session).await?;
            let mut data = Data::open(endpoint, c, &session, &block, trace).await?;
            print_json(json!({"data":data.summary()}))?;
            let deadline = tokio::time::sleep(Duration::from_secs(seconds));
            tokio::pin!(deadline);
            let mut resumed = false;
            let mut heartbeat = tokio::time::interval(Duration::from_secs(120));
            heartbeat.tick().await;
            loop {
                c.drain_events();
                tokio::select! {
                    _ = &mut deadline => break,
                    _ = tokio::signal::ctrl_c() => break,
                    _ = heartbeat.tick() => { c.ping().await?; data.ping().await?; },
                    frame = c.next_message() => { let m=frame?; if m["type"]=="event" { print_json(m)?; } },
                    chunk = data.read() => {
                        let chunk=chunk?;
                        if let Some(file)=&mut file { file.write_all(&chunk)?; }
                        print_json(json!({"chunk_bytes":chunk.len(),"data":data.summary()}))?;
                        if resume && !resumed { data=data.resume(endpoint).await?; resumed=true; print_json(json!({"resumed":true,"data":data.summary()}))?; }
                    },
                }
            }
            data.close().await?;
        }
        Command::Attach {
            session,
            block,
            output,
        } => attach(endpoint, c, trace, &session, &block, &output).await?,
        Command::SelfTest => self_test(endpoint, c, trace).await?,
    }
    Ok(())
}

async fn snapshot(c: &mut Control, s: &str, b: &str, format: &str) -> Result<String> {
    snapshot_with_budget(c, s, b, format, 65536).await
}

async fn snapshot_with_budget(
    c: &mut Control,
    s: &str,
    b: &str,
    format: &str,
    budget: usize,
) -> Result<String> {
    let mut part = c
        .terminal(
            s,
            b,
            "format",
            json!({"format":format,"trim":true,"max_bytes":budget}),
        )
        .await?;
    let mut text = part["content"]
        .as_str()
        .context("snapshot content missing")?
        .to_owned();
    while let Some(id) = part.get("id").and_then(Value::as_u64) {
        part = c
            .terminal(
                s,
                b,
                "blob_read",
                json!({"id":id,"offset":text.len(),"max_bytes":budget}),
            )
            .await?;
        let next = part["data"].as_str().context("blob data missing")?;
        ensure!(
            !next.is_empty() || part["eof"] == true,
            "blob made no progress"
        );
        text.push_str(next);
        ensure!(
            text.len() <= 128 * 1024 * 1024,
            "snapshot exceeds probe's 128 MiB limit"
        );
        if part["eof"] == true {
            break;
        }
    }
    Ok(text)
}

struct RawMode(bool);
impl Drop for RawMode {
    fn drop(&mut self) {
        if self.0 {
            let _ = crossterm::terminal::disable_raw_mode();
        }
    }
}
async fn attach(
    endpoint: &Endpoint,
    c: &mut Control,
    trace: Trace,
    s: &str,
    b: &str,
    path: &PathBuf,
) -> Result<()> {
    let mut file: Box<dyn Write> = if path.as_os_str() == "-" {
        ensure!(
            !std::io::stdout().is_terminal(),
            "pipe raw stdout to a file or decoder; the stream starts with a binary Ghostty snapshot"
        );
        Box::new(std::io::stdout())
    } else {
        Box::new(new_output(path)?)
    };
    c.attach(s).await?;
    let mut data = Data::open(endpoint, c, s, b, trace).await?;
    eprintln!(
        "Capturing raw Ghostty snapshot + VT to {}. Stdin sends input; Ctrl-] detaches. No Ghostty renderer is integrated.",
        path.display()
    );
    let raw = std::io::stdin().is_terminal();
    if raw {
        crossterm::terminal::enable_raw_mode()?;
    }
    let _guard = RawMode(raw);
    // Tokio's stdin uses an uncancelable runtime blocking read. A detached input
    // thread lets the CLI exit if the remote block closes while stdin is idle.
    let (sender, mut input) = tokio::sync::mpsc::channel(8);
    std::thread::spawn(move || {
        use std::io::Read;
        let mut stdin = std::io::stdin().lock();
        loop {
            let mut buffer = vec![0; 4096];
            let result = stdin.read(&mut buffer).map(|count| {
                buffer.truncate(count);
                buffer
            });
            let done = match &result {
                Ok(bytes) => bytes.is_empty(),
                Err(_) => true,
            };
            if sender.blocking_send(result).is_err() || done {
                break;
            }
        }
    });
    let mut pending = Vec::new();
    let mut heartbeat = tokio::time::interval(Duration::from_secs(120));
    heartbeat.tick().await;
    loop {
        c.drain_events();
        tokio::select! {
            _ = tokio::signal::ctrl_c() => break,
            _ = heartbeat.tick() => { c.ping().await?; data.ping().await?; },
            frame=c.next_message() => { frame?; },
            chunk=data.read() => { file.write_all(&chunk?)?; file.flush()?; },
            buffer=input.recv() => {
                let buffer=buffer.context("stdin reader ended")??;
                let count=buffer.len();
                if count==0 { ensure!(pending.is_empty(),"stdin ended in incomplete UTF-8"); break; }
                let stop=buffer[..count].iter().position(|v|*v==0x1d);
                pending.extend_from_slice(&buffer[..stop.unwrap_or(count)]);
                let valid=match std::str::from_utf8(&pending) { Ok(s)=>s.len(), Err(e) if e.error_len().is_none()=>e.valid_up_to(), Err(_)=>bail!("terminal.write requires UTF-8 input") };
                if valid>0 {
                    let text=std::str::from_utf8(&pending[..valid])?;
                    c.terminal(s,b,"write",json!({"data":text})).await?;
                    pending.drain(..valid);
                }
                if stop.is_some() { break; }
            },
        }
    }
    data.close().await?;
    Ok(())
}

async fn read_until(data: &mut Data, needle: &[u8]) -> Result<Vec<u8>> {
    tokio::time::timeout(Duration::from_secs(10), async {
        let mut bytes = Vec::new();
        loop {
            bytes.extend(data.read().await?);
            ensure!(
                bytes.len() < 4 * 1024 * 1024,
                "self-test received too much output"
            );
            if bytes.windows(needle.len()).any(|w| w == needle) {
                return Ok(bytes);
            }
        }
    })
    .await
    .context("self-test timed out waiting for terminal bytes")?
}

async fn self_test(endpoint: &Endpoint, c: &mut Control, trace: Trace) -> Result<()> {
    let label = format!("rex-probe-test-{}", std::process::id());
    let script = "stty -echo; printf 'PROBE_READY\\n'; while IFS= read -r line; do case \"$line\" in size) stty size ;; *) printf 'PROBE_REPLY:%s\\n' \"$line\" ;; esac; done";
    let created=c.call("session.create",json!({"label":label,"initial_windows":[{"window_label":"Probe","layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script],"size":{"columns":80,"rows":24}}}}}]})).await?;
    let s = created["session_id"]
        .as_str()
        .context("session.create returned no ID")?
        .to_owned();
    eprintln!("Testing disposable session {s}; cleanup runs on success or error.");
    let result = tokio::select! {
        result=test_session(endpoint,c,trace,&s,&created)=>result,
        _=tokio::signal::ctrl_c()=>Err(anyhow::anyhow!("self-test interrupted")),
    };
    // A failed or canceled call may have an outstanding response. Use a fresh
    // connection for cleanup so an unknown outcome cannot confuse its request IDs.
    let cleanup = async {
        let mut cleanup = Control::connect(endpoint.clone(), None, Trace::default()).await?;
        cleanup.attach(&s).await?;
        cleanup
            .call("session.destroy", json!({"session_id":s}))
            .await?;
        cleanup.close().await
    }
    .await;
    if let Err(e) = &cleanup {
        eprintln!("Cleanup failed for {s}: {e:#}. Destroy only this disposable session manually.");
    }
    let report = result?;
    cleanup?;
    print_json(report)
}

async fn test_session(
    endpoint: &Endpoint,
    c: &mut Control,
    trace: Trace,
    s: &str,
    created: &Value,
) -> Result<Value> {
    let b = created["initial_windows"][0]["block_ids"][0]
        .as_str()
        .context("no terminal block")?;
    let attached = c.attach(s).await?;
    ensure!(
        attached["view"]["windows"]
            .as_array()
            .is_some_and(|w| !w.is_empty()),
        "no windows in attached view"
    );
    let described = c
        .call(
            "session.describe_block",
            json!({"session_id":s,"block_id":b}),
        )
        .await?;
    // Wait for the controlled child to be ready; terminal writes only acknowledge queuing.
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if snapshot(c, s, b, "text").await?.contains("PROBE_READY") {
                return Ok::<(), anyhow::Error>(());
            }
            tokio::time::sleep(Duration::from_millis(40)).await;
        }
    })
    .await
    .context("child readiness timeout")??;
    let mut data = Data::open(endpoint, c, s, b, trace.clone()).await?;
    let nonce = data.nonce;
    let prefix = format!("\u{1b}_rex1;{nonce};");
    let initial = read_until(&mut data, prefix.as_bytes()).await?;
    ensure!(
        initial.starts_with(b"GHOSTSNP"),
        "unexpected Ghostty snapshot signature"
    );
    let snapshot_prefix = initial
        .iter()
        .take(48)
        .map(|b| format!("{b:02x}"))
        .collect::<String>();
    c.terminal(s, b, "write", json!({"data":"live-one\n"}))
        .await?;
    let live = read_until(&mut data, b"PROBE_REPLY:live-one").await?;
    ensure!(!live.is_empty(), "no incremental output");
    c.call(
        "session.own_block",
        json!({"session_id":s,"block_ids":[b],"mode":"take"}),
    )
    .await?;
    c.terminal(s, b, "resize", json!({"columns":93,"rows":31}))
        .await?;
    let size = c.terminal(s, b, "size", json!({})).await?;
    ensure!(
        size["columns"] == 93 && size["rows"] == 31,
        "resize did not apply"
    );
    c.terminal(s, b, "write", json!({"data":"size\n"})).await?;
    read_until(&mut data, b"31 93").await?;
    let before = data.received;
    let resume = data.disconnect();
    c.terminal(s, b, "write", json!({"data":"during-data-gap\n"}))
        .await?;
    let mut data = resume.connect(endpoint).await?;
    ensure!(
        data.received == before && data.nonce == nonce,
        "data resume lost state"
    );
    read_until(&mut data, b"PROBE_REPLY:during-data-gap").await?;

    let id = c.client_id().context("no resumable client ID")?.to_owned();
    let token = c.token().context("no control reconnect token")?.to_owned();
    let replacement = Control::connect(endpoint.clone(), None, Trace::default()).await?;
    // Replace and drop the old connection with no close frame; keep cleanup handle valid.
    drop(std::mem::replace(c, replacement));
    c.attach(s).await?;
    let window = created["initial_windows"][0]["window_id"]
        .as_str()
        .context("no test window ID")?;
    c.call(
        "session.set_window_label",
        json!({"session_id":s,"window_id":window,"label":"Changed during control gap"}),
    )
    .await?;
    c.terminal(s, b, "write", json!({"data":"during-control-gap\n"}))
        .await?;
    // Ensure output actually happened while the original control connection was absent.
    read_until(&mut data, b"PROBE_REPLY:during-control-gap").await?;
    let resumed = Control::connect(endpoint.clone(), Some(&token), trace.clone()).await?;
    ensure!(
        resumed.client_id() == Some(id.as_str()) && resumed.welcome()["client"]["resumed"] == true,
        "control client was not resumed"
    );
    ensure!(
        resumed.token() != Some(token.as_str()),
        "control reconnect token was not rotated"
    );
    let mut temporary = std::mem::replace(c, resumed);
    temporary.close().await?;
    let view = c.call("session.view", json!({"session_id":s})).await?;
    ensure!(view["session_id"] == s, "view resynchronization failed");
    ensure!(
        view["windows"][0]["label"] == "Changed during control gap",
        "fresh view missed a change during disconnect"
    );
    let final_snapshot = snapshot(c, s, b, "text").await?;
    ensure!(
        final_snapshot.contains("PROBE_REPLY:during-control-gap"),
        "snapshot did not synchronize missed output"
    );
    let paged_snapshot = snapshot_with_budget(c, s, b, "text", 32).await?;
    ensure!(
        paged_snapshot == final_snapshot,
        "paginated snapshot differs from unchunked snapshot"
    );
    c.terminal(s, b, "write", json!({"data":"after-control-resume\n"}))
        .await?;
    read_until(&mut data, b"PROBE_REPLY:after-control-resume").await?;
    let summary = data.summary();
    data.close().await?;
    c.drain_events();
    Ok(
        json!({"passed":true,"session_id":s,"block_id":b,"session_destroyed_after_test":true,"api_revision":c.welcome()["api_revision"],"checks":["session creation and window/block inspection","text snapshot","ticket and binary data handshake","initial Ghostty snapshot and nonce-marked APC","incremental output after input","resize API and PTY stty size","data resume with output produced during disconnect","control resume preserves client ID and rotates token","fresh view and snapshot after control resume","paginated snapshot agrees with full snapshot","input and live output after control resume"],"initial_stream_bytes":initial.len(),"initial_stream_prefix_hex":snapshot_prefix,"data":summary,"size":size,"described_block":described["block"]["block_id"],"post_resume_event_seq":c.last_seq()}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::CommandFactory;

    #[test]
    fn command_line_is_consistent() {
        Cli::command().debug_assert();
    }
}
