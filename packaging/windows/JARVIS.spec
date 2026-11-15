# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller onedir package for the Windows JARVIS desktop console."""
from pathlib import Path
from PyInstaller.utils.hooks import collect_submodules

ROOT = Path(SPECPATH).resolve().parents[1]
STATIC = ROOT / "tools" / "jarvis_app" / "static"
EXTENSION = ROOT / "tools" / "jarvis_app" / "browser_extension"

hidden = []
for package in (
    "tools.neuron_runtime",
    "tools.jpvr_reference",
    "tools.reasoning_engine",
    "tools.numerical_oracle",
    "tools.jarvis_app",
):
    hidden += collect_submodules(package)

a = Analysis(
    [str(ROOT / "tools" / "jarvis_app" / "windows_entry.py")],
    pathex=[str(ROOT)],
    binaries=[],
    datas=[
        (str(STATIC), "tools/jarvis_app/static"),
        (str(EXTENSION), "tools/jarvis_app/browser_extension"),
    ],
    hiddenimports=hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "pytest"],
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
