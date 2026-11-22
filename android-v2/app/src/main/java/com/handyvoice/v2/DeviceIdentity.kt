package com.handyvoice.v2

/**
 * Decides whether the transport installed on the shared client still matches the
 * saved device settings. Pure so the pairing race is unit-testable.
 *
 * The race: a scan pairs a toy and the transport knows its address at once, but
 * the address reaches the saved prefs a moment later. A prefs snapshot taken in
 * between still has a blank address and must not read as "the device changed"
 * (that would disconnect the toy that was just paired).
 *
 * @param installedAddress address the installed Lovense transport uses, or null if it has none yet.
 * @param pendingAddress address just paired but possibly not saved yet, else null.
 * @param snapAddress address saved in prefs ("" if none).
 */
internal fun deviceMatches(
    installedLovense: Boolean,
    installedAddress: String?,
    pendingAddress: String?,
    snapLovense: Boolean,
    snapAddress: String,
): Boolean {
    if (installedLovense != snapLovense) return false
    if (!snapLovense) return true
    if (snapAddress == installedAddress.orEmpty()) return true
    return pendingAddress != null && (snapAddress.isBlank() || snapAddress == pendingAddress)
}
