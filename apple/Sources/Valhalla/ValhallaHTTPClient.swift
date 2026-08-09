import Foundation
import ValhallaObjc

public struct ValhallaHTTPResponse: Sendable {
    public let statusCode: Int
    public let body: Data
    public let lastModifiedEpochSeconds: UInt64
    public let failureMessage: String?

    public init(
        statusCode: Int,
        body: Data = Data(),
        lastModifiedEpochSeconds: UInt64 = 0,
        failureMessage: String? = nil
    ) {
        self.statusCode = statusCode
        self.body = body
        self.lastModifiedEpochSeconds = lastModifiedEpochSeconds
        self.failureMessage = failureMessage
    }

    public static func success(
        statusCode: Int,
        body: Data = Data(),
        lastModifiedEpochSeconds: UInt64 = 0
    ) -> Self {
        Self(
            statusCode: statusCode,
            body: body,
            lastModifiedEpochSeconds: lastModifiedEpochSeconds
        )
    }

    public static func failure(
        _ message: String,
        statusCode: Int = 0
    ) -> Self {
        Self(statusCode: statusCode, failureMessage: message)
    }
}

public protocol ValhallaHTTPClient: AnyObject {
    func get(
        url: String,
        rangeOffset: UInt64,
        rangeSize: UInt64
    ) -> ValhallaHTTPResponse

    func head(
        url: String,
        headerMask: UInt
    ) -> ValhallaHTTPResponse
}

final class ValhallaHTTPClientBridge: NSObject, ValhallaObjcHTTPClient {
    private let client: any ValhallaHTTPClient

    init(_ client: any ValhallaHTTPClient) {
        self.client = client
    }

    func getURL(
        _ url: String,
        rangeOffset: UInt64,
        rangeSize: UInt64
    ) -> ValhallaObjcHTTPResponse {
        client.get(
            url: url,
            rangeOffset: rangeOffset,
            rangeSize: rangeSize
        ).objc
    }

    func headURL(
        _ url: String,
        headerMask: UInt
    ) -> ValhallaObjcHTTPResponse {
        client.head(url: url, headerMask: headerMask).objc
    }
}

private extension ValhallaHTTPResponse {
    var objc: ValhallaObjcHTTPResponse {
        ValhallaObjcHTTPResponse(
            statusCode: statusCode,
            body: body,
            lastModifiedEpochSeconds: lastModifiedEpochSeconds,
            failureMessage: failureMessage
        )
    }
}
