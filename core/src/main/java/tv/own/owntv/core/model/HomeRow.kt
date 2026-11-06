package tv.own.owntv.core.model

import androidx.compose.runtime.Immutable

import org.json.JSONArray
import org.json.JSONObject

enum class HomeRow {
    TRENDING,

    /** Aura: the Live-TV preview card — the last channel playing in a rounded window, with what is on and a Watch button. */
    LIVE_NOW,
    HERO,

    /** Sports Hub: followed matches, drawn only while one is live or kicks off within the hour. */
    LIVE_SPORTS,
    RECENT_CHANNELS,
    FAVORITE_CHANNELS,
    FAVORITE_MOVIES,
    FAVORITE_SERIES,
    CONTINUE_MOVIES,
    CONTINUE_SERIES;

    val implemented: Boolean get() = true
}

enum class HomeLiveRowMode {
    CARDS,
    ON_NOW;

    fun toggled(): HomeLiveRowMode = when (this) {
        CARDS -> ON_NOW
        ON_NOW -> CARDS
    }
}

/**
 * How the Trending row is drawn: the full card with the artwork, badges and reasons, or the plain
 * strip of posters. [HERO] is the default, so nothing changes for anyone who never opens the setting.
 */
enum class HomeTrendingStyle {
    HERO,
    POSTERS,
}

enum class HeroKind {
    LIVE, MOVIES, SERIES,
}

@Immutable
data class HomeConfig(
    val order: List<HomeRow> = AURA_ORDER,
    val hidden: Set<HomeRow> = setOf(HomeRow.RECENT_CHANNELS),
    // Aura: Continue watching is films and series only; live channels have their own rows.
    val heroIncludeLive: Boolean = false,
    val heroIncludeMovies: Boolean = true,
    val heroIncludeSeries: Boolean = true,
    val recentLiveMode: HomeLiveRowMode = HomeLiveRowMode.CARDS,
    val favoriteLiveMode: HomeLiveRowMode = HomeLiveRowMode.ON_NOW,
    val trendingStyle: HomeTrendingStyle = HomeTrendingStyle.HERO,
) {
    val visibleOrder: List<HomeRow>
        get() = buildList {
            // Trending is a fixed, optional Home feature rather than a reorderable content row.
            // When enabled it always owns the top position, regardless of any previously saved order.
            if (HomeRow.TRENDING !in hidden) add(HomeRow.TRENDING)
            addAll(order.filter { it != HomeRow.TRENDING && it.implemented && it !in hidden })
        }

    val settingsRows: List<HomeRow>
        get() = order.filter { it != HomeRow.TRENDING && it.implemented }

    fun toJson(): JSONObject = JSONObject().apply {
        put("order", JSONArray(order.map { it.name }))
        put("hidden", JSONArray(hidden.map { it.name }))
        put("heroLive", heroIncludeLive)
        put("heroMovies", heroIncludeMovies)
        put("heroSeries", heroIncludeSeries)
        put("recentLiveMode", recentLiveMode.name)
        put("favoriteLiveMode", favoriteLiveMode.name)
        put("trendingStyle", trendingStyle.name)
        put(AURA_LAYOUT_KEY, AURA_LAYOUT_VERSION)
    }

    companion object {
        fun fromJson(raw: String?): HomeConfig {
            if (raw.isNullOrBlank()) return HomeConfig()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return HomeConfig()
            val storedOrder = obj.optJSONArray("order").toHomeRows()
            val hidden = if (obj.has("hidden")) obj.optJSONArray("hidden").toHomeRows().toSet()
                else HomeConfig().hidden
            // Aura: a config saved before the Aura layout existed gets the Aura order and no live channels in
            // Continue watching, once. Anything saved since carries the marker and is left exactly as saved.
            val aura = obj.has(AURA_LAYOUT_KEY)
            return HomeConfig(
                order = if (aura) mergeOrder(storedOrder) else AURA_ORDER,
                hidden = hidden,
                heroIncludeLive = if (aura) readBool(obj, "heroLive", "heroIncludeLive", default = false) else false,
                heroIncludeMovies = readBool(obj, "heroMovies", "heroIncludeMovies", default = true),
                heroIncludeSeries = readBool(obj, "heroSeries", "heroIncludeSeries", default = true),
                recentLiveMode = readLiveMode(obj, "recentLiveMode", HomeLiveRowMode.CARDS),
                favoriteLiveMode = readLiveMode(obj, "favoriteLiveMode", HomeLiveRowMode.ON_NOW),
                // Absent in every config written before the setting existed, and in every backup
                // taken then — those all read as the hero, which is what they were showing.
                trendingStyle = runCatching { HomeTrendingStyle.valueOf(obj.optString("trendingStyle")) }
                    .getOrDefault(HomeTrendingStyle.HERO),
            )
        }

        private fun readBool(obj: JSONObject, primary: String, fallback: String, default: Boolean): Boolean =
            when {
                obj.has(primary) -> obj.optBoolean(primary, default)
                obj.has(fallback) -> obj.optBoolean(fallback, default)
                else -> default
            }

        private fun readLiveMode(obj: JSONObject, key: String, default: HomeLiveRowMode): HomeLiveRowMode =
            runCatching { HomeLiveRowMode.valueOf(obj.optString(key)) }.getOrDefault(default)
    }
}

/** The Aura Start page, top to bottom: Live now, favourites, then what was being watched. */
val AURA_ORDER: List<HomeRow> = listOf(
    HomeRow.TRENDING, HomeRow.LIVE_NOW, HomeRow.FAVORITE_CHANNELS, HomeRow.FAVORITE_MOVIES, HomeRow.FAVORITE_SERIES,
    HomeRow.HERO, HomeRow.CONTINUE_MOVIES, HomeRow.CONTINUE_SERIES, HomeRow.LIVE_SPORTS, HomeRow.RECENT_CHANNELS,
)
private const val AURA_LAYOUT_KEY = "auraLayout"
private const val AURA_LAYOUT_VERSION = 1

fun mergeOrder(stored: List<HomeRow>): List<HomeRow> {
    val result = LinkedHashSet<HomeRow>()
    stored.forEach { result += it }
    HomeRow.entries.forEachIndexed { index, row ->
        if (row !in result) {
            val current = result.toMutableList()
            current.add(index.coerceAtMost(current.size), row)
            result.clear()
            result += current
        }
    }
    return result.toList()
}

private fun JSONArray?.toHomeRows(): List<HomeRow> {
    if (this == null) return emptyList()
    val out = ArrayList<HomeRow>(length())
    for (i in 0 until length()) {
        val row = runCatching { HomeRow.valueOf(optString(i)) }.getOrNull() ?: continue
        if (row !in out) out += row
    }
    return out
}
