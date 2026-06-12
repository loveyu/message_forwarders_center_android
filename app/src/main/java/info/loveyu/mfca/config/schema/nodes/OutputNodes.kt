package info.loveyu.mfca.config.schema.nodes

import info.loveyu.mfca.config.schema.ObjectNodeBuilder
import info.loveyu.mfca.config.schema.ObjectNodeDef

object OutputNodes {
    fun ObjectNodeBuilder.outputs(): ObjectNodeDef =
        objectNode("outputs") {
            description = "Output sink configurations"

            objectList("http", block = { description = "HTTP output sinks" }) {
                string("name") {
                    required()
                    description = "Unique output name referenced by rules"
                }
                string("url") {
                    required()
                    description = "Target HTTP URL"
                }
                string("method") {
                    description = "HTTP method"
                    default = "POST"
                }
                any("headers") {
                    description =
                        "Additional HTTP request headers; values support template variables"
                }
                string("body") { description = "Request body template (defaults to message data)" }
                duration("timeout") {
                    description = "Request timeout"
                    default = "5s"
                }
                objectNode("retry") {
                    description = "Retry policy on transient failure"

                    int("maxAttempts") {
                        description = "Maximum delivery attempts"
                        default = 1
                    }
                    duration("interval") {
                        description = "Interval between retry attempts"
                        default = "1s"
                    }
                }
                objectNode("onFailureQueue") {
                    description = "Queue to enqueue message after all retries are exhausted (for async retry)"

                    string("name") {
                        required()
                        description = "Queue name as defined in queues section"
                    }
                    duration("delay") {
                        description = "Enqueue delay before the message becomes eligible for retry"
                        default = "0s"
                    }
                }
                objectNode("queue") {
                    description = "Queue reference for async delivery"

                    string("name") {
                        required()
                        description = "Queue name as defined in queues section"
                    }
                    duration("delay") {
                        description = "Enqueue delay before the message is first processed"
                        default = "0s"
                    }
                }
                any("format") {
                    description = "Output format: string template or list of format steps"
                }
                string("when") { description = "Enable condition (e.g. network=wifi&ssid=MyWiFi)" }
                string("deny") { description = "Disable condition (e.g. network=mobile)" }
            }

            objectList(
                "link",
                block = {
                    description = "Link output sinks (MQTT publish, WebSocket send, TCP send)"
                },
            ) {
                string("name") {
                    required()
                    description = "Unique output name referenced by rules"
                }
                stringList("linkId") {
                    required()
                    description = "Target link ID(s) — single string or list for fan-out to multiple links"
                }
                enum("role", listOf("consumer", "producer")) {
                    description = "Link role"
                    default = "producer"
                }
                string("topic") { description = "Target topic (MQTT)" }
                int("qos") {
                    description = "MQTT QoS level"
                    range(0, 2)
                }
                boolean("retain") {
                    description = "MQTT retain flag"
                    default = false
                }
                objectNode("retry") {
                    int("maxAttempts") { default = 1 }
                    duration("interval") { default = "1s" }
                }
                objectNode("onFailureQueue") {
                    description = "Queue to enqueue message after all retries are exhausted (for async retry)"

                    string("name") { required() }
                    duration("delay") {
                        description = "Enqueue delay before the message becomes eligible for retry"
                        default = "0s"
                    }
                }
                objectNode("queue") {
                    string("name") { required() }
                    duration("delay") {
                        description = "Enqueue delay before the message is first processed"
                        default = "0s"
                    }
                }
                string("when") { description = "Enable condition" }
                string("deny") { description = "Disable condition" }
                any("format") {
                    description = "Output format: string template or list of format steps"
                }
            }

            objectList(
                "internal",
                block = {
                    description = "Internal output sinks (clipboard, file, broadcast, notify)"
                },
            ) {
                string("name") {
                    required()
                    description = "Unique output name referenced by rules"
                }
                enum(
                    "type",
                    listOf("clipboard", "file", "broadcast", "notify", "clipboardHistory"),
                ) {
                    required()
                    description = "Internal output type"
                }
                string("basePath") { description = "Base path for file output" }
                string("fileName") { description = "File name template for file output" }
                string("channel") { description = "Notification channel ID" }
                objectNode("queue") {
                    string("name") { required() }
                    duration("delay") {
                        description = "Enqueue delay before the message is first processed"
                        default = "0s"
                    }
                }
                any("format") {
                    description = "Output format: string template or list of format steps"
                }
                string("when") { description = "Enable condition (e.g. network=wifi&ssid=MyWiFi)" }
                string("deny") { description = "Disable condition (e.g. network=mobile)" }
            }
        }
}
