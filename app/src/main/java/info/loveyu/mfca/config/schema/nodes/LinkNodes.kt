package info.loveyu.mfca.config.schema.nodes

import info.loveyu.mfca.config.schema.ListNodeDef
import info.loveyu.mfca.config.schema.ObjectNodeBuilder

object LinkNodes {
    fun ObjectNodeBuilder.links(): ListNodeDef =
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
}
