# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller onedir package for the Windows JARVIS desktop console."""
from pathlib import Path

ROOT = Path(SPECPATH).resolve().parents[1]
STATIC = ROOT / "tools" / "jarvis_app" / "static"
EXTENSION = ROOT / "tools" / "jarvis_app" / "browser_extension"

# Keep this list explicit. collect_submodules() imports every discovered module,
# including platform-specific test helpers; that caused Windows analysis to try
# importing POSIX-only fcntl. The production server uses normal static imports,
# which PyInstaller follows recursively from windows_entry.py.
hiddenimports = []

a = Analysis(
    [str(ROOT / "tools" / "jarvis_app" / "windows_entry.py")],
    pathex=[str(ROOT)],
    binaries=[],
    datas=[
        (str(STATIC), "tools/jarvis_app/static"),
        (str(EXTENSION), "tools/jarvis_app/browser_extension"),
    ],
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "pytest", "unittest"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="JARVIS",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="JARVIS",
)
