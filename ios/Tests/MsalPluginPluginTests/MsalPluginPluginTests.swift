import XCTest
@testable import MsalPluginPlugin

class MsalPluginTests: XCTestCase {
    func testInstantiation() {
        // Smoke test: the implementation can be created without a configured client.
        let implementation = MsalPlugin()
        XCTAssertNotNil(implementation)
    }
}
