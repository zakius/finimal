package com.zakius.finimal.desktop

import org.jetbrains.skia.BreakIterator
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import java.io.File
import java.security.MessageDigest

// Run with ./gradlew :desktop:skiaCheck. Checks that Skia's library with its ICU data trimmed (trimSkiaIcu in
// desktop/build.gradle.kts) breaks text the same way as the original. Each library runs in its own JVM. For each
// script, words of assigned code points are laid out at several widths to exercise line breaks, then walked with the
// line and character break iterators, and the results are digested for comparison. Word breaking isn't compared,
// because without the Chinese/Japanese dictionary it splits those languages per character.
fun main(args: Array<String>) {
    if (args[0] == "run") {
        println(digests())
        return
    }
    fun run(dir: String): String {
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path, "-Dskiko.library.path=$dir",
            "-cp", System.getProperty("java.class.path"), "com.zakius.finimal.desktop.SkiaCheckKt", "run",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        return process.inputStream.bufferedReader().readText().trim()
    }
    val full = run(args[0])
    val trimmed = run(args[1])
    println("full    $full\ntrimmed $trimmed")
    check(full.isNotEmpty() && full == trimmed) { "Skia breaks text differently with the trimmed ICU data" }
}

private fun digests(): String {
    val texts = sortedMapOf<String, StringBuilder>()
    for (c in 0x20..0x2FFFF) {
        val type = Character.getType(c)
        if (!Character.isDefined(c) || Character.isISOControl(c) || type == Character.SURROGATE.toInt() || type == Character.PRIVATE_USE.toInt()) continue
        val sb = texts.getOrPut(Character.UnicodeScript.of(c).name) { StringBuilder() }
        if (sb.codePointCount(0, sb.length) >= 600) continue
        sb.appendCodePoint(c)
        if (sb.length % 7 == 0) sb.append(' ')
    }
    val fonts = FontCollection().setDefaultFontManager(FontMgr.default)
    val md = MessageDigest.getInstance("SHA-256")
    for ((_, sb) in texts) {
        val text = sb.toString()
        for (width in listOf(60f, 150f, 400f)) {
            val p = ParagraphBuilder(ParagraphStyle(), fonts).pushStyle(TextStyle().setFontSize(14f)).addText(text).build()
            p.layout(width)
            for (line in p.lineMetrics) md.update("${line.startIndex}-${line.endIndex};".toByteArray())
            p.close()
        }
        for (iterator in listOf(BreakIterator.makeLineInstance(), BreakIterator.makeCharacterInstance())) {
            iterator.setText(text)
            var at = iterator.first()
            while (at != BreakIterator.DONE) {
                md.update("$at,".toByteArray())
                at = iterator.next()
            }
            iterator.close()
        }
    }
    return md.digest().toHexString()
}
