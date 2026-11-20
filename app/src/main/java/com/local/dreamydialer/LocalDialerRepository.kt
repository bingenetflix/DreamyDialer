package com.local.dreamydialer

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat

data class DeviceContact(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val label: String,
    val photoUri: String?
)

data class RecentCallEntry(
    val id: Long,
    val name: String,
    val number: String,
    val callType: Int,
    val relativeTime: String,
    val durationSec: Long
)

class LocalDialerRepository(private val context: Context) {

    fun loadContacts(): List<DeviceContact> {
        val contacts = mutableListOf<DeviceContact>()
        val seenKeys = mutableSetOf<String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                val photoCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)?.trim().orEmpty()
                    val rawNumber = cursor.getString(numCol)?.trim().orEmpty()
                    if (name.isEmpty() || rawNumber.isEmpty()) continue

                    val normalized = rawNumber.replace(Regex("[^0-9+]"), "")
                    val dedupKey = "$id:$normalized"
                    if (!seenKeys.add(dedupKey)) continue

                    val phoneType = cursor.getInt(typeCol)
                    val customLabel = cursor.getString(labelCol)
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources,
                        phoneType,
                        customLabel
                    ).toString()

                    val photoUri = cursor.getString(photoCol)

                    contacts.add(
                        DeviceContact(
                            id = id,
                            name = name,
                            phoneNumber = rawNumber,
                            label = label.ifBlank { "Mobile" },
                            photoUri = photoUri
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        return contacts
    }

    fun loadContactPhoto(contactId: Long, photoUri: String?): ImageBitmap? {
        try {
            if (!photoUri.isNullOrBlank()) {
                context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()?.let { return it }
                }
            }
            val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            ContactsContract.Contacts.openContactPhotoInputStream(
                context.contentResolver,
                contactUri,
                true
            )?.use { stream ->
                return BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun loadRecentCalls(): List<RecentCallEntry> {
        val recents = mutableListOf<RecentCallEntry>()
        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val numCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                var count = 0
                while (cursor.moveToNext() && count < 80) {
                    val id = cursor.getLong(idCol)
                    val cachedName = cursor.getString(nameCol)?.trim().orEmpty()
                    val number = cursor.getString(numCol)?.trim().orEmpty().ifEmpty { "Unknown" }
                    val type = cursor.getInt(typeCol)
                    val dateMs = cursor.getLong(dateCol)
                    val duration = cursor.getLong(durCol)

                    val relTime = DateUtils.getRelativeTimeSpanString(
                        dateMs,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    ).toString()

                    recents.add(
                        RecentCallEntry(
                            id = id,
                            name = cachedName.ifEmpty { number },
                            number = number,
                            callType = type,
                            relativeTime = relTime,
                            durationSec = duration
                        )
                    )
                    count++
                }
            }
        } catch (_: Exception) {
        }

        return recents
    }

    fun saveNewContact(name: String, phone: String): Boolean {
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    )
                    .build()
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun placeDirectCall(phoneNumber: String) {
        val cleaned = phoneNumber.trim()
        if (cleaned.isEmpty()) return
        val telUri = Uri.parse("tel:${Uri.encode(cleaned)}")
        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = Intent(if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL, telUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
