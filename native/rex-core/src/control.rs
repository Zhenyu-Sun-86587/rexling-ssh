use crate::transport::{self, CONTROL, Endpoint, MAX_MESSAGE, Ws};
use anyhow::{Context, Result, bail, ensure};
use futures_util::SinkExt;
use serde_json::{Value, json};
use std::{
    collections::VecDeque,
    fs::{File, OpenOptions},
    io::Write,
    path::Path,
    sync::{Arc, Mutex},
};
use tokio_tungstenite::tungstenite::Message;

pub fn redact(value: &mut Value) {
    match value {
        Value::Object(map) => {
            for (k, v) in map {
                if matches!(k.as_str(), "reconnect_token" | "ticket") {
                    *v = json!("[REDACTED]");
                } else {
                    redact(v);
                }
            }
        }
        Value::Array(items) => {
            for v in items {
                redact(v);
            }
        }
        _ => (),
    }
}

#[derive(Clone, Default)]
pub struct Trace(Option<Arc<Mutex<File>>>);
impl Trace {
    pub fn create(path: &Path) -> Result<Self> {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        Ok(Self(Some(Arc::new(Mutex::new(options.open(path)?)))))
    }
    pub fn record(&self, direction: &str, mut value: Value) -> Result<()> {
        redact(&mut value);
        if let Some(file) = &self.0 {
            let mut file = file
                .lock()
                .map_err(|_| anyhow::anyhow!("trace lock poisoned"))?;
            serde_json::to_writer(&mut *file, &json!({"direction":direction,"message":value}))?;
            writeln!(file)?;
        }
        Ok(())
    }
}

pub struct Control {
    ws: Ws,
    endpoint: Endpoint,
    welcome: Value,
    next_id: u64,
    pub events: VecDeque<Value>,
    last_seq: u64,
    trace: Trace,
}
impl Control {
    pub async fn connect(endpoint: Endpoint, token: Option<&str>, trace: Trace) -> Result<Self> {
        let mut ws = endpoint.open("/api/control", CONTROL).await?;
        let platform = match std::env::consts::OS {
            "macos" => "macos",
            "linux" => "linux",
            _ => "other",
        };
        // The common control/data shapes have been checked against revisions 7 and 8.
        let mut hello = json!({"type":"hello","api_revision":8,"api_min_revision":7,"client":{"kind":"app","name":"rexlink","platform":platform,"version":env!("CARGO_PKG_VERSION")}});
        if let Some(token) = token {
            hello["reconnect_token"] = json!(token);
        }
        let encoded = serde_json::to_string(&hello)?;
        ensure!(encoded.len() < 512, "hello must be under 512 bytes");
        trace.record("control.send", hello)?;
        let welcome = tokio::time::timeout(endpoint.timeout, async {
            ws.send(Message::Text(encoded.into())).await?;
            let frame = transport::receive(&mut ws).await?;
            match frame {
                Message::Text(s) => Ok::<Value, anyhow::Error>(serde_json::from_str(&s)?),
                _ => bail!("expected text welcome"),
            }
        })
        .await
        .context("control hello timeout")??;
        trace.record("control.recv", welcome.clone())?;
        ensure!(
            welcome["type"] == "welcome" && welcome["protocol"] == CONTROL,
            "invalid control welcome"
        );
        ensure!(
            matches!(welcome["api_revision"].as_u64(), Some(7 | 8)),
            "unsupported negotiated API revision"
        );
        ensure!(
            welcome["limits"]["max_message_bytes"]
                .as_u64()
                .is_some_and(|n| n >= 65536),
            "invalid message limit"
        );
        Ok(Self {
            ws,
            endpoint,
            welcome,
            next_id: 1,
            events: VecDeque::new(),
            last_seq: 0,
            trace,
        })
    }
    pub fn welcome(&self) -> Value {
        let mut v = self.welcome.clone();
        redact(&mut v);
        v
    }
    pub fn token(&self) -> Option<&str> {
        self.welcome["client"]["reconnect_token"].as_str()
    }
    pub fn client_id(&self) -> Option<&str> {
        self.welcome["client"]["id"].as_str()
    }
    pub fn last_seq(&self) -> u64 {
        self.last_seq
    }
    pub fn drain_events(&mut self) {
        self.events.clear();
    }

    pub async fn next_message(&mut self) -> Result<Value> {
        loop {
            let frame = transport::receive(&mut self.ws).await?;
            let Message::Text(text) = frame else {
                bail!("binary message on control connection");
            };
            let message: Value = serde_json::from_str(&text)?;
            self.trace.record("control.recv", message.clone())?;
            if message["type"] == "event" {
                let seq = message["seq"].as_u64().context("event has no seq")?;
                ensure!(
                    seq == self.last_seq + 1,
                    "event sequence gap: expected {}, got {seq}; reload state",
                    self.last_seq + 1
                );
                self.last_seq = seq;
            }
            if matches!(message["type"].as_str(), Some("event" | "response")) {
                return Ok(message);
            }
        }
    }
    pub async fn call(&mut self, method: &str, payload: Value) -> Result<Value> {
        let id = self.next_id.to_string();
        self.next_id += 1;
        let request = json!({"type":"request","id":id,"method":method,"payload":payload});
        let text = serde_json::to_string(&request)?;
        let max = self.welcome["limits"]["max_message_bytes"]
            .as_u64()
            .unwrap() as usize;
        ensure!(
            text.len() <= max.min(MAX_MESSAGE),
            "request exceeds message limit"
        );
        self.trace.record("control.send", request)?;
        let timeout = self.endpoint.timeout;
        tokio::time::timeout(timeout, async {
            self.ws.send(Message::Text(text.into())).await?;
            loop {
                let reply = self.next_message().await?;
                if reply["type"] == "event" {
                    ensure!(
                        self.events.len() < 4096,
                        "too many queued events; reload state"
                    );
                    self.events.push_back(reply);
                    continue;
                }
                ensure!(reply["id"] == id, "unexpected response ID");
                if let Some(error) = reply.get("error").filter(|e| !e.is_null()) {
                    bail!(
                        "{method}: {} (code={}, status={})",
                        error["message"],
                        error["code"],
                        error["status"]
                    );
                }
                return Ok(reply.get("payload").cloned().unwrap_or(Value::Null));
            }
        })
        .await
        .with_context(|| {
            format!("{method} timed out; its outcome is unknown, inspect state before retrying")
        })?
    }
    pub async fn attach(&mut self, session: &str) -> Result<Value> {
        self.call("session.attach", json!({"session_id":session}))
            .await
    }
    pub async fn terminal(
        &mut self,
        session: &str,
        block: &str,
        method: &str,
        args: Value,
    ) -> Result<Value> {
        self.call(
            &format!("com.superlogical.terminal.{method}"),
            json!({"session_id":session,"block_id":block,"args":args}),
        )
        .await
    }
    pub async fn ping(&mut self) -> Result<()> {
        self.ws.send(Message::Ping(Vec::new().into())).await?;
        Ok(())
    }
    pub async fn close(&mut self) -> Result<()> {
        self.ws.close().await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn secrets_are_removed_recursively() {
        let mut v = json!({"client":{"reconnect_token":"s"},"items":[{"ticket":"s"}],"seq":4});
        redact(&mut v);
        assert_eq!(v["client"]["reconnect_token"], "[REDACTED]");
        assert_eq!(v["items"][0]["ticket"], "[REDACTED]");
        assert_eq!(v["seq"], 4);
    }
}
