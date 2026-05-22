"""Dynamic category discovery and navigation for Lalafo.

Uses the catalog/v2/categories API endpoint to build a full category tree
with 7000+ categories across all verticals (not just real estate).

The tree uses nested set model (lft/rgt) internally for efficient subtree queries.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import httpx

logger = logging.getLogger(__name__)

CATEGORIES_API = "https://lalafo.kg/api/catalog/v2/categories"
DEFAULT_HEADERS = {
    "accept": "application/json",
    "Device": "pc",
    "Language": "ru_RU",
    "Country-Id": "12",
    "user-agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
}

# Cache file for offline use
CACHE_DIR = Path.home() / ".cache" / "lalafo-client"


@dataclass
class Category:
    """Single category node."""

    id: int
    name: str
    parent_id: int | None = None
    depth: int = 0
    lft: int = 0
    rgt: int = 0
    count: int = 0
    children: list[Category] = field(default_factory=list)

    @property
    def is_leaf(self) -> bool:
        return self.rgt - self.lft == 1

    @property
    def has_children(self) -> bool:
        return self.rgt - self.lft > 1


class CategoryTree:
    """Full Lalafo category tree with search and navigation.

    Usage:
        tree = await CategoryTree.fetch()  # from API
        tree = CategoryTree.load()          # from cache

        # Find categories
        cats = tree.search("macbook")
        cat = tree.find_by_id(1469)

        # Navigate
        children = tree.children_of(1467)  # electronics subcategories
        path = tree.path_to(2044)          # ["Недвижимость", "Аренда квартир"]
    """

    def __init__(self, categories: list[Category]):
        self._all = categories
        self._by_id: dict[int, Category] = {c.id: c for c in categories}
        self._by_name: dict[str, Category] = {}
        self._roots: list[Category] = []

        # Build parent-child relationships
        for cat in categories:
            name_lower = cat.name.lower()
            self._by_name[name_lower] = cat
            if cat.parent_id and cat.parent_id in self._by_id:
                self._by_id[cat.parent_id].children.append(cat)
            if cat.depth == 1:
                self._roots.append(cat)

    @classmethod
    async def fetch(
        cls,
        *,
        client: httpx.AsyncClient | None = None,
        cache: bool = True,
    ) -> CategoryTree:
        """Fetch category tree from Lalafo API.

        Args:
            client: Optional httpx client (for using proxied client)
            cache: Whether to cache results locally
        """
        should_close = False
        if client is None:
            client = httpx.AsyncClient(timeout=30)
            should_close = True

        try:
            resp = await client.get(CATEGORIES_API, headers=DEFAULT_HEADERS)
            resp.raise_for_status()
            data = resp.json()
        finally:
            if should_close:
                await client.aclose()

        raw_cats = data.get("data", [])
        categories = [
            Category(
                id=c["id"],
                name=c.get("name", ""),
                parent_id=c.get("parent_id"),
                depth=c.get("depth", 0),
                lft=c.get("lft", 0),
                rgt=c.get("rgt", 0),
                count=c.get("count", 0),
            )
            for c in raw_cats
        ]

        tree = cls(categories)

        if cache:
            tree._save_cache(raw_cats)

        logger.info("Loaded %d categories from API (%d top-level)", len(categories), len(tree._roots))
        return tree

    @classmethod
    def load(cls, cache_path: Path | None = None) -> CategoryTree | None:
        """Load category tree from local cache."""
        path = cache_path or (CACHE_DIR / "categories.json")
        if not path.exists():
            return None

        try:
            raw = json.loads(path.read_text())
            categories = [
                Category(
                    id=c["id"],
                    name=c.get("name", ""),
                    parent_id=c.get("parent_id"),
                    depth=c.get("depth", 0),
                    lft=c.get("lft", 0),
                    rgt=c.get("rgt", 0),
                    count=c.get("count", 0),
                )
                for c in raw
            ]
            tree = cls(categories)
            logger.info("Loaded %d categories from cache", len(categories))
            return tree
        except Exception as e:
            logger.warning("Failed to load category cache: %s", e)
            return None

    def _save_cache(self, raw: list[dict[str, Any]]) -> None:
        """Save raw category data to cache."""
        try:
            CACHE_DIR.mkdir(parents=True, exist_ok=True)
            (CACHE_DIR / "categories.json").write_text(json.dumps(raw, ensure_ascii=False))
        except Exception as e:
            logger.warning("Failed to save category cache: %s", e)

    # --- Query methods ---

    def find_by_id(self, category_id: int) -> Category | None:
        return self._by_id.get(category_id)

    def find_by_name(self, name: str) -> Category | None:
        return self._by_name.get(name.lower())

    @property
    def roots(self) -> list[Category]:
        """Top-level categories."""
        return self._roots

    def children_of(self, category_id: int) -> list[Category]:
        """Direct children of a category."""
        cat = self._by_id.get(category_id)
        return cat.children if cat else []

    def subtree_ids(self, category_id: int) -> list[int]:
        """All descendant IDs (using nested set lft/rgt)."""
        cat = self._by_id.get(category_id)
        if not cat:
            return []
        return [c.id for c in self._all if cat.lft <= c.lft and c.rgt <= cat.rgt]

    def path_to(self, category_id: int) -> list[str]:
        """Get path from root to category as list of names."""
        path = []
        cat = self._by_id.get(category_id)
        while cat:
            path.append(cat.name)
            cat = self._by_id.get(cat.parent_id) if cat.parent_id else None
        return list(reversed(path))

    def search(self, query: str, *, max_results: int = 20) -> list[Category]:
        """Search categories by name (fuzzy substring match)."""
        q = query.lower()
        exact = []
        partial = []
        for cat in self._all:
            name = cat.name.lower()
            if name == q:
                exact.append(cat)
            elif q in name:
                partial.append(cat)

        # Prefer exact matches, then sort partials by depth (shallower = more general)
        results = exact + sorted(partial, key=lambda c: c.depth)
        return results[:max_results]

    def suggest_category(self, query: str) -> Category | None:
        """Smart category suggestion for a search query.

        Tries to find the most specific leaf category matching the query.
        Returns None if no good match found (caller should search without category).
        """
        matches = self.search(query, max_results=10)
        if not matches:
            return None

        # Prefer leaf categories (more specific)
        leaves = [m for m in matches if m.is_leaf]
        if leaves:
            return leaves[0]
        return matches[0]

    def summary(self) -> str:
        """Human-readable summary of top-level categories."""
        lines = []
        for root in sorted(self._roots, key=lambda c: c.lft):
            child_count = len(self.subtree_ids(root.id)) - 1
            lines.append(f"  [{root.id}] {root.name} ({child_count} subcategories)")
        return f"Lalafo Categories ({len(self._all)} total):\n" + "\n".join(lines)

    def __len__(self) -> int:
        return len(self._all)

    def __repr__(self) -> str:
        return f"CategoryTree({len(self._all)} categories, {len(self._roots)} roots)"
