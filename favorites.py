# -*- coding: utf-8 -*-
"""
favorites.py — простое персистентное хранилище "Избранного".

Файл лежит в /etc/enigma2/ — это стандартное писабельное место для
настроек сторонних плагинов на большинстве образов Enigma2 (в отличие от
папки самого плагина, которая на некоторых сборках может быть на
squashfs/только для чтения).
"""
import json
import os
import threading
import time

FAVORITES_PATH = "/etc/enigma2/hdrezka_favorites.json"

_lock = threading.Lock()


def _canon(url):
	return url.split(".html")[0]


def _load():
	try:
		with open(FAVORITES_PATH, "r") as f:
			data = json.load(f)
			if isinstance(data, dict):
				return data
	except Exception:
		pass
	return {}


def _save(data):
	try:
		tmp_path = FAVORITES_PATH + ".tmp"
		with open(tmp_path, "w") as f:
			json.dump(data, f)
		# атомарная замена -- чтобы обрыв питания/краш посреди записи не
		# оставил битый json, из-за которого потом ничего бы не читалось.
		os.rename(tmp_path, FAVORITES_PATH)
	except Exception:
		pass


def list_favorites():
	"""Возвращает список словарей title/url/image, новые сверху."""
	with _lock:
		data = _load()
	items = sorted(data.items(), key=lambda kv: kv[1].get("added", 0), reverse=True)
	return [{"title": v.get("title", ""), "url": v.get("url", k), "image": None} for k, v in items]


def is_favorite(url):
	if not url:
		return False
	with _lock:
		data = _load()
	return _canon(url) in data


def add_favorite(url, title):
	with _lock:
		data = _load()
		data[_canon(url)] = {"title": title, "url": url, "added": time.time()}
		_save(data)


def remove_favorite(url):
	with _lock:
		data = _load()
		data.pop(_canon(url), None)
		_save(data)


def toggle_favorite(url, title):
	"""Возвращает True, если добавили, False -- если убрали."""
	if is_favorite(url):
		remove_favorite(url)
		return False
	add_favorite(url, title)
	return True
