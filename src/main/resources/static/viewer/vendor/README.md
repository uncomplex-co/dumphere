# Emoji Mart

- `emoji-mart-5.6.0.js`: browser distribution of `emoji-mart@5.6.0` (MIT).
- `emoji-mart-data.json`: Unicode 15 native data from `@emoji-mart/data@1.2.1` (MIT), with only the default variant retained for every emoji.
- Licenses are included alongside the assets.

Regenerate with `python3 tools/vendor-emoji-mart.py` from the repository root.
No runtime CDN requests. The viewer lazily loads these files from its own origin.
The picker stylesheet's CSP hash in `HtmlPageController` must be updated when upgrading Emoji Mart.
