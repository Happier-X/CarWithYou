package com.carwithyou.lite

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val ms: Long, val text: String)

/**
 * 自用歌词抓取：本地优先 -> 网易云API。
 * 本地路径：/Music/lyrics/歌名-歌手.lrc ，准且不用流量。
 */
object LyricFetcher {
    private val cache = mutableMapOf<String, List<LyricLine>>()

    fun cacheLines(key: String, lines: List<LyricLine>) { cache[key] = lines }

    fun pickByProgress(key: String, posMs: Long): Pair<String, String> {
        val lines = cache[key] ?: return "" to ""
        if (lines.isEmpty()) return "" to ""
        var idx = lines.indexOfLast { it.ms <= posMs }
        if (idx < 0) idx = 0
        val l1 = lines.getOrNull(idx)?.text.orEmpty()
        val l2 = lines.getOrNull(idx + 1)?.text.orEmpty()
        return l1 to l2
    }

    suspend fun getLyricLines(ctx: Context, title: String, artist: String): List<LyricLine> {
        // 1. 本地
        readLocal(title, artist)?.let {
            if (it.isNotEmpty()) return it
            AppLog.wThrottle("Lyric", "本地 lrc 读到了但没解析出句子（格式不对？）：$title", 20_000)
        }
        // 2. 网络（自用，失败就返回空，不崩）
        return try {
            fetchFromNetease(title, artist)
        } catch (e: Exception) {
            AppLog.wThrottle("Lyric", "拉网络歌词失败：${e.message ?: e.javaClass.name}（$title - $artist）", 30_000)
            emptyList()
        }
    }

    private fun readLocal(title: String, artist: String): List<LyricLine>? {
        return try {
            val dir = File(Environment.getExternalStorageDirectory(), "Music/lyrics")
            val cands = listOf(
                File(dir, "$title-$artist.lrc"),
                File(dir, "$title.lrc"),
                File(dir, "$artist-$title.lrc")
            )
            val f = cands.firstOrNull { it.exists() } ?: return null
            parseLrc(f.readText())
        } catch (e: Exception) {
            AppLog.wThrottle("Lyric", "读本地歌词报错：${e.message}", 30_000)
            null
        }
    }

    private fun fetchFromNetease(title: String, artist: String): List<LyricLine> {
        val keyword = URLEncoder.encode("$title $artist", "UTF-8")
        val searchUrl = "https://music.163.com/api/search/get/?s=$keyword&type=1&limit=1"
        val searchJson = httpGet(searchUrl) ?: return emptyList()
        val songId = JSONObject(searchJson)
            .optJSONObject("result")
            ?.optJSONArray("songs")
            ?.optJSONObject(0)?.optLong("id") ?: return emptyList()
        val lyricJson = httpGet("https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1") ?: return emptyList()
        val lrc = JSONObject(lyricJson)
            .optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (lrc.isBlank()) return emptyList()
        return parseLrc(lrc)
    }

    private fun httpGet(urlStr: String): String? {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "CarWithYou-Lite")
        }
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLog.d("Lyric", "歌词接口不通：${e.message} <- ${urlStr.take(48)}")
            null
        } finally { c.disconnect() }
    }

    fun parseLrc(lrc: String): List<LyricLine> {
        val re = Regex("""\[(\d+):(\d+)[.:](\d+)\](.*)""")
        val out = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { line ->
            val m = re.find(line.trim()) ?: return@forEach
            val min = m.groupValues[1].toLongOrNull() ?: 0
            val sec = m.groupValues[2].toLongOrNull() ?: 0
            var ms3 = m.groupValues[3]
            // 兼容 2位/3位毫秒
            val ms = when (ms3.length) {
                2 -> ms3.toLongOrNull()?.times(10) ?: 0
                else -> ms3.take(3).toLongOrNull() ?: 0
            }
            val text = m.groupValues[4].trim()
            if (text.isNotEmpty()) out += LyricLine(min * 60_000 + sec * 1000 + ms, text)
        }
        return out.sortedBy { it.ms }
    }
}
