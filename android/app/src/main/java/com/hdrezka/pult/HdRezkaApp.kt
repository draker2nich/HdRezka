package com.hdrezka.pult

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hdrezka.pult.core.Net

/**
 * Coil грузит постеры через наш же OkHttp-клиент (общий с парсером): тот же
 * User-Agent, cookie и терпимость к сертификатам зеркал — иначе часть обложек
 * HDRezka не открывается (403/битый сертификат CDN).
 */
class HdRezkaApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Net.client)
            .crossfade(true)
            .build()
}
