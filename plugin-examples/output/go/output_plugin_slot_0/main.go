// Go Output Slot 0 Plugin - Pass-through example
// Build: CGO_ENABLED=1 GOOS=android GOARCH=arm64 go build -buildmode=c-shared -o libOutput_plugin_slot_0.so .
// Output plugins only have front processing (no rear).

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

type OutputInput struct {
	Version    int               `json:"version"`
	Type       string            `json:"type"` // "output"
	Source     string            `json:"source"`
	OutputType string            `json:"outputType"`
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

//export Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeGetCapabilities
func Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeGetCapabilities(
	env *C.JNIEnv, class C.jobject,
) C.int {
	return 1 // CAP_FRONT only
}

//export Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeProcess
func Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeProcess(
	env *C.JNIEnv, class C.jobject, inputJson C.jstring,
) C.jstring {
	goInput := jstringToString(env, inputJson)

	var input OutputInput
	if err := json.Unmarshal([]byte(goInput), &input); err != nil {
		return errorJson(env, "parse error: "+err.Error())
	}

	logs := []LogEntry{
		{Level: "info", Message: "OutputSlot0: pass-through (Go)"},
		{Level: "debug", Message: "Output type: " + input.OutputType + ", source: " + input.Source},
	}

	if input.DataBase64 != "" {
		if decoded, err := base64.StdEncoding.DecodeString(input.DataBase64); err == nil {
			preview := string(decoded)
			if len(preview) > 80 {
				preview = preview[:80] + "..."
			}
			logs = append(logs, LogEntry{Level: "debug", Message: "Data preview: " + preview})
		}
	}

	return jsonString(env, PluginOutput{
		Action: "pass",
		Logs:   logs,
	})
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

func errorJson(env *C.JNIEnv, msg string) C.jstring {
	return jsonString(env, PluginOutput{
		Action: "pass",
		Logs:   []LogEntry{{Level: "error", Message: msg}},
	})
}

func main() {}
