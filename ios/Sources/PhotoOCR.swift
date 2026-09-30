import Foundation
import Photos
import Vision
import UIKit
import ImageIO

enum PhotoOCR {
    static func recognize(localIdentifier: String) throws -> String {
        let fetch = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = fetch.firstObject else { throw StoreError(detail: "图片不再可访问") }
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .exact
        options.isSynchronous = true
        options.isNetworkAccessAllowed = false
        var result: UIImage?
        PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 2560, height: 2560),
                                              contentMode: .aspectFit, options: options) { image, _ in result = image }
        guard let image = result, let cgImage = image.cgImage else {
            throw StoreError(detail: "原图未保存在本机，或已失去相册访问权限")
        }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        let supported = try request.supportedRecognitionLanguages()
        let preferred = ["zh-Hans", "en-US"].filter { supported.contains($0) }
        if !preferred.isEmpty { request.recognitionLanguages = preferred }
        request.usesLanguageCorrection = true // Vision applies this where the language supports it.
        try VNImageRequestHandler(cgImage: cgImage, orientation: orientation(image.imageOrientation), options: [:])
            .perform([request])
        let observations = request.results ?? []
        let raw = observations.compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
        let glyphs = observations.compactMap { observation -> OCRGlyph? in
            guard let text = observation.topCandidates(1).first?.string else { return nil }
            return OCRGlyph(text: text, rect: observation.boundingBox)
        }
        return TextLayout.withReadingOrder(raw, glyphs: glyphs)
    }

    private static func orientation(_ value: UIImage.Orientation) -> CGImagePropertyOrientation {
        switch value {
        case .up: return .up
        case .down: return .down
        case .left: return .left
        case .right: return .right
        case .upMirrored: return .upMirrored
        case .downMirrored: return .downMirrored
        case .leftMirrored: return .leftMirrored
        case .rightMirrored: return .rightMirrored
        @unknown default: return .up
        }
    }
}
