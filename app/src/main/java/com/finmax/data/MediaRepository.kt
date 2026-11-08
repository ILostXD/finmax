package com.finmax.data

import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.moviesApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.RecommendationDto
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import java.util.UUID

/** Library types FINMAX can browse and play today. Music, live TV and books are not supported yet. */
val SupportedViews = setOf(CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.BOXSETS, CollectionType.HOMEVIDEOS)

enum class SortChoice(val label: String, val by: ItemSortBy, val order: SortOrder) {
    NAME("A–Z", ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
    ADDED("Recently added", ItemSortBy.DATE_CREATED, SortOrder.DESCENDING),
    RELEASED("Release date", ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING),
    RATING("Rating", ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING),
    PLAYED("Last watched", ItemSortBy.DATE_PLAYED, SortOrder.DESCENDING),
    RUNTIME("Runtime", ItemSortBy.RUNTIME, SortOrder.ASCENDING),
    RANDOM("Shuffle", ItemSortBy.RANDOM, SortOrder.ASCENDING),
}

enum class FilterChoice(val label: String, val filter: ItemFilter?) {
    ALL("All", null),
    UNWATCHED("Unwatched", ItemFilter.IS_UNPLAYED),
    WATCHED("Watched", ItemFilter.IS_PLAYED),
    FAVORITES("My List", ItemFilter.IS_FAVORITE),
    IN_PROGRESS("In progress", ItemFilter.IS_RESUMABLE),
}

/** All Jellyfin library reads/writes go through here; screens never talk to the SDK directly. */
class MediaRepository(private val session: Session) {
    private val api = session.api
    private val userId = session.userId

    private val cardFields = listOf(
        ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
        ItemFields.CHILD_COUNT, ItemFields.MEDIA_SOURCE_COUNT,
    )
    private val detailFields = cardFields + listOf(
        ItemFields.PEOPLE, ItemFields.STUDIOS, ItemFields.TAGLINES, ItemFields.MEDIA_SOURCES,
        ItemFields.MEDIA_STREAMS, ItemFields.PROVIDER_IDS, ItemFields.PATH, ItemFields.EXTERNAL_URLS,
        ItemFields.REMOTE_TRAILERS,
    )

    suspend fun views(): List<BaseItemDto> =
        api.userViewsApi.getUserViews(userId = userId).content.items.filter { it.collectionType in SupportedViews }

    suspend fun resume(limit: Int = 24): List<BaseItemDto> = api.itemsApi.getResumeItems(
        GetResumeItemsRequest(userId = userId, limit = limit, fields = cardFields, mediaTypes = listOf(MediaType.VIDEO), enableUserData = true),
    ).content.items

    suspend fun nextUp(limit: Int = 24, seriesId: UUID? = null): List<BaseItemDto> = api.tvShowsApi.getNextUp(
        GetNextUpRequest(userId = userId, limit = limit, fields = cardFields, seriesId = seriesId, enableResumable = false, enableUserData = true),
    ).content.items

    suspend fun latest(parentId: UUID, limit: Int = 24): List<BaseItemDto> = api.userLibraryApi.getLatestMedia(
        GetLatestMediaRequest(userId = userId, parentId = parentId, limit = limit, fields = cardFields, groupItems = true),
    ).content

    suspend fun recommendations(): List<RecommendationDto> =
        api.moviesApi.getMovieRecommendations(userId = userId, fields = cardFields, categoryLimit = 3, itemLimit = 16).content

    suspend fun genres(parentId: UUID?): List<BaseItemDto> =
        api.genresApi.getGenres(userId = userId, parentId = parentId, sortBy = listOf(ItemSortBy.SORT_NAME)).content.items

    suspend fun items(
        parentId: UUID? = null,
        types: List<BaseItemKind>? = null,
        sort: SortChoice = SortChoice.NAME,
        filter: FilterChoice = FilterChoice.ALL,
        genre: String? = null,
        search: String? = null,
        start: Int = 0,
        limit: Int = 60,
        recursive: Boolean = true,
    ): BaseItemDtoQueryResult = api.itemsApi.getItems(
        GetItemsRequest(
            userId = userId,
            parentId = parentId,
            includeItemTypes = types,
            recursive = recursive,
            sortBy = listOf(sort.by, ItemSortBy.SORT_NAME),
            sortOrder = listOf(sort.order, SortOrder.ASCENDING),
            filters = listOfNotNull(filter.filter),
            genres = genre?.let { listOf(it) },
            searchTerm = search,
            startIndex = start,
            limit = limit,
            fields = cardFields,
            enableTotalRecordCount = true,
            enableUserData = true,
        ),
    ).content

    suspend fun recentlyWatched(limit: Int = 24) = items(
        types = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE), sort = SortChoice.PLAYED, filter = FilterChoice.WATCHED, limit = limit,
    ).items

    suspend fun item(id: UUID): BaseItemDto =
        api.itemsApi.getItems(GetItemsRequest(userId = userId, ids = listOf(id), fields = detailFields, enableUserData = true))
            .content.items.firstOrNull() ?: error("Item not found")

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        api.tvShowsApi.getSeasons(seriesId = seriesId, userId = userId, fields = cardFields, enableUserData = true).content.items

    /** Episodes of one season, or of the whole series in airing order when [seasonId] is null. */
    suspend fun episodes(seriesId: UUID, seasonId: UUID? = null): List<BaseItemDto> =
        api.tvShowsApi.getEpisodes(
            seriesId = seriesId, userId = userId, seasonId = seasonId,
            fields = cardFields + ItemFields.MEDIA_SOURCES, enableUserData = true,
        ).content.items

    suspend fun similar(id: UUID): List<BaseItemDto> =
        api.libraryApi.getSimilarItems(itemId = id, userId = userId, limit = 16, fields = cardFields).content.items

    suspend fun collectionItems(id: UUID): List<BaseItemDto> =
        items(parentId = id, sort = SortChoice.RELEASED, recursive = false, limit = 100).items.sortedBy { it.premiereDate }

    suspend fun setFavorite(id: UUID, favorite: Boolean) = if (favorite) {
        api.userLibraryApi.markFavoriteItem(itemId = id, userId = userId).content.isFavorite
    } else {
        api.userLibraryApi.unmarkFavoriteItem(itemId = id, userId = userId).content.isFavorite
    }

    suspend fun setPlayed(id: UUID, played: Boolean) = if (played) {
        api.playStateApi.markPlayedItem(itemId = id, userId = userId).content.played
    } else {
        api.playStateApi.markUnplayedItem(itemId = id, userId = userId).content.played
    }

    suspend fun currentUser() = api.userApi.getCurrentUser().content

    // ---- Images ----------------------------------------------------------------------------

    private fun image(id: UUID, type: ImageType, tag: String?, width: Int, index: Int? = null): String? =
        tag?.let { api.imageApi.getItemImageUrl(itemId = id, imageType = type, tag = it, maxWidth = width, quality = 90, imageIndex = index) }

    fun backdrop(item: BaseItemDto, width: Int = 1920): String? =
        image(item.id, ImageType.BACKDROP, item.backdropImageTags?.firstOrNull(), width, 0)
            ?: item.parentBackdropItemId?.let { image(it, ImageType.BACKDROP, item.parentBackdropImageTags?.firstOrNull(), width, 0) }
            ?: image(item.id, ImageType.THUMB, item.imageTags?.get(ImageType.THUMB), width)

    /** Landscape art for cards: episode stills, then thumbs, then backdrops. */
    fun landscape(item: BaseItemDto, width: Int = 640): String? = when (item.type) {
        BaseItemKind.EPISODE -> image(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), width)
            ?: item.parentThumbItemId?.let { image(it, ImageType.THUMB, item.parentThumbImageTag, width) }
            ?: backdrop(item, width)
        else -> image(item.id, ImageType.THUMB, item.imageTags?.get(ImageType.THUMB), width)
            ?: backdrop(item, width)
            ?: image(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), width)
    }

    fun poster(item: BaseItemDto, width: Int = 400): String? =
        if (item.type == BaseItemKind.EPISODE && item.seriesId != null) {
            image(item.seriesId!!, ImageType.PRIMARY, item.seriesPrimaryImageTag, width)
        } else {
            image(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), width)
        }

    fun logo(item: BaseItemDto, width: Int = 800): String? =
        image(item.id, ImageType.LOGO, item.imageTags?.get(ImageType.LOGO), width)
            ?: item.parentLogoItemId?.let { image(it, ImageType.LOGO, item.parentLogoImageTag, width) }

    fun personImage(id: UUID, tag: String?) = image(id, ImageType.PRIMARY, tag, 300)

    fun userImage(): String? = session.user.imageTag?.let {
        "${session.server.url.trimEnd('/')}/Users/${session.user.userId}/Images/Primary?tag=$it&maxWidth=120"
    }
}
