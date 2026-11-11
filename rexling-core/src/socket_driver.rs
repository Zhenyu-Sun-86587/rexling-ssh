//! WebSocket I/O remains scheduled while the terminal actor awaits navigation/RPCs.
use crate::{diagnostics, transport::Ws};
use futures_util::{Sink, SinkExt, Stream, StreamExt};
use std::{
    collections::VecDeque,
    io,
    pin::Pin,
    sync::OnceLock,
    task::{Context, Poll},
    time::Duration,
};
use tokio::{
    runtime,
    sync::{mpsc, oneshot},
    task::JoinHandle,
};
use tokio_tungstenite::tungstenite::{Error, Message};
type Write = (Message, oneshot::Sender<Result<(), String>>);
fn error(message: impl Into<String>) -> Error {
    Error::Io(io::Error::new(
        io::ErrorKind::ConnectionAborted,
        message.into(),
    ))
}
fn runtime() -> &'static runtime::Handle {
    static HANDLE: OnceLock<runtime::Handle> = OnceLock::new();
    HANDLE.get_or_init(|| {
        let (tx, rx) = std::sync::mpsc::sync_channel(1);
        std::thread::Builder::new()
            .name("rex-websocket".into())
            .spawn(move || {
                let rt = runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .expect("WebSocket runtime");
                tx.send(rt.handle().clone()).ok();
                rt.block_on(std::future::pending::<()>());
            })
            .expect("WebSocket driver thread");
        rx.recv().expect("WebSocket runtime ready")
    })
}
struct Driven {
    incoming: mpsc::Receiver<Result<Message, Error>>,
    outgoing: futures_channel::mpsc::Sender<Write>,
    pending: VecDeque<oneshot::Receiver<Result<(), String>>>,
    task: JoinHandle<()>,
    closing: bool,
}
impl Drop for Driven {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl Stream for Driven {
    type Item = Result<Message, Error>;
    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.incoming.poll_recv(cx)
    }
}
impl Sink<Message> for Driven {
    type Error = Error;
    fn poll_ready(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Error>> {
        Pin::new(&mut self.outgoing)
            .poll_ready(cx)
            .map_err(|_| error("WebSocket driver stopped"))
    }
    fn start_send(mut self: Pin<&mut Self>, message: Message) -> Result<(), Error> {
        if matches!(message, Message::Close(_)) {
            self.closing = true;
        }
        let (tx, rx) = oneshot::channel();
        Pin::new(&mut self.outgoing)
            .start_send((message, tx))
            .map_err(|_| error("WebSocket driver stopped"))?;
        self.pending.push_back(rx);
        Ok(())
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Error>> {
        while let Some(reply) = self.pending.front_mut() {
            match Pin::new(reply).poll(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Ok(Ok(()))) => {
                    self.pending.pop_front();
                }
                Poll::Ready(Ok(Err(reason))) => return Poll::Ready(Err(error(reason))),
                Poll::Ready(Err(_)) => {
                    return Poll::Ready(Err(error("WebSocket write outcome unknown")));
                }
            }
        }
        Poll::Ready(Ok(()))
    }
    fn poll_close(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Error>> {
        if !self.closing {
            match self.as_mut().poll_ready(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Ready(Ok(())) => {}
            }
            self.as_mut().start_send(Message::Close(Some(
                tokio_tungstenite::tungstenite::protocol::CloseFrame {
                    code:
                        tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode::Normal,
                    reason: "".into(),
                },
            )))?;
            self.closing = true;
        }
        self.poll_flush(cx)
    }
}
pub fn drive(ws: Ws) -> Ws {
    drive_with_policy(ws, Duration::from_secs(20), Duration::from_secs(15))
}
fn drive_with_policy(mut ws: Ws, probe_interval: Duration, pong_timeout: Duration) -> Ws {
    let (incoming, rx) = mpsc::channel(32);
    let (tx, mut outgoing) = futures_channel::mpsc::channel::<Write>(32);
    let task=runtime().spawn(async move {
        let result=async {
            let mut timer=tokio::time::interval(Duration::from_secs(5).min(probe_interval));
            timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            timer.tick().await;
            let mut probe:Option<tokio::time::Instant>=None;
            let mut last_probe=tokio::time::Instant::now();
            loop {
                tokio::select! {
                    frame=ws.next()=>{
                        let message=frame.ok_or_else(||error("WebSocket EOF"))??;
                        match message {
                            Message::Ping(_)=>{
                                tokio::time::timeout(Duration::from_secs(5),ws.flush()).await.map_err(|_|error("WebSocket Pong write stalled"))??;
                                diagnostics::record("ws","server ping answered");
                            },
                            Message::Pong(data)=>{
                                if data.as_ref()==b"rex-health" {probe=None;diagnostics::record("ws","health pong");}
                                else {incoming.try_send(Ok(Message::Pong(data))).map_err(|_|error("WebSocket consumer stalled or closed"))?;}
                            },
                            Message::Close(frame)=>{
                                let _=tokio::time::timeout(Duration::from_secs(2),ws.flush()).await;
                                diagnostics::record("ws",format!("close code={:?}",frame.as_ref().map(|f|f.code)));
                                incoming.try_send(Ok(Message::Close(frame))).map_err(|_|error("WebSocket consumer stalled or closed"))?;
                                return Ok(());
                            },
                            Message::Frame(_)=>{},
                            message=>incoming.try_send(Ok(message)).map_err(|_|error("WebSocket consumer stalled or closed"))?,
                        }
                    },
                    write=outgoing.next()=>{
                        let Some((message,reply))=write else {return Ok(());};
                        let closing=matches!(message,Message::Close(_));
                        let result=tokio::time::timeout(Duration::from_secs(5),ws.send(message)).await.map_err(|_|error("WebSocket write stalled")).and_then(|r|r);
                        let failed=result.is_err();let _=reply.send(result.map_err(|e|e.to_string()));
                        if failed {return Err(error("WebSocket send failed"));}
                        if closing {return Ok(());}
                    },
                    _=timer.tick()=>{
                        if probe.is_some_and(|sent|sent.elapsed()>=pong_timeout) {return Err(error("WebSocket health Pong timed out"));}
                        if probe.is_none() && last_probe.elapsed()>=probe_interval {
                            tokio::time::timeout(Duration::from_secs(5),ws.send(Message::Ping(b"rex-health".to_vec().into()))).await.map_err(|_|error("WebSocket health Ping stalled"))??;
                            probe=Some(tokio::time::Instant::now());last_probe=tokio::time::Instant::now();
                            diagnostics::record("ws","health ping");
                        }
                    }
                }
            }
        }.await;
        if let Err(e)=result {diagnostics::record("ws",e.to_string());let _=incoming.try_send(Err(e));}
    });
    Box::new(Driven {
        incoming: rx,
        outgoing: tx,
        pending: VecDeque::new(),
        task,
        closing: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    async fn pair() -> (
        Ws,
        tokio_tungstenite::WebSocketStream<tokio::net::TcpStream>,
    ) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client =
            tokio_tungstenite::connect_async(format!("ws://{}", listener.local_addr().unwrap()));
        let server = async {
            let (socket, _) = listener.accept().await.unwrap();
            tokio_tungstenite::accept_async(socket).await.unwrap()
        };
        let (client, server) = tokio::join!(client, server);
        (Box::new(client.unwrap().0), server)
    }
    #[tokio::test]
    async fn server_ping_is_answered_while_actor_does_not_poll_socket() {
        let (client, mut server) = pair().await;
        let _idle = drive(client);
        server
            .send(Message::Ping(b"server-probe".to_vec().into()))
            .await
            .unwrap();
        let reply = tokio::time::timeout(Duration::from_secs(2), server.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert_eq!(reply, Message::Pong(b"server-probe".to_vec().into()));
    }
    #[tokio::test]
    async fn sink_close_waits_for_the_close_frame_to_be_written() {
        let (client, mut server) = pair().await;
        let mut socket = drive(client);
        socket.close().await.unwrap();
        let close = tokio::time::timeout(Duration::from_secs(2), server.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert!(
            matches!(close,Message::Close(Some(frame)) if frame.code==tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode::Normal)
        );
    }
    #[tokio::test]
    async fn silent_tcp_peer_is_detected_without_sending_user_input() {
        let (client, _silent_peer) = pair().await;
        let mut driven =
            drive_with_policy(client, Duration::from_millis(30), Duration::from_millis(60));
        let error = tokio::time::timeout(Duration::from_secs(2), driven.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap_err();
        assert!(error.to_string().contains("health Pong timed out"));
    }
}
