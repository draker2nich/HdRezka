# -*- coding: utf-8 -*-
"""
settings.py — постоянные настройки плагина через стандартный config.plugins
Enigma2 (хранится в /etc/enigma2/settings, переживает перезагрузку).

Импортировать ОТСЮДА, а не создавать ConfigSubsection в нескольких местах —
повторная регистрация одной и той же ветки config.plugins.hdrezka в разных
модулях на некоторых образах приводит к тому, что значения "теряются".
"""
from Components.config import config, ConfigSubsection, ConfigYesNo, ConfigText, ConfigInteger

if not hasattr(config.plugins, "hdrezka"):
	config.plugins.hdrezka = ConfigSubsection()

	# Если включено — экран выбора качества не показывается, сразу
	# воспроизводится качество, ближайшее (снизу) к target_quality.
	config.plugins.hdrezka.auto_quality_enabled = ConfigYesNo(default=True)

	# Целевое качество в виде числа (например "720"). Логика подбора:
	# берём максимальное доступное качество <= target; если таких нет
	# вообще (например, в наличии только 1080/2160) — берём минимальное
	# из доступных, а не прыгаем вверх.
	config.plugins.hdrezka.target_quality = ConfigText(default="720", fixed_size=False)

	# Часть названия озвучки (например "Дубляж" или "HDrezka Studio").
	# Если не пусто — такой перевод поднимается в начало списка переводов
	# при открытии карточки фильма, поверх стандартного приоритета по ID.
	config.plugins.hdrezka.preferred_translator = ConfigText(default="", fixed_size=False)

	# Принудительный домен — если задан, get_origin() используется он,
	# без автоопределения по списку зеркал. Полезно, если все зеркала из
	# списка вдруг недоступны, а у пользователя есть свежий рабочий адрес.
	config.plugins.hdrezka.force_domain = ConfigText(default="", fixed_size=False)

	# ── Агент для Android-пульта (этап 2) ────────────────────────────────
	# HTTP API + UDP discovery на agent_port. Телефон сам парсит HDRezka
	# и присылает готовый URL потока; приставка только играет.
	config.plugins.hdrezka.agent_enabled = ConfigYesNo(default=True)
	config.plugins.hdrezka.agent_port = ConfigInteger(default=8123, limits=(1024, 65535))
