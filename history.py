# -*- coding: utf-8 -*-
"""
history.py — "Продолжить просмотр": помним последний выбранный перевод/
сезон/эпизод для каждого фильма-сериала (не точную секунду позиции —
это требовало бы отдельного трекинга внутри плеера, — а именно "докуда
долистали" по сериям, что для навигации уже большая экономия кликов).
"""
import json
import os
import threading
import time

HISTORY_PATH = "/etc/enigma2/hdrezka_history.json"
MAX_ITEMS = 100

_lock = threading.Lock()


def _canon(url):
	return url.split(".html")[0]


def _load():
	try:
		with open(HISTORY_PATH, "r") as f:
			data = json.load(f)
			if isinstance(data, dict):
				return data
	except Exception:
		pass
	return {}


def _save(data):
	try:
		# ограничиваем размер файла -- не даём ему расти бесконечно
		if len(data) > MAX_ITEMS:
			ordered = sorted(data.items(), key=lambda kv: kv[1].get("updated", 0), reverse=True)
			data = dict(ordered[:MAX_ITEMS])
		tmp_path = HISTORY_PATH + ".tmp"
		with open(tmp_path, "w") as f:
			json.dump(data, f)
		os.rename(tmp_path, HISTORY_PATH)
	except Exception:
		pass


def get_progress(url):
	if not url:
		return None
	with _lock:
		data = _load()
	return data.get(_canon(url))


def set_progress(url, title, translator_id=None, translator_name=None,
                  season=None, episode=None):
	with _lock:
		data = _load()
		data[_canon(url)] = {
			"title": title,
			"url": url,
			"translator_id": translator_id,
			"translator_name": translator_name,
			"season": season,
			"episode": episode,
			"updated": time.time(),
		}
		_save(data)


def remove_progress(url):
	with _lock:
		data = _load()
		data.pop(_canon(url), None)
		_save(data)


def list_history():
	"""Список для экрана "Продолжить просмотр", новые сверху."""
	with _lock:
		data = _load()
	items = sorted(data.items(), key=lambda kv: kv[1].get("updated", 0), reverse=True)
	result = []
	for k, v in items:
		title = v.get("title", "")
		season = v.get("season")
		episode = v.get("episode")
		if season and episode:
			suffix = " (S%sE%s)" % (season, episode)
			if isinstance(title, unicode):
				title = title + suffix.decode("ascii")
			else:
				title = title + suffix
		result.append({"title": title, "url": v.get("url", k), "image": None})
	return result
