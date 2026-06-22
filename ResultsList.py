# -*- coding: utf-8 -*-
import threading
import requests

from Screens.Screen import Screen
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList
from enigma import eListboxPythonMultiContent, gFont, RT_HALIGN_LEFT, eTimer
from Components.MultiContent import MultiContentEntryText

from .config import HDREZKA_ORIGIN

# Убираем варнинги SSL на старом железе
try:
	requests.packages.urllib3.disable_warnings()
except Exception:
	pass

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


class HdRezkaResultsList(Screen):
	skin = """
	<screen name="HdRezkaResultsList" position="center,center" size="760,560" title="HDRezka">
		<widget name="title"  position="10,10"  size="740,40"  font="Regular;26" halign="center" />
		<widget name="status" position="10,55"  size="740,30"  font="Regular;20" halign="center" foregroundColor="#aaaaaa" />
		<widget name="menu"   position="10,90"  size="740,460" scrollbarMode="showOnDemand" />
	</screen>
	"""

	def __init__(self, session, mode, title, query=None, category=None):
		Screen.__init__(self, session)
		self.session  = session
		self.mode     = mode
		self.query    = query
		self.category = category
		self.results  = []
		self.error    = None
		self._loaded  = False

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

		self.poll_timer = eTimer()
		try:
			self.poll_timer_conn = self.poll_timer.timeout.connect(self.checkLoaded)
		except AttributeError:
			self.poll_timer.callback.append(self.checkLoaded)

		# Останавливаем таймер при закрытии экрана
		self.onClose.append(self._onClose)
		self.onLayoutFinish.append(self.startLoading)

	def _onClose(self):
		self.poll_timer.stop()
		self._loaded = True  # прерываем фоновый поток

	def startLoading(self):
		self._loaded = False
		thread = threading.Thread(target=self._loadWorker)
		thread.daemon = True
		thread.start()
		self.poll_timer.start(300, False)

	def _loadWorker(self):
		try:
			if self.mode == "search":
				from .HdRezkaApi.search import HdRezkaSearch
				searcher = HdRezkaSearch(HDREZKA_ORIGIN)
				self.results = searcher(self.query, find_all=False)
			else:
				self.results = self._fetchCategoryPage(self.category)
		except Exception as e:
			self.error = str(e)
		finally:
			self._loaded = True

	def _fetchCategoryPage(self, category_key):
		from bs4 import BeautifulSoup
		path = CATEGORY_URLS.get(category_key, "/")
		url  = HDREZKA_ORIGIN.rstrip("/") + path
		headers = {
			"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/81.0.4044.138 Safari/537.36"
		}
		r = requests.get(url, headers=headers, timeout=15, verify=False)
		r.raise_for_status()
		soup  = BeautifulSoup(r.content, "html.parser")
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

	def checkLoaded(self):
		if not self._loaded:
			return
		self.poll_timer.stop()

		if self.error:
			self["status"].setText(("Ошибка: %s" % self.error).encode("utf-8"))
			return

		if not self.results:
			self["status"].setText(u"Ничего не найдено".encode("utf-8"))
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
