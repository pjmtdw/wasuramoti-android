package karuta.hpnpwd.wasuramoti

import android.os.Bundle
import android.app.Dialog
import android.content.{DialogInterface,Context}
import android.widget.{CheckBox,TextView}
import android.view.LayoutInflater
import android.text.Html
import android.text.method.LinkMovementMethod

import android.support.v7.app.AlertDialog
import android.support.v4.app.{FragmentActivity,DialogFragment,Fragment,FragmentManager}

import scala.reflect.ClassTag

object CommonDialog {

  // Putting Enumration into Bundle.putSerializable causes error,
  // Read KarutaPlayUtils.scala for more information
  val TARGET_ACTIVITY = "TARGET_ACTIVITY"
  val TARGET_FRAGMENT = "TARGET_FRAGMENT"

  val DIALOG_MESSAGE = "DIALOG_MESSAGE"
  val DIALOG_CONFIRM = "DIALOG_CONFIRM"
  val DIALOG_HTML = "DIALOG_HTML"
  val DIALOG_CHECKBOX = "DIALOG_CHECKBOX"
  val DIALOG_LIST = "DIALOG_LIST"

  // We get IllegalStateException when we try to commit the FragmentTransaction, or call DialogFrament#show, which actually calls commit(), after onSaveInstanceState.
  // The easy workaround is using commitAllowingStateLoss() instead of commit(), however, it is a last resort.
  // Instead, we save the state to member variable when the activity is not visible (after onPause()), and show dialog in onResumeFragments()
  // reference:
  //   https://medium.com/inloop/demystifying-androids-commitallowingstateloss-cb9011a544cc
  //   http://www.androiddesignpatterns.com/2013/08/fragment-transaction-commit-state-loss.html
  //   https://stackoverflow.com/questions/10114324/show-dialogfragment-from-onactivityresult
  //   https://stackoverflow.com/questions/8040280/how-to-handle-handler-messages-when-activity-fragment-is-paused
  trait DialogStateHandler{
    self: FragmentActivity =>
    // since we only have to show latest dialog, we don't need queue
    var commonDialogState:Bundle = null
    var isCommonDialogShowable:Boolean = false
    override def onResumeFragments(){
      self.onResumeFragments()
      isCommonDialogShowable = true
      if(commonDialogState != null){
        showMessageDialogFromBundle(commonDialogState)
      }
      commonDialogState = null
    }
    override def onPause(){
      isCommonDialogShowable = false
      self.onPause()
    }
    // this should be used for Dialog which has a possibility that to be shown when Activity is not visible. e.g. when Activity is in background, or the device is locked.
    def showMessageDialogOrEnqueue(arg:Either[String,Int]){
      val bundle = new Bundle
      val message = getStringOrResource(this,arg)
      bundle.putString("message", message)
      bundle.putString("dialog_type",DIALOG_MESSAGE)
      if(isCommonDialogShowable){
        showMessageDialogFromBundle(bundle)
      }else{
        commonDialogState = bundle.clone.asInstanceOf[Bundle]
      }
    }
    def showMessageDialogFromBundle(bundle:Bundle){
      val fragment = new CommonDialogFragment
      val manager = self.getSupportFragmentManager
      fragment.setArguments(bundle)
      fragment.show(manager, "common_dialog_base")
    }
    override def onSaveInstanceState(state:Bundle){
      state.putBundle("common_dialog_state",commonDialogState)
      self.onSaveInstanceState(state)
    }
    override def onCreate(state:Bundle){
      self.onCreate(state)
      if(state != null){
        commonDialogState = state.getBundle("common_dialog_state")
      }
    }
  }
  

  trait CallbackListener{
    def onCommonDialogCallback(bundle:Bundle)
  }

  trait CustomDialog extends CallbackListener{
    def customCommonDialog(bundle:Bundle, builder:AlertDialog.Builder)
  }

  trait WrappableDialog {
    var callbackListener:CallbackListener = null
    var extraArguments:Bundle = null
  }

  class DialogWrapperFragment extends DialogFragment {
    override def onCreateDialog(state:Bundle):Dialog = {
      val args = super.getArguments
      val classTag = args.getSerializable("class_tag").asInstanceOf[ClassTag[Dialog]]
      val extraArgs = args.getBundle("extra_args")
      val dialog = classTag.runtimeClass.getConstructor(classOf[Context]).newInstance(getContext).asInstanceOf[Dialog]
      val callbackTarget = args.getString("callback_target") match {
        case TARGET_ACTIVITY => getActivity
        case TARGET_FRAGMENT => getTargetFragment
        case null => null
      }
      if(dialog.isInstanceOf[WrappableDialog]){
        val wrappable = dialog.asInstanceOf[WrappableDialog]
        wrappable.callbackListener = callbackTarget.asInstanceOf[CallbackListener] 
        wrappable.extraArguments = extraArgs
        
      }
      dialog
    }
  }

  // NOTE: The dialog class which uses showWrappedDialog and showWrappedDialogWithCallback method
  //       shuld be annotated by @KeepConstructor. This is maybe because we pass ClassTag as serializable,
  //       and Proguard cannot determine that we need the constructor.

  def showWrappedDialog[C <: Dialog](manager:FragmentManager,extraArgs:Bundle=new Bundle)(implicit tag:ClassTag[C]){
    wrappedDialogBase(Right(manager),extraArgs)
  }

  def showWrappedDialogWithCallback[C <: Dialog](parent:CallbackListener,extraArgs:Bundle=new Bundle)(implicit tag:ClassTag[C]){
    wrappedDialogBase(Left(parent),extraArgs)
  }

  def wrappedDialogBase[C <: Dialog](
    target:Either[CallbackListener,FragmentManager],extraArgs:Bundle=new Bundle)
  (implicit tag:ClassTag[C]){
    val fragment = new DialogWrapperFragment
    val bundle = new Bundle
    bundle.putSerializable("class_tag",tag)
    bundle.putBundle("extra_args",extraArgs)
    if(target.isLeft){
      val parent = target.left.get
      bundle.putString("callback_target", parent match {
        case _:Fragment => TARGET_FRAGMENT
        case _:FragmentActivity => TARGET_ACTIVITY
      })
      if(parent.isInstanceOf[Fragment]){
        fragment.setTargetFragment(parent.asInstanceOf[Fragment], 0)
      }
    }
    val manager = target match {
      case Left(parent) => getContextAndManager(parent)._2
      case Right(manager) => manager
    }
    val name = tag.toString.toLowerCase.replaceAllLiterally(".","_")
    fragment.setArguments(bundle)
    fragment.show(manager, name)
  }

  class CommonDialogFragment extends DialogFragment {
    override def onCreateDialog(state:Bundle):Dialog = {
      val args = super.getArguments
      val callbackTarget = args.getString("callback_target") match {
        case TARGET_ACTIVITY => getActivity
        case TARGET_FRAGMENT => getTargetFragment
        case null => null
      }
      val callbackBundle = Option(args.getBundle("callback_bundle")).getOrElse(new Bundle)
      val dialogType = args.getString("dialog_type")
      val listener = if(callbackTarget != null && callbackTarget.isInstanceOf[CallbackListener]){
        if(dialogType == DIALOG_CHECKBOX){
          new DialogInterface.OnClickListener(){
            override def onClick(interface:DialogInterface,which:Int){
              val dialog = interface.asInstanceOf[Dialog]
              val bundle = callbackBundle.clone.asInstanceOf[Bundle]
              val checkbox = dialog.findViewById[CheckBox](R.id.checkbox_dialog_checkbox)
              bundle.putBoolean("checked",checkbox.isChecked)
              bundle.putInt("which",which)
              callbackTarget.asInstanceOf[CallbackListener].onCommonDialogCallback(bundle)
            }
          }
        }else{
          new DialogInterface.OnClickListener(){
            override def onClick(interface:DialogInterface,which:Int){
              callbackTarget.asInstanceOf[CallbackListener].onCommonDialogCallback(callbackBundle)
            }
          }
        }
      }else{
        null
      }
      val message = args.getString("message")
      val context = getContext
      val builder = new AlertDialog.Builder(context)
      dialogType match {
        case DIALOG_MESSAGE =>
          builder.setPositiveButton(android.R.string.ok,listener)
          builder.setMessage(message)
        case DIALOG_CONFIRM => 
          builder.setPositiveButton(android.R.string.yes,listener)
          builder.setNegativeButton(android.R.string.no,null)
          builder.setMessage(message)
        case DIALOG_HTML =>
          builder.setPositiveButton(android.R.string.ok,listener)
          val view = LayoutInflater.from(context).inflate(R.layout.general_scroll,null)
          val txtview = view.findViewById[TextView](R.id.general_scroll_body)
          txtview.setText(Html.fromHtml(Utils.htmlAttrFormatter(context,message)))
          // this makes "<a href='...'></a>" clickable
          txtview.setMovementMethod(LinkMovementMethod.getInstance)
          builder.setView(view)
        case DIALOG_CHECKBOX =>
          builder.setPositiveButton(android.R.string.ok,listener)
          builder.setNegativeButton(android.R.string.no,listener)
          val view = LayoutInflater.from(context).inflate(R.layout.general_checkbox_dialog,null)
          val vtext = view.findViewById[TextView](R.id.checkbox_dialog_text)
          val vcheckbox = view.findViewById[CheckBox](R.id.checkbox_dialog_checkbox)
          vtext.setText(message)
          vcheckbox.setText(args.getString("message_checkbox"))
          builder.setView(view)
        case DIALOG_LIST =>
          builder.setNegativeButton(R.string.button_cancel,null)
          builder.setTitle(message)
          val items = context.getResources().getStringArray(args.getInt("items_id"))
          builder.setItems(items.map{_.asInstanceOf[CharSequence]},new DialogInterface.OnClickListener(){
            override def onClick(d:DialogInterface,position:Int){
              val bundle = callbackBundle.clone.asInstanceOf[Bundle]
              bundle.putInt("position",position)
              callbackTarget.asInstanceOf[CallbackListener].onCommonDialogCallback(bundle)
              d.dismiss()
            }
          })
      }
      if(callbackTarget.isInstanceOf[CustomDialog]){
        callbackTarget.asInstanceOf[CustomDialog].customCommonDialog(callbackBundle, builder)
      }
      builder.create
    }
  }

  def getStringOrResource(context:Context,arg:Either[String,Int]):String = {
    arg match {
      case Left(x) => x
      case Right(x) => context.getResources.getString(x)
    }
  }
  def messageDialog(context:Context,message:Either[String,Int]){
    baseDialog(DIALOG_MESSAGE,context,message)
  }
  def generalHtmlDialog(context:Context,message:Either[String,Int]){
    baseDialog(DIALOG_HTML,context,message)
  }
  // TODO: replace Context with FragmentManager since casting Context to FragmentActivity is not always safe
  def baseDialog(dialogType:String,context:Context,message:Either[String,Int]){
    val manager = context.asInstanceOf[FragmentActivity].getSupportFragmentManager
    val fragment = new CommonDialogFragment
    val bundle = new Bundle
    bundle.putString("message", getStringOrResource(context,message))
    bundle.putString("dialog_type",dialogType)
    fragment.setArguments(bundle)
    fragment.show(manager, "common_dialog_message")
  }

  def messageDialogWithCallback(
    parent:CallbackListener,
    message:Either[String,Int],
    callbackBundle:Bundle
    ){
      baseDialogWithCallback(DIALOG_MESSAGE,parent,message,callbackBundle)
  }
  def confirmDialogWithCallback(
    parent:CallbackListener,
    message:Either[String,Int],
    callbackBundle:Bundle
     ){
      baseDialogWithCallback(DIALOG_CONFIRM,parent,message,callbackBundle)
  }
  def generalListDialogWithCallback(
    parent:CallbackListener,
    title:Either[String,Int],
    items_id:Int,
    callbackBundle:Bundle
  ){
    val extraArgs = new Bundle
    extraArgs.putInt("items_id",items_id)
    baseDialogWithCallback(DIALOG_LIST,parent,title,callbackBundle,extraArgs)

  }

  def generalCheckBoxConfirmDialogWithCallback(
    parent:CallbackListener,
    message:Either[String,Int],
    message_checkbox:Either[String,Int],
    callbackBundle:Bundle
    ){
      val extraArgs = new Bundle
      val (context,_) = getContextAndManager(parent)
      extraArgs.putString("message_checkbox",getStringOrResource(context, message_checkbox))
      baseDialogWithCallback(DIALOG_CHECKBOX,parent,message,callbackBundle,extraArgs)
  }

  def getContextAndManager(parent:CallbackListener) = {
    parent match {
      case fragment:Fragment => (fragment.getContext,fragment.getFragmentManager)
      case activity:FragmentActivity => (activity,activity.getSupportFragmentManager)
    }
  }

  def baseDialogWithCallback(
    dialogType:String,
    parent:CallbackListener,
    message:Either[String,Int],
    callbackBundle:Bundle,
    extraArgs:Bundle = null
    ){
    val fragment = new CommonDialogFragment 
    val bundle = new Bundle
    val (context,manager) = getContextAndManager(parent)
    bundle.putString("message", getStringOrResource(context, message))
    bundle.putBundle("callback_bundle", callbackBundle)
    bundle.putString("callback_target", parent match {
      case _:Fragment => TARGET_FRAGMENT
      case _:FragmentActivity => TARGET_ACTIVITY
    })
    bundle.putString("dialog_type",dialogType)
    if(extraArgs != null){
      bundle.putAll(extraArgs)
    }
    fragment.setArguments(bundle)
    if(parent.isInstanceOf[Fragment]){
      fragment.setTargetFragment(parent.asInstanceOf[Fragment], 0)
    }
    Utils.showDialogOrFallbackToStateless(manager,fragment,"common_dialog_base")
  }

}
