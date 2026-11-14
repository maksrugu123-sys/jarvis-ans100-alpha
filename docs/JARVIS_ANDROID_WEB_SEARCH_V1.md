# JARVIS Android Web Search V1

## User experience

The Android chat recognizes explicit requests such as:
- "Найди в интернете ..."
- "Поищи ..."
- "Найди информацию ..."
- "/search ..."

JARVIS performs a read-only request for the Bing HTML search-results page and displays up to five result titles, short search snippets and clickable HTTPS source URLs in the chat. The app does not open a browser window during search and does not navigate to, fetch, or read the result pages. The user chooses whether to open each source by tapping its link; the existing Android link renderer opens it in the external phone browser.

## Implementation boundary

- Native Android Kotlin service; Termux is not required.
- HTTPS GET to the fixed Bing search endpoint only.
- Connection timeout 8 seconds, read timeout 12 seconds, response body cap 1.5 MB.
- Search query cap 240 characters.
- At most five results; title cap 180 characters, snippet cap 260 characters, URL cap 2,048 characters.
- Only HTTPS source URLs with a hostname and standard port are shown; Bing-internal, credential-bearing, local-looking and non-HTTPS URLs are discarded.
- The result titles/snippets are untrusted search-provider data. They are displayed as plain text; no HTML is rendered and no page-provided instruction is executed.
- Search results are saved as the assistant's response in the normal local conversation history. JARVIS Core still analyzes the original user prompt before committing that response.
- Ordinary messages continue through the existing local response engine. Only explicit search phrases trigger the web-search path.

## Limitations

This first version extracts the search provider's current HTML result markup; provider markup, anti-bot responses, locale behavior or rate limits can change. It is not a guaranteed search API and does not provide evidence validation, source ranking beyond Bing's order, multi-source synthesis, page reading, image search, PDF extraction, or automatic browser control. It should report provider failures rather than silently inventing results.

## Verification

Parser, query-intent and formatting tests are authored in AndroidWebSearchTest.kt, but have not been run. No Gradle build, APK build, Android device test, live Bing request or external browser launch was performed. This is source-level implementation only until those checks are explicitly authorized and completed.
