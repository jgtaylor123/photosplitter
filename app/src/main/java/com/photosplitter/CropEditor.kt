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
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        val bmp=photo?:return false;if(!editing)return false
        val x=event.x.coerceIn(0f,width.toFloat())*bmp.width/width
        val y=event.y.coerceIn(0f,height.toFloat())*bmp.height/height
        val radius=28*density*bmp.width/width
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                active=-1;mode=-1
                for(i in boxes.indices.reversed()){
                    val pts=boxes[i].points
                    val corner=pts.indexOfFirst{hypot(x-it.x,y-it.y)<radius}
                    if(corner>=0){active=i;mode=corner;break}
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
            MotionEvent.ACTION_UP->{onChanged?.invoke();performClick();return true}
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
