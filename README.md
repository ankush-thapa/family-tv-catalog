# Family TV

This repo contains both:

- The generated TV catalog JSON.
- The Android TV app that reads and plays that catalog.

Edit `catalog_source.py` to add playlists.

## Channel Playlists

For public YouTube channels, add a channel handle or channel URL:

```python
{
    "id": "ms-rachel",
    "name": "Ms Rachel",
    "channel": "@msrachel",
    "minDurationSeconds": 60,
}
```

The script fetches the channel's public uploads, keeps videos that are at least
`minDurationSeconds`, skips videos that YouTube marks as not embeddable, and
writes them into `family_tv.json`.

`channel` accepts:

- `@msrachel`
- `msrachel`
- `https://www.youtube.com/@msrachel`
- `https://www.youtube.com/channel/UC...`
- `UC...` channel IDs

Use handles or channel URLs rather than fuzzy display names. Display names can be
ambiguous.

To use channel playlists in GitHub Actions, add a repository secret named:

```text
YOUTUBE_API_KEY
```

Create this as a YouTube Data API v3 API key in Google Cloud. A normal API key is
enough for public channel uploads; do not put your YouTube password in GitHub
Secrets.

Optional channel settings:

```python
{
    "id": "pinkfong",
    "name": "Pinkfong",
    "channel": "@Pinkfong",
    "minDurationSeconds": 60,
    "maxVideos": 200,
    "requireEmbeddable": True,
}
```

`maxVideos` limits how many newest uploads are scanned before filtering. Omit it
to scan all public uploads.

## Hand-picked Video Playlists

Video entries can be plain URL strings:

```python
{
    "id": "ms-rachel",
    "name": "Ms Rachel",
    "videos": [
        "https://www.youtube.com/watch?v=w264Mn-2MnQ",
        "https://youtu.be/8KtnrtHRiCg",
    ],
}
```

Titles are fetched automatically from YouTube.

When you commit to `main`, GitHub Actions regenerates `family_tv.json`.

The Android TV app reads:

```text
https://raw.githubusercontent.com/ankush-thapa/family-tv-catalog/main/family_tv.json
```

## Local Test

```sh
python build_family_tv_json.py
```

## Android TV App

The app source lives in `app/`.

Before creating a new APK, bump these values in `app/build.gradle`:

```gradle
versionCode 2
versionName "0.2.0"
```

Then build a versioned debug APK:

```sh
scripts/build_versioned_apk.sh
```

The script writes APKs to:

```text
apks/family-tv-v<versionName>-debug.apk
```

Commit the generated APK when you want that installable version tracked.

## Notes

- `youtube.com/shorts/...` links are rejected.
- Accidental trailing punctuation like `.` after a copied link is ignored.
- YouTube oEmbed metadata is attempted for every video.
- If YouTube metadata is unavailable, generation continues with a fallback title.
- Duplicate video IDs are skipped.
- `thumbnailUrl` is optional.
- `durationText` is optional.

This catches malformed links and Shorts. YouTube metadata checks are best-effort only: YouTube may return `401 Unauthorized` to automated oEmbed requests even for videos that play in the TV app. The TV app remains the final playback test.
