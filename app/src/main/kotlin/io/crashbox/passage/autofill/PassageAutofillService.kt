package io.crashbox.passage.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import io.crashbox.passage.App
import io.crashbox.passage.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offers the store's entries for login forms. Suggestions only carry entry
 * names: picking one starts [AutofillActivity], which asks the user to
 * authenticate before decrypting and filling it.
 */
class PassageAutofillService : AutofillService() {

  override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
    val structure = request.fillContexts.last().structure
    val packageName = structure.activityComponent.packageName
    if (packageName == this.packageName) return callback.onSuccess(null)

    val fields = mutableListOf<FormField<AutofillId>>()
    var webDomain: String? = null
    fun visit(node: AssistStructure.ViewNode) {
      if (webDomain == null) webDomain = node.webDomain?.takeIf { it.isNotEmpty() }
      val id = node.autofillId
      if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE) {
        fields += FormField(
          id = id,
          autofillHints = node.autofillHints?.toList() ?: emptyList(),
          inputType = node.inputType,
          htmlTag = node.htmlInfo?.tag,
          htmlAttributes = node.htmlInfo?.attributes?.associate { it.first to it.second } ?: emptyMap(),
          idEntry = node.idEntry,
          hint = node.hint,
        )
      }
      for (i in 0 until node.childCount) visit(node.getChildAt(i))
    }
    for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)

    val form = findLoginFields(fields) ?: return callback.onSuccess(null)
    val ids = listOfNotNull(form.username, form.password)
    val target = webDomain?.let { Target.Web(it) } ?: Target.App(packageName)

    val passage = (application as App).passage
    if (!passage.repo.exists()) return callback.onSuccess(null)
    val response = FillResponse.Builder()
    for (name in suggestions(passage.store.entries(), target).take(MAX_SUGGESTIONS)) {
      response.addDataset(dataset(name, name, ids, form, target))
    }
    response.addDataset(dataset(getString(R.string.autofill_search), null, ids, form, target))
    callback.onSuccess(response.build())
  }

  /** A locked suggestion: it fills nothing until [AutofillActivity] returned the decrypted values. */
  private fun dataset(label: String, entry: String?, ids: List<AutofillId>, form: LoginForm<AutofillId>, target: Target): Dataset {
    val presentation = RemoteViews(packageName, R.layout.autofill_item).apply { setTextViewText(R.id.text, label) }
    val intent = AutofillActivity.intent(this, entry, form.username, form.password, describe(target))
    // mutable: the framework adds the form's structure to the intent
    val pending = PendingIntent.getActivity(
      this,
      requestCodes.incrementAndGet(),
      intent,
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
    )
    return Dataset.Builder(Presentations.Builder().setMenuPresentation(presentation).build()).apply {
      for (id in ids) setField(id, Field.Builder().build())
      setAuthentication(pending.intentSender)
    }.build()
  }

  /** How the target is shown to the user when asked to authenticate: the domain, or the app's name and package. */
  private fun describe(target: Target): String = when (target) {
    is Target.Web -> target.domain
    is Target.App -> {
      val label = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(target.packageName, 0)).toString()
      } catch (e: PackageManager.NameNotFoundException) {
        null
      }
      if (label == null) target.packageName else "$label (${target.packageName})"
    }
  }

  override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
    callback.onFailure("Saving is not supported")
  }

  private companion object {
    const val MAX_SUGGESTIONS = 5
    val requestCodes = AtomicInteger()
  }
}
