package dev.arnv.bluke.ui

internal fun toggledCycleSelection(
    current: Set<String>,
    value: String,
): Set<String> {
    if (value in current && current.size == 1) return current
    return current.toCollection(linkedSetOf()).apply {
        if (!add(value)) remove(value)
    }
}

internal fun <T> selectedOrFirstEnabled(
    selected: T,
    enabled: Set<T>,
    available: List<T>,
): T? = selected.takeIf(enabled::contains) ?: available.firstOrNull(enabled::contains)
