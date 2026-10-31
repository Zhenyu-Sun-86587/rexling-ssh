//! One actor owns every connection and the !Send Ghostty replica.
use anyhow::{Context, Result, ensure};
use rex_core::{
    control::{Control, Trace},
    data::{Data, Resume},
    ghostty::{Frame, Replica},
    transport::Endpoint,
};
use serde_json::{Value, json};
use std::{
    future::pending,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::mpsc;

pub struct Shared {
    pub revision: u64,
    pub json: String,
}
pub struct Session {
    tx: mpsc::Sender<Value>,
    pub shared: Arc<Mutex<Shared>>,
}
impl Session {
    pub fn spawn() -> Result<Self> {
        let (tx, rx) = mpsc::channel(128);
        let shared = Arc::new(Mutex::new(Shared {
            revision: 0,
            json: String::new(),
        }));
        let worker = shared.clone();
        std::thread::Builder::new()
            .name("rex-terminal".into())
            .spawn(move || {
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build();
                if let Ok(runtime) = runtime {
                    runtime.block_on(Actor::new(worker).run(rx));
                }
            })?;
        Ok(Self { tx, shared })
    }
    pub fn command(&self, value: Value) -> Result<()> {
        self.tx
            .try_send(value)
            .context("terminal command queue is full or closed")
    }
    pub fn poll(&self, after: u64) -> Result<String> {
        let state = self
            .shared
            .lock()
            .map_err(|_| anyhow::anyhow!("terminal state unavailable"))?;
        Ok(if state.revision > after {
            state.json.clone()
        } else {
            String::new()
        })
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        let _ = self.tx.try_send(json!({"type":"shutdown"}));
    }
}

struct Actor {
    shared: Arc<Mutex<Shared>>,
    endpoint: Option<Endpoint>,
    server: String,
    control: Option<Control>,
    data: Option<Data>,
    replica: Option<Replica>,
    control_token: Option<String>,
    data_resume: Option<Resume>,
    phase: String,
    identity: String,
    error: String,
    notice: String,
    sessions: Value,
    windows: Value,
    screen: Value,
    selected_session: String,
    selected_block: String,
    controlled: bool,
    paused: bool,
    retry: Option<tokio::time::Instant>,
    backoff: u64,
    dirty: bool,
}
impl Actor {
    fn new(shared: Arc<Mutex<Shared>>) -> Self {
        Self {
            shared,
            endpoint: None,
            server: String::new(),
            control: None,
            data: None,
            replica: None,
            control_token: None,
            data_resume: None,
            phase: "disconnected".into(),
            identity: String::new(),
            error: String::new(),
            notice: String::new(),
            sessions: json!([]),
            windows: json!([]),
            screen: Value::Null,
            selected_session: String::new(),
            selected_block: String::new(),
            controlled: false,
            paused: false,
            retry: None,
            backoff: 1,
            dirty: false,
        }
    }
    fn publish(&self) {
        if let Ok(mut shared) = self.shared.lock() {
            shared.revision += 1;
            shared.json=json!({"revision":shared.revision,"phase":self.phase,"endpoint":self.server,
                "identity":self.identity,"error":self.error,"notice":self.notice,"sessions":self.sessions,
                "windows":self.windows,"screen":self.screen,"selectedSession":self.selected_session,
                "selectedBlock":self.selected_block,"controlled":self.controlled}).to_string();
        }
    }
    async fn run(mut self, mut rx: mpsc::Receiver<Value>) {
        self.publish();
        let mut paint = tokio::time::interval(Duration::from_millis(50));
        let mut heartbeat = tokio::time::interval(Duration::from_secs(120));
        paint.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        heartbeat.tick().await;
        loop {
            let active = !self.paused;
            tokio::select! {
                value=rx.recv()=>{
                    let Some(value)=value else{break;};
                    if value["type"]=="shutdown"{break;}
                    if let Err(e)=self.command(value).await {
                        let message=e.to_string();
                        if message.contains("timed out")||message.contains("WebSocket") {
                            self.recover(e);
                        } else {self.error=message;self.publish();}
                    }
                },
                message=async {match &mut self.control{Some(c)=>c.next_message().await,None=>pending().await}}=>{
                    match message {
                        Ok(message)=>if let Err(e)=self.event(message).await{self.recover(e);},
                        Err(e)=>self.recover(e),
                    }
                },
                chunk=async {match &mut self.data{Some(d)=>d.read().await,None=>pending().await}}=>{
                    match chunk {
                        Ok(chunk)=>{
                            let result=self.replica.as_mut().context("terminal replica missing").and_then(|r|r.feed(&chunk));
                            if let Err(e)=result {
                                self.error=format!("Terminal state could not be decoded: {e}");
                                self.data=None;self.replica=None;self.data_resume=None;self.phase="error".into();self.controlled=false;self.publish();
                            } else {
                                self.dirty=true;
                                self.update_apcs();
                            }
                        }
                        Err(e)=>self.recover(e),
                    }
                },
                _=async {if active {paint.tick().await;}else{pending::<()>().await;}}=>{
                    if self.retry.is_some_and(|at|at<=tokio::time::Instant::now())&&!self.paused {
                        self.retry=None;
                        if let Err(e)=self.reconnect().await{self.recover(e);}
                    }
                    if self.dirty {
                        self.dirty=false;
                        if let Some(replica)=self.replica.as_mut().filter(|r|r.ready()) {
                            match replica.terminal().and_then(|t|t.frame()) {
                                Ok(frame)=>{self.screen=frame_json(frame);self.publish();}
                                Err(e)=>{self.error=e.to_string();self.publish();}
                            }
                        }
                    }
                    if let Some(c)=&mut self.control {c.drain_events();}
                },
                _=async {if active {heartbeat.tick().await;}else{pending::<()>().await;}}=>{
                    let result=async {
                        if let Some(c)=&mut self.control{c.ping().await?;}
                        if let Some(d)=&mut self.data{d.ping().await?;}
                        Ok::<(),anyhow::Error>(())
                    }.await;
                    if let Err(e)=result{self.recover(e);}
                },
            }
        }
        self.disconnect().await;
    }
    async fn disconnect(&mut self) {
        self.retry = None;
        self.control_token = None;
        self.data_resume = None;
        if let Some(mut d) = self.data.take() {
            let _ = tokio::time::timeout(Duration::from_secs(2), d.close()).await;
        }
        if let Some(mut c) = self.control.take() {
            let _ = tokio::time::timeout(Duration::from_secs(2), c.close()).await;
        }
        self.controlled = false;
    }
    fn lose_connections(&mut self) {
        if let Some(c) = self.control.take() {
            self.control_token = c.token().map(str::to_owned);
            drop(c);
        }
        if let Some(data) = self.data.take() {
            self.data_resume = Some(data.disconnect());
        }
        self.controlled = false;
    }
    fn recover(&mut self, error: anyhow::Error) {
        self.lose_connections();
        self.error = error.to_string();
        if self.endpoint.is_some() && !self.paused {
            self.phase = "reconnecting".into();
            self.retry = Some(tokio::time::Instant::now() + Duration::from_secs(self.backoff));
            self.backoff = (self.backoff * 2).min(15);
        }
        self.publish();
    }
    async fn reconnect(&mut self) -> Result<()> {
        let endpoint = self.endpoint.clone().context("server missing")?;
        let mut control = Control::connect(
            endpoint.clone(),
            self.control_token.as_deref(),
            Trace::default(),
        )
        .await?;
        let resumed = control.welcome()["client"]["resumed"] == true;
        self.control_token = control.token().map(str::to_owned);
        self.sessions = control
            .call("session.attach_all", json!({"event_scope":"session"}))
            .await?["sessions"]
            .clone();
        let who = control.call("server.whoami", json!({})).await?;
        self.identity = who["principal"]["display"]
            .as_str()
            .unwrap_or("Connected")
            .to_owned();
        self.control = Some(control);
        self.phase = "connected".into();
        self.error.clear();
        self.backoff = 1;
        if !self.selected_session.is_empty() {
            let session = self.selected_session.clone();
            let attached = self.control.as_mut().unwrap().attach(&session).await?;
            self.set_view(&attached["view"]);
            if !self.selected_block.is_empty() {
                let block = self.selected_block.clone();
                let mut recovered = None;
                if resumed && let Some(state) = self.data_resume.take() {
                    recovered = state.connect(&endpoint).await.ok();
                }
                self.data = if let Some(data) = recovered {
                    Some(data)
                } else {
                    let data = Data::open(
                        &endpoint,
                        self.control.as_mut().unwrap(),
                        &session,
                        &block,
                        Trace::default(),
                    )
                    .await?;
                    self.replica = Some(Replica::new(data.nonce));
                    Some(data)
                };
                self.data_resume = None;
                self.notice = if resumed {
                    "Connection restored"
                } else {
                    "Connected; terminal state refreshed"
                }
                .into();
            }
        }
        self.publish();
        Ok(())
    }
    fn set_view(&mut self, view: &Value) {
        let client = self
            .control
            .as_ref()
            .and_then(|c| c.client_id())
            .unwrap_or("");
        let mut windows = Vec::new();
        let mut selected_exists = false;
        for window in view["windows"].as_array().into_iter().flatten() {
            let mut blocks = Vec::new();
            for layer in window["layers"].as_array().into_iter().flatten() {
                for block in layer["blocks"].as_array().into_iter().flatten() {
                    if block["creator_name"] != "com.superlogical.terminal" {
                        continue;
                    }
                    let id = block["block_id"].as_str().unwrap_or("");
                    if id == self.selected_block {
                        selected_exists = true;
                        self.controlled = block["owner"].as_str() == Some(client);
                    }
                    blocks.push(
                        json!({"id":id,"label":block["label"].as_str().unwrap_or("Terminal")}),
                    );
                }
            }
            windows.push(json!({"id":window["window_id"],"label":window["label"],"blocks":blocks}));
        }
        self.windows = json!(windows);
        if !self.selected_block.is_empty() && !selected_exists {
            self.data = None;
            self.data_resume = None;
            self.replica = None;
            self.screen = Value::Null;
            self.selected_block.clear();
            self.controlled = false;
            self.notice = "That terminal has closed".into();
        }
    }
    async fn refresh_view(&mut self) -> Result<()> {
        if self.selected_session.is_empty() {
            return Ok(());
        }
        let view = self
            .control
            .as_mut()
            .context("not connected")?
            .call("session.view", json!({"session_id":self.selected_session}))
            .await?;
        self.set_view(&view);
        self.publish();
        Ok(())
    }
    async fn event(&mut self, event: Value) -> Result<()> {
        let event = &event["payload"];
        if event["type"] == "session_destroyed"
            && event["payload"]["session_id"].as_str() == Some(self.selected_session.as_str())
        {
            self.selected_session.clear();
            self.selected_block.clear();
            self.data = None;
            self.data_resume = None;
            self.replica = None;
            self.screen = Value::Null;
            self.windows = json!([]);
            self.controlled = false;
            self.notice = "That session has closed".into();
        }
        if matches!(
            event["type"].as_str(),
            Some("session_created" | "session_destroyed" | "session_changed")
        ) {
            self.sessions = self
                .control
                .as_mut()
                .unwrap()
                .call("session.list", json!({}))
                .await?["sessions"]
                .clone();
        }
        if event["type"] == "session_event" {
            let inner = &event["payload"]["event"];
            if matches!(
                inner["type"].as_str(),
                Some("session_view_changed" | "block_owner_changed" | "block_closed")
            ) {
                self.refresh_view().await?;
            }
        }
        self.publish();
        Ok(())
    }
    fn update_apcs(&mut self) {
        let Some(replica) = self.replica.as_mut() else {
            return;
        };
        for event in replica.events.drain(..) {
            if event["type"] == "resize"
                && let Some(owner) = event.get("owner")
            {
                self.controlled = owner
                    .as_str()
                    .zip(self.control.as_ref().and_then(|c| c.client_id()))
                    .is_some_and(|(owner, client)| owner == client);
            }
            if event["type"] == "child_exited" {
                self.notice = format!("Program exited ({})", event["exit_code"]);
            }
        }
    }
    async fn command(&mut self, value: Value) -> Result<()> {
        match value["type"].as_str().context("command type missing")? {
            "connect" => {
                let server = value["server"]
                    .as_str()
                    .context("server required")?
                    .trim()
                    .to_owned();
                ensure!(
                    server.starts_with("https://")
                        || server.starts_with("http://")
                        || server.starts_with("wss://")
                        || server.starts_with("ws://"),
                    "Enter a complete http(s) or ws(s) server URL"
                );
                let endpoint = Endpoint::new(&server, Duration::from_secs(10))?;
                self.disconnect().await;
                self.endpoint = Some(endpoint);
                self.server = server;
                self.selected_session.clear();
                self.selected_block.clear();
                self.windows = json!([]);
                self.screen = Value::Null;
                self.replica = None;
                self.paused = false;
                self.phase = "connecting".into();
                self.error.clear();
                self.notice.clear();
                self.publish();
                if let Err(e) = self.reconnect().await {
                    self.recover(e);
                }
            }
            "disconnect" => {
                self.disconnect().await;
                self.endpoint = None;
                self.replica = None;
                self.screen = Value::Null;
                self.phase = "disconnected".into();
                self.error.clear();
                self.notice.clear();
                self.selected_block.clear();
                self.selected_session.clear();
                self.windows = json!([]);
                self.sessions = json!([]);
                self.publish();
            }
            "session" => {
                let session = value["id"]
                    .as_str()
                    .context("session ID required")?
                    .to_owned();
                self.release_selected().await?;
                if let Some(mut data) = self.data.take() {
                    let _ = data.close().await;
                }
                self.data_resume = None;
                self.replica = None;
                self.screen = Value::Null;
                self.selected_block.clear();
                self.controlled = false;
                if !self.selected_session.is_empty() && self.selected_session != session {
                    self.control
                        .as_mut()
                        .context("not connected")?
                        .call(
                            "session.detach",
                            json!({"session_id":self.selected_session}),
                        )
                        .await?;
                }
                let attached = self
                    .control
                    .as_mut()
                    .context("not connected")?
                    .attach(&session)
                    .await?;
                self.selected_session = session;
                self.set_view(&attached["view"]);
                self.error.clear();
                self.publish();
            }
            "block" => {
                let block = value["id"]
                    .as_str()
                    .context("block ID required")?
                    .to_owned();
                ensure!(!self.selected_session.is_empty(), "Choose a session first");
                self.release_selected().await?;
                if let Some(mut data) = self.data.take() {
                    let _ = data.close().await;
                }
                self.data_resume = None;
                self.selected_block = block;
                self.controlled = false;
                self.screen = Value::Null;
                let data = Data::open(
                    self.endpoint.as_ref().context("no server")?,
                    self.control.as_mut().context("not connected")?,
                    &self.selected_session,
                    &self.selected_block,
                    Trace::default(),
                )
                .await?;
                self.replica = Some(Replica::new(data.nonce));
                self.data = Some(data);
                self.error.clear();
                self.notice = "Viewing terminal; take control to type".into();
                self.refresh_view().await?;
            }
            "take" => {
                ensure!(!self.selected_block.is_empty(), "Choose a terminal first");
                self.control.as_mut().context("not connected")?.call("session.own_block",json!({"session_id":self.selected_session,"block_ids":[self.selected_block],"mode":"take"})).await?;
                self.refresh_view().await?;
                self.notice = "You control this terminal".into();
                self.publish();
            }
            "release" => {
                if let Some(c) = &mut self.control {
                    c.call("session.disown_block",json!({"session_id":self.selected_session,"block_ids":[self.selected_block]})).await?;
                }
                self.controlled = false;
                self.notice = "Viewing terminal".into();
                self.publish();
            }
            "input" | "paste" | "resize" => {
                ensure!(self.controlled, "Take control before typing or resizing");
                let kind = value["type"].as_str().unwrap();
                let c = self.control.as_mut().context("not connected")?;
                let result=match kind {
                    "input"=>c.terminal(&self.selected_session,&self.selected_block,"write",json!({"data":value["text"].as_str().context("text required")?})).await,
                    "paste"=>c.call("block.event",json!({"session_id":self.selected_session,"block_id":self.selected_block,"event":{"type":"paste","payload":{"text":value["text"].as_str().context("text required")?}}})).await,
                    _=>{
                        let cols=value["columns"].as_u64().context("columns required")?.clamp(1,512);
                        let rows=value["rows"].as_u64().context("rows required")?.clamp(1,256);
                        c.terminal(&self.selected_session,&self.selected_block,"resize",json!({"columns":cols,"rows":rows})).await
                    }
                };
                if let Err(e) = result {
                    // A lost reply does not prove the server did not type the text.
                    // Reconnect, but never automatically replay input or paste.
                    self.recover(e);
                    self.notice = "Request outcome is uncertain; input was not resent".into();
                    self.publish();
                }
            }
            "pause" => {
                self.paused = true;
                self.retry = None;
                self.lose_connections();
                if self.endpoint.is_some() {
                    self.phase = "paused".into();
                }
                self.publish();
            }
            "resume" => {
                self.paused = false;
                if self.endpoint.is_some() && self.control.is_none() {
                    self.retry = Some(tokio::time::Instant::now());
                    self.phase = "reconnecting".into();
                    self.publish();
                }
            }
            "refresh" => {
                self.sessions = self
                    .control
                    .as_mut()
                    .context("not connected")?
                    .call("session.list", json!({}))
                    .await?["sessions"]
                    .clone();
                self.refresh_view().await?;
                self.publish();
            }
            "preview" => {
                self.disconnect().await;
                self.endpoint = None;
                self.selected_session.clear();
                self.selected_block.clear();
                self.sessions = json!([]);
                self.windows = json!([]);
                let metadata: Value = serde_json::from_str(include_str!(
                    "../../rex-core/tests/fixtures/ghostty-remote.json"
                ))?;
                let mut replica = Replica::new(
                    metadata["nonce"]
                        .as_u64()
                        .context("preview nonce missing")?,
                );
                for chunk in
                    include_bytes!("../../rex-core/tests/fixtures/ghostty-remote.bin").chunks(7)
                {
                    replica.feed(chunk)?;
                }
                self.screen = frame_json(replica.terminal()?.frame()?);
                self.replica = Some(replica);
                self.phase = "preview".into();
                self.error.clear();
                self.notice = "Offline preview · captured test terminal".into();
                self.controlled = false;
                self.publish();
            }
            other => anyhow::bail!("Unknown command: {other}"),
        }
        Ok(())
    }
    async fn release_selected(&mut self) -> Result<()> {
        if !self.selected_session.is_empty()
            && !self.selected_block.is_empty()
            && let Some(control) = self.control.as_mut()
        {
            control
                .call(
                    "session.disown_block",
                    json!({"session_id":self.selected_session,"block_ids":[self.selected_block]}),
                )
                .await?;
        }
        self.controlled = false;
        Ok(())
    }
}
fn rgb(rgb: [u8; 3]) -> u32 {
    ((rgb[0] as u32) << 16) | ((rgb[1] as u32) << 8) | rgb[2] as u32
}
fn frame_json(frame: Frame) -> Value {
    let cells: Vec<Value> = frame
        .cells
        .into_iter()
        .map(|c| {
            json!([
                c.x,
                c.y,
                c.text,
                rgb(c.foreground),
                rgb(c.background),
                c.attributes,
                c.width
            ])
        })
        .collect();
    json!({"columns":frame.columns,"rows":frame.rows,"cursor":frame.cursor,"foreground":rgb(frame.foreground),"background":rgb(frame.background),"cells":cells})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn offline_preview_runs_on_the_owner_thread_and_polls_by_revision() {
        let session = Session::spawn().unwrap();
        session.command(json!({"type":"preview"})).unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        loop {
            let value = session.poll(0).unwrap();
            if !value.is_empty() {
                let state: Value = serde_json::from_str(&value).unwrap();
                if state["phase"] == "preview" {
                    assert_eq!(state["screen"]["columns"], 93);
                    assert_eq!(state["screen"]["rows"], 31);
                    assert!(!state["controlled"].as_bool().unwrap());
                    assert!(
                        session
                            .poll(state["revision"].as_u64().unwrap())
                            .unwrap()
                            .is_empty()
                    );
                    break;
                }
            }
            assert!(std::time::Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn wait(session: &Session, predicate: impl Fn(&Value) -> bool) -> Value {
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        loop {
            let text = session.poll(0).unwrap();
            if !text.is_empty() {
                let value: Value = serde_json::from_str(&text).unwrap();
                if predicate(&value) {
                    return value;
                }
                assert!(
                    std::time::Instant::now() < deadline,
                    "Actor timed out: {}",
                    value
                );
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }
    fn text(state: &Value) -> String {
        state["screen"]["cells"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|cell| cell[6].as_u64() != Some(0))
            .map(|cell| cell[2].as_str().unwrap_or(""))
            .collect()
    }
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_actor_connects_types_and_recovers_after_lifecycle_pause() {
        let server = std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let mut writer = runtime
            .block_on(Control::connect(
                Endpoint::new(&server, Duration::from_secs(10)).unwrap(),
                None,
                Trace::default(),
            ))
            .unwrap();
        let script = "stty -echo; printf 'ACTOR_READY\\n'; while IFS= read -r line; do printf 'ACTOR_REPLY:%s\\n' \"$line\"; done";
        let created=runtime.block_on(writer.call("session.create",json!({"label":format!("rex-android-host-test-{}",std::process::id()),"initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script]}}}}]}))).unwrap();
        let id = created["session_id"].as_str().unwrap();
        let block = created["initial_windows"][0]["block_ids"][0]
            .as_str()
            .unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app = Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":server}))
                .unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":id})).unwrap();
            wait(&app, |v| {
                v["selectedSession"] == id && !v["windows"].as_array().unwrap().is_empty()
            });
            app.command(json!({"type":"block","id":block})).unwrap();
            wait(&app, |v| text(v).contains("ACTOR_READY"));
            app.command(json!({"type":"take"})).unwrap();
            wait(&app, |v| v["controlled"] == true);
            app.command(json!({"type":"input","text":"hello-λ🦀\n"}))
                .unwrap();
            wait(&app, |v| text(v).contains("ACTOR_REPLY:hello-λ🦀"));
            app.command(json!({"type":"resize","columns":72,"rows":22}))
                .unwrap();
            wait(&app, |v| {
                v["screen"]["columns"] == 72 && v["screen"]["rows"] == 22
            });
            app.command(json!({"type":"pause"})).unwrap();
            wait(&app, |v| v["phase"] == "paused" && v["controlled"] == false);
            runtime
                .block_on(writer.terminal(id, block, "write", json!({"data":"during-app-pause\n"})))
                .unwrap();
            app.command(json!({"type":"resume"})).unwrap();
            wait(&app, |v| {
                v["phase"] == "connected" && text(v).contains("ACTOR_REPLY:during-app-pause")
            });
            app.command(json!({"type":"release"})).unwrap();
            wait(&app, |v| v["controlled"] == false);
            app.command(json!({"type":"disconnect"})).unwrap();
            wait(&app, |v| v["phase"] == "disconnected");
        }));
        runtime
            .block_on(writer.call("session.destroy", json!({"session_id":id})))
            .unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }
}
