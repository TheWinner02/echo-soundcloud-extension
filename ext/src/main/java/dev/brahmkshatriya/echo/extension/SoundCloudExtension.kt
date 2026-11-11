package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.providers.GlobalSettingsProvider

import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class SoundCloudExtension : ExtensionClient, QuickSearchClient, SearchFeedClient, TrackClient,
    HomeFeedClient, LibraryFeedClient, LoginClient.WebView, ArtistClient,
    PlaylistClient, RadioClient, LikeClient, FollowClient, GlobalSettingsProvider {
    override fun setGlobalSettings(globalSettings: Settings) {
        ExtensionText.settings = globalSettings
    }


    private val httpClient = OkHttpClient()
    private lateinit var setting: Settings
    private val json = Json { ignoreUnknownKeys = true }
    private var clientId = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"

    private var currentUser: User? = null
    private var oauthToken: String? = null
    private val hideSnip get() = if (::setting.isInitialized) setting.getBoolean("hide_snip") ?: false else false

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingSwitch(
            key = "hide_snip",
            title = ExtensionText.get("providers.hide_preview_only_tracks_09a698", "Hide Preview-Only Tracks"),
            summary = ExtensionText.get("providers.hide_tracks_that_only_play_a_30_second_preview_g_3b319d", "Hide tracks that only play a 30-second preview (Go+ tracks)"),
            defaultValue = false
        )
    )

    override fun setSettings(settings: Settings) {
        setting = settings
        oauthToken = settings.getString("auth_token")
        clientId = settings.getString("client_id") ?: clientId
    }

    override suspend fun onInitialize() {
        refreshClientId()
    }

    private suspend fun refreshClientId() {
        runCatching {
            val html = httpClient.newCall(
                Request.Builder().url("https://soundcloud.com").build()
            ).await().use { it.body.string() }
            val scriptUrls = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
                .findAll(html).map { it.groupValues[1] }.toList().reversed()
            for (scriptUrl in scriptUrls) {
                val script = httpClient.newCall(
                    Request.Builder().url(scriptUrl).build()
                ).await().use { it.body.string() }
                val id = Regex("""client_id[:=]\s*["']([a-zA-Z0-9]{30,34})["']""")
                    .find(script)?.groupValues?.get(1) ?: continue
                clientId = id
                println("SoundCloudExtension: Client ID refreshed: $id")
                if (::setting.isInitialized) {
                    setting.putString("client_id", id)
                }
                break
            }
        }
    }

    private fun Request.Builder.withAuth(): Request.Builder {
        oauthToken?.let { header("Authorization", "OAuth $it") }
        return this
    }

    private fun encode(param: String): String = URLEncoder.encode(param, "UTF-8")

    // ---- LOGIN ----

    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl = NetworkRequest("https://soundcloud.com/signin")
        override val stopUrlRegex = Regex("soundcloud\\.com/discover|soundcloud\\.com/feed|soundcloud\\.com/you")

        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User>? {
            val token = cookie.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("auth_token=") || it.startsWith("oauth_token=") }
                ?.split("=", limit = 2)?.getOrNull(1)?.trim()?.removeSurrounding("\"")
                ?: return null

            oauthToken = token
            if (::setting.isInitialized) {
                setting.putString("auth_token", token)
            }

            val body = httpClient.newCall(
                Request.Builder().url("https://api-v2.soundcloud.com/me?client_id=$clientId")
                    .withAuth().build()
            ).await().use { it.body.string() }
            val root = json.parseToJsonElement(body).jsonObject
            val id = root["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
            val name = root["username"]?.jsonPrimitive?.contentOrNull ?: return null
            val user = User(
                id = id,
                name = name,
                cover = root["avatar_url"]?.jsonPrimitive?.contentOrNull?.toImageHolder(),
                extras = mapOf("auth_token" to token)
            )
            return listOf(user)
        }
    }

    override fun setLoginUser(user: User?) {
        currentUser = user
        oauthToken = user?.extras?.get("auth_token")
            ?: if (::setting.isInitialized) setting.getString("auth_token") else null
    }

    override suspend fun getCurrentUser(): User? = currentUser

    // ---- LIKE ----

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        val token = oauthToken ?: return false
        val userId = currentUser?.id ?: return false
        val trackId = (item as? Track)?.id ?: return false
        return httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/users/$userId/track_likes/$trackId?client_id=$clientId")
                .header("Authorization", "OAuth $token").build()
        ).await().use { it.code == 200 }
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        val token = oauthToken ?: return
        val userId = currentUser?.id ?: return
        val trackId = (item as? Track)?.id ?: return
        val url = "https://api-v2.soundcloud.com/users/$userId/track_likes/$trackId?client_id=$clientId"
        val request = if (shouldLike)
            Request.Builder().url(url).header("Authorization", "OAuth $token")
                .put("".toRequestBody("application/json".toMediaType())).build()
        else
            Request.Builder().url(url).header("Authorization", "OAuth $token").delete().build()
        httpClient.newCall(request).await().close()
    }

    // ---- FOLLOW ----

    override suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val token = oauthToken ?: return false
        val artistId = (item as? Artist)?.id ?: return false
        return httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/me/followings/$artistId?client_id=$clientId")
                .header("Authorization", "OAuth $token").build()
        ).await().use { it.code == 200 }
    }

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        val token = oauthToken ?: return
        val artistId = (item as? Artist)?.id ?: return
        val url = "https://api-v2.soundcloud.com/me/followings/$artistId?client_id=$clientId"
        val request = if (shouldFollow)
            Request.Builder().url(url).header("Authorization", "OAuth $token")
                .put("".toRequestBody("application/json".toMediaType())).build()
        else
            Request.Builder().url(url).header("Authorization", "OAuth $token").delete().build()
        httpClient.newCall(request).await().close()
    }

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? {
        val artistId = (item as? Artist)?.id ?: return null
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/users/$artistId?client_id=$clientId").build()
        ).await().use { it.body.string() }
        return json.parseToJsonElement(body).jsonObject["followers_count"]?.jsonPrimitive?.longOrNull
    }

    // ---- HOME FEED ----

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()

        if (oauthToken != null) {
            runCatching {
                val body = httpClient.newCall(
                    Request.Builder()
                        .url("https://api-v2.soundcloud.com/me/play-history/tracks?client_id=$clientId&limit=10")
                        .withAuth().build()
                ).await().use { it.body.string() }
                if (!body.isNullOrBlank()) {
                    val tracks = json.parseToJsonElement(body).jsonObject["collection"]
                        ?.jsonArray?.mapNotNull { it.jsonObject["track"]?.jsonObject?.toTrack() }
                        ?.filter { !hideSnip || !it.extras.containsKey("snip") }
                        .orEmpty()
                    if (tracks.isNotEmpty())
                        shelves.add(Shelf.Lists.Tracks(id = "recently_played", title = ExtensionText.get("providers.recently_played_2344da", "Recently Played"), list = tracks))
                }
            }

            runCatching {
                val body = httpClient.newCall(
                    Request.Builder()
                        .url("https://api-v2.soundcloud.com/stream?client_id=$clientId&limit=20")
                        .withAuth().build()
                ).await().use { it.body.string() }
                if (!body.isNullOrBlank()) {
                    val tracks = json.parseToJsonElement(body).jsonObject["collection"]
                        ?.jsonArray?.mapNotNull {
                            val obj = it.jsonObject
                            val trackObj = obj["track"]?.jsonObject
                                ?: if (obj["kind"]?.jsonPrimitive?.contentOrNull == "track") obj else null
                            trackObj?.toTrack()
                        }?.filter { !hideSnip || !it.extras.containsKey("snip") }
                        .orEmpty()
                    if (tracks.isNotEmpty())
                        shelves.add(Shelf.Lists.Tracks(id = "your_stream", title = ExtensionText.get("providers.your_stream_930dee", "Your Stream"), list = tracks))
                }
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/charts?kind=trending&genre=soundcloud:genres:all-music&client_id=$clientId&limit=20")
                    .build()
            ).await().use { it.body.string() }
            if (!body.isNullOrBlank()) {
                val tracks = json.parseToJsonElement(body).jsonObject["collection"]
                    ?.jsonArray?.mapNotNull { it.jsonObject["track"]?.jsonObject?.toTrack() }
                    ?.filter { !hideSnip || !it.extras.containsKey("snip") }
                    .orEmpty()
                if (tracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "trending", title = ExtensionText.get("providers.trending_5e1a0e", "Trending"), list = tracks))
            }
        }

        return shelves.toFeed()
    }

    // ---- LIBRARY Feed ----

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val token = oauthToken ?: return listOf<Shelf>().toFeed()
        val userId = currentUser?.id ?: return listOf<Shelf>().toFeed()
        val shelves = mutableListOf<Shelf>()

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/users/$userId/track_likes?client_id=$clientId&limit=20")
                    .header("Authorization", "OAuth $token").build()
            ).await().use { it.body.string() }
            if (!body.isNullOrBlank()) {
                val tracks = json.parseToJsonElement(body).jsonObject["collection"]
                    ?.jsonArray?.mapNotNull {
                        val obj = it.jsonObject
                        obj["track"]?.jsonObject?.toTrack()
                            ?: if (obj["kind"]?.jsonPrimitive?.contentOrNull == "track") obj.toTrack() else null
                    }?.filter { !hideSnip || !it.extras.containsKey("snip") }
                    .orEmpty()
                if (tracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "liked_tracks", title = ExtensionText.get("providers.liked_tracks_8268ae", "Liked Tracks"), list = tracks))
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/users/$userId/playlists_without_albums?client_id=$clientId&limit=20")
                    .header("Authorization", "OAuth $token").build()
            ).await().use { it.body.string() }
            if (!body.isNullOrBlank()) {
                val playlists = json.parseToJsonElement(body).jsonObject["collection"]
                    ?.jsonArray?.mapNotNull { runCatching { it.jsonObject.toPlaylist() }.getOrNull() }
                    .orEmpty()
                if (playlists.isNotEmpty())
                    shelves.add(Shelf.Lists.Items(
                        id = "playlists", title = ExtensionText.get("providers.playlists_dcd0a4", "Playlists"),
                        list = playlists.map { it.toShelf().media }
                    ))
            }
        }

        return shelves.toFeed()
    }

    // ---- SEARCH ----

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/search/autocomplete?q=${encode(query)}&client_id=$clientId&limit=5")
                .build()
        ).await().use { it.body.string() }
        return runCatching {
            json.parseToJsonElement(body).jsonObject["collection"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject["output"]?.jsonPrimitive?.contentOrNull }
                ?.map { QuickSearchItem.Query(it, false) }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val encodedQuery = encode(query)

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/search/tracks?q=$encodedQuery&client_id=$clientId&limit=20")
                    .build()
            ).await().use { it.body.string() }
            if (!body.isNullOrBlank()) {
                val tracks = json.parseToJsonElement(body).jsonObject["collection"]
                    ?.jsonArray?.mapNotNull { runCatching { it.jsonObject.toTrack() }.getOrNull() }
                    ?.filter { !hideSnip || !it.extras.containsKey("snip") }
                    .orEmpty()
                if (tracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "search_tracks", title = ExtensionText.get("providers.tracks_889801", "Tracks"), list = tracks))
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/search/playlists?q=$encodedQuery&client_id=$clientId&limit=10")
                    .build()
            ).await().use { it.body.string() }
            if (!body.isNullOrBlank()) {
                val playlists = json.parseToJsonElement(body).jsonObject["collection"]
                    ?.jsonArray?.mapNotNull { runCatching { it.jsonObject.toPlaylist() }.getOrNull() }
                    .orEmpty()
                if (playlists.isNotEmpty())
                    shelves.add(Shelf.Lists.Items(
                        id = "search_playlists", title = ExtensionText.get("providers.playlists_dcd0a4", "Playlists"),
                        list = playlists.map { it.toShelf().media }
                    ))
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/search/users?q=$encodedQuery&client_id=$clientId&limit=10")
                    .build()
            ).await().use { it.body.string() }
            if (!body.isNullOrBlank()) {
                val artists = json.parseToJsonElement(body).jsonObject["collection"]
                    ?.jsonArray?.mapNotNull { runCatching { it.jsonObject.toArtist() }.getOrNull() }
                    .orEmpty()
                if (artists.isNotEmpty())
                    shelves.add(Shelf.Lists.Items(
                        id = "search_users", title = ExtensionText.get("providers.users_6b0cc9", "Users"),
                        list = artists.map { it.toShelf().media }
                    ))
            }
        }

        return shelves.toFeed()
    }

    // ---- ARTIST ----

    override suspend fun loadArtist(artist: Artist): Artist {
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/users/${artist.id}?client_id=$clientId").build()
        ).await().use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val name = root["username"]?.jsonPrimitive?.contentOrNull ?: artist.name
        return Artist(
            id = artist.id,
            name = name,
            cover = root["avatar_url"]?.jsonPrimitive?.contentOrNull?.replace("large", "t500x500")?.toImageHolder() ?: artist.cover,
            bio = root["description"]?.jsonPrimitive?.contentOrNull,
            subtitle = root["followers_count"]?.jsonPrimitive?.longOrNull?.let { if (it > 0) ExtensionText.get("providers.1_d_followers_5d1551", "%1\$d followers", it) else null },
            isFollowable = oauthToken != null
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/users/${artist.id}/tracks?client_id=$clientId&limit=20").build()
            ).await().use { it.body.string() }
            if (!body.isNullOrBlank()) {
                val tracks = json.parseToJsonElement(body).jsonObject["collection"]
                    ?.jsonArray?.mapNotNull { runCatching { it.jsonObject.toTrack() }.getOrNull() }
                    ?.filter { !hideSnip || !it.extras.containsKey("snip") }
                    .orEmpty()
                if (tracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "artist_tracks", title = ExtensionText.get("providers.tracks_889801", "Tracks"), list = tracks))
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/users/${artist.id}/playlists_without_albums?client_id=$clientId&limit=10").build()
            ).await().use { it.body.string() }
            if (!body.isNullOrBlank()) {
                val playlists = json.parseToJsonElement(body).jsonObject["collection"]
                    ?.jsonArray?.mapNotNull { runCatching { it.jsonObject.toPlaylist() }.getOrNull() }
                    .orEmpty()
                if (playlists.isNotEmpty())
                    shelves.add(Shelf.Lists.Items(
                        id = "artist_playlists", title = ExtensionText.get("providers.playlists_dcd0a4", "Playlists"),
                        list = playlists.map { it.toShelf().media }
                    ))
            }
        }

        return shelves.toFeed()
    }

    // ---- PLAYLIST ----

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = playlist

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/playlists/${playlist.id}?client_id=$clientId&representation=full").build()
        ).await().use { it.body.string() }
        if (body.isNullOrBlank()) return listOf<Track>().toFeed()
        val root = json.parseToJsonElement(body).jsonObject
        val trackObjs = root["tracks"]?.jsonArray ?: return listOf<Track>().toFeed()

        val trackMap = mutableMapOf<String, Track>()
        val missingIds = mutableListOf<String>()

        for (element in trackObjs) {
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.longOrNull?.toString() ?: continue
            if (obj.containsKey("title")) {
                obj.toTrack()?.let { trackMap[id] = it }
            } else {
                missingIds.add(id)
            }
        }

        for (chunk in missingIds.chunked(50)) {
            runCatching {
                val idsParam = chunk.joinToString(",")
                val tracksBody = httpClient.newCall(
                    Request.Builder()
                        .url("https://api-v2.soundcloud.com/tracks?ids=$idsParam&client_id=$clientId").build()
                ).await().use { it.body.string() }
                if (!tracksBody.isNullOrBlank()) {
                    json.parseToJsonElement(tracksBody).jsonArray.forEach { elem ->
                        elem.jsonObject.toTrack()?.let { track ->
                            trackMap[track.id] = track
                        }
                    }
                }
            }
        }

        val orderedTracks = trackObjs.mapNotNull { element ->
            val id = element.jsonObject["id"]?.jsonPrimitive?.longOrNull?.toString()
            trackMap[id]
        }.filter { !hideSnip || !it.extras.containsKey("snip") }

        return orderedTracks.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    // ---- RADIO ----

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        return Radio(
            id = item.id,
            title = ExtensionText.get("providers.radio_1_s_736db9", "Radio: %1\$s", item.title),
            cover = item.cover,
            extras = mapOf("seed_id" to item.id)
        )
    }

    override suspend fun loadRadio(radio: Radio): Radio = radio

    override suspend fun loadTracks(radio: Radio): Feed<Track> {
        val seedId = radio.extras["seed_id"] ?: radio.id
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/tracks/$seedId/related?client_id=$clientId&limit=20").build()
        ).await().use { it.body.string() }
        if (body.isNullOrBlank()) return listOf<Track>().toFeed()
        val tracks = json.parseToJsonElement(body).jsonObject["collection"]
            ?.jsonArray?.mapNotNull { runCatching { it.jsonObject.toTrack() }.getOrNull() }
            ?.filter { !hideSnip || !it.extras.containsKey("snip") }
            .orEmpty()
        return tracks.toFeed()
    }

    // ---- TRACK ----

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        if (track.streamables.isNotEmpty()) return track
        val url = "https://api-v2.soundcloud.com/tracks/${track.id}?client_id=$clientId"
        val res = httpClient.newCall(Request.Builder().url(url).withAuth().build()).await()
        val body = res.use { if (it.isSuccessful) it.body.string() else null } ?: return track
        return runCatching {
            json.parseToJsonElement(body).jsonObject.toTrack() ?: track
        }.getOrDefault(track)
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        suspend fun resolveMediaUrl(targetUrl: String): String? {
            val separator = if (targetUrl.contains("?")) "&" else "?"
            val fullUrl = "${targetUrl}${separator}client_id=$clientId"

            var resp = httpClient.newCall(Request.Builder().url(fullUrl).withAuth().build()).await()
            var body = resp.use { if (it.isSuccessful) it.body.string() else null }

            if (body.isNullOrBlank() && oauthToken != null) {
                resp = httpClient.newCall(Request.Builder().url(fullUrl).build()).await()
                body = resp.use { if (it.isSuccessful) it.body.string() else null }
            }

            if (body.isNullOrBlank()) return null
            return runCatching {
                json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }

        suspend fun resolveWithRetry(targetUrl: String): String? {
            var res = resolveMediaUrl(targetUrl)
            if (res == null) {
                refreshClientId()
                res = resolveMediaUrl(targetUrl)
            }
            return res
        }

        var streamUrl: String? = null
        val targetId = streamable.id

        if (targetId.startsWith("http")) {
            streamUrl = resolveWithRetry(targetId)
        }

        if (streamUrl == null) {
            val trackId = Regex("""tracks[:/](\d+)""").find(targetId)?.groupValues?.get(1) ?: targetId
            val trackApiUrl = "https://api-v2.soundcloud.com/tracks/$trackId?client_id=$clientId"
            val trackResp = httpClient.newCall(Request.Builder().url(trackApiUrl).build()).await()
            val trackBody = trackResp.use { if (it.isSuccessful) it.body.string() else null }
            if (!trackBody.isNullOrBlank()) {
                val transcodings = runCatching {
                    json.parseToJsonElement(trackBody).jsonObject["media"]?.jsonObject?.get("transcodings")?.jsonArray
                }.getOrNull()

                if (transcodings != null) {
                    for (t in transcodings) {
                        val transUrl = t.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: continue
                        val resolved = resolveMediaUrl(transUrl)
                        if (resolved != null) {
                            streamUrl = resolved
                            break
                        }
                    }
                }
            }
        }

        if (streamUrl == null) {
            throw Exception("Failed to resolve stream URL from SoundCloud for '$targetId'")
        }

        val isHls = streamUrl.contains(".m3u8") || streamable.id.contains("hls")
        return if (isHls) streamUrl.toServerMedia(type = Streamable.SourceType.HLS)
        else streamUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // ---- HELPERS ----

    private fun kotlinx.serialization.json.JsonObject.toTrack(): Track? {
        val id = this["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
        val title = this["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val duration = this["duration"]?.jsonPrimitive?.longOrNull ?: 0L
        val artwork = this["artwork_url"]?.jsonPrimitive?.contentOrNull?.replace("large", "t500x500")
        val user = this["user"]?.jsonObject ?: return null
        val userId = user["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
        val username = user["username"]?.jsonPrimitive?.contentOrNull ?: ExtensionText.get("providers.unknown_artist_fc0fb2", "Unknown Artist")
        val artist = Artist(
            id = userId,
            name = username,
            cover = user["avatar_url"]?.jsonPrimitive?.contentOrNull?.toImageHolder()
        )
        val transcodings = this["media"]?.jsonObject?.get("transcodings")?.jsonArray
        val isSnip = this["policy"]?.jsonPrimitive?.contentOrNull == "SNIP"

        val streamables = mutableListOf<Streamable>()
        transcodings?.forEach { t ->
            val obj = t.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull
            val format = obj["format"]?.jsonObject
            val protocol = format?.get("protocol")?.jsonPrimitive?.contentOrNull
            val mime = format?.get("mime_type")?.jsonPrimitive?.contentOrNull
            val preset = obj["preset"]?.jsonPrimitive?.contentOrNull
            val qualityStr = obj["quality"]?.jsonPrimitive?.contentOrNull

            if (!url.isNullOrEmpty()) {
                val codec = when {
                    mime?.contains("opus") == true || preset?.contains("opus") == true -> "Opus"
                    mime?.contains("mp4") == true || mime?.contains("aac") == true || preset?.contains("aac") == true -> "AAC"
                    mime?.contains("mpeg") == true || preset?.contains("mp3") == true -> "MP3"
                    else -> "Audio"
                }
                val bitrate = when {
                    qualityStr == "hq" -> "256 kbps"
                    codec == "Opus" -> "160 kbps"
                    codec == "AAC" -> "160 kbps"
                    else -> "128 kbps"
                }
                val streamType = if (protocol == "progressive") "Progressive" else "HLS"
                val title = "$codec $bitrate ($streamType)"
                val qualityInt = when {
                    qualityStr == "hq" -> 256
                    codec == "Opus" -> 160
                    codec == "AAC" -> 160
                    protocol == "progressive" -> 128
                    else -> 120
                }
                streamables.add(Streamable.server(id = url, quality = qualityInt, title = title))
            }
        }

        val uniqueStreamables = streamables
            .distinctBy { it.title }
            .sortedByDescending { it.quality }

        return Track(
            id = id, title = title, artists = listOf(artist),
            cover = artwork?.toImageHolder(), duration = duration,
            isLikeable = oauthToken != null,
            extras = if (isSnip) mapOf("snip" to "true") else emptyMap(),
            streamables = uniqueStreamables
        )
    }

    private fun kotlinx.serialization.json.JsonObject.toPlaylist(): Playlist? {
        val id = this["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
        val title = this["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val artwork = this["artwork_url"]?.jsonPrimitive?.contentOrNull?.replace("large", "t500x500")
        val user = this["user"]?.jsonObject
        val artist = user?.let {
            val userId = it["id"]?.jsonPrimitive?.longOrNull?.toString()
            val username = it["username"]?.jsonPrimitive?.contentOrNull
            if (userId != null && username != null) {
                Artist(
                    id = userId,
                    name = username,
                    cover = it["avatar_url"]?.jsonPrimitive?.contentOrNull?.toImageHolder()
                )
            } else null
        }
        return Playlist(
            id = id, title = title, isEditable = false, isPrivate = false,
            cover = artwork?.toImageHolder(),
            authors = if (artist != null) listOf(artist) else emptyList(),
            trackCount = this["track_count"]?.jsonPrimitive?.longOrNull,
            description = this["description"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun kotlinx.serialization.json.JsonObject.toArtist(): Artist? {
        val id = this["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
        val name = this["username"]?.jsonPrimitive?.contentOrNull ?: return null
        return Artist(
            id = id,
            name = name,
            cover = this["avatar_url"]?.jsonPrimitive?.contentOrNull?.replace("large", "t500x500")?.toImageHolder(),
            subtitle = this["followers_count"]?.jsonPrimitive?.longOrNull?.let { if (it > 0) ExtensionText.get("providers.1_d_followers_5d1551", "%1\$d followers", it) else null },
            isFollowable = oauthToken != null
        )
    }
}
