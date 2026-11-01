// Public libghostty-vt C ABI only. The Rust-facing ABI is deliberately small.
#include <ghostty/vt.h>
#include <stdlib.h>
#include <string.h>

typedef void (*ApcFn)(void *, const uint8_t *, size_t);
typedef void (*CellFn)(void *, uint16_t, uint16_t, const uint32_t *, size_t,
                       const uint8_t *, const uint8_t *, uint32_t, uint8_t);
typedef struct {
  uint16_t columns, rows, cursor_x, cursor_y;
  uint8_t cursor_visible, foreground[3], background[3];
  uint64_t scroll_total, scroll_offset, scroll_visible;
} RpFrame;
typedef struct {
  GhosttyTerminal terminal;
  GhosttyRenderState render;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  ApcFn callback;
  void *userdata;
} RpTerminal;

void rp_free(RpTerminal *t) {
  if (!t) return;
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
int rp_palette(RpTerminal *t, uint8_t index, const uint8_t *rgb) {
  GhosttyColorRgb palette[256];
  int r = ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, palette);
  if (r != 0) return r;
  palette[index] = (GhosttyColorRgb){rgb[0],rgb[1],rgb[2]};
  return ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette);
}
int rp_text(RpTerminal *t, uint8_t **out, size_t *len) {
  GhosttyFormatterTerminalOptions options = GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
  options.extra = (GhosttyFormatterTerminalExtra)GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalExtra);
  options.extra.screen = (GhosttyFormatterScreenExtra)GHOSTTY_INIT_SIZED(GhosttyFormatterScreenExtra);
  options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  options.trim = true;
  GhosttyFormatter formatter = NULL;
  int r = ghostty_formatter_terminal_new(NULL, &formatter, t->terminal, options);
  if (r == 0) r = ghostty_formatter_format_alloc(formatter, NULL, out, len);
  ghostty_formatter_free(formatter);
  return r;
}
int rp_encode(RpTerminal *t, uint8_t **out, size_t *len) {
  return ghostty_snapshot_encode_alloc(t->terminal, NULL, out, len);
}
void rp_bytes_free(uint8_t *bytes, size_t len) { ghostty_free(NULL, bytes, len); }

void rp_scroll(RpTerminal *t, int32_t lines, int bottom) {
  GhosttyTerminalScrollViewport behavior = {0};
  behavior.tag = bottom ? GHOSTTY_SCROLL_VIEWPORT_BOTTOM : GHOSTTY_SCROLL_VIEWPORT_DELTA;
  behavior.value.delta = lines;
  ghostty_terminal_scroll_viewport(t->terminal, behavior);
}
int rp_frame(RpTerminal *t, RpFrame *frame, CellFn callback, void *userdata) {
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
  frame->cursor_x=cursor.viewport_x; frame->cursor_y=cursor.viewport_y;
  frame->cursor_visible=cursor.visible && cursor.viewport_has_value;
  memcpy(frame->foreground,&colors.foreground,3);
  memcpy(frame->background,&colors.background,3);
  GET(GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,&t->rows);
  uint16_t y=0;
  uint32_t *graphemes=NULL; size_t capacity=0;
  while (ghostty_render_state_row_iterator_next(t->rows)) {
    r=ghostty_render_state_row_get(t->rows,GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,&t->cells);
    if(r!=0)break;
    uint16_t x=0;
    while (ghostty_render_state_row_cells_next(t->cells)) {
      uint32_t count=0;
      GhosttyStyle style=GHOSTTY_INIT_SIZED(GhosttyStyle);
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
      if(count) { CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,graphemes); }
      uint32_t flags=style.bold | (style.italic<<1) | (style.faint<<2) |
          (style.inverse<<3) | (style.invisible<<4) | (style.strikethrough<<5) |
          ((style.underline!=0)<<6) | (fg_default<<7) | (bg_default<<8);
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
      callback(userdata,x++,y,graphemes,count,(const uint8_t *)&fg,(const uint8_t *)&bg,flags,width);
    }
    y++;
  }
done:
  free(graphemes);
  if(r==0)r=ghostty_render_state_clean(t->render);
  return r;
}
