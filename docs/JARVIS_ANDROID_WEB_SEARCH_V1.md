# JARVIS Android Web Search V1

## User experience

The Android chat recognizes direct and polite search commands such as:
- "Найди в интернете ..."
- "Поищи ..."
- "Найди информацию ..."
- "Ты можешь найти в интернете ..."
- "Можешь ли ты поискать в интернете ..."
- "Найди ... в интернете"
- "/search ..."

A question such as "Ты можешь искать в интернете?" is classified separately as a capability question and answered from the app's actual search integration. It does not send an empty search query. The reply explains that JARVIS can show search titles, snippets and links, but does not automatically open or verify the result pages.

JARVIS performs a read-only request for the Bing HTML search-results page and displays up to five result titles, short search snippets and clickable HTTPS source URLs in the chat. The app does not open a browser window during search and does not navigate to, fetch, or read the result pages. The user chooses whether to open each source by tapping its link; the existing Android link renderer opens it in the external phone browser.

## Ranking and source comparison

- The parser collects a bounded candidate set (up to 30 unique canonical URLs) instead of stopping at the first five.
- Exact query-token matches in titles are weighted more strongly than matches in snippets. This is a deterministic lexical heuristic; it does not understand synonyms, morphology, context or factual meaning.
- Exact URL duplicates are removed. Canonical comparison ignores a leading `www.`, fragments and common tracking parameters (`utm_*`, `gclid`, `fbclid`, `msclkid`).
- The first selection pass prefers one result per normalized hostname; remaining places are filled by relevance order when fewer than five hosts are available.
- Each result shows its hostname and a short explanation of where query words matched.

This ranking is not a website reputation score. It does not prove that a source is authoritative, current, independent or factually correct. Different hostnames can belong to the same organization, and subdomains may be counted separately.

## Implementation boundary

- Native Android Kotlin service; Termux is not required.
- HTTPS GET to the fixed Bing search endpoint only.
- Connection timeout 8 seconds, read timeout 12 seconds, response body cap 1.5 MB.
- Search query cap 240 characters; at most 30 parsed candidates and five displayed results.
- Title cap 180 characters, snippet cap 260 characters, URL cap 2,048 characters.
- Only HTTPS source URLs with a hostname and standard port are shown; Bing-internal, credential-bearing, local-looking and non-HTTPS URLs are discarded.
- The result titles/snippets are untrusted search-provider data. They are displayed as plain text; no HTML is rendered and no page-provided instruction is executed.
- Search results are saved as the assistant's response in the normal local conversation history. JARVIS Core still analyzes the original user prompt before committing that response.
- Ordinary messages continue through the existing local response engine. Search commands with a non-empty extracted query trigger the web-search path; capability questions are answered directly without calling the provider.

## Limitations

This implementation extracts the search provider's current HTML result markup; provider markup, anti-bot responses, locale behavior or rate limits can change. It is not a guaranteed search API and does not provide independent multi-provider verification, source reputation checks, semantic relevance, page reading, image search, PDF extraction, or automatic browser control. The comparison is limited to visible search titles and snippets; pages are not fetched or analyzed. Provider failures should be reported rather than silently replaced with invented results.

## Verification

Parser, intent, ranking, canonical-deduplication and formatting tests are authored in AndroidWebSearchTest.kt, but have not been run. No Gradle build, APK build, Android device test, live Bing request or external browser launch was performed. This is source-level implementation only until those checks are explicitly authorized and completed.
