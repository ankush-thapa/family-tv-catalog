#!/usr/bin/env python3
import datetime as dt
import json
import os
import re
import sys
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen

from catalog_source import CATALOG


OUTPUT_PATH = Path("family_tv.json")
YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3"
DEFAULT_MIN_DURATION_SECONDS = 60


def slugify(value):
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower()).strip("-")
    return slug or "item"


def youtube_video_id(url):
    cleaned_url = url.strip().rstrip(".,;)")
    parsed = urlparse(cleaned_url)
    host = parsed.netloc.lower().replace("www.", "")

    if host == "youtu.be":
        candidate = parsed.path.strip("/").split("/")[0]
    elif host.endswith("youtube.com"):
        if parsed.path == "/watch":
            candidate = parse_qs(parsed.query).get("v", [""])[0]
        elif parsed.path.startswith("/shorts/"):
            raise ValueError(f"Shorts are not allowed: {cleaned_url}")
        elif parsed.path.startswith("/embed/"):
            candidate = parsed.path.split("/")[2]
        else:
            candidate = ""
    else:
        candidate = ""

    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", candidate):
        raise ValueError(f"Could not parse YouTube video id from: {cleaned_url}")
    return candidate


def parse_iso8601_duration(value):
    match = re.fullmatch(
        r"P(?:(?P<days>\d+)D)?(?:T(?:(?P<hours>\d+)H)?(?:(?P<minutes>\d+)M)?(?:(?P<seconds>\d+)S)?)?",
        value or "",
    )
    if not match:
        return 0

    parts = {name: int(match.group(name) or 0) for name in ("days", "hours", "minutes", "seconds")}
    return parts["days"] * 86400 + parts["hours"] * 3600 + parts["minutes"] * 60 + parts["seconds"]


def format_duration(seconds):
    hours, remainder = divmod(seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{seconds:02d}"
    return f"{minutes}:{seconds:02d}"


def youtube_api_get(resource, params):
    api_key = os.environ.get("YOUTUBE_API_KEY")
    if not api_key:
        raise RuntimeError(
            "A playlist uses 'channel', so YOUTUBE_API_KEY is required. "
            "Add it as a GitHub Actions repository secret."
        )

    query = urlencode({**params, "key": api_key})
    request = Request(f"{YOUTUBE_API_BASE}/{resource}?{query}", headers={"User-Agent": "FamilyTV catalog builder"})
    try:
        with urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        details = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"YouTube API {resource} failed with HTTP {exc.code}: {details}") from exc


def fetch_oembed(video_id):
    url = f"https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={video_id}&format=json"
    request = Request(url, headers={"User-Agent": "FamilyTV catalog builder"})
    with urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def normalize_video_entry(entry):
    if isinstance(entry, str):
        return {"url": entry}
    if isinstance(entry, dict):
        return entry
    raise ValueError(f"Video entry must be a URL string or object, got: {entry!r}")


def normalize_channel_reference(reference):
    value = reference.strip().rstrip(".,;)")
    parsed = urlparse(value)
    host = parsed.netloc.lower().replace("www.", "")

    if host.endswith("youtube.com") or host == "youtu.be":
        parts = [part for part in parsed.path.strip("/").split("/") if part]
        if parts and parts[0].startswith("@"):
            return "forHandle", parts[0]
        if len(parts) >= 2 and parts[0] == "channel":
            return "id", parts[1]
        if len(parts) >= 2 and parts[0] == "user":
            return "forUsername", parts[1]
        raise ValueError(f"Use a channel handle URL like https://www.youtube.com/@msrachel: {reference}")

    if value.startswith("@"):
        return "forHandle", value
    if re.fullmatch(r"UC[A-Za-z0-9_-]{22}", value):
        return "id", value
    if re.fullmatch(r"[A-Za-z0-9_.-]+", value):
        return "forHandle", value

    raise ValueError(
        f"Channel reference should be a YouTube handle, channel URL, or channel ID, got: {reference!r}"
    )


def resolve_channel(reference):
    filter_name, filter_value = normalize_channel_reference(reference)
    response = youtube_api_get(
        "channels",
        {
            "part": "snippet,contentDetails",
            filter_name: filter_value,
            "maxResults": 1,
            "fields": "items(id,snippet/title,contentDetails/relatedPlaylists/uploads)",
        },
    )
    items = response.get("items", [])
    if not items:
        raise ValueError(f"YouTube channel not found: {reference}")
    return items[0]


def fetch_playlist_items(playlist_id, max_videos=None):
    items = []
    page_token = None

    while True:
        params = {
            "part": "snippet,contentDetails,status",
            "playlistId": playlist_id,
            "maxResults": 50,
            "fields": (
                "nextPageToken,"
                "items(snippet/title,snippet/resourceId/videoId,snippet/thumbnails,"
                "contentDetails/videoId,status/privacyStatus)"
            ),
        }
        if page_token:
            params["pageToken"] = page_token

        response = youtube_api_get("playlistItems", params)
        for item in response.get("items", []):
            video_id = item.get("contentDetails", {}).get("videoId") or item.get("snippet", {}).get("resourceId", {}).get("videoId")
            title = item.get("snippet", {}).get("title", "")
            if video_id and title not in {"Deleted video", "Private video"}:
                items.append(item)
                if max_videos and len(items) >= max_videos:
                    return items

        page_token = response.get("nextPageToken")
        if not page_token:
            return items


def chunks(values, size):
    for index in range(0, len(values), size):
        yield values[index : index + size]


def fetch_video_details(video_ids):
    details = {}
    for batch in chunks(video_ids, 50):
        response = youtube_api_get(
            "videos",
            {
                "part": "snippet,contentDetails,status",
                "id": ",".join(batch),
                "fields": (
                    "items(id,snippet/title,snippet/thumbnails,"
                    "contentDetails/duration,status/privacyStatus,status/embeddable)"
                ),
            },
        )
        for item in response.get("items", []):
            details[item["id"]] = item
    return details


def thumbnail_url(video_id, snippet):
    thumbnails = snippet.get("thumbnails", {})
    for name in ("medium", "default", "standard", "high", "maxres"):
        if thumbnails.get(name, {}).get("url"):
            return thumbnails[name]["url"]
    return f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg"


def channel_video_entries(playlist):
    channel = resolve_channel(playlist["channel"])
    uploads_playlist_id = channel["contentDetails"]["relatedPlaylists"]["uploads"]
    min_duration = int(playlist.get("minDurationSeconds", DEFAULT_MIN_DURATION_SECONDS))
    max_videos = playlist.get("maxVideos")
    require_embeddable = playlist.get("requireEmbeddable", True)

    playlist_items = fetch_playlist_items(uploads_playlist_id, max_videos=max_videos)
    video_ids = []
    for item in playlist_items:
        video_id = item.get("contentDetails", {}).get("videoId")
        if video_id:
            video_ids.append(video_id)

    details_by_id = fetch_video_details(video_ids)
    entries = []
    skipped_short = 0
    skipped_unembeddable = 0

    for video_id in video_ids:
        details = details_by_id.get(video_id)
        if not details:
            continue

        duration_seconds = parse_iso8601_duration(details.get("contentDetails", {}).get("duration"))
        if duration_seconds < min_duration:
            skipped_short += 1
            continue

        status = details.get("status", {})
        if status.get("privacyStatus") not in (None, "public", "unlisted"):
            continue
        if require_embeddable and status.get("embeddable") is False:
            skipped_unembeddable += 1
            continue

        snippet = details.get("snippet", {})
        entries.append(
            {
                "title": snippet.get("title") or f"{playlist.get('name') or channel['snippet']['title']} {len(entries) + 1:02d}",
                "youtubeVideoId": video_id,
                "durationText": format_duration(duration_seconds),
                "thumbnailUrl": thumbnail_url(video_id, snippet),
            }
        )

    print(
        f"Fetched {len(entries)} videos from {channel['snippet']['title']} "
        f"(skipped {skipped_short} shorter than {min_duration}s, {skipped_unembeddable} not embeddable)"
    )
    return channel, entries


def manual_video_entry(raw_video, playlist_name, fallback_index):
    video = normalize_video_entry(raw_video)
    video_id = youtube_video_id(video["url"])

    oembed = {}
    try:
        oembed = fetch_oembed(video_id)
    except Exception as exc:
        print(
            f"WARNING: Could not fetch YouTube metadata for {video['url']}. "
            f"Using fallback title. Details: {exc}",
            file=sys.stderr,
        )

    title = video.get("title") or oembed.get("title") or f"{playlist_name} {fallback_index:02d}"
    return {
        "id": video.get("id"),
        "title": title,
        "youtubeVideoId": video_id,
        "durationText": video.get("durationText") or playlist_name,
        "thumbnailUrl": video.get("thumbnailUrl") or f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
    }


def add_video(videos, seen_video_ids, playlist_id, entry):
    video_id = entry["youtubeVideoId"]
    if video_id in seen_video_ids:
        return
    seen_video_ids.add(video_id)

    title = entry["title"]
    videos.append(
        {
            "id": entry.get("id") or f"{playlist_id}-{slugify(title)}",
            "title": title,
            "youtubeVideoId": video_id,
            "durationText": entry.get("durationText", ""),
            "thumbnailUrl": entry.get("thumbnailUrl") or f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
        }
    )


def build_catalog():
    playlists = []
    seen_video_ids = set()

    for playlist in CATALOG:
        channel = None
        channel_entries = []
        if playlist.get("channel"):
            channel, channel_entries = channel_video_entries(playlist)

        playlist_name = playlist.get("name") or channel["snippet"]["title"]
        playlist_id = playlist.get("id") or slugify(playlist_name)
        videos = []

        for entry in channel_entries:
            add_video(videos, seen_video_ids, playlist_id, entry)

        for index, raw_video in enumerate(playlist.get("videos", []), start=1):
            entry = manual_video_entry(raw_video, playlist_name, index)
            add_video(videos, seen_video_ids, playlist_id, entry)

        if videos:
            playlists.append(
                {
                    "id": playlist_id,
                    "name": playlist_name,
                    "videos": videos,
                }
            )

    return {
        "updatedAt": dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "playlists": playlists,
    }


def main():
    try:
        catalog = build_catalog()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    OUTPUT_PATH.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n")
    total = sum(len(playlist["videos"]) for playlist in catalog["playlists"])
    print(f"Wrote {OUTPUT_PATH} with {len(catalog['playlists'])} playlists and {total} videos")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
