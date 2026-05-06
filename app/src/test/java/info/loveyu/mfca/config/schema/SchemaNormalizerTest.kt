package info.loveyu.mfca.config.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaNormalizerTest {

    @Test
    fun `scalar value is wrapped using scalarKey`() {
        val s =
            configSchema {
                objectNode("redis") {
                    scalarKey = "host"
                    string("host") {}
                }
            }

        val normalized = SchemaNormalizer.normalize(s, mapOf("redis" to "localhost"))

        @Suppress("UNCHECKED_CAST")
        val redis = normalized["redis"] as? Map<String, Any>
        assertEquals("localhost", redis?.get("host"))
    }

    @Test
    fun `map value is passed through unchanged when scalarKey not set`() {
        val s =
            configSchema {
                objectNode("redis") {
                    string("host") {}
                }
            }

        val input = mapOf("redis" to mapOf("host" to "localhost"))
        val normalized = SchemaNormalizer.normalize(s, input)

        @Suppress("UNCHECKED_CAST")
        val redis = normalized["redis"] as? Map<String, Any>
        assertEquals("localhost", redis?.get("host"))
    }

    @Test
    fun `null data returns empty map`() {
        val s = configSchema { string("host") {} }
        val normalized = SchemaNormalizer.normalize(s, null)
        assertTrue(normalized.isEmpty())
    }

    @Test
    fun `list items are recursively normalized`() {
        val s =
            configSchema {
                objectList("servers") {
                    scalarKey = "host"
                    string("host") {}
                }
            }

        val input = mapOf("servers" to listOf("server1", "server2"))
        val normalized = SchemaNormalizer.normalize(s, input)

        @Suppress("UNCHECKED_CAST")
        val servers = normalized["servers"] as? List<Map<String, Any>>
        assertEquals(2, servers?.size)
        assertEquals("server1", servers?.get(0)?.get("host"))
        assertEquals("server2", servers?.get(1)?.get("host"))
    }

    @Test
    fun `map values are recursively normalized`() {
        val s =
            configSchema {
                objectMap("queues") {
                    scalarKey = "path"
                    string("path") {}
                }
            }

        val input = mapOf("queues" to mapOf("myQueue" to "data://queue.db"))
        val normalized = SchemaNormalizer.normalize(s, input)

        @Suppress("UNCHECKED_CAST")
        val queues = normalized["queues"] as? Map<String, Map<String, Any>>
        assertEquals("data://queue.db", queues?.get("myQueue")?.get("path"))
    }

    @Test
    fun `unknown fields are preserved in output`() {
        val s = configSchema { string("host") {} }
        val input = mapOf("host" to "localhost", "extra" to "value")
        val normalized = SchemaNormalizer.normalize(s, input)
        assertEquals("value", normalized["extra"])
    }
}
