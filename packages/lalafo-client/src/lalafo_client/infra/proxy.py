"""Smartproxy integration for Lalafo client.

Provides sticky sessions through Smartproxy gateway with Kyrgyzstan geo-targeting.
"""

from __future__ import annotations

import secrets
import logging
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class SmartproxyConfig:
    """Smartproxy gateway configuration.

    Can be initialized from pass store values or environment variables.
    """

    username: str = "smart-elixir"
    password: str = ""
    endpoint: str = "proxy.smartproxy.net:3120"
    residential_endpoint: str = "gate.smartproxy.com:7000"

    # Lalafo-specific targeting
    country: str = "KG"
    city: str = "OSH"
    session_ttl_minutes: int = 120

    # Current sticky session token
    _session_token: str = field(default="", init=False)

    @classmethod
    def from_userpass(cls, userpass: str, **kwargs) -> SmartproxyConfig:
        """Create from 'username:password' string (from pass store)."""
        if ":" in userpass:
            user, pwd = userpass.split(":", 1)
            return cls(username=user, password=pwd, **kwargs)
        return cls(password=userpass, **kwargs)

    @property
    def session_token(self) -> str:
        if not self._session_token:
            self._session_token = secrets.token_hex(8)
        return self._session_token

    def rotate_session(self) -> str:
        """Generate new sticky session token."""
        self._session_token = secrets.token_hex(8)
        logger.info("Rotated proxy session: %s", self._session_token)
        return self._session_token

    @property
    def is_configured(self) -> bool:
        return bool(self.username and self.password)


def build_proxy_url(
    config: SmartproxyConfig,
    *,
    session_token: str | None = None,
    residential: bool = False,
) -> str | None:
    """Build proxy URL for httpx client.

    Args:
        config: Smartproxy configuration
        session_token: Override sticky session token
        residential: Use residential endpoint (slower but less detectable)
    """
    if not config.is_configured:
        return None

    token = session_token or config.session_token
    endpoint = config.residential_endpoint if residential else config.endpoint

    # Lalafo format: user_area-KG_city-OSH_life-120_session-TOKEN
    username = (
        f"{config.username}"
        f"_area-{config.country}"
        f"_city-{config.city}"
        f"_life-{config.session_ttl_minutes}"
        f"_session-{token}"
    )

    return f"http://{username}:{config.password}@{endpoint}"


def build_proxy_headers(config: SmartproxyConfig) -> dict[str, str]:
    """Build proxy authentication headers (alternative to URL auth)."""
    if not config.is_configured:
        return {}
    return {
        "Proxy-Authorization": f"Basic {config.username}:{config.password}",
    }


def build_solver_proxy_string(
    config: SmartproxyConfig,
    session_token: str | None = None,
) -> str | None:
    """Build proxy string for captcha solver APIs."""
    return build_proxy_url(config, session_token=session_token)
