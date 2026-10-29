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
const DispatchEvent = struct { body: ?[]const u8 = null, terminator: clipboard.Terminator = .st, reset: bool = false };
const Case = struct {
    name: []const u8, metadata: ?[]const u8 = null, limit: usize = clipboard.max_write_size,
    events: []const Event = &.{}, response: ?ResponseInput = null, grants: ?[]const GrantEvent = null,
    dispatch: ?[]const DispatchEvent = null, host: bool = true, allowed: bool = true, remember: bool = false,
    read_status: clipboard.Status = .OK, write_status: clipboard.Status = .DONE, twice: bool = false,
    otp: ?[]const []const u8 = null,
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
const Access = struct { direction: []const u8, location: []const u8, name: []const u8, mimes: []const []const u8, list: bool, granted: bool, can_remember: bool };
var dispatch_alloc: std.mem.Allocator = undefined;
var dispatch_case: Case = undefined;
var replies: std.ArrayList(u8) = .empty;
var accesses: std.ArrayList(Access) = .empty;
var writes: std.ArrayList([]const Content) = .empty;
var stored: []const vt.clipboard.Content = &.{};
var entropy_chunks: []const []const u8 = &.{};
var entropy_calls: usize = 0;
fn oracleEntropy(buffer: []u8) vt.sys.RandomSecureError!void {
    const index = entropy_calls;
    entropy_calls += 1;
    if (index >= entropy_chunks.len) return error.EntropyUnavailable;
    const chunk = entropy_chunks[index];
    if (buffer.len != chunk.len) @panic("unexpected entropy request size");
    @memcpy(buffer, chunk);
}
fn writePty(_: *vt.TerminalStream.Handler, bytes: []const u8) void { replies.appendSlice(dispatch_alloc, bytes) catch @panic("OOM"); }
fn captureAccess(direction: []const u8, location: vt.clipboard.Location, name: []const u8, mimes: []const []const u8, list: bool, granted: bool, can_remember: bool) void {
    const copy = dispatch_alloc.alloc([]const u8, mimes.len) catch @panic("OOM");
    for (mimes, copy) |mime, *dest| dest.* = dispatch_alloc.dupe(u8, mime) catch @panic("OOM");
    accesses.append(dispatch_alloc, .{ .direction = direction, .location = @tagName(location), .name = dispatch_alloc.dupe(u8, name) catch @panic("OOM"), .mimes = copy, .list = list, .granted = granted, .can_remember = can_remember }) catch @panic("OOM");
}
fn clipboardRead(_: *vt.TerminalStream.Handler, request: vt.clipboard.Read) void {
    captureAccess("read", request.location, request.name, request.mimes, request.list, request.granted, request.can_remember);
    const available = dispatch_alloc.alloc([]const u8, stored.len) catch @panic("OOM");
    for (stored, available) |content, *mime| mime.* = content.mime;
    const status = if (dispatch_case.allowed) dispatch_case.read_status else .EPERM;
    request.reply(switch (status) {
        .OK => .{ .success = .{ .contents = stored, .available = available, .remember = dispatch_case.remember } },
        .EPERM => .denied, .ENOSYS => .unsupported, .EBUSY => .busy, .EIO => .io_error,
        else => @panic("invalid test read status"),
    });
    if (dispatch_case.twice) request.reply(.denied);
}
fn clipboardWrite(_: *vt.TerminalStream.Handler, request: vt.clipboard.Write) void {
    const mimes = dispatch_alloc.alloc([]const u8, request.contents.len) catch @panic("OOM");
    for (request.contents, mimes) |content, *mime| mime.* = content.mime;
    captureAccess("write", request.location, request.name, mimes, false, request.granted, request.can_remember);
    const status = if (dispatch_case.allowed) dispatch_case.write_status else .EPERM;
    if (status == .DONE) {
        const copy = dispatch_alloc.alloc(vt.clipboard.Content, request.contents.len) catch @panic("OOM");
        const captured = dispatch_alloc.alloc(Content, request.contents.len) catch @panic("OOM");
        for (request.contents, copy, captured) |content, *dest, *out| {
            dest.* = .{ .mime = dispatch_alloc.dupe(u8, content.mime) catch @panic("OOM"), .data = dispatch_alloc.dupe(u8, content.data) catch @panic("OOM") };
            out.* = .{ .mime = dest.mime, .hex = hex(dispatch_alloc, dest.data) catch @panic("OOM") };
        }
        stored = copy; writes.append(dispatch_alloc, captured) catch @panic("OOM");
    }
    request.reply(switch (status) {
        .DONE => .{ .success = .{ .remember = dispatch_case.remember } },
        .EPERM => .denied, .ENOSYS => .unsupported, .EBUSY => .busy, .EIO => .io_error, .EINVAL => .invalid_data,
        else => @panic("invalid test write status"),
    });
    if (dispatch_case.twice) request.reply(.denied);
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        if (case.otp) |chunks| {
            const decoded = try alloc.alloc([]const u8, chunks.len);
            for (chunks, decoded) |chunk, *dest| dest.* = try unhex(alloc, chunk);
            entropy_chunks = decoded;
            entropy_calls = 0;
            const previous = vt.sys.random_secure;
            vt.sys.random_secure = oracleEntropy;
            defer vt.sys.random_secure = previous;
            const password = clipboard.generateOtp(std.Io.failing) catch {
                try emit(alloc, .{ .name = case.name, .result = @as(?[]const u8, null), .calls = entropy_calls });
                continue;
            };
            try emit(alloc, .{ .name = case.name, .result = password[0..], .calls = entropy_calls });
            continue;
        }
        if (case.dispatch) |events| {
            dispatch_alloc = alloc; dispatch_case = case;
            stored = &.{ .{ .mime = "text/plain", .data = "hello" }, .{ .mime = "text/html", .data = "<b>hello</b>" }, .{ .mime = "image/png", .data = "\x00\xff" } };
            var terminal = try vt.Terminal.init(init.io, init.gpa, .{ .cols = 12, .rows = 6 });
            defer terminal.deinit(init.gpa);
            var stream = terminal.vtStream();
            defer stream.deinit();
            stream.handler.effects.write_pty = writePty;
            stream.handler.kitty_clipboard_write_max_bytes = case.limit;
            if (case.host) { stream.handler.effects.clipboard_read = clipboardRead; stream.handler.effects.clipboard_write = clipboardWrite; }
            const DispatchSnapshot = struct { replies: []const u8, accesses: []const Access, writes: []const []const Content };
            var snapshots: std.ArrayList(DispatchSnapshot) = .empty;
            for (events) |event| {
                replies.clearRetainingCapacity(); accesses.clearRetainingCapacity(); writes.clearRetainingCapacity();
                if (event.reset) stream.nextSlice("\x1bc");
                if (event.body) |body| {
                    stream.nextSlice("\x1b]5522;"); stream.nextSlice(body); stream.nextSlice(event.terminator.string());
                }
                try snapshots.append(alloc, .{ .replies = try alloc.dupe(u8, replies.items), .accesses = try alloc.dupe(Access, accesses.items), .writes = try alloc.dupe([]const Content, writes.items) });
            }
            try emit(alloc, .{ .name = case.name, .snapshots = snapshots.items });
            continue;
        }
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
