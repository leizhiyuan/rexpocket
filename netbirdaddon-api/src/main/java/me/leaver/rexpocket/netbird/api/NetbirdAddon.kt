package me.leaver.rexpocket.netbird.api

/** The names and the version RexPocket and the Netbird add-on agree on. */
object NetbirdAddon {
    /** The add-on's application id. */
    const val PACKAGE = "me.leaver.rexpocket.netbird"

    /** The action the node's service is bound with. */
    const val ACTION = "me.leaver.rexpocket.netbird.NODE"

    /** The signature permission that guards the service. */
    const val PERMISSION = "me.leaver.rexpocket.netbird.permission.NODE"

    /**
     * The version of [INetbirdNode]. Raised whenever a call is added, removed or changes meaning;
     * RexPocket only talks to an add-on of its own version.
     */
    const val VERSION = 1

    /** The file name of the add-on's APK, in a release and in the CI artifact. */
    const val APK_NAME = "rexpocket-netbird-addon.apk"
}
