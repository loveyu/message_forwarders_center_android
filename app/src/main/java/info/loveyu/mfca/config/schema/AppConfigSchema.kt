package info.loveyu.mfca.config.schema

object AppConfigSchema {

    val schema: ObjectNodeDef = configSchema {
        string("version") {
            description = "Config version identifier"
            default = ""
        }

        objectNode("scheduler") {
            description = "Unified scheduler configuration"

            duration("tickInterval") {
                description = "Scheduler tick interval (minimum 20s enforced at runtime)"
                default = "40s"
            }
            duration("chargingTickInterval") {
                description = "Tick interval when charging (defaults to tickInterval if omitted)"
            }
            duration("wakeLockTimeout") {
                description = "Wake lock maximum hold duration"
                default = "1h"
            }
            duration("wifiLockTimeout") {
                description = "WiFi lock maximum hold duration"
                default = "1h"
            }
        }

        objectList("links", block = { description = "Link (connection pool) configurations" }) {
            string("id") {
                required()
                description = "Unique link identifier referenced by inputs and outputs"
            }
            string("dsn") {
                description =
                    "Connection string: protocol://[user:pass@]host:port[?params]. " +
                        "Protocol determines link type: mqtt[s]:// | ws[s]:// | tcp[s]:// | http[s]://"
            }
            string("clientId") { description = "Client identifier (MQTT)" }
            string("host") { description = "Host override when not using dsn" }
            int("port") {
                description = "Port override when not using dsn"
                range(1, 65535)
            }
            objectNode("reconnect") {
                description = "Reconnection policy"

                boolean("enabled") { default = true }
                duration("interval") {
                    description = "Initial reconnect interval"
                    default = "10s"
                }
                duration("maxInterval") {
                    description = "Maximum reconnect interval"
                    default = "60s"
                }
            }
            objectNode("tls") {
                description = "TLS configuration"

                string("ca") { description = "CA certificate path" }
                string("cert") { description = "Client certificate path" }
                string("key") { description = "Client private key path" }
                boolean("insecure") {
                    description = "Skip TLS certificate verification"
                    default = false
                }
            }
            string("when") { description = "Enable condition (e.g. network=wifi,ssid=MyWiFi)" }
            string("deny") { description = "Disable condition (e.g. network=mobile)" }
        }

        objectNode("inputs") {
            description = "Input source configurations"

            objectList(
                "http",
                block = { description = "HTTP server input sources" },
            ) {
                string("name") {
                    required()
                    description = "Unique input name referenced by rules"
                }
                string("dsn") {
                    required()
                    description =
                        "HTTP listen DSN: http://[user:pass@]host:port[?method=GET&token=xxx]"
                }
                stringList("paths") { description = "URL path filters (empty = all paths)" }
                string("linkId") { description = "Link to also publish received messages to" }
                string("when") { description = "Enable condition" }
                string("deny") { description = "Disable condition" }
            }

            objectList(
                "link",
                block = {
                    description = "Link-based input sources (MQTT subscriber, WebSocket, TCP)"
                },
            ) {
                string("name") {
                    required()
                    description = "Unique input name referenced by rules"
                }
                stringList("linkId") {
                    required()
                    description = "Link ID(s) to subscribe from (string or list of strings)"
                }
                enum("role", listOf("consumer", "producer")) {
                    description = "Link role: consumer (subscribe) or producer (publish)"
                    default = "consumer"
                }
                string("topic") { description = "Topic to subscribe (MQTT)" }
                stringList("topics") { description = "Multiple topics to subscribe" }
                stringList("excludeTopics") { description = "Topics to exclude" }
                int("qos") {
                    description = "MQTT QoS level"
                    range(0, 2)
                }
                objectNode("replay") {
                    description = "Message replay configuration"

                    boolean("enabled") { default = false }
                    enum("provider", listOf("gotifyApi")) {
                        description = "Replay data provider"
                        default = "gotifyApi"
                    }
                    string("messageIdPath") {
                        description = "JSON path to the message ID field"
                        default = "id"
                    }
                    int("pageSize") {
                        description = "Messages per page when fetching"
                        default = 50
                    }
                    int("maxPages") {
                        description = "Maximum pages to fetch"
                        default = 20
                    }
                    int("maxMessages") {
                        description = "Maximum total messages to replay"
                        default = 500
                    }
                    boolean("persistState") {
                        description = "Persist last-seen message ID across restarts"
                        default = true
                    }
                    string("baseUrl") { description = "Provider base URL" }
                    string("token") { description = "Provider authentication token" }
                    int("applicationId") { description = "Provider application ID filter" }
                }
                string("when") { description = "Enable condition" }
                string("deny") { description = "Disable condition" }
            }

            objectList(
                "failQueue",
                block = { description = "Fail-queue input sources (re-inject failed messages)" },
            ) {
                string("name") {
                    required()
                    description = "Unique input name referenced by rules"
                }
                stringList("idTypes") { description = "Message type filter (empty = all)" }
                stringList("queues") {
                    description =
                        "Queue references to read from, e.g. 'sqlite:myQueue', 'memory:myQueue'"
                }
                int("batchSize") {
                    description = "Maximum messages to process per tick"
                    default = 20
                }
            }
        }

        objectNode("queues") {
            description = "Queue system configuration"

            objectMap("memory") {
                description = "In-memory queue (Channel-driven)"

                int("capacity") {
                    description = "Maximum queue capacity"
                    default = 1000
                }
                int("workers") {
                    description = "Number of consumer coroutines"
                    default = 1
                }
                enum("overflow", listOf("dropOldest", "dropNew", "block")) {
                    description = "Overflow strategy when queue is full"
                    default = "dropOldest"
                }
            }

            objectMap("sqlite") {
                description = "SQLite-backed persistent queue (tick-driven)"

                string("path") {
                    required()
                    description =
                        "Database path. Protocols: data:// | sdcard:// | file:// | cache://"
                }
                int("batchSize") {
                    description = "Messages to dequeue per tick"
                    default = 20
                }
                duration("retryInterval") {
                    description = "Minimum interval between retry attempts"
                    default = "5s"
                }
                int("maxRetry") {
                    description = "Maximum delivery attempts before dead-lettering"
                    default = 10
                }
                objectNode("backoff") {
                    description = "Retry backoff configuration"

                    enum("type", listOf("exponential", "linear")) {
                        description = "Backoff calculation strategy"
                        default = "exponential"
                    }
                    duration("initial") {
                        description = "Initial backoff duration"
                        default = "2s"
                    }
                    duration("max") {
                        description = "Maximum backoff duration"
                        default = "5m"
                    }
                }
                objectNode("cleanup") {
                    description = "Completed-message cleanup policy"

                    duration("maxAge") {
                        description = "Retain completed messages for this duration"
                        default = "7d"
                    }
                }
            }
        }

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
                objectNode("onFailure") {
                    description = "Action after all retries are exhausted"

                    string("action") {
                        description = "Failure action: 'discard' or a queue name"
                        default = "discard"
                    }
                    string("idType") { description = "Message type tag for fail-queue filtering" }
                    duration("delay") {
                        description =
                            "Delay before the message is re-injected from the fail queue"
                        default = "60s"
                    }
                }
                objectNode("queue") {
                    description = "Queue reference for async delivery"

                    string("name") {
                        required()
                        description = "Queue name as defined in queues section"
                    }
                    long("delay") {
                        description = "Enqueue delay in milliseconds"
                        default = 0
                    }
                }
                any("format") {
                    description = "Output format: string template or list of format steps"
                }
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
                objectNode("onFailure") {
                    description = "Action after all retries are exhausted"

                    string("action") {
                        description = "Failure action: 'discard' or a queue name"
                        default = "discard"
                    }
                    string("idType") { description = "Message type tag for fail-queue filtering" }
                    duration("delay") {
                        description =
                            "Delay before the message is re-injected from the fail queue"
                        default = "60s"
                    }
                }
                objectNode("queue") {
                    string("name") { required() }
                    long("delay") { default = 0 }
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
                    long("delay") { default = 0 }
                }
                any("format") {
                    description = "Output format: string template or list of format steps"
                }
            }
        }

        objectList("rules", block = { description = "Message forwarding rules" }) {
            string("name") {
                required()
                description = "Unique rule name"
            }
            stringList("from") {
                required()
                description = "Source input name(s): single string or list of strings"
            }
            objectList(
                "pipeline",
                block = { description = "Processing pipeline steps" },
            ) {
                objectNode("transform") {
                    description = "Data transformation to apply"

                    string("decode") {
                        description =
                            "Decode pipeline, e.g. 'base64Decode', 'jsonDecode', 'gzDecode'"
                    }
                    string("detect") { description = "Type detection: 'image', 'json', 'text'" }
                    string("enrich") { description = "Enrichment spec, e.g. 'gotifyIcon:linkId'" }
                    string("filter") {
                        description = "Filter expression, e.g. 'data.type == \"alert\"'"
                    }
                    string("extract") {
                        description = "GJSON path or '\$raw', e.g. 'data.temperature'"
                    }
                    string("format") {
                        description = "Template string, e.g. '{data.title}: {data.message}'"
                    }
                    stringList("formatSteps") {
                        description = "Structured format steps (mutually exclusive with format)"
                    }
                    boolean("breakOnReject") {
                        description = "Abort pipeline when filter rejects"
                        default = false
                    }
                }
                stringList("to") { description = "Output names to forward to after this step" }
            }
            objectList("onError", block = { description = "Pipeline executed on error" }) {
                objectNode("transform") {}
                stringList("to") {}
            }
            string("when") { description = "Enable condition" }
            string("deny") { description = "Disable condition" }
        }

        objectNode("deadLetter") {
            description = "Dead-letter handling for messages that exhausted all retries"

            boolean("enabled") { default = false }
            int("maxRetry") {
                description = "Maximum retry attempts in dead-letter processing"
                default = 10
            }
            objectList(
                "action",
                block = { description = "Dead-letter processing pipeline" },
            ) {
                objectNode("transform") {}
                stringList("to") {}
            }
        }

        objectNode("quickSettings") {
            description = "Quick-settings tile configuration"

            boolean("inputMethodSwitcher") {
                description = "Show input method switcher tile"
                default = true
            }
        }
    }
}
