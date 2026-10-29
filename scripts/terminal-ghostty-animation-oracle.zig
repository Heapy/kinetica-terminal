// Original pinned libghostty-vt animation execution and deterministic clock oracle.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, tick: ?u64 = null };
const Case = struct { name: []const u8, events: []const Event };
const Snapshot = struct {
    pixels: []const []const u8, gaps: []const u32, format: u32, initialized: bool,
    current: u32, state: []const u8, loops: u32, maxLoops: u32, shownAt: ?u64,
    bytes: usize, contentChanged: bool, delay: ?u64,
};
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        var t = try vt.Terminal.init(init.io, init.gpa, .{ .cols = 10, .rows = 6, .kitty_image_storage_limit = 10000000 });
        defer t.deinit(init.gpa);
        var stream = t.vtStream();
        defer stream.deinit();
        var snapshots: std.ArrayList(Snapshot) = .empty;
        var previous: u64 = 0;
        for (case.events) |event| {
            if (event.text) |text| stream.nextSlice(text);
            var delay: ?u64 = null;
            if (event.tick) |now| delay = t.screens.active.kitty_images.animationTick(init.io, now);
            const image = t.screens.active.kitty_images.imageById(1) orelse return error.ImageMissing;
            const animation = image.animation;
            const count = if (animation) |a| a.frameCount() else 1;
            var pixels: std.ArrayList([]const u8) = .empty;
            var gaps: std.ArrayList(u32) = .empty;
            for (0..count) |i| {
                const data = image.frameData(@intCast(i+1)).?;
                const hex = try alloc.alloc(u8, data.len*2);
                const digits = "0123456789abcdef";
                for (data,0..) |byte,index| { hex[index*2] = digits[byte>>4]; hex[index*2+1] = digits[byte&15]; }
                try pixels.append(alloc,hex);
                try gaps.append(alloc,if (animation) |a| a.gapAt(@intCast(i)) else 0);
            }
            try snapshots.append(alloc,.{
                .pixels = pixels.items, .gaps = gaps.items, .format = @backingInt(image.format), .initialized = animation != null,
                .current = if (animation) |a| a.current_index else 0, .state = if (animation) |a| @tagName(a.state) else "stopped",
                .loops = if (animation) |a| a.current_loop else 0, .maxLoops = if (animation) |a| a.max_loops else 0,
                .shownAt = if (animation) |a| a.frame_shown_at_ms else null, .bytes = image.storageSize(),
                .contentChanged = image.generation != previous, .delay = delay,
            });
            previous = image.generation;
        }
        const result = try std.json.Stringify.valueAlloc(alloc,.{ .name = case.name, .snapshots = snapshots.items },.{});
        if (write(1,result.ptr,result.len) != result.len or write(1,"\n",1) != 1) return error.WriteFailed;
    }
}
