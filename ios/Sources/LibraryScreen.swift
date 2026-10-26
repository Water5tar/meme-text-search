import SwiftUI
import Photos
import UIKit

struct LibraryScreen: View {
    @StateObject private var model = LibraryModel()
    @State private var query = ""
    @State private var selected: PhotoRecord?
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        NavigationStack {
            Group {
                if model.access == .authorized || model.access == .limited { library }
                else { permissionPrompt }
            }
            .navigationTitle("Meme 文字搜索")
            .toolbar {
                if model.access == .authorized || model.access == .limited {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("同步") { model.refresh() }
                    }
                }
            }
            .sheet(item: $selected) { record in
                PhotoPreview(record: record).presentationDetents([.large])
            }
            .alert("索引遇到问题", isPresented: Binding(
                get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } }
            )) { Button("知道了", role: .cancel) { model.errorMessage = nil } }
            message: { Text(model.errorMessage ?? "") }
        }
        .onAppear { model.refresh() }
        .onChange(of: scenePhase) { if $0 == .active { model.refresh() } }
    }

    private var permissionPrompt: some View {
        VStack(spacing: 18) {
            Image(systemName: "photo.on.rectangle.angled").font(.system(size: 52)).foregroundStyle(.tint)
            Text("照片始终留在你的设备上").font(.headline)
            Text("授权读取相册后，使用 iPhone 自带的文字识别能力建立本机索引。输入图片里的文字，就能找到表情包。")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button("授权读取照片") { model.requestAccess() }.buttonStyle(.borderedProminent)
            if model.access == .denied || model.access == .restricted {
                Button("打开系统权限设置") { openSettings() }
            }
        }
        .padding(28)
    }

    private var library: some View {
        VStack(spacing: 8) {
            if model.access == .limited {
                HStack {
                    Text("仅搜索已授权的照片").font(.footnote)
                    Spacer()
                    Button("管理授权") { openSettings() }.font(.footnote)
                }.padding(.horizontal)
            }
            TextField("搜索图片里的文字", text: $query)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .padding(.horizontal)
                .onChange(of: query) { model.search($0) }
            HStack(spacing: 12) {
                Text("\(model.totalResults) 张 · 已识别 \(model.counts.indexed)/\(model.counts.total)")
                    .lineLimit(1).minimumScaleFactor(0.8)
                Spacer(minLength: 0)
                if model.counts.failed > 0 { Button("重试失败项") { model.retryFailures() }.font(.caption) }
                Button(model.paused ? "继续" : "暂停") { model.togglePause() }.font(.caption)
            }.font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
            if !model.stage.isEmpty {
                Text(model.stage).font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal)
            }
            if model.results.isEmpty {
                Spacer()
                Text(query.isEmpty ? "相册中暂无可访问图片" : "没有匹配的图片。识别错字或竖排漏字也可能影响搜索。")
                    .foregroundStyle(.secondary).multilineTextAlignment(.center).padding()
                Spacer()
            } else {
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: sizeClass == .regular ? 4 : 3), spacing: 3) {
                        ForEach(model.results) { record in
                            Button { selected = record } label: { PhotoTile(id: record.id) }
                                .buttonStyle(.plain)
                        }
                    }
                    if model.results.count < model.totalResults {
                        Button("加载更多") { model.loadMore() }.padding()
                    }
                }
            }
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

private struct PhotoTile: View {
    let id: String
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            Group {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height).clipped()
                } else { Color(.secondarySystemFill) }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .task(id: id) {
            guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return }
            let options = PHImageRequestOptions()
            options.deliveryMode = .opportunistic
            options.isNetworkAccessAllowed = false
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 360, height: 360),
                                                  contentMode: .aspectFill, options: options) { picture, _ in
                if let picture { Task { @MainActor in image = picture } }
            }
        }
        .accessibilityLabel("表情包图片")
    }
}

private struct PhotoPreview: View {
    let record: PhotoRecord
    @State private var image: UIImage?
    @State private var sharing = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Group {
                    if let image { Image(uiImage: image).resizable().scaledToFit() }
                    else { ProgressView() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                Text(record.text.isEmpty ? "未识别到文字" : record.text)
                    .font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
                    .lineLimit(6).padding()
            }
            .navigationTitle("图片预览")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("分享") { sharing = true }.disabled(image == nil)
                }
            }
            .sheet(isPresented: $sharing) { ActivitySheet(items: image.map { [$0] } ?? []) }
            .task(id: record.id) {
                guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [record.id], options: nil).firstObject else { return }
                let options = PHImageRequestOptions()
                options.deliveryMode = .highQualityFormat
                options.isNetworkAccessAllowed = false
                PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 2048, height: 2048),
                                                      contentMode: .aspectFit, options: options) { picture, _ in
                    if let picture { Task { @MainActor in image = picture } }
                }
            }
        }
    }
}

private struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
