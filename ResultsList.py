# -*- coding: utf-8 -*-
from Screens.Screen import Screen
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList
from enigma import eListboxPythonMultiContent, gFont, RT_HALIGN_LEFT
from Components.MultiContent import MultiContentEntryText
from Screens.MessageBox import MessageBox

from .config import get_origin
from .async_screen import AsyncLoaderMixin
from .log import log_exception, exc_text

CATEGORY_URLS = {
	"films":     "/films/",
	"series":    "/series/",
	"cartoons":  "/cartoons/",
	"animation": "/animation/",
}


def ResultEntryComponent(item):
	title = item.get("title", "???")
	if isinstance(title, unicode):
		title = title.encode("utf-8")
	rating = item.get("rating")
	line = title
	if rating:
		line = "%s   [%.1f]" % (title, rating)

	res = [item]
	res.append(MultiContentEntryText(
		pos=(10, 0), size=(640, 40),
		font=0, flags=RT_HALIGN_LEFT,
		text=line
	))
	return res


class ResultsMenuList(MenuList):
	def __init__(self, list_items):
		MenuList.__init__(self, list_items, enableWrapAround=True, content=eListboxPythonMultiContent)
		self.l.setFont(0, gFont("Regular", 26))
		self.l.setItemHeight(45)


class HdRezkaResultsList(Screen, AsyncLoaderMixin):
	skin = """
	<screen name="HdRezkaResultsList" position="center,center" size="760,560" title="HDRezka">
		<widget name="title"  position="10,10"  size="740,40"  font="Regular;26" halign="center" />
		<widget name="status" position="10,55"  size="740,30"  font="Regular;20" halign="center" foregroundColor="#aaaaaa" />
		<widget name="menu"   position="10,90"  size="740,460" scrollbarMode="showOnDemand" />
	</screen>
	"""

	def __init__(self, session, mode, title, query=None, category=None):
		Screen.__init__(self, session)
		self._initAsyncLoader()

		self.session  = session
		self.mode     = mode
		self.query    = query
		self.category = category
		self.results  = []

		if isinstance(title, unicode):
			title = title.encode("utf-8")

		self["title"]   = Label(title)
		self["status"]  = Label(u"Загрузка...".encode("utf-8"))
		self["menu"]    = ResultsMenuList([])

		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok":     self.onSelect,
			"cancel": self.close,
			"red":    self.close,
		}, -1)

		self.onLayoutFinish.append(self.startLoading)

	def startLoading(self):
		self["status"].setText(u"Загрузка...".encode("utf-8"))
		self.startAsync(self._loadWorker, self._onLoaded)

	def _loadWorker(self):
		if self.mode == "search":
			from .HdRezkaApi.search import HdRezkaSearch
			searcher = HdRezkaSearch(get_origin())
			return searcher(self.query, find_all=False)
		elif self.mode == "favorites":
			from .favorites import list_favorites
			return list_favorites()
		elif self.mode == "history":
			from .history import list_history
			return list_history()
		else:
			return self._fetchCategoryPage(self.category)

	def _fetchCategoryPage(self, category_key):
		# Общий пул сессий (keep-alive) + общий парсер вместо локальных.
		from .HdRezkaApi.session_pool import get_session
		from .HdRezkaApi.types import make_soup
		path = CATEGORY_URLS.get(category_key, "/")
		url  = get_origin().rstrip("/") + path
		r = get_session().get(url, timeout=20)
		r.raise_for_status()
		soup  = make_soup(r.content)
		items = soup.find_all(class_="b-content__inline_item")
		results = []
		for item in items:
			link = item.find(class_="b-content__inline_item-link")
			if not link:
				continue
			a = link.find("a")
			if not a:
				continue
			cover = item.find(class_="b-content__inline_item-cover")
			img   = cover.find("img") if cover else None
			results.append({
				"title": a.text.strip(),
				"url":   a.attrs.get("href", ""),
				"image": img.attrs.get("src", "") if img else None,
			})
		return results

	def _onLoaded(self, result, error):
		if error:
			# БАГФИКС: str(error) в py2 падал с UnicodeEncodeError на
			# unicode-сообщениях с кириллицей.
			msg = exc_text(error)
			self["status"].setText(("Ошибка: %s" % msg))
			log_exception("HdRezkaResultsList._loadWorker")
			self.session.open(MessageBox, msg, MessageBox.TYPE_ERROR, timeout=8)
			return

		self.results = result or []

		if not self.results:
			if self.mode == "favorites":
				empty_msg = u"Список избранного пуст"
			elif self.mode == "history":
				empty_msg = u"Вы ещё ничего не смотрели"
			else:
				empty_msg = u"Ничего не найдено"
			self["status"].setText(empty_msg.encode("utf-8"))
			return

		self["status"].setText(("Найдено: %d" % len(self.results)).encode("utf-8"))
		entries = [ResultEntryComponent(item) for item in self.results]
		self["menu"].setList(entries)

	def onSelect(self):
		index = self["menu"].getSelectedIndex()
		if index is None or not self.results:
			return
		item = self.results[index]
		from .MovieDetails import HdRezkaMovieDetails
		self.session.open(HdRezkaMovieDetails, url=item["url"], title_hint=item.get("title", ""))
