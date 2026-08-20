package com.example.bezellists

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- markdown
// A deliberately small inline renderer: bold, italic, code, links, bullet
// and heading markers. Enough for card descriptions; not a spec engine.

private val INLINE = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|\[(.+?)]\((\S+?)\)""")

fun markdownToAnnotated(src: String): AnnotatedString = buildAnnotatedString {
    src.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw.trim()
        line = line.removePrefix("### ").removePrefix("## ").removePrefix("# ")
        if (line.startsWith("- ") || line.startsWith("* ")) line = "• " + line.drop(2)
        var cursor = 0
        for (m in INLINE.findAll(line)) {
            append(line.substring(cursor, m.range.first))
            when {
                m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groups[1]!!.value) }
                m.groups[2] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groups[2]!!.value) }
                m.groups[3] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groups[3]!!.value) }
                else -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(m.groups[4]!!.value) }
            }
            cursor = m.range.last + 1
        }
        append(line.substring(cursor))
    }
}

// ---------------------------------------------------------------- images
// An item's picture: the explicit `image` attribute wins; otherwise the
// link's og:image / twitter:image, resolved once and cached for the
// process lifetime ("" = looked, found nothing).

private val ogCache = ConcurrentHashMap<String, String>()

private val OG_PATTERNS = listOf(
    Regex("""(?:property|name)=["'](?:og:image|twitter:image)["'][^>]*?content=["']([^"']+)""", RegexOption.IGNORE_CASE),
    Regex("""content=["']([^"']+)["'][^>]*?(?:property|name)=["'](?:og:image|twitter:image)["']""", RegexOption.IGNORE_CASE),
)

fun resolveSiteImage(link: String): String {
    ogCache[link]?.let { return it }
    // Users paste "example.com"; URL() needs a scheme.
    val normalized = if (link.startsWith("http://") || link.startsWith("https://")) link
                     else "https://$link"
    val found = runCatching {
        var url = URL(normalized)
        var head = ""
        // HttpURLConnection won't follow redirects across protocols
        // (http→https), so hops are walked by hand.
        for (hop in 0 until 4) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (bezel-lists)")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(url, location)
                continue
            }
            // Read up to 128k chars or </head>: one read() only returns
            // the first chunk, which routinely misses the meta tags.
            head = conn.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < 131_072) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    if (sb.indexOf("</head>") >= 0) break
                }
                sb.toString()
            }
            break
        }
        OG_PATTERNS.firstNotNullOfOrNull { it.find(head)?.groupValues?.get(1) }
            ?.let { URL(url, it).toString() }
    }.getOrNull() ?: ""
    ogCache[link] = found
    return found
}

/** The URL an item's card image loads from, or null. */
fun explicitImage(body: JSONObject): String? =
    body.optJSONObject("attributes")?.optString("image")?.ifBlank { null }

@Composable
fun ItemImage(body: JSONObject, modifier: Modifier = Modifier) {
    val explicit = explicitImage(body)
    val link = body.optString("link").ifBlank { null }
    var url by remember(explicit, link) { mutableStateOf(explicit ?: link?.let { ogCache[it] }) }
    LaunchedEffect(explicit, link) {
        if (explicit == null && link != null && url == null) {
            url = withContext(Dispatchers.IO) { resolveSiteImage(link) }
        }
    }
    val resolved = url
    if (!resolved.isNullOrEmpty()) {
        AsyncImage(
            model = resolved,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}
