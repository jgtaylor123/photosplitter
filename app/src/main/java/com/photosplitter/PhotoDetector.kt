package com.photosplitter
import android.graphics.*
import kotlin.math.*

data class CropQuad(val points: MutableList<PointF>) {
    fun copyQuad() = CropQuad(points.map { PointF(it.x,it.y) }.toMutableList())
}
object PhotoDetector {
    fun detectQuads(source: Bitmap): List<CropQuad> {
        val scale = min(1f, 850f / max(source.width,source.height))
        val w = max(1,(source.width*scale).toInt()); val h=max(1,(source.height*scale).toInt())
        val small=Bitmap.createScaledBitmap(source,w,h,true)
        val px=IntArray(w*h); small.getPixels(px,0,w,0,0,w,h)
        if(small !== source) small.recycle()
        val mask=BooleanArray(w*h) { i ->
            val c=px[i]; val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            r>=125 && g>=110 && b>=85 && g>=r*.72f && b>=r*.54f && r+g+b>=390
        }
        val seen=BooleanArray(w*h); val queue=IntArray(w*h); val result=mutableListOf<CropQuad>()
        for(start in mask.indices) {
            if(!mask[start] || seen[start]) continue
            var head=0;var tail=0;queue[tail++]=start;seen[start]=true
            val boundary=ArrayList<PointF>();var count=0
            while(head<tail) {
                val p=queue[head++];val x=p%w;val y=p/w;count++
                var edge=false
                val neighbors=intArrayOf(if(x>0)p-1 else -1,if(x<w-1)p+1 else -1,if(y>0)p-w else -1,if(y<h-1)p+w else -1)
                for(n in neighbors) {
                    if(n<0 || !mask[n]) edge=true
                    else if(!seen[n]) {seen[n]=true;queue[tail++]=n}
                }
                if(edge) boundary.add(PointF(x.toFloat(),y.toFloat()))
            }
            if(count < w*h/260 || boundary.size<20) continue
            // Principal-axis oriented bounding rectangle, robust to rotated prints.
            var cx=0.0;var cy=0.0
            for(p in boundary){cx+=p.x;cy+=p.y}
            cx/=boundary.size;cy/=boundary.size
            var xx=0.0;var yy=0.0;var xy=0.0
            for(p in boundary){val dx=p.x-cx;val dy=p.y-cy;xx+=dx*dx;yy+=dy*dy;xy+=dx*dy}
            val angle=.5*atan2(2*xy,xx-yy)
            val a=cos(angle);val b=sin(angle)
            var loU=Double.POSITIVE_INFINITY;var hiU=Double.NEGATIVE_INFINITY
            var loV=Double.POSITIVE_INFINITY;var hiV=Double.NEGATIVE_INFINITY
            for(p in boundary) {
                val dx=p.x-cx;val dy=p.y-cy
                val u=dx*a+dy*b;val v=-dx*b+dy*a
                loU=min(loU,u);hiU=max(hiU,u);loV=min(loV,v);hiV=max(hiV,v)
            }
            val bw=hiU-loU;val bh=hiV-loV
            if(bw< w/15.0 || bh<h/15.0 || bw/bh !in .35..2.85 || count/(bw*bh)<.45) continue
            val pad=max(2.0,min(bw,bh)*.025)
            loU-=pad;hiU+=pad;loV-=pad;hiV+=pad
            fun corner(u:Double,v:Double):PointF = PointF(
                ((cx+u*a-v*b)/w*source.width).toFloat().coerceIn(0f,source.width.toFloat()),
                ((cy+u*b+v*a)/h*source.height).toFloat().coerceIn(0f,source.height.toFloat()))
            val corners=listOf(corner(loU,loV),corner(hiU,loV),corner(hiU,hiV),corner(loU,hiV))
            // Order corners in image coordinates (top-left, top-right, bottom-right, bottom-left).
            val top=corners.sortedBy { it.y }.take(2).sortedBy { it.x }
            val bottom=corners.sortedByDescending { it.y }.take(2).sortedBy { it.x }
            val quad=CropQuad(mutableListOf(top[0],top[1],bottom[1],bottom[0]))
            val mx=quad.points.map{it.x}.average();val my=quad.points.map{it.y}.average()
            if(result.none { old ->
                val ox=old.points.map{it.x}.average();val oy=old.points.map{it.y}.average()
                hypot(mx-ox,my-oy)<min(bw/source.width*source.width,bh/source.height*source.height)*.3
            }) result.add(quad)
        }
        return result.sortedWith(compareBy({it.points.map{p->p.y}.average()},{it.points.map{p->p.x}.average()}))
    }
    fun crop(source:Bitmap,quad:CropQuad):Bitmap {
        val p=quad.points
        fun dist(a:PointF,b:PointF)=hypot((a.x-b.x).toDouble(),(a.y-b.y).toDouble())
        val w=max(1,max(dist(p[0],p[1]),dist(p[3],p[2])).roundToInt())
        val h=max(1,max(dist(p[0],p[3]),dist(p[1],p[2])).roundToInt())
        val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val matrix=Matrix()
        val src=floatArrayOf(p[0].x,p[0].y,p[1].x,p[1].y,p[2].x,p[2].y,p[3].x,p[3].y)
        val dst=floatArrayOf(0f,0f,w.toFloat(),0f,w.toFloat(),h.toFloat(),0f,h.toFloat())
        if(matrix.setPolyToPoly(src,0,dst,0,4)) {
            Canvas(output).drawBitmap(source,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return output
    }
}
