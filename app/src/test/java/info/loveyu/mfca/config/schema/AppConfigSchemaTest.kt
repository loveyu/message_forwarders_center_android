package info.loveyu.mfca.config.schema

import info.loveyu.mfca.config.ConfigLoader
import info.loveyu.mfca.config.ConfigLoadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigSchemaTest {

    @Test
    fun `empty config loads without errors`() {
        val config = ConfigLoader.loadConfig("{}")
        assertEquals("", config.version)
        assertTrue(config.links.isEmpty())
        assertTrue(config.rules.isEmpty())
    }

    @Test
    fun `full minimal valid config loads`() {
        val yaml =
            """
            version: "1.0"
            links:
              - id: mqtt_server
                dsn: mqtt://broker.example.com:1883
            inputs:
              link:
                - name: my_input
                  linkId: mqtt_server
                  role: consumer
                  topic: test/#
            rules:
              - name: forward_all
                from: my_input
                pipeline:
                  - to:
                      - my_output
            outputs:
              http:
                - name: my_output
                  url: https://example.com/webhook
            """.trimIndent()

        val config = ConfigLoader.loadConfig(yaml)
        assertEquals("1.0", config.version)
        assertEquals(1, config.links.size)
        assertEquals("mqtt_server", config.links[0].id)
        assertEquals(1, config.rules.size)
        assertEquals("forward_all", config.rules[0].name)
    }

    @Test
    fun `link with invalid port range fails validation`() {
        val yaml =
            """
            links:
              - id: my_link
                dsn: mqtt://broker.example.com:1883
                port: 99999
            """.trimIndent()

        try {
            ConfigLoader.loadConfig(yaml)
            assert(false) { "Expected ConfigLoadException" }
        } catch (e: ConfigLoadException) {
            assertTrue(e.message?.contains("schema validation") == true)
        }
    }

    @Test
    fun `invalid qos value fails validation`() {
        val yaml =
            """
            inputs:
              link:
                - name: my_input
                  linkId: mqtt_server
                  qos: 5
            """.trimIndent()

        try {
            ConfigLoader.loadConfig(yaml)
            assert(false) { "Expected ConfigLoadException" }
        } catch (e: ConfigLoadException) {
            assertTrue(e.message?.contains("schema validation") == true)
        }
    }

    @Test
    fun `invalid duration format fails validation`() {
        val yaml =
            """
            scheduler:
              tickInterval: "thirty seconds"
            """.trimIndent()

        try {
            ConfigLoader.loadConfig(yaml)
            assert(false) { "Expected ConfigLoadException" }
        } catch (e: ConfigLoadException) {
            assertTrue(e.message?.contains("schema validation") == true)
        }
    }

    @Test
    fun `valid scheduler config passes validation`() {
        val yaml =
            """
            scheduler:
              tickInterval: 30s
              chargingTickInterval: 60s
              wakeLockTimeout: 2h
            """.trimIndent()

        val config = ConfigLoader.loadConfig(yaml)
        assertEquals(30_000L, config.scheduler.tickInterval.millis)
        assertEquals(60_000L, config.scheduler.chargingTickInterval?.millis)
    }

    @Test
    fun `plugin config passes validation and parsing`() {
        val yaml =
            """
            plugin:
              udp2rawCore: https://example.com/libudp2raw_plugin.so
              m2mCore: https://example.com/libm2m_plugin.so
              downloadProxy: socks5://127.0.0.1:1080
            """.trimIndent()

        val config = ConfigLoader.loadConfig(yaml)
        assertEquals("https://example.com/libudp2raw_plugin.so", config.plugin.udp2rawCore)
        assertEquals("https://example.com/libm2m_plugin.so", config.plugin.m2mCore)
        assertEquals("socks5://127.0.0.1:1080", config.plugin.downloadProxy)
    }

    @Test
    fun `m2m input config passes validation and parsing`() {
        val yaml =
            """
            inputs:
              m2m:
                - name: office_vpn
                  configUrl: https://example.com/m2m.yaml
                  refreshInterval: "24h"
                  enabled: true
                  when: network=wifi
                  accessControlMode: exclude
                  packages:
                    - info.loveyu.mfca
                    - com.android.chrome
            """.trimIndent()

        val config = ConfigLoader.loadConfig(yaml)
        assertEquals(1, config.inputs.m2m.size)
        assertEquals("office_vpn", config.inputs.m2m[0].name)
        assertEquals("https://example.com/m2m.yaml", config.inputs.m2m[0].configUrl)
        assertEquals(2, config.inputs.m2m[0].packages.size)
        assertEquals(86_400_000L, config.inputs.m2m[0].refreshIntervalMs)
    }

    @Test
    fun `invalid overflow enum fails validation`() {
        val yaml =
            """
            queues:
              memory:
                myQueue:
                  overflow: invalid_strategy
            """.trimIndent()

        try {
            ConfigLoader.loadConfig(yaml)
            assert(false) { "Expected ConfigLoadException" }
        } catch (e: ConfigLoadException) {
            assertTrue(e.message?.contains("schema validation") == true)
        }
    }

    @Test
    fun `valid queue config passes`() {
        val yaml =
            """
            queues:
              memory:
                fast:
                  capacity: 500
                  workers: 2
                  overflow: dropOldest
              sqlite:
                slow:
                  path: data://slow.db
                  batchSize: 10
                  retryInterval: 10s
                  maxRetry: 5
            """.trimIndent()

        val config = ConfigLoader.loadConfig(yaml)
        assertEquals(500, config.queues.memory["fast"]?.capacity)
        assertEquals("data://slow.db", config.queues.sqlite["slow"]?.path)
    }

    @Test
    fun `AppConfigSchema fields are complete`() {
        val schema = AppConfigSchema.schema
        val topLevelNames = schema.children.map { it.name }
        assertTrue("version" in topLevelNames)
        assertTrue("scheduler" in topLevelNames)
        assertTrue("links" in topLevelNames)
        assertTrue("inputs" in topLevelNames)
        assertTrue("queues" in topLevelNames)
        assertTrue("outputs" in topLevelNames)
        assertTrue("rules" in topLevelNames)
        assertTrue("deadLetter" in topLevelNames)
        assertTrue("quickSettings" in topLevelNames)
    }
}
