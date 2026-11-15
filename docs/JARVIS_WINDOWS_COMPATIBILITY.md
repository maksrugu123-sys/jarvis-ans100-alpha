# JARVIS Windows compatibility

## Supported desktop targets

- Windows 10 version 22H2 (build 19045), x64.
- Windows 11 versions 23H2 and 24H2, x64.

JARVIS is packaged as a standalone desktop application. The launcher starts
the local ANS-100 service and opens the interface in its own native window
through pywebview and Microsoft Edge WebView2. It does not launch Chrome or
Edge as a browser and does not show browser tabs or an address bar.

The Windows app requires the Microsoft Edge WebView2 Runtime. Windows 11
includes the Evergreen Runtime. On Windows 10 it may already be installed by
another application; if it is missing, install the Evergreen WebView2 Runtime
from Microsoft's official download page:
https://developer.microsoft.com/microsoft-edge/webview2/

## Automated compatibility checks

The Windows packaging workflow installs pywebview, builds the portable
executable, and starts its local API in headless smoke-test mode on
GitHub-hosted Windows Server 2022 and Windows Server 2025 runners before
publishing the ZIP. These checks verify packaging and the API, but do not
replace testing the actual WebView2 window on physical Windows 10/11 devices.

## Scope and limitations

- The current portable build targets x64. Windows on ARM is not yet verified.
- The native WebView2 window still needs a physical-device visual test on
  Windows 10 and Windows 11.
- Chrome/Edge Browser Bridge remains an optional integration for controlling
  ordinary browser tabs; it is not used to display the JARVIS application.
- Use a fully extracted ZIP folder; do not run JARVIS.exe from inside the ZIP.
