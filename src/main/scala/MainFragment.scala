package karuta.hpnpwd.wasuramoti

import android.app.Activity
import android.os.{Bundle,Handler}
import android.support.v4.app.Fragment
import android.util.TypedValue
import android.view.{View,ViewStub,LayoutInflater,ViewGroup}
import android.widget.Button

import scala.collection.mutable


object WasuramotiFragment{
  def newInstance(have_to_resume_task:Boolean):WasuramotiFragment={
    val fragment = new WasuramotiFragment
    val args = new Bundle
    args.putBoolean("have_to_resume_task",have_to_resume_task)
    fragment.setArguments(args)
    return fragment
  }
}

class WasuramotiFragment extends Fragment{
  override def onCreateView(inflater:LayoutInflater,parent:ViewGroup,state:Bundle):View = {
    return inflater.inflate(R.layout.main_fragment, parent, false)
  }

  override def onViewCreated(root:View, state:Bundle){
    val was = getActivity.asInstanceOf[WasuramotiActivity]
    switchViewAndReloadHandler(was,root)
    was.setCustomActionBar()
    setLongClickYomiInfo(root)
    setLongClickButton(was,root)
    if(getArguments.getBoolean("have_to_resume_task")){
      was.doWhenResume()
    }
  }

  def switchViewAndReloadHandler(was:WasuramotiActivity, root:View){
    val read_button = root.findViewById(R.id.read_button).asInstanceOf[Button]
    val stub = root.findViewById(R.id.yomi_info_stub).asInstanceOf[ViewStub]
    if(YomiInfoUtils.showPoemText){
      stub.inflate()
      read_button.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources.getDimension(R.dimen.read_button_text_normal))
      read_button.setBackgroundResource(R.drawable.main_button)
    }

    val frag_stub = root.findViewById(R.id.yomi_info_search_stub).asInstanceOf[ViewStub]
    if(frag_stub != null &&
      YomiInfoUtils.showPoemText){
      frag_stub.inflate()
      val fragment = YomiInfoSearchDialog.newInstance(false,Some(0))
      getChildFragmentManager.beginTransaction.replace(R.id.yomi_info_search_fragment,fragment).commit
    }

    val replay_stub = root.findViewById(R.id.replay_last_button_stub).asInstanceOf[ViewStub]
    if(!YomiInfoUtils.showPoemText && Globals.prefs.get.getBoolean("show_replay_last_button",false)){
      replay_stub.inflate()
      val btn = root.findViewById(R.id.replay_last_button).asInstanceOf[Button]
      btn.setText(Utils.replayButtonText(getResources))
      btn.setOnClickListener(new View.OnClickListener(){
        override def onClick(v:View){
           KarutaPlayUtils.startReplay(was)
        }
      })
    }

  }
  def setLongClickYomiInfo(root:View){
    for(id <- Array(R.id.yomi_info_view_prev,R.id.yomi_info_view_cur,R.id.yomi_info_view_next)){
      val view = root.findViewById(id).asInstanceOf[YomiInfoView]
      if(view != null){
        view.setOnLongClickListener(
          new View.OnLongClickListener(){
            override def onLongClick(v:View):Boolean = {
              if(view.cur_num.nonEmpty){
                val dlg = YomiInfoSearchDialog.newInstance(true,view.cur_num)
                dlg.show(getChildFragmentManager,"yomi_info_search")
              }
              return true
            }
          }
        )
      }
    }
  }
  def setLongClickButton(was:WasuramotiActivity,root:View){
    val btn = root.findViewById(R.id.read_button).asInstanceOf[Button]
    if(btn != null){
      btn.setOnLongClickListener(
        if(Globals.prefs.get.getBoolean("skip_on_longclick",false)){
          new View.OnLongClickListener(){
            override def onLongClick(v:View):Boolean = {
              Globals.global_lock.synchronized{
                if(Globals.is_playing){
                  Globals.player.foreach{p=>
                    p.stop()
                    was.moveToNextFuda()
                    was.doPlay()
                  }
                }
              }
              return true
            }
          }
        }else{
          null
        }
      )
    }
  }

}
