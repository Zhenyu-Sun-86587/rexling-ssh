mod engine;
use engine::Session;
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jlong, jstring},
};
use std::{
    collections::HashMap,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};

static SESSIONS: OnceLock<Mutex<HashMap<u64, Session>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn sessions() -> &'static Mutex<HashMap<u64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_create(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    let result = std::panic::catch_unwind(|| -> anyhow::Result<u64> {
        let session = Session::spawn()?;
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        sessions()
            .lock()
            .map_err(|_| anyhow::anyhow!("session registry unavailable"))?
            .insert(id, session);
        Ok(id)
    });
    match result {
        Ok(Ok(id)) => id as jlong,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Could not start terminal worker",
            );
            0
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_command(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    request: JString,
) -> jstring {
    let result =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<()> {
            let request: String = env.get_string(&request)?.into();
            let value = serde_json::from_str(&request)?;
            let sessions = sessions()
                .lock()
                .map_err(|_| anyhow::anyhow!("session registry unavailable"))?;
            sessions
                .get(&(handle as u64))
                .ok_or_else(|| anyhow::anyhow!("terminal session is closed"))?
                .command(value)
        }));
    let message = match result {
        Ok(Ok(())) => String::new(),
        Ok(Err(e)) => e.to_string(),
        Err(_) => "Native command failed".into(),
    };
    string(&mut env, message)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_poll(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    after: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(|| -> anyhow::Result<String> {
        let sessions = sessions()
            .lock()
            .map_err(|_| anyhow::anyhow!("session registry unavailable"))?;
        sessions
            .get(&(handle as u64))
            .ok_or_else(|| anyhow::anyhow!("terminal session is closed"))?
            .poll(after.max(0) as u64)
    });
    string(
        &mut env,
        match result {
            Ok(Ok(value)) => value,
            _ => String::new(),
        },
    )
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = std::panic::catch_unwind(|| {
        if let Ok(mut sessions) = sessions().lock() {
            sessions.remove(&(handle as u64));
        }
    });
}
