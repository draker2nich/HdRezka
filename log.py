# -*- coding: utf-8 -*-
"""
log.py — минимальный файловый логгер.

Раньше ошибки (например, неудачный bind веб-сервера или сетевые сбои)
уходили только в print(), который на большинстве образов никуда не
выводится в обычном режиме — пользователь видел просто "не работает"
без единой зацепки для диагностики.
"""
import time
import traceback

LOG_PATH = "/tmp/hdrezka_plugin.log"


def exc_text(e):
	"""Безопасно превращает исключение в utf-8 str (bytes).

	В py2 голый str(e) падает с UnicodeEncodeError, если у исключения
	unicode-сообщение с кириллицей (например, из requests), а unicode(e)
	падает с UnicodeDecodeError, если сообщение — utf-8 байты. Здесь
	обрабатываются оба случая.
	"""
	try:
		msg = unicode(e)
	except UnicodeDecodeError:
		msg = str(e).decode("utf-8", "replace")
	except Exception:
		try:
			msg = repr(e).decode("utf-8", "replace")
		except Exception:
			msg = u"unknown error"
	return msg.encode("utf-8")


def log(msg):
	try:
		with open(LOG_PATH, "a") as f:
			f.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
	except Exception:
		pass


def log_exception(context=""):
	try:
		with open(LOG_PATH, "a") as f:
			f.write("[%s] EXCEPTION %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), context))
			f.write(traceback.format_exc())
			f.write("\n")
	except Exception:
		pass
