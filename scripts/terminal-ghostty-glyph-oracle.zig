// Exercise the unmodified original parser, glossary and TrueType outline decoder.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, fill: ?usize = null, capture: ?usize = null };
const Case = struct { name: []const u8, events: []const Event };
const Entry = struct { contours: []const u16, points: []const i32, upm: u32, aw: u32, lh: u32, width: u8 };
const Snapshot = struct { replies: []const u8, count: usize, entry: ?Entry };
var replies: std.ArrayList(u8) = .empty;
var reply_allocator: std.mem.Allocator = undefined;
fn writePty(_: *vt.TerminalStream.Handler, data: []const u8) void { replies.appendSlice(reply_allocator,data) catch @panic("OOM"); }
fn hex(alloc: std.mem.Allocator, bytes: []const u8) ![]const u8 {
    const result=try alloc.alloc(u8,bytes.len*2);
    for(bytes,0..) |b,i| {result[i*2]="0123456789abcdef"[b>>4];result[i*2+1]="0123456789abcdef"[b&15];}
    return result;
}
pub fn main(init: std.process.Init) !void {
    var arena=std.heap.ArenaAllocator.init(init.gpa); defer arena.deinit();
    const alloc=arena.allocator(); reply_allocator=alloc;
    const cases=try std.json.parseFromSlice([]const Case,alloc,@embedFile("cases.json"),.{});
    for(cases.value) |case| {
        var t=try vt.Terminal.init(init.io,init.gpa,.{.cols=12,.rows=6}); defer t.deinit(init.gpa);
        var stream=t.vtStream(); defer stream.deinit(); stream.handler.effects.write_pty=writePty;
        var snapshots: std.ArrayList(Snapshot)=.empty;
        for(case.events) |event| {
            replies.clearRetainingCapacity();
            if(event.text) |text| stream.nextSlice(text);
            if(event.capture) |length| {
                stream.nextSlice("\x1b_25a1;s");
                for(1..length) |_| stream.nextSlice(";");
                stream.nextSlice("\x1b\\");
            }
            if(event.fill) |count| {
                for(0..count) |i| stream.nextSlice(try std.fmt.allocPrint(alloc,"\x1b_25a1;r;cp={x};reply=0;AAAAAAAAAAAAAA==\x1b\\",.{0xe000+i}));
            }
            var entry: ?Entry=null;
            if(t.glyph_glossary.entries.get(0xe0a0)) |e| {
                const outline=e.glyph.glyf;
                const points=try alloc.alloc(i32,outline.points.len*3);
                for(outline.points,0..) |p,i| {points[i*3]=p.x;points[i*3+1]=p.y;points[i*3+2]=@intFromBool(p.on_curve);}
                entry=.{.contours=try alloc.dupe(u16,outline.contours),.points=points,.upm=e.design.units_per_em,.aw=e.design.advance_width,.lh=e.design.line_height,.width=@intFromEnum(e.width)};
            }
            try snapshots.append(alloc,.{.replies=try hex(alloc,replies.items),.count=t.glyph_glossary.entries.count(),.entry=entry});
        }
        const result=try std.json.Stringify.valueAlloc(alloc,.{.name=case.name,.snapshots=snapshots.items},.{});
        if(write(1,result.ptr,result.len)!=result.len or write(1,"\n",1)!=1) return error.WriteFailed;
    }
}
