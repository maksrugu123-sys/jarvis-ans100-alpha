"""Frozen entry point for the native JARVIS Windows desktop application."""
import sys

if "--server-only" in sys.argv:
    # CI uses this mode to test the local API without opening a desktop window.
    sys.argv.remove("--server-only")
    from tools.jarvis_app.server import main
else:
    from tools.jarvis_app.windows_desktop import main


if __name__ == "__main__":
    raise SystemExit(main())
