// Public libghostty-vt C ABI only. The JNI-facing ABI (shim.h) is deliberately small.
#include <stdlib.h>
#include <string.h>

#include "shim.h"

typedef struct RpTerminal {
  GhosttyTerminal terminal;
  GhosttyRenderState render;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  GhosttyRenderStateImageIterator images;
  ApcFn callback;
  void *userdata;
} RpTerminal;

void rp_free(RpTerminal *t) {
  if (!t) return;
  ghostty_render_state_image_iterator_free(t->images);
  ghostty_render_state_row_cells_free(t->cells);
  ghostty_render_state_row_iterator_free(t->rows);
  ghostty_render_state_free(t->render);
  ghostty_terminal_free(t->terminal);
  free(t);
}
static void unknown(GhosttyTerminal terminal, void *userdata,
                    const GhosttyTerminalUnknownSequence *sequence) {
  (void)terminal;
  RpTerminal *t = userdata;
  if (sequence->tag == GHOSTTY_TERMINAL_UNKNOWN_SEQUENCE_APC &&
      !sequence->value.apc.truncated && t->callback) {
    GhosttyString s = sequence->value.apc.content;
    t->callback(t->userdata, (const uint8_t *)s.ptr, s.len);
  }
}
static int configure(RpTerminal *t) {
  size_t limit = 65536;
  int r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, t);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_UNKNOWN_SEQUENCE, (const void *)unknown);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_UNKNOWN_MAX_BYTES, &limit);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &limit);
  const GhosttyTerminalData defaults[] = {GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND_DEFAULT,
      GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND_DEFAULT};
  const GhosttyTerminalOption color_options[] = {GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
      GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND};
  for (int i=0; r==0 && i<2; i++) {
    GhosttyColorRgb value;
    int present=ghostty_terminal_get(t->terminal,defaults[i],&value);
    if (present==GHOSTTY_NO_VALUE) {
      value=(GhosttyColorRgb){i==0?255:0,i==0?255:0,i==0?255:0};
      r=ghostty_terminal_set(t->terminal,color_options[i],&value);
    } else if(present!=0) r=present;
  }
  // No WRITE_PTY callback: the server's canonical terminal answers queries.
  if (r == 0) r = ghostty_render_state_new(NULL, &t->render);
  if (r == 0) r = ghostty_render_state_row_iterator_new(NULL, &t->rows);
  if (r == 0) r = ghostty_render_state_row_cells_new(NULL, &t->cells);
  if (r == 0) r = ghostty_render_state_image_iterator_new(NULL, &t->images);
  return r;
}
int rp_new(uint16_t cols, uint16_t rows, RpTerminal **out) {
  *out = NULL;
  RpTerminal *t = calloc(1, sizeof(*t));
  if (!t) return GHOSTTY_OUT_OF_MEMORY;
  int r = ghostty_terminal_new(NULL, &t->terminal, cols, rows);
  if (r == 0) r = configure(t);
  if (r != 0) { rp_free(t); return r; }
  *out = t;
  return 0;
}
int rp_decode(const uint8_t *bytes, size_t len, RpTerminal **out, size_t *offset) {
  *out = NULL;
  RpTerminal *t = calloc(1, sizeof(*t));
  if (!t) return GHOSTTY_OUT_OF_MEMORY;
  GhosttySnapshotDecoder decoder = NULL;
  int r = ghostty_snapshot_decoder_new_buf(NULL, &decoder, bytes, len);
  if (r == 0) r = ghostty_snapshot_decoder_decode(decoder, &t->terminal);
  if (r == 0) r = ghostty_snapshot_decoder_get(decoder,
      GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET, offset);
  ghostty_snapshot_decoder_free(decoder);
  if (r == 0) r = configure(t);
  if (r != 0) { rp_free(t); return r; }
  *out = t;
  return 0;
}
int rp_write(RpTerminal *t, const uint8_t *bytes, size_t len, size_t *consumed,
             ApcFn callback, void *userdata) {
  t->callback = callback;
  t->userdata = userdata;
  GhosttyTerminalWriteStop stop = GHOSTTY_INIT_SIZED(GhosttyTerminalWriteStop);
  int r = ghostty_terminal_vt_write_until(t->terminal, bytes, len,
      GHOSTTY_TERMINAL_BOUNDARY_APC_END, &stop);
  t->callback = NULL;
  t->userdata = NULL;
  if (r == GHOSTTY_NO_VALUE) { *consumed = len; return 0; }
  *consumed = stop.consumed;
  return r;
}
int rp_resize(RpTerminal *t, uint16_t cols, uint16_t rows, uint32_t cw, uint32_t ch) {
  return ghostty_terminal_resize(t->terminal, cols, rows, cw, ch);
}
int rp_color(RpTerminal *t, int which, const uint8_t *rgb) {
  const GhosttyTerminalOption options[] = {GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
      GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR,
      GHOSTTY_TERMINAL_OPT_COLOR_PALETTE};
  if (which < 0 || which > 3) return GHOSTTY_INVALID_VALUE;
  GhosttyColorRgb color;
  // RenderState only updates fg/bg together when both defaults are set.
  // These are Ghostty RenderState.empty's built-in fallback colors.
  const uint8_t fallback[3] = {which == 0 ? 255 : 0, which == 0 ? 255 : 0, which == 0 ? 255 : 0};
  if (!rgb && which < 2) rgb = fallback;
  if (rgb) { color.r=rgb[0]; color.g=rgb[1]; color.b=rgb[2]; }
  return ghostty_terminal_set(t->terminal, options[which], rgb ? &color : NULL);
}

void rp_scroll(RpTerminal *t, int32_t lines, int bottom) {
  GhosttyTerminalScrollViewport behavior = {0};
  behavior.tag = bottom ? GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM : GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA;
  behavior.value.delta = lines;
  ghostty_terminal_scroll_viewport(t->terminal, behavior);
}
int rp_input_flags(RpTerminal *t, uint8_t *flags) {
  bool mouse=false;
  GhosttyTerminalScreen screen=GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  GhosttyTerminalModeConfig alt={.mode=GHOSTTY_MODE_MOUSE_ALTERNATE_SCROLL},cursor={.mode=GHOSTTY_MODE_CURSOR_KEYS};
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,&mouse);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,&screen);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&alt);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&cursor);
  *flags=(mouse?1:0)|(screen==GHOSTTY_TERMINAL_SCREEN_ALTERNATE?2:0)|(alt.value?4:0)|(cursor.value?8:0);
  return r;
}
static int rp_mouse(RpTerminal *t,GhosttyMouseButton button,int release,int col,int row,uint32_t cw,uint32_t ch,uint8_t *out,size_t capacity,size_t *len) {
  GhosttyMouseEncoder encoder=NULL;GhosttyMouseEvent event=NULL;
  uint16_t cols=0,rows=0;*len=0;
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_COLS,&cols);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ROWS,&rows);
  if(r==0) r=ghostty_mouse_encoder_new(NULL,&encoder);
  if(r==0) r=ghostty_mouse_event_new(NULL,&event);
  if(r==0) {
    if(col<0)col=0;if(col>=cols)col=cols-1;if(row<0)row=0;if(row>=rows)row=rows-1;
    if(cw==0)cw=1;if(ch==0)ch=1;
    GhosttyMouseEncoderSize geometry=GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
    geometry.screen_width=cols*cw;geometry.screen_height=rows*ch;geometry.cell_width=cw;geometry.cell_height=ch;
    r=ghostty_mouse_encoder_set_from_terminal(encoder,t->terminal);
    if(r==0) r=ghostty_mouse_encoder_set(encoder,GHOSTTY_MOUSE_ENCODER_OPT_SIZE,&geometry);
    if(r==0) r=ghostty_mouse_event_set_action(event,GHOSTTY_MOUSE_ACTION_PRESS);
    if(r==0) r=ghostty_mouse_event_set_button(event,button);
    if(r==0) r=ghostty_mouse_event_set_position(event,(GhosttyMousePosition){(col+.5f)*cw,(row+.5f)*ch});
    if(r==0) r=ghostty_mouse_encoder_encode(encoder,event,out,capacity,len);
    if(r==0 && release && *len<=capacity) {
      size_t tail=0;
      r=ghostty_mouse_event_set_action(event,GHOSTTY_MOUSE_ACTION_RELEASE);
      if(r==0) r=ghostty_mouse_encoder_encode(encoder,event,out+*len,capacity-*len,&tail);
      if(r==0) *len+=tail;
    }
  }
  ghostty_mouse_event_free(event);ghostty_mouse_encoder_free(encoder);return r;
}
int rp_wheel(RpTerminal *t,int down,int col,int row,uint32_t cw,uint32_t ch,uint8_t *out,size_t capacity,size_t *len) {
  return rp_mouse(t,down?GHOSTTY_MOUSE_BUTTON_FIVE:GHOSTTY_MOUSE_BUTTON_FOUR,0,col,row,cw,ch,out,capacity,len);
}
int rp_click(RpTerminal *t,int col,int row,uint32_t cw,uint32_t ch,uint8_t *out,size_t capacity,size_t *len) {
  return rp_mouse(t,GHOSTTY_MOUSE_BUTTON_LEFT,1,col,row,cw,ch,out,capacity,len);
}
// Every Kitty image the last update put on screen, in no particular order.
static int rp_images(RpTerminal *t, ImageFn callback, void *userdata) {
  int r = ghostty_render_state_get(t->render, GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR, &t->images);
  if (r != 0) return r;
  while (ghostty_render_state_image_iterator_next(t->images)) {
    GhosttyImageProtocol protocol = 0;
    r = ghostty_render_state_image_get(t->images, GHOSTTY_RENDER_STATE_IMAGE_DATA_PROTOCOL, &protocol);
    if (r != 0) return r;
    // A protocol a later libghostty adds is skipped, not drawn wrongly.
    if (protocol != GHOSTTY_IMAGE_PROTOCOL_KITTY) continue;
    RpImage image = {0};
    GhosttyImageLayer layer = 0;
    const GhosttyRenderStateImageData keys[] = {
        GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID, GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION,
        GHOSTTY_RENDER_STATE_IMAGE_DATA_LAYER, GHOSTTY_RENDER_STATE_IMAGE_DATA_Z,
        GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL, GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW,
        GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X, GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y,
        GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_WIDTH, GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_HEIGHT,
        GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X, GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y,
        GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH, GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT,
    };
    void *values[] = {
        &image.image_id, &image.generation, &layer, &image.z, &image.column, &image.row,
        &image.offset_x, &image.offset_y, &image.width, &image.height,
        &image.source_x, &image.source_y, &image.source_width, &image.source_height,
    };
    r = ghostty_render_state_image_get_multi(t->images, sizeof(keys) / sizeof(keys[0]), keys, values, NULL);
    if (r != 0) return r;
    image.layer = (int32_t)layer;
    callback(userdata, &image);
  }
  return 0;
}

int rp_image_pixels(RpTerminal *t, uint32_t image_id, uint32_t *width, uint32_t *height,
                    GhosttyKittyImageFormat *format, const uint8_t **pixels, size_t *len) {
  GhosttyKittyGraphics graphics = NULL;
  int r = ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics);
  if (r != 0) return r;
  if (!graphics) return GHOSTTY_NO_VALUE;
  GhosttyKittyGraphicsImage image = ghostty_kitty_graphics_image(graphics, image_id);
  if (!image) return GHOSTTY_NO_VALUE;
  GhosttyString data = {0};
  r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_WIDTH, width);
  if (r == 0) r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_HEIGHT, height);
  if (r == 0) r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_FORMAT, format);
  if (r == 0) r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_PIXELS, &data);
  if (r != 0) return r;
  *pixels = data.ptr;
  *len = data.len;
  return 0;
}

int rp_frame(RpTerminal *t, RpFrame *frame, CellFn callback, ImageFn images, void *userdata) {
  int r = ghostty_render_state_update(t->render, t->terminal);
  if (r != 0) return r;
  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
#define GET(key,out) do { r=ghostty_render_state_get(t->render,key,out); if(r!=0)return r; } while(0)
  GET(GHOSTTY_RENDER_STATE_DATA_COLS,&frame->columns);
  GET(GHOSTTY_RENDER_STATE_DATA_ROWS,&frame->rows);
  GET(GHOSTTY_RENDER_STATE_DATA_COLORS,&colors);
  GET(GHOSTTY_RENDER_STATE_DATA_CURSOR,&cursor);
  GhosttyTerminalScrollbar scrollbar = {0};
  r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_SCROLLBAR,&scrollbar);
  if(r!=0)return r;
  frame->scroll_total=scrollbar.total;
  frame->scroll_offset=scrollbar.offset;
  frame->scroll_visible=scrollbar.len;
  r=rp_input_flags(t,&frame->input_flags);if(r!=0)return r;
  frame->cursor_x=cursor.viewport_x; frame->cursor_y=cursor.viewport_y;
  frame->cursor_visible=cursor.visible && cursor.viewport_has_value;
  frame->cursor_style=(uint8_t)cursor.visual_style;
  uint32_t width_px=0,height_px=0;
  r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_WIDTH_PX,&width_px);if(r!=0)return r;
  r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_HEIGHT_PX,&height_px);if(r!=0)return r;
  frame->cell_width_px=frame->columns?width_px/frame->columns:0;
  frame->cell_height_px=frame->rows?height_px/frame->rows:0;
  memcpy(frame->foreground,&colors.foreground,3);
  memcpy(frame->background,&colors.background,3);
  GET(GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,&t->rows);
  uint16_t y=0;
  uint32_t *graphemes=NULL; size_t capacity=0;
  uint8_t *uri=NULL; size_t uri_capacity=0;
  while (ghostty_render_state_row_iterator_next(t->rows)) {
    r=ghostty_render_state_row_get(t->rows,GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,&t->cells);
    if(r!=0)break;
    GhosttyRow raw_row=0; bool wrapped=false,continuation=false;
    r=ghostty_render_state_row_get(t->rows,GHOSTTY_RENDER_STATE_ROW_DATA_RAW,&raw_row);if(r!=0)break;
    r=ghostty_row_get(raw_row,GHOSTTY_ROW_DATA_WRAP,&wrapped);if(r!=0)break;
    r=ghostty_row_get(raw_row,GHOSTTY_ROW_DATA_WRAP_CONTINUATION,&continuation);if(r!=0)break;
    uint16_t x=0;
    while (ghostty_render_state_row_cells_next(t->cells)) {
      uint32_t count=0;
      GhosttyStyle style={0};
      GhosttyColorRgb fg=colors.foreground,bg=colors.background;
      GhosttyCell raw=0; GhosttyCellWide wide=GHOSTTY_CELL_WIDE_NARROW;
#define CELL(key,out) do { r=ghostty_render_state_row_cells_get(t->cells,key,out); if(r!=0)goto done; } while(0)
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,&count);
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,&style);
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,&raw);
      r=ghostty_cell_get(raw,GHOSTTY_CELL_DATA_WIDE,&wide); if(r!=0)goto done;
      // No explicit color is reported as INVALID_VALUE; retain resolved defaults.
      int fg_default = ghostty_render_state_row_cells_get(t->cells,GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,&fg) != 0;
      int bg_default = ghostty_render_state_row_cells_get(t->cells,GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,&bg) != 0;
      if(count>4096) { r=GHOSTTY_LIMIT_EXCEEDED; goto done; }
      if(count>capacity) {
        uint32_t *next=realloc(graphemes,count*sizeof(*graphemes));
        if(!next) { r=GHOSTTY_OUT_OF_MEMORY; goto done; }
        graphemes=next; capacity=count;
      }
      if(count) {
        GhosttyCodepointBuffer text={.ptr=graphemes,.cap=count};
        CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,&text);
      }
      // Client-local row metadata occupies bits above the color-role fields.
      uint32_t row_flags=(1u<<29)|((uint32_t)wrapped<<27)|((uint32_t)continuation<<28);
      uint32_t flags=row_flags |
          ((style.flags&GHOSTTY_STYLE_FLAG_BOLD)?RP_CELL_BOLD:0) |
          ((style.flags&GHOSTTY_STYLE_FLAG_ITALIC)?RP_CELL_ITALIC:0) |
          ((style.flags&GHOSTTY_STYLE_FLAG_FAINT)?RP_CELL_FAINT:0) |
          ((style.flags&GHOSTTY_STYLE_FLAG_INVERSE)?RP_CELL_INVERSE:0) |
          ((style.flags&GHOSTTY_STYLE_FLAG_INVISIBLE)?RP_CELL_INVISIBLE:0) |
          ((style.flags&GHOSTTY_STYLE_FLAG_STRIKETHROUGH)?RP_CELL_STRIKETHROUGH:0) |
          (GHOSTTY_STYLE_UNDERLINE(style)!=GHOSTTY_SGR_UNDERLINE_NONE?RP_CELL_UNDERLINE:0) |
          ((uint32_t)fg_default<<7) | ((uint32_t)bg_default<<8);
      if (style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE)
        flags |= (1u<<9) | ((uint32_t)style.fg_color.value.palette<<11);
      GhosttyCellContentTag content_tag=GHOSTTY_CELL_CONTENT_CODEPOINT;
      ghostty_cell_get(raw,GHOSTTY_CELL_DATA_CONTENT_TAG,&content_tag);
      if (content_tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) {
        GhosttyColorPaletteIndex index=0;
        ghostty_cell_get(raw,GHOSTTY_CELL_DATA_COLOR_PALETTE,&index);
        flags |= (1u<<10) | ((uint32_t)index<<19);
      } else if (style.bg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE) {
        flags |= (1u<<10) | ((uint32_t)style.bg_color.value.palette<<19);
      }
      uint8_t width=wide==GHOSTTY_CELL_WIDE_WIDE ? 2 :
          (wide==GHOSTTY_CELL_WIDE_SPACER_TAIL || wide==GHOSTTY_CELL_WIDE_SPACER_HEAD ? 0 : 1);
      size_t uri_len=0; bool has_link=false;
      r=ghostty_cell_get(raw,GHOSTTY_CELL_DATA_HAS_HYPERLINK,&has_link);if(r!=0)goto done;
      if(has_link && width>0) {
        GhosttyGridRef ref={0};
        GhosttyPoint point={.tag=GHOSTTY_POINT_TAG_VIEWPORT,.value.coordinate={.x=x,.y=y}};
        r=ghostty_terminal_grid_ref(t->terminal,point,&ref);if(r!=0)goto done;
        r=ghostty_grid_ref_hyperlink_uri(&ref,uri,uri_capacity,&uri_len);
        if(r==GHOSTTY_OUT_OF_SPACE && uri_len<=65536) {
          uint8_t *next=realloc(uri,uri_len);
          if(!next) {r=GHOSTTY_OUT_OF_MEMORY;goto done;}
          uri=next;uri_capacity=uri_len;
          r=ghostty_grid_ref_hyperlink_uri(&ref,uri,uri_capacity,&uri_len);
        }
        if(uri_len>65536) {uri_len=0;r=0;}
        if(r!=0)goto done;
      }
      callback(userdata,x++,y,graphemes,count,(const uint8_t *)&fg,(const uint8_t *)&bg,flags,width,uri,uri_len);
    }
    y++;
  }
done:
  free(graphemes);free(uri);
  if(r==0&&images)r=rp_images(t,images,userdata);
  if(r==0)r=ghostty_render_state_clean(t->render);
  return r;
}

int rp_hyperlink(RpTerminal *t, int col, int row, uint8_t *out, size_t capacity, size_t *len) {
  *len = 0;
  if (col < 0 || row < 0) return GHOSTTY_INVALID_VALUE;
  GhosttyGridRef ref = {0};
  GhosttyPoint point = {.tag = GHOSTTY_POINT_TAG_VIEWPORT};
  point.value.coordinate.x = (uint16_t)col;
  point.value.coordinate.y = (uint32_t)row;
  int r = ghostty_terminal_grid_ref(t->terminal, point, &ref);
  if (r != 0) return r;
  return ghostty_grid_ref_hyperlink_uri(&ref, out, capacity, len);
}

// Android KeyEvent key codes (android.view.KeyEvent) to the W3C-style keys libghostty-vt names.
static int android_key(int code, GhosttyKey *key) {
  if (code >= 29 && code <= 54) { *key = GHOSTTY_KEY_A + (code - 29); return 1; }       // KEYCODE_A..Z
  if (code >= 7 && code <= 16) { *key = GHOSTTY_KEY_DIGIT_0 + (code - 7); return 1; }   // KEYCODE_0..9
  if (code >= 131 && code <= 142) { *key = GHOSTTY_KEY_F1 + (code - 131); return 1; }   // KEYCODE_F1..F12
  switch (code) {
    case 66: *key = GHOSTTY_KEY_ENTER; return 1;
    case 160: *key = GHOSTTY_KEY_NUMPAD_ENTER; return 1;
    case 67: *key = GHOSTTY_KEY_BACKSPACE; return 1;
    case 112: *key = GHOSTTY_KEY_DELETE; return 1;
    case 61: *key = GHOSTTY_KEY_TAB; return 1;
    case 62: *key = GHOSTTY_KEY_SPACE; return 1;
    case 111: *key = GHOSTTY_KEY_ESCAPE; return 1;
    case 19: *key = GHOSTTY_KEY_ARROW_UP; return 1;
    case 20: *key = GHOSTTY_KEY_ARROW_DOWN; return 1;
    case 21: *key = GHOSTTY_KEY_ARROW_LEFT; return 1;
    case 22: *key = GHOSTTY_KEY_ARROW_RIGHT; return 1;
    case 92: *key = GHOSTTY_KEY_PAGE_UP; return 1;
    case 93: *key = GHOSTTY_KEY_PAGE_DOWN; return 1;
    case 122: *key = GHOSTTY_KEY_HOME; return 1;
    case 123: *key = GHOSTTY_KEY_END; return 1;
    case 124: *key = GHOSTTY_KEY_INSERT; return 1;
    case 55: *key = GHOSTTY_KEY_COMMA; return 1;
    case 56: *key = GHOSTTY_KEY_PERIOD; return 1;
    case 68: *key = GHOSTTY_KEY_BACKQUOTE; return 1;
    case 69: *key = GHOSTTY_KEY_MINUS; return 1;
    case 70: *key = GHOSTTY_KEY_EQUAL; return 1;
    case 71: *key = GHOSTTY_KEY_BRACKET_LEFT; return 1;
    case 72: *key = GHOSTTY_KEY_BRACKET_RIGHT; return 1;
    case 73: *key = GHOSTTY_KEY_BACKSLASH; return 1;
    case 74: *key = GHOSTTY_KEY_SEMICOLON; return 1;
    case 75: *key = GHOSTTY_KEY_QUOTE; return 1;
    case 76: *key = GHOSTTY_KEY_SLASH; return 1;
  }
  return 0;
}

int rp_key(RpTerminal *t, int android_keycode, int repeat, int android_meta,
           const char *utf8, size_t utf8_len, uint8_t *out, size_t capacity, size_t *len) {
  *len = 0;
  GhosttyKey key;
  if (!android_key(android_keycode, &key)) return GHOSTTY_NO_VALUE;
  GhosttyKeyEncoder encoder = NULL;
  GhosttyKeyEvent event = NULL;
  int r = ghostty_key_encoder_new(NULL, &encoder);
  if (r == 0) r = ghostty_key_event_new(NULL, &event);
  if (r == 0) {
    GhosttyMods mods = 0;
    if (android_meta & 0x1) mods |= GHOSTTY_MODS_SHIFT;
    if (android_meta & 0x2) mods |= GHOSTTY_MODS_ALT;
    if (android_meta & 0x1000) mods |= GHOSTTY_MODS_CTRL;
    if (android_meta & 0x10000) mods |= GHOSTTY_MODS_SUPER;
    // Ghostty keeps macOS's Option for typing symbols unless told otherwise. A phone has no Option
    // key: Alt is Alt, and the unit tests that build this for macOS must see what Android sees.
    // It is set after the terminal's state, which resets it.
    GhosttyOptionAsAlt option_as_alt = GHOSTTY_OPTION_AS_ALT_TRUE;
    GhosttyString text = {.ptr = (const uint8_t *)utf8, .len = utf8_len};
    r = ghostty_key_encoder_set_from_terminal(encoder, t->terminal);
    if (r == 0) r = ghostty_key_encoder_set(encoder, GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT, &option_as_alt);
    if (r == 0) r = ghostty_key_event_set_action(event, repeat ? GHOSTTY_KEY_ACTION_REPEAT : GHOSTTY_KEY_ACTION_PRESS);
    if (r == 0) r = ghostty_key_event_set_key(event, key);
    if (r == 0) r = ghostty_key_event_set_mods(event, mods);
    // The shift that made an uppercase letter or a symbol is part of the text, not of the chord.
    if (r == 0 && utf8_len && (mods & GHOSTTY_MODS_SHIFT)) r = ghostty_key_event_set_consumed_mods(event, GHOSTTY_MODS_SHIFT);
    if (r == 0) r = ghostty_key_event_set_utf8(event, utf8_len ? text : (GhosttyString){0});
    if (r == 0) r = ghostty_key_encoder_encode(encoder, event, out, capacity, len);
  }
  ghostty_key_event_free(event);
  ghostty_key_encoder_free(encoder);
  return r;
}
