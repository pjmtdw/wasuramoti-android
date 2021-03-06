package karuta.hpnpwd.wasuramoti

import android.content.Context
import android.support.v7.preference.{DialogPreference,PreferenceDialogFragmentCompat}
import android.text.TextUtils
import android.util.AttributeSet
import android.view.{View,LayoutInflater}
import android.widget.{AdapterView,ArrayAdapter,Spinner}
import android.os.Bundle
import java.util.ArrayList

case class FudaSetWithSize(val title:String, val num:Int){
  override def toString():String = {
    title + " (" + num + ")"
  }
  def equals(obj:FudaSetWithSize):Boolean = {
    title.equals(obj.title)
  }
}

trait FudaSetEditListener {
  def onFudaSetEditListenerResult(isAdd:Boolean, origFs:FudaSetWithSize, newFs:FudaSetWithSize)
}

class FudaSetPreferenceFragment extends PreferenceDialogFragmentCompat
  with ButtonListener with FudaSetEditListener with CommonDialog.CallbackListener{
  var listItems = new ArrayList[FudaSetWithSize]()
  var adapter = None:Option[ArrayAdapter[FudaSetWithSize]]
  var spinner = None:Option[Spinner]
  
  override def onCommonDialogCallback(bundle:Bundle){
    bundle.getString("tag") match {
      case "fudaset_delete_confirmed" => 
        Globals.db_lock.synchronized{
          val pos = bundle.getInt("delete_pos")
          val title = bundle.getString("delete_title")
          try{
            val fs = adapter.get.getItem(pos)
            if(fs.title == title){ //consistency check
              val db = Globals.database.get.getWritableDatabase
              db.delete(Globals.TABLE_FUDASETS,"title = ?", Array(title))
              db.close()
              adapter.get.remove(fs)
            }
          }catch{
            // I could'n figure out how the following exception occurs but there was user report.
            // TODO: how does this happen?
            case _:IndexOutOfBoundsException => None
          }
        }
      case "fudaset_copymerge_done" =>
        addFudaSetToSpinner(bundle.getSerializable("fudaset").asInstanceOf[FudaSetWithSize])

      case "fudaset_list_changed" =>
        refreshListItems
    }
  }

  override def onFudaSetEditListenerResult(isAdd: Boolean, origFs:FudaSetWithSize, newFs:FudaSetWithSize){
    if(isAdd){
      addFudaSetToSpinner(newFs)
    }else{
      val pos = spinner.get.getSelectedItemPosition
      adapter.get.remove(origFs)
      adapter.get.insert(newFs,pos)
    }
  }
  override def buttonMapping = Map(
      R.id.button_fudaset_edit -> editFudaSet _,
      R.id.button_fudaset_new ->  newFudaSet _,
      R.id.button_fudaset_delete ->  deleteFudaSet _,
      R.id.button_fudaset_copymerge -> copymergeFudaSet _,
      R.id.button_fudaset_reorder -> reorderFudaSet _,
      R.id.button_fudaset_transfer -> transferFudaSet _
    )
  override def onDialogClosed(positiveResult:Boolean){
    val pref = getPreference.asInstanceOf[FudaSetPreference]
    if(positiveResult){
      val context = getContext // this will be null when call onDialogClosed was call by screen rotate
      val pos = spinner.get.getSelectedItemPosition()
      val title = try{
        listItems.get(pos).title
      }catch{
        case _:IndexOutOfBoundsException => return
      }
      pref.persistString(title)
      FudaListHelper.updateSkipList(context,title)
    }
    pref.notifyChangedPublic // in case that number of fudas in current fudaset changed
  }
  override def onCreateDialogView(context:Context):View = Globals.db_lock.synchronized{
    val view = LayoutInflater.from(context).inflate(R.layout.fudaset, null)

    adapter = Some(new ArrayAdapter[FudaSetWithSize](context,android.R.layout.simple_spinner_item,listItems))
    val spin = view.findViewById[Spinner](R.id.fudaset_list)
    spin.setAdapter(adapter.get)
    spinner =  Some(spin)
    refreshListItems
    setButtonMapping(view)
    return view
  }

  def refreshListItems(){
    val pref = getPreference.asInstanceOf[FudaSetPreference]
    val persisted = pref.getPersistedString("")
    listItems.clear()
    for((fs,i) <- FudaListHelper.selectFudasetAll.zipWithIndex){
      listItems.add(new FudaSetWithSize(fs.title,fs.set_size))
      if(fs.title == persisted){
        spinner.get.setSelection(i)
      }
    }
    adapter.get.notifyDataSetChanged()
  }

  def addFudaSetToSpinner(fs:FudaSetWithSize){
    adapter.get.add(fs)
    adapter.get.notifyDataSetChanged
    spinner.get.setSelection(adapter.get.getCount-1)
  }
  def editFudaSetBase(is_add:Boolean,orig_fs:FudaSetWithSize=null){
    val orig_title = Option(orig_fs).map(_.title).getOrElse("")
    val fragment = FudaSetEditDialogFragment.newInstance(is_add,orig_title,orig_fs) 
    fragment.setTargetFragment(this,0)
    fragment.show(getFragmentManager,"fudaset_edit_dialog")
  }
  def newFudaSet(){
    editFudaSetBase(true)
  }

  def editFudaSet(){
    val pos = spinner.get.getSelectedItemPosition
    if(pos == AdapterView.INVALID_POSITION){
      return
    }
    val fs = adapter.get.getItem(pos)
    editFudaSetBase(false, fs)
  }
  def deleteFudaSet(){ Globals.db_lock.synchronized{
    val context = getContext
    val pos = spinner.get.getSelectedItemPosition
    if(pos == AdapterView.INVALID_POSITION){
      return
    }
    val fs = adapter.get.getItem(pos)
    val message = context.getResources.getString(R.string.fudaset_confirmdelete,fs.title)
    val bundle = new Bundle
    bundle.putString("tag","fudaset_delete_confirmed")
    bundle.putInt("delete_pos",pos)
    bundle.putString("delete_title",fs.title)
    CommonDialog.confirmDialogWithCallback(this, Left(message), bundle)
  }}
  def copymergeFudaSet(){
    CommonDialog.showWrappedDialogWithCallback[FudaSetCopyMergeDialog](this)
  }
  def reorderFudaSet(){
    CommonDialog.showWrappedDialogWithCallback[FudaSetReOrderDialog](this)
  }
  def transferFudaSet(){
    CommonDialog.showWrappedDialogWithCallback[FudaSetTransferDialog](this)
  }
}

class FudaSetPreference(context:Context,attrs:AttributeSet) extends DialogPreference(context,attrs) with CustomPref{
  def this(context:Context,attrs:AttributeSet,def_style:Int) = this(context,attrs)

  override def getAbbrValue():String = Globals.db_lock.synchronized{
    val title = getPersistedString("")
    if(!TextUtils.isEmpty(title)){
      val db = Globals.database.get.getReadableDatabase
      val cursor = db.query(Globals.TABLE_FUDASETS,Array("set_size"),"title = ?",Array(title),null,null,null,null)
      cursor.moveToFirst
      val set_size = if(cursor.getCount > 0){ cursor.getInt(0) }else{0}
      cursor.close
      //db.close
      title + " ("+set_size+")"
    }else{
      ""
    }
  }

}
