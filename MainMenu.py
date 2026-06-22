# -*- coding: utf-8 -*-
from Screens.Screen import Screen
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList
from enigma import eListboxPythonMultiContent, gFont, RT_HALIGN_LEFT
from Components.MultiContent import MultiContentEntryText

from .config import HDREZKA_ORIGIN


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
	<screen name="HdRezkaMainMenu" position="center,center" size="700,500" title="HDRezka">
		<widget name="title" position="10,10" size="680,40" font="Regular;30" halign="center" />
		<widget name="menu" position="10,60" size="680,420" scrollbarMode="showOnDemand" />
	</screen>
	"""

	def __init__(self, session):
		Screen.__init__(self, session)
		self.session = session

		self["title"] = Label("HDRezka — %s" % HDREZKA_ORIGIN)

		self.menu_items = [
			("search",    u"Поиск по названию"),
			("films",     u"Фильмы"),
			("series",    u"Сериалы"),
			("cartoons",  u"Мультфильмы"),
			("animation", u"Аниме"),
		]

		entries = [MenuEntryComponent(title) for (key, title) in self.menu_items]
		self["menu"] = SimpleMenuList(entries)

		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok":     self.onSelect,
			"cancel": self.onCancel,  # не закрываем корневой экран
			"red":    self.onCancel,
		}, -1)

	def onCancel(self):
		# Корневой экран — выход заблокирован, чтобы не уйти в пустое меню E2
		pass

	def onSelect(self):
		index = self["menu"].getSelectedIndex()
		if index is None:
			return
		key, title = self.menu_items[index]

		if key == "search":
			self.openSearch()
		else:
			self.openCategory(key, title)

	def openSearch(self):
		from Screens.VirtualKeyBoard import VirtualKeyBoard

		def callback(text=None):
			if text:
				self.openResultsList(query=text)

		self.session.openWithCallback(callback, VirtualKeyBoard, title=u"Поиск на HDRezka", text="")

	def openCategory(self, category_key, title):
		from .ResultsList import HdRezkaResultsList
		self.session.open(HdRezkaResultsList, mode="category", category=category_key, title=title)

	def openResultsList(self, query):
		from .ResultsList import HdRezkaResultsList
		self.session.open(HdRezkaResultsList, mode="search", query=query, title=u'Поиск: "%s"' % query)
