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
- YouTube oEmbed metadata is fetched for every video.
- If YouTube does not return embeddable metadata, generation fails so the bad link can be removed.
- Duplicate video IDs are skipped.
- `thumbnailUrl` is optional.
- `durationText` is optional.

This catches many common problems such as private/deleted/restricted/non-embeddable videos. It is still not a perfect guarantee: the Android TV WebView can still be rejected by YouTube for device, region, account, or embed-origin reasons. The TV app remains the final playback test.
