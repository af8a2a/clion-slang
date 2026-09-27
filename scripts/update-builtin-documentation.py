"""Refresh the offline documentation link catalog from official indexes (maintainer tool).

Stores only symbol names and links, not documentation text. Never runs during build or hover.
"""
import html
import json
import re
from pathlib import Path
from urllib.parse import urljoin
from urllib.request import Request, urlopen

HLSL = "https://learn.microsoft.com/en-us/windows/win32/direct3dhlsl/dx-graphics-hlsl-intrinsic-functions"
SLANG = "https://docs.shader-slang.org/en/latest/external/core-module-reference/global-decls/index.html"
OUTPUT = Path(__file__).resolve().parent.parent / "src/main/resources/documentation/builtins.json"


def anchors(markup, base):
    for href, label in re.findall(r'<a\b[^>]*href="([^"]+)"[^>]*>(.*?)</a>', markup, re.S):
        name = html.unescape(re.sub(r"<[^>]+>", "", label)).strip()
        if re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", name):
            yield name, urljoin(base, html.unescape(href))


def fetch(url):
    request = Request(url, headers={"User-Agent": "Mozilla/5.0"})
    return urlopen(request, timeout=45).read().decode("utf-8")


def main():
    entries = {}
    # Only the function name cell, never links in descriptions or site navigation.
    cells = re.findall(r"<tr>\s*<td>(.*?)</td>", fetch(HLSL), re.S)
    for cell in cells:
        for name, url in anchors(cell, HLSL):
            if url.startswith(HLSL.rsplit("/", 1)[0] + "/"):
                entries.setdefault(name, {}).setdefault("microsoft", []).append(url)
    # plain=1 avoids a CDN error on the bare index; links still resolve against the canonical URL.
    for name, url in anchors(fetch(SLANG + "?plain=1"), SLANG):
        if url.startswith(SLANG.rsplit("/", 1)[0] + "/") and not url.endswith("index.html") and "#" not in url:
            entries.setdefault(name, {})["slang"] = url
    for item in entries.values():
        if "microsoft" in item:
            item["microsoft"] = sorted(set(item["microsoft"]))
    assert len(entries) > 300 and "microsoft" in entries["abs"] and "slang" in entries["RAY_FLAG_NONE"]
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps({"sources": [HLSL, SLANG], "symbols": dict(sorted(entries.items()))}, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Wrote {len(entries)} symbol references to {OUTPUT}")


if __name__ == "__main__":
    main()
