// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris

import me.lagna.ephemeris.swe.SweThreads
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Settings from environment variables, falling back to JVM system properties (used by Gradle runs and tests).
 *
 * | Variable           | Property     | Default                                              |
 * |--------------------|--------------|------------------------------------------------------|
 * | `PORT`             | `port`       | 50051                                                |
 * | `SWE_LIB`          | `swe.lib`    | `native/libswe.so` of a release archive              |
 * | `SWE_EPHE`         | `swe.ephe`   | `ephe/` of a release archive                         |
 * | `SWE_THREADS`      | `swe.threads`| CPU count (1 on macOS)                               |
 * | `SWE_QUEUE`        | `swe.queue`  | 256 requests waiting for a worker; more get RESOURCE_EXHAUSTED |
 * | `MAX_ITEMS`        | `max.items`  | 10000: instants x bodies, queries, search results    |
 * | `MAX_STREAM_ITEMS` | `max.stream` | 1000000: samples x bodies of one StreamPositions     |
 * | `CACHE_MAX_MB`     | `cache.mb`   | 64; 0 disables the cache                             |
 * | `SEARCH_WITHOUT_SPEED` | `search.withoutSpeed` | true: crossing searches skip `SEFLG_SPEED`  |
 * | `SOURCE_URL`       | `source.url` | this repository                                      |
 */
data class ServiceConfig(
    val port: Int = DEFAULT_PORT,
    val library: Path,
    val ephemeris: Path,
    val threads: Int = SweThreads.defaultSize(),
    val queueCapacity: Int = SweThreads.DEFAULT_QUEUE_CAPACITY,
    val limits: Limits = Limits(),
    val cacheMaxBytes: Long = DEFAULT_CACHE_MB * MB,
    val sourceUrl: String = DEFAULT_SOURCE_URL,
    /** Crossing searches compute longitudes without speed; station searches always use speed. */
    val searchWithoutSpeed: Boolean = true,
) {
    data class Limits(
        val maxItems: Int = DEFAULT_MAX_ITEMS,
        val maxStreamItems: Long = DEFAULT_MAX_STREAM_ITEMS,
        /** Instants the bundled `.se1` files cover for every body, with margin for rise/set look-ahead. */
        val supportedFrom: Instant = Instant.parse("1800-01-02T00:00:00Z"),
        val supportedTo: Instant = Instant.parse("2400-01-01T00:00:00Z"),
    )

    companion object {
        const val DEFAULT_PORT = 50051
        const val DEFAULT_MAX_ITEMS = 10_000
        const val DEFAULT_MAX_STREAM_ITEMS = 1_000_000L
        const val DEFAULT_CACHE_MB = 64L
        const val DEFAULT_SOURCE_URL = "https://github.com/vibemade/swiss-ephemeris-service"
        private const val MB = 1024L * 1024L

        /**
         * A file inside the release archive this jar came from (`<home>/lib/<jar>`), or null when running from
         * classes or the file is not there.
         */
        private fun bundled(relative: String): String? {
            val jar =
                runCatching { Path.of(ServiceConfig::class.java.protectionDomain.codeSource.location.toURI()) }
                    .getOrNull() ?: return null
            if (!Files.isRegularFile(jar)) return null
            val candidate = jar.parent?.parent?.resolve(relative) ?: return null
            return if (Files.exists(candidate)) candidate.toString() else null
        }

        private fun parseBoolean(value: String): Boolean =
            when (value.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> error("expected true or false, got '$value'")
            }

        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServiceConfig {
            fun setting(
                variable: String,
                property: String,
            ): String? = env[variable]?.takeIf { it.isNotBlank() } ?: System.getProperty(property)

            fun required(
                variable: String,
                property: String,
            ): String = setting(variable, property) ?: error("set $variable (or -D$property)")

            return ServiceConfig(
                port = setting("PORT", "port")?.toInt() ?: DEFAULT_PORT,
                library = Path.of(setting("SWE_LIB", "swe.lib") ?: bundled("native/libswe.so") ?: required("SWE_LIB", "swe.lib")),
                ephemeris = Path.of(setting("SWE_EPHE", "swe.ephe") ?: bundled("ephe") ?: required("SWE_EPHE", "swe.ephe")),
                threads = setting("SWE_THREADS", "swe.threads")?.toInt() ?: SweThreads.defaultSize(),
                queueCapacity = setting("SWE_QUEUE", "swe.queue")?.toInt() ?: SweThreads.DEFAULT_QUEUE_CAPACITY,
                limits =
                    Limits(
                        maxItems = setting("MAX_ITEMS", "max.items")?.toInt() ?: DEFAULT_MAX_ITEMS,
                        maxStreamItems = setting("MAX_STREAM_ITEMS", "max.stream")?.toLong() ?: DEFAULT_MAX_STREAM_ITEMS,
                    ),
                cacheMaxBytes = (setting("CACHE_MAX_MB", "cache.mb")?.toLong() ?: DEFAULT_CACHE_MB) * MB,
                sourceUrl = setting("SOURCE_URL", "source.url") ?: DEFAULT_SOURCE_URL,
                searchWithoutSpeed = setting("SEARCH_WITHOUT_SPEED", "search.withoutSpeed")?.let(::parseBoolean) ?: true,
            )
        }
    }
}
