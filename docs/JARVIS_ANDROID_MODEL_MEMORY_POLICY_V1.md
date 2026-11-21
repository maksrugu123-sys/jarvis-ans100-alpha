# JARVIS Android model memory policy V1

## Runtime lifecycle

- The Qwen3-0.6B INT4 model file remains in the app-private model directory; closing the LiteRT-LM engine does not delete the downloaded model.
- The native LiteRT-LM engine is initialized lazily on the conversation worker when a model response is needed.
- The engine stays loaded while the app is active so consecutive turns do not pay model-initialization cost.
- When Android reports `TRIM_MEMORY_RUNNING_CRITICAL` or a stronger trim level, or calls `onLowMemory()`, the ViewModel queues an engine close on the same single-thread executor used for inference and model-file operations.
- When the engine is closed, LiteRT-LM can release its native runtime resources and KV cache. The next model-backed message initializes it again.
- No engine close is performed concurrently with an active generation, download, import or model deletion.

## Memory guard

- Model initialization is blocked when Android reports `lowMemory` or less than 768 MiB available system RAM.
- This is a conservative admission guard, not a guarantee that the device cannot run out of memory.
- The guard checks system-wide available RAM; it is not a measurement of JARVIS process PSS/RSS.
- A process footprint around 200 MiB while the 0.6B model is loaded is not by itself proof of a leak. Actual memory use must be measured on target devices before changing model/runtime settings.

## Context and memory are different units

- `EngineConfig.maxNumTokens = 1280` is the model's token-context/KV-cache capacity setting, not a request for 1,280 MiB of RAM.
- `PromptContextBudgeter` reserves output and safety space and packs bounded history, approved memory, neural summaries and the current prompt into that context.
- Do not reduce `maxNumTokens` without changing the prompt budget and running multi-turn tests; the prompt estimator is approximate and Russian tokenization can differ from its character-based estimate.
- The 768 MiB admission guard and the 1,280-token context setting must remain separately named and documented.

## Verification limits

- Unit tests can verify lifecycle routing and prompt-budget arithmetic, but they cannot establish native memory savings.
- Measure process PSS/RSS, Java heap, native heap and available system RAM on a real Android device before and after engine release.
- Do not claim a specific RAM reduction until a device measurement confirms it.
