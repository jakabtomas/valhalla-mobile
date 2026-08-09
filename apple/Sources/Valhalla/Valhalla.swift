import ValhallaObjc
import ValhallaModels
import ValhallaConfigModels

public protocol ValhallaProviding {
    
    init(_ config: ValhallaConfig) throws
    
    init(configPath: String) throws

    func route(request: RouteRequest) throws -> RouteResponse
}

public final class Valhalla: ValhallaProviding {
    private let actor: ValhallaWrapper
    private let httpClientBridge: ValhallaHTTPClientBridge?

    public convenience init(_ config: ValhallaConfig) throws {
        let configURL = try ValhallaFileManager.saveConfigTo(config)
        try self.init(configPath: configURL.relativePath)
    }

    public convenience init(
        _ config: ValhallaConfig,
        httpClient: any ValhallaHTTPClient
    ) throws {
        let configURL = try ValhallaFileManager.saveConfigTo(config)
        try self.init(
            configPath: configURL.relativePath,
            httpClient: httpClient
        )
    }

    public required init(configPath: String) throws {
        try Self.prepareTimeZoneData()
        httpClientBridge = nil
        actor = try Self.makeActor(configPath: configPath)
    }

    public init(
        configPath: String,
        httpClient: any ValhallaHTTPClient
    ) throws {
        try Self.prepareTimeZoneData()
        let bridge = ValhallaHTTPClientBridge(httpClient)
        httpClientBridge = bridge
        actor = try Self.makeActor(
            configPath: configPath,
            httpClient: bridge
        )
    }
    
    public func route(request: RouteRequest) throws -> RouteResponse {
        let requestData = try JSONEncoder().encode(request)
        guard let requestStr = String(data: requestData, encoding: .utf8) else {
            throw ValhallaError.encodingNotUtf8("requestStr")
        }
        
        let resultStr = route(rawRequest: requestStr)
        guard let resultData = resultStr.data(using: .utf8) else {
            throw ValhallaError.encodingNotUtf8("resultData")
        }
        
        if let error = try? JSONDecoder().decode(ValhallaErrorModel.self, from: resultData) {
            throw ValhallaError.valhallaError(error.code, error.message)
        }
        
        return try JSONDecoder().decode(RouteResponse.self, from: resultData)
    }

    public func route(rawRequest request: String) -> String {
        actor.route(request)
    }

    private static func prepareTimeZoneData() throws {
        do {
            try ValhallaFileManager.injectTzdataIntoLibrary()
        } catch {
            throw ValhallaError.valhallaError(
                -1,
                "Valhalla time-zone data could not be prepared: " +
                    error.localizedDescription
            )
        }
    }

    private static func makeActor(
        configPath: String,
        httpClient: ValhallaHTTPClientBridge? = nil
    ) throws -> ValhallaWrapper {
        do {
            if let httpClient {
                return try ValhallaWrapper(
                    configPath: configPath,
                    httpClient: httpClient
                )
            }
            return try ValhallaWrapper(configPath: configPath)
        } catch let error as NSError {
            throw ValhallaError.valhallaError(error.code, error.domain)
        } catch {
            throw ValhallaError.valhallaError(
                -1,
                error.localizedDescription
            )
        }
    }
}
