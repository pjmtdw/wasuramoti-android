package karuta.hpnpwd.wasuramoti

import _root_.android.view.{View,LayoutInflater}
import _root_.android.os.Bundle
import _root_.android.content.Context
import _root_.android.widget.{ArrayAdapter,ListView,EditText}
import _root_.android.app.AlertDialog
import _root_.android.text.TextUtils

class FudaSetCopyMergeDialog(context:Context) extends AlertDialog(context) with CustomAlertDialogTrait{

  class FudaSetItem(val id:Long, val title:String, val set_size:Int){
    override def toString():String = {
      s"${title} (${set_size})"
    }
  }

  def addItemsToListView(view:View){
    val container_view = view.findViewById(R.id.fudasetcopymerge_container).asInstanceOf[ListView]
    val setlist = FudaListHelper.selectFudasetAll.map{ x => new FudaSetItem(x._1,x._2,x._3) }
    val adapter = new ArrayAdapter[FudaSetItem](context,R.layout.my_simple_list_item_multiple_choice,setlist)
    container_view.setAdapter(adapter)
  }

  override def doWhenClose(view:View){Globals.db_lock.synchronized {
    val title_view = view.findViewById(R.id.fudasetcopymerge_name).asInstanceOf[EditText]
    val title = title_view.getText.toString
    if(TextUtils.isEmpty(title)){
      Utils.messageDialog(context,Right(R.string.fudasetedit_titleempty),{()=>show()})
      return
    }
    val items = Utils.getCheckedItemsFromListView[FudaSetItem](view.findViewById(R.id.fudasetcopymerge_container).asInstanceOf[ListView])
    if(items.isEmpty){
      Utils.messageDialog(context,Right(R.string.fudaset_copymerge_notchecked),{()=>show()})
      return
    }
    val newset = FudaListHelper.queryMergedFudaset(items.map{_.id})
    newset.foreach{case (body,st_size) =>
      Utils.writeFudaSetToDB(title,body,st_size,true)
    }
  } }
  override def onCreate(state:Bundle){
    val view = LayoutInflater.from(context).inflate(R.layout.fudaset_copymerge, null)
    addItemsToListView(view)
    setTitle(R.string.fudaset_copymerge_title)
    setViewAndButton(view)
    super.onCreate(state)
  }
}
