package com.example.actions.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.actions.ConfirmationSpec

class MakePhoneCallAction(private val context: Context) : Action {

    override val name: String = "make_phone_call"
    override val description: String = "Places a phone call (direct_call=true, needs user confirmation) or opens the dialer with the number filled in."
    override val isSensitive: Boolean = true

    override val parameters: Map<String, ParamDefinition> = mapOf(
        "phone_number" to ParamDefinition("string", "Phone number or contact name", required = true),
        "direct_call" to ParamDefinition("boolean", "If true, places the call after the user confirms; otherwise only opens the dialer", required = false)
    )

    /** Opening the dialer places no call (the user still presses call), so only direct calls confirm. */
    override fun confirmationFor(args: Map<String, Any?>): ConfirmationSpec? {
        if (args["direct_call"] as? Boolean != true) return null
        val number = (args["phone_number"] as? String)?.trim().orEmpty()
        return ConfirmationSpec("Phone Call", "Place a direct phone call to $number?")
    }

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val phoneNumber = (args["phone_number"] as? String)?.trim()
            ?: return ActionResult("No phone number specified.", isError = true)
        val direct = args["direct_call"] as? Boolean ?: false
        val uri = Uri.parse("tel:${Uri.encode(phoneNumber)}")

        if (direct) {
            try {
                context.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return ActionResult("Call placed to $phoneNumber.")
            } catch (e: SecurityException) {
                // CALL_PHONE not granted: fall through to the dialer.
            }
        }
        context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult("Dialer opened for $phoneNumber.")
    }
}

class SendSmsAction(private val context: Context) : Action {

    override val name: String = "send_sms"
    override val description: String = "Prepares an SMS to a phone number in the messaging app after the user confirms. The user still presses send there."
    override val isSensitive: Boolean = true

    override val parameters: Map<String, ParamDefinition> = mapOf(
        "recipient" to ParamDefinition("string", "Phone number or recipient name", required = true),
        "message" to ParamDefinition("string", "Body of the text message", required = true)
    )

    override fun confirmationFor(args: Map<String, Any?>): ConfirmationSpec {
        val recipient = (args["recipient"] as? String)?.trim().orEmpty()
        val message = (args["message"] as? String)?.trim().orEmpty()
        return ConfirmationSpec("Send SMS", "Send to $recipient: \"$message\"")
    }

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val recipient = (args["recipient"] as? String)?.trim() ?: return ActionResult("Recipient missing.", isError = true)
        val message = (args["message"] as? String)?.trim() ?: return ActionResult("Message body missing.", isError = true)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(recipient)}")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult("SMS to $recipient is ready in your messaging app; press send there.")
    }
}

class SendMessagingAppAction(private val context: Context) : Action {

    override val name: String = "compose_message"
    override val description: String = "Prepares and opens message composition in WhatsApp, Telegram, or Email via deep links."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "platform" to ParamDefinition("string", "'whatsapp', 'telegram', or 'email'", required = true, enumValues = listOf("whatsapp", "telegram", "email")),
        "recipient" to ParamDefinition("string", "Phone number with country code, username, or email address", required = false),
        "text" to ParamDefinition("string", "Text or body to compose", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val platform = (args["platform"] as? String)?.lowercase() ?: "whatsapp"
        val recipient = (args["recipient"] as? String)?.trim() ?: ""
        val text = (args["text"] as? String)?.trim() ?: ""

        val intent = when (platform) {
            "whatsapp" -> {
                val cleanNumber = recipient.replace("+", "").replace(" ", "").replace("-", "")
                val uri = if (cleanNumber.isNotEmpty()) {
                    Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(text)}")
                } else {
                    Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(text)}")
                }
                Intent(Intent.ACTION_VIEW, uri).apply {
                    `package` = "com.whatsapp"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "telegram" -> {
                val uri = if (recipient.isNotEmpty()) {
                    Uri.parse("https://t.me/${recipient.removePrefix("@")}?text=${Uri.encode(text)}")
                } else {
                    Uri.parse("tg://msg?text=${Uri.encode(text)}")
                }
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "email" -> {
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$recipient")
                    putExtra(Intent.EXTRA_SUBJECT, "Message via JARVIS")
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            else -> return ActionResult("Unsupported platform $platform", isError = true)
        }

        return try {
            context.startActivity(intent)
            ActionResult("Opening $platform with your message, Sir.")
        } catch (e: Exception) {
            // If target app not installed, fallback to generic share
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Send message via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ActionResult("$platform app not found. Opened share options, Sir.")
        }
    }
}

class ContactsLookupAction(private val context: Context) : Action {

    override val name: String = "lookup_contact"
    override val description: String = "Searches device contacts for phone numbers or details."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "query" to ParamDefinition("string", "Name or partial name of contact", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val query = (args["query"] as? String)?.trim() ?: return ActionResult("No contact name given, Sir.", isError = true)

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        val results = mutableListOf<String>()
        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && results.size < 5) {
                    val name = it.getString(nameIndex)
                    val number = it.getString(numberIndex)
                    results.add("$name: $number")
                }
            }
        } catch (e: Exception) {
            return ActionResult("Contacts access denied or unavailable: ${e.message}", isError = true)
        }

        return if (results.isNotEmpty()) {
            ActionResult(
                spokenResult = "Found ${results.size} contact(s): ${results.joinToString(", ")}.",
                cardData = mapOf("contacts" to results)
            )
        } else {
            ActionResult("No contacts found matching '$query', Sir.")
        }
    }
}
