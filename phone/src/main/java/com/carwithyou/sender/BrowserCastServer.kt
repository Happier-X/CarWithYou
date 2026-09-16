package com.carwithyou.sender

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 车机免安装模式：手机开 HTTP 服务，车机用自带浏览器打开即可看。
 *
 * - GET /      → 全屏播放页（<img src=/video> + 点/滑回传）
 * - GET /video → MJPEG multipart/x-mixed-replace，任何浏览器原生支持
 * - GET /config→ {"w":1280,"h":720}
 * - GET /tap?x=0~1&y=0~1
 * - GET /swipe?x0=&y0=&x1=&y1=&dur=
 *
 * 无需车机装 APK。触摸走 HTTP GET，直接调 TouchInjectorService。
 */
class BrowserCastServer(val port: Int = 8080) {

    private var server: ServerSocket? = null
    private var job: Job? = null

    private val lock = Object()
    @Volatile private var latest: ByteArray? = null
    @Volatile private var seq: Long = 0

    @Volatile var videoW = 1280
    @Volatile var videoH = 720
    @Volatile var framesServed: Long = 0

    fun offerFrame(jpeg: ByteArray) {
        synchronized(lock) {
            latest = jpeg
            seq++
            lock.notifyAll()
        }
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                server = ServerSocket(port).also {
                    AppLog.i(TAG, "浏览器投屏已监听 $port，车机浏览器打开 http://192.168.43.1:$port/")
                }
                while (isActive) {
                    val sock = try {
                        server?.accept() ?: break
                    } catch (e: Exception) {
                        if (isActive) AppLog.w(TAG, "浏览器端口 accept 退出：${e.message}")
                        break
                    }
                    launch { handle(sock) }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "浏览器投屏端口 $port 起不来（被占？）：${e.message}", e)
            }
        }
    }

    fun stop() {
        try { job?.cancel() } catch (_: Exception) {}
        job = null
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = s.getOutputStream()
                val reqLine = try { reader.readLine() } catch (_: Exception) { null } ?: return
                // 读掉剩余 header，避免粘包
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) { }
                } catch (_: Exception) {}
                val parts = reqLine.split(" ")
                if (parts.size < 2) return
                val rawPath = parts[0].let { if (it == "GET" || it == "POST") parts[1] else parts[0] }
                val path = rawPath.substringBefore("?")
                val query = rawPath.substringAfter("?", "")
                when (path) {
                    "/" -> servePage(out)
                    "/config" -> serveText(out, """{"w":$videoW,"h":$videoH}""", "application/json")
                    "/tap" -> {
                        val q = parseQuery(query)
                        val x = q["x"]?.toFloatOrNull()
                        val y = q["y"]?.toFloatOrNull()
                        if (x != null && y != null) {
                            val svc = TouchInjectorService.instance
                            if (svc != null) svc.tap(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
                            else AppLog.wThrottle(TAG, "浏览器点了，但无障碍没开", 10_000)
                            serveText(out, "ok")
                        } else serveText(out, "bad args", code = 400)
                    }
                    "/swipe" -> {
                        val q = parseQuery(query)
                        val x0 = q["x0"]?.toFloatOrNull()
                        val y0 = q["y0"]?.toFloatOrNull()
                        val x1 = q["x1"]?.toFloatOrNull()
                        val y1 = q["y1"]?.toFloatOrNull()
                        val dur = q["dur"]?.toLongOrNull() ?: 300L
                        if (x0 != null && y0 != null && x1 != null && y1 != null) {
                            val svc = TouchInjectorService.instance
                            if (svc != null) svc.swipe(
                                x0.coerceIn(0f, 1f), y0.coerceIn(0f, 1f),
                                x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f), dur
                            )
                            else AppLog.wThrottle(TAG, "浏览器滑了，但无障碍没开", 10_000)
                            serveText(out, "ok")
                        } else serveText(out, "bad args", code = 400)
                    }
                    "/video" -> serveMjpeg(out)
                    else -> serveText(out, "not found", code = 404)
                }
            }
        } catch (e: Exception) {
            AppLog.wThrottle(TAG, "浏览器请求处理失败：${e.message}", 5_000)
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf("=")
            if (i <= 0) return@mapNotNull null
            try {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
            } catch (_: Exception) { null }
        }.toMap()
    }

    private fun serveText(out: OutputStream, body: String, mime: String = "text/plain", code: Int = 200) {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $mime; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun servePage(out: OutputStream) {
        val html = """
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>CarWithYou</title>
<style>
html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden;touch-action:none;
  font-family:system-ui,sans-serif;-webkit-user-select:none;user-select:none}
#v{position:fixed;inset:0;width:100vw;height:100vh;object-fit:contain;background:#000}
#bar{position:fixed;left:0;right:0;bottom:0;display:flex;gap:8px;justify-content:center;
  padding:10px;background:linear-gradient(transparent,rgba(0,0,0,.55));opacity:.9}
#bar button{background:rgba(255,255,255,.14);color:#fff;border:1px solid rgba(255,255,255,.25);
  border-radius:10px;padding:10px 18px;font-size:15px}
#tip{position:fixed;top:8px;left:0;right:0;text-align:center;color:#888;font-size:12px;
  pointer-events:none}
</style></head><body>
<div id="tip">CarWithYou · 点屏可控 · 双击全屏</div>
<img id="v" src="/video" alt="connecting…">
<div id="bar"><button onclick="fs()">全屏</button>
<button onclick="location.reload()">重连</button></div>
<script>
const img=document.getElementById('v');
function fs(){const e=document.documentElement;if(e.requestFullscreen)e.requestFullscreen();}
img.onerror=()=>{setTimeout(()=>{img.src='/video?t='+Date.now()},1000);};
// 把屏幕触点换算成视频帧归一化坐标（含黑边扣除，和原生协议一致）
function norm(ev){
  const r=img.getBoundingClientRect();
  const vw=img.naturalWidth||1280, vh=img.naturalHeight||720;
  const s=Math.min(r.width/vw,r.height/vh);
  const cw=vw*s, ch=vh*s, ox=r.left+(r.width-cw)/2, oy=r.top+(r.height-ch)/2;
  const x=(ev.clientX-ox)/cw, y=(ev.clientY-oy)/ch;
  if(x<0||y<0||x>1||y>1)return null;
  return [x.toFixed(4),y.toFixed(4)];
}
let down=null,downT=0;
img.addEventListener('pointerdown',e=>{down=norm(e);downT=Date.now();});
img.addEventListener('pointerup',e=>{
  const p=norm(e);if(!p)return;
  const dt=Date.now()-downT;
  if(down&&Math.hypot(p[0]-down[0],p[1]-down[1])>0.02){
    fetch('/swipe?x0='+down[0]+'&y0='+down[1]+'&x1='+p[0]+'&y1='+p[1]+'&dur='+Math.min(1000,Math.max(80,dt)));
  }else{
    fetch('/tap?x='+p[0]+'&y='+p[1]);
  }
  down=null;
});
img.addEventListener('dblclick',fs);
</script></body></html>
        """.trimIndent()
        serveText(out, html, "text/html")
    }

    private fun serveMjpeg(out: OutputStream) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(head.toByteArray())
        out.flush()
        var lastSeq = -1L
        // 每个浏览器连接独立循环；断开即抛异常退出
        while (true) {
            val frame: ByteArray?
            synchronized(lock) {
                while (seq == lastSeq) {
                    try { (lock as Object).wait(1500) } catch (_: Exception) { }
                    if (latest == null) continue
                    break
                }
                lastSeq = seq
                frame = latest
            }
            val f = frame ?: continue
            try {
                val h = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n"
                out.write(h.toByteArray())
                out.write(f)
                out.write("\r\n".toByteArray())
                out.flush()
                framesServed++
            } catch (_: Exception) {
                break // 浏览器关了/切后台
            }
        }
    }

    companion object {
        private const val TAG = "CarWithYou"
    }
}
