package com.caio.navi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Procura contatos da agenda pelo nome falado. */
object Contacts {
    data class Contact(val name: String, val phone: String)

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contatos cujo nome bate com o que foi dito (melhores primeiro). */
    fun findPhones(c: Context, spoken: String): List<Contact> {
        if (!hasPermission(c)) return emptyList()
        val wanted = Brain.normalize(spoken)
        if (wanted.isEmpty()) return emptyList()
        val all = mutableListOf<Contact>()
        try {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    val num = cur.getString(1) ?: continue
                    all += Contact(name, num)
                }
            }
        } catch (_: Exception) {
        }
        return rank(all, wanted) { it.name }.distinctBy { it.name to digits(it.phone).takeLast(8) }
    }

    fun findEmails(c: Context, spoken: String): List<Pair<String, String>> {
        if (!hasPermission(c)) return emptyList()
        val wanted = Brain.normalize(spoken)
        val all = mutableListOf<Pair<String, String>>()
        try {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    val mail = cur.getString(1) ?: continue
                    all += name to mail
                }
            }
        } catch (_: Exception) {
        }
        return rank(all, wanted) { it.first }
    }

    private fun <T> rank(all: List<T>, wanted: String, name: (T) -> String): List<T> {
        fun score(t: T): Int {
            val n = Brain.normalize(name(t))
            return when {
                n == wanted -> 100
                n.startsWith("$wanted ") -> 80
                n.split(" ").contains(wanted) -> 60
                n.contains(wanted) -> 40
                wanted.split(" ").all { w -> n.contains(w) } -> 30
                else -> 0
            }
        }
        return all.map { it to score(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    fun digits(s: String) = s.filter { it.isDigit() }

    /** Número no formato do WhatsApp (55 + DDD + número). */
    fun toWhatsApp(phone: String): String {
        val plus = phone.trim().startsWith("+")
        var d = digits(phone)
        if (plus) return d
        if (d.startsWith("00")) return d.drop(2)
        if (d.startsWith("0")) d = d.drop(1)          // 0 + operadora + DDD...
        if (d.length == 12 || d.length == 13) {
            if (d.startsWith("55")) return d           // já tem o código do Brasil
            d = d.drop(2)                               // código de operadora (ex.: 15, 21, 41)
        }
        return if (d.length == 10 || d.length == 11) "55$d" else d
    }
}
