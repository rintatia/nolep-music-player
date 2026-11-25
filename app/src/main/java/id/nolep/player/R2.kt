package id.nolep.player

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Klien kecil Cloudflare R2 (API kompatibel S3, tanda tangan AWS SigV4). */
class R2(
    private val account: String,
    private val bucket: String,
    private val accessKey: String,
    private val secret: String
) {
    companion object {
        const val EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    private val host = "$account.r2.cloudflarestorage.com"

    private fun hmac(k: ByteArray, d: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(k, "HmacSHA256"))
        return m.doFinal(d.toByteArray(Charsets.UTF_8))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun enc(s: String, keepSlash: Boolean): String {
        val sb = StringBuilder()
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' || (keepSlash && ch == '/')) {
                sb.append(ch)
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun request(
        method: String,
        objKey: String?,
        query: Map<String, String>,
        payloadHash: String,
        extra: Map<String, String> = mapOf()
    ): HttpURLConnection {
        val df = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        val amz = df.format(Date())
        val date = amz.substring(0, 8)
        val path = "/" + enc(bucket, false) + (if (objKey != null) "/" + enc(objKey, true) else "")
        val q = query.toSortedMap().entries.joinToString("&") { enc(it.key, false) + "=" + enc(it.value, false) }
        val headers = sortedMapOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to amz)
        val signed = headers.keys.joinToString(";")
        val canon = "$method\n$path\n$q\n" + headers.entries.joinToString("") { "${it.key}:${it.value}\n" } + "\n$signed\n$payloadHash"
        val scope = "$date/auto/s3/aws4_request"
        val sts = "AWS4-HMAC-SHA256\n$amz\n$scope\n" + sha(canon.toByteArray(Charsets.UTF_8))
        var k = hmac(("AWS4$secret").toByteArray(Charsets.UTF_8), date)
        k = hmac(k, "auto")
        k = hmac(k, "s3")
        k = hmac(k, "aws4_request")
        val sig = hex(hmac(k, sts))
        val c = URL("https://$host$path" + (if (q.isNotEmpty()) "?$q" else "")).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("x-amz-content-sha256", payloadHash)
        c.setRequestProperty("x-amz-date", amz)
        c.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signed, Signature=$sig")
        for ((a, b) in extra) c.setRequestProperty(a, b)
        return c
    }

    private fun errMsg(code: Int, body: String): String {
        val c = Regex("<Code>(.*?)</Code>").find(body)?.groupValues?.get(1)
        return when {
            c == "NoSuchBucket" -> "Bucket tidak ditemukan. Cek nama bucket."
            c == "SignatureDoesNotMatch" -> "Secret Access Key salah"
            c == "InvalidAccessKeyId" -> "Access Key ID salah"
            code == 401 || code == 403 -> "Akses ditolak ($code). Cek izin API token."
            else -> ("Cloudflare menjawab $code " + (c ?: "")).trim()
        }
    }

    private fun unx(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    fun list(prefix: String): List<Triple<String, Long, Long>> {
        val out = mutableListOf<Triple<String, Long, Long>>()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        iso.timeZone = TimeZone.getTimeZone("UTC")
        var token: String? = null
        do {
            val q = mutableMapOf("list-type" to "2", "prefix" to prefix)
            token?.let { q["continuation-token"] = it }
            val c = request("GET", null, q, EMPTY)
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw Exception(errMsg(code, body))
            for (m in Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
                val b = m.groupValues[1]
                val key = Regex("<Key>(.*?)</Key>").find(b)?.groupValues?.get(1)?.let { unx(it) } ?: continue
                val size = Regex("<Size>(\\d+)</Size>").find(b)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val lm = Regex("<LastModified>(.*?)</LastModified>").find(b)?.groupValues?.get(1) ?: ""
                val t = try {
                    iso.parse(lm.take(19))?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
                if (!key.endsWith("/")) out.add(Triple(key, size, t))
            }
            token = if (body.contains("<IsTruncated>true</IsTruncated>")) {
                Regex("<NextContinuationToken>(.*?)</NextContinuationToken>").find(body)?.groupValues?.get(1)?.let { unx(it) }
            } else null
        } while (token != null)
        return out
    }

    fun put(objKey: String, src: Source, mime: String, progress: (Int) -> Unit) {
        val md = MessageDigest.getInstance("SHA-256")
        var len = 0L
        src.open().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                len += n
            }
        }
        val c = request("PUT", objKey, mapOf(), hex(md.digest()), mapOf("Content-Type" to mime))
        c.doOutput = true
        c.setFixedLengthStreamingMode(len)
        c.outputStream.use { out ->
            src.open().use { ins ->
                val buf = ByteArray(65536)
                var done = 0L
                var last = -5
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val p = (done * 100 / maxOf(1L, len)).toInt()
                    if (p >= last + 5) {
                        last = p
                        progress(p)
                    }
                }
            }
        }
        val code = c.responseCode
        if (code >= 300) {
            val body = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception(errMsg(code, body))
        }
    }

    fun get(objKey: String, range: String?): HttpURLConnection =
        request("GET", objKey, mapOf(), EMPTY, if (range != null) mapOf("Range" to range) else mapOf())

    fun delete(objKey: String) {
        val c = request("DELETE", objKey, mapOf(), EMPTY)
        val code = c.responseCode
        if (code >= 300) throw Exception(errMsg(code, c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""))
    }
}
