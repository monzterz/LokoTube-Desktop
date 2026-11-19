# Changelog

Releases are tagged `v*`; each one builds the Windows `.exe`/`.msi`, the macOS `.dmg` (arm64 + Intel) and
the Linux `.deb`/AppImage through the three GitHub workflows.

## 0.2.3 — subscription feed

- **Subscriptions** section in the sidebar: the newest videos from the channels the account follows
  (`FEsubscriptions`), with a refresh button. It is account data, so a signed-out app shows a
  "sign in" line instead of an empty screen.

## 0.2.2 — full description

- Third sidebar tab on the watch page: **Description**, with the view count and upload date above it.
  The whole text arrives with the watch page (YouTube's "…more" is a client-side collapse), so there
  is no extra request and nothing is truncated. The text is selectable, since a description is
  usually a list of links to copy.

## 0.2.1 — comments and subscribing

- **Watch-page strip**: title, channel · subscribers and a Subscribe pill that reflects the account
  (guests get "Sign in to subscribe" instead of a dead button).
- **Comments** tab beside the video: author, time, text, likes and reply counts, a page at a time with
  "Show more comments"; the count comes from YouTube itself.

## 0.2.0 — video, Shorts, saving videos

- **Video section**: search, a Live / Trending / Recommended home, an Up next sidebar, quality
  switching, full screen, and live broadcasts (HLS).
- **Shorts**: vertical snap pager with mouse wheel / drag / arrow keys, autoplay (muted), auto-advance
  and the next Short resolved in the background.
- **Save videos**: one click writes a single `.mp4`/`.webm` to `~/Videos/LokoTube`. Video and audio
  arrive as separate streams and are joined with a **stream copy** — no re-encode, no quality loss, no
  bundled ffmpeg. The Downloads page lists them with play and delete.
- **1080p by default**: measured on a normal desktop CPU, 1080p holds real time at about one core,
  while 1440p/4K fall behind — those stay an explicit choice in the quality menu.
- Titles decode the HTML entities YouTube embeds (emoji show as emoji).
- Music panels no longer squeeze the video and its sidebar in the video section.

## 0.1.0 — first release

Rebranded LokoTube desktop client (from Sonara/Metrolist): music playback with synced lyrics, library,
playlists, radio, downloads, equalizer, mini player, tray, media keys, SponsorBlock, channel blocking,
multi-channel switching, and installers for the three platforms.

---

## Where the video work lives

| Piece | File |
|---|---|
| Stream resolving (SABR + HLS) | `desktop/.../playback/StreamResolver.kt` |
| Player, prefetch, seek | `desktop/.../playback/VideoPlayer.kt`, `VideoRenderer.kt` |
| Saving videos (stream copy) | `desktop/.../playback/Muxer.kt`, `VideoDownloader.kt` |
| Watch page data (comments, description, subscribe) | `innertube/.../models/response/WatchNextResponse.kt` |
| Shorts feed | `innertube/.../models/response/ShortsSearchResponse.kt` |
| Screens | `desktop/.../ui/VideoScreen.kt`, `ShortsScreen.kt`, `VideoHomeScreen.kt` |

Tests that talk to YouTube live (everything under `com.lokotube.desktop.playback`) are opt-in:
`./gradlew -p desktop test -PliveTests`.

## Known limits

- A channel's bell preference (All / Personalized / None) is **not** implemented: it is a command
  mutation InnerTubeX does not expose.
- The subscriptions feed and subscribing need a signed-in account. The feed is verified against a real
  account (Linux, v0.2.3); the Subscribe **action** is not — it would change the account's
  subscriptions, so it stays a user-side check.
- 1440p and 4K playback fall behind the clock (software decode). mpv/hardware decoding was measured
  and deliberately not added: 1080p already holds real time.
