package info.loveyu.mfca.config.schema.nodes

import info.loveyu.mfca.config.schema.ObjectNodeBuilder
import info.loveyu.mfca.config.schema.ObjectNodeDef

object PluginNode {
    fun ObjectNodeBuilder.plugin() {
        objectNode("plugin") {
            description = "插件下载配置"
            string("udp2rawCore") { description = "udp2raw 核心插件下载地址" }
            string("m2mCore") { description = "m2m 核心插件下载地址" }
            string("downloadProxy") { description = "下载代理（如 socks5://127.0.0.1:1080）" }
        }
    }
}

object SchedulerNode {
    fun ObjectNodeBuilder.scheduler() {
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
    }
}

object GeoNode {
    fun ObjectNodeBuilder.geo() {
        objectNode("geo") {
            description = "Global geo data file download URLs"

            string("geoip") {
                description = "GeoIP database download URL"
                default = ""
            }
            string("geosite") {
                description = "GeoSite database download URL"
                default = ""
            }
            string("country") {
                description = "Country MMDB database download URL"
                default = ""
            }
            string("asn") {
                description = "ASN database download URL"
                default = ""
            }
        }
    }
}

object QuickSettingsNode {
    fun ObjectNodeBuilder.quickSettings() {
        objectNode("quickSettings") {
            description = "Quick-settings tile configuration"

            boolean("inputMethodSwitcher") {
                description = "Show input method switcher tile"
                default = true
            }
        }
    }
}
