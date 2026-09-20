#!/usr/bin/env python3
"""Checks the store listing texts against Google Play's limits and for emoji.

  python3 play-store/check_listing.py
"""
import re
import sys
import unicodedata
from pathlib import Path

LIMITS = {"App name": 30, "Uygulama adı": 30, "Short description": 80, "Kısa açıklama": 80, "Full description": 4000, "Tam açıklama": 4000}
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿⬀-⯿️‍]")


def sections(text: str) -> dict[str, str]:
    parts = re.split(r"^## (.+)$", text, flags=re.M)
    return {parts[i].strip(): parts[i + 1].strip() for i in range(1, len(parts), 2)}


def main() -> int:
    failed = False
    for path in sorted(Path(__file__).parent.glob("listing-*.md")):
        text = path.read_text()
        for name, body in sections(text).items():
            limit = LIMITS.get(name)
            if limit is None:
                continue
            length = len(unicodedata.normalize("NFC", body))
            problems = []
            if length > limit:
                problems.append(f"over the limit by {length - limit}")
            if EMOJI.search(body):
                problems.append("contains an emoji")
            print(f"{path.name}: {name}: {length}/{limit}" + (f"  FAIL: {', '.join(problems)}" if problems else ""))
            failed |= bool(problems)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
