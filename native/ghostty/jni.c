// JNI for me.leaver.rexpocket.terminal.GhosttyVt: a libghostty-vt terminal per handle.
//
// The handle is an RpTerminal*. A terminal is not thread-safe: the Kotlin side serializes every
// call on one handle. Errors are reported as non-zero libghostty result codes (or null/0 for
// calls that return data); nothing here throws.
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "shim.h"

#define FN(name) Java_me_leaver_rexpocket_terminal_GhosttyVt_##name
#define TERM(h) ((RpTerminal *)(intptr_t)(h))

// ---- PNG -------------------------------------------------------------------------------------
// libghostty-vt ships no PNG decoder, and rejects a PNG image without one. Programs such as pi send
// PNG, so the platform's decoder is lent to it: GhosttyVt.decodePng, Android's BitmapFactory. It is
// called on the thread that is writing to the terminal, which is a Java thread, so it has a JNIEnv.
static JavaVM *vm;
static jclass vt_class;
static jmethodID decode_png_method;

static bool decode_png(void *userdata, const GhosttyAllocator *allocator, const uint8_t *data,
                       size_t data_len, GhosttySysImage *out) {
  (void)userdata;
  JNIEnv *env = NULL;
  if (!vm || data_len > 0x7fffffff || (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return false;
  jbyteArray png = (*env)->NewByteArray(env, (jsize)data_len);
  if (!png) { (*env)->ExceptionClear(env); return false; }
  (*env)->SetByteArrayRegion(env, png, 0, (jsize)data_len, (const jbyte *)data);
  jintArray decoded = (*env)->CallStaticObjectMethod(env, vt_class, decode_png_method, png);
  (*env)->DeleteLocalRef(env, png);
  if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return false; }
  if (!decoded) return false;
  bool ok = false;
  jsize words = (*env)->GetArrayLength(env, decoded);
  jint *argb = words >= 2 ? (*env)->GetIntArrayElements(env, decoded, NULL) : NULL;
  if (argb) {
    uint32_t width = (uint32_t)argb[0], height = (uint32_t)argb[1];
    size_t count = (size_t)width * height;
    uint8_t *rgba = NULL;
    if (width && height && count == (size_t)(words - 2) && (rgba = ghostty_alloc(allocator, count * 4))) {
      for (size_t i = 0; i < count; i++) {
        uint32_t c = (uint32_t)argb[2 + i];
        rgba[i * 4] = (uint8_t)(c >> 16);
        rgba[i * 4 + 1] = (uint8_t)(c >> 8);
        rgba[i * 4 + 2] = (uint8_t)c;
        rgba[i * 4 + 3] = (uint8_t)(c >> 24);
      }
      out->width = width;
      out->height = height;
      out->data = rgba;
      out->data_len = count * 4;
      ok = true;
    }
    (*env)->ReleaseIntArrayElements(env, decoded, argb, JNI_ABORT);
  }
  (*env)->DeleteLocalRef(env, decoded);
  return ok;
}

// Runs once, from System.loadLibrary, before any terminal exists: ghostty_sys_set is process-wide
// and must not race a terminal in use.
JNIEXPORT jint JNI_OnLoad(JavaVM *loaded, void *reserved) {
  (void)reserved;
  JNIEnv *env = NULL;
  if ((*loaded)->GetEnv(loaded, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass local = (*env)->FindClass(env, "me/leaver/rexpocket/terminal/GhosttyVt");
  if (!local) return JNI_ERR;
  vt_class = (*env)->NewGlobalRef(env, local);
  (*env)->DeleteLocalRef(env, local);
  decode_png_method = (*env)->GetStaticMethodID(env, vt_class, "decodePng", "([B)[I");
  if (!vt_class || !decode_png_method) return JNI_ERR;
  vm = loaded;
  if (ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, (const void *)decode_png) != 0) return JNI_ERR;
  return JNI_VERSION_1_6;
}

static jbyteArray to_bytes(JNIEnv *env, const void *data, size_t len) {
  jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
  if (out && len) (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, data);
  return out;
}

JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv *env, jclass cls, jint cols, jint rows) {
  (void)env; (void)cls;
  RpTerminal *t = NULL;
  if (cols <= 0 || rows <= 0 || cols > 0xffff || rows > 0xffff) return 0;
  return rp_new((uint16_t)cols, (uint16_t)rows, &t) == 0 ? (jlong)(intptr_t)t : 0;
}

JNIEXPORT jlong JNICALL FN(nativeRestore)(JNIEnv *env, jclass cls, jbyteArray snapshot) {
  (void)cls;
  jsize len = (*env)->GetArrayLength(env, snapshot);
  // Not a critical region: decoding can call back into Java, for a PNG.
  jbyte *bytes = (*env)->GetByteArrayElements(env, snapshot, NULL);
  if (!bytes) return 0;
  RpTerminal *t = NULL;
  size_t offset = 0;
  int r = rp_decode((const uint8_t *)bytes, (size_t)len, &t, &offset);
  (*env)->ReleaseByteArrayElements(env, snapshot, bytes, JNI_ABORT);
  return r == 0 ? (jlong)(intptr_t)t : 0;
}

JNIEXPORT void JNICALL FN(nativeFree)(JNIEnv *env, jclass cls, jlong handle) {
  (void)env; (void)cls;
  rp_free(TERM(handle));
}

// The pane's own terminal answers what a program asks of it, so APC sequences are consumed and
// dropped here, and writing carries on after each one.
static void ignore_apc(void *userdata, const uint8_t *bytes, size_t len) {
  (void)userdata; (void)bytes; (void)len;
}

JNIEXPORT jint JNICALL FN(nativeWrite)(JNIEnv *env, jclass cls, jlong handle, jbyteArray data, jint offset, jint length) {
  (void)cls;
  // Not a critical region: a Kitty image sent as PNG is decoded by calling back into Java.
  jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
  if (!bytes) return GHOSTTY_OUT_OF_MEMORY;
  const uint8_t *p = (const uint8_t *)bytes + offset;
  size_t left = (size_t)length;
  int r = 0;
  while (left > 0) {
    size_t consumed = 0;
    r = rp_write(TERM(handle), p, left, &consumed, ignore_apc, NULL);
    if (r != 0 || consumed == 0) { if (r == 0) r = GHOSTTY_INVALID_VALUE; break; }
    p += consumed;
    left -= consumed;
  }
  (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
  return r;
}

JNIEXPORT jint JNICALL FN(nativeResize)(JNIEnv *env, jclass cls, jlong handle, jint cols, jint rows, jint cw, jint ch) {
  (void)env; (void)cls;
  if (cols <= 0 || rows <= 0 || cols > 0xffff || rows > 0xffff) return GHOSTTY_INVALID_VALUE;
  return rp_resize(TERM(handle), (uint16_t)cols, (uint16_t)rows, cw > 0 ? (uint32_t)cw : 1, ch > 0 ? (uint32_t)ch : 1);
}

// which: 0 foreground, 1 background; argb's alpha is ignored.
JNIEXPORT jint JNICALL FN(nativeSetColor)(JNIEnv *env, jclass cls, jlong handle, jint which, jint argb) {
  (void)env; (void)cls;
  uint8_t rgb[3] = {(uint8_t)(argb >> 16), (uint8_t)(argb >> 8), (uint8_t)argb};
  return rp_color(TERM(handle), which, rgb);
}

JNIEXPORT void JNICALL FN(nativeScroll)(JNIEnv *env, jclass cls, jlong handle, jint lines) {
  (void)env; (void)cls;
  rp_scroll(TERM(handle), lines, 0);
}

JNIEXPORT void JNICALL FN(nativeScrollToBottom)(JNIEnv *env, jclass cls, jlong handle) {
  (void)env; (void)cls;
  rp_scroll(TERM(handle), 0, 1);
}

// ---- Frame -------------------------------------------------------------------------------------
// One int[]: HEADER words, then per cell CELL_WORDS + codepoints, then IMAGE_WORDS per image. Cells
// come row by row, left to right, every cell of the viewport. The layout is read by TerminalFrame.kt.
#define HEADER 15
#define CELL_WORDS 5
#define IMAGE_WORDS 15

typedef struct {
  int32_t *words;
  size_t len, cap;
  int failed;
} Packed;

static int push(Packed *p, const int32_t *v, size_t n) {
  if (p->failed) return 0;
  if (p->len + n > p->cap) {
    size_t cap = p->cap ? p->cap : 4096;
    while (cap < p->len + n) cap *= 2;
    int32_t *next = realloc(p->words, cap * sizeof(*next));
    if (!next) { p->failed = 1; return 0; }
    p->words = next;
    p->cap = cap;
  }
  memcpy(p->words + p->len, v, n * sizeof(*v));
  p->len += n;
  return 1;
}

typedef struct {
  Packed cells, images;
} FrameOut;

static int32_t argb(const uint8_t *c) { return (int32_t)(0xff000000u | (uint32_t)c[0] << 16 | (uint32_t)c[1] << 8 | c[2]); }

static void on_cell(void *userdata, uint16_t x, uint16_t y, const uint32_t *graphemes, size_t count,
                    const uint8_t *fg, const uint8_t *bg, uint32_t flags, uint8_t width,
                    const uint8_t *uri, size_t uri_len) {
  (void)uri;
  Packed *p = &((FrameOut *)userdata)->cells;
  int32_t head[CELL_WORDS] = {
      (int32_t)((uint32_t)x | (uint32_t)y << 16), (int32_t)flags, argb(fg), argb(bg),
      (int32_t)((uint32_t)width | (uri_len ? 16u : 0u) | (uint32_t)count << 8)};
  if (!push(p, head, CELL_WORDS)) return;
  for (size_t i = 0; i < count; i++) {
    int32_t cp = (int32_t)graphemes[i];
    if (!push(p, &cp, 1)) return;
  }
}

// Image: id, generation (low, high), layer, z, column, row, offset x, offset y, width, height,
// source x, y, width, height. See RpImage.
static void on_image(void *userdata, const RpImage *image) {
  Packed *p = &((FrameOut *)userdata)->images;
  int32_t words[IMAGE_WORDS] = {
      (int32_t)image->image_id, (int32_t)(uint32_t)image->generation, (int32_t)(uint32_t)(image->generation >> 32),
      image->layer, image->z, image->column, image->row,
      (int32_t)image->offset_x, (int32_t)image->offset_y, (int32_t)image->width, (int32_t)image->height,
      (int32_t)image->source_x, (int32_t)image->source_y, (int32_t)image->source_width, (int32_t)image->source_height};
  push(p, words, IMAGE_WORDS);
}

// Header: columns, rows, cursor x, cursor y (-1 when hidden), default fg, default bg, scrollback
// total (rows), offset of the viewport (rows), rows visible, input flags, cell count (filled in
// last), cursor style, image count, the cell's width and height in the terminal's pixels.
JNIEXPORT jintArray JNICALL FN(nativeFrame)(JNIEnv *env, jclass cls, jlong handle) {
  (void)cls;
  RpFrame frame;
  memset(&frame, 0, sizeof(frame));
  FrameOut out = {0};
  int32_t header[HEADER] = {0};
  push(&out.cells, header, HEADER);
  int r = rp_frame(TERM(handle), &frame, on_cell, on_image, &out);
  if (r == 0 && out.images.len) push(&out.cells, out.images.words, out.images.len);
  free(out.images.words);
  if (r != 0 || out.cells.failed || out.images.failed) { free(out.cells.words); return NULL; }
  Packed p = out.cells;
  size_t cells = 0;
  size_t images = out.images.len / IMAGE_WORDS;
  for (size_t i = HEADER; i < p.len - images * IMAGE_WORDS; cells++) i += CELL_WORDS + ((uint32_t)p.words[i + 4] >> 8);
  p.words[0] = frame.columns;
  p.words[1] = frame.rows;
  p.words[2] = frame.cursor_visible ? frame.cursor_x : -1;
  p.words[3] = frame.cursor_visible ? frame.cursor_y : -1;
  p.words[4] = argb(frame.foreground);
  p.words[5] = argb(frame.background);
  p.words[6] = (int32_t)frame.scroll_total;
  p.words[7] = (int32_t)frame.scroll_offset;
  p.words[8] = (int32_t)frame.scroll_visible;
  p.words[9] = frame.input_flags;
  p.words[10] = (int32_t)cells;
  p.words[11] = frame.cursor_style;  // 0 bar, 1 block, 2 underline, 3 hollow block
  p.words[12] = (int32_t)images;
  p.words[13] = (int32_t)frame.cell_width_px;
  p.words[14] = (int32_t)frame.cell_height_px;
  jintArray words = (*env)->NewIntArray(env, (jsize)p.len);
  if (words) (*env)->SetIntArrayRegion(env, words, 0, (jsize)p.len, p.words);
  free(p.words);
  return words;
}

// A stored Kitty image as [width, height, ARGB...], which is what an Android Bitmap is made from;
// null when there is no such image or its pixels have not all arrived.
JNIEXPORT jintArray JNICALL FN(nativeImage)(JNIEnv *env, jclass cls, jlong handle, jint image_id) {
  (void)cls;
  uint32_t width = 0, height = 0;
  GhosttyKittyImageFormat format = 0;
  const uint8_t *px = NULL;
  size_t len = 0;
  if (rp_image_pixels(TERM(handle), (uint32_t)image_id, &width, &height, &format, &px, &len) != 0) return NULL;
  size_t bpp = format == GHOSTTY_KITTY_IMAGE_FORMAT_RGBA ? 4 : format == GHOSTTY_KITTY_IMAGE_FORMAT_RGB ? 3
             : format == GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA ? 2 : format == GHOSTTY_KITTY_IMAGE_FORMAT_GRAY ? 1 : 0;
  size_t count = (size_t)width * height;
  if (!bpp || !count || count > 0x7ffffff0u || len < count * bpp) return NULL;
  int32_t *argb_px = malloc((count + 2) * sizeof(*argb_px));
  if (!argb_px) return NULL;
  argb_px[0] = (int32_t)width;
  argb_px[1] = (int32_t)height;
  for (size_t i = 0; i < count; i++) {
    const uint8_t *s = px + i * bpp;
    uint32_t r, g, b, a;
    switch (bpp) {
      case 4: r = s[0]; g = s[1]; b = s[2]; a = s[3]; break;
      case 3: r = s[0]; g = s[1]; b = s[2]; a = 255; break;
      case 2: r = g = b = s[0]; a = s[1]; break;
      default: r = g = b = s[0]; a = 255; break;
    }
    argb_px[2 + i] = (int32_t)(a << 24 | r << 16 | g << 8 | b);
  }
  jintArray out = (*env)->NewIntArray(env, (jsize)(count + 2));
  if (out) (*env)->SetIntArrayRegion(env, out, 0, (jsize)(count + 2), argb_px);
  free(argb_px);
  return out;
}

// ---- Input -------------------------------------------------------------------------------------
JNIEXPORT jbyteArray JNICALL FN(nativeWheel)(JNIEnv *env, jclass cls, jlong handle, jboolean down,
                                             jint col, jint row, jint cw, jint ch) {
  (void)cls;
  uint8_t out[64];
  size_t len = 0;
  if (rp_wheel(TERM(handle), down, col, row, (uint32_t)cw, (uint32_t)ch, out, sizeof(out), &len) != 0) return NULL;
  return to_bytes(env, out, len);
}

JNIEXPORT jbyteArray JNICALL FN(nativeClick)(JNIEnv *env, jclass cls, jlong handle, jint col, jint row, jint cw, jint ch) {
  (void)cls;
  uint8_t out[64];
  size_t len = 0;
  if (rp_click(TERM(handle), col, row, (uint32_t)cw, (uint32_t)ch, out, sizeof(out), &len) != 0) return NULL;
  return to_bytes(env, out, len);
}

// Null when the key is not one the terminal encodes; empty when it encodes to nothing.
JNIEXPORT jbyteArray JNICALL FN(nativeKey)(JNIEnv *env, jclass cls, jlong handle, jint keycode,
                                           jboolean repeat, jint meta, jstring text) {
  (void)cls;
  char utf8[16];
  size_t utf8_len = 0;
  if (text) {
    jsize n = (*env)->GetStringUTFLength(env, text);
    if (n > 0 && (size_t)n < sizeof(utf8)) {
      (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), utf8);
      utf8_len = (size_t)n;
    }
  }
  uint8_t out[128];
  size_t len = 0;
  if (rp_key(TERM(handle), keycode, repeat, meta, utf8, utf8_len, out, sizeof(out), &len) != 0) return NULL;
  return to_bytes(env, out, len);
}

// The link under a viewport cell, UTF-8; null when there is none.
JNIEXPORT jbyteArray JNICALL FN(nativeHyperlinkAt)(JNIEnv *env, jclass cls, jlong handle, jint col, jint row) {
  (void)cls;
  uint8_t small[512];
  size_t len = 0;
  int r = rp_hyperlink(TERM(handle), col, row, small, sizeof(small), &len);
  if (r == 0) return len ? to_bytes(env, small, len) : NULL;
  if (r != GHOSTTY_OUT_OF_SPACE || len == 0 || len > 65536) return NULL;
  uint8_t *big = malloc(len);
  if (!big) return NULL;
  jbyteArray out = NULL;
  if (rp_hyperlink(TERM(handle), col, row, big, len, &len) == 0 && len) out = to_bytes(env, big, len);
  free(big);
  return out;
}
