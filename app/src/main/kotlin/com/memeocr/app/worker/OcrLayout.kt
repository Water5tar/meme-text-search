package com.memeocr.app.worker

import com.google.mlkit.vision.text.Text
import com.memeocr.core.OcrGlyph
import com.memeocr.core.VerticalTextLayout

object OcrLayout {
    fun text(result: Text): String {
        val lines = buildList {
            for (block in result.textBlocks) for (line in block.lines) {
                line.boundingBox?.let { box ->
                    add(OcrGlyph(line.text, box.left, box.top, box.right, box.bottom))
                }
            }
        }
        val glyphs = buildList {
            for (block in result.textBlocks) for (line in block.lines) for (element in line.elements) {
                if (element.symbols.isEmpty()) {
                    element.boundingBox?.let { box ->
                        add(OcrGlyph(element.text, box.left, box.top, box.right, box.bottom))
                    }
                } else for (symbol in element.symbols) {
                    symbol.boundingBox?.let { box ->
                        add(OcrGlyph(symbol.text, box.left, box.top, box.right, box.bottom))
                    }
                }
            }
        }
        return VerticalTextLayout.withReadingOrder(result.text, glyphs, lines)
    }
}
