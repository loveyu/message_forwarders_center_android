// Rust Input Plugin Slot 1 - Front-only data modifier
// Build: cargo ndk -t arm64-v8a -o ./jniLibs build --release

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Deserialize)]
struct PluginInput {
    #[serde(rename = "type")]
    type_field: Option<String>,
    source: Option<String>,
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
pub extern "system" fn Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeGetCapabilities(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1 // CAP_FRONT only
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeProcess(
    env: JNIEnv,
    _class: JClass,
    input_json: JString,
) -> jstring {
    let result = || -> Result<PluginOutput, String> {
        let input_str: String = env
            .get_string(&input_json)
            .map_err(|e| format!("get_string: {}", e))?
            .into();

        let input: PluginInput =
            serde_json::from_str(&input_str).map_err(|e| format!("parse: {}", e))?;

        // Only process front
        if input.type_field.as_deref() != Some("input_front") {
            return Ok(PluginOutput {
                action: "pass".to_string(),
                data_base64: None,
                headers: None,
                metadata: None,
                logs: Some(vec![LogEntry {
                    level: "warn".to_string(),
                    message: format!("Slot1: front-only, skip {:?}", input.type_field),
                }]),
            });
        }

        // Decode
        let decoded = input
            .data_base64
            .as_ref()
            .and_then(|b64| base64::Engine::decode(&base64::engine::general_purpose::STANDARD, b64).ok())
            .and_then(|bytes| String::from_utf8(bytes).ok())
            .unwrap_or_default();

        // Modify: uppercase + prefix
        let modified = format!("[RUST_SLOT1] {}", decoded);
        let encoded = base64::Engine::encode(
            &base64::engine::general_purpose::STANDARD,
            modified.as_bytes(),
        );

        Ok(PluginOutput {
            action: "modify".to_string(),
            data_base64: Some(encoded),
            headers: Some(HashMap::from([
                ("x-slot1".to_string(), "rust_applied".to_string()),
                ("x-language".to_string(), "rust".to_string()),
            ])),
            metadata: None,
            logs: Some(vec![
                LogEntry {
                    level: "info".to_string(),
                    message: format!("Slot1: modified data {}→{} bytes", decoded.len(), modified.len()),
                },
                LogEntry {
                    level: "debug".to_string(),
                    message: format!("Slot1: source={}", input.source.as_deref().unwrap_or("?")),
                },
            ]),
        })
    };

    match result() {
        Ok(output) => {
            let json = serde_json::to_string(&output).unwrap_or_default();
            env.new_string(&json).expect("new_string").into_raw()
        }
        Err(e) => {
            let json = serde_json::to_string(&PluginOutput {
                action: "pass".to_string(),
                data_base64: None,
                headers: None,
                metadata: None,
                logs: Some(vec![LogEntry {
                    level: "error".to_string(),
                    message: format!("Slot1: {}", e),
                }]),
            })
            .unwrap_or_default();
            env.new_string(&json).expect("new_string").into_raw()
        }
    }
}
