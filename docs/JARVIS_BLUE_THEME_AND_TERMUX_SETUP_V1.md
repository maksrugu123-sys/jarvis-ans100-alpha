# JARVIS Android Blue UI and Termux Setup V2

## Android visual update

The Android app keeps the dark navy palette and adds:
- a blue/cyan outline and elevated emphasis to the main chat, local-status and Termux panels;
- a compact status pill in the header;
- a small animated cyan pulse only while the conversation is busy, returning to a static indicator when idle;
- clickable HTTP(S) addresses in chat messages. Tapping an address opens it in the phone's external browser.

The pulse is limited to the active-processing state so it does not animate continuously when the app is idle.

## What works without Termux

The native Android app keeps its own chat, local conversation history, local memories and in-process ANS-100 signal pass. Termux is optional and is used only for the Python companion bridge, shared JPVR workspace, task/session registry and the bounded reasoning preview. The bridge does not execute shell commands on behalf of chat messages and does not make the Android app's base chat depend on Termux.

## Install F-Droid safely

The app store name is **F-Droid**. Its official first-install website is:

- https://f-droid.org/

Do not download the F-Droid installer from random APK mirrors, advertisements, file-sharing sites or comments.

1. Open https://f-droid.org/ in the phone browser.
2. Tap **Download F-Droid** and download the APK.
3. Open the downloaded APK. Android may show a warning that the browser is not allowed to install apps. Open the Android settings prompt and allow installation from that browser only for this installation.
4. Install F-Droid, open it, and let it download/update its app catalogue. The first catalogue refresh can take a little while.
5. After installation, consider turning off the browser's permission to install unknown apps again. F-Droid itself will request the permission needed to install apps from its catalogue.

F-Droid's own installation guide recommends using its official APK download for most users: https://f-droid.org/en/docs/Get_F-Droid/.

## Install the original Termux from F-Droid

Open the official package page:

- https://f-droid.org/packages/com.termux/

Or search for **Termux** inside the F-Droid application and choose the package named **Termux** (com.termux). Do not confuse the base terminal app with optional add-ons such as Termux:API or Termux:Widget. JARVIS's current bridge setup needs only the base Termux app.

Tap **Install** and wait for Android to finish. On the first Termux launch, wait for its bootstrap environment to finish preparing.

### Important update and signature notes

F-Droid gives you the newest Termux build that has already been indexed and published in its catalogue. It is not guaranteed to be the same-day newest upstream GitHub release: the Termux maintainers state that F-Droid publication can lag a few days or a week or more. You can refresh the F-Droid Updates screen manually by pulling down.

Use one installation source for the whole Termux family. Termux and its add-ons from F-Droid must not be mixed with Termux or add-ons installed from GitHub, because APK signing keys differ. If changing sources, back up Termux first, then uninstall the existing Termux app and its add-ons before installing the new source. Uninstalling without a backup can delete Termux's private files and settings. See the official Termux installation notes: https://github.com/termux/termux-app.

## Configure Termux

Open Termux and run:

~~~sh
pkg update
pkg upgrade -y
pkg install -y python git gh
~~~

The commands update the package catalogue, upgrade installed packages, and install Python, Git and GitHub CLI.

If pkg update cannot reach its repository, run:

~~~sh
termux-change-repo
~~~

Choose **Main repository** and a working mirror, confirm, then run pkg update again. Do not copy repository URLs from random tutorials.

Check that the tools are available:

~~~sh
python3 --version
git --version
gh --version
~~~

No termux-setup-storage permission is required for the basic JARVIS bridge; the project can live in Termux's private home directory.

## Sign in to GitHub before cloning

The current JARVIS repository is private, so an unauthenticated clone will fail. Install GitHub CLI as shown above, then run:

~~~sh
gh auth login --hostname github.com --git-protocol https --web
gh auth setup-git
~~~

Follow the prompts. Choose GitHub.com and HTTPS if asked. Termux will show a one-time device code; open the official GitHub site in the browser, enter that code and approve the sign-in with the GitHub account that has access to this repository.

Do not paste a GitHub password, personal access token or one-time code into this chat. Do not use the insecure-storage option for GitHub CLI authentication.

## Download the correct JARVIS branch

The app's current work is on **experiment/jarvis-integrated-prototype-v1**; main is intentionally not used for this setup. Clone that branch:

~~~sh
git clone --branch experiment/jarvis-integrated-prototype-v1 --single-branch https://github.com/maksrugu123-sys/jarvis-ans100-alpha.git
cd jarvis-ans100-alpha
~~~

## Start the Android companion bridge

Run:

~~~sh
python3 -m tools.jarvis_app --port 8765 --platform android
~~~

On first start, the console prints a one-time **ANDROID / PWA ACCESS TOKEN**. Copy it immediately. In the JARVIS app open **Система → Необязательное расширение Termux**, paste the token, tap **Сохранить токен**, then tap **Подключить расширение**. The app stores the token encrypted with Android Keystore; the service stores its SHA-256 digest. The token authorizes local bridge access, so never publish or forward it.

Keep Termux and the running Python process open while using the extension. To stop the service, press **Ctrl+C** in Termux. The ordinary Android chat remains available after the bridge stops.

### If the one-time token is lost

Stop the service with **Ctrl+C**, then run:

~~~sh
python3 -m tools.jarvis_app --port 8765 --platform android --rotate-token
~~~

This replaces the previous token. Save the newly printed value in the Android app.

### Update the checked-out source later

Stop the service, then run:

~~~sh
cd ~/jarvis-ans100-alpha
git pull --ff-only origin experiment/jarvis-integrated-prototype-v1
~~~

Start the bridge again with the command above.

## Browser links on Android

Chat messages now render HTTP(S) addresses as tappable links. Tapping an address opens it in the phone's external browser. This is link display only; it does not mean that Android already has an automatic web-search engine.

The repository currently contains a separate visible Playwright/Chromium research browser for the desktop/PWA runtime. It searches and reads pages in a visible PC browser window. The current Android app does not yet call that desktop browser or perform the same automatic source search on the phone.

The requested mobile behavior is recorded as the target for the Android web-search integration: JARVIS should return concise search findings with clickable source links, without opening a large browser window inside the app or silently reading full pages by default. The user should tap a source link to open it in the phone's browser; full-page reading should require a separate explicit request. Until that integration is implemented, do not describe Android as having automatic browser search.

## PC behavior

Termux is an Android environment and is not required on a PC. On Windows, Linux or macOS use the Python JARVIS desktop/PWA runtime directly. The desktop visible-browser research feature requires a graphical session, Playwright and Chromium installed separately.

Android and PC currently have separate local data directories. Cross-device transfer is an explicit encrypted JWS/JWB export/import workflow, not automatic background synchronization.

## Verification status

This change updates Android source and documentation only. No Gradle build, APK, unit test, instrumentation test, Termux installation, GitHub CLI sign-in, bridge launch, Android device test or PC browser test was run. The source update is not proof of runtime success.
