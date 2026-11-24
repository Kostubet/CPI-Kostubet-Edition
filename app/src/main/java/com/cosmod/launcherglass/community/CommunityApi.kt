package com.cosmod.launcherglass.community

import com.cosmod.launcherglass.ui.SettingsTransfer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class CommunityApi(url: String, private val key: String) {
    private val base = URI(url)
    init {
        require(base.scheme == "https" && base.host != null && base.userInfo == null &&
            base.path.let { it.isEmpty() || it == "/" } && base.query == null && base.fragment == null)
        require(Regex("sb_publishable_[A-Za-z0-9_-]+").matches(key))
    }

    class Failure(val status: Int, val code: String) : Exception()

    data class Session(val access: String, val refresh: String, val expiresAt: Long, val email: String) {
        fun json() = JSONObject().put("access", access).put("refresh", refresh)
            .put("expires", expiresAt).put("email", email)

        companion object {
            fun read(body: JSONObject) = Session(body.getString("access"), body.getString("refresh"),
                body.getLong("expires"), body.getString("email"))
        }
    }

    enum class Reaction(val value: String) { LIKE("like"), FAVORITE("favorite") }

    data class Reactions(val likes: Long, val favorites: Long, val liked: Boolean, val favorited: Boolean) {
        companion object {
            fun read(row: JSONObject) = Reactions(row.getLong("likes"), row.getLong("favorites"),
                row.getBoolean("liked"), row.getBoolean("favorited"))
        }
    }

    data class Entry(val id: String, val title: String, val description: String, val author: String,
        val createdAt: String, val pinned: Boolean, val reactions: Reactions?)

    data class Page(val entries: List<Entry>, val supportsReactions: Boolean)

    fun githubSignInUrl(verifier: String): String = CommunityOAuth.authorizeUrl(base, verifier)

    fun exchangeCode(code: String, verifier: String): Session =
        session(request("/auth/v1/token?grant_type=pkce",
            JSONObject().put("auth_code", code).put("code_verifier", verifier)), "")

    fun refresh(previous: Session): Session =
        session(request("/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", previous.refresh)), previous.email)

    fun signOut(access: String) {
        request("/auth/v1/logout?scope=local", JSONObject(), access)
    }

    private fun session(text: String, email: String): Session {
        val payload = JSONObject(text)
        val seconds = payload.optLong("expires_in", 3600).coerceIn(1, 86_400)
        return Session(payload.getString("access_token"), payload.getString("refresh_token"),
            System.currentTimeMillis() + seconds * 1000, payload.optJSONObject("user")?.optString("email")
                ?.takeIf(String::isNotBlank) ?: email)
    }

    fun name(access: String): String? = JSONArray("[${rpc("cpi_profile_name", JSONObject(), access)}]")
        .let { if (it.isNull(0)) null else it.getString(0) }

    fun setName(access: String, name: String) {
        rpc("cpi_set_profile_name", JSONObject().put("nickname", name), access)
    }

    fun search(query: String, after: Entry?, mine: Boolean, favorites: Boolean, access: String?): Page {
        val cursor = JSONObject().put("search_text", query.take(80)).put("own_only", mine)
            .put("after_time", after?.createdAt ?: JSONObject.NULL)
            .put("after_id", after?.id ?: JSONObject.NULL)
        val payload = JSONObject(cursor.toString()).put("favorites_only", favorites)
            .put("after_pinned", after?.pinned ?: JSONObject.NULL)
        val (rows, supportsReactions) = try {
            JSONArray(rpc("cpi_find_configs_v2", payload, access)) to true
        } catch (error: Failure) {
            if (error.code != "PGRST202" || favorites) throw error
            JSONArray(rpc("cpi_find_configs", cursor, access)) to false
        }
        require(rows.length() <= PAGE_SIZE)
        val entries = List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            Entry(row.getString("id"), row.getString("title"), row.getString("description"),
                row.getString("author"), row.getString("created_at"), row.optBoolean("pinned"),
                if (supportsReactions) Reactions.read(row) else null)
        }
        return Page(entries, supportsReactions)
    }

    fun canPin(access: String): Boolean = try {
        rpc("cpi_can_pin_configs", JSONObject(), access).trim() == "true"
    } catch (error: Failure) {
        if (error.code != "PGRST202") throw error
        false
    }

    fun setPin(access: String, id: String, pinned: Boolean) {
        rpc("cpi_set_config_pin", JSONObject().put("config_id", id).put("pinned", pinned), access)
    }

    fun setReaction(access: String, id: String, reaction: Reaction, selected: Boolean): Reactions {
        val payload = JSONObject().put("config_id", id).put("reaction", reaction.value).put("selected", selected)
        return Reactions.read(JSONArray(rpc("cpi_set_config_reaction", payload, access)).getJSONObject(0))
    }

    fun download(id: String): SettingsTransfer.Document {
        val result = rpc("cpi_get_config", JSONObject().put("config_id", id))
        if (result.trim() == "null") throw Failure(404, "")
        return SettingsTransfer.parseDocument(result)
    }

    fun publish(access: String, title: String, description: String, settings: String) {
        val document = JSONObject(settings)
        val portable = SettingsTransfer.parseDocument(settings)
        // Re-encode the validated portable keys. Nothing from the session,
        // preview device, or local saved profiles can enter this payload.
        document.put("settings", JSONObject(portable.values))
        document.remove("metadata")
        rpc("cpi_publish_config", JSONObject().put("config_title", title)
            .put("config_description", description).put("document", document), access)
    }

    fun remove(access: String, id: String) {
        if (rpc("cpi_remove_config", JSONObject().put("config_id", id), access).trim() != "true")
            throw Failure(404, "")
    }

    private fun rpc(method: String, args: JSONObject, access: String? = null) =
        request("/rest/v1/rpc/$method", args, access)

    private fun request(path: String, body: JSONObject, access: String? = null): String {
        val connection = URL(base.toString().trimEnd('/') + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("apikey", key)
            if (access != null) connection.setRequestProperty("Authorization", "Bearer $access")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.doOutput = true
            connection.outputStream.use { it.write(bytes) }

            val status = connection.responseCode
            val ok = status in 200..299
            val stream = if (ok) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > 128 * 1024) throw Failure(status, "response_too_large")
                    output.write(buffer, 0, size)
                }
                output.toString("UTF-8")
            }.orEmpty()
            if (!ok) {
                val code = runCatching {
                    JSONObject(text).let { it.optString("error_code").ifEmpty { it.optString("code") } }
                }.getOrDefault("")
                throw Failure(status, code)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PAGE_SIZE = 24
        val NICKNAME = Regex("[A-Za-z0-9_]{3,24}")
    }
}
