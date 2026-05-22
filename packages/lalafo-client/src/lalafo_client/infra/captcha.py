"""RiskBypass Cloudflare challenge solver.

Solves Cloudflare WAF challenges via RiskBypass API to get cf_clearance cookies.
No browser needed — pure API-based bypass.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any

import httpx

logger = logging.getLogger(__name__)


@dataclass
class CfSolution:
    """Cloudflare challenge solution."""

    cookies: dict[str, str]
    user_agent: str
    cf_clearance: str

    @classmethod
    def from_riskbypass(cls, result: dict[str, Any]) -> CfSolution | None:
        cookies = result.get("cookies", {})
        cf_clearance = cookies.get("cf_clearance", "")
        if not cf_clearance:
            return None
        user_agent = result.get("ua") or result.get("user_agent") or ""
        return cls(cookies=cookies, user_agent=user_agent, cf_clearance=cf_clearance)


class CaptchaSolver:
    """RiskBypass-based Cloudflare challenge solver."""

    SUBMIT_URL = "https://riskbypass.com/task/submit"
    RESULT_URL = "https://riskbypass.com/task/result"

    def __init__(self, api_key: str):
        self.api_key = api_key.strip()
        self._client = httpx.Client(timeout=60)

    async def solve_cloudflare(
        self,
        url: str = "https://lalafo.kg/kyrgyzstan",
        *,
        proxy: str | None = None,
        task_type: str = "cloudflare_waf",
        timeout: int = 300,
    ) -> CfSolution | None:
        """Solve Cloudflare challenge for a URL.

        Args:
            url: Target URL with Cloudflare protection
            proxy: Optional proxy string (http://user:pass@host:port)
            task_type: Challenge type (cloudflare_waf, turnstile)
            timeout: Max wait time in seconds

        Returns:
            CfSolution with cookies and user agent, or None on failure
        """
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key,
        }

        payload: dict[str, Any] = {
            "task_type": task_type,
            "target_url": url,
            "target_method": "GET",
        }
        if proxy:
            payload["proxy"] = proxy

        # Submit task
        try:
            resp = self._client.post(self.SUBMIT_URL, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
        except Exception as e:
            logger.error("RiskBypass submit failed: %s", e)
            return None

        if not data.get("ok"):
            logger.error("RiskBypass declined: %s", data)
            return None

        task_id = data.get("task_id")
        if not task_id:
            logger.error("No task_id in response: %s", data)
            return None

        # Poll for result
        logger.info("Waiting for RiskBypass solution (task %s)...", task_id)
        deadline = time.monotonic() + timeout
        poll_headers = {"x-api-key": self.api_key, "Cache-Control": "no-cache"}

        while time.monotonic() < deadline:
            try:
                poll = self._client.get(
                    f"{self.RESULT_URL}/{task_id}", headers=poll_headers
                )
                poll.raise_for_status()
                body = poll.json()
            except Exception as e:
                logger.error("Polling error: %s", e)
                time.sleep(5)
                continue

            status = body.get("status")
            if status in {"RUNNING", "QUEUED"}:
                time.sleep(5)
                continue
            if status == "SUCCESS":
                result = body.get("result", {})
                solution = CfSolution.from_riskbypass(result)
                if solution:
                    logger.info("✅ Cloudflare challenge solved")
                    return solution
                logger.warning("Solution missing cf_clearance: %s", result)
                return None
            if status in {"FAILED", "NOT_FOUND"}:
                logger.error("Task %s failed: %s", task_id, body)
                return None

            logger.warning("Unexpected status: %s", body)
            time.sleep(3)

        logger.error("Task %s timed out after %ds", task_id, timeout)
        return None

    def close(self) -> None:
        self._client.close()
