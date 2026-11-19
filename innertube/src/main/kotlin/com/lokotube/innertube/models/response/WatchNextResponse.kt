package com.lokotube.innertube.models.response

import com.lokotube.innertube.models.Comment
import com.lokotube.innertube.models.CommentsPage
import com.lokotube.innertube.models.NavigationEndpoint
import com.lokotube.innertube.models.Runs
import com.lokotube.innertube.models.WatchInfo
import com.lokotube.innertube.utils.decodeHtmlEntities
import kotlinx.serialization.Serializable

/**
 * The WEB watch page (`next`): the channel strip with its subscribe button, and a comment section
 * that only states a placeholder header — the rows arrive when the section's continuation token is
 * fetched (see [CommentsResponse]).
 */
@Serializable
data class WatchNextResponse(
    val contents: Contents? = null,
) {
    @Serializable
    data class Contents(
        val twoColumnWatchNextResults: TwoColumnWatchNextResults? = null,
    )

    @Serializable
    data class TwoColumnWatchNextResults(
        val results: Results? = null,
    )

    @Serializable
    data class Results(
        val results: InnerResults? = null,
    )

    @Serializable
    data class InnerResults(
        val contents: List<Content>? = null,
    )

    @Serializable
    data class Content(
        val videoPrimaryInfoRenderer: VideoPrimaryInfoRenderer? = null,
        val videoSecondaryInfoRenderer: VideoSecondaryInfoRenderer? = null,
        val itemSectionRenderer: ItemSectionRenderer? = null,
    )

    @Serializable
    data class VideoPrimaryInfoRenderer(
        val title: Runs? = null,
        val viewCount: ViewCount? = null,
        val dateText: SimpleText? = null,
    ) {
        @Serializable
        data class ViewCount(val videoViewCountRenderer: VideoViewCountRenderer? = null)

        @Serializable
        data class VideoViewCountRenderer(val viewCount: SimpleText? = null)
    }

    @Serializable
    data class SimpleText(val simpleText: String? = null)

    @Serializable
    data class VideoSecondaryInfoRenderer(
        val owner: Owner? = null,
        val subscribeButton: SubscribeButton? = null,
        val attributedDescription: Description? = null,
    ) {
        @Serializable
        data class Description(val content: String? = null)
    }

    @Serializable
    data class Owner(
        val videoOwnerRenderer: VideoOwnerRenderer? = null,
    )

    @Serializable
    data class VideoOwnerRenderer(
        val title: Runs? = null,
        val subscriberCountText: SubscriberText? = null,
        val navigationEndpoint: NavigationEndpoint? = null,
    )

    @Serializable
    data class SubscriberText(
        val simpleText: String? = null,
        val runs: List<Run>? = null,
    ) {
        val text: String? get() = simpleText ?: runs?.joinToString("") { it.text ?: "" }?.ifBlank { null }
    }

    @Serializable
    data class Run(
        val text: String? = null,
    )

    @Serializable
    data class SubscribeButton(
        val subscribeButtonRenderer: SubscribeButtonRenderer? = null,
    )

    @Serializable
    data class SubscribeButtonRenderer(
        val subscribed: Boolean = false,
        val channelId: String? = null,
    )

    @Serializable
    data class ItemSectionRenderer(
        val contents: List<Item>? = null,
        val header: Header? = null,
        val sectionIdentifier: String? = null,
    )

    @Serializable
    data class Header(
        val commentsHeaderRenderer: CommentsHeaderRenderer? = null,
    )

    @Serializable
    data class CommentsHeaderRenderer(
        val countText: Runs? = null,
    )

    @Serializable
    data class Item(
        val continuationItemRenderer: ContinuationItemRenderer? = null,
    )

    @Serializable
    data class ContinuationItemRenderer(
        val continuationEndpoint: ContinuationEndpoint? = null,
    )

    @Serializable
    data class ContinuationEndpoint(
        val continuationCommand: ContinuationCommand? = null,
    )

    @Serializable
    data class ContinuationCommand(
        val token: String? = null,
    )
}

/** Reads the channel strip and the comment section's token out of a watch page. */
fun WatchNextResponse.toWatchInfo(): WatchInfo {
    val contents = contents?.twoColumnWatchNextResults?.results?.results?.contents.orEmpty()
    val secondary = contents.firstNotNullOfOrNull { it.videoSecondaryInfoRenderer }
    val owner = secondary?.owner?.videoOwnerRenderer
    val subscribe = secondary?.subscribeButton?.subscribeButtonRenderer
    // The comment section identifies itself; its only item is the continuation that loads the rows.
    val commentsSection =
        contents
            .mapNotNull { it.itemSectionRenderer }
            .firstOrNull { it.sectionIdentifier?.contains("comment", ignoreCase = true) == true || it.header?.commentsHeaderRenderer != null }
    val token =
        commentsSection
            ?.contents
            .orEmpty()
            .firstNotNullOfOrNull { it.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token }
    val primary = contents.firstNotNullOfOrNull { it.videoPrimaryInfoRenderer }
    val title = primary?.title?.runs?.firstOrNull()?.text?.decodeHtmlEntities()
    // The description arrives whole (YouTube's own "…more" is a client-side collapse), so the panel
    // can show all of it without another request.
    val description = secondary?.attributedDescription?.content?.decodeHtmlEntities()?.ifBlank { null }
    return WatchInfo(
        title = title,
        description = description,
        viewCount = primary?.viewCount?.videoViewCountRenderer?.viewCount?.simpleText,
        publishedDate = primary?.dateText?.simpleText,
        channelId = subscribe?.channelId ?: owner?.navigationEndpoint?.browseEndpoint?.browseId,
        channelName = owner?.title?.runs?.firstOrNull()?.text?.decodeHtmlEntities(),
        subscriberText = owner?.subscriberCountText?.text,
        subscribed = subscribe?.subscribed == true,
        commentsToken = token,
    )
}

/**
 * A comments page (`next` continuation, or the first page after following the section's token). The
 * rows come as `commentThreadRenderer` **views**; their text lives in `frameworkUpdates` entity
 * payloads keyed by `commentViewModel.commentKey`, so both have to be joined.
 */
@Serializable
data class CommentsResponse(
    val onResponseReceivedEndpoints: List<Endpoint>? = null,
    val frameworkUpdates: FrameworkUpdates? = null,
) {
    @Serializable
    data class Endpoint(
        val reloadContinuationItemsCommand: ContinuationItems? = null,
        val appendContinuationItemsAction: ContinuationItems? = null,
    )

    @Serializable
    data class ContinuationItems(
        val continuationItems: List<Item>? = null,
    )

    @Serializable
    data class Item(
        val commentThreadRenderer: CommentThreadRenderer? = null,
        val commentsHeaderRenderer: Header? = null,
        val continuationItemRenderer: WatchNextResponse.ContinuationItemRenderer? = null,
    )

    @Serializable
    data class Header(
        val countText: Runs? = null,
    )

    @Serializable
    data class CommentThreadRenderer(
        val commentViewModel: CommentViewModel? = null,
    )

    @Serializable
    data class CommentViewModel(
        val commentViewModel: ViewModel? = null,
    )

    @Serializable
    data class ViewModel(
        val commentKey: String? = null,
        val commentId: String? = null,
    )

    @Serializable
    data class FrameworkUpdates(
        val entityBatchUpdate: EntityBatchUpdate? = null,
    )

    @Serializable
    data class EntityBatchUpdate(
        val mutations: List<Mutation>? = null,
    )

    @Serializable
    data class Mutation(
        val payload: Payload? = null,
    )

    @Serializable
    data class Payload(
        val commentEntityPayload: CommentEntityPayload? = null,
    )

    @Serializable
    data class CommentEntityPayload(
        val key: String? = null,
        val properties: Properties? = null,
        val author: Author? = null,
        val toolbar: Toolbar? = null,
    )

    @Serializable
    data class Properties(
        val commentId: String? = null,
        val content: CommentContent? = null,
        val publishedTime: String? = null,
    )

    @Serializable
    data class CommentContent(
        val content: String? = null,
    )

    @Serializable
    data class Author(
        val channelId: String? = null,
        val displayName: String? = null,
        val avatarThumbnailUrl: String? = null,
        val isCreator: Boolean = false,
    )

    @Serializable
    data class Toolbar(
        val likeCountNotliked: String? = null,
        val replyCount: String? = null,
    )
}

fun CommentsResponse.toCommentsPage(): CommentsPage {
    val items = onResponseReceivedEndpoints.orEmpty().flatMap { it.reloadContinuationItemsCommand?.continuationItems ?: it.appendContinuationItemsAction?.continuationItems.orEmpty() }
    val countText = items.firstNotNullOfOrNull { it.commentsHeaderRenderer?.countText?.runs?.joinToString("") { run -> run.text }?.ifBlank { null } }
    val payloads =
        frameworkUpdates
            ?.entityBatchUpdate
            ?.mutations
            .orEmpty()
            .mapNotNull { it.payload?.commentEntityPayload }
            .filter { it.key != null }
            .associateBy { it.key }
    val comments =
        items
            .mapNotNull { it.commentThreadRenderer?.commentViewModel?.commentViewModel }
            .mapNotNull { view ->
                val payload = payloads[view.commentKey] ?: return@mapNotNull null
                Comment(
                    id = view.commentId ?: payload.properties?.commentId.orEmpty(),
                    author = payload.author?.displayName?.decodeHtmlEntities().orEmpty(),
                    authorChannelId = payload.author?.channelId,
                    avatar = payload.author?.avatarThumbnailUrl,
                    text = payload.properties?.content?.content?.decodeHtmlEntities().orEmpty(),
                    publishedTime = payload.properties?.publishedTime,
                    likes = payload.toolbar?.likeCountNotliked?.takeIf { it.isNotBlank() && it != "0" },
                    replies = payload.toolbar?.replyCount?.takeIf { it.isNotBlank() && it != "0" },
                    byCreator = payload.author?.isCreator == true,
                )
            }
            // Deleted/placeholder rows carry no text; an empty box in the list is worse than skipping it.
            .filter { it.text.isNotBlank() && it.author.isNotBlank() }
    val continuation =
        items.lastOrNull { it.continuationItemRenderer != null }
            ?.continuationItemRenderer
            ?.continuationEndpoint
            ?.continuationCommand
            ?.token
    return CommentsPage(comments = comments, countText = countText, continuation = continuation)
}
