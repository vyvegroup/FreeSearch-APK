package com.vyvegroup.searchengine.utils

object TextUtils {

    fun generateSnippet(content: String, title: String, maxLength: Int = 300): String {
        if (content.isBlank()) return title.take(maxLength)
        val cleaned = cleanText(content)
        if (cleaned.length <= maxLength) return cleaned
        return cleaned.take(maxLength) + "..."
    }

    fun cleanText(html: String): String {
        var text = html

        // Remove script and style blocks
        text = text.replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<noscript[^>]*>.*?</noscript>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<iframe[^>]*>.*?</iframe>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<nav[^>]*>.*?</nav>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<footer[^>]*>.*?</footer>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE), "")

        // Remove all HTML tags
        text = text.replace(Regex("<[^>]+>"), "")

        // Decode HTML entities
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        text = text.replace("&#39;", "'")
        text = text.replace("&nbsp;", " ")
        text = text.replace("&#8217;", "'")
        text = text.replace("&#8220;", "\"")
        text = text.replace("&#8221;", "\"")
        text = text.replace("&#8212;", "-")
        text = text.replace("&#8211;", "-")
        text = text.replace("&mdash;", "-")
        text = text.replace("&ndash;", "-")
        text = text.replace("&hellip;", "...")
        text = text.replace(Regex("&#[0-9]+;"), "")

        // Collapse whitespace
        text = text.replace(Regex("[\\t\\r]+"), " ")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        text = text.trim()

        return text
    }

    fun extractTitle(html: String, url: String): String {
        // Try <title> tag
        val titleRegex = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
        val titleMatch = titleRegex.find(html)
        if (titleMatch != null) {
            val title = titleMatch.groupValues[1].trim()
            if (title.isNotBlank() && title.length > 2) {
                return title.replace(Regex("&[^;]+;"), "").trim()
            }
        }

        // Try h1 tag
        val h1Regex = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
        val h1Match = h1Regex.find(html)
        if (h1Match != null) {
            val title = h1Match.groupValues[1].trim()
            if (title.isNotBlank() && title.length > 2) {
                return title.replace(Regex("<[^>]+>"), "").trim()
            }
        }

        // Try og:title
        val ogRegex = Regex("""property=["']og:title["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val ogMatch = ogRegex.find(html)
        if (ogMatch != null) {
            return ogMatch.groupValues[1].trim()
        }
        val ogRegex2 = Regex("""content=["']([^"']+)["'][^>]*property=["']og:title["']""", RegexOption.IGNORE_CASE)
        val ogMatch2 = ogRegex2.find(html)
        if (ogMatch2 != null) {
            return ogMatch2.groupValues[1].trim()
        }

        // Extract from URL as fallback
        return try {
            val path = url.removePrefix("http://").removePrefix("https://")
                .split("/").lastOrNull()?.replace(Regex("[-_+.]"), " ") ?: ""
            path.split(" ").map { it.capitalize() }.joinToString(" ").ifBlank { url }
        } catch (_: Exception) {
            url
        }
    }

    fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun isSameDomain(url1: String, url2: String): Boolean {
        return extractDomain(url1).equals(extractDomain(url2), ignoreCase = true)
    }

    fun normalizeUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) {
            val base = try { java.net.URI(baseUrl) } catch (_: Exception) { return url }
            return "${base.scheme}://${base.authority}$url"
        }
        if (url.startsWith("?") || url.startsWith("#")) return "$baseUrl$url"
        // Relative path
        val lastSlash = baseUrl.lastIndexOf('/')
        return if (lastSlash > 0) baseUrl.substring(0, lastSlash + 1) + url else "$baseUrl/$url"
    }

    fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.length > 2048) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (url.contains("javascript:", ignoreCase = true)) return false
        if (url.contains("mailto:", ignoreCase = true)) return false
        return true
    }

    fun formatFileSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}
