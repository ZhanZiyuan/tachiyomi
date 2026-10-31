package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList

/** Modern Source includes browsing; retain CatalogueSource for the existing reader UI. */
internal fun Source.asCatalogueSource(): Source = when {
    this is CatalogueSource -> this
    this is ConfigurableSource -> ConfigurableCatalogueSource(this)
    else -> ModernCatalogueSource(this)
}

private open class ModernCatalogueSource(private val source: Source) : CatalogueSource, Source by source {
    override val lang get() = source.lang
    override val supportsLatest get() = source.supportsLatest
    override fun getFilterList() = source.getFilterList()
    override suspend fun getPopularManga(page: Int) = source.getPopularManga(page)
    override suspend fun getLatestUpdates(page: Int) = source.getLatestUpdates(page)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
        source.getSearchManga(page, query, filters)
    override fun toString() = source.toString()
}

private class ConfigurableCatalogueSource(private val source: ConfigurableSource) :
    ModernCatalogueSource(source), ConfigurableSource {
    override fun getSourcePreferences() = source.getSourcePreferences()
    override fun setupPreferenceScreen(screen: PreferenceScreen) = source.setupPreferenceScreen(screen)
}
