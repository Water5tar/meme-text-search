import Foundation
import SQLite3
import Photos

struct PhotoRecord: Identifiable {
    let id: String
    let created: TimeInterval
    let text: String
}

struct IndexCounts {
    var total = 0
    var indexed = 0
    var pending = 0
    var failed = 0
}

struct StoreError: Error, LocalizedError {
    let detail: String
    var errorDescription: String? { detail }
}

/// All calls come from LibraryModel on the main actor; OCR and image decoding run off it.
final class SearchStore {
    private var db: OpaquePointer?
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init() throws {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var protectedDirectory = directory
        try protectedDirectory.setResourceValues(values)
        let file = directory.appendingPathComponent("meme-search.sqlite")
        guard sqlite3_open_v2(file.path, &db, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            throw StoreError(detail: "无法打开本机索引")
        }
        try execute("PRAGMA journal_mode=WAL")
        try execute("CREATE TABLE IF NOT EXISTS photos (id TEXT PRIMARY KEY, modified REAL NOT NULL, created REAL NOT NULL, text TEXT NOT NULL DEFAULT '', normalized TEXT NOT NULL DEFAULT '', state INTEGER NOT NULL DEFAULT 0, visible INTEGER NOT NULL DEFAULT 1, scan INTEGER NOT NULL DEFAULT 0)")
        try execute("CREATE INDEX IF NOT EXISTS photos_state ON photos(visible,state)")
    }

    deinit { sqlite3_close(db) }

    private func execute(_ sql: String) throws {
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw StoreError(detail: String(cString: sqlite3_errmsg(db))) }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw StoreError(detail: String(cString: sqlite3_errmsg(db)))
        }
        return statement
    }

    private func bind(_ value: String, to statement: OpaquePointer?, at index: Int32) {
        value.withCString { sqlite3_bind_text(statement, index, $0, -1, Self.transient) }
    }

    private func string(_ statement: OpaquePointer?, _ column: Int32) -> String {
        guard let bytes = sqlite3_column_text(statement, column) else { return "" }
        return String(decoding: UnsafeBufferPointer(start: bytes, count: Int(sqlite3_column_bytes(statement, column))), as: UTF8.self)
    }

    func hideAll() throws { try execute("UPDATE photos SET visible=0") }
    func beginScan() throws { try execute("BEGIN IMMEDIATE") }
    func rollback() { try? execute("ROLLBACK") }

    func observe(_ asset: PHAsset, scan: Int64) throws {
        let statement = try prepare("""
            INSERT INTO photos(id,modified,created,text,normalized,state,visible,scan)
            VALUES(?,?,?,'','',0,1,?) ON CONFLICT(id) DO UPDATE SET
            created=excluded.created, visible=1, scan=excluded.scan,
            text=CASE WHEN photos.modified=excluded.modified THEN photos.text ELSE '' END,
            normalized=CASE WHEN photos.modified=excluded.modified THEN photos.normalized ELSE '' END,
            state=CASE WHEN photos.modified=excluded.modified THEN photos.state ELSE 0 END,
            modified=excluded.modified
            """)
        defer { sqlite3_finalize(statement) }
        bind(asset.localIdentifier, to: statement, at: 1)
        sqlite3_bind_double(statement, 2, (asset.modificationDate ?? asset.creationDate ?? .distantPast).timeIntervalSince1970)
        sqlite3_bind_double(statement, 3, (asset.creationDate ?? .distantPast).timeIntervalSince1970)
        sqlite3_bind_int64(statement, 4, scan)
        guard sqlite3_step(statement) == SQLITE_DONE else { throw StoreError(detail: String(cString: sqlite3_errmsg(db))) }
    }

    func finishScan(_ scan: Int64, fullAccess: Bool) throws {
        let statement = try prepare("UPDATE photos SET visible=0 WHERE scan<>?")
        sqlite3_bind_int64(statement, 1, scan)
        let result = sqlite3_step(statement)
        sqlite3_finalize(statement)
        guard result == SQLITE_DONE else { throw StoreError(detail: String(cString: sqlite3_errmsg(db))) }
        if fullAccess { try execute("DELETE FROM photos WHERE visible=0") }
        try execute("COMMIT")
    }

    func pending() throws -> [(String, Double)] {
        let statement = try prepare("SELECT id,modified FROM photos WHERE visible=1 AND state=0 ORDER BY created DESC")
        defer { sqlite3_finalize(statement) }
        var rows = [(String, Double)]()
        while sqlite3_step(statement) == SQLITE_ROW {
            rows.append((string(statement, 0), sqlite3_column_double(statement, 1)))
        }
        return rows
    }

    func finish(id: String, modified: Double, text: String?, error: String = "") throws {
        let statement = try prepare("UPDATE photos SET text=?, normalized=?, state=?, visible=1 WHERE id=? AND modified=? AND visible=1")
        defer { sqlite3_finalize(statement) }
        bind(text ?? error, to: statement, at: 1)
        bind(text.map(TextLayout.normalize) ?? "", to: statement, at: 2)
        sqlite3_bind_int(statement, 3, text == nil ? 2 : 1)
        bind(id, to: statement, at: 4)
        sqlite3_bind_double(statement, 5, modified)
        guard sqlite3_step(statement) == SQLITE_DONE else { throw StoreError(detail: String(cString: sqlite3_errmsg(db))) }
    }

    func retryFailures() throws { try execute("UPDATE photos SET state=0 WHERE state=2 AND visible=1") }

    func counts() throws -> IndexCounts {
        let statement = try prepare("SELECT COUNT(*),COALESCE(SUM(state=1),0),COALESCE(SUM(state=0),0),COALESCE(SUM(state=2),0) FROM photos WHERE visible=1")
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW else { return IndexCounts() }
        return IndexCounts(total: Int(sqlite3_column_int(statement, 0)), indexed: Int(sqlite3_column_int(statement, 1)), pending: Int(sqlite3_column_int(statement, 2)), failed: Int(sqlite3_column_int(statement, 3)))
    }

    func search(_ query: String, limit: Int = 200, offset: Int = 0) throws -> ([PhotoRecord], Int) {
        let normalized = TextLayout.normalize(query)
        let filter = normalized.isEmpty ? "visible=1" : "visible=1 AND state=1 AND instr(normalized,?)>0"
        let countStatement = try prepare("SELECT COUNT(*) FROM photos WHERE \(filter)")
        if !normalized.isEmpty { bind(normalized, to: countStatement, at: 1) }
        let total = sqlite3_step(countStatement) == SQLITE_ROW ? Int(sqlite3_column_int(countStatement, 0)) : 0
        sqlite3_finalize(countStatement)
        let statement = try prepare("SELECT id,created,text FROM photos WHERE \(filter) ORDER BY created DESC,id DESC LIMIT ? OFFSET ?")
        defer { sqlite3_finalize(statement) }
        let start: Int32 = normalized.isEmpty ? 1 : 2
        if !normalized.isEmpty { bind(normalized, to: statement, at: 1) }
        sqlite3_bind_int(statement, start, Int32(limit))
        sqlite3_bind_int(statement, start + 1, Int32(offset))
        var rows = [PhotoRecord]()
        while sqlite3_step(statement) == SQLITE_ROW {
            rows.append(PhotoRecord(id: string(statement, 0), created: sqlite3_column_double(statement, 1), text: string(statement, 2)))
        }
        return (rows, total)
    }
}
