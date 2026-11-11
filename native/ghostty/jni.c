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
  jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, snapshot, NULL);
  if (!bytes) return 0;
  RpTerminal *t = NULL;
  size_t offset = 0;
  int r = rp_decode((const uint8_t *)bytes, (size_t)len, &t, &offset);
  (*env)->ReleasePrimitiveArrayCritical(env, snapshot, bytes, JNI_ABORT);
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
  jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
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
  (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
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
// One int[]: HEADER words, then per cell CELL_WORDS + codepoints. Cells come row by row, left to
// right, every cell of the viewport. The layout is read by TerminalFrame.kt.
#define HEADER 12
#define CELL_WORDS 5

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

static int32_t argb(const uint8_t *c) { return (int32_t)(0xff000000u | (uint32_t)c[0] << 16 | (uint32_t)c[1] << 8 | c[2]); }

static void on_cell(void *userdata, uint16_t x, uint16_t y, const uint32_t *graphemes, size_t count,
                    const uint8_t *fg, const uint8_t *bg, uint32_t flags, uint8_t width,
                    const uint8_t *uri, size_t uri_len) {
  (void)uri;
  Packed *p = userdata;
  int32_t head[CELL_WORDS] = {
      (int32_t)((uint32_t)x | (uint32_t)y << 16), (int32_t)flags, argb(fg), argb(bg),
      (int32_t)((uint32_t)width | (uri_len ? 16u : 0u) | (uint32_t)count << 8)};
  if (!push(p, head, CELL_WORDS)) return;
  for (size_t i = 0; i < count; i++) {
    int32_t cp = (int32_t)graphemes[i];
    if (!push(p, &cp, 1)) return;
  }
}

// Header: columns, rows, cursor x, cursor y (-1 when hidden), default fg, default bg, scrollback
// total (rows), offset of the viewport (rows), rows visible, input flags, cell count (filled in
// last), cursor style.
JNIEXPORT jintArray JNICALL FN(nativeFrame)(JNIEnv *env, jclass cls, jlong handle) {
  (void)cls;
  RpFrame frame;
  memset(&frame, 0, sizeof(frame));
  Packed p = {0};
  int32_t header[HEADER] = {0};
  push(&p, header, HEADER);
  int r = rp_frame(TERM(handle), &frame, on_cell, &p);
  if (r != 0 || p.failed) { free(p.words); return NULL; }
  size_t cells = 0;
  for (size_t i = HEADER; i < p.len; cells++) i += CELL_WORDS + ((uint32_t)p.words[i + 4] >> 8);
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
  jintArray out = (*env)->NewIntArray(env, (jsize)p.len);
  if (out) (*env)->SetIntArrayRegion(env, out, 0, (jsize)p.len, p.words);
  free(p.words);
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
