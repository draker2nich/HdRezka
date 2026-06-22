# -*- coding: utf-8 -*-
"""
webserver.py — HTTP + UDP broadcast сервер для управления с телефона.

Запускается вместе с плагином. Телефон открывает страницу (встроена в сервер) —
страница слушает UDP broadcast и автоматически находит ресивер.

Оптимизации против исходной версии:
  * состояние просмотра привязано к URL фильма, а не к одному глобальному
    синглтону → два клиента/повторные тапы не затирают друг друга;
  * /info для сериала грузит ТОЛЬКО первый перевод (а не все N), что убирает
    основной лаг на медленном железе;
  * LRU-кэш загруженных HdRezkaApi объектов;
  * общий requests.Session под капотом (см. api.py).
"""

import threading
import socket
import json
import time

try:
    from BaseHTTPServer import BaseHTTPRequestHandler, HTTPServer
    from SocketServer import ThreadingMixIn
    from urlparse import urlparse, parse_qs
    from urllib import unquote
except ImportError:
    from http.server import BaseHTTPRequestHandler, HTTPServer
    from socketserver import ThreadingMixIn
    from urllib.parse import urlparse, parse_qs, unquote

from .config import HDREZKA_ORIGIN

HTTP_PORT  = 8888
UDP_PORT   = 8889
BROADCAST_INTERVAL = 5   # секунд между рассылками
MOVIE_CACHE_MAX = 6      # сколько загруженных фильмов держим в памяти

# ── глобальное состояние ─────────────────────────────────────────────────────
_lock = threading.Lock()
_play_cb = None                 # callback(url, title) → запускает плеер
_movie_cache = {}               # canon_url -> HdRezkaApi
_movie_order = []               # порядок для LRU-вытеснения


def set_play_callback(cb):
    global _play_cb
    with _lock:
        _play_cb = cb


def _canon(url):
    """Канонический ключ фильма — без .html-хвоста и query."""
    return url.split(".html")[0]


def _cache_get(url):
    with _lock:
        return _movie_cache.get(_canon(url))


def _cache_put(url, rezka):
    key = _canon(url)
    with _lock:
        if key not in _movie_cache:
            _movie_order.append(key)
        _movie_cache[key] = rezka
        while len(_movie_order) > MOVIE_CACHE_MAX:
            old = _movie_order.pop(0)
            _movie_cache.pop(old, None)


# ── вспомогательные функции ──────────────────────────────────────────────────

def _get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def _json(data):
    return json.dumps(data, ensure_ascii=False)


def _search(query):
    from .HdRezkaApi.search import HdRezkaSearch
    searcher = HdRezkaSearch(HDREZKA_ORIGIN)
    return searcher(query, find_all=False)


def _load_movie(url):
    """Грузит фильм с кэшированием по URL."""
    cached = _cache_get(url)
    if cached is not None:
        return cached
    from .HdRezkaApi.api import HdRezkaApi
    r = HdRezkaApi(url)
    if not r.ok:
        raise Exception(str(r.exception))
    _cache_put(url, r)
    return r


# ── HTML страница ─────────────────────────────────────────────────────────────

HTML = u"""<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>HDRezka Remote</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
     background:#111;color:#eee;padding:12px}
h1{font-size:1.3em;color:#e50914;margin-bottom:14px;text-align:center}
input,select{width:100%;padding:10px;border-radius:8px;border:none;
             background:#222;color:#eee;font-size:1em;margin-bottom:10px}
button{width:100%;padding:12px;border-radius:8px;border:none;
       background:#e50914;color:#fff;font-size:1em;font-weight:bold;
       cursor:pointer;margin-bottom:8px}
button.sec{background:#333}
button:active{opacity:.7}
.card{background:#1a1a1a;border-radius:10px;padding:12px;margin-bottom:8px;cursor:pointer}
.card:active{background:#2a2a2a}
.card h3{font-size:.95em;margin-bottom:4px}
.card small{color:#888;font-size:.8em}
.status{text-align:center;color:#888;font-size:.85em;padding:8px}
.err{color:#e50914}
.ok{color:#2ecc71}
#spinner{display:none;text-align:center;padding:20px;color:#888}
.tag{display:inline-block;background:#e50914;color:#fff;font-size:.7em;
     border-radius:4px;padding:2px 6px;margin-left:6px;vertical-align:middle}
.tag.prem{background:#f39c12}
</style>
</head>
<body>
<h1>HDRezka Remote</h1>

<div id="screen-search">
  <input id="q" type="search" placeholder="Название фильма или сериала..." autocomplete="off">
  <button onclick="doSearch()">Найти</button>
  <div id="spinner">Поиск...</div>
  <div id="results"></div>
</div>

<div id="screen-detail" style="display:none">
  <button class="sec" onclick="back('search')">Назад</button>
  <div id="detail-title" style="font-size:1.1em;font-weight:bold;margin-bottom:10px;text-align:center"></div>
  <div id="detail-info" class="status"></div>
  <div id="spinner2" class="status">Загрузка...</div>
  <div id="translators"></div>
</div>

<div id="screen-seasons" style="display:none">
  <button class="sec" onclick="back('detail')">Назад</button>
  <div id="seasons-title" class="status"></div>
  <div id="seasons-spinner" class="status" style="display:none">Загрузка сезонов...</div>
  <div id="seasons-list"></div>
</div>

<div id="screen-episodes" style="display:none">
  <button class="sec" onclick="back('seasons')">Назад</button>
  <div id="episodes-title" class="status"></div>
  <div id="episodes-list"></div>
</div>

<div id="screen-quality" style="display:none">
  <button class="sec" onclick="back('episodes')">Назад</button>
  <div id="quality-title" class="status"></div>
  <div id="quality-list"></div>
</div>

<div id="screen-playing" style="display:none">
  <div style="text-align:center;padding:30px">
    <div id="playing-title" style="margin:12px 0;font-size:1.1em;font-weight:bold"></div>
    <div class="status ok">Воспроизводится на ресивере</div>
  </div>
  <button onclick="back('search')">Новый поиск</button>
</div>

<script>
var BASE = '';
var _currentUrl = '';
var _currentTr  = null;
var _currentSeason = null;
var _isSeries   = false;
var _movieData  = null;
var _seasonsCache = {};   // trId -> seasons map (ленивая подгрузка per-translator)
var _qualityVideos = null;

function qualityKeysSorted(obj) {
  return Object.keys(obj).sort(function(a,b){
    var na = parseInt((a.match(/\\d+/)||[0])[0], 10) || 0;
    var nb = parseInt((b.match(/\\d+/)||[0])[0], 10) || 0;
    return nb - na;
  });
}

function show(id) {
  ['search','detail','seasons','episodes','quality','playing'].forEach(function(s){
    document.getElementById('screen-'+s).style.display = (s===id?'block':'none');
  });
}
function back(to) { show(to); }

function api(path, params, cb) {
  var qs = Object.keys(params||{}).map(function(k){
    return encodeURIComponent(k)+'='+encodeURIComponent(params[k]);
  }).join('&');
  fetch(BASE+path+(qs?'?'+qs:''))
    .then(function(r){return r.json();})
    .then(cb)
    .catch(function(e){ alert('Ошибка: '+e); });
}

function doSearch() {
  var q = document.getElementById('q').value.trim();
  if (!q) return;
  document.getElementById('spinner').style.display='block';
  document.getElementById('results').innerHTML='';
  api('/search', {q:q}, function(data) {
    document.getElementById('spinner').style.display='none';
    if (data.error) { document.getElementById('results').innerHTML='<div class="status err">'+data.error+'</div>'; return; }
    if (!data.results || !data.results.length) {
      document.getElementById('results').innerHTML='<div class="status">Ничего не найдено</div>'; return;
    }
    var html = '';
    data.results.forEach(function(item) {
      var rating = item.rating ? ' <small>★'+item.rating+'</small>' : '';
      html += '<div class="card" onclick="openMovie(\\''+escHtml(item.url)+'\\',\\''+escHtml(item.title)+'\\')"><h3>'+escHtml(item.title)+rating+'</h3></div>';
    });
    document.getElementById('results').innerHTML = html;
  });
}

function escHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

function openMovie(url, title) {
  _currentUrl = url;
  _seasonsCache = {};
  document.getElementById('detail-title').textContent = title;
  document.getElementById('detail-info').textContent = '';
  document.getElementById('translators').innerHTML = '';
  document.getElementById('spinner2').style.display='block';
  show('detail');
  api('/info', {url:url}, function(data) {
    document.getElementById('spinner2').style.display='none';
    if (data.error) { document.getElementById('detail-info').innerHTML='<span class="err">'+data.error+'</span>'; return; }
    _movieData = data;
    _isSeries  = data.is_series;
    if (data.rating) document.getElementById('detail-info').textContent = '★ '+data.rating;
    var html = '';
    Object.keys(data.translators).forEach(function(trId) {
      var name = data.translators[trId];
      html += '<div class="card" onclick="selectTranslator('+trId+',\\''+escHtml(name)+'\\')"><h3>'+escHtml(name)+'</h3></div>';
    });
    document.getElementById('translators').innerHTML = html || '<div class="status">Переводы не найдены</div>';
  });
}

function selectTranslator(trId, trName) {
  _currentTr = {id: trId, name: trName};
  if (_isSeries) {
    show('seasons');
    document.getElementById('seasons-title').textContent = trName;
    document.getElementById('seasons-list').innerHTML = '';
    // сезоны/эпизоды грузим ЛЕНИВО для выбранного перевода
    if (_seasonsCache[trId]) { renderSeasons(_seasonsCache[trId]); return; }
    document.getElementById('seasons-spinner').style.display='block';
    api('/seasons', {url:_currentUrl, translator:trId}, function(data){
      document.getElementById('seasons-spinner').style.display='none';
      if (data.error) { document.getElementById('seasons-list').innerHTML='<div class="status err">'+data.error+'</div>'; return; }
      _seasonsCache[trId] = data.seasons || {};
      renderSeasons(_seasonsCache[trId]);
    });
  } else {
    getQuality(null, null);
  }
}

function renderSeasons(seasons) {
  var html = '';
  Object.keys(seasons).map(Number).sort(function(a,b){return a-b;}).forEach(function(s) {
    html += '<div class="card" onclick="selectSeason('+s+')"><h3>'+escHtml(seasons[s].text || ('Сезон '+s))+'</h3></div>';
  });
  document.getElementById('seasons-list').innerHTML = html || '<div class="status">Сезоны не найдены</div>';
}

function selectSeason(season) {
  _currentSeason = season;
  var seasons = _seasonsCache[_currentTr.id] || {};
  var seasonData = seasons[season] || {episodes:{}};
  document.getElementById('episodes-title').textContent = 'Сезон '+season;
  var html = '';
  Object.keys(seasonData.episodes).map(Number).sort(function(a,b){return a-b;}).forEach(function(ep) {
    html += '<div class="card" onclick="getQuality('+season+','+ep+')"><h3>Эпизод '+ep+' — '+escHtml(seasonData.episodes[ep])+'</h3></div>';
  });
  document.getElementById('episodes-list').innerHTML = html || '<div class="status">Эпизоды не найдены</div>';
  show('episodes');
}

function getQuality(season, episode) {
  document.getElementById('quality-title').textContent = 'Загрузка...';
  document.getElementById('quality-list').innerHTML = '';
  show('quality');
  var params = {url:_currentUrl, translator:_currentTr.id};
  if (season !== null) { params.season = season; params.episode = episode; }
  api('/stream', params, function(data) {
    if (data.error) { document.getElementById('quality-title').innerHTML='<span class="err">'+data.error+'</span>'; return; }
    _qualityVideos = data.videos || {};
    document.getElementById('quality-title').textContent = 'Выберите качество';
    var html = '';
    qualityKeysSorted(_qualityVideos).forEach(function(q) {
      html += '<div class="card" onclick="play(\\''+escHtml(q)+'\\')"><h3>'+escHtml(q)+'</h3></div>';
    });
    document.getElementById('quality-list').innerHTML = html || '<div class="status">Ссылки не найдены</div>';
  });
}

function play(quality) {
  var urls = (_qualityVideos && _qualityVideos[quality]) || [];
  if (!urls.length) { alert('Нет ссылки для качества ' + quality); return; }
  var title = (_movieData && _movieData.title) || (_currentTr && _currentTr.name) || 'HDRezka';
  api('/play', {url: urls[0], title: title}, function(data) {
    if (data.error) { alert(data.error); return; }
    document.getElementById('playing-title').textContent = data.title || '';
    show('playing');
  });
}

document.getElementById('q').addEventListener('keydown', function(e){
  if (e.key==='Enter') doSearch();
});
</script>
</body>
</html>
"""

# ── HTTP обработчик ──────────────────────────────────────────────────────────

class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class Handler(BaseHTTPRequestHandler):

    protocol_version = "HTTP/1.1"   # keep-alive со стороны телефона

    def log_message(self, fmt, *args):
        pass  # тихий режим

    def _send(self, code, content_type, body):
        if isinstance(body, unicode):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json_ok(self, data):
        self._send(200, "application/json", _json(data))

    def _json_err(self, msg):
        self._json_ok({"error": msg})

    def do_GET(self):
        parsed = urlparse(self.path)
        path   = parsed.path
        params = {k: v[0] for k, v in parse_qs(parsed.query).items()}

        if path == "/" or path == "/index.html":
            self._send(200, "text/html", HTML)

        elif path == "/ping":
            self._json_ok({"ok": True})

        elif path == "/status":
            self._json_ok({"status": "ok", "ip": _get_local_ip(), "port": HTTP_PORT})

        elif path == "/search":
            self._handle_search(params)

        elif path == "/info":
            self._handle_info(params)

        elif path == "/seasons":
            self._handle_seasons(params)

        elif path == "/stream":
            self._handle_stream(params)

        elif path == "/play":
            self._handle_play(params)

        else:
            self._send(404, "text/plain", "Not found")

    # ── обработчики маршрутов ────────────────────────────────────────────────

    def _handle_search(self, params):
        q = params.get("q", "").strip()
        if not q:
            return self._json_err("Пустой запрос")
        try:
            self._json_ok({"results": _search(q)})
        except Exception as e:
            self._json_err(str(e))

    def _handle_info(self, params):
        url = params.get("url", "")
        if not url:
            return self._json_err("Нет URL")
        try:
            r = _load_movie(url)
            translators = r.sort_translators(r.translators)
            tr_map = {}
            for tid, info in translators.items():
                name = info["name"]
                if info.get("premium"):
                    name += " [PREMIUM]"
                tr_map[str(tid)] = name

            from .HdRezkaApi.types import TVSeries
            is_series = (r.type == TVSeries)

            rating = None
            try:
                if r.rating and r.rating.value:
                    rating = str(r.rating.value)
            except Exception:
                pass

            year = None
            try:
                year = r.releaseYear
            except Exception:
                pass

            # ВАЖНО: сезоны больше НЕ грузим здесь — это делалось по всем
            # переводам сразу и было главным тормозом /info для сериалов.
            # Сезоны подтянет /seasons лениво для выбранного перевода.
            self._json_ok({
                "title":       r.name or "",
                "year":        year,
                "is_series":   is_series,
                "rating":      rating,
                "translators": tr_map,
            })
        except Exception as e:
            self._json_err(str(e))

    def _handle_seasons(self, params):
        url   = params.get("url", "")
        tr_id = params.get("translator")
        if not url or not tr_id:
            return self._json_err("Нет url/translator")
        try:
            r = _load_movie(url)
            data = r.seriesInfoFor(int(tr_id))
            seasons_map = {}
            if data:
                seasons = data["seasons"]
                episodes = data["episodes"]
                for sn in sorted(seasons.keys()):
                    ep_dict = episodes.get(sn, {})
                    seasons_map[str(sn)] = {
                        "text":     "Сезон %s" % sn,
                        "episodes": {str(ep): ep_dict[ep] for ep in sorted(ep_dict.keys())},
                    }
            self._json_ok({"seasons": seasons_map})
        except Exception as e:
            self._json_err(str(e))

    def _handle_stream(self, params):
        url     = params.get("url", "")
        tr_id   = params.get("translator")
        season  = params.get("season")
        episode = params.get("episode")
        if not url or not tr_id:
            return self._json_err("Нет url/translator")
        tr_id   = int(tr_id)
        season  = int(season)  if season  else None
        episode = int(episode) if episode else None
        try:
            r = _load_movie(url)
            stream = r.getStream(season=season, episode=episode, translation=tr_id)

            import re as _re
            def qkey(q):
                m = _re.search(r'\d+', q)
                return int(m.group()) if m else 0

            videos = {}
            for k in stream.videos.keys():
                videos[k] = stream(k) or []

            best_key = sorted(videos.keys(), key=qkey, reverse=True)[0] if videos else None
            best_url = videos[best_key][0] if best_key and videos.get(best_key) else None

            self._json_ok({"videos": videos, "best_key": best_key, "best_url": best_url})
        except Exception as e:
            self._json_err(str(e))

    def _handle_play(self, params):
        url   = params.get("url", "")
        title = params.get("title", "HDRezka")
        if not url:
            return self._json_err("Нет URL")
        try:
            play_url = "%s#Referer=%s&User-Agent=Mozilla/5.0" % (url, HDREZKA_ORIGIN)
            with _lock:
                cb = _play_cb
            if cb:
                cb(play_url, title)
                self._json_ok({"ok": True, "title": title})
            else:
                self._json_err("Плеер не готов")
        except Exception as e:
            self._json_err(str(e))


# ── UDP broadcast ─────────────────────────────────────────────────────────────

class UDPBroadcaster(threading.Thread):
    """Каждые N секунд рассылает UDP broadcast — телефон находит ресивер сам."""

    def __init__(self):
        threading.Thread.__init__(self)
        self.daemon = True
        self._stop  = False

    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        while not self._stop:
            try:
                ip  = _get_local_ip()
                msg = json.dumps({"hdrezka": True, "ip": ip, "port": HTTP_PORT})
                sock.sendto(msg.encode("utf-8"), ("<broadcast>", UDP_PORT))
            except Exception:
                pass
            time.sleep(BROADCAST_INTERVAL)
        sock.close()

    def stop(self):
        self._stop = True


# ── основной запуск ───────────────────────────────────────────────────────────

_server    = None
_broadcaster = None

def start(play_callback):
    """Вызывается из plugin.py при старте Enigma2."""
    global _server, _broadcaster
    set_play_callback(play_callback)

    _server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
    t = threading.Thread(target=_server.serve_forever)
    t.daemon = True
    t.start()

    _broadcaster = UDPBroadcaster()
    _broadcaster.start()

    print("[HDRezka] Web server started: http://%s:%d" % (_get_local_ip(), HTTP_PORT))

def stop():
    global _server, _broadcaster
    if _server:
        try: _server.shutdown()
        except Exception: pass
    if _broadcaster:
        _broadcaster.stop()
