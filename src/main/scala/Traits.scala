package karuta.hpnpwd.wasuramoti

import android.view.View
import android.content.{DialogInterface,Context}
import android.os.Bundle

import android.support.v4.app.Fragment
import android.support.v7.app.AlertDialog

trait GetFudanum {
  self:Fragment =>
  def getFudanum():Option[Int] = {
    Option(self.getArguments.getSerializable("fudanum").asInstanceOf[Option[Int]]).flatten
  }
}

// Override AlertDialog so that it does not close on button click
abstract class CustomAlertDialog(context:Context) extends AlertDialog(context){
  def doWhenClose():Boolean

  override def onCreate(bundle:Bundle){
    setButton(DialogInterface.BUTTON_POSITIVE,getContext.getResources.getString(android.R.string.ok),null.asInstanceOf[DialogInterface.OnClickListener])
    setButton(DialogInterface.BUTTON_NEGATIVE,getContext.getResources.getString(android.R.string.cancel),null.asInstanceOf[DialogInterface.OnClickListener])
    // the button will be setup here
    // https://github.com/android/platform_frameworks_base/blob/master/core/java/com/android/internal/app/AlertController.java
    super.onCreate(bundle)

    // overwrite the button's behavior
    val positive = findViewById(android.R.id.button1)
    val negative = findViewById(android.R.id.button2)
    positive.setOnClickListener(new View.OnClickListener{
      override def onClick(v:View){
        if(doWhenClose){
          dismiss
        }
      }
    })
    negative.setOnClickListener(new View.OnClickListener{
      override def onClick(v:View){
        dismiss
      }
    })
  }
}

trait ButtonListener extends View.OnClickListener{
  self => 
  def buttonMapping():Map[Int,()=>Unit]
  override def onClick(view:View){
    buttonMapping.get(view.getId).foreach{_()}
  }
  def setButtonMapping(view:View){
    for(id <- buttonMapping.keys){
      view.findViewById(id).setOnClickListener(self)
    }
  }
}
