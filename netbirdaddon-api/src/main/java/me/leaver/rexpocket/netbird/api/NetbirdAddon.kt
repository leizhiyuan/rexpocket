package me.leaver.rexpocket.netbird.api

/**
 * The names and the versions RexPocket and the Netbird add-on agree on.
 *
 * Two numbers say what an add-on speaks (docs/design/tailnet-backends.md, "Versions"):
 *
 * - [VERSION], the base protocol: the calls of the first add-on (`version` to `keepRunning`) and
 *   their meaning. RexPocket uses only an add-on of its own [VERSION]. It is raised only when one of
 *   those calls is removed or changes meaning, which parts every app and add-on of the old one, so
 *   it stays 1 while calls are only added.
 * - [REVISION], the calls added since, appended to the interface so the old ones keep their
 *   transaction codes. An add-on of revision 1 has no `revision` call and answers it with nothing,
 *   which RexPocket reads as 1; RexPocket then does without what the later revisions add. A
 *   RexPocket that knows only revision 1 never makes the new calls, so a newer add-on serves it too.
 */
object NetbirdAddon {
    /** The add-on's application id. */
    const val PACKAGE = "me.leaver.rexpocket.netbird"

    /** The action the node's service is bound with. */
    const val ACTION = "me.leaver.rexpocket.netbird.NODE"

    /** The signature permission that guards the service. */
    const val PERMISSION = "me.leaver.rexpocket.netbird.permission.NODE"

    /** The base protocol of [INetbirdNode]; see the class. */
    const val VERSION = 1

    /** The newest revision of [INetbirdNode] this side speaks; see the class. */
    const val REVISION = 2

    /** The revision that added accounts: `startProfile` and `forgetProfile`. */
    const val REVISION_ACCOUNTS = 2

    /**
     * The service's meta-data naming its revision, so RexPocket knows what an installed add-on
     * speaks without binding it. An add-on without it is revision 1.
     */
    const val META_REVISION = "me.leaver.rexpocket.netbird.REVISION"

    /** The file name of the add-on's APK, in a release and in the CI artifact. */
    const val APK_NAME = "rexpocket-netbird-addon.apk"

    /** The first account: the registration the add-on always had, in its `netbird` directory. */
    const val FIRST_PROFILE = ""

    /**
     * The directory name of account [profile] under the add-on's `netbird-profiles/`: lowercase
     * letters, digits and dashes, at most 63 of them, as RexPocket names its own account directories.
     * Null for a name that leaves nothing, which no account has: the add-on refuses it.
     */
    fun profileDirectory(profile: String): String? = profile.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .trim('-')
        .take(63)
        .ifEmpty { null }
}
