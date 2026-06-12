// Rust Output Plugin Slot 0 - Pass-through
// Build: cargo ndk -t arm64-v8a -o ./jniLibs build --release

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
struct OutputInput {
    #[serde(rename = "type")]
    type_field: Option<String>,
    source: Option<String>,
    output_type: Option<String>,
    data_base64: Option<String>,
    headers: Option<std::collections::HashMap<String, String>>,
    metadata: Option<std::collections::HashMap<String, String>>,
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
    logs: Option<Vec<LogEntry>>,
}

#[derive(Serialize)]
struct LogEntry {
    level: String,
    message: String,
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeGetCapabilities(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1 // CAP_FRONT
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeProcess(
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

        let data_preview = input
            .data_base64
            .as_ref()
            .and_then(|b64| base64::Engine::decode(&base64::engine::general_purpose::STANDARD, b64).ok())
            .and_then(|b| String::from_utf8(b).ok())
            .map(|s| if s.len() > 80 { format!("{}...", &s[..80]) } else { s })
            .unwrap_or_default();

        Ok(PluginOutput {
            action: "pass".to_string(),
            data_base64: None,
            headers: None,
            metadata: None,
            logs: Some(vec![
                LogEntry { level: "info".to_string(), message: "OutputSlot0: pass-through (Rust)".to_string() },
                LogEntry { level: "debug".to_string(), message: format!("type={}, source={}", input.type_field.as_deref().unwrap_or("?"), input.source.as_deref().unwrap_or("?")) },
                LogEntry { level: "debug".to_string(), message: format!("data preview: {}", data_preview) },
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
