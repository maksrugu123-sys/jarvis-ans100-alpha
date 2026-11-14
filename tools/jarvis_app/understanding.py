"""Bounded deterministic text analysis owned by JARVIS Core, before language generation."""
from __future__ import annotations

import re

MAX_INPUT_CHARS = 900
MAX_TOKENS = 128
_TOKEN = re.compile(r"[A-Za-zА-Яа-яЁё0-9]+", re.UNICODE)
_ENTITY = re.compile(r"\\b[A-ZА-ЯЁ][\\w-]{1,47}\\b", re.UNICODE)
_STOP = frozenset({"это", "для", "или", "что", "чтобы", "мне", "могу", "можно", "когда", "где", "почему", "который", "которая", "которые", "есть", "the", "and", "for", "with", "that", "this"})
_QUESTIONS = frozenset({"кто", "что", "где", "когда", "почему", "зачем", "как", "сколько", "можно", "ли", "what", "who", "where", "when", "why", "how"})
_NEGATIONS = frozenset({"не", "нет", "ни", "без", "нельзя", "never", "not", "no", "without"})
_GREETINGS = frozenset({"привет", "здравствуй", "здравствуйте", "hello", "hi", "hey"})
_THANKS = ("спасибо", "благодарю", "thanks", "thank you")
_FAREWELLS = ("пока", "до свидания", "увидимся", "bye", "goodbye")
_COMPARISONS = ("сравни", "сравнение", "что лучше", "лучше чем", "разница", "отличие", "compare", "versus", " vs ")
_EXPLANATIONS = ("объясни", "расскажи", "почему", "зачем", "что означает", "что такое", "explain", "describe")
_ACTIONS = ("сделай", "создай", "найди", "исправь", "проверь", "напиши", "добавь", "удали", "открой", "помоги", "надо", "нужно", "хочу", "можешь", "сделать", "implement", "build", "fix", "find", "create")
_CORRECTIONS = ("я имел в виду", "не так", "исправление", "точнее", "я ошибся", "i meant", "correction")
_CONTEXT = ("это", "этот", "эта", "эти", "там", "тогда", "продолжай", "дальше", "как раньше", "предыдущ", "в прошлый раз", "it", "that", "those", "continue", "previous")


def analyze_text(value: str) -> dict[str, object]:
    """Return bounded heuristic evidence; this is not a semantic model."""
    if not isinstance(value, str):
        value = ""
    text = value.strip().replace("\\x00", "")[:MAX_INPUT_CHARS]
    normalized = text.casefold()
    tokens = [token.casefold() for token in _TOKEN.findall(normalized)[:MAX_TOKENS]]
    key_terms = list(dict.fromkeys(
        token for token in tokens if len(token) >= 3 and token not in _STOP
    ))[:10]
    has_question = "?" in text or any(token in _QUESTIONS for token in tokens)
    refers_back = any(marker in normalized for marker in _CONTEXT)
    negation = any(token in _NEGATIONS for token in tokens)
    first = tokens[0] if tokens else ""
    greeting = first in _GREETINGS
    thanks = any(marker in normalized for marker in _THANKS)
    farewell = any(marker in normalized for marker in _FAREWELLS)
    comparison = any(marker in normalized for marker in _COMPARISONS)
    explanation = any(marker in normalized for marker in _EXPLANATIONS)
    action = any(marker in normalized for marker in _ACTIONS)
    correction = any(marker in normalized for marker in _CORRECTIONS)

    if correction:
        intent, confidence, task = "correction", 0.82, "revise_previous_result"
        evidence = ["corrective phrase"]
    elif farewell:
        intent, confidence, task = "farewell", 0.90, "social_response"
        evidence = ["farewell marker"]
    elif thanks:
        intent, confidence, task = "thanks", 0.88, "social_response"
        evidence = ["gratitude marker"]
    elif greeting:
        intent, confidence, task = "greeting", 0.88, "social_response"
        evidence = ["greeting marker"]
    elif comparison:
        intent, confidence, task = "comparison", 0.76, "compare_options"
        evidence = ["comparison marker"]
    elif action:
        intent, confidence, task = "action_request", 0.68, "plan_or_explain_requested_action"
        evidence = ["action/request marker"]
    elif has_question:
        intent, confidence, task = "question", 0.70, "answer_question"
        evidence = ["question marker"]
    elif explanation:
        intent, confidence, task = "explanation", 0.72, "explain_concept"
        evidence = ["explanation marker"]
    elif refers_back:
        intent, confidence, task = "continuation", 0.64, "continue_context"
        evidence = ["context reference"]
    else:
        intent, confidence, task = "statement_or_unknown", 0.30, "interpret_and_respond"
        evidence = ["no strong intent marker"]

    if has_question:
        evidence.append("question form")
    if negation:
        evidence.append("negation detected")
    if refers_back:
        evidence.append("previous-context reference")

    entities = list(dict.fromkeys(
        match.group(0) for match in _ENTITY.finditer(text)
        if match.group(0).casefold() not in _GREETINGS
    ))[:5]
    language = "ru_or_cyrillic" if re.search(r"[\\u0400-\\u04ff]", text) else (
        "en_or_latin" if re.search(r"[A-Za-z]", text) else "unknown"
    )
    return {
        "schema": "JARVIS_UNDERSTANDING_V1",
        "language": language,
        "intent": intent,
        "intent_confidence": confidence,
        "task_kind": task,
        "entities": entities,
        "key_terms": key_terms,
        "contains_negation": negation,
        "refers_to_previous_context": refers_back,
        "asks_for_explanation": explanation,
        "evidence": evidence[:5],
        "scope": "bounded_heuristic_pre_generation_analysis",
    }
