# -*- coding: utf-8 -*-
import re
import time

from Screens.Screen import Screen
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList
from enigma import eListboxPythonMultiContent, gFont, RT_HALIGN_LEFT, eServiceReference
from Components.MultiContent import MultiContentEntryText
from Screens.MessageBox import MessageBox
from Screens.InfoBar import MoviePlayer

from .config import HDREZKA_ORIGIN
from .async_screen import AsyncLoaderMixin
from .log import log_exception, exc_text
from .settings import config

# Скомпилирован один раз, а не на каждый вызов quality_key.
_QNUM_RE = re.compile(r'\d+')

# Сколько раз пробуем перезапросить данные перевода у API, прежде чем
# показать ошибку. Раньше при неудаче (success: false от сервера) код
# вместо повтора молча перегружал ВСЕ переводы фильма целиком — это
# дорогая операция и на слабом железе выглядела как зависание на
# 10-20+ секунд при простом открытии списка сезонов/эпизодов.
_FETCH_RETRIES = 2
_RETRY_DELAY_SEC = 0.6


def _u(s):
	"""Гарантированно возвращает utf-8 str для Enigma2 виджетов."""
	if isinstance(s, unicode):
		return s.encode("utf-8")
	return str(s) if s is not None else ""


def _uni(s):
	"""Гарантированно возвращает unicode.

	БАГФИКС: раньше utf-8 БАЙТЫ из _u() подставлялись в unicode-литералы
	(u"%s — Сезон %d" % ...), что в py2 вызывает неявный .decode('ascii')
	и роняет экран UnicodeDecodeError'ом на любом русском названии.
	Правило теперь простое: внутри — только unicode (_uni), кодирование
	в utf-8 (_u) — один раз на границе с виджетом.
	"""
	if isinstance(s, unicode):
		return s
	if s is None:
		return u""
	try:
		return str(s).decode("utf-8")
	except Exception:
		return str(s).decode("utf-8", "replace")


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


def _fetch_translator_data_with_retry(rezka, tr_id):
	"""Запрашивает данные ОДНОГО перевода с парой повторов вместо тяжёлого
	фоллбэка на полную загрузку всех переводов фильма."""
	last_err = None
	for attempt in range(_FETCH_RETRIES):
		try:
			data = rezka.seriesInfoFor(tr_id)
		except Exception as e:
			last_err = e
			data = None
		if data:
			return data
		if attempt < _FETCH_RETRIES - 1:
			time.sleep(_RETRY_DELAY_SEC)
	if last_err:
		raise last_err
	raise Exception(u"Сервер не вернул данные для этого перевода".encode("utf-8"))


class _BaseScreen(Screen, AsyncLoaderMixin):
	skin = """
	<screen name="HdRezkaBase" position="center,center" size="760,560" title="HDRezka">
		<widget name="title"  position="10,10"  size="740,40"  font="Regular;24" halign="center" />
		<widget name="status" position="10,55"  size="740,30"  font="Regular;20" halign="center" foregroundColor="#aaaaaa" />
		<widget name="menu"   position="10,90"  size="740,460" scrollbarMode="showOnDemand" />
	</screen>
	"""

	def __init__(self, session, title="HDRezka"):
		Screen.__init__(self, session)
		self._initAsyncLoader()

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

	def showError(self, error, context=""):
		"""Раньше ошибка тонула в мелком статус-лейбле, который легко
		пропустить. Теперь дополнительно всплывает MessageBox и пишется
		в лог — проще понять, что именно пошло не так."""
		# БАГФИКС: str(error) в py2 сам падал с UnicodeEncodeError на
		# unicode-сообщениях с кириллицей (например, из requests).
		msg = exc_text(error)
		self.setStatus(u"Ошибка: %s" % msg.decode("utf-8", "replace"))
		log_exception(context)
		self.session.open(MessageBox, msg, MessageBox.TYPE_ERROR, timeout=8)

	def onOk(self):
		pass


class HdRezkaMovieDetails(_BaseScreen):

	def __init__(self, session, url, title_hint=""):
		_BaseScreen.__init__(self, session, title=title_hint or "HDRezka")
		self.url   = url
		self.rezka = None
		self._progress = None
		self["favActions"] = ActionMap(["ColorActions"], {
			"yellow": self.toggleFavorite,
		}, -1)
		self["histActions"] = ActionMap(["ColorActions"], {
			"blue": self.continueWatching,
		}, -1)
		self.onLayoutFinish.append(self._start)

	def _start(self):
		self.setStatus(u"Загрузка информации...")
		self.startAsync(self._worker, self._onLoaded)

	def _worker(self):
		from .HdRezkaApi.api import HdRezkaApi
		rezka = HdRezkaApi(self.url)
		if not rezka.ok:
			raise rezka.exception or Exception(u"Не удалось загрузить страницу".encode("utf-8"))
		return rezka

	def _buildTitle(self):
		from .favorites import is_favorite
		r = self.rezka
		title = _u(r.name or u"—")
		try:
			year = r.releaseYear
			if year:
				title = "%s (%s)" % (title, year)
		except Exception:
			pass
		if is_favorite(self.url):
			title = "* " + title
		return title

	def toggleFavorite(self):
		if not self.rezka:
			return
		from .favorites import toggle_favorite
		added = toggle_favorite(self.url, self.rezka.name or u"")
		self.setStatus(u"Добавлено в избранное (ЖЁЛТАЯ ещё раз — убрать)" if added
		               else u"Убрано из избранного")
		self["title"].setText(self._buildTitle())

	def continueWatching(self):
		if not self.rezka or not self._progress:
			return
		p = self._progress
		translator = {"id": p.get("translator_id"), "name": p.get("translator_name") or u""}
		if not translator["id"]:
			return
		self.session.open(HdRezkaQualityScreen,
		                  rezka=self.rezka, translator=translator,
		                  season=p.get("season"), episode=p.get("episode"))

	def _onLoaded(self, result, error):
		if error:
			self.showError(error, "HdRezkaMovieDetails._worker")
			return

		self.rezka = result
		r = self.rezka
		self["title"].setText(self._buildTitle())

		from .history import get_progress
		self._progress = get_progress(self.url)

		try:
			if r.rating and r.rating.value:
				self.setStatus(u"Рейтинг: %s" % r.rating.value)
		except Exception:
			pass

		try:
			translators = r.translators
			sorted_tr   = r.sort_translators(translators)
		except Exception as e:
			self.showError(e, "HdRezkaMovieDetails.translators")
			return

		items = list(sorted_tr.items())
		try:
			pref = config.plugins.hdrezka.preferred_translator.value.strip().lower()
			if pref:
				matched = [it for it in items if pref in it[1]["name"].lower()]
				if matched:
					rest = [it for it in items if it not in matched]
					items = matched + rest
		except Exception:
			pass

		entries = []
		for tr_id, info in items:
			label = info["name"]
			if info.get("premium"):
				label += "  [PREMIUM]"
			entries.append(SimpleEntry(label, data={"id": tr_id, "name": info["name"]}))

		if not entries:
			self.setStatus(u"Переводы не найдены")
			return

		self.setList(entries)
		status = u"Выберите перевод"
		if self._progress and self._progress.get("translator_id"):
			p = self._progress
			if p.get("season") and p.get("episode"):
				status += u"  |  СИНЯЯ — продолжить: С%sЭ%s" % (p["season"], p["episode"])
			else:
				status += u"  |  СИНЯЯ — продолжить просмотр"
		self.setStatus(status)

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
		_BaseScreen.__init__(self, session, title=_uni(rezka.name) or u"HDRezka")
		self.rezka      = rezka
		self.translator = translator
		self.onLayoutFinish.append(self._start)

	def _start(self):
		self.setStatus(u"Загрузка сезонов...")
		self.startAsync(self._worker, self._onLoaded)

	def _worker(self):
		tr_id = self.translator["id"]
		data = _fetch_translator_data_with_retry(self.rezka, tr_id)
		return sorted(data["seasons"].keys())

	def _onLoaded(self, result, error):
		if error:
			self.showError(error, "HdRezkaSeasonsScreen._worker")
			return
		if not result:
			self.setStatus(u"Сезоны не найдены")
			return
		entries = [SimpleEntry(u"Сезон %d" % s, data=s) for s in result]
		self.setList(entries)
		self.setStatus(u"Выберите сезон")

	def onOk(self):
		season = self.getSelected()
		if season is None:
			return
		self.session.open(HdRezkaEpisodesScreen,
		                  rezka=self.rezka, translator=self.translator, season=season)


class HdRezkaEpisodesScreen(_BaseScreen):

	def __init__(self, session, rezka, translator, season):
		# БАГФИКС: было _u(...) (байты) внутри unicode-формата -> краш
		# UnicodeDecodeError на русских названиях.
		_BaseScreen.__init__(self, session,
		                     title=u"%s — Сезон %d" % (_uni(rezka.name) or u"HDRezka", season))
		self.rezka      = rezka
		self.translator = translator
		self.season     = season
		self.onLayoutFinish.append(self._start)

	def _start(self):
		self.setStatus(u"Загрузка эпизодов...")
		self.startAsync(self._worker, self._onLoaded)

	def _worker(self):
		tr_id = self.translator["id"]
		data = _fetch_translator_data_with_retry(self.rezka, tr_id)
		ep_dict = data["episodes"].get(self.season, {})
		return [{"episode": ep, "text": ep_dict[ep]} for ep in sorted(ep_dict.keys())]

	def _onLoaded(self, result, error):
		if error:
			self.showError(error, "HdRezkaEpisodesScreen._worker")
			return
		if not result:
			self.setStatus(u"Эпизоды не найдены")
			return
		# БАГФИКС: было _u(e["text"]) (байты) внутри unicode-формата ->
		# краш на любом русском названии серии ("Серия 1" и т.п.).
		entries = [
			SimpleEntry(u"Эпизод %d — %s" % (e["episode"], _uni(e["text"])), data=e["episode"])
			for e in result
		]
		self.setList(entries)
		self.setStatus(u"Выберите эпизод")

	def onOk(self):
		episode = self.getSelected()
		if episode is None:
			return
		self.session.open(HdRezkaQualityScreen,
		                  rezka=self.rezka, translator=self.translator,
		                  season=self.season, episode=episode)


class HdRezkaQualityScreen(_BaseScreen):

	def __init__(self, session, rezka, translator, season, episode):
		title = _uni(rezka.name) or u"HDRezka"
		if season and episode:
			title = u"%s — С%d Э%d" % (title, int(season), int(episode))
		_BaseScreen.__init__(self, session, title=title)

		self.rezka      = rezka
		self.translator = translator
		self.season     = season
		self.episode    = episode
		self._stream    = None
		self.onLayoutFinish.append(self._start)

	def _start(self):
		self.setStatus(u"Получение ссылок...")
		self.startAsync(self._worker, self._onLoaded)

	def _worker(self):
		tr_id = self.translator["id"]
		return self.rezka.getStream(
			season=self.season,
			episode=self.episode,
			translation=tr_id,
		)

	def _onLoaded(self, result, error):
		if error:
			self.showError(error, "HdRezkaQualityScreen._worker")
			return
		self._stream = result
		if not self._stream or not self._stream.videos:
			self.setStatus(u"Ссылки не найдены")
			return

		def quality_key(q):
			m = _QNUM_RE.search(q)
			return int(m.group()) if m else 0

		qualities = sorted(self._stream.videos.keys(), key=quality_key, reverse=True)
		entries = [SimpleEntry(q, data=q) for q in qualities]
		self.setList(entries)
		self.setStatus(u"Выберите качество")

		try:
			if config.plugins.hdrezka.auto_quality_enabled.value and qualities:
				best = self._pickTargetQuality(qualities, quality_key)
				urls = self._stream(best)
				if urls:
					self.setStatus(u"Автовыбор качества: %s" % best)
					self._play(urls[0])
		except Exception:
			pass

	@staticmethod
	def _pickTargetQuality(qualities, quality_key):
		"""Подбирает качество максимально близкое СНИЗУ к целевому (по
		умолчанию 720p): если 720p нет — берём ближайшее МЕНЬШЕЕ, а не
		большее. Если вообще нет ничего <= target (например, есть только
		1080/2160) — берём минимальное из того, что есть, чтобы не
		проигрывать что-то тяжелее, чем просил пользователь."""
		try:
			target = int(config.plugins.hdrezka.target_quality.value.strip())
		except Exception:
			target = 720

		numbered = [(quality_key(q), q) for q in qualities]
		not_above = [nq for nq in numbered if nq[0] <= target and nq[0] > 0]
		if not_above:
			return max(not_above, key=lambda nq: nq[0])[1]
		# ничего не нашлось <= target (либо у всех qualities не распознан
		# номер) -- берём минимальное доступное
		return min(numbered, key=lambda nq: nq[0])[1]

	def onOk(self):
		quality = self.getSelected()
		if not quality or not self._stream:
			return
		urls = self._stream(quality)
		if not urls:
			self.session.open(MessageBox,
			                  _u(u"Нет ссылки для качества %s" % quality),
			                  MessageBox.TYPE_ERROR, timeout=5)
			return
		self._play(urls[0])

	def _play(self, url):
		try:
			referer = getattr(self.rezka, "origin", None) or HDREZKA_ORIGIN
			play_url = "%s#Referer=%s&User-Agent=Mozilla/5.0" % (url, referer)
			sref = eServiceReference(5002, 0, str(play_url))
			sref.setName(_u(self.rezka.name or "HDRezka"))
			self.session.open(MoviePlayer, sref)

			try:
				from .history import set_progress
				set_progress(
					self.rezka.url, self.rezka.name or u"",
					translator_id=self.translator.get("id"),
					translator_name=self.translator.get("name"),
					season=self.season, episode=self.episode,
				)
			except Exception:
				pass
		except Exception as e:
			# Раньше любая ошибка тут (например, неподдерживаемый тип
			# сервиса на конкретном образе) приводила просто к "ничего
			# не произошло" без какой-либо обратной связи пользователю.
			log_exception("HdRezkaQualityScreen._play")
			self.session.open(MessageBox,
			                  _u(u"Не удалось запустить воспроизведение: %s" % e),
			                  MessageBox.TYPE_ERROR, timeout=8)
