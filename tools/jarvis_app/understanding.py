"""Bounded deterministic text analysis owned by JARVIS Core, before language generation."""
from __future__ import annotations

import re
from collections.abc import Mapping, Sequence

MAX_INPUT_CHARS = 750
MAX_TOKENS = 128
_TOKEN = re.compile(r"[^\W_]+", re.UNICODE)
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
    parts = tuple(phrase.lower().split())
    if not parts:
        return False
    width = len(parts)
    return any(tuple(tokens[index:index + width]) == parts for index in range(len(tokens) - width + 1))


def _contains_any(tokens: Sequence[str], phrases: Sequence[str]) -> bool:
    return any(_contains_phrase(tokens, phrase) for phrase in phrases)


_ACTION_TERMS = frozenset({
    "сделай", "создай", "найди", "исправь", "проверь", "напиши", "добавь",
    "удали", "открой", "помоги", "надо", "нужно", "хочу", "можешь",
    "сделать", "implement", "build", "fix", "find", "create",
})


def _build_reasoning_plan(
    intent: str,
    key_terms: list[str],
    entities: list[str],
    refers_back: bool,
    has_recent_context: bool,
) -> dict[str, object]:
    """Create a bounded template plan; this does not execute or prove a task."""
    templates = {
        "question": [
            "Определить точный вопрос и его предмет",
            "Проверить доступный контекст и подтверждённые сведения",
            "Отделить факты от предположений",
            "Сформулировать ответ или запросить недостающее уточнение",
        ],
        "explanation": [
            "Выделить понятие или механизм, который нужно объяснить",
            "Разложить объяснение на последовательные части",
            "Подобрать конкретный пример",
            "Указать ограничения и оставшуюся неопределённость",
        ],
        "comparison": [
            "Определить сравниваемые варианты",
            "Выбрать общие критерии сравнения",
            "Сопоставить только подтверждённые характеристики",
            "Показать компромиссы и условия выбора",
        ],
        "action_request": [
            "Сформулировать требуемый результат",
            "Определить цель, объект работы и доступные исходные данные",
            "Разбить работу на проверяемые и обратимые этапы",
            "Проверить ограничения, риски и критерии готовности",
            "Не выполнять внешние действия без отдельного разрешения",
        ],
        "continuation": [
            "Разрешить ссылку на предыдущий диалог по недавней истории",
            "Сохранить ранее заданные ограничения и цель",
            "Определить ближайший незавершённый шаг",
            "Продолжить либо запросить уточнение при неоднозначной ссылке",
        ],
        "correction": [
            "Выделить исправленное условие или факт",
            "Заменить конфликтующую прежнюю интерпретацию",
            "Повторно оценить вывод или план с учётом исправления",
        ],
        "greeting": ["Распознать приветствие и ответить в рамках текущего диалога"],
        "thanks": ["Распознать благодарность и кратко ответить"],
        "farewell": ["Распознать завершение диалога и ответить"],
        "statement_or_unknown": [
            "Выделить значимые слова и возможную цель сообщения",
            "Проверить, достаточно ли контекста для надёжной интерпретации",
            "Задать один конкретный уточняющий вопрос, если цель неясна",
        ],
    }
    non_action_terms = [term for term in key_terms if term not in _ACTION_TERMS]
    unresolved_context = refers_back and not has_recent_context
    if intent == "statement_or_unknown":
        clarification = True
    elif unresolved_context:
        clarification = True
    elif intent == "question":
        clarification = not bool(key_terms or entities)
    elif intent == "action_request":
        clarification = not bool(non_action_terms or entities)
    elif intent == "comparison":
        clarification = len(non_action_terms) + len(entities) < 2
    else:
        clarification = False

    clarification_questions = {
        "question": "Какой именно объект или ситуацию вы имеете в виду?",
        "action_request": "Что именно нужно изменить, создать или проверить?",
        "comparison": "Какие именно варианты нужно сравнить?",
        "statement_or_unknown": "Какой результат вы хотите получить?",
    }
    return {
        "schema": "JARVIS_REASONING_PLAN_V1",
        "goal": intent,
        "focus_terms": non_action_terms[:6],
        "steps": templates[intent][:5],
        "requires_clarification": clarification,
        "clarification_question": (
            "К какому предыдущему сообщению относится «это»? Напомните контекст."
            if unresolved_context else clarification_questions.get(intent, "")
        ),
        "context_available": bool(refers_back and has_recent_context),
        "execution_authorized": False,
        "scope": "bounded_template_plan_only",
    }


def analyze_text(
    value: str,
    history: Sequence[Mapping[str, object]] = (),
) -> dict[str, object]:
    """Return the JARVIS_UNDERSTANDING_V1 record; this is not a semantic model."""
    if not isinstance(value, str):
        value = ""
    text = value.strip().replace("\x00", "")[:MAX_INPUT_CHARS]
    tokens = [token.lower() for token in _TOKEN.findall(text)[:MAX_TOKENS]]
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
        evidence = ["unknown_intent_marker"]

    if has_question:
        evidence.append("question_form")
    if negation:
        evidence.append("negation_marker")
    if refers_back:
        evidence.append("previous_context_reference")

    entities = list(dict.fromkeys(
        match.group(0) for match in _ENTITY.finditer(text)
        if match.group(0).lower() not in _ENTITY_STOPWORDS
    ))[:5]
    has_recent_context = any(
        isinstance(item, Mapping)
        and item.get("role") == "user"
        and isinstance(item.get("content"), str)
        and bool(item.get("content", "").strip())
        for item in (history[-4:] if isinstance(history, (list, tuple)) else ())
    )
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
        "has_recent_user_context": has_recent_context,
        "reasoning_plan": _build_reasoning_plan(intent, key_terms, entities, refers_back, has_recent_context),
        "scope": "bounded_heuristic_pre_generation_analysis",
    }
