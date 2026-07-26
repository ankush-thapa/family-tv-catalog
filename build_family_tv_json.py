#!/usr/bin/env python3
import datetime as dt
import json
import re
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen

from catalog_source import CATALOG


OUTPUT_PATH = Path("family_tv.json")


def slugify(value):
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower()).strip("-")
    return slug or "item"


def youtube_video_id(url):
    parsed = urlparse(url.strip())
    host = parsed.netloc.lower().replace("www.", "")

    if host == "youtu.be":
        candidate = parsed.path.strip("/").split("/")[0]
    elif host.endswith("youtube.com"):
        if parsed.path == "/watch":
            candidate = parse_qs(parsed.query).get("v", [""])[0]
        elif parsed.path.startswith("/shorts/"):
            raise ValueError(f"Shorts are not allowed: {url}")
        elif parsed.path.startswith("/embed/"):
            candidate = parsed.path.split("/")[2]
        else:
            candidate = ""
    else:
        candidate = ""

    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", candidate):
        raise ValueError(f"Could not parse YouTube video id from: {url}")
    return candidate


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


def build_catalog():
    playlists = []
    seen_video_ids = set()

    for playlist in CATALOG:
        playlist_id = playlist.get("id") or slugify(playlist["name"])
        videos = []

        for index, raw_video in enumerate(playlist.get("videos", []), start=1):
            video = normalize_video_entry(raw_video)
            video_id = youtube_video_id(video["url"])
            if video_id in seen_video_ids:
                continue
            seen_video_ids.add(video_id)

            try:
                oembed = fetch_oembed(video_id)
            except Exception as exc:
                raise ValueError(
                    f"YouTube did not provide embeddable metadata for {video['url']}. "
                    f"The video may be private, deleted, restricted, or not embeddable. Details: {exc}"
                ) from exc

            title = video.get("title") or oembed.get("title") or f"{playlist['name']} {index:02d}"
            videos.append(
                {
                    "id": video.get("id") or f"{playlist_id}-{slugify(title)}",
                    "title": title,
                    "youtubeVideoId": video_id,
                    "durationText": video.get("durationText") or playlist["name"],
                    "thumbnailUrl": video.get("thumbnailUrl")
                    or f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
                }
            )

        if videos:
            playlists.append(
                {
                    "id": playlist_id,
                    "name": playlist["name"],
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
