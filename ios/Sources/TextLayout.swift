import Foundation
import CoreGraphics

struct OCRGlyph {
    let text: String
    let rect: CGRect // Vision coordinates: origin at the bottom left.
    var centerX: CGFloat { rect.midX }
    var centerY: CGFloat { 1 - rect.midY }
}

enum TextLayout {
    static func normalize(_ input: String) -> String {
        let halfWidth = String(String.UnicodeScalarView(input.unicodeScalars.map { scalar in
            if (0xFF01...0xFF5E).contains(scalar.value) {
                return Unicode.Scalar(scalar.value - 0xFEE0)!
            }
            return scalar.value == 0x3000 ? Unicode.Scalar(0x20)! : scalar
        }))
        return halfWidth.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "[\\s\\p{Z}]+", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "(?<=\\p{Han}) (?=\\p{Han})", with: "", options: .regularExpression)
            .lowercased()
    }

    static func readingOrder(_ glyphs: [OCRGlyph]) -> String {
        let usable = glyphs.filter { glyph in
            glyph.text.count == 1 && glyph.text.unicodeScalars.allSatisfy(CharacterSet.alphanumerics.contains)
                && glyph.rect.width > 0 && glyph.rect.height > 0
                && glyph.rect.height <= glyph.rect.width * 1.65
                && glyph.rect.width <= glyph.rect.height * 1.65
        }
        guard (3...512).contains(usable.count) else { return "" }
        let widths = usable.map(\.rect.width).sorted()
        let size = widths[widths.count / 2]
        var columns = [[OCRGlyph]]()
        for glyph in usable.sorted(by: { $0.centerX < $1.centerX }) {
            if let index = columns.indices.min(by: {
                abs(columns[$0].map(\.centerX).reduce(0, +) / CGFloat(columns[$0].count) - glyph.centerX)
                    < abs(columns[$1].map(\.centerX).reduce(0, +) / CGFloat(columns[$1].count) - glyph.centerX)
            }), abs(columns[index].map(\.centerX).reduce(0, +) / CGFloat(columns[index].count) - glyph.centerX) <= size * 0.65 {
                columns[index].append(glyph)
            } else { columns.append([glyph]) }
        }
        let centerX: ([OCRGlyph]) -> CGFloat = { $0.map(\.centerX).reduce(0, +) / CGFloat($0.count) }
        var groups = [[[OCRGlyph]]]()
        for column in columns.sorted(by: { centerX($0) < centerX($1) }) {
            if let last = groups.last?.last, centerX(column) - centerX(last) <= size * 2.5 {
                groups[groups.count - 1].append(column)
            } else { groups.append([column]) }
        }
        return groups.sorted(by: { left, right in
            (left.map(centerX).max() ?? 0) > (right.map(centerX).max() ?? 0)
        }).compactMap { group -> String? in
            let glyphs = group.flatMap { $0 }
            var rows = [[OCRGlyph]]()
            for glyph in glyphs.sorted(by: { $0.centerY < $1.centerY }) {
                if let index = rows.indices.min(by: {
                    abs(rows[$0].map(\.centerY).reduce(0, +) / CGFloat(rows[$0].count) - glyph.centerY)
                        < abs(rows[$1].map(\.centerY).reduce(0, +) / CGFloat(rows[$1].count) - glyph.centerY)
                }), abs(rows[index].map(\.centerY).reduce(0, +) / CGFloat(rows[index].count) - glyph.centerY) <= size * 0.65 {
                    rows[index].append(glyph)
                } else { rows.append([glyph]) }
            }
            guard (group.map(\.count).max() ?? 0) >= 3,
                  (group.map(\.count).max() ?? 0) > (rows.map(\.count).max() ?? 0),
                  !group.contains(where: { column in
                      let ordered = column.sorted(by: { $0.centerY < $1.centerY })
                      return zip(ordered, ordered.dropFirst()).contains(where: { pair in
                          pair.1.centerY - pair.0.centerY < size * 0.65
                      })
                  }) else { return nil }
            return group.sorted(by: { centerX($0) > centerX($1) })
                .map { $0.sorted(by: { $0.centerY < $1.centerY }).map(\.text).joined() }
                .joined(separator: "\n")
        }.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    static func withReadingOrder(_ raw: String, glyphs: [OCRGlyph]) -> String {
        let ordered = readingOrder(glyphs)
        guard !ordered.isEmpty && !normalize(raw).contains(normalize(ordered)) else { return raw }
        return raw.isEmpty ? ordered : ordered + "\n" + raw
    }
}
