# -*- coding: utf-8 -*-
class HdRezkaStream(object):
	def __init__(self, season, episode, name, translator_id, subtitles=None):
		if subtitles is None: subtitles = {}
		self._videos = {}
		self.season = season
		self.episode = episode
		self.name = name
		self.translator_id = translator_id
		self.subtitles = HdRezkaStreamSubtitles(**subtitles)

	@property
	def videos(self): return self._videos

	def append(self, resolution, link):
		if resolution in self._videos.keys():
			self._videos[resolution].append(link)
		else:
			self._videos[resolution] = [link]

	def __call__(self, resolution):
		coincidences = list(filter(lambda x: str(resolution) in x, self._videos))
		if len(coincidences) > 0:
			return self._videos[coincidences[0]]
		raise ValueError('Resolution "%s" is not defined' % resolution)

	def __str__(self):
		resolutions = list(self._videos.keys())
		if self.subtitles.subtitles:
			return "<HdRezkaStream> : %s, subtitles=%s" % (resolutions, self.subtitles)
		return "<HdRezkaStream> : " + str(resolutions)

	def __repr__(self):
		return "<HdRezkaStream(season:%s, episode:%s)>" % (self.season, self.episode)

class HdRezkaStreamSubtitles(object):
	def __init__(self, data, codes):
		self.subtitles = {}
		self.keys = []
		if data:
			arr = data.split(",")
			for i in arr:
				temp = i.split("[")[1].split("]")
				lang = temp[0]
				link = temp[1]
				code = codes[lang]
				self.subtitles[code] = {'title': lang, 'link': link}
			self.keys = list(self.subtitles.keys())
	def __str__(self):
		return str(self.keys)
	def __repr__(self):
		return str(self.keys)
	def __call__(self, id=None):
		if self.subtitles:
			if id:
				if id in self.subtitles.keys():
					return self.subtitles[id]['link']
				for key, value in self.subtitles.items():
					if value['title'] == id:
						return self.subtitles[key]['link']
				# BUGFIX: было `str(id).isdigit` (ссылка на метод, всегда True).
				# Должно быть `str(id).isdigit()` — реальная проверка.
				if str(id).isdigit():
					code = list(self.subtitles.keys())[int(id)]
					return self.subtitles[code]['link']
				raise ValueError('Subtitles "%s" is not defined' % id)
			else:
				return None
