# -*- coding: utf-8 -*-
"""
agent.py — HTTP-агент для Android-пульта (этап 2).

Архитектура: телефон парсит HDRezka сам и присылает сюда готовый URL
потока. Приставка НЕ парсит ничего — только играет и отчитывается о
состоянии. Агент встраивается в уже работающий Twisted-реактор Enigma2
(e2reactor), поэтому ни потоков, ни блокировок GUI: все обработчики
выполняются в основном цикле и обязаны быть быстрыми (здесь все
операции локальные — открытие сервиса, seek, чтение позиции).

Эндпоинты (JSON, спека в AGENT_API.md):
  GET  /ping     — версия/имя, проверка живости
  GET  /status   — что играет, позиция/длительность, пауза
  POST /play     — запустить поток {url, title, referer, ...}
  POST /control  — {cmd: pause|resume|toggle|stop|seek|seekto|volup|voldown|mute, value}
  POST /osd      — {text, timeout} тост на телевизоре

Discovery: UDP на том же порту, запрос "HDREZKA_DISCOVER" -> JSON-ответ.

Безопасность: v1 — только LAN (запросы не из частных диапазонов
отклоняются 403). Токен-авторизация — кандидат на v2, см. спеку.
"""
import json
import socket

from twisted.web import server, resource
from twisted.internet import reactor
from twisted.internet.protocol import DatagramProtocol

from enigma import eServiceReference

from .log import log, log_exception, exc_text

AGENT_VERSION = "1.0"
DEFAULT_PORT = 8123
PTS_PER_SEC = 90000  # позиции/длительности в Enigma2 — в тиках 90 кГц

# Максимально «свежая» справка о том, что мы сами запустили.
# Позиция/длительность в /status берутся не отсюда, а из живого сервиса.


class _AgentState(object):
	def __init__(self):
		self.session = None      # Enigma2 session (из sessionstart)
		self.player = None       # открытый MoviePlayer (или None)
		self.paused = False
		self.meta = {}           # title/season/episode/translator/url от /play
		self.tcp_port = None     # twisted Port objects — для restart
		self.udp_port = None


STATE = _AgentState()


# ── утилиты ──────────────────────────────────────────────────────────────────

def _b(s):
	"""unicode -> utf-8 bytes, всё остальное -> str."""
	if isinstance(s, unicode):
		return s.encode("utf-8")
	return str(s) if s is not None else ""


def _is_lan(ip):
	if not ip:
		return False
	if ip == "127.0.0.1" or ip.startswith("10.") or ip.startswith("192.168."):
		return True
	if ip.startswith("172."):
		try:
			second = int(ip.split(".")[1])
			return 16 <= second <= 31
		except Exception:
			return False
	return False


def _service():
	try:
		return STATE.session.nav.getCurrentService()
	except Exception:
		return None


def _seekable():
	s = _service()
	return s.seek() if s else None


def _pauseable():
	s = _service()
	return s.pause() if s else None


def _on_player_closed(*args):
	STATE.player = None
	STATE.paused = False


# ── команды ──────────────────────────────────────────────────────────────────

def _do_play(data):
	url = data.get("url")
	if not url:
		return {"ok": False, "error": "url is required"}, 400

	referer = data.get("referer") or ""
	ua = data.get("user_agent") or "Mozilla/5.0"
	extras = []
	if referer:
		extras.append("Referer=%s" % referer)
	extras.append("User-Agent=%s" % ua)
	play_url = "%s#%s" % (url, "&".join(extras))

	# 5002 = exteplayer3 (умеет http(s) заголовки через #...),
	# телефон может явно попросить gstreamer: {"engine": "gst"} -> 4097.
	service_type = 4097 if data.get("engine") == "gst" else 5002
	sref = eServiceReference(service_type, 0, str(_b(play_url)))
	sref.setName(_b(data.get("title") or "HDRezka"))

	STATE.meta = {
		"title": data.get("title") or "",
		"season": data.get("season"),
		"episode": data.get("episode"),
		"translator": data.get("translator") or "",
		"url": url,
	}
	STATE.paused = False

	if STATE.player is None:
		# Плеер не открыт — открываем поверх текущего экрана.
		from Screens.InfoBar import MoviePlayer
		STATE.player = STATE.session.open(MoviePlayer, sref)
		STATE.player.onClose.append(_on_player_closed)
	else:
		# Плеер уже открыт (переключение серии/озвучки) — просто меняем
		# сервис, экран остаётся, никакого мигания UI.
		STATE.session.nav.playService(sref)

	return {"ok": True}, 200


def _do_control(data):
	cmd = data.get("cmd")
	value = data.get("value")

	if cmd == "pause":
		p = _pauseable()
		if not p:
			return {"ok": False, "error": "nothing is playing"}, 409
		p.pause()
		STATE.paused = True
		return {"ok": True}, 200

	if cmd == "resume":
		p = _pauseable()
		if not p:
			return {"ok": False, "error": "nothing is playing"}, 409
		p.unpause()
		STATE.paused = False
		return {"ok": True}, 200

	if cmd == "toggle":
		p = _pauseable()
		if not p:
			return {"ok": False, "error": "nothing is playing"}, 409
		if STATE.paused:
			p.unpause()
		else:
			p.pause()
		STATE.paused = not STATE.paused
		return {"ok": True, "paused": STATE.paused}, 200

	if cmd == "stop":
		if STATE.player is not None:
			STATE.player.close()  # onClose обнулит STATE.player
		else:
			try:
				STATE.session.nav.stopService()
			except Exception:
				pass
		return {"ok": True}, 200

	if cmd == "seek":
		# value: секунды, знак = направление (например -15 / +15)
		sk = _seekable()
		if not sk:
			return {"ok": False, "error": "stream is not seekable"}, 409
		try:
			seconds = int(value)
		except (TypeError, ValueError):
			return {"ok": False, "error": "value must be int seconds"}, 400
		direction = 1 if seconds >= 0 else -1
		sk.seekRelative(direction, abs(seconds) * PTS_PER_SEC)
		return {"ok": True}, 200

	if cmd == "seekto":
		# value: абсолютная позиция в секундах
		sk = _seekable()
		if not sk:
			return {"ok": False, "error": "stream is not seekable"}, 409
		try:
			seconds = int(value)
		except (TypeError, ValueError):
			return {"ok": False, "error": "value must be int seconds"}, 400
		sk.seekTo(max(0, seconds) * PTS_PER_SEC)
		return {"ok": True}, 200

	if cmd in ("volup", "voldown", "mute"):
		try:
			from Components.VolumeControl import VolumeControl
			vc = VolumeControl.instance
			if vc is None:
				return {"ok": False, "error": "volume control unavailable"}, 409
			if cmd == "volup":
				vc.volUp()
			elif cmd == "voldown":
				vc.volDown()
			else:
				vc.volMute()
			return {"ok": True}, 200
		except Exception as e:
			return {"ok": False, "error": exc_text(e)}, 500

	return {"ok": False, "error": "unknown cmd: %s" % cmd}, 400


def _do_status():
	position = None
	duration = None
	sk = _seekable()
	if sk:
		try:
			l = sk.getLength()
			if l and not l[0]:
				duration = l[1] // PTS_PER_SEC
			p = sk.getPlayPosition()
			if p and not p[0]:
				position = p[1] // PTS_PER_SEC
		except Exception:
			pass

	return {
		"ok": True,
		"playing": STATE.player is not None,
		"paused": STATE.paused,
		"position": position,   # сек, None если недоступно
		"duration": duration,   # сек, None если недоступно
		"title": STATE.meta.get("title", ""),
		"season": STATE.meta.get("season"),
		"episode": STATE.meta.get("episode"),
		"translator": STATE.meta.get("translator", ""),
		"url": STATE.meta.get("url", ""),
	}, 200


def _do_osd(data):
	text = data.get("text")
	if not text:
		return {"ok": False, "error": "text is required"}, 400
	try:
		timeout = int(data.get("timeout", 4))
	except (TypeError, ValueError):
		timeout = 4
	try:
		from Screens.MessageBox import MessageBox
		from Tools import Notifications
		Notifications.AddNotification(
			MessageBox, _b(text), MessageBox.TYPE_INFO, timeout=timeout)
		return {"ok": True}, 200
	except Exception as e:
		log_exception("agent.osd")
		return {"ok": False, "error": exc_text(e)}, 500


# ── HTTP ─────────────────────────────────────────────────────────────────────

class ApiResource(resource.Resource):
	isLeaf = True

	def _json(self, request, obj, code=200):
		request.setResponseCode(code)
		request.setHeader("Content-Type", "application/json; charset=utf-8")
		return json.dumps(obj)

	def _check_lan(self, request):
		ip = request.getClientIP()
		if not _is_lan(ip):
			log("agent: rejected non-LAN request from %s" % ip)
			return False
		return True

	def render_GET(self, request):
		if not self._check_lan(request):
			return self._json(request, {"ok": False, "error": "forbidden"}, 403)
		path = request.path
		try:
			if path == "/ping":
				return self._json(request, {
					"ok": True,
					"service": "hdrezka-agent",
					"version": AGENT_VERSION,
					"name": socket.gethostname(),
				})
			if path == "/status":
				obj, code = _do_status()
				return self._json(request, obj, code)
			return self._json(request, {"ok": False, "error": "not found"}, 404)
		except Exception as e:
			log_exception("agent.GET %s" % path)
			return self._json(request, {"ok": False, "error": exc_text(e)}, 500)

	def render_POST(self, request):
		if not self._check_lan(request):
			return self._json(request, {"ok": False, "error": "forbidden"}, 403)
		path = request.path
		try:
			body = request.content.read()
			try:
				data = json.loads(body) if body else {}
			except ValueError:
				return self._json(request, {"ok": False, "error": "invalid json"}, 400)
			if not isinstance(data, dict):
				return self._json(request, {"ok": False, "error": "json object expected"}, 400)

			if path == "/play":
				obj, code = _do_play(data)
			elif path == "/control":
				obj, code = _do_control(data)
			elif path == "/osd":
				obj, code = _do_osd(data)
			else:
				obj, code = {"ok": False, "error": "not found"}, 404
			return self._json(request, obj, code)
		except Exception as e:
			log_exception("agent.POST %s" % path)
			return self._json(request, {"ok": False, "error": exc_text(e)}, 500)


# ── Discovery (UDP) ──────────────────────────────────────────────────────────

class DiscoveryProtocol(DatagramProtocol):
	def __init__(self, http_port):
		self.http_port = http_port

	def datagramReceived(self, datagram, addr):
		try:
			if datagram.strip() != "HDREZKA_DISCOVER":
				return
			if not _is_lan(addr[0]):
				return
			payload = json.dumps({
				"service": "hdrezka-agent",
				"version": AGENT_VERSION,
				"port": self.http_port,
				"name": socket.gethostname(),
			})
			self.transport.write(payload, addr)
		except Exception:
			log_exception("agent.discovery")


# ── запуск/останов ───────────────────────────────────────────────────────────

def start_agent(session):
	"""Вызывается из plugin.sessionstart. Повторный вызов безопасен."""
	STATE.session = session

	try:
		from .settings import config
		if not config.plugins.hdrezka.agent_enabled.value:
			log("agent: disabled in settings")
			return
		port = int(config.plugins.hdrezka.agent_port.value)
	except Exception:
		port = DEFAULT_PORT

	if STATE.tcp_port is not None:
		return  # уже запущен

	try:
		STATE.tcp_port = reactor.listenTCP(port, server.Site(ApiResource()))
		STATE.udp_port = reactor.listenUDP(port, DiscoveryProtocol(port))
		log("agent: listening on tcp/udp %d" % port)
	except Exception:
		# Порт занят / нет сети на момент старта — плагин должен жить дальше.
		log_exception("agent.start (port %s)" % port)
		stop_agent()


def stop_agent():
	for attr in ("tcp_port", "udp_port"):
		p = getattr(STATE, attr)
		if p is not None:
			try:
				p.stopListening()
			except Exception:
				pass
			setattr(STATE, attr, None)


def restart_agent():
	"""Дергается из экрана настроек при смене порта/флага."""
	stop_agent()
	if STATE.session is not None:
		start_agent(STATE.session)
