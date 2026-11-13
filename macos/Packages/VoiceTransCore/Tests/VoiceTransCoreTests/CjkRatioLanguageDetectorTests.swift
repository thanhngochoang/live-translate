import XCTest
@testable import VoiceTransCore

final class CjkRatioLanguageDetectorTests: XCTestCase {
    private let detector = CjkRatioLanguageDetector()

    func testEnglish() {
        XCTAssertEqual(detector.detect(text: "good morning everyone"), .en)
    }

    func testChinese() {
        XCTAssertEqual(detector.detect(text: "大家早上好"), .zh)
    }

    func testMixedMostlyChinese() {
        XCTAssertEqual(detector.detect(text: "我们明天开meeting"), .zh)
    }

    func testMixedMostlyEnglish() {
        XCTAssertEqual(detector.detect(text: "let's go to the 公司 now please"), .en)
    }

    func testBlank() {
        XCTAssertEqual(detector.detect(text: "   "), .en)
    }
}
