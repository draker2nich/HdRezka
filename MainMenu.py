# -*- coding: utf-8 -*-
from Screens.Screen import Screen
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList
from enigma import eListboxPythonMultiContent, gFont, RT_HALIGN_LEFT
from Components.MultiContent import MultiContentEntryText

from .config import peek_origin


def MenuEntryComponent(title):
	if isinstance(title, unicode):
		title = title.encode("utf-8")
	res = [title]
	res.append(MultiContentEntryText(
		pos=(10, 0), size=(600, 40),
		font=0, flags=RT_HALIGN_LEFT,
		text=title
	))
	return res


class SimpleMenuList(MenuList):
	def __init__(self, list_items):
		MenuList.__init__(self, list_items, enableWrapAround=True, content=eListboxPythonMultiContent)
		self.l.setFont(0, gFont("Regular", 28))
		self.l.setItemHeight(45)


class HdRezkaMainMenu(Screen):
	skin = """
	<screen name="HdRezkaMainMenu" position="center,center" size="700,520" title="HDRezka">
		<widget name="title" position="10,10" size="680,40" font="Regular;30" halign="center" />
		<widget name="menu" position="10,60" size="680,440" scrollbarMode="showOnDemand" />
	</screen>
	"""

	def __init__(self, session):
		Screen.__init__(self, session)
		self.session = session

		# peek_origin() не лезет в сеть -- безопасно дёргать прямо в GUI-потоке.
		# Реальное (возможно другое, если зеркало недоступно) определение
		# домена происходит лениво в фоновых потоках при первом запросе.
		self["title"] = Label("HDRezka — %s" % peek_origin())

		self.menu_items = [
			("search",    u"Поиск по названию"),
			("continue",  u"Продолжить просмотр"),
			("films",     u"Фильмы"),
			("series",    u"Сериалы"),
			("cartoons",  u"Мультфильмы"),
			("animation", u"Аниме"),
			("favorites", u"Избранное"),
			("settings",  u"Настройки"),
		]

		entries = [MenuEntryComponent(title) for (key, title) in self.menu_items]
		self["menu"] = SimpleMenuList(entries)

		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok":     self.onSelect,
			"cancel": self.onCancel,
			"red":    self.onCancel,
		}, -1)

	def onCancel(self):
		# БАГФИКС: раньше тут был pass — Exit/Cancel не работал вообще,
		# из плагина невозможно было выйти (особенно критично в связке
		# с автозапуском меню при загрузке ресивера).
		self.close()

	def onSelect(self):
		index = self["menu"].getSelectedIndex()
		if index is None:
			return
		key, title = self.menu_items[index]

		if key == "search":
			self.openSearch()
		elif key == "settings":
			self.openSettings()
		elif key == "favorites":
			self.openFavorites()
		elif key == "continue":
			self.openHistory()
		else:
			self.openCategory(key, title)

	def openSearch(self):
		from Screens.VirtualKeyBoard import VirtualKeyBoard

		def callback(text=None):
			if text:
				self.openResultsList(query=text)

		self.session.openWithCallback(callback, VirtualKeyBoard, title=u"Поиск на HDRezka", text="")

	def openSettings(self):
		from .config_screen import HdRezkaSetup
		self.session.openWithCallback(self._onSettingsClosed, HdRezkaSetup)

	def _onSettingsClosed(self, *args):
		# Домен мог поменяться (принудительный или сброс кэша) -- обновим заголовок.
		self["title"].setText("HDRezka — %s" % peek_origin())

	def openFavorites(self):
		from .ResultsList import HdRezkaResultsList
		self.session.open(HdRezkaResultsList, mode="favorites", category=None, title=u"Избранное")

	def openHistory(self):
		from .ResultsList import HdRezkaResultsList
		self.session.open(HdRezkaResultsList, mode="history", category=None, title=u"Продолжить просмотр")

	def openCategory(self, category_key, title):
		from .ResultsList import HdRezkaResultsList
		self.session.open(HdRezkaResultsList, mode="category", category=category_key, title=title)

	def openResultsList(self, query):
		from .ResultsList import HdRezkaResultsList
		self.session.open(HdRezkaResultsList, mode="search", query=query, title=u'Поиск: "%s"' % query)
