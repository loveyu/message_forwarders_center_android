package info.loveyu.mfca.config.schema

import info.loveyu.mfca.config.schema.nodes.CallNodes.call
import info.loveyu.mfca.config.schema.nodes.DeadLetterNodes.deadLetter
import info.loveyu.mfca.config.schema.nodes.GeoNode.geo
import info.loveyu.mfca.config.schema.nodes.InputNodes.inputs
import info.loveyu.mfca.config.schema.nodes.LinkNodes.links
import info.loveyu.mfca.config.schema.nodes.OutputNodes.outputs
import info.loveyu.mfca.config.schema.nodes.PluginNode.plugin
import info.loveyu.mfca.config.schema.nodes.QueueNodes.queues
import info.loveyu.mfca.config.schema.nodes.QuickSettingsNode.quickSettings
import info.loveyu.mfca.config.schema.nodes.RuleNodes.rules
import info.loveyu.mfca.config.schema.nodes.SchedulerNode.scheduler

object AppConfigSchema {

    val schema: ObjectNodeDef = configSchema {
        string("version") {
            description = "Config version identifier"
            default = ""
        }
        plugin()
        scheduler()
        geo()
        links()
        inputs()
        queues()
        outputs()
        call()
        rules()
        deadLetter()
        quickSettings()
    }
}
