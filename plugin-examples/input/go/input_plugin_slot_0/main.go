// Go Input Slot 0 Plugin - Pass-through example
// Build: CGO_ENABLED=1 GOOS=android GOARCH=arm64 go build -buildmode=c-shared -o libInput_plugin_slot_0.so .
// For armv7a: GOARCH=arm
// For x86_64: GOARCH=amd64

package main

/*
#include <jni.h>
*/
import "C"
import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"unsafe"
)

// PluginInput represents the JSON received from Kotlin
type PluginInput struct {
	Version    int               `json:"version"`
	Type       string            `json:"type"` // "input_front" or "input_rear"
	Source     string            `json:"source"`
	DataBase64 string            `json:"dataBase64"`
	Headers    map[string]string `json:"headers"`
	Metadata   map[string]string `json:"metadata"`

	// Rear-only fields
	Method          string            `json:"method,omitempty"`
	URI             string            `json:"uri,omitempty"`
	StatusCode      int               `json:"statusCode,omitempty"`
	ResponseBody    string            `json:"responseBody,omitempty"`
	ResponseHeaders map[string]string `json:"responseHeaders,omitempty"`
}

// PluginOutput represents the JSON returned to Kotlin
type PluginOutput struct {
	Action    string `json:"action"` // "modify" or "pass"
	DataBase64 string `json:"dataBase64,omitempty"`

	Headers  map[string]string `json:"headers,omitempty"`
	Metadata map[string]string `json:"metadata,omitempty"`

	// Rear-only fields
	StatusCode      int               `json:"statusCode,omitempty"`
	ResponseBody    string            `json:"responseBody,omitempty"`
	ResponseHeaders map[string]string `json:"responseHeaders,omitempty"`

	Logs []LogEntry `json:"logs,omitempty"`
}

type LogEntry struct {
	Level   string `json:"level"`   // "info", "warn", "error"
	Message string `json:"message"`
}

//export Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeGetCapabilities
func Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeGetCapabilities(
	env *C.JNIEnv, class C.jobject,
) C.int {
	// CAP_FRONT (1) | CAP_REAR (2) = 3
	return 3
}

//export Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeProcess
func Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeProcess(
	env *C.JNIEnv, class C.jobject, inputJson C.jstring,
) C.jstring {
	// Convert JNI string to Go string
	goInput := jstringToString(env, inputJson)

	// Parse input JSON
	var input PluginInput
	if err := json.Unmarshal([]byte(goInput), &input); err != nil {
		return errorResult(env, "failed to parse input: "+err.Error())
	}

	// Pass-through: no modification to data
	output := PluginOutput{
		Action: "pass",
		Logs: []LogEntry{
			{Level: "info", Message: "Slot0: pass-through - no modification"},
		},
	}

	// If the input has data, decode it for logging purposes
	if input.DataBase64 != "" {
		if decoded, err := base64.StdEncoding.DecodeString(input.DataBase64); err == nil {
			preview := string(decoded)
			if len(preview) > 100 {
				preview = preview[:100] + "..."
			}
			output.Logs = append(output.Logs, LogEntry{
				Level:   "debug",
				Message: "Slot0: input data preview: " + preview,
			})
		}
	}

	return jsonString(env, output)
}

//export Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeGetCapabilities
func Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeGetCapabilities(
	env *C.JNIEnv, class C.jobject,
) C.int {
	// CAP_FRONT only = 1
	return 1
}

//export Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeProcess
func Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeProcess(
	env *C.JNIEnv, class C.jobject, inputJson C.jstring,
) C.jstring {
	goInput := jstringToString(env, inputJson)

	var input PluginInput
	if err := json.Unmarshal([]byte(goInput), &input); err != nil {
		return errorResult(env, "failed to parse input: "+err.Error())
	}

	// Only process front type
	if input.Type != "input_front" {
		output := PluginOutput{
			Action: "pass",
			Logs: []LogEntry{
				{Level: "warn", Message: "Slot1: front-only, skipping " + input.Type},
			},
		}
		return jsonString(env, output)
	}

	// Decode, modify, re-encode
	decoded := ""
	if input.DataBase64 != "" {
		if d, err := base64.StdEncoding.DecodeString(input.DataBase64); err == nil {
			decoded = string(d)
		}
	}

	modified := "[MODIFIED_BY_SLOT1] " + decoded
	encoded := base64.StdEncoding.EncodeToString([]byte(modified))

	output := PluginOutput{
		Action:     "modify",
		DataBase64: encoded,
		Logs: []LogEntry{
			{Level: "info", Message: "Slot1: prepended prefix to data"},
			{Level: "debug", Message: "Slot1: original length=" + itoa(len(decoded)) + " new length=" + itoa(len(modified))},
		},
	}
	return jsonString(env, output)
}

// --- JNI helpers ---

func jstringToString(env *C.JNIEnv, js C.jstring) string {
	if js == nil {
		return ""
	}
	var isCopy C.jboolean
	chars := C.GetStringUTFChars(env, js, &isCopy)
	if chars == nil {
		return ""
	}
	defer C.ReleaseStringUTFChars(env, js, chars)
	return C.GoString(chars)
}

func jsonString(env *C.JNIEnv, v interface{}) C.jstring {
	data, err := json.Marshal(v)
	if err != nil {
		return errorResult(env, "json marshal failed: "+err.Error())
	}
	cstr := C.CString(string(data))
	defer C.free(unsafe.Pointer(cstr))
	return C.NewStringUTF(env, cstr)
}

func errorResult(env *C.JNIEnv, msg string) C.jstring {
	output := PluginOutput{
		Action: "pass",
		Logs: []LogEntry{
			{Level: "error", Message: msg},
		},
	}
	return jsonString(env, output)
}

func itoa(n int) string {
	return strings.TrimSpace(strings.Replace(strings.Replace(
		C.GoString(C.CString(string(rune(n+'0')))), "\x00", "", -1), "\x00", "", -1))
}

func main() {}
