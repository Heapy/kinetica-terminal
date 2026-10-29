/* Full graphics oracle: original libghostty with an independent libpng host decoder.
 * The base oracle intentionally has no PNG host callback. Keep that configuration intact. */
#define main terminal_oracle_main
#include "terminal-ghostty-oracle.c"
#undef main
#include <png.h>

typedef struct { const uint8_t *data; size_t size, offset; } PngInput;
static void read_png(png_structp png, png_bytep output, png_size_t size) {
    PngInput *input = png_get_io_ptr(png);
    if (size > input->size - input->offset) png_error(png, "truncated PNG");
    memcpy(output, input->data + input->offset, size);
    input->offset += size;
}

static bool decode_png(void *userdata, const GhosttyAllocator *allocator,
                       const uint8_t *data, size_t size, GhosttySysImage *out) {
    (void)userdata;
    png_structp png = png_create_read_struct(PNG_LIBPNG_VER_STRING, NULL, NULL, NULL);
    png_infop info = png ? png_create_info_struct(png) : NULL;
    if (!info) { if (png) png_destroy_read_struct(&png, NULL, NULL); return false; }
    if (setjmp(png_jmpbuf(png))) { png_destroy_read_struct(&png, &info, NULL); return false; }
    PngInput input = {data, size, 0};
    png_set_read_fn(png, &input, read_png);
    png_read_info(png, info);
    png_uint_32 width = png_get_image_width(png, info), height = png_get_image_height(png, info);
    if (!width || !height || width > 1024 || height > 1024) png_error(png, "fixture dimensions");
    int color = png_get_color_type(png, info), depth = png_get_bit_depth(png, info);
    int alpha = png_get_valid(png, info, PNG_INFO_tRNS);
    if (depth == 16) png_set_strip_16(png);
    if (color == PNG_COLOR_TYPE_PALETTE) png_set_palette_to_rgb(png);
    if (color == PNG_COLOR_TYPE_GRAY && depth < 8) png_set_expand_gray_1_2_4_to_8(png);
    if (alpha) png_set_tRNS_to_alpha(png);
    if (color == PNG_COLOR_TYPE_GRAY || color == PNG_COLOR_TYPE_GRAY_ALPHA) png_set_gray_to_rgb(png);
    if (!(color & PNG_COLOR_MASK_ALPHA) && !alpha) png_set_add_alpha(png, 255, PNG_FILLER_AFTER);
    (void)png_set_interlace_handling(png);
    png_read_update_info(png, info);
    if (png_get_rowbytes(png, info) != width * 4) png_error(png, "unexpected stride");
    /* Bounded fixture scratch, including all libpng longjmp failure paths. No color conversion. */
    static uint8_t pixels[1024 * 1024 * 4];
    static png_bytep rows[1024];
    for (png_uint_32 y = 0; y < height; y++) rows[y] = pixels + y * width * 4;
    png_read_image(png, rows);
    png_read_end(png, info);
    png_destroy_read_struct(&png, &info, NULL);
    const size_t length = (size_t)width * height * 4;
    uint8_t *owned = ghostty_alloc(allocator, length);
    if (!owned) return false;
    memcpy(owned, pixels, length);
    *out = (GhosttySysImage){width, height, owned, length};
    return true;
}

int main(void) {
    if (strcmp(png_get_libpng_ver(NULL), "1.6.59") != 0) return 2;
    ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, (const void *)decode_png);
    return terminal_oracle_main();
}
