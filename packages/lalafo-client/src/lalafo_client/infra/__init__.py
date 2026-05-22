"""Infrastructure: proxy management, anti-bot bypass, session handling."""

from lalafo_client.infra.proxy import SmartproxyConfig, build_proxy_url, build_proxy_headers
from lalafo_client.infra.session import SessionManager
from lalafo_client.infra.captcha import CaptchaSolver

__all__ = [
    "SmartproxyConfig",
    "build_proxy_url",
    "build_proxy_headers",
    "SessionManager",
    "CaptchaSolver",
]
