package karuta.hpnpwd.wasuramoti

import _root_.android.widget.{Button,TableLayout,TableRow}
import _root_.android.util.AttributeSet
import _root_.android.content.Context
import _root_.android.text.TextUtils
import _root_.android.view.{View,ViewGroup,Gravity}


// displays button list
//   large, xlarge screen -> two rows
//   otherwise -> one rows

object YomiInfoButtonList{
  abstract class OnClickListener{
    def onClick(btn:Button,tag:String)
  }
}

class YomiInfoButtonList(context:Context,attrs:AttributeSet) extends TableLayout(context,attrs) {
  var m_on_click_listener:YomiInfoButtonList.OnClickListener = null
  def setOnClickListener(listener:YomiInfoButtonList.OnClickListener){
    m_on_click_listener = listener
  }
  def genButton(tag:String,text:String):Button = {
    val button = new Button(context)
    button.setTag(tag)
    button.setText(text)
    button.setOnClickListener(new View.OnClickListener(){
        override def onClick(v:View){
          m_on_click_listener.onClick(v.asInstanceOf[Button],tag)
        }
      })
    button
  }
  def addButtons(context:Context,text_and_tags:Array[(String,String)]){

    if(Utils.isLarge(context)){
      for(ar<-text_and_tags.grouped(2)){
        val lay = new TableRow(context)
        for((text,tag)<-ar if ! TextUtils.isEmpty(text)){
          val button = genButton(tag,text)
          val params = new TableRow.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
          lay.addView(button,params)
        }
        val lparam = new TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT,1.0f)
        lay.setGravity(Gravity.CENTER)
        addView(lay,lparam)
      }
    }else{
      for((text,tag)<-text_and_tags){
        val button = genButton(tag,text)
        button.setLayoutParams(new TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(button)
      }
    }
  }
}
