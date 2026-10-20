package io.crashbox.passage.autofill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutofillTest {
  @Test
  fun username() {
    assertEquals("jakob", usernameOf("pw\nuser: jakob\n"))
    assertEquals("me@example.com", usernameOf("pw\nurl: x\nEmail:me@example.com\nlogin: other\n"))
    assertEquals("j", usernameOf("pw\n  Username :  j  \n"))
    assertNull(usernameOf("user: not the password line"))
    assertNull(usernameOf("pw\nuser:\nnotes"))
    assertNull(usernameOf("pw\nusers: x\nmy user: y"))
  }

  @Test
  fun keywords() {
    assertEquals("github", keyword(Target.Web("github.com")))
    assertEquals("github", keyword(Target.Web("gist.github.com")))
    assertEquals("bbc", keyword(Target.Web("www.bbc.co.uk")))
    assertEquals("co", keyword(Target.Web("co.uk"))) // not a two-level suffix by itself
    assertNull(keyword(Target.Web("localhost")))
    assertNull(keyword(Target.Web("192.168.1.1")))
    assertEquals("github", keyword(Target.App("com.github.android")))
    assertEquals("whatsapp", keyword(Target.App("com.whatsapp")))
    assertEquals("google", keyword(Target.App("com.google.android.youtube")))
  }

  @Test
  fun suggestions() {
    val entries = listOf("email", "web/GitHub", "work/github-enterprise", "web/example.com", "bank")
    assertEquals(listOf("web/GitHub", "work/github-enterprise"), suggestions(entries, Target.Web("gist.github.com")))
    assertEquals(listOf("web/example.com"), suggestions(entries, Target.Web("example.com")))
    assertEquals(emptyList(), suggestions(entries, Target.Web("localhost")))
  }

  private val text = 0x01
  private val email = 0x21
  private val password = 0x81
  private val pin = 0x12

  @Test
  fun appLoginForm() {
    val form = findLoginFields(
      listOf(
        FormField("search", inputType = text, idEntry = "search"),
        FormField("user", inputType = text, idEntry = "login_name"),
        FormField("pw", inputType = password),
        FormField("button", isText = false),
      )
    )!!
    assertEquals("user", form.username)
    assertEquals("pw", form.password)
  }

  @Test
  fun hintsWin() {
    val form = findLoginFields(
      listOf(
        FormField("a", inputType = text),
        FormField("pw", autofillHints = listOf("password"), inputType = text),
        FormField("u", autofillHints = listOf("username"), inputType = text),
      )
    )!!
    assertEquals("u", form.username)
    assertEquals("pw", form.password)
  }

  @Test
  fun webLoginForm() {
    val form = findLoginFields(
      listOf(
        FormField("csrf", htmlTag = "input", htmlAttributes = mapOf("type" to "hidden")),
        FormField("q", htmlTag = "input", htmlAttributes = mapOf("type" to "search", "name" to "q")),
        FormField("email", htmlTag = "input", htmlAttributes = mapOf("type" to "email", "name" to "email")),
        FormField("pw", htmlTag = "input", htmlAttributes = mapOf("type" to "password", "autocomplete" to "current-password")),
      )
    )!!
    assertEquals("email", form.username)
    assertEquals("pw", form.password)
  }

  @Test
  fun usernameIsTheTextFieldBeforeThePassword() {
    val form = findLoginFields(
      listOf(
        FormField("x", htmlTag = "input", htmlAttributes = mapOf("type" to "text", "name" to "q")),
        FormField("y", htmlTag = "input", htmlAttributes = mapOf("type" to "text", "name" to "id")),
        FormField("pw", htmlTag = "input", htmlAttributes = mapOf("type" to "password")),
      )
    )!!
    assertEquals("y", form.username)
  }

  @Test
  fun passwordOnlyAndPins() {
    assertEquals("pw", findLoginFields(listOf(FormField("pw", inputType = password)))!!.password)
    assertEquals("pin", findLoginFields(listOf(FormField("pin", inputType = pin)))!!.password)
  }

  @Test
  fun usernameOnlyStepNeedsAHint() {
    // first step of a two-step login
    val hinted = findLoginFields(listOf(FormField("u", htmlTag = "input", htmlAttributes = mapOf("autocomplete" to "username"))))!!
    assertEquals("u", hinted.username)
    assertNull(hinted.password)
    // a lone email field, e.g. a newsletter sign-up
    assertNull(findLoginFields(listOf(FormField("e", inputType = email))))
  }

  @Test
  fun noLoginForm() {
    assertNull(findLoginFields(listOf(FormField("a", inputType = text), FormField("b", isText = false))))
  }
}
