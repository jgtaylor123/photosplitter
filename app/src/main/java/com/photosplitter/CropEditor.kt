package com.photosplitter
import android.content.Context
import android.graphics.*
import android.view.*
import kotlin.math.*
class CropEditor(context:Context):View(context) {
    var photo:Bitmap?=null
        set(value){field=value;requestLayout();invalidate()}
    val boxes=mutableListOf<CropQuad>()
    var editing=false
    var onChanged:(()->Unit)?=null
    private var active=-1;private var mode=-1
    private var prevX=0f;private var prevY=0f
    private var cornerDragging=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    fun setBoxes(items:List<CropQuad>){boxes.clear();boxes.addAll(items.map{it.copyQuad()});invalidate();onChanged?.invoke()}
    fun addBox(){
        val b=photo?:return;val w=b.width.toFloat();val h=b.height.toFloat()
        boxes.add(CropQuad(mutableListOf(PointF(w*.3f,h*.3f),PointF(w*.7f,h*.3f),PointF(w*.7f,h*.7f),PointF(w*.3f,h*.7f))))
        active=boxes.lastIndex;invalidate();onChanged?.invoke()
    }
    fun removeSelected(){if(active in boxes.indices){boxes.removeAt(active);active=-1;invalidate();onChanged?.invoke()}}
    override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);val b=photo;setMeasuredDimension(w,if(b!=null&&b.width>0) max(1,(w.toFloat()*b.height/b.width).toInt()) else w)}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);val bmp=photo?:return
        canvas.drawBitmap(bmp,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)
        val sx=width.toFloat()/bmp.width;val sy=height.toFloat()/bmp.height
        boxes.forEachIndexed{index,q->
            val path=Path()
            q.points.forEachIndexed{i,p->if(i==0)path.moveTo(p.x*sx,p.y*sy) else path.lineTo(p.x*sx,p.y*sy)}
            path.close()
            paint.color=if(index==active&&editing)Color.YELLOW else Color.CYAN
            paint.style=Paint.Style.STROKE;paint.strokeWidth=3*density;canvas.drawPath(path,paint)
            paint.style=Paint.Style.FILL;paint.textSize=18*density
            canvas.drawText("${index+1}",q.points[0].x*sx+4*density,q.points[0].y*sy+20*density,paint)
            if(editing)q.points.forEach{canvas.drawCircle(it.x*sx,it.y*sy,8*density,paint)}
        }
        if (editing && cornerDragging && active in boxes.indices && mode in 0..3) {
            drawCornerMagnifier(canvas, bmp, boxes[active].points[mode], sx, sy)
        }
    }

    private fun drawCornerMagnifier(canvas: Canvas, bmp: Bitmap, point: PointF, sx: Float, sy: Float) {
        val px = point.x * sx
        val py = point.y * sy
        val side = min(160f * density, min(width, height) * .42f)
        if (side < 32f) return
        val margin = 10f * density
        // Place the magnifier in the screen corner farthest from the edited point.
        val destinations = listOf(
            RectF(margin, margin, margin + side, margin + side),
            RectF(width - margin - side, margin, width - margin, margin + side),
            RectF(margin, height - margin - side, margin + side, height - margin),
            RectF(width - margin - side, height - margin - side, width - margin, height - margin)
        )
        val dest = destinations.maxByOrNull {
            val dx = it.centerX() - px
            val dy = it.centerY() - py
            dx * dx + dy * dy
        } ?: return
        val visiblePixels = (side / (min(sx, sy) * 3f)).coerceAtLeast(8f)
        val source = RectF(
            point.x - visiblePixels / 2f, point.y - visiblePixels / 2f,
            point.x + visiblePixels / 2f, point.y + visiblePixels / 2f
        )
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(5f * density, 0f, 2f * density, Color.BLACK)
        }
        canvas.save()
        canvas.drawRoundRect(dest, 8f * density, 8f * density, bg)
        canvas.clipRect(dest)
        // Draw the photo with the selected point exactly at the centre.
        val mapping = Matrix().apply {
            setRectToRect(source, dest, Matrix.ScaleToFit.FILL)
        }
        canvas.drawBitmap(bmp, mapping, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            strokeWidth = 2f * density
            style = Paint.Style.STROKE
        }
        val cx = dest.centerX()
        val cy = dest.centerY()
        canvas.drawLine(cx, dest.top, cx, dest.bottom, line)
        canvas.drawLine(dest.left, cy, dest.right, cy, line)
        line.color = Color.BLACK
        line.strokeWidth = 1f * density
        canvas.drawCircle(cx, cy, 5f * density, line)
        canvas.restore()
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        canvas.drawRoundRect(dest, 8f * density, 8f * density, outline)
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        val bmp=photo?:return false;if(!editing)return false
        val x=event.x.coerceIn(0f,width.toFloat())*bmp.width/width
        val y=event.y.coerceIn(0f,height.toFloat())*bmp.height/height
        val radius=28*density*bmp.width/width
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                // Keep the gesture in this view even when the finger crosses another box.
                parent?.requestDisallowInterceptTouchEvent(true)
                active=-1;mode=-1;cornerDragging=false
                for(i in boxes.indices.reversed()){
                    val pts=boxes[i].points
                    val corner=pts.indexOfFirst{hypot(x-it.x,y-it.y)<radius}
                    if(corner>=0){active=i;mode=corner;cornerDragging=true;break}
                    val path=Path();pts.forEachIndexed{j,p->if(j==0)path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y)};path.close()
                    val region=Region();val clip=Region(0,0,bmp.width,bmp.height);region.setPath(path,clip)
                    if(region.contains(x.toInt(),y.toInt())){active=i;mode=4;break}
                }
                prevX=x;prevY=y;invalidate();return true
            }
            MotionEvent.ACTION_MOVE->{
                if(active !in boxes.indices)return true
                val pts=boxes[active].points
                if(mode in 0..3){pts[mode].x=x;pts[mode].y=y}
                else if(mode==4){
                    val dx=x-prevX;val dy=y-prevY
                    val safeX=dx.coerceIn(-pts.minOf{it.x},bmp.width-pts.maxOf{it.x})
                    val safeY=dy.coerceIn(-pts.minOf{it.y},bmp.height-pts.maxOf{it.y})
                    pts.forEach{it.offset(safeX,safeY)}
                }
                prevX=x;prevY=y;invalidate();return true
            }
            MotionEvent.ACTION_UP->{
                parent?.requestDisallowInterceptTouchEvent(false)
                cornerDragging=false
                invalidate()
                onChanged?.invoke()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL->{
                parent?.requestDisallowInterceptTouchEvent(false)
                cornerDragging=false
                invalidate()
                return true
            }
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
