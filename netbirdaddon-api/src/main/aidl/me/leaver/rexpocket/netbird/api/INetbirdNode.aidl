package me.leaver.rexpocket.netbird.api;

import me.leaver.rexpocket.netbird.api.INetbirdNodeListener;

/**
 * The Netbird node the add-on runs for RexPocket. Bound through NetbirdAddon.ACTION and guarded by
 * NetbirdAddon.PERMISSION. A failure is an IllegalStateException carrying the node's own reason.
 * Every call but version() and revision() may block; RexPocket makes them off its main thread.
 *
 * The calls keep their order: each one's place is its transaction code, so a call is only ever
 * appended, and an older add-on answers a call it does not have with nothing. NetbirdAddon says how
 * the two sides tell what the other speaks.
 */
interface INetbirdNode {
    /** NetbirdAddon.VERSION of the add-on. RexPocket checks it before any other call. */
    int version();

    /**
     * Configures and starts the node, or keeps the running one when hostname and managementUrl are
     * the same. setupKey joins the network the first time; afterwards it may be empty, since the
     * add-on keeps the registration. managementUrl is empty for Netbird Cloud.
     */
    void start(String hostname, String setupKey, String managementUrl);

    /** Stops the node. */
    void stop();

    /** The node's state as JSON, the shape the app's Go library reports for Netbird. */
    String state();

    /** The path to the peer at ip as JSON, the shape the app's Go library reports for Netbird. */
    String ping(String ip, int timeoutMillis);

    /** Why the node's last dial to host failed, or "" when it did not fail lately. */
    String dialError(String host);

    /**
     * Dials host:port through the node. The result is one end of a Unix socket pair the add-on
     * pipes to the connection; the caller owns and closes it.
     */
    ParcelFileDescriptor dial(String host, int port, int timeoutMillis);

    /** The one listener told of state changes; null clears it. */
    void setListener(INetbirdNodeListener listener);

    /**
     * Whether the node must keep running while RexPocket is in the background: the add-on is then a
     * foreground service, as long as its node runs.
     */
    void keepRunning(boolean background);

    // Revision 2 (NetbirdAddon.REVISION_ACCOUNTS): accounts.

    /**
     * NetbirdAddon.REVISION of the add-on; clientRevision is the caller's, for the add-on's log. An
     * add-on of revision 1 has no such call: the caller reads its empty answer, 0, as 1.
     */
    int revision(int clientRevision);

    /**
     * start() for account profile: its registration is kept in a directory of its own, so each
     * account joins once. NetbirdAddon.FIRST_PROFILE is the registration start() runs. A running
     * node of another account stops first. Refused for a name NetbirdAddon.profileDirectory rejects.
     */
    void startProfile(String profile, String hostname, String setupKey, String managementUrl);

    /**
     * Deletes account profile's registration, the account was removed. Refused for the account
     * running and for the first one; an account with nothing kept is no failure.
     */
    void forgetProfile(String profile);
}
