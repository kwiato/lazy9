package io.github.kwiato.lazy9.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** A starred contact that can be found with the T9 keypad and called. */
@Immutable
class ContactEntry(
    val id: Long,
    val name: String,
    val number: String,
    val photo: ImageBitmap?,
    val t9: T9Name,
)

/**
 * Reads the starred ("favourite") contacts that have a phone number. Nothing
 * read here is stored or leaves the device; it only lives in memory.
 */
class ContactRepository(private val context: Context) {

    fun canRead(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canCall(): Boolean =
        context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** One entry per contact, using its default number when it has several. */
    fun favorites(): List<ContactEntry> {
        if (!canRead()) return emptyList()
        val out = LinkedHashMap<Long, ContactEntry>()
        try {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI),
                "${Phone.STARRED} = 1",
                null,
                "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id in out) continue
                    val name = c.getString(1)?.trim().orEmpty()
                    val number = c.getString(2)?.trim().orEmpty()
                    if (name.isEmpty() || number.isEmpty()) continue
                    out[id] = ContactEntry(id, name, number, photo(c.getString(3)), T9.encode(name))
                }
            }
        } catch (e: Exception) {
            // permission revoked mid-query, or no contacts provider on this device
        }
        return out.values.toList()
    }

    /**
     * What other apps offer for this contact. Messengers (Signal, WhatsApp,
     * Telegram, …) add their own rows to a contact — "Message +48…", "Voice
     * call…" — each opened by viewing that row. Only rows some installed app
     * actually handles are returned.
     */
    fun actions(contact: ContactEntry): List<ContactAction> {
        if (!canRead()) return emptyList()
        val out = ArrayList<ContactAction>()
        val seen = HashSet<String>()
        val pm = context.packageManager
        try {
            context.contentResolver.query(
                Data.CONTENT_URI,
                arrayOf(Data._ID, Data.MIMETYPE, Data.DATA3),
                "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} LIKE 'vnd.android.cursor.item/vnd.%'",
                arrayOf(contact.id.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: continue
                    if (!seen.add(mime)) continue
                    val action = ContactAction(c.getLong(0), mime, "", "")
                    val handler = pm.resolveActivity(action.intent(), 0)?.activityInfo ?: continue
                    val label = c.getString(2)?.trim().orEmpty()
                        .ifEmpty { handler.applicationInfo.loadLabel(pm).toString() }
                    out += ContactAction(action.dataId, mime, label, handler.packageName)
                }
            }
        } catch (e: Exception) {
            // same as favorites(): treat as "nothing on offer"
        }
        return out.sortedBy { it.pkg }
    }

    private fun photo(uri: String?): ImageBitmap? {
        if (uri == null) return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it)?.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Something another app can do with a contact; see [ContactRepository.actions]. */
class ContactAction(val dataId: Long, val mime: String, val label: String, val pkg: String) {
    fun intent(): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(ContentUris.withAppendedId(Data.CONTENT_URI, dataId), mime)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** One T9 result: an app to open or a favourite contact to call. */
sealed interface Hit {
    class App(val app: AppEntry) : Hit
    class Contact(val contact: ContactEntry) : Hit
}
