//! Shared SSH transport. Drivers and channel destruction use a persistent runtime.
use anyhow::{Context, Result, ensure};
use futures_util::{StreamExt, stream::FuturesUnordered};
use russh::{
    Channel, ChannelMsg, ChannelStream, Disconnect,
    client::{self, Handle},
    keys::{
        Algorithm, Certificate, HashAlg, PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate,
    },
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::net::{IpAddr, SocketAddr};
use std::{
    collections::HashMap,
    fmt, io,
    pin::Pin,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    task::{Context as TaskContext, Poll},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncRead, AsyncWrite, ReadBuf},
    runtime,
    sync::{Mutex as AsyncMutex, oneshot, watch},
    time::Instant,
};
use zeroize::Zeroizing;

const HANDSHAKE: Duration = Duration::from_secs(20);
const CHANNEL: Duration = Duration::from_secs(10);
type Driver = Handle<Verifier>;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub host: String,
    #[serde(default = "default_port")]
    pub port: u16,
    pub user: String,
    #[serde(default)]
    pub address_family: AddressFamily,
    #[serde(default)]
    pub socket_path: String,
    #[serde(default)]
    pub fingerprint: String,
    /// One optional jump host; nested jump chains are rejected.
    #[serde(default)]
    pub jump_host: Option<Box<Config>>,
}
#[derive(Clone, Copy, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AddressFamily {
    #[default]
    Auto,
    Ipv4,
    Ipv6,
}
fn normalize_address(config: &mut Config) -> Result<()> {
    let input = config.host.trim();
    let (host, port) = if let Ok(ip) = input.parse::<IpAddr>() {
        (ip.to_string(), config.port)
    } else if let Ok(address) = input.parse::<SocketAddr>() {
        (address.ip().to_string(), address.port())
    } else if let Some(inner) = input.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
        (
            inner
                .parse::<std::net::Ipv6Addr>()
                .context("Invalid IPv6 address")?
                .to_string(),
            config.port,
        )
    } else if let Some((host, port)) = input.split_once(':') {
        ensure!(
            !port.contains(':'),
            "Use [IPv6]:port or a complete IPv6 address without a port"
        );
        (
            host.to_owned(),
            port.parse::<u16>().context("Invalid SSH port")?,
        )
    } else {
        (input.to_owned(), config.port)
    };
    ensure!(
        !host.is_empty()
            && !host
                .chars()
                .any(|c| c.is_whitespace() || matches!(c, '/' | '?' | '#' | '@'))
            && port > 0,
        "Invalid SSH host or port"
    );
    config.host = host;
    config.port = port;
    Ok(())
}

// Stagger both address families so an unreachable AAAA/A record cannot consume
// the entire handshake deadline before the reachable family gets a chance.
async fn connect_tcp(endpoint: &Config) -> Result<tokio::net::TcpStream> {
    let addresses: Vec<_> = tokio::net::lookup_host((endpoint.host.as_str(), endpoint.port))
        .await?
        .filter(|address| match endpoint.address_family {
            AddressFamily::Auto => true,
            AddressFamily::Ipv4 => address.is_ipv4(),
            AddressFamily::Ipv6 => address.is_ipv6(),
        })
        .take(16)
        .collect();
    ensure!(
        !addresses.is_empty(),
        "No address found for the selected IP family; check the host's AAAA/A records and your network"
    );
    let prefer_v6 = addresses[0].is_ipv6();
    let mut v4: std::collections::VecDeque<_> = addresses
        .iter()
        .copied()
        .filter(SocketAddr::is_ipv4)
        .collect();
    let mut v6: std::collections::VecDeque<_> = addresses
        .iter()
        .copied()
        .filter(SocketAddr::is_ipv6)
        .collect();
    let mut ordered = Vec::new();
    while !v4.is_empty() || !v6.is_empty() {
        if prefer_v6 {
            ordered.extend(v6.pop_front());
            ordered.extend(v4.pop_front());
        } else {
            ordered.extend(v4.pop_front());
            ordered.extend(v6.pop_front());
        }
    }
    let mut attempts = FuturesUnordered::new();
    for (index, address) in ordered.into_iter().enumerate() {
        attempts.push(async move {
            tokio::time::sleep(Duration::from_millis(250 * index as u64)).await;
            tokio::net::TcpStream::connect(address).await
        });
    }
    let mut last = None;
    while let Some(result) = attempts.next().await {
        match result {
            Ok(stream) => {
                stream.set_nodelay(true)?;
                return Ok(stream);
            }
            Err(error) => last = Some(error),
        }
    }
    Err(last.context("SSH connection failed")?.into())
}

fn default_port() -> u16 {
    22
}

#[derive(Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Credentials {
    pub private_key: Zeroizing<String>,
    pub passphrase: Zeroizing<String>,
    pub password: Zeroizing<String>,
    pub certificate: String,
    pub method: String,
    pub jump_host: Option<Box<Credentials>>,
}
#[derive(Debug)]
pub struct SshError {
    pub code: &'static str,
    pub message: String,
}
impl fmt::Display for SshError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for SshError {}
fn failure(code: &'static str, message: impl Into<String>) -> anyhow::Error {
    SshError {
        code,
        message: message.into(),
    }
    .into()
}
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct Status {
    phase: String,
    error: String,
    fingerprint: String,
    algorithm: String,
    trusted: String,
}
struct State {
    driver: Option<Arc<Connection>>,
    socket: Option<String>,
    failures: u32,
    retry_at: Instant,
    last_used: Instant,
    epoch: u64,
}
pub struct Profile {
    config: Config,
    credentials: Credentials,
    status: Arc<Mutex<Status>>,
    jump_status: Option<Arc<Mutex<Status>>>,
    state: AsyncMutex<State>,
    epoch: watch::Sender<u64>,
    active: AtomicUsize,
    removed: AtomicBool,
}
impl Profile {
    pub fn status(&self) -> Value {
        let jump = self
            .jump_status
            .as_ref()
            .zip(self.config.jump_host.as_deref());
        let (config, status, hop) = if let Some((status, config)) = jump {
            let blocked = matches!(
                status
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .phase
                    .as_str(),
                "host_key" | "host_key_changed" | "auth_failed"
            );
            if blocked {
                (config, status, "jump")
            } else {
                (&self.config, &self.status, "target")
            }
        } else {
            (&self.config, &self.status, "target")
        };
        let status = status.lock().unwrap_or_else(|e| e.into_inner());
        json!({"host":config.host,"port":config.port,"user":config.user,"hop":hop,
            "phase":status.phase,"error":status.error,"fingerprint":status.fingerprint,"algorithm":status.algorithm})
    }
    fn publish(&self, phase: &str, error: impl Into<String>) {
        crate::diagnostics::record("ssh",format!("phase={phase}"));
        let mut status = self.status.lock().unwrap_or_else(|e| e.into_inner());
        status.phase = phase.into();
        status.error = error.into();
    }
    pub fn blocked(&self) -> bool {
        self.removed.load(Ordering::Acquire)
            || matches!(
                self.status()["phase"].as_str(),
                Some("host_key" | "host_key_changed" | "auth_failed" | "removed")
            )
    }
}
struct Verifier {
    status: Arc<Mutex<Status>>,
}
impl client::Handler for Verifier {
    type Error = russh::Error;
    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> std::result::Result<bool, Self::Error> {
        let public = key.public_key();
        let fingerprint = public.fingerprint(HashAlg::Sha256).to_string();
        let mut status = self.status.lock().unwrap_or_else(|e| e.into_inner());
        status.fingerprint = fingerprint.clone();
        status.algorithm = public.algorithm().to_string();
        if status.trusted == fingerprint {
            return Ok(true);
        }
        let changed = !status.trusted.is_empty();
        status.phase = if changed {
            "host_key_changed"
        } else {
            "host_key"
        }
        .into();
        status.error = if changed {
            "SSH host key changed. Verify the new key on the host before replacing its saved trust."
        } else {
            "Confirm this SSH host key before connecting."
        }
        .into();
        Ok(false)
    }
}
pub struct SshManager {
    runtime: runtime::Handle,
    profiles: Mutex<HashMap<u64, Arc<Profile>>>,
    next: AtomicU64,
    route: Mutex<Option<String>>,
}
impl SshManager {
    pub fn global() -> &'static Self {
        static MANAGER: OnceLock<SshManager> = OnceLock::new();
        MANAGER.get_or_init(|| {
            let (tx, rx) = std::sync::mpsc::sync_channel(1);
            std::thread::Builder::new()
                .name("rex-ssh".into())
                .spawn(move || {
                    let rt = runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()
                        .expect("SSH runtime");
                    tx.send(rt.handle().clone()).expect("SSH startup");
                    rt.block_on(std::future::pending::<()>());
                })
                .expect("SSH worker");
            let manager = Self {
                runtime: rx.recv().expect("SSH runtime ready"),
                profiles: Mutex::new(HashMap::new()),
                next: AtomicU64::new(1),
                route: Mutex::new(None),
            };
            manager.runtime.spawn(async {
                let mut tick = tokio::time::interval(Duration::from_secs(30));
                loop {
                    tick.tick().await;
                    let profiles: Vec<_> = Self::global()
                        .profiles
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .values()
                        .cloned()
                        .collect();
                    for profile in profiles {
                        if profile.active.load(Ordering::Acquire) != 0 {
                            continue;
                        }
                        let driver = if let Ok(mut state) = profile.state.try_lock() {
                            if profile.active.load(Ordering::Acquire) == 0
                                && state.last_used.elapsed() > Duration::from_secs(60)
                            {
                                state.driver.take()
                            } else {
                                None
                            }
                        } else {
                            None
                        };
                        if let Some(driver) = driver {
                            let _ = tokio::time::timeout(
                                Duration::from_secs(2),
                                driver.disconnect(Disconnect::ByApplication, "idle", ""),
                            )
                            .await;
                        }
                    }
                }
            });
            manager
        })
    }
    pub fn register(&self, mut config: Config, credentials: Credentials) -> Result<u64> {
        normalize_address(&mut config)?;
        if let Some(jump) = config.jump_host.as_deref_mut() {
            normalize_address(jump)?;
        }
        ensure!(
            config.address_family != AddressFamily::Ipv6 || config.jump_host.is_none(),
            "IPv6 direct cannot use a jump host"
        );
        ensure!(
            !config.host.is_empty() && config.port > 0 && !config.user.is_empty(),
            "SSH host, port and username are required"
        );
        ensure!(
            config.socket_path.is_empty()
                || (config.socket_path.starts_with('/') && !config.socket_path.contains('\0')),
            "Use an absolute remote Unix socket path"
        );
        validate_credentials(&credentials).context("Target SSH credentials")?;
        if let Some(jump) = config.jump_host.as_deref() {
            ensure!(
                jump.jump_host.is_none(),
                "Only one SSH jump host is supported"
            );
            ensure!(
                !jump.host.is_empty() && jump.port > 0 && !jump.user.is_empty(),
                "Jump host, port and username are required"
            );
            validate_credentials(
                credentials
                    .jump_host
                    .as_deref()
                    .context("Jump host credentials missing")?,
            )
            .context("Jump host SSH credentials")?;
        }
        let now = Instant::now();
        let profile = Arc::new(Profile {
            status: Arc::new(Mutex::new(Status {
                phase: "disconnected".into(),
                trusted: config.fingerprint.clone(),
                ..Status::default()
            })),
            jump_status: config.jump_host.as_ref().map(|jump| {
                Arc::new(Mutex::new(Status {
                    phase: "disconnected".into(),
                    trusted: jump.fingerprint.clone(),
                    ..Status::default()
                }))
            }),
            config,
            credentials,
            state: AsyncMutex::new(State {
                driver: None,
                socket: None,
                failures: 0,
                retry_at: now,
                last_used: now,
                epoch: 0,
            }),
            epoch: watch::channel(0).0,
            active: AtomicUsize::new(0),
            removed: AtomicBool::new(false),
        });
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        self.profiles
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(id, profile);
        Ok(id)
    }
    pub fn profile(&self, id: u64) -> Result<Arc<Profile>> {
        self.profiles
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&id)
            .cloned()
            .context("SSH profile is no longer available")
    }
    pub fn trust(&self, id: u64, fingerprint: &str) -> Result<()> {
        let profile = self.profile(id)?;
        ensure!(
            !profile.removed.load(Ordering::Acquire),
            "SSH configuration was replaced"
        );
        for status in profile
            .jump_status
            .iter()
            .chain(std::iter::once(&profile.status))
        {
            let mut status = status.lock().unwrap_or_else(|e| e.into_inner());
            if status.phase == "host_key"
                && status.fingerprint == fingerprint
                && !fingerprint.is_empty()
            {
                status.trusted = fingerprint.into();
                status.phase = "disconnected".into();
                status.error.clear();
                return Ok(());
            }
        }
        Err(failure(
            "blocked",
            "SSH host key confirmation is stale or the saved host key changed",
        ))
    }

    pub fn remove(&self, id: u64) {
        let profile = self
            .profiles
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&id);
        if let Some(profile) = profile {
            profile.removed.store(true, Ordering::Release);
            profile.publish("removed", "SSH configuration was replaced");
            self.invalidate(profile);
        }
    }
    pub fn network(&self, route: String) {
        let mut current = self.route.lock().unwrap_or_else(|e| e.into_inner());
        if current.as_ref() == Some(&route) {
            return;
        }
        let changed = current.replace(route).is_some();
        drop(current);
        if changed {
            let profiles: Vec<_> = self
                .profiles
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .values()
                .cloned()
                .collect();
            for profile in profiles {
                self.invalidate(profile);
            }
        }
    }
    fn invalidate(&self, profile: Arc<Profile>) {
        profile
            .epoch
            .send_modify(|epoch| *epoch = epoch.wrapping_add(1));
        self.runtime.spawn(async move {
            let mut state = profile.state.lock().await;
            let generation = *profile.epoch.borrow();
            if state.epoch != generation {
                state.epoch = generation;
                state.retry_at = Instant::now();
                state.socket = None;
                let driver = state.driver.take();
                drop(state);
                if let Some(driver) = driver {
                    let _ = tokio::time::timeout(
                        Duration::from_secs(1),
                        driver.disconnect(Disconnect::ByApplication, "network changed", ""),
                    )
                    .await;
                }
            }
        });
    }
    pub async fn open(&'static self, profile: Arc<Profile>) -> Result<ManagedStream> {
        let (mut tx, rx) = oneshot::channel();
        self.runtime.spawn(async move {
            let result = self.open_owned(profile, &mut tx).await;
            let _ = tx.send(result);
        });
        rx.await.context("SSH worker stopped")?
    }
    async fn open_owned(
        &self,
        profile: Arc<Profile>,
        reply: &mut oneshot::Sender<Result<ManagedStream>>,
    ) -> Result<ManagedStream> {
        let mut epoch = profile.epoch.subscribe();
        let generation = *epoch.borrow_and_update();
        // Cancellation covers lock acquisition, backoff, authentication and discovery.
        tokio::select! {
            biased;
            _ = reply.closed() => Err(failure("canceled", "SSH channel request canceled")),
            _ = epoch.changed() => Err(failure("network", "SSH connection was replaced or network changed")),
            result = self.open_generation(profile, generation) => result,
        }
    }
    async fn open_generation(
        &self,
        profile: Arc<Profile>,
        generation: u64,
    ) -> Result<ManagedStream> {
        let mut state = loop {
            let mut state = profile.state.lock().await;
            if state.epoch != generation {
                state.epoch = generation;
                state.retry_at = Instant::now();
                state.socket = None;
                // Connection::drop disconnects on the manager runtime with a deadline.
                state.driver.take();
            }
            if state
                .driver
                .as_ref()
                .is_some_and(|driver| driver.is_closed())
            {
                state.driver.take();
                state.socket = None;
            }
            if state.driver.is_some() || state.retry_at <= Instant::now() || profile.blocked() {
                break state;
            }
            profile.publish("backoff", "SSH reconnect is cooling down");
            let retry_at = state.retry_at;
            drop(state);
            tokio::time::sleep_until(retry_at).await;
            // Recheck under the lock: another waiter may have connected or failed.
        };
        if profile.blocked() {
            return Err(failure(
                "blocked",
                profile.status()["error"]
                    .as_str()
                    .unwrap_or("SSH requires attention"),
            ));
        }
        if state.driver.is_none() {
            profile.publish("connecting", "");
            let connected = authenticate(&profile).await;
            match connected {
                Ok(driver) => {
                    state.driver = Some(Arc::new(driver));
                    state.failures = 0;
                    state.epoch = generation;
                    profile.publish("connected", "");
                }
                Err(error) => {
                    if profile.blocked() {
                        return Err(failure(
                            "blocked",
                            profile.status()["error"]
                                .as_str()
                                .unwrap_or("SSH requires attention"),
                        ));
                    }
                    if error
                        .downcast_ref::<SshError>()
                        .is_some_and(|error| error.code == "network")
                    {
                        return Err(error);
                    }
                    state.failures = state.failures.saturating_add(1);
                    let seconds = if state.failures >= 5 {
                        300
                    } else {
                        5 * (1u64 << state.failures.min(5))
                    };
                    state.retry_at = Instant::now()
                        + Duration::from_secs(seconds + rand::random::<u32>() as u64 % 5);
                    profile.publish(
                        "backoff",
                        format!("SSH connection failed; retrying after {seconds}s: {error}"),
                    );
                    return Err(error);
                }
            }
        }
        if state.socket.is_none() {
            let socket = if !profile.config.socket_path.is_empty() {
                profile.config.socket_path.clone()
            } else {
                let driver = state.driver.as_ref().context("SSH connection missing")?;
                let output = exec(driver, "id -u; printenv XDG_RUNTIME_DIR; true").await?;
                let mut lines = output.lines();
                let uid = lines
                    .next()
                    .filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
                    .context("Remote UID discovery failed; enter a socket path")?;
                let directory = lines
                    .next()
                    .filter(|s| s.starts_with('/'))
                    .map(str::to_owned)
                    .unwrap_or_else(|| format!("/run/user/{uid}"));
                format!("{directory}/rex/server.sock")
            };
            state.socket = Some(socket);
        }
        let path = state.socket.clone().context("Remote socket missing")?;
        let driver = state
            .driver
            .as_ref()
            .context("SSH connection missing")?
            .clone();
        state.last_used = Instant::now();
        // Connection initialization is serialized; independent channel opens are concurrent.
        drop(state);
        let mut channel = open_channel(&driver, &path).await;
        if channel.is_err() && profile.config.socket_path.is_empty() {
            let output = exec(&driver,
                "if command -v rex >/dev/null 2>&1; then rex --autostart=false server status --json; elif [ -x \"$HOME/.local/bin/rex\" ]; then \"$HOME/.local/bin/rex\" --autostart=false server status --json; else exit 127; fi").await;
            if let Ok(output) = output
                && let Ok(value) = serde_json::from_str::<Value>(&output)
                && let Some(found) = unix_endpoint(&value)
            {
                channel = open_channel(&driver, &found).await;
                if channel.is_ok() {
                    let mut state = profile.state.lock().await;
                    if state.epoch == generation
                        && state
                            .driver
                            .as_ref()
                            .is_some_and(|current| Arc::ptr_eq(current, &driver))
                    {
                        state.socket = Some(found);
                    }
                }
            }
        }
        let channel = channel.with_context(||format!("Could not open Rex Unix socket {path}. Ensure Rex is running or enable rex.socket on the target (systemctl --user enable --now rex.socket), and allow SSH streamlocal forwarding. This app does not start an unconfigured remote service."))?;
        let state = profile.state.lock().await;
        ensure!(
            !profile.removed.load(Ordering::Acquire) && generation == *profile.epoch.borrow(),
            "SSH connection was replaced"
        );
        profile.active.fetch_add(1, Ordering::AcqRel);
        drop(state);
        Ok(ManagedStream {
            inner: Some(channel.into_stream()),
            runtime: self.runtime.clone(),
            profile,
        })
    }
}
/// Own both SSH drivers together, including cancellation and partial authentication failure.
struct Connection {
    target: Option<Driver>,
    jump: Option<Driver>,
}
impl std::ops::Deref for Connection {
    type Target = Driver;
    fn deref(&self) -> &Driver {
        self.target.as_ref().expect("authenticated target SSH")
    }
}
impl Connection {
    fn is_closed(&self) -> bool {
        self.target.as_ref().is_none_or(Handle::is_closed)
            || self.jump.as_ref().is_some_and(Handle::is_closed)
    }
    async fn disconnect(
        &self,
        reason: Disconnect,
        message: &str,
        language: &str,
    ) -> std::result::Result<(), russh::Error> {
        let result = if let Some(target) = &self.target {
            target.disconnect(reason, message, language).await
        } else {
            Ok(())
        };
        if let Some(jump) = &self.jump {
            let _ = jump
                .disconnect(Disconnect::ByApplication, "target disconnected", "")
                .await;
        }
        result
    }
}
impl Drop for Connection {
    fn drop(&mut self) {
        let target = self.target.take();
        let jump = self.jump.take();
        SshManager::global().runtime.spawn(async move {
            for driver in target.into_iter().chain(jump) {
                let _ = tokio::time::timeout(
                    Duration::from_secs(1),
                    driver.disconnect(Disconnect::ByApplication, "connection released", ""),
                )
                .await;
            }
        });
    }
}
trait SshIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> SshIo for T {}
type HopStream = Box<dyn SshIo>;
async fn authenticate(profile: &Profile) -> Result<Connection> {
    let mut connection = Connection {
        target: None,
        jump: None,
    };
    let upstream = if let Some(jump) = profile.config.jump_host.as_deref() {
        let credentials = profile
            .credentials
            .jump_host
            .as_deref()
            .context("Jump host credentials missing")?;
        let status = profile
            .jump_status
            .as_ref()
            .context("Jump host status missing")?
            .clone();
        connection.jump =
            Some(authenticate_hop(jump, credentials, status, None, "Jump host").await?);
        let channel = tokio::time::timeout(
            CHANNEL,
            connection
                .jump
                .as_ref()
                .expect("jump authenticated")
                .channel_open_direct_tcpip(
                    &profile.config.host,
                    u32::from(profile.config.port),
                    "127.0.0.1",
                    0,
                ),
        )
        .await
        .context("Jump host TCP forwarding timed out (10s)")?
        .context(
            "Jump host could not reach the target SSH port; check routing and AllowTcpForwarding",
        )?;
        Some(Box::new(channel.into_stream()) as HopStream)
    } else {
        None
    };
    connection.target = Some(
        authenticate_hop(
            &profile.config,
            &profile.credentials,
            profile.status.clone(),
            upstream,
            "Target",
        )
        .await?,
    );
    Ok(connection)
}
async fn authenticate_hop(
    endpoint: &Config,
    credentials: &Credentials,
    status: Arc<Mutex<Status>>,
    upstream: Option<HopStream>,
    label: &str,
) -> Result<Driver> {
    let operation = async {
        let stream = if let Some(stream) = upstream {
            stream
        } else {
            let stream = connect_tcp(endpoint)
                .await
                .with_context(|| format!("{label} TCP connection failed"))?;
            stream.set_nodelay(true)?;
            Box::new(stream) as HopStream
        };
        let config = Arc::new(client::Config {
            keepalive_interval: Some(Duration::from_secs(15)),
            keepalive_max: 3,
            nodelay: true,
            ..client::Config::default()
        });
        let driver = client::connect_stream(
            config,
            stream,
            Verifier {
                status: status.clone(),
            },
        )
        .await
        .with_context(|| format!("{label} SSH handshake failed"))?;
        let mut owned = Connection {
            target: Some(driver),
            jump: None,
        };
        let driver = owned.target.as_mut().expect("SSH handshake complete");
        let auth = if credentials.method == "password" {
            driver
                .authenticate_password(&endpoint.user, credentials.password.as_str())
                .await?
        } else {
            let (key, certificate) = identity(credentials).map_err(|error| {
                let mut status = status.lock().unwrap_or_else(|e| e.into_inner());
                status.phase = "auth_failed".into();
                status.error = format!("{label}: {error:#}");
                failure("blocked", status.error.clone())
            })?;
            if let Some(certificate) = certificate {
                driver
                    .authenticate_openssh_cert(&endpoint.user, Arc::new(key), certificate)
                    .await?
            } else {
                driver
                    .authenticate_publickey(
                        &endpoint.user,
                        PrivateKeyWithHashAlg::new(Arc::new(key), None),
                    )
                    .await?
            }
        };
        if !auth.success() {
            let mut status = status.lock().unwrap_or_else(|e| e.into_inner());
            status.phase = "auth_failed".into();
            status.error = format!(
                "{label} SSH authentication rejected. Check this hop's username, key/certificate or password, then edit and save."
            );
            return Err(failure("blocked", status.error.clone()));
        }
        {
            let mut status = status.lock().unwrap_or_else(|e| e.into_inner());
            status.phase = "connected".into();
            status.error.clear();
        }
        Ok(owned.target.take().expect("authenticated SSH"))
    };
    tokio::time::timeout(HANDSHAKE, operation)
        .await
        .with_context(|| format!("{label} SSH connection and authentication timed out (20s)"))?
}
fn validate_credentials(credentials: &Credentials) -> Result<()> {
    if credentials.method == "password" {
        ensure!(!credentials.password.is_empty(), "Enter an SSH password");
    } else {
        let _ = identity(credentials)?;
    }
    Ok(())
}
fn identity(credentials: &Credentials) -> Result<(PrivateKey, Option<Certificate>)> {
    let passphrase =
        (!credentials.passphrase.is_empty()).then_some(credentials.passphrase.as_str());
    let key = russh::keys::decode_secret_key(&credentials.private_key, passphrase)
        .context("Could not unlock private key; check its format and passphrase")?;
    let certificate = if credentials.certificate.trim().is_empty() {
        None
    } else {
        let certificate = Certificate::from_openssh(credentials.certificate.trim())
            .context("Invalid OpenSSH user certificate or unsupported CA algorithm")?;
        ensure!(
            certificate.cert_type() == russh::keys::ssh_key::certificate::CertType::User,
            "Import a user certificate, not a host certificate"
        );
        ensure!(
            certificate.public_key() == key.public_key().key_data(),
            "Certificate does not match the private key"
        );
        let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs();
        ensure!(
            now >= certificate.valid_after() && now < certificate.valid_before(),
            "SSH user certificate is not currently valid"
        );
        Some(certificate)
    };
    Ok((key, certificate))
}
async fn open_channel(driver: &Driver, path: &str) -> Result<Channel<client::Msg>> {
    tokio::time::timeout(CHANNEL, driver.channel_open_direct_streamlocal(path))
        .await
        .context("SSH channel open timed out (10s)")?
        .context("SSH Unix socket forwarding failed")
}
async fn exec(driver: &Driver, command: &str) -> Result<String> {
    tokio::time::timeout(CHANNEL, async {
        let mut channel = driver.channel_open_session().await?;
        channel.exec(true, command).await?;
        let mut output = Vec::new();
        let mut exit = None;
        while let Some(message) = channel.wait().await {
            match message {
                ChannelMsg::Data { data } => {
                    ensure!(
                        output.len() + data.len() <= 65536,
                        "Remote discovery output is too large"
                    );
                    output.extend_from_slice(&data);
                }
                ChannelMsg::ExitStatus { exit_status } => exit = Some(exit_status),
                ChannelMsg::Close => break,
                _ => (),
            }
        }
        let _ = channel.close().await;
        ensure!(
            exit.is_none_or(|code| code == 0),
            "Remote Rex discovery command failed"
        );
        String::from_utf8(output).context("Remote discovery did not return UTF-8")
    })
    .await
    .context("Remote discovery timed out")?
}
fn unix_endpoint(value: &Value) -> Option<String> {
    match value {
        Value::String(s) if s.starts_with("unix:///") => {
            let url = url::Url::parse(s).ok()?;
            Some(
                percent_encoding::percent_decode_str(url.path())
                    .decode_utf8()
                    .ok()?
                    .into_owned(),
            )
        }
        Value::Object(map) => map.values().find_map(unix_endpoint),
        Value::Array(items) => items.iter().find_map(unix_endpoint),
        _ => None,
    }
}
pub struct ManagedStream {
    inner: Option<ChannelStream<client::Msg>>,
    runtime: runtime::Handle,
    profile: Arc<Profile>,
}
impl AsyncRead for ManagedStream {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        Pin::new(self.inner.as_mut().expect("live SSH stream")).poll_read(cx, buf)
    }
}
impl AsyncWrite for ManagedStream {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        Pin::new(self.inner.as_mut().expect("live SSH stream")).poll_write(cx, buf)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Pin::new(self.inner.as_mut().expect("live SSH stream")).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Pin::new(self.inner.as_mut().expect("live SSH stream")).poll_shutdown(cx)
    }
}
impl Drop for ManagedStream {
    fn drop(&mut self) {
        if let Some(mut stream) = self.inner.take() {
            let profile = self.profile.clone();
            self.runtime.spawn(async move {
                use tokio::io::AsyncWriteExt;
                let _ = tokio::time::timeout(Duration::from_secs(2), stream.shutdown()).await;
                drop(stream);
                profile.active.fetch_sub(1, Ordering::AcqRel);
            });
        }
    }
}
pub fn generate_key() -> Result<Zeroizing<Vec<u8>>> {
    let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519)
        .context("Could not generate Ed25519 key")?;
    let private = key.to_openssh(russh::keys::ssh_key::LineEnding::LF)?;
    let public = key.public_key().to_openssh()?;
    #[derive(Serialize)]
    #[serde(rename_all = "camelCase")]
    struct Generated<'a> {
        private_key: &'a str,
        public_key: &'a str,
    }
    Ok(Zeroizing::new(serde_json::to_vec(&Generated {
        private_key: &private,
        public_key: &public,
    })?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ipv6_and_host_ports_are_unambiguous() {
        for (input, host, port) in [
            ("2001:db8::22", "2001:db8::22", 22),
            ("[2001:db8::22]:2222", "2001:db8::22", 2222),
            ("[::1]", "::1", 22),
            ("rex.example:2222", "rex.example", 2222),
        ] {
            let mut config: Config =
                serde_json::from_value(json!({"host":input,"user":"test"})).unwrap();
            normalize_address(&mut config).unwrap();
            assert_eq!((config.host.as_str(), config.port), (host, port));
        }
    }
    #[tokio::test]
    async fn both_ip_families_connect_with_nodelay() {
        for (host, family) in [("127.0.0.1", "ipv4"), ("::1", "ipv6")] {
            let listener = tokio::net::TcpListener::bind((host, 0)).await.unwrap();
            let config: Config=serde_json::from_value(json!({"host":host,"port":listener.local_addr().unwrap().port(),"user":"test","addressFamily":family})).unwrap();
            let stream = tokio::time::timeout(Duration::from_secs(2), connect_tcp(&config))
                .await
                .unwrap()
                .unwrap();
            assert!(stream.nodelay().unwrap());
            let (_, peer) = listener.accept().await.unwrap();
            assert_eq!(peer.is_ipv6(), family == "ipv6");
        }
    }

    // No network required: exercise a real 5-minute backoff without waiting it out.
    #[tokio::test]
    async fn backoff_releases_state_and_cancels_on_network_change() {
        check_backoff(true).await;
    }
    #[tokio::test]
    async fn backoff_cancels_when_caller_leaves() {
        check_backoff(false).await;
    }
    async fn check_backoff(network: bool) {
        let manager = SshManager::global();
        let id = manager
            .register(
                serde_json::from_value(json!({"host":"127.0.0.1","user":"test"})).unwrap(),
                serde_json::from_value(json!({"method":"password","password":"test"})).unwrap(),
            )
            .unwrap();
        let profile = manager.profile(id).unwrap();
        profile.state.lock().await.retry_at = Instant::now() + Duration::from_secs(300);
        let (mut tx, rx) = oneshot::channel();
        let open = manager.open_owned(profile.clone(), &mut tx);
        tokio::pin!(open);
        assert!(
            tokio::time::timeout(Duration::from_millis(10), &mut open)
                .await
                .is_err()
        );
        assert!(
            profile.state.try_lock().is_ok(),
            "backoff held the state lock"
        );
        if network {
            profile.epoch.send_modify(|epoch| *epoch += 1);
        } else {
            drop(rx);
        }
        let result = tokio::time::timeout(Duration::from_secs(1), &mut open)
            .await
            .expect("cancellation waited for the 5-minute backoff");
        let error = result.err().expect("canceled open succeeded");
        assert_eq!(
            error.downcast_ref::<SshError>().unwrap().code,
            if network { "network" } else { "canceled" }
        );
        manager.remove(id);
    }
}
