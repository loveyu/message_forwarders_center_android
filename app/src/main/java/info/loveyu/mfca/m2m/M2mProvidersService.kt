package info.loveyu.mfca.m2m

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun refreshProviders(
    providers: MutableList<M2mProviderInfo>,
) = withContext(Dispatchers.IO) {
    val result = M2mManager.fetchProviders()
    providers.clear()
    result.onSuccess { map ->
        providers.addAll(map.values.sortedBy { it.name })
    }
}

suspend fun updateSingleProvider(
    context: Context,
    provider: M2mProviderInfo,
    providers: MutableList<M2mProviderInfo>,
    workingNames: MutableList<String>,
    updateError: MutableMap<String, String?>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    updateError[provider.name] = null
    workingNames.add(provider.name)
    try {
        val result = withContext(Dispatchers.IO) {
            M2mManager.updateProvider(provider.name, provider.type)
        }
        result.onSuccess {
            refreshProviders(providers)
            snackbarHostState.showSnackbar(
                context.getString(
                    info.loveyu.mfca.R.string.providers_update_success, provider.name
                ),
            )
        }.onFailure { e ->
            updateError[provider.name] = e.message
            snackbarHostState.showSnackbar(
                context.getString(
                    info.loveyu.mfca.R.string.providers_update_failed,
                    provider.name,
                    e.message ?: "",
                ),
            )
        }
    } finally {
        workingNames.remove(provider.name)
    }
}

suspend fun updateAll(
    context: Context,
    type: M2mProviderType,
    providers: MutableList<M2mProviderInfo>,
    workingNames: MutableList<String>,
    updateError: MutableMap<String, String?>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    val targetProviders = providers.filter { it.type == type }
    if (targetProviders.isEmpty()) {
        snackbarHostState.showSnackbar(
            context.getString(info.loveyu.mfca.R.string.providers_empty)
        )
        return
    }
    var successCount = 0
    var failCount = 0
    for (provider in targetProviders) {
        updateError[provider.name] = null
        workingNames.add(provider.name)
        val result = withContext(Dispatchers.IO) {
            M2mManager.updateProvider(provider.name, provider.type)
        }
        result.onSuccess {
            successCount++
        }.onFailure { e ->
            updateError[provider.name] = e.message
            failCount++
        }
        workingNames.remove(provider.name)
    }
    refreshProviders(providers)
    val msg = if (failCount == 0) {
        context.getString(info.loveyu.mfca.R.string.providers_update_all_success)
    } else {
        context.getString(
            info.loveyu.mfca.R.string.providers_update_all_failed,
            "$successCount/$failCount"
        )
    }
    snackbarHostState.showSnackbar(msg)
}
