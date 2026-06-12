// Go Input Slot 1 Plugin - Data modifier example (Front-only)
// Build: CGO_ENABLED=1 GOOS=android GOARCH=arm64 go build -buildmode=c-shared -o libInput_plugin_slot_1.so .
// This implements a front-only plugin that:
//   - Prepends a prefix to incoming data
//   - Injects a custom header x-slot1: applied
//   - Logs modification details

package main

/*
#include <jni.h>
*/
import "C"
import (
	"encoding/base64"
	"encoding/json"
	"unsafe"
)

type PluginInput struct {
	Version    int               `json:"version"`
	Type       string            `json:"type"`
	Source     string            `json:"source"`
	DataBase64 string            `json:"dataBase64"`
	Headers    map[string]string `json:"headers"`
	Metadata   map[string]string `json:"metadata"`
}

type PluginOutput struct {
	Action     string            `json:"action"`
	DataBase64 string            `json:"dataBase64,omitempty"`
	Headers    map[string]string `json:"headers,omitempty"`
	Metadata   map[string]string `json:"metadata,omitempty"`
	Logs       []LogEntry        `json:"logs,omitempty"`
}

type LogEntry struct {
	Level   string `json:"level"`
	Message string `json:"message"`
}

//export Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeGetCapabilities
func Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeGetCapabilities(
	env *C.JNIEnv, class C.jobject,
) C.int {
	return 1 // CAP_FRONT only
}

//export Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeProcess
func Java_info_loveyu_mfca_input_plugin_InputSlot1_nativeProcess(
	env *C.JNIEnv, class C.jobject, inputJson C.jstring,
) C.jstring {
	goInput := jstringToString(env, inputJson)

	var input PluginInput
	if err := json.Unmarshal([]byte(goInput), &input); err != nil {
		return jsonString(env, PluginOutput{
			Action: "pass",
			Logs:   []LogEntry{{Level: "error", Message: "parse error: " + err.Error()}},
		})
	}

	if input.Type != "input_front" {
		return jsonString(env, PluginOutput{
			Action: "pass",
			Logs:   []LogEntry{{Level: "warn", Message: "front-only, skip " + input.Type}},
		})
	}

	// Decode original data
	decoded := ""
	if input.DataBase64 != "" {
		if d, err := base64.StdEncoding.DecodeString(input.DataBase64); err == nil {
			decoded = string(d)
		}
	}

	// Modification: prefix + inject header
	modified := "[SLOT1_GO] " + decoded
	encoded := base64.StdEncoding.EncodeToString([]byte(modified))

	output := PluginOutput{
		Action:     "modify",
		DataBase64: encoded,
		Headers: map[string]string{
			"x-slot1":    "applied",
			"x-language": "go",
		},
		Logs: []LogEntry{
			{Level: "info", Message: "Slot1: data modified, length " + itoa(len(decoded)) + "→" + itoa(len(modified))},
			{Level: "debug", Message: "Source: " + input.Source},
		},
	}
	return jsonString(env, output)
}

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
	data, _ := json.Marshal(v)
	cstr := C.CString(string(data))
	defer C.free(unsafe.Pointer(cstr))
	return C.NewStringUTF(env, cstr)
}

func itoa(n int) string {
	return C.GoString(C.CString(
		string(rune(n%10+'0')) + // simplified; use strconv in real code
		""))
}

func main() {}
