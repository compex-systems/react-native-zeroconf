//
//  RNZeroconf.m
//  RNZeroconf
//
//  Created by Balthazar Gronon on 25/10/2015.
//  Copyright © 2016 Balthazar Gronon MIT
//

#import "RNZeroconf.h"
#import "RNNetServiceSerializer.h"

@interface RNZeroconf ()

@property (nonatomic, strong, readonly) NSMutableDictionary *resolvingServices;
@property (nonatomic, strong, readonly) NSMutableDictionary *resolvingTimers;

@end

@implementation RNZeroconf

@synthesize bridge = _bridge;

RCT_EXPORT_MODULE()

RCT_EXPORT_METHOD(scan:(NSString *)type protocol:(NSString *)protocol domain:(NSString *)domain)
{
    [self stop];
    [self.browser searchForServicesOfType:[NSString stringWithFormat:@"_%@._%@.", type, protocol] inDomain:domain];
}

RCT_EXPORT_METHOD(stop)
{
    [self.browser stop];
    [self.resolvingServices removeAllObjects];
    [self.resolvingTimers removeAllObjects];
}

+ (BOOL)requiresMainQueueSetup
{
    return YES;
}

#pragma mark - NSNetServiceBrowserDelegate

// When a service is discovered.
- (void) netServiceBrowser:(NSNetServiceBrowser *)browser
            didFindService:(NSNetService *)service
                moreComing:(BOOL)moreComing
{
    if (service == nil) {
      return;
    }

    NSDictionary *serviceInfo = [RNNetServiceSerializer serializeServiceToDictionary:service resolved:NO];
    [self.bridge.eventDispatcher sendDeviceEventWithName:@"RNZeroconfFound" body:serviceInfo];

    // resolving services must be strongly referenced or they will be garbage collected
    // and will never resolve or timeout.
    // source: http://stackoverflow.com/a/16130535/2715
    self.resolvingServices[service.name] = service;

    [self resolveService:service withDebounce:0.0];

    [service startMonitoring];
}

// When a service is removed.
- (void) netServiceBrowser:(NSNetServiceBrowser*)netServiceBrowser
          didRemoveService:(NSNetService*)service
                moreComing:(BOOL)moreComing
{
    if (service == nil) {
      return;
    }

    [service stopMonitoring];

    NSDictionary *serviceInfo = [RNNetServiceSerializer serializeServiceToDictionary:service resolved:NO];
    [self.bridge.eventDispatcher sendDeviceEventWithName:@"RNZeroconfRemove" body:serviceInfo];
}

// When a service is updated.
- (void)netService:(NSNetService *)sender didUpdateTXTRecordData:(NSData *)data
{
    NSMutableDictionary *serviceInfo = [[RNNetServiceSerializer serializeServiceToDictionary:sender resolved:YES] mutableCopy];

    NSDictionary<NSString *, NSData *> *txtRecordDict = [NSNetService dictionaryFromTXTRecordData:data];

    NSMutableDictionary *dict = [[NSMutableDictionary alloc] init];
    for (NSString *key in txtRecordDict) {
        dict[key] = [[NSString alloc]
                     initWithData:txtRecordDict[key]
                     encoding:NSASCIIStringEncoding];
    }

    serviceInfo[@"txt"] = dict;

    [self.bridge.eventDispatcher sendDeviceEventWithName:@"RNZeroconfUpdate" body:serviceInfo];

    if (sender.addresses.count > 0) {
        // resolve service in case the IP address changed
        [self resolveService:sender withDebounce:3.0];
    }
}

- (void)resolveService:(NSNetService *)service withDebounce:(NSTimeInterval)timeout
{
    if (self.resolvingTimers[service.name]) {
        [self.resolvingTimers[service.name] invalidate];
    }

    self.resolvingTimers[service.name] = [NSTimer scheduledTimerWithTimeInterval:timeout
                                                                          target:self
                                                                        selector:@selector(resolveServiceWithTimer:)
                                                                        userInfo:service
                                                                         repeats:NO];
}

- (void)resolveServiceWithTimer:(NSTimer*)timer
{
    NSNetService *service = (NSNetService *)[timer userInfo];
    service.delegate = self;
    [service resolveWithTimeout:5.0];
}

// When the search fails.
- (void) netServiceBrowser:(NSNetServiceBrowser *)browser
              didNotSearch:(NSDictionary *)errorDict
{
    [self reportError:errorDict withMessage:@"didNotSearch"];
}

// When the search stops.
- (void) netServiceBrowserDidStopSearch:(NSNetServiceBrowser *)browser
{
    [self.bridge.eventDispatcher sendDeviceEventWithName:@"RNZeroconfStop" body:nil];
}

// When the search starts.
- (void) netServiceBrowserWillSearch:(NSNetServiceBrowser *)browser
{
    [self.bridge.eventDispatcher sendDeviceEventWithName:@"RNZeroconfStart" body:nil];
}

#pragma mark - NSNetServiceDelegate

// When the service has resolved it's network data (IP addresses, etc)
- (void) netServiceDidResolveAddress:(NSNetService *)sender
{
    NSDictionary *serviceInfo = [RNNetServiceSerializer serializeServiceToDictionary:sender resolved:YES];
    [self.bridge.eventDispatcher sendDeviceEventWithName:@"RNZeroconfResolved" body:serviceInfo];
}

// When the service has failed to resolve it's network data (IP addresses, etc)
- (void) netService:(NSNetService *)sender
      didNotResolve:(NSDictionary *)errorDict
{
    [self reportError:errorDict withMessage:@"didNotResolve"];

    // Remove service if it has not resolved before
    if ([[self.resolvingServices[sender.name] addresses] count] == 0) {
        sender.delegate = nil;
        [self.resolvingServices removeObjectForKey:sender.name];
        [self.resolvingTimers removeObjectForKey:sender.name];
    }
}

#pragma mark - Class methods

- (instancetype) init
{
    self = [super init];

    if (self) {
        _resolvingServices = [[NSMutableDictionary alloc] init];
        _resolvingTimers = [[NSMutableDictionary alloc] init];
        _browser = [[NSNetServiceBrowser alloc] init];
        [_browser setDelegate:self];
    }

    return self;
}

- (void) reportError:(NSDictionary *)errorDict withMessage:(NSString *)message
{
    [self.bridge.eventDispatcher sendDeviceEventWithName:@"RNZeroconfError" body:message];
}

@end
