package karuta.hpnpwd.wasuramoti

import android.app.Activity
import android.content.{Intent,IntentFilter,Context,DialogInterface,BroadcastReceiver}
import android.os.{Bundle,Handler}
import android.util.{Base64,TypedValue}
import android.view.animation.{AnimationUtils,Interpolator}
import android.view.{View,Menu,MenuItem,WindowManager,ViewStub}
import android.widget.{ImageView,Button,RelativeLayout,TextView,LinearLayout,Toast}

import android.support.v7.app.{AppCompatActivity,ActionBar,AlertDialog}

import org.json.{JSONTokener,JSONObject}

import scala.collection.mutable

import karuta.hpnpwd.audio.{OggVorbisDecoder,OpenSLESPlayer}

class WasuramotiActivity extends AppCompatActivity
    with ActivityDebugTrait with CommonDialog.DialogStateHandler
    with CommonDialog.CustomDialog with CommonDialog.CallbackListener
    with RequirePermission.OnRequirePermissionCallback
    {
  val MINUTE_MILLISEC = 60000
  var haseo_count = 0
  var release_lock = None:Option[()=>Unit]
  var run_dimlock = None:Option[Runnable]
  var run_refresh_text = None:Option[Runnable]
  val handler = new Handler()
  var bar_poem_info_num = None:Option[Int]
  var broadcast_receiver = None:Option[BroadcastReceiver]

  override def onNewIntent(intent:Intent){
    super.onNewIntent(intent)
    // Since Android core system cannot determine whether or not the new intent is important for us,
    // We have to set intent by our own.
    // We can rely on fact that onResumeFragments() is called after onNewIntent()
    setIntent(intent)
  }
  def handleActionView(){
    val intent = getIntent
    // Android 2.x sends same intent at onResumeFragments() even after setIntent() is called if resumed from shown list where home button is long pressed.
    // Therefore we check FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY flag to distinguish it.
    if(intent == null ||
      intent.getAction != Intent.ACTION_VIEW ||
      (intent.getFlags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) > 0
    ){
      return
    }
    val dataString = intent.getDataString
    // we don't need the current intent anymore so replace it with default intent so that getIntent returns plain intent.
    setIntent(new Intent(this,this.getClass))
    // There was some crash error that throws NullPointerException here,
    // I could not find the reason, but simply ignore when dataString is null
    if(dataString == null){
      return
    }
    dataString.replaceFirst("wasuramoti://","").split("/")(0) match {
      case "fudaset" => importFudaset(dataString)
      case "from_oom" => CommonDialog.messageDialog(this,Right(R.string.from_oom_message))
      case m => CommonDialog.messageDialog(this,Left(s"'${m}' is not correct intent data for ACTION_VIEW for wasuramoti"))
    }
  }

  def reloadFragment(){
    val fragment = WasuramotiFragment.newInstance(true)
    getSupportFragmentManager.beginTransaction.replace(R.id.activity_placeholder, fragment).commit
  }

  def importFudaset(dataString:String){
    try{
      val data = dataString.split("/").last
      val bytes = Base64.decode(data,Base64.URL_SAFE)
      val jsonString = new String(bytes,"UTF-8")
      val obj = new JSONTokener(jsonString).nextValue.asInstanceOf[JSONObject]
      val title = obj.keys.next.asInstanceOf[String]
      val ar = obj.getJSONArray(title)
      val bundle = new Bundle
      bundle.putString("tag","import_fudaset")
      bundle.putString("json",jsonString)
      CommonDialog.confirmDialogWithCallback(this,
        Left(getResources.getString(R.string.confirm_action_view_fudaset,title,new java.lang.Integer(ar.length))),
        bundle)
    }catch{
      case e:Exception => {
        val msg = getResources.getString(R.string.confirm_action_view_fudaset_fail) + "\n" + e.toString
        CommonDialog.messageDialog(this,Left(msg))
      }
    }
  }

  def restartRefreshTimer(){
    Globals.global_lock.synchronized{
      run_refresh_text.foreach(handler.removeCallbacks(_))
      run_refresh_text = None
      if(NotifyTimerUtils.notify_timers.nonEmpty){
        run_refresh_text = Some(new Runnable(){
          override def run(){
            if(NotifyTimerUtils.notify_timers.isEmpty){
              run_refresh_text.foreach(handler.removeCallbacks(_))
              run_refresh_text = None
              return
            }
            setButtonTextByState()
            run_refresh_text.foreach{handler.postDelayed(_,MINUTE_MILLISEC)}
          }
        })
        run_refresh_text.foreach{_.run()}
      }
    }
  }

  def setButtonText(txt:String){
     runOnUiThread(new Runnable(){
      override def run(){
        Option(findViewById[Button](R.id.read_button)).foreach{ read_button =>
          val lines = txt.split("\n")
          val max_chars = lines.map{x=>Utils.measureStringWidth(x)}.max
          if(YomiInfoUtils.showPoemText){
            val res_id = if(lines.length >= 4 || max_chars >= 18){
              R.dimen.read_button_text_small
            }else{
              R.dimen.read_button_text_normal
            }
            read_button.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources.getDimension(res_id))
          }
          read_button.setMinLines(lines.length)
          read_button.setMaxLines(lines.length+1) // We accept exceeding one row
          read_button.setText(txt)
        }
      }
    })
  }

  def setButtonTextByState(fromAuto:Boolean = false, invalidateQueryCacheExceptKarafuda:Boolean = false){
    val txt =
      if(NotifyTimerUtils.notify_timers.nonEmpty){
        NotifyTimerUtils.makeTimerText(getApplicationContext)
      }else if(Globals.is_playing && KarutaPlayUtils.have_to_mute){
        getResources.getString(R.string.muted_of_audio_focus)
      }else{
        if(invalidateQueryCacheExceptKarafuda){
          FudaListHelper.invalidateQueryCacheExceptKarafuda()
        }
        FudaListHelper.makeReadIndexMessage(getApplicationContext,fromAuto)
      }
    setButtonText(txt)
  }

  def refreshAndSetButton(force:Boolean = false, fromAuto:Boolean = false, nextRandom:Option[Int] = None){
    Globals.global_lock.synchronized{
      Globals.player = AudioHelper.refreshKarutaPlayer(this, Globals.player, force, fromAuto, nextRandom)
      setButtonTextByState(fromAuto)
    }
  }

  def refreshAndInvalidate(fromAuto:Boolean = false){
    refreshAndSetButton(fromAuto = fromAuto)
    invalidateYomiInfo()
  }

  override def onCreateOptionsMenu(menu: Menu):Boolean = {
    getMenuInflater.inflate(R.menu.main, menu)
    super.onCreateOptionsMenu(menu)
  }

  def showShuffleDialog(){
    val bundle = new Bundle
    bundle.putString("tag","do_shuffle")
    CommonDialog.confirmDialogWithCallback(this,Right(R.string.menu_shuffle_confirm),bundle)
  }

  override def onOptionsItemSelected(item: MenuItem):Boolean = {
    KarutaPlayUtils.cancelAllPlay()
    setButtonTextByState()
    item.getItemId match {
      case R.id.menu_shuffle => {
        showShuffleDialog()
      }
      case R.id.menu_move => {
        val dlg = new MovePositionDialog()
        dlg.show(getSupportFragmentManager,"move_position")
      }
      case R.id.menu_timer => startActivity(new Intent(this,classOf[NotifyTimerActivity]))
      case R.id.menu_play_history => {
        val html = PlayHistoryDialog.genHtml(this)
        CommonDialog.generalHtmlDialog(this,html)
      }
      case R.id.menu_quick_conf => {
        val dlg = new QuickConfigDialog()
        dlg.show(getSupportFragmentManager,"quick_config")
      }
      case R.id.menu_conf => startActivity(new Intent(this,classOf[PrefActivity]))
      case android.R.id.home => {
        // android.R.id.home will be returned when the Application Icon is clicked if we are using android.support.v7.app.ActionBarActivity
      }

      case _ => {}
    }
    return true
  }
  def haseoCounter(){
    if(haseo_count < 3){
      haseo_count += 1
    }else{
      val layout = new RelativeLayout(this)
      val builder = new AlertDialog.Builder(this)
      val iv = new ImageView(this)
      iv.setImageResource(R.drawable.hasewo)
      iv.setAdjustViewBounds(true)
      iv.setScaleType(ImageView.ScaleType.FIT_XY)
      val metrics = getResources.getDisplayMetrics
      val maxw = metrics.widthPixels
      val maxh = metrics.heightPixels
      val width = iv.getDrawable.getIntrinsicWidth
      val height = iv.getDrawable.getIntrinsicHeight
      val ratio = width.toDouble/height.toDouble
      val OCCUPY_IN_SCREEN = 0.9
      val Array(tw,th) = (if(maxw/ratio < maxh){
        Array(maxw,maxw/ratio)
      }else{
        Array(maxh*ratio,maxh)
      })
      val Array(neww,newh) = (for (i <- Array(tw,th))yield (i*OCCUPY_IN_SCREEN).toInt)
      val params = new RelativeLayout.LayoutParams(neww,newh)
      params.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
      iv.setLayoutParams(params)
      layout.addView(iv)
      builder.setView(layout)
      val dialog = builder.create
      dialog.show
      // we have to get attributes after show()
      val dparams = dialog.getWindow.getAttributes
      dparams.height = newh
      dparams.width = neww
      dialog.getWindow.setAttributes(dparams)
      haseo_count = 0
    }
  }

  def setCustomActionBar(){
    val actionbar = getSupportActionBar
    val actionview = getLayoutInflater.inflate(R.layout.actionbar_custom,null)
    val brc = actionview.findViewById[View](R.id.actionbar_blue_ring_container)
    if(brc != null){
      brc.setOnClickListener(new View.OnClickListener(){
        override def onClick(v:View){
          haseoCounter()
        }
      })
    }
    actionbar.setCustomView(actionview)
    actionbar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,ActionBar.DISPLAY_SHOW_CUSTOM)

    val bar_container = actionview.findViewById[ViewStub](R.id.yomi_info_bar_container)
    val show_kimari  = Globals.prefs.get.getBoolean("yomi_info_show_bar_kimari",true)
    val show_poem_num  = Globals.prefs.get.getBoolean("yomi_info_show_bar_poem_num",true)
    if(bar_container != null &&
      YomiInfoUtils.showPoemText && (show_kimari || show_poem_num)
    ){
      val inflated = bar_container.inflate()
      Option(inflated.findViewById[View](R.id.command_button_kimariji_container)).foreach{v =>
        v.setVisibility(if(show_kimari){View.VISIBLE}else{View.GONE})
      }
      Option(inflated.findViewById[View](R.id.command_button_poem_num_container)).foreach{v =>
        v.setVisibility(if(show_poem_num){View.VISIBLE}else{View.GONE})
      }
      actionbar.setDisplayShowTitleEnabled(false)
      actionbar.setDisplayShowHomeEnabled(false)
    }else{
      actionbar.setDisplayShowTitleEnabled(true)
      actionbar.setDisplayShowHomeEnabled(true)
    }
  }

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    Utils.initGlobals(getApplicationContext)

    OggVorbisDecoder.loadLibrary(getApplicationContext)

    // TODO: only load when "use_opensles" option is set
    OpenSLESPlayer.loadLibrary(getApplicationContext)

    setTheme(ColorThemeHelper.getFromPref.styleId)

    setContentView(R.layout.main_activity)
    
    // since onResumeFragments is always called after onCreate, we don't have to set have_to_resume_task = true
    val fragment = WasuramotiFragment.newInstance(false)

    getSupportFragmentManager.beginTransaction.replace(R.id.activity_placeholder, fragment).commit
    getSupportActionBar.setHomeButtonEnabled(true)
    if(Globals.IS_DEBUG){
      setTitle(getResources().getString(R.string.app_name) + " DEBUG")
      val layout = getWindow.getDecorView.findViewWithTag[LinearLayout]("main_linear_layout")
      val view = new TextView(this)
      view.setTag("main_debug_info")
      view.setContentDescription("MainDebugInfo")
      layout.addView(view)
    }
    RequirePermission.addFragment(getSupportFragmentManager,
      R.string.read_external_storage_permission_denied,
      R.string.read_external_storage_permission_denied_forever
      )
  }
  def showProgress(){
    val v = getSupportActionBar.getCustomView
    if(v!=null){
      val ring = v.findViewById[View](R.id.actionbar_blue_ring)
      if(ring!=null){
        val rotation = AnimationUtils.loadAnimation(getApplicationContext,R.anim.rotator)
        rotation.setInterpolator(new Interpolator(){
            override def getInterpolation(input:Float):Float={
              return (input*8.0f).toInt/8.0f
            }
          })
        ring.setVisibility(View.VISIBLE)
        ring.startAnimation(rotation)
      }
    }
  }
  def hideProgress(){
    val v = getSupportActionBar.getCustomView
    if(v!=null){
      val ring = v.findViewById[View](R.id.actionbar_blue_ring)
      if(ring!=null){
        ring.clearAnimation()
        ring.setVisibility(View.INVISIBLE)
      }
    }
  }
  def invalidateYomiInfo(){
    if(!YomiInfoUtils.showPoemText){
      return
    }
    val yomi_info = findViewById[YomiInfoLayout](R.id.yomi_info)
    if(yomi_info != null){
      yomi_info.invalidateAndScroll()
    }
  }

  // There was user report that "Poem text differs with actually played audio".
  // Therefore we periodically check whether poem text and audio queue are same,
  // and set poem text if differs.
  def checkConsistencyBetweenPoemTextAndAudio(){
    Globals.player.foreach{ player =>
      val aq = player.audio_queue.collect{ case Left(w) => Some(w.num) }.flatten.distinct.toList
      if(aq.isEmpty){
        return
      }
      val yomi_info = findViewById[YomiInfoLayout](R.id.yomi_info)
      if(yomi_info == null){
        return
      }
      val sx = yomi_info.getScrollX
      val cur_view = Array(R.id.yomi_info_view_cur,R.id.yomi_info_view_next,R.id.yomi_info_view_prev).view
        .flatMap{ x => Option(yomi_info.findViewById[YomiInfoView](x)) }
        .find( _.getLeft == sx ) // only get current displayed YomiInfoView, which scroll ended
      if(cur_view.isEmpty){
        return
      }
      lazy val next_view =
        yomi_info.getNextViewId(cur_view.get.getId).flatMap{
          vid => Option(yomi_info.findViewById[YomiInfoView](vid))
        }
      val vq = List(cur_view.flatMap{_.rendered_num},
        if(aq.length > 1){
          next_view.flatMap{_.rendered_num}
        }else{
          None
        }
      ).flatten
      if(vq != aq){
        aq.zip(List(cur_view,next_view).flatten).foreach{ case (num,vw) =>
          vw.updateCurNum(Some(num))
          vw.invalidate()
        }
      }
      if(bar_poem_info_num.exists(_ != aq.head)){
        cur_view.foreach{ c =>
          c.updateCurNum(Some(aq.head))
          updatePoemInfo(c.getId)
        }
      }
    }
  }

  def updatePoemInfo(cur_view:Int){
    val yomi_cur = findViewById[YomiInfoView](cur_view)
    if(yomi_cur != null){
      val fudanum = yomi_cur.cur_num
      bar_poem_info_num = fudanum
      for(
           main <- Option(getSupportFragmentManager.findFragmentById(R.id.activity_placeholder).asInstanceOf[WasuramotiFragment]);
           yomi_dlg <- Option(main.getChildFragmentManager.findFragmentById(R.id.command_button_fragment).asInstanceOf[CommandButtonPanel])
       ){
             yomi_dlg.setFudanum(fudanum)
       }
      val cv = getSupportActionBar.getCustomView
      if(cv != null){
        val show_kimari  = Globals.prefs.get.getBoolean("yomi_info_show_bar_kimari",true)
        val show_poem_num  = Globals.prefs.get.getBoolean("yomi_info_show_bar_poem_num",true)
        if(show_kimari || show_poem_num){
          val (fudanum_s,kimari_s) = CommandButtonPanel.getFudaNumAndKimari(this,fudanum)
          if(show_poem_num){
            Option(cv.findViewById[TextView](R.id.command_button_poem_num)).foreach{ tv =>
              tv.setText(fudanum_s)
            }
          }
          if(show_kimari){
            Option(cv.findViewById[TextView](R.id.command_button_kimariji)).foreach{ tv =>
              tv.setText(kimari_s)
            }
          }
        }
      }
    }
  }

  def getCurNumInView():Option[Int] = {
    Option(findViewById[YomiInfoView](R.id.yomi_info_view_cur)).flatMap{_.cur_num}
  }

  def scrollYomiInfo(id:Int,smooth:Boolean,do_after_done:Option[()=>Unit]=None){
    if(!YomiInfoUtils.showPoemText){
      return
    }
    val yomi_info = findViewById[YomiInfoLayout](R.id.yomi_info)
    if(yomi_info != null){
      yomi_info.scrollToView(id,smooth,false,do_after_done)
    }
  }

  override def onStart(){
    super.onStart()
    if( Globals.prefs.isEmpty ){
      // onCreate returned before loading preference
      return
    }
  }

  // code which have to be done:
  // (a) after reloadFragment() or in onResumeFragments() ... put it inside WasuramotiActivity.doWhenResume()
  // (b) after reloadFragment() or after onCreate() ... put it inside WasuramotiFragment.onViewCreated()
  // (c) only in onResumeFragments() ... put it inside WasuramotiActivity.onResumeFragments()
  def doWhenResume(){
    Globals.global_lock.synchronized{
      if(Globals.forceRefreshPlayer){
        KarutaPlayUtils.replay_audio_queue = None
      }
      if(Globals.player.isEmpty || Globals.forceRefreshPlayer){
        if(! Utils.readFirstFuda && FudaListHelper.getCurrentIndex(this) <=0 ){
          FudaListHelper.moveToFirst(this)
        }
        Globals.player = AudioHelper.refreshKarutaPlayer(this,Globals.player,false)
      }
    }
    setButtonTextByState()
    if(Globals.player.forall(!_.is_replay)){
      invalidateYomiInfo()
    }else{
      Globals.player.foreach{ p =>
        KarutaPlayUtils.replay_audio_queue.foreach{ q =>
          p.forceYomiInfoView(q)
        }
      }
    }
    setVolumeControlStream(Utils.getAudioStreamType(this))
    KarutaPlayUtils.setReplayButtonEnabled(this,
      if(Globals.is_playing){
        Some(false)
      }else{
        None
      }
    )
    KarutaPlayUtils.setSkipButtonEnabled(this)
    KarutaPlayUtils.setRewindButtonEnabled(this)
  }

  def genBroadcastReceiver():BroadcastReceiver = {
    new BroadcastReceiver(){
        override def onReceive(c:Context,i:Intent){
          setButtonTextByState()
        }
      }
  }

  override def onResumeFragments(){
    super.onResumeFragments()
    if( Globals.prefs.isEmpty ){
      // onCreate returned before loading preference
      return
    }
    if(Globals.forceRestart){
      Globals.forceRestart = false
      Utils.restartActivity(this)
    }
    if(Globals.forceReloadUI){
      Globals.forceReloadUI = false
      reloadFragment()
    }else{
      doWhenResume()
    }
    Globals.global_lock.synchronized{
      Globals.player.foreach{ p =>
        // When screen is rotated, the activity is destroyed and new one is created.
        // Therefore, we have to reset the KarutaPlayer's activity
        p.activity = this
      }
    }
    handleActionView()
    restartRefreshTimer()
    startDimLockTimer()
    Globals.prefs.foreach{ p =>
      if(!p.contains("intended_use") && getSupportFragmentManager.findFragmentByTag("intended_use_dialog") == null){
        IntendedUseDialog.newInstance(true).show(getSupportFragmentManager,"intended_use_dialog")
      }
    }
    if(NotifyTimerUtils.notify_timers.nonEmpty){
      val brc = genBroadcastReceiver
      registerReceiver(brc,new IntentFilter(Globals.ACTION_NOTIFY_TIMER_FIRED))
      broadcast_receiver = Some(brc)
    }
  }
  override def onPause(){
    super.onPause()
    release_lock.foreach(_())
    release_lock = None
    run_dimlock.foreach(handler.removeCallbacks(_))
    run_dimlock = None
    run_refresh_text.foreach(handler.removeCallbacks(_))
    run_refresh_text = None
    broadcast_receiver.foreach{ brc =>
      unregisterReceiver(brc)
      broadcast_receiver = None
    }
  }
  override def onStop(){
    super.onStop()
    Utils.cleanProvidedFile(this,false)
  }

  def startDimLockTimer(){
    Globals.global_lock.synchronized{
      release_lock.foreach(_())
      getWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      release_lock = {
          Some( () => {
            getWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          })
      }
      rescheduleDimLockTimer()
    }
  }

  def rescheduleDimLockTimer(millisec:Option[Long]=None){
    val DEFAULT_DIMLOCK_MINUTES = PrefManager.getPrefDefault[Long](this, PrefKeyNumeric.DimlockMinutes)
    Globals.global_lock.synchronized{
      run_dimlock.foreach(handler.removeCallbacks(_))
      run_dimlock = None
      var dimlock_millisec = millisec.getOrElse({
        MINUTE_MILLISEC * PrefManager.getPrefNumeric[Long](this, PrefKeyNumeric.DimlockMinutes)
      })
      // if dimlock_millisec overflows then set default value
      if(dimlock_millisec < 0){
        dimlock_millisec = DEFAULT_DIMLOCK_MINUTES * MINUTE_MILLISEC
      }
      run_dimlock = Some(new Runnable(){
        override def run(){
          release_lock.foreach(_())
          release_lock = None
        }
      })
      run_dimlock.foreach{handler.postDelayed(_,dimlock_millisec)}
    }
  }

  def onMainButtonClick(v:View) {
    doPlay(from_main_button=true)
  }
  def moveToNextFuda(showToast:Boolean = true, fromAuto:Boolean = false){
    val context = getApplicationContext
    val is_shuffle = ! Utils.isRandom
    if(is_shuffle){
      FudaListHelper.moveNext(context)
    }
    // In random mode, there is a possibility that same pairs of fuda are read in a row.
    // In that case, if we do not show "now loading" message, the user can know that same pairs are read.
    // Therefore we give force flag to true for refreshAndSetButton.
    this.refreshAndSetButton(!is_shuffle,fromAuto)
    if(Utils.readCurNext(context)){
      invalidateYomiInfo()
    }
    if(showToast && PrefManager.getPrefBool(context,PrefKeyBool.ShowMessageWhenMoved)){
      Toast.makeText(getApplicationContext,R.string.message_when_moved,Toast.LENGTH_SHORT).show()
    }
  }

  override def customCommonDialog(bundle:Bundle, builder:AlertDialog.Builder){
    bundle.getString("tag") match {
      case "all_read_done" =>
        builder.setNeutralButton(R.string.menu_shuffle, new DialogInterface.OnClickListener(){
          override def onClick(dialog:DialogInterface, which:Int){
            showShuffleDialog()
          }
        })
      case "volume_alert_confirm" =>
         builder.setTitle(R.string.conf_volume_alert) 
      case "ringer_mode_alert_confirm" =>
         builder.setTitle(R.string.conf_ringer_mode_alert) 
      case _ =>
        // Do Nothing
    }
  }

  override def onCommonDialogCallback(bundle:Bundle){
    bundle.getString("tag") match {
      case "all_read_done" =>
        None
      case "already_reported_expection" =>
        throw new AlreadyReportedException(bundle.getString("error_message"))
      case "karutaplay_rewind" =>
        val includeCur = bundle.getBoolean("include_cur")
        FudaListHelper.rewind(this,includeCur)
        refreshAndInvalidate()
      case "do_shuffle" =>
        FudaListHelper.shuffleAndMoveToFirst(getApplicationContext())
        refreshAndInvalidate()
      case "volume_alert_confirm" => Globals.global_lock.synchronized{
        val cur_time = java.lang.System.currentTimeMillis
        KarutaPlayUtils.last_confirmed_for_volume = Some(cur_time)
        if(bundle.getBoolean("checked")){
          val edit = Globals.prefs.get.edit
          edit.putBoolean(PrefKeyBool.VolumeAlert.key,false)
          edit.commit()
        }
        if(bundle.getInt("which") == DialogInterface.BUTTON_POSITIVE){
          val playBundle = bundle.getBundle("play_bundle")
          val fromAuto = bundle.getBoolean("from_auto")
          val fromSwipe = bundle.getBoolean("from_swipe")
          Globals.player.get.playWithoutConfirm(playBundle,fromAuto,fromSwipe)
        }
      }
      case "ringer_mode_alert_confirm" => Globals.global_lock.synchronized{
        val cur_time = java.lang.System.currentTimeMillis
        KarutaPlayUtils.last_confirmed_for_ringer_mode = Some(cur_time)
        if(bundle.getBoolean("checked")){
          val edit = Globals.prefs.get.edit
          edit.putBoolean(PrefKeyBool.RingerModeAlert.key,false)
          edit.commit()
        }
        if(bundle.getInt("which") == DialogInterface.BUTTON_POSITIVE){
          val playBundle = bundle.getBundle("play_bundle")
          val fromAuto = bundle.getBoolean("from_auto")
          val fromSwipe = bundle.getBoolean("from_swipe")
          Globals.player.get.playWithoutConfirm(playBundle,fromAuto,fromSwipe)
        }
      }
      case "import_fudaset" =>
        val jsonString = bundle.getString("json")
        val obj = new JSONTokener(jsonString).nextValue.asInstanceOf[JSONObject]
        val title = obj.keys.next.asInstanceOf[String]
        val ar = obj.getJSONArray(title)
        var count = 0
        val res = (0 until ar.length).map{ i =>
          val o = ar.get(i).asInstanceOf[JSONObject]
          val name = o.keys.next.asInstanceOf[String]
          val n = BigInt(o.getString(name),36)
          val a = mutable.Buffer[Int]()
          for(j <- 0 until n.bitLength){
            if ( ((n >> j) & 1) == 1 ){
              a += j + 1
            }
          }
          val r = TrieUtils.makeKimarijiSetFromNumList(a.toList).exists{
            case (kimari,st_size) =>
              Utils.writeFudaSetToDB(this,name,kimari,st_size)
          }
          (if(r){count+=1;"[OK]"}else{"[NG]"}) + " " + name
        }
        val msg = getResources.getString(R.string.confirm_action_view_fudaset_done,new java.lang.Integer(count)) +
        "\n" + res.mkString("\n")
        CommonDialog.messageDialog(this,Left(msg))
      case "quicklang_dialog" =>
        val position = bundle.getInt("position")
        val lang = Utils.YomiInfoLang.values.toArray.apply(position)
        val edit = Globals.prefs.get.edit
        YomiInfoUtils.showPoemTextAndTitleBar(edit)
        edit.putString("yomi_info_default_lang",lang.toString)
        if(lang == Utils.YomiInfoLang.Japanese){
          edit.putBoolean("yomi_info_show_translate_button",!Romanization.isJapanese(this))
        }else{
          edit.putBoolean("yomi_info_show_translate_button",true)
          edit.putBoolean("yomi_info_author",false)
        }
        edit.commit
        reloadFragment
    }
  }

  override def onRequirePermissionGranted(requestCode:Int){
    Globals.player = AudioHelper.refreshKarutaPlayer(this, Globals.player, true)
  }

  def doPlay(auto_play:Boolean = false, from_main_button:Boolean = false, from_swipe:Boolean = false){
    Globals.global_lock.synchronized{
      if(Globals.player.isEmpty){
        if(Globals.prefs.get.getBoolean("memorization_mode",false) &&
          FudaListHelper.getOrQueryNumbersToRead() == 0){
          CommonDialog.messageDialog(this,Right(R.string.all_memorized))
        }else if(FudaListHelper.allReadDone(this.getApplicationContext())){
          val bundle = new Bundle
          bundle.putString("tag", "all_read_done")
          CommonDialog.messageDialogWithCallback(this,Right(R.string.all_read_done),bundle)
        }else if(
          Utils.isExternalReaderPath(Globals.prefs.get.getString("reader_path",null))
          && RequirePermission.getFragment(getSupportFragmentManager)
            .checkRequestMarshmallowPermission(RequirePermission.REQ_PERM_MAIN_ACTIVITY)
          ){
          // do nothing since checkRequestMarshmallowPermission shows the dialog when permission denied
        }else if(Globals.player_none_reason.nonEmpty){
          CommonDialog.messageDialog(this,Left(Globals.player_none_reason.get))
        }else{
          CommonDialog.messageDialog(this,Right(R.string.player_none_reason_unknown))
        }
        return
      }
      val player = Globals.player.get
      KarutaPlayUtils.cancelAutoTimer()
      if(Globals.is_playing){
        val have_to_go_next = (
          from_main_button &&
          Globals.prefs.get.getBoolean("move_after_first_phrase",true) &&
          ! player.is_replay &&
          player.isAfterFirstPhrase
        )
        player.stop()
        if(have_to_go_next){
          moveToNextFuda()
        }else{
          setButtonTextByState()
        }
      }else{
        // TODO: if auto_play then turn off display
        if(!auto_play){
          startDimLockTimer()
        }
        val bundle = new Bundle()
        bundle.putBoolean("have_to_run_border",YomiInfoUtils.showPoemText && Utils.readCurNext(this.getApplicationContext))
        bundle.putString("fromSender",KarutaPlayUtils.SENDER_MAIN)
        player.play(bundle,auto_play,from_swipe)
      }
    }
  }
}

trait ActivityDebugTrait{
  self:WasuramotiActivity =>
  def showBottomInfo(key:String,value:String){
    if(Globals.IS_DEBUG){
      val btn = getWindow.getDecorView.findViewWithTag[TextView]("main_debug_info")
      val txt = (btn.getText.toString.split(";").map{_.split("=")}.collect{
        case Array(k,v)=>(k,v)
      }.toMap + ((key,value))).collect{case (k,v)=>k+"="+v}.mkString(";")
      btn.setText(txt)
    }
  }
  def showAudioLength(len:Long){
    if(Globals.IS_DEBUG){
      showBottomInfo("len",len.toString)
    }
  }
}

trait WasuramotiBaseTrait {
  self:Activity =>
  override def onOptionsItemSelected(item: MenuItem):Boolean = {
    item.getItemId match {
      case android.R.id.home => {
        // android.R.id.home will be returned when the Application Icon is clicked if we are using android.support.v7.app.ActionBarActivity
        self.finish()
      }
      case _ => {}
    }
    return true
  }
}
