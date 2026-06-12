package info.loveyu.mfca.config.schema.nodes

import info.loveyu.mfca.config.schema.ListNodeDef
import info.loveyu.mfca.config.schema.ObjectNodeBuilder

object CallNodes {
    fun ObjectNodeBuilder.call(): ListNodeDef =
        objectList(
            "call",
            block = { description = "Named call resource definitions (callable from pipeline transform.call)" },
        ) {
            string("name") {
                required()
                description = "Unique call resource name referenced in pipeline transform.call"
            }
            enum("type", listOf("http")) {
                description = "Call resource type (currently only http)"
                default = "http"
            }
            string("url") {
                required()
                description = "Target URL, supports format templates (e.g. '{args[0]}')"
            }
            string("method") {
                description = "HTTP method"
                default = "POST"
            }
            objectNode("headers") {
                description = "HTTP request headers; values support format templates"
                any("*") { description = "Header value (supports format templates)" }
            }
            string("body") {
                description =
                    "Request body template; if omitted, current data is sent. Supports format templates."
            }
            string("response") {
                description =
                    "Response processing template; '{response}' is the raw response body. " +
                        "Can return string, map, or list. If result has 'data'/'headers' keys they " +
                        "override current pipeline variables."
            }
            duration("timeout") {
                description = "Request timeout"
                default = "15s"
            }
            objectNode("retry") {
                description = "Retry policy"

                int("maxAttempts") {
                    description = "Maximum number of attempts"
                    default = 3
                }
                duration("interval") {
                    description = "Delay between retries"
                    default = "1s"
                }
            }
        }
}
