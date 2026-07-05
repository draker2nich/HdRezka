# -*- coding: utf-8 -*-
from Plugins.Plugin import PluginDescriptor
from .log import log_exception


def main(session, **kwargs):
	from .MainMenu import HdRezkaMainMenu
	session.open(HdRezkaMainMenu)


def sessionstart(reason, **kwargs):
	"""Автооткрытие меню при загрузке убрано (фикс этапа 1). Здесь
	стартует HTTP-агент для Android-пульта: телефон парсит HDRezka сам
	и шлёт сюда готовый URL потока + команды управления."""
	if reason == 0:
		try:
			session = kwargs.get("session")
			if session:
				from .agent import start_agent
				start_agent(session)
		except Exception:
			# Падение здесь не должно утянуть автозапуск других плагинов
			# в этом же проходе WHERE_SESSIONSTART.
			log_exception("sessionstart")


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
			name="HDRezka",
			where=PluginDescriptor.WHERE_SESSIONSTART,
			fnc=sessionstart,
		),
	]
