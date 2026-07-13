import Foundation

/// Smart Notes have their own enhancement toggle and must not inherit dictation prompt state.
enum SmartNoteAIPostProcessingGate {
    static func isConfigured() -> Bool {
        DictationAIPostProcessingGate.isProviderConfigured()
    }
}

@MainActor
final class SmartNoteCaptureService {
    static let shared = SmartNoteCaptureService()

    struct PromptProfile: Identifiable, Equatable {
        let id: String
        let title: String
        let category: String
        let tag: String
        let systemPrompt: String
    }

    static let noAIPromptID = "__smart_note_no_ai__"
    static let defaultPromptID = "__smart_note_default__"
    static let shoppingListPromptID = "shopping-list"

    private init() {}

    func capture(rawText: String, enhance: Bool, promptID: String? = nil) async throws -> RemoteAPI.SmartNoteCaptureResponse {
        let profile = Self.promptProfile(for: promptID)
        let storedPromptID = promptID == Self.noAIPromptID ? Self.noAIPromptID : profile.id
        let providerConfiguration = SettingsStore.shared.remoteAIConfiguration(promptID: profile.id)
        var note = try SmartNotesStore.shared.capture(rawText: rawText, promptID: storedPromptID)
        var enhancementError: String?

        let shouldEnhance = enhance && promptID != Self.noAIPromptID
        if shouldEnhance {
            DebugLogger.shared.info(
                "Remote Smart Notes enhancement requested=true",
                source: "SmartNoteCaptureService"
            )

            do {
                let response = try await DictationPostProcessingService.shared.process(
                    rawText,
                    promptOverride: Self.promptWithExistingTags(profile.systemPrompt),
                    providerConfiguration: providerConfiguration
                ).text
                let enhancement = try SmartNoteEnhancement.parseAIResponse(response)
                note = try SmartNotesStore.shared.apply(
                    Self.normalizedEnhancement(enhancement, for: profile),
                    to: note.id
                )
            } catch {
                enhancementError = error.localizedDescription
                DebugLogger.shared.error(
                    "Remote Smart Notes enrichment failed; raw note preserved: \(error.localizedDescription)",
                    source: "SmartNoteCaptureService"
                )
            }
        } else {
            DebugLogger.shared.info(
                "Remote Smart Notes enhancement requested=false",
                source: "SmartNoteCaptureService"
            )
        }

        return .init(note: Self.response(for: note), rawText: rawText, enhancementError: enhancementError)
    }

    func continueNote(id: UUID, rawText: String, promptID: String? = nil) async throws -> RemoteAPI.SmartNoteCaptureResponse {
        SmartNotesStore.shared.reload()
        guard let note = SmartNotesStore.shared.notes.first(where: { $0.id == id }) else {
            throw SmartNotesError.noteNotFound
        }
        if promptID == Self.noAIPromptID || (promptID == nil && note.promptID == Self.noAIPromptID) {
            let updated = try SmartNotesStore.shared.append(rawText: rawText, to: note.id)
            return .init(note: Self.response(for: updated), rawText: rawText, enhancementError: nil)
        }
        let profile = Self.promptProfile(for: promptID, note: note)
        let providerConfiguration = SettingsStore.shared.remoteAIConfiguration(promptID: profile.id)

        let prompt = """
        \(Self.promptWithExistingTags(profile.systemPrompt))

        You are updating one existing Smart Note. Apply the user's new spoken instruction to the current note.
        Keep the existing useful content unless the user clearly changes or removes it.
        Return the complete updated note, not a diff.

        Current note:
        {"title":\(Self.json(note.title)),"category":\(Self.json(note.category ?? profile.category)),"tags":\(Self.json(note.tags)),"body":\(Self.json(note.body))}
        """
        let response = try await DictationPostProcessingService.shared.process(
            rawText,
            promptOverride: prompt,
            providerConfiguration: providerConfiguration
        ).text
        let enhancement = try SmartNoteEnhancement.parseAIResponse(response)
        let updated = try SmartNotesStore.shared.apply(
            Self.normalizedEnhancement(enhancement, for: profile),
            to: note.id
        )
        return .init(note: Self.response(for: updated), rawText: rawText, enhancementError: nil)
    }

    func notes(limit: Int = 100) -> [RemoteAPI.SmartNoteResponse] {
        SmartNotesStore.shared.reload()
        return SmartNotesStore.shared.notes.prefix(limit).map(Self.response)
    }

    func delete(id: UUID) throws {
        SmartNotesStore.shared.reload()
        guard let note = SmartNotesStore.shared.notes.first(where: { $0.id == id }) else {
            throw SmartNotesError.noteNotFound
        }
        try SmartNotesStore.shared.delete(note)
    }

    private static func response(for note: SmartNote) -> RemoteAPI.SmartNoteResponse {
        .init(
            id: note.id.uuidString.lowercased(),
            createdAt: Self.iso8601.string(from: note.createdAt),
            title: note.title,
            category: note.category,
            tags: note.tags,
            body: note.body,
            isAIEnhanced: note.isAIEnhanced,
            promptID: note.promptID
        )
    }

    static func promptProfile(for id: String?, note: SmartNote? = nil) -> PromptProfile {
        SettingsStore.shared.reconcilePromptStateAfterProfileChanges()
        let profiles = SettingsStore.shared.promptProfiles(for: .smartNote)
        if let id, id != Self.defaultPromptID,
           let profile = profiles.first(where: { $0.id.caseInsensitiveCompare(id) == .orderedSame })
        {
            return Self.promptProfile(from: profile)
        }
        if id == nil, let storedPromptID = note?.promptID,
           let profile = profiles.first(where: { $0.id.caseInsensitiveCompare(storedPromptID) == .orderedSame })
        {
            return Self.promptProfile(from: profile)
        }
        if note?.tags.contains(Self.shoppingListPromptID) == true {
            if let profile = profiles.first(where: { $0.id == Self.shoppingListPromptID }) {
                return Self.promptProfile(from: profile)
            }
        }
        return .init(
            id: Self.defaultPromptID,
            title: "General Note",
            category: "Note",
            tag: "generic-note",
            systemPrompt: SettingsStore.defaultSystemPromptText(for: .smartNote)
        )
    }

    static func smartNotePromptResponses() -> [RemoteAPI.PromptResponse] {
        SettingsStore.shared.reconcilePromptStateAfterProfileChanges()
        let noAI = RemoteAPI.PromptResponse(
            id: Self.noAIPromptID,
            title: "Note (no AI)",
            kind: .smartNote,
            icon: SettingsStore.PromptIcon.document.rawValue,
            isBuiltIn: true
        )
        let defaultPrompt = RemoteAPI.PromptResponse(
            id: Self.defaultPromptID,
            title: "General Note",
            kind: .smartNote,
            icon: SettingsStore.PromptIcon.documentSparkles.rawValue,
            isBuiltIn: true
        )
        let profiles = SettingsStore.shared.promptProfiles(for: .smartNote).map {
            RemoteAPI.PromptResponse(
                id: $0.id,
                title: $0.name.isEmpty ? "Untitled Prompt" : $0.name,
                kind: .smartNote,
                icon: $0.icon.rawValue,
                isBuiltIn: false
            )
        }
        return [noAI, defaultPrompt] + profiles
    }

    private static func promptProfile(from profile: SettingsStore.DictationPromptProfile) -> PromptProfile {
        let title = profile.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Untitled Prompt" : profile.name
        let isShopping = profile.id == Self.shoppingListPromptID || title.localizedCaseInsensitiveContains("shopping")
        return .init(
            id: profile.id,
            title: title,
            category: isShopping ? "Shopping List" : "Note",
            tag: isShopping ? Self.shoppingListPromptID : Self.slug(for: title),
            systemPrompt: SettingsStore.combineBasePrompt(for: .smartNote, with: profile.prompt)
        )
    }

    private static func normalizedEnhancement(_ enhancement: SmartNoteEnhancement, for profile: PromptProfile) -> SmartNoteEnhancement {
        var tags = enhancement.tags
        if !tags.contains(profile.tag) {
            tags.insert(profile.tag, at: 0)
        }
        return SmartNoteEnhancement(
            title: enhancement.title,
            category: profile.id == Self.shoppingListPromptID ? profile.category : enhancement.category,
            tags: Array(Self.uniqued(tags).prefix(5)),
            body: enhancement.body
        )
    }

    private static func promptWithExistingTags(_ prompt: String) -> String {
        SmartNotesStore.shared.reload()
        let existingTags = Self.uniqued(SmartNotesStore.shared.notes.flatMap(\.tags)).sorted()
        guard !existingTags.isEmpty else { return prompt }
        return """
        \(prompt)

        Existing tags available for reuse:
        \(existingTags.prefix(80).map { "- \($0)" }.joined(separator: "\n"))
        """
    }

    private static func uniqued(_ values: [String]) -> [String] {
        var seen: Set<String> = []
        return values.filter { seen.insert($0).inserted }
    }

    private static func json<T: Encodable>(_ value: T) -> String {
        guard let data = try? JSONEncoder().encode(value) else { return "\"\"" }
        return String(decoding: data, as: UTF8.self)
    }

    private static func slug(for value: String) -> String {
        let allowed = value
            .lowercased()
            .map { character -> Character in
                if character.isLetter || character.isNumber { return character }
                return "-"
            }
        let collapsed = String(allowed).split(separator: "-").joined(separator: "-")
        return collapsed.isEmpty ? "smart-note" : String(collapsed.prefix(40))
    }

    private static let iso8601: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}
