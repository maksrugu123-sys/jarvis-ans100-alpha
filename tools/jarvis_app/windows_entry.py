"""Frozen Windows entry point for the JARVIS desktop console.

Kept separate from __main__.py so PyInstaller can resolve the package imports
from the repository root without changing the normal Python module launcher.
"""
from tools.jarvis_app.server import main


if __name__ == "__main__":
    raise SystemExit(main())
