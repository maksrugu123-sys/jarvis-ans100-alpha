# JARVIS Visible Browser Research V1

**Status:** Proposed desktop capability; no browser controller implementation is included in this change  
**Date:** 2026-11-14  
**Target:** PC / desktop JARVIS  
**Primary product rule:** the browser window must be visible to the user throughout an agent-driven research session

## 1. Goal

When the user asks JARVIS to research a topic, JARVIS should open a real browser window on the PC, show the searches and pages it visits, read relevant page content, and return a grounded summary. The user can watch, read the same pages, switch tabs, pause the agent, take over, or stop the session.

This is not a hidden scraper and not an invisible headless browser. The browser is part of the user-facing interaction.

## 2. User flow

1. The user gives JARVIS a research request.
2. JARVIS restates or displays the intended search query and starts a visible browser session.
3. The browser opens a search engine and visibly performs the search.
4. JARVIS opens relevant result pages in visible tabs and extracts accessible page text.
5. JARVIS compares the sources, separates facts from disagreement and uncertainty, and prepares a summary.
6. The answer includes source titles, direct links, retrieval times and evidence excerpts where useful.
7. The visible browser session remains available for the user to inspect after the summary; the user may close it.

The UI should expose session state (starting, searching, reading, paused, completed, cancelled or failed) and controls for **Pause**, **Resume**, **Take control** and **Stop**.

## 3. Visible browser contract

- Browser automation must run in a foreground, visible window. The browser automation library's headless mode is forbidden for this user-facing feature.
- Initial implementation should use a dedicated JARVIS research browser profile rather than silently attaching to the user's everyday browser profile. This reduces accidental access to unrelated tabs, passwords and personal cookies.
- The browser engine is behind a narrow `VisibleBrowserAdapter`; Playwright in headed mode is a candidate implementation, not yet a committed dependency.
- Search, navigation and reading are the default allowed operations. The exact query and current URL remain visible in the address bar.
- The user can interact with the window at any time. When manual control is detected or explicitly requested, automation pauses and waits for the user to resume it.
- Closing the research window cancels the active session and records that it was cancelled.
- If no supported browser is installed or the browser cannot be shown, the research action fails visibly. JARVIS must not silently switch to a hidden browser.
- The browser must not be used to bypass CAPTCHA, paywalls, access controls or site restrictions.

## 4. Read-only capability boundary

The initial capability is intentionally read-only:

- submit a search query to the configured search engine;
- open a result URL;
- navigate within the currently requested research flow;
- read page title, URL, accessible DOM text and relevant visible text;
- capture a screenshot for user-facing inspection when useful;
- return page metadata and extracted text to JARVIS Core.

The initial version must not submit arbitrary forms, send messages, publish content, purchase items, delete data, upload files, download or run executables, execute page scripts, modify accounts or change settings. If a site requires sign-in, the user may interact with the visible browser manually; JARVIS must not ask for or capture the user's password. Reading private/authenticated content requires a clear per-site user confirmation.

## 5. Untrusted page content and prompt-injection resistance

Every page, search result, ad, snippet, document and downloaded text is untrusted external data. Instructions found inside a page must never be treated as JARVIS system instructions, developer instructions, user approval or permission to perform actions.

Page content may be passed to the language model only as quoted/untrusted research material. It cannot grant browser capabilities, change the task, reveal secrets, approve an action or override the user's request. Browser tools should expose narrow typed operations rather than unrestricted shell or arbitrary JavaScript execution.

## 6. Evidence and research result contract

A research session should produce structured records:

- **ResearchSession:** stable ID, original user request, displayed search query, status, start/end time, cancellation state and configured limits.
- **ResearchSource:** stable source ID, title, canonical URL, host, retrieval timestamp, page-read status and bounded extracted text.
- **ResearchClaim:** concise claim, source IDs that support it, source IDs that disagree, and an explicit state such as supported, conflicting or unverified. Do not report heuristic scores as calibrated probabilities.
- **ResearchSummary:** answer, limitations, unresolved questions and source list.

Do not fabricate citations. A claim without a source should be marked as the assistant's inference or left unverified. Distinguish the page's publication date from the time JARVIS accessed it. Prefer primary and authoritative sources where appropriate, and compare sources rather than treating the first result as fact.

Initial resource limits should be explicit and configurable: maximum open tabs, pages read, characters extracted per page, total context, navigation duration and overall session duration. The first release should begin with conservative bounded defaults and measure actual PC resource use before increasing them.

## 7. Permissions, privacy and lifecycle

- A user's explicit request to research authorizes the bounded read-only search/open/read flow for that request.
- Any action that changes remote state requires a separate capability and confirmation for the exact action. Read permission never implies write permission.
- Keep the dedicated browser profile separate from Android app storage and unrelated browser profiles.
- Show the search query, active URL, and session state to the user.
- Do not record raw passwords, authentication forms or full private page contents in routine diagnostics.
- Provide a control to clear the dedicated research profile's cookies and browsing data.
- On timeout, browser crash, user stop, or unsupported content, fail safely and return the sources that were successfully read.
- Desktop browser research is a PC feature. The Android phone must continue to work independently; remote PC browser control from Android is a separate future feature requiring authenticated pairing and explicit user consent.

## 8. Proposed integration boundary

Desktop JARVIS should own a `VisibleBrowserResearchService` behind a `VisibleBrowserAdapter` interface. The service creates a bounded `ResearchSession`, calls only the read-only browser capabilities, and returns structured source records to JARVIS Core. The Core treats all extracted page text as untrusted context and produces the final answer with source provenance.

The browser controller must be hosted on the same PC as the visible browser. It must not expose an unauthenticated LAN or Internet control port. If a local IPC or loopback endpoint is required, it must be authenticated, bound to loopback only, and limited to the declared browser operations.

This proposal does not yet connect the capability to Task Loop, CQE or Permission & Safety Gate. Those integrations must preserve explicit capability checks, bounded execution, cancellation and exact-action authorization. No browser task should be reported as complete unless its page reads and evidence records have been confirmed.

## 9. Implementation and verification stages

1. **Visible shell:** launch a visible dedicated browser profile; test launch failure, window visibility, close and cancellation.
2. **Read-only research:** search, open, extract text, track tabs and produce source records.
3. **Evidence-aware answer:** link every factual claim to read sources, display disagreements and mark unsupported claims.
4. **User control:** reliable pause/resume/takeover/stop behavior; preserve the user's manual navigation without silently overriding it.
5. **Permission integration:** only after the read-only feature is verified, consider additional browser actions behind separate capability grants.

Required tests include: visible mode is mandatory; no headless fallback; navigation and page/text bounds; URL/title provenance; page prompt-injection text never changes permissions; manual takeover pauses automation; user stop and window close cancel; browser crash/timeout fails safely; form submission and other side effects are rejected; authenticated page access requires site-specific confirmation.

**Verification status:** proposed only. No browser automation source, dependency, browser profile, desktop build, integration test or PC runtime validation was added or run.
