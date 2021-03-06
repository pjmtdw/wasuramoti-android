package karuta.hpnpwd.wasuramoti

import android.app.Dialog
import android.os.Bundle
import android.content.DialogInterface
import android.widget.RadioGroup

import android.support.v4.app.DialogFragment

object IntendedUseDialog{
  def newInstance(first_config:Boolean = true):IntendedUseDialog = {
    val fragment = new IntendedUseDialog
    val args = new Bundle
    if(first_config){
      args.putShort("first_config",1)
    }
    fragment.setArguments(args)
    return fragment
  }
}

class IntendedUseDialog extends DialogFragment with DialogInterface.OnClickListener{
  override def onCreateDialog(saved:Bundle):Dialog = {
    val first_config = getArguments.getShort("first_config") == 1
    val helper = new GeneralRadioHelper(getActivity)
    helper.setDescription(R.string.intended_use_desc)
    helper.addItems(Seq(
      GeneralRadioHelper.Item(R.id.intended_use_recreation,Left(R.string.intended_use_recreation),Left(R.string.intended_use_recreation_desc)),
      GeneralRadioHelper.Item(R.id.intended_use_competitive,Left(R.string.intended_use_competitive),Left(R.string.intended_use_competitive_desc)),
      GeneralRadioHelper.Item(R.id.intended_use_study,Left(R.string.intended_use_study),Left(R.string.intended_use_study_desc)),
      GeneralRadioHelper.Item(R.id.intended_use_shimonoku,Left(R.string.intended_use_shimonoku),Left(R.string.intended_use_shimonoku_desc))
    ))
    (Globals.prefs.get.getString("intended_use","") match {
      case "study" => Some(R.id.intended_use_study)
      case "competitive" => Some(R.id.intended_use_competitive)
      case "recreation" => Some(R.id.intended_use_recreation)
      case "shimonoku" => Some(R.id.intended_use_shimonoku)
      case _ => None
    }).foreach{ helper.radio_group.check(_) }
    helper.builder
          .setTitle(if(first_config){R.string.intended_use_title}else{R.string.quick_conf_intended_use})
          .setPositiveButton(android.R.string.yes,this)
    if(first_config){
      setCancelable(false)
    }else{
      helper.builder.setNegativeButton(android.R.string.no,null)
    }
    val dialog = helper.builder.create
    if(first_config){
      dialog.setOnShowListener(new DialogInterface.OnShowListener(){
        override def onShow(interface:DialogInterface){
          val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
          val rgp = helper.radio_group
          if(rgp != null && button != null){
            rgp.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener(){
              override def onCheckedChanged(group:RadioGroup,checkedId:Int){
                button.setEnabled(true)
              }
            })
            button.setEnabled(false)
          }
        }
      })
    }
    return dialog
  }
  override def onClick(dialog:DialogInterface,which:Int){
    import FudaSetEditListDialog.{SortMode,ListItemMode}
    val radio_group = dialog.asInstanceOf[Dialog].findViewById[RadioGroup](R.id.general_radio_dialog_group)
    val id = Option(radio_group).map{_.getCheckedRadioButtonId}.getOrElse(-1)
    if(id == -1){
      return
    }
    val edit = Globals.prefs.get.edit
    val changes = id match {
      case R.id.intended_use_competitive => {
        edit.putString("intended_use","competitive")
        edit.putString("fudaset_edit_list_dlg_mode",FudaSetEditListDialog.genDialogMode(SortMode.ABC,ListItemMode.KIMARIJI))
        edit.putString("read_order_each","CUR2_NEXT1")
        edit.putBoolean("joka_enable",true)
        edit.putString("read_order_joka","upper_1,lower_1")
        edit.putBoolean("memorization_mode",false)
        edit.putBoolean("show_replay_last_button",false)
        edit.putBoolean("show_skip_button",false)
        edit.putBoolean("show_rewind_button",false)
        edit.putBoolean("show_kimari_simo",false)
        edit.putBoolean("move_next_after_done",true)
        edit.putBoolean("move_after_first_phrase",true)
        YomiInfoUtils.hidePoemText(edit)
        Array(
          (R.string.intended_use_poem_text,R.string.quick_conf_hide),
          (R.string.intended_use_read_order,R.string.conf_read_order_name_cur2_next1),
          (R.string.intended_use_joka,R.string.intended_use_joka_on_twice),
          (R.string.conf_memorization_title,R.string.message_disabled),
          (R.string.intended_use_replay,R.string.intended_use_hide),
          (R.string.intended_use_skip,R.string.intended_use_hide),
          (R.string.intended_use_rewind,R.string.intended_use_hide),
          (R.string.intended_use_move_next,R.string.intended_use_move_next_on)
        )
      }
      case R.id.intended_use_study => {
        edit.putString("intended_use","study")
        edit.putString("fudaset_edit_list_dlg_mode",FudaSetEditListDialog.genDialogMode(SortMode.NUM,ListItemMode.FULL))
        edit.putString("read_order_each","CUR1_CUR2")
        edit.putBoolean("joka_enable",false)
        edit.putBoolean("memorization_mode",true)
        edit.putBoolean("show_replay_last_button",false)
        edit.putBoolean("show_skip_button",false)
        edit.putBoolean("show_rewind_button",false)
        edit.putBoolean("show_kimari_simo",false)
        edit.putBoolean("move_next_after_done",false)
        edit.putBoolean("move_after_first_phrase",false)
        YomiInfoUtils.showFull(edit)
         Array(
          (R.string.intended_use_poem_text,R.string.quick_conf_full),
          (R.string.intended_use_read_order,R.string.conf_read_order_name_cur1_cur2),
          (R.string.intended_use_joka,R.string.intended_use_joka_off),
          (R.string.conf_memorization_title,R.string.message_enabled),
          (R.string.intended_use_replay,R.string.intended_use_hide),
          (R.string.intended_use_skip,R.string.intended_use_hide),
          (R.string.intended_use_rewind,R.string.intended_use_hide),
          (R.string.intended_use_move_next,R.string.intended_use_move_next_off),
          (R.string.intended_use_kimariji,R.string.intended_use_kimariji_upper)
        )
      }
      case R.id.intended_use_recreation => {
        edit.putString("intended_use","recreation")
        edit.putString("fudaset_edit_list_dlg_mode",FudaSetEditListDialog.genDialogMode(SortMode.NUM,ListItemMode.FULL))
        edit.putString("read_order_each","CUR1_CUR2_CUR2")
        edit.putBoolean("joka_enable",false)
        edit.putBoolean("memorization_mode",false)
        edit.putBoolean("show_replay_last_button",true)
        edit.putBoolean("show_skip_button",true)
        edit.putBoolean("show_rewind_button",false)
        edit.putBoolean("show_kimari_simo",false)
        edit.putBoolean("move_next_after_done",true)
        edit.putBoolean("move_after_first_phrase",true)
        YomiInfoUtils.showOnlyFirst(edit)
        Array(
          (R.string.intended_use_poem_text,R.string.quick_conf_only_first),
          (R.string.intended_use_read_order,R.string.conf_read_order_name_cur1_cur2_cur2),
          (R.string.intended_use_joka,R.string.intended_use_joka_off),
          (R.string.conf_memorization_title,R.string.message_disabled),
          (R.string.intended_use_replay,R.string.intended_use_show),
          (R.string.intended_use_skip,R.string.intended_use_show),
          (R.string.intended_use_rewind,R.string.intended_use_hide),
          (R.string.intended_use_move_next,R.string.intended_use_move_next_on),
          (R.string.intended_use_kimariji,R.string.intended_use_kimariji_upper)
        )
      }
      case R.id.intended_use_shimonoku => {
        edit.putString("intended_use","shimonoku")
        edit.putString("fudaset_edit_list_dlg_mode",FudaSetEditListDialog.genDialogMode(SortMode.NUM,ListItemMode.FULL))
        edit.putString("read_order_each","CUR2_NEXT2")
        edit.putBoolean("joka_enable",true)
        edit.putString("read_order_joka","upper_0,lower_1")
        edit.putBoolean("memorization_mode",false)
        edit.putBoolean("show_replay_last_button",false)
        edit.putBoolean("show_skip_button",false)
        edit.putBoolean("show_rewind_button",true)
        edit.putBoolean("show_kimari_simo",true)
        edit.putBoolean("move_next_after_done",true)
        edit.putBoolean("move_after_first_phrase",true)
        YomiInfoUtils.showOnlySecond(edit)
        Array(
          (R.string.intended_use_poem_text,R.string.quick_conf_only_second),
          (R.string.intended_use_read_order,R.string.conf_read_order_name_cur2_next2),
          (R.string.intended_use_joka,R.string.intended_use_joka_on_once),
          (R.string.conf_memorization_title,R.string.message_disabled),
          (R.string.intended_use_replay,R.string.intended_use_hide),
          (R.string.intended_use_skip,R.string.intended_use_hide),
          (R.string.intended_use_rewind,R.string.intended_use_show),
          (R.string.intended_use_move_next,R.string.intended_use_move_next_on),
          (R.string.intended_use_kimariji,R.string.intended_use_kimariji_lower)
        )
      }
      case _ => Array() // do nothing
    }
    val footnote = id match {
      case R.id.intended_use_competitive => Some(R.string.intended_use_competitive_footnote)
      case R.id.intended_use_study => Some(R.string.intended_use_study_footnote)
      case R.id.intended_use_recreation => Some(R.string.intended_use_recreation_footnote)
      case R.id.intended_use_shimonoku => Some(R.string.intended_use_shimonoku_footnote)
      case _ => None
    }
    edit.commit()
    FudaListHelper.updateSkipList(getActivity)
    Globals.forceRefreshPlayer = true

    var html = "<big>" + getString(R.string.intended_use_result) + "</big><br>-------<br>" + changes.map({case(k,v)=>
      val kk = getString(k)
      val vv = getString(v)
      s"""&middot; ${kk} &hellip; <font color="?attr/intendedUseValueColor">${vv}</font>"""
    }).mkString("<br>")  
    footnote.foreach{
      html += "<br>-------<br><big>" + getString(_) + "</big>"
    }
    CommonDialog.generalHtmlDialog(getContext,Left(html))
    getActivity.asInstanceOf[WasuramotiActivity].reloadFragment()
  }
}

