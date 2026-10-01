#import <Foundation/Foundation.h>
#import <Vision/Vision.h>
#import <AppKit/AppKit.h>
#include <stdio.h>

// Test-only, offline OCR of a captured diagnostic window, never Accessibility text.
int main(int argc, char **argv) {
    @autoreleasepool {
        if (argc != 2 && argc != 3) return 2;
        NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:argv[1]]];
        VNRecognizeTextRequest *request = [VNRecognizeTextRequest new];
        request.recognitionLevel = VNRequestTextRecognitionLevelAccurate;
        request.usesLanguageCorrection = NO;
        request.recognitionLanguages = @[@"en-US"];
        VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithURL:url options:@{}];
        NSError *error = nil;
        if (![handler performRequests:@[request] error:&error]) {
            fprintf(stderr, "%s\n", error.localizedDescription.UTF8String);
            return 1;
        }
        NSMutableArray *results = [NSMutableArray array];
        NSBitmapImageRep *bitmap = [NSBitmapImageRep imageRepWithData:[NSData dataWithContentsOfURL:url]];
        // The resize fixture uses green text. Measure its actual ink pixels independently
        // of Vision's approximate text box (which can shift with surrounding whitespace).
        NSInteger minX = bitmap.pixelsWide, minY = bitmap.pixelsHigh, maxX = -1, maxY = -1;
        if (bitmap.bitsPerSample != 8 || bitmap.isPlanar || bitmap.samplesPerPixel < 3 ||
            (bitmap.bitmapFormat & NSBitmapFormatAlphaFirst)) return 2;
        for (NSInteger y = 0; y < bitmap.pixelsHigh; y++) {
            unsigned char *row = bitmap.bitmapData + y * bitmap.bytesPerRow;
            for (NSInteger x = 0; x < bitmap.pixelsWide; x++) {
                unsigned char *pixel = row + x * bitmap.samplesPerPixel;
                if (pixel[1] > 120 && pixel[1] > pixel[0] * 2 && pixel[1] > pixel[2] * 2) {
                    minX = MIN(minX, x); minY = MIN(minY, y); maxX = MAX(maxX, x); maxY = MAX(maxY, y);
                }
            }
        }
        id ink = maxX < 0 ? (id)[NSNull null] : @{@"x": @(minX), @"y": @(minY),
            @"width": @(maxX - minX + 1), @"height": @(maxY - minY + 1)};
        NSMutableArray *background = [NSMutableArray array];
        if (argc == 3) {
            // Shadow-free capture: the content extends to the image's bottom edge.
            NSInteger contentHeight = (NSInteger)strtol(argv[2], NULL, 10);
            if (contentHeight <= 0 || contentHeight > bitmap.pixelsHigh) return 2;
            for (double fy = 0.2; fy < 0.9; fy += 0.3) {
                for (double fx = 0.2; fx < 0.9; fx += 0.3) {
                    NSInteger x = (NSInteger)(bitmap.pixelsWide * fx);
                    NSInteger y = bitmap.pixelsHigh - contentHeight + (NSInteger)(contentHeight * fy);
                    unsigned char *pixel = bitmap.bitmapData + y * bitmap.bytesPerRow + x * bitmap.samplesPerPixel;
                    [background addObject:@{@"x": @(x), @"y": @(y), @"rgb": @[@(pixel[0]), @(pixel[1]), @(pixel[2])]}];
                }
            }
        }
        for (VNRecognizedTextObservation *observation in request.results) {
            VNRecognizedText *candidate = [observation topCandidates:1].firstObject;
            CGRect bounds = observation.boundingBox;
            if (candidate) [results addObject:@{@"text": candidate.string, @"confidence": @(candidate.confidence), @"inkPixels": ink, @"background": background,
                @"pixels": @{@"x": @(bounds.origin.x * bitmap.pixelsWide),
                    @"y": @((1 - CGRectGetMaxY(bounds)) * bitmap.pixelsHigh),
                    @"width": @(bounds.size.width * bitmap.pixelsWide),
                    @"height": @(bounds.size.height * bitmap.pixelsHigh)}}];
        }
        NSData *json = [NSJSONSerialization dataWithJSONObject:results options:0 error:&error];
        if (!json) return 1;
        fwrite(json.bytes, 1, json.length, stdout);
        fputc('\n', stdout);
        return 0;
    }
}
