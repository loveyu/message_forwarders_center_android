package info.loveyu.mfca.ui

import android.content.Context
import info.loveyu.mfca.config.models.AppConfig
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.queue.QueueManager

internal val forwardServiceCurrentConfig: AppConfig?
    get() = ForwardService.currentConfig

fun getAllComponentStatuses(context: Context): List<ComponentStatus> {
    val statuses = mutableListOf<ComponentStatus>()
    getConfiguredLinkStatuses(context).forEach(statuses::add)
    forwardServiceCurrentConfig?.let { appConfig ->
        appConfig.inputs.http.forEach { httpConfig ->
            statuses.add(buildHttpInputStatus(context, appConfig, httpConfig))
        }
        appConfig.inputs.link.forEach { linkInputConfig ->
            val targetLinkIds = if (linkInputConfig.linkIds.isNotEmpty()) {
                linkInputConfig.linkIds
            } else {
                listOf(linkInputConfig.linkId)
            }
            targetLinkIds.forEach { linkId ->
                statuses.add(
                    buildLinkInputStatus(
                        context = context,
                        config = linkInputConfig,
                        linkId = linkId,
                        includeLinkIdInName = targetLinkIds.size > 1
                    )
                )
            }
        }
        appConfig.inputs.udp2raw.forEach { udp2rawConfig ->
            statuses.add(buildUdp2RawInputStatus(context, udp2rawConfig))
        }
        appConfig.rules.forEach { ruleConfig ->
            statuses.add(buildRuleStatus(context, ruleConfig))
        }
        appConfig.outputs.http.forEach { httpOutputConfig ->
            statuses.add(buildHttpOutputStatus(context, httpOutputConfig))
        }
        appConfig.outputs.link.forEach { linkOutputConfig ->
            statuses.add(buildLinkOutputStatus(context, linkOutputConfig))
        }
        appConfig.outputs.internal.forEach { internalOutputConfig ->
            statuses.add(buildInternalOutputStatus(context, internalOutputConfig))
        }
    }
    QueueManager.getAllQueues().forEach { (_, queue) ->
        statuses.add(buildQueueStatus(queue))
    }
    return statuses
}

fun getGroupedComponentStatuses(context: Context): List<Pair<ComponentType, List<ComponentStatus>>> {
    return getAllComponentStatuses(context)
        .groupBy { it.type }
        .toList()
        .sortedBy { getComponentTypeOrder(it.first) }
}

fun getEnabledAndDisabledComponents(
    context: Context
): Pair<List<ComponentStatus>, List<ComponentStatus>> {
    val all = getAllComponentStatuses(context)
    val enabled = all.filter { it.isEnabled }
    val disabled = all.filter { !it.isEnabled }
    return Pair(enabled, disabled)
}
