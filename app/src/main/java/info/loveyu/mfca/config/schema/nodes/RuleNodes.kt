package info.loveyu.mfca.config.schema.nodes

import info.loveyu.mfca.config.schema.ListNodeDef
import info.loveyu.mfca.config.schema.ObjectNodeBuilder

object RuleNodes {
    fun ObjectNodeBuilder.rules(): ListNodeDef =
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
                    any("call") {
                        description =
                            "List of call invocations: [{varName: 'callName(arg1, arg2)'}]. " +
                                "Executed sequentially; later entries may use vars from earlier ones."
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
}
