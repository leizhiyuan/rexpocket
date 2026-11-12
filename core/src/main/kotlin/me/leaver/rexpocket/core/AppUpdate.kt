package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A version as the app writes them in its tags: `0.0.16`, `0.1.0-alpha1`. Numeric parts compare as
 * numbers; a build without a pre-release part is newer than one with it, so `0.1.0` beats
 * `0.1.0-rc1`, and the pre-release parts compare name first (`alpha` < `beta`), then number
 * (`alpha1` < `alpha2` < `alpha10`).
 */
class AppVersion private constructor(
    val numbers: List<Int>,
    val preRelease: List<String>,
) : Comparable<AppVersion> {

    /** A build that is still an alpha, a beta or a release candidate: it accepts pre-releases. */
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: AppVersion): Int {
        val width = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until width) {
            val mine = numbers.getOrElse(i) { 0 }
            val theirs = other.numbers.getOrElse(i) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until maxOf(preRelease.size, other.preRelease.size)) {
            // A shorter list of pre-release parts comes first: 0.1.0-alpha < 0.1.0-alpha.1.
            val mine = preRelease.getOrNull(i) ?: return -1
            val theirs = other.preRelease.getOrNull(i) ?: return 1
            val order = comparePart(mine, theirs)
            if (order != 0) return order
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = numbers.hashCode() * 31 + preRelease.hashCode()

    override fun toString(): String =
        numbers.joinToString(".") + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        /** Parses a tag or a version name; null when it is not one, so nothing is offered for it. */
        fun parse(text: String): AppVersion? {
            val trimmed = text.trim().removePrefix("v").removePrefix("V")
            if (trimmed.isEmpty()) return null
            val dash = trimmed.indexOf('-')
            val core = if (dash < 0) trimmed else trimmed.substring(0, dash)
            val tail = if (dash < 0) "" else trimmed.substring(dash + 1)
            val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
            if (numbers.isEmpty()) return null
            val preRelease = if (tail.isEmpty()) emptyList() else tail.split('.', '-').filter { it.isNotEmpty() }
            return AppVersion(numbers, preRelease)
        }

        /** `alpha10` against `alpha2`: the letters first, then the number they end in. */
        private fun comparePart(mine: String, theirs: String): Int {
            if (mine == theirs) return 0
            val (myName, myNumber) = splitPart(mine)
            val (theirName, theirNumber) = splitPart(theirs)
            if (myName != theirName) {
                // A part that is only digits ranks below one with letters, as in SemVer.
                if (myName.isEmpty() || theirName.isEmpty()) return if (myName.isEmpty()) -1 else 1
                return myName.compareTo(theirName)
            }
            return myNumber.compareTo(theirNumber)
        }

        private fun splitPart(part: String): Pair<String, Long> {
            val digitsFrom = part.indexOfLast { !it.isDigit() } + 1
            val number = part.substring(digitsFrom).toLongOrNull() ?: 0L
            return part.substring(0, digitsFrom) to number
        }
    }
}

/** The one APK a release carries, as the feed describes it. */
data class UpdateApk(
    val name: String,
    /** Where to download it: GitHub's own redirect, so the app needs no token. */
    val url: String,
    val size: Long,
    /** `sha256:` + hex, when the feed has one; checked after the download. */
    val sha256: String?,
)

/** A published build of the app. */
data class AppRelease(
    val tag: String,
    val version: AppVersion,
    val preRelease: Boolean,
    val apk: UpdateApk?,
) {
    companion object {
        /**
         * Reads the GitHub releases feed. A draft is not published, and a release without an APK
         * cannot be installed, so neither is offered; a version the app cannot parse is skipped
         * rather than guessed at.
         */
        fun parseFeed(json: String): List<AppRelease> {
            val releases = JSONArray(json)
            return buildList {
                for (i in 0 until releases.length()) {
                    val release = releases.optJSONObject(i) ?: continue
                    if (release.optBoolean("draft")) continue
                    val tag = release.optString("tag_name")
                    val version = AppVersion.parse(tag) ?: continue
                    add(
                        AppRelease(
                            tag = tag,
                            version = version,
                            preRelease = release.optBoolean("prerelease"),
                            apk = apkOf(release),
                        ),
                    )
                }
            }
        }

        private fun apkOf(release: JSONObject): UpdateApk? {
            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk")) continue
                val url = asset.optString("browser_download_url")
                if (url.isEmpty()) continue
                return UpdateApk(
                    name = name,
                    url = url,
                    size = asset.optLong("size"),
                    sha256 = asset.optString("digest").takeIf { it.startsWith("sha256:") },
                )
            }
            return null
        }
    }
}

/**
 * The newest release worth installing: newer than [current], carrying an APK, and stable unless the
 * app itself is a pre-release - an alpha is not offered over a finished version.
 */
fun List<AppRelease>.newestFor(current: AppVersion): AppRelease? =
    filter { it.apk != null && it.version > current && (current.isPreRelease || !it.preRelease) }
        .maxByOrNull { it.version }
