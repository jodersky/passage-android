package io.crashbox.passage.autofill

/*
 * Platform-independent parts of autofill: which fields of a form to fill,
 * which entries to suggest, and the username of an entry.
 */

/** The username in an entry's notes: the first `user:`, `username:`, `login:` or `email:` line. */
fun usernameOf(content: String): String? =
  content.lineSequence().drop(1) // the first line is the password
    .mapNotNull { USERNAME_LINE.matchEntire(it.trim())?.groupValues?.get(2)?.trim() }
    .firstOrNull { it.isNotEmpty() }

private val USERNAME_LINE = Regex("""(?i)(user|username|login|email)\s*:\s*(.*)""")

/** Where autofill was requested: a website (from a browser) or an app. */
sealed interface Target {
  data class Web(val domain: String) : Target
  data class App(val packageName: String) : Target
}

/**
 * The word identifying the site or app, to match against entry names:
 * `gist.github.com` -> `github`, `www.bbc.co.uk` -> `bbc`,
 * `com.github.android` -> `github`.
 */
fun keyword(target: Target): String? = when (target) {
  is Target.Web -> {
    val labels = target.domain.lowercase().trim('.').split('.').filter { it.isNotEmpty() }
    val suffixLength = if (labels.size >= 3 && labels.takeLast(2).joinToString(".") in TWO_LEVEL_SUFFIXES) 2 else 1
    labels.dropLast(suffixLength).lastOrNull()?.takeIf { labels.size > suffixLength && it.any(Char::isLetter) }
  }
  is Target.App ->
    target.packageName.lowercase().split('.').firstOrNull { it.length >= 3 && it !in GENERIC_PACKAGE_PARTS }
}

/** Entries whose name contains the target's [keyword], ignoring case. */
fun suggestions(entries: List<String>, target: Target): List<String> {
  val keyword = keyword(target) ?: return emptyList()
  return entries.filter { it.contains(keyword, ignoreCase = true) }
}

// The common second-level public suffixes; a full public suffix list is not
// worth it for matching suggestions the user picks from anyway.
private val TWO_LEVEL_SUFFIXES = setOf(
  "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk",
  "com.au", "net.au", "org.au", "edu.au", "gov.au",
  "co.jp", "ne.jp", "or.jp", "ac.jp",
  "co.nz", "org.nz", "co.za", "co.in", "co.kr", "co.il",
  "com.br", "com.cn", "com.mx", "com.ar", "com.tr", "com.sg", "com.hk", "com.tw",
)

private val GENERIC_PACKAGE_PARTS = setOf(
  "com", "org", "net", "io", "app", "apps", "android", "mobile", "client", "www", "the",
)

/**
 * A field of a form, as relevant to autofill. [id] is opaque (an Android
 * AutofillId), the rest mirrors Android's AssistStructure.ViewNode.
 */
data class FormField<Id>(
  val id: Id,
  val autofillHints: List<String> = emptyList(),
  /** android.text.InputType bits */
  val inputType: Int = 0,
  val htmlTag: String? = null,
  /** HTML attributes, for web pages */
  val htmlAttributes: Map<String, String> = emptyMap(),
  /** the view's resource id name */
  val idEntry: String? = null,
  /** placeholder text */
  val hint: String? = null,
  /** whether it is a text field at all (an EditText, an HTML input, ...) */
  val isText: Boolean = true,
)

class LoginForm<Id>(val username: Id?, val password: Id?)

/**
 * Finds the username and password fields among a form's fields (in
 * traversal order). Explicit hints win; otherwise the password is the first
 * password-like input, and the username the text field just before it.
 */
fun <Id> findLoginFields(fields: List<FormField<Id>>): LoginForm<Id>? {
  val candidates = fields.filter { it.isText && !it.isHidden() }
  val password = candidates.firstOrNull { it.hinted(PASSWORD_HINTS) }
    ?: candidates.firstOrNull { it.isPasswordInput() }
  val username = candidates.firstOrNull { it.hinted(USERNAME_HINTS) }
    ?: candidates.firstOrNull { it.isEmailInput() }
    ?: password?.let { p -> candidates.takeWhile { it !== p }.lastOrNull { !it.isPasswordInput() && it.looksLikeUsername() } }
    ?: password?.let { p -> candidates.takeWhile { it !== p }.lastOrNull { !it.isPasswordInput() && it.isPlainText() } }
  if (password == null && username == null) return null
  // a lone username-looking field (e.g. a search box hinted as email) is
  // only worth filling if it is explicitly hinted
  if (password == null && username != null && !username.hinted(USERNAME_HINTS)) return null
  return LoginForm(username?.id, password?.id)
}

// Android's View.AUTOFILL_HINT_* and HTML autocomplete tokens
private val PASSWORD_HINTS = setOf("password", "current-password", "new-password")
private val USERNAME_HINTS = setOf("username", "emailaddress", "email")

private const val TYPE_MASK_CLASS = 0x0f
private const val TYPE_MASK_VARIATION = 0xff0
private const val TYPE_CLASS_TEXT = 1
private const val TYPE_CLASS_NUMBER = 2
private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
private const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xd0
private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0
private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x10

private fun FormField<*>.hints(): Set<String> =
  (autofillHints + (htmlAttributes["autocomplete"]?.split(' ') ?: emptyList())).map { it.lowercase() }.toSet()

private fun FormField<*>.hinted(set: Set<String>) = hints().any { it in set }

private fun FormField<*>.htmlType() = htmlAttributes["type"]?.lowercase()

private fun FormField<*>.isHidden() = htmlType() == "hidden"

private fun FormField<*>.isPasswordInput(): Boolean {
  if (htmlType() == "password") return true
  val cls = inputType and TYPE_MASK_CLASS
  val variation = inputType and TYPE_MASK_VARIATION
  return (cls == TYPE_CLASS_TEXT && variation in setOf(TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
    (cls == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD)
}

private fun FormField<*>.isEmailInput(): Boolean {
  if (htmlType() == "email") return true
  val variation = inputType and TYPE_MASK_VARIATION
  return (inputType and TYPE_MASK_CLASS) == TYPE_CLASS_TEXT &&
    (variation == TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
}

private fun FormField<*>.isPlainText(): Boolean {
  val type = htmlType()
  return if (htmlTag != null) type == null || type == "text" || type == "email" || type == "tel"
  else (inputType and TYPE_MASK_CLASS) == TYPE_CLASS_TEXT || inputType == 0
}

private val USERNAME_WORDS = Regex("(?i)user|login|e-?mail|account|identifier|benutzer|nutzer")

private fun FormField<*>.looksLikeUsername(): Boolean =
  isPlainText() && listOfNotNull(idEntry, hint, htmlAttributes["name"], htmlAttributes["id"]).any { USERNAME_WORDS.containsMatchIn(it) }
