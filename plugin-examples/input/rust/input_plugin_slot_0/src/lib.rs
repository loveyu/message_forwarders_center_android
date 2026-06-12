// Rust Input Plugin Slot 0 - Pass-through with logging
// Build: cargo build --release --target aarch64-linux-android
// Requires: rustup target add aarch64-linux-android
// NDK standalone toolchain or cargo-ndk: cargo ndk -t arm64-v8a -o ./jniLibs build --release

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
struct PluginInput {
    version: Option<i32>,
    #[serde(rename = "type")]
    type_field: Option<String>,
    source: Option<String>,
    data_base64: Option<String>,
    headers: Option<std::collections::HashMap<String, String>>,
    metadata: Option<std::collections::HashMap<String, String>>,
    method: Option<String>,
    uri: Option<String>,
    status_code: Option<i32>,
    response_body: Option<String>,
    response_headers: Option<std::collections::HashMap<String, String>>,
}

#[derive(Serialize)]
struct PluginOutput {
    action: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    data_base64: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    headers: Option<std::collections::HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    metadata: Option<std::collections::HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    status_code: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    response_body: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    response_headers: Option<std::collections::HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    logs: Option<Vec<LogEntry>>,
}

#[derive(Serialize)]
struct LogEntry {
    level: String,
    message: String,
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeGetCapabilities(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    // CAP_FRONT (1) | CAP_REAR (2) = 3
    3
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeProcess(
    env: JNIEnv,
    _class: JClass,
    input_json: JString,
) -> jstring {
    let result = process_input(&env, &input_json);
    match result {
        Ok(output) => json_to_jstring(&env, &output),
        Err(e) => json_to_jstring(&env, &PluginOutput {
            action: "pass".to_string(),
            data_base64: None,
            headers: None,
            metadata: None,
            status_code: None,
            response_body: None,
            response_headers: None,
            logs: Some(vec![LogEntry {
                level: "error".to_string(),
                message: format!("Slot0: {}", e),
            }]),
        }),
    }
}

fn process_input(env: &JNIEnv, input_json: &JString) -> Result<PluginOutput, String> {
    let input_str: String = env
        .get_string(input_json)
        .map_err(|e| format!("get_string failed: {}", e))?
        .into();

    let input: PluginInput =
        serde_json::from_str(&input_str).map_err(|e| format!("json parse: {}", e))?;

    // Decode base64 for logging preview
    let data_preview = input
        .data_base64
        .as_ref()
        .and_then(|b64| base64::Engine::decode(&base64::engine::general_purpose::STANDARD, b64).ok())
        .and_then(|bytes| String::from_utf8(bytes).ok())
        .map(|s| if s.len() > 100 { format!("{}...", &s[..100]) } else { s })
        .unwrap_or_default();

    Ok(PluginOutput {
        action: "pass".to_string(),
        data_base64: None,
        headers: None,
        metadata: None,
        status_code: None,
        response_body: None,
        response_headers: None,
        logs: Some(vec![
            LogEntry {
                level: "info".to_string(),
                message: "Slot0: pass-through (Rust)".to_string(),
            },
            LogEntry {
                level: "debug".to_string(),
                message: format!("Slot0: type={}, source={}", input.type_field.as_deref().unwrap_or("?"), input.source.as_deref().unwrap_or("?")),
            },
            LogEntry {
                level: "debug".to_string(),
                message: format!("Slot0: data preview: {}", data_preview),
            },
        ]),
    })
}

fn json_to_jstring(env: &JNIEnv, output: &PluginOutput) -> jstring {
    let json = serde_json::to_string(output).unwrap_or_else(|_| r#"{"action":"pass"}"#.to_string());
    env.new_string(&json)
        .expect("Failed to create JNI string")
        .into_raw()
}
