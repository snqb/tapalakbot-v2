"""Lalafo session management — handles Cloudflare bypass sessions.

Sessions consist of:
- cf_clearance cookie (IP-locked, 2hr TTL)
- User-Agent string (must match the one used during challenge solve)
- Proxy session token (for IP consistency)
"""

from __future__ import annotations

import json
import logging
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from lalafo_client.infra.captcha import CaptchaSolver, CfSolution
from lalafo_client.infra.proxy import SmartproxyConfig, build_solver_proxy_string

logger = logging.getLogger(__name__)

SESSION_DIR = Path.home() / ".cache" / "lalafo-client" / "sessions"
SESSION_FILE = SESSION_DIR / "lalafo_session.json"


class SessionData:
    """Parsed session state."""

    def __init__(
        self,
        cookies: dict[str, str],
        user_agent: str,
        proxy_session_token: str,
        expires_at: datetime,
        refreshed_at: datetime,
    ):
        self.cookies = cookies
        self.user_agent = user_agent
        self.proxy_session_token = proxy_session_token
        self.expires_at = expires_at
        self.refreshed_at = refreshed_at

    @property
    def is_expired(self) -> bool:
        margin = timedelta(minutes=5)
        return datetime.now(timezone.utc) >= self.expires_at - margin

    @property
    def cf_clearance(self) -> str | None:
        return self.cookies.get("cf_clearance")

    def to_dict(self) -> dict[str, Any]:
        return {
            "cookies": self.cookies,
            "user_agent": self.user_agent,
            "proxy_session_token": self.proxy_session_token,
            "expires_at": self.expires_at.isoformat(),
            "refreshed_at": self.refreshed_at.isoformat(),
            "method": "riskbypass_direct",
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> SessionData:
        return cls(
            cookies=data.get("cookies") or data.get("cookie_jar") or {},
            user_agent=data.get("user_agent", ""),
            proxy_session_token=data.get("proxy_session_token", ""),
            expires_at=datetime.fromisoformat(data["expires_at"]).replace(tzinfo=timezone.utc)
            if "expires_at" in data
            else datetime.now(timezone.utc),
            refreshed_at=datetime.fromisoformat(data["refreshed_at"]).replace(tzinfo=timezone.utc)
            if "refreshed_at" in data
            else datetime.now(timezone.utc),
        )


class SessionManager:
    """Manages Cloudflare bypass sessions for Lalafo.

    Handles:
    - Loading/saving sessions from disk
    - Checking expiry and refreshing
    - RiskBypass-based session capture
    - Proxy session token rotation
    """

    def __init__(
        self,
        proxy_config: SmartproxyConfig | None = None,
        captcha_solver: CaptchaSolver | None = None,
        ttl_minutes: int = 120,
    ):
        self.proxy_config = proxy_config
        self.captcha_solver = captcha_solver
        self.ttl_minutes = ttl_minutes
        self._session: SessionData | None = None

        # Try loading existing session
        self._load_from_disk()

    def _load_from_disk(self) -> None:
        if not SESSION_FILE.exists():
            return
        try:
            data = json.loads(SESSION_FILE.read_text())
            session = SessionData.from_dict(data)
            if not session.is_expired:
                self._session = session
                logger.info(
                    "Loaded session from disk (age: %.1f min)",
                    (datetime.now(timezone.utc) - session.refreshed_at).total_seconds() / 60,
                )
            else:
                logger.info("Cached session expired, will refresh on demand")
        except Exception as e:
            logger.warning("Failed to load session: %s", e)

    def _save_to_disk(self) -> None:
        if not self._session:
            return
        try:
            SESSION_DIR.mkdir(parents=True, exist_ok=True)
            SESSION_FILE.write_text(json.dumps(self._session.to_dict(), indent=2))
        except Exception as e:
            logger.warning("Failed to save session: %s", e)

    @property
    def session(self) -> SessionData | None:
        return self._session

    @property
    def needs_refresh(self) -> bool:
        return self._session is None or self._session.is_expired

    def get_headers(self) -> dict[str, str]:
        """Get session headers for API requests."""
        if not self._session:
            return {}
        return {"user-agent": self._session.user_agent}

    def get_cookies(self) -> dict[str, str]:
        """Get session cookies for API requests."""
        if not self._session:
            return {}
        return self._session.cookies

    async def ensure_session(self, *, force: bool = False) -> SessionData | None:
        """Ensure we have a valid session, refreshing if needed.

        Args:
            force: Force refresh even if current session is valid

        Returns:
            SessionData or None if refresh failed
        """
        if not force and self._session and not self._session.is_expired:
            return self._session

        if not self.captcha_solver:
            logger.warning("No captcha solver configured — cannot refresh session")
            return self._session

        logger.info("Refreshing Lalafo session via RiskBypass...")

        # Generate new proxy session token
        proxy_token = secrets.token_hex(8)
        proxy_string = None
        if self.proxy_config:
            proxy_string = build_solver_proxy_string(
                self.proxy_config, session_token=proxy_token
            )

        solution = await self.captcha_solver.solve_cloudflare(
            proxy=proxy_string,
        )

        if not solution:
            logger.error("Failed to capture Cloudflare session")
            return self._session  # Return stale session if available

        now = datetime.now(timezone.utc)
        self._session = SessionData(
            cookies=solution.cookies,
            user_agent=solution.user_agent,
            proxy_session_token=proxy_token,
            expires_at=now + timedelta(minutes=self.ttl_minutes),
            refreshed_at=now,
        )

        # Update proxy config with new token
        if self.proxy_config:
            self.proxy_config._session_token = proxy_token

        self._save_to_disk()
        logger.info("✅ Session refreshed (expires in %d min)", self.ttl_minutes)
        return self._session
