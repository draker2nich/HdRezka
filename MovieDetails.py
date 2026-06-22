# -*- coding: utf-8 -*-
import threading
import re

from Screens.Screen import Screen
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList
from enigma import eListboxPythonMultiContent, gFont, RT_HALIGN_LEFT, eTimer, eServiceReference
from Components.MultiContent import MultiContentEntryText
from Screens.MessageBox import MessageBox

from .config import HDREZKA_ORIGIN

# Скомпилирован один раз, а не на каждый вызов quality_key.
_QNUM_RE = re.compile(r'\d+')

# Интервал опроса фонового потока. 150 мс — компромисс между отзывчивостью
# и нагрузкой (опрос пустого флага практически бесплатен).
_POLL_MS = 150


def _u(s):
	"""Гарантированно возвращает utf-8 str для Enigma2 виджетов."""
	if isinstance(s, unicode):
		return s.encode("utf-8")
	return str(s) if s is not None else ""


def SimpleEntry(text, data=None):
	res = [data if data is not None else text]
	res.append(MultiContentEntryText(
		pos=(10, 0), size=(680, 40),
		font=0, flags=RT_HALIGN_LEFT,
		text=_u(text)
	))
	return res


class SimpleMenuList(MenuList):
	def __init__(self, items):
		MenuList.__init__(self, items, enableWrapAround=True,
		                  content=eListboxPythonMultiContent)
		self.l.setFont(0, gFont("Regular", 26))
		self.l.setItemHeight(45)


class _BaseScreen(Screen):
	skin = """
	<screen name="HdRezkaBase" position="center,center" size="760,560" title="HDRezka">
		<widget name="title"  position="10,10"  size="740,40"  font="Regular;24" halign="center" />
		<widget name="status" position="10,55"  size="740,30"  font="Regular;20" halign="center" foregroundColor="#aaaaaa" />
		<widget name="menu"   position="10,90"  size="740,460" scrollbarMode="showOnDemand" />
	</screen>
	"""

	def __init__(self, session, title="HDRezka"):
		Screen.__init__(self, session)
		self["title"]  = Label(_u(title))
		self["status"] = Label("")
		self["menu"]   = SimpleMenuList([])
		self["actions"] = ActionMap(
			["OkCancelActions", "ColorActions"], {
				"ok":     self.onOk,
				"cancel": self.close,
				"red":    self.close,
			}, -1
		)

	def setStatus(self, text):
		self["status"].setText(_u(text))

	def setList(self, items):
		self["menu"].setList(items)

	def getSelected(self):
		idx = self["menu"].getSelectedIndex()
		lst = self["menu"].list
		if idx is None or not lst:
			return None
		return lst[idx][0]

	def onOk(self):
		pass


class HdRezkaMovieDetails(_BaseScreen):

	def __init__(self, session, url, title_hint=""):
		_BaseScreen.__init__(self, session, title=title_hint or "HDRezka")
		self.url     = url
		self.rezka   = None
		self._loaded = False
		self._error  = None

		self._poll = eTimer()
		try:
			self._poll_conn = self._poll.timeout.connect(self._checkLoaded)
		except AttributeError:
			self._poll.callback.append(self._checkLoaded)

		# Прерываем фоновый поток при закрытии
		self.onClose.append(self._onClose)
		self.onLayoutFinish.append(self._start)

	def _onClose(self):
		self._poll.stop()
		self._loaded = True

	def _start(self):
		self.setStatus("Загрузка информации...")
		t = threading.Thread(target=self._worker)
		t.daemon = True
		t.start()
		self._poll.start(_POLL_MS, False)

	def _worker(self):
		try:
			from .HdRezkaApi.api import HdRezkaApi
			self.rezka = HdRezkaApi(self.url)
			if not self.rezka.ok:
				self._error = str(self.rezka.exception)
		except Exception as e:
			self._error = str(e)
		finally:
			self._loaded = True

	def _checkLoaded(self):
		if not self._loaded:
			return
		self._poll.stop()

		if self._error:
			self.setStatus("Ошибка: %s" % self._error)
			return

		r = self.rezka
		title = _u(r.name or "—")
		try:
			year = r.releaseYear
			if year:
				title = "%s (%s)" % (title, year)
		except Exception:
			pass
		self["title"].setText(title)

		try:
			if r.rating and r.rating.value:
				self.setStatus("Рейтинг: %s" % r.rating.value)
		except Exception:
			pass

		# Список переводов
		try:
			translators  = r.translators
			sorted_tr    = r.sort_translators(translators)
		except Exception as e:
			self.setStatus("Ошибка переводов: %s" % e)
			return

		entries = []
		for tr_id, info in sorted_tr.items():
			label = info["name"]
			if info.get("premium"):
				label += "  [PREMIUM]"
			entries.append(SimpleEntry(label, data={"id": tr_id, "name": info["name"]}))

		if not entries:
			self.setStatus("Переводы не найдены")
			return

		self.setList(entries)
		self.setStatus("Выберите перевод")

	def onOk(self):
		tr = self.getSelected()
		if not tr or not self.rezka:
			return
		try:
			from .HdRezkaApi.types import TVSeries
			is_series = (self.rezka.type == TVSeries)
		except Exception:
			is_series = False

		if is_series:
			self.session.open(HdRezkaSeasonsScreen, rezka=self.rezka, translator=tr)
		else:
			self.session.open(HdRezkaQualityScreen,
			                  rezka=self.rezka, translator=tr,
			                  season=None, episode=None)


class HdRezkaSeasonsScreen(_BaseScreen):

	def __init__(self, session, rezka, translator):
		_BaseScreen.__init__(self, session, title=_u(rezka.name or "HDRezka"))
		self.rezka      = rezka
		self.translator = translator
		self._seasons   = []
		self._loaded    = False
		self._error     = None

		self._poll = eTimer()
		try:
			self._poll_conn = self._poll.timeout.connect(self._checkLoaded)
		except AttributeError:
			self._poll.callback.append(self._checkLoaded)

		self.onClose.append(self._onClose)
		self.onLayoutFinish.append(self._start)

	def _onClose(self):
		self._poll.stop()
		self._loaded = True

	def _start(self):
		self.setStatus("Загрузка сезонов...")
		t = threading.Thread(target=self._worker)
		t.daemon = True
		t.start()
		self._poll.start(_POLL_MS, False)

	def _worker(self):
		try:
			tr_id = self.translator["id"]
			# Перевод уже выбран — грузим эпизоды ТОЛЬКО для него.
			try:
				data = self.rezka.seriesInfoFor(tr_id)
			except Exception:
				data = None
			if data:
				self._seasons = sorted(data["seasons"].keys())
			else:
				info = self.rezka.seriesInfo
				if tr_id in info:
					self._seasons = sorted(info[tr_id]["seasons"].keys())
				else:
					self._seasons = sorted(set(s["season"] for s in self.rezka.episodesInfo))
		except Exception as e:
			self._error = str(e)
		finally:
			self._loaded = True

	def _checkLoaded(self):
		if not self._loaded:
			return
		self._poll.stop()
		if self._error:
			self.setStatus("Ошибка: %s" % self._error)
			return
		if not self._seasons:
			self.setStatus("Сезоны не найдены")
			return
		entries = [SimpleEntry("Сезон %d" % s, data=s) for s in self._seasons]
		self.setList(entries)
		self.setStatus("Выберите сезон")

	def onOk(self):
		season = self.getSelected()
		if season is None:
			return
		self.session.open(HdRezkaEpisodesScreen,
		                  rezka=self.rezka, translator=self.translator, season=season)


class HdRezkaEpisodesScreen(_BaseScreen):

	def __init__(self, session, rezka, translator, season):
		_BaseScreen.__init__(self, session,
		                     title="%s — Сезон %d" % (_u(rezka.name or "HDRezka"), season))
		self.rezka      = rezka
		self.translator = translator
		self.season     = season
		self._episodes  = []
		self._loaded    = False
		self._error     = None

		self._poll = eTimer()
		try:
			self._poll_conn = self._poll.timeout.connect(self._checkLoaded)
		except AttributeError:
			self._poll.callback.append(self._checkLoaded)

		self.onClose.append(self._onClose)
		self.onLayoutFinish.append(self._start)

	def _onClose(self):
		self._poll.stop()
		self._loaded = True

	def _start(self):
		self.setStatus("Загрузка эпизодов...")
		t = threading.Thread(target=self._worker)
		t.daemon = True
		t.start()
		self._poll.start(_POLL_MS, False)

	def _worker(self):
		try:
			tr_id = self.translator["id"]
			# Грузим только выбранный перевод (а не seriesInfo по всем).
			try:
				data = self.rezka.seriesInfoFor(tr_id)
			except Exception:
				data = None
			if data:
				ep_dict = data["episodes"].get(self.season, {})
				self._episodes = [
					{"episode": ep, "text": ep_dict[ep]}
					for ep in sorted(ep_dict.keys())
				]
			else:
				info = self.rezka.seriesInfo
				if tr_id in info:
					ep_dict = info[tr_id]["episodes"].get(self.season, {})
					self._episodes = [
						{"episode": ep, "text": ep_dict[ep]}
						for ep in sorted(ep_dict.keys())
					]
				else:
					season_data = next(
						(s for s in self.rezka.episodesInfo if s["season"] == self.season),
						None
					)
					if season_data:
						self._episodes = [
							{"episode": e["episode"], "text": e["episode_text"]}
							for e in sorted(season_data["episodes"], key=lambda x: x["episode"])
						]
		except Exception as e:
			self._error = str(e)
		finally:
			self._loaded = True

	def _checkLoaded(self):
		if not self._loaded:
			return
		self._poll.stop()
		if self._error:
			self.setStatus("Ошибка: %s" % self._error)
			return
		if not self._episodes:
			self.setStatus("Эпизоды не найдены")
			return
		entries = [
			SimpleEntry("Эпизод %d — %s" % (e["episode"], _u(e["text"])), data=e["episode"])
			for e in self._episodes
		]
		self.setList(entries)
		self.setStatus("Выберите эпизод")

	def onOk(self):
		episode = self.getSelected()
		if episode is None:
			return
		self.session.open(HdRezkaQualityScreen,
		                  rezka=self.rezka, translator=self.translator,
		                  season=self.season, episode=episode)


class HdRezkaQualityScreen(_BaseScreen):

	def __init__(self, session, rezka, translator, season, episode):
		title = _u(rezka.name or "HDRezka")
		if season and episode:
			title = "%s — С%d Э%d" % (title, season, episode)
		_BaseScreen.__init__(self, session, title=title)

		self.rezka      = rezka
		self.translator = translator
		self.season     = season
		self.episode    = episode
		self._stream    = None
		self._loaded    = False
		self._error     = None

		self._poll = eTimer()
		try:
			self._poll_conn = self._poll.timeout.connect(self._checkLoaded)
		except AttributeError:
			self._poll.callback.append(self._checkLoaded)

		self.onClose.append(self._onClose)
		self.onLayoutFinish.append(self._start)

	def _onClose(self):
		self._poll.stop()
		self._loaded = True

	def _start(self):
		self.setStatus("Получение ссылок...")
		t = threading.Thread(target=self._worker)
		t.daemon = True
		t.start()
		self._poll.start(_POLL_MS, False)

	def _worker(self):
		try:
			tr_id = self.translator["id"]
			self._stream = self.rezka.getStream(
				season=self.season,
				episode=self.episode,
				translation=tr_id,
			)
		except Exception as e:
			self._error = str(e)
		finally:
			self._loaded = True

	def _checkLoaded(self):
		if not self._loaded:
			return
		self._poll.stop()
		if self._error:
			self.setStatus("Ошибка: %s" % self._error)
			return
		if not self._stream or not self._stream.videos:
			self.setStatus("Ссылки не найдены")
			return

		def quality_key(q):
			m = _QNUM_RE.search(q)
			return int(m.group()) if m else 0

		qualities = sorted(self._stream.videos.keys(), key=quality_key, reverse=True)
		entries = [SimpleEntry(q, data=q) for q in qualities]
		self.setList(entries)
		self.setStatus("Выберите качество")

	def onOk(self):
		quality = self.getSelected()
		if not quality or not self._stream:
			return
		urls = self._stream(quality)
		if not urls:
			self.session.open(MessageBox,
			                  "Нет ссылки для качества %s" % quality,
			                  MessageBox.TYPE_ERROR, timeout=5)
			return
		self._play(urls[0])

	def _play(self, url):
		from Screens.InfoBar import MoviePlayer
		play_url = "%s#Referer=%s&User-Agent=Mozilla/5.0" % (url, HDREZKA_ORIGIN)
		sref = eServiceReference(5002, 0, str(play_url))
		sref.setName(_u(self.rezka.name or "HDRezka"))
		self.session.open(MoviePlayer, sref)
