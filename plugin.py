# -*- coding: utf-8 -*-
from Plugins.Plugin import PluginDescriptor
from . import webserver


def main(session, **kwargs):
	from .MainMenu import HdRezkaMainMenu
	session.open(HdRezkaMainMenu)


def _play_on_receiver(play_url, title):
	"""Вызывается из webserver.py, когда веб-клиент жмёт 'Смотреть'."""
	from enigma import eServiceReference
	from Screens.InfoBar import MoviePlayer
	import NavigationInstance

	session = NavigationInstance.instance and NavigationInstance.instance.session
	if not session:
		return

	sref = eServiceReference(5002, 0, str(play_url))
	sref.setName(title.encode("utf-8") if isinstance(title, unicode) else str(title))
	session.open(MoviePlayer, sref)


def autostart(reason, **kwargs):
	if reason == 0:
		webserver.start(_play_on_receiver)
	elif reason == 1:
		webserver.stop()


def sessionstart(reason, **kwargs):
	"""Открывает главное меню сразу после старта Enigma2."""
	if reason == 0:
		session = kwargs.get("session")
		if session:
			from .MainMenu import HdRezkaMainMenu
			session.open(HdRezkaMainMenu)


def Plugins(**kwargs):
	return [
		PluginDescriptor(
			name="HDRezka",
			description="Онлайн-кинотеатр HDRezka",
			where=PluginDescriptor.WHERE_PLUGINMENU,
			fnc=main,
			icon="plugin.png",
		),
		PluginDescriptor(
			name="HDRezka",
			description="Онлайн-кинотеатр HDRezka",
			where=PluginDescriptor.WHERE_EXTENSIONSMENU,
			fnc=main,
		),
		PluginDescriptor(
			name="HDRezka WebRemote",
			where=PluginDescriptor.WHERE_AUTOSTART,
			fnc=autostart,
		),
		PluginDescriptor(
			name="HDRezka",
			where=PluginDescriptor.WHERE_SESSIONSTART,
			fnc=sessionstart,
		),
	]
