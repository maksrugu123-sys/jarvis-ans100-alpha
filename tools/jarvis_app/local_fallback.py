"""Deterministic no-model fallback for the experimental JARVIS console.

This is a deliberately small command/greeting responder, not an LLM. It keeps
chat usable before a local model is imported and reports the active subsystems
without pretending to provide open-ended language understanding.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence


class LocalFallbackResponseEngine:
    """Offline fallback matching the LocalResponseEngine/LiteRT adapter contract."""

    runtime_available = True
    language_model_configured = False
    backend_label = "JARVIS базовый офлайн-режим · без языковой модели"

    def respond(
        self,
        prompt: str,
        history: list[dict[str, str]],
        *,
        memory_context: Sequence[Mapping[str, str]] = (),
        signal_context: Mapping[str, object] | None = None,
        reasoning_context: Mapping[str, object] | None = None,
        external_context: Mapping[str, object] | None = None,
        dialog_neuron_context: Mapping[str, object] | None = None,
    ) -> str:
        clean = prompt.strip()
        normalized = clean.casefold().strip(" .!?;,:")
        if not clean or len(clean) > 900 or "\x00" in clean:
            raise ValueError("Сообщение должно содержать от 1 до 900 символов.")

        if normalized in {"/help", "помощь", "что ты умеешь", "что ты можешь"}:
            return (
                "Я — экспериментальный JARVIS. Сейчас доступны локальный диалоговый журнал, "
                "подтверждаемая память, ANS-100 сигнальная обработка, экспериментальные "
                "диалоговые нейроны, символьное планирование и управление сессиями/задачами. "
                "На ПК также предусмотрен видимый браузер для чтения страниц. "
                "Без импортированной языковой модели я отвечаю только на базовые команды. "
                "Внешние действия и автоматическое выполнение задач отключены."
            )

        if normalized == "/status":
            signal = signal_context or {}
            neurons = dialog_neuron_context or {}
            memory_count = len(memory_context)
            parts = [
                "JARVIS работает в базовом локальном режиме; полноценная языковая модель не подключена.",
                f"Подтверждённых заметок, переданных в этот ход: {memory_count}.",
            ]
            if signal:
                parts.append(
                    "ANS-100: "
                    f"событий {signal.get('processed_events', 0)}, "
                    f"импульсов {signal.get('spike_count', 0)}, "
                    f"нейронов в сигнальном контуре {signal.get('neuron_count', 'не указано')}."
                )
            if neurons:
                parts.append(
                    "Диалоговый классификатор: "
                    f"{neurons.get('neuron_count', 10_000)} адресуемых признаковых нейронов, "
                    f"обучающих примеров {neurons.get('trained_examples', 0)}; "
                    "это классификация структуры текста, а не понимание смысла."
                )
            parts.append(
                "Планирование доступно отдельно и не исполняет шаги. "
                "Сетевые и системные действия выключены."
            )
            return " ".join(parts)

        if normalized in {"привет", "здравствуй", "здравствуйте", "доброе утро",
                          "добрый день", "добрый вечер", "hello", "hi"}:
            return (
                "Привет! Я JARVIS. Базовый локальный режим активен, история сохраняется "
                "в локальном рабочем пространстве. Для содержательного свободного диалога "
                "подключите поддерживаемую локальную языковую модель."
            )

        if normalized in {"спасибо", "благодарю", "thanks", "thank you"}:
            return "Пожалуйста. Я сохранил этот ход в локальной истории."

        if normalized in {"пока", "до свидания", "увидимся", "bye", "goodbye"}:
            return "До связи. Локальная история диалога останется на этом устройстве."

        if reasoning_context is not None:
            steps = reasoning_context.get("steps", [])
            return (
                "Я получил структурированный план из "
                f"{len(steps) if isinstance(steps, list) else 0} шагов. "
                "Это только символьный предварительный план: ни один шаг не запускался. "
                "Для содержательного объяснения подключите локальную языковую модель."
            )

        if external_context is not None:
            title = external_context.get("title", "страница")
            return (
                f"Страница «{title}» передана в контекст JARVIS, но базовый режим не умеет "
                "суммировать произвольный текст. Подключите локальную языковую модель; "
                "содержимое страницы останется недоверенным источником."
            )

        return (
            "Я получил сообщение и передал его через локальное ядро JARVIS, но базовый "
            "ответчик не умеет формировать свободные содержательные ответы. Подключите "
            "локальную языковую модель для полноценного диалога. Никакие внешние действия "
            "по этому сообщению не выполнялись."
        )

    def close(self) -> None:
        return None
