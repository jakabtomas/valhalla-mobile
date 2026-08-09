import Foundation
import ValhallaConfigModels
import ValhallaModels
import XCTest
@testable import Valhalla

final class TestInjectedHTTPClient: XCTestCase {
    private var cacheDirectory: URL!

    override func setUpWithError() throws {
        cacheDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: cacheDirectory,
            withIntermediateDirectories: true
        )
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: cacheDirectory)
    }

    func testBridgePreservesRangeResponse() {
        let expected = Data([0x01, 0x02, 0x03])
        let client = RecordingHTTPClient { _, offset, size in
            XCTAssertEqual(offset, 512)
            XCTAssertEqual(size, 3)
            return .success(statusCode: 206, body: expected)
        }
        let bridge = ValhallaHTTPClientBridge(client)

        let response = bridge.getURL(
            "provider://archive",
            rangeOffset: 512,
            rangeSize: 3
        )

        XCTAssertEqual(response.statusCode, 206)
        XCTAssertEqual(response.body, expected)
        XCTAssertNil(response.failureMessage)
    }

    func testInjectedProviderRoutesWithoutNetwork() throws {
        let provider = LooseTileFixtureHTTPClient()
        let config = try ValhallaConfig(
            tilesUrl: "provider://tiles/{tilePath}",
            tilesDir: cacheDirectory
        )
        let valhalla = try Valhalla(config, httpClient: provider)
        let request = RouteRequest(
            locations: [
                RoutingWaypoint(lat: 42.5063, lon: 1.5218),
                RoutingWaypoint(lat: 42.5086, lon: 1.5394)
            ],
            costing: .auto,
            units: .mi
        )

        let response = try valhalla.route(request: request)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        XCTAssertTrue(
            provider.requestedPaths.contains {
                $0.hasSuffix("2/000/763/926.gph")
            }
        )
    }
}

private final class RecordingHTTPClient: ValhallaHTTPClient {
    typealias GetHandler = (String, UInt64, UInt64) -> ValhallaHTTPResponse

    private let getHandler: GetHandler

    init(getHandler: @escaping GetHandler) {
        self.getHandler = getHandler
    }

    func get(
        url: String,
        rangeOffset: UInt64,
        rangeSize: UInt64
    ) -> ValhallaHTTPResponse {
        getHandler(url, rangeOffset, rangeSize)
    }

    func head(
        url _: String,
        headerMask _: UInt
    ) -> ValhallaHTTPResponse {
        .success(statusCode: 200)
    }
}

private final class LooseTileFixtureHTTPClient: ValhallaHTTPClient {
    private let lock = NSLock()
    private var paths: [String] = []

    var requestedPaths: [String] {
        lock.lock()
        defer { lock.unlock() }
        return paths
    }

    func get(
        url: String,
        rangeOffset: UInt64,
        rangeSize: UInt64
    ) -> ValhallaHTTPResponse {
        guard rangeOffset == 0, rangeSize == 0 else {
            return .failure("The loose-tile fixture does not support ranges.")
        }
        let path = String(url.dropFirst("provider://tiles/".count))
        lock.lock()
        paths.append(path)
        lock.unlock()

        let file = Bundle.module.resourceURL!
            .appendingPathComponent("TestData/valhalla_tiles")
            .appendingPathComponent(path)
        do {
            return .success(statusCode: 200, body: try Data(contentsOf: file))
        } catch {
            return .failure("The requested fixture tile does not exist.", statusCode: 404)
        }
    }

    func head(
        url _: String,
        headerMask _: UInt
    ) -> ValhallaHTTPResponse {
        .success(statusCode: 200)
    }
}
