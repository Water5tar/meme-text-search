import Foundation
import Photos

@MainActor
final class LibraryModel: ObservableObject {
    @Published private(set) var results = [PhotoRecord]()
    @Published private(set) var totalResults = 0
    @Published private(set) var counts = IndexCounts()
    @Published private(set) var access: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published private(set) var stage = ""
    @Published private(set) var paused = UserDefaults.standard.bool(forKey: "indexPaused")
    @Published var errorMessage: String?

    private let store: SearchStore?
    private var indexTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?
    private var query = ""

    init() {
        do { store = try SearchStore() }
        catch { store = nil; errorMessage = "无法建立本机索引：\(error.localizedDescription)" }
        reloadResults()
    }

    func requestAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in
            Task { @MainActor in self.refresh() }
        }
    }

    func refresh() {
        access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard access == .authorized || access == .limited else {
            try? store?.hideAll()
            results = []; totalResults = 0; counts = IndexCounts()
            return
        }
        guard indexTask == nil else { reloadResults(); return }
        indexTask = Task {
            await index()
            indexTask = nil
        }
    }

    func search(_ input: String) {
        query = input
        searchTask?.cancel()
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 200_000_000)
            guard !Task.isCancelled else { return }
            reloadResults()
        }
    }

    func loadMore() {
        guard let store, results.count < totalResults else { return }
        do { results += try store.search(query, offset: results.count).0 }
        catch { errorMessage = error.localizedDescription }
    }

    func togglePause() {
        paused.toggle()
        UserDefaults.standard.set(paused, forKey: "indexPaused")
        if !paused { refresh() }
    }

    func retryFailures() {
        do { try store?.retryFailures(); paused = false; UserDefaults.standard.set(false, forKey: "indexPaused"); refresh() }
        catch { errorMessage = error.localizedDescription }
    }

    private func reloadResults() {
        guard let store else { return }
        do {
            (results, totalResults) = try store.search(query)
            counts = try store.counts()
        } catch { errorMessage = error.localizedDescription }
    }

    private func index() async {
        guard let store else { return }
        let startingAccess = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        do {
            stage = "正在同步相册"
            try store.hideAll() // Immediately hide records whose authorization may have been revoked.
            results = []; totalResults = 0
            let scan = Int64(Date().timeIntervalSince1970 * 1000)
            try store.beginScan()
            do {
                let options = PHFetchOptions()
                options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
                let assets = PHAsset.fetchAssets(with: .image, options: options)
                for index in 0..<assets.count {
                    try Task.checkCancellation()
                    try store.observe(assets.object(at: index), scan: scan)
                    if index % 300 == 299 { await Task.yield() }
                }
                guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == startingAccess else {
                    throw StoreError(detail: "相册授权范围已改变，请重新同步")
                }
                try store.finishScan(scan, fullAccess: startingAccess == .authorized)
            } catch { store.rollback(); throw error }
            reloadResults()
            let pending = try store.pending()
            for (index, row) in pending.enumerated() {
                if paused || Task.isCancelled { break }
                guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == startingAccess else {
                    try store.hideAll(); reloadResults(); break
                }
                stage = "正在识别 \(index + 1)/\(pending.count)"
                do {
                    let text = try await Task.detached(priority: .utility) {
                        try PhotoOCR.recognize(localIdentifier: row.0)
                    }.value
                    try store.finish(id: row.0, modified: row.1, text: text)
                } catch {
                    try store.finish(id: row.0, modified: row.1, text: nil, error: error.localizedDescription)
                }
                if index % 10 == 9 { reloadResults(); await Task.yield() }
            }
            reloadResults()
            stage = paused ? "索引已暂停" : "索引完成"
        } catch {
            stage = "索引中断"
            errorMessage = error.localizedDescription
            reloadResults()
        }
    }
}
