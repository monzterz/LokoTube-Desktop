package com.lokotube.innertube.models

/** What the watch page says about the channel: who it is and whether the account follows it. */
data class WatchInfo(
    val title: String? = null,
    val channelId: String?,
    val channelName: String?,
    val subscriberText: String?,
    val subscribed: Boolean,
    /** Token that opens the comment section (`next` continuation). */
    val commentsToken: String?,
)

/** One comment on the watch page. */
data class Comment(
    val id: String,
    val author: String,
    val authorChannelId: String?,
    val avatar: String?,
    val text: String,
    val publishedTime: String?,
    /** e.g. "223K", "1.2K" — YouTube's own label, not a number. */
    val likes: String?,
    val replies: String?,
    val byCreator: Boolean,
)

/** A page of comments: the rows plus the token for the next page. */
data class CommentsPage(
    val comments: List<Comment>,
    /** e.g. "2.3M Comments"; the header carries it, the request itself does not. */
    val countText: String?,
    val continuation: String?,
)
