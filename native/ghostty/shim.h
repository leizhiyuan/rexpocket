// The small C surface between the JNI layer and the public libghostty-vt C ABI.
#ifndef REXPOCKET_SHIM_H
#define REXPOCKET_SHIM_H
#include <ghostty/vt.h>
#include <stddef.h>
#include <stdint.h>

typedef void (*ApcFn)(void *, const uint8_t *, size_t);
typedef void (*CellFn)(void *, uint16_t, uint16_t, const uint32_t *, size_t,
                       const uint8_t *, const uint8_t *, uint32_t, uint8_t, const uint8_t *, size_t);
typedef struct {
  uint16_t columns, rows, cursor_x, cursor_y;
  uint8_t cursor_visible, foreground[3], background[3];
  uint64_t scroll_total, scroll_offset, scroll_visible;
  uint8_t input_flags;
  uint8_t cursor_style;  // GhosttyRenderStateCursorVisualStyle
} RpFrame;
typedef struct RpTerminal RpTerminal;

// Cell flag bits reported to CellFn.
#define RP_CELL_BOLD (1u << 0)
#define RP_CELL_ITALIC (1u << 1)
#define RP_CELL_FAINT (1u << 2)
#define RP_CELL_INVERSE (1u << 3)
#define RP_CELL_INVISIBLE (1u << 4)
#define RP_CELL_STRIKETHROUGH (1u << 5)
#define RP_CELL_UNDERLINE (1u << 6)
#define RP_CELL_FG_DEFAULT (1u << 7)
#define RP_CELL_BG_DEFAULT (1u << 8)
#define RP_CELL_WRAPPED (1u << 27)
// RpFrame.input_flags bits.
#define RP_INPUT_MOUSE 1
#define RP_INPUT_ALT_SCREEN 2
#define RP_INPUT_ALT_SCROLL 4
#define RP_INPUT_CURSOR_APP 8

int rp_new(uint16_t cols, uint16_t rows, RpTerminal **out);
int rp_decode(const uint8_t *bytes, size_t len, RpTerminal **out, size_t *offset);
void rp_free(RpTerminal *t);
int rp_write(RpTerminal *t, const uint8_t *bytes, size_t len, size_t *consumed, ApcFn callback, void *userdata);
int rp_resize(RpTerminal *t, uint16_t cols, uint16_t rows, uint32_t cw, uint32_t ch);
int rp_color(RpTerminal *t, int which, const uint8_t *rgb);
void rp_scroll(RpTerminal *t, int32_t lines, int bottom);
int rp_wheel(RpTerminal *t, int down, int col, int row, uint32_t cw, uint32_t ch, uint8_t *out, size_t capacity, size_t *len);
int rp_click(RpTerminal *t, int col, int row, uint32_t cw, uint32_t ch, uint8_t *out, size_t capacity, size_t *len);
int rp_frame(RpTerminal *t, RpFrame *frame, CellFn callback, void *userdata);

// The link under a viewport cell; *len is 0 when there is none, or the size needed on OUT_OF_SPACE.
int rp_hyperlink(RpTerminal *t, int col, int row, uint8_t *out, size_t capacity, size_t *len);
// Encodes an Android key event the way the terminal's current modes ask for.
int rp_key(RpTerminal *t, int android_keycode, int repeat, int android_meta,
           const char *utf8, size_t utf8_len, uint8_t *out, size_t capacity, size_t *len);
#endif
