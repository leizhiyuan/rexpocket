package me.leaver.rexpocket.netbird.api;

import me.leaver.rexpocket.netbird.api.INetbirdNodeListener;

/**
 * The Netbird node the add-on runs for RexPocket. Bound through NetbirdAddon.ACTION and guarded by
 * NetbirdAddon.PERMISSION. A failure is an IllegalStateException carrying the node's own reason.
 * Every call but version() may block; RexPocket makes them off its main thread.
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
}
