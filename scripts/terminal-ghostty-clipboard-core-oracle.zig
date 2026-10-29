// Calls original Ghostty metadata decoder and WriteState; no copied protocol implementation.
const std = @import("std");
const vt = @import("ghostty-vt");
const clipboard = vt.kitty.clipboard;
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { op: []const u8, mime: []const u8 = "", payload: []const u8 = "" };
const Case = struct { name: []const u8, metadata: ?[]const u8 = null, limit: usize = clipboard.max_write_size, events: []const Event = &.{} };
const Metadata = struct { operation: []const u8, location: []const u8, id: []const u8, mime: []const u8, password: []const u8, name: []const u8 };
const Content = struct { mime: []const u8, hex: []const u8 };
const Snapshot = struct { status: []const u8, bytes: ?usize, contents: ?[]const Content = null };

fn emit(alloc: std.mem.Allocator, value: anytype) !void {
    const json = try std.json.Stringify.valueAlloc(alloc, value, .{});
    if (write(1, json.ptr, json.len) != json.len or write(1, "\n", 1) != 1) return error.WriteFailed;
}
fn hex(alloc: std.mem.Allocator, bytes: []const u8) ![]const u8 {
    const result = try alloc.alloc(u8, bytes.len * 2);
    const alphabet = "0123456789abcdef";
    for (bytes, 0..) |byte, i| { result[i * 2] = alphabet[byte >> 4]; result[i * 2 + 1] = alphabet[byte & 15]; }
    return result;
}
fn failure(err: anyerror) []const u8 {
    return switch (err) { error.Invalid => "EINVAL", error.TooLarge => "EFBIG", else => @panic("unexpected original error") };
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        if (case.metadata) |raw| {
            const parsed = clipboard.Metadata.parse(alloc, raw) catch |err| switch (err) {
                error.OutOfMemory => return err,
                error.InvalidValue => {
                    try emit(alloc, .{ .name = case.name, .result = .{ .status = "invalid", .operation = @tagName(clipboard.Metadata.operation(raw).?) } });
                    continue;
                },
            };
            if (parsed) |meta| {
                try emit(alloc, .{ .name = case.name, .result = .{ .status = "valid", .metadata = Metadata{
                    .operation = @tagName(meta.op), .location = @tagName(meta.loc), .id = meta.id,
                    .mime = meta.mime, .password = meta.pw, .name = meta.name,
                } } });
            } else try emit(alloc, .{ .name = case.name, .result = .{ .status = "dropped" } });
            continue;
        }
        var state: ?clipboard.WriteState = try .init(init.gpa, &.{ .op = .write }, .{ .max_size = case.limit });
        defer if (state) |*s| s.deinit(init.gpa);
        var snapshots: std.ArrayList(Snapshot) = .empty;
        for (case.events) |event| {
            const s = if (state) |*s| s else {
                try snapshots.append(alloc, .{ .status = "inactive", .bytes = null });
                continue;
            };
            var status: []const u8 = "ok";
            var contents: ?[]const Content = null;
            if (std.mem.eql(u8, event.op, "data")) {
                s.data(init.gpa, &.{ .op = .wdata, .mime = event.mime }, event.payload) catch |err| { status = failure(err); };
            } else if (std.mem.eql(u8, event.op, "alias")) {
                s.alias(init.gpa, &.{ .op = .walias, .mime = event.mime }, event.payload) catch |err| { status = failure(err); };
            } else if (std.mem.eql(u8, event.op, "commit")) commit: {
                const result = s.commit(init.gpa) catch |err| { status = failure(err); break :commit; };
                defer result.deinit(init.gpa);
                const copy = try alloc.alloc(Content, result.contents.len);
                for (result.contents, copy) |source, *dest| dest.* = .{ .mime = try alloc.dupe(u8, source.mime), .hex = try hex(alloc, source.data) };
                contents = copy;
                status = "committed";
            } else return error.UnknownOperation;
            const pending: ?usize = if (std.mem.eql(u8, status, "ok")) s.spool.items.len else null;
            if (pending == null) { s.deinit(init.gpa); state = null; }
            try snapshots.append(alloc, .{ .status = status, .bytes = pending, .contents = contents });
        }
        try emit(alloc, .{ .name = case.name, .snapshots = snapshots.items });
    }
}
