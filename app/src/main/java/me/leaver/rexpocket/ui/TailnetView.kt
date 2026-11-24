package me.leaver.rexpocket.ui

import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.data.SavedHost

/**
 * One tailnet as its chip, its sheet and its page show it: its choices, whether it was
 * disconnected by hand, whether its node runs (and until when, while it lingers), and the hosts
 * connected through it, whom a Disconnect takes with it.
 */
data class TailnetView(
    val id: String,
    val name: String,
    val backend: TailnetBackend = TailnetBackend.Tailscale,
    val joined: Boolean = false,
    val policy: TailnetPolicy = TailnetPolicy(),
    val off: Boolean = false,
    val running: Boolean = false,
    val lingerUntil: Long? = null,
    val hosts: List<SavedHost> = emptyList(),
)
