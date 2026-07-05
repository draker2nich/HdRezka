# -*- coding: utf-8 -*-
"""
config.py — домен(ы) HDRezka.

Раньше тут был один жёстко зашитый HDREZKA_ORIGIN, и при блокировке
домена плагин просто переставал работать, пока кто-то не лез в код
руками. Теперь — список зеркал по приоритету и get_origin(), которая
сама находит первый отвечающий домен и кэширует результат в памяти на
время работы плагина.
"""
import threading

# Порядок важен — первый отвечающий и будет использован.
HDREZKA_MIRRORS = [
	"https://rezka-ua.tv",
	"https://hdrezka.ag",
	"https://rezka.ag",
]

# Оставлено для обратной совместимости со старым кодом/импортами —
# фактически используется только как fallback, если ни одно зеркало
# не ответило вообще ни на один запрос.
HDREZKA_ORIGIN = HDREZKA_MIRRORS[0]

_lock = threading.Lock()
_resolved = None


def _normalize_origin(value):
	"""БАГФИКС: если пользователь ввёл домен без схемы ("rezka.ag"),
	urlparse дальше по коду давал пустой netloc и всё неочевидно
	ломалось. Дописываем https:// сами."""
	value = (value or "").strip().rstrip("/")
	if value and "://" not in value:
		value = "https://" + value
	return value


def get_origin(force_recheck=False):
	"""Возвращает рабочий домен.

	Если в настройках задан принудительный домен
	(config.plugins.hdrezka.force_domain) — используется он без проверки
	остальных зеркал. Иначе перебираются HDREZKA_MIRRORS по порядку,
	первый ответивший — кэшируется в памяти.
	"""
	try:
		from .settings import config
		forced = _normalize_origin(config.plugins.hdrezka.force_domain.value)
		if forced:
			return forced
	except Exception:
		pass

	global _resolved
	if _resolved and not force_recheck:
		return _resolved

	with _lock:
		if _resolved and not force_recheck:
			return _resolved

		import requests
		for origin in HDREZKA_MIRRORS:
			try:
				r = requests.head(origin, timeout=5, allow_redirects=True, verify=False)
				if r.status_code < 500:
					_resolved = origin
					return origin
			except Exception:
				continue

		# Ничего не ответило — отдаём первое зеркало по умолчанию,
		# пусть конкретный запрос дальше упадёт с понятной сетевой
		# ошибкой, а не молча зависнет здесь.
		_resolved = HDREZKA_MIRRORS[0]
		return _resolved


def reset_origin_cache():
	global _resolved
	with _lock:
		_resolved = None


def peek_origin():
	"""Неблокирующая версия для UI (например, заголовок главного меню):
	НЕ делает сетевых запросов, просто отдаёт уже определённый ранее домен
	(или первый по списку, если автоопределение ещё не запускалось)."""
	try:
		from .settings import config
		forced = _normalize_origin(config.plugins.hdrezka.force_domain.value)
		if forced:
			return forced
	except Exception:
		pass
	return _resolved or HDREZKA_MIRRORS[0]
