// Small C ABI for copying glyph outlines out of the isolated browser WASM module.
// HarfBuzz owns shaping; no JavaScript callbacks or retained JS objects enter WASM.
#include "hb.h"
#include <cstdlib>
#include <cstring>

struct Outline {
  float *values;
  unsigned count;
  unsigned capacity;
  bool failed;
};

static void append(void *data, const float *values, unsigned count) {
  auto *path = static_cast<Outline *>(data);
  constexpr unsigned limit = 1 << 20;
  if (path->failed) return;
  if (count > limit - path->count) { path->failed = true; return; }
  if (path->count + count > path->capacity) {
    unsigned capacity = path->capacity ? path->capacity * 2 : 128;
    if (capacity < path->count + count) capacity = path->count + count;
    if (capacity > limit) capacity = limit;
    auto *next = static_cast<float *>(std::realloc(path->values, capacity * sizeof(float)));
    if (!next) { path->failed = true; return; }
    path->values = next;
    path->capacity = capacity;
  }
  std::memcpy(path->values + path->count, values, count * sizeof(float));
  path->count += count;
}

static void move(hb_draw_funcs_t *, void *data, hb_draw_state_t *, float x, float y, void *) {
  const float values[] = {0, x, y}; append(data, values, 3);
}
static void line(hb_draw_funcs_t *, void *data, hb_draw_state_t *, float x, float y, void *) {
  const float values[] = {1, x, y}; append(data, values, 3);
}
static void quadratic(hb_draw_funcs_t *, void *data, hb_draw_state_t *, float x1, float y1, float x2, float y2, void *) {
  const float values[] = {2, x1, y1, x2, y2}; append(data, values, 5);
}
static void cubic(hb_draw_funcs_t *, void *data, hb_draw_state_t *, float x1, float y1, float x2, float y2, float x3, float y3, void *) {
  const float values[] = {3, x1, y1, x2, y2, x3, y3}; append(data, values, 7);
}
static void close(hb_draw_funcs_t *, void *data, hb_draw_state_t *, void *) {
  const float values[] = {4}; append(data, values, 1);
}

extern "C" void kt_hb_outline_destroy(Outline *path) {
  if (path) { std::free(path->values); std::free(path); }
}

extern "C" Outline *kt_hb_outline(hb_font_t *font, unsigned glyph) {
  auto *path = static_cast<Outline *>(std::calloc(1, sizeof(Outline)));
  if (!path) return nullptr;
  auto *functions = hb_draw_funcs_create();
  if (hb_draw_funcs_is_immutable(functions)) {
    hb_draw_funcs_destroy(functions); kt_hb_outline_destroy(path); return nullptr;
  }
  hb_draw_funcs_set_move_to_func(functions, move, nullptr, nullptr);
  hb_draw_funcs_set_line_to_func(functions, line, nullptr, nullptr);
  hb_draw_funcs_set_quadratic_to_func(functions, quadratic, nullptr, nullptr);
  hb_draw_funcs_set_cubic_to_func(functions, cubic, nullptr, nullptr);
  hb_draw_funcs_set_close_path_func(functions, close, nullptr, nullptr);
  hb_font_draw_glyph(font, glyph, functions, path);
  hb_draw_funcs_destroy(functions);
  if (path->failed) { kt_hb_outline_destroy(path); return nullptr; }
  return path;
}

extern "C" const float *kt_hb_outline_data(const Outline *path) { return path->values; }
extern "C" unsigned kt_hb_outline_length(const Outline *path) { return path->count; }
