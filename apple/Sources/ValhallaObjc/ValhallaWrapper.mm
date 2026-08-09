#import "ValhallaWrapper.h"

#import <include/main.h>
#import <Foundation/Foundation.h>

@implementation ValhallaMobileHTTPResponse

- (instancetype)initWithStatusCode:(NSInteger)statusCode
                              body:(NSData*)body
          lastModifiedEpochSeconds:(uint64_t)lastModifiedEpochSeconds
                    failureMessage:(NSString*)failureMessage
{
    self = [super init];
    if (self) {
        _statusCode = statusCode;
        _body = [body copy];
        _lastModifiedEpochSeconds = lastModifiedEpochSeconds;
        _failureMessage = [failureMessage copy];
    }
    return self;
}

@end

class InjectedHTTPClient final : public ValhallaMobileHttpClient {
public:
    explicit InjectedHTTPClient(id<ValhallaMobileHTTPClient> client)
        : client_(client) {}

    valhalla::baldr::tile_getter_t::GET_response_t
    get(
        const std::string& url,
        uint64_t range_offset = 0,
        uint64_t range_size = 0
    ) override {
        valhalla::baldr::tile_getter_t::GET_response_t result;
        @autoreleasepool {
            NSString* value = [NSString stringWithUTF8String:url.c_str()];
            if (value == nil) {
                result.status_ =
                    valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                return result;
            }
            ValhallaMobileHTTPResponse* response = [
                client_ getURL:value
                rangeOffset:range_offset
                rangeSize:range_size
            ];
            if (response == nil) {
                result.status_ =
                    valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                return result;
            }
            result.http_code_ = response.statusCode;
            if (
                response.failureMessage == nil &&
                response.statusCode >= 200 &&
                response.statusCode < 300
            ) {
                if (response.body.length > 0) {
                    const char* bytes =
                        static_cast<const char*>(response.body.bytes);
                    result.bytes_.assign(
                        bytes,
                        bytes + response.body.length
                    );
                }
                result.status_ =
                    valhalla::baldr::tile_getter_t::status_code_t::SUCCESS;
            } else {
                result.status_ =
                    valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
            }
        }
        return result;
    }

    valhalla::baldr::tile_getter_t::HEAD_response_t
    head(
        const std::string& url,
        valhalla::baldr::tile_getter_t::header_mask_t header_mask
    ) override {
        valhalla::baldr::tile_getter_t::HEAD_response_t result;
        @autoreleasepool {
            NSString* value = [NSString stringWithUTF8String:url.c_str()];
            if (value == nil) {
                result.status_ =
                    valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                return result;
            }
            ValhallaMobileHTTPResponse* response = [
                client_ headURL:value
                headerMask:static_cast<NSUInteger>(header_mask)
            ];
            if (response == nil) {
                result.status_ =
                    valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                return result;
            }
            result.http_code_ = response.statusCode;
            result.last_modified_time_ =
                response.lastModifiedEpochSeconds;
            result.status_ =
                response.failureMessage == nil &&
                response.statusCode >= 200 &&
                response.statusCode < 300
                    ? valhalla::baldr::tile_getter_t::status_code_t::SUCCESS
                    : valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
        }
        return result;
    }

private:
    __strong id<ValhallaMobileHTTPClient> client_;
};

/**
 * iOS implementation of ValhallaMobileHttpClient using NSMutableURLRequest
 */
class ValhallaMobileHttpClientImpl : public ValhallaMobileHttpClient {
public:
    valhalla::baldr::tile_getter_t::GET_response_t 
    get(const std::string& url, uint64_t range_offset = 0, uint64_t range_size = 0) override {
        valhalla::baldr::tile_getter_t::GET_response_t response;
        
        @autoreleasepool {
            NSString* urlString = [NSString stringWithUTF8String:url.c_str()];
            NSURL* nsurl = [NSURL URLWithString:urlString];
            
            if (!nsurl) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                response.http_code_ = 0;
                return response;
            }
            
            NSMutableURLRequest* request = [NSMutableURLRequest requestWithURL:nsurl];
            request.HTTPMethod = @"GET";
            request.timeoutInterval = 10;
            
            // Set range header if needed
            if (range_size > 0) {
                NSString* rangeHeader = [NSString stringWithFormat:@"bytes=%llu-%llu", 
                                                  range_offset, range_offset + range_size - 1];
                [request setValue:rangeHeader forHTTPHeaderField:@"Range"];
            }
            
            NSHTTPURLResponse* httpResponse = nil;
            NSError* error = nil;
            
            NSData* data = [NSURLConnection sendSynchronousRequest:request 
                                                  returningResponse:&httpResponse 
                                                              error:&error];
            
            if (error || !httpResponse) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                response.http_code_ = httpResponse ? httpResponse.statusCode : 0;
                return response;
            }
            
            response.http_code_ = httpResponse.statusCode;
            
            if (httpResponse.statusCode >= 200 && httpResponse.statusCode < 300) {
                // Copy data to response bytes
                if (data) {
                    const char* dataBytes = static_cast<const char*>(data.bytes);
                    response.bytes_.assign(dataBytes, dataBytes + data.length);
                }
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::SUCCESS;
            } else {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
            }
        }
        
        return response;
    }
    
    valhalla::baldr::tile_getter_t::HEAD_response_t 
    head(const std::string& url, valhalla::baldr::tile_getter_t::header_mask_t header_mask) override {
        valhalla::baldr::tile_getter_t::HEAD_response_t response;
        
        @autoreleasepool {
            NSString* urlString = [NSString stringWithUTF8String:url.c_str()];
            NSURL* nsurl = [NSURL URLWithString:urlString];
            
            if (!nsurl) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                response.http_code_ = 0;
                return response;
            }
            
            NSMutableURLRequest* request = [NSMutableURLRequest requestWithURL:nsurl];
            request.HTTPMethod = @"HEAD";
            request.timeoutInterval = 10;
            
            NSHTTPURLResponse* httpResponse = nil;
            NSError* error = nil;
            
            [NSURLConnection sendSynchronousRequest:request 
                                  returningResponse:&httpResponse 
                                              error:&error];
            
            if (error || !httpResponse) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                response.http_code_ = httpResponse ? httpResponse.statusCode : 0;
                return response;
            }
            
            response.http_code_ = httpResponse.statusCode;
            
            if (httpResponse.statusCode >= 200 && httpResponse.statusCode < 300) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::SUCCESS;
                
                // Extract Last-Modified header if requested
                if (header_mask & valhalla::baldr::tile_getter_t::kHeaderLastModified) {
                    NSString* lastModified = [httpResponse valueForHTTPHeaderField:@"Last-Modified"];
                    if (lastModified) {
                        NSDateFormatter* formatter = [[NSDateFormatter alloc] init];
                        formatter.dateFormat = @"EEE, dd MMM yyyy HH:mm:ss zzz";
                        formatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"en_US_POSIX"];
                        NSDate* date = [formatter dateFromString:lastModified];
                        response.last_modified_time_ = (uint64_t)[date timeIntervalSince1970];
                    } else {
                        response.last_modified_time_ = 0;
                    }
                }
            } else {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
            }
        }
        
        return response;
    }
};


@implementation ValhallaWrapper

- (instancetype)initWithConfigPath:(NSString*)config_path error:(__autoreleasing NSError **)error
{
    self = [super init];
    std::string path = std::string([config_path UTF8String]);
    try {
        // Create the network interface implementation for iOS
        ValhallaMobileHttpClient* httpClient = new ValhallaMobileHttpClientImpl();
        _actor = create_valhalla_actor(path.c_str(), httpClient);
    } catch (NSException *exception) {
        *error = [[NSError alloc] initWithDomain:exception.name code:0 userInfo:@{
            NSUnderlyingErrorKey: exception,
            NSLocalizedDescriptionKey: exception.reason,
            @"CallStackSymbols": exception.callStackSymbols
        }];
        return nil;
    } catch (const std::exception &err) {
        *error = [[NSError alloc] initWithDomain: [NSString stringWithUTF8String:err.what()] code:-1 userInfo: nil];
        return nil;
    } catch (...) {
        *error = [[NSError alloc] initWithDomain: @"unknown exception" code:-1 userInfo: nil];
        return nil;
    }
    return self;
}

- (instancetype)initWithConfigPath:(NSString*)config_path
                        httpClient:(id<ValhallaMobileHTTPClient>)httpClient
                             error:(__autoreleasing NSError **)error
{
    self = [super init];
    if (!self) {
        return nil;
    }
    std::string path = std::string([config_path UTF8String]);
    try {
        ValhallaMobileHttpClient* bridge = new InjectedHTTPClient(httpClient);
        _actor = create_valhalla_actor(path.c_str(), bridge);
    } catch (NSException *exception) {
        if (error != nullptr) {
            *error = [[NSError alloc] initWithDomain:exception.name code:0 userInfo:@{
                NSUnderlyingErrorKey: exception,
                NSLocalizedDescriptionKey: exception.reason,
                @"CallStackSymbols": exception.callStackSymbols
            }];
        }
        return nil;
    } catch (const std::exception &err) {
        if (error != nullptr) {
            *error = [[NSError alloc]
                initWithDomain:[NSString stringWithUTF8String:err.what()]
                code:-1
                userInfo:nil
            ];
        }
        return nil;
    } catch (...) {
        if (error != nullptr) {
            *error = [[NSError alloc]
                initWithDomain:@"unknown exception"
                code:-1
                userInfo:nil
            ];
        }
        return nil;
    }
    return self;
}

- (NSString*)route:(NSString*)request
{
    @synchronized(self) {
        // Convert the NSString to std::string
        std::string req = std::string([request UTF8String]);
        
        // Generate the valhalla response
        std::string res = route(req.c_str(), _actor);
        
        return [NSString stringWithUTF8String:res.c_str()];
    }
}

- (void) dealloc
{
    delete_valhalla_actor(_actor);
    _actor = nil;
}

@end
