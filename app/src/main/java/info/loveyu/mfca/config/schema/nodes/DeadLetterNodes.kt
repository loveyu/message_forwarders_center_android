package info.loveyu.mfca.config.schema.nodes

import info.loveyu.mfca.config.schema.ObjectNodeBuilder
import info.loveyu.mfca.config.schema.ObjectNodeDef

object DeadLetterNodes {
    fun ObjectNodeBuilder.deadLetter(): ObjectNodeDef =
        objectNode("deadLetter") {
            description = "Dead-letter handling for messages that exhausted all retries"

            boolean("enabled") { default = false }
            int("maxRetry") {
                description = "Maximum retry attempts in dead-letter processing"
                default = 10
            }
            objectList(
                "pipeline",
                block = { description = "Pipeline executed on dead-letter messages (same structure as rules.pipeline)" },
            ) {
                objectNode("transform") {
                    description = "Data transformation to apply"

                    string("decode") { description = "Decode pipeline" }
                    string("detect") { description = "Type detection: 'image', 'json', 'text'" }
                    string("enrich") { description = "Enrichment spec" }
                    string("filter") { description = "Filter expression" }
                    string("extract") { description = "GJSON path or '\$raw'" }
                    string("format") { description = "Template string" }
                    stringList("formatSteps") { description = "Structured format steps" }
                    any("call") {
                        description =
                            "List of call invocations: [{varName: 'callName(arg1, arg2)'}]."
                    }
                    boolean("breakOnReject") {
                        description = "Abort pipeline when filter rejects"
                        default = false
                    }
                }
                stringList("to") { description = "Output names to forward to after this step" }
            }
        }
}
