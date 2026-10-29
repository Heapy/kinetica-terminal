// Runs the unmodified Ghostty OSC 72 command/state/response implementation.
const std = @import("std");
const vt = @import("ghostty-vt");
const dnd = vt.kitty.dnd;
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Item = struct { mime: []const u8, hex: []const u8 = "", fill: u8 = 0, count: usize = 0 };
const Step = struct {
    metadata: ?[]const u8 = null, payload: []const u8 = "", fill: u8 = 97, count: usize = 0,
    wire: ?[]const u8 = null, tail: []const u8 = "",
    bel: bool = false, action: ?[]const u8 = null,
    x: u32 = 2, y: u32 = 1, X: i32 = 20, Y: i32 = 18, operations: u2 = 1,
    mimes: []const []const u8 = &.{}, items: []const Item = &.{},
};
const Case = struct { name: []const u8, events: []const Step, wire: bool = false };
const Run = struct { byte: u8, count: usize };
const Meta = struct { kind: ?u8, more: bool, id: u32, op: u32, x: i32, y: i32, X: i32, Y: i32 };
const SavedItem = struct { mime: []const u8, hex: []const u8 };
const State = struct {
    id: u32, chunk: ?Meta, registered: []const Run, accepted_mimes: []const Run,
    hovered: bool, dropped: bool, accepted: ?u8, accepting: bool, client_accepted: ?u8,
    offered: ?[]const []const u8, items: ?[]const SavedItem,
};
const Snapshot = struct { parsed: ?Meta, event: ?[]const u8, replies: []const u8, state: ?State };
var wire_writer: *std.Io.Writer = undefined;
var wire_event: ?dnd.Event = null;
fn writePty(_: *vt.TerminalStream.Handler, bytes: []const u8) void {
    wire_writer.writeAll(bytes) catch @panic("write failed");
}
fn dragAndDrop(_: *vt.TerminalStream.Handler, event: dnd.Event) void {
    // Corpus steps deliberately contain at most one state-changing command.
    if (wire_event != null) @panic("multiple events in one checkpoint");
    wire_event = event;
}
fn meta(value: dnd.Metadata) Meta {
    return .{ .kind = if (value.type) |t| @intFromEnum(t) else null, .more = value.more,
        .id = value.client_id, .op = value.operation, .x = value.cell_x, .y = value.cell_y, .X = value.pixel_x, .Y = value.pixel_y };
}
fn hex(alloc: std.mem.Allocator, bytes: []const u8) ![]const u8 {
    const out = try alloc.alloc(u8, bytes.len * 2);
    const alphabet = "0123456789abcdef";
    for (bytes, 0..) |b, i| { out[i*2] = alphabet[b >> 4]; out[i*2+1] = alphabet[b & 15]; }
    return out;
}
fn data(alloc: std.mem.Allocator, value: []const u8, fill: u8, count: usize) ![]const u8 {
    if (count != 0) { const out = try alloc.alloc(u8, count); @memset(out, fill); return out; }
    const out = try alloc.alloc(u8, value.len / 2);
    for (out, 0..) |*b, i| b.* = try std.fmt.parseInt(u8, value[i*2..][0..2], 16);
    return out;
}
fn runs(alloc: std.mem.Allocator, bytes: []const u8) ![]const Run {
    var out: std.ArrayList(Run) = .empty;
    for (bytes) |b| {
        if (out.items.len > 0 and out.items[out.items.len-1].byte == b) out.items[out.items.len-1].count += 1
        else try out.append(alloc, .{ .byte = b, .count = 1 });
    }
    return out.items;
}
fn snapshot(alloc: std.mem.Allocator, slot: ?*dnd.State) !?State {
    const s = slot orelse return null;
    var offered: ?[]const []const u8 = null;
    if (s.drop.offered) |v| {
        const list = try alloc.alloc([]const u8, v.mimes.len);
        for (list, v.mimes) |*item, mime| item.* = try hex(alloc, mime);
        offered = list;
    }
    var items: ?[]const SavedItem = null;
    if (s.drop.items) |values| {
        const list = try alloc.alloc(SavedItem, values.len);
        for (list, values) |*item, value| item.* = .{ .mime = try hex(alloc, value.mime), .hex = try hex(alloc, value.data) };
        items = list;
    }
    return .{ .id = s.drop.client_id, .chunk = if (s.chunking.active) meta(s.chunking.metadata) else null,
        .registered = try runs(alloc, s.drop.registered_mimes.items), .accepted_mimes = try runs(alloc, s.drop.accepted_mimes.items),
        .hovered = s.drop.hovered, .dropped = s.drop.dropped,
        .accepted = if (s.drop.accepted) |v| @intFromEnum(v) else null, .accepting = s.drop.accept_in_progress,
        .client_accepted = if (s.clientAccepted()) |v| @intFromEnum(v) else null, .offered = offered, .items = items };
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const input = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (input.value) |case| {
        var terminal = try vt.Terminal.init(init.io, init.gpa, .{ .cols = 12, .rows = 6 });
        defer terminal.deinit(init.gpa);
        var stream = terminal.vtStream();
        defer stream.deinit();
        stream.handler.effects.write_pty = writePty;
        stream.handler.effects.drag_and_drop = dragAndDrop;
        const slot = &terminal.kitty_dnd;
        var snapshots: std.ArrayList(Snapshot) = .empty;
        for (case.events) |step| {
            var out: std.Io.Writer.Allocating = .init(alloc);
            var event: ?dnd.Event = null;
            var parsed: ?Meta = null;
            wire_writer = &out.writer; wire_event = null;
            if (step.wire) |bytes| {
                stream.nextSlice(try data(alloc, bytes, 0, 0));
                if (step.count > 0) stream.nextSlice(try data(alloc, "", step.fill, step.count));
                stream.nextSlice(try data(alloc, step.tail, 0, 0));
                event = wire_event;
            } else if (step.metadata) |metadata| {
                if (dnd.Metadata.parse(metadata)) |value| parsed = meta(value);
                event = try dnd.handleCommand(slot, init.gpa, &out.writer, .{
                    .metadata = metadata, .payload = try data(alloc, step.payload, step.fill, step.count),
                    .terminator = if (step.bel) .bel else .st,
                });
            } else if (step.action) |action| {
                if (std.mem.eql(u8, action, "reset")) { if (slot.*) |s| s.chunking = .{}; }
                else if (std.mem.eql(u8, action, "dispose")) { if (slot.*) |s| s.destroy(init.gpa); slot.* = null; }
                else if (slot.*) |s| {
                    const pos: dnd.MoveEvent = .{ .cell_x = step.x, .cell_y = step.y, .pixel_x = step.X, .pixel_y = step.Y,
                        .operations = @bitCast(step.operations) };
                    if (std.mem.eql(u8, action, "leave")) try s.dragLeave(init.gpa, &out.writer)
                    else if (std.mem.eql(u8, action, "move")) try s.dragMove(init.gpa, &out.writer, pos, step.mimes)
                    else if (std.mem.eql(u8, action, "drop")) {
                        const items = try alloc.alloc(dnd.Item, step.items.len);
                        for (items, step.items) |*item, value| item.* = .{ .mime = value.mime, .data = try data(alloc, value.hex, value.fill, value.count) };
                        try s.dragDrop(init.gpa, &out.writer, pos, items);
                    } else return error.BadAction;
                }
            }
            try snapshots.append(alloc, .{ .parsed = parsed, .event = if (event) |ev| @tagName(ev) else null,
                .replies = try hex(alloc, out.written()), .state = try snapshot(alloc, slot.*) });
        }
        const json = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        if (write(1,json.ptr,json.len) != json.len or write(1,"\n",1) != 1) return error.WriteFailed;
    }
}
