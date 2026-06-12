package info.loveyu.mfca.config.schema.nodes

import info.loveyu.mfca.config.schema.ObjectNodeBuilder
import info.loveyu.mfca.config.schema.ObjectNodeDef

object InputNodes {
    fun ObjectNodeBuilder.inputs(): ObjectNodeDef =
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
                "m2m",
                block = { description = "m2m inputs backed by a remote m2m config and a m2m JNI plugin" },
            ) {
                string("name") {
                    required()
                    description = "Unique m2m candidate name"
                }
                string("configUrl") {
                    required()
                    description = "Remote m2m config URL"
                }
                duration("refreshInterval") {
                    description = "Auto-refresh interval for the config (e.g. '1h', '24h'); omit or set to '0' to disable"
                    default = "0"
                }
                boolean("enabled") {
                    description = "Whether this m2m candidate is enabled by default"
                    default = true
                }
                boolean("insecure") {
                    description = "Skip TLS certificate verification for config download"
                    default = false
                }
                enum("accessControlMode", listOf("acceptAll", "include", "exclude")) {
                    description = "App access control mode for this m2m profile"
                    default = "acceptAll"
                }
                stringList("packages") {
                    description = "Package names for include/exclude access control"
                }
                string("when") { description = "Enable condition" }
                string("deny") { description = "Disable condition" }
            }

            objectList(
                "udp2raw",
                block = { description = "udp2raw inputs – plugin loaded dynamically from internal storage" },
            ) {
                string("name") {
                    required()
                    description = "Unique udp2raw input name referenced by rules"
                }
                string("dsn") {
                    description =
                        "Connection DSN: udp2raw://[key@]remoteHost:remotePort?listen=localHost:localPort" +
                            "[&role=client|server][&rawMode=faketcp|udp|icmp]. " +
                            "role: client (default) or server. rawMode: faketcp (default), udp, icmp. " +
                            "Domain names in remoteHost are resolved to IP at every start. " +
                            "If both dsn and args are set, args take precedence."
                }
                stringList("args") {
                    description =
                        "Raw udp2raw command arguments. Overrides dsn when both are set. " +
                            "Example: ['-c', '-l0.0.0.0:4096', '-r127.0.0.1:53', '--raw-mode', 'faketcp']"
                }
                boolean("enabled") {
                    description = "Whether this udp2raw input is enabled"
                    default = true
                }
                string("when") { description = "Enable condition" }
                string("deny") { description = "Disable condition" }
            }
        }
}
