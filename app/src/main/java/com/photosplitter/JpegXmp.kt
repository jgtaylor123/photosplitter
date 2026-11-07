package com.photosplitter

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Adds a standard JPEG APP1 XMP packet alongside existing EXIF metadata.
 * Google Photos may or may not import dc:description into its editable caption.
 */
internal fun embedXmpDescription(file: File, title: String, people: String, batch: String) {
    if (title.isBlank()) return
    fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    val packet = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:photosplitter="https://photosplitter.app/ns/1.0/">
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">${xml(title)}</rdf:li></rdf:Alt></dc:title>
<dc:description><rdf:Alt><rdf:li xml:lang="x-default">${xml(title)}</rdf:li></rdf:Alt></dc:description>
<photosplitter:people>${xml(people)}</photosplitter:people>
<photosplitter:batch>${xml(batch)}</photosplitter:batch>
</rdf:Description></rdf:RDF></x:xmpmeta>"""

    val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)
    val payload = header + packet.toByteArray(StandardCharsets.UTF_8)
    val segmentLength = payload.size + 2
    require(segmentLength <= 65535) { "XMP packet too large for JPEG APP1" }
    val original = file.readBytes()
    require(original.size >= 2 && original[0] == 0xFF.toByte() && original[1] == 0xD8.toByte()) {
        "Not a JPEG file"
    }
    val segment = byteArrayOf(
        0xFF.toByte(), 0xE1.toByte(),
        (segmentLength shr 8).toByte(), segmentLength.toByte()
    ) + payload
    file.writeBytes(original.copyOfRange(0, 2) + segment + original.copyOfRange(2, original.size))
}
