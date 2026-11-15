# JARVIS Windows compatibility

## Supported desktop targets

- Windows 10 version 22H2 (build 19045), x64.
- Windows 11 versions 23H2 and 24H2, x64.

The portable package uses a standard PyInstaller Windows executable, Python's
Windows runtime, PowerShell for the launcher, and a loopback-only HTTP server.
It does not require Windows 11-only APIs. Windows 10 and Windows 11 both report
the NT 10.0 kernel family; the supported desktop versions differ by build.

## Automated compatibility checks

The Windows packaging workflow runs the packaging contract, creates the
portable executable, and starts its local API on GitHub-hosted
Windows Server 2022 and Windows Server 2025 runners before publishing the ZIP.
These runner checks catch Windows runtime and packaging regressions, but they
are not a substitute for testing on a physical Windows 10/11 desktop.

## Scope and limitations

- The current portable build targets x64. Windows on ARM is not yet verified.
- Windows 11 desktop compatibility is targeted based on the shared Windows
  runtime/API surface and the CI matrix; a physical Windows 11 desktop smoke
  test remains to be completed.
- Chrome/Edge Browser Bridge still requires a separate end-to-end check on
  each desktop version.
- Use a fully extracted ZIP folder; do not run JARVIS.exe from inside the ZIP.
