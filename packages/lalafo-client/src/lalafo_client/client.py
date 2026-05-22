"""Async Lalafo.kg API client.

Supports all categories, keyword search, filtering, pagination.
Handles Cloudflare bypass sessions and proxy rotation transparently.

Usage:
    from lalafo_client import LalafoClient

    # Simple (no proxy, no CF bypass — works for most read operations)
    async with LalafoClient() as client:
        results = await client.search("macbook m1")
        details = await client.get_details(62631870)
        categories = await client.get_categories()

    # Full infra (proxy + CF bypass)
    from lalafo_client.infra import SmartproxyConfig, SessionManager, CaptchaSolver

    proxy = SmartproxyConfig.from_userpass("user:pass")
    solver = CaptchaSolver(api_key="...")
    session_mgr = SessionManager(proxy_config=proxy, captcha_solver=solver)

    async with LalafoClient(session_manager=session_mgr, proxy_config=proxy) as client:
        results = await client.search("квартира бишкек", category_id=2044)
"""

from __future__ import annotations

import logging
from typing import Any

import httpx
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from lalafo_client.categories import CategoryTree
from lalafo_client.infra.proxy import SmartproxyConfig, build_proxy_url
from lalafo_client.infra.session import SessionManager
from lalafo_client.models import Listing, SearchResult

logger = logging.getLogger(__name__)

BASE_URL = "https://lalafo.kg/api"

# Browser-like headers to avoid detection
DEFAULT_HEADERS = {
    "accept": "application/json, text/plain, */*",
    "accept-language": "en-US,en;q=0.9",
    "Device": "pc",
    "Language": "ru_RU",
    "Country-Id": "12",
    "sec-ch-ua": '"Chromium";v="142", "Google Chrome";v="142"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"macOS"',
    "sec-fetch-dest": "empty",
    "sec-fetch-mode": "cors",
    "sec-fetch-site": "same-origin",
    "x-cache-bypass": "yes",
    "user-agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/142.0.0.0 Safari/537.36"
    ),
}

TRANSIENT_ERRORS = (httpx.ConnectError, httpx.ReadTimeout, httpx.ConnectTimeout)


class LalafoClient:
    """Async Lalafo.kg API client."""

    def __init__(
        self,
        *,
        session_manager: SessionManager | None = None,
        proxy_config: SmartproxyConfig | None = None,
        base_url: str = BASE_URL,
        timeout: int = 45,
    ):
        self.base_url = base_url
        self.session_manager = session_manager
        self.proxy_config = proxy_config
        self._timeout = timeout
        self._client: httpx.AsyncClient | None = None
        self._category_tree: CategoryTree | None = None

    async def __aenter__(self) -> LalafoClient:
        self._client = self._build_client()
        return self

    async def __aexit__(self, *args: Any) -> None:
        if self._client:
            await self._client.aclose()

    def _build_client(self) -> httpx.AsyncClient:
        """Build httpx client with optional proxy."""
        proxy_url = None
        if self.proxy_config and self.proxy_config.is_configured:
            proxy_url = build_proxy_url(self.proxy_config)

        return httpx.AsyncClient(
            timeout=self._timeout,
            proxy=proxy_url,
            follow_redirects=True,
            http2=True,
        )

    def _get_headers(self) -> dict[str, str]:
        """Merge default headers with session headers."""
        headers = dict(DEFAULT_HEADERS)
        if self.session_manager:
            headers.update(self.session_manager.get_headers())
        return headers

    def _get_cookies(self) -> dict[str, str]:
        """Get session cookies."""
        if self.session_manager:
            return self.session_manager.get_cookies()
        return {}

    @retry(
        retry=retry_if_exception_type(TRANSIENT_ERRORS),
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=10),
        reraise=True,
    )
    async def _request(
        self,
        path: str,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any] | None:
        """Make an API request with retry logic."""
        assert self._client is not None, "Client not initialized. Use async with."

        # Ensure session if needed
        if self.session_manager and self.session_manager.needs_refresh:
            await self.session_manager.ensure_session()

        url = f"{self.base_url}/{path.lstrip('/')}"
        try:
            resp = await self._client.get(
                url,
                params=params,
                headers=self._get_headers(),
                cookies=self._get_cookies(),
            )

            if resp.status_code in {401, 403, 419}:
                logger.warning("Got %d — refreshing session", resp.status_code)
                if self.session_manager:
                    await self.session_manager.ensure_session(force=True)
                    # Rebuild client if proxy token changed
                    await self._client.aclose()
                    self._client = self._build_client()
                resp.raise_for_status()

            resp.raise_for_status()
            return resp.json()

        except httpx.HTTPStatusError:
            raise
        except TRANSIENT_ERRORS:
            # Session/proxy might be stale
            if self.session_manager:
                await self.session_manager.ensure_session(force=True)
            raise

    # --- Public API ---

    async def search(
        self,
        query: str | None = None,
        *,
        category_id: int | None = None,
        page: int = 1,
        per_page: int = 200,
        price_min: int | None = None,
        price_max: int | None = None,
        city_id: int | None = None,
        **extra_params: Any,
    ) -> SearchResult:
        """Search listings across all categories.

        Args:
            query: Search text (e.g., "macbook m1 pro", "квартира центр")
            category_id: Filter by category (None = all categories)
            page: Page number (1-based)
            per_page: Results per page (max 200)
            price_min: Minimum price filter
            price_max: Maximum price filter
            city_id: Filter by city (103184 = Bishkek)
            **extra_params: Additional API parameters

        Returns:
            SearchResult with parsed Listing objects
        """
        params: dict[str, Any] = {
            "expand": "url",
            "page": page,
            "per-page": per_page,
        }

        if query:
            params["q"] = query
        if category_id is not None:
            params["category_id"] = category_id
        if price_min is not None:
            params["price_from"] = price_min
        if price_max is not None:
            params["price_to"] = price_max
        if city_id is not None:
            params["city_id"] = city_id
        params.update(extra_params)

        data = await self._request("search/v3/feed/search", params)
        if not data:
            return SearchResult(items=[], total_count=0)

        return SearchResult.model_validate(data)

    async def get_details(self, listing_id: int) -> Listing | None:
        """Get full listing details including contact info.

        Args:
            listing_id: Lalafo listing ID

        Returns:
            Listing with full details, or None if not found
        """
        data = await self._request(
            f"search/v3/feed/details/{listing_id}",
            {"expand": "url"},
        )
        if not data:
            return None
        return Listing.model_validate(data)

    async def get_categories(self, *, force_refresh: bool = False) -> CategoryTree:
        """Get full category tree (cached after first call).

        Args:
            force_refresh: Force re-fetch from API (ignores cache)
        """
        if self._category_tree and not force_refresh:
            return self._category_tree

        # Try cache first
        if not force_refresh:
            cached = CategoryTree.load()
            if cached:
                self._category_tree = cached
                return cached

        # Fetch from API
        self._category_tree = await CategoryTree.fetch(client=self._client)
        return self._category_tree

    async def search_smart(
        self,
        query: str,
        *,
        page: int = 1,
        per_page: int = 50,
        **kwargs: Any,
    ) -> SearchResult:
        """Smart search: auto-detect category from query, then search.

        Uses the category tree to find the best matching category,
        then searches within it for more relevant results.

        Falls back to all-category search if no good category match.
        """
        tree = await self.get_categories()
        suggested = tree.suggest_category(query)

        category_id = None
        if suggested:
            path = " → ".join(tree.path_to(suggested.id))
            logger.info("Auto-detected category: %s (id=%d)", path, suggested.id)
            category_id = suggested.id

        return await self.search(
            query,
            category_id=category_id,
            page=page,
            per_page=per_page,
            **kwargs,
        )

    async def count(
        self,
        query: str | None = None,
        *,
        category_id: int | None = None,
    ) -> int:
        """Get total listing count for a query/category without fetching items."""
        result = await self.search(query, category_id=category_id, per_page=1)
        return result.total_count
