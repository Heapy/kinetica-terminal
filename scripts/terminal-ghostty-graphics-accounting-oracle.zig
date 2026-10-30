// Original Ghostty stream, image/frame admission and resident storage accounting.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, limit: ?usize = null };
const Case = struct { name: []const u8, limit: usize, events: []const Event };
const Image = struct { id: u32, format: u32, bytes: usize, frames: []const []const u8 };
const Snapshot = struct {
    replies: []const u8, limit: usize, bytes: usize, pending: bool,
    imageCount: usize, placementCount: usize, images: []const Image,
};
var replies: std.ArrayList(u8) = .empty;
var reply_allocator: std.mem.Allocator = undefined;
fn writePty(_: *vt.TerminalStream.Handler, data: []const u8) void {
    replies.appendSlice(reply_allocator, data) catch @panic("OOM");
}
fn hex(alloc: std.mem.Allocator, data: []const u8) ![]const u8 {
    const result = try alloc.alloc(u8, data.len * 2);
    for (data, 0..) |byte, i| {
        result[i*2] = "0123456789abcdef"[byte >> 4];
        result[i*2+1] = "0123456789abcdef"[byte & 15];
    }
    return result;
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    reply_allocator = alloc;
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        replies.clearRetainingCapacity();
        var terminal = try vt.Terminal.init(init.io, init.gpa, .{
            .cols = 12, .rows = 6, .kitty_image_storage_limit = case.limit,
        });
        defer terminal.deinit(init.gpa);
        var stream = terminal.vtStream();
        defer stream.deinit();
        stream.handler.effects.write_pty = writePty;
        var snapshots: std.ArrayList(Snapshot) = .empty;
        for (case.events) |event| {
            if (event.limit) |limit| {
                // Same operation as libghostty's KITTY_IMAGE_STORAGE_LIMIT host option.
                var it = terminal.screens.all.iterator();
                while (it.next()) |entry| {
                    const screen = entry.value.*;
                    screen.kitty_images.setLimit(screen.io, screen.alloc, screen, limit);
                }
            }
            if (event.text) |text| stream.nextSlice(text);
            const storage = &terminal.screens.active.kitty_images;
            var images: std.ArrayList(Image) = .empty;
            for (1..5) |id| if (storage.imageById(@intCast(id))) |image| {
                var frames: std.ArrayList([]const u8) = .empty;
                const count = if (image.animation) |animation| animation.frameCount() else 1;
                for (0..count) |index| try frames.append(alloc, try hex(alloc, image.frameData(@intCast(index+1)).?));
                try images.append(alloc, .{ .id = @intCast(id), .format = @backingInt(image.format),
                    .bytes = image.storageSize(), .frames = frames.items });
            };
            if (images.items.len != storage.images.count()) return error.UnprobedImage;
            try snapshots.append(alloc, .{
                .replies = try hex(alloc, replies.items), .limit = storage.total_limit,
                .bytes = storage.total_bytes, .pending = storage.loading != null,
                .imageCount = storage.images.count(), .placementCount = storage.placements.count(),
                .images = images.items,
            });
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
