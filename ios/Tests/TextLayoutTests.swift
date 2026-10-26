import XCTest
@testable import MemeSearch

final class TextLayoutTests: XCTestCase {
    private func glyph(_ text: String, _ x: Double, _ y: Double) -> OCRGlyph {
        OCRGlyph(text: text, rect: CGRect(x: x, y: 1 - y - 0.1, width: 0.1, height: 0.1))
    }

    func testVerticalColumnsReadRightToLeft() {
        let items = [glyph("我", 0.1, 0.1), glyph("们", 0.1, 0.22), glyph("呀", 0.1, 0.34),
                     glyph("你", 0.26, 0.1), glyph("好", 0.26, 0.22), glyph("啊", 0.26, 0.34)]
        XCTAssertEqual(TextLayout.readingOrder(items), "你好啊\n我们呀")
        XCTAssertTrue(TextLayout.withReadingOrder("我你\n们好\n呀啊", glyphs: items).hasPrefix("你好啊\n我们呀"))
    }

    func testHorizontalRowsRemainUnchanged() {
        let items = (0..<3).flatMap { row in (0..<5).map { col in glyph("字", Double(col) * 0.12, Double(row) * 0.12) } }
        XCTAssertEqual(TextLayout.withReadingOrder("横排文字", glyphs: items), "横排文字")
    }

    func testChineseSpacesAndFullWidthNormalize() {
        XCTAssertEqual(TextLayout.normalize("ＡＢＣ 你\n好"), "abc 你好")
    }

    func testWholeVerticalLinesReadRightToLeft() {
        let lines = [OCRGlyph(text: "你我他", rect: CGRect(x: 0.1, y: 0.3, width: 0.1, height: 0.5)),
                     OCRGlyph(text: "天地人", rect: CGRect(x: 0.6, y: 0.25, width: 0.1, height: 0.6))]
        XCTAssertEqual(TextLayout.verticalLineOrder(lines), "天地人\n你我他")
        XCTAssertTrue(TextLayout.withReadingOrder("你我他\n天地人", glyphs: lines).hasPrefix("天地人\n你我他"))
    }
}
