package local.airuize.receiver

/** Removes identifiers and credentials before saving local diagnostics. */
object DiagnosticRedactor {
    // Compile once; never repeatedly compile patterns for each transport record.
    private val rules=listOf(
        Regex("-----BEGIN [^-]+-----.*?-----END [^-]+-----",setOf(RegexOption.DOT_MATCHES_ALL)) to "[credential container removed]",
        Regex("^.*(?:authorization\\s*:|bearer\\s+|identity\\.pk8|certificate\\.p7b|password\\s*[:=]|privatekey\\s*[:=]|pairrecord\\s*[:=]|upload.token|x-upload-token).*$",setOf(RegexOption.IGNORE_CASE,RegexOption.MULTILINE)) to "[credential line removed]",
        Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}",RegexOption.IGNORE_CASE) to "[MAC]",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b") to "[IP]",
        Regex("(?<![\\w:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:%\\w]*",RegexOption.IGNORE_CASE) to "[IPv6]",
        Regex("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",RegexOption.IGNORE_CASE) to "[UUID]",
        Regex("/(?:Users|home)/[^\\s;；]+") to "[personal path]",
        Regex("wxid_[A-Za-z0-9_]+") to "[messaging ID]",
        Regex("(?i)(\\b(?:ssid|serial|udid|deviceid|usbTransport|hostid|systembuid|buid|pairingid|token|secret|latitude|longitude|lat|lon)\\s*[:=]\\s*)[^\\n;；]+") to "$1[redacted]"
    )
    fun clean(value:String):String {
        var text=value
        for((pattern,replacement) in rules) text=pattern.replace(text,replacement)
        return text
    }
}
