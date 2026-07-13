import AppKit
import SwiftUI

struct SmartNotesView: View {
    @ObservedObject private var store = SmartNotesStore.shared
    @ObservedObject private var settings = SettingsStore.shared
    @ObservedObject private var asr: ASRService
    @Environment(\.theme) private var theme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let onDictate: (SmartNote) -> Void
    @State private var selectedTag: String?
    @State private var tagSearchText: String = ""
    @State private var notePendingDeletion: SmartNote?
    @State private var deleteErrorMessage: String?

    init(asr: ASRService, onDictate: @escaping (SmartNote) -> Void) {
        self._asr = ObservedObject(wrappedValue: asr)
        self.onDictate = onDictate
    }

    private var filteredNotes: [SmartNote] {
        guard let selectedTag else { return self.store.notes }
        return self.store.notes.filter { $0.tags.contains(selectedTag) }
    }

    private var availableTags: [String] {
        Array(Set(self.store.notes.flatMap(\.tags))).sorted()
    }

    private var visibleTags: [String] {
        let query = self.tagSearchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let tags = query.isEmpty ? self.availableTags : self.availableTags.filter { $0.localizedCaseInsensitiveContains(query) }
        return Array(tags.prefix(10))
    }

    private var selectedNote: SmartNote? {
        guard let selectedNoteID = self.store.selectedNoteID else { return self.filteredNotes.first }
        return self.filteredNotes.first(where: { $0.id == selectedNoteID }) ?? self.filteredNotes.first
    }

    var body: some View {
        VStack(spacing: 0) {
            self.header
            Divider()

            if self.store.notes.isEmpty {
                self.emptyState
            } else {
                HSplitView {
                    VStack(spacing: 0) {
                        self.tagFilters
                        self.noteList
                    }
                    .frame(minWidth: 230, idealWidth: 280, maxWidth: 360)
                    self.noteDetail
                        .frame(minWidth: 420, maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
        .navigationTitle("Smart Notes")
        .onAppear {
            self.store.reload()
            if self.store.selectedNoteID == nil {
                self.store.selectedNoteID = self.filteredNotes.first?.id
            }
        }
        .confirmationDialog(
            "Delete this note?",
            isPresented: Binding(
                get: { self.notePendingDeletion != nil },
                set: { if !$0 { self.notePendingDeletion = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete Note", role: .destructive) {
                guard let note = self.notePendingDeletion else { return }
                self.notePendingDeletion = nil
                do {
                    try self.store.delete(note)
                    self.store.selectedNoteID = self.filteredNotes.first?.id
                } catch {
                    self.deleteErrorMessage = error.localizedDescription
                    DebugLogger.shared.warning(
                        "Failed to delete Smart Note: \(error.localizedDescription)",
                        source: "SmartNotesView"
                    )
                }
            }
            Button("Cancel", role: .cancel) {
                self.notePendingDeletion = nil
            }
        }
        .alert(
            "Couldn’t Delete Note",
            isPresented: Binding(
                get: { self.deleteErrorMessage != nil },
                set: { if !$0 { self.deleteErrorMessage = nil } }
            )
        ) {
            Button("OK", role: .cancel) {
                self.deleteErrorMessage = nil
            }
        } message: {
            Text(self.deleteErrorMessage ?? "The note could not be deleted.")
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Smart Notes")
                    .font(.title2.weight(.semibold))
                Text("Dictate from anywhere. Notes are saved as Markdown in Documents/FluidVoice Notes.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Toggle("Enhance with AI", isOn: Binding(
                get: { self.settings.smartNotesAIEnhancementEnabled },
                set: { self.settings.smartNotesAIEnhancementEnabled = $0 }
            ))
            .toggleStyle(.switch)
            .help("Organize new notes, add a title, category, and tags using the configured AI provider.")

            Button("Open Folder") {
                try? FileManager.default.createDirectory(
                    at: self.store.notesDirectoryURL,
                    withIntermediateDirectories: true
                )
                NSWorkspace.shared.open(self.store.notesDirectoryURL)
            }

            Button {
                self.store.reload()
            } label: {
                Image(systemName: "arrow.clockwise")
            }
            .help("Refresh notes")
        }
        .padding(18)
    }

    private var noteList: some View {
        List(selection: self.$store.selectedNoteID) {
            ForEach(self.filteredNotes) { note in
                VStack(alignment: .leading, spacing: 5) {
                    Text(note.title)
                        .font(.body.weight(.medium))
                        .lineLimit(2)

                    HStack(spacing: 6) {
                        Text(note.createdAt, style: .date)
                        Text(note.createdAt, style: .time)
                        if note.isAIEnhanced {
                            Label("AI", systemImage: "sparkles")
                                .foregroundStyle(.purple)
                        }
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
                .tag(note.id)
                .contextMenu {
                    Button("Reveal in Finder") {
                        NSWorkspace.shared.activateFileViewerSelecting([note.fileURL])
                    }
                    Button("Delete", role: .destructive) {
                        self.notePendingDeletion = note
                    }
                }
            }
        }
        .listStyle(.sidebar)
    }

    private var tagFilters: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("Search tags", text: self.$tagSearchText)
                    .textFieldStyle(.plain)
                if self.selectedTag != nil || !self.tagSearchText.isEmpty {
                    Button("Clear") {
                        self.selectedTag = nil
                        self.tagSearchText = ""
                        self.store.selectedNoteID = self.filteredNotes.first?.id
                    }
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(Color.secondary.opacity(0.10))
            .clipShape(RoundedRectangle(cornerRadius: 9))

            if self.selectedTag != nil || !self.visibleTags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        self.tagChip(title: "All", tag: nil)
                        ForEach(self.visibleTags, id: \.self) { tag in
                            self.tagChip(title: "#\(tag)", tag: tag)
                        }
                    }
                }
            }
        }
        .padding(12)
        .background(.regularMaterial)
    }

    private func tagChip(title: String, tag: String?) -> some View {
        Button {
            self.selectedTag = tag
            self.tagSearchText = tag ?? ""
            self.store.selectedNoteID = self.filteredNotes.first?.id
        } label: {
            Text(title)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(self.selectedTag == tag ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.12))
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var noteDetail: some View {
        if let note = self.selectedNote {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        HStack(alignment: .top, spacing: 16) {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(note.title)
                                    .font(.largeTitle.weight(.bold))
                                    .textSelection(.enabled)

                                HStack(spacing: 8) {
                                    if let category = note.category {
                                        Label(category, systemImage: "folder")
                                    }
                                    ForEach(note.tags, id: \.self) { tag in
                                        Text("#\(tag)")
                                    }
                                }
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }

                            Spacer()

                            Button(role: .destructive) {
                                self.notePendingDeletion = note
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                            .buttonStyle(.bordered)
                            .tint(.red)
                            .help("Delete this note")

                            Menu {
                                Button("Reveal in Finder") {
                                    NSWorkspace.shared.activateFileViewerSelecting([note.fileURL])
                                }
                            } label: {
                                Image(systemName: "ellipsis.circle")
                            }
                            .menuStyle(.borderlessButton)
                        }

                        Divider()

                        MarkdownBodyView(markdown: note.body)
                            .textSelection(.enabled)
                    }
                    .padding(28)
                }

                Divider()
                VStack(spacing: 8) {
                    if self.asr.isRunning {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(.red)
                                .frame(width: 6, height: 6)
                            Text("Recording...")
                                .font(self.theme.typography.captionStrong)
                                .foregroundStyle(.red)
                        }
                    }

                    Button {
                        self.onDictate(note)
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: self.asr.isRunning ? "stop.fill" : "mic.fill")
                            Text(self.asr.isRunning ? "Stop Recording" : "Start Recording")
                        }
                        .frame(maxWidth: 220)
                    }
                    .fluidButton(.primary, size: .large, isRecording: self.asr.isRunning)
                    .buttonHoverEffect()
                    .scaleEffect(!self.reduceMotion && self.asr.isRunning ? 1.02 : 1.0)
                    .animation(self.reduceMotion ? nil : .spring(response: 0.3), value: self.asr.isRunning)
                    .disabled(!self.asr.isAsrReady && !self.asr.isRunning)
                    .help(self.asr.isRunning ? "Stop and update this note" : "Dictate an update to this note")
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(.regularMaterial)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "note.text.badge.plus")
                .font(.system(size: 46, weight: .light))
                .foregroundStyle(.secondary)
            Text("No Smart Notes Yet")
                .font(.title3.weight(.semibold))
            Text("Set a Smart Notes shortcut in Settings, then use it to dictate your first note.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Open Notes Folder") {
                NSWorkspace.shared.open(self.store.notesDirectoryURL)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

private struct MarkdownBodyView: View {
    let markdown: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(self.markdown.split(separator: "\n", omittingEmptySubsequences: false).enumerated()), id: \.offset) { _, rawLine in
                self.lineView(String(rawLine))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func lineView(_ line: String) -> some View {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            Spacer().frame(height: 4)
        } else if trimmed.hasPrefix("### ") {
            Text(String(trimmed.dropFirst(4))).font(.title3.weight(.semibold)).padding(.top, 4)
        } else if trimmed.hasPrefix("## ") {
            Text(String(trimmed.dropFirst(3))).font(.title2.weight(.semibold)).padding(.top, 6)
        } else if trimmed.hasPrefix("# ") {
            Text(String(trimmed.dropFirst(2))).font(.title.weight(.bold)).padding(.top, 8)
        } else if trimmed.hasPrefix("- [ ] ") || trimmed.hasPrefix("* [ ] ") {
            self.checkLine(text: String(trimmed.dropFirst(6)), checked: false)
        } else if trimmed.hasPrefix("- [x] ") || trimmed.hasPrefix("- [X] ") || trimmed.hasPrefix("* [x] ") || trimmed.hasPrefix("* [X] ") {
            self.checkLine(text: String(trimmed.dropFirst(6)), checked: true)
        } else if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") {
            self.bulletLine(text: String(trimmed.dropFirst(2)))
        } else if trimmed.hasPrefix("> ") {
            Text(String(trimmed.dropFirst(2)))
                .foregroundStyle(.secondary)
                .padding(.leading, 12)
                .overlay(alignment: .leading) { Rectangle().fill(Color.secondary.opacity(0.35)).frame(width: 3) }
        } else if let attributed = try? AttributedString(markdown: trimmed) {
            Text(attributed).font(.body).lineSpacing(5)
        } else {
            Text(trimmed).font(.body).lineSpacing(5)
        }
    }

    private func checkLine(text: String, checked: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: checked ? "checkmark.square.fill" : "square")
                .foregroundStyle(checked ? .green : .secondary)
            Text((try? AttributedString(markdown: text)) ?? AttributedString(text))
        }
        .font(.body)
    }

    private func bulletLine(text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("•").foregroundStyle(.secondary)
            Text((try? AttributedString(markdown: text)) ?? AttributedString(text))
        }
        .font(.body)
    }
}
