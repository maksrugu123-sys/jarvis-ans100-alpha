"""Bounded deterministic text analysis owned by JARVIS Core, before language generation."""
from __future__ import annotations

import re
from collections.abc import Sequence

MAX_INPUT_CHARS = 750
MAX_TOKENS = 128
_TOKEN = re.compile(r"[A-Za-zА-Яа-яЁё0-9]+", re.UNICODE)
_ENTITY = re.compile(r"(?<![\w])[A-ZА-ЯЁ][\w-]{1,47}(?![\w])", re.UNICODE)
_STOP = frozenset({
    "это", "для", "или", "что", "чтобы", "мне", "могу", "можно",
    "когда", "где", "почему", "который", "которая", "которые", "есть",
    "the", "and", "for", "with", "that", "this",
})
_QUESTIONS = frozenset({
    "кто", "что", "где", "когда", "почему", "зачем", "как", "сколько",
    "можно", "ли", "what", "who", "where", "when", "why", "how",
})
_NEGATIONS = frozenset({"не", "нет", "ни", "без", "нельзя", "never", "not", "no", "without"})
_GREETINGS = frozenset({"привет", "здравствуй", "здравствуйте", "добрый", "hello", "hi", "hey"})
_THANKS = ("спасибо", "благодарю", "thanks", "thank you")
_FAREWELLS = ("пока", "до свидания", "увидимся", "bye", "goodbye")
_COMPARISONS = ("сравни", "сравнение", "что лучше", "лучше чем", "разница", "отличие", "compare", "versus", "vs")
# "почему" and "зачем" are questions, not commands to explain.
_EXPLANATIONS = ("объясни", "расскажи", "что означает", "что такое", "explain", "describe")
_ACTIONS = (
    "сделай", "создай", "найди", "исправь", "проверь", "напиши", "добавь",
    "удали", "открой", "помоги", "надо", "нужно", "хочу", "можешь",
    "сделать", "implement", "build", "fix", "find", "create",
)
_CORRECTIONS = ("я имел в виду", "не так", "исправление", "точнее", "я ошибся", "i meant", "correction")
_CONTEXT = (
    "это", "этот", "эта", "эти", "там", "тогда", "продолжай", "дальше",
    "предыдущ", "it", "that", "those", "continue", "previous",
)
_CONTEXT_PHRASES = ("как раньше", "в прошлый раз")
_ENTITY_STOPWORDS = frozenset({
    "привет", "здравствуй", "здравствуйте", "добрый", "спасибо", "благодарю",
    "пока", "можешь", "сделай", "создай", "найди", "исправь", "проверь",
    "напиши", "добавь", "удали", "открой", "помоги", "объясни", "расскажи",
})


def _contains_phrase(tokens: Sequence[str], phrase: str) -> bool:
    parts = tuple(phrase.casefold().split())
    if not parts:
        return False
    width = len(parts)
    return any(tuple(tokens[index:index + width]) == parts for index in range(len(tokens) - width + 1))


def _contains_any(tokens: Sequence[str], phrases: Sequence[str]) -> bool:
    return any(_contains_phrase(tokens, phrase) for phrase in phrases)


def analyze_text(value: str) -> dict[str, object]:
    """Return the JARVIS_UNDERSTANDING_V1 record; this is not a semantic model."""
    if not isinstance(value, str):
        value = ""
    text = value.strip().replace("\x00", "")[:MAX_INPUT_CHARS]
    tokens = [token.casefold() for token in _TOKEN.findall(text)[:MAX_TOKENS]]
    key_terms = list(dict.fromkeys(
        token for token in tokens if len(token) >= 3 and token not in _STOP
    ))[:10]
    has_question = "?" in text or any(token in _QUESTIONS for token in tokens)
    refers_back = any(
        marker in tokens or (len(marker) >= 4 and any(token.startswith(marker) for token in tokens))
        for marker in _CONTEXT
    ) or _contains_any(tokens, _CONTEXT_PHRASES)
    negation = any(token in _NEGATIONS for token in tokens)
    greeting = bool(tokens and tokens[0] in _GREETINGS)
    thanks = _contains_any(tokens, _THANKS)
    farewell = _contains_any(tokens, _FAREWELLS)
    comparison = _contains_any(tokens, _COMPARISONS)
    explanation = _contains_any(tokens, _EXPLANATIONS)
    action = _contains_any(tokens, _ACTIONS)
    correction = _contains_any(tokens, _CORRECTIONS)

    if correction:
        intent, confidence, task = "correction", 0.82, "revise_previous_result"
        evidence = ["correction_marker"]
    elif farewell:
        intent, confidence, task = "farewell", 0.90, "social_response"
        evidence = ["farewell_marker"]
    elif thanks:
        intent, confidence, task = "thanks", 0.88, "social_response"
        evidence = ["gratitude_marker"]
    elif greeting:
        intent, confidence, task = "greeting", 0.88, "social_response"
        evidence = ["greeting_marker"]
    elif comparison:
        intent, confidence, task = "comparison", 0.76, "compare_options"
        evidence = ["comparison_marker"]
    elif explanation:
        intent, confidence, task = "explanation", 0.72, "explain_concept"
        evidence = ["explanation_marker"]
    elif action:
        intent, confidence, task = "action_request", 0.68, "plan_or_explain_requested_action"
        evidence = ["action_marker"]
    elif has_question:
        intent, confidence, task = "question", 0.70, "answer_question"
        evidence = ["question_marker"]
    elif refers_back:
        intent, confidence, task = "continuation", 0.64, "continue_context"
        evidence = ["context_reference"]
    else:
        intent, confidence, task = "statement_or_unknown", 0.30, "interpret_and_respond"
        evidence = ["no strong intent marker"]

    if has_question:
        evidence.append("question_form")
    if negation:
        evidence.append("negation_marker")
    if refers_back:
        evidence.append("previous_context_reference")

    entities = list(dict.fromkeys(
        match.group(0) for match in _ENTITY.finditer(text)
        if match.group(0).casefold() not in _ENTITY_STOPWORDS
    ))[:5]
    language = "ru_or_cyrillic" if re.search(r"[\u0400-\u04ff]", text) else (
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
