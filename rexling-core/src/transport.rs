use anyhow::{Context, Result, bail, ensure};
use futures_util::{Sink, Stream};
use percent_encoding::percent_decode_str;
use std::time::Duration;
use tokio_tungstenite::{
    client_async_with_config, connect_async_with_config,
    tungstenite::{self, Message, client::IntoClientRequest, protocol::WebSocketConfig},
};
use url::Url;

pub const CONTROL: &str = "rex.control.v1";
pub const TERMINAL: &str = "rex.data.terminal.v1";
pub const MAX_MESSAGE: usize = 1_048_576;

pub trait Socket:
    Stream<Item = Result<Message, tungstenite::Error>>
    + Sink<Message, Error = tungstenite::Error>
    + Unpin
    + Send
{
}
impl<T> Socket for T where
    T: Stream<Item = Result<Message, tungstenite::Error>>
        + Sink<Message, Error = tungstenite::Error>
        + Unpin
        + Send
{
}
pub type Ws = Box<dyn Socket>;

#[derive(Clone)]
pub struct Endpoint {
    url: Url,
    pub timeout: Duration,
    ssh: Option<std::sync::Arc<crate::ssh::Profile>>,
}

impl Endpoint {
    pub fn new(server: &str, timeout: Duration) -> Result<Self> {
        let url = Url::parse(server).context("use an explicit unix://, https://, http://, wss://, or ws:// URL (host labels are CLI-only)")?;
        ensure!(
            matches!(url.scheme(), "unix" | "https" | "http" | "wss" | "ws" | "ssh"),
            "unsupported server scheme"
        );
        ensure!(
            (url.scheme() == "ssh" || url.username().is_empty()) && url.password().is_none(),
            "login-server account selection is not implemented; URL credentials are not accepted"
        );
        ensure!(
            url.query().is_none() && url.fragment().is_none(),
            "server URL must not contain a query or fragment"
        );
        if url.scheme() == "unix" {
            ensure!(
                url.host_str().is_none() && !url.path().is_empty(),
                "use unix:///absolute/socket/path"
            );
        } else {
            ensure!(
                url.host_str().is_some() && matches!(url.path(), "" | "/"),
                "network URL must name a server root"
            );
        }
        ensure!(!timeout.is_zero(), "timeout must be positive");
        Ok(Self { url, timeout, ssh: None })
    }

    pub fn with_ssh(mut self, id: u64) -> Result<Self> {
        ensure!(self.url.scheme() == "ssh", "SSH transport requires an ssh:// endpoint");
        self.ssh = Some(crate::ssh::SshManager::global().profile(id)?);
        Ok(self)
    }
    pub fn ssh_blocked(&self) -> bool {self.ssh.as_ref().is_some_and(|profile|profile.blocked())}
    pub fn retry_ssh(&self) -> bool {
        if let Some(profile) = &self.ssh {
            crate::ssh::SshManager::global().retry(profile.clone());
            true
        } else {
            false
        }
    }

    pub async fn open(&self, path: &str, protocol: &str) -> Result<Ws> {
        if self.url.scheme() == "ssh" {
            let profile = self.ssh.clone().context("SSH credentials have not been configured")?;
            // The shared handshake has its own 20s deadline, independent of WS/Rex deadlines.
            let stream = crate::ssh::SshManager::global().open(profile).await?;
            let mut request = format!("ws://localhost{path}").into_client_request()?;
            request.headers_mut().insert("Sec-WebSocket-Protocol", protocol.parse()?);
            let config = WebSocketConfig::default().max_message_size(Some(MAX_MESSAGE)).max_frame_size(Some(MAX_MESSAGE));
            let (ws, response) = tokio::time::timeout(self.timeout, client_async_with_config(request, stream, Some(config)))
                .await.context("WebSocket upgrade over SSH timed out")??;
            ensure!(response.headers().get("Sec-WebSocket-Protocol").and_then(|h|h.to_str().ok()) == Some(protocol),
                "server did not negotiate {protocol}");
            return Ok(crate::socket_driver::drive(Box::new(ws)));
        }
        let unix = self.url.scheme() == "unix";
        let mut url = if unix {
            Url::parse("ws://localhost")?
        } else {
            self.url.clone()
        };
        if !unix {
            let scheme = match url.scheme() {
                "https" => "wss",
                "http" => "ws",
                s => s,
            };
            let scheme = scheme.to_owned();
            url.set_scheme(&scheme)
                .map_err(|_| anyhow::anyhow!("invalid WebSocket scheme"))?;
        }
        url.set_path(path);
        let mut request = url.as_str().into_client_request()?;
        request
            .headers_mut()
            .insert("Sec-WebSocket-Protocol", protocol.parse()?);
        // No identity headers, compression, cookies, redirects, or TLS bypasses.
        let config = WebSocketConfig::default()
            .max_message_size(Some(MAX_MESSAGE))
            .max_frame_size(Some(MAX_MESSAGE));
        let operation = async {
            let (socket, response): (Ws, _) = if unix {
                #[cfg(unix)]
                {
                    let path = percent_decode_str(self.url.path())
                        .decode_utf8()
                        .context("socket path isn't UTF-8")?;
                    let stream = tokio::net::UnixStream::connect(path.as_ref())
                        .await
                        .context("connect Unix socket")?;
                    let (ws, response) =
                        client_async_with_config(request, stream, Some(config)).await?;
                    (Box::new(ws), response)
                }
                #[cfg(not(unix))]
                {
                    bail!("Unix sockets are unsupported on this platform");
                }
            } else {
                let (ws, response) = connect_async_with_config(request, Some(config), true)
                    .await
                    .context(
                        "direct WebSocket connection (check Tailscale and the existing listener)",
                    )?;
                (Box::new(ws), response)
            };
            ensure!(
                response
                    .headers()
                    .get("Sec-WebSocket-Protocol")
                    .and_then(|h| h.to_str().ok())
                    == Some(protocol),
                "server did not negotiate {protocol}"
            );
            Ok(crate::socket_driver::drive(socket))
        };
        tokio::time::timeout(self.timeout, operation)
            .await
            .context("connection timeout")?
    }
}

/// Read application frames while servicing automatic WebSocket pong/close replies.
pub async fn receive(ws: &mut Ws) -> Result<Message> {
    use futures_util::{SinkExt, StreamExt};
    loop {
        let frame = ws.next().await.context("WebSocket ended")??;
        match frame {
            Message::Ping(_) | Message::Pong(_) => ws.flush().await?,
            Message::Close(close) => {
                let _ = ws.flush().await;
                bail!("WebSocket closed: {close:?}");
            }
            Message::Frame(_) => continue,
            frame => return Ok(frame),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn explicit_endpoints_only() {
        for url in [
            "unix:///tmp/a%20b.sock",
            "https://rex.example.ts.net",
            "http://100.100.1.2:7297",
        ] {
            assert!(Endpoint::new(url, Duration::from_secs(3)).is_ok());
        }
        for url in [
            "host-label",
            "https://user@rex",
            "https://rex/path",
            "https://rex/?token=secret",
            "ftp://rex",
        ] {
            assert!(Endpoint::new(url, Duration::from_secs(3)).is_err());
        }
    }
}
