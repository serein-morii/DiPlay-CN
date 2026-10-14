package com.shilapi.xcertplay.update

internal object UpdateVersion {
    private val VERSION = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?")

    internal fun parse(value: String): Triple<Int, Int, Int>? =
        VERSION.find(value)?.let { match ->
            Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }

    internal fun isNewer(remote: String, installed: String): Boolean {
        val target = components(remote) ?: return false
        val current = components(installed) ?: return true
        for (index in target.indices) {
            if (target[index] != current[index]) return target[index] > current[index]
        }
        // CN releases share the upstream patch number. Compare their own revision as well.
        return cnRevision(remote) > cnRevision(installed)
    }

    private fun components(value: String): List<Int>? = VERSION.find(value)?.groupValues?.let { parts ->
        (1..4).map { parts[it].toIntOrNull() ?: 0 }
    }

    private fun cnRevision(value: String): Int =
        Regex("-cn\\.(\\d+)(?:$|[^\\d])").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
