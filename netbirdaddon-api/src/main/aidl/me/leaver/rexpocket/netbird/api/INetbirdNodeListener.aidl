package me.leaver.rexpocket.netbird.api;

/** Told by the add-on that its node's state changed; RexPocket then reads INetbirdNode.state(). */
oneway interface INetbirdNodeListener {
    void stateChanged();
}
