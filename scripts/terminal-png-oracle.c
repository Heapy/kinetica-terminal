/* Test-only libpng pixel oracle. No Kinetica code or OS image/color transformations. */
#include <png.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts(png_get_libpng_ver(NULL));
        return 0;
    }
    if (argc != 2) return 1;
    FILE *file = fopen(argv[1], "rb");
    if (!file) return 1;
    png_structp png = png_create_read_struct(PNG_LIBPNG_VER_STRING, NULL, NULL, NULL);
    png_infop info = png ? png_create_info_struct(png) : NULL;
    if (!info) return 1;
    if (setjmp(png_jmpbuf(png))) {
        png_destroy_read_struct(&png, &info, NULL);
        fclose(file);
        return 2;
    }
    png_init_io(png, file);
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
    /* Fixed fixture-size arrays also avoid allocations leaked on libpng longjmp errors. */
    static unsigned char pixels[1024 * 1024 * 4];
    static png_bytep rows[1024];
    for (png_uint_32 y = 0; y < height; y++) rows[y] = pixels + y * width * 4;
    png_read_image(png, rows);
    png_read_end(png, info);
    png_destroy_read_struct(&png, &info, NULL);
    fclose(file);
    unsigned char header[8] = {width >> 24, width >> 16, width >> 8, width,
                              height >> 24, height >> 16, height >> 8, height};
    if (fwrite(header, 1, 8, stdout) != 8 || fwrite(pixels, 4, width * height, stdout) != width * height) return 1;
    return 0;
}
