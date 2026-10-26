package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import kotlin.time.Duration.Companion.seconds

abstract class SearchViewModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
    private val preferences: SourcePreferences,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow<State>(initialState)

    // Subclasses can't touch the backing field (Kotlin forbids a visibility modifier on one),
    // so state writes from them go through here.
    protected fun updateState(function: (State) -> State) {
        state.update(function)
    }

    private val coroutineDispatcher = Dispatchers.IO.limitedParallelism(5)
    private var searchJob: Job? = null
    private var chapterCountsJob: Job? = null
    private var detailsJob: Job? = null
    private val detailsTried = mutableSetOf<Long>()

    private val enabledLanguages = sourcePreferences.enabledLanguages.get()
    private val disabledSources = sourcePreferences.disabledSources.get()
    protected val pinnedSources = sourcePreferences.pinnedSources.get()

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null

    protected var extensionFilter: String? = null

    open val sortComparator = { map: Map<Source, SearchItemResult> ->
        compareBy<Source>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        viewModelScope.launch {
            preferences.globalSearchFilterState.changes().collectLatest { onlyShowHasResults ->
                state.update { it.copy(onlyShowHasResults = onlyShowHasResults) }
            }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    open suspend fun getEnabledSources(): List<Source> {
        return sourceManager.getAll()
            .filter { (it.lang in enabledLanguages || it.isLocal()) && "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private suspend fun getSelectedSources(): List<Source> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        return extensionManager.getLoadedExtensions()
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filter { it in enabledSources }
    }

    fun updateSearchQuery(query: String?) {
        state.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: SourceFilter) {
        state.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState.toggle()
    }

    fun toggleStatusFilter(status: Long) {
        state.update { it.copy(filters = it.filters.copy(statuses = it.filters.statuses.toggled(status))) }
        onFiltersChanged()
    }

    fun toggleTagFilter(tag: String) {
        state.update { it.copy(filters = it.filters.copy(tags = it.filters.tags.toggled(tag.lowercase()))) }
        onFiltersChanged()
    }

    fun clearFilters() {
        state.update { it.copy(filters = SearchFilters()) }
        onFiltersChanged()
    }

    /**
     * Without a word to search, the filters alone list what the sources have (their popular titles) and
     * keep only the ones that match; with no filters either there is nothing to show.
     */
    private fun onFiltersChanged() {
        if (state.value.searchQuery.isNullOrBlank()) {
            if (state.value.filters.isActive) {
                search()
            } else {
                searchJob?.cancel()
                detailsJob?.cancel()
                lastQuery = null
                state.update { it.copy(items = emptyMap(), detailsProgress = null) }
            }
        }
        if (state.value.filters.isActive) loadFilterDetails()
    }

    /**
     * Search listings rarely carry the status or the tags, so when the filter is opened the titles that lack
     * them get their details fetched (one request per title, not stored), which is what makes filtering by
     * them useful.
     */
    fun loadFilterDetails() {
        if (detailsJob?.isActive == true) return

        detailsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = state.value.unifiedGroupsUnfiltered
                    .filter { group -> group.entries.none { state.value.hasDetails(it.manga) } }
                    .map { it.entries.first() }
                    .filter { it.manga.id !in detailsTried }
                    .take(MAX_DETAIL_REQUESTS)
                if (pending.isEmpty()) break

                detailsTried += pending.map { it.manga.id }
                state.update { it.copy(detailsProgress = 0 to pending.size) }
                pending.map { entry ->
                    async {
                        try {
                            val manga = withTimeout(CHAPTER_COUNT_TIMEOUT) {
                                withContext(coroutineDispatcher) {
                                    entry.source.getMangaUpdate(
                                        manga = entry.manga.toSManga(),
                                        chapters = emptyList(),
                                        fetchDetails = true,
                                        fetchChapters = false,
                                    )
                                }
                            }.manga
                            val details = MangaDetails(
                                status = manga.status.toLong(),
                                genres = manga.getGenres().orEmpty(),
                            )
                            state.update { it.copy(details = it.details + (entry.manga.id to details)) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Left without details, so a filter won't match it
                        }
                        state.update {
                            it.copy(detailsProgress = it.detailsProgress?.let { (done, total) -> done + 1 to total })
                        }
                    }
                }
                    .awaitAll()
            }
            state.update { it.copy(detailsProgress = null) }
        }
    }

    /**
     * Opens the chooser for a title found in several sources, counting the chapters each one has so the
     * fullest one can be picked.
     */
    fun openUnifiedGroup(group: UnifiedGroup) {
        chapterCountsJob?.cancel()
        state.update {
            it.copy(
                unifiedDialog = group,
                chapterCounts = group.entries.associate { entry -> entry.manga.id to ChapterCount.Loading },
            )
        }
        chapterCountsJob = viewModelScope.launch(Dispatchers.IO) {
            group.entries.map { entry ->
                async {
                    val count = try {
                        withTimeout(CHAPTER_COUNT_TIMEOUT) {
                            withContext(coroutineDispatcher) {
                                entry.source.getMangaUpdate(
                                    manga = entry.manga.toSManga(),
                                    chapters = emptyList(),
                                    fetchDetails = false,
                                    fetchChapters = true,
                                )
                            }
                        }.chapters.size.let { ChapterCount.Count(it) }
                    } catch (e: TimeoutCancellationException) {
                        ChapterCount.Failed
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ChapterCount.Failed
                    }
                    state.update { it.copy(chapterCounts = it.chapterCounts + (entry.manga.id to count)) }
                }
            }
                .awaitAll()
        }
    }

    fun closeUnifiedGroup() {
        chapterCountsJob?.cancel()
        state.update { it.copy(unifiedDialog = null, chapterCounts = emptyMap()) }
    }

    fun search() {
        val query = state.value.searchQuery.orEmpty()
        val sourceFilter = state.value.sourceFilter

        if (query.isBlank() && !state.value.filters.isActive) return

        val sameQuery = this.lastQuery == query
        if (sameQuery && this.lastSourceFilter == sourceFilter) return

        this.lastQuery = query
        this.lastSourceFilter = sourceFilter

        searchJob?.cancel()
        detailsJob?.cancel()
        state.update { it.copy(detailsProgress = null) }

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val sources = getSelectedSources()

            // Reuse previous results if possible
            if (sameQuery) {
                val existingResults = state.value.items
                updateItems(
                    sources
                        .associateWith { existingResults[it] ?: SearchItemResult.Loading },
                )
            } else {
                updateItems(
                    sources
                        .associateWith { SearchItemResult.Loading },
                )
            }

            sources.map { source ->
                async {
                    if (state.value.items[source] !is SearchItemResult.Loading) {
                        return@async
                    }

                    try {
                        val page = withContext(coroutineDispatcher) {
                            if (query.isBlank()) {
                                source.getPopularManga(1)
                            } else {
                                source.getSearchManga(1, query, source.getFilterList())
                            }
                        }

                        val titles = page.mangas
                            .map { it.toDomainManga(source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(it) }

                        if (isActive) {
                            updateItem(source, SearchItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }
                .awaitAll()

            if (state.value.filters.isActive) loadFilterDetails()
        }
    }

    private fun updateItems(items: Map<Source, SearchItemResult>) {
        state.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items)),
            )
        }
    }

    private fun updateItem(source: Source, result: SearchItemResult) {
        state.update { currentState ->
            val newItems = currentState.items + (source to result)
            currentState.copy(
                items = newItems.toSortedMap(sortComparator(newItems)),
            )
        }
    }

    fun setMigrateDialog(currentId: Long, target: Manga) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = getManga.await(currentId) ?: return@launch
            state.update { it.copy(dialog = Dialog.Migrate(target, current)) }
        }
    }

    fun clearDialog() {
        state.update { it.copy(dialog = null) }
    }

    @Immutable
    data class State(
        val from: Manga? = null,
        val searchQuery: String? = null,
        val sourceFilter: SourceFilter = SourceFilter.All,
        val onlyShowHasResults: Boolean = false,
        val items: Map<Source, SearchItemResult> = mapOf(),
        val dialog: Dialog? = null,
        val filters: SearchFilters = SearchFilters(),
        val details: Map<Long, MangaDetails> = emptyMap(),
        val detailsProgress: Pair<Int, Int>? = null,
        val unifiedDialog: UnifiedGroup? = null,
        val chapterCounts: Map<Long, ChapterCount> = emptyMap(),
    ) {
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
        val unifiedGroupsUnfiltered: List<UnifiedGroup> = buildUnifiedGroups(items)
        val unifiedGroups: List<UnifiedGroup> = unifiedGroupsUnfiltered.filtered()

        // Tags of the titles being shown, the most common first, with how many titles each one would leave
        val availableTags: List<Pair<String, Int>> by lazy {
            val seen = unifiedGroups
                .flatMap { group -> group.entries.flatMap { genresOf(it.manga) }.distinctBy { it.lowercase() } }
                .groupBy { it.lowercase() }
                .map { (_, names) -> names.first() to names.size }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            if (filters.isActive) {
                seen
            } else {
                // The usual ones are offered from the start, before there is anything searched
                val known = seen.map { it.first.lowercase() }.toSet()
                seen + COMMON_TAGS.filter { it.lowercase() !in known }.map { it to 0 }
            }
        }

        fun statusOf(manga: Manga): Long = details[manga.id]?.status ?: manga.status

        fun genresOf(manga: Manga): List<String> = details[manga.id]?.genres ?: manga.genre.orEmpty()

        fun hasDetails(manga: Manga): Boolean = manga.id in details || manga.status != 0L || !manga.genre.isNullOrEmpty()

        private fun List<UnifiedGroup>.filtered(): List<UnifiedGroup> {
            if (!filters.isActive) return this
            return mapNotNull { group ->
                val entries = group.entries.filter { entry ->
                    (filters.statuses.isEmpty() || statusOf(entry.manga) in filters.statuses) &&
                        filters.tags.all { tag -> genresOf(entry.manga).any { it.lowercase() == tag } }
                }
                if (entries.isEmpty()) null else group.copy(entries = entries)
            }
        }
    }

    sealed interface Dialog {
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }
}

/**
 * The same title as found in each source.
 */
@Immutable
data class UnifiedGroup(val key: String, val title: String, val entries: List<UnifiedEntry>)

@Immutable
data class UnifiedEntry(val source: Source, val manga: Manga)

sealed interface ChapterCount {
    data object Loading : ChapterCount
    data class Count(val value: Int) : ChapterCount
    data object Failed : ChapterCount
}

/**
 * Titles that only differ in case or punctuation are taken as the same one. Groups found in more sources
 * go first, and the rest keep the order the sources answered in.
 */
private fun buildUnifiedGroups(items: Map<Source, SearchItemResult>): List<UnifiedGroup> {
    return items
        .flatMap { (source, result) ->
            (result as? SearchItemResult.Success)?.result.orEmpty().map { UnifiedEntry(source, it) }
        }
        .groupBy { entry ->
            val title = entry.manga.title.lowercase()
            title.filter { it.isLetterOrDigit() }.ifEmpty { title }
        }
        .map { (key, entries) -> UnifiedGroup(key, entries.first().manga.title, entries) }
        .sortedByDescending { it.entries.size }
}

private val CHAPTER_COUNT_TIMEOUT = 12.seconds

enum class SourceFilter {
    All,
}

/**
 * Narrows the titles found. Any of the chosen statuses may match, but a title needs all the chosen tags.
 * Tags are kept lowercase.
 */
@Immutable
data class SearchFilters(val statuses: Set<Long> = emptySet(), val tags: Set<String> = emptySet()) {
    val count: Int get() = statuses.size + tags.size
    val isActive: Boolean get() = count > 0
}

@Immutable
data class MangaDetails(val status: Long, val genres: List<String>)

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

private const val MAX_DETAIL_REQUESTS = 120

private val COMMON_TAGS = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem", "Historical", "Horror", "Isekai", "Josei",
    "Martial Arts", "Mecha", "Mystery", "Psychological", "Romance", "School Life", "Sci-Fi", "Seinen",
    "Shoujo", "Shounen", "Slice of Life", "Sports", "Supernatural", "Thriller", "Tragedy", "Webtoon",
)

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : SearchItemResult

    data class Success(
        val result: List<Manga>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
