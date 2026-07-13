@testable import FluidVoice_Debug
import Foundation
import XCTest

@MainActor
final class SmartNotesStoreTests: XCTestCase {
    private var temporaryDirectory: URL!

    override func setUpWithError() throws {
        self.temporaryDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FluidVoice-SmartNotesTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: self.temporaryDirectory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let temporaryDirectory {
            try? FileManager.default.removeItem(at: temporaryDirectory)
        }
        self.temporaryDirectory = nil
    }

    func testRawCaptureWritesReadableMarkdownAndReloads() throws {
        let store = SmartNotesStore(directoryURL: self.temporaryDirectory)
        let id = UUID(uuidString: "6A1AF9AF-EE38-49AA-8E51-23E573A62002")!
        let date = Date(timeIntervalSince1970: 1_725_000_000)

        let captured = try store.capture(
            rawText: "Remember to send the quarterly planning notes to Maya tomorrow.",
            at: date,
            id: id
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: captured.fileURL.path))
        XCTAssertTrue(captured.fileURL.lastPathComponent.contains(id.uuidString.lowercased()))
        XCTAssertFalse(captured.isAIEnhanced)
        XCTAssertEqual(captured.body, "Remember to send the quarterly planning notes to Maya tomorrow.")

        let markdown = try String(contentsOf: captured.fileURL, encoding: .utf8)
        XCTAssertTrue(markdown.contains("enhanced: false"))
        XCTAssertTrue(markdown.contains("<!-- fluidvoice:body -->"))

        let reloaded = SmartNotesStore(directoryURL: self.temporaryDirectory)
        XCTAssertEqual(reloaded.notes.count, 1)
        XCTAssertEqual(reloaded.notes[0].id, id)
        XCTAssertEqual(reloaded.notes[0].createdAt, date)
        XCTAssertEqual(reloaded.notes[0].body, captured.body)
    }

    func testBodyMarkerInsideNoteDoesNotDiscardEarlierContent() throws {
        let store = SmartNotesStore(directoryURL: self.temporaryDirectory)
        let body = "Before marker\n<!-- fluidvoice:body -->\nAfter marker"

        let captured = try store.capture(rawText: body)
        let reloaded = SmartNotesStore(directoryURL: self.temporaryDirectory)

        XCTAssertEqual(reloaded.notes.first?.id, captured.id)
        XCTAssertEqual(reloaded.notes.first?.body, body)
    }

    func testPromptIDPersistsAndRawContinuationAppendsWithoutAI() throws {
        let store = SmartNotesStore(directoryURL: self.temporaryDirectory)
        let captured = try store.capture(rawText: "Milk", promptID: SmartNoteCaptureService.noAIPromptID)

        let updated = try store.append(rawText: "Bread", to: captured.id)
        let reloaded = SmartNotesStore(directoryURL: self.temporaryDirectory)

        XCTAssertEqual(updated.body, "Milk\n\nBread")
        XCTAssertEqual(reloaded.notes.first?.promptID, SmartNoteCaptureService.noAIPromptID)
        XCTAssertFalse(reloaded.notes.first?.isAIEnhanced ?? true)
    }

    func testEverySmartNotePromptEndsWithJSONContract() {
        let combined = SettingsStore.combineBasePrompt(for: .smartNote, with: "Create a project note.")

        XCTAssertTrue(combined.hasSuffix(SettingsStore.smartNoteOutputContractText()))
        XCTAssertTrue(combined.contains("Return only one valid JSON object"))
    }

    func testShoppingListUsesPlainListsAndMovesPurchasedItems() {
        let prompt = SettingsStore.defaultShoppingListPromptBodyText()

        XCTAssertTrue(prompt.contains("## To buy"))
        XCTAssertTrue(prompt.contains("## Purchased"))
        XCTAssertTrue(prompt.contains("move it to \"## Purchased\""))
        XCTAssertTrue(prompt.contains("Never use checkboxes"))
    }

    func testPromptIconPersistsWithProfile() throws {
        let profile = SettingsStore.DictationPromptProfile(
            name: "Project note",
            prompt: "Create a project note.",
            mode: .smartNote,
            icon: .documentSparkles
        )

        let decoded = try JSONDecoder().decode(
            SettingsStore.DictationPromptProfile.self,
            from: JSONEncoder().encode(profile)
        )

        XCTAssertEqual(decoded.icon, .documentSparkles)
    }

    func testAIResponseParserExtractsJSONAndNormalizesTags() throws {
        let response = """
        ```json
        {"title":"Launch checklist","category":"Work","tags":[" Launch ","release","launch",""],"body":"- Confirm build\n- Notify support"}
        ```
        """

        let enhancement = try SmartNoteEnhancement.parseAIResponse(response)

        XCTAssertEqual(enhancement.title, "Launch checklist")
        XCTAssertEqual(enhancement.category, "Work")
        XCTAssertEqual(enhancement.tags, ["launch", "release"])
        XCTAssertEqual(enhancement.body, "- Confirm build\n- Notify support")
    }

    func testEnhancementUpdatesTheExistingMarkdownFile() throws {
        let store = SmartNotesStore(directoryURL: self.temporaryDirectory)
        let note = try store.capture(rawText: "we should organize the launch checklist")
        let originalURL = note.fileURL
        let enhancement = SmartNoteEnhancement(
            title: "Launch checklist",
            category: "Work",
            tags: ["launch", "release"],
            body: "- Organize the launch checklist"
        )

        let updated = try store.apply(enhancement, to: note.id)

        XCTAssertEqual(updated.fileURL, originalURL)
        XCTAssertTrue(updated.isAIEnhanced)
        XCTAssertEqual(updated.title, "Launch checklist")
        XCTAssertEqual(updated.tags, ["launch", "release"])
        XCTAssertEqual(store.notes.count, 1)
    }

    func testDeleteRemovesMarkdownFileAndReloadsStore() throws {
        let store = SmartNotesStore(directoryURL: self.temporaryDirectory)
        let note = try store.capture(rawText: "Delete this note")

        try store.delete(note)

        XCTAssertFalse(FileManager.default.fileExists(atPath: note.fileURL.path))
        XCTAssertTrue(store.notes.isEmpty)
    }
}
