"""Refresh pinned, self-hosted Emoji Mart assets. Run from the repository root."""
import io
import json
from pathlib import Path
import tarfile
import urllib.request

destination = Path("src/main/resources/static/viewer/vendor")
destination.mkdir(parents=True, exist_ok=True)
packages = [
    ("https://registry.npmjs.org/emoji-mart/-/emoji-mart-5.6.0.tgz", {
        "package/dist/browser.js": "emoji-mart-5.6.0.js",
        "package/LICENSE": "emoji-mart-LICENSE",
    }),
    ("https://registry.npmjs.org/@emoji-mart/data/-/data-1.2.1.tgz", {
        "package/sets/15/native.json": "emoji-mart-data.json",
        "package/LICENSE": "emoji-mart-data-LICENSE",
    }),
]
for url, files in packages:
    with urllib.request.urlopen(url) as response:
        archive = tarfile.open(fileobj=io.BytesIO(response.read()), mode="r:gz")
    for member, name in files.items():
        content = archive.extractfile(member).read()
        if name == "emoji-mart-data.json":
            data = json.loads(content)
            for emoji in data["emojis"].values():
                emoji["skins"] = emoji["skins"][:1]
            content = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()
        (destination / name).write_bytes(content)
