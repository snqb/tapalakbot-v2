"""Pydantic models for Lalafo API responses."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field, model_validator


class ListingImage(BaseModel):
    """Single listing image."""

    original_url: str = ""
    thumbnail_url: str = ""

    @model_validator(mode="before")
    @classmethod
    def extract_urls(cls, data: Any) -> Any:
        if isinstance(data, dict):
            return {
                "original_url": data.get("original_url", data.get("original", "")),
                "thumbnail_url": data.get("thumbnail_url", data.get("thumbnail", "")),
            }
        return data


class ListingParam(BaseModel):
    """Key-value parameter from listing details."""

    name: str = ""
    value: str = ""


class Listing(BaseModel):
    """Single Lalafo listing (ad)."""

    id: int
    title: str = ""
    description: str = ""
    price: float | None = None
    currency: str | None = "KGS"
    category_id: int | None = None
    city: str = ""
    city_id: int | None = None
    url: str = ""
    images: list[ListingImage] = Field(default_factory=list)
    params: list[ListingParam] = Field(default_factory=list)
    created_at: datetime | None = None
    updated_at: datetime | None = None
    user_id: int | None = None
    username: str | None = ""
    mobile: str | None = ""
    status: str = ""

    @property
    def full_url(self) -> str:
        """Get full lalafo.kg URL."""
        if self.url.startswith("http"):
            return self.url
        return f"https://lalafo.kg{self.url}" if self.url else ""

    @property
    def first_image(self) -> str:
        """Get first image URL or empty string."""
        return self.images[0].original_url if self.images else ""

    @model_validator(mode="before")
    @classmethod
    def normalize_api_response(cls, data: Any) -> Any:
        """Normalize raw API response fields to our model."""
        if not isinstance(data, dict):
            return data

        # Map API field names to our fields
        images_raw = data.get("images") or []
        images = []
        for img in images_raw:
            if isinstance(img, dict):
                images.append(img)
            elif isinstance(img, str):
                images.append({"original_url": img})

        # Extract price
        price = data.get("price")
        if isinstance(price, str):
            try:
                price = float(price.replace(" ", "").replace(",", "."))
            except ValueError:
                price = None

        return {
            "id": data.get("id"),
            "title": data.get("title", ""),
            "description": data.get("description", ""),
            "price": price,
            "currency": data.get("currency", "KGS"),
            "category_id": data.get("category_id"),
            "city": data.get("city", ""),
            "city_id": data.get("city_id"),
            "url": data.get("url", ""),
            "images": images,
            "params": data.get("params") or [],
            "created_at": data.get("created_time"),
            "updated_at": data.get("updated_time"),
            "user_id": data.get("user_id"),
            "username": data.get("username") or "",
            "mobile": data.get("mobile") or "",
            "status": data.get("status", ""),
        }


class SearchResult(BaseModel):
    """Full search response with pagination."""

    items: list[Listing] = Field(default_factory=list)
    total_count: int = 0
    current_page: int = 1
    per_page: int = 200
    has_more: bool = False

    @model_validator(mode="before")
    @classmethod
    def from_api_response(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data
        # Lalafo puts pagination in _meta, not at top level
        meta = data.get("_meta", {})
        total = meta.get("totalCount", 0) or data.get("totalCount", 0)
        page = meta.get("currentPage", 1) or data.get("currentPage", 1)
        per_page = meta.get("perPage", 200) or data.get("perPage", 200)
        items_raw = data.get("items") or []
        return {
            "items": items_raw,
            "total_count": total,
            "current_page": page,
            "per_page": per_page,
            "has_more": (page * per_page) < total,
        }
