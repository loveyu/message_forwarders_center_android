package info.loveyu.mfca.config.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaValidatorTest {

    private fun schema(block: ObjectNodeBuilder.() -> Unit) = configSchema(block = block)

    // ==================== Required ====================

    @Test
    fun `required string missing produces error`() {
        val s = schema { string("host") { required() } }
        val errors = SchemaValidator.validate(s, emptyMap())
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("missing"))
        assertEquals(SchemaError.Severity.ERROR, errors[0].severity)
    }

    @Test
    fun `required string present produces no error`() {
        val s = schema { string("host") { required() } }
        val errors = SchemaValidator.validate(s, mapOf("host" to "localhost"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `optional missing field produces no error`() {
        val s = schema { string("host") {} }
        val errors = SchemaValidator.validate(s, emptyMap())
        assertTrue(errors.isEmpty())
    }

    // ==================== Type checking ====================

    @Test
    fun `wrong type for int field produces error`() {
        val s = schema { int("port") {} }
        val errors = SchemaValidator.validate(s, mapOf("port" to "not-a-number"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("Expected int"))
    }

    @Test
    fun `int value accepted`() {
        val s = schema { int("port") {} }
        val errors = SchemaValidator.validate(s, mapOf("port" to 8080))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `wrong type for boolean field produces error`() {
        val s = schema { boolean("enabled") {} }
        val errors = SchemaValidator.validate(s, mapOf("enabled" to "yes"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("Expected boolean"))
    }

    // ==================== Int range ====================

    @Test
    fun `int below min produces error`() {
        val s = schema {
            int("port") {
                min = 1
                max = 65535
            }
        }
        val errors = SchemaValidator.validate(s, mapOf("port" to 0))
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("below minimum"))
    }

    @Test
    fun `int above max produces error`() {
        val s = schema {
            int("port") {
                min = 1
                max = 65535
            }
        }
        val errors = SchemaValidator.validate(s, mapOf("port" to 70000))
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("exceeds maximum"))
    }

    @Test
    fun `int in range produces no error`() {
        val s = schema {
            int("port") {
                min = 1
                max = 65535
            }
        }
        val errors = SchemaValidator.validate(s, mapOf("port" to 8080))
        assertTrue(errors.isEmpty())
    }

    // ==================== Duration ====================

    @Test
    fun `valid duration string produces no error`() {
        val s = schema { duration("timeout") {} }
        for (d in listOf("10s", "5m", "2h", "1d", "500ms")) {
            val errors = SchemaValidator.validate(s, mapOf("timeout" to d))
            assertTrue("Expected no error for '$d' but got: $errors", errors.isEmpty())
        }
    }

    @Test
    fun `invalid duration string produces error`() {
        val s = schema { duration("timeout") {} }
        val errors = SchemaValidator.validate(s, mapOf("timeout" to "10seconds"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("Invalid duration"))
    }

    @Test
    fun `wrong type for duration produces error`() {
        val s = schema { duration("timeout") {} }
        val errors = SchemaValidator.validate(s, mapOf("timeout" to 10))
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("Expected duration string"))
    }

    // ==================== Enum ====================

    @Test
    fun `valid enum value produces no error`() {
        val s = schema { enum("strategy", listOf("dropOldest", "dropNew", "block")) {} }
        val errors = SchemaValidator.validate(s, mapOf("strategy" to "dropOldest"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `invalid enum value produces error`() {
        val s = schema { enum("strategy", listOf("dropOldest", "dropNew", "block")) {} }
        val errors = SchemaValidator.validate(s, mapOf("strategy" to "unknown"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("Must be one of"))
    }

    // ==================== Nested object ====================

    @Test
    fun `nested required field missing produces error with correct path`() {
        val s = schema {
            objectNode("server") {
                string("host") { required() }
            }
        }
        val errors = SchemaValidator.validate(s, mapOf("server" to mapOf<String, Any>()))
        assertEquals(1, errors.size)
        assertEquals("server.host", errors[0].path)
    }

    @Test
    fun `nested field wrong type produces error with path`() {
        val s = schema {
            objectNode("server") {
                int("port") {}
            }
        }
        val errors = SchemaValidator.validate(s, mapOf("server" to mapOf("port" to "8080")))
        assertEquals(1, errors.size)
        assertEquals("server.port", errors[0].path)
    }

    // ==================== List ====================

    @Test
    fun `valid list of objects produces no error`() {
        val s = schema {
            objectList("links") {
                string("id") { required() }
            }
        }
        val errors =
            SchemaValidator.validate(
                s,
                mapOf("links" to listOf(mapOf("id" to "link1"), mapOf("id" to "link2"))),
            )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `list item missing required field produces error with index path`() {
        val s = schema {
            objectList("links") {
                string("id") { required() }
            }
        }
        val errors =
            SchemaValidator.validate(s, mapOf("links" to listOf(mapOf<String, Any>())))
        assertEquals(1, errors.size)
        assertEquals("links[0].id", errors[0].path)
    }

    @Test
    fun `non-list value for list field produces error`() {
        val s = schema { objectList("links") { string("id") {} } }
        val errors = SchemaValidator.validate(s, mapOf("links" to "not-a-list"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("Expected list"))
    }

    // ==================== Map ====================

    @Test
    fun `valid map of objects produces no error`() {
        val s = schema {
            objectMap("queues") {
                int("capacity") {}
            }
        }
        val errors =
            SchemaValidator.validate(
                s,
                mapOf("queues" to mapOf("q1" to mapOf("capacity" to 100))),
            )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `map value invalid type produces error`() {
        val s = schema {
            objectMap("queues") {
                int("capacity") {}
            }
        }
        val errors =
            SchemaValidator.validate(
                s,
                mapOf("queues" to mapOf("q1" to mapOf("capacity" to "large"))),
            )
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("Expected int"))
    }

    // ==================== Deprecated ====================

    @Test
    fun `deprecated field in use produces warning`() {
        val s = schema {
            string("oldHost") {
                deprecated(since = "2.0", replacement = "host")
            }
        }
        val errors = SchemaValidator.validate(s, mapOf("oldHost" to "localhost"))
        assertEquals(1, errors.size)
        assertEquals(SchemaError.Severity.WARNING, errors[0].severity)
        assertTrue(errors[0].message.contains("deprecated"))
    }

    @Test
    fun `deprecated field absent produces no warning`() {
        val s = schema {
            string("oldHost") {
                deprecated(since = "2.0", replacement = "host")
            }
        }
        val errors = SchemaValidator.validate(s, emptyMap())
        assertTrue(errors.isEmpty())
    }

    // ==================== allowExtra ====================

    @Test
    fun `unknown field emits warning when allowExtra=false`() {
        val s =
            configSchema {
                allowExtra = false
                string("host") {}
            }
        val errors = SchemaValidator.validate(s, mapOf("host" to "localhost", "unknown" to "x"))
        assertEquals(1, errors.size)
        assertEquals(SchemaError.Severity.WARNING, errors[0].severity)
        assertTrue(errors[0].message.contains("Unknown field"))
    }

    @Test
    fun `unknown field ignored when allowExtra=true`() {
        val s =
            configSchema {
                allowExtra = true
                string("host") {}
            }
        val errors = SchemaValidator.validate(s, mapOf("host" to "localhost", "unknown" to "x"))
        assertTrue(errors.isEmpty())
    }
}
