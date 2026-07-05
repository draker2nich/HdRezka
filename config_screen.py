# -*- coding: utf-8 -*-
from Screens.Screen import Screen
# ConfigListScreen канонически живёт в Components.ConfigList. На части образов
# (в т.ч. OpenVision) его больше нет в Screens.Setup — оттуда импорт падал и
# ронял ресивер при открытии настроек. Берём из правильного места, со старым
# расположением как фолбэком.
try:
	from Components.ConfigList import ConfigListScreen
except ImportError:
	from Screens.Setup import ConfigListScreen
from Components.ActionMap import ActionMap
from Components.config import config, getConfigListEntry, configfile

from .settings import config as _ensure_loaded  # noqa: F401  (регистрирует ветку настроек)
from .config import reset_origin_cache


class HdRezkaSetup(ConfigListScreen, Screen):
	skin = """
	<screen name="HdRezkaSetup" position="center,center" size="650,420" title="Настройки HDRezka">
		<widget name="config" position="10,10" size="630,340" scrollbarMode="showOnDemand" />
		<widget name="hint" position="10,360" size="630,50" font="Regular;18" foregroundColor="#888888" />
	</screen>
	"""

	def __init__(self, session):
		Screen.__init__(self, session)

		from Components.Label import Label
		self["hint"] = Label(
			u"OK/Влево-Вправо — изменить, ЗЕЛЁНАЯ — сохранить, КРАСНАЯ/Cancel — отмена".encode("utf-8")
		)

		self.list = [
			getConfigListEntry(
				u"Автовыбор качества (без ручного меню)".encode("utf-8"),
				config.plugins.hdrezka.auto_quality_enabled
			),
			getConfigListEntry(
				u"Целевое качество (720 = 720p или ближайшее меньшее)".encode("utf-8"),
				config.plugins.hdrezka.target_quality
			),
			getConfigListEntry(
				u"Предпочитаемая озвучка (часть названия)".encode("utf-8"),
				config.plugins.hdrezka.preferred_translator
			),
			getConfigListEntry(
				u"Домен принудительно (пусто = автоопределение)".encode("utf-8"),
				config.plugins.hdrezka.force_domain
			),
			getConfigListEntry(
				u"Агент для смартфона-пульта".encode("utf-8"),
				config.plugins.hdrezka.agent_enabled
			),
			getConfigListEntry(
				u"Порт агента".encode("utf-8"),
				config.plugins.hdrezka.agent_port
			),
		]
		ConfigListScreen.__init__(self, self.list, session=session)

		self["actions"] = ActionMap(["SetupActions", "ColorActions"], {
			"save":   self.keySave,
			"cancel": self.keyCancel,
			"red":    self.keyCancel,
			"green":  self.keySave,
		}, -1)

	def keySave(self):
		ConfigListScreen.keySave(self)
		try:
			configfile.save()
		except Exception:
			pass
		# Если пользователь поменял принудительный домен (или очистил его) —
		# сбрасываем кэш автоопределения, чтобы изменение применилось сразу,
		# а не после перезапуска плагина.
		reset_origin_cache()
		# БАГФИКС: пул keep-alive-сессий тоже сбрасываем — иначе старые
		# соединения (и cookies) к прежнему зеркалу продолжали жить.
		try:
			from .HdRezkaApi.session_pool import reset_session
			reset_session()
		except Exception:
			pass
		# Порт/флаг агента могли измениться — перезапускаем слушатели.
		try:
			from .agent import restart_agent
			restart_agent()
		except Exception:
			pass
		self.close()

	def keyCancel(self):
		for x in self["config"].list:
			x[1].cancel()
		self.close()
