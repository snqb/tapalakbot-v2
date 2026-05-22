#!/usr/bin/env python3
"""CLI wrapper for lalafo-client — JSON-in, JSON-out for Clojure harness.
Run from tapalakbot directory: uv run python lalafo_cli.py search '...'

Usage:
  cd ~/Projects/tapalakbot && uv run python ~/Projects/tapalakbot-v2/lalafo_cli.py search '{"queries":["router"],"price_max":4000}'
  cd ~/Projects/tapalakbot && uv run python ~/Projects/tapalakbot-v2/lalafo_cli.py categories
  cd ~/Projects/tapalakbot && uv run python ~/Projects/tapalakbot-v2/lalafo_cli.py category-search headphones
  cd ~/Projects/tapalakbot && uv run python ~/Projects/tapalakbot-v2/lalafo_cli.py research '{"query":"iPad Air 3 specs"}'
"""

import argparse
import asyncio
import json
import os
import sys

# Add tapalakbot packages to path — Railway (bundled) vs local dev
_basedir = os.path.dirname(os.path.abspath(__file__))
_pkgdir = os.path.join(_basedir, "packages")
if os.path.isdir(_pkgdir):
    sys.path.insert(0, os.path.join(_pkgdir, "lalafo-client", "src"))
    sys.path.insert(0, os.path.join(_pkgdir, "bot", "src"))
else:
    sys.path.insert(0, os.path.expanduser("~/Projects/tapalakbot/packages/lalafo-client/src"))
    sys.path.insert(0, os.path.expanduser("~/Projects/tapalakbot/packages/bot/src"))

from lalafo_client import LalafoClient
from lalafo_client.models import Listing
from tapalakbot.agents.category_prompt import build_category_prompt


# ══════════════════════════ QUALITY PRE-FILTER ══════════════════════════

def quality_filter(items: list[Listing]) -> list[Listing]:
    """Deterministic quality pre-filter. Removes obvious junk.
    Rules: must have price, price > 50, title >= 8 chars, not ALL CAPS, has images."""
    result = []
    for item in items:
        if item.price is None or item.price == 0:
            continue
        if item.price < 50:
            continue
        title = item.title or ""
        if len(title) < 8:
            continue
        if title.isupper() and len(title) > 15:
            continue
        if not item.images:
            continue
        result.append(item)
    return result


async def cmd_search(args_str: str) -> str:
    """Multi-query + multi-page Lalafo search with quality pre-filter.
    Scans up to 3 pages x 200 items per query, deduplicates, filters junk.
    Returns up to 250 compact candidates for LLM relevance pass."""
    params = json.loads(args_str)
    queries = params.get("queries", [params.get("query", "")])
    category_id = params.get("category_id")
    price_max = params.get("price_max")
    price_min = params.get("price_min")
    city_id = params.get("city_id", 103184)  # default Bishkek
    max_pages = params.get("max_pages", 3)
    per_page = params.get("per_page", 200)

    async with LalafoClient() as client:
        all_items: dict[int, Listing] = {}
        total_raw = 0
        pages_scanned = 0

        async def _search_all_pages(q: str) -> tuple[str, int, int]:
            """Search all pages for one query. Returns (query, found, pages)."""
            found = 0
            pages = 0
            for page in range(1, max_pages + 1):
                r = await client.search(
                    q, category_id=category_id,
                    price_max=price_max, price_min=price_min,
                    city_id=city_id, per_page=per_page, page=page,
                )
                pages += 1
                for item in r.items:
                    if item.id not in all_items:
                        all_items[item.id] = item
                        found += 1
                if not r.has_more or not r.items:
                    break
            return q, found, pages

        tasks = [_search_all_pages(q) for q in queries[:6]]
        results_list = await asyncio.gather(*tasks, return_exceptions=True)

        for result in results_list:
            if isinstance(result, Exception):
                continue
            q, found, pages = result
            total_raw += found
            pages_scanned += pages

        unique = list(all_items.values())

        # Apply price_max filter (user-requested, not quality filter)
        if price_max:
            filtered = [i for i in unique if not i.price or i.price <= price_max]
            if filtered:
                unique = filtered

        # Apply quality pre-filter (deterministic junk removal)
        quality_filtered = quality_filter(unique)

        if not quality_filtered:
            return json.dumps({
                "found": 0, "items": [],
                "message": f"Nothing found for: {', '.join(queries)}",
                "stats": {"raw": len(unique), "pages": pages_scanned}
            }, ensure_ascii=False)

        # Compact format for LLM: id, title, price, url, short desc
        candidate_limit = params.get("candidate_limit", 250)
        items_out = []
        for item in quality_filtered[:candidate_limit]:  # broad pool for LLM relevance pass
            items_out.append({
                "id": item.id,
                "title": (item.title or "")[:80],
                "price": item.price,
                "currency": item.currency or "KGS",
                "url": item.full_url,
                "desc": (item.description or "")[:80].replace("\n", " "),
            })

        return json.dumps({
            "found": len(quality_filtered),
            "truncated": len(quality_filtered) > candidate_limit,
            "items": items_out,
            "stats": {"raw": len(unique), "filtered": len(quality_filtered), "pages": pages_scanned},
        }, ensure_ascii=False)


async def cmd_categories(args_str: str | None = None) -> str:
    """Get category tree. Optional search_term for filtering."""
    async with LalafoClient() as client:
        tree = await client.get_categories()

        if args_str:
            try:
                params = json.loads(args_str)
            except (json.JSONDecodeError, TypeError):
                params = {}

            search_term = params.get("search_term", "")

            if search_term and not search_term.strip().startswith("{"):
                matches = tree.search(search_term, max_results=10)
                if matches:
                    lines = [f"Categories matching '{search_term}':"]
                    for cat in matches:
                        path = " → ".join(tree.path_to(cat.id))
                        lines.append(f"  [{cat.id}] {path}")
                    return "\n".join(lines)
                return f"No categories found for '{search_term}'."

        # Full prompt
        return build_category_prompt(tree)


async def cmd_research(args_str: str) -> str:
    """Web research via Exa API."""
    params = json.loads(args_str)
    query = params.get("query", "")

    exa_key = os.environ.get("EXA_API_KEY", "")
    if not exa_key:
        # Try pass
        import subprocess
        try:
            result = subprocess.run(
                ["pass", "show", "api/exa"],
                capture_output=True, text=True, timeout=5,
            )
            if result.returncode == 0:
                exa_key = result.stdout.strip()
        except Exception:
            pass

    if not exa_key:
        return json.dumps({"error": "No Exa API key"})

    import httpx
    async with httpx.AsyncClient(timeout=15) as client:
        try:
            resp = await client.post(
                "https://api.exa.ai/search",
                headers={"x-api-key": exa_key, "content-type": "application/json"},
                json={
                    "query": query,
                    "type": "auto",
                    "numResults": 5,
                    "contents": {"text": {"maxCharacters": 300}},
                },
            )
            resp.raise_for_status()
            data = resp.json()
            results = [
                {"title": r.get("title", ""), "url": r.get("url", ""), "snippet": (r.get("text", "") or "")[:300]}
                for r in data.get("results", [])
            ]
            return json.dumps({"results": results}, ensure_ascii=False)
        except Exception as e:
            return json.dumps({"error": str(e)})


def main():
    parser = argparse.ArgumentParser(description="Lalafo CLI for Clojure harness")
    parser.add_argument("command", choices=["search", "categories", "category-search", "research"])
    parser.add_argument("args", nargs="?", default="{}")
    args = parser.parse_args()

    cmd = args.command
    cmd_args = args.args

    if cmd == "search":
        result = asyncio.run(cmd_search(cmd_args))
    elif cmd in ("categories", "category-search"):
        result = asyncio.run(cmd_categories(cmd_args))
    elif cmd == "research":
        result = asyncio.run(cmd_research(cmd_args))
    else:
        result = json.dumps({"error": f"Unknown command: {cmd}"})

    print(result)


if __name__ == "__main__":
    main()
