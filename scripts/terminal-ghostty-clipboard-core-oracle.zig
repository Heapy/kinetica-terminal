// Calls original Ghostty clipboard modules; no copied protocol implementation.
const std = @import("std");
const vt = @import("ghostty-vt");
const clipboard = vt.kitty.clipboard;
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { op: []const u8, mime: []const u8 = "", payload: []const u8 = "" };
const ContentInput = struct { mime: []const u8, hex: []const u8 };
const ResponseInput = struct {
    kind: []const u8, operation: clipboard.Operation = .write, status: clipboard.Status = .DONE,
    primary: bool = false, id: []const u8 = "", mime: ?[]const u8 = null, password: ?[]const u8 = null,
    payload: []const u8 = "", terminator: clipboard.Terminator = .st, list: bool = false,
    available: []const []const u8 = &.{}, contents: []const ContentInput = &.{},
};
const GrantEvent = struct { action: []const u8, password: []const u8 = "", direction: clipboard.Grants.Direction = .read, one_time: bool = false };
const Case = struct {
    name: []const u8, metadata: ?[]const u8 = null, limit: usize = clipboard.max_write_size,
    events: []const Event = &.{}, response: ?ResponseInput = null, grants: ?[]const GrantEvent = null,
};
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
fn unhex(alloc: std.mem.Allocator, value: []const u8) ![]const u8 {
    if (value.len % 2 != 0) return error.InvalidHex;
    const bytes = try alloc.alloc(u8, value.len / 2);
    for (bytes, 0..) |*byte, i| byte.* = try std.fmt.parseInt(u8, value[i * 2 ..][0..2], 16);
    return bytes;
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        if (case.response) |input| {
            var output: std.Io.Writer.Allocating = .init(alloc);
            defer output.deinit();
            const contents = try alloc.alloc(clipboard.Content, input.contents.len);
            for (input.contents, contents) |source, *dest| dest.* = .{ .mime = source.mime, .data = try unhex(alloc, source.hex) };
            if (std.mem.eql(u8, input.kind, "packet")) {
                try (clipboard.Response{
                    .op = input.operation, .status = input.status, .primary = input.primary, .id = input.id,
                    .mime = input.mime, .pw = input.password, .payload = try unhex(alloc, input.payload), .terminator = input.terminator,
                }).encode(&output.writer);
            } else if (std.mem.eql(u8, input.kind, "read")) {
                try (clipboard.ReadSuccess{
                    .primary = input.primary, .id = input.id, .pw = input.password, .list = input.list,
                    .available = input.available, .contents = contents, .terminator = input.terminator,
                }).encode(&output.writer);
            } else if (std.mem.eql(u8, input.kind, "paste")) {
                try (clipboard.PasteEvent{ .primary = input.primary, .pw = input.password.?, .available = input.available, .terminator = input.terminator }).encode(&output.writer);
            } else return error.UnknownResponse;
            try emit(alloc, .{ .name = case.name, .result = output.written() });
            continue;
        }
        if (case.grants) |events| {
            var grants: clipboard.Grants = .{};
            defer grants.deinit(init.gpa);
            const GrantSnapshot = struct { allowed: ?bool, count: usize };
            var snapshots: std.ArrayList(GrantSnapshot) = .empty;
            for (events) |event| {
                var allowed: ?bool = null;
                if (std.mem.eql(u8, event.action, "grant")) {
                    try grants.grant(init.gpa, event.password, event.direction, event.one_time);
                } else if (std.mem.eql(u8, event.action, "use")) {
                    allowed = grants.use(init.gpa, event.password, event.direction);
                } else if (std.mem.eql(u8, event.action, "clear")) {
                    grants.deinit(init.gpa); grants = .{};
                } else return error.UnknownGrantAction;
                try snapshots.append(alloc, .{ .allowed = allowed, .count = grants.entries.items.len });
            }
            try emit(alloc, .{ .name = case.name, .snapshots = snapshots.items });
            continue;
        }
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
