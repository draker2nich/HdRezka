# -*- coding: utf-8 -*-
"""
session_pool.py — единый общий requests.Session на весь плагин.

Зачем: раньше HdRezkaApi, HdRezkaSearch, SearchResult и ResultsList создавали
КАЖДЫЙ свою сессию. На STi7111 это означало новый TLS-handshake (сотни мс CPU)
на каждом шаге навигации поиск -> info -> seasons -> stream. Один общий пул с
keep-alive переиспользует соединение к зеркалу.
"""
import requests

from .types import default_headers, default_cookies

_session = None


def get_session():
	global _session
	if _session is None:
		s = requests.Session()
		s.headers.update(default_headers)
		s.cookies.update(default_cookies)
		try:
			s.verify = False
			requests.packages.urllib3.disable_warnings()
		except Exception:
			pass
		# keep-alive пул + один ретрай на случай обрыва соединения
		try:
			from requests.adapters import HTTPAdapter
			ad = HTTPAdapter(pool_connections=2, pool_maxsize=4, max_retries=1)
			s.mount("https://", ad)
			s.mount("http://", ad)
		except Exception:
			pass
		_session = s
	return _session


def reset_session():
	"""Сброс пула (например при смене зеркала)."""
	global _session
	if _session is not None:
		try:
			_session.close()
		except Exception:
			pass
	_session = None
