# Family TV Catalog

Edit `catalog_source.py` to add playlists and YouTube video links.

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

## Notes

- `youtube.com/shorts/...` links are rejected.
- Accidental trailing punctuation like `.` after a copied link is ignored.
- YouTube oEmbed metadata is attempted for every video.
- If YouTube metadata is unavailable, generation continues with a fallback title.
- Duplicate video IDs are skipped.
- `thumbnailUrl` is optional.
- `durationText` is optional.

This catches malformed links and Shorts. YouTube metadata checks are best-effort only: YouTube may return `401 Unauthorized` to automated oEmbed requests even for videos that play in the TV app. The TV app remains the final playback test.
