// Exercise the pinned original LoadingImage readers against real native resources.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
extern "c" fn getenv([*:0]const u8) ?[*:0]const u8;
const Case = struct {
    name: []const u8, path_hex: []const u8, medium: []const u8, temporary_directory: []const u8,
    allowed: bool, offset: u32, size: u32, format: u32, width: u32, height: u32, compressed: bool,
};
// This oracle stops at LoadingImage.init, before decoding. Enable the PNG capability
// gate to exercise its native reader, and fail loudly if this scope ever changes.
fn unexpectedDecode(_: std.mem.Allocator, _: []const u8) vt.sys.DecodeError!vt.sys.Image {
    @panic("native media oracle must not decode images");
}
pub fn main(init: std.process.Init) !void {
    vt.sys.decode_png = &unexpectedDecode;
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case,alloc,@embedFile("cases.json"),.{});
    for (cases.value) |case| {
        if (getenv("KINETICA_MEDIA_CASE")) |selected| {
            if (!std.mem.eql(u8,case.name,std.mem.span(selected))) continue;
        }
        const path = try alloc.alloc(u8,case.path_hex.len / 2);
        _ = try std.fmt.hexToBytes(path,case.path_hex);
        const command: vt.kitty.graphics.Command = .{
            .control = .{ .transmit = .{
                .medium = switch (case.medium[0]) { 'f' => .file, 't' => .temporary_file, 's' => .shared_memory, else => unreachable },
                .offset = case.offset, .size = case.size,
                .format = switch (case.format) { 24 => .rgb, 32 => .rgba, 100 => .png, else => unreachable },
                .width = case.width, .height = case.height,
                .compression = if (case.compressed) .zlib_deflate else .none,
            } },
            .data = path,
        };
        var error_name: ?[]const u8 = null;
        var bytes: ?[]const u8 = null;
        if (vt.kitty.graphics.LoadingImage.init(init.io,init.gpa,&command,.{
            .file = case.allowed, .shared_memory = case.allowed,
            .temporary_file = if (case.allowed) .{ .enabled = .{ .directory = case.temporary_directory } } else .disabled,
        })) |value| {
            var loading = value;
            defer loading.deinit(init.gpa);
            const hex = try alloc.alloc(u8,loading.data.items.len * 2);
            for (loading.data.items,0..) |byte,i| { hex[i*2] = "0123456789abcdef"[byte >> 4]; hex[i*2+1] = "0123456789abcdef"[byte & 15]; }
            bytes = hex;
        } else |err| error_name = @errorName(err);
        const result = try std.json.Stringify.valueAlloc(alloc,.{ .name = case.name, .error_name = error_name, .bytes = bytes },.{});
        if (write(1,result.ptr,result.len) != result.len or write(1,"\n",1) != 1) return error.WriteFailed;
    }
}
