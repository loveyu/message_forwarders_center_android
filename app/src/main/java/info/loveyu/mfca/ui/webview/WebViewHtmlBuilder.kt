package info.loveyu.mfca.ui.webview

private const val CDN_HIGHLIGHT_JS = "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0"
private const val CDN_MARKED_JS = "https://cdn.jsdelivr.net/npm/marked@9.1.6"

private data class ThemeColors(
    val bgColor: String, val textColor: String, val codeBg: String,
    val codeText: String, val linkColor: String, val borderColor: String,
    val secondaryText: String, val topbarBg: String, val topbarText: String,
    val codeHighlightBg: String, val scrollThumb: String,
    val blockquoteBg: String, val blockquoteBorder: String,
    val tableBorder: String, val tableRowEven: String, val hrColor: String,
    val badgeBg: String, val badgeText: String, val headingColor: String,
)

fun generateThemeSwitchScript(isDarkTheme: Boolean): String {
    val target = if (isDarkTheme) "dark" else "light"
    return """
(function() {
    document.documentElement.setAttribute('data-theme', '$target');
    var meta = document.querySelector('meta[name="color-scheme"]');
    if (meta) meta.content = '$target';
})();
""".trimIndent()
}

fun wrapHtml(title: String, content: String, isDarkTheme: Boolean): String {
    val colors = if (isDarkTheme) {
        ThemeColors(
            bgColor = "#1e1e1e", textColor = "#d4d4d4", codeBg = "#2d2d2d",
            codeText = "#d4d4d4", linkColor = "#4fc3f7", borderColor = "#333",
            secondaryText = "#888", topbarBg = "#252526", topbarText = "#ccc",
            codeHighlightBg = "#3a3d41", scrollThumb = "#555",
            blockquoteBg = "#2d2d2d", blockquoteBorder = "#4fc3f7",
            tableBorder = "#333", tableRowEven = "#252526", hrColor = "#333",
            badgeBg = "#4fc3f7", badgeText = "#fff", headingColor = "#e0e0e0",
        )
    } else {
        ThemeColors(
            bgColor = "#fff", textColor = "#333", codeBg = "#f5f5f5",
            codeText = "#333", linkColor = "#1976d2", borderColor = "#e0e0e0",
            secondaryText = "#666", topbarBg = "#f0f0f0", topbarText = "#333",
            codeHighlightBg = "#f0f0f0", scrollThumb = "#ccc",
            blockquoteBg = "#f0f0f0", blockquoteBorder = "#1976d2",
            tableBorder = "#e0e0e0", tableRowEven = "#fafafa", hrColor = "#e0e0e0",
            badgeBg = "#1976d2", badgeText = "#fff", headingColor = "#333",
        )
    }
    val (bgColor, textColor, codeBg, codeText, linkColor, borderColor, secondaryText,
        topbarBg, topbarText, codeHighlightBg, scrollThumb, blockquoteBg, blockquoteBorder,
        tableBorder, tableRowEven, hrColor, badgeBg, badgeText, headingColor) = colors

    return """<!DOCTYPE html>
<html data-theme="${if (isDarkTheme) "dark" else "light"}">
<head>
<meta charset="utf-8">
<meta name="color-scheme" content="${if (isDarkTheme) "dark" else "light"}">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/${if (isDarkTheme) "github-dark" else "github"}.min.css">
<script src="$CDN_HIGHLIGHT_JS/highlight.min.js"></script>
<script src="$CDN_MARKED_JS/marked.min.js"></script>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    background: $bgColor; color: $textColor; line-height: 1.6; font-size: 15px; padding: 0;
    -webkit-font-smoothing: antialiased;
}
::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: $scrollThumb; border-radius: 3px; }
a { color: $linkColor; text-decoration: none; }
a:hover { text-decoration: underline; }
h1, h2, h3, h4, h5, h6 {
    margin: 1.2em 0 0.6em; font-weight: 600; line-height: 1.3; color: $headingColor;
}
h1 { font-size: 1.5em; border-bottom: 1px solid $borderColor; padding-bottom: 0.3em; }
h2 { font-size: 1.3em; border-bottom: 1px solid $borderColor; padding-bottom: 0.25em; }
h3 { font-size: 1.15em; }
p { margin: 0.8em 0; }
ul, ol { margin: 0.6em 0; padding-left: 1.5em; }
li { margin: 0.3em 0; }
blockquote {
    margin: 0.8em 0; padding: 0.5em 1em; background: $blockquoteBg;
    border-left: 4px solid $blockquoteBorder; border-radius: 0 6px 6px 0;
}
blockquote p { margin: 0.3em 0; }
code {
    font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace;
    font-size: 0.9em; background: $codeBg; color: $codeText;
    padding: 0.2em 0.4em; border-radius: 3px;
}
pre {
    margin: 0.8em 0; background: $codeBg; border-radius: 6px;
    overflow-x: auto; position: relative;
}
pre code {
    padding: 1em; display: block; overflow-x: auto;
    background: $codeHighlightBg; font-size: 0.85em; line-height: 1.5;
}
pre code.hljs { background: $codeHighlightBg; }
table { width: 100%; border-collapse: collapse; margin: 0.8em 0; }
th, td { border: 1px solid $tableBorder; padding: 0.5em 0.8em; text-align: left; }
th { background: $tableRowEven; font-weight: 600; }
tr:nth-child(even) { background: $tableRowEven; }
img { max-width: 100%; height: auto; border-radius: 6px; }
hr { border: none; border-top: 1px solid $hrColor; margin: 1.2em 0; }
.topbar {
    display: flex; align-items: center; justify-content: space-between;
    background: $topbarBg; padding: 8px 16px; position: sticky; top: 0; z-index: 100;
    border-bottom: 1px solid $borderColor;
}
.topbar-title { font-size: 14px; font-weight: 600; color: $topbarText; flex: 1; }
.topbar-btn {
    background: none; border: 1px solid $borderColor; color: $topbarText;
    padding: 4px 12px; border-radius: 4px; cursor: pointer; font-size: 13px;
    margin-left: 8px;
}
.topbar-btn:active { opacity: 0.7; }
.content { padding: 8px 16px 32px; }
.badge {
    display: inline-block; background: $badgeBg; color: $badgeText;
    font-size: 11px; padding: 1px 6px; border-radius: 3px; margin-left: 4px;
    vertical-align: middle; font-weight: 500;
}
.copy-btn {
    position: absolute; top: 6px; right: 6px; background: $badgeBg; color: $badgeText;
    border: none; padding: 2px 8px; border-radius: 3px; font-size: 11px; cursor: pointer;
    opacity: 0; transition: opacity 0.2s;
}
pre:hover .copy-btn { opacity: 1; }
.copy-btn:active { opacity: 0.7; }
details { margin: 0.6em 0; }
summary { cursor: pointer; font-weight: 500; color: $linkColor; }
details[open] summary { margin-bottom: 0.4em; }
kbd {
    font-family: monospace; background: $codeBg; border: 1px solid $borderColor;
    border-radius: 3px; padding: 1px 4px; font-size: 0.85em;
}
</style>
</head>
<body>
<div class="topbar">
    <div class="topbar-title">$title</div>
    <button class="topbar-btn" onclick="toggleTheme()">🌓</button>
</div>
<div class="content" id="content">${renderContent(content)}</div>
<script>
${generateThemeSwitchScript(isDarkTheme)}
function toggleTheme() {
    var html = document.documentElement;
    var current = html.getAttribute('data-theme');
    var target = current === 'dark' ? 'light' : 'dark';
    html.setAttribute('data-theme', target);
    document.querySelector('meta[name="color-scheme"]').content = target;
    Android.onThemeChanged(target);
}
document.addEventListener('click', function(e) {
    var link = e.target.closest('a');
    if (link && link.href) {
        e.preventDefault();
        Android.onLinkClick(link.href);
    }
});
hljs.highlightAll();
marked.setOptions({ breaks: true, gfm: true });
document.querySelectorAll('pre code').forEach(function(block) {
    var btn = document.createElement('button');
    btn.className = 'copy-btn';
    btn.textContent = 'Copy';
    btn.onclick = function() {
        Android.onCopy(block.textContent);
        btn.textContent = 'Copied!';
        setTimeout(function() { btn.textContent = 'Copy'; }, 2000);
    };
    block.parentNode.appendChild(btn);
});
document.querySelectorAll('div.\\"language-').forEach(function(div) {
    // fix any legacy rendering
});
</script>
</body>
</html>"""
}

private fun renderContent(content: String): String {
    if (content.isBlank()) return "<p>（空内容）</p>"
    val lower = content.lowercase().trimStart()
    return if (lower.startsWith("<") && !lower.startsWith("<!") &&
        (lower.contains(">") || lower.startsWith("<pre") || lower.startsWith("<div") ||
                lower.startsWith("<table") || lower.startsWith("<h") ||
                lower.startsWith("<p") || lower.startsWith("<ul") || lower.startsWith("<ol") ||
                lower.startsWith("<blockquote"))
    ) {
        content
    } else {
        val escaped = escapeHtml(content)
        """<div id="md-content" style="display:none">$escaped</div>
<script>
var md = document.getElementById('md-content');
if (md) {
    var html = marked.parse(decodeHTMLEntities(md.innerHTML));
    md.outerHTML = '<div>' + html + '</div>';
    hljs.highlightAll();
    md.querySelectorAll('pre code').forEach(function(block) {
        var btn = document.createElement('button');
        btn.className = 'copy-btn';
        btn.textContent = 'Copy';
        btn.onclick = function() {
            Android.onCopy(block.textContent);
            btn.textContent = 'Copied!';
            setTimeout(function() { btn.textContent = 'Copy'; }, 2000);
        };
        block.parentNode.appendChild(btn);
    });
}
</script>"""
    }
}

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
