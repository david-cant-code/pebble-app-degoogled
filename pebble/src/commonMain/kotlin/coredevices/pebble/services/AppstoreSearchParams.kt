package coredevices.pebble.services

import com.algolia.client.model.search.SearchParamsObject
import com.algolia.client.model.search.TagFilters

/**
 * The one place an Algolia search request is assembled. Both app store
 * feeds keep their search index on Algolia and upstream queries it straight
 * from the phone with per-feed search-only keys (`AppstoreSources.kt`), so
 * every store search is a request to a third party that the app's listing
 * has to disclose. Building the parameters here keeps the privacy flags
 * identical on every call and lets `AppstoreSearchParamsTest` pin them,
 * on the object and on the wire.
 *
 * What each flag buys:
 * - `analytics = false`: Algolia's server-side default is true, which
 *   retains the query and its metadata (hit count, country derived from
 *   the requesting IP) in the index owner's Algolia Analytics. False
 *   excludes the request from that retention; the query itself still has
 *   to reach Algolia to be answered.
 * - `clickAnalytics = false`: no queryID is minted for Insights
 *   click/conversion events. The app never sends those events, so the ID
 *   would only ever be a correlation handle.
 * - `enablePersonalization = false`: no per-user ranking signals. The
 *   client default is already false; it is pinned so a default change in
 *   a client upgrade cannot turn it on.
 * - `userToken` is never set: without one, nothing in the request links
 *   one query to the next beyond the connection itself.
 */
internal fun algoliaSearchParams(
    query: String,
    tagFilters: TagFilters? = null,
    page: Int? = null,
    hitsPerPage: Int? = null,
): SearchParamsObject = SearchParamsObject(
    query = query,
    tagFilters = tagFilters,
    page = page,
    hitsPerPage = hitsPerPage,
    analytics = false,
    clickAnalytics = false,
    enablePersonalization = false,
)
