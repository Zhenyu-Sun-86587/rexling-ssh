package dev.shulhi.rex

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.Paint
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.widget.OverScroller
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.max

class TerminalView(context: Context) : View(context) {
    var interactive = true
        set(value) {if(field!=value) {field=value;if(!value) clearSelection()}}
    private var selection: TerminalSelection?=null
    private var selectionMode: ActionMode?=null
    private var selectionTouch=false
    private var moveSelectionAnchor=false
    private var pressedLink: TerminalLink?=null
    internal var onOpenLink: (String)->Unit={openWebLink(it)}
    private var pressX=0f
    private var pressY=0f
    private val longPress=Runnable {performLongClick()}
    internal val selectedText: String? get()=selection?.text()
    var palette: TerminalPalette = TerminalPalette.Dark
        set(value) { if(field != value) { field=value;clearRows();invalidate() } }
    var onPaneDrag: ((Float, Boolean) -> Unit)? = null
    var onPaneDragEnd: ((Float, Float) -> Unit)? = null
    private var startX=0f
    private var startY=0f
    private var dragging=false
    private var scrolling=false
    private var lastScrollY=0f
    private var scrollPixels=0f
    private var lastFlingY=0
    private val scrollFling=OverScroller(context)
    private var touchVelocity: VelocityTracker?=null
    var onInput: (String) -> Unit = {}
    var onScroll: (Int) -> Unit = {}
    var onWheel: (Int,Int,Int,Int,Int) -> Unit = {lines,_,_,_,_->onScroll(lines)}
    var onActivate: () -> Unit = {}
    private var wheelCol=0
    private var wheelRow=0
    var onPaneSwipe: (Int) -> Unit = {}
    var onDimensions: (Int, Int) -> Unit = { _, _ -> }
        set(value) {val changed=field!=value;field=value;if(changed && width>0 && height>0) reportDimensions(force=true)}
    var screen: TerminalScreen? = null
        set(value) { if(field!==value) {val columns=field?.columns;field = value;if(columns!=value?.columns) configureRendering();postInvalidateOnAnimation()} }
    var controlled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) composing = ""
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
            }
        }
    var textSp: Float = 10f
        set(value) { if (field != value) { field = value; metrics(); invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint() // Cell fills must meet without antialiased seams.
    private val fonts = TerminalFonts(context)
    private data class RecordedRow(val cells: List<TerminalCell>,val picture: Picture,val links: List<TerminalLink>,val continues: Boolean,val boundary: Boolean)
    private var recorded= arrayOfNulls<RecordedRow>(0)
    private var rowGeometry=""
    private val advances=object: LinkedHashMap<Pair<Int,String>,Float>(256,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int,String>,Float>?)=size>4096
    }
    internal var recordedRowCount=0;private set
    private fun clearRows() {recorded=arrayOfNulls(0);rowGeometry=""}
    private fun advance(text: String,style: Int): Float=advances.getOrPut(style to text){paint.measureText(text)}
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f
    private var desiredCellWidth=1f
    private var desiredCellHeight=1f
    private var desiredTextSize=1f
    private var dimensions = 0 to 0
    private var composing = ""
    private var insetHorizontal = 0f
    private var insetTop = 0f
    private var insetBottom = 0f
    private val contentWidth: Float get() = (width - insetHorizontal * 2).coerceAtLeast(1f)
    private val contentHeight: Float get() = (height - insetTop - insetBottom).coerceAtLeast(1f)
    fun setTerminalPaddingDp(horizontal: Int, top: Int, bottom: Int) {
        val density = resources.displayMetrics.density
        val h = (horizontal * density).roundToInt().toFloat()
        val t = (top * density).roundToInt().toFloat()
        val b = (bottom * density).roundToInt().toFloat()
        if (h == insetHorizontal && t == insetTop && b == insetBottom) return
        insetHorizontal = h; insetTop = t; insetBottom = b
        configureRendering();clearRows();reportDimensions(); invalidate()
    }
    private val paneGesture = PaneGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 48f * resources.displayMetrics.density)

    init {
        isFocusable = true; isFocusableInTouchMode = true;isLongClickable=true
        contentDescription = "Terminal output. Tap web links to open them. Tap elsewhere to take control and type. Long press to select and copy text."
        metrics()
    }
    private fun metrics() {
        clearRows();advances.clear()
        val grid = fonts.configure(paint, textSp)
        desiredCellWidth=grid.cellWidth;desiredCellHeight=grid.cellHeight;desiredTextSize=paint.textSize
        configureRendering();reportDimensions()
    }
    private fun configureRendering() {
        // Keep the user's font fixed. Reflow comes from the canonical PTY resize.
        val fitted=desiredCellWidth
        val size=desiredTextSize
        if(cellWidth==fitted && paint.textSize==size) return
        cellWidth=fitted;paint.textSize=size;paint.typeface=fonts.regular;paint.textScaleX=1f
        paint.textScaleX=fitted/paint.measureText("M").coerceAtLeast(.01f)
        val metrics=paint.fontMetrics;cellHeight=ceil(metrics.descent-metrics.ascent).coerceAtLeast(1f);baseline=-metrics.ascent
        clearRows();advances.clear()
    }
    private fun reportDimensions(force: Boolean=false) {
        // Resize uses the requested font grid, never the temporary read-only fit.
        val next = max(1, floor(contentWidth / desiredCellWidth).toInt()) to max(1, floor(contentHeight / desiredCellHeight).toInt())
        if (width > 0 && height > 0 && (force || next != dimensions)) { dimensions = next; onDimensions(next.first, next.second) }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {clearSelection();configureRendering();reportDimensions()}
    override fun onDraw(canvas: Canvas) {
        val frame = selection?.frame ?: screen ?: return
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(palette.background)
        val visibleColumns = minOf(frame.columns, max(1, ceil(contentWidth / cellWidth).toInt()))
        val visibleRows = minOf(frame.rows, max(1, ceil(contentHeight / cellHeight).toInt()))
        android.os.Trace.beginSection("Rexlet.drawTerminal")
        val geometry="$width:$height:${frame.columns}:$visibleColumns:$visibleRows:$insetHorizontal:$insetTop:$insetBottom"
        if(geometry!=rowGeometry) {recorded=arrayOfNulls(visibleRows);rowGeometry=geometry}
        for(y in 0 until visibleRows) {
            val cells=frame.lines[y]
            var row=recorded[y]
            val continues=continuesInNextRow(frame,y)
            if(row==null || row.cells!=cells || (row.boundary && row.continues!=continues)) {
                val origin=if(y==0) 0f else insetTop+y*cellHeight
                val top=if(y==0) insetTop else 0f
                val bottom=if(y==visibleRows-1) height-origin else top+cellHeight
                val picture=Picture()
                val target=picture.beginRecording(width,bottom.toInt().coerceAtLeast(1))
                val rawLinks=TerminalLinks.row(cells,y,frame.columns)
                val links=rawLinks.filterNot {it.end>=frame.columns && continues}
                drawRow(target,cells,visibleColumns,top,bottom)
                target.save();target.clipRect(insetHorizontal,top,width-insetHorizontal,top+cellHeight)
                backgroundPaint.color=palette.accent;backgroundPaint.strokeWidth=resources.displayMetrics.density.coerceAtLeast(1f)
                for(link in links) target.drawLine(insetHorizontal+link.start*cellWidth,top+baseline+2,
                    insetHorizontal+link.end*cellWidth,top+baseline+2,backgroundPaint)
                target.restore()
                picture.endRecording();row=RecordedRow(cells,picture,links,continues,rawLinks.any {it.end>=frame.columns});recorded[y]=row;recordedRowCount++
            }
            canvas.save();canvas.translate(0f,if(y==0) 0f else insetTop+y*cellHeight)
            canvas.drawPicture(row.picture);canvas.restore()
        }
        canvas.save()
        canvas.clipRect(insetHorizontal,insetTop,width-insetHorizontal,height-insetBottom)
        canvas.translate(insetHorizontal,insetTop)
        if (selection==null && frame.cursorX != null && frame.cursorY != null) {
            val cell=frame.lines.getOrNull(frame.cursorY)?.firstOrNull {it.x==frame.cursorX && it.width>0}
            val x = frame.cursorX * cellWidth; val y = frame.cursorY * cellHeight
            val cursorWidth=max(1,cell?.width ?: 1)*cellWidth
            backgroundPaint.color=palette.accent
            canvas.drawRect(x,y,x+cursorWidth,y+cellHeight,backgroundPaint)
            if(cell!=null && cell.text.isNotEmpty() && cell.attributes and 16==0) {
                paint.color=android.graphics.Color.WHITE;paint.alpha=255
                paint.typeface=when(cell.attributes and 3) {1->fonts.bold;2->fonts.italic;3->fonts.boldItalic;else->fonts.regular}
                val advance=advance(cell.text,cell.attributes and 3)
                canvas.save();canvas.clipRect(x,y,x+cursorWidth,y+cellHeight)
                if(advance>cursorWidth) canvas.scale(cursorWidth/advance,1f,x,y)
                canvas.drawText(cell.text,x+((cursorWidth-advance)/2f).coerceAtLeast(0f),y+baseline,paint)
                canvas.restore()
            }
            if (composing.isNotEmpty()) {
                paint.typeface = fonts.regular;paint.color=android.graphics.Color.WHITE
                val composingWidth=max(cellWidth,paint.measureText(composing))
                canvas.drawRect(x,y,x+composingWidth,y+cellHeight,backgroundPaint)
                canvas.drawText(composing, x, y + baseline, paint)
                canvas.drawLine(x, y + baseline + 2, x + composingWidth, y + baseline + 2, paint)
            }
        }
        canvas.restore()
        drawSelection(canvas)
        canvas.restoreToCount(saved)
        android.os.Trace.endSection()
    }
    private fun drawRow(canvas: Canvas,cells: List<TerminalCell>,columns: Int,top: Float,bottom: Float) {
        var start=0;var end=0;var color=0
        fun flush() {
            if(end<=start || start>=columns) return
            backgroundPaint.color=color
            canvas.drawRect(if(start==0) 0f else insetHorizontal+start*cellWidth,0f,
                if(end>=columns) width.toFloat() else insetHorizontal+end*cellWidth,bottom,backgroundPaint)
        }
        for(cell in cells) {
            if(cell.width==0 && cell.x<end) continue
            val next=if(cell.attributes and 8!=0) palette.foreground(cell) else palette.background(cell)
            if(cell.x!=end || next!=color) {flush();start=cell.x;color=next}
            end=cell.x+max(1,cell.width)
        }
        flush()
        canvas.save();canvas.clipRect(insetHorizontal,top,width-insetHorizontal,top+cellHeight)
        canvas.translate(insetHorizontal,top)
        var index=0
        while(index<cells.size) {
            val cell=cells[index++]
            if(cell.x>=columns || cell.width==0 || cell.text.isEmpty() || cell.attributes and 16!=0) continue
            val style=cell.attributes and 3
            paint.typeface=when(style) {1->fonts.bold;2->fonts.italic;3->fonts.boldItalic;else->fonts.regular}
            paint.color=if(cell.attributes and 8!=0) palette.background(cell) else palette.foreground(cell)
            paint.alpha=if(cell.attributes and 4!=0) 150 else 255
            var text=cell.text;var width=cell.width
            val ascii=cell.width==1 && cell.text.length==1 && cell.text[0] in ' '..'~'
            if(ascii) {
                val run=StringBuilder(text)
                while(index<cells.size) {
                    val next=cells[index]
                    if(next.x!=cell.x+width || next.x>=columns || next.width!=1 || next.text.length!=1 || next.text[0] !in ' '..'~' ||
                        next.attributes!=cell.attributes || next.foreground!=cell.foreground || next.background!=cell.background) break
                    run.append(next.text);width++;index++
                }
                text=run.toString()
            }
            val x=cell.x*cellWidth;val allocated=width*cellWidth
            if(text.any {it!=' '}) {
                if(ascii) canvas.drawText(text,x,baseline,paint)
                else {
                    val advance=advance(text,style)
                    canvas.save();canvas.clipRect(x,0f,x+allocated,cellHeight)
                    if(advance>allocated) canvas.scale(allocated/advance,1f,x,0f)
                    canvas.drawText(text,x+((allocated-advance)/2f).coerceAtLeast(0f),baseline,paint);canvas.restore()
                }
            }
            paint.strokeWidth=resources.displayMetrics.density.coerceAtLeast(1f)
            if(cell.attributes and 64!=0) canvas.drawLine(x,baseline+2,x+allocated,baseline+2,paint)
            if(cell.attributes and 32!=0) canvas.drawLine(x,cellHeight/2,x+allocated,cellHeight/2,paint)
            paint.alpha=255
        }
        canvas.restore()
    }
    private fun scrollByPixels(pixels: Float) {
        scrollPixels+=pixels
        val lines=(scrollPixels/cellHeight).toInt()
        if(lines!=0) {
            scrollPixels-=lines*cellHeight
            if(screen?.applicationScroll==true && screen?.scroll?.below==0L) onWheel(lines,wheelCol,wheelRow,desiredCellWidth.roundToInt(),desiredCellHeight.roundToInt())
            else if((screen?.scroll?.available ?: 0)>0) onScroll(lines)
        }
    }
    override fun computeScroll() {
        if(scrollFling.computeScrollOffset()) {
            val y=scrollFling.currY;scrollByPixels((y-lastFlingY).toFloat());lastFlingY=y
            postInvalidateOnAnimation()
        }
    }
    override fun onDetachedFromWindow() {clearSelection();scrollFling.abortAnimation();touchVelocity?.recycle();touchVelocity=null;super.onDetachedFromWindow()}
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(selection!=null && event.actionMasked==MotionEvent.ACTION_SCROLL) return true
        if(interactive && event.actionMasked==MotionEvent.ACTION_SCROLL) {
            val lines=(-event.getAxisValue(MotionEvent.AXIS_VSCROLL)*3).roundToInt()
            if(lines!=0) {
                if(screen?.applicationScroll==true && screen?.scroll?.below==0L && event.metaState and KeyEvent.META_SHIFT_ON==0)
                    onWheel(lines,((event.x-insetHorizontal)/desiredCellWidth).toInt(),((event.y-insetTop)/desiredCellHeight).toInt(),desiredCellWidth.roundToInt(),desiredCellHeight.roundToInt())
                else onScroll(lines)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }
    fun keyboard() {
        if (!controlled) return
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
    }
    private fun continuesInNextRow(frame: TerminalScreen,row: Int): Boolean =
        frame.lines.getOrNull(row+1)?.firstOrNull {it.width>0 && it.x==0}?.text?.any {c->!c.isWhitespace()}==true
    private fun linksForRow(frame: TerminalScreen,row: Int): List<TerminalLink> {
        val cells=frame.lines.getOrNull(row) ?: return emptyList()
        // Row-wrap flags are not yet exported. Never launch an apparent URL
        // prefix at a full row boundary when the next row could continue it.
        return TerminalLinks.row(cells,row,frame.columns).filterNot {
            it.end>=frame.columns && continuesInNextRow(frame,row)
        }
    }
    private fun linkAt(x: Float,y: Float): TerminalLink? {
        val frame=screen ?: return null
        if(x<insetHorizontal || x>=width-insetHorizontal || y<insetTop || y>=height-insetBottom) return null
        val column=((x-insetHorizontal)/cellWidth).toInt();val row=((y-insetTop)/cellHeight).toInt()
        val cells=frame.lines.getOrNull(row) ?: return null
        val links=recorded.getOrNull(row)?.takeIf {it.cells==cells && (!it.boundary || it.continues==continuesInNextRow(frame,row))}?.links ?: linksForRow(frame,row)
        return links.firstOrNull {it.contains(column)}
    }
    private fun openWebLink(url: String) {
        val safe=TerminalLinks.webUrl(url) ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context,"No app can open this web link",Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context,"Could not open this web link",Toast.LENGTH_SHORT).show()
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!interactive) return false
        if(selectionTouch || selection!=null) return selectionEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {touchVelocity?.recycle();touchVelocity=VelocityTracker.obtain()}
        val sample=MotionEvent.obtain(event)
        sample.offsetLocation(event.rawX-event.x,event.rawY-event.y)
        touchVelocity?.addMovement(sample);sample.recycle()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {pressedLink=linkAt(event.x,event.y);pressX=event.x;pressY=event.y;removeCallbacks(longPress);postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong());wheelCol=((event.x-insetHorizontal)/desiredCellWidth).toInt();wheelRow=((event.y-insetTop)/desiredCellHeight).toInt();scrollFling.abortAnimation();startX=event.rawX;startY=event.rawY;lastScrollY=event.rawY;scrollPixels=0f;scrolling=false;dragging=false;paneGesture.begin(event.x, event.y)}
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) { removeCallbacks(longPress);paneGesture.cancel();onPaneDrag?.invoke(0f,false);dragging=false;scrolling=false }
                else {
                    paneGesture.move(event.x,event.y)
                    val dx=event.rawX-startX;val dy=event.rawY-startY
                    val slop=ViewConfiguration.get(context).scaledTouchSlop
                    if(kotlin.math.abs(dx)>slop || kotlin.math.abs(dy)>slop) removeCallbacks(longPress)
                    if(!scrolling && kotlin.math.abs(dx)>slop && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f) dragging=true
                    if(!dragging && kotlin.math.abs(dy)>slop && kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.5f) {scrolling=true;paneGesture.cancel()}
                    if(scrolling) {scrollByPixels(lastScrollY-event.rawY);lastScrollY=event.rawY}
                    else if(dragging) onPaneDrag?.invoke(dx,true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {pressedLink=null;removeCallbacks(longPress);paneGesture.cancel();onPaneDrag?.invoke(0f,false);dragging=false;scrolling=false}
            MotionEvent.ACTION_UP -> if(scrolling) {
                touchVelocity?.computeCurrentVelocity(1000)
                val velocity=-(touchVelocity?.yVelocity ?: 0f).toInt()
                val history=screen?.scroll
                if(history!=null && history.available>0) {
                    val minimum=-(history.offset.toDouble()*cellHeight).coerceAtMost(1000000.0).toInt()
                    val maximum=(history.below.toDouble()*cellHeight).coerceAtMost(1000000.0).toInt()
                    lastFlingY=0;scrollFling.fling(0,0,0,velocity,0,0,minimum,maximum);postInvalidateOnAnimation()
                }
                scrolling=false;paneGesture.cancel()
            } else if(dragging && onPaneDragEnd!=null) {
                touchVelocity?.computeCurrentVelocity(1000)
                onPaneDragEnd?.invoke(event.rawX-startX,touchVelocity?.xVelocity ?: 0f);dragging=false;paneGesture.cancel()
            } else when (paneGesture.finish(event.x, event.y)) {
                PaneGesture.Result.TAP -> {val link=pressedLink;if(link==null) performClick() else if(link==linkAt(event.x,event.y)) onOpenLink(link.url)}
                PaneGesture.Result.NEXT -> onPaneSwipe(1)
                PaneGesture.Result.PREVIOUS -> onPaneSwipe(-1)
                PaneGesture.Result.NONE -> Unit
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {pressedLink=null;removeCallbacks(longPress);touchVelocity?.recycle();touchVelocity=null}
        return true
    }
    private fun clearSelection() {
        removeCallbacks(longPress)
        selection=null;selectionTouch=false
        val mode=selectionMode;selectionMode=null;mode?.finish();invalidate()
    }
    private fun selectionEvent(event: MotionEvent): Boolean {
        val current=selection
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectionTouch=true
                if(current!=null) {
                    fun distance(point: TerminalSelection.Point,end: Boolean): Float {
                        val x=insetHorizontal+(if(end) current.endColumn(point) else point.column)*cellWidth
                        val y=insetTop+(point.row+1)*cellHeight
                        return kotlin.math.hypot(event.x-x,event.y-y)
                    }
                    val a=distance(current.anchor,false);val b=distance(current.extent,true)
                    if(minOf(a,b)<=28*resources.displayMetrics.density) moveSelectionAnchor=a<b
                    else {clearSelection();selectionTouch=true}
                }
            }
            MotionEvent.ACTION_MOVE -> if(current!=null && event.pointerCount==1) {
                current.move(((event.x-insetHorizontal)/cellWidth).toInt(),((event.y-insetTop)/cellHeight).toInt(),moveSelectionAnchor)
                selectionMode?.invalidateContentRect();invalidate()
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {selectionTouch=false;touchVelocity?.recycle();touchVelocity=null}
        }
        return true
    }
    override fun performLongClick(): Boolean {
        val frame=screen ?: return false
        if(!interactive) return false
        pressedLink=null
        removeCallbacks(longPress);paneGesture.cancel();dragging=false;scrolling=false;scrollFling.abortAnimation();onPaneDrag?.invoke(0f,false)
        selection=TerminalSelection(frame,((pressX-insetHorizontal)/cellWidth).toInt(),((pressY-insetTop)/cellHeight).toInt()).also {it.word()}
        selectionTouch=true;moveSelectionAnchor=false;parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        selectionMode=startActionMode(object: ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode,menu: Menu): Boolean {
                menu.add(0,android.R.id.copy,0,"Copy").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0,android.R.id.selectAll,1,"Select all").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode,menu: Menu)=false
            override fun onActionItemClicked(mode: ActionMode,item: MenuItem): Boolean = when(item.itemId) {
                android.R.id.copy -> {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal selection",selection?.text().orEmpty()))
                    mode.finish();true
                }
                android.R.id.selectAll -> {selection?.all();invalidate();mode.invalidateContentRect();true}
                else -> false
            }
            override fun onDestroyActionMode(mode: ActionMode) {if(selectionMode===mode) {selectionMode=null;selection=null;invalidate()}}
            override fun onGetContentRect(mode: ActionMode,view: View,outRect: android.graphics.Rect) {
                val selected=selection ?: return super.onGetContentRect(mode,view,outRect)
                outRect.set((insetHorizontal+selected.first.column*cellWidth).toInt(),(insetTop+selected.first.row*cellHeight).toInt(),
                    (insetHorizontal+selected.endColumn(selected.last)*cellWidth).toInt().coerceAtMost(width),
                    (insetTop+(selected.last.row+1)*cellHeight).toInt().coerceAtMost(height))
            }
        },ActionMode.TYPE_FLOATING)
        invalidate();return true
    }
    private fun drawSelection(canvas: Canvas) {
        val selected=selection ?: return
        backgroundPaint.color=(palette.accent and 0xffffff) or (0x55 shl 24)
        for(row in selected.first.row..selected.last.row) {
            val left=if(row==selected.first.row) selected.first.column else 0
            val right=if(row==selected.last.row) selected.endColumn(selected.last) else selected.frame.columns
            canvas.drawRect(insetHorizontal+left*cellWidth,insetTop+row*cellHeight,
                (insetHorizontal+right*cellWidth).coerceAtMost(width-insetHorizontal),insetTop+(row+1)*cellHeight,backgroundPaint)
        }
        backgroundPaint.color=palette.accent
        for((point,end) in listOf(selected.first to false,selected.last to true)) {
            val x=insetHorizontal+(if(end) selected.endColumn(point) else point.column)*cellWidth
            val y=insetTop+(point.row+1)*cellHeight
            canvas.drawCircle(x,y,5*resources.displayMetrics.density,backgroundPaint)
        }
    }
    override fun performClick(): Boolean { super.performClick();if(interactive) {if(controlled) keyboard() else onActivate()};return true }
    override fun onCheckIsTextEditor() = controlled
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!controlled) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = ""; if (text != null) onInput(text.toString()); invalidate(); return true }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString().orEmpty(); invalidate(); return true }
            override fun finishComposingText(): Boolean { if (composing.isNotEmpty()) onInput(composing); composing = ""; invalidate(); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) { composing = composing.dropLast(beforeLength.coerceAtMost(composing.length)); invalidate(); return true }
                repeat(beforeLength.coerceAtMost(128)) { onInput("\u007f") }
                repeat(afterLength.coerceAtMost(128)) { onInput("\u001b[3~") }; return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                if (event.keyCode == KeyEvent.KEYCODE_ENTER) finishComposingText()
                return onKeyDown(event.keyCode, event)
            }
            override fun performEditorAction(actionCode: Int): Boolean { finishComposingText(); onInput("\r"); return true }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(event.isShiftPressed && keyCode in listOf(KeyEvent.KEYCODE_PAGE_UP,KeyEvent.KEYCODE_PAGE_DOWN)) {
            onScroll((if(keyCode==KeyEvent.KEYCODE_PAGE_UP) -1 else 1)*(screen?.rows ?: 24));return true
        }
        if (!controlled) return super.onKeyDown(keyCode, event)
        val named = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_DEL -> "backspace"; KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
            KeyEvent.KEYCODE_PAGE_UP -> "pageup"; KeyEvent.KEYCODE_PAGE_DOWN -> "pagedown"
            KeyEvent.KEYCODE_TAB -> "tab"; KeyEvent.KEYCODE_ESCAPE -> "esc"
            KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"; KeyEvent.KEYCODE_MOVE_END -> "end"
            else -> null
        }
        if (named != null) { onInput(TerminalInput.key(named,event.isCtrlPressed,event.isAltPressed,event.isShiftPressed)); return true }
        val point = event.unicodeChar
        if (point != 0 && point and KeyCharacterMapCompat.COMBINING_ACCENT == 0) {
            onInput(TerminalInput.modified(String(Character.toChars(point)), event.isCtrlPressed, event.isAltPressed)); return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
private object KeyCharacterMapCompat { const val COMBINING_ACCENT: Int = Int.MIN_VALUE }
