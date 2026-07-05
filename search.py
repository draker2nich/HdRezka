# -*- coding: utf-8 -*-
import requests
from urlparse import urlparse
from ._compat import cached_property
from .session_pool import get_session
from .types import default_cookies, default_headers, make_soup
from .types import (HdRezkaCategory, Film, Series, Cartoon, Anime)
from .errors import HTTP, LoginRequiredError, CaptchaError


DEFAULT_TIMEOUT = 20


try:
	requests.packages.urllib3.disable_warnings()
except Exception:
	pass


class HdRezkaSearch(object):
	def __init__(self, origin, proxy=None, headers=None, cookies=None, session=None):
		if proxy is None: proxy = {}
		if headers is None: headers = {}
		if cookies is None: cookies = {}
		uri = urlparse(origin)
		self.origin = "%s://%s" % (uri.scheme, uri.netloc)
		self.proxy = proxy
		self.cookies = dict(default_cookies, **cookies)
		self.HEADERS = dict(default_headers, **headers)
		# Общий пул сессий вместо новой сессии на каждый поиск.
		self.session = session if session is not None else get_session()

	def __call__(self, query, find_all=False):
		return self.advanced_search(query) if find_all else self.fast_search(query)

	def fast_search(self, query):
		r = self.session.post('%s/engine/ajax/search.php' % self.origin,
			data={'q': query}, headers=self.HEADERS, proxies=self.proxy,
			cookies=self.cookies, timeout=DEFAULT_TIMEOUT)
		if r.ok:
			soup = make_soup(r.content)
			results = []
			for item in soup.select('.b-search__section_list li'):
				title = item.find('span', class_='enty').get_text().strip()
				url = item.find('a').attrs['href']
				rating_span = item.find('span', class_='rating')
				rating = float(rating_span.get_text()) if rating_span else None
				results.append({"title": title, "url": url, "rating": rating})
			return results
		raise HTTP(r.status_code, r.reason)

	def advanced_search(self, query):
		return SearchResult(self.origin, query, proxy=self.proxy,
			cookies=self.cookies, headers=self.HEADERS, session=self.session)


class SearchResult(object):
	def __init__(self, origin, query, proxy=None, headers=None, cookies=None, session=None):
		self.origin = origin
		self.query = query
		self.proxy = proxy
		self.headers = headers
		self.cookies = cookies
		self.session = session if session is not None else get_session()
		self._page_cache = {}
	def __str__(self): return "SearchResult(%s)" % self.query
	def __len__(self): return len(self.all_pages)

	def __iter__(self):
		self.current_page = 1
		return self
	def next(self):  # Python 2 iterator protocol (Python 3: __next__)
		result = self.get_page(self.current_page)
		if result:
			self.current_page += 1
			return result
		raise StopIteration
	__next__ = next  # на случай если код где-то ожидает py3-style

	def __getitem__(self, key):
		if isinstance(key, int) and key >= 0:
			return self.get_page(key + 1)
		return self.all_pages[key]

	@cached_property
	def all(self): return [item for page in self for item in page]
	@cached_property
	def all_pages(self): return [page for page in self]

	def get_page(self, page):
		if page in self._page_cache:
			return self._page_cache[page]
		data = {
			'do': 'search',
			'subaction': 'search',
			'q': self.query,
			'page': page
		}
		# Используем общий пул (keep-alive) вместо голого requests.get.
		r = self.session.get('%s/search/' % self.origin, params=data,
			headers=self.headers, proxies=self.proxy, cookies=self.cookies,
			timeout=DEFAULT_TIMEOUT)
		# БАГФИКС: раньше при !r.ok молча кэшировался None — итерация
		# просто останавливалась на упавшей странице, и результаты были
		# неполными без единого признака ошибки (при этом fast_search в
		# той же ситуации честно бросал HTTP). Теперь поведение единое,
		# а ошибка не кэшируется — повторный запрос страницы возможен.
		if not r.ok:
			raise HTTP(r.status_code, r.reason)
		soup = make_soup(r.content)
		title = soup.title.text if soup.title else ""
		if title == "Sign In": raise LoginRequiredError()
		if title == "Verify": raise CaptchaError()
		result = None
		items = soup.find_all(class_='b-content__inline_item')
		if len(items) > 0: result = list(map(self.process_item, items))
		self._page_cache[page] = result
		return result

	@classmethod
	def process_item(cls, item):
		link = item.find(class_='b-content__inline_item-link').find('a')
		cover = item.find(class_='b-content__inline_item-cover').find('img')
		url = link.attrs['href']
		title = link.text.strip()
		image = cover.attrs['src']
		cat = item.find(class_='cat')
		type_ = cls.detect_type(list(filter(lambda x: x != 'cat', cat['class']))) if cat else None
		return {"title": title, "url": url, "image": image, "category": type_}

	@staticmethod
	def detect_type(classes):
		if 'films' in classes: return Film()
		if 'series' in classes: return Series()
		if 'cartoons' in classes: return Cartoon()
		if 'animation' in classes: return Anime()
		return HdRezkaCategory(classes[0])
