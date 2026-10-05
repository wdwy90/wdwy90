import XCTest
@testable import PullUpCore

final class ArrivalDetectorTests: XCTestCase {
    let lat = 40.0, lng = -75.0
    func s(_ t: TimeInterval, _ v: Double?, dLat: Double = 0) -> ArrivalDetector.Sample {
        .init(lat: lat + dLat, lng: lng, time: t, speedMps: v)
    }

    func testTriggersAfterDwell() {
        var d = ArrivalDetector(dwellSeconds: 15)
        XCTAssertFalse(d.onSample(s(0, 0)))
        XCTAssertFalse(d.onSample(s(10, 0.5)))
        XCTAssertTrue(d.onSample(s(15, 0)))
    }

    func testDriveThruCreepCountsAsStopped() {
        var d = ArrivalDetector()
        XCTAssertFalse(d.onSample(s(0, 0)))
        XCTAssertFalse(d.onSample(s(8, 2.5)))
        XCTAssertTrue(d.onSample(s(15, 0)))
    }

    func testDrivingResetsDwell() {
        var d = ArrivalDetector(dwellSeconds: 15)
        _ = d.onSample(s(0, 0))
        _ = d.onSample(s(10, 10))
        XCTAssertFalse(d.onSample(s(20, 0)))
        XCTAssertTrue(d.onSample(s(35, 0)))
    }

    func testOneLookupPerSpot() {
        var d = ArrivalDetector(dwellSeconds: 0)
        XCTAssertTrue(d.onSample(s(0, 0)))
        XCTAssertFalse(d.onSample(s(5, 0)))
        XCTAssertTrue(d.onSample(s(10, 0, dLat: 0.01)))
    }

    func testDerivesSpeedWhenMissing() {
        var d = ArrivalDetector(dwellSeconds: 0)
        _ = d.onSample(s(0, 20))
        XCTAssertFalse(d.onSample(s(5, nil, dLat: 0.001)))   // ~22 m/s
        XCTAssertTrue(d.onSample(s(10, nil, dLat: 0.001)))
    }
}

final class PlacesParserTests: XCTestCase {
    func testParsesAndSorts() throws {
        let json = """
        {"places":[
          {"id":"far","displayName":{"text":"Far Diner"},"location":{"latitude":40.0005,"longitude":-75.0}},
          {"id":"near","displayName":{"text":"McDonald's"},"shortFormattedAddress":"1 Main St",
           "location":{"latitude":40.0001,"longitude":-75.0},"rating":3.9,
           "primaryTypeDisplayName":{"text":"Fast Food Restaurant"},
           "photos":[{"name":"places/near/photos/a"}],"googleMapsUri":"https://maps.google.com/?cid=1"}
        ]}
        """
        let r = try PlacesParser.parseNearby(Data(json.utf8), fromLat: 40, fromLng: -75)
        XCTAssertEqual(r.map(\.id), ["near", "far"])
        XCTAssertEqual(r[0].name, "McDonald's")
        XCTAssertEqual(r[0].address, "1 Main St")
        XCTAssertEqual(r[0].photoNames, ["places/near/photos/a"])
        XCTAssertEqual(r[0].distanceMeters, 11.1, accuracy: 0.5)
        XCTAssertNil(r[1].rating)
        XCTAssertEqual(try PlacesParser.parseNearby(Data("{}".utf8), fromLat: 0, fromLng: 0).count, 0)
    }
}

final class ChainMenusTests: XCTestCase {
    // ios/PullUpCore/Tests/PullUpCoreTests/ -> pullup-menu/shared/
    lazy var menus: ChainMenus = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/chain_menus.json")
        return try! ChainMenus(json: Data(contentsOf: url))
    }()

    func testMatchesNameVariants() {
        XCTAssertEqual(menus.menuUrl(for: "McDonald's"), "https://www.mcdonalds.com/us/en-us/full-menu.html")
        XCTAssertEqual(menus.menuUrl(for: "McDonald’s"), "https://www.mcdonalds.com/us/en-us/full-menu.html")
        XCTAssertEqual(menus.menuUrl(for: "Chick-fil-A"), "https://www.chick-fil-a.com/menu")
        XCTAssertEqual(menus.menuUrl(for: "In-N-Out Burger"), "https://www.in-n-out.com/menu")
        XCTAssertEqual(menus.menuUrl(for: "Carl's Jr."), "https://www.carlsjr.com/full-menu")
        XCTAssertEqual(menus.menuUrl(for: "Sonic Drive-In"), "https://www.sonicdrivein.com/menu/")
    }

    func testWholeWordsOnly() {
        XCTAssertNil(menus.menuUrl(for: "Supersonic Car Wash"))
        XCTAssertNil(menus.menuUrl(for: "Joe's Burgers"))
    }

    func testNormalize() {
        XCTAssertEqual(ChainMenus.normalize("Chick-fil-A #123"), "chickfila 123")
    }

    func testPhotoURL() {
        let u = PlacesClient(apiKey: "K").photoURL("places/a/photos/b", maxWidthPx: 400)
        XCTAssertEqual(u?.absoluteString, "https://places.googleapis.com/v1/places/a/photos/b/media?maxWidthPx=400&key=K")
    }
}
