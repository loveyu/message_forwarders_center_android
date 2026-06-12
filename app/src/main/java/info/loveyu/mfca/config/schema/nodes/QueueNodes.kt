package info.loveyu.mfca.config.schema.nodes

import info.loveyu.mfca.config.schema.ObjectNodeBuilder
import info.loveyu.mfca.config.schema.ObjectNodeDef

object QueueNodes {
    fun ObjectNodeBuilder.queues(): ObjectNodeDef =
        objectNode("queues") {
            description = "Queue system configuration"

            objectMap("memory") {
                description = "In-memory queue (worker-driven)"

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
                duration("retryInterval") {
                    description = "Initial retry delay on consumer failure"
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
}
