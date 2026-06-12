// Go Output Slot 1 Plugin - Data modifier example
// Build: CGO_ENABLED=1 GOOS=android GOARCH=arm64 go build -buildmode=c-shared -o libOutput_plugin_slot_1.so .
// This plugin modifies output data by prepending a timestamp prefix.

package main

/*
#include <jni.h>
*/
import "C"
import (
	"encoding/base64"
	"encoding/json"
	"time"
	"unsafe"
)

type OutputInput struct {
	Version    int               `json:"version"`
	Type       string            `json:"type"`
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

//export Java_info_loveyu_mfca_output_plugin_OutputSlot1_nativeGetCapabilities
func Java_info_loveyu_mfca_output_plugin_OutputSlot1_nativeGetCapabilities(
	env *C.JNIEnv, class C.jobject,
) C.int {
	return 1 // CAP_FRONT
}

//export Java_info_loveyu_mfca_output_plugin_OutputSlot1_nativeProcess
func Java_info_loveyu_mfca_output_plugin_OutputSlot1_nativeProcess(
	env *C.JNIEnv, class C.jobject, inputJson C.jstring,
) C.jstring {
	goInput := jstringToString(env, inputJson)

	var input OutputInput
	if err := json.Unmarshal([]byte(goInput), &input); err != nil {
		return jsonString(env, PluginOutput{
			Action: "pass",
			Logs:   []LogEntry{{Level: "error", Message: "parse: " + err.Error()}},
		})
	}

	// Decode original data
	decoded := ""
	if input.DataBase64 != "" {
		if d, err := base64.StdEncoding.DecodeString(input.DataBase64); err == nil {
			decoded = string(d)
		}
	}

	// Prepend timestamp
	timestamp := time.Now().Format(time.RFC3339)
	modified := "[Go@" + timestamp + "] " + decoded
	encoded := base64.StdEncoding.EncodeToString([]byte(modified))

	output := PluginOutput{
		Action:     "modify",
		DataBase64: encoded,
		Headers: map[string]string{
			"x-output-slot1": "go_modified",
		},
		Logs: []LogEntry{
			{Level: "info", Message: "OutputSlot1: prepended timestamp"},
			{Level: "debug", Message: "Output: " + input.Source + " type: " + input.OutputType},
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

func main() {}
