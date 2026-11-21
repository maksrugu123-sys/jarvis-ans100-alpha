"""Pure validation tests for the optional visible browser controller."""
from __future__ import annotations

import unittest

from tools.jarvis_app.visible_browser import (
    VisibleBrowserResearchError,
    validate_external_url,
    validate_search_query,
)


class VisibleBrowserValidationTests(unittest.TestCase):
    def test_accepts_public_http_and_https_urls(self):
        self.assertEqual(
            validate_external_url("https://example.com"),
            "https://example.com/",
        )
        self.assertEqual(
            validate_external_url("http://example.com/path?q=1#fragment"),
            "http://example.com/path?q=1",
        )

    def test_rejects_non_http_schemes_and_embedded_credentials(self):
        for value in (
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hello",
            "ftp://example.com/file",
            "https://user:password@example.com/",
        ):
            with self.subTest(url=value), self.assertRaises(VisibleBrowserResearchError):
                validate_external_url(value)

    def test_rejects_loopback_private_reserved_and_local_hosts(self):
        for value in (
            "http://127.0.0.1/",
            "http://10.0.0.2/",
            "http://192.168.1.1/",
            "http://[::1]/",
            "https://localhost/",
            "https://printer.local/",
            "https://router.internal/",
        ):
            with self.subTest(url=value), self.assertRaises(VisibleBrowserResearchError):
                validate_external_url(value)

    def test_restricts_custom_ports_and_control_characters(self):
        with self.assertRaises(VisibleBrowserResearchError):
            validate_external_url("https://example.com:8443/")
        with self.assertRaises(VisibleBrowserResearchError):
            validate_external_url("https://example.com/\nheader")

    def test_normalizes_and_bounds_search_query(self):
        self.assertEqual(validate_search_query("  jarvis   memory "), "jarvis memory")
        with self.assertRaises(VisibleBrowserResearchError):
            validate_search_query(" ")
        with self.assertRaises(VisibleBrowserResearchError):
            validate_search_query("x" * 501)


if __name__ == "__main__":
    unittest.main()
