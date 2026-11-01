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
    collections::BTreeMap,
    future::{Future, pending},
    pin::Pin,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::mpsc;

pub struct Shared {
    pub revision: u64,
    pub json: String,
    pub pending_previews: BTreeMap<String, Value>,
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
            pending_previews: BTreeMap::new(),
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
        let mut state = self
            .shared
            .lock()
            .map_err(|_| anyhow::anyhow!("terminal state unavailable"))?;
        Ok(if state.revision > after {
            if state.pending_previews.is_empty() {
                state.json.clone()
            } else {
                let mut value: Value = serde_json::from_str(&state.json)?;
                value["previews"] =
                    serde_json::to_value(std::mem::take(&mut state.pending_previews))?;
                value.to_string()
            }
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

type PreviewTask = Pin<Box<dyn Future<Output = Result<Vec<(String, Value)>>>>>;
type ProgramTask = Pin<Box<dyn Future<Output = Result<Value>>>>;
type SummaryTask = Pin<Box<dyn Future<Output = Result<Value>>>>;
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
    navigation_id: u64,
    controlled: bool,
    theme: Option<Value>,
    creating: bool,
    paused: bool,
    retry: Option<tokio::time::Instant>,
    backoff: u64,
    dirty: bool,
    preview_task: Option<PreviewTask>,
    summary_task: Option<SummaryTask>,
    program_task: Option<ProgramTask>,
    programs: Value,
    directories: Value,
    program_refresh: tokio::time::Instant,
    preview_updates: BTreeMap<String, Value>,
    session_details: Value,
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
            navigation_id: 0,
            controlled: false,
            theme: None,
            creating: false,
            paused: false,
            retry: None,
            backoff: 1,
            dirty: false,
            preview_task: None,
            summary_task: None,
            program_task: None,
            programs: json!({}),
            directories: json!({}),
            program_refresh: tokio::time::Instant::now(),
            preview_updates: BTreeMap::new(),
            session_details: json!({}),
        }
    }
    fn publish(&mut self) {
        if let Ok(mut shared) = self.shared.lock() {
            shared.revision += 1;
            shared.json=json!({"revision":shared.revision,"phase":self.phase,"endpoint":self.server,
                "identity":self.identity,"error":self.error,"notice":self.notice,"sessions":self.sessions,
                "windows":self.windows,"screen":self.screen,"selectedSession":self.selected_session,
                "selectedBlock":self.selected_block,"navigationId":self.navigation_id,"controlled":self.controlled,"creating":self.creating,"sessionDetails":self.session_details,"programs":self.programs,"directories":self.directories}).to_string();
            shared
                .pending_previews
                .extend(std::mem::take(&mut self.preview_updates));
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
                    let navigation_id=value["navigationId"].as_u64();
                    if let Err(e)=self.command(value).await {
                        if let Some(id)=navigation_id {self.navigation_id=id;}
                        self.creating=false;
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
                result=async {match &mut self.preview_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.preview_task=None;
                    if let Ok(frames)=result {for(id,frame) in frames {self.preview_updates.insert(id,frame);}self.publish();}
                },
                result=async {match &mut self.summary_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.summary_task=None;
                    if let Ok(details)=result {self.session_details=details;self.publish();}
                },
                result=async {match &mut self.program_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.program_task=None;
                    if let Ok(details)=result {self.programs=details["programs"].clone();self.directories=details["directories"].clone();self.publish();}
                },
                _=async {if active {paint.tick().await;}else{pending::<()>().await;}}=>{
                    if self.retry.is_some_and(|at|at<=tokio::time::Instant::now())&&!self.paused {
                        self.retry=None;
                        if let Err(e)=self.reconnect().await{self.recover(e);}
                    }
                    if self.phase=="connected" && self.program_task.is_none() && !self.selected_session.is_empty()
                        && self.program_refresh<=tokio::time::Instant::now()
                    {
                        let ids=self.windows.as_array().into_iter().flatten().flat_map(|w|
                            w["blocks"].as_array().into_iter().flatten().filter_map(|b|b["id"].as_str().map(str::to_owned))).collect();
                        self.program_task=Some(program_task(self.endpoint.clone().unwrap(),self.selected_session.clone(),ids));
                        self.program_refresh=tokio::time::Instant::now()+Duration::from_secs(4);
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
        self.preview_task = None;
        self.summary_task = None;
        self.program_task = None;
        self.preview_updates.clear();
        self.session_details = json!({});
        self.programs = json!({});
        self.directories = json!({});
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
        self.preview_task = None;
        self.summary_task = None;
        self.program_task = None;
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
                self.apply_theme().await?;
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
        let next = json!(windows);
        let ids = |windows: &Value| -> Vec<String> {
            windows
                .as_array()
                .into_iter()
                .flatten()
                .flat_map(|w| {
                    w["blocks"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(|b| b["id"].as_str().map(str::to_owned))
                })
                .collect()
        };
        if ids(&self.windows) != ids(&next) {
            self.program_task = None;
            self.programs = json!({});
            self.directories = json!({});
            self.program_refresh = tokio::time::Instant::now();
        }
        self.windows = next;
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
            if matches!(
                event["type"].as_str(),
                Some(
                    "process_changed"
                        | "program_status_changed"
                        | "program_status_removed"
                        | "child_exited"
                )
            ) {
                self.program_refresh = tokio::time::Instant::now();
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
            "peek" => {
                if self.phase != "connected"
                    || self.paused
                    || value["session"].as_str() != Some(self.selected_session.as_str())
                {
                    return Ok(());
                }
                let ids: Vec<String> = value["ids"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(Value::as_str)
                    .filter(|id| {
                        self.windows.as_array().into_iter().flatten().any(|w| {
                            w["blocks"]
                                .as_array()
                                .into_iter()
                                .flatten()
                                .any(|b| b["id"].as_str() == Some(*id))
                        })
                    })
                    .take(2)
                    .map(str::to_owned)
                    .collect();
                if !ids.is_empty() {
                    self.preview_task = Some(preview_task(
                        self.endpoint.clone().context("no server")?,
                        self.selected_session.clone(),
                        ids,
                    ));
                }
            }
            "sessions_info" => {
                if self.phase == "connected" {
                    let sessions = self.sessions.clone();
                    let own = self
                        .control
                        .as_ref()
                        .and_then(Control::client_id)
                        .unwrap_or("")
                        .to_owned();
                    self.summary_task = Some(summary_task(
                        self.endpoint.clone().context("no server")?,
                        sessions,
                        own,
                    ));
                }
            }
            "new_session" => {
                ensure!(
                    self.phase == "connected" && !self.paused,
                    "Connect before creating a session"
                );
                self.creating = true;
                self.publish();
                let label = value["label"].as_str().unwrap_or("").trim();
                ensure!(
                    label.chars().count() <= 128,
                    "Use a session name of at most 128 characters"
                );
                let result=self.control.as_mut().context("not connected")?.call("session.create",json!({"label":label,"initial_windows":[{"window_label":"shell","layout":{"block":{"flavor":"com.superlogical.terminal.shell"}}}]})).await?;
                let id = result["session_id"]
                    .as_str()
                    .context("No session ID")?
                    .to_owned();
                let block = result["initial_windows"][0]["block_ids"][0]
                    .as_str()
                    .context("No shell block")?
                    .to_owned();
                self.release_selected().await?;
                if let Some(mut data) = self.data.take() {
                    let _ = data.close().await;
                }
                if !self.selected_session.is_empty() {
                    let _ = self
                        .control
                        .as_mut()
                        .unwrap()
                        .call(
                            "session.detach",
                            json!({"session_id":self.selected_session}),
                        )
                        .await;
                }
                let attached = self.control.as_mut().unwrap().attach(&id).await?;
                self.selected_session = id;
                self.selected_block.clear();
                self.set_view(&attached["view"]);
                self.sessions = self
                    .control
                    .as_mut()
                    .unwrap()
                    .call("session.list", json!({}))
                    .await?["sessions"]
                    .clone();
                self.open_block(block).await?;
                self.creating = false;
                self.publish();
            }
            "session" => {
                self.preview_task = None;
                self.summary_task = None;
                self.program_task = None;
                self.programs = json!({});
                self.directories = json!({});
                self.program_refresh = tokio::time::Instant::now();
                self.preview_updates.clear();
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
                self.navigation_id = value["navigationId"].as_u64().unwrap_or(0);
                self.set_view(&attached["view"]);
                self.error.clear();
                self.publish();
            }
            "block" => {
                self.navigation_id = value["navigationId"].as_u64().unwrap_or(0);
                let block = value["id"]
                    .as_str()
                    .context("block ID required")?
                    .to_owned();
                self.open_block(block).await?;
            }
            "new_tab" | "new_pane" => {
                ensure!(
                    self.phase == "connected" && !self.paused,
                    "Connect before creating a terminal"
                );
                ensure!(!self.selected_session.is_empty(), "Choose a session first");
                ensure!(
                    value["session"].as_str() == Some(self.selected_session.as_str()),
                    "The selected session changed; try again"
                );
                let layout = json!({"block":{"flavor":"com.superlogical.terminal.shell"}});
                let (method, args) = if value["type"] == "new_tab" {
                    (
                        "session.new_window",
                        json!({"session_id":self.selected_session,"window_label":format!("Terminal {}", self.windows.as_array().map_or(0, Vec::len) + 1),"layout":layout,"focus":false}),
                    )
                } else {
                    ensure!(!self.selected_block.is_empty(), "Choose a pane first");
                    ensure!(
                        value["anchor"].as_str() == Some(self.selected_block.as_str()),
                        "The selected pane changed; try again"
                    );
                    (
                        "session.new_split",
                        json!({"session_id":self.selected_session,"anchor_id":self.selected_block,"direction":"horizontal","side":"after","layout":layout,"focus":false}),
                    )
                };
                self.creating = true;
                self.error.clear();
                self.publish();
                // Creation is sent once. A lost reply must never duplicate a shell.
                let result = self
                    .control
                    .as_mut()
                    .context("not connected")?
                    .call(method, args)
                    .await?;
                let block = result["block_ids"][0]
                    .as_str()
                    .context("Server created no terminal block")?
                    .to_owned();
                self.open_block(block).await?;
                self.creating = false;
                self.publish();
            }
            "theme" => {
                let args = value["args"].clone();
                ensure!(args.is_object(), "theme arguments required");
                ensure!(
                    matches!(args["scheme"].as_str(), Some("light" | "dark")),
                    "invalid theme scheme"
                );
                self.theme = Some(args);
                self.apply_theme().await?;
            }
            "take" => {
                ensure!(!self.selected_block.is_empty(), "Choose a terminal first");
                self.control.as_mut().context("not connected")?.call("session.own_block",json!({"session_id":self.selected_session,"block_ids":[self.selected_block],"mode":"take"})).await?;
                self.refresh_view().await?;
                self.apply_theme().await?;
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
    async fn open_block(&mut self, block: String) -> Result<()> {
        ensure!(!self.selected_session.is_empty(), "Choose a session first");
        self.release_selected().await?;
        if let Some(mut data) = self.data.take() {
            let _ = data.close().await;
        }
        self.data_resume = None;
        self.selected_block = block;
        self.controlled = false;
        self.screen = Value::Null;
        self.apply_theme().await?;
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
        Ok(())
    }
    async fn apply_theme(&mut self) -> Result<()> {
        // Rex applies the owner's colors, or queues ours until we take ownership.
        // Its canonical terminal answers OSC queries and sends scheme notifications.
        if !self.paused
            && !self.selected_session.is_empty()
            && !self.selected_block.is_empty()
            && let (Some(control), Some(theme)) = (&mut self.control, &self.theme)
        {
            control
                .terminal(
                    &self.selected_session,
                    &self.selected_block,
                    "set_theme",
                    theme.clone(),
                )
                .await?;
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
fn preview_task(endpoint: Endpoint, session: String, ids: Vec<String>) -> PreviewTask {
    Box::pin(async move {
        tokio::time::timeout(Duration::from_secs(5), async {
            let mut control = Control::connect(endpoint.clone(), None, Trace::default()).await?;
            control
                .call("client.set_label", json!({"label":"rex-android-preview"}))
                .await?;
            control.attach(&session).await?;
            let mut frames = Vec::new();
            for id in ids {
                let result = async {
                    let mut data =
                        Data::open(&endpoint, &mut control, &session, &id, Trace::default())
                            .await?;
                    let mut replica = Replica::new(data.nonce);
                    while !replica.ready() {
                        replica.feed(&data.read().await?)?;
                    }
                    let frame = frame_json(replica.terminal()?.frame()?);
                    let _ = data.close().await;
                    Ok::<_, anyhow::Error>(frame)
                }
                .await;
                if let Ok(frame) = result {
                    frames.push((id, frame));
                }
            }
            let _ = control.close().await;
            Ok::<_, anyhow::Error>(frames)
        })
        .await
        .context("pane preview timed out")?
    })
}
// Executable identifiers and a display directory cross JNI; never command arguments or user IDs.
fn program_candidates(process: &Value, status: &Value) -> Value {
    let foreground = &process["foreground"];
    let mut names: Vec<String> = Vec::new();
    let mut add = |value: &Value| {
        if let Some(name) = value.as_str().filter(|s| !s.is_empty()) {
            let name: String = name.chars().take(512).collect();
            if !names.contains(&name) {
                names.push(name);
            }
        }
    };
    if foreground["pid"].is_number() && foreground["name"].is_string() {
        for record in status["records"].as_array().into_iter().flatten() {
            // Never label a new foreground program using an old status record.
            if record["owner"]["pid"] == foreground["pid"]
                && record["owner"]["name"] == foreground["name"]
            {
                add(&record["effective_app"]);
                add(&record["app"]);
            }
        }
    }
    let source = if foreground.is_object() {
        foreground
    } else {
        &process["child"]
    };
    for key in ["script", "argv0", "invoked_path", "path", "name"] {
        add(&source[key]);
    }
    json!(names)
}
fn program_directory(process: &Value) -> String {
    let source = if process["foreground"].is_object() {
        &process["foreground"]
    } else {
        &process["child"]
    };
    let cwd = source["cwd"].as_str().unwrap_or("");
    let home = process["home"].as_str().unwrap_or("").trim_end_matches('/');
    let display = if !home.is_empty() && cwd == home {
        "~".to_owned()
    } else if !home.is_empty()
        && let Some(relative) = cwd.strip_prefix(&format!("{home}/"))
    {
        format!("~/{relative}")
    } else {
        cwd.to_owned()
    };
    display
        .chars()
        .filter(|c| !c.is_control())
        .take(1024)
        .collect()
}
fn program_task(endpoint: Endpoint, session: String, ids: Vec<String>) -> ProgramTask {
    Box::pin(async move {
        tokio::time::timeout(Duration::from_secs(8), async {
            let mut control = Control::connect(endpoint, None, Trace::default()).await?;
            control
                .call("client.set_label", json!({"label":"rex-android-programs"}))
                .await?;
            control.attach(&session).await?;
            let mut programs = serde_json::Map::new();
            let mut directories = serde_json::Map::new();
            for id in ids.into_iter().take(64) {
                if let Ok(process) = control.terminal(&session, &id, "process", json!({})).await {
                    let status = control
                        .terminal(&session, &id, "program_status", json!({}))
                        .await
                        .unwrap_or(Value::Null);
                    directories.insert(id.clone(), json!(program_directory(&process)));
                    programs.insert(id, program_candidates(&process, &status));
                }
            }
            let _ = control.close().await;
            Ok::<_, anyhow::Error>(json!({"programs":programs,"directories":directories}))
        })
        .await
        .context("program metadata timed out")?
    })
}
fn summary_task(endpoint: Endpoint, sessions: Value, own: String) -> SummaryTask {
    Box::pin(async move {
        tokio::time::timeout(Duration::from_secs(8), async {
            let mut control = Control::connect(endpoint, None, Trace::default()).await?;
            control
                .call("client.set_label", json!({"label":"rex-android-summary"}))
                .await?;
            let clients = control
                .call("client.list", json!({"kinds":["app"]}))
                .await?["clients"]
                .clone();
            let mut details = serde_json::Map::new();
            for session in sessions.as_array().into_iter().flatten().take(64) {
                let Some(id) = session["session_id"].as_str() else {
                    continue;
                };
                let mut attachments = Vec::new();
                let mut local = false;
                let mut lost = false;
                for client in clients.as_array().into_iter().flatten() {
                    if client["info"]["label"].as_str().is_some_and(|s| {
                        s.starts_with("rex-android-preview")
                            || s.starts_with("rex-android-summary")
                            || s.starts_with("rex-android-programs")
                    }) {
                        continue;
                    }
                    if !client["session_ids"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .any(|s| s.as_str() == Some(id))
                    {
                        continue;
                    }
                    if client["connection_state"] == "lost" {
                        lost = true;
                        continue;
                    }
                    if client["client_id"].as_str() == Some(own.as_str()) {
                        local = true;
                    } else {
                        let device = client["principal"]["node"]
                            .as_str()
                            .filter(|s| !s.is_empty())
                            .or(client["info"]["platform"].as_str())
                            .unwrap_or("another device");
                        if !attachments.iter().any(|s| s == device) {
                            attachments.push(device.to_owned());
                        }
                    }
                }
                let attachment = if local {
                    "this device".to_owned()
                } else if !attachments.is_empty() {
                    format!("attached on {}", attachments.join(", "))
                } else if lost {
                    "reconnecting".to_owned()
                } else {
                    "detached".to_owned()
                };
                if let Ok(attached) = control.attach(id).await {
                    let windows = &attached["view"]["windows"];
                    let tabs = windows.as_array().map_or(0, Vec::len);
                    let panes = windows
                        .as_array()
                        .into_iter()
                        .flatten()
                        .flat_map(|w| w["layers"].as_array().into_iter().flatten())
                        .flat_map(|l| l["blocks"].as_array().into_iter().flatten())
                        .filter(|b| b["creator_name"] == "com.superlogical.terminal")
                        .count();
                    details.insert(
                        id.to_owned(),
                        json!({"attachment":attachment,"tabs":tabs,"panes":panes}),
                    );
                    let _ = control
                        .call("session.detach", json!({"session_id":id}))
                        .await;
                }
            }
            let _ = control.close().await;
            Ok::<_, anyhow::Error>(Value::Object(details))
        })
        .await
        .context("session details timed out")?
    })
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
    fn header_directory_abbreviates_only_the_real_home_and_prefers_foreground() {
        assert_eq!(
            program_directory(
                &json!({"home":"/home/al","foreground":{"cwd":"/home/al/code"},"child":{"cwd":"/old"}})
            ),
            "~/code"
        );
        assert_eq!(
            program_directory(&json!({"home":"/home/al/","foreground":{"cwd":"/home/al"}})),
            "~"
        );
        assert_eq!(
            program_directory(&json!({"home":"/home/al","foreground":{"cwd":"/home/alice/code"}})),
            "/home/alice/code"
        );
        assert_eq!(program_directory(&json!({"child":{"cwd":"/tmp"}})), "/tmp");
        assert_eq!(
            program_directory(&json!({"foreground":{"name":"private"},"child":{"cwd":"/old"}})),
            ""
        );
    }
    #[test]
    fn program_candidates_ignore_stale_status_owners_and_private_process_fields() {
        let process = json!({"foreground":{"pid":12,"name":"node","script":"/tools/agent.js","argv0":"node","cwd":"/private/work","argv":["secret"]}});
        let status = json!({"records":[
            {"app":"wrong-pid","owner":{"pid":11,"name":"node"}},
            {"app":"reused-pid","owner":{"pid":12,"name":"python"}},
            {"effective_app":"claude-code","owner":{"pid":12,"name":"node"}}
        ]});
        assert_eq!(
            program_candidates(&process, &status),
            json!(["claude-code", "/tools/agent.js", "node"])
        );
        assert_eq!(
            program_candidates(
                &json!({"foreground":null,"child":{"argv0":"-fish","name":"fish"}}),
                &status
            ),
            json!(["-fish", "fish"])
        );
        assert_eq!(program_candidates(&json!({}), &status), json!([]));
    }
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
            app.command(json!({"type":"block","id":block,"navigationId":17}))
                .unwrap();
            wait(&app, |v| {
                text(v).contains("ACTOR_READY") && v["navigationId"] == 17
            });
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
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server with nvim"]
    fn live_neovim_theme_follows_ownership_and_survives_pause() {
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
        let created = runtime.block_on(writer.call("session.create", json!({
            "label":format!("rex-android-nvim-test-{}",std::process::id()),
            "initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell",
                "options":{"shell":"none","command":["/bin/sh","-c",
                    "exec nvim --clean -n -i NONE -c 'set laststatus=2 statusline=BG=%{&background}'"]}}}}]
        }))).unwrap();
        let id = created["session_id"].as_str().unwrap();
        let block = created["initial_windows"][0]["block_ids"][0]
            .as_str()
            .unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let theme = |light: bool| {
            json!({"scheme":if light {"light"} else {"dark"},
            "background":if light {"#ffffff"} else {"#11120f"},
            "foreground":if light {"#202432"} else {"#d9dccf"}})
        };
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            runtime
                .block_on(writer.call(
                    "session.own_block",
                    json!({"session_id":id,"block_ids":[block],"mode":"take"}),
                ))
                .unwrap();
            runtime
                .block_on(writer.terminal(id, block, "set_theme", theme(false)))
                .unwrap();
            let app = Session::spawn().unwrap();
            // Store the resolved appearance before connecting, as Compose does.
            app.command(json!({"type":"theme","args":theme(true)}))
                .unwrap();
            app.command(json!({"type":"connect","server":server}))
                .unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":id})).unwrap();
            wait(&app, |v| v["selectedSession"] == id);
            app.command(json!({"type":"block","id":block})).unwrap();
            wait(&app, |v| text(v).contains("BG=dark"));
            app.command(json!({"type":"take"})).unwrap();
            wait(&app, |v| {
                v["controlled"] == true && text(v).contains("BG=light")
            });
            app.command(json!({"type":"theme","args":theme(false)}))
                .unwrap();
            wait(&app, |v| text(v).contains("BG=dark"));
            app.command(json!({"type":"pause"})).unwrap();
            wait(&app, |v| v["phase"] == "paused");
            // Another client owns the terminal while Android is suspended.
            runtime
                .block_on(writer.call(
                    "session.own_block",
                    json!({"session_id":id,"block_ids":[block],"mode":"take"}),
                ))
                .unwrap();
            runtime
                .block_on(writer.terminal(id, block, "set_theme", theme(false)))
                .unwrap();
            app.command(json!({"type":"theme","args":theme(true)}))
                .unwrap();
            app.command(json!({"type":"resume"})).unwrap();
            wait(&app, |v| {
                v["phase"] == "connected" && text(v).contains("BG=dark")
            });
            app.command(json!({"type":"take"})).unwrap();
            wait(&app, |v| {
                v["controlled"] == true && text(v).contains("BG=light")
            });
            app.command(json!({"type":"theme","args":theme(false)}))
                .unwrap();
            wait(&app, |v| text(v).contains("BG=dark"));
            wait(&app, |v| {
                v["programs"][block].as_array().is_some_and(|a| {
                    a.iter().any(|n| {
                        n.as_str()
                            .is_some_and(|n| n == "nvim" || n.ends_with("/nvim"))
                    })
                })
            });
            app.command(json!({"type":"disconnect"})).unwrap();
            wait(&app, |v| v["phase"] == "disconnected");
        }));
        runtime
            .block_on(writer.call("session.destroy", json!({"session_id":id})))
            .unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_program_icons_follow_reports_in_selected_and_background_tabs() {
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
        let script = r#"stty -echo; printf '\033]7501;state=idle:app=claude-code\007ICON_READY\n'; while IFS= read -r line; do case "$line" in codex) printf '\033]7501;state=idle:app=codex\007';; clear) printf '\033]7501;state=clear\007';; esac; done"#;
        let created=runtime.block_on(writer.call("session.create",json!({
            "label":format!("rex-android-program-test-{}",std::process::id()),
            "initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script]}}}},
                {"layout":{"block":{"flavor":"com.superlogical.terminal.shell"}}}]
        }))).unwrap();
        let id = created["session_id"].as_str().unwrap();
        let first = created["initial_windows"][0]["block_ids"][0]
            .as_str()
            .unwrap();
        let second = created["initial_windows"][1]["block_ids"][0]
            .as_str()
            .unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app = Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":server}))
                .unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":id})).unwrap();
            wait(&app, |v| v["selectedSession"] == id);
            app.command(json!({"type":"block","id":first})).unwrap();
            wait(&app, |v| {
                text(v).contains("ICON_READY") && v["programs"][first][0] == "claude-code"
            });
            runtime
                .block_on(writer.terminal(id, first, "write", json!({"data":"codex\n"})))
                .unwrap();
            wait(&app, |v| v["programs"][first][0] == "codex");
            app.command(json!({"type":"block","id":second})).unwrap();
            wait(&app, |v| {
                v["selectedBlock"] == second && !v["screen"].is_null()
            });
            runtime
                .block_on(writer.terminal(id, first, "write", json!({"data":"clear\n"})))
                .unwrap();
            let updated = wait(&app, |v| {
                v["programs"][first].as_array().is_some_and(|a| {
                    !a.is_empty() && a.iter().all(|x| x != "codex" && x != "claude-code")
                })
            });
            assert_eq!(updated["selectedBlock"], second);
            assert_eq!(updated["controlled"], false);
            app.command(json!({"type":"disconnect"})).unwrap();
            wait(&app, |v| v["phase"] == "disconnected");
        }));
        runtime
            .block_on(writer.call("session.destroy", json!({"session_id":id})))
            .unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_actor_creates_tabs_and_panes_and_rejects_stale_targets() {
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
        let created = runtime.block_on(writer.call("session.create", json!({
            "label":format!("rex-android-create-test-{}", std::process::id()),
            "initial_windows":[{"window_label":"Original", "layout":{"block":{"flavor":"com.superlogical.terminal.shell"}}}]
        }))).unwrap();
        let id = created["session_id"].as_str().unwrap();
        let original = created["initial_windows"][0]["block_ids"][0]
            .as_str()
            .unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app = Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":server}))
                .unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":id})).unwrap();
            wait(&app, |v| v["selectedSession"] == id);
            app.command(json!({"type":"block","id":original})).unwrap();
            wait(&app, |v| !v["screen"].is_null());
            app.command(json!({"type":"new_tab","session":"stale-session"}))
                .unwrap();
            wait(&app, |v| {
                v["error"]
                    .as_str()
                    .unwrap_or("")
                    .contains("selected session changed")
            });
            app.command(json!({"type":"new_tab","session":id})).unwrap();
            let tab = wait(&app, |v| {
                v["windows"].as_array().is_some_and(|w| w.len() == 2)
                    && v["selectedBlock"] != original
                    && !v["screen"].is_null()
                    && v["creating"] == false
            });
            let new_block = tab["selectedBlock"].as_str().unwrap();
            app.command(json!({"type":"new_pane","session":id,"anchor":"stale-pane"}))
                .unwrap();
            wait(&app, |v| {
                v["error"]
                    .as_str()
                    .unwrap_or("")
                    .contains("selected pane changed")
            });
            app.command(json!({"type":"new_pane","session":id,"anchor":new_block}))
                .unwrap();
            let pane = wait(&app, |v| {
                v["windows"].as_array().is_some_and(|windows| {
                    windows
                        .iter()
                        .any(|w| w["blocks"].as_array().is_some_and(|b| b.len() == 2))
                }) && v["selectedBlock"] != new_block
                    && !v["screen"].is_null()
                    && v["creating"] == false
            });
            assert_eq!(pane["windows"][0]["blocks"].as_array().unwrap().len(), 1);
            assert_eq!(pane["windows"][1]["blocks"].as_array().unwrap().len(), 2);
            assert!(pane["error"].as_str().unwrap().is_empty());
            app.command(json!({"type":"peek","session":id,"ids":[original]}))
                .unwrap();
            let preview = wait(&app, |v| !v["previews"][original].is_null());
            assert_eq!(preview["selectedBlock"], pane["selectedBlock"]);
            assert_eq!(preview["controlled"], pane["controlled"]);
            app.command(json!({"type":"sessions_info"})).unwrap();
            let summary = wait(&app, |v| v["sessionDetails"][id]["tabs"] == 2);
            assert_eq!(summary["sessionDetails"][id]["panes"], 3);
            assert_eq!(summary["sessionDetails"][id]["attachment"], "this device");
            let metadata = wait(&app, |v| {
                v["programs"].as_object().is_some_and(|p| p.len() == 3)
            });
            assert_eq!(metadata["selectedBlock"], pane["selectedBlock"]);
            assert_eq!(metadata["controlled"], pane["controlled"]);
            assert_eq!(metadata["directories"].as_object().unwrap().len(), 3);

            app.command(json!({"type":"new_session","label":format!("rex-android-new-session-test-{}",std::process::id())})).unwrap();
            let fresh = wait(&app, |v| {
                v["selectedSession"] != id
                    && !v["selectedSession"].as_str().unwrap_or("").is_empty()
                    && !v["screen"].is_null()
                    && v["creating"] == false
            });
            assert_eq!(fresh["windows"].as_array().unwrap().len(), 1);
            assert_eq!(fresh["windows"][0]["blocks"].as_array().unwrap().len(), 1);
            app.command(json!({"type":"disconnect"})).unwrap();
            wait(&app, |v| v["phase"] == "disconnected");
        }));
        let listed = runtime
            .block_on(writer.call("session.list", json!({})))
            .unwrap();
        let label = format!("rex-android-new-session-test-{}", std::process::id());
        for session in listed["sessions"].as_array().into_iter().flatten() {
            if session["label"] == label {
                let fresh = session["session_id"].as_str().unwrap();
                runtime.block_on(writer.attach(fresh)).unwrap();
                runtime
                    .block_on(writer.call("session.destroy", json!({"session_id":fresh})))
                    .unwrap();
            }
        }
        runtime
            .block_on(writer.call("session.destroy", json!({"session_id":id})))
            .unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }
}
