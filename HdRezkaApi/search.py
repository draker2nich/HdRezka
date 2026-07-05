# -*- coding: utf-8 -*-
import requests
from urlparse import urlparse
from .session_pool import get_session
from .types import default_cookies, default_headers, make_soup
from .errors import HTTP


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

	def __call__(self, query):
		return self.fast_search(query)

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
