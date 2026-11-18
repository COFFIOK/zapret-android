package com.coffiok.zapret

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Telegram-прокси на телефоне: Telegram подключается к 127.0.0.1 как к MTProto-прокси,
 * а прокси переправляет трафик через WebSocket веб-версии Telegram (kwsN.web.telegram.org),
 * который провайдеры обычно не режут. Порт с tg-ws-proxy (Flowseal, MIT) — та же схема, что в Zapret GUI на ПК.
 */
object TgProxy {
    const val PORT = 2443
    private const val FRONT_SNI = "sprinthost.ru"           // «маскировка» соединения, как в tg-ws-proxy
    private const val WS_IP = "149.154.167.220"
    private val DC_IPS = mapOf(1 to "149.154.175.50", 2 to "149.154.167.51", 3 to "149.154.175.100", 4 to "149.154.167.91", 5 to "149.154.171.5", 203 to "91.105.192.100")
    private val TAGS = setOf(0xEFEFEFEF.toInt(), 0xEEEEEEEE.toInt(), 0xDDDDDDDD.toInt())
    private val rnd = SecureRandom()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var frontedFirst = true

    fun secret(ctx: Context): String {
        val sp = ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE)
        var s = sp.getString("tgSecret", null)
        if (s == null || !Regex("^[0-9a-f]{32}$").matches(s)) {
            s = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            sp.edit().putString("tgSecret", s).commit()
        }
        return s
    }

    fun link(ctx: Context) = "tg://proxy?server=127.0.0.1&port=$PORT&secret=dd${secret(ctx)}"

    fun start(secretHex: String, log: (String) -> Unit) {
        if (server != null) return
        val secret = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ss = try { ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { log("TG: порт $PORT занят: ${e.message}"); return }
        server = ss
        log("TG: прокси на 127.0.0.1:$PORT")
        thread(name = "tg-accept") {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                thread(name = "tg-client") { try { handle(c, secret, log) } catch (e: Exception) { } finally { try { c.close() } catch (e: Exception) { } } }
            }
        }
    }

    fun stop() { try { server?.close() } catch (e: Exception) { }; server = null }

    // ---- одно подключение Telegram ----
    private fun handle(c: Socket, secret: ByteArray, log: (String) -> Unit) {
        c.tcpNoDelay = true; c.soTimeout = 15000
        val cin = c.getInputStream(); val cout = c.getOutputStream()
        val hs = readN(cin, 64) ?: return
        val prekeyIv = hs.copyOfRange(8, 56)
        val cltDec = ctr(sha256(prekeyIv.copyOfRange(0, 32) + secret), prekeyIv.copyOfRange(32, 48))
        val dec = cltDec.update(hs)                                   // заодно проматывает первые 64 байта
        val tag = le32(dec, 56)
        if (tag !in TAGS) { log("TG: неверный секрет — переподключи прокси в Telegram"); return }
        var dc = ((dec[60].toInt() and 0xFF) or (dec[61].toInt() shl 8)).toShort().toInt()
        val media = dc < 0; dc = Math.abs(dc)
        val test = dc >= 10000; if (test) dc -= 10000
        val relayInit = relayInit(dec.copyOfRange(56, 60), if (media) -dc else dc)

        val rev = prekeyIv.reversedArray()
        val cltEnc = ctr(sha256(rev.copyOfRange(0, 32) + secret), rev.copyOfRange(32, 48))
        val tgEnc = ctr(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56)).also { it.update(ByteArray(64)) }
        val rr = relayInit.copyOfRange(8, 56).reversedArray()
        val tgDec = ctr(rr.copyOfRange(0, 32), rr.copyOfRange(32, 48))
        c.soTimeout = 0

        val ws = connectWs(dc, media, test)
        if (ws == null) {
            // запасной путь — напрямую к серверу Telegram (может быть заблокирован)
            val ip = DC_IPS[dc] ?: return
            val s = Socket(); s.connect(InetSocketAddress(ip, 443), 8000); s.tcpNoDelay = true
            log("TG: DC$dc напрямую (WebSocket недоступен)")
            s.use { up ->
                up.getOutputStream().write(relayInit)
                pipe(cin, { b, n -> up.getOutputStream().write(tgEnc.update(cltDec.update(b, 0, n))) }, { up.close() })
                val uin = up.getInputStream(); val buf = ByteArray(65536)
                while (true) { val n = uin.read(buf); if (n <= 0) break; cout.write(cltEnc.update(tgDec.update(buf, 0, n))) }
            }
            return
        }
        ws.use {
            ws.send(relayInit)
            val split = Splitter(tag)
            pipe(cin, { b, n ->
                val plain = cltDec.update(b, 0, n); val enc = tgEnc.update(plain)
                for (p in split.split(enc, plain)) ws.send(p)
            }, { ws.close() })
            while (true) {
                val data = ws.recv() ?: break
                cout.write(cltEnc.update(tgDec.update(data)))
            }
        }
    }

    /** Поток «клиент → сервер» в отдельной нитке. */
    private fun pipe(inp: InputStream, onData: (ByteArray, Int) -> Unit, onEnd: () -> Unit) {
        thread(name = "tg-up") {
            val buf = ByteArray(65536)
            try { while (true) { val n = inp.read(buf); if (n <= 0) break; onData(buf, n) } } catch (e: Exception) { } finally { onEnd() }
        }
    }

    private fun connectWs(dc: Int, media: Boolean, test: Boolean): Ws? {
        val d = if (dc == 203) 2 else dc
        val domains = if (media) listOf("kws$d-1.web.telegram.org", "kws$d.web.telegram.org") else listOf("kws$d.web.telegram.org")
        val path = if (test) "/apiws_test" else "/apiws"
        for (domain in domains) for (fronted in if (frontedFirst) listOf(true, false) else listOf(false, true)) {
            try { val ws = Ws.connect(WS_IP, domain, path, if (fronted) FRONT_SNI else null); frontedFirst = fronted; return ws } catch (e: Exception) { }
        }
        return null
    }

    private fun relayInit(tag: ByteArray, dcIdx: Int): ByteArray {
        val r = ByteArray(64)
        while (true) {
            rnd.nextBytes(r)
            if ((r[0].toInt() and 0xFF) == 0xEF) continue
            val s4 = le32(r, 0)
            if (s4 in setOf(0x44414548, 0x54534F50, 0x20544547, 0xEEEEEEEE.toInt(), 0xDDDDDDDD.toInt(), 0x02010316, 0x4954504F)) continue
            if (le32(r, 4) == 0) continue
            break
        }
        val enc = ctr(r.copyOfRange(8, 40), r.copyOfRange(40, 56)).update(r)
        val tail = tag + byteArrayOf((dcIdx and 0xFF).toByte(), ((dcIdx shr 8) and 0xFF).toByte()) + ByteArray(2).also { rnd.nextBytes(it) }
        val out = r.copyOf()
        for (i in 0 until 8) out[56 + i] = (tail[i].toInt() xor (enc[56 + i].toInt() xor r[56 + i].toInt())).toByte()
        return out
    }

    /** Режет поток на целые пакеты MTProto: каждый уходит отдельным кадром WebSocket. */
    private class Splitter(private val tag: Int) {
        private var cipher = ByteArray(0); private var plain = ByteArray(0); private var off = false
        fun split(enc: ByteArray, pl: ByteArray): List<ByteArray> {
            if (off) return listOf(enc)
            cipher += enc; plain += pl
            val out = mutableListOf<ByteArray>(); var o = 0
            while (o < cipher.size) {
                val len = next(o, cipher.size - o) ?: break
                if (len <= 0) { out.add(cipher.copyOfRange(o, cipher.size)); o = cipher.size; off = true; break }
                out.add(cipher.copyOfRange(o, o + len)); o += len
            }
            if (o > 0) { cipher = cipher.copyOfRange(o, cipher.size); plain = plain.copyOfRange(o, plain.size) }
            return out
        }
        private fun next(o: Int, avail: Int): Int? {
            if (tag == 0xEFEFEFEF.toInt()) {
                val first = plain[o].toInt() and 0xFF
                val (len, hdr) = if (first == 0x7F || first == 0xFF) {
                    if (avail < 4) return null
                    ((plain[o + 1].toInt() and 0xFF) or ((plain[o + 2].toInt() and 0xFF) shl 8) or ((plain[o + 3].toInt() and 0xFF) shl 16)) * 4 to 4
                } else (first and 0x7F) * 4 to 1
                if (len <= 0) return 0
                return if (avail < hdr + len) null else hdr + len
            }
            if (avail < 4) return null
            val len = le32(plain, o) and 0x7FFFFFFF
            if (len <= 0) return 0
            return if (avail < 4 + len) null else 4 + len
        }
    }

    // ---- WebSocket (двоичные кадры) ----
    private class Ws(private val s: SSLSocket) : AutoCloseable {
        private val inp = s.inputStream.buffered(65536)
        private val out = s.outputStream
        companion object {
            fun connect(ip: String, domain: String, path: String, sni: String?): Ws {
                val raw = Socket(); raw.connect(InetSocketAddress(ip, 443), 8000); raw.tcpNoDelay = true
                val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni ?: domain, 443, true) as SSLSocket
                s.soTimeout = 10000
                s.startHandshake()
                if (sni == null && !HttpsURLConnection.getDefaultHostnameVerifier().verify(domain, s.session)) { s.close(); throw IllegalStateException("cert") }
                val key = android.util.Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
                s.outputStream.write(("GET $path HTTP/1.1\r\nHost: $domain\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n").toByteArray())
                val ws = Ws(s)
                val status = ws.readLine()
                if (!status.contains(" 101")) { s.close(); throw IllegalStateException(status) }
                while (ws.readLine().isNotEmpty()) { }
                s.soTimeout = 0
                return ws
            }
        }
        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) { val b = inp.read(); if (b < 0) throw IllegalStateException("eof"); if (b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
            return sb.toString()
        }
        @Synchronized fun send(data: ByteArray, op: Int = 2) {
            val n = data.size
            val hdr = java.io.ByteArrayOutputStream()
            hdr.write(0x80 or op)
            when { n < 126 -> hdr.write(0x80 or n); n < 65536 -> { hdr.write(0x80 or 126); hdr.write(n shr 8); hdr.write(n and 0xFF) }
                else -> { hdr.write(0x80 or 127); for (i in 7 downTo 0) hdr.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) } }
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            hdr.write(mask)
            val body = ByteArray(n) { (data[it].toInt() xor mask[it and 3].toInt()).toByte() }
            out.write(hdr.toByteArray() + body); out.flush()
        }
        /** Следующее сообщение; null — соединение закрыто. */
        fun recv(): ByteArray? {
            var acc: java.io.ByteArrayOutputStream? = null
            while (true) {
                val b0 = inp.read(); val b1 = inp.read()
                if (b0 < 0 || b1 < 0) return null
                val op = b0 and 0x0F; val fin = b0 and 0x80 != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((inp.read() shl 8) or inp.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or inp.read().toLong() } }
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(it) } else null
                val p = ByteArray(len.toInt()); readFully(p)
                if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (op) {
                    8 -> return null
                    9 -> { send(p, 10); continue }
                    10 -> continue
                }
                if (fin && acc == null) return p
                if (acc == null) acc = java.io.ByteArrayOutputStream()
                acc.write(p)
                if (fin) return acc.toByteArray()
            }
        }
        private fun readFully(b: ByteArray) { var o = 0; while (o < b.size) { val n = inp.read(b, o, b.size - o); if (n < 0) throw IllegalStateException("eof"); o += n } }
        override fun close() { try { s.close() } catch (e: Exception) { } }
    }

    // ---- мелочи ----
    private fun readN(i: InputStream, n: Int): ByteArray? { val b = ByteArray(n); var o = 0; while (o < n) { val r = i.read(b, o, n - o); if (r < 0) return null; o += r }; return b }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun ctr(key: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    @Suppress("unused") private fun OutputStream.w(b: ByteArray) = write(b)
}
