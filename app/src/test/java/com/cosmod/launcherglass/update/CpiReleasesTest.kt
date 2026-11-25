package com.cosmod.launcherglass.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CpiReleasesTest {
    private val digest = "ab".repeat(32)
    private fun feed(build: Int = 425, tag: String = "v0.3.1") = JSONObject().apply {
        put("format", 1); put("tag", tag); put("version", "0.3.1"); put("build", build)
        val name = "CPI-Kostubet-Edition-0.3.1-build$build.apk"
        put("apk", JSONObject().apply {
            put("name", name); put("size", 33000000)
            put("url", "https://github.com/Kostubet/CPI-Kostubet-Edition/releases/download/$tag/$name")
            put("sha256", digest)
        })
    }
    private fun release(build: Int = 425, tag: String = "v0.3.1", draft: Boolean = false): JSONObject {
        val manifest = feed(build, tag)
        val apk = manifest.getJSONObject("apk")
        return JSONObject().apply {
            put("tag_name", tag); put("draft", draft)
            put("assets", JSONArray().put(JSONObject().apply {
                put("name", apk.getString("name")); put("size", apk.getLong("size"))
                put("browser_download_url", apk.getString("url")); put("digest", "sha256:$digest")
            }))
        }
    }

    @Test fun plainStableTagAndBuildTagsAreDiscoverable() {
        assertEquals(425, CpiReleases.manifest(feed())?.build)
        assertEquals(426, CpiReleases.manifest(feed(426, "v0.3.1-build426"))?.build)
        assertEquals(426, CpiReleases.select(JSONArray().put(release()).put(release(426, "v0.3.1-build426")))?.build)
    }

    @Test fun notesAreOptionalAndSelectedWithTheRelease() {
        assertNull(CpiReleases.manifest(feed())?.notes)
        assertNull(CpiReleases.manifest(feed().put("notes", JSONArray()))?.notes)
        assertEquals("Published", CpiReleases.manifest(feed().put("notes", "Published"))?.notes)
        val entries = JSONArray().put(release().put("body", "Old"))
            .put(release(426, "v0.3.1-build426").put("body", "New"))
        assertEquals("New", CpiReleases.select(entries)?.notes)
    }

    @Test fun rejectsInconsistentOrForeignManifestAssets() {
        val invalid = listOf(
            feed().put("build", 424), feed().put("version", "0.3.0"), feed().put("format", 2),
            feed().apply { getJSONObject("apk").put("url", "https://example.com/update.apk") },
            feed().apply { getJSONObject("apk").put("sha256", "unverified") },
            feed().apply { getJSONObject("apk").put("size", 0) },
            feed().apply { getJSONObject("apk").put("size", 129L * 1024 * 1024) },
            feed(425, "v0.3.1-build426")
        )
        invalid.forEach { assertNull(it.toString(), CpiReleases.manifest(it)) }
    }

    @Test fun workingPublicFeedDoesNotQueryRateLimitedApi() {
        val urls = mutableListOf<String>()
        val found = CpiReleases.discover { url, _ ->
            urls += url
            if (url != CpiReleases.FEED) throw CpiReleases.HttpFailure(403)
            feed().toString().toByteArray()
        }
        assertEquals(425, found?.build)
        assertEquals(listOf(CpiReleases.FEED), urls)
    }

    @Test fun brokenOrUnreachableFeedFallsBackToMirror() {
        for (badJson in listOf(false, true)) {
            val urls = mutableListOf<String>()
            val found = CpiReleases.discover { url, _ ->
                urls += url
                if (url == CpiReleases.FEED) {
                    if (!badJson) throw IOException("offline")
                    "not json".toByteArray()
                } else feed().toString().toByteArray()
            }
            assertEquals(425, found?.build)
            assertEquals(listOf(CpiReleases.FEED, CpiReleases.MIRROR), urls)
        }
    }

    @Test fun missingFeedsKeepApiCompatibilityAndSkipDrafts() {
        val found = CpiReleases.discover { url, _ ->
            if (url == CpiReleases.FEED || url == CpiReleases.MIRROR) "{}".toByteArray()
            else JSONArray().put(release()).put(release(427, "v0.3.1-build427", draft = true)).toString().toByteArray()
        }
        assertEquals(425, found?.build)
    }

    @Test fun networkFailureIsNotReportedAsLatestVersion() {
        try {
            CpiReleases.discover { _, _ -> throw CpiReleases.HttpFailure(403) }
            fail("A failed check must propagate to the update screen")
        } catch (expected: CpiReleases.HttpFailure) {
            assertEquals(403, expected.status)
        }
    }
}
