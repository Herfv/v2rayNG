package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.Utils

/**
 * Builds [com.v2ray.ang.dto.CoreConfigContext] from the selected profile.
 * Keeps parsing and resolution logic out of [CoreConfigManager].
 */
object CoreConfigContextBuilder {

    /** Loads profile by guid and returns a resolved runtime context. */
    fun build(context: Context, guid: String): CoreConfigContext? {
        val config = MmkvManager.decodeServerConfig(guid) ?: return null

        return when (config.configType) {
            EConfigType.CUSTOM -> CoreConfigContext(
                context = context,
                guid = guid,
                selectedProfile = config,
                resolvedProfiles = listOf(config),
                resolvedType = CoreResolvedType.CUSTOM,
            )

            EConfigType.POLICYGROUP -> {
                val profiles = resolvePolicyGroupProfiles(config)
                CoreConfigContext(
                    context = context,
                    guid = guid,
                    selectedProfile = config,
                    resolvedProfiles = profiles,
                    resolvedType = CoreResolvedType.POLICYGROUP,
                )
            }

            else -> {
                val chainProfiles = resolveProxyChainProfiles(config)
                CoreConfigContext(
                    context = context,
                    guid = guid,
                    selectedProfile = config,
                    resolvedProfiles = chainProfiles,
                    resolvedType = if (chainProfiles.size <= 1) CoreResolvedType.NORMAL else CoreResolvedType.PROXYCHAIN,
                )
            }
        }
    }

    /** Resolves policy-group members with the same filters as runtime build. */
    private fun resolvePolicyGroupProfiles(config: ProfileItem): List<ProfileItem> {
        val serverList = MmkvManager.decodeAllServerList()
        return serverList
            .asSequence()
            .mapNotNull { id -> MmkvManager.decodeServerConfig(id) }
            .filter { profile ->
                val subscriptionId = config.policyGroupSubscriptionId
                if (subscriptionId.isNullOrBlank()) {
                    true
                } else {
                    profile.subscriptionId == subscriptionId
                }
            }
            .filter { profile ->
                val filter = config.policyGroupFilter
                if (filter.isNullOrBlank()) {
                    true
                } else {
                    try {
                        Regex(filter).containsMatchIn(profile.remarks)
                    } catch (_: Exception) {
                        profile.remarks.contains(filter)
                    }
                }
            }
            .filter { it.server.isNotNullEmpty() }
            .filter { !Utils.isPureIpAddress(it.server!!) || Utils.isValidUrl(it.server!!) }
            .filter { it.configType != EConfigType.CUSTOM }
            .filter { it.configType != EConfigType.POLICYGROUP }
            .toList()
    }

    /**
     * Resolves chain nodes in fixed order: next -> current -> prev.
     * If chain cannot be built, caller treats result as normal mode.
     */
    private fun resolveProxyChainProfiles(config: ProfileItem): List<ProfileItem> {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false) == true) {
            return listOf(config)
        }
        if (config.subscriptionId.isEmpty()) {
            return listOf(config)
        }

        val subItem = MmkvManager.decodeSubscription(config.subscriptionId) ?: return listOf(config)
        val resolved = mutableListOf<ProfileItem>()

        // Keep the same practical chain order as current runtime assembly:
        // next -> current -> prev
        SettingsManager.getServerViaRemarks(subItem.nextProfile)?.let { resolved.add(it) }
        resolved.add(config)
        SettingsManager.getServerViaRemarks(subItem.prevProfile)?.let { resolved.add(it) }

        return resolved
    }
}

