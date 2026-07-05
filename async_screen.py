# -*- coding: utf-8 -*-
"""
async_screen.py — общая инфраструктура для экранов, которые грузят данные
в фоновом потоке и опрашивают результат через eTimer.

Раньше это было скопировано почти один в один 4 раза (HdRezkaMovieDetails,
HdRezkaSeasonsScreen, HdRezkaEpisodesScreen, HdRezkaQualityScreen в
MovieDetails.py + ещё раз в ResultsList.py). Любая правка обработки ошибок
требовала синхронизировать все места руками — отсюда и часть "кривого"
поведения: где-то поправили, где-то забыли.

Теперь — один раз, здесь.
"""

import threading
from enigma import eTimer

POLL_MS = 150


class AsyncLoaderMixin(object):
	"""Подмешивается к Screen. Сам Screen.__init__ экран вызывает как обычно;
	дополнительно нужно вызвать self._initAsyncLoader() после него."""

	def _initAsyncLoader(self):
		self._async_loaded = False
		self._async_error = None
		self._async_result = None
		self._async_done_fn = None
		# Токен поколения: если startAsync вызван повторно, пока прошлый
		# worker ещё жив, устаревший поток не должен затирать результат
		# нового (раньше оба писали в одни и те же поля).
		self._async_gen = 0

		self._poll = eTimer()
		try:
			self._poll_conn = self._poll.timeout.connect(self._onPollTick)
		except AttributeError:
			# старые образы Enigma2 (без PyQt-style connect) используют callback list
			self._poll.callback.append(self._onPollTick)

		self.onClose.append(self._stopAsyncLoader)

	def _stopAsyncLoader(self):
		self._poll.stop()
		# Инвалидируем все живые worker'ы: их результаты после закрытия
		# экрана никому не нужны.
		self._async_gen += 1
		self._async_loaded = True

	def startAsync(self, worker_fn, done_fn):
		"""worker_fn() выполняется в фоновом потоке, должен вернуть результат
		или кинуть исключение. done_fn(result, error) вызывается в GUI-потоке
		по таймеру, когда worker_fn завершится."""
		self._async_gen += 1
		gen = self._async_gen
		self._async_loaded = False
		self._async_error = None
		self._async_result = None
		self._async_done_fn = done_fn

		def runner():
			try:
				result, error = worker_fn(), None
			except Exception as e:
				result, error = None, e
			# Пишем в общие поля только если наше поколение всё ещё
			# актуально (не было нового startAsync / закрытия экрана).
			if gen == self._async_gen:
				self._async_result = result
				self._async_error = error
				self._async_loaded = True

		t = threading.Thread(target=runner)
		t.daemon = True
		t.start()
		self._poll.start(POLL_MS, False)

	def _onPollTick(self):
		if not self._async_loaded:
			return
		self._poll.stop()
		fn = self._async_done_fn
		self._async_done_fn = None
		if fn:
			fn(self._async_result, self._async_error)
