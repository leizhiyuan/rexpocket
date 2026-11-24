package me.leaver.rexpocket.core

// Accounts: several sign-ins to one backend, one of them active at a time, since the app runs one
// node per backend. Each account keeps its own login in a state directory of its own, so switching
// is the node stopping and starting again on the other directory. A host can name the account it is
// on; one that names none follows the active one. See docs/design/tailnet-control.md, "Accounts".

/** One account of [backend], named by the person. The first account of a backend has the backend's key as its id. */
data class TailnetProfile(val id: String, val backend: TailnetBackend, val name: String) {
    /** Whether this is the account the app had before accounts: its login is in the old directory. */
    val isFirst: Boolean get() = id == backend.storageKey
}

/** Every account, by backend, and which one each backend runs. */
data class TailnetProfiles(
    val profiles: List<TailnetProfile> = emptyList(),
    val active: Map<TailnetBackend, String> = emptyMap(),
) {
    /** [backend]'s accounts, the first one always among them. */
    fun of(backend: TailnetBackend): List<TailnetProfile> {
        val mine = profiles.filter { it.backend == backend }
        return if (mine.any { it.isFirst }) mine else listOf(first(backend)) + mine
    }

    fun find(id: String): TailnetProfile? = TailnetBackend.entries.firstNotNullOfOrNull { b -> of(b).firstOrNull { it.id == id } }

    /** The account [backend] runs: the chosen one while it exists, the first otherwise. */
    fun activeOf(backend: TailnetBackend): TailnetProfile {
        val all = of(backend)
        return all.firstOrNull { it.id == active[backend] } ?: all.first()
    }

    /** With [profile] added (its name made unique among its backend's). */
    fun add(profile: TailnetProfile): TailnetProfiles = copy(profiles = withFirst(profile.backend) + profile.copy(name = uniqueName(profile.backend, profile.name)))

    fun rename(id: String, name: String): TailnetProfiles {
        val p = find(id) ?: return this
        val clean = name.trim().ifBlank { return this }
        return copy(profiles = withFirst(p.backend).map { if (it.id == id) it.copy(name = clean) else it })
    }

    /**
     * Without account [id]. The active one is not removed (switch first): its node runs on it.
     * The last account of a backend is not removed either; leaving the tailnet is for that.
     */
    fun remove(id: String): TailnetProfiles {
        val p = find(id) ?: return this
        if (activeOf(p.backend).id == id || of(p.backend).size < 2) return this
        return copy(profiles = withFirst(p.backend).filter { it.id != id })
    }

    fun activate(id: String): TailnetProfiles {
        val p = find(id) ?: return this
        return copy(active = active + (p.backend to id))
    }

    /**
     * The account a host on the tailnet of [backend] goes through: the one it names while that
     * exists, else the active one.
     */
    fun profileOf(backend: TailnetBackend, named: String): TailnetProfile =
        of(backend).firstOrNull { it.id == named } ?: activeOf(backend)

    private fun withFirst(backend: TailnetBackend): List<TailnetProfile> =
        if (profiles.any { it.backend == backend && it.isFirst }) profiles else profiles + first(backend)

    private fun uniqueName(backend: TailnetBackend, name: String): String {
        val base = name.trim().ifBlank { backend.displayName }
        val taken = of(backend).map { it.name }.toSet()
        if (base !in taken) return base
        return (2..99).map { "$base $it" }.first { it !in taken }
    }

    companion object {
        fun first(backend: TailnetBackend) = TailnetProfile(backend.storageKey, backend, backend.displayName)

        /** A new account's id: the backend's key and a random part, safe as a directory name. */
        fun newId(backend: TailnetBackend, random: String): String =
            backend.storageKey + "-" + random.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(12)
    }
}
