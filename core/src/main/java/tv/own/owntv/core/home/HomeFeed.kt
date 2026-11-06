package tv.own.owntv.core.home

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ChannelWithWatchedAt
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.launcher.LauncherContinuationItem
import tv.own.owntv.core.launcher.LauncherContinuationKind
import tv.own.owntv.core.launcher.LauncherRecommendationPlanner
import tv.own.owntv.core.launcher.LauncherWatchNextType
import tv.own.owntv.core.live.GuideReader
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher

/** The item behind the big card at the top of Home — the last thing worth carrying on with. */
sealed interface HeroItem {
    val streamUrl: String
    val seekToMs: Long
    val positionMs: Long
    val durationMs: Long
    val watchNextType: LauncherWatchNextType
    val lastEngagementAt: Long

    /** Playlist this item came from — used to give the preview the same source-wide User-Agent the
     *  player uses. */
    val sourceId: Long

    /** This item's own `Key: Value` request headers (M3U), or null. Same reason as [sourceId]. */
    val httpHeaders: String?

    data class MovieHero(
        val movie: MovieEntity,
        val item: LauncherContinuationItem,
    ) : HeroItem {
        override val sourceId: Long = movie.sourceId
        override val httpHeaders: String? = movie.httpHeaders
        override val streamUrl: String = movie.streamUrl
        override val seekToMs: Long = (item.positionMs - HERO_REWIND_MS).coerceAtLeast(0L)
        override val positionMs: Long = item.positionMs
        override val durationMs: Long = item.durationMs
        override val watchNextType: LauncherWatchNextType = item.watchNextType
        override val lastEngagementAt: Long = item.lastEngagementAt
    }

    data class SeriesHero(
        val series: SeriesEntity,
        val episode: EpisodeEntity,
        val item: LauncherContinuationItem,
    ) : HeroItem {
        override val sourceId: Long = series.sourceId // episodes hang off the series, which owns the source
        override val httpHeaders: String? = episode.httpHeaders
        override val streamUrl: String = episode.streamUrl
        override val seekToMs: Long = if (item.watchNextType == LauncherWatchNextType.NEXT) {
            0L
        } else {
            (item.positionMs - HERO_REWIND_MS).coerceAtLeast(0L)
        }
        override val positionMs: Long = item.positionMs
        override val durationMs: Long = item.durationMs
        override val watchNextType: LauncherWatchNextType = item.watchNextType
        override val lastEngagementAt: Long = item.lastEngagementAt
    }

    data class LiveHero(
        val channel: ChannelEntity,
        val watchedAt: Long,
    ) : HeroItem {
        override val sourceId: Long = channel.sourceId
        override val httpHeaders: String? = channel.httpHeaders
        override val streamUrl: String = channel.streamUrl
        override val seekToMs: Long = 0L
        override val positionMs: Long = 0L
        override val durationMs: Long = 0L
        override val watchNextType: LauncherWatchNextType = LauncherWatchNextType.CONTINUE
        override val lastEngagementAt: Long = watchedAt
    }
}

/** Identity of a hero item across reloads — what a metadata or artwork cache is keyed by. */
val HeroItem.homeKey: String
    get() = when (this) {
        is HeroItem.MovieHero -> "movie:${movie.id}"
        is HeroItem.SeriesHero -> "episode:${episode.id}"
        is HeroItem.LiveHero -> "live:${channel.id}"
    }

/** A trending title from TMDB that was matched to something the user's own playlist actually has. */
@Immutable
sealed interface TrendingHomeItem {
    val snapshot: TrendingItemEntity
    val stableKey: String get() = "${snapshot.mediaType.name}:${snapshot.tmdbId}"

    data class Movie(
        override val snapshot: TrendingItemEntity,
        val movie: MovieEntity,
    ) : TrendingHomeItem

    data class Series(
        override val snapshot: TrendingItemEntity,
        val series: SeriesEntity,
    ) : TrendingHomeItem
}

/** The guide behind a live rail in "On now" mode: the channels, and their programmes in the window. */
@Immutable
data class GuideSliceState(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: Map<Long, List<EpgProgrammeEntity>> = emptyMap(),
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L,
    val now: Long = 0L,
) {
    val hasContent: Boolean
        get() = channels.isNotEmpty()
}

/** Everything Home shows, for one profile, at one moment. */
@Immutable
data class HomeFeed(
    val trendingItems: List<TrendingHomeItem> = emptyList(),
    val trendingPreferredLanguage: String = "EN",
    val trendingSeasonCounts: Map<Long, Int> = emptyMap(),
    val heroItems: List<HeroItem> = emptyList(),
    val continueMovies: List<LauncherContinuationItem> = emptyList(),
    val continueSeries: List<LauncherContinuationItem> = emptyList(),
    val recentLive: List<ChannelEntity> = emptyList(),
    val favoriteLive: List<ChannelEntity> = emptyList(),
    val favoriteMovies: List<MovieEntity> = emptyList(),
    val favoriteSeries: List<SeriesEntity> = emptyList(),
    val config: HomeConfig = HomeConfig(),
    val recentGuide: GuideSliceState = GuideSliceState(),
    val favoriteGuide: GuideSliceState = GuideSliceState(),
)

/**
 * Building Home's rails.
 *
 * This is fifteen dependent reads over history, progress, both catalogues, the channel list, the
 * customizations and the guide, and the *rules* between them are the app's, not any one screen's:
 * which playlists count, what a kids profile may not see, what the user hid, how trending titles are
 * deduplicated across playlists, and which items are eligible to be the hero. A television and a
 * phone lay Home out completely differently and must still agree, item for item, on what is in it.
 *
 * The independent reads are started together rather than one after another. WAL lets SQLite serve
 * concurrent readers, so that is real overlap: the longest of them, `buildContinuationItems`
 * (150–400 ms on a TCL), finishes while the source-id, config and favourites reads happen.
 */
class HomeFeedReader(
    private val planner: LauncherRecommendationPlanner,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val customize: CustomizationStore,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val trendingDao: TrendingDao,
    private val guide: GuideReader,
) {
    suspend fun load(profileId: Long): HomeFeed = withContext(Dispatchers.IO) {
        coroutineScope {
            val configAsync = async { settings.homeConfig(profileId).first() }
            val metadataAsync = async { settings.metadataConfig() }
            // Active-playlist filter + per-section enabledScope: Home rails never show Off sections.
            val liveIdsAsync = async { activeSourceIds(settings, sourceDao, profileId, MediaType.LIVE).toSet() }
            val movieIdsAsync = async { activeSourceIds(settings, sourceDao, profileId, MediaType.MOVIE).toSet() }
            val seriesIdsAsync = async { activeSourceIds(settings, sourceDao, profileId, MediaType.SERIES).toSet() }
            val allIdsAsync = async { sourceDao.sourceIdsForProfile(profileId).toSet() }
            val continuationAsync = async { planner.buildContinuationItems(profileId) }
            // Aura: favourite channels in the user's own Favorites order (same as the Live TV Favorites folder),
            // not alphabetical, so Home shows them the way the user arranged them.
            val favouritesAsync = async {
                val ids = liveIdsAsync.await()
                if (ids.isEmpty()) emptyList()
                else channelDao.snapshotFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, ids.toList(), FAVORITE_LIVE_ROW_LIMIT)
            }

            val config = configAsync.await()
            val metadataConfig = metadataAsync.await()
            val preferredLanguage = metadataConfig.resolvedLanguage.substringBefore('-').ifBlank { "en" }.uppercase()
            val liveIds = liveIdsAsync.await()
            val movieIds = movieIdsAsync.await()
            val seriesIds = seriesIdsAsync.await()

            // Hidden items / hidden categories (per profile) never surface on Home either.
            val hidden = hiddenState(profileId, allIdsAsync.await().toList())
            // Trending and the recent-live query are independent of each other too.
            val recentLiveAsync = async {
                channelDao.recentlyWatchedWithTimestampFiltered(profileId, liveIds.toList(), RECENT_LIVE_ROW_LIMIT).first()
            }
            val trending = buildTrendingItems(
                movieSourceIds = movieIds,
                seriesSourceIds = seriesIds,
                hidden = hidden,
                sourceOrder = (movieIds + seriesIds).distinct().toList(),
                metadataEnabled = metadataConfig.enabled,
                preferredLanguage = preferredLanguage,
            )
            val trendingSeriesIds = trending.filterIsInstance<TrendingHomeItem.Series>().map { it.series.id }
            val trendingSeasonCounts = if (trendingSeriesIds.isEmpty()) emptyMap() else {
                seriesDao.storedSeasonCounts(trendingSeriesIds).associate { it.seriesId to it.seasonCount }
            }

            val items = continuationAsync.await()
                .filter { item ->
                    val sid = continuationSourceId(item) ?: return@filter false
                    when (item.kind) {
                        LauncherContinuationKind.MOVIE -> sid in movieIds
                        LauncherContinuationKind.EPISODE -> sid in seriesIds
                        LauncherContinuationKind.LIVE -> sid in liveIds
                    }
                }
                .filterNot { isContinuationHidden(it, hidden) }
            val movies = items.filter { it.kind == LauncherContinuationKind.MOVIE }
            val series = items.filter { it.kind == LauncherContinuationKind.EPISODE }
            val liveWithTs = recentLiveAsync.await().filterNot { isChannelHidden(it.channel, hidden) }
            val live = liveWithTs.map { it.channel }
            val favLive = favouritesAsync.await()
                .filter { c -> c.sourceId in liveIds }
                .filterNot { isChannelHidden(it, hidden) }
            // Favourites in the user's own Favorites order, so Home and the Favorites folder agree.
            val favMoviesAsync = async {
                if (HomeRow.FAVORITE_MOVIES !in config.visibleOrder || movieIds.isEmpty()) emptyList()
                else movieDao.snapshotFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, movieIds.toList(), FAVORITE_VOD_ROW_LIMIT)
                    .filterNot { CustomizeKeys.movie(it) in hidden.movie.hiddenItems || (it.categoryId != null && it.categoryId in hidden.movieCats) }
            }
            val favSeriesAsync = async {
                if (HomeRow.FAVORITE_SERIES !in config.visibleOrder || seriesIds.isEmpty()) emptyList()
                else seriesDao.snapshotFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, seriesIds.toList(), FAVORITE_VOD_ROW_LIMIT)
                    .filterNot { CustomizeKeys.series(it) in hidden.series.hiddenItems || (it.categoryId != null && it.categoryId in hidden.seriesCats) }
            }
            val heroItems = buildHeroItems(items, liveWithTs, config)
            // The two guide slices read different channel sets and never depend on each other.
            val recentGuideAsync = async {
                if (HomeRow.RECENT_CHANNELS in config.visibleOrder && config.recentLiveMode == HomeLiveRowMode.ON_NOW) {
                    liveGuide(profileId, liveIds, live)
                } else {
                    GuideSliceState()
                }
            }
            val favoriteGuideAsync = async {
                if (HomeRow.FAVORITE_CHANNELS in config.visibleOrder && config.favoriteLiveMode == HomeLiveRowMode.ON_NOW) {
                    liveGuide(profileId, liveIds, favLive)
                } else {
                    GuideSliceState()
                }
            }

            // Customize renames go on last: every key and guide lookup above ran on the provider's name,
            // which is what a channel without a provider id is keyed by.
            val names = Names(hidden.live, hidden.movie, hidden.series)
            HomeFeed(
                trendingItems = trending,
                trendingPreferredLanguage = preferredLanguage,
                trendingSeasonCounts = trendingSeasonCounts,
                heroItems = heroItems.map { names.of(it) },
                continueMovies = movies.map { names.ofContinuation(it) },
                continueSeries = series.map { names.ofContinuation(it) },
                recentLive = live.map { names.of(it) },
                favoriteLive = favLive.map { names.of(it) },
                favoriteMovies = favMoviesAsync.await().map { names.of(it) },
                favoriteSeries = favSeriesAsync.await().map { names.of(it) },
                config = config,
                recentGuide = recentGuideAsync.await().let { it.copy(channels = it.channels.map { ch -> names.of(ch) }) },
                favoriteGuide = favoriteGuideAsync.await().let { it.copy(channels = it.channels.map { ch -> names.of(ch) }) },
            )
        }
    }

    /** This profile's hide customizations across all three sections, with category keys resolved to ids. */
    private data class HiddenState(
        val live: SectionCustomizations,
        val movie: SectionCustomizations,
        val series: SectionCustomizations,
        val liveCats: Set<Long>,
        val movieCats: Set<Long>,
        val seriesCats: Set<Long>,
    ) {
        val isEmpty: Boolean
            get() = live.hiddenItems.isEmpty() && movie.hiddenItems.isEmpty() && series.hiddenItems.isEmpty() &&
                liveCats.isEmpty() && movieCats.isEmpty() && seriesCats.isEmpty()
    }

    private suspend fun hiddenState(profileId: Long, sourceIds: List<Long>): HiddenState {
        val live = customize.observeVisible(profileId, MediaType.LIVE).first()
        val movie = customize.observeVisible(profileId, MediaType.MOVIE).first()
        val series = customize.observeVisible(profileId, MediaType.SERIES).first()
        val isKidsProfile = profileDao.getById(profileId)?.isKids == true
        suspend fun catIds(type: MediaType, hiddenKeys: Set<String>): Set<Long> {
            if ((hiddenKeys.isEmpty() && !isKidsProfile) || sourceIds.isEmpty()) return emptySet()
            return AdultCategoryClassifier.hiddenCategoryIds(
                categoryDao.observe(sourceIds, type).first(),
                hiddenKeys,
                isKidsProfile,
            )
        }
        return HiddenState(
            live = live, movie = movie, series = series,
            liveCats = catIds(MediaType.LIVE, live.hiddenCategories),
            movieCats = catIds(MediaType.MOVIE, movie.hiddenCategories),
            seriesCats = catIds(MediaType.SERIES, series.hiddenCategories),
        )
    }

    /** The names the user gave items in Customize, put onto what Home draws. */
    private inner class Names(
        val live: SectionCustomizations,
        val movie: SectionCustomizations,
        val series: SectionCustomizations,
    ) {
        fun of(ch: ChannelEntity) = ch.copy(name = live.displayName(ch))
        fun of(m: MovieEntity) = m.copy(name = movie.displayName(m))
        fun of(s: SeriesEntity) = s.copy(name = series.displayName(s))

        fun of(hero: HeroItem): HeroItem = when (hero) {
            is HeroItem.MovieHero -> hero.copy(movie = of(hero.movie))
            is HeroItem.SeriesHero -> hero.copy(series = of(hero.series))
            is HeroItem.LiveHero -> hero.copy(channel = of(hero.channel))
        }

        /** A film's title, or a show's — and the episode title too where it fell back to the show's. */
        suspend fun ofContinuation(item: LauncherContinuationItem): LauncherContinuationItem = when (item.kind) {
            LauncherContinuationKind.MOVIE -> if (movie.itemNames.isEmpty()) item else
                movieDao.getById(item.sourceItemId)?.let { item.copy(title = movie.displayName(it)) } ?: item
            LauncherContinuationKind.EPISODE -> if (series.itemNames.isEmpty()) item else
                seriesDao.getEpisodeById(item.targetItemId)?.let { seriesDao.getSeriesById(it.seriesId) }?.let { s ->
                    val shown = series.displayName(s)
                    item.copy(containerTitle = shown, title = if (item.title == s.name) shown else item.title)
                } ?: item
            LauncherContinuationKind.LIVE -> item
        }
    }

    private fun isChannelHidden(ch: ChannelEntity, h: HiddenState): Boolean =
        CustomizeKeys.channel(ch) in h.live.hiddenItems || (ch.categoryId != null && ch.categoryId in h.liveCats)

    private suspend fun buildTrendingItems(
        movieSourceIds: Set<Long>,
        seriesSourceIds: Set<Long>,
        hidden: HiddenState,
        sourceOrder: List<Long>,
        metadataEnabled: Boolean,
        preferredLanguage: String,
    ): List<TrendingHomeItem> {
        if (!metadataEnabled) return emptyList()
        val sourceIds = (movieSourceIds + seriesSourceIds).toList()
        if (sourceIds.isEmpty()) return emptyList()
        val snapshots = trendingDao.getItemsForSources(sourceIds)
        if (snapshots.isEmpty()) return emptyList()

        val movieRows = movieDao.getByIds(snapshots.filter { it.mediaType == MediaType.MOVIE }.map { it.providerItemId })
            .associateBy { it.id }
        val seriesRows = seriesDao.getSeriesByIds(snapshots.filter { it.mediaType == MediaType.SERIES }.map { it.providerItemId })
            .associateBy { it.id }
        val resolved = snapshots.mapNotNull { snapshot ->
            when (snapshot.mediaType) {
                MediaType.MOVIE -> movieRows[snapshot.providerItemId]
                    ?.takeIf { it.sourceId in movieSourceIds }
                    ?.takeUnless { CustomizeKeys.movie(it) in hidden.movie.hiddenItems || (it.categoryId != null && it.categoryId in hidden.movieCats) }
                    ?.let { TrendingHomeItem.Movie(snapshot, it) }
                MediaType.SERIES -> seriesRows[snapshot.providerItemId]
                    ?.takeIf { it.sourceId in seriesSourceIds }
                    ?.takeUnless { CustomizeKeys.series(it) in hidden.series.hiddenItems || (it.categoryId != null && it.categoryId in hidden.seriesCats) }
                    ?.let { TrendingHomeItem.Series(snapshot, it) }
                else -> null
            }
        }
        val sourceRanks = sourceOrder.withIndex().associate { it.value to it.index }
        val deduplicated = resolved
            .groupBy { it.snapshot.mediaType to it.snapshot.tmdbId }
            .values
            .map { variants ->
                variants.minWith(
                    compareBy<TrendingHomeItem> { homeLanguageRank(it.snapshot.providerLanguage, preferredLanguage) }
                        .thenByDescending { homeQualityRank(it.snapshot.advertisedQuality) }
                        .thenBy { sourceRanks[it.snapshot.sourceId] ?: Int.MAX_VALUE }
                        .thenBy { it.snapshot.position },
                )
            }
        val movies = deduplicated.filter { it.snapshot.mediaType == MediaType.MOVIE }
            .sortedBy { it.snapshot.trendingRank }
            .take(TrendingMatcher.MAX_PER_MEDIA_TYPE)
        val series = deduplicated.filter { it.snapshot.mediaType == MediaType.SERIES }
            .sortedBy { it.snapshot.trendingRank }
            .take(TrendingMatcher.MAX_PER_MEDIA_TYPE)
        val seriesTarget = if (movies.size >= 5) 5 else TrendingMatcher.MAX_TOTAL - movies.size
        val selectedSeries = series.take(seriesTarget)
        val selectedMovies = movies.take(TrendingMatcher.MAX_TOTAL - selectedSeries.size)
        val interleaved = buildList {
            for (index in 0 until maxOf(selectedMovies.size, selectedSeries.size)) {
                selectedMovies.getOrNull(index)?.let(::add)
                selectedSeries.getOrNull(index)?.let(::add)
            }
        }.take(TrendingMatcher.MAX_TOTAL)
        return interleaved.takeIf { it.size >= TrendingDao.MIN_ELIGIBLE_ITEMS }.orEmpty()
    }

    private fun homeLanguageRank(language: String?, preferred: String): Int = when {
        language == preferred -> 0
        language == "EN" -> 1
        language == null -> 2
        else -> 3
    }

    private fun homeQualityRank(quality: String?): Int = when (quality) {
        "8K" -> 5
        "4K", "4K UHD" -> 4
        "FHD", "1080p FHD" -> 3
        "HD", "720p HD" -> 2
        "SD" -> 1
        else -> 0
    }

    private suspend fun isContinuationHidden(item: LauncherContinuationItem, h: HiddenState): Boolean {
        if (h.isEmpty) return false
        return when (item.kind) {
            LauncherContinuationKind.MOVIE -> movieDao.getById(item.sourceItemId)?.let {
                CustomizeKeys.movie(it) in h.movie.hiddenItems || (it.categoryId != null && it.categoryId in h.movieCats)
            } ?: false
            LauncherContinuationKind.EPISODE -> seriesDao.getEpisodeById(item.targetItemId)?.let { ep ->
                seriesDao.getSeriesById(ep.seriesId)?.let { s ->
                    CustomizeKeys.series(s) in h.series.hiddenItems || (s.categoryId != null && s.categoryId in h.seriesCats)
                }
            } ?: false
            LauncherContinuationKind.LIVE -> channelDao.getById(item.sourceItemId)?.let { isChannelHidden(it, h) } ?: false
        }
    }

    /** The playlist a continuation item belongs to, for the active-playlist filter (null if it's gone). */
    private suspend fun continuationSourceId(item: LauncherContinuationItem): Long? = when (item.kind) {
        LauncherContinuationKind.MOVIE -> movieDao.getById(item.sourceItemId)?.sourceId
        LauncherContinuationKind.EPISODE ->
            seriesDao.getEpisodeById(item.targetItemId)?.let { seriesDao.getSeriesById(it.seriesId)?.sourceId }
        LauncherContinuationKind.LIVE -> channelDao.getById(item.sourceItemId)?.sourceId
    }

    private suspend fun buildHeroItems(
        continuationItems: List<LauncherContinuationItem>,
        liveChannels: List<ChannelWithWatchedAt>,
        config: HomeConfig,
    ): List<HeroItem> {
        data class Candidate(val engagedAt: Long, val resolve: suspend () -> HeroItem?)

        val candidates = mutableListOf<Candidate>()

        continuationItems.forEach { item ->
            when (item.kind) {
                LauncherContinuationKind.MOVIE -> if (config.heroIncludeMovies) {
                    candidates += Candidate(item.lastEngagementAt) {
                        val movie = movieDao.getById(item.sourceItemId) ?: return@Candidate null
                        HeroItem.MovieHero(movie, item)
                    }
                }
                LauncherContinuationKind.EPISODE -> if (config.heroIncludeSeries) {
                    candidates += Candidate(item.lastEngagementAt) {
                        val episode = seriesDao.getEpisodeById(item.targetItemId) ?: return@Candidate null
                        val series = seriesDao.getSeriesById(episode.seriesId) ?: return@Candidate null
                        HeroItem.SeriesHero(series, episode, item)
                    }
                }
                LauncherContinuationKind.LIVE -> Unit
            }
        }

        if (config.heroIncludeLive) {
            liveChannels.forEach { watched ->
                candidates += Candidate(watched.watchedAt) {
                    HeroItem.LiveHero(watched.channel, watched.watchedAt)
                }
            }
        }

        val result = mutableListOf<HeroItem>()
        for (candidate in candidates.sortedByDescending { it.engagedAt }) {
            if (result.size >= MAX_HERO_ITEMS) break
            candidate.resolve()?.let { result += it }
        }
        return result
    }

    /** A live rail in "On now" mode: six hours of guide for the channels it is about to draw. */
    private suspend fun liveGuide(
        profileId: Long,
        activeIds: Set<Long>,
        channels: List<ChannelEntity>,
    ): GuideSliceState {
        val now = System.currentTimeMillis()
        val windowStart = now / HALF_HOUR_MS * HALF_HOUR_MS
        val windowEnd = windowStart + SLICE_WINDOW_MS
        if (channels.isEmpty()) return GuideSliceState(now = now, windowStart = windowStart, windowEnd = windowEnd)

        return GuideSliceState(
            channels = channels,
            programmes = guide.slice(
                channels = channels,
                cust = customize.observe(profileId, MediaType.LIVE).first(),
                globalShiftMinutes = settings.epgOffsetMinutes.first(),
                from = windowStart,
                to = windowEnd,
            ),
            windowStart = windowStart,
            windowEnd = windowEnd,
            now = now,
        )
    }
}

/** How far a resumed item is rewound, so it starts just before where the user stopped. */
private const val HERO_REWIND_MS = 10_000L

private const val MAX_HERO_ITEMS = 10
private const val SLICE_WINDOW_MS = 360 * 60_000L
private const val HALF_HOUR_MS = 30 * 60_000L
private const val RECENT_LIVE_ROW_LIMIT = 20
private const val FAVORITE_VOD_ROW_LIMIT = 50
private const val FAVORITE_LIVE_ROW_LIMIT = 200
