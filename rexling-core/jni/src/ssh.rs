//! Metadata uses JSON; credentials cross JNI in byte arrays and never enter actor updates.
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JString},
    sys::{jbyteArray, jlong, jstring},
};
use rex_core::ssh::{Config, Credentials, SshManager};

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_sshCreate(
    mut env: JNIEnv,
    _: JClass,
    config: JString,
    credentials: JByteArray,
) -> jlong {
    let result =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<u64> {
            let config: String = env.get_string(&config)?.into();
            let mut bytes = env.convert_byte_array(&credentials)?;
            let secret: Result<Credentials, _> = serde_json::from_slice(&bytes);
            // Clear the transfer copy; secret fields use Zeroizing in core.
            bytes.fill(0);
            SshManager::global().register(serde_json::from_str::<Config>(&config)?, secret?)
        }));
    match result {
        Ok(Ok(id)) => id as jlong,
        other => {
            let message = match other {
                Ok(Err(e)) => format!("{e:#}"),
                _ => "Could not configure SSH".into(),
            };
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            0
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_sshStatus(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
) -> jstring {
    let value = SshManager::global()
        .profile(id as u64)
        .map(|p| p.status())
        .unwrap_or_default();
    super::string(&mut env, value.to_string())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_sshTrust(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    fingerprint: JString,
) -> jstring {
    let result = (|| -> anyhow::Result<()> {
        let fingerprint: String = env.get_string(&fingerprint)?.into();
        SshManager::global().trust(id as u64, &fingerprint)
    })();
    super::string(
        &mut env,
        result.err().map(|e| e.to_string()).unwrap_or_default(),
    )
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_sshRemove(_: JNIEnv, _: JClass, id: jlong) {
    SshManager::global().remove(id as u64);
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_sshNetwork(
    mut env: JNIEnv,
    _: JClass,
    route: JString,
) {
    if let Ok(route) = env.get_string(&route) {
        SshManager::global().network(route.into());
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_sshGenerate(
    mut env: JNIEnv,
    _: JClass,
) -> jbyteArray {
    match rex_core::ssh::generate_key() {
        Ok(bytes) => env
            .byte_array_from_slice(&bytes)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            std::ptr::null_mut()
        }
    }
}
