#ifndef ValhallaWrapperHeader_h
#define ValhallaWrapperHeader_h

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@class ValhallaWrapper;

NS_SWIFT_NAME(ValhallaObjcHTTPResponse)
@interface ValhallaMobileHTTPResponse : NSObject

@property(nonatomic, readonly) NSInteger statusCode;
@property(nonatomic, readonly) NSData* body;
@property(nonatomic, readonly) uint64_t lastModifiedEpochSeconds;
@property(nonatomic, readonly, nullable) NSString* failureMessage;

- (instancetype)initWithStatusCode:(NSInteger)statusCode
                              body:(NSData*)body
          lastModifiedEpochSeconds:(uint64_t)lastModifiedEpochSeconds
                    failureMessage:(nullable NSString*)failureMessage;

@end

NS_SWIFT_NAME(ValhallaObjcHTTPClient)
@protocol ValhallaMobileHTTPClient <NSObject>

- (ValhallaMobileHTTPResponse*)getURL:(NSString*)url
                              rangeOffset:(uint64_t)rangeOffset
                                rangeSize:(uint64_t)rangeSize;

- (ValhallaMobileHTTPResponse*)headURL:(NSString*)url
                                headerMask:(NSUInteger)headerMask;

@end

@interface ValhallaWrapper : NSObject {
    @private
    void* _actor;
}

- (nullable instancetype)initWithConfigPath:(NSString*)config_path
                                      error:(NSError* _Nullable __autoreleasing* _Nullable)error;

- (nullable instancetype)initWithConfigPath:(NSString*)config_path
                                httpClient:(id<ValhallaMobileHTTPClient>)httpClient
                                     error:(NSError* _Nullable __autoreleasing* _Nullable)error;

- (NSString*)route:(NSString*)request;

- (NSString*)traceAttributes:(NSString*)request;

@end

NS_ASSUME_NONNULL_END

#endif /* ValhallaWrapperHeader_h */
