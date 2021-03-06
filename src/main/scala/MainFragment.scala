package karuta.hpnpwd.wasuramoti

import android.os.Bundle
import android.support.v4.app.Fragment
import android.util.TypedValue
import android.view.{View,ViewStub,LayoutInflater,ViewGroup}
import android.widget.{Button,LinearLayout}

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
    if(getArguments.getBoolean("have_to_resume_task")){
      was.doWhenResume()
    }
  }

  def subButtonMargin(sub_buttons:View,inflated:View){
    // TODO: for API >= 11 you can use android:showDividers and android:divider instead
    // http://stackoverflow.com/questions/4259467/in-android-how-to-make-space-between-linearlayout-children
    val container = sub_buttons.asInstanceOf[LinearLayout]
    val params = inflated.getLayoutParams.asInstanceOf[LinearLayout.LayoutParams]
    val margin = Utils.dpToPx(4).toInt // 4dp
    container.getOrientation match {
      case LinearLayout.HORIZONTAL =>
        params.setMargins(margin,0,0,0) //left margin
      case LinearLayout.VERTICAL =>
        params.setMargins(0,margin,0,0) // top margin
    }
    inflated.setLayoutParams(params)
  }

  def switchViewAndReloadHandler(was:WasuramotiActivity, root:View){
    val read_button = root.findViewById[Button](R.id.read_button)
    val stub = root.findViewById[ViewStub](R.id.yomi_info_stub)
    if(YomiInfoUtils.showPoemText){
      stub.inflate()
      read_button.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources.getDimension(R.dimen.read_button_text_normal))
      // https://stackoverflow.com/questions/12104401/extract-the-background-color-drawable-from-a-typedvalue
      val typedValue = new TypedValue
      was.getTheme.resolveAttribute(R.attr.mainButtonDrawable, typedValue, true)
      val typedArray = was.obtainStyledAttributes(Array(android.R.attr.background))
      typedArray.getValue(0, typedValue)
      read_button.setBackgroundResource(typedValue.resourceId)
      typedArray.recycle()
    }

    val frag_stub = root.findViewById[ViewStub](R.id.command_button_stub)
    if(frag_stub != null &&
      YomiInfoUtils.showPoemText){
      frag_stub.inflate()
      val fragment = CommandButtonPanel.newInstance(Some(0))
      getChildFragmentManager.beginTransaction.replace(R.id.command_button_fragment,fragment).commit
    }

    val show_rewind_button = Globals.prefs.get.getBoolean("show_rewind_button",false) 
    val show_replay_last_button = Globals.prefs.get.getBoolean("show_replay_last_button",false) 
    val show_skip_button = Globals.prefs.get.getBoolean("show_skip_button",false)

    if(!YomiInfoUtils.showPoemText && (show_replay_last_button || show_skip_button || show_rewind_button)){
      val sub_buttons = root.findViewById[ViewStub](R.id.sub_buttons_stub).inflate()
      if(show_rewind_button){
        val inflated = sub_buttons.findViewById[ViewStub](R.id.rewind_button_stub).inflate()
        val btn = inflated.findViewById[Button](R.id.rewind_button)
        btn.setOnClickListener(new View.OnClickListener(){
          override def onClick(v:View){
             KarutaPlayUtils.rewind(was)
          }
        })
      }
      if(show_replay_last_button){
        val inflated = sub_buttons.findViewById[ViewStub](R.id.replay_last_button_stub).inflate()
        val btn = inflated.findViewById[Button](R.id.replay_last_button)
        btn.setText(Utils.replayButtonText(getResources))
        btn.setOnClickListener(new View.OnClickListener(){
          override def onClick(v:View){
             KarutaPlayUtils.startReplay(was)
          }
        })
        if(show_rewind_button){
          subButtonMargin(sub_buttons,inflated)
        }
      }
      if(show_skip_button){
        val inflated = sub_buttons.findViewById[ViewStub](R.id.skip_button_stub).inflate()
        val btn = inflated.findViewById[Button](R.id.skip_button)
        btn.setOnClickListener(new View.OnClickListener(){
          override def onClick(v:View){
             KarutaPlayUtils.skipToNext(was)
          }
        })
        if(show_rewind_button || show_replay_last_button){
          subButtonMargin(sub_buttons,inflated)
        }
      }
    }

  }
  def setLongClickYomiInfo(root:View){
    for(id <- Array(R.id.yomi_info_view_prev,R.id.yomi_info_view_cur,R.id.yomi_info_view_next)){
      val view = root.findViewById[YomiInfoView](id)
      if(view != null){
        view.setOnLongClickListener(
          new View.OnLongClickListener(){
            override def onLongClick(v:View):Boolean = {
              if(view.cur_num.nonEmpty){
                val dialog = PoemDescriptionDialog.newInstance(view.cur_num)
                Utils.showDialogOrFallbackToStateless(getFragmentManager,dialog,"poem_description_dialog")
              }
              return true
            }
          }
        )
      }
    }
  }
}
