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
        XCTAssertFalse(d.onSample(s(15, 0)))
        XCTAssertTrue(d.onSample(s(20, 0)))
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

    func testManualLookupMarksSpot() {
        var d = ArrivalDetector(dwellSeconds: 0)
        d.markLookedUp(lat: lat, lng: lng)
        XCTAssertTrue(d.wasLookedUpNear(lat: lat, lng: lng))
        XCTAssertFalse(d.onSample(s(0, 0)))
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
           "photos":[{"name":"places/near/photos/a",
             "authorAttributions":[{"displayName":"Ann","uri":"//maps.google.com/maps/contrib/1"}]}],"googleMapsUri":"https://maps.google.com/?cid=1"}
        ]}
        """
        let r = try PlacesParser.parseNearby(Data(json.utf8), fromLat: 40, fromLng: -75)
        XCTAssertEqual(r.map(\.id), ["near", "far"])
        XCTAssertEqual(r[0].name, "McDonald's")
        XCTAssertEqual(r[0].address, "1 Main St")
        XCTAssertEqual(r[0].photoNames, ["places/near/photos/a"])
        XCTAssertEqual(r[0].photos[0].authorName, "Ann")
        XCTAssertEqual(r[0].photos[0].authorUri, "https://maps.google.com/maps/contrib/1")
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

/// pullup-menu/shared/<name>, from ios/PullUpCore/Tests/PullUpCoreTests/.
private func sharedFile(_ name: String, from file: String = #filePath) -> Data {
    let url = URL(fileURLWithPath: file)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("shared/\(name)")
    return try! Data(contentsOf: url)
}

final class ChainItemListsTests: XCTestCase {
    lazy var lists = try! ChainItemLists(json: sharedFile("chain_prices.json"))

    func testEveryMenuChainHasAnItemList() throws {
        let root = try JSONSerialization.jsonObject(with: sharedFile("chain_menus.json")) as! [String: Any]
        for chain in root["chains"] as! [[String: Any]] {
            let name = chain["name"] as! String
            let list = try XCTUnwrap(lists.list(for: name), "\(name) has no item list")
            XCTAssertFalse(list.items.isEmpty)
            XCTAssertLessThanOrEqual(list.items.count, 150)
            let keys = list.items.map { ChainMenus.normalize($0.name) }
            XCTAssertEqual(keys.count, Set(keys).count, "\(name) has duplicate items")
            XCTAssertTrue(list.sourceUrl.hasPrefix("https://"), name)
            XCTAssertFalse(list.checked.isEmpty, name)
        }
    }

    func testMatchesLikeChainMenus() {
        XCTAssertEqual(lists.list(for: "Taco Bell Cantina")?.chain, "Taco Bell")
        XCTAssertEqual(lists.list(for: "McDonald’s (demo)")?.chain, "McDonald's")
        XCTAssertNil(lists.list(for: "Supersonic Car Wash"))
    }

    func testNotesAndCategories() throws {
        let list = try XCTUnwrap(lists.list(for: "Starbucks"))
        XCTAssertEqual(list.items.first { $0.name == "Pumpkin Spice Latte" }?.note, "Seasonal, may not be available now")
        XCTAssertNil(list.items.first { $0.name == "Caffe Latte" }?.note)
        XCTAssertTrue(list.items.allSatisfy { $0.category != nil })
    }

    func testParsingRules() throws {
        let json = """
        {"checked":"Oct 6, 2026","chains":[
          {"chain":"Both","match":["both"],"sourceName":"news.com","sourceUrl":"https://news.com",
           "menuSourceName":"both.com","menuSourceUrl":"https://both.com/menu",
           "items":[{"name":"Big Burger Deal","price":"$5"}],
           "menuItems":[{"name":"Big Burger","category":"Burgers","price":"$4"},{"name":"Big  Burger"},
                        {"name":"Fries","category":"Sides","note":""}]},
          {"chain":"Deals","match":["deals"],"sourceName":"deals.com","sourceUrl":"https://deals.com",
           "checked":"Jan 1, 2026","items":[{"name":"Meal Deal","price":"$6"}]},
          {"chain":"Empty","match":["empty"],"sourceName":"e.com","sourceUrl":"https://e.com","items":[]}
        ]}
        """
        let l = try ChainItemLists(json: Data(json.utf8))
        let both = try XCTUnwrap(l.list(for: "Both"))
        XCTAssertEqual(both.items.map(\.name), ["Big Burger", "Fries"])
        XCTAssertNil(both.items[1].note)
        XCTAssertEqual(both.sourceName, "both.com")
        XCTAssertEqual(both.disclaimer, "Menu items from both.com. Availability varies by location. Checked Oct 6, 2026.")
        let deals = try XCTUnwrap(l.list(for: "Deals"))
        XCTAssertEqual(deals.items.map(\.name), ["Meal Deal"])
        XCTAssertEqual(deals.checked, "Jan 1, 2026")
        XCTAssertNil(l.list(for: "Empty"))
        XCTAssertEqual(l.demoChainName, "Both")
    }
}

final class RedLightFilterTests: XCTestCase {
    let lat = 40.0, lng = -75.0

    func testDrivingOffQuicklyIgnoresTheSpot() {
        var f = RedLightFilter()
        f.onTrigger(lat: lat, lng: lng, time: 0)
        XCTAssertFalse(f.onSample(lat: lat, lng: lng, time: 10, speedMps: 2))
        XCTAssertTrue(f.onSample(lat: lat, lng: lng, time: 20, speedMps: 12))
        XCTAssertTrue(f.isIgnored(lat: lat + 0.0002, lng: lng))   // ~22 m away
        XCTAssertFalse(f.isIgnored(lat: lat + 0.001, lng: lng))   // ~111 m away
        XCTAssertFalse(f.onSample(lat: lat, lng: lng, time: 25, speedMps: 12))  // only once
    }

    func testSlowLineIsNotAFalseAlarm() {
        var f = RedLightFilter()
        f.onTrigger(lat: lat, lng: lng, time: 0)
        XCTAssertFalse(f.onSample(lat: lat, lng: lng, time: 30, speedMps: 3))
        XCTAssertFalse(f.onSample(lat: lat, lng: lng, time: 60, speedMps: 15))  // after the window
        XCTAssertFalse(f.isIgnored(lat: lat, lng: lng))
    }

    func testClear() {
        var f = RedLightFilter()
        f.onTrigger(lat: lat, lng: lng, time: 0)
        _ = f.onSample(lat: lat, lng: lng, time: 5, speedMps: 20)
        f.clear()
        XCTAssertFalse(f.isIgnored(lat: lat, lng: lng))
    }
}
