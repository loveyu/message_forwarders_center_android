// Rust Output Plugin Slot 1 - Data modifier with uppercase transformation
// Build: cargo ndk -t arm64-v8a -o ./jniLibs build --release

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Deserialize)]
struct OutputInput {
    source: Option<String>,
    output_type: Option<String>,
    data_base64: Option<String>,
    headers: Option<HashMap<String, String>>,
    metadata: Option<HashMap<String, String>>,
}

#[derive(Serialize)]
struct PluginOutput {
    action: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    data_base64: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    headers: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    metadata: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    logs: Option<Vec<LogEntry>>,
}

#[derive(Serialize)]
struct LogEntry {
    level: String,
    message: String,
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_output_plugin_OutputSlot1_nativeGetCapabilities(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1 // CAP_FRONT
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_output_plugin_OutputSlot1_nativeProcess(
    env: JNIEnv,
    _class: JClass,
    input_json: JString,
) -> jstring {
    let result = || -> Result<PluginOutput, String> {
        let input_str: String = env
            .get_string(&input_json)
            .map_err(|e| format!("get_string: {}", e))?
            .into();

        let input: OutputInput =
            serde_json::from_str(&input_str).map_err(|e| format!("parse: {}", e))?;

        let decoded = input
            .data_base64
            .as_ref()
            .and_then(|b64| base64::Engine::decode(&base64::engine::general_purpose::STANDARD, b64).ok())
            .and_then(|b| String::from_utf8(b).ok())
            .unwrap_or_default();

        // Uppercase transformation + prefix
        let modified = format!("[RUST_OUTPUT] {}", decoded.to_uppercase());
        let encoded = base64::Engine::encode(
            &base64::engine::general_purpose::STANDARD,
            modified.as_bytes(),
        );

        Ok(PluginOutput {
            action: "modify".to_string(),
            data_base64: Some(encoded),
            headers: Some(HashMap::from([
                ("x-rust-output".to_string(), "slot1".to_string()),
                ("x-transform".to_string(), "uppercase".to_string()),
            ])),
            metadata: None,
            logs: Some(vec![
                LogEntry {
                    level: "info".to_string(),
                    message: format!("OutputSlot1: uppercased data ({} bytes)", modified.len()),
                },
                LogEntry {
                    level: "debug".to_string(),
                    message: format!("source={}, type={}", input.source.as_deref().unwrap_or("?"), input.output_type.as_deref().unwrap_or("?")),
                },
            ]),
        })
    };

    let output = result().unwrap_or_else(|e| PluginOutput {
        action: "pass".to_string(),
        data_base64: None,
        headers: None,
        metadata: None,
        logs: Some(vec![LogEntry { level: "error".to_string(), message: e }]),
    });

    let json = serde_json::to_string(&output).unwrap_or_default();
    env.new_string(&json).expect("new_string").into_raw()
}
