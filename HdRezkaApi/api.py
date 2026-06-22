# -*- coding: utf-8 -*-
import requests
import base64
from itertools import product
from urlparse import urlparse
import time
import re

from ._compat import cached_property
from .session_pool import get_session
from .stream import HdRezkaStream
from .types import BeautifulSoupCustom, make_soup
from .types import (TVSeries, Movie)
from .types import (Film, Series, Cartoon, Anime)
from .types import (HdRezkaFormat, HdRezkaCategory)
from .types import (HdRezkaRating, HdRezkaEmptyRating)
from .types import default_cookies, default_headers
from .types import (default_translators_priority, default_translators_non_priority)
from .errors import (LoginRequiredError, LoginFailed, FetchFailed, CaptchaError, HTTP)


# Таймаут по умолчанию на ВСЕ сетевые запросы. Без него зависшее зеркало
# вешает фоновый поток Enigma2 навсегда, а polling-таймер крутится впустую —
# для пользователя это выглядит как "плагин намертво повис".
DEFAULT_TIMEOUT = 20


# ── Предвычисленный набор "мусорных" base64-кодов ────────────────────────────
def _build_trash_codes():
	trash_list = ["@", "#", "!", "^", "$"]
	codes = []
	for i in range(2, 4):
		for chars in product(trash_list, repeat=i):
			combo = base64.b64encode("".join(chars).encode("utf-8"))
			codes.append(combo.decode("utf-8") if isinstance(combo, bytes) else combo)
	return codes

_TRASH_CODES = _build_trash_codes()

# Один проход regex вместо 30 последовательных .replace() по длинной строке.
_TRASH_RE = re.compile("|".join(re.escape(c) for c in _TRASH_CODES))

# Скомпилированные один раз regex
_HTML_TAG_RE = re.compile(r'<[^>]*>')
_DIGITS_RE = re.compile(r'\d+')


class HdRezkaApi(object):
	def __init__(self, url, proxy=None, headers=None, cookies=None,
		translators_priority=None, translators_non_priority=None, session=None
	):
		if proxy is None: proxy = {}
		if headers is None: headers = {}
		if cookies is None: cookies = {}
		self.url = url.split(".html")[0] + ".html"
		uri = urlparse(url)
		self.origin = "%s://%s" % (uri.scheme, uri.netloc)
		self.proxy = proxy
		self.cookies = dict(default_cookies, **cookies)
		self.HEADERS = dict(default_headers, **headers)
		self._translators_priority = translators_priority or default_translators_priority
		self._translators_non_priority = translators_non_priority or default_translators_non_priority

		# По умолчанию берём ОБЩИЙ пул сессий (keep-alive, без лишних TLS).
		# Внешний session по-прежнему можно передать явно.
		if session is None:
			session = get_session()
		self.session = session

		# Ленивый кэш переводов для seriesInfo
		self._series_info_cache = {}

	def __str__(self): return 'HdRezka("%s")' % self.name
	def __repr__(self): return str(self)

	# ── helpers поверх session ──────────────────────────────────────────────
	def _get(self, url, **kw):
		kw.setdefault("headers", self.HEADERS)
		kw.setdefault("proxies", self.proxy)
		kw.setdefault("cookies", self.cookies)
		kw.setdefault("timeout", DEFAULT_TIMEOUT)
		return self.session.get(url, **kw)

	def _post(self, url, **kw):
		kw.setdefault("headers", self.HEADERS)
		kw.setdefault("proxies", self.proxy)
		kw.setdefault("cookies", self.cookies)
		kw.setdefault("timeout", DEFAULT_TIMEOUT)
		return self.session.post(url, **kw)

	@property
	def translators_priority(self):
		return self._translators_priority

	@translators_priority.setter
	def translators_priority(self, value):
		self._translators_priority = value or []

	@property
	def translators_non_priority(self):
		return self._translators_non_priority

	@translators_non_priority.setter
	def translators_non_priority(self, value):
		self._translators_non_priority = value or []

	@property
	def ok(self):
		try: return True if self.soup else False
		except Exception: return False

	@property
	def exception(self):
		if not self.ok:
			try: self.soup
			except Exception as e: return e

	def login(self, email, password, raise_exception=True):
		response = self._post("%s/ajax/login/" % self.origin,
			data={"login_name": email, "login_password": password})
		data = response.json()
		if data['success']:
			self.cookies = dict(self.cookies, **response.cookies.get_dict())
			return True
		if raise_exception: raise LoginFailed(data.get("message"))

	@staticmethod
	def make_cookies(user_id, password_hash):
		"""Build cookies helper"""
		return {"dle_user_id": str(user_id), "dle_password": password_hash}

	@cached_property
	def page(self):
		r = self._get(self.url, allow_redirects=True)
		if r.ok: return r
		raise HTTP(r.status_code, r.reason)

	@cached_property
	def soup(self):
		s = BeautifulSoupCustom(self.page.content)
		if s.title.text == "Sign In": raise LoginRequiredError()
		if s.title.text == "Verify": raise CaptchaError()
		return s

	@cached_property
	def id(self):
		def get_val(el, attr): return el.attrs.get(attr) if el else None
		return int(
			get_val(self.soup.find(id="post_id"), 'value') or
			get_val(self.soup.find(id="send-video-issue"), 'data-id') or
			get_val(self.soup.find(id="user-favorites-holder"), 'data-post_id') or
			self.url.split("/")[-1].split("-")[0]
		)

	@cached_property
	def name(self): return self.names[0]

	@cached_property
	def names(self):
		return [s.strip() for s in
			self.soup.find(class_="b-post__title").get_text().split("/")]

	@cached_property
	def origName(self):
		return self.origNames[-1] if self.origNames else None

	@cached_property
	def origNames(self):
		el = self.soup.find(class_="b-post__origtitle")
		if el:
			return [s.strip() for s in el.get_text().split("/")]
		return []

	@cached_property
	def description(self):
		el = self.soup.find(class_="b-post__description_text")
		return el.get_text().strip() if el else ""

	@cached_property
	def thumbnail(self):
		return self.soup.find(class_="b-sidecover").find('img').attrs['src']

	@cached_property
	def thumbnailHQ(self):
		return self.soup.find(class_="b-sidecover").find('a').attrs['href']

	@cached_property
	def releaseYear(self):
		el = self.soup.select_one('.b-content__main .b-post__info a[href*="/year/"]')
		if el:
			match = re.search(r'\d{4}', el.get('href', ''))
			if match: return int(match.group(0))
		return None

	@cached_property
	def type(self):
		type_str = self.soup.find('meta', property="og:type").attrs['content']
		if type_str == "video.tv_series": return TVSeries()
		if type_str == "video.movie": return Movie()
		return HdRezkaFormat(type_str)

	@cached_property
	def category(self):
		uri = urlparse(self.url)
		cat = uri.path.lstrip("/").split("/")[0]
		if cat == 'films': return Film()
		if cat == 'series': return Series()
		if cat == 'cartoons': return Cartoon()
		if cat == 'animation': return Anime()
		return HdRezkaCategory(cat)

	@cached_property
	def rating(self):
		wraper = self.soup.find(class_='b-post__rating')
		if wraper:
			rating = wraper.find(class_='num').get_text()
			votes = wraper.find(class_='votes').get_text().strip("()")
			return HdRezkaRating(value=float(rating), votes=int(votes))
		else:
			return HdRezkaEmptyRating()

	@cached_property
	def translators(self):
		arr = {}
		translators = self.soup.find(id="translators-list")
		if translators:
			for child in translators.findChildren(recursive=False):
				id = int(child.attrs['data-translator_id'])
				name = child.text.strip()
				premium = 'b-prem_translator' in child['class']
				img = child.find('img')
				if img:
					lang = img.attrs.get('title')
					if lang and lang not in name:
						name += " (%s)" % lang
				arr[id] = {"name": name, "premium": premium}

		if not arr:
			# auto-detect (один перевод, без списка)
			def getTranslationName(s):
				table = s.find(class_="b-post__info")
				for tr in table.findAll("tr"):
					if "переводе" in tr.get_text():
						td = tr.find_all("td")[-1]
						return td.get_text().strip()
			def getTranslationID(s):
				initCDNEvents = {'video.tv_series': 'initCDNSeriesEvents',
								 'video.movie'    : 'initCDNMoviesEvents'}
				tmp = s.text.split("sof.tv.%s" % initCDNEvents['video.%s' % self.type.name])[-1].split("{")[0]
				return int(tmp.split(",")[1].strip())

			arr[getTranslationID(self.page)] = {"name": getTranslationName(self.soup), "premium": False}
		return arr

	def sort_translators(self, translators=None, priority=None, non_priority=None):
		prior = {}
		for index, item in enumerate(priority if isinstance(priority, list) else self._translators_priority or []):
			prior[item] = index + 1

		max_index = len(prior) + 1

		for index, item in enumerate(non_priority if isinstance(non_priority, list) else self._translators_non_priority or []):
			if item not in prior:
				prior[item] = max_index + index + 1

		sorted_translators = dict(sorted((translators or self.translators).items(),
			key=lambda item: prior.get(item[0], max_index)))
		return sorted_translators

	@cached_property
	def translators_names(self):
		result = {}
		for k, v in self.translators.items():
			result[v["name"]] = {"id": k, "premium": v["premium"]}
		return result

	@staticmethod
	def clearTrash(data):
		arr = data.replace("#h", "").split("//_//")
		trashString = ''.join(arr)
		trashString = _TRASH_RE.sub('', trashString)
		try:
			finalString = base64.b64decode(trashString + "==")
			return finalString.decode("utf-8")
		except Exception:
			return trashString

	@cached_property
	def otherParts(self):
		parts = self.soup.find(class_="b-post__partcontent")
		other = []
		if parts:
			for i in parts.findAll(class_="b-post__partcontent_item"):
				if 'current' in i.attrs['class']:
					other.append({i.find(class_="title").text: self.url})
				else:
					other.append({i.find(class_="title").text: i.attrs['data-url']})
		return other

	@staticmethod
	def getEpisodes(s, e):
		seasons = make_soup(s)
		episodes = make_soup(e)

		seasons_ = {}
		for season in seasons.findAll(class_="b-simple_season__item"):
			seasons_[int(season.attrs['data-tab_id'])] = season.text

		episodes_ = {}
		for episode in episodes.findAll(class_="b-simple_episode__item"):
			season_id = int(episode.attrs['data-season_id'])
			episode_id = int(episode.attrs['data-episode_id'])
			if season_id in episodes_:
				episodes_[season_id][episode_id] = episode.text
			else:
				episodes_[season_id] = {episode_id: episode.text}

		return seasons_, episodes_

	# ── Ленивая загрузка эпизодов отдельного перевода ────────────────────────
	def _fetch_translator_series(self, tr_id):
		if tr_id in self._series_info_cache:
			return self._series_info_cache[tr_id]
		tr_val = self.translators[tr_id]
		js = {"id": self.id, "translator_id": tr_id, "action": "get_episodes"}
		r = self._post("%s/ajax/get_cdn_series/" % self.origin, data=js)
		response = r.json()
		result = None
		if response['success']:
			seasons, episodes = self.getEpisodes(response['seasons'], response['episodes'])
			result = {
				"translator_name": tr_val["name"],
				"premium": tr_val["premium"],
				"seasons": seasons, "episodes": episodes
			}
		self._series_info_cache[tr_id] = result
		return result

	def seriesInfoFor(self, tr_id):
		"""Публичный доступ к данным одного перевода (для веб-ремоута)."""
		if self.type != TVSeries:
			raise ValueError("Only available for TVSeries.")
		return self._fetch_translator_series(int(tr_id))

	@cached_property
	def seriesInfo(self):
		if self.type != TVSeries:
			raise ValueError("The `seriesInfo` attribute is only available for TVSeries.")
		arr = {}
		for tr_id in self.translators.keys():
			data = self._fetch_translator_series(tr_id)
			if data is not None:
				arr[tr_id] = data
		return arr

	@cached_property
	def episodesInfo(self):
		if self.type != TVSeries:
			raise ValueError("The `episodesInfo` attribute is only available for TVSeries.")
		output_data = []
		for translator_id, translator_info in self.seriesInfo.items():
			translator_name = translator_info["translator_name"]
			premium = translator_info["premium"]
			for season, season_text in translator_info["seasons"].items():
				season_obj = next((s for s in output_data if s["season"] == int(season)), None)
				if not season_obj:
					season_obj = {
						"season": int(season),
						"season_text": season_text,
						"episodes": []
					}
					output_data.append(season_obj)

				for episode, episode_text in translator_info["episodes"].get(season, {}).items():
					episode_obj = next((e for e in season_obj["episodes"] if e["episode"] == int(episode)), None)
					if not episode_obj:
						episode_obj = {
							"episode": int(episode),
							"episode_text": episode_text,
							"translations": []
						}
						season_obj["episodes"].append(episode_obj)

					episode_obj["translations"].append({
						"translator_id": translator_id,
						"translator_name": translator_name,
						"premium": premium
					})
		return output_data

	def getStream(self, season=None, episode=None, translation=None,
		priority=None, non_priority=None
	):
		def strip_html(html): return _HTML_TAG_RE.sub('', html)
		def makeRequest(data):
			r = self._post("%s/ajax/get_cdn_series/" % self.origin, data=data)
			r = r.json()
			if r['success'] and r['url']:
				arr = self.clearTrash(r['url']).split(",")
				stream = HdRezkaStream(season=season, episode=episode,
										name=self.name, translator_id=data['translator_id'],
										subtitles={'data': r['subtitle'], 'codes': r['subtitle_lns']}
									)
				for i in arr:
					temp = i.split("[")[1].split("]")
					quality = strip_html(temp[0])
					links = [x for x in temp[1].split(" or ") if x.endswith(".mp4")]
					for video in links:
						stream.append(quality, video)
				return stream
			raise FetchFailed()

		def getStreamSeries(self, season, episode, translation_id):
			return makeRequest({
				"id": self.id,
				"translator_id": translation_id,
				"season": season,
				"episode": episode,
				"action": "get_stream"
			})

		def getStreamMovie(self, translation_id):
			return makeRequest({
				"id": self.id,
				"translator_id": translation_id,
				"action": "get_movie"
			})

		def get_translator_id(translators):
			translators_dict = {}
			for translator in translators:
				translators_dict[translator['translator_id']] = {
					'name': translator['translator_name'],
					'premium': translator['premium']
				}

			if translation:
				if str(translation).isdigit():
					if int(translation) in translators_dict:
						return int(translation)
					else:
						raise ValueError('Translation with code "%s" is not defined' % translation)
				elif any(d['translator_name'] == translation for d in translators):
					return next((d['translator_id'] for d in translators if d['translator_name'] == translation), None)
				else:
					raise ValueError('Translation "%s" is not defined' % translation)
			else:
				return list(
					self.sort_translators(translators_dict, priority=priority, non_priority=non_priority).keys()
				)[0]

		if self.type == TVSeries:
			if season and episode:
				episodes = next((s['episodes'] for s in self.episodesInfo if s['season'] == int(season)), None)
				if not episodes:
					raise ValueError('Season "%s" is not found!' % season)

				translators = next((e['translations'] for e in episodes if e['episode'] == int(episode)), None)
				if not translators:
					raise ValueError('Episode "%s" in season "%s" is not found!' % (episode, season))

				tr_id = get_translator_id(translators)
				return getStreamSeries(self, int(season), int(episode), tr_id)
			elif season and (not episode):
				raise TypeError("getStream() missing one required argument (episode)")
			elif episode and (not season):
				raise TypeError("getStream() missing one required argument (season)")
			else:
				raise TypeError("getStream() missing required arguments (season and episode)")
		elif self.type == Movie:
			translators = [{'translator_id': id, 'translator_name': details['name'], 'premium': details['premium']} for id, details in self.translators.items()]
			tr_id = get_translator_id(translators)
			return getStreamMovie(self, tr_id)
		else:
			raise TypeError("Undefined content type")

	def getSeasonStreams(self, season, translation=None,
		priority=None, non_priority=None,
		ignore=False, progress=None
	):
		if not progress: progress = lambda cur, all: None
		streams = {}

		def get_episodes_by_translator(data):
			result = {}
			for item in data:
				episode = item['episode']
				for translation_item in item['translations']:
					translator_id = translation_item['translator_id']
					translator_name = translation_item['translator_name']
					if translator_id not in result:
						result[translator_id] = {'translator_name': translator_name, 'episodes': []}
					result[translator_id]['episodes'].append(episode)
			return result

		def get_translator_id(translators):
			if translation:
				if str(translation).isdigit():
					if int(translation) in translators.keys():
						return int(translation)
					else:
						raise ValueError('Translation with code "%s" is not defined' % translation)
				elif any(v['translator_name'] == translation for k, v in translators.items()):
					return next((k for k, v in translators.items() if v['translator_name'] == translation), None)
				else:
					raise ValueError('Translation "%s" is not defined' % translation)
			else:
				return list(
					self.sort_translators(translators, priority=priority, non_priority=non_priority).keys()
				)[0]

		episodes = next((s['episodes'] for s in self.episodesInfo if s['season'] == int(season)), None)
		if not episodes: raise ValueError('Season "%s" is not found!' % season)

		episodes_data = get_episodes_by_translator(episodes)
		tr_id = get_translator_id(episodes_data)
		series = episodes_data[tr_id]['episodes']
		series_length = len(series)
		progress(0, series_length)

		def make_call(episode, retry=True):
			try:
				stream = self.getStream(season, episode, tr_id)
				streams[episode] = stream
				progress(len(streams), series_length)
				return stream
			except Exception as e:
				if retry:
					time.sleep(1)
					if ignore:
						return make_call(episode)
					else:
						return make_call(episode, retry=False)
				if not ignore:
					ex_name = e.__class__.__name__
					ex_desc = e
					print("%s > ep:%s: %s" % (ex_name, episode, ex_desc))
					streams[episode] = None
					progress(len(streams), series_length)

		for episode in series:
			yield episode, make_call(episode)
