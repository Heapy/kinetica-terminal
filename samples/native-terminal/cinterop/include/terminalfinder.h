#import <AppKit/AppKit.h>

// Services require a three-argument Objective-C selector, including an NSString **
// error parameter. A protocol preserves that ABI when implemented in Kotlin.
@protocol KineticaFinderService <NSObject>
- (void)openKineticaHere:(NSPasteboard * _Nonnull)pasteboard
              userData:(NSString * _Nullable)userData
                 error:(NSString * _Nullable * _Nullable)error;
@end
