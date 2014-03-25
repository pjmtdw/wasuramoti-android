package karuta.hpnpwd.wasuramoti
import _root_.android.content.Context
import _root_.android.view.{View,MotionEvent,ViewTreeObserver}
import _root_.android.text.TextUtils
import _root_.android.widget.HorizontalScrollView
import _root_.android.graphics.{Canvas,Typeface,Paint,Color,Rect,Path}
import _root_.android.util.{Log,AttributeSet}
import _root_.android.os.CountDownTimer

import scala.collection.mutable

object TypefaceManager{
  val cache = new mutable.HashMap[String,Typeface]()
  def get(context:Context,conf:String):Typeface = {
    if(conf.startsWith("asset:")){
      cache.getOrElse(conf,
        try{
          val t = Typeface.createFromAsset(context.getAssets,"font/"+conf.substring(6))
          cache.put(conf,t)
          t
        }catch{
          case _:Throwable => Typeface.DEFAULT
        })
    }else{
      Typeface.DEFAULT
    }
  }
}

class YomiInfoLayout(context:Context, attrs:AttributeSet) extends HorizontalScrollView(context, attrs){

  var SCROLL_THREASHOLD = 0.25
  var SCROLL_THREASHOLD_DIP = 100
  val SCROLL_SPEED = 200 // in millisec
  var cur_view = None:Option[Int]
  def scrollAnimation(endx:Int,on_finish:Unit=>Unit=Unit=>Unit){
    val startx = getScrollX
    new CountDownTimer(SCROLL_SPEED,10){
      override def onTick(millisUntilFinished:Long){
        val r = millisUntilFinished / SCROLL_SPEED.toFloat
        val pos = (startx * r + endx * (1-r)).toInt
        smoothScrollTo(pos,0)
      }
      override def onFinish(){
        smoothScrollTo(endx,0)
        // There seems no simple way to run a hook after smoothScrollTo() is ended.
        // Therefore we run on_finish() after specific time.
        postDelayed(new Runnable(){
            override def run(){
              on_finish()
            }},30)
      }
    }.start
  }
  override def onTouchEvent(ev:MotionEvent):Boolean = {
    super.onTouchEvent(ev)
    ev.getAction match{
      case MotionEvent.ACTION_UP =>
        cur_view.foreach{ vid=>
          val v = findViewById(vid)
          if(v != null){
            val dx = getScrollX-v.getLeft
            val threshold = Math.min((v.getWidth * SCROLL_THREASHOLD).toInt, Utils.DipToPx(context,SCROLL_THREASHOLD_DIP).toInt)
            val nvid = if(Math.abs(dx) > threshold){
              if(dx > 0){
                vid match{
                  case R.id.yomi_info_view_next => R.id.yomi_info_view_cur
                  case _ => R.id.yomi_info_view_prev
                }
              }else{
                vid match{
                  case R.id.yomi_info_view_prev => R.id.yomi_info_view_cur
                  case _ => R.id.yomi_info_view_next
                }
              }
            }else{
              vid
            }
            scrollToView(nvid,true,true)
          }
        }
      case _ => Unit
    }
    true
  }
  override def onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
    super.onSizeChanged(w,h,oldw,oldh)
    if(w == 0){
      return
    }
    val that = this
    // as for android 2.1, we have to execute the following in post(...) method
    post(new Runnable(){
        override def run(){
          for(i <- Array(R.id.yomi_info_view_next,R.id.yomi_info_view_cur,R.id.yomi_info_view_prev)){
            val v = findViewById(i)
            if(v!=null){
              val prop = v.getLayoutParams
              prop.width = w
              v.setLayoutParams(prop)
            }
          }

          val vto = that.getViewTreeObserver
          vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
              override def onGlobalLayout(){
                that.getViewTreeObserver.removeGlobalOnLayoutListener(this)
                val vid = Globals.player.flatMap{_.current_yomi_info}.getOrElse(R.id.yomi_info_view_cur)
                that.scrollToView(vid,false)
              }
          })

          requestLayout()
      }
    })
  }
  def invalidateAndScroll(){
    for(i <- Array(R.id.yomi_info_view_next,R.id.yomi_info_view_cur,R.id.yomi_info_view_prev)){
      val v = findViewById(i).asInstanceOf[YomiInfoView]
      if(v!=null){
        v.updateCurNum
        v.invalidate
      }
    }
    scrollToView(R.id.yomi_info_view_cur,false)
  }

  def scrollToView(id:Int,smooth:Boolean,from_touch_event:Boolean=false){
    val v = findViewById(id).asInstanceOf[YomiInfoView]
    if(v!=null){
      val x = v.getLeft
      val have_to_move = from_touch_event && Array(R.id.yomi_info_view_prev,R.id.yomi_info_view_next).contains(id)
      if(smooth){
        scrollAnimation(x,
          _=>
            if(have_to_move){
              val wa = context.asInstanceOf[WasuramotiActivity]
              wa.cancelAllPlay()
              v.cur_num.foreach{ cn =>
                FudaListHelper.queryIndexFromFudaNum(context,cn).foreach{index =>
                  if(Utils.readCurNext(context)){
                    FudaListHelper.putCurrentIndex(context,index)
                  }else{
                    FudaListHelper.queryPrevOrNext(context,index,false).foreach{ x=>
                      FudaListHelper.putCurrentIndex(context,x._4)
                    }
                  }
                }
              }
              wa.refreshAndSetButton()
              wa.invalidateYomiInfo()
            }
        )
      }else{
        scrollTo(x,0)
      }
      cur_view = Some(id)
      getContext.asInstanceOf[WasuramotiActivity].updatePoemInfo(id)
    }
  }

}

class YomiInfoView(context:Context, attrs:AttributeSet) extends View(context, attrs) {
  var screen_range_main:Rect = null
  var screen_range_furi:Rect = null

  var render_with_path = false

  val MARGIN_TOP = Array(0.04,0.08,0.12,0.06,0.10) // from right to left, rate of view height
  val MARGIN_AUTHOR = Array(0.09,0.13) // rate of view height
  val MARGIN_BOTTOM = 0.06 // rate of view height
  val MARGIN_LR = 0.06 // rate of view width

  val SPACE_V = 0.15 // rate of text size
  val SPACE_H = 0.05 // rate of view width
  val SPACE_V_FURIGANA = 0.15 // rate of text size
  val FURIGANA_TOP_LIMIT = 0.02 // rate of view height
  val FURIGANA_BOTTOM_LIMIT = 0.03 // rate of view height
  val FURIGANA_RATIO_DEFAULT = 0.70 // rate of span_h
  val FURIGANA_MARGIN_LEFT_MIN = 2 // in pixels

  // According to http://developer.android.com/guide/topics/graphics/hardware-accel.html ,
  // `Don't create render objects in draw methods`
  val paint = new Paint(Paint.ANTI_ALIAS_FLAG)
  paint.setColor(Color.WHITE)
  val paint_furigana = new Paint(Paint.ANTI_ALIAS_FLAG)
  paint_furigana.setColor(Color.rgb(199,239,251))

  var show_furigana = true
  var show_author = true
  var show_kami = true
  var show_simo = true

  var cur_num = None:Option[Int]

  // This does not include span height
  def measureBoundOneLine(str:String,paint:Paint):(Int,Int,Int) = {
    var wchar = 0
    var hchar = 0
    var hline = 0
    for(s <- str){
      val r = new Rect()
      paint.getTextBounds(s.toString,0,1,r)
      wchar = Math.max(wchar, r.right - r.left + 1)
      val h = r.bottom - r.top + 1
      hchar = Math.max(hchar,h)
      hline += h
    }
    (wchar,hchar,hline)
  }

  def measureBoundAve(text_array:Array[String],paint:Paint):(Int,Int,Int) = {
    val ar = text_array.map{measureBoundOneLine(_,paint)}
    val w_ave = ar.map{_._1}.sum / ar.length
    val h_ave = ar.map{_._2}.sum / ar.length
    val hh_ave = ar.map{_._3}.sum / ar.length
    (w_ave,h_ave,hh_ave)
  }
  def measureBoundMax(text_array:Array[String],paint:Paint):(Int,Int,Int) = {
    val ar = text_array.map{measureBoundOneLine(_,paint)}
    val w_max = ar.map{_._1}.max.toInt
    val h_max = ar.map{_._2}.max.toInt
    val hh_max = ar.map{_._3}.max.toInt
    (w_max,h_max,hh_max)
  }

  // Typeface of paint must be set before calling this function
  // Also text size must be default before calling this function ( I don't know why, maybe it's a bug ? )
  def calculateTextSize(text_array_with_margin:Array[(String,Double)],paint:Paint,space_h:Double):Int ={
    // we estimate the result text size, the closer it is, the result will be more accurate
    val estimate_size = getWidth / text_array_with_margin.length
    paint.setTextSize(estimate_size)
    val bounds = (for((t,m)<-text_array_with_margin)yield(measureBoundOneLine(t,paint)))
    val r1 = (for(((w,_,h),(t,m)) <- bounds.zip(text_array_with_margin))yield{
      val ya = (1-m-MARGIN_BOTTOM)*getHeight.toFloat
      val yb = h + (t.length-1) * SPACE_V * estimate_size;
      ya / yb
    }).min
    val xa = (1-MARGIN_LR*2-text_array_with_margin.length*space_h)*getWidth.toFloat
    val xb = bounds.map{case(w,_,h)=>w}.sum
    val r2 = xa / xb
    (Math.min(r1,r2)*estimate_size).toInt
  }


  def verticalText(canvas:Canvas,startx:Int,starty:Int,text:String,margin_left_furigana:Int){
    val text_s = AllFuda.parseFurigana(text)
    var y = starty
    var prev_furigana_bottom = (getHeight*FURIGANA_TOP_LIMIT).toInt
    for((t,furigana) <- text_s){
      val (y_,prev_furigana_bottom_) = verticalWord(canvas,startx,y,t,furigana,margin_left_furigana,prev_furigana_bottom)
      // TODO: the following is so ugly
      y = y_
      prev_furigana_bottom = prev_furigana_bottom_
    }
  }
  def verticalWord(canvas:Canvas,startx:Int,starty:Int,text:String,furigana:String,margin_left_furigana:Int,prev_furigana_bottom:Int):(Int,Int) = {
    val (y,width,height) = drawVertical(paint,canvas,startx,starty,text,(paint.getTextSize*SPACE_V).toInt)
    var new_furigana_bottom = prev_furigana_bottom
    if(show_furigana && !TextUtils.isEmpty(furigana)){
      val span_v = (paint_furigana.getTextSize*SPACE_V_FURIGANA).toInt
      val (_,_,this_height_wo) = measureBoundOneLine(furigana,paint_furigana)
      val this_height = this_height_wo + (furigana.length-1)*span_v
      val candidate_y1 = starty+height/2-this_height.toInt/2
      val candidate_y2 = (getHeight*(1-FURIGANA_BOTTOM_LIMIT)).toInt - this_height
      val sy = Math.max(prev_furigana_bottom, Math.min(candidate_y1,candidate_y2))

      val sx = (startx+Math.max(margin_left_furigana,width/2+paint_furigana.getTextSize/2+FURIGANA_MARGIN_LEFT_MIN)).toInt
      val(furigana_y,_,_) = drawVertical(paint_furigana,canvas,sx,sy,furigana,span_v)
      new_furigana_bottom = furigana_y
    }
    (y,new_furigana_bottom)
  }
  def drawVertical(paint:Paint,canvas:Canvas,startx:Int,starty:Int,text:String,span:Int) = {
    var y = starty
    paint.setTextAlign(Paint.Align.CENTER)
    var width = Int.MinValue
    for(t <- text){
      val r = new Rect()
      paint.getTextBounds(t.toString,0,1,r)
      width = Math.max(r.right-r.left,width)
      val yy = (y - r.top).toInt
      if(render_with_path){
        val path = new Path()
        paint.getTextPath(t.toString,0,1,startx,yy,path)
        path.close
        canvas.drawPath(path,paint)
      }else{
        canvas.drawText(t.toString,startx,yy,paint)
      }
      y += r.bottom - r.top + span
      if(Globals.IS_DEBUG){
        val paint_debug = new Paint()
        paint_debug.setStyle(Paint.Style.STROKE)
        paint_debug.setColor(Color.RED)
        paint_debug.setStrokeWidth(3)
        val r_l = startx-(r.right-r.left)/2
        val r_t = yy+r.top
        val r_r = startx+(r.right-r.left)/2
        val r_b = yy+r.bottom
        canvas.drawRect(r_l,r_t,r_r,r_b,paint_debug)
        val screen_range = if(paint.hashCode == paint_furigana.hashCode){screen_range_furi}else{screen_range_main}
        screen_range.left = Math.min(r_l,screen_range.left)
        screen_range.top = Math.min(r_t,screen_range.top)
        screen_range.right = Math.max(r_r,screen_range.right)
        screen_range.bottom = Math.max(r_b,screen_range.bottom)
      }
    }
    (y,width,y-starty-span)
  }

  def updateCurNum(){
    var fn = getId match {
      case R.id.yomi_info_view_prev => -1
      case R.id.yomi_info_view_next => 1
      case _ => 0
    }
    if(!Utils.readCurNext(context)){
      fn += 1
    }
    cur_num = if(Utils.isRandom && (Array(2,-1).contains(fn) || !Utils.readCurNext(context) && fn == 0)){
      None
    }else{
      FudaListHelper.getOrQueryFudaNumToRead(context,fn)
    }
    show_author = Globals.prefs.get.getBoolean("yomi_info_author",false)
    show_kami = Globals.prefs.get.getBoolean("yomi_info_kami",true)
    show_simo = Globals.prefs.get.getBoolean("yomi_info_simo",true)
    show_furigana = Globals.prefs.get.getString("yomi_info_furigana","None") != "None"
    paint.setTypeface(TypefaceManager.get(context,Globals.prefs.get.getString("show_yomi_info","None")))
    paint_furigana.setTypeface(TypefaceManager.get(context,Globals.prefs.get.getString("yomi_info_furigana","None")))
  }

  override def onDraw(canvas:Canvas){
    super.onDraw(canvas)

    if(Globals.IS_DEBUG){
      val paint_debug = new Paint()
      paint_debug.setStyle(Paint.Style.STROKE)
      paint_debug.setStrokeWidth(3)
      paint_debug.setColor(Color.GREEN)
      canvas.drawRect((getWidth*MARGIN_LR).toInt,(getHeight*MARGIN_TOP.min).toInt,
        (getWidth*(1.0-MARGIN_LR)).toInt,(getHeight*(1-MARGIN_BOTTOM)).toInt,paint_debug)
      paint_debug.setColor(Color.CYAN)
      canvas.drawRect((getWidth*MARGIN_LR).toInt,(getHeight*FURIGANA_TOP_LIMIT).toInt,
        (getWidth*(1.0-MARGIN_LR)).toInt,(getHeight*(1-FURIGANA_BOTTOM_LIMIT)).toInt,paint_debug)
      val ni = {_:Unit=>new Rect(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE)}
      screen_range_main = ni()
      screen_range_furi = ni()
    }
    cur_num.foreach{num =>
      val furigana_width_conf_default = context.getResources.getInteger(R.integer.yomi_info_furigana_width_default)
      val furigana_width_conf_max = context.getResources.getInteger(R.integer.yomi_info_furigana_width_max)
      val furigana_width_conf_cur = Globals.prefs.get.getInt("yomi_info_furigana_width",furigana_width_conf_default)

      val full = AllFuda.list_full(num).split(" ").zip(MARGIN_TOP)
      
      var text_array_with_margin = (if(show_author){AllFuda.author(num).split(" ").zip(MARGIN_AUTHOR)}else{new Array[(String,Double)](0)}) ++
        (if(show_kami){full.take(3)}else{new Array[(String,Double)](0)}) ++
        (if(show_simo){full.takeRight(2)}else{new Array[(String,Double)](0)})

      if(text_array_with_margin.isEmpty){
        return
      }

      if(show_author && ! show_kami && ! show_simo){
        // if only author then vertical center since the furigana will sometimes be larger than poem text
        val mint = text_array_with_margin.map{_._2}.min
        text_array_with_margin = text_array_with_margin.map{case(t,m)=>(t,m-mint+MARGIN_BOTTOM)}
      }

      val space_boost1 = if(show_furigana){
        1.3
      }else{
        1.0
      }
      val space_boost2 = if(show_furigana && furigana_width_conf_cur > furigana_width_conf_default){
        1.0 + 0.4*(furigana_width_conf_cur - furigana_width_conf_default)/(furigana_width_conf_max - furigana_width_conf_default).toDouble
      }else{
        1.0
      }

      val space_boost3 = text_array_with_margin.length match {
        case 1 => 1.4
        case 2 => 1.3
        case 3 => 1.2
        case 4 => 1.1
        case 5 => 1.0
        case 6 => 0.9
        case 7 => 0.8
        case _ => 0.8
      }

      val furigana_ratio = if(show_furigana && furigana_width_conf_cur < furigana_width_conf_default){
        1.0 - 0.4*(1.0 - (furigana_width_conf_cur/furigana_width_conf_default.toDouble))
      }else{
        1.0
      }
      val space_h = SPACE_H * space_boost1 * space_boost2 * space_boost3

      val no_furigana = text_array_with_margin.map{case(t,m)=>(AllFuda.removeInsideParens(t),m)}
      val text_size = calculateTextSize(no_furigana,paint,space_h)
      paint.setTextSize(text_size)

      val (actual_width_ave,_,_) = measureBoundAve(no_furigana.map{case(t,m)=>t},paint)
      val (actual_width_max,height_char_max,_) = measureBoundMax(no_furigana.map{case(t,m)=>t},paint)
      
      // When hardware acceleration is enabled, trying to render big text causes exception:
      //  E/OpenGLRenderer(16754): Font size too large to fit in cache. width, height = ...
      // Reading libs/hwui/FontRenderer.cpp we found that this occurs when following equation holds:
      //  glyph.fHeight + TEXTURE_BORDER_SIZE * 2 > DEFAULT_TEXT_LARGE_CACHE_HEIGHT
      //    where TEXTURE_BORDER_SIZE = 1 and DEFAULT_TEXT_LARGE_CACHE_HEIGHT = 512
      // so we use getTextPath and drawPath instead of drawText when this equation holds.
      // Note: since height_char_max is not accurate, we consider the threshold a litte bit smaller.
      render_with_path = height_char_max >= 496

      val span_h = space_h*getWidth
      var rowspan = (span_h+actual_width_ave).toInt
      var startx = (getWidth/2 + (rowspan*(text_array_with_margin.length-1))/2).toInt
      var margin_left_furigana = 0.0
      if(show_furigana){
        val furisize_tmp = (getWidth/text_array_with_margin.length)/4 // this must be close to result text size
        paint_furigana.setTextSize(furisize_tmp)
        val only_furigana = text_array_with_margin.map{case(t,m)=>AllFuda.onlyInsideParens(t)}
        val (actual_width_furigana,_,furigana_height_max) = measureBoundMax(only_furigana,paint_furigana)
        val actual_ratio_furigana = furisize_tmp / actual_width_furigana.toFloat
        val candidate_w = span_h.toFloat*actual_ratio_furigana*furigana_ratio.toFloat*FURIGANA_RATIO_DEFAULT

        val candidate_h = furisize_tmp * (1-FURIGANA_TOP_LIMIT-FURIGANA_BOTTOM_LIMIT)*getHeight / (furisize_tmp* SPACE_V_FURIGANA * (only_furigana.map{_.length-1}).max + furigana_height_max)
        paint_furigana.setTextSize(Math.min(candidate_w,candidate_h).toFloat)
        margin_left_furigana = rowspan / 2.0
        startx -= (span_h / 2.0).toInt
      }
      for((t,m) <- text_array_with_margin){
        val starty = (getHeight * m).toInt
        verticalText(canvas,startx,starty,t,margin_left_furigana.toInt)
        startx -= rowspan
      }
      if(Globals.IS_DEBUG){
        for(screen_range <- Array(screen_range_main,screen_range_furi) if screen_range.left != Integer.MAX_VALUE){
          val (name,t_top,t_bottom) = if(screen_range.hashCode == screen_range_furi.hashCode){
            ("screen_range_furi",FURIGANA_TOP_LIMIT,FURIGANA_BOTTOM_LIMIT)
          }else{
            ("screen_range_main",MARGIN_TOP.min,MARGIN_BOTTOM)
          }
          val margin_l = screen_range.left / getWidth.toDouble
          val margin_t = screen_range.top / getHeight.toDouble
          val margin_r = (getWidth - screen_range.right) / getWidth.toDouble
          val margin_b = (getHeight - screen_range.bottom) / getHeight.toDouble
          Log.v("wasuramoti_debug",String.format("%s,n=%d,l=%.3f t=%.3f r=%.3f b=%.3f",
            name,
            new java.lang.Integer(num),
            new java.lang.Double(margin_l),
            new java.lang.Double(margin_t),
            new java.lang.Double(margin_r),
            new java.lang.Double(margin_b)))
          if(margin_l < MARGIN_LR*0.9){
            Log.w("wasuramoti_debug",name + ",n=" + num + " => margin_l is too small")
          }
          if(margin_r < MARGIN_LR*0.9){
            Log.w("wasuramoti_debug",name + ",n=" + num + " => margin_r is too small")
          }
          if(margin_t < t_top*0.9){
            Log.w("wasuramoti_debug",name + ",n=" + num + " => margin_t is too small")
          }
          if(margin_b < t_bottom*0.9){
            Log.w("wasuramoti_debug",name + ",n=" + num + " => margin_b is too small")
          }
        }
      }
    }
  }
}

