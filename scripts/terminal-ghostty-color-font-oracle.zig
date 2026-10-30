// Exercise the unmodified Ghostty font renderer using original Kinetica artwork.
const std = @import("std");
const font = @import("font/main.zig");
extern "c" fn write(c_int, [*]const u8, usize) isize;

fn emit(alloc: std.mem.Allocator, value: anytype) !void {
    const output = try std.json.Stringify.valueAlloc(alloc, value, .{});
    defer alloc.free(output);
    if (write(1, output.ptr, output.len) != output.len or write(1, "\n", 1) != 1) return error.WriteFailed;
}

test "kinetica color font oracle" {
    const alloc = std.testing.allocator;
    var library = try font.Library.init(alloc);
    defer library.deinit();
    const sizes = try std.json.parseFromSlice([]const f32, alloc, @embedFile("kinetica-color-font-sizes.json"), .{});
    defer sizes.deinit();
    for (sizes.value) |points| {
    var face = try font.Face.init(library, @embedFile("kinetica-color-font.ttf"),
        .{ .size = .{ .points = points, .xdpi = 72, .ydpi = 72 } });
    defer face.deinit();
    for ("ABCDEF") |code| {
        const glyph_id = face.glyphIndex(code).?;
        const color = face.isColorGlyph(glyph_id);
        var atlas = try font.Atlas.init(alloc, 128, if (color) .bgra else .grayscale);
        defer atlas.deinit(alloc);
        const glyph = face.renderGlyph(alloc, &atlas, glyph_id,
            .{ .grid_metrics = font.Metrics.calc(face.getMetrics()) }) catch |err| {
            try emit(alloc, .{ .points = points, .codepoint = code, .glyph = glyph_id, .color = color, .err = @errorName(err) });
            continue;
        };
        // u16 forces a JSON number array rather than interpreting RGBA as UTF-8.
        const pixels = try alloc.alloc(u16, glyph.width * glyph.height * 4);
        defer alloc.free(pixels);
        for (0..glyph.height) |y| for (0..glyph.width) |x| {
            const source = ((glyph.atlas_y + y) * atlas.size + glyph.atlas_x + x) * atlas.format.depth();
            const dest = (y * glyph.width + x) * 4;
            if (color) {
                pixels[dest..][0..4].* = .{ atlas.data[source + 2], atlas.data[source + 1], atlas.data[source], atlas.data[source + 3] };
            } else pixels[dest..][0..4].* = .{ atlas.data[source], atlas.data[source], atlas.data[source], atlas.data[source] };
        };
        try emit(alloc, .{ .points = points, .codepoint = code, .glyph = glyph_id,
            .color = color, .width = glyph.width, .height = glyph.height,
            .offset_x = glyph.offset_x, .offset_y = glyph.offset_y, .rgba_premultiplied = pixels });
    }
    }
}
